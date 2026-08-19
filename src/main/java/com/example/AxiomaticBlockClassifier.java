package com.example;

import java.util.regex.Pattern;

/**
 * Classifica o CONTEÚDO de um bloco {@code /*@ ... *&#47;} (axiomático ou predicado/lógica
 * standalone) numa das 3 camadas do plano de padronização de ordem — TIPO, ASSINATURA, AXIOMA —
 * ou MISTO quando o mesmo bloco intercala declarações de assinatura ({@code logic}/
 * {@code predicate}) com {@code axiom}/{@code lemma}. Base de {@link AxiomaticTierSorter} (Fase A
 * do plano em {@code plans/execute-o-projeto-cv-sets-squishy-patterson.md}): substitui as
 * heurísticas de NOME dispersas pelos vários passes antigos ({@code .endsWith("_axioms")}, etc.)
 * por uma classificação baseada no CONTEÚDO real do bloco.
 */
final class AxiomaticBlockClassifier {

    private AxiomaticBlockClassifier() {}

    enum Tier {
        TYPE,
        SIGNATURE,
        AXIOM,
        MIXED,
        /** Bloco sem nenhuma das 3 formas reconhecidas (ex.: comentário solto) — fora do sort. */
        OTHER
    }

    /** {@code type X = Y;} / {@code type X;} — só reconhecido no início de linha (evita casar
     * a palavra "type" dentro de um comentário ou identificador). */
    private static final Pattern TYPE_DECL = Pattern.compile("(?m)^\\s*type\\s+\\w");

    /** {@code logic}/{@code predicate}, mesma forma usada pelos passes antigos (linha própria). */
    private static final Pattern SIGNATURE_DECL = Pattern.compile("(?m)^\\s*(logic|predicate)\\s+");

    /** {@code axiom}/{@code lemma} (incl. {@code admit lemma}) — só no início de linha, para não
     * confundir com "axiomatic" (bloqueado por \\b) nem com a palavra aparecendo dentro do corpo
     * de uma expressão/comentário no meio da linha. */
    private static final Pattern AXIOM_DECL =
            Pattern.compile("(?m)^\\s*(?:admit\\s+)?(axiom|lemma)\\b");

    static Tier classify(String blockText) {
        boolean hasSignature = SIGNATURE_DECL.matcher(blockText).find();
        boolean hasAxiom = AXIOM_DECL.matcher(blockText).find();
        if (hasSignature && hasAxiom) {
            return Tier.MIXED;
        }
        if (hasAxiom) {
            return Tier.AXIOM;
        }
        if (hasSignature) {
            return Tier.SIGNATURE;
        }
        if (TYPE_DECL.matcher(blockText).find()) {
            return Tier.TYPE;
        }
        return Tier.OTHER;
    }
}
