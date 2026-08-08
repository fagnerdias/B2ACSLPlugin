package com.example;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Filtragem de lemas da lib ACSL para {@code merged_code.c} (só anexa {@code admit lemma} cujas
 * chamadas estejam no fecho de includes deduzido) e correção de ordem de declaração de
 * {@code sequence_list_to_function}. Extraído de {@code B2ACSLPipeline} (WMC=607) por extract-class
 * puro: nenhuma linha de lógica mudou, só o arquivo em que vive.
 */
final class LemmaLibraryFilter {

    private LemmaLibraryFilter() {}

    /** Primeira utilização da lib {@code list_to_function(} (não {@code dummy_list_to_function}). */
    private static final Pattern LIST_TO_FUNCTION_LIB_CALL =
            Pattern.compile("(?<![A-Za-z0-9_])list_to_function\\s*\\(");

    /** Lemas admitidos da B2ACSLLib (anexados ao fim do merge Frama-C). */
    private static final String ACSL_LIB_LEMMAS_RESOURCE = B2AcslLibraryPaths.classpathResource("lemmas.acsl");

    /**
     * Passo 10–11: lê {@code ACSL_Lib/lemmas.acsl}; passo 11 remove cada {@code admit lemma} que invoque
     * (como chamada {@code nome(}) algum símbolo de biblioteca mapeado em {@link AcslLibIncludes} que não
     * esteja no fecho de includes deduzido do texto dos ficheiros {@code .acsl} importados e do
     * {@code ghost_operations.ci}. Passo 10 anexa o resultado ao fim de {@code merged_code.c} num
     * comentário ACSL.
     *
     * @param allowedLibSymbols símbolos permitidos (ex.: {@code belongs}, {@code ran}); vazio → não anexa
     */
    static void appendLemmasAcslLibToMergedEnd(Path mergedC, Set<String> allowedLibSymbols)
            throws IOException {
        if (allowedLibSymbols == null || allowedLibSymbols.isEmpty()) {
            return;
        }
        Optional<String> lemmas = readAcslLibLemmasText();
        if (lemmas.isEmpty() || lemmas.get().isBlank()) {
            return;
        }
        String filtered = filterLemmasByAllowedLibSymbols(lemmas.get(), allowedLibSymbols);
        if (filtered.isBlank()) {
            return;
        }
        // filtered already contains individually wrapped /*@ axiomatic X { ... } */ blocks
        String existing = Files.readString(mergedC, StandardCharsets.UTF_8);
        String sep = existing.endsWith("\n") ? "" : "\n";
        Files.writeString(mergedC, existing + sep + filtered, StandardCharsets.UTF_8);
    }

    /**
     * Chamadas {@code identificador(} típicas da ACSL_Lib (identificador ASCII minúsculo, não precedido
     * de {@code \} nem de carácter alfanumérico).
     */
    private static final Pattern LEMMA_LIB_STYLE_CALL =
            Pattern.compile("(?<![A-Za-z0-9_\\\\])([a-z][a-z0-9_]*)\\s*\\(");

    private static final Pattern LEMMA_AXIOMATIC_HEADER =
            Pattern.compile("(?m)^\\s*axiomatic\\s+(\\w+)\\s*\\{");

    /**
     * Mantém apenas lemas cujo corpo não contém chamadas a símbolos de lib fora de {@code allowed}.
     *
     * <p>Processa cada bloco {@code axiomatic} do ficheiro de lemas individualmente, emitindo
     * cada um como um bloco {@code /*@ axiomatic X \{ … \} *\/} separado. Isso evita que o
     * conteúdo estrutural (fechamentos {@code \}} e cabeçalhos {@code axiomatic Name \{}) dos
     * blocos intermédios seja copiado repetidamente durante a monomorphização genérica.
     */
    private static String filterLemmasByAllowedLibSymbols(String lemmasFileText, Set<String> allowed) {
        Matcher headerMatcher = LEMMA_AXIOMATIC_HEADER.matcher(lemmasFileText);
        StringBuilder result = new StringBuilder();

        while (headerMatcher.find()) {
            String blockName = headerMatcher.group(1);
            int bodyStart = headerMatcher.end(); // posição após '{'

            // Encontra o '}' correspondente (profundidade de chaves)
            int depth = 1;
            int pos = bodyStart;
            while (pos < lemmasFileText.length() && depth > 0) {
                char c = lemmasFileText.charAt(pos);
                if (c == '{') depth++;
                else if (c == '}') depth--;
                pos++;
            }
            if (depth != 0) continue; // chave sem par — ignora

            String blockBody = lemmasFileText.substring(bodyStart, pos - 1);

            // Filtra os lemas deste bloco individualmente
            String[] chunks = blockBody.split("(?m)(?=^\\s*admit\\s+lemma\\s+)");
            List<String> kept = new ArrayList<>();
            for (String chunk : chunks) {
                String t = chunk.trim();
                if (t.isEmpty() || !t.startsWith("admit")) continue;
                if (!lemmaBodyUsesDisallowedLibCall(t, allowed)) {
                    kept.add(t);
                }
            }
            if (kept.isEmpty()) continue;

            // Cada bloco axiomatic é emitido como um /*@ ... */ independente
            result.append("/*@\n")
                    .append("axiomatic ").append(blockName).append(" {\n\n    ")
                    .append(String.join("\n\n    ", kept))
                    .append("\n\n}\n */\n");
        }

        return result.toString();
    }

