package com.example.gui.generation;

import com.example.FramaCRunner;
import com.example.FramaCRunner.MergeOutcome;
import com.example.comparator.Workspace;
import com.example.comparator.gen.GeneratedSpec;
import com.example.comparator.gen.PluginSpecGenerator;
import com.example.comparator.gen.RulesSpecGenerator;
import com.example.comparator.gen.SpecGenerator;
import com.example.comparator.util.Json;
import com.example.gui.model.Generator;
import com.example.ui.PreVerificationErrorDialog;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Headless child process that generates ONE specification: copies the project into a fresh
 * workspace, runs the Plugin or the ATL Rules, then Frama-C {@code -acsl-import} + post-processing,
 * and publishes {@code merged_code.c}. A separate JVM keeps the translator's static state and
 * FramaCRunner's Swing dialogs out of the GUI process, and lets the GUI kill it at any time.
 *
 * <p>{@code --generator plugin|atl --project DIR --work DIR --publish FILE --result FILE
 * [--rules-jar JAR --lib-dir DIR --rules-timeout SECONDS]}
 */
public final class GenerationChildMain {

    private GenerationChildMain() {}

    public static void main(String[] args) throws Exception {
        Map<String, String> a = parse(args);
        Generator generator = Generator.fromId(a.get("--generator"));
        Path project = Path.of(a.get("--project"));
        Path work = Path.of(a.get("--work"));
        Path publish = Path.of(a.get("--publish"));
        Path resultFile = Path.of(a.get("--result"));
        long start = System.nanoTime();
        ChildResult result = run(generator, project, work, publish, a, start);
        Json.write(resultFile, result);
        System.out.flush();
        System.exit(result.ok() ? 0 : 1);
    }

    private static ChildResult run(
            Generator generator, Path project, Path work, Path publish, Map<String, String> a, long start) {
        String label = generator.label();
        String stage = "Preparing the workspace";
        try {
            Workspace ws = Workspace.prepare(project, work);
            stage = "Specification generation (" + label + ")";
            System.out.println(
                    generator == Generator.PLUGIN
                            ? "Generating the ACSL annotations with the specification plugin..."
                            : "Applying the transformation rules...");
            SpecGenerator specGenerator =
                    generator == Generator.PLUGIN
                            ? new PluginSpecGenerator()
                            : new RulesSpecGenerator(
                                    Path.of(a.get("--rules-jar")),
                                    Path.of(a.get("--lib-dir")),
                                    Integer.parseInt(a.getOrDefault("--rules-timeout", "300")),
                                    System.out::println);
            GeneratedSpec spec = specGenerator.generate(ws);
            if (!spec.succeeded()) {
                return failure(generator, stage, spec.status(), spec.exitCode(), spec.message(), spec.message(), null,
                        spec.warnings(), start);
            }
            stage = "Frama-C -acsl-import (" + label + ")";
            System.out.println("Merging the annotations with the C code (frama-c -acsl-import)...");
            MergeOutcome merge = FramaCRunner.buildMergedCode(
                    spec.acslImportFiles(), spec.ghostCi(), spec.cFiles(), spec.cDir(), spec.specificationUsedTypes());
            if (merge.status() != FramaCRunner.VerificationStatus.COMPLETED) {
                String output = merge.importOutput();
                String message = switch (merge.status()) {
                    case NO_C_FILES -> "No C file was found in lang/c.";
                    case NO_ACSL_FILES -> "The generator produced no .acsl file.";
                    case IMPORT_TIMEOUT -> "Frama-C -acsl-import did not finish within 120 s.";
                    default -> PreVerificationErrorDialog.extractHeadline(output);
                };
                return failure(generator, stage, merge.status().name(), merge.exitCode(), message, output,
                        merge.importCommand(), spec.warnings(), start);
            }
            stage = "Publishing merged_code.c";
            Files.createDirectories(publish.toAbsolutePath().getParent());
            Files.copy(merge.mergedCode(), publish, StandardCopyOption.REPLACE_EXISTING);
            List<String> functions = FramaCRunner.resolveOperationFunctionNamesForWp(spec.acslImportFiles());
            return new ChildResult(true, generator.id(), stage, "OK", 0, null, null, null, spec.warnings(), functions,
                    elapsed(start));
        } catch (Throwable e) {
            StringWriter trace = new StringWriter();
            e.printStackTrace(new PrintWriter(trace));
            e.printStackTrace();
            return failure(generator, stage, "ERROR", -1, e.toString(), trace.toString(), null, List.of(), start);
        }
    }

    private static ChildResult failure(
            Generator generator,
            String stage,
            String status,
            int exitCode,
            String message,
            String output,
            String command,
            List<String> warnings,
            long start) {
        return new ChildResult(false, generator.id(), stage, status, exitCode, message, output, command,
                warnings == null ? List.of() : warnings, List.of(), elapsed(start));
    }

    private static long elapsed(long start) {
        return (System.nanoTime() - start) / 1_000_000;
    }

    private static Map<String, String> parse(String[] args) {
        Map<String, String> out = new HashMap<>();
        for (int i = 0; i + 1 < args.length; i += 2) {
            out.put(args[i], args[i + 1]);
        }
        for (String required : List.of("--generator", "--project", "--work", "--publish", "--result")) {
            if (!out.containsKey(required)) {
                throw new IllegalArgumentException("missing " + required);
            }
        }
        return out;
    }
}
