package com.example.comparator.spec;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Splits a specification written in the acsl-import draft format ({@code .acsl} files of both
 * tools) into {@link SpecUnit}s:
 *
 * <ul>
 *   <li>{@code include "p";} → {@code include:p}
 *   <li>{@code function F: contract: ... at loop 1: ...} → {@code function:F}, one clause per
 *       statement, prefixed by its section ({@code [at loop 1] loop invariant ...});
 *   <li>{@code predicate}/{@code logic}/{@code lemma}/{@code axiom}/{@code type}/{@code
 *       inductive} declarations (top level or inside an axiomatic) → {@code <kind>:<name>};
 *   <li>{@code axiomatic A { ... }} → {@code axiomatic:A}, whose clauses are the keys of the
 *       declarations it groups (so regrouping shows up here, while the declarations themselves are
 *       compared individually);
 *   <li>anything else → {@code other:toplevel}.
 * </ul>
 */
public final class AcslUnitExtractor {

    private static final Pattern NEXT_TOP_LEVEL =
            Pattern.compile(
                    "(?m)^[ \\t]*(include|axiomatic|function|predicate|logic|lemma|axiom|admit|check|type|inductive)\\b");
    private static final Pattern SECTION_LABEL =
            Pattern.compile("\\G\\s*(contract|at\\s+[A-Za-z_][A-Za-z0-9_ ]*?|behavior\\s+[A-Za-z_][A-Za-z0-9_]*)\\s*:(?!:)");
    private static final Pattern QUOTED = Pattern.compile("\"([^\"]*)\"");
    private static final Pattern GENERIC_ARGS = Pattern.compile("<[^<>]*>");
    private static final Pattern LOGIC_NAME =
            Pattern.compile("^logic\\s+.*?([A-Za-z_][A-Za-z0-9_]*)\\s*(?:\\(|=|;|\\breads\\b|$)", Pattern.DOTALL);
    private static final Pattern NAMED_DECLARATION =
            Pattern.compile(
                    "^(?:admit\\s+|check\\s+)?(predicate|lemma|axiom|type|inductive)\\s+([A-Za-z_][A-Za-z0-9_]*)");
    private static final Set<String> DECLARATION_KEYWORDS =
            Set.of("predicate", "logic", "lemma", "axiom", "admit", "check", "type", "inductive");

    private AcslUnitExtractor() {}

    /** Units of an {@code .acsl} text; {@code kindPrefix} (e.g. {@code ghost-}) is prepended to every kind. */
    public static List<SpecUnit> extract(String text, String kindPrefix) {
        String src = AcslScanner.stripComments(text);
        List<SpecUnit> units = new ArrayList<>();
        int i = 0;
        while (true) {
            i = AcslScanner.skipWhitespace(src, i);
            if (i >= src.length()) {
                return units;
            }
            String keyword = AcslScanner.identifierAt(src, i);
            switch (keyword) {
                case "include" -> i = parseInclude(src, i, kindPrefix, units);
                case "axiomatic" -> i = parseAxiomatic(src, i, kindPrefix, units);
                case "function" -> i = parseFunction(src, i, kindPrefix, units);
                default -> {
                    int end = AcslScanner.statementEnd(src, i);
                    String statement = src.substring(i, end);
                    if (DECLARATION_KEYWORDS.contains(keyword)) {
                        units.add(declarationUnit(statement, kindPrefix));
                    } else if (!statement.isBlank()) {
                        SpecUnit other = new SpecUnit(kindPrefix + "other", "toplevel");
                        other.addClause(null, statement);
                        units.add(other);
                    }
                    i = Math.max(end, i + 1);
                }
            }
        }
    }

    private static int parseInclude(String src, int start, String kindPrefix, List<SpecUnit> units) {
        int end = AcslScanner.statementEnd(src, start);
        String statement = src.substring(start, end);
        Matcher m = QUOTED.matcher(statement);
        SpecUnit unit = new SpecUnit(kindPrefix + "include", m.find() ? m.group(1) : statement.trim());
        unit.addClause(null, "include");
        units.add(unit);
        return end;
    }

