package com.example.comparator.spec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.comparator.report.Reports.SpecComparison;
import com.example.comparator.report.Reports.UnitComparison;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Classification of whole specifications: renamed units, shared valuations and state parameters. */
class SpecDiffMeaningTest {

    private static final String PLUGIN =
            """
            axiomatic Goods_constants {
                logic Set<integer> Goods_GOODS;
                axiom Goods_i_values_GOODS: \\forall integer x; belongs(x, Goods_GOODS) <==> 0 <= x <= 10;
            }
            axiomatic M_variables {
                logic Set<integer> numbers reads dummy_ghost_numbers;
                logic Set<integer> sc1;
                axiom sc1_values: \\forall integer xx; belongs(xx, sc1) <==> belongs(xx, numbers) && xx % 2 == 1;
            }
            predicate M_invariant = inclusion(numbers, Goods_GOODS);
            function M__add:
            contract:
                requires M_invariant;
                requires belongs(gg, Goods_GOODS) && \\valid(rr);
                ensures M_invariant;
                ensures *rr == card(sc1);
                assigns M__arr[0 .. 10];
                assigns *rr;
            function M__get:
            contract:
                requires \\valid(rr);
                requires \\separated(rr, M__arr + (0 .. 10));
                ensures *rr == card(sc1);
                assigns *rr;
            """;

    private static final String RULES =
            """
            axiomatic Goods_constants {
                logic Set<integer> GOODS;
                axiom Goods_i_values_GOODS: GOODS == interval_set(0, 10);
            }
            axiomatic M_variables {
                logic Set<integer> numbers reads dummy_ghost_numbers;
                logic Set<integer> sc1(Set<integer> numbers);
                axiom sc1_values: \\forall Set<integer> numbers, integer xx;
                    belongs(xx, sc1(numbers)) <==> belongs(xx, numbers) && ((xx % 2) == 1);
            }
            predicate M_invariant_1 = inclusion(numbers, GOODS);
            function M__add:
            contract:
                requires M_invariant_1;
                requires belongs(gg, GOODS) && \\valid(rr);
                ensures M_invariant_1;
                ensures *rr == card(sc1(numbers));
                assigns M__arr[0 .. card(GOODS) - 1], *rr;
            function M__get:
            contract:
                requires \\valid(rr);
                ensures *rr == card(sc1(\\old(numbers)));
                assigns *rr;
            """;

    private static SpecComparison compare(Path dir) throws Exception {
        Path plugin = Files.createDirectories(dir.resolve("plugin"));
        Path rules = Files.createDirectories(dir.resolve("rules"));
        Files.writeString(plugin.resolve("M.acsl"), PLUGIN);
        Files.writeString(rules.resolve("M.acsl"), RULES);
        return SpecDiff.compare(plugin, rules, Set.of("Goods", "Goods_i", "M", "M_i"));
    }

    private static UnitComparison unit(SpecComparison spec, String key) {
        return spec.units().stream().filter(u -> u.key().equals(key)).findFirst().orElseThrow();
    }

    @Test
    void unitsThatDifferOnlyInFormAreSeparatedFromDifferencesInMeaning(@TempDir Path dir) throws Exception {
        SpecComparison spec = compare(dir);

        UnitComparison add = unit(spec, "function:M__add");
        assertEquals("FORM", add.difference());
        assertTrue(add.formRules().containsAll(
                java.util.List.of("machine-prefix", "invariant-name", "card-valuation", "state-parameter", "assigns-list")),
                add.formRules().toString());

        UnitComparison get = unit(spec, "function:M__get");
        assertEquals("MEANING", get.difference());
        assertEquals(2, get.clauses().meaningPlugin().size()); // \separated, and card(sc1) of the post-state
        assertEquals(1, get.clauses().meaningRules().size()); // \old(numbers): the pre-state, not the state

        UnitComparison invariant = unit(spec, "predicate:M_invariant");
        assertEquals("predicate:M_invariant_1", invariant.rulesKey());
        assertEquals("FORM", invariant.difference());

        UnitComparison goods = unit(spec, "logic:Goods_GOODS");
        assertEquals("logic:GOODS", goods.rulesKey());
        assertEquals("FORM", goods.difference());

        assertEquals("FORM", unit(spec, "axiom:Goods_i_values_GOODS").difference());
        assertEquals("FORM", unit(spec, "axiom:sc1_values").difference());
        assertEquals("FORM", unit(spec, "logic:sc1").difference());
        assertEquals(1, spec.summary().meaningBothUnits());
    }
}
