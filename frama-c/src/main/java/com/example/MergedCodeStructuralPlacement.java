package com.example;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Remove ruído de log do Frama-C no topo de {@code merged_code.c}. Extraído de {@code
 * B2ACSLPipeline} (WMC=607) por extract-class puro: nenhuma linha de lógica mudou, só o arquivo em
 * que vive.
 *
 * <p>Os outros dois métodos que viviam aqui — {@code moveNewTypesAxiomaticBlockAfterPreamble} e
 * {@code moveTupleCodomainAxiomaticBlocksAfterNewTypes} — foram retirados na Fase B do plano de
 * padronização de ordem de axiomáticas ({@code
 * plans/execute-o-projeto-cv-sets-squishy-patterson.md}): {@link AxiomaticTierSorter} já garante,
 * por construção, que {@code new_types} e os blocos {@code *_tuple_types} (ambos TIPO puro, ver
 * {@link AxiomaticBlockClassifier}) ficam no início da camada TIPO, sem precisar de um passe
 * dedicado para cada um.
 */
final class MergedCodeStructuralPlacement {

    private MergedCodeStructuralPlacement() {}

    /**
     * Linhas de diagnóstico que o Frama-C escreve no stdout antes do C gerado com {@code -print}
     * (ex.: {@code [kernel] Parsing ...}, {@code [acsl-import] Success ...}).
     */
    private static final Pattern FRAMA_C_STDOUT_TAG_LINE =
            Pattern.compile("^\\[[^\\]]+\\]\\s*.*");

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
}
