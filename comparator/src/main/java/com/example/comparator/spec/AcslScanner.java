package com.example.comparator.spec;

import java.util.Set;

/**
 * Lexical helpers for the acsl-import draft format ({@code .acsl}) and ACSL annotations: comment
 * removal and statement boundaries that respect parentheses, braces, string literals and the
 * {@code ;} that closes the binder list of {@code \forall}/{@code \exists}/{@code \lambda}/{@code
 * \let}.
 */
final class AcslScanner {

    private static final Set<String> BINDERS = Set.of("forall", "exists", "lambda", "let");

    private AcslScanner() {}

    /** Removes {@code /* ... *}{@code /} and {@code // ...} comments (string literals are kept). */
    static String stripComments(String text) {
        StringBuilder out = new StringBuilder(text.length());
        int i = 0;
        int n = text.length();
        while (i < n) {
            char c = text.charAt(i);
            if (c == '"') {
                int end = skipString(text, i);
                out.append(text, i, end);
                i = end;
            } else if (c == '/' && i + 1 < n && text.charAt(i + 1) == '*') {
                int end = text.indexOf("*/", i + 2);
                i = end < 0 ? n : end + 2;
                out.append(' ');
            } else if (c == '/' && i + 1 < n && text.charAt(i + 1) == '/') {
                int end = text.indexOf('\n', i);
                i = end < 0 ? n : end;
            } else {
                out.append(c);
                i++;
            }
        }
        return out.toString();
    }

    /**
     * End (exclusive) of the statement starting at {@code from}: just after the first {@code ;} at
     * nesting depth 0 that does not close a binder list, or just after a top-level {@code {...}}
     * block (plus an optional {@code ;}), or {@code text.length()}.
     */
    static int statementEnd(String text, int from) {
        int parens = 0;
        int braces = 0;
        int pendingBinders = 0;
        int n = text.length();
        int i = from;
        while (i < n) {
            char c = text.charAt(i);
            switch (c) {
                case '"' -> {
                    i = skipString(text, i);
                    continue;
                }
                case '(', '[' -> parens++;
                case ')', ']' -> parens = Math.max(0, parens - 1);
                case '{' -> braces++;
                case '}' -> {
                    braces = Math.max(0, braces - 1);
                    if (braces == 0 && parens == 0) {
                        int j = skipWhitespace(text, i + 1);
                        return j < n && text.charAt(j) == ';' ? j + 1 : i + 1;
                    }
                }
                case '\\' -> {
                    int j = i + 1;
                    while (j < n && Character.isLetter(text.charAt(j))) {
                        j++;
                    }
                    if (parens == 0 && braces == 0 && BINDERS.contains(text.substring(i + 1, j))) {
                        pendingBinders++;
                    }
                    i = j;
                    continue;
                }
                case ';' -> {
                    if (parens == 0 && braces == 0) {
                        if (pendingBinders == 0) {
                            return i + 1;
                        }
                        pendingBinders--;
                    }
                }
                default -> {
                    // ordinary character
                }
            }
            i++;
        }
        return n;
    }

    /** Index of the {@code }} matching the {@code {} at {@code openIndex}, or {@code -1}. */
    static int matchingBrace(String text, int openIndex) {
        int depth = 0;
        for (int i = openIndex; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '"') {
                i = skipString(text, i) - 1;
            } else if (c == '{') {
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

    static int skipWhitespace(String text, int from) {
        int i = from;
        while (i < text.length() && Character.isWhitespace(text.charAt(i))) {
            i++;
        }
        return i;
    }

    /** Identifier ({@code [A-Za-z_][A-Za-z0-9_]*}) starting at {@code from}, or {@code ""}. */
    static String identifierAt(String text, int from) {
        int i = from;
        while (i < text.length() && (Character.isLetterOrDigit(text.charAt(i)) || text.charAt(i) == '_')) {
            i++;
        }
        return text.substring(from, i);
    }

    private static int skipString(String text, int quoteIndex) {
        int i = quoteIndex + 1;
        while (i < text.length()) {
            char c = text.charAt(i);
            if (c == '\\') {
                i += 2;
                continue;
            }
            if (c == '"') {
                return i + 1;
            }
            i++;
        }
        return text.length();
    }
}
