package com.example.analysis;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Estima {@code n} para {@code frama-c -ulevel n} a partir de laços {@code while}/{@code for} em C
 * fundido: {@code n = tamanho_do_maior_laco + 1}, em que o tamanho é o número máximo de iterações
 * quando o limite é constante, ou o número de instruções no corpo do laço caso contrário.
 */
public final class LoopUnrollLevelEstimator {

    private static final Pattern CONDITION_LT_INT =
            Pattern.compile("\\b\\w+\\s*<\\s*(\\d+)\\b");
    private static final Pattern CONDITION_LE_INT =
            Pattern.compile("\\b\\w+\\s*<=\\s*(\\d+)\\b");
    private static final Pattern CONDITION_GT_INT =
            Pattern.compile("\\b(\\d+)\\s*>\\s*\\w+\\b");
    private static final Pattern CONDITION_GE_INT =
            Pattern.compile("\\b(\\d+)\\s*>=\\s*\\w+\\b");

    private LoopUnrollLevelEstimator() {}

    /**
     * @return {@code maxLoopSize + 1} com mínimo {@code 1}; {@code 1} se não houver laços
     */
    public static int computeUlevel(Path mergedC) throws IOException {
        if (mergedC == null || !Files.isRegularFile(mergedC)) {
            return 1;
        }
        String src = Files.readString(mergedC, StandardCharsets.UTF_8);
        return computeUlevelFromSource(src);
    }

    static int computeUlevelFromSource(String source) {
        if (source == null || source.isBlank()) {
            return 1;
        }
        String code = stripComments(source);
        List<LoopSite> loops = findLoops(code);
        if (loops.isEmpty()) {
            return 1;
        }
        int maxSize = 0;
        for (LoopSite loop : loops) {
            maxSize = Math.max(maxSize, loop.loopSize(code));
        }
        return Math.max(1, maxSize + 1);
    }

    private static final class LoopSite {
        final String condition;
        final int bodyStart;
        final int bodyEnd;

        LoopSite(String condition, int bodyStart, int bodyEnd) {
            this.condition = condition;
            this.bodyStart = bodyStart;
            this.bodyEnd = bodyEnd;
        }

        int loopSize(String code) {
            int fromCondition = estimateIterationsFromCondition(condition);
            if (fromCondition > 0) {
                return fromCondition;
            }
            return countBodyStatements(code, bodyStart, bodyEnd);
        }
    }

    private static final Pattern LOOP_KEYWORD = Pattern.compile("\\b(while|for)\\b");

    private static List<LoopSite> findLoops(String code) {
        List<LoopSite> out = new ArrayList<>();
        Matcher kw = LOOP_KEYWORD.matcher(code);
        while (kw.find()) {
            boolean isFor = "for".equals(kw.group(1));
            int kwStart = kw.start();
            int condOpen = code.indexOf('(', kwStart);
            if (condOpen < 0) {
                continue;
            }
            int condClose = findMatchingParen(code, condOpen);
            if (condClose < 0) {
                continue;
            }
            String condition = code.substring(condOpen + 1, condClose);
            int bodyStart = skipToBlockStart(code, condClose + 1);
            int bodyEnd = bodyStart >= 0 ? findMatchingBrace(code, bodyStart) : -1;
            if (isFor) {
                Integer forBound = estimateForLoopIterations(condition);
                if (forBound != null && forBound > 0) {
                    out.add(new LoopSite("__for_bound_" + forBound, bodyStart, bodyEnd));
                } else if (bodyStart >= 0 && bodyEnd > bodyStart) {
                    out.add(new LoopSite(condition, bodyStart, bodyEnd));
                }
            } else if (bodyStart >= 0 && bodyEnd > bodyStart) {
                out.add(new LoopSite(condition, bodyStart, bodyEnd));
            }
        }
        return out;
    }

    private static Integer estimateForLoopIterations(String forHeader) {
        if (forHeader == null || forHeader.isBlank()) {
            return null;
        }
        String[] parts = forHeader.split(";", -1);
        if (parts.length < 2) {
            return null;
        }
        return parseIterationBound(parts[1].trim());
    }

    private static int estimateIterationsFromCondition(String condition) {
        if (condition == null || condition.isBlank()) {
            return 0;
        }
        if (condition.startsWith("__for_bound_")) {
            try {
                return Integer.parseInt(condition.substring("__for_bound_".length()).trim());
            } catch (NumberFormatException e) {
                return 0;
            }
        }
        Integer bound = parseIterationBound(condition);
        return bound == null ? 0 : bound;
    }

    private static Integer parseIterationBound(String expr) {
        Matcher lt = CONDITION_LT_INT.matcher(expr);
        if (lt.find()) {
            return Integer.parseInt(lt.group(1));
        }
        Matcher le = CONDITION_LE_INT.matcher(expr);
        if (le.find()) {
            return Integer.parseInt(le.group(1)) + 1;
        }
        Matcher gt = CONDITION_GT_INT.matcher(expr);
        if (gt.find()) {
            return Integer.parseInt(gt.group(1));
        }
        Matcher ge = CONDITION_GE_INT.matcher(expr);
        if (ge.find()) {
            return Integer.parseInt(ge.group(1)) + 1;
        }
        return null;
    }

    private static int countBodyStatements(String code, int bodyStart, int bodyEnd) {
        if (bodyStart < 0 || bodyEnd <= bodyStart + 1) {
            return 1;
        }
        String inner = code.substring(bodyStart + 1, bodyEnd).trim();
        if (inner.isEmpty()) {
            return 1;
        }
        int count = 0;
        for (String line : inner.split("\n")) {
            String t = line.trim();
            if (t.isEmpty() || t.equals("{") || t.equals("}")) {
                continue;
            }
            if (t.endsWith(";") || t.endsWith("{") || t.startsWith("return")) {
                count++;
            }
        }
        return Math.max(1, count);
    }

    private static int skipToBlockStart(String code, int from) {
        for (int j = from; j < code.length(); j++) {
            char c = code.charAt(j);
            if (c == '{') {
                return j;
            }
            if (!Character.isWhitespace(c) && c != ';') {
                return -1;
            }
        }
        return -1;
    }

    private static int findMatchingParen(String s, int openIdx) {
        if (openIdx < 0 || openIdx >= s.length() || s.charAt(openIdx) != '(') {
            return -1;
        }
        int depth = 0;
        for (int i = openIdx; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
                if (depth == 0) {
                    return i;
                }
            }
        }
        return -1;
    }

    private static int findMatchingBrace(String s, int openIdx) {
        if (openIdx < 0 || openIdx >= s.length() || s.charAt(openIdx) != '{') {
            return -1;
        }
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

    static String stripComments(String src) {
        StringBuilder sb = new StringBuilder(src.length());
        int i = 0;
        while (i < src.length()) {
            if (i + 1 < src.length() && src.charAt(i) == '/' && src.charAt(i + 1) == '/') {
                i += 2;
                while (i < src.length() && src.charAt(i) != '\n') {
                    i++;
                }
                continue;
            }
            if (i + 1 < src.length() && src.charAt(i) == '/' && src.charAt(i + 1) == '*') {
                i += 2;
                while (i + 1 < src.length()
                        && !(src.charAt(i) == '*' && src.charAt(i + 1) == '/')) {
                    i++;
                }
                i = Math.min(i + 2, src.length());
                continue;
            }
            sb.append(src.charAt(i));
            i++;
        }
        return sb.toString();
    }
}
