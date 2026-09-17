package com.example;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.example.bxml.GhostOperationsCiGenerator;

/**
 * Limpeza/transformação do {@code merged_code.c} relativa a construções ghost (blocos {@code
 * dummy_ghost}, prefixo {@code dummy_}, variáveis/operações ghost, marcadores {@code ANY_Sub}, …)
 * — extraído de {@code B2ACSLPipeline} (extract-class puro: nenhuma linha de lógica mudou, só o
 * ficheiro em que vive) para quebrar o acoplamento circular {@code B2ACSLPipeline} &lt;-&gt;
 * {@code FramaCRunner}: antes, {@code FramaCRunner.runFramaC} chamava de volta estes métodos
 * estáticos em {@code B2ACSLPipeline}, que por sua vez invoca {@code FramaCRunner} (via {@code
 * ExternalVerifierRunner}) — um ciclo real. Com estes métodos aqui, {@code FramaCRunner} deixa de
 * chamar de volta para {@code B2ACSLPipeline}, tornando a dependência unidirecional.
 */
final class GhostMergedCodeCleanup {

    private GhostMergedCodeCleanup() {}

    /**
     * Troca marcadores \exists/\forall não-escalares (ver AnySubMarkerSpec) pelo ensures da
     * função ghost gêmea correspondente, lido diretamente de ghost_operations.ci — ANTES de
     * stripDummyPrefixFromMergedCode (abaixo), para que a MESMA limpeza global (dummy_ ->
     * real, incl. DRelation<A,B> -> Relation<A,B>) trate esta cópia recém-inserida no mesmo
     * passo, sem duplicar aqui a tradução.
     */
    static void spliceAnySubMarkerSpecsFromGhostCi(Path mergedC, Path ghostCi) throws IOException {
        if (!Files.isRegularFile(ghostCi)) {
            return;
        }
        String ghostText = Files.readString(ghostCi, StandardCharsets.UTF_8);
        Matcher bm = GHOST_TWIN_MARKER_BLOCK.matcher(ghostText);
        Map<String, String> specsByOp = new LinkedHashMap<>();
        while (bm.find()) {
            String opSlug = bm.group(2);
            List<String> ensuresLines = new ArrayList<>();
            Matcher em = GHOST_BLOCK_ENSURES_LINE.matcher(bm.group(1));
            while (em.find()) {
                ensuresLines.add(em.group(1).trim());
            }
            if (!ensuresLines.isEmpty()) {
                specsByOp.put(opSlug, String.join(" && ", ensuresLines));
            }
        }
        if (specsByOp.isEmpty()) {
            return;
        }
        String merged = Files.readString(mergedC, StandardCharsets.UTF_8);
        boolean changed = false;
        for (Map.Entry<String, String> e : specsByOp.entrySet()) {
            String markerName = AnySubMarkerSpec.markerPredicateName(e.getKey());
            Pattern ensuresMarker =
                    Pattern.compile("ensures\\s+" + Pattern.quote(markerName) + "\\s*;");
            Matcher m = ensuresMarker.matcher(merged);
            if (m.find()) {
                merged = m.replaceFirst(Matcher.quoteReplacement("ensures " + e.getValue() + ";"));
                changed = true;
            }
        }
        if (changed) {
            Files.writeString(mergedC, merged, StandardCharsets.UTF_8);
        }
    }

    /**
     * Remove blocos ACSL {@code axiomatic dummy_ghost} e {@code axiomatic <M>_ghost_patterns} do merge
     * Frama-C (gerados a partir de {@code ghost_operations.ci}).
     */
    static void removeGhostPatternAxiomaticBlocks(Path mergedC) throws IOException {
        String content = Files.readString(mergedC, StandardCharsets.UTF_8);
        content = removeAllAxiomaticBlocksNamed(content, "dummy_ghost");
        Pattern ghostPatterns = Pattern.compile("axiomatic\\s+\\w+_ghost_patterns\\b");
        Matcher m = ghostPatterns.matcher(content);
        while (m.find()) {
            content = removeAxiomaticCommentBlockContaining(content, m.start());
            m = ghostPatterns.matcher(content);
        }
        Files.writeString(mergedC, content, StandardCharsets.UTF_8);
    }

