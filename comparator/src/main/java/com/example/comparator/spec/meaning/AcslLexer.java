package com.example.comparator.spec.meaning;

import java.util.ArrayList;
import java.util.List;

/**
 * Tokens of one ACSL clause or declaration (comments are already gone). {@code <<} and {@code >>}
 * are not tokens of their own: {@code Set<Tuple<A,B>>} must close two type argument lists, so the
 * parser joins two adjacent {@code <}/{@code >} into a shift where an operator is expected.
 */
final class AcslLexer {

    enum Type {
        IDENT,
        LITERAL,
        OP,
        EOF
    }

    record Token(Type type, String text, int start, int end, boolean spaceBefore) {

        boolean is(String op) {
            return type == Type.OP && text.equals(op);
        }

        boolean isWord() {
            return type == Type.IDENT || type == Type.LITERAL;
        }
    }

    /** Raised for any text this parser does not understand; callers fall back to a textual form. */
    static final class ParseException extends RuntimeException {
        ParseException(String message) {
            super(message);
        }
    }

    /** Longest first, so that {@code <==>} wins over {@code <=} and {@code ==>} over {@code ==}. */
    private static final List<String> OPERATORS =
            List.of(
                    "<==>", "<-->", "==>", "-->", "[|", "|]", "&&", "||", "^^", "==", "!=", "<=", ">=", "->", "..",
                    "::", "<", ">", "+", "-", "*", "/", "%", "!", "~", "&", "|", "^", "?", ":", ",", ";", "(", ")",
                    "[", "]", "{", "}", ".", "=", "@", "$", "#");

    private AcslLexer() {}

    static List<Token> lex(String s) {
        List<Token> out = new ArrayList<>();
        int i = 0;
        boolean space = false;
        int n = s.length();
        while (i < n) {
            char c = s.charAt(i);
            if (Character.isWhitespace(c)) {
                space = true;
                i++;
                continue;
            }
            int start = i;
            if (isIdentStart(c) || (c == '\\' && i + 1 < n && isIdentStart(s.charAt(i + 1)))) {
                i++;
                while (i < n && isIdentPart(s.charAt(i))) {
                    i++;
                }
                out.add(new Token(Type.IDENT, s.substring(start, i), start, i, space));
            } else if (Character.isDigit(c)) {
                i = number(s, i);
                out.add(new Token(Type.LITERAL, s.substring(start, i), start, i, space));
            } else if (c == '"' || c == '\'') {
                i = quoted(s, i);
                out.add(new Token(Type.LITERAL, s.substring(start, i), start, i, space));
            } else {
                String op = operatorAt(s, i);
                if (op == null) {
                    throw new ParseException("unexpected character '" + c + "'");
                }
                i += op.length();
                out.add(new Token(Type.OP, op, start, i, space));
            }
            space = false;
        }
        out.add(new Token(Type.EOF, "", n, n, space));
        return out;
    }

    private static int number(String s, int i) {
        int n = s.length();
        i++;
        while (i < n) {
            char d = s.charAt(i);
            if (isIdentPart(d)) {
                i++;
            } else if (d == '.' && i + 1 < n && Character.isDigit(s.charAt(i + 1))) {
                i++; // decimal point, never the ".." of a range
            } else {
                break;
            }
        }
        return i;
    }

    private static int quoted(String s, int i) {
        char quote = s.charAt(i);
        i++;
        while (i < s.length() && s.charAt(i) != quote) {
            i += s.charAt(i) == '\\' ? 2 : 1;
        }
        if (i >= s.length()) {
            throw new ParseException("unterminated literal");
        }
        return i + 1;
    }

    private static String operatorAt(String s, int i) {
        for (String op : OPERATORS) {
            if (s.startsWith(op, i)) {
                return op;
            }
        }
        return null;
    }

    static boolean isIdentStart(char c) {
        return Character.isLetter(c) || c == '_';
    }

    static boolean isIdentPart(char c) {
        return Character.isLetterOrDigit(c) || c == '_';
    }
}
