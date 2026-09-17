package com.example;

/**
 * Um comentário {@code /*@ ... *&#47;} em {@code merged_code.c} (posição, texto, nome axiomático se
 * aplicável) — usado pelos vários passes de pós-processamento de {@link B2ACSLPipeline} que
 * reordenam/movem blocos axiomáticos. Extraído de {@code B2ACSLPipeline} (WMC=607) por
 * extract-class puro: campos e construtor idênticos, só o arquivo em que vive mudou.
 */
final class AcsCommentSpan {
    final int start;
    final int end;
    final String text;
    final String axiomaticName;

    AcsCommentSpan(int start, int end, String text, String axiomaticName) {
        this.start = start;
        this.end = end;
        this.text = text;
        this.axiomaticName = axiomaticName;
    }
}