    private static String removeAllAxiomaticBlocksNamed(String content, String axiomaticName) {
        String marker = "axiomatic " + axiomaticName;
        int idx;
        while ((idx = content.indexOf(marker)) >= 0) {
            content = removeAxiomaticCommentBlockContaining(content, idx);
        }
        return content;
    }

    /**
     * Remove o bloco de comentário ACSL (aberto com {@code slash-star-at}) que contém {@code keywordIdx}
     * (por exemplo o nome {@code axiomatic ...}).
     */
    private static String removeAxiomaticCommentBlockContaining(String content, int keywordIdx) {
        int blockStart = content.lastIndexOf("/*@", keywordIdx);
        if (blockStart < 0) {
            return content;
        }
        int openBrace = content.indexOf('{', keywordIdx);
        if (openBrace < 0) {
            return content;
        }
        int closeBrace = AcslCommentSpanScanner.findMatchingBrace(content, openBrace);
        if (closeBrace < 0) {
            return content;
        }
        int commentEnd = content.indexOf("*/", closeBrace);
        if (commentEnd < 0) {
            return content;
        }
        int blockEnd = AcslCommentSpanScanner.skipNewlineAfter(commentEnd + 2, content);
        return content.substring(0, blockStart) + content.substring(blockEnd);
    }

    /** Remove o prefixo {@code dummy_} de identificadores no C/ACSL fundido. */
    static void stripDummyPrefixFromMergedCode(Path mergedC) throws IOException {
        String content = Files.readString(mergedC, StandardCharsets.UTF_8);
        content = content.replaceAll("\\bdummy_", "");
        // DSet<A>/DTuple<A,B>/DRelation<A,B> foram introduzidos no ghost_operations.ci; no
        // merged_code.c os tipos reais Set<A>/Tuple<A,B>/Relation<A,B> já estão disponíveis via
        // ACSL imports (o bloco axiomatic dummy_ghost que declarava os D-prefixados já foi
        // removido — ver removeGhostPatternAxiomaticBlocks).
        content = content.replaceAll("\\bDSet<", "Set<");
        content = content.replaceAll("\\bDTuple<", "Tuple<");
        content = content.replaceAll("\\bDRelation<", "Relation<");
        Files.writeString(mergedC, content, StandardCharsets.UTF_8);
    }

    /**
     * Após {@link #stripDummyPrefixFromMergedCode}, {@code ensures dummy_ghost_<v>} passa a
     * {@code ensures ghost_<v>}; troca por {@code assigns ghost_<v>;} (variáveis ghost no merge).
     * Inclui a forma que o Frama-C emite para não-nulo: {@code ensures ghost_<v> != 0;}.
     */
    private static final Pattern ENSURES_GHOST_VAR =
            Pattern.compile("ensures\\s+(ghost_\\w+)\\s*(?:;|!=\\s*0\\s*;)");

    static void replaceEnsuresGhostVarWithAssignsInMerged(Path mergedC) throws IOException {
        String content = Files.readString(mergedC, StandardCharsets.UTF_8);
        content = ENSURES_GHOST_VAR.matcher(content).replaceAll("assigns $1;");
        content = dropRedundantAssignsNothingWhenRealLocationPresent(content);
        Files.writeString(mergedC, content, StandardCharsets.UTF_8);
    }

    private static final Pattern ASSIGNS_NOTHING_CLAUSE =
            Pattern.compile("\\bassigns\\s+\\\\nothing\\s*;\\s*");
    private static final Pattern ASSIGNS_ANY_CLAUSE = Pattern.compile("\\bassigns\\s+([^;]+);");

