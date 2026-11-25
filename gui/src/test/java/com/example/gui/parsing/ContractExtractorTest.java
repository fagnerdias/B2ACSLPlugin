package com.example.gui.parsing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.gui.parsing.ContractExtractor.Contract;
import java.util.List;
import org.junit.jupiter.api.Test;

class ContractExtractorTest {

    /** Excerpt of examples/AddRunner merged_code.c (prototype without contract, then the definition). */
    private static final List<String> ADD_RUNNER = """
            void AddRunner__INITIALISATION(void);

            void AddRunner__Add(int32_t xx);

            void AddRunner__count(int32_t *rr);


            /*@ requires AddRunner_invariant_1;
                requires AddRunner_invariant_2;
                requires belongs(xx, NAT) && not_belongs(xx, ss) && card(ss) < MM;
                ensures equals(ss, set_union(\\old(ss), singleton(\\old(xx))));
                ensures AddRunner_invariant_1;
                assigns ghost_ss;
                assigns AddRunner__ss_i;
             */
            void AddRunner__Add(int32_t xx)
            {
              AddRunner__ss_i ++;
            }
            """.lines().toList();

    @Test
    void takesTheContractAboveTheDefinition() {
        Contract c = ContractExtractor.extract(ADD_RUNNER, "AddRunner__Add");
        assertTrue(c.found());
        assertTrue(c.hasContract());
        assertEquals(8, c.openLine());
        assertEquals(15, c.closeLine());
        assertEquals(7, c.clauses().size());
        assertEquals("requires AddRunner_invariant_1;", c.clauses().get(0).text());
        assertEquals(8, c.clauses().get(0).fileLine());
        assertEquals(
                "requires belongs(xx, NAT) && not_belongs(xx, ss) && card(ss) < MM;", c.clauses().get(2).text());
        assertEquals("assigns AddRunner__ss_i;", c.clauses().get(6).text());
        assertEquals(14, c.clauses().get(6).fileLine());
        assertEquals("void AddRunner__Add(int32_t xx);", c.signature().text());
        assertEquals(16, c.signature().fileLine());
    }

    @Test
    void joinsMultiLineClausesAndSplitsBehaviors() {
        List<String> lines = """
                /*@ requires \\valid(p) &&
                      *p >= 0;
                    behavior pos:
                      assumes *p > 0;
                      ensures \\result == 1;
                    complete behaviors;
                 */
                int M__f(int *p)
                {
                }
                """.lines().toList();
        Contract c = ContractExtractor.extract(lines, "M__f");
        assertEquals(
                List.of(
                        "requires \\valid(p) && *p >= 0;",
                        "behavior pos:",
                        "assumes *p > 0;",
                        "ensures \\result == 1;",
                        "complete behaviors;"),
                c.clauses().stream().map(ContractExtractor.ClauseLine::text).toList());
        assertEquals(1, c.clauses().get(0).fileLine());
        assertEquals(3, c.clauses().get(1).fileLine());
    }

    @Test
    void functionWithoutContractOrMissing() {
        Contract none = ContractExtractor.extract(ADD_RUNNER, "AddRunner__count");
        assertTrue(none.found());
        assertFalse(none.hasContract());
        assertEquals("void AddRunner__count(int32_t *rr);", none.signature().text());
        assertFalse(ContractExtractor.extract(ADD_RUNNER, "Other__op").found());
    }
}
