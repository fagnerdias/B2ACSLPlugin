package com.example.gui.generation;

import com.example.comparator.util.Json;
import com.example.gui.model.GeneratedSpecification;
import com.example.gui.model.Generator;
import com.example.gui.model.ProjectInfo;
import com.example.gui.parsing.GenerationErrorParser;
import com.example.gui.verification.Cancellation;
import com.example.gui.verification.ProcessSupervisor;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/** Runs {@link GenerationChildMain} for one generator and turns its result into the GUI model. */
abstract class ChildJvmSpecificationGenerator implements SpecificationGenerator {

    /** Generator-specific arguments of the child (e.g. the rules jar). */
    protected abstract List<String> extraArgs() throws IOException;

    @Override
    public GeneratedSpecification generate(ProjectInfo project, RunDirectory run, Consumer<String> log, Cancellation cancel)
            throws SpecificationGenerationException {
        Generator g = generator();
        String stage = "Specification generation (" + g.label() + ")";
        Path resultFile = run.resultFile(g);
        List<String> args = new ArrayList<>(List.of(
                "--generator", g.id(),
                "--project", project.folder().toAbsolutePath().toString(),
                "--work", run.workDir(g).toString(),
                "--publish", run.publishedFile(g).toString(),
                "--result", resultFile.toString()));
        ProcessSupervisor.Outcome outcome;
        try {
            args.addAll(extraArgs());
            outcome = ProcessSupervisor.run(ChildJvm.command(GenerationChildMain.class, args), run.root(), 0, log, cancel);
        } catch (IOException e) {
            throw new SpecificationGenerationException(
                    g, stage, "ERROR", "The generator could not be started: " + e.getMessage(), null, null, -1,
                    e.toString(), false);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw interrupted(g, stage, "");
        }
        if (outcome.cancelled()) {
            throw interrupted(g, stage, outcome.output());
        }
        ChildResult result;
        try {
            result = Json.read(resultFile, ChildResult.class);
        } catch (IOException e) {
            throw new SpecificationGenerationException(
                    g, stage, "ERROR",
                    "The generator stopped without a result (exit code " + outcome.exitCode() + ").",
                    null, null, outcome.exitCode(), tail(outcome.output()), false);
        }
        if (!result.ok()) {
            String output = result.output() == null || result.output().isBlank() ? tail(outcome.output()) : result.output();
            String message = result.message() == null || result.message().isBlank()
                    ? result.status() : result.message().trim();
            throw new SpecificationGenerationException(
                    g,
                    result.stage(),
                    result.status(),
                    message,
                    GenerationErrorParser.function(message, output),
                    g == Generator.ATL ? GenerationErrorParser.rule(message, outcome.output()) : null,
                    result.exitCode(),
                    output,
                    false);
        }
        if (!Files.isRegularFile(run.publishedFile(g))) {
            throw new SpecificationGenerationException(
                    g, "Publishing merged_code.c", "ERROR", "merged_code.c was not written.", null, null, -1,
                    tail(outcome.output()), false);
        }
        return new GeneratedSpecification(
                g, run.publishedFile(g), run.displayPath(g), result.functions(), result.warnings(), result.elapsedMs());
    }

    private static SpecificationGenerationException interrupted(Generator g, String stage, String output) {
        return new SpecificationGenerationException(
                g, stage, "INTERRUPTED", "The generation was interrupted.", null, null, -1, tail(output), true);
    }

    /** The last lines of a long output. */
    static String tail(String output) {
        if (output == null) {
            return "";
        }
        List<String> lines = output.lines().toList();
        int from = Math.max(0, lines.size() - 60);
        return String.join("\n", lines.subList(from, lines.size()));
    }
}
