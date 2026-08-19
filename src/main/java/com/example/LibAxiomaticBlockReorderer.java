package com.example;

import java.util.Map;

/**
 * {@code libAxiomaticRank}: usado por {@link AxiomaticTierSorter} para ordenar blocos ASSINATURA
 * de biblioteca pela mesma ordem de {@link AcslLibIncludes#FILE_ORDER}.
 *
 * <p>Os 8 sub-passes que davam nome a esta classe (reordenação heurística por NOME de blocos
 * axiomáticos de biblioteca/máquina em {@code merged_code.c}) foram retirados na Fase B do plano
 * de padronização de ordem de axiomáticas ({@code
 * plans/execute-o-projeto-cv-sets-squishy-patterson.md}): {@link AxiomaticTierSorter} os substitui
 * por uma classificação de CONTEÚDO ({@link AxiomaticBlockClassifier}) partilhada, validada sem
 * regressão na suite completa de 17 exemplos antes da remoção.
 */
final class LibAxiomaticBlockReorderer {

    private LibAxiomaticBlockReorderer() {}

    /**
     * Rank de {@code axiomaticName} em {@link AcslLibIncludes#FILE_ORDER} (via {@code rank}, já
     * calculado a partir de {@link AcslLibIncludes#orderedLibFunctionAxiomaticNames()}) — direto,
     * ou pelo nome-base sem sufixo {@code _axioms} (ex.: {@code range_axioms} -> {@code
     * range_function}). {@link Integer#MAX_VALUE} se não for um bloco de biblioteca conhecido
     * (empurra para o fim da ordenação por rank, nunca para erro).
     */
    static int libAxiomaticRank(Map<String, Integer> rank, String axiomaticName) {
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
}
