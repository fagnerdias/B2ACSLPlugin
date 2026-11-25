package com.example.gui.generation;

import com.example.gui.model.GeneratedSpecification;
import com.example.gui.model.GenerationMode;
import com.example.gui.model.Generator;
import com.example.gui.model.ProjectInfo;
import com.example.gui.verification.Cancellation;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.EnumMap;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Generates the specifications of a mode, sequentially (Plugin first). A failure of one generator
 * does not discard the result of the other. Must not run on the Event Dispatch Thread.
 */
public final class GenerationService {

    /**
     * @param successes the specifications that were generated
     * @param failures the generators that failed
     */
    public record GenerationOutcome(
            RunDirectory run,
            Map<Generator, GeneratedSpecification> successes,
            Map<Generator, SpecificationGenerationException> failures) {

        public boolean complete() {
            return failures.isEmpty() && successes.size() == run.mode().generators().size();
        }
    }

    private GenerationService() {}

    public static SpecificationGenerator generatorFor(Generator generator) {
        return generator == Generator.PLUGIN ? new PluginSpecificationGenerator() : new AtlSpecificationGenerator();
    }

    public static GenerationOutcome generate(
            ProjectInfo project, GenerationMode mode, Consumer<String> log, Cancellation cancel) throws IOException {
        RunDirectory run = RunDirectory.create(project.folder(), mode);
        Map<Generator, GeneratedSpecification> successes = new EnumMap<>(Generator.class);
        Map<Generator, SpecificationGenerationException> failures = new EnumMap<>(Generator.class);
        try (BufferedWriter file = Files.newBufferedWriter(run.generationLog(), StandardCharsets.UTF_8)) {
            for (Generator g : mode.generators()) {
                if (cancel.isCancelled()) {
                    break;
                }
                Consumer<String> sideLog = line -> {
                    String prefixed = g.logPrefix() + " " + line;
                    log.accept(prefixed);
                    write(file, prefixed);
                };
                sideLog.accept("Generating ACSL for " + project.functions().size() + " operation(s)...");
                try {
                    GeneratedSpecification spec = generatorFor(g).generate(project, run, sideLog, cancel);
                    successes.put(g, spec);
                    sideLog.accept("Specification written to " + spec.displayPath());
                } catch (SpecificationGenerationException e) {
                    failures.put(g, e);
                    sideLog.accept("ERROR: " + e.getMessage());
                    sideLog.accept("Specification generation aborted. No specification was written.");
                }
            }
        }
        return new GenerationOutcome(run, successes, failures);
    }

    private static void write(BufferedWriter file, String line) {
        synchronized (file) {
            try {
                file.write(line);
                file.newLine();
                file.flush();
            } catch (IOException e) {
                // the on-screen log still has the line
            }
        }
    }
}
