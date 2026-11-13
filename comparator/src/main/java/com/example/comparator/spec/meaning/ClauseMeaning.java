package com.example.comparator.spec.meaning;

import com.example.comparator.spec.ClauseNormalizer;
import com.example.comparator.spec.meaning.AcslLexer.ParseException;
import com.example.comparator.spec.meaning.AcslLexer.Token;
import com.example.comparator.spec.meaning.AcslLexer.Type;
import com.example.comparator.spec.meaning.Expr.Decl;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns one clause of a {@code SpecUnit} into its atoms: canonical strings that are compared with
 * the atoms of the other side. A contract clause gives one atom per conjunct ({@code requires},
 * {@code ensures}, ...) or per location ({@code assigns}); a {@code predicate}/{@code logic}
 * declaration gives one atom; an axiom or lemma gives one atom per conjunct of its statement (its
 * name is dropped); includes, axiomatic blocks and type aliases give none.
 *
 * <p>Text the parser does not understand gets one atom made of its tokens, with names resolved.
 */
final class ClauseMeaning {

    /**
     * @param key canonical form, prefixed by where it may match ({@code ensures|}, {@code [at loop 1]
     *     loop invariant|}, {@code axiom|}, {@code ghost|...})
     * @param source text of the top-level conjunct (or location) it comes from
     * @param canonical canonical tree, or {@code null} for the textual fallback
     */
    record Atom(String key, String source, Expr canonical) {}

    private static final Pattern CONTRACT_KEYWORD =
            Pattern.compile(
                    "^(requires|ensures|assigns|loop invariant|loop assigns|loop variant|assert|check|admit|"
                            + "assumes|decreases|terminates|invariant|allocates|frees|loop allocates|loop frees|"
                            + "exits|breaks|continues|returns)\\s+(.*)$",
                    Pattern.DOTALL);
    private static final Set<String> PREDICATE_KEYWORDS =
            Set.of(
                    "requires", "ensures", "loop invariant", "assert", "check", "admit", "assumes", "terminates",
                    "invariant", "exits", "breaks", "continues", "returns");
    private static final Set<String> LOCATION_KEYWORDS = Set.of("assigns", "loop assigns");
    private static final Set<String> TERM_KEYWORDS = Set.of("loop variant", "decreases");
    private static final Pattern FACT =
            Pattern.compile("^(?:admit\\s+|check\\s+)?(axiom|lemma)\\s+[A-Za-z_][A-Za-z0-9_]*\\s*(?:<[^:]*>)?\\s*:(.*)$",
                    Pattern.DOTALL);
    static final Pattern TYPE_DECLARATION =
            Pattern.compile("^type\\s+([A-Za-z_][A-Za-z0-9_]*)\\s*(?:<([^=]*)>)?\\s*(?:=(.*))?$", Pattern.DOTALL);
    private static final String GHOST_PREFIX = "ghost-";
    /** Units whose clause is a declaration ({@code admit lemma ...} is not an {@code admit} clause). */
    private static final Set<String> DECLARATION_KINDS = Set.of("predicate", "logic", "axiom", "lemma", "type", "inductive");

    private ClauseMeaning() {}

    static boolean isGhost(String unitKind) {
        return unitKind.startsWith(GHOST_PREFIX);
    }

    static String baseKind(String unitKind) {
        return isGhost(unitKind) ? unitKind.substring(GHOST_PREFIX.length()) : unitKind;
    }

    static boolean isFactKind(String unitKind) {
        String base = baseKind(unitKind);
        return base.equals("axiom") || base.equals("lemma");
    }

