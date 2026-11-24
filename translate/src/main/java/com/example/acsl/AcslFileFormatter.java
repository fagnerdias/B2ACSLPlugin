package com.example.acsl;

import com.example.acsl.AcslLexer.ParseException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Reescreve as expressões dos {@code .acsl} gerados e do {@code ghost_operations.ci} na convenção
 * de parênteses de {@link AcslPrinter} (a do printer do rules). Só o corpo de cada cláusula,
 * definição, axioma ou lema muda; comentários, palavras-chave, cabeçalhos, listas {@code reads} e o
 * espaço antes do corpo ficam como estão. Um corpo que não se consegue ler fica intacto e vira
 * aviso.
 */
public final class AcslFileFormatter {

    /** Cláusulas cujo corpo é um termo ou predicado. */
    private static final Set<String> CLAUSES =
            Set.of("requires", "ensures", "assumes", "assert", "check", "decreases", "loop invariant", "loop variant");
    /** Cláusulas cujo corpo é uma lista de locais. */
    private static final Set<String> LOCATION_CLAUSES = Set.of("assigns", "loop assigns");
    private static final Set<String> BINDERS = Set.of("forall", "exists", "lambda", "let");
    private static final Pattern GHOST_CLAUSE =
            Pattern.compile("^(\\s*(?:/@|@)\\s*)(requires|ensures|assumes|assigns|decreases)(\\s+)(.*?)(;\\s*)$");
    private static final Pattern WORD = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

    private final List<String> warnings = new ArrayList<>();

    private AcslFileFormatter() {}

    /** Formata os {@code .acsl} do topo de {@code acslDir} (sem a biblioteca nem {@code b_pow.acsl}) e o ghost. */
    public static List<String> formatGenerated(Path acslDir, Path ghostCi) throws IOException {
        AcslFileFormatter f = new AcslFileFormatter();
        if (acslDir != null && Files.isDirectory(acslDir)) {
            List<Path> files;
            try (Stream<Path> s = Files.list(acslDir)) {
                files = s.filter(p -> Files.isRegularFile(p) && p.toString().endsWith(".acsl"))
                        .filter(p -> !p.getFileName().toString().equals("b_pow.acsl"))
                        .sorted()
                        .toList();
            }
            for (Path p : files) {
                String text = Files.readString(p, StandardCharsets.UTF_8);
                String out = f.acsl(text, p.getFileName().toString());
                if (!out.equals(text)) Files.writeString(p, out, StandardCharsets.UTF_8);
            }
        }
        if (ghostCi != null && Files.isRegularFile(ghostCi)) {
            String text = Files.readString(ghostCi, StandardCharsets.UTF_8);
            String out = f.ghost(text, ghostCi.getFileName().toString());
            if (!out.equals(text)) Files.writeString(ghostCi, out, StandardCharsets.UTF_8);
        }
        return f.warnings;
    }

    /** Texto de um {@code .acsl}; os avisos vão para {@code warnings}. */
    public static String formatAcsl(String text, List<String> warnings) {
        AcslFileFormatter f = new AcslFileFormatter();
        String out = f.acsl(text, "<acsl>");
        warnings.addAll(f.warnings);
        return out;
    }

    /** Texto de um {@code ghost_operations.ci}; os avisos vão para {@code warnings}. */
    public static String formatGhost(String text, List<String> warnings) {
        AcslFileFormatter f = new AcslFileFormatter();
        String out = f.ghost(text, "<ghost>");
        warnings.addAll(f.warnings);
        return out;
    }

    // ---- .acsl --------------------------------------------------------------------------------

    private String acsl(String text, String where) {
        StringBuilder out = new StringBuilder(text.length());
        region(text, 0, text.length(), false, out, where);
        return out.toString();
    }

    /** Statements de {@code text[from, to)}; {@code inAxiomatic} muda a regra do corpo de predicate. */
    private void region(String text, int from, int to, boolean inAxiomatic, StringBuilder out, String where) {
        int i = from;
        while (i < to) {
            char c = text.charAt(i);
            if (Character.isWhitespace(c)) {
                out.append(c);
                i++;
                continue;
            }
            if (text.startsWith("/*", i)) {
                int end = text.indexOf("*/", i + 2);
                end = end < 0 || end + 2 > to ? to : end + 2;
                out.append(text, i, end);
                i = end;
                continue;
            }
            if (text.startsWith("//", i)) {
                int end = text.indexOf('\n', i);
                end = end < 0 || end > to ? to : end;
                out.append(text, i, end);
                i = end;
                continue;
            }
            String word = wordAt(text, i);
            if (word == null) {
                int end = Math.min(statementEnd(text, i), to);
                out.append(text, i, end);
                i = end;
                continue;
            }
            switch (word) {
                case "axiomatic" -> {
                    int open = text.indexOf('{', i);
                    int close = open < 0 ? -1 : matchingBrace(text, open);
                    if (open < 0 || close < 0 || close >= to) {
                        out.append(text, i, to);
                        return;
                    }
                    out.append(text, i, open + 1);
                    region(text, open + 1, close, true, out, where);
                    out.append('}');
                    i = close + 1;
                }
                case "function", "contract", "at", "behavior" -> {
                    // "function F:", "contract:", "at loop 1:", "at return:" — até o ':' do rótulo
                    int colon = labelColon(text, i, to);
                    out.append(text, i, colon);
                    i = colon;
                }
                default -> {
                    int end = Math.min(statementEnd(text, i), to);
                    out.append(statement(text.substring(i, end), inAxiomatic, where));
                    i = end;
                }
            }
        }
    }