    /**
     * Conjuntos lógicos globais da ACSL_Lib ({@code set_functions/variables.acsl}) referenciados
     * como identificador NU (sem parênteses), ex.: {@code inclusion(s, BOOL)} — {@link
     * #LEMMA_LIB_STYLE_CALL} só apanha dependências no estilo chamada ({@code nome(}), então um
     * lema cuja única chamada permitida (ex.: {@code inclusion}) esconde uma dependência de {@code
     * BOOL} escapava ao filtro por completo, sobrevivendo mesmo em projetos (ex.:
     * BirthdayRegister) que nunca usam boolean em lado nenhum — "BOOL" nunca fica {@code allowed},
     * mas o lema não era rejeitado, dando "unbound logic variable BOOL" no Frama-C.
     */
    private static final List<String> BARE_GLOBAL_LOGIC_SET_NAMES = List.of("BOOL", "NAT1", "NAT", "INT");

    private static boolean lemmaBodyUsesDisallowedLibCall(String admitLemmaChunk, Set<String> allowed) {
        Matcher m = LEMMA_LIB_STYLE_CALL.matcher(admitLemmaChunk);
        while (m.find()) {
            String name = m.group(1);
            if (allowed.contains(name)) {
                continue;
            }
            return true;
        }
        for (String bareName : BARE_GLOBAL_LOGIC_SET_NAMES) {
            if (allowed.contains(bareName)) {
                continue;
            }
            if (Pattern.compile("\\b" + bareName + "\\b").matcher(admitLemmaChunk).find()) {
                return true;
            }
        }
        return false;
    }

    private static Optional<String> readAcslLibLemmasText() throws IOException {
        try (InputStream in = B2ACSLPipeline.class.getResourceAsStream(ACSL_LIB_LEMMAS_RESOURCE)) {
            if (in != null) {
                return Optional.of(new String(in.readAllBytes(), StandardCharsets.UTF_8));
            }
        }
        Path dev = B2AcslLibraryPaths.devRoot().resolve("lemmas.acsl");
        if (Files.isRegularFile(dev)) {
            return Optional.of(Files.readString(dev, StandardCharsets.UTF_8));
        }
        return Optional.empty();
    }

    /**
     * Após monomorfização / renomeações, o Frama-C pode deixar
     * {@code axiomatic sequence_list_to_function…} depois de blocos (ex.
     * {@code sequence_utils_axioms}) que já chamam {@code list_to_function(}. Este passo move a
     * declaração (e {@code sequence_list_to_function_axioms…}) para imediatamente antes do primeiro
     * {@code list_to_function(} real, ou após {@code new_types} se não houver uso.
     */
    static void ensureSequenceListToFunctionDeclBeforeFirstUse(Path mergedC) throws IOException {
        if (!Files.isRegularFile(mergedC)) {
            return;
        }
        String content = Files.readString(mergedC, StandardCharsets.UTF_8);
        String updated = ensureSequenceListToFunctionDeclBeforeFirstUseInString(content);
        if (!updated.equals(content)) {
            Files.writeString(mergedC, updated, StandardCharsets.UTF_8);
        }
    }

