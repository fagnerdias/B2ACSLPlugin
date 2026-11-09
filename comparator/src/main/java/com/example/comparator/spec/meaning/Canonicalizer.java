package com.example.comparator.spec.meaning;

import static com.example.comparator.spec.meaning.MeaningRule.ARITHMETIC;
import static com.example.comparator.spec.meaning.MeaningRule.CARD_VALUATION;
import static com.example.comparator.spec.meaning.MeaningRule.CONJUNCTION;
import static com.example.comparator.spec.meaning.MeaningRule.CONSTANT_VALUE;
import static com.example.comparator.spec.meaning.MeaningRule.EMPTY_WITNESS;
import static com.example.comparator.spec.meaning.MeaningRule.IF_FORM;
import static com.example.comparator.spec.meaning.MeaningRule.INTEGER_CAST;
import static com.example.comparator.spec.meaning.MeaningRule.LIST_LITERAL;
import static com.example.comparator.spec.meaning.MeaningRule.NEGATION;
import static com.example.comparator.spec.meaning.MeaningRule.POINTER_INDEX;
import static com.example.comparator.spec.meaning.MeaningRule.RELATION_CHAIN;
import static com.example.comparator.spec.meaning.MeaningRule.SET_EQUALITY;
import static com.example.comparator.spec.meaning.MeaningRule.SET_UNION;
import static com.example.comparator.spec.meaning.MeaningRule.SET_VALUATION;
import static com.example.comparator.spec.meaning.MeaningRule.STATE_PARAMETER;
import static com.example.comparator.spec.meaning.MeaningRule.SYMMETRY;
import static com.example.comparator.spec.meaning.MeaningRule.TYPE_ALIAS;

import com.example.comparator.spec.meaning.Expr.Decl;
import com.example.comparator.spec.meaning.Expr.Kind;
import java.math.BigInteger;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;

/**
 * Rewrites a parsed term or predicate into canonical form under a set of {@link MeaningRule}s, and
 * splits a canonical predicate into atoms (the conjuncts that are compared one by one).
 *
 * <p>One instance canonicalizes one clause: it holds the scopes of bound variables, which are
 * renamed {@code _b<depth>_<index>} on entry so that sorting the operands of commutative operators
 * does not depend on the names each side chose ({@link Expr#print} renumbers them again).
 */
final class Canonicalizer {

    private static final Set<String> LOGIC_OPS =
            Set.of("&&", "||", "==>", "<==>", "^^", "==", "!=", "<", "<=", ">", ">=");
    private static final Pattern INTEGER_LITERAL = Pattern.compile("[0-9]+");

    private final MeaningContext ctx;
    private final Set<MeaningRule> rules;
    private final boolean ghost;
    private final MeaningContext.Side side;
    /** Inside an axiom or lemma: values are never substituted there, they are where values come from. */
    private final boolean fact;
    private final Deque<Map<String, String>> scopes = new ArrayDeque<>();
    private int depth;

    Canonicalizer(MeaningContext ctx, Set<MeaningRule> rules, boolean ghost, MeaningContext.Side side, boolean fact) {
        this.ctx = ctx;
        this.rules = rules;
        this.ghost = ghost;
        this.side = side;
        this.fact = fact;
    }

    private boolean on(MeaningRule rule) {
        return rules.contains(rule);
    }

    /** Opens a scope where {@code names} (e.g. declaration parameters) are bound. */
    void bind(Map<String, String> names) {
        scopes.push(names);
    }

    // ---- predicates ---------------------------------------------------------------------------

    Expr pred(Expr e) {
        switch (e.kind) {
            case BIN -> {
                Expr l = e.args.get(0);
                Expr r = e.args.get(1);
                return switch (e.op) {
                    case "&&" -> conj(List.of(pred(l), pred(r)));
                    case "||" -> disj(List.of(pred(l), pred(r)));
                    case "==>" -> implies(pred(l), pred(r));
                    case "<==>", "^^" -> symmetric(e.op, pred(l), pred(r));
                    case "==", "!=" -> symmetric(e.op, term(l), term(r));
                    case "<", "<=", ">", ">=" -> order(e.op, term(l), term(r));
                    default -> term(e);
                };
            }
            case CHAIN -> {
                return chain(e);
            }
            case UN -> {
                return e.op.equals("!") ? negate(pred(e.args.get(0))) : term(e);
            }
            case TERNARY -> {
                Expr c = pred(e.args.get(0));
                Expr a = pred(e.args.get(1));
                Expr b = pred(e.args.get(2));
                if (on(IF_FORM)) {
                    return conj(List.of(implies(c, a), implies(negate(c), b)));
                }
                return Expr.ternary(c, a, b);
            }
            case BINDER -> {
                return e.op.equals("\\lambda") ? term(e) : quantifier(e);
            }
            case LET -> {
                return let(e, true);
            }
            case AND, OR -> {
                return e;
            }
            default -> {
                return term(e);
            }
        }
    }

