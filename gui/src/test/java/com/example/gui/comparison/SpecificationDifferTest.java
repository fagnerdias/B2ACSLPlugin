package com.example.gui.comparison;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.example.gui.comparison.SpecificationDiffer.Change;
import com.example.gui.comparison.SpecificationDiffer.DiffRow;
import com.example.gui.comparison.SpecificationDiffer.RowKind;
import com.example.gui.comparison.SpecificationDiffer.SpecDiff;
import com.example.gui.parsing.ContractExtractor;
import com.example.gui.parsing.ContractExtractor.Contract;
import java.util.List;
import org.junit.jupiter.api.Test;

class SpecificationDifferTest {

    private static Contract contract(String text) {
        return ContractExtractor.extract(text.lines().toList(), "AddRunner__Add");
    }

    /** The case of mockup 6. */
    @Test
    void mockupCase() {
        Contract plugin = contract("""
                /*@ requires \\valid(state);
                    requires state->count < INT_MAX;
                    assigns state->count;
                    ensures state->count == \\old(state->count) + 1;
                 */
                void AddRunner__Add(AddRunner_state *state)
                {
                }
                """);
        Contract atl = contract("""
                /*@ requires \\valid(state);
                    assigns state->count, state->total;
                    ensures state->count == \\old(state->count) + 1;
                    ensures state->total >= \\old(state->total);
                 */
                void AddRunner__Add(AddRunner_state *state)
                {
                }
                """);
        SpecDiff diff = SpecificationDiffer.diff("AddRunner__Add", plugin, atl);
        assertEquals(
                List.of(
                        RowKind.DELIMITER,
                        RowKind.EQUAL,
                        RowKind.ONLY_PLUGIN,
                        RowKind.CHANGED,
                        RowKind.EQUAL,
                        RowKind.ONLY_ATL,
                        RowKind.DELIMITER,
                        RowKind.EQUAL),
                diff.rows().stream().map(DiffRow::kind).toList());
        assertEquals(3, diff.differenceCount());
        assertEquals(
                List.of(
                        "The precondition “state->count < INT_MAX” exists only in the Plugin specification.",
                        "The assigns clause is different: the ATL Rules specification also allows changes to state->total.",
                        "The postcondition “state->total >= \\old(state->total)” exists only in the ATL Rules specification."),
                diff.changes().stream().map(Change::sentence).toList());
    }

    @Test
    void reorderedAssignsHaveTheSameMeaning() {
        Contract plugin = contract("""
                /*@ assigns JogoDados__dado1, JogoDados__dado2, JogoDados__semente; */
                void AddRunner__Add(int a)
                {
                }
                """);
        Contract atl = contract("""
                /*@ assigns JogoDados__semente, JogoDados__dado1, JogoDados__dado2; */
                void AddRunner__Add(int a)
                {
                }
                """);
        assertEquals(
                "The assigns clause lists the same locations in a different order (same meaning): Plugin"
                        + " “JogoDados__dado1, JogoDados__dado2, JogoDados__semente”, ATL Rules"
                        + " “JogoDados__semente, JogoDados__dado1, JogoDados__dado2”.",
                SpecificationDiffer.diff("f", plugin, atl).changes().get(0).sentence());
    }

    @Test
    void differentKindsAreNotPairedAsChanged() {
        Contract plugin = contract("""
                /*@ requires a > 0; */
                void AddRunner__Add(int a)
                {
                }
                """);
        Contract atl = contract("""
                /*@ ensures b > 0; */
                void AddRunner__Add(int a)
                {
                }
                """);
        List<RowKind> kinds = SpecificationDiffer.diff("f", plugin, atl).rows().stream().map(DiffRow::kind).toList();
        assertEquals(
                List.of(RowKind.DELIMITER, RowKind.ONLY_PLUGIN, RowKind.ONLY_ATL, RowKind.DELIMITER, RowKind.EQUAL),
                kinds);
    }

    @Test
    void changedPostconditionAndMissingFunction() {
        Contract plugin = contract("""
                /*@ ensures x == 1; */
                void AddRunner__Add(int a)
                {
                }
                """);
        Contract atl = contract("""
                /*@ ensures x == 2; */
                void AddRunner__Add(long a)
                {
                }
                """);
        SpecDiff diff = SpecificationDiffer.diff("f", plugin, atl);
        assertEquals(
                List.of(
                        "The postcondition is different: Plugin “x == 1”, ATL Rules “x == 2”.",
                        "The signature is different: Plugin “void AddRunner__Add(int a);”, ATL Rules “void AddRunner__Add(long a);”."),
                diff.changes().stream().map(Change::sentence).toList());
        Contract missing = ContractExtractor.extract(List.of("int main(void)", "{", "}"), "AddRunner__Add");
        SpecDiff onlyPlugin = SpecificationDiffer.diff("f", plugin, missing);
        assertEquals(
                "The function exists only in the Plugin specification.",
                onlyPlugin.changes().get(onlyPlugin.changes().size() - 1).sentence());
    }
}
