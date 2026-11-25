package com.example.gui.ui;

import com.example.gui.generation.GenerationService.GenerationOutcome;
import com.example.gui.generation.SpecificationGenerationException;
import com.example.gui.model.ErrorReport;
import com.example.gui.model.ErrorReport.PipelineStep;
import com.example.gui.model.ErrorReport.StepState;
import com.example.gui.model.GeneratedSpecification;
import com.example.gui.model.Generator;
import com.example.gui.model.ProjectInfo;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Builds the texts of the error dialog for each kind of failure. */
public final class ErrorReports {

    private static final String NONE = "—";

    private ErrorReports() {}

    /** A specification (or both) could not be generated in step 2. */
    public static ErrorReport generationFailed(ProjectInfo project, GenerationOutcome outcome) {
        List<Generator> order = outcome.run().mode().generators();
        Generator firstFailed = order.stream().filter(outcome.failures()::containsKey).findFirst().orElseThrow();
        SpecificationGenerationException e = outcome.failures().get(firstFailed);
        boolean compare = outcome.run().mode().isCompare();

        List<PipelineStep> steps = new ArrayList<>();
        steps.add(new PipelineStep(
                "Project loaded (" + project.name() + ", " + project.functions().size() + " "
                        + (project.functions().size() == 1 ? "function" : "functions") + ")",
                StepState.DONE));
        for (Generator g : order) {
            GeneratedSpecification ok = outcome.successes().get(g);
            SpecificationGenerationException f = outcome.failures().get(g);
            if (ok != null) {
                steps.add(new PipelineStep(g.label() + " specification generated (" + ok.displayPath() + ")", StepState.DONE));
            } else if (f != null) {
                steps.add(new PipelineStep(g.label() + " specification failed: " + shortCause(f), StepState.FAILED));
            } else {
                steps.add(new PipelineStep(g.label() + " specification not generated", StepState.NOT_STARTED));
            }
        }
        steps.add(new PipelineStep("Verification not started", StepState.NOT_STARTED));

        String forWhat = e.function() != null ? " for " + e.function() : "";
        String sentence = outcome.failures().size() > 1
                ? "No specification could be generated, so the verification was not started."
                : "The " + firstFailed.label() + " specification could not be generated" + forWhat
                        + ", so the verification was not started.";
        if (e.interrupted()) {
            sentence = "The " + firstFailed.label() + " specification generation was interrupted, so the verification"
                    + " was not started.";
        }

        Map<String, String> details = new LinkedHashMap<>();
        details.put("Stage", e.stage());
        details.put("Function", e.function() == null ? NONE : e.function());
        if (firstFailed == Generator.ATL) {
            details.put("Rule", e.rule() == null ? NONE : e.rule());
        }
        details.put("Exit code", e.exitCode() < 0 ? NONE : Integer.toString(e.exitCode()));
        details.put("Cause", e.status());

        StringBuilder output = new StringBuilder();
        for (Generator g : order) {
            SpecificationGenerationException f = outcome.failures().get(g);
            if (f == null) {
                continue;
            }
            if (outcome.failures().size() > 1) {
                output.append("== ").append(g.label()).append(" ==\n");
            }
            output.append(f.getMessage()).append('\n');
            if (!f.output().isBlank() && !f.output().trim().equals(f.getMessage().trim())) {
                output.append('\n').append(f.output().strip()).append('\n');
            }
            if (outcome.failures().size() > 1) {
                output.append('\n');
            }
        }

        Generator continueWith = null;
        if (compare && outcome.failures().size() == 1) {
            Generator other = firstFailed.other();
            if (outcome.successes().containsKey(other)) {
                continueWith = other;
            }
        }
        return new ErrorReport(
                "Specification Generation Error",
                "Specification generation failed",
                sentence,
                steps,
                details,
                output.toString().strip(),
                continueWith,
                true);
    }

    static String shortCause(SpecificationGenerationException e) {
        String cause = switch (e.status()) {
            case "TRANSLATION_ERROR" -> "transformation error";
            case "GENERATION_ERROR" -> "translation error";
            case "IMPORT_ERROR" -> "Frama-C could not parse the generated ACSL";
            case "IMPORT_TIMEOUT", "TIMEOUT" -> "timed out";
            case "NOT_AVAILABLE" -> "rules not found";
            case "NO_C_FILES" -> "no C code";
            case "NO_ACSL_FILES" -> "no ACSL generated";
            case "INTERRUPTED" -> "interrupted";
            default -> "error";
        };
        return e.function() == null ? cause : cause + " in " + e.function();
    }

    /** Frama-C is not installed (start-up check or when starting WP). */
    public static ErrorReport framaCNotFound(String detail) {
        Map<String, String> details = new LinkedHashMap<>();
        details.put("Stage", "Tool check");
        details.put("Command", "frama-c -version");
        details.put("Exit code", NONE);
        return new ErrorReport(
                "Frama-C Not Found",
                "Frama-C was not found",
                "The frama-c command could not be run, so no verification can start.",
                List.of(
                        new PipelineStep("Looking for frama-c on the PATH", StepState.FAILED),
                        new PipelineStep("Verification not started", StepState.NOT_STARTED)),
                details,
                (detail == null ? "" : detail + "\n\n") + "PATH=" + System.getenv("PATH"),
                null,
                true);
    }

    /** The folder given on the command line is missing or is not a usable project. */
    public static ErrorReport invalidProject(java.nio.file.Path folder, String problem) {
        Map<String, String> details = new LinkedHashMap<>();
        details.put("Stage", "Reading the project");
        details.put("Folder", folder == null ? NONE : folder.toString());
        return new ErrorReport(
                "Invalid Project",
                folder == null ? "No project to verify" : "This folder is not a B project",
                problem + " Start the tool with the project folder (or its bdp/ folder) as argument.",
                List.of(
                        new PipelineStep("Reading the project", StepState.FAILED),
                        new PipelineStep("Specification not generated", StepState.NOT_STARTED)),
                details,
                "java -jar gui/target/b2acsl-gui-0.1.0-all.jar <project folder>\n"
                        + "make gui PROJECT=<project folder>",
                null,
                false);
    }

    /** WP could not run or was interrupted during the verification. */
    public static ErrorReport verificationFailed(String title, String sentence, String output, boolean interrupted) {
        Map<String, String> details = new LinkedHashMap<>();
        details.put("Stage", "Verification (Frama-C WP)");
        details.put("Exit code", NONE);
        return new ErrorReport(
                interrupted ? "Verification Interrupted" : "Verification Error",
                title,
                sentence,
                List.of(
                        new PipelineStep("Specifications generated", StepState.DONE),
                        new PipelineStep(interrupted ? "Verification interrupted" : "Verification failed", StepState.FAILED),
                        new PipelineStep("Report not produced", StepState.NOT_STARTED)),
                details,
                output,
                null,
                true);
    }
}