    private static String ensureSequenceListToFunctionDeclBeforeFirstUseInString(String content) {
        List<AcsCommentSpan> spans = AcslCommentSpanScanner.findAllAcsCommentSpans(content);
        List<AcsCommentSpan> declOnly = new ArrayList<>();
        List<AcsCommentSpan> axiomOnly = new ArrayList<>();
        for (AcsCommentSpan sp : spans) {
            if (sp.axiomaticName == null) {
                continue;
            }
            String n = sp.axiomaticName;
            if (n.startsWith("sequence_list_to_function_axioms")) {
                axiomOnly.add(sp);
            } else if (n.equals("sequence_list_to_function") || n.startsWith("sequence_list_to_function_")) {
                declOnly.add(sp);
            }
        }
        List<AcsCommentSpan> toMove = new ArrayList<>(declOnly.size() + axiomOnly.size());
        declOnly.sort(Comparator.comparingInt(s -> s.start));
        axiomOnly.sort(Comparator.comparingInt(s -> s.start));
        toMove.addAll(declOnly);
        toMove.addAll(axiomOnly);
        if (toMove.isEmpty()) {
            return content;
        }

        List<AcsCommentSpan> removeOrder = new ArrayList<>(toMove);
        removeOrder.sort(Comparator.comparingInt((AcsCommentSpan s) -> s.start).reversed());
        String cut = content;
        for (AcsCommentSpan sp : removeOrder) {
            cut = cut.substring(0, sp.start) + cut.substring(sp.end);
        }

        Matcher use = LIST_TO_FUNCTION_LIB_CALL.matcher(cut);
        int insertAt;
        if (use.find()) {
            int at = use.start();
            insertAt = cut.lastIndexOf("/*@", at);
            if (insertAt < 0) {
                insertAt = findInsertAfterNewTypesBlockEnd(cut);
            }
        } else {
            insertAt = findInsertAfterNewTypesBlockEnd(cut);
        }
        if (insertAt < 0) {
            insertAt = AcslCommentSpanScanner.findPreambleInsertIndex(cut);
        }
        // sequence_list_to_function_axioms usa outros símbolos da lib no seu PRÓPRIO corpo (ex.
        // length(list_to_function(l)), is_sequence(...), function_apply(...)) — mover o bloco para
        // logo antes do seu primeiro USO real, sem olhar para essas dependências, pode reintroduzir a
        // MESMA violação declare-antes-de-usar que este passo tenta resolver, só que para o símbolo
        // dependente (ex. "length") em vez de para list_to_function. Empurra o ponto de inserção para
        // depois de qualquer declaração (logic/predicate) já presente no ficheiro de que
        // list_to_function dependa transitivamente.
        insertAt = pushInsertAfterSymbolDeclDependencies(cut, insertAt, "list_to_function");

        StringBuilder sb = new StringBuilder();
        for (AcsCommentSpan sp : toMove) {
            sb.append(sp.text);
        }
        return cut.substring(0, insertAt) + sb + cut.substring(insertAt);
    }

    /** Índice imediatamente após o span {@code axiomatic new_types} (ou primeiro {@code /*@}). */
    private static int findInsertAfterNewTypesBlockEnd(String cut) {
        List<AcsCommentSpan> ss = AcslCommentSpanScanner.findAllAcsCommentSpans(cut);
        for (AcsCommentSpan sp : ss) {
            if ("new_types".equals(sp.axiomaticName)) {
                return sp.end;
            }
        }
        return AcslCommentSpanScanner.findPreambleInsertIndex(cut);
    }

    /**
     * Empurra {@code insertAt} para depois de qualquer declaração ({@code logic}/{@code predicate})
     * já presente em {@code content}, de um símbolo do qual {@code rootSymbol} dependa transitivamente
     * (ver {@link AcslLibSymbolDependencyMap#transitiveDependencySymbols(String)}) — necessário quando
     * se reposiciona {@code rootSymbol} (ex. {@code list_to_function}) para logo antes do seu próprio
     * primeiro USO: se um símbolo de que ele depende (ex. {@code length}, referenciado dentro do
     * próprio corpo dos axiomas de {@code list_to_function}) já estiver declarado MAIS À FRENTE no
     * ficheiro do que o ponto de inserção calculado, mover para lá deixaria essa dependência por
     * declarar nesse ponto — a mesma violação declare-antes-de-usar que esta função tenta resolver,
     * só que para o símbolo dependente. Devolve {@code insertAt} inalterado se nenhuma dependência
     * conhecida tiver uma declaração no ficheiro em posição igual/posterior.
     */
    private static int pushInsertAfterSymbolDeclDependencies(
            String content, int insertAt, String rootSymbol) {
        Set<String> deps =
                AcslLibSymbolDependencyMap.instance().transitiveDependencySymbols(rootSymbol);
        if (deps.isEmpty()) {
            return insertAt;
        }
        List<AcsCommentSpan> spans = AcslCommentSpanScanner.findAllAcsCommentSpans(content);
        int result = insertAt;
        for (String dep : deps) {
            if (dep.equals(rootSymbol)) {
                continue;
            }
            Pattern declPattern =
                    Pattern.compile(
                            "(?m)^.*\\b(?:logic|predicate)\\b.*?\\b"
                                    + Pattern.quote(dep)
                                    + "\\s*\\(");
            for (AcsCommentSpan sp : spans) {
                if (sp.end <= result) {
                    continue;
                }
                if (declPattern.matcher(sp.text).find()) {
                    result = Math.max(result, sp.end);
                }
            }
        }
        return result;
    }

}
