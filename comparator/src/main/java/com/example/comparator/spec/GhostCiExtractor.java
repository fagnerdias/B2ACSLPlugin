package com.example.comparator.spec;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Splits {@code ghost_operations.ci} into {@link SpecUnit}s. The file is C with ACSL annotations,
 * so only {@code //@ ...} and {@code /*@ ... *}{@code /} annotations are read:
 *
 * <ul>
 *   <li>{@code //@ ghost int v;} → {@code ghost-var:v}
 *   <li>{@code /*@ ghost /@ contract @/ void f(...); *}{@code /} → {@code ghost-fn:f} (contract
 *       clauses plus a {@code signature} clause)
 *   <li>any other annotation (e.g. {@code axiomatic dummy_ghost}) → the units of {@link
 *       AcslUnitExtractor}, with a {@code ghost-} kind prefix.
 * </ul>
 */
public final class GhostCiExtractor {

    static final String KIND_PREFIX = "ghost-";

    private static final String GHOST = "ghost";
    /** Leading {@code @} of annotation lines; not the {@code @/} that closes a ghost contract. */
    private static final Pattern LINE_AT_PREFIX = Pattern.compile("(?m)^\\s*@(?!/)");
    private static final Pattern GHOST_VAR_NAME =
            Pattern.compile("([A-Za-z_][A-Za-z0-9_]*)\\s*(?:\\[[^\\]]*\\]\\s*)*(?:=|;|$)");
    private static final Pattern FUNCTION_NAME = Pattern.compile("([A-Za-z_][A-Za-z0-9_]*)\\s*\\(");

    private GhostCiExtractor() {}

    public static List<SpecUnit> extract(String text) {
        List<SpecUnit> units = new ArrayList<>();
        for (String annotation : annotations(text)) {
            String content = LINE_AT_PREFIX.matcher(annotation).replaceAll(" ").trim();
            if (content.startsWith(GHOST) && !Character.isLetterOrDigit(charAfter(content, GHOST))) {
                units.add(ghostUnit(content.substring(GHOST.length()).trim()));
            } else {
                units.addAll(AcslUnitExtractor.extract(content, KIND_PREFIX));
            }
        }
        return units;
    }

    /** Contents of the {@code /*@ ... *}{@code /} and {@code //@ ...} annotations, in file order. */
    static List<String> annotations(String text) {
        List<String> out = new ArrayList<>();
        int i = 0;
        while (i < text.length()) {
            int block = text.indexOf("/*", i);
            int line = text.indexOf("//", i);
            if (block < 0 && line < 0) {
                break;
            }
            boolean isBlock = line < 0 || (block >= 0 && block < line);
            i = isBlock ? readComment(text, block, "*/", out) : readComment(text, line, "\n", out);
        }
        return out;
    }

    /** Reads the comment at {@code start}; keeps its content if it is an annotation ({@code @}). */
    private static int readComment(String text, int start, String terminator, List<String> out) {
        int end = text.indexOf(terminator, start + 2);
        end = end < 0 ? text.length() : end;
        if (start + 2 < text.length() && text.charAt(start + 2) == '@') {
            out.add(text.substring(start + 3, end));
        }
        return Math.min(text.length(), end + terminator.length());
    }

    private static SpecUnit ghostUnit(String rest) {
        int contractStart = rest.indexOf("/@");
        int contractEnd = rest.lastIndexOf("@/");
        if (contractStart >= 0 && contractEnd > contractStart) {
            String contract = rest.substring(contractStart + 2, contractEnd);
            String signature = ClauseNormalizer.display(rest.substring(contractEnd + 2));
            Matcher name = FUNCTION_NAME.matcher(signature);
            SpecUnit unit = new SpecUnit(KIND_PREFIX + "fn", name.find() ? name.group(1) : signature);
            AcslUnitExtractor.addContractClauses(unit, LINE_AT_PREFIX.matcher(contract).replaceAll(" "));
            unit.addClause(null, "signature: " + signature);
            return unit;
        }
        Matcher fn = FUNCTION_NAME.matcher(rest);
        if (fn.find() && rest.contains("{")) {
            SpecUnit unit = new SpecUnit(KIND_PREFIX + "fn", fn.group(1));
            unit.addClause(null, rest);
            return unit;
        }
        String declaration = ClauseNormalizer.display(rest);
        Matcher variable = GHOST_VAR_NAME.matcher(declaration);
        String name = declaration;
        while (variable.find()) {
            name = variable.group(1);
            if (declaration.charAt(variable.end() - 1) == '=' || declaration.charAt(variable.end() - 1) == ';') {
                break;
            }
        }
        SpecUnit unit = new SpecUnit(KIND_PREFIX + "var", name);
        unit.addClause(null, declaration);
        return unit;
    }

    private static char charAfter(String s, String prefix) {
        return s.length() > prefix.length() ? s.charAt(prefix.length()) : ' ';
    }
}
