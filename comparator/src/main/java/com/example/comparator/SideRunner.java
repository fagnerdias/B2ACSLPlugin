package com.example.comparator;

import com.example.comparator.gen.GeneratedSpec;
import com.example.comparator.gen.PluginSpecGenerator;
import com.example.comparator.gen.RulesSpecGenerator;
import com.example.comparator.gen.SpecGenerator;
import com.example.comparator.report.Reports.SideResult;
import com.example.comparator.report.Reports.VerificationReport;
import com.example.comparator.spec.SpecDiff;
import com.example.comparator.util.Json;
import com.example.comparator.verify.SharedVerifier;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Child-process side of the comparison: generates and verifies ONE side of ONE example, then
 * writes {@code side-result.json}. Each side runs in its own JVM, as the plugin does in a normal
 * run, so static state of the translator never leaks between sides or examples.
 */
final class SideRunner {

    private SideRunner() {}

    static int run(ComparatorConfig config) throws IOException {
        Workspace ws = new Workspace(config.workspace());
        Side side = config.side();
        String example = ws.root().getFileName().toString();

        SpecGenerator generator =
                side == Side.PLUGIN
                        ? new PluginSpecGenerator()
                        : new RulesSpecGenerator(config.rulesJar(), config.libDir(), config.rulesTimeoutSeconds());
        GeneratedSpec spec = generator.generate(ws);
        snapshotSpec(ws.cDir(side), ws.specSnapshot(side));

        VerificationReport verification;
        if (!config.verify()) {
            verification = VerificationReport.skipped("--no-verify");
        } else if (!spec.succeeded()) {
            verification = VerificationReport.skipped("generation status " + spec.status());
        } else {
            SharedVerifier verifier =
                    new SharedVerifier(
                            config.prover(), config.timeoutSeconds(), config.functionTimeoutSeconds(), config.perOperation());
            verification = verifier.verify(spec, example);
        }
        Json.write(ws.sideResult(side), new SideResult(side.id(), spec.toReport(), verification));
        return 0;
    }

    /** Copies the specification files before verification, which may rename symbols in place. */
    private static void snapshotSpec(Path cDir, Path snapshot) throws IOException {
        Files.createDirectories(snapshot);
        for (Path f : SpecDiff.specFiles(cDir)) {
            Files.copy(f, snapshot.resolve(f.getFileName().toString()));
        }
    }
}
