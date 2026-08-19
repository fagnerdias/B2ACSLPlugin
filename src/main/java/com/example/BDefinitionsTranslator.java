package com.example;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Traduz a cláusula B {@code DEFINITIONS} (macro-substituição textual do compilador AtelierB) para
 * um bloco ACSL {@code axiomatic} standalone, e reescreve o ACSL já gerado (a partir do BXML, que
 * já vem com as macros totalmente expandidas — ver {@link #translateAndRewrite}) para referenciar
 * os nomes simbólicos em vez dos valores literais expandidos.
 *
 * <p><b>Porque este ficheiro lê texto B cru (único no pipeline)</b>: {@code DEFINITIONS} é
 * eliminado pelo compilador AtelierB antes de gerar o {@code .bxml} — confirmado por grep direto a
 * {@code cv_struct.bxml}: nenhum vestígio de "Definition"/"LIMIT"/"in_range", só os valores já
 * substituídos ({@code 9}, {@code 0..9}). O único sítio onde a cláusula ainda existe como texto é
 * {@code bdp/expand_src/&lt;machine&gt;.mch} (a fonte ANTES da expansão de macros; {@code bdp/src/}
 * já vem expandida — confirmado por diff entre os dois). Sem um ficheiro BXML a ler, não há
 * alternativa a um parser de texto dedicado, restrito às formas de DEFINITIONS efetivamente vistas
 * (ver {@link #classify}) — qualquer forma não reconhecida é omitida silenciosamente, nunca
 * adivinhada errada.
 */
public final class BDefinitionsTranslator {

    private BDefinitionsTranslator() {}

    /** Uma entrada resolvida da cláusula, pronta para emissão ACSL e reescrita de pontos de uso. */
    sealed interface ResolvedDefinition permits IntConstant, IntervalPredicate {
        String name();
    }

    /** {@code NAME == <inteiro>} → {@code logic integer NAME = <inteiro>;}. */
    record IntConstant(String name, long value) implements ResolvedDefinition {}

    /**
     * {@code NAME(param) == (param : lo .. hi)} → {@code predicate NAME(integer param) =
     * belongs(param, interval_set(loAcsl, hiAcsl));}.
     *
     * @param loAcsl/hiAcsl forma simbólica para o CORPO do axiomatic (ex. {@code "LIMIT"}, não
     *        {@code "9"} — legibilidade, referenciando a constante irmã já declarada acima)
     * @param loLiteral/hiLiteral valor numérico TOTALMENTE resolvido (ex. {@code "9"}), usado para
     *        casar o texto ACSL já gerado a partir do BXML (que só conhece o valor expandido)
     */
    record IntervalPredicate(
            String name, String param, String loAcsl, String hiAcsl, String loLiteral, String hiLiteral)
            implements ResolvedDefinition {}

    record Translation(String axiomaticBlock, List<ResolvedDefinition> defs) {}

    private static final List<String> TOP_LEVEL_CLAUSES =
            List.of(
                    "SETS", "ABSTRACT_CONSTANTS", "CONCRETE_CONSTANTS", "PROPERTIES",
                    "ABSTRACT_VARIABLES", "CONCRETE_VARIABLES", "INVARIANT", "ASSERTIONS",
                    "INITIALISATION", "OPERATIONS", "USES", "SEES", "INCLUDES", "IMPORTS",
                    "PROMOTES", "EXTENDS", "REFINES", "LOCAL_OPERATIONS", "VALUES");

    // Âncora à linha inteira (não só \b): o comentário de cabeçalho do próprio ficheiro (ex.
    // "a DEFINITIONS clause with rewriting definitions") também contém a palavra "DEFINITIONS"
    // fora de qualquer cláusula real — sem esta âncora, o parser arrancava do sítio errado.
    // Mesma âncora aplicada às palavras-chave de TOP_LEVEL_CLAUSES (ver extractDefinitionsEntries).
    private static final Pattern DEFINITIONS_KEYWORD = Pattern.compile("(?m)^\\s*DEFINITIONS\\s*$");

    private static final Pattern ENTRY_PATTERN =
            Pattern.compile("^(\\w+)\\s*(?:\\(\\s*(\\w+)\\s*\\))?\\s*==\\s*(.+)$", Pattern.DOTALL);

    private static final Pattern INTEGER_BODY = Pattern.compile("^-?\\d+$");

    private static final Pattern INTERVAL_BODY =
            Pattern.compile("^(\\w+)\\s*:\\s*(\\S+)\\s*\\.\\.\\s*(\\S+)$");

    /**
     * Lê {@code expand_src/<machineName>.mch}, extrai e resolve a cláusula DEFINITIONS. Vazio se o
     * ficheiro não existir ou a máquina não tiver DEFINITIONS (o caso comum — a esmagadora maioria
     * dos exemplos não usa esta cláusula B).
     */
    static Optional<Translation> parse(Path bxmlDirectory, String machineName) {
        if (bxmlDirectory == null || machineName == null || machineName.isBlank()) {
            return Optional.empty();
        }
        Path src = bxmlDirectory.resolve("expand_src").resolve(machineName + ".mch");
        if (!Files.isRegularFile(src)) {
            return Optional.empty();
        }
        String text;
        try {
            text = Files.readString(src, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return Optional.empty();
        }
        List<String> rawEntries = extractDefinitionsEntries(text);
        if (rawEntries.isEmpty()) {
            return Optional.empty();
        }
        List<ResolvedDefinition> defs = new ArrayList<>();
        Map<String, Long> intValues = new LinkedHashMap<>();
        for (String entry : rawEntries) {
            ResolvedDefinition d = classify(entry, intValues);
            if (d != null) {
                defs.add(d);
                if (d instanceof IntConstant ic) {
                    intValues.put(ic.name(), ic.value());
                }
            }
        }
        if (defs.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new Translation(formatAxiomaticBlock(machineName, defs), defs));
    }

    /** Substitui, no ACSL já gerado a partir do BXML expandido, os valores literais pelos nomes simbólicos. */
    static String rewriteCallSites(String acslText, List<ResolvedDefinition> defs) {
        String out = acslText;
        // IntervalPredicate primeiro: consome o literal "hi" DENTRO de interval_set(lo,hi) antes de
        // o passo seguinte (IntConstant) tentar substituir esse mesmo literal fora de contexto.
        for (ResolvedDefinition d : defs) {
            if (d instanceof IntervalPredicate p) {
                Pattern call =
                        Pattern.compile(
                                "belongs\\(\\s*([A-Za-z_][A-Za-z0-9_]*)\\s*,\\s*interval_set\\(\\s*"
                                        + Pattern.quote(p.loLiteral())
                                        + "\\s*,\\s*"
                                        + Pattern.quote(p.hiLiteral())
                                        + "\\s*\\)\\s*\\)");
                out = call.matcher(out).replaceAll(p.name() + "($1)");
            }
        }
        for (ResolvedDefinition d : defs) {
            if (d instanceof IntConstant c) {
                Pattern eq = Pattern.compile("==\\s*" + Pattern.quote(String.valueOf(c.value())) + "\\b");
                out = eq.matcher(out).replaceAll("== " + c.name());
            }
        }
        return out;
    }

    private static String formatAxiomaticBlock(String machineName, List<ResolvedDefinition> defs) {
        StringBuilder sb = new StringBuilder();
        sb.append("axiomatic ").append(machineName).append("_definitions {\n");
        for (ResolvedDefinition d : defs) {
            if (d instanceof IntConstant c) {
                sb.append("    logic integer ").append(c.name()).append(" = ").append(c.value()).append(";\n");
            } else if (d instanceof IntervalPredicate p) {
                sb.append("    predicate ").append(p.name()).append("(integer ").append(p.param()).append(") =\n");
                sb.append("        belongs(").append(p.param()).append(", interval_set(")
                        .append(p.loAcsl()).append(", ").append(p.hiAcsl()).append("));\n");
            }
        }
        sb.append("}\n");
        return sb.toString();
    }

    private static ResolvedDefinition classify(String entry, Map<String, Long> intValues) {
        Matcher m = ENTRY_PATTERN.matcher(entry.trim());
        if (!m.matches()) {
            return null;
        }
        String name = m.group(1);
        String param = m.group(2);
        String body = m.group(3).trim();
        if (param == null) {
            if (INTEGER_BODY.matcher(body).matches()) {
                return new IntConstant(name, Long.parseLong(body));
            }
            return null;
        }
        String inner = stripOuterParens(body);
        Matcher im = INTERVAL_BODY.matcher(inner.trim());
        if (im.matches() && param.equals(im.group(1))) {
            String lo = im.group(2);
            String hi = im.group(3);
            return new IntervalPredicate(
                    name, param, lo, hi, resolveLiteral(lo, intValues), resolveLiteral(hi, intValues));
        }
        return null;
    }

    /** Nome de outra DEFINITIONS já resolvida vira o seu valor; um literal fica como está. */
    private static String resolveLiteral(String token, Map<String, Long> intValues) {
        Long v = intValues.get(token);
        return v != null ? String.valueOf(v) : token;
    }

    private static String stripOuterParens(String s) {
        if (s.length() < 2 || s.charAt(0) != '(' || s.charAt(s.length() - 1) != ')') {
            return s;
        }
        int depth = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '(') depth++;
            else if (c == ')') {
                depth--;
                if (depth == 0 && i < s.length() - 1) {
                    return s; // fecho antes do fim — não é um único invólucro externo
                }
            }
        }
        return s.substring(1, s.length() - 1);
    }

    /** Extrai as entradas (separadas por {@code ;} de topo, fora de parênteses) da cláusula DEFINITIONS. */
    private static List<String> extractDefinitionsEntries(String text) {
        Matcher start = DEFINITIONS_KEYWORD.matcher(text);
        if (!start.find()) {
            return List.of();
        }
        int from = start.end();
        int end = text.length();
        for (String kw : TOP_LEVEL_CLAUSES) {
            Matcher m = Pattern.compile("(?m)^\\s*" + kw + "\\s*$").matcher(text);
            if (m.find(from) && m.start() < end) {
                end = m.start();
            }
        }
        String clause = text.substring(from, end);
        List<String> entries = new ArrayList<>();
        int depth = 0;
        int segStart = 0;
        for (int i = 0; i < clause.length(); i++) {
            char c = clause.charAt(i);
            if (c == '(') depth++;
            else if (c == ')') depth--;
            else if (c == ';' && depth == 0) {
                String seg = clause.substring(segStart, i).trim();
                if (!seg.isEmpty()) entries.add(seg);
                segStart = i + 1;
            }
        }
        String last = clause.substring(segStart).trim();
        if (!last.isEmpty()) entries.add(last);
        return entries;
    }
}
