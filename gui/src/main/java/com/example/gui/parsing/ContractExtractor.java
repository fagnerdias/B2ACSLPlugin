package com.example.gui.parsing;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Extracts the ACSL contract and the signature of one function from a merged C file (Frama-C
 * {@code -print} output): the {@code /*@ ... *&#47;} block right above the function definition
 * (or, when the function has no body, above its prototype), one entry per clause.
 */
public final class ContractExtractor {

    /**
     * One clause (or the signature), whitespace-normalised.
     *
     * @param fileLine 1-based line of the clause start in the merged file
     */
    public record ClauseLine(int fileLine, String text) {}

    /**
     * @param found {@code false} when the function does not exist in the file
     * @param openLine line of {@code /*@}, {@code -1} when there is no contract
     * @param closeLine line of the closing comment, {@code -1} when there is no contract
     */
    public record Contract(
            String function, boolean found, List<ClauseLine> clauses, ClauseLine signature, int openLine, int closeLine) {

        public Contract {
            clauses = List.copyOf(clauses);
        }

        public boolean hasContract() {
            return openLine > 0;
        }

        static Contract missing(String function) {
            return new Contract(function, false, List.of(), null, -1, -1);
        }
    }

    private ContractExtractor() {}

    public static Contract extract(Path mergedFile, String function) throws IOException {
        return extract(Files.readAllLines(mergedFile, StandardCharsets.ISO_8859_1), function);
    }

    public static Contract extract(List<String> lines, String function) {
        Pattern declaration =
                Pattern.compile("^[A-Za-z_][^;(]*\\b" + Pattern.quote(function) + "\\s*\\(.*$");
        int definition = -1;
        int prototype = -1;
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            if (!declaration.matcher(line).matches()) {
                continue;
            }
            String t = line.trim();
            if (t.endsWith(";")) {
                if (prototype < 0) {
                    prototype = i;
                }
            } else if (t.endsWith("{") || nextNonBlankIsBrace(lines, i)) {
                definition = i;
                break;
            }
        }
        int at = definition >= 0 ? definition : prototype;
        if (at < 0) {
            return Contract.missing(function);
        }
        ClauseLine signature = new ClauseLine(at + 1, signatureText(lines.get(at)));
        Contract own = contractAbove(lines, at, function, signature);
        if (!own.hasContract() && definition >= 0 && prototype >= 0) {
            Contract onPrototype = contractAbove(lines, prototype, function, signature);
            if (onPrototype.hasContract()) {
                return onPrototype;
            }
        }
        return own;
    }

    private static Contract contractAbove(List<String> lines, int at, String function, ClauseLine signature) {
        int end = at - 1;
        while (end >= 0 && lines.get(end).isBlank()) {
            end--;
        }
        if (end < 0 || !lines.get(end).trim().endsWith("*/")) {
            return new Contract(function, true, List.of(), signature, -1, -1);
        }
        int start = end;
        while (start >= 0 && !lines.get(start).contains("/*@")) {
            if (start < end && lines.get(start).contains("*/")) {
                return new Contract(function, true, List.of(), signature, -1, -1);
            }
            start--;
        }
        if (start < 0) {
            return new Contract(function, true, List.of(), signature, -1, -1);
        }
        return new Contract(function, true, clauses(lines, start, end), signature, start + 1, end + 1);
    }

    /** Splits the block {@code [start, end]} into clauses ending at {@code ;} outside brackets. */
    static List<ClauseLine> clauses(List<String> lines, int start, int end) {
        List<ClauseLine> out = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        int currentLine = -1;
        int depth = 0;
        for (int i = start; i <= end; i++) {
            String line = lines.get(i);
            if (i == start) {
                line = line.substring(line.indexOf("/*@") + 3);
            }
            if (i == end) {
                int close = line.lastIndexOf("*/");
                line = close >= 0 ? line.substring(0, close) : line;
            }
            String body = line.stripLeading();
            if (body.startsWith("@")) {
                body = body.substring(1);
            }
            for (int k = 0; k < body.length(); k++) {
                char c = body.charAt(k);
                if (current.toString().isBlank() && !Character.isWhitespace(c)) {
                    currentLine = i + 1;
                }
                current.append(c);
                if (c == '(' || c == '{' || c == '[') {
                    depth++;
                } else if ((c == ')' || c == '}' || c == ']') && depth > 0) {
                    depth--;
                } else if (c == ';' && depth == 0) {
                    out.add(new ClauseLine(currentLine, normalize(current.toString())));
                    current.setLength(0);
                }
            }
            String pending = current.toString().trim();
            if (depth == 0 && pending.endsWith(":") && (pending.startsWith("behavior") || pending.startsWith("for "))) {
                out.add(new ClauseLine(currentLine, normalize(pending)));
                current.setLength(0);
            } else {
                current.append(' ');
            }
        }
        String rest = normalize(current.toString());
        if (!rest.isEmpty()) {
            out.add(new ClauseLine(currentLine, rest));
        }
        return out;
    }

    /** Collapses runs of whitespace and trims. */
    public static String normalize(String text) {
        return text.replaceAll("\\s+", " ").trim();
    }

    private static String signatureText(String line) {
        String t = line.trim();
        if (t.endsWith("{")) {
            t = t.substring(0, t.length() - 1).trim();
        }
        if (!t.endsWith(";")) {
            t = t + ";";
        }
        return normalize(t);
    }

    private static boolean nextNonBlankIsBrace(List<String> lines, int i) {
        for (int k = i + 1; k < lines.size(); k++) {
            String t = lines.get(k).trim();
            if (!t.isEmpty()) {
                return t.startsWith("{");
            }
        }
        return false;
    }
}
