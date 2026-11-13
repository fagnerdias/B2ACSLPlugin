package com.example.comparator.spec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class GhostCiExtractorTest {

    private static final String GHOST_CI =
            """
            /* ghost_operations.ci — non-pure ghost operations (generated) — AddRunner */

            //@ ghost int ghost_ss;

            /*@
                axiomatic dummy_ghost {
                    type DSet<A>;
                    predicate dummy_equals<A>(DSet<A> ss, DSet<A> tt);
                    predicate dummy_equals<A,B>(DTuple<A,B> t, DTuple<A,B> s);
                    logic DSet<integer> dummy_ss;
                }
            */
            /*@ ghost
              /@ assigns ghost_ss;
              @ ensures dummy_equals(dummy_ss, dummy_set_union(\\old(dummy_ss), dummy_singleton((integer)xx)));
                @/
              void add(int xx);
            */
            """;

    @Test
    void extractsGhostVariablesFunctionsAndAxiomatics() {
        Map<String, SpecUnit> units = AcslUnitExtractorTest.byKey(GhostCiExtractor.extract(GHOST_CI));
        assertEquals(
                List.of(
                        "ghost-var:ghost_ss",
                        "ghost-type:DSet",
                        "ghost-predicate:dummy_equals",
                        "ghost-logic:dummy_ss",
                        "ghost-axiomatic:dummy_ghost",
                        "ghost-fn:add"),
                List.copyOf(units.keySet()));
        SpecUnit add = units.get("ghost-fn:add");
        assertTrue(add.clauses().containsValue("assigns ghost_ss"));
        assertTrue(add.clauses().containsValue("signature: void add(int xx)"));
        assertEquals(3, add.clauses().size());
        assertEquals(2, units.get("ghost-predicate:dummy_equals").clauses().size());
    }

    @Test
    void ignoresPlainComments() {
        assertTrue(GhostCiExtractor.extract("/* not an annotation */\n// neither\nint x;").isEmpty());
    }
}
