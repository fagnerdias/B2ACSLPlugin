package com.example;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Posicionamento estrutural de blocos em {@code merged_code.c}: remove ruído de log do Frama-C no
 * topo do ficheiro, e move os blocos {@code axiomatic new_types}/{@code *_tuple_types} para o
 * preâmbulo, antes dos restantes blocos {@code axiomatic}. Extraído de {@code B2ACSLPipeline}
 * (WMC=607) por extract-class puro: nenhuma linha de lógica mudou, só o arquivo em que vive.
 */
final class MergedCodeStructuralPlacement {

    private MergedCodeStructuralPlacement() {}

    /**
     * Linhas de diagnóstico que o Frama-C escreve no stdout antes do C gerado com {@code -print}
     * (ex.: {@code [kernel] Parsing ...}, {@code [acsl-import] Success ...}).
     */
    private static final Pattern FRAMA_C_STDOUT_TAG_LINE =
            Pattern.compile("^\\[[^\\]]+\\]\\s*.*");

    /** Marca o bloco {@code axiomatic new_types} importado (ex. de {@code types.acsl}). */
    private static final String AXIOMATIC_NEW_TYPES_MARKER = "axiomatic new_types";

    /**
     * Remove do início de {@code merged_code.c} linhas em branco e linhas de log Frama-C
     * {@code [etiqueta] …} até à primeira linha que não corresponde a esse padrão (código C / ACSL).
     */
    static void stripLeadingFramaCNonCOutput(Path mergedC) throws IOException {
        List<String> lines = Files.readAllLines(mergedC, StandardCharsets.UTF_8);
        int start = 0;
        while (start < lines.size()) {
            String trimmed = lines.get(start).trim();
            if (trimmed.isEmpty()) {
                start++;
                continue;
            }
            if (FRAMA_C_STDOUT_TAG_LINE.matcher(trimmed).matches()) {
                start++;
                continue;
            }
            break;
        }
        if (start == 0) {
            return;
        }
        Files.write(mergedC, lines.subList(start, lines.size()), StandardCharsets.UTF_8);
    }

    /**
     * Coloca o comentário ACSL {@code axiomatic new_types} logo após o preâmbulo (comentário gerado,
     * {@code #include}, linhas em branco), antes dos restantes blocos {@code axiomatic} em ACSL.
     */
    static void moveNewTypesAxiomaticBlockAfterPreamble(Path mergedC) throws IOException {
        String content = Files.readString(mergedC, StandardCharsets.UTF_8);
        int typesKeywordIdx = content.indexOf(AXIOMATIC_NEW_TYPES_MARKER);
        if (typesKeywordIdx < 0) {
            return;
        }
        int blockStart = content.lastIndexOf("/*@", typesKeywordIdx);
        if (blockStart < 0) {
            return;
        }
        int openBrace = content.indexOf('{', typesKeywordIdx);
        if (openBrace < 0) {
            return;
        }
        int closeBrace = AcslCommentSpanScanner.findMatchingBrace(content, openBrace);
        if (closeBrace < 0) {
            return;
        }
        int commentEnd = content.indexOf("*/", closeBrace);
        if (commentEnd < 0) {
            return;
        }
        int blockEnd = commentEnd + 2;
        blockEnd = AcslCommentSpanScanner.skipNewlineAfter(blockEnd, content);

        String block = content.substring(blockStart, blockEnd);
        String without = content.substring(0, blockStart) + content.substring(blockEnd);
        int insertAt = AcslCommentSpanScanner.findPreambleInsertIndex(without);
        String sepBefore =
                insertAt > 0 && without.charAt(insertAt - 1) != '\n' ? "\n" : "";
        String sepAfter = block.endsWith("\n") ? "" : "\n";
        String result = without.substring(0, insertAt) + sepBefore + block + sepAfter + without.substring(insertAt);
        Files.writeString(mergedC, result, StandardCharsets.UTF_8);
    }

    private static final Pattern TUPLE_TYPES_AXIOMATIC_HEADER =
            Pattern.compile("axiomatic\\s+\\w*_tuple_types\\s*\\{");

    /**
     * Move cada bloco {@code axiomatic <machine>_tuple_types { ... }} (ver
     * {@code AcslGenerator#generateAcsl}/{@code TupleCodomainTypeRegistry}, tipos de codomínio-tupla
     * descobertos por máquina) para logo após {@code axiomatic new_types} — que
     * {@link #moveNewTypesAxiomaticBlockAfterPreamble} já fixou no preâmbulo. Sem isto, o bloco fica
     * onde {@code B2ACSLPipeline#reorderLibAxiomaticBlocksPerAcslLibIncludesOrder} o deixar (não é um nome
     * conhecido de {@code AcslLibIncludes}, cai numa posição tardia arbitrária) — tarde demais para
     * os blocos genéricos da lib (ex. {@code Relation_domain}, {@code tuple_couple}) que a
     * referenciam já monomorphizados para este tipo, dando {@code no such type} no Frama-C.
     */
    static void moveTupleCodomainAxiomaticBlocksAfterNewTypes(Path mergedC) throws IOException {
        String content = Files.readString(mergedC, StandardCharsets.UTF_8);
        int newTypesIdx = content.indexOf(AXIOMATIC_NEW_TYPES_MARKER);
        if (newTypesIdx < 0) {
            return;
        }
        int newTypesOpenBrace = content.indexOf('{', newTypesIdx);
        if (newTypesOpenBrace < 0) {
            return;
        }
        int newTypesCloseBrace = AcslCommentSpanScanner.findMatchingBrace(content, newTypesOpenBrace);
        if (newTypesCloseBrace < 0) {
            return;
        }
        int newTypesCommentEnd = content.indexOf("*/", newTypesCloseBrace);
        if (newTypesCommentEnd < 0) {
            return;
        }
        int insertAt = AcslCommentSpanScanner.skipNewlineAfter(newTypesCommentEnd + 2, content);

        boolean changed = false;
        Matcher m = TUPLE_TYPES_AXIOMATIC_HEADER.matcher(content);
        while (m.find(insertAt)) {
            int headerIdx = m.start();
            int blockStart = content.lastIndexOf("/*@", headerIdx);
            if (blockStart < 0 || blockStart < insertAt) {
                break;
            }
            int openBrace = content.indexOf('{', headerIdx);
            if (openBrace < 0) break;
            int closeBrace = AcslCommentSpanScanner.findMatchingBrace(content, openBrace);
            if (closeBrace < 0) break;
            int commentEnd = content.indexOf("*/", closeBrace);
            if (commentEnd < 0) break;
            int blockEnd = AcslCommentSpanScanner.skipNewlineAfter(commentEnd + 2, content);

            if (blockStart == insertAt) {
                insertAt = blockEnd;
                m = TUPLE_TYPES_AXIOMATIC_HEADER.matcher(content);
                continue;
            }

            String block = content.substring(blockStart, blockEnd);
            String without = content.substring(0, blockStart) + content.substring(blockEnd);
            String sepBefore = insertAt > 0 && without.charAt(insertAt - 1) != '\n' ? "\n" : "";
            String sepAfter = block.endsWith("\n") ? "" : "\n";
            content = without.substring(0, insertAt) + sepBefore + block + sepAfter + without.substring(insertAt);
            insertAt = insertAt + sepBefore.length() + block.length() + sepAfter.length();
            changed = true;
            m = TUPLE_TYPES_AXIOMATIC_HEADER.matcher(content);
        }
        if (changed) {
            Files.writeString(mergedC, content, StandardCharsets.UTF_8);
        }
    }
}
