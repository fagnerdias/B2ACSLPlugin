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

    /**
     * A clause whose meaning has no counterpart on the other side.
     *
     * @param conjuncts the conjuncts of {@code clause} that have no counterpart, when the clause has
     *     several and only some of them lack one (empty otherwise)
     */
    public record MeaningClause(String clause, List<String> conjuncts) {}

    /**
     * Clauses of a unit. {@code equal}/{@code onlyPlugin}/{@code onlyRules} compare the text (in
     * canonical form); {@code meaningPlugin}/{@code meaningRules} are the clauses of {@code
     * onlyPlugin}/{@code onlyRules} whose meaning the other side does not have. The rest of {@code
     * onlyPlugin}/{@code onlyRules} differ only in form.
     */
    public record ClauseSets(
            List<String> equal,
            List<String> onlyPlugin,
            List<String> onlyRules,
            List<MeaningClause> meaningPlugin,
            List<MeaningClause> meaningRules) {}

    /**
     * @param status {@code EQUAL}, {@code DIFFERENT}, {@code ONLY_PLUGIN} or {@code ONLY_RULES}
     * @param clauses omitted for {@code EQUAL} units (only {@code clauseCount} is kept)
     * @param rulesKey key of the rules unit, when it has another name than the plugin's
     * @param difference {@code FORM} (same meaning, written differently) or {@code MEANING}; omitted
     *     for {@code EQUAL} units
     * @param formRules ids of the rules that explain the form differences (see {@code MeaningRule})
     */
    public record UnitComparison(
            String key,
            String status,
            int clauseCount,
            ClauseSets clauses,
            String rulesKey,
            String difference,
            List<String> formRules) {}

    /**
     * Unit and clause counts. The first eight compare the text; the {@code Integer} ones (absent in
     * reports written before the meaning comparison existed) split the differences by meaning.
     */
    public record SpecSummary(
            int units,
            int equalUnits,
            int differentUnits,
            int onlyPluginUnits,
            int onlyRulesUnits,
            int clausesEqual,
            int clausesOnlyPlugin,
            int clausesOnlyRules,
            Integer sameMeaningUnits,
            Integer meaningBothUnits,
            Integer meaningOnlyPluginUnits,
            Integer meaningOnlyRulesUnits,
            Integer clausesSameMeaning,
            Integer clausesMeaningPlugin,
            Integer clausesMeaningRules) {

        /** Whether the meaning fields are present. */
        public boolean hasMeaning() {
            return sameMeaningUnits != null;
        }

        public int meaningUnits() {
            return hasMeaning() ? meaningBothUnits + meaningOnlyPluginUnits + meaningOnlyRulesUnits : 0;
        }
    }

    /**
     * @param formRules for each rule that explains form differences, the number of units it explains
     * @param renamings names taken to be the same symbol: plugin name → rules name (rule)
     * @param unparsedClauses clauses compared as text because the parser did not understand them,
     *     with the reason (at most {@code 200})
     */
    public record SpecComparison(
            SpecSummary summary,
            List<UnitComparison> units,
            Map<String, String> textDiff,
            Map<String, List<String>> filesOnlyIn,
            Map<String, Integer> formRules,
            Map<String, String> renamings,
            Map<String, String> unparsedClauses) {}

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
