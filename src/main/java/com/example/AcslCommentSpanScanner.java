package com.example;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Infraestrutura de varredura de blocos {@code /*@ ... *&#47;} em {@code merged_code.c}, partilhada
 * por vários passes de pós-processamento de {@link B2ACSLPipeline} (reordenação de blocos
 * axiomáticos, posicionamento estrutural, etc.). Extraído de {@code B2ACSLPipeline} (WMC=607) por
 * extract-class puro: nenhuma linha de lógica mudou, só o arquivo em que vive.
 */
final class AcslCommentSpanScanner {

    private AcslCommentSpanScanner() {}

    private static final Pattern AXIOMATIC_NAME_IN_ACSL_COMMENT =
            Pattern.compile("axiomatic\\s+(\\w+)");

    static List<AcsCommentSpan> findAllAcsCommentSpans(String content) {
        List<AcsCommentSpan> spans = new ArrayList<>();
        int from = 0;
        while (from < content.length()) {
            int start = content.indexOf("/*@", from);
            if (start < 0) {
                break;
            }
            int close = content.indexOf("*/", start + 3);
            if (close < 0) {
                break;
            }
            int end = skipNewlineAfter(close + 2, content);
            String text = content.substring(start, end);
            Matcher m = AXIOMATIC_NAME_IN_ACSL_COMMENT.matcher(text);
            String axName = m.find() ? m.group(1) : null;
            spans.add(new AcsCommentSpan(start, end, text, axName));
            from = end;
        }
        return spans;
    }

    static int skipNewlineAfter(int pos, String s) {
        int i = pos;
        while (i < s.length()) {
            char ch = s.charAt(i);
            if (ch == '\n') {
                return i + 1;
            }
            if (ch == '\r') {
                i++;
                if (i < s.length() && s.charAt(i) == '\n') {
                    i++;
                }
                return i;
            }
            break;
        }
        return i;
    }

    static int findMatchingBrace(String s, int openIdx) {
        int depth = 0;
        for (int i = openIdx; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return i;
                }
            }
        }
        return -1;
    }

    /** Índice do primeiro {@code /*@} após o preâmbulo inicial; se não houver, o fim do texto. */
    static int findPreambleInsertIndex(String s) {
        int i = 0;
        while (i < s.length()) {
            int lineStart = i;
            int nl = s.indexOf('\n', i);
            int lineEnd = nl < 0 ? s.length() : nl + 1;
            String line = s.substring(i, lineEnd);
            String left = line.stripLeading();
            if (left.startsWith("/*@")) {
                return lineStart;
            }
            String t = line.strip();
            if (t.isEmpty()
                    || t.startsWith("#include")
                    || t.startsWith("/* Generated")
                    || t.startsWith("//")) {
                i = lineEnd;
                continue;
            }
            i = lineEnd;
        }
        return s.length();
    }
}
