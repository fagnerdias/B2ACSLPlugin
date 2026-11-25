package com.example.gui.parsing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.gui.model.FunctionResult;
import com.example.gui.model.FunctionStatus;
import com.example.gui.model.Generator;
import com.example.gui.model.Issue;
import java.util.List;
import org.junit.jupiter.api.Test;

class WpOutputParserTest {

    /** Lines from comparator-out/20261124_114005/count_array/plugin/run.log (-wp-status). */
    private static final String COUNT_ARRAY = """
            [kernel] Parsing merged_code.c (with preprocessing)
            [wp] Running WP plugin...
            [wp] 38 goals scheduled
            [wp] [Timeout] typed_CountArray_count_assert_rte_mem_access_2 (Qed 22ms) (CVC5) (Cached) (No Model)
            [wp] [Cache] updated:1
            [wp] Proved goals:   34 / 38
              Terminating:     1
              Unreachable:     1
              Qed:            16 (3ms-15ms-67ms)
              CVC5 1.1.2:     16 (123ms-364ms-3.7s)
              Timeout:         4
              Smoke Tests:     5 / 5
            ------------------------------------------------------------
              Function CountArray__count
            ------------------------------------------------------------

            Goal Post-condition ("merged_code.c", lines 378-384) in 'CountArray__count':
            Tags: Else.
            Prove: L_card_2_(a) = v.
            Prover CVC5 1.1.2 returns Timeout (Qed:11ms) (10s)

            ------------------------------------------------------------
            ------------------------------------------------------------
              Function CountArray__count
            ------------------------------------------------------------

            Goal Assertion 'rte,mem_access' ("merged_code.c", line 409):
            Tags: Then.
            Prove: valid_rd(Malloc_0, shift_sint32(array_0, l_1_0), 1).
            Prover CVC5 1.1.2 returns Timeout (Qed:22ms) (10s)

            ------------------------------------------------------------
            """;

    @Test
    void countsComeFromTheSharedParserAndIssuesFromGoalBlocks() {
        FunctionResult r = WpOutputParser.parse(
                "CountArray__count", Generator.ATL, COUNT_ARRAY, "atl/merged_code.c", 10, 0, null, 1000);
        assertEquals(38, r.totalGoals());
        assertEquals(34, r.proved());
        assertEquals(4, r.timeouts());
        assertEquals(0, r.failures());
        assertEquals("5 / 5", r.smokeDisplay());
        assertEquals(FunctionStatus.FAILED, r.status());
        assertEquals(
                List.of(
                        "CountArray__count: postcondition (ensures) timed out after 10s (CVC5) (atl/merged_code.c:378)",
                        "CountArray__count: runtime check (mem_access) timed out after 10s (CVC5) (atl/merged_code.c:409)"),
                r.issues().stream().map(Issue::text).toList());
    }

    @Test
    void unknownWithCounterexampleSmokeFailureAndProverCrash() {
        String out = """
                [wp] [Failed] (Doomed) typed_M_op_wp_smoke_dead_code_s22 (Qed 10ms) (CVC5 70ms) (Cached) (No Model)
                [wp] merged_code.c:122: Warning: Failed smoke-test
                [wp] Warning: Goal Smoke-test:
                    running prover CVC5:1.1.2 failed ([Why3 Error] anomaly: File "src/lib/qed/term.ml")
                Warning: Error while interpreting P_not_belongs_2_: Type bool for sort (Sbool)
                [wp] Proved goals:    5 / 7
                  Qed:             4
                  CVC5 1.1.2:      1
                  Unknown:         1
                  Failed:          1
                  Smoke Tests:     1 / 2
                ------------------------------------------------------------
                  Function M__op
                ------------------------------------------------------------

                Goal Post-condition ("merged_code.c", line 87) in 'M__op':
                Prove: false.
                Prover CVC5 1.1.2 returns Unknown (Model) (Qed:9ms)
                Model new_yy = 0
                Model xx = 3

                ------------------------------------------------------------
                """;
        FunctionResult r = WpOutputParser.parse("M__op", Generator.PLUGIN, out, "plugin/merged_code.c", 10, 0, null, 5);
        assertEquals(7, r.totalGoals());
        assertEquals(5, r.proved());
        assertEquals(2, r.failures());
        assertEquals(1, r.smokePassed());
        assertEquals(2, r.smokeTotal());
        assertEquals(1, r.smokeFailed());
        List<String> texts = r.issues().stream().map(Issue::text).toList();
        assertEquals(3, texts.size(), texts.toString());
        assertEquals(
                "M__op: smoke test failed — dead code or inconsistent precondition (plugin/merged_code.c:122)",
                texts.get(0));
        assertEquals("M__op: prover CVC5 crashed on goal smoke-test", texts.get(1));
        assertEquals(
                "M__op: postcondition (ensures) could not be proved (plugin/merged_code.c:87)"
                        + " — counterexample: new_yy = 0, xx = 3",
                texts.get(2));
    }

    @Test
    void statusLinesAreUsedWhenThereAreNoGoalBlocks() {
        String out = """
                [wp] [Timeout] typed_Seats_take_ensures_3_part2 (Qed 4ms) (CVC5) (Cached) (No Model)
                [wp] [Failure] typed_cv_subst_INITIALISATION_ensures_2 (Qed 5ms) (CVC5) (No Cache) (No Model)
                [wp] Proved goals:    8 / 10
                  Timeout:         1
                  Failed:          1
                """;
        List<String> texts = WpOutputParser.parse("Seats__take", Generator.PLUGIN, out, "merged_code.c", 7, 0, null, 0)
                .issues().stream().map(Issue::text).toList();
        assertEquals(
                List.of(
                        "Seats__take: goal typed_Seats_take_ensures_3_part2 timed out after 7s (CVC5)",
                        "Seats__take: goal typed_cv_subst_INITIALISATION_ensures_2 could not be checked (CVC5)"),
                texts);
    }

    @Test
    void processFailuresAreFailedEvenWithoutGoals() {
        String out = "[kernel] User Error: merged_code.c:12: syntax error\n";
        FunctionResult r = WpOutputParser.parse("M__op", Generator.ATL, out, "atl/merged_code.c", 10, 1, null, 0);
        assertEquals(FunctionStatus.FAILED, r.status());
        assertTrue(r.issues().get(0).text().contains("Frama-C exited with code 1 — [kernel] User Error"));
        FunctionResult killed =
                WpOutputParser.parse("M__op", Generator.ATL, "", "atl/merged_code.c", 10, -1, "did not finish within 600 s", 0);
        assertEquals("M__op: did not finish within 600 s", killed.issues().get(0).text());
        assertEquals("—", killed.smokeDisplay());
    }

    @Test
    void noGoalsIsProved() {
        String out = "[wp] Proved goals:    0 / 0\n";
        assertEquals(
                FunctionStatus.PROVED,
                WpOutputParser.parse("M__op", Generator.ATL, out, "f", 10, 0, null, 0).status());
    }
}
