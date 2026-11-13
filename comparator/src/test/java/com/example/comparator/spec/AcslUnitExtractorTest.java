package com.example.comparator.spec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AcslUnitExtractorTest {

    private static final String PLUGIN_STYLE =
            """
            /* ACSL generated from AddRunner.bxml */
            include "import/types.acsl";
            include "import/set_functions/card.acsl";
            axiomatic AddRunner_constants {
                logic integer MM;
            }
            axiomatic AddRunner_variables {
                logic Set<integer> ss reads dummy_ghost_ss;
            }
            predicate AddRunner_invariant_3 =
                (card(ss) <= MM);
            axiomatic AddRunner_properties {
                axiom AddRunner_properties_MM:
                    belongs(MM, NAT);
                axiom spec:
                  \\forall Set<integer> s;
                    (\\exists \\list<integer> out; belongs(out, seq(NAT))) ==> is_finite(s);
            }
            function AddRunner__Add:
            contract:
                requires  AddRunner_invariant_3;
                requires  belongs(xx, NAT) && (card(ss) < MM);
                ensures  equals(ss,  set_union(\\old(ss), singleton((integer)xx)));
                assigns AddRunner__ss_i;
                at return: assert ghost__add(xx);

            function AddRunner__count:
            contract:
                requires \\valid(rr);
                ensures *rr == card(ss);
                assigns *rr;
                at loop 1:
                    loop invariant \\forall integer k; 0 <= k < i ==> t[k] == 0;
                    loop variant n - i;
            """;

    /** Same specification, printed the way the ATL translator does (other line breaks and spacing). */
    private static final String RULES_STYLE =
            """
            include "import/types.acsl";
            include "import/set_functions/card.acsl";
            axiomatic AddRunner_constants { logic integer MM; }
            axiomatic AddRunner_variables { logic Set<integer> ss reads dummy_ghost_ss; }
            predicate AddRunner_invariant_3 = (card(ss) <= MM);
            axiomatic AddRunner_properties {
                axiom AddRunner_properties_MM: belongs(MM, NAT);
                axiom spec: \\forall Set<integer> t; (\\exists \\list<integer> l; belongs(l, seq(NAT))) ==> is_finite(t);
            }
            function AddRunner__Add: contract:
                requires AddRunner_invariant_3;
                requires belongs(xx, NAT) && (card(ss) < MM);
                ensures equals(ss, set_union(\\old(ss), singleton((integer)xx)));
                assigns AddRunner__ss_i;
                at return: assert ghost__add(xx);
            function AddRunner__count: contract:
                requires \\valid(rr);
                ensures *rr == card(ss);
                assigns *rr;
                at loop 1: loop invariant \\forall integer j; 0 <= j < i ==> t[j] == 0;
                loop variant n - i;
            """;

    @Test
    void extractsTheExpectedUnits() {
        Map<String, SpecUnit> units = byKey(AcslUnitExtractor.extract(PLUGIN_STYLE, ""));
        assertEquals(
                List.of(
                        "include:import/types.acsl",
                        "include:import/set_functions/card.acsl",
                        "logic:MM",
                        "axiomatic:AddRunner_constants",
                        "logic:ss",
                        "axiomatic:AddRunner_variables",
                        "predicate:AddRunner_invariant_3",
                        "axiom:AddRunner_properties_MM",
                        "axiom:spec",
                        "axiomatic:AddRunner_properties",
                        "function:AddRunner__Add",
                        "function:AddRunner__count"),
                List.copyOf(units.keySet()));
        assertEquals(5, units.get("function:AddRunner__Add").clauses().size());
        assertTrue(
                units.get("function:AddRunner__count").clauses().containsValue("[at loop 1] loop variant n - i"));
        assertTrue(units.get("function:AddRunner__Add").clauses().containsValue("[at return] assert ghost__add(xx)"));
        assertTrue(units.get("axiomatic:AddRunner_properties").clauses().containsValue("axiom:spec"));
    }

    @Test
    void formattingDifferencesProduceNoStructuralDifference() {
        Map<String, SpecUnit> plugin = byKey(AcslUnitExtractor.extract(PLUGIN_STYLE, ""));
        Map<String, SpecUnit> rules = byKey(AcslUnitExtractor.extract(RULES_STYLE, ""));
        assertEquals(plugin.keySet(), rules.keySet());
        for (String key : plugin.keySet()) {
            assertEquals(plugin.get(key).clauses().keySet(), rules.get(key).clauses().keySet(), key);
        }
    }

    @Test
    void aChangedClauseIsDetected() {
        String changed = RULES_STYLE.replace("ensures *rr == card(ss);", "ensures *rr >= 0;");
        SpecUnit plugin = byKey(AcslUnitExtractor.extract(PLUGIN_STYLE, "")).get("function:AddRunner__count");
        SpecUnit rules = byKey(AcslUnitExtractor.extract(changed, "")).get("function:AddRunner__count");
        assertTrue(plugin.clauses().containsValue("ensures *rr == card(ss)"));
        assertTrue(rules.clauses().containsValue("ensures *rr >= 0"));
        assertEquals(plugin.clauses().size(), rules.clauses().size());
    }

    static Map<String, SpecUnit> byKey(List<SpecUnit> units) {
        Map<String, SpecUnit> out = new LinkedHashMap<>();
        for (SpecUnit u : units) {
            SpecUnit existing = out.putIfAbsent(u.key(), u);
            if (existing != null) {
                existing.mergeFrom(u);
            }
        }
        return out;
    }
}
