package com.example.comparator.report;

import java.util.List;
import java.util.Map;

/**
 * Records serialized to the JSON reports ({@code report.json} per example, {@code summary.json}
 * per run, {@code side-result.json} per tool). Only strings/numbers/collections, so the files stay
 * readable by any tool.
 */
public final class Reports {

    private Reports() {}

    /** What one tool produced from the BXML input. */
    public record GenerationReport(
            String status,
            int exitCode,
            long elapsedMs,
            List<String> files,
            List<String> acslImportOrder,
            List<String> warnings,
            String message) {}

    /** WP numbers of one function (per-operation mode) or of the whole file. */
    public record FunctionResult(
            String name, int proved, int total, int failures, int timeouts, String smokeTests) {}

    /** Frama-C ({@code -acsl-import} + {@code -wp}) result of one tool's specification. */
    public record VerificationReport(
            String status,
            int exitCode,
            int proved,
            int total,
            int failures,
            int timeouts,
            String smokeTests,
            long elapsedMs,
            String mergedCode,
            String importError,
            List<FunctionResult> functions) {

        public static VerificationReport skipped(String reason) {
            return new VerificationReport("SKIPPED", 0, 0, 0, 0, 0, null, 0, null, reason, List.of());
        }
    }

    /** Written by each side's child process ({@code <workspace>/<side>/side-result.json}). */
    public record SideResult(String side, GenerationReport generation, VerificationReport verification) {}

    public record ClauseSets(List<String> equal, List<String> onlyPlugin, List<String> onlyRules) {}

    /**
     * @param status {@code EQUAL}, {@code DIFFERENT}, {@code ONLY_PLUGIN} or {@code ONLY_RULES}
     * @param clauses omitted for {@code EQUAL} units (only {@code clauseCount} is kept)
     */
    public record UnitComparison(String key, String status, int clauseCount, ClauseSets clauses) {}

    public record SpecSummary(
            int units,
            int equalUnits,
            int differentUnits,
            int onlyPluginUnits,
            int onlyRulesUnits,
            int clausesEqual,
            int clausesOnlyPlugin,
            int clausesOnlyRules) {}

    public record SpecComparison(
            SpecSummary summary,
            List<UnitComparison> units,
            Map<String, String> textDiff,
            Map<String, List<String>> filesOnlyIn) {}

    public record FunctionComparison(String name, FunctionResult plugin, FunctionResult rules) {}

    public record VerificationComparison(
            VerificationReport plugin, VerificationReport rules, List<FunctionComparison> functions) {}

    /** Settings shared by both sides, plus the revisions of every component involved. */
    public record RunConfig(
            String prover,
            int timeout,
            int functionTimeout,
            boolean perOperation,
            boolean verify,
            Map<String, String> commits,
            List<String> warnings) {}

    public record ExampleReport(
            String example,
            String generatedAt,
            RunConfig config,
            Map<String, GenerationReport> generation,
            SpecComparison spec,
            VerificationComparison verification) {}

    public record SummaryRow(
            String example,
            String pluginGeneration,
            String rulesGeneration,
            SpecSummary spec,
            String pluginVerification,
            int pluginProved,
            int pluginTotal,
            String rulesVerification,
            int rulesProved,
            int rulesTotal) {}

    public record Summary(String generatedAt, RunConfig config, List<SummaryRow> examples) {}
}
