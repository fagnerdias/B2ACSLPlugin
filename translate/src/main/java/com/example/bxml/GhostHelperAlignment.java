package com.example.bxml;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Helpers SIGMA/PI/MIN/MAX, lambda e UNION/INTER usados no {@code ghost_operations.ci} na forma do
 * rules: a chamada é {@code dummy_<nome real>(...)} e o {@code dummy_ghost} declara só a assinatura,
 * que no {@code merged_code.c} (sem o prefixo {@code dummy_}) é a função do {@code .acsl}.
 *
 * <p>O ghost é gerado antes do {@code .acsl} e o seu contexto tem registros próprios (outra
 * numeração), por isso o texto ghost trazia cópias locais {@code ghost_sigma_funcNN} com corpo e
 * axiomas (RulesOfTheSeas attackplayer), e uma cópia sem uso quando a operação ghost era descartada
 * (lunh). Este passo roda depois da geração dos {@code .acsl}: cada helper {@code ghost_X} é
 * comparado, pela definição normalizada, aos helpers reais; os iguais passam a chamar o real.
 */
public final class GhostHelperAlignment {

    private static final Pattern GHOST_BLOCK =
            Pattern.compile("/\\*@\\n(axiomatic\\s+ghost_\\w+\\s*\\{\\n)(.*?)\\n\\}\\n\\*/\\n*", Pattern.DOTALL);
    private static final Pattern REAL_BLOCK =
            Pattern.compile(
                    "(?m)^axiomatic\\s+\\w*(?:lambda_functions|generalized_quantifier_functions"
                            + "|generalized_union_inter_functions)\\s*\\{\\n(.*?)\\n\\}",
                    Pattern.DOTALL);
    private static final Pattern UNIT_START =
            Pattern.compile("(?m)^\\s*(?:logic\\s+.+?|predicate)\\s+(\\w+)\\s*\\(");
    private static final String DUMMY_GHOST_END = "    }\n*/\n";

    private GhostHelperAlignment() {}

    /** Um helper: nome, texto completo (declaração + lemas/axiomas) e a assinatura. */
    private record Unit(String name, String text, String signature) {}

    public static void align(Path ghostCi, Path acslDir) throws IOException {
        if (ghostCi == null || !Files.isRegularFile(ghostCi) || acslDir == null) return;
        String ghost = Files.readString(ghostCi, StandardCharsets.UTF_8);
        if (!ghost.contains("axiomatic ghost_")) return;

        // definições iguais com nomes diferentes (RulesOfTheSeas sigma_func01/02: mesmo corpo, só o
        // domínio do SIGMA muda e vira parâmetro) ficam em ordem; cada helper ghost pega o próximo
        Map<String, List<Unit>> realByKey = new LinkedHashMap<>();
        Map<String, Integer> nextByKey = new LinkedHashMap<>();
        try (Stream<Path> files = Files.list(acslDir)) {
            for (Path f : files.filter(p -> p.toString().endsWith(".acsl")).sorted().toList()) {
                Matcher b = REAL_BLOCK.matcher(Files.readString(f, StandardCharsets.UTF_8));
                while (b.find()) {
                    for (Unit u : units(b.group(1))) {
                        realByKey.computeIfAbsent(key(u, false), k -> new ArrayList<>()).add(u);
                    }
                }
            }
        }

        // nome ghost -> nome real, e as assinaturas dummy_ a declarar
        Map<String, String> rename = new LinkedHashMap<>();
        List<String> signatures = new ArrayList<>();
        StringBuilder out = new StringBuilder();
        Matcher m = GHOST_BLOCK.matcher(ghost);
        int prev = 0;
        String outsideBlocks = GHOST_BLOCK.matcher(ghost).replaceAll("");
        m = GHOST_BLOCK.matcher(ghost);
        while (m.find()) {
            out.append(ghost, prev, m.start());
            StringBuilder kept = new StringBuilder();
            for (Unit u : units(m.group(2))) {
                Unit real = null;
                List<Unit> candidates = realByKey.get(key(u, true));
                if (candidates != null) {
                    int k = nextByKey.merge(key(u, true), 1, Integer::sum) - 1;
                    real = candidates.get(Math.min(k, candidates.size() - 1));
                }
                boolean called = Pattern.compile("(?<!\\w)" + Pattern.quote(u.name()) + "\\s*\\(")
                        .matcher(outsideBlocks).find();
                if (real != null) {
                    rename.put(u.name(), real.name());
                    // a assinatura entra sempre; a poda tira a que nada chama
                    String sig = dummySignature(real);
                    if (sig != null && !signatures.contains(sig)) signatures.add(sig);
                } else if (called) {
                    kept.append(u.text()).append("\n\n");
                }
                // sem par real e sem chamada: cópia morta (lunh), sai
            }
            if (kept.length() > 0) {
                out.append("/*@\n").append(m.group(1)).append("  ").append(kept.toString().strip()).append("\n}\n*/\n\n");
            }
            prev = m.end();
        }
        out.append(ghost.substring(prev));
        String result = renameCalls(out.toString(), rename);
        result = insertSignatures(result, signatures);
        if (!result.equals(ghost)) {
            Files.writeString(ghostCi, result, StandardCharsets.UTF_8);
            GhostOperationsCiGenerator.pruneUnusedDummyDeclarations(ghostCi);
        }
    }

