package com.example.comparator;

import com.example.comparator.report.Reports.ExampleReport;
import com.example.comparator.report.Reports.SpecComparison;
import com.example.comparator.report.Reports.SpecSummary;
import com.example.comparator.report.Reports.Summary;
import com.example.comparator.report.Reports.SummaryRow;
import com.example.comparator.spec.SpecDiff;
import com.example.comparator.util.Json;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * {@code --respec DIR}: compares again the specification snapshots ({@code
 * <example>/{plugin,rules}/spec}) of an existing output directory and rewrites the {@code spec}
 * part of its {@code report.json} files and of {@code summary.json}. Generation and verification
 * results are kept as they are, so a change to the specification comparison can be applied to an
 * earlier run without running the translators or Frama-C again.
 */
final class Respec {

    private Respec() {}

    static void run(Path outDir) throws IOException {
        Map<String, SpecSummary> updated = new HashMap<>();
        List<Path> dirs;
        try (Stream<Path> entries = Files.list(outDir)) {
            dirs = entries.filter(d -> Files.isRegularFile(d.resolve(Workspace.REPORT_FILE))).sorted().toList();
        }
        for (Path dir : dirs) {
            Workspace ws = new Workspace(dir.toAbsolutePath().normalize());
            ExampleReport old = Json.read(ws.report(), ExampleReport.class);
            SpecComparison spec = SpecDiff.compare(
                    ws.specSnapshot(Side.PLUGIN), ws.specSnapshot(Side.RULES), SpecDiff.machineNames(ws.bdp(Side.PLUGIN)));
            Json.write(ws.report(), new ExampleReport(
                    old.example(), old.generatedAt(), old.config(), old.generation(), spec, old.verification()));
            updated.put(old.example(), spec.summary());
            SpecSummary s = spec.summary();
            System.out.printf(
                    "[comparator] %-28s units %4d: %4d equal, %4d same meaning, %4d different meaning%n",
                    old.example(), s.units(), s.equalUnits(), s.sameMeaningUnits(), s.meaningUnits());
        }
        Path summaryFile = outDir.resolve("summary.json");
        if (Files.isRegularFile(summaryFile)) {
            Summary summary = Json.read(summaryFile, Summary.class);
            List<SummaryRow> rows = new ArrayList<>();
            for (SummaryRow row : summary.examples()) {
                rows.add(new SummaryRow(
                        row.example(),
                        row.pluginGeneration(),
                        row.rulesGeneration(),
                        updated.getOrDefault(row.example(), row.spec()),
                        row.pluginVerification(),
                        row.pluginProved(),
                        row.pluginTotal(),
                        row.rulesVerification(),
                        row.rulesProved(),
                        row.rulesTotal()));
            }
            Json.write(summaryFile, new Summary(summary.generatedAt(), summary.config(), rows));
        }
    }
}