    private Expr conj(List<Expr> parts) {
        if (!on(CONJUNCTION)) {
            Expr acc = parts.get(0);
            for (int i = 1; i < parts.size(); i++) {
                acc = Expr.bin("&&", acc, parts.get(i));
            }
            return acc;
        }
        List<Expr> flat = new ArrayList<>();
        for (Expr x : parts) {
            if (x.kind == Kind.AND) {
                flat.addAll(x.args);
            } else if (!x.isId("\\true")) {
                flat.add(x);
            }
        }
        flat = sortedUnique(flat);
        if (flat.isEmpty()) {
            return Expr.id("\\true");
        }
        return flat.size() == 1 ? flat.get(0) : Expr.and(flat);
    }

    private Expr disj(List<Expr> parts) {
        List<Expr> flat = new ArrayList<>();
        for (Expr x : parts) {
            if (x.kind == Kind.OR) {
                flat.addAll(x.args);
            } else {
                flat.add(x);
            }
        }
        if (on(SYMMETRY)) {
            flat = sortedUnique(flat);
        }
        return flat.size() == 1 ? flat.get(0) : Expr.or(flat);
    }

    /** {@code h ==> c}; with {@link MeaningRule#IF_FORM}, {@code h ==> (h2 ==> c2)} becomes {@code h && h2 ==> c2}. */
    private Expr implies(Expr h, Expr c) {
        if (on(IF_FORM)) {
            if (c.isBin("==>")) {
                return implies(conj(List.of(h, c.args.get(0))), c.args.get(1));
            }
            if (h.isId("\\true")) {
                return c;
            }
        }
        return Expr.bin("==>", h, c);
    }

    private Expr negate(Expr x) {
        if (on(NEGATION)) {
            if (x.kind == Kind.UN && x.op.equals("!")) {
                return x.args.get(0);
            }
            if (x.isId("\\true")) {
                return Expr.id("\\false");
            }
            if (x.isId("\\false")) {
                return Expr.id("\\true");
            }
            if (x.kind == Kind.BIN) {
                Expr a = x.args.get(0);
                Expr b = x.args.get(1);
                switch (x.op) {
                    case "==":
                        return symmetric("!=", a, b);
                    case "!=":
                        return symmetric("==", a, b);
                    case "<":
                        return order(">=", a, b);
                    case "<=":
                        return order(">", a, b);
                    case ">":
                        return order("<=", a, b);
                    case ">=":
                        return order("<", a, b);
                    default:
                        break;
                }
            }
        }
        return Expr.un("!", x);
    }

    private Expr symmetric(String op, Expr a, Expr b) {
        if (on(SYMMETRY) && a.print().compareTo(b.print()) > 0) {
            return Expr.bin(op, b, a);
        }
        return Expr.bin(op, a, b);
    }

    /** With {@link MeaningRule#SYMMETRY}, {@code a > b} becomes {@code b < a}. */
    private Expr order(String op, Expr a, Expr b) {
        if (on(SYMMETRY)) {
            if (op.equals(">")) {
                return Expr.bin("<", b, a);
            }
            if (op.equals(">=")) {
                return Expr.bin("<=", b, a);
            }
        }
        return Expr.bin(op, a, b);
    }

    private Expr chain(Expr e) {
        String[] ops = e.op.split(",");
        List<Expr> operands = new ArrayList<>();
        for (Expr a : e.args) {
            operands.add(term(a));
        }
        if (!on(RELATION_CHAIN)) {
            return Expr.chain(operands, List.of(ops));
        }
        List<Expr> parts = new ArrayList<>();
        for (int i = 0; i < ops.length; i++) {
            parts.add(order(ops[i], operands.get(i), operands.get(i + 1)));
        }
        return conj(parts);
    }

    private Expr quantifier(Expr e) {
        Expr out = bound(e, true);
        if (e.op.equals("\\forall") && on(SET_VALUATION)) {
            Expr valued = valuation(out);
            if (valued != null) {
                return valued;
            }
        }
        return out;
    }