    /** Divide o corpo de um bloco em helpers: de cada {@code logic/predicate f(} até o próximo. */
    private static List<Unit> units(String body) {
        List<Unit> out = new ArrayList<>();
        Matcher u = UNIT_START.matcher(body);
        List<int[]> starts = new ArrayList<>();
        List<String> names = new ArrayList<>();
        while (u.find()) {
            starts.add(new int[] {u.start(), u.end()});
            names.add(u.group(1));
        }
        for (int i = 0; i < starts.size(); i++) {
            int from = starts.get(i)[0];
            int to = i + 1 < starts.size() ? starts.get(i + 1)[0] : body.length();
            String text = body.substring(from, to).strip();
            out.add(new Unit(names.get(i), text, declarationHead(body, starts.get(i)[1])
                    .map(h -> body.substring(from, h).strip()).orElse(null)));
        }
        return out;
    }

    /** Fim ({@code )} correspondente) da lista de parâmetros que começa em {@code openEnd}. */
    private static java.util.Optional<Integer> declarationHead(String s, int openEnd) {
        int depth = 0;
        for (int i = openEnd; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '(') depth++;
            else if (c == ')') {
                if (depth == 0) return java.util.Optional.of(i + 1);
                depth--;
            }
        }
        return java.util.Optional.empty();
    }

    /**
     * Definição normalizada: sem comentários, sem os prefixos {@code ghost_}/{@code dummy_}, tipos D*
     * como os reais, o nome do próprio helper como {@code @F}, sem casts {@code (integer)}, parênteses
     * e espaços.
     */
    private static String key(Unit u, boolean ghost) {
        String s = u.text();
        s = s.replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("//[^\\n]*", "");
        s = s.replaceAll("(?<!\\w)" + Pattern.quote(u.name()) + "(?=\\w*)", "@F");
        if (ghost) {
            s = s.replaceAll("(?<!\\w)ghost_(?=\\w)", "");
            s = s.replaceAll("(?<!\\w)dummy_(?=\\w)", "");
            s = s.replace("DRelation<", "Relation<").replace("DSet<", "Set<").replace("DTuple<", "Tuple<");
        }
        s = s.replace("Function<", "Relation<");
        s = s.replace("(integer)", "");
        return s.replaceAll("[()\\s]", "");
    }

    /** {@code logic T dummy_f(DTipo p, ...);} a partir da declaração real. */
    private static String dummySignature(Unit real) {
        String head = real.signature();
        if (head == null) return null;
        head = head.replaceFirst("(?<!\\w)" + Pattern.quote(real.name()) + "\\s*\\(", "dummy_" + real.name() + "(");
        return GhostNamespacePrefixer.prefixFunctionTypesForGhost(head).replaceAll("\\s+", " ") + ";";
    }

    /** Duas fases (marcador) para não trocar 01↔02 ao renomear. */
    private static String renameCalls(String text, Map<String, String> rename) {
        String s = text;
        int i = 0;
        Map<String, String> marks = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : rename.entrySet()) {
            String mark = "@@GHOSTHELPER" + (i++) + "@@";
            s = s.replaceAll("(?<!\\w)" + Pattern.quote(e.getKey()) + "(?=\\s*\\()", Matcher.quoteReplacement(mark));
            marks.put(mark, "dummy_" + e.getValue());
        }
        for (Map.Entry<String, String> e : marks.entrySet()) {
            s = s.replace(e.getKey(), e.getValue());
        }
        return s;
    }

    /** Acrescenta as assinaturas no fim do primeiro {@code axiomatic dummy_ghost}. */
    private static String insertSignatures(String text, List<String> signatures) {
        List<String> sigs = signatures.stream().filter(java.util.Objects::nonNull).toList();
        if (sigs.isEmpty()) return text;
        int a = text.indexOf("axiomatic dummy_ghost {");
        int f = a < 0 ? -1 : text.indexOf(DUMMY_GHOST_END, a);
        if (f < 0) return text;
        StringBuilder b = new StringBuilder();
        for (String sig : sigs) b.append("        ").append(sig).append("\n\n");
        return text.substring(0, f) + b + text.substring(f);
    }
}
