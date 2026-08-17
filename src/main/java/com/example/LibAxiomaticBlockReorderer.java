package com.example;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Reordenação de blocos {@code axiomatic} de biblioteca em {@code merged_code.c}, alinhada à ordem
 * de {@link AcslLibIncludes#orderedLibFunctionAxiomaticNames()}: agrupa sequência, acopla
 * {@code *_axioms} ao pai, antecipa blocos deixados depois de {@code Connection_*}, etc. Extraído
 * de {@code B2ACSLPipeline} (WMC=607) por extract-class puro: nenhuma linha de lógica mudou, só o
 * arquivo em que vive.
 */
final class LibAxiomaticBlockReorderer {

    private LibAxiomaticBlockReorderer() {}

    /**
     * Passo 6: reordena no merge os blocos {@code /*@ axiomatic X { ... } star-slash} que declaram
     * {@code logic} ou {@code predicate}, quando {@code X} pertence a
     * {@link AcslLibIncludes#orderedLibFunctionAxiomaticNames()}; coloca {@code mapping_function} após
     * {@code new_types}; acopla cada {@code X_axioms} ao axiomatic pai {@code X}; agrupa blocos de
     * sequência ({@code sequence_is_seq_of}, {@code range_function}, {@code sequence_iseq}) na ordem da
     * lib; e antecipa qualquer bloco da lib (incluindo {@code *_axioms}) que o Frama-C tenha deixado
     * depois de {@code Connection_*}, para declarações como {@code is_seq_of} existirem antes do uso.
     */
    static void reorderLibAxiomaticBlocksPerAcslLibIncludesOrder(Path mergedC) throws IOException {
        List<String> orderedNames = AcslLibIncludes.orderedLibFunctionAxiomaticNames();
        String content = Files.readString(mergedC, StandardCharsets.UTF_8);
        Map<String, Integer> rank = new HashMap<>();
        if (!orderedNames.isEmpty()) {
            for (int i = 0; i < orderedNames.size(); i++) {
                rank.put(orderedNames.get(i), i);
            }
            content = reorderLibAxiomaticBlocksInMerged(content, rank);
        }
        content = placeMappingFunctionImmediatelyAfterNewTypes(content);
        content = clusterSequenceAxiomaticsAfterIsSeqOf(content);
        content = attachAxiomsBlocksAfterParentAxiomatic(content);
        if (!rank.isEmpty()) {
            content = moveLibAxiomaticBlocksBeforeConnection(content, rank);
            content = moveMachineAxiomaticsAfterSurroundingLibBlocks(content, rank);
        }
        content = moveStandaloneMachinePredicatesAfterMachineAxiomatics(content, rank);
        content = moveMachineAcslBlocksBeforeFirstGhostBlock(content, rank);
        Files.writeString(mergedC, content, StandardCharsets.UTF_8);
    }


    /**
     * Ordem {@link AcslLibIncludes#FILE_ORDER} para ficheiros de sequência: {@code is_seq_of.acsl},
     * {@code range.acsl}, {@code iseq.acsl}.
     */
    private static final List<String> SEQUENCE_CLUSTER_AFTER_IS_SEQ_OF =
            List.of("range_function", "sequence_iseq", "sequence_last");

    /**
     * Garante {@code range_function} e {@code sequence_iseq} (se existirem) imediatamente após
     * {@code sequence_is_seq_of}, alinhado à ordem dos includes da lib.
     */
    private static String clusterSequenceAxiomaticsAfterIsSeqOf(String content) {
        List<AcsCommentSpan> spans = AcslCommentSpanScanner.findAllAcsCommentSpans(content);
        AcsCommentSpan isSeqOf = null;
        Map<String, AcsCommentSpan> followers = new HashMap<>();
        for (AcsCommentSpan sp : spans) {
            if (sp.axiomaticName == null || !LOGIC_OR_PREDICATE_IN_BLOCK.matcher(sp.text).find()) {
                continue;
            }
            if ("sequence_is_seq_of".equals(sp.axiomaticName) && isSeqOf == null) {
                isSeqOf = sp;
            } else if (SEQUENCE_CLUSTER_AFTER_IS_SEQ_OF.contains(sp.axiomaticName)
                    && !followers.containsKey(sp.axiomaticName)) {
                followers.put(sp.axiomaticName, sp);
            }
        }
        if (isSeqOf == null || followers.isEmpty()) {
            return content;
        }
        List<AcsCommentSpan> toRemove = new ArrayList<>(followers.values());
        for (AcsCommentSpan follower : followers.values()) {
            collectAxiomsBlocksForParent(spans, follower.axiomaticName, toRemove);
        }
        toRemove.sort(Comparator.comparingInt((AcsCommentSpan s) -> s.start).reversed());
        String cut = content;
        for (AcsCommentSpan sp : toRemove) {
            cut = cut.substring(0, sp.start) + cut.substring(sp.end);
        }
        spans = AcslCommentSpanScanner.findAllAcsCommentSpans(cut);
        AcsCommentSpan anchor = null;
        for (AcsCommentSpan sp : spans) {
            if ("sequence_is_seq_of".equals(sp.axiomaticName)
                    && LOGIC_OR_PREDICATE_IN_BLOCK.matcher(sp.text).find()) {
                anchor = sp;
                break;
            }
        }
        if (anchor == null) {
            return cut;
        }
        StringBuilder insert = new StringBuilder();
        for (String name : SEQUENCE_CLUSTER_AFTER_IS_SEQ_OF) {
            AcsCommentSpan sp = followers.get(name);
            if (sp == null) {
                continue;
            }
            insert.append(sp.text);
            for (AcsCommentSpan ax : toRemove) {
                if (ax.axiomaticName == null || !ax.axiomaticName.endsWith("_axioms")) {
                    continue;
                }
                if (name.equals(resolveParentAxiomaticName(ax.axiomaticName, spans))) {
                    insert.append(ax.text);
                }
            }
        }
        return cut.substring(0, anchor.end) + insert + cut.substring(anchor.end);
    }

    private static void collectAxiomsBlocksForParent(
            List<AcsCommentSpan> spans, String parentName, List<AcsCommentSpan> out) {
        for (AcsCommentSpan sp : spans) {
            if (sp.axiomaticName == null || !sp.axiomaticName.endsWith("_axioms")) {
                continue;
            }
            if (parentName.equals(resolveParentAxiomaticName(sp.axiomaticName, spans))
                    && out.stream().noneMatch(x -> x.start == sp.start)) {
                out.add(sp);
            }
        }
    }

    /**
     * Quando o bloco {@code axiomatic mapping_function} existir no merge, move-o para imediatamente
     * depois de {@code axiomatic new_types} para manter as definições de mapeamento de listas junto
     * da declaração de tipos base.
     */
    private static String placeMappingFunctionImmediatelyAfterNewTypes(String content) {
        List<AcsCommentSpan> spans = AcslCommentSpanScanner.findAllAcsCommentSpans(content);
        AcsCommentSpan mapping = null;
        AcsCommentSpan newTypes = null;
        for (AcsCommentSpan sp : spans) {
            if ("mapping_function".equals(sp.axiomaticName)
                    && LOGIC_OR_PREDICATE_IN_BLOCK.matcher(sp.text).find()
                    && mapping == null) {
                mapping = sp;
            } else if ("new_types".equals(sp.axiomaticName) && newTypes == null) {
                newTypes = sp;
            }
        }
        if (mapping == null || newTypes == null) {
            return content;
        }

        String cut = content.substring(0, mapping.start) + content.substring(mapping.end);
        List<AcsCommentSpan> cutSpans = AcslCommentSpanScanner.findAllAcsCommentSpans(cut);
        AcsCommentSpan newTypesAfter = null;
        for (AcsCommentSpan sp : cutSpans) {
            if ("new_types".equals(sp.axiomaticName)) {
                newTypesAfter = sp;
                break;
            }
        }
        if (newTypesAfter == null) {
            return content;
        }
        return cut.substring(0, newTypesAfter.end) + mapping.text + cut.substring(newTypesAfter.end);
    }

    /**
     * Coloca cada bloco {@code axiomatic Foo_axioms} imediatamente após o axiomatic pai {@code Foo}
     * (mesmo nome sem o sufixo {@code _axioms}), como nos ficheiros da lib que fazem {@code include}
     * do axioma a seguir à declaração.
     */
    private static String attachAxiomsBlocksAfterParentAxiomatic(String content) {
        List<AcsCommentSpan> spans = AcslCommentSpanScanner.findAllAcsCommentSpans(content);
        List<String[]> toAttach = new ArrayList<>();
        List<AcsCommentSpan> toRemove = new ArrayList<>();
        for (AcsCommentSpan sp : spans) {
            if (sp.axiomaticName == null || !sp.axiomaticName.endsWith("_axioms")) {
                continue;
            }
            String parent = resolveParentAxiomaticName(sp.axiomaticName, spans);
            toAttach.add(new String[] {parent, sp.text});
            toRemove.add(sp);
        }
        if (toRemove.isEmpty()) {
            return content;
        }
        toRemove.sort(Comparator.comparingInt((AcsCommentSpan s) -> s.start).reversed());
        String cut = content;
        for (AcsCommentSpan sp : toRemove) {
            cut = cut.substring(0, sp.start) + cut.substring(sp.end);
        }
        for (String[] pair : toAttach) {
            String parentName = pair[0];
            String axiomText = pair[1];
            AcsCommentSpan parent = findDeclAxiomaticBlock(AcslCommentSpanScanner.findAllAcsCommentSpans(cut), parentName);
            if (parent != null) {
                cut = cut.substring(0, parent.end) + axiomText + cut.substring(parent.end);
            } else {
                int idx = AcslCommentSpanScanner.findPreambleInsertIndex(cut);
                cut = cut.substring(0, idx) + axiomText + cut.substring(idx);
            }
        }
        return cut;
    }

    /**
     * Antecipa blocos da lib (declarações e {@code *_axioms}) que o Frama-C deixou depois do primeiro
     * {@code Connection_*}, na ordem de {@link AcslLibIncludes#orderedLibFunctionAxiomaticNames()}.
     */
    private static String moveLibAxiomaticBlocksBeforeConnection(
            String content, Map<String, Integer> rank) {
        List<AcsCommentSpan> spans = AcslCommentSpanScanner.findAllAcsCommentSpans(content);
        int connectionStart = -1;
        for (AcsCommentSpan sp : spans) {
            if (sp.axiomaticName != null && sp.axiomaticName.startsWith("Connection_")) {
                connectionStart = sp.start;
                break;
            }
        }
        if (connectionStart < 0) {
            return content;
        }
        Set<String> libNames = new LinkedHashSet<>(rank.keySet());
        for (String n : rank.keySet()) {
            libNames.add(n + "_axioms");
        }
        List<AcsCommentSpan> toMove = new ArrayList<>();
        for (AcsCommentSpan sp : spans) {
            if (sp.axiomaticName == null || sp.start <= connectionStart) {
                continue;
            }
            boolean libDecl = libNames.contains(sp.axiomaticName);
            boolean libAxioms =
                    sp.axiomaticName.endsWith("_axioms")
                            && libNames.contains(
                                    resolveParentAxiomaticName(sp.axiomaticName, spans));
            if (libDecl || libAxioms) {
                toMove.add(sp);
            }
        }
        if (toMove.isEmpty()) {
            return content;
        }
        toMove.sort(Comparator.comparingInt((AcsCommentSpan s) -> s.start).reversed());
        String cut = content;
        for (AcsCommentSpan sp : toMove) {
            cut = cut.substring(0, sp.start) + cut.substring(sp.end);
        }
        List<AcsCommentSpan> after = AcslCommentSpanScanner.findAllAcsCommentSpans(cut);
        int newConn = -1;
        for (AcsCommentSpan sp : after) {
            if (sp.axiomaticName != null && sp.axiomaticName.startsWith("Connection_")) {
                newConn = sp.start;
                break;
            }
        }
        if (newConn < 0) {
            return cut;
        }
        List<AcsCommentSpan> orderedMove = new ArrayList<>(toMove);
        orderedMove.sort(
                Comparator.comparingInt((AcsCommentSpan s) -> libAxiomaticRank(rank, s.axiomaticName))
                        .thenComparingInt(s -> s.start));
        StringBuilder insert = new StringBuilder();
        for (AcsCommentSpan sp : orderedMove) {
            insert.append(sp.text);
        }
        return cut.substring(0, newConn) + insert + cut.substring(newConn);
    }

    /**
     * Move blocos axiomáticos de máquina (não-lib) que estejam intercalados entre blocos de lib
     * para depois do último bloco de lib que os segue. Isso evita referências a símbolos de lib
     * (ex.: {@code relation_inverse}) antes da respetiva declaração.
     */
    private static String moveMachineAxiomaticsAfterSurroundingLibBlocks(
            String content, Map<String, Integer> rank) {
        List<AcsCommentSpan> spans = AcslCommentSpanScanner.findAllAcsCommentSpans(content);
        // Identify spans that are non-lib but surrounded (before and after) by lib spans.
        // "Surrounded" = has at least one lib span before AND at least one lib span after.
        Set<String> libAndAxioms = new LinkedHashSet<>(rank.keySet());
        for (String n : rank.keySet()) {
            libAndAxioms.add(n + "_axioms");
        }
        int lastLibIdx = -1;
        for (int i = spans.size() - 1; i >= 0; i--) {
            AcsCommentSpan sp = spans.get(i);
            if (sp.axiomaticName != null && libAndAxioms.contains(sp.axiomaticName)) {
                lastLibIdx = i;
                break;
            }
        }
        if (lastLibIdx < 0) return content;

        boolean seenLib = false;
        List<AcsCommentSpan> toMove = new ArrayList<>();
        for (int i = 0; i < lastLibIdx; i++) {
            AcsCommentSpan sp = spans.get(i);
            if (sp.axiomaticName != null && libAndAxioms.contains(sp.axiomaticName)) {
                seenLib = true;
            } else if (seenLib && sp.axiomaticName != null
                    && !libAndAxioms.contains(sp.axiomaticName)
                    && !sp.axiomaticName.endsWith("_tuple_types")
                    && i < lastLibIdx) {
                // Non-lib block after at least one lib block, and more lib blocks follow.
                // "_tuple_types" excluído: são FORNECEDORES de tipo (ver
                // moveTupleTypesBlocksAfterNewTypes), não consumidores — mover para o fim
                // reintroduziria "no such type" nos blocos de lib monomorfizados que os precedem
                // e dependem deles (ex.: dom/relation_ran instanciados no mesmo par).
                toMove.add(sp);
            }
        }
        if (toMove.isEmpty()) return content;

        // Remove moved spans in reverse order (so earlier offsets remain valid)
        List<AcsCommentSpan> rev = new ArrayList<>(toMove);
        rev.sort(Comparator.comparingInt((AcsCommentSpan s) -> s.start).reversed());
        String result = content;
        List<String> movedTexts = new ArrayList<>();
        for (AcsCommentSpan sp : rev) {
            movedTexts.add(0, result.substring(sp.start, sp.end));
            result = result.substring(0, sp.start) + result.substring(sp.end);
        }

        // Find the new position of the last lib span in the modified content
        List<AcsCommentSpan> newSpans = AcslCommentSpanScanner.findAllAcsCommentSpans(result);
        int insertAfter = -1;
        for (int i = newSpans.size() - 1; i >= 0; i--) {
            AcsCommentSpan sp = newSpans.get(i);
            if (sp.axiomaticName != null && libAndAxioms.contains(sp.axiomaticName)) {
                insertAfter = sp.end;
                break;
            }
        }
        if (insertAfter < 0) return content; // safety fallback

        StringBuilder insert = new StringBuilder();
        for (String t : movedTexts) {
            insert.append(t);
        }
        return result.substring(0, insertAfter) + insert + result.substring(insertAfter);
    }

    /**
     * Move predicados ACSL standalone (sem wrapper axiomatic) que estejam intercalados entre
     * blocos de lib para depois do último bloco axiomatic do arquivo (incluindo axiomáticos de
     * máquina). Isso corrige o caso onde Frama-C emite {@code predicate Biblioteca_invariant}
     * antes dos axiomáticos de máquina que declaram as variáveis que o predicado referencia.
     */
    private static String moveStandaloneMachinePredicatesAfterMachineAxiomatics(
            String content, Map<String, Integer> rank) {
        List<AcsCommentSpan> spans = AcslCommentSpanScanner.findAllAcsCommentSpans(content);

        Set<String> libAndAxioms = new LinkedHashSet<>(rank.keySet());
        for (String n : rank.keySet()) libAndAxioms.add(n + "_axioms");

        // Find the last lib span index in the spans list
        int lastLibIdx = -1;
        for (int i = spans.size() - 1; i >= 0; i--) {
            AcsCommentSpan sp = spans.get(i);
            if (sp.axiomaticName != null && libAndAxioms.contains(sp.axiomaticName)) {
                lastLibIdx = i;
                break;
            }
        }
        if (lastLibIdx < 0) return content;

        // Collect standalone predicate blocks that appear between lib blocks
        boolean seenLib = false;
        List<AcsCommentSpan> toMove = new ArrayList<>();
        for (int i = 0; i < lastLibIdx; i++) {
            AcsCommentSpan sp = spans.get(i);
            if (sp.axiomaticName != null && libAndAxioms.contains(sp.axiomaticName)) {
                seenLib = true;
            } else if (seenLib && sp.axiomaticName == null
                    && LOGIC_OR_PREDICATE_IN_BLOCK.matcher(sp.text).find()) {
                toMove.add(sp);
            }
        }
        if (toMove.isEmpty()) return content;

        // Remove them in reverse order to preserve offsets
        List<AcsCommentSpan> rev = new ArrayList<>(toMove);
        rev.sort(Comparator.comparingInt((AcsCommentSpan s) -> s.start).reversed());
        String result = content;
        List<String> movedTexts = new ArrayList<>();
        for (AcsCommentSpan sp : rev) {
            movedTexts.add(0, result.substring(sp.start, sp.end));
            result = result.substring(0, sp.start) + result.substring(sp.end);
        }

        // Insert after the last axiomatic block (machine or lib) in the modified content
        List<AcsCommentSpan> newSpans = AcslCommentSpanScanner.findAllAcsCommentSpans(result);
        int insertAfter = -1;
        for (int i = newSpans.size() - 1; i >= 0; i--) {
            if (newSpans.get(i).axiomaticName != null) {
                insertAfter = newSpans.get(i).end;
                break;
            }
        }
        if (insertAfter < 0) return content;

        StringBuilder insert = new StringBuilder();
        for (String t : movedTexts) insert.append(t);
        return result.substring(0, insertAfter) + insert + result.substring(insertAfter);
    }

    /**
     * Move todos os blocos ACSL de máquina (axiomáticos não-lib e predicados standalone) que
     * aparecem após o primeiro bloco ghost ({@code /*@ ghost ... *\/}) para antes desse bloco.
     * Isso garante que variáveis lógicas de máquina (ex.: {@code books}, {@code copyOf}) estejam
     * declaradas quando o Frama-C processa os contratos de funções ghost.
     */
    private static String moveMachineAcslBlocksBeforeFirstGhostBlock(
            String content, Map<String, Integer> rank) {
        List<AcsCommentSpan> spans = AcslCommentSpanScanner.findAllAcsCommentSpans(content);

        // Find the first ghost block (/*@ ghost ... */)
        int firstGhostStart = -1;
        for (AcsCommentSpan sp : spans) {
            if (sp.axiomaticName == null && sp.text.startsWith("/*@ ghost")) {
                firstGhostStart = sp.start;
                break;
            }
        }
        if (firstGhostStart < 0) return content;

        Set<String> libAndAxioms = new LinkedHashSet<>(rank.keySet());
        for (String n : rank.keySet()) libAndAxioms.add(n + "_axioms");

        // Collect machine-specific ACSL blocks that appear AFTER the first ghost block
        List<AcsCommentSpan> toMove = new ArrayList<>();
        for (AcsCommentSpan sp : spans) {
            if (sp.start <= firstGhostStart) continue;
            boolean isLibAxioms = sp.axiomaticName != null
                    && sp.axiomaticName.endsWith("_axioms")
                    && libAndAxioms.contains(resolveParentAxiomaticName(sp.axiomaticName, spans));
            boolean isMachineAxiomatic = sp.axiomaticName != null
                    && !libAndAxioms.contains(sp.axiomaticName)
                    && !isLibAxioms;
            boolean isStandalonePredicate = sp.axiomaticName == null
                    && LOGIC_OR_PREDICATE_IN_BLOCK.matcher(sp.text).find();
            if (isMachineAxiomatic || isStandalonePredicate) {
                toMove.add(sp);
            }
        }
        if (toMove.isEmpty()) return content;

        // Remove them in reverse order to preserve earlier offsets
        List<AcsCommentSpan> rev = new ArrayList<>(toMove);
        rev.sort(Comparator.comparingInt((AcsCommentSpan s) -> s.start).reversed());
        String result = content;
        List<String> movedTexts = new ArrayList<>();
        for (AcsCommentSpan sp : rev) {
            movedTexts.add(0, result.substring(sp.start, sp.end));
            result = result.substring(0, sp.start) + result.substring(sp.end);
        }

        // Find new position of first ghost block in modified content
        List<AcsCommentSpan> newSpans = AcslCommentSpanScanner.findAllAcsCommentSpans(result);
        int newGhostStart = -1;
        for (AcsCommentSpan sp : newSpans) {
            if (sp.axiomaticName == null && sp.text.startsWith("/*@ ghost")) {
                newGhostStart = sp.start;
                break;
            }
        }
        if (newGhostStart < 0) return content;

        StringBuilder insertSb = new StringBuilder();
        for (String t : movedTexts) insertSb.append(t);
        return result.substring(0, newGhostStart) + insertSb + result.substring(newGhostStart);
    }

    private static AcsCommentSpan findDeclAxiomaticBlock(List<AcsCommentSpan> spans, String name) {
        for (AcsCommentSpan sp : spans) {
            if (name.equals(sp.axiomaticName)) {
                return sp;
            }
        }
        Pattern decl = Pattern.compile("axiomatic\\s+" + Pattern.quote(name) + "\\b");
        for (AcsCommentSpan sp : spans) {
            if (decl.matcher(sp.text).find()) {
                return sp;
            }
        }
        return null;
    }

    /**
     * Nome do axiomatic de declaração correspondente a {@code Foo_axioms}. Na lib o sufixo nem sempre
     * é um prefixo exacto do pai (ex.: {@code range_axioms} → {@code range_function}).
     */
    private static String resolveParentAxiomaticName(
            String axiomsName, List<AcsCommentSpan> spans) {
        if (!axiomsName.endsWith("_axioms")) {
            return axiomsName;
        }
        String base = axiomsName.substring(0, axiomsName.length() - "_axioms".length());
        Set<String> declNames = new LinkedHashSet<>();
        for (AcsCommentSpan sp : spans) {
            if (sp.axiomaticName != null && !sp.axiomaticName.endsWith("_axioms")) {
                declNames.add(sp.axiomaticName);
            }
        }
        if (declNames.contains(base)) {
            return base;
        }
        String withFunction = base + "_function";
        if (declNames.contains(withFunction)) {
            return withFunction;
        }
        String withSequence = "sequence_" + base;
        if (declNames.contains(withSequence)) {
            return withSequence;
        }
        for (String n : declNames) {
            if (axiomsName.equals(n + "_axioms")) {
                return n;
            }
        }
        // Caso: base começa com o nome de uma declaração conhecida seguida de "_"
        // ex.: array_to_function_int_axioms → base = array_to_function_int
        //      → começa com "array_to_function_" → pai = array_to_function
        for (String n : declNames) {
            if (base.startsWith(n + "_")) {
                return n;
            }
        }
        return base;
    }

    private static int libAxiomaticRank(Map<String, Integer> rank, String axiomaticName) {
        if (rank.containsKey(axiomaticName)) {
            return rank.get(axiomaticName);
        }
        if (axiomaticName.endsWith("_axioms")) {
            String base = axiomaticName.substring(0, axiomaticName.length() - "_axioms".length());
            if (rank.containsKey(base)) {
                return rank.get(base);
            }
            if (rank.containsKey(base + "_function")) {
                return rank.get(base + "_function");
            }
            String withSequence = "sequence_" + base;
            if (rank.containsKey(withSequence)) {
                return rank.get(withSequence);
            }
            for (String n : rank.keySet()) {
                if (axiomaticName.equals(n + "_axioms")) {
                    return rank.get(n);
                }
            }
        }
        return Integer.MAX_VALUE;
    }

    private static final Pattern LOGIC_OR_PREDICATE_IN_BLOCK =
            Pattern.compile("\\b(logic|predicate)\\s+");

    private static String reorderLibAxiomaticBlocksInMerged(String content, Map<String, Integer> rank) {
        List<AcsCommentSpan> spans = AcslCommentSpanScanner.findAllAcsCommentSpans(content);
        List<AcsCommentSpan> sortable = new ArrayList<>();
        for (AcsCommentSpan sp : spans) {
            if (sp.axiomaticName != null
                    && rank.containsKey(sp.axiomaticName)
                    && LOGIC_OR_PREDICATE_IN_BLOCK.matcher(sp.text).find()) {
                sortable.add(sp);
            }
        }
        if (sortable.size() <= 1) {
            return content;
        }
        List<AcsCommentSpan> byFile = new ArrayList<>(sortable);
        byFile.sort(Comparator.comparingInt(a -> a.start));
        List<AcsCommentSpan> byRank = new ArrayList<>(sortable);
        byRank.sort(
                Comparator.comparingInt((AcsCommentSpan a) -> rank.get(a.axiomaticName))
                        .thenComparingInt(a -> a.start));
        Map<Integer, String> replacement = new HashMap<>();
        for (int i = 0; i < byFile.size(); i++) {
            replacement.put(byFile.get(i).start, byRank.get(i).text);
        }
        StringBuilder sb = new StringBuilder();
        int cursor = 0;
        for (AcsCommentSpan sp : spans) {
            sb.append(content, cursor, sp.start);
            String rep = replacement.get(sp.start);
            if (rep != null) {
                sb.append(rep);
            } else {
                sb.append(sp.text);
            }
            cursor = sp.end;
        }
        sb.append(content, cursor, content.length());
        return sb.toString();
    }
}