    /**
     * O acréscimo acima ({@code ensures ghost_v} → {@code assigns ghost_v;}) pode produzir DUAS
     * cláusulas {@code assigns} no mesmo contrato quando a operação já tinha
     * {@code assigns \nothing;} do lado real (ex.: {@code examples/Register}'s {@code add}, cuja
     * variável abstrata {@code myset} não corresponde a nenhuma localização C concreta própria) —
     * ACSL/WP rejeita {@code assigns \nothing;} misturado com uma localização real ("Mixing
     * \nothing and a real location"). Remove {@code assigns \nothing;} quando o MESMO bloco {@code
     * /*@ ... *&#47;} também tem outra cláusula {@code assigns} com uma localização real — a mais
     * específica prevalece.
     */
    private static String dropRedundantAssignsNothingWhenRealLocationPresent(String content) {
        List<AcsCommentSpan> spans = AcslCommentSpanScanner.findAllAcsCommentSpans(content);
        if (spans.isEmpty()) {
            return content;
        }
        StringBuilder out = new StringBuilder();
        int cursor = 0;
        for (AcsCommentSpan span : spans) {
            out.append(content, cursor, span.start);
            String text = span.text;
            if (ASSIGNS_NOTHING_CLAUSE.matcher(text).find() && hasRealLocationAssigns(text)) {
                text = ASSIGNS_NOTHING_CLAUSE.matcher(text).replaceFirst("");
            }
            out.append(text);
            cursor = span.end;
        }
        out.append(content.substring(cursor));
        return out.toString();
    }

    private static boolean hasRealLocationAssigns(String annotationText) {
        Matcher m = ASSIGNS_ANY_CLAUSE.matcher(annotationText);
        while (m.find()) {
            if (!"\\nothing".equals(m.group(1).trim())) {
                return true;
            }
        }
        return false;
    }

    /**
     * Coloca no início do ficheiro (logo após o preâmbulo) as linhas de declaração ghost lidas de
     * {@code ghost_operations.ci}, removendo duplicados equivalentes já presentes no merge.
     */
    static void insertGhostVariableDeclarationsFromGhostCi(Path mergedC, Path ghostCi)
            throws IOException {
        if (!Files.isRegularFile(ghostCi)) {
            return;
        }
        List<String> ghostLines = new ArrayList<>();
        for (String line : Files.readAllLines(ghostCi, StandardCharsets.UTF_8)) {
            String t = line.stripLeading();
            if (t.startsWith("//@") && t.contains("ghost")) {
                ghostLines.add(line.stripTrailing());
            }
        }
        if (ghostLines.isEmpty()) {
            return;
        }
        String content = Files.readString(mergedC, StandardCharsets.UTF_8);
        content = removeExistingGhostVariableDeclarationsFromMerged(content);
        int insertAt = AcslCommentSpanScanner.findPreambleInsertIndex(content);
        String block = String.join("\n", ghostLines);
        String sepBefore = insertAt > 0 && content.charAt(insertAt - 1) != '\n' ? "\n" : "";
        String sepAfter = insertAt < content.length() && content.charAt(insertAt) != '\n' ? "\n" : "";
        String result = content.substring(0, insertAt) + sepBefore + block + "\n" + sepAfter + content.substring(insertAt);
        Files.writeString(mergedC, result, StandardCharsets.UTF_8);
    }

    private static final Pattern GHOST_ANNOTATION_LINE = Pattern.compile("(?m)^\\s*//@\\s+ghost[^\\n]*\\R?");

    /**
     * Bloco {@code /*@ ghost ... star-slash} — candidato a duplicado de <strong>variável</strong> ghost
     * no merge; contratos de operações (assigns, ensures, {@code void} …) são filtrados em
     * {@link #shouldRemoveDuplicateGhostVarBlock}.
     */
    private static final Pattern GHOST_VAR_BLOCK_COMMENT =
            Pattern.compile("/\\*@\\s*ghost\\b[\\s\\S]*?\\*/\\s*\\R?");

