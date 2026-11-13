package com.example.comparator.spec.meaning;

import com.example.comparator.report.Reports.MeaningClause;
import com.example.comparator.spec.SpecUnit;
import com.example.comparator.spec.meaning.MeaningContext.Side;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Decides whether the textual differences of a unit (the clauses only one side wrote) change its
 * meaning: a clause has the same meaning when each of its atoms (see {@link ClauseMeaning}) is
 * also an atom of the other side's unit. Axioms and lemmas are matched against all the axioms and
 * lemmas of the other side, since their names and grouping carry no meaning.
 *
 * <p>The rules that explain a unit's form differences are found by ablation: a rule is reported
 * when turning it off leaves fewer atoms matched.
 */
public final class MeaningComparator {

    public static final String FORM = "FORM";
    public static final String MEANING = "MEANING";

    /**
     * @param difference {@link #FORM} or {@link #MEANING}
     * @param formRules ids of the {@link MeaningRule}s that explain the form differences
     * @param meaningPlugin plugin clauses with at least one atom the rules side lacks
     * @param meaningRules rules clauses with at least one atom the plugin side lacks
     */
    public record Result(
            String difference,
            List<String> formRules,
            List<MeaningClause> meaningPlugin,
            List<MeaningClause> meaningRules) {}

    private record Match(
            int matched, List<MeaningClause> unmatchedPlugin, List<MeaningClause> unmatchedRules, boolean viaOtherUnit,
            boolean anyAtoms) {}

    private final MeaningContext ctx;
    private final Collection<SpecUnit> pluginUnits;
    private final Collection<SpecUnit> rulesUnits;
    private final Map<String, List<ClauseMeaning.Atom>> atomCache = new HashMap<>();
    private final Map<Long, Set<String>> pluginFacts = new HashMap<>();
    private final Map<Long, Set<String>> rulesFacts = new HashMap<>();

    public MeaningComparator(MeaningContext ctx, Collection<SpecUnit> pluginUnits, Collection<SpecUnit> rulesUnits) {
        this.ctx = ctx;
        this.pluginUnits = pluginUnits;
        this.rulesUnits = rulesUnits;
    }

    /** The unit's key with its name resolved through the renamings (to pair units only one side has). */
    public String mappedKey(SpecUnit unit) {
        String kind = unit.kind();
        if (ClauseMeaning.baseKind(kind).equals("include")) {
            return unit.key();
        }
        String name = unit.key().substring(unit.key().indexOf(':') + 1);
        return kind + ":" + ctx.resolve(name, ClauseMeaning.isGhost(kind), MeaningRule.REWRITES);
    }

    /**
     * @param plugin the plugin's unit, or {@code null}
     * @param rules the rules' unit, or {@code null}
     * @param onlyPlugin display text of the plugin clauses with no textually equal rules clause
     * @param onlyRules the same for the rules side
     */
    public Result compare(SpecUnit plugin, SpecUnit rules, List<String> onlyPlugin, List<String> onlyRules) {
        Set<MeaningRule> all = EnumSet.copyOf(MeaningRule.REWRITES);
        Match full = match(plugin, rules, onlyPlugin, onlyRules, all);
        boolean same = full.unmatchedPlugin().isEmpty() && full.unmatchedRules().isEmpty();
        List<String> formRules = new ArrayList<>();
        if (full.matched() > 0) {
            for (MeaningRule rule : MeaningRule.REWRITES) {
                Set<MeaningRule> without = EnumSet.copyOf(all);
                without.remove(rule);
                if (match(plugin, rules, onlyPlugin, onlyRules, without).matched() < full.matched()) {
                    formRules.add(rule.id());
                }
            }
            if (full.viaOtherUnit()) {
                formRules.add(MeaningRule.AXIOM_GROUPING.id());
            }
            if (formRules.isEmpty()) {
                formRules.add(MeaningRule.LAYOUT.id());
            }
        }
        if (same && !full.anyAtoms()) {
            formRules.add(MeaningRule.ORGANIZATION.id());
        }
        return new Result(same ? FORM : MEANING, formRules, full.unmatchedPlugin(), full.unmatchedRules());
    }

