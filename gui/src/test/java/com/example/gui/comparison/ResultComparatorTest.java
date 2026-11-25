package com.example.gui.comparison;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.gui.model.FunctionComparison;
import com.example.gui.model.FunctionResult;
import com.example.gui.model.Generator;
import com.example.gui.model.Verdict;
import java.util.List;
import org.junit.jupiter.api.Test;

class ResultComparatorTest {

    static FunctionResult r(String fn, Generator g, int total, int proved, int failures, int timeouts, int smokeP, int smokeT) {
        return new FunctionResult(fn, g, total, proved, failures, timeouts, smokeP, smokeT, 0, false, List.of(), 0);
    }

    /** The case of mockup 5. */
    @Test
    void mockupCase() {
        List<FunctionComparison> rows = List.of(
                ResultComparator.compare(
                        "AddRunner__INITIALISATION",
                        r("AddRunner__INITIALISATION", Generator.PLUGIN, 13, 13, 0, 0, 1, 1),
                        r("AddRunner__INITIALISATION", Generator.ATL, 13, 13, 0, 0, 1, 1)),
                ResultComparator.compare(
                        "AddRunner__Add",
                        r("AddRunner__Add", Generator.PLUGIN, 15, 15, 0, 0, 2, 2),
                        r("AddRunner__Add", Generator.ATL, 18, 16, 1, 1, 2, 2)),
                ResultComparator.compare(
                        "AddRunner__count",
                        r("AddRunner__count", Generator.PLUGIN, 13, 13, 0, 0, 1, 1),
                        r("AddRunner__count", Generator.ATL, 13, 13, 0, 0, 1, 1)));
        assertTrue(rows.get(0).same());
        assertEquals("—", rows.get(0).difference());
        assertFalse(rows.get(1).same());
        assertEquals("ATL Rules: 3 more goals, 1 failure and 1 timeout", rows.get(1).difference());

        Verdict v = ResultComparator.verdict(rows);
        assertFalse(v.allSame());
        assertEquals("Results differ in 1 of 3 functions", v.title());
        assertEquals(
                "AddRunner__Add has 1 failure and 1 timeout with the ATL Rules specification, while the Plugin"
                        + " specification proved all of its goals.",
                v.sentence());
        assertEquals("AddRunner__Add", v.firstDifferent());
    }

    @Test
    void differencesOnBothSides() {
        FunctionComparison row = ResultComparator.compare(
                "f", r("f", Generator.PLUGIN, 20, 15, 3, 2, 3, 4), r("f", Generator.ATL, 18, 15, 1, 2, 4, 4));
        assertEquals("Plugin: 2 more goals, 2 more failures and 1 failed smoke test", row.difference());
    }

    @Test
    void allSame() {
        Verdict v = ResultComparator.verdict(List.of(ResultComparator.compare(
                "f", r("f", Generator.PLUGIN, 3, 3, 0, 0, -1, -1), r("f", Generator.ATL, 3, 3, 0, 0, -1, -1))));
        assertTrue(v.allSame());
        assertEquals("Same results in all 1 function", v.title());
        assertNull(v.firstDifferent());
    }
}
