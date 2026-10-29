package com.example.comparator;

import com.example.comparator.report.Reports.ExampleReport;
import com.example.comparator.report.Reports.FunctionComparison;
import com.example.comparator.report.Reports.FunctionResult;
import com.example.comparator.report.Reports.GenerationReport;
import com.example.comparator.report.Reports.RunConfig;
import com.example.comparator.report.Reports.SideResult;
import com.example.comparator.report.Reports.SpecComparison;
import com.example.comparator.report.Reports.SummaryRow;
import com.example.comparator.report.Reports.VerificationComparison;
import com.example.comparator.report.Reports.VerificationReport;
import com.example.comparator.spec.SpecDiff;
import com.example.comparator.util.Json;
import com.example.comparator.util.ProcessRunner;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Compares one example: prepares the {@link Workspace}, runs each side in its own child JVM
 * ({@link SideRunner}), sequentially so the provers never compete for CPU, then compares the
 * specification snapshots and writes {@code report.json}.
 */
final class ComparisonRunner {

    private final ComparatorConfig config;
    private final RunConfig runConfig;

    ComparisonRunner(ComparatorConfig config, RunConfig runConfig) {
        this.config = config;
        this.runConfig = runConfig;
    }

    SummaryRow run(String example) throws IOException, InterruptedException {
        Workspace ws = Workspace.prepare(config.examplesDir().resolve(example), config.outDir().resolve(example));
        Map<Side, SideResult> results = new LinkedHashMap<>();
        for (Side side : Side.values()) {
            System.out.println("[comparator] " + example + ": " + side.id() + " ...");
            results.put(side, runSide(side, ws));
        }
        SideResult plugin = results.get(Side.PLUGIN);
        SideResult rules = results.get(Side.RULES);

        SpecComparison spec = SpecDiff.compare(ws.specSnapshot(Side.PLUGIN), ws.specSnapshot(Side.RULES));
        Map<String, GenerationReport> generation = new LinkedHashMap<>();
        generation.put(Side.PLUGIN.id(), plugin.generation());
        generation.put(Side.RULES.id(), rules.generation());
        VerificationComparison verification =
                new VerificationComparison(
                        plugin.verification(),
                        rules.verification(),
                        compareFunctions(plugin.verification(), rules.verification()));
        Json.write(
                ws.report(),
                new ExampleReport(example, OffsetDateTime.now().toString(), runConfig, generation, spec, verification));

        return new SummaryRow(
                example,
                plugin.generation().status(),
                rules.generation().status(),
                spec.summary(),
                plugin.verification().status(),
                plugin.verification().proved(),
                plugin.verification().total(),
                rules.verification().status(),
                rules.verification().proved(),
                rules.verification().total());
    }

    /** Launches the child JVM for {@code side}; a crash becomes an {@code ERROR} result, never an exception. */
    private SideResult runSide(Side side, Workspace ws) throws IOException, InterruptedException {
        List<String> command = new ArrayList<>();
        command.add(ProcessRunner.javaExecutable());
        command.add("-Djava.awt.headless=true");
        System.getProperties().stringPropertyNames().stream()
                .filter(name -> name.startsWith("b2acsl."))
                .sorted()
                .forEach(name -> command.add("-D" + name + "=" + System.getProperty(name)));
        command.addAll(List.of("-cp", absoluteClassPath(), ComparatorMain.class.getName()));
        command.addAll(config.childArgs(side, ws));

        ProcessRunner.Result result = ProcessRunner.run(command, ws.sideRoot(side), ws.sideLog(side), 0);
        if (Files.isRegularFile(ws.sideResult(side))) {
            return Json.read(ws.sideResult(side), SideResult.class);
        }
        String message = "side process exited with code " + result.exitCode() + "; see " + ws.sideLog(side);
        Files.createDirectories(ws.specSnapshot(side));
        return new SideResult(
                side.id(),
                new GenerationReport("ERROR", result.exitCode(), 0, List.of(), List.of(), List.of(), message),
                VerificationReport.skipped(message));
    }

    /** The child runs in the side's directory, so relative class path entries must be resolved first. */
    private static String absoluteClassPath() {
        return java.util.Arrays.stream(System.getProperty("java.class.path").split(java.io.File.pathSeparator))
                .map(entry -> Path.of(entry).toAbsolutePath().normalize().toString())
                .collect(java.util.stream.Collectors.joining(java.io.File.pathSeparator));
    }

    /** Pairs the per-function WP results of both sides by function name. */
    static List<FunctionComparison> compareFunctions(VerificationReport plugin, VerificationReport rules) {
        Map<String, FunctionResult> p = byName(plugin.functions());
        Map<String, FunctionResult> r = byName(rules.functions());
        Set<String> names = new LinkedHashSet<>(p.keySet());
        names.addAll(r.keySet());
        List<FunctionComparison> out = new ArrayList<>();
        for (String name : names) {
            out.add(new FunctionComparison(name, p.get(name), r.get(name)));
        }
        return out;
    }

    private static Map<String, FunctionResult> byName(List<FunctionResult> functions) {
        Map<String, FunctionResult> out = new LinkedHashMap<>();
        if (functions != null) {
            for (FunctionResult f : functions) {
                out.put(f.name(), f);
            }
        }
        return out;
    }

    static Path summaryFile(ComparatorConfig config) {
        return config.outDir().resolve("summary.json");
    }
}