    /** Binder with its variables renamed and its body canonicalized (as a predicate or a term). */
    private Expr bound(Expr e, boolean predicateBody) {
        Set<String> stateVariables = fact && on(STATE_PARAMETER) ? stateArguments(e.args.get(0)) : Set.of();
        depth++;
        Map<String, String> scope = new HashMap<>();
        List<Decl> decls = new ArrayList<>();
        for (Decl d : e.decls) {
            if (stateVariables.contains(d.name())) {
                continue; // taken at the state variable of the same name (MeaningRule.STATE_PARAMETER)
            }
            String fresh = "_b" + depth + "_" + decls.size();
            scope.put(d.name(), fresh);
            decls.add(new Decl(type(d.type()), fresh));
        }
        scopes.push(scope);
        Expr body = predicateBody ? pred(e.args.get(0)) : term(e.args.get(0));
        scopes.pop();
        depth--;
        return decls.isEmpty() ? body : Expr.binder(e.op, decls, body);
    }

    /**
     * Names passed, in the source tree {@code e}, as the state parameter of a helper this side
     * parameterizes ({@code f(numbers, x)} where {@code numbers} is a state parameter of {@code f}).
     */
    private Set<String> stateArguments(Expr e) {
        Set<String> out = new HashSet<>();
        collectStateArguments(e, out);
        return out;
    }

    private void collectStateArguments(Expr e, Set<String> out) {
        if (e.kind == Kind.CALL) {
            Map<Integer, String> state = ctx.stateParameters(side, ghost, ctx.resolve(e.op, ghost, rules));
            state.forEach((position, name) -> {
                if (position < e.args.size() && e.args.get(position).kind == Kind.ID
                        && ctx.resolve(e.args.get(position).op, ghost, rules).equals(ctx.resolve(name, ghost, rules))) {
                    out.add(e.args.get(position).op);
                }
            });
        }
        for (Expr a : e.args) {
            collectStateArguments(a, out);
        }
    }

    /** {@code \forall integer x; belongs(x, S) <==> lo <= x <= hi} → {@code equals(S, interval_set(lo, hi))}. */
    private Expr valuation(Expr forall) {
        if (forall.decls.size() != 1 || !forall.decls.get(0).type().equals("integer")) {
            return null;
        }
        String x = forall.decls.get(0).name();
        Expr body = forall.args.get(0);
        if (!body.isBin("<==>")) {
            return null;
        }
        for (int side = 0; side < 2; side++) {
            Expr member = body.args.get(side);
            Expr bounds = body.args.get(1 - side);
            if (member.kind != Kind.CALL || !member.op.equals("belongs") || member.args.size() != 2
                    || !member.args.get(0).isId(x) || mentions(member.args.get(1), x)) {
                continue;
            }
            Expr[] loHi = bounds(bounds, x);
            if (loHi != null) {
                Expr interval = Expr.call("interval_set", List.of(loHi[0], loHi[1]));
                Expr set = member.args.get(1);
                return on(SET_EQUALITY) ? symmetric("==", set, interval) : Expr.call("equals", List.of(set, interval));
            }
        }
        return null;
    }

    /** {@code lo <= x && x <= hi} (as a conjunction or a chain) → {@code [lo, hi]}. */
    private static Expr[] bounds(Expr e, String x) {
        List<Expr> parts;
        if (e.kind == Kind.AND && e.args.size() == 2) {
            parts = e.args;
        } else if (e.isBin("&&")) {
            parts = e.args;
        } else if (e.kind == Kind.CHAIN && e.op.equals("<=,<=") && e.args.get(1).isId(x)) {
            return new Expr[] {e.args.get(0), e.args.get(2)};
        } else {
            return null;
        }
        Expr lo = null;
        Expr hi = null;
        for (Expr part : parts) {
            if (!part.isBin("<=")) {
                return null;
            }
            if (part.args.get(1).isId(x) && !mentions(part.args.get(0), x)) {
                lo = part.args.get(0);
            } else if (part.args.get(0).isId(x) && !mentions(part.args.get(1), x)) {
                hi = part.args.get(1);
            }
        }
        return lo != null && hi != null ? new Expr[] {lo, hi} : null;
    }

    private static boolean mentions(Expr e, String name) {
        Set<String> ids = new HashSet<>();
        e.collectIdentifiers(ids);
        return ids.contains(name);
    }

    private Expr let(Expr e, boolean predicateBody) {
        Expr value = term(e.args.get(0));
        depth++;
        String fresh = "_b" + depth + "_0";
        scopes.push(Map.of(e.op, fresh));
        Expr body = predicateBody ? pred(e.args.get(1)) : term(e.args.get(1));
        scopes.pop();
        depth--;
        return Expr.let(fresh, value, body);
    }

