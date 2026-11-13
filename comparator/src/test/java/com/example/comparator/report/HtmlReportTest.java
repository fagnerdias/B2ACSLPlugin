package com.example.comparator.report;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.comparator.report.Reports.ClauseSets;
import com.example.comparator.report.Reports.ExampleReport;
import com.example.comparator.report.Reports.FunctionComparison;
import com.example.comparator.report.Reports.FunctionResult;
import com.example.comparator.report.Reports.GenerationReport;
import com.example.comparator.report.Reports.MeaningClause;
import com.example.comparator.report.Reports.RunConfig;
import com.example.comparator.report.Reports.SpecComparison;
import com.example.comparator.report.Reports.SpecSummary;
import com.example.comparator.report.Reports.Summary;
import com.example.comparator.report.Reports.SummaryRow;
import com.example.comparator.report.Reports.UnitComparison;
import com.example.comparator.report.Reports.VerificationComparison;
import com.example.comparator.report.Reports.VerificationReport;
import com.example.comparator.util.Json;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class HtmlReportTest {

    private static final GenerationReport GEN_OK =
            new GenerationReport("OK", 0, 900, List.of("M.acsl"), List.of("M.acsl"), List.of(), null);

    @Test
    void keyErrorFindsTheFirstFramaCError() {
        String importOutput =
                """
                [kernel] Parsing ghost_operations.ci (external front-end)
                [kernel] Parsing Price_i.c (with preprocessing)
                [acsl-import] Success for Goods.acsl
                [kernel:annot-error] Price.acsl:20: Failure:\s
                  unbound logic function array_to_function_int
                [kernel] User Error: warning annot-error treated as fatal error.
                """;
        assertEquals(
                "Price.acsl:20: Failure: unbound logic function array_to_function_int",
                HtmlReport.keyError(importOutput));
    }

    @Test
    void keyErrorNamesTheGhostFileInsteadOfItsPreprocessedCopy() {
        String importOutput =
                "[kernel:annot-error] /tmp/ghost_operations.ci709a21.pp:39: Failure: unbound logic variable PRESSURE.\n"
                        + "[kernel] User Error: warning annot-error treated as fatal error.";
        assertEquals(
                "ghost_operations.ci:39: Failure: unbound logic variable PRESSURE.", HtmlReport.keyError(importOutput));
    }

    @Test
    void keyErrorKeepsBracketsInsideTheMessage() {
        String importOutput = "[acsl-import] M_tuple_types.acsl:2: User Error: \n  [Syntax error] Relation_Tuple.\n"
                + "[kernel] Plug-in acsl-import aborted: invalid user input.";
        assertEquals("M_tuple_types.acsl:2: User Error: [Syntax error] Relation_Tuple.", HtmlReport.keyError(importOutput));
    }

    @Test
    void failureReasonPrefersTheGenerationMessage() {
        GenerationReport failed = new GenerationReport(
                "TRANSLATION_ERROR", 2, 10, List.of(), List.of(), List.of(), "ERRO: use pastas de saida diferentes");
        assertEquals(
                "ERRO: use pastas de saida diferentes",
                HtmlReport.failureReason(failed, VerificationReport.skipped("generation status TRANSLATION_ERROR"), 600));
    }

    @Test
    void failureReasonNamesTheFunctionThatHitTheWpLimit() {
        VerificationReport timedOut = new VerificationReport(
                "WP_TIMEOUT", 6, 10, 12, 0, 2, null, 1000, null, null,
                List.of(new FunctionResult("M__op1", 10, 12, 0, 2, null), new FunctionResult("M__op2", 0, 0, 0, 0, null)));
        assertEquals(
                "WP exceeded the per-function limit (600 s) on M__op2; later functions were not verified",
                HtmlReport.failureReason(GEN_OK, timedOut, 600));
        assertNull(HtmlReport.failureReason(GEN_OK, completed(5, 5), 600));
    }

    @Test
    void writesIndexAndExamplePagesFromTheJsonReports(@TempDir Path out) throws Exception {
        RunConfig config = new RunConfig("CVC5", 10, 600, true, true, Map.of("plugin", "abc1234"), List.of());
        SpecSummary spec = new SpecSummary(2, 1, 1, 0, 0, 1, 1, 1, 0, 1, 0, 0, 0, 1, 1);
        List<UnitComparison> units = List.of(
                new UnitComparison("logic:x", "EQUAL", 1, null, null, null, null),
                new UnitComparison(
                        "function:M__op", "DIFFERENT", 3,
                        new ClauseSets(
                                List.of("requires a < b"), List.of("ensures <script>"), List.of("ensures c"),
                                List.of(new MeaningClause("ensures <script>", List.of())),
                                List.of(new MeaningClause("ensures c", List.of()))),
                        null, "MEANING", List.of()));
        VerificationReport plugin = completed(4, 5);
        VerificationReport rules = completed(5, 5);
        ExampleReport report = new ExampleReport(
                "Demo", "2026-11-01T12:41:17-03:00", config,
                Map.of("plugin", GEN_OK, "rules", GEN_OK),
                new SpecComparison(
                        spec, units, Map.of("M.acsl", "--- plugin/M.acsl\n+++ rules/M.acsl\n-a\n+b"), Map.of(),
                        Map.of(), Map.of(), Map.of()),
                new VerificationComparison(plugin, rules, List.of(new FunctionComparison("M__op", null, null))));
        Json.write(out.resolve("Demo").resolve("report.json"), report);
        Json.write(
                out.resolve("summary.json"),
                new Summary("2026-11-01T12:41:17-03:00", config,
                        List.of(new SummaryRow("Demo", "OK", "OK", spec, "COMPLETED", 4, 5, "COMPLETED", 5, 5))));

        Path index = HtmlReport.write(out);

        String indexHtml = Files.readString(index);
        assertTrue(indexHtml.contains("href=\"Demo/report.html\""));
        assertTrue(indexHtml.contains("4/5"));
        String exampleHtml = Files.readString(out.resolve("Demo").resolve("report.html"));
        assertTrue(exampleHtml.contains("ensures &lt;script&gt;"));
        assertFalse(exampleHtml.contains("ensures <script>"));
        assertTrue(exampleHtml.contains("data-status=\"DIFFERENT\""));
        assertTrue(exampleHtml.contains("class=\"del\">-a</span>"));
    }

    private static VerificationReport completed(int proved, int total) {
        return new VerificationReport("COMPLETED", 0, proved, total, 0, total - proved, null, 1000, null, null, List.of());
    }
}
