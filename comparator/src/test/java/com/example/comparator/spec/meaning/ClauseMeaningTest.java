package com.example.comparator.spec.meaning;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;

/**
 * Pairs of clauses written by the two translators (most taken from the examples) that must, or
 * must not, have the same atoms.
 */
class ClauseMeaningTest {

    private static Set<String> atoms(String kind, List<String> clauses) {
        MeaningContext ctx = MeaningContext.empty();
        Set<String> out = new TreeSet<>();
        for (String clause : clauses) {
            ClauseMeaning.atoms(kind, clause, ctx, EnumSet.copyOf(MeaningRule.REWRITES), MeaningContext.Side.PLUGIN)
                    .forEach(a -> out.add(a.key()));
        }
        return out;
    }

    private static void same(String plugin, String rules) {
        same("function", List.of(plugin), List.of(rules));
    }

    private static void same(String kind, List<String> plugin, List<String> rules) {
        assertEquals(atoms(kind, plugin), atoms(kind, rules));
    }

    private static void different(String plugin, String rules) {
        assertNotEquals(atoms("function", List.of(plugin)), atoms("function", List.of(rules)));
    }

    @Test
    void conjunctionsSplitIntoClauses() {
        same("function",
                List.of("ensures belongs(a, PRESSURE)", "ensures is_open == \\false", "ensures cycle == ACQ"),
                List.of("ensures belongs(a, PRESSURE) && (is_open == \\false) && (cycle == ACQ) && \\true"));
    }

    @Test
    void conditionalAsImplications() {
        same("function",
                List.of("ensures ((\\old(counter) > 0)) ==> (counter == (\\old(counter) - 1))",
                        "ensures !((\\old(counter) > 0)) ==> (counter == \\old(counter))"),
                List.of("ensures (\\old(counter) > 0) ? (counter == (\\old(counter) - 1)) : (\\true && (counter == \\old(counter)))"));
    }

    @Test
    void castsParenthesesAndSymmetry() {
        same("requires ((integer)cycle == (integer)ACQ)", "requires ACQ == cycle");
        same("ensures x > 0", "ensures 0 < x");
    }

    @Test
    void boundVariablesAndRelationChains() {
        same("loop invariant \\forall integer k; 0 <= k < i ==> a[k] == 0",
                "loop invariant \\forall integer j; (0 <= j) && (j < i) ==> (a[j] == 0)");
    }

    @Test
    void arithmeticAndPointers() {
        same("ensures r == size(t, (10 - (0) + 1))", "ensures r == size(t, 11)");
        same("assigns *(cc + (0 .. 9))", "assigns cc[0 .. 9]");
        same("function", List.of("assigns a, b[0 .. 3]"), List.of("assigns a", "assigns b[0..3]"));
    }

    @Test
    void setsAndLists() {
        same("ensures equals(Done, empty(NAT))", "ensures Done == empty(INT)");
        same("ensures equals(s, set_union(set_union(singleton(1), singleton(2)), singleton(3)))",
                "ensures equals(s, set_union(singleton(3), pair(1, 2)))");
        same("requires SQ != [| |] && \\concat([|3|], [| 1, 2 |]) == SQ",
                "requires SQ != \\Nil && \\Cons(3, \\Cons(1, \\Cons(2, \\Nil))) == SQ");
    }

    @Test
    void intervalValuationOfASet() {
        same("axiom",
                List.of("axiom Goods_i_values_GOODS: \\forall integer x; belongs(x, GOODS) <==> 0 <= x <= 10"),
                List.of("axiom Goods_i_values_GOODS: GOODS == interval_set(0, 10)"));
    }

    @Test
    void axiomsAreComparedByContentNotName() {
        same("axiom",
                List.of("axiom Context_properties_1: (MAX_SEAT == 10) && equals(SEAT, interval_set(0, MAX_SEAT))"),
                List.of("axiom Context_properties_MAX_SEAT: MAX_SEAT == 10",
                        "axiom Context_properties_SEAT: equals(SEAT, interval_set(0, MAX_SEAT))"));
    }

    @Test
    void typeAliasesAndParameterNames() {
        same("logic",
                List.of("logic Function<integer,integer> price_i = array_to_function_int((int*)(Price__price_i), 11)"),
                List.of("logic Set<Tuple<integer, integer> > price_i = array_to_function_int((int*)Price__price_i, 11)"));
        same("predicate",
                List.of("predicate P(integer x, Relation<integer,boolean> r) = (\\forall integer y; (y < x) ==> belongs(y, dom(r)))"),
                List.of("predicate P(integer a, Set<Tuple<integer,boolean> > s) = \\forall integer z; z < a ==> belongs(z, dom(s))"));
    }

    @Test
    void differencesInMeaningStay() {
        different("requires \\valid(ee + (0 .. maximum))", "requires \\valid(ee)");
        different("ensures (integer)(*ret != 0) == b", "ensures *ret == b");
        different("ensures x == 1", "ensures x == 2");
        different("ensures v == \\old(w)", "ensures v == w");
        different("requires a < b", "ensures a < b");
        different("ensures c ==> x == 1", "ensures x == 1");
        different("assigns \\nothing", "assigns hwm");
        different("ensures \\exists integer i; P(i) && Q(i)", "ensures (\\exists integer i; P(i)) && (\\exists integer i; Q(i))");
        different("ensures a - b == 0", "ensures b - a == 1");
    }

    @Test
    void organizationalUnitsHaveNoAtoms() {
        assertEquals(Set.of(), atoms("include", List.of("include")));
        assertEquals(Set.of(), atoms("axiomatic", List.of("predicate:P")));
        assertEquals(Set.of(), atoms("type", List.of("type Relation_X = Set<Tuple<integer,integer> >")));
    }
}
