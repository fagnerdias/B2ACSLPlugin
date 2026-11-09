package com.example.comparator.spec.meaning;

import com.example.comparator.spec.SpecUnit;
import com.example.comparator.spec.meaning.AcslLexer.ParseException;
import com.example.comparator.spec.meaning.AcslLexer.Token;
import com.example.comparator.spec.meaning.AcslLexer.Type;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What the comparison of one example knows beyond a single clause, derived from both
 * specifications:
 *
 * <ul>
 *   <li>renamings ({@link MeaningRule#MACHINE_PREFIX}, {@link MeaningRule#INVARIANT_NAME}): a name
 *       used by only one side is mapped to the name the other side uses for the same thing, when
 *       the candidate is unique (computed separately for the {@code .acsl} files and for {@code
 *       ghost_operations.ci}, which is a front-end of its own);
 *   <li>type aliases ({@link MeaningRule#TYPE_ALIAS}): those of B2ACSLLib and those declared by
 *       either specification;
 *   <li>valuations ({@link MeaningRule#CARD_VALUATION}, {@link MeaningRule#CONSTANT_VALUE}): {@code
 *       S == interval_set(lo, hi)} and {@code C == n} facts stated by both sides.
 * </ul>
 *
 * <p>Also caches parsed clauses. In ghost units ({@code ghost_operations.ci}) every name loses its
 * {@code dummy_} prefix before anything else, on both sides.
 */
public final class MeaningContext {

    /** Which specification a clause belongs to. */
    public enum Side {
        PLUGIN,
        RULES
    }

    record Rename(String target, MeaningRule rule) {}

    record Valuation(Expr lo, Expr hi) {}

    private static final Pattern INVARIANT = Pattern.compile("^(.+_invariant)(?:_1)?$");
    private static final String DUMMY = "dummy_";

    private final Map<String, Rename> renames;
    private final Map<String, Rename> ghostRenames;
    private final Map<String, TypeCanon.Alias> aliases;
    private Map<String, Valuation> valuations = Map.of();
    private Map<String, Expr> constants = Map.of();
    /** {@code side|ghost|function} → position → name of the parameters that only carry a state variable. */
    private final Map<String, Map<Integer, String>> stateParams = new HashMap<>();
    private final Map<String, Object> parsed = new HashMap<>();
    private final Map<String, String> failures = new TreeMap<>();

    private MeaningContext(
            Map<String, Rename> renames, Map<String, Rename> ghostRenames, Map<String, TypeCanon.Alias> aliases) {
        this.renames = renames;
        this.ghostRenames = ghostRenames;
        this.aliases = aliases;
    }

    /** A context with no renamings, valuations or declared aliases (B2ACSLLib aliases only). */
    static MeaningContext empty() {
        return new MeaningContext(Map.of(), Map.of(), libraryAliases());
    }

    public static MeaningContext build(Collection<SpecUnit> plugin, Collection<SpecUnit> rules, Set<String> machines) {
        List<String> byLength = new ArrayList<>(machines);
        byLength.sort(Comparator.comparingInt(String::length).reversed());
        Set<String> pluginNames = identifiers(plugin, false);
        Set<String> rulesNames = identifiers(rules, false);
        Set<String> pluginGhostNames = identifiers(plugin, true);
        Set<String> rulesGhostNames = identifiers(rules, true);
        Map<String, Rename> renames = renamings(pluginNames, rulesNames, byLength);
        Map<String, Rename> ghostRenames = renamings(pluginGhostNames, rulesGhostNames, byLength);

        Map<String, TypeCanon.Alias> aliases = libraryAliases();
        declaredAliases(plugin, aliases);
        declaredAliases(rules, aliases);

        MeaningContext ctx = new MeaningContext(renames, ghostRenames, aliases);
        ctx.stateParameters(plugin, rules, false, pluginNames, rulesNames);
        ctx.stateParameters(plugin, rules, true, pluginGhostNames, rulesGhostNames);
        Map<String, Valuation> pluginSets = new HashMap<>();
        Map<String, Valuation> rulesSets = new HashMap<>();
        Map<String, Expr> pluginConstants = new HashMap<>();
        Map<String, Expr> rulesConstants = new HashMap<>();
        ctx.facts(plugin, Side.PLUGIN, pluginSets, pluginConstants);
        ctx.facts(rules, Side.RULES, rulesSets, rulesConstants);
        ctx.valuations = shared(pluginSets, rulesSets, v -> v.lo().print() + ".." + v.hi().print());
        ctx.constants = shared(pluginConstants, rulesConstants, Expr::print);
        return ctx;
    }

    /** Renamings between the names only the plugin uses and the names only the rules use. */
    private static Map<String, Rename> renamings(Set<String> plugin, Set<String> rules, List<String> machinesLongestFirst) {
        Set<String> onlyPlugin = new HashSet<>(plugin);
        onlyPlugin.removeAll(rules);
        Set<String> onlyRules = new HashSet<>(rules);
        onlyRules.removeAll(plugin);
        Map<String, Rename> renames = new HashMap<>();
        Map<String, List<String>> pluginInvariants = group(onlyPlugin, MeaningContext::invariantBase);
        Map<String, List<String>> rulesInvariants = group(onlyRules, MeaningContext::invariantBase);
        pluginInvariants.forEach((base, names) -> {
            List<String> other = rulesInvariants.get(base);
            if (names.size() == 1 && other != null && other.size() == 1) {
                link(names.get(0), other.get(0), MeaningRule.INVARIANT_NAME, renames, onlyPlugin, onlyRules);
            }
        });
        Map<String, List<String>> pluginBare = group(onlyPlugin, n -> stripMachines(n, machinesLongestFirst).get(0));
        Map<String, List<String>> rulesBare = group(onlyRules, n -> stripMachines(n, machinesLongestFirst).get(0));
        pluginBare.forEach((base, names) -> {
            List<String> other = rulesBare.get(base);
            if (other != null) {
                pairQualified(names, other, machinesLongestFirst, renames, onlyPlugin, onlyRules);
            }
        });
        return renames;
    }

    /**
     * Pairs names with the same bare name: first those qualified by the same machines ({@code
     * ctx__LOW_LEVEL} and {@code ctx_LOW_LEVEL}), then, if exactly one name is left on each side and
     * one of them is bare, those two ({@code GOODS} and {@code Goods_GOODS}). Names qualified by
     * different machines ({@code Airlock_i_x}, {@code Airlock_x}) are never paired.
     */
    private static void pairQualified(
            List<String> plugin,
            List<String> rules,
            List<String> machinesLongestFirst,
            Map<String, Rename> renames,
            Set<String> onlyPlugin,
            Set<String> onlyRules) {
        Map<List<String>, List<String>> pluginByQualifier = new HashMap<>();
        Map<List<String>, List<String>> rulesByQualifier = new HashMap<>();
        plugin.forEach(n -> pluginByQualifier.computeIfAbsent(qualifier(n, machinesLongestFirst), k -> new ArrayList<>()).add(n));
        rules.forEach(n -> rulesByQualifier.computeIfAbsent(qualifier(n, machinesLongestFirst), k -> new ArrayList<>()).add(n));
        List<String> leftPlugin = new ArrayList<>(plugin);
        List<String> leftRules = new ArrayList<>(rules);
        pluginByQualifier.forEach((qualifier, names) -> {
            List<String> other = rulesByQualifier.get(qualifier);
            if (names.size() == 1 && other != null && other.size() == 1) {
                link(names.get(0), other.get(0), MeaningRule.MACHINE_PREFIX, renames, onlyPlugin, onlyRules);
                leftPlugin.remove(names.get(0));
                leftRules.remove(other.get(0));
            }
        });
        if (leftPlugin.size() == 1 && leftRules.size() == 1
                && (qualifier(leftPlugin.get(0), machinesLongestFirst).isEmpty()
                        || qualifier(leftRules.get(0), machinesLongestFirst).isEmpty())) {
            link(leftPlugin.get(0), leftRules.get(0), MeaningRule.MACHINE_PREFIX, renames, onlyPlugin, onlyRules);
        }
    }

    private static List<String> qualifier(String name, List<String> machinesLongestFirst) {
        List<String> stripped = stripMachines(name, machinesLongestFirst);
        return stripped.subList(1, stripped.size());
    }

    private static void link(
            String from, String to, MeaningRule rule, Map<String, Rename> renames, Set<String> onlyPlugin, Set<String> onlyRules) {
        if (!onlyPlugin.contains(from) || !onlyRules.contains(to)) {
            return;
        }
        if (!from.equals(to)) {
            renames.put(from, new Rename(to, rule));
        }
        onlyPlugin.remove(from);
        onlyRules.remove(to);
    }

    // ---- lookups used by the canonicalizer ----------------------------------------------------

    String resolve(String name, boolean ghost, Set<MeaningRule> rules) {
        String n = ghost && name.startsWith(DUMMY) && name.length() > DUMMY.length() ? name.substring(DUMMY.length()) : name;
        Rename rename = (ghost ? ghostRenames : renames).get(n);
        return rename != null && rules.contains(rename.rule()) ? rename.target() : n;
    }

    /** Position → name of the parameters of {@code function} (resolved name) that only carry a state variable. */
    Map<Integer, String> stateParameters(Side side, boolean ghost, String function) {
        return stateParams.getOrDefault(side + "|" + ghost + "|" + function, Map.of());
    }

    Expr constant(String name) {
        return constants.get(name);
    }

    Valuation valuation(String name) {
        return valuations.get(name);
    }

    Map<String, TypeCanon.Alias> aliases() {
        return aliases;
    }

    /** Parsed clause text, cached; rethrows the parse error of a text that does not parse. */
    Expr parse(String text) {
        Object cached = parsed.get(text);
        if (cached == null) {
            try {
                cached = AcslParser.parse(text);
            } catch (ParseException e) {
                cached = e;
            }
            parsed.put(text, cached);
        }
        if (cached instanceof ParseException e) {
            throw e;
        }
        return (Expr) cached;
    }

    void parseFailed(String clause, ParseException e) {
        failures.putIfAbsent(clause, e.getMessage());
    }

    /** Clauses compared textually because the parser did not understand them, with the reason. */
    public Map<String, String> parseFailures() {
        return failures;
    }

    /** Name pairs taken to be the same symbol: plugin name → rules name, with the rule. */
    public Map<String, String> renamings() {
        Map<String, String> out = new TreeMap<>();
        renames.forEach((from, r) -> out.put(from, r.target() + " (" + r.rule().id() + ")"));
        ghostRenames.forEach((from, r) -> out.putIfAbsent("ghost: " + from, r.target() + " (" + r.rule().id() + ")"));
        return out;
    }

    // ---- building -----------------------------------------------------------------------------

    /**
     * Every identifier of one namespace of a side (the {@code .acsl} files, or {@code
     * ghost_operations.ci}): names of its units and the words of its clauses.
     */
    private static Set<String> identifiers(Collection<SpecUnit> units, boolean ghostNamespace) {
        Set<String> out = new HashSet<>();
        for (SpecUnit unit : units) {
            String kind = unit.kind();
            boolean ghost = ClauseMeaning.isGhost(kind);
            if (ghost != ghostNamespace || ClauseMeaning.baseKind(kind).equals("include")) {
                continue;
            }
            out.add(undummy(unit.key().substring(unit.key().indexOf(':') + 1), ghost));
            for (String clause : unit.clauses().values()) {
                try {
                    for (Token t : AcslLexer.lex(clause)) {
                        if (t.type() == Type.IDENT && !t.text().startsWith("\\")) {
                            out.add(undummy(t.text(), ghost));
                        }
                    }
                } catch (ParseException e) {
                    // a clause the lexer rejects contributes no names
                }
            }
        }
        return out;
    }

    private static String undummy(String name, boolean ghost) {
        return ghost && name.startsWith(DUMMY) && name.length() > DUMMY.length() ? name.substring(DUMMY.length()) : name;
    }

    private static Map<String, List<String>> group(Set<String> names, Function<String, String> base) {
        Map<String, List<String>> out = new TreeMap<>();
        for (String name : names) {
            String b = base.apply(name);
            if (b != null) {
                out.computeIfAbsent(b, k -> new ArrayList<>()).add(name);
            }
        }
        return out;
    }

    /**
     * {@code Goods_GOODS}, {@code Airlock__ACQ}, {@code Airlock_pressure_bs_PRESSURE} → {@code GOODS},
     * {@code ACQ}, {@code PRESSURE}: the bare name, followed by the machines whose prefixes were
     * removed (outermost first).
     */
    static List<String> stripMachines(String name, List<String> machinesLongestFirst) {
        List<String> out = new ArrayList<>();
        out.add(name);
        String current = name;
        boolean changed = true;
        while (changed) {
            changed = false;
            for (String machine : machinesLongestFirst) {
                for (String separator : List.of("__", "_")) {
                    String prefix = machine + separator;
                    if (current.startsWith(prefix) && current.length() > prefix.length()
                            && AcslLexer.isIdentStart(current.charAt(prefix.length()))
                            && current.charAt(prefix.length()) != '_') {
                        current = current.substring(prefix.length());
                        out.add(machine);
                        changed = true;
                        break;
                    }
                }
                if (changed) {
                    break;
                }
            }
        }
        out.set(0, current);
        return out;
    }

    /** {@code Relation<A,B>}, {@code Function<A,B>} and the fixed aliases of {@code types_lemmas.acsl}. */
    private static Map<String, TypeCanon.Alias> libraryAliases() {
        Map<String, TypeCanon.Alias> out = new LinkedHashMap<>();
        alias(out, "Relation", List.of("A", "B"), "Set<Tuple<A,B>>");
        alias(out, "Function", List.of("A", "B"), "Relation<A,B>");
        alias(out, "Relation_int_int", List.of(), "Set<Tuple<integer,integer>>");
        alias(out, "Relation_int_bool", List.of(), "Set<Tuple<integer,boolean>>");
        alias(out, "Relation_bool_int", List.of(), "Set<Tuple<boolean,integer>>");
        alias(out, "Relation_int_tuple_int_int_int", List.of(), "Set<Tuple<integer,Tuple<Tuple<integer,integer>,integer>>>");
        alias(out, "Function_int_int", List.of(), "Relation_int_int");
        alias(out, "Function_int_bool", List.of(), "Relation_int_bool");
        alias(out, "Function_bool_int", List.of(), "Relation_bool_int");
        alias(out, "Function_int_tuple_int_int_int", List.of(), "Relation_int_tuple_int_int_int");
        return out;
    }

    private static void alias(Map<String, TypeCanon.Alias> out, String name, List<String> params, String body) {
        out.put(name, new TypeCanon.Alias(params, TypeCanon.parse(body)));
    }

    /** {@code type X<A,B> = T} declarations of a side (the plugin's {@code *_tuple_types.acsl}, ...). */
    private static void declaredAliases(Collection<SpecUnit> units, Map<String, TypeCanon.Alias> out) {
        for (SpecUnit unit : units) {
            if (!ClauseMeaning.baseKind(unit.kind()).equals("type")) {
                continue;
            }
            for (String clause : unit.clauses().values()) {
                Matcher m = ClauseMeaning.TYPE_DECLARATION.matcher(clause);
                if (!m.matches() || m.group(3) == null) {
                    continue;
                }
                List<String> params = new ArrayList<>();
                if (m.group(2) != null) {
                    for (String p : m.group(2).split(",")) {
                        params.add(p.strip());
                    }
                }
                try {
                    out.putIfAbsent(m.group(1), new TypeCanon.Alias(params, TypeCanon.parse(m.group(3).strip())));
                } catch (ParseException e) {
                    // an alias whose definition does not parse stays unexpanded
                }
            }
        }
    }

    /**
     * Finds the helpers declared by both sides where one side has extra parameters, named like state
     * variables the other side uses directly ({@code set_comprehension_1(Set<integer> numbers)} versus
     * {@code set_comprehension_1}); see {@link MeaningRule#STATE_PARAMETER}.
     */
    private void stateParameters(
            Collection<SpecUnit> plugin, Collection<SpecUnit> rules, boolean ghost, Set<String> pluginNames, Set<String> rulesNames) {
        Map<String, List<String>> pluginParams = declaredParams(plugin, ghost);
        Map<String, List<String>> rulesParams = declaredParams(rules, ghost);
        pluginParams.forEach((function, pp) -> {
            List<String> rp = rulesParams.get(function);
            if (pp == null || rp == null) {
                return;
            }
            Map<Integer, String> rulesExtra = extraParams(pp, rp, pluginNames);
            if (!rulesExtra.isEmpty()) {
                stateParams.put(Side.RULES + "|" + ghost + "|" + function, rulesExtra);
            }
            Map<Integer, String> pluginExtra = extraParams(rp, pp, rulesNames);
            if (!pluginExtra.isEmpty()) {
                stateParams.put(Side.PLUGIN + "|" + ghost + "|" + function, pluginExtra);
            }
        });
    }

    /**
     * Positions of {@code longer} left over when {@code shorter} is matched in order, if every
     * left-over parameter is named like a name the other side uses ({@code otherNames}); else empty.
     */
    private static Map<Integer, String> extraParams(List<String> shorter, List<String> longer, Set<String> otherNames) {
        if (longer.size() <= shorter.size()) {
            return Map.of();
        }
        Map<Integer, String> extra = new TreeMap<>();
        int j = 0;
        for (int i = 0; i < longer.size(); i++) {
            if (j < shorter.size() && longer.get(i).equals(shorter.get(j))) {
                j++;
            } else if (otherNames.contains(longer.get(i))) {
                extra.put(i, longer.get(i));
            } else {
                return Map.of();
            }
        }
        return j == shorter.size() ? extra : Map.of();
    }

    /** Resolved name → parameter names of the {@code logic}/{@code predicate} declarations of one namespace (null if overloaded). */
    private Map<String, List<String>> declaredParams(Collection<SpecUnit> units, boolean ghostNamespace) {
        Map<String, List<String>> out = new HashMap<>();
        for (SpecUnit unit : units) {
            String base = ClauseMeaning.baseKind(unit.kind());
            if (ClauseMeaning.isGhost(unit.kind()) != ghostNamespace || !(base.equals("logic") || base.equals("predicate"))) {
                continue;
            }
            for (String clause : unit.clauses().values()) {
                try {
                    AcslParser.Header h = AcslParser.header(clause);
                    String name = resolve(h.name(), ghostNamespace, MeaningRule.REWRITES);
                    List<String> params = h.params().stream().map(Expr.Decl::name).toList();
                    if (out.containsKey(name) && !params.equals(out.get(name))) {
                        out.put(name, null); // overloads: not a candidate
                    } else {
                        out.put(name, params);
                    }
                } catch (ParseException e) {
                    // not a declaration this parser reads
                }
            }
        }
        return out;
    }

    /** Collects {@code S == interval_set(lo, hi)} and {@code C == n} from the (non-ghost) axioms and lemmas. */
    private void facts(Collection<SpecUnit> units, Side side, Map<String, Valuation> sets, Map<String, Expr> values) {
        Set<String> conflictingSets = new HashSet<>();
        Set<String> conflictingValues = new HashSet<>();
        Set<MeaningRule> rules = EnumSet.copyOf(MeaningRule.REWRITES);
        for (SpecUnit unit : units) {
            if (!ClauseMeaning.isFactKind(unit.kind()) || ClauseMeaning.isGhost(unit.kind())) {
                continue;
            }
            for (String clause : unit.clauses().values()) {
                for (ClauseMeaning.Atom atom : ClauseMeaning.atoms(unit.kind(), clause, this, rules, side)) {
                    Expr e = atom.canonical();
                    if (e == null || !e.isBin("==")) {
                        continue;
                    }
                    for (int operand = 0; operand < 2; operand++) {
                        Expr name = e.args.get(operand);
                        Expr value = e.args.get(1 - operand);
                        if (name.kind != Expr.Kind.ID) {
                            continue;
                        }
                        if (value.kind == Expr.Kind.CALL && value.op.equals("interval_set") && value.args.size() == 2) {
                            record(sets, conflictingSets, name.op, new Valuation(value.args.get(0), value.args.get(1)),
                                    v -> v.lo().print() + ".." + v.hi().print());
                        } else if (value.kind == Expr.Kind.NUM && value.op.matches("-?[0-9]+")) {
                            record(values, conflictingValues, name.op, value, Expr::print);
                        }
                    }
                }
            }
        }
        conflictingSets.forEach(sets::remove);
        conflictingValues.forEach(values::remove);
    }

    private static <V> void record(Map<String, V> out, Set<String> conflicting, String name, V value, Function<V, String> text) {
        V previous = out.putIfAbsent(name, value);
        if (previous != null && !text.apply(previous).equals(text.apply(value))) {
            conflicting.add(name);
        }
    }

    private static <V> Map<String, V> shared(Map<String, V> plugin, Map<String, V> rules, Function<V, String> text) {
        Map<String, V> out = new HashMap<>();
        plugin.forEach((name, value) -> {
            V other = rules.get(name);
            if (other != null && text.apply(other).equals(text.apply(value))) {
                out.put(name, value);
            }
        });
        return out;
    }

    /** {@code M_invariant_1} and {@code M_invariant} → {@code M_invariant}; other names have no base. */
    private static String invariantBase(String name) {
        Matcher m = INVARIANT.matcher(name);
        return m.matches() ? m.group(1) : null;
    }
}