    // ---- terms --------------------------------------------------------------------------------

    Expr term(Expr e) {
        switch (e.kind) {
            case ID -> {
                return identifier(e.op);
            }
            case NUM, RAW, EMPTY, AND, OR -> {
                return e;
            }
            case CAST -> {
                String type = type(e.op);
                Expr x = term(e.args.get(0));
                return on(INTEGER_CAST) && type.equals("integer") ? x : Expr.cast(type, x);
            }
            case CALL -> {
                return call(e);
            }
            case BIN -> {
                if (e.op.equals("+") || e.op.equals("-") || e.op.equals("*")) {
                    return arithmetic(e);
                }
                if (LOGIC_OPS.contains(e.op)) {
                    return pred(e);
                }
                Expr l = term(e.args.get(0));
                Expr r = term(e.args.get(1));
                boolean commutative = e.op.equals("&") || e.op.equals("|") || e.op.equals("^");
                return commutative ? symmetric(e.op, l, r) : Expr.bin(e.op, l, r);
            }
            case CHAIN -> {
                return pred(e);
            }
            case UN -> {
                if (e.op.equals("!")) {
                    return pred(e);
                }
                if (e.op.equals("-") || e.op.equals("+")) {
                    return arithmetic(e);
                }
                Expr operand = e.args.get(0);
                if (e.op.equals("*") && operand.isBin("+") && on(POINTER_INDEX)) {
                    return Expr.index(term(operand.args.get(0)), term(operand.args.get(1)));
                }
                return Expr.un(e.op, term(operand));
            }
            case TERNARY -> {
                return Expr.ternary(pred(e.args.get(0)), term(e.args.get(1)), term(e.args.get(2)));
            }
            case BINDER -> {
                return e.op.equals("\\lambda") ? bound(e, false) : pred(e);
            }
            case LET -> {
                return let(e, false);
            }
            case INDEX -> {
                return Expr.index(term(e.args.get(0)), term(e.args.get(1)));
            }
            case RANGE -> {
                return Expr.range(term(e.args.get(0)), term(e.args.get(1)));
            }
            case FIELD -> {
                return Expr.field(term(e.args.get(0)), e.op);
            }
            case LIST -> {
                List<Expr> items = new ArrayList<>();
                for (Expr a : e.args) {
                    items.add(term(a));
                }
                if (!on(LIST_LITERAL)) {
                    return Expr.list(items);
                }
                Expr acc = Expr.id("\\Nil");
                for (int i = items.size() - 1; i >= 0; i--) {
                    acc = Expr.call("\\Cons", List.of(items.get(i), acc));
                }
                return acc;
            }
            default -> throw new IllegalStateException("unexpected " + e.kind);
        }
    }

    private Expr identifier(String name) {
        for (Map<String, String> scope : scopes) {
            String bound = scope.get(name);
            if (bound != null) {
                return Expr.id(bound);
            }
        }
        String resolved = ctx.resolve(name, ghost, rules);
        if (!fact && on(CONSTANT_VALUE)) {
            Expr value = ctx.constant(resolved);
            if (value != null) {
                return value;
            }
        }
        return Expr.id(resolved);
    }

    private Expr call(Expr e) {
        String name = ctx.resolve(e.op, ghost, rules);
        List<Expr> args = new ArrayList<>();
        for (Expr a : e.args) {
            args.add(term(a));
        }
        if (on(STATE_PARAMETER)) {
            int before = args.size();
            args = withoutStateArguments(name, args);
            if (args.isEmpty() && before > 0) {
                return Expr.id(name); // f(numbers) ≡ the constant f that reads numbers
            }
        }
        switch (name) {
            case "equals" -> {
                if (args.size() == 2 && on(SET_EQUALITY)) {
                    return symmetric("==", args.get(0), args.get(1));
                }
            }
            case "pair" -> {
                if (args.size() == 2 && on(SET_UNION)) {
                    return union(List.of(
                            Expr.call("singleton", List.of(args.get(0))), Expr.call("singleton", List.of(args.get(1)))));
                }
            }
            case "set_union" -> {
                if (on(SET_UNION)) {
                    return union(args);
                }
            }
            case "\\concat" -> {
                if (args.size() == 2 && on(LIST_LITERAL)) {
                    List<Expr> joined = new ArrayList<>();
                    if (literalItems(args.get(0), joined) && literalItems(args.get(1), joined)) {
                        Expr acc = Expr.id("\\Nil");
                        for (int i = joined.size() - 1; i >= 0; i--) {
                            acc = Expr.call("\\Cons", List.of(joined.get(i), acc));
                        }
                        return acc;
                    }
                }
            }
            case "empty" -> {
                if (args.size() == 1 && on(EMPTY_WITNESS)) {
                    return Expr.call("empty", List.of(Expr.id("_")));
                }
            }
            case "card" -> {
                if (args.size() == 1 && args.get(0).kind == Kind.ID && !fact && on(CARD_VALUATION)) {
                    MeaningContext.Valuation v = ctx.valuation(args.get(0).op);
                    if (v != null) {
                        return arithmetic(Expr.bin("+", Expr.bin("-", v.hi(), v.lo()), Expr.num("1")));
                    }
                }
            }
            default -> {
                // an ordinary call
            }
        }
        return Expr.call(name, args);
    }