    private static int parseAxiomatic(String src, int start, String kindPrefix, List<SpecUnit> units) {
        int nameStart = AcslScanner.skipWhitespace(src, start + "axiomatic".length());
        String name = AcslScanner.identifierAt(src, nameStart);
        int open = src.indexOf('{', nameStart);
        int close = open < 0 ? -1 : AcslScanner.matchingBrace(src, open);
        if (close < 0) {
            int end = AcslScanner.statementEnd(src, start);
            SpecUnit broken = new SpecUnit(kindPrefix + "axiomatic", name);
            broken.addClause(null, src.substring(start, end));
            units.add(broken);
            return end;
        }
        SpecUnit axiomatic = new SpecUnit(kindPrefix + "axiomatic", name);
        String body = src.substring(open + 1, close);
        int i = 0;
        while (true) {
            i = AcslScanner.skipWhitespace(body, i);
            if (i >= body.length()) {
                break;
            }
            int end = AcslScanner.statementEnd(body, i);
            String member = body.substring(i, end);
            if (!member.isBlank()) {
                SpecUnit declaration = declarationUnit(member, kindPrefix);
                axiomatic.addClause(null, declaration.key());
                units.add(declaration);
            }
            i = Math.max(end, i + 1);
        }
        units.add(axiomatic);
        int after = AcslScanner.skipWhitespace(src, close + 1);
        return after < src.length() && src.charAt(after) == ';' ? after + 1 : close + 1;
    }

    private static int parseFunction(String src, int start, String kindPrefix, List<SpecUnit> units) {
        int nameStart = AcslScanner.skipWhitespace(src, start + "function".length());
        String name = AcslScanner.identifierAt(src, nameStart);
        int colon = src.indexOf(':', nameStart + name.length());
        int bodyStart = colon < 0 ? nameStart + name.length() : colon + 1;
        Matcher next = NEXT_TOP_LEVEL.matcher(src);
        int bodyEnd = next.find(bodyStart) ? next.start() : src.length();
        SpecUnit function = new SpecUnit(kindPrefix + "function", name);
        addContractClauses(function, src.substring(bodyStart, bodyEnd));
        units.add(function);
        return bodyEnd;
    }

    /**
     * Adds every statement of a contract body ({@code requires ...; ensures ...; at loop 1: loop
     * invariant ...;}) to {@code unit}, tracking the current section label.
     */
    static void addContractClauses(SpecUnit unit, String body) {
        String section = "";
        int i = 0;
        while (true) {
            Matcher label = SECTION_LABEL.matcher(body);
            label.region(i, body.length());
            if (label.lookingAt()) {
                String text = label.group(1);
                section = "contract".equals(text) ? "" : text;
                i = label.end();
                continue;
            }
            i = AcslScanner.skipWhitespace(body, i);
            if (i >= body.length()) {
                return;
            }
            int end = AcslScanner.statementEnd(body, i);
            unit.addClause(section, body.substring(i, end));
            i = Math.max(end, i + 1);
        }
    }

    private static SpecUnit declarationUnit(String statement, String kindPrefix) {
        String compact = ClauseNormalizer.display(statement);
        Matcher named = NAMED_DECLARATION.matcher(compact);
        SpecUnit unit;
        if (named.find()) {
            unit = new SpecUnit(kindPrefix + named.group(1), named.group(2));
        } else if (compact.startsWith("logic")) {
            String withoutGenerics = compact;
            String previous;
            do {
                previous = withoutGenerics;
                withoutGenerics = GENERIC_ARGS.matcher(withoutGenerics).replaceAll("");
            } while (!withoutGenerics.equals(previous));
            Matcher logic = LOGIC_NAME.matcher(withoutGenerics);
            unit = new SpecUnit(kindPrefix + "logic", logic.find() ? logic.group(1) : "#" + compact.hashCode());
        } else {
            unit = new SpecUnit(kindPrefix + "other", "declaration");
        }
        unit.addClause(null, statement);
        return unit;
    }
}