    /**
     * Remove do merge só duplicados de declaração de variável ghost ({@code //@ ghost int …} ou bloco
     * ACSL equivalente). Não remove contratos / protótipos de operações ghost não puras.
     */
    private static String removeExistingGhostVariableDeclarationsFromMerged(String content) {
        content = GHOST_ANNOTATION_LINE.matcher(content).replaceAll("");
        Matcher m = GHOST_VAR_BLOCK_COMMENT.matcher(content);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String block = m.group();
            if (shouldRemoveDuplicateGhostVarBlock(block)) {
                m.appendReplacement(sb, "");
            } else {
                m.appendReplacement(sb, Matcher.quoteReplacement(block));
            }
        }
        m.appendTail(sb);
        return sb.toString();
    }

    /**
     * {@code true} se o bloco for só declaração de variável ghost a eliminar antes de reinserir a linha
     * vinda de {@code ghost_operations.ci}; {@code false} para contratos (assigns, ensures, …) ou
     * protótipos {@code void op(...)}.
     */
    private static boolean shouldRemoveDuplicateGhostVarBlock(String block) {
        if (block.contains("assigns")
                || block.contains("ensures")
                || block.contains("requires")
                || block.contains("assumes")
                || block.contains("behavior")) {
            return false;
        }
        if (block.contains("/@") || block.contains("@/")) {
            return false;
        }
        if (Pattern.compile("\\bvoid\\s+[A-Za-z_]\\w*\\s*\\(").matcher(block).find()) {
            return false;
        }
        return true;
    }

    /**
     * Substitui {@code assert ghost__} por {@code ghost } nas anotações Frama-C.
     *
     * <p>Usa {@code \s+} (não um espaço literal) entre {@code assert} e {@code ghost__}: o
     * pretty-printer do {@code -print} do Frama-C QUEBRA a linha quando é longa o suficiente (ex.:
     * {@code attackplayer}, cuja lista de 4 parâmetros é mais longa que a de qualquer outra operação
     * ghost neste projeto) — {@code "assert\n        ghost__attackplayer(...)"}, com quebra de linha
     * e indentação em vez de um único espaço. Um {@code String.replace} literal (a versão anterior)
     * nunca casava nesse caso, deixando {@code ghost__attackplayer} para trás como uma referência a
     * PREDICADO ACSL normal dentro do {@code assert} — mas só {@code void attackplayer(...)} (a
     * função ghost em si, sem o prefixo {@code ghost__}) sobrevive até {@code merged_code.c}, daí
     * "unbound logic predicate ghost__attackplayer". Só descoberto ao correr RulerOfTheSeas
     * (primeira operação ghost deste projeto cuja lista de parâmetros é longa o suficiente para
     * quebrar linha).
     */
    static void replaceAssertGhostWithGhostKeyword(Path mergedC) throws IOException {
        String content = Files.readString(mergedC, StandardCharsets.UTF_8);
        content = content.replaceAll("assert\\s+ghost__", "ghost ");
        Files.writeString(mergedC, content, StandardCharsets.UTF_8);
    }

    /** Garante {@code ghost op();} em vez de {@code ghost op;} para chamadas ghost sem argumentos. */
    static void addParenthesesToVoidGhostCalls(Path mergedC) throws IOException {
        String content = Files.readString(mergedC, StandardCharsets.UTF_8);
        content =
                Pattern.compile("\\bghost\\s+([A-Za-z_][A-Za-z0-9_]*)\\s*;")
                        .matcher(content)
                        .replaceAll("ghost $1();");
        Files.writeString(mergedC, content, StandardCharsets.UTF_8);
    }

    private static final Pattern GHOST_OP_BLOCK_IN_CI =
            Pattern.compile("(?s)/\\*@\\s*ghost\\b.*?\\bvoid\\s+([A-Za-z_]\\w*)\\s*\\([^;{}]*\\)\\s*;\\s*\\*/");
    private static final Pattern INITIALISATION_FUNCTION_DEFINITION =
            Pattern.compile("\\bvoid\\s+[A-Za-z_]\\w*__INITIALISATION\\s*\\([^;{}]*\\)\\s*\\{");

    /**
     * Novo passo pré-WP: move especificações ghost de operações para imediatamente acima da função C
     * correspondente (ex.: bloco {@code initialisation} acima de {@code Deck__INITIALISATION}).
     */
    static void placeGhostOperationSpecsAboveFunctions(Path mergedC, Path ghostCi) throws IOException {
        if (!Files.isRegularFile(ghostCi)) {
            return;
        }
        String merged = Files.readString(mergedC, StandardCharsets.UTF_8);
        String ghostText =
                GhostOperationsCiGenerator.normalizeIntegerBoolComparisonsInMergedGhostSpecs(
                        GhostOperationsCiGenerator.stripDummyPrefixForMergedGhostSpecs(
                                Files.readString(ghostCi, StandardCharsets.UTF_8)));

        Matcher bm = GHOST_OP_BLOCK_IN_CI.matcher(ghostText);
        List<String> opNames = new ArrayList<>();
        List<String> blocks = new ArrayList<>();
        while (bm.find()) {
            opNames.add(bm.group(1));
            blocks.add(bm.group().stripTrailing() + "\n\n");
        }
        if (opNames.isEmpty()) {
            return;
        }

        // Remove blocos ghost de operação já presentes para evitar duplicação.
        Matcher existingGhostBlocks = GHOST_VAR_BLOCK_COMMENT.matcher(merged);
        StringBuilder cleaned = new StringBuilder();
        while (existingGhostBlocks.find()) {
            String block = existingGhostBlocks.group();
            if (Pattern.compile("\\bvoid\\s+[A-Za-z_]\\w*\\s*\\(").matcher(block).find()) {
                existingGhostBlocks.appendReplacement(cleaned, "");
            } else {
                existingGhostBlocks.appendReplacement(cleaned, Matcher.quoteReplacement(block));
            }
        }
        existingGhostBlocks.appendTail(cleaned);
        merged = cleaned.toString();

        for (int i = 0; i < opNames.size(); i++) {
            String opName = opNames.get(i);
            String ghostBlock = blocks.get(i);
            if (opName.toLowerCase().endsWith("__initialisation")) {
                merged = placeInitialisationGhostAndContract(merged, ghostBlock);
            } else {
                int insertAt = findFunctionStartForGhostSpec(merged, opName);
                if (insertAt < 0) {
                    continue;
                }
                merged = merged.substring(0, insertAt) + ghostBlock + merged.substring(insertAt);
            }
        }
        Files.writeString(mergedC, merged, StandardCharsets.UTF_8);
    }

    // Grupo 1 usa [^*]*(?:\*(?!/)[^*]*)* (mesmo truque "desenrolado" que
    // ghostThenNormalBeforeDefinition já usa) em vez de "(.*?)" com DOTALL: "(.*?)" não tem
    // barreira nenhuma contra atravessar o "*/" de FECHO deste bloco — se "void any_sub_spec__X("
    // não for a PRIMEIRA ocorrência no ficheiro após ALGUM "/*@ ghost" anterior (ex.: o bloco
    // ghost NORMAL de uma operação diferente, processado antes deste no mesmo ghost_operations.ci),
    // o grupo "engolia" todos os blocos ghost intermédios (INITIALISATION/add/remove/…) — cada um
    // dos SEUS ensures entrava depois em specsByOp, juntados com " && " (ver GHOST_BLOCK_ENSURES_LINE
    // abaixo), corrompendo o marcador com o ensures de operações completamente alheias.
    private static final Pattern GHOST_TWIN_MARKER_BLOCK =
            Pattern.compile(
                    "/\\*@\\s*ghost\\b([^*]*(?:\\*(?!/)[^*]*)*)\\bvoid\\s+"
                            + Pattern.quote(AnySubMarkerSpec.MARKER_PREFIX)
                            + "([A-Za-z_]\\w*)\\s*\\([^;{}]*\\)\\s*;\\s*\\*/");
    private static final Pattern GHOST_BLOCK_ENSURES_LINE =
            Pattern.compile("(?m)^\\s*@\\s*ensures\\s+(.+?)\\s*;\\s*$");

    /**
     * Tratamento especial para {@code initialisation}: o Frama-C costuma colocar o contrato de
     * {@code Deck__INITIALISATION} no topo do ficheiro, separado da sua definição. Este método
     * remove o contrato flutuante e o reinsere, junto com o bloco ghost, imediatamente acima da
     * definição da função, produzindo a ordem: bloco-ghost → contrato → definição.
     */
    private static String placeInitialisationGhostAndContract(String content, String ghostBlock) {
        // Find the function definition (with body).
        Matcher defMatcher = INITIALISATION_FUNCTION_DEFINITION.matcher(content);
        if (!defMatcher.find()) {
            return content;
        }
        int defStart = defMatcher.start();

        // Check if a contract is already immediately adjacent (just before the definition).
        int lastSpecStart = content.lastIndexOf("/*@", defStart);
        if (lastSpecStart >= 0) {
            int lastSpecEnd = content.indexOf("*/", lastSpecStart);
            if (lastSpecEnd >= 0 && lastSpecEnd < defStart) {
                String between = content.substring(lastSpecEnd + 2, defStart).trim();
                if (between.isEmpty()) {
                    // Contract is adjacent — just insert ghost block above the contract.
                    int lineStart = content.lastIndexOf('\n', lastSpecStart);
                    int insertAt = lineStart < 0 ? 0 : lineStart + 1;
                    return content.substring(0, insertAt) + ghostBlock + content.substring(insertAt);
                }
            }
        }

        // Contract is NOT adjacent. Look for a floating ensures/assigns contract before the definition.
        Pattern floatingContractPat =
                Pattern.compile("(?s)/\\*@(?!\\s*(?:ghost|axiomatic)\\b)([\\s\\S]*?)\\*/");
        Matcher fcm = floatingContractPat.matcher(content.substring(0, defStart));
        String floatingContract = null;
        int fcStart = -1;
        int fcEnd = -1;
        while (fcm.find()) {
            String blk = fcm.group();
            if (blk.contains("ensures") || blk.contains("assigns")) {
                floatingContract = blk;
                fcStart = fcm.start();
                fcEnd = fcm.end();
            }
        }

        if (floatingContract == null) {
            // No floating contract found — just insert ghost block before the definition.
            int lineStart = content.lastIndexOf('\n', defStart);
            int insertAt = lineStart < 0 ? 0 : lineStart + 1;
            return content.substring(0, insertAt) + ghostBlock + content.substring(insertAt);
        }

        // Expand removal range to consume surrounding blank lines neatly.
        int removeStart = fcStart;
        while (removeStart > 0 && content.charAt(removeStart - 1) == '\n') {
            removeStart--;
        }
        int removeEnd = fcEnd;
        while (removeEnd < content.length() && content.charAt(removeEnd) == '\n') {
            removeEnd++;
        }

        String withoutContract = content.substring(0, removeStart) + content.substring(removeEnd);

        // Re-locate definition (offsets shifted after removal).
        Matcher defMatcher2 = INITIALISATION_FUNCTION_DEFINITION.matcher(withoutContract);
        if (!defMatcher2.find()) {
            return content; // Safety fallback — restore original.
        }
        int newDefStart = defMatcher2.start();
        int lineStart = withoutContract.lastIndexOf('\n', newDefStart);
        int insertAt = lineStart < 0 ? 0 : lineStart + 1;

        String toInsert = ghostBlock + floatingContract + "\n";
        return withoutContract.substring(0, insertAt) + toInsert + withoutContract.substring(insertAt);
    }

    private static int findFunctionStartForGhostSpec(String content, String opName) {
        if (content == null || content.isBlank() || opName == null || opName.isBlank()) {
            return -1;
        }
        List<Pattern> candidates = new ArrayList<>();
        if (opName.toLowerCase().endsWith("__initialisation")) {
            candidates.add(
                    Pattern.compile(
                            "\\bvoid\\s+[A-Za-z_]\\w*__INITIALISATION\\s*\\([^;{}]*\\)\\s*\\{"));
        }
        candidates.add(
                Pattern.compile(
                        "\\bvoid\\s+[A-Za-z_]\\w*__(?i:"
                                + Pattern.quote(opName)
                                + ")\\s*\\([^;{}]*\\)\\s*\\{"));
        for (Pattern p : candidates) {
            Matcher m = p.matcher(content);
            if (!m.find()) {
                continue;
            }
            int idx = m.start();
            int specStart = content.lastIndexOf("/*@", idx);
            if (specStart >= 0) {
                int specEnd = content.indexOf("*/", specStart);
                if (specEnd >= 0 && specEnd < idx) {
                    String between = content.substring(specEnd + 2, idx).trim();
                    if (between.isEmpty()) {
                        idx = specStart;
                    }
                }
            }
            int lineStart = content.lastIndexOf('\n', idx);
            return lineStart < 0 ? 0 : lineStart + 1;
        }
        return -1;
    }

    /**
     * Para operações cujo contrato ghost não altera variáveis abstratas
     * ({@code assigns \nothing;}), remove o bloco ghost e injeta os seus
     * {@code ensures} no contrato normal da operação imediatamente adjacente.
     */
    static void liftPureGhostEnsuresToOperationContracts(Path mergedC) throws IOException {
        String content = Files.readString(mergedC, StandardCharsets.UTF_8);
        // Group 1 usa [^*]*(?:\*(?!/)[^*]*)* — "loop desenrolado" equivalente a (?:[^*]|\*(?!/))*
        // (mesma linguagem: qualquer texto até o primeiro "*/" literal) mas SEM repetir um GRUPO de
        // alternação via "*": em Java, Pattern$Loop recursa uma stack frame por repetição de um
        // GRUPO (Pattern$GroupHead/GroupTail/Branch/BranchConn no stack trace), então o "(?:X|Y)*"
        // original recursava uma vez por CARACTER do bloco ghost — StackOverflowError em blocos
        // ghost grandes (ex.: RulerOfTheSeas, com muito mais conteúdo ghost que os exemplos
        // anteriores — nunca disparou até rodar esse exemplo). A forma desenrolada só recursa uma
        // vez por "*" literal (raro em texto ACSL), com o grosso do texto consumido pelas classes de
        // caracteres [^*]* (repetição eficiente, não-recursiva-por-caractere em Java). Mesma proteção
        // do original contra a cauda "\*/" ser ultrapassada por backtracking: um "*" só é consumido
        // pelo grupo interno quando NÃO é seguido de "/".
        Pattern ghostThenNormalBeforeDefinition =
                Pattern.compile(
                        "(/\\*@\\s*ghost\\b[^*]*(?:\\*(?!/)[^*]*)*\\*/\\s*)"
                                + "(?s)(/\\*@(?!\\s*(?:ghost|axiomatic)\\b)[\\s\\S]*?\\*/\\s*)"
                                + "(void\\s+[A-Za-z_]\\w*__([A-Za-z_]\\w*)\\s*\\([^;{}]*\\)\\s*\\{)");
        Pattern pureGhostAssignsNothing =
                Pattern.compile("(?m)^\\s*/?@\\s*assigns\\s+\\\\nothing\\s*;");
        Pattern ghostEnsureLine = Pattern.compile("(?m)^\\s*@\\s*ensures\\s+(.+?)\\s*;\\s*$");

        Matcher m = ghostThenNormalBeforeDefinition.matcher(content);
        StringBuilder sb = new StringBuilder();
        Set<String> liftedOps = new LinkedHashSet<>();
        while (m.find()) {
            String ghostBlock = m.group(1);
            String normalContract = m.group(2);
            String functionDef = m.group(3);
            String opSuffix = m.group(4);

            if (!pureGhostAssignsNothing.matcher(ghostBlock).find()) {
                m.appendReplacement(sb, Matcher.quoteReplacement(m.group()));
                continue;
            }
            // Cláusula \exists/\forall sobre tipo não-escalar (ex.: Registro__elements): já foi
            // colocada no contrato normal por uma função ghost gêmea (ver
            // com.example.AnySubMarkerSpec / GhostOperationsCiGenerator), trocada pelo marcador em
            // GhostMergedCodeCleanup#spliceAnySubMarkerSpecsFromGhostCi ANTES deste passo correr (ver
            // ordem em FramaCRunner). Levantar este bloco TAMBÉM duplicaria a mesma especificação —
            // mantém-se o bloco ghost como está (ainda necessário para a ponte "ghost <op>();" na
            // definição da função), só sem o levantamento.
            if (ghostEnsureLine.matcher(ghostBlock).results().anyMatch(
                    r -> AnySubMarkerSpec.isNonScalarQuantifiedClause(r.group(1)))) {
                m.appendReplacement(sb, Matcher.quoteReplacement(m.group()));
                continue;
            }

            Matcher em = ghostEnsureLine.matcher(ghostBlock);
            List<String> ensuresToLift = new ArrayList<>();
            while (em.find()) {
                ensuresToLift.add(em.group(1).trim());
            }
            if (ensuresToLift.isEmpty()) {
                m.appendReplacement(sb, Matcher.quoteReplacement(normalContract + functionDef));
                continue;
            }

            int contractEnd = normalContract.lastIndexOf("*/");
            if (contractEnd < 0) {
                m.appendReplacement(sb, Matcher.quoteReplacement(normalContract + functionDef));
                continue;
            }

            StringBuilder liftedEnsures = new StringBuilder();
            for (String ensure : ensuresToLift) {
                liftedEnsures.append("  @ ensures ").append(ensure).append(";\n");
            }
            String mergedContract =
                    normalContract.substring(0, contractEnd)
                            + liftedEnsures
                            + normalContract.substring(contractEnd);
            liftedOps.add(opSuffix);
            m.appendReplacement(sb, Matcher.quoteReplacement(mergedContract + functionDef));
        }
        m.appendTail(sb);
        String rewritten = sb.toString();
        for (String opSuffix : liftedOps) {
            rewritten = removeGhostCallFromMergedCode(rewritten, opSuffix);
        }
        Files.writeString(mergedC, rewritten, StandardCharsets.UTF_8);
    }

    /**
     * Remove do merge a chamada de anotação ghost
     * (ex.: {@code slash-star-at ghost op(...); star-slash}) referente à própria operação quando
     * o contrato ghost foi incorporado ao contrato normal.
     */
    private static String removeGhostCallFromMergedCode(String content, String opSuffix) {
        if (content == null || content.isEmpty() || opSuffix == null || opSuffix.isBlank()) {
            return content;
        }
        Pattern ghostCall =
                Pattern.compile(
                        "/\\*@\\s*ghost\\s+"
                                + Pattern.quote(opSuffix)
                                + "\\s*\\([^;{}]*\\)\\s*;\\s*\\*/\\s*");
        return ghostCall.matcher(content).replaceAll("");
    }
}