    /** Adds the items of a canonical literal list ({@code \Cons(a, \Cons(b, \Nil))}) to {@code out}. */
    private static boolean literalItems(Expr list, List<Expr> out) {
        Expr current = list;
        List<Expr> items = new ArrayList<>();
        while (current.kind == Kind.CALL && current.op.equals("\\Cons") && current.args.size() == 2) {
            items.add(current.args.get(0));
            current = current.args.get(1);
        }
        if (!current.isId("\\Nil")) {
            return false;
        }
        out.addAll(items);
        return true;
    }

    /** {@code f(numbers, x)} → {@code f(x)} when {@code numbers} is passed as the state parameter it names. */
    private List<Expr> withoutStateArguments(String function, List<Expr> args) {
        Map<Integer, String> state = ctx.stateParameters(side, ghost, function);
        if (state.isEmpty()) {
            return args;
        }
        for (Map.Entry<Integer, String> p : state.entrySet()) {
            int position = p.getKey();
            if (position >= args.size() || !args.get(position).isId(ctx.resolve(p.getValue(), ghost, rules))) {
                return args;
            }
        }
        List<Expr> kept = new ArrayList<>();
        for (int i = 0; i < args.size(); i++) {
            if (!state.containsKey(i)) {
                kept.add(args.get(i));
            }
        }
        return kept;
    }

    private Expr union(List<Expr> args) {
        List<Expr> flat = new ArrayList<>();
        for (Expr a : args) {
            if (a.kind == Kind.CALL && a.op.equals("set_union")) {
                flat.addAll(a.args);
            } else {
                flat.add(a);
            }
        }
        flat.sort(Comparator.comparing(Expr::print));
        return flat.size() == 1 ? flat.get(0) : Expr.call("set_union", flat);
    }

    // ---- linear arithmetic --------------------------------------------------------------------

    /** Sum of coefficient × atom, plus a constant. */
    private static final class Linear {
        final Map<String, Expr> atoms = new TreeMap<>();
        final Map<String, BigInteger> coefficients = new HashMap<>();
        BigInteger constant = BigInteger.ZERO;

        void add(Expr atom, BigInteger k) {
            String key = atom.print();
            atoms.putIfAbsent(key, atom);
            coefficients.merge(key, k, BigInteger::add);
        }

        Expr toExpr() {
            List<Expr> parts = new ArrayList<>();
            for (Map.Entry<String, Expr> entry : atoms.entrySet()) {
                BigInteger k = coefficients.get(entry.getKey());
                if (k.signum() == 0) {
                    continue;
                }
                parts.add(k.equals(BigInteger.ONE) ? entry.getValue() : Expr.bin("*", Expr.num(k.toString()), entry.getValue()));
            }
            if (constant.signum() != 0 || parts.isEmpty()) {
                parts.add(Expr.num(constant.toString()));
            }
            Expr acc = parts.get(0);
            for (int i = 1; i < parts.size(); i++) {
                acc = Expr.bin("+", acc, parts.get(i));
            }
            return acc;
        }
    }

    private Expr arithmetic(Expr e) {
        if (!on(ARITHMETIC)) {
            if (e.kind == Kind.UN) {
                return Expr.un(e.op, term(e.args.get(0)));
            }
            Expr l = term(e.args.get(0));
            Expr r = term(e.args.get(1));
            return e.op.equals("-") ? Expr.bin(e.op, l, r) : symmetric(e.op, l, r);
        }
        Linear linear = new Linear();
        collect(e, BigInteger.ONE, linear);
        return linear.toExpr();
    }