    static List<Atom> atoms(
            String unitKind, String clause, MeaningContext ctx, Set<MeaningRule> rules, MeaningContext.Side side) {
        boolean ghost = isGhost(unitKind);
        String base = baseKind(unitKind);
        if (base.equals("include") || base.equals("axiomatic")) {
            return List.of();
        }
        String prefix = ghost ? "ghost|" : "";
        String section = "";
        String rest = clause;
        if (clause.startsWith("[")) {
            int close = clause.indexOf("] ");
            if (close > 0) {
                section = clause.substring(0, close + 1);
                rest = clause.substring(close + 2);
            }
        }
        try {
            Matcher keyword = CONTRACT_KEYWORD.matcher(rest);
            if (!DECLARATION_KINDS.contains(base) && keyword.matches()) {
                String kw = keyword.group(1);
                String body = keyword.group(2);
                String atomPrefix = prefix + section + kw + "|";
                if (PREDICATE_KEYWORDS.contains(kw)) {
                    return predicateAtoms(atomPrefix, body, ctx, rules, ghost, side, false);
                }
                if (LOCATION_KEYWORDS.contains(kw)) {
                    return locationAtoms(atomPrefix, body, ctx, rules, ghost, side);
                }
                if (TERM_KEYWORDS.contains(kw)) {
                    Canonicalizer c = new Canonicalizer(ctx, rules, ghost, side, false);
                    Expr t = c.term(ctx.parse(body));
                    return List.of(new Atom(atomPrefix + t.print(), body, t));
                }
                return List.of(textual(atomPrefix, body, ctx, rules, ghost));
            }
            switch (base) {
                case "predicate", "logic" -> {
                    return List.of(declaration(prefix, rest, ctx, rules, ghost, side));
                }
                case "axiom", "lemma" -> {
                    Matcher fact = FACT.matcher(rest);
                    if (fact.matches()) {
                        return predicateAtoms(prefix + fact.group(1) + "|", fact.group(2), ctx, rules, ghost, side, true);
                    }
                }
                case "type" -> {
                    Matcher type = TYPE_DECLARATION.matcher(rest);
                    if (type.matches()) {
                        if (type.group(3) != null) {
                            return List.of(); // an alias: expanded wherever it is used
                        }
                        String name = ctx.resolve(type.group(1), ghost, rules);
                        return List.of(new Atom(prefix + "type " + name, rest, null));
                    }
                }
                default -> {
                    // textual below
                }
            }
        } catch (ParseException e) {
            ctx.parseFailed(clause, e);
            return List.of(textual(prefix + section + "raw|", rest, ctx, rules, ghost));
        }
        return List.of(textual(prefix + section + "text|", rest, ctx, rules, ghost));
    }

    private static List<Atom> predicateAtoms(
            String prefix,
            String body,
            MeaningContext ctx,
            Set<MeaningRule> rules,
            boolean ghost,
            MeaningContext.Side side,
            boolean fact) {
        Expr parsed = ctx.parse(body);
        List<Expr> conjuncts = new ArrayList<>();
        if (rules.contains(MeaningRule.CONJUNCTION)) {
            topConjuncts(parsed, conjuncts);
        } else {
            conjuncts.add(parsed);
        }
        Canonicalizer c = new Canonicalizer(ctx, rules, ghost, side, fact);
        List<Atom> out = new ArrayList<>();
        for (Expr conjunct : conjuncts) {
            String source = conjunct.start >= 0 ? body.substring(conjunct.start, conjunct.end) : body;
            for (Expr atom : c.atoms(c.pred(conjunct))) {
                out.add(new Atom(prefix + atom.print(), source.strip(), atom));
            }
        }
        return out;
    }

    private static void topConjuncts(Expr e, List<Expr> out) {
        if (e.isBin("&&")) {
            topConjuncts(e.args.get(0), out);
            topConjuncts(e.args.get(1), out);
        } else {
            out.add(e);
        }
    }