    /** Um statement completo (com o ';' final, se houver). */
    private String statement(String st, boolean inAxiomatic, String where) {
        String trimmedEnd = st.endsWith(";") ? st.substring(0, st.length() - 1) : st;
        String semi = st.endsWith(";") ? ";" : "";
        String keyword = clauseKeyword(trimmedEnd);
        if (keyword != null) {
            int bodyStart = skipWhitespace(trimmedEnd, keyword.length());
            String head = trimmedEnd.substring(0, bodyStart);
            String body = trimmedEnd.substring(bodyStart);
            String formatted = LOCATION_CLAUSES.contains(keyword) ? locations(body, where) : term(body, where, 0);
            return formatted == null ? st : head + formatted + semi;
        }
        String first = wordAt(trimmedEnd, 0);
        if (first == null) return st;
        switch (first) {
            case "predicate", "logic" -> {
                int eq;
                try {
                    eq = AcslParser.header(stripComments(trimmedEnd)).bodyOffset();
                } catch (ParseException e) {
                    warn(where, trimmedEnd, e);
                    return st;
                }
                if (eq < 0 || hasComment(trimmedEnd)) return st;
                int bodyStart = skipWhitespace(trimmedEnd, eq);
                int mode = first.equals("logic") ? 2 : (inAxiomatic ? 0 : 1);
                String formatted = term(trimmedEnd.substring(bodyStart), where, mode);
                return formatted == null ? st : trimmedEnd.substring(0, bodyStart) + formatted + semi;
            }
            case "axiom", "lemma", "admit", "check" -> {
                int colon = trimmedEnd.indexOf(':');
                if (colon < 0) return st;
                int bodyStart = skipWhitespace(trimmedEnd, colon + 1);
                String formatted = term(trimmedEnd.substring(bodyStart), where, 0);
                return formatted == null ? st : trimmedEnd.substring(0, bodyStart) + formatted + semi;
            }
            default -> {
                return st;
            }
        }
    }

    /** Palavra-chave de cláusula no início ({@code loop invariant} conta como uma). */
    private static String clauseKeyword(String st) {
        String w = wordAt(st, 0);
        if (w == null) return null;
        if (w.equals("loop")) {
            int j = skipWhitespace(st, 4);
            String w2 = wordAt(st, j);
            if (w2 == null) return null;
            String k = "loop " + w2;
            if (!CLAUSES.contains(k) && !LOCATION_CLAUSES.contains(k)) return null;
            // mantém o espaço original entre "loop" e a segunda palavra
            return st.substring(0, j + w2.length()).equals("loop " + w2) ? k : null;
        }
        return CLAUSES.contains(w) || LOCATION_CLAUSES.contains(w) ? w : null;
    }

    // ---- ghost_operations.ci ------------------------------------------------------------------

    private String ghost(String text, String where) {
        StringBuilder out = new StringBuilder(text.length());
        int i = 0;
        while (i < text.length()) {
            int open = text.indexOf("/*@", i);
            if (open < 0) {
                out.append(text, i, text.length());
                break;
            }
            int close = text.indexOf("*/", open + 3);
            if (close < 0) {
                out.append(text, i, text.length());
                break;
            }
            out.append(text, i, open + 3);
            String inner = text.substring(open + 3, close);
            if (inner.stripLeading().startsWith("ghost")) {
                out.append(ghostClauses(inner, where));
            } else {
                out.append(acsl(inner, where));
            }
            out.append("*/");
            i = close + 2;
        }
        return out.toString();
    }

    /** Linhas {@code @ ensures ...;} do contrato de uma função ghost. */
    private String ghostClauses(String block, String where) {
        String[] lines = block.split("\n", -1);
        StringBuilder out = new StringBuilder(block.length());
        for (int k = 0; k < lines.length; k++) {
            String line = lines[k];
            Matcher m = GHOST_CLAUSE.matcher(line);
            if (m.matches()) {
                String body = m.group(4);
                String formatted = m.group(2).equals("assigns") ? locations(body, where) : term(body, where, 0);
                if (formatted != null) {
                    line = m.group(1) + m.group(2) + m.group(3) + formatted + m.group(5);
                }
            }
            out.append(line);
            if (k < lines.length - 1) out.append('\n');
        }
        return out.toString();
    }

    // ---- corpos -------------------------------------------------------------------------------