    /** Adds the source expression {@code e}, times {@code k}, to {@code linear}. */
    private void collect(Expr e, BigInteger k, Linear linear) {
        if (e.kind == Kind.BIN && (e.op.equals("+") || e.op.equals("-"))) {
            collect(e.args.get(0), k, linear);
            collect(e.args.get(1), e.op.equals("-") ? k.negate() : k, linear);
            return;
        }
        if (e.kind == Kind.UN && (e.op.equals("-") || e.op.equals("+"))) {
            collect(e.args.get(0), e.op.equals("-") ? k.negate() : k, linear);
            return;
        }
        if (e.isBin("*")) {
            Expr l = term(e.args.get(0));
            Expr r = term(e.args.get(1));
            BigInteger lv = integerValue(l);
            BigInteger rv = integerValue(r);
            if (lv != null) {
                collectCanonical(r, k.multiply(lv), linear);
            } else if (rv != null) {
                collectCanonical(l, k.multiply(rv), linear);
            } else {
                linear.add(symmetric("*", l, r), k);
            }
            return;
        }
        collectCanonical(term(e), k, linear);
    }

    /** Same as {@link #collect}, for an expression that is already canonical. */
    private void collectCanonical(Expr c, BigInteger k, Linear linear) {
        BigInteger value = integerValue(c);
        if (value != null) {
            linear.constant = linear.constant.add(k.multiply(value));
            return;
        }
        if (c.kind == Kind.BIN && (c.op.equals("+") || c.op.equals("-"))) {
            collectCanonical(c.args.get(0), k, linear);
            collectCanonical(c.args.get(1), c.op.equals("-") ? k.negate() : k, linear);
            return;
        }
        if (c.kind == Kind.UN && c.op.equals("-")) {
            collectCanonical(c.args.get(0), k.negate(), linear);
            return;
        }
        if (c.isBin("*")) {
            BigInteger lv = integerValue(c.args.get(0));
            if (lv != null) {
                collectCanonical(c.args.get(1), k.multiply(lv), linear);
                return;
            }
        }
        linear.add(c, k);
    }

    private static BigInteger integerValue(Expr e) {
        if (e.kind == Kind.NUM && INTEGER_LITERAL.matcher(e.op).matches()) {
            return new BigInteger(e.op);
        }
        if (e.kind == Kind.NUM && e.op.matches("-[0-9]+")) {
            return new BigInteger(e.op);
        }
        return null;
    }

    // ---- atoms --------------------------------------------------------------------------------

    /**
     * The atoms of a canonical predicate: with {@link MeaningRule#CONJUNCTION}, its conjuncts, with
     * {@code ==>} and {@code \forall} distributed over {@code &&} and unused bound variables dropped.
     */
    List<Expr> atoms(Expr e) {
        if (!on(CONJUNCTION)) {
            return List.of(e);
        }
        if (e.isId("\\true")) {
            return List.of();
        }
        if (e.kind == Kind.AND) {
            List<Expr> out = new ArrayList<>();
            for (Expr part : e.args) {
                out.addAll(atoms(part));
            }
            return out;
        }
        if (e.isBin("==>")) {
            List<Expr> out = new ArrayList<>();
            for (Expr a : atoms(e.args.get(1))) {
                out.add(implies(e.args.get(0), a));
            }
            return out;
        }
        if (e.kind == Kind.BINDER && e.op.equals("\\forall")) {
            List<Expr> out = new ArrayList<>();
            for (Expr a : atoms(e.args.get(0))) {
                out.add(prune(e, a));
            }
            return out;
        }
        return List.of(e);
    }

    private static Expr prune(Expr binder, Expr body) {
        Set<String> used = new HashSet<>();
        body.collectIdentifiers(used);
        List<Decl> kept = new ArrayList<>();
        for (Decl d : binder.decls) {
            if (used.contains(d.name())) {
                kept.add(d);
            }
        }
        return kept.isEmpty() ? body : Expr.binder(binder.op, kept, body);
    }

    // ---- helpers ------------------------------------------------------------------------------

    String type(String text) {
        return TypeCanon.canonical(text, on(TYPE_ALIAS) ? ctx.aliases() : null);
    }

    private static List<Expr> sortedUnique(List<Expr> items) {
        Map<String, Expr> byText = new LinkedHashMap<>();
        for (Expr x : items) {
            byText.putIfAbsent(x.print(), x);
        }
        return new ArrayList<>(new TreeMap<>(byText).values());
    }
}
