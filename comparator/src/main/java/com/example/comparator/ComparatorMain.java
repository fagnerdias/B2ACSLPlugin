package com.example.comparator;

import com.example.comparator.report.Reports.RunConfig;
import com.example.comparator.report.Reports.SpecSummary;
import com.example.comparator.report.Reports.Summary;
import com.example.comparator.report.Reports.SummaryRow;
import com.example.comparator.util.GitInfo;
import com.example.comparator.util.Json;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Entry point of the B2ACSLPlugin × B2ACSLRules comparator.
 *
 * <p>Without {@code --side} it is the orchestrator: for each example it runs {@link
 * ComparisonRunner} and finally writes {@code summary.json}. With {@code --side} (used internally)
 * it runs one side of one example ({@link SideRunner}).
 */
public final class ComparatorMain {

    private ComparatorMain() {}

    public static void main(String[] args) {
        if (List.of(args).contains("--help")) {
            System.out.println(ComparatorConfig.USAGE);
            return;
        }
        System.setProperty("java.awt.headless", "true");
        ComparatorConfig config;
        try {
            config = ComparatorConfig.parse(args);
        } catch (IllegalArgumentException e) {
            System.err.println("[comparator] " + e.getMessage());
            System.err.println(ComparatorConfig.USAGE);
            System.exit(1);
            return;
        }
        try {
            System.exit(config.side() != null ? SideRunner.run(config) : orchestrate(config));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            System.exit(130);
        } catch (Exception e) {
            System.err.println("[comparator] " + e);
            System.exit(2);
        }
    }

    private static int orchestrate(ComparatorConfig config) throws IOException, InterruptedException {
        List<String> examples = config.all() ? allExamples(config.examplesDir()) : config.examples();
        RunConfig runConfig = runConfig(config);
        runConfig.warnings().forEach(w -> System.err.println("[comparator] WARNING: " + w));
        Files.createDirectories(config.outDir());
        System.out.println("[comparator] output: " + config.outDir());

        ComparisonRunner runner = new ComparisonRunner(config, runConfig);
        List<SummaryRow> rows = new ArrayList<>();
        for (String example : examples) {
            if (!Files.isDirectory(config.examplesDir().resolve(example).resolve("bdp"))) {
                System.err.println("[comparator] skipping " + example + ": no bdp/ folder in " + config.examplesDir());
                continue;
            }
            rows.add(runner.run(example));
            // rewritten after every example, so an interrupted batch still leaves a summary
            Json.write(
                    ComparisonRunner.summaryFile(config),
                    new Summary(OffsetDateTime.now().toString(), runConfig, rows));
        }
        printTable(rows);
        System.out.println("[comparator] summary: " + ComparisonRunner.summaryFile(config));
        return 0;
    }

    private static List<String> allExamples(Path examplesDir) throws IOException {
        try (Stream<Path> dirs = Files.list(examplesDir)) {
            return dirs.filter(d -> Files.isDirectory(d.resolve("bdp")))
                    .map(d -> d.getFileName().toString())
                    .sorted(String.CASE_INSENSITIVE_ORDER)
                    .toList();
        }
    }

    /** Shared settings plus the revision of every component, and a warning if the two B2ACSLLib copies differ. */
    private static RunConfig runConfig(ComparatorConfig config) {
        Path rulesRoot = config.rulesJar().getParent() == null ? null : config.rulesJar().getParent().getParent();
        Map<String, String> commits = new LinkedHashMap<>();
        commits.put("plugin", GitInfo.describe(Path.of("").toAbsolutePath()));
        commits.put("rules", GitInfo.describe(rulesRoot));
        commits.put("libPlugin", GitInfo.describe(config.libDir().getParent()));
        Path rulesLib = rulesRoot == null ? null : rulesRoot.resolve("lib").resolve("ACSL2BMethodLib");
        boolean rulesUsesSubmodule = rulesLib != null && Files.isDirectory(rulesLib.resolve("B2ACSLLib"));
        commits.put("libRules", rulesUsesSubmodule ? GitInfo.describe(rulesLib)
                : "not initialized (git submodule update --init --recursive)");

        List<String> warnings = new ArrayList<>();
        if (!commits.get("libPlugin").equals(commits.get("libRules"))) {
            warnings.add(
                    "B2ACSLLib revisions differ (plugin " + commits.get("libPlugin") + ", rules "
                            + commits.get("libRules") + "); both sides are verified with the plugin's copy, "
                            + "but the rules translator's include table follows its own copy");
        }
        if (!Files.isRegularFile(config.rulesJar())) {
            warnings.add("B2ACSLRules jar not found: " + config.rulesJar() + " (run: make build-rules)");
        }
        return new RunConfig(
                config.prover(),
                config.timeoutSeconds(),
                config.functionTimeoutSeconds(),
                config.perOperation(),
                config.verify(),
                commits,
                warnings);
    }

    private static void printTable(List<SummaryRow> rows) {
        String format = "%-28s %-17s %-17s %-23s %-14s %-14s%n";
        System.out.println();
        System.out.printf(format, "example", "plugin gen", "rules gen", "units eq/diff/P/R", "plugin WP", "rules WP");
        for (SummaryRow row : rows) {
            SpecSummary s = row.spec();
            System.out.printf(
                    format,
                    row.example(),
                    row.pluginGeneration(),
                    row.rulesGeneration(),
                    s.equalUnits() + "/" + s.differentUnits() + "/" + s.onlyPluginUnits() + "/" + s.onlyRulesUnits(),
                    wp(row.pluginVerification(), row.pluginProved(), row.pluginTotal()),
                    wp(row.rulesVerification(), row.rulesProved(), row.rulesTotal()));
        }
        System.out.println();
    }

    private static String wp(String status, int proved, int total) {
        return "SKIPPED".equals(status) ? status : proved + "/" + total;
    }
}
