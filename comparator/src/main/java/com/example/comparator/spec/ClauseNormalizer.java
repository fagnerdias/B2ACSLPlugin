package com.example.comparator.spec;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Normal forms of an ACSL clause, so that two tools that print the same clause differently still
 * match:
 *
 * <ul>
 *   <li>{@link #display}: whitespace collapsed, trailing {@code ;} removed (shown in reports);
 *   <li>{@link #canonical}: additionally drops every space not needed to separate two identifiers,
 *       removes parentheses wrapping a whole clause body, and renames bound variables of {@code
 *       \forall}/{@code \exists}/{@code \lambda}/{@code \let} to {@code _b0}, {@code _b1}, ... in
 *       order of appearance (alpha-renaming).
 * </ul>
 */
public final class ClauseNormalizer {

    private static final Pattern WHITESPACE = Pattern.compile("\\s+");
    private static final Pattern CLAUSE_KEYWORD =
            Pattern.compile(
                    "^(requires|ensures|assigns|assert|check|admit|assumes|decreases|terminates|"
                            + "loop invariant|loop assigns|loop variant|invariant)\\s*(.*)$",
                    Pattern.DOTALL);
    private static final Pattern BINDER = Pattern.compile("\\\\(forall|exists|lambda|let)(?![A-Za-z0-9_])");
    private static final Pattern TRAILING_IDENTIFIER = Pattern.compile("([A-Za-z_][A-Za-z0-9_]*)\\s*$");
    private static final Pattern LEADING_IDENTIFIER = Pattern.compile("^\\s*([A-Za-z_][A-Za-z0-9_]*)");

    private ClauseNormalizer() {}

    public static String display(String clause) {
        String s = WHITESPACE.matcher(clause).replaceAll(" ").trim();
        while (s.endsWith(";")) {
            s = s.substring(0, s.length() - 1).trim();
        }
        return s;
    }

    public static String canonical(String clause) {
        String s = compactSpaces(display(clause));
        s = unwrapClauseBody(s);
        return alphaRename(s);
    }

    /** Keeps a space only between two identifier characters ({@code integer x}, {@code logic T}). */
    static String compactSpaces(String s) {
        StringBuilder out = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == ' ') {
                char prev = out.isEmpty() ? ' ' : out.charAt(out.length() - 1);
                char next = i + 1 < s.length() ? s.charAt(i + 1) : ' ';
                if (isWordChar(prev) && isWordChar(next)) {
                    out.append(' ');
                }
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }

    /** {@code requires(a&&b)} becomes {@code requires a&&b}. */
    private static String unwrapClauseBody(String s) {
        Matcher m = CLAUSE_KEYWORD.matcher(s);
        if (!m.matches()) {
            return s;
        }
        String body = m.group(2);
        while (isWrappedInParens(body)) {
            body = body.substring(1, body.length() - 1);
        }
        return m.group(1) + " " + body;
    }

    static boolean isWrappedInParens(String s) {
        if (s.length() < 2 || s.charAt(0) != '(' || s.charAt(s.length() - 1) != ')') {
            return false;
        }
        int depth = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
                if (depth == 0 && i < s.length() - 1) {
                    return false;
                }
            }
        }
        return depth == 0;
    }

    static String alphaRename(String s) {
        String result = s;
        int counter = 0;
        int searchFrom = 0;
        while (true) {
            Matcher binder = BINDER.matcher(result);
            if (!binder.find(searchFrom)) {
                return result;
            }
            int bindersStart = binder.end();
            int bindersEnd = result.indexOf(';', bindersStart);
            if (bindersEnd < 0) {
                return result;
            }
            String binderList = result.substring(bindersStart, bindersEnd);
            String head = result.substring(0, bindersStart);
            String tail = result.substring(bindersStart);
            for (String var : boundVariables(binder.group(1), binderList)) {
                String fresh = "_b" + counter++;
                tail = tail.replaceAll("(?<![A-Za-z0-9_\\\\.])" + Pattern.quote(var) + "(?![A-Za-z0-9_])", fresh);
            }
            result = head + tail;
            searchFrom = bindersStart;
        }
    }

    private static java.util.List<String> boundVariables(String binderKind, String binderList) {
        java.util.List<String> vars = new java.util.ArrayList<>();
        if ("let".equals(binderKind)) {
            int eq = binderList.indexOf('=');
            Matcher m = LEADING_IDENTIFIER.matcher(eq < 0 ? binderList : binderList.substring(0, eq));
            if (m.find()) {
                vars.add(m.group(1));
            }
            return vars;
        }
        for (String part : splitTopLevelCommas(binderList)) {
            Matcher m = TRAILING_IDENTIFIER.matcher(part);
            if (m.find()) {
                vars.add(m.group(1));
            }
        }
        return vars;
    }

    /** Splits on commas outside {@code <...>} and {@code (...)} (e.g. {@code Set<Tuple<A,B>> s, integer i}). */
    private static java.util.List<String> splitTopLevelCommas(String s) {
        java.util.List<String> parts = new java.util.ArrayList<>();
        int depth = 0;
        int start = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '<' || c == '(') {
                depth++;
            } else if (c == '>' || c == ')') {
                depth--;
            } else if (c == ',' && depth == 0) {
                parts.add(s.substring(start, i));
                start = i + 1;
            }
        }
        parts.add(s.substring(start));
        return parts;
    }

    private static boolean isWordChar(char c) {
        return Character.isLetterOrDigit(c) || c == '_';
    }
}