    /**
     * Corpo reimpresso; {@code null} se não se consegue ler. {@code mode}: 0 cláusula/axioma/predicate
     * num axiomatic, 1 predicate fora de axiomatic, 2 corpo de logic.
     */
    private String term(String body, String where, int mode) {
        if (body.isBlank() || hasComment(body)) return null;
        try {
            Expr e = AcslParser.parse(body);
            return switch (mode) {
                case 1 -> AcslPrinter.printTopLevelPredicateBody(e);
                case 2 -> AcslPrinter.printLogicBody(e);
                default -> AcslPrinter.print(e);
            };
        } catch (ParseException | IndexOutOfBoundsException e) {
            warn(where, body, e);
            return null;
        }
    }

    /** {@code a, b[0 .. n], *(p + (0 .. 9))}: cada local reimpresso. */
    private String locations(String body, String where) {
        if (body.isBlank() || hasComment(body)) return null;
        List<String> parts = splitTopLevel(body);
        List<String> printed = new ArrayList<>();
        for (String part : parts) {
            String f = term(part.trim(), where, 0);
            if (f == null) return null;
            printed.add(f);
        }
        return String.join(", ", printed);
    }

    private void warn(String where, String text, Exception e) {
        String t = text.replaceAll("\\s+", " ").trim();
        warnings.add(where + ": " + e.getMessage() + " — " + (t.length() > 160 ? t.substring(0, 160) + "…" : t));
    }

    // ---- texto --------------------------------------------------------------------------------

    private static String wordAt(String s, int i) {
        Matcher m = WORD.matcher(s);
        m.region(i, s.length());
        return m.lookingAt() ? m.group() : null;
    }

    private static int skipWhitespace(String s, int i) {
        while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++;
        return i;
    }

    private static boolean hasComment(String s) {
        return s.contains("/*") || s.contains("//");
    }

    private static String stripComments(String s) {
        return s.replaceAll("(?s)/\\*.*?\\*/", " ").replaceAll("//[^\\n]*", "");
    }

    /** Fim (depois do ':') de um rótulo {@code function F:}, {@code at loop 1:}, {@code contract:}. */
    private static int labelColon(String text, int from, int to) {
        int colon = text.indexOf(':', from);
        int eol = text.indexOf('\n', from);
        if (colon < 0 || colon >= to || (eol >= 0 && eol < colon)) {
            return eol < 0 || eol > to ? to : eol;
        }
        return colon + 1;
    }

    /** Partes separadas por ',' fora de parênteses, colchetes e chaves. */
    private static List<String> splitTopLevel(String s) {
        List<String> parts = new ArrayList<>();
        int depth = 0;
        int start = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '(' || c == '[' || c == '{') depth++;
            else if (c == ')' || c == ']' || c == '}') depth--;
            else if (c == ',' && depth == 0) {
                parts.add(s.substring(start, i));
                start = i + 1;
            }
        }
        parts.add(s.substring(start));
        return parts;
    }

    /**
     * Fim (exclusivo) do statement que começa em {@code from}: depois do primeiro {@code ;} fora de
     * parênteses que não fecha a lista de um {@code \forall}/{@code \exists}/{@code \let}, ou depois
     * de um bloco {@code {...}} de topo; comentários e strings são saltados.
     */
    static int statementEnd(String text, int from) {
        int parens = 0;
        int braces = 0;
        int pendingBinders = 0;
        int n = text.length();
        int i = from;
        while (i < n) {
            char c = text.charAt(i);
            if (c == '"') {
                i = skipString(text, i);
                continue;
            }
            if (text.startsWith("/*", i)) {
                int end = text.indexOf("*/", i + 2);
                i = end < 0 ? n : end + 2;
                continue;
            }
            if (text.startsWith("//", i)) {
                int end = text.indexOf('\n', i);
                i = end < 0 ? n : end;
                continue;
            }
            switch (c) {
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
                    while (j < n && Character.isLetter(text.charAt(j))) j++;
                    if (parens == 0 && braces == 0 && BINDERS.contains(text.substring(i + 1, j))) {
                        pendingBinders++;
                    }
                    i = j;
                    continue;
                }
                case ';' -> {
                    if (parens == 0 && braces == 0) {
                        if (pendingBinders == 0) return i + 1;
                        pendingBinders--;
                    }
                }
                default -> {
                    // caractere comum
                }
            }
            i++;
        }
        return n;
    }

    private static int skipString(String text, int i) {
        int j = i + 1;
        while (j < text.length() && text.charAt(j) != '"') {
            j += text.charAt(j) == '\\' ? 2 : 1;
        }
        return Math.min(j + 1, text.length());
    }

    private static int matchingBrace(String text, int open) {
        int depth = 0;
        for (int i = open; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '"') {
                i = skipString(text, i) - 1;
            } else if (text.startsWith("/*", i)) {
                int end = text.indexOf("*/", i + 2);
                if (end < 0) return -1;
                i = end + 1;
            } else if (text.startsWith("//", i)) {
                int end = text.indexOf('\n', i);
                if (end < 0) return -1;
                i = end;
            } else if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) return i;
            }
        }
        return -1;
    }
}