    private Match match(SpecUnit plugin, SpecUnit rules, List<String> onlyPlugin, List<String> onlyRules, Set<MeaningRule> enabled) {
        long mask = mask(enabled);
        String kind = plugin != null ? plugin.kind() : rules.kind();
        boolean fact = ClauseMeaning.isFactKind(kind);
        Set<String> ownPlugin = unitAtoms(plugin, Side.PLUGIN, enabled, mask);
        Set<String> ownRules = unitAtoms(rules, Side.RULES, enabled, mask);
        Set<String> poolPlugin = fact ? facts(pluginUnits, Side.PLUGIN, pluginFacts, enabled, mask) : ownPlugin;
        Set<String> poolRules = fact ? facts(rulesUnits, Side.RULES, rulesFacts, enabled, mask) : ownRules;

        int[] matched = {0};
        boolean[] via = {false};
        boolean[] any = {false};
        List<MeaningClause> unmatchedPlugin =
                unmatched(kind, Side.PLUGIN, onlyPlugin, poolRules, ownRules, enabled, mask, matched, via, any);
        List<MeaningClause> unmatchedRules =
                unmatched(kind, Side.RULES, onlyRules, poolPlugin, ownPlugin, enabled, mask, matched, via, any);
        return new Match(matched[0], unmatchedPlugin, unmatchedRules, via[0], any[0]);
    }

    private List<MeaningClause> unmatched(
            String kind,
            Side side,
            List<String> clauses,
            Set<String> otherPool,
            Set<String> otherOwn,
            Set<MeaningRule> enabled,
            long mask,
            int[] matched,
            boolean[] via,
            boolean[] any) {
        List<MeaningClause> out = new ArrayList<>();
        for (String clause : clauses) {
            List<ClauseMeaning.Atom> atoms = atoms(kind, side, clause, enabled, mask);
            if (!atoms.isEmpty()) {
                any[0] = true;
            }
            List<ClauseMeaning.Atom> missing = new ArrayList<>();
            for (ClauseMeaning.Atom atom : atoms) {
                if (otherPool.contains(atom.key())) {
                    matched[0]++;
                    if (!otherOwn.contains(atom.key())) {
                        via[0] = true;
                    }
                } else {
                    missing.add(atom);
                }
            }
            if (!missing.isEmpty()) {
                out.add(new MeaningClause(clause, conjuncts(atoms, missing)));
            }
        }
        return out;
    }

    /** The conjuncts of a clause that have no counterpart, when the clause has more than one conjunct. */
    private static List<String> conjuncts(List<ClauseMeaning.Atom> atoms, List<ClauseMeaning.Atom> missing) {
        Set<String> all = new LinkedHashSet<>();
        atoms.forEach(a -> all.add(a.source()));
        if (all.size() < 2) {
            return List.of();
        }
        Set<String> out = new LinkedHashSet<>();
        missing.forEach(a -> out.add(a.source()));
        return List.copyOf(out);
    }

    private Set<String> unitAtoms(SpecUnit unit, Side side, Set<MeaningRule> enabled, long mask) {
        Set<String> out = new HashSet<>();
        if (unit != null) {
            for (String clause : unit.clauses().values()) {
                atoms(unit.kind(), side, clause, enabled, mask).forEach(a -> out.add(a.key()));
            }
        }
        return out;
    }

    /** Atoms of every axiom and lemma of one side. */
    private Set<String> facts(
            Collection<SpecUnit> units, Side side, Map<Long, Set<String>> cache, Set<MeaningRule> enabled, long mask) {
        return cache.computeIfAbsent(mask, m -> {
            Set<String> out = new HashSet<>();
            for (SpecUnit unit : units) {
                if (ClauseMeaning.isFactKind(unit.kind())) {
                    out.addAll(unitAtoms(unit, side, enabled, mask));
                }
            }
            return out;
        });
    }

    private List<ClauseMeaning.Atom> atoms(String kind, Side side, String clause, Set<MeaningRule> enabled, long mask) {
        String key = kind + '\u0000' + side + '\u0000' + mask + '\u0000' + clause;
        List<ClauseMeaning.Atom> cached = atomCache.get(key);
        if (cached == null) {
            cached = ClauseMeaning.atoms(kind, clause, ctx, enabled, side);
            atomCache.put(key, cached);
        }
        return cached;
    }

    private static long mask(Set<MeaningRule> rules) {
        long m = 0;
        for (MeaningRule r : rules) {
            m |= 1L << r.ordinal();
        }
        return m;
    }
}