    private static List<Atom> locationAtoms(
            String prefix, String body, MeaningContext ctx, Set<MeaningRule> rules, boolean ghost, MeaningContext.Side side) {
        Canonicalizer c = new Canonicalizer(ctx, rules, ghost, side, false);
        List<Atom> out = new ArrayList<>();
        List<String> canonical = new ArrayList<>();
        for (String location : splitTopLevel(body)) {
            Expr t = c.term(ctx.parse(location));
            canonical.add(t.print());
            out.add(new Atom(prefix + t.print(), location.strip(), t));
        }
        if (rules.contains(MeaningRule.ASSIGNS_LIST)) {
            return out;
        }
        return List.of(new Atom(prefix + String.join(",", canonical), body, null));
    }

    /** {@code predicate N(T x) = body} / {@code logic T N(...) reads l = body}: one atom, parameters renamed. */
    private static Atom declaration(
            String prefix, String text, MeaningContext ctx, Set<MeaningRule> rules, boolean ghost, MeaningContext.Side side) {
        AcslParser.Header h = AcslParser.header(text);
        Canonicalizer c = new Canonicalizer(ctx, rules, ghost, side, false);
        String name = ctx.resolve(h.name(), ghost, rules);
        // parameters that only carry a state variable (MeaningRule.STATE_PARAMETER) are left out
        Set<Integer> stateParams = rules.contains(MeaningRule.STATE_PARAMETER)
                ? ctx.stateParameters(side, ghost, name).keySet()
                : Set.of();
        Map<String, String> params = new HashMap<>();
        List<String> paramTypes = new ArrayList<>();
        for (int i = 0; i < h.params().size(); i++) {
            if (stateParams.contains(i)) {
                continue;
            }
            Decl d = h.params().get(i);
            params.put(d.name(), "_p" + paramTypes.size());
            paramTypes.add(c.type(d.type()));
        }
        c.bind(params);
        StringBuilder key = new StringBuilder(prefix).append(h.keyword()).append(' ');
        if (h.type() != null) {
            key.append(c.type(h.type())).append(' ');
        }
        key.append(name);
        if (!h.generics().isEmpty()) {
            key.append('<').append(String.join(",", h.generics())).append('>');
        }
        key.append('(').append(String.join(",", paramTypes)).append(')');
        if (!h.reads().isEmpty()) {
            TreeSet<String> reads = new TreeSet<>();
            for (String location : h.reads()) {
                reads.add(c.term(ctx.parse(location)).print());
            }
            key.append(" reads ").append(String.join(",", reads));
        }
        Expr body = null;
        if (h.bodyOffset() >= 0) {
            Expr parsed = ctx.parse(text.substring(h.bodyOffset()));
            body = h.keyword().equals("predicate") ? c.pred(parsed) : c.term(parsed);
            key.append('=').append(body.print());
        }
        return new Atom(key.toString(), text, body);
    }

    /** Fallback: the tokens of {@code text}, names resolved, spacing normalized. */
    private static Atom textual(String prefix, String text, MeaningContext ctx, Set<MeaningRule> rules, boolean ghost) {
        String canonical;
        try {
            List<Token> tokens = new ArrayList<>();
            for (Token t : AcslLexer.lex(text)) {
                if (t.type() == Type.IDENT && !t.text().startsWith("\\")) {
                    tokens.add(new Token(Type.IDENT, ctx.resolve(t.text(), ghost, rules), t.start(), t.end(), t.spaceBefore()));
                } else if (t.type() != Type.EOF) {
                    tokens.add(t);
                }
            }
            canonical = AcslParser.compact(tokens);
        } catch (ParseException e) {
            canonical = ClauseNormalizer.canonical(text);
        }
        return new Atom(prefix + canonical, text, null);
    }

    /** Splits on commas outside parentheses, brackets and braces. */
    static List<String> splitTopLevel(String s) {
        List<String> parts = new ArrayList<>();
        int depth = 0;
        int start = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '(' || c == '[' || c == '{') {
                depth++;
            } else if (c == ')' || c == ']' || c == '}') {
                depth--;
            } else if (c == ',' && depth == 0) {
                parts.add(s.substring(start, i));
                start = i + 1;
            }
        }
        parts.add(s.substring(start));
        return parts;
    }
}
