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
        List<String> fix = new ArrayList<>(advice(e, outcome));
        if (continueWith != null) {
            fix.add("The " + continueWith.label() + " specification was generated correctly. You can continue the"
                    + " verification with the " + continueWith.label() + " only, or fix the problem and try again.");
        }
        return new ErrorReport(
                "Specification Generation Error",
                "Specification generation failed",
                sentence,
                steps,
                details,
                output.toString().strip(),
                fix,
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

    static List<String> advice(SpecificationGenerationException e, GenerationOutcome outcome) {
        String work = outcome.run().root().relativize(outcome.run().workDir(e.generator())).toString();
        String side = e.generator() == Generator.PLUGIN ? "plugin" : "rules";
        List<String> out = new ArrayList<>();
        switch (e.status()) {
            case "NOT_AVAILABLE" -> {
                out.add("The ATL Rules jar was not found. Rebuild it with \"make build-rules\" (needs the Eclipse"
                        + " ATL/EMF jars), or start the tool with -Db2acsl.gui.rulesJar=<path to b2acsl.jar>.");
            }
            case "TRANSLATION_ERROR", "IO_ERROR" -> {
                if (e.rule() != null) {
                    out.add("Check the rule " + e.rule() + " in rules/transformations/BXML2ACSL.atl"
                            + (e.function() != null ? ": it failed on " + e.function() + "." : "."));
                } else {
                    out.add("Check the ATL transformation (rules/transformations/BXML2ACSL.atl) for the construct"
                            + " named in the generator output.");
                }
            }
            case "TIMEOUT" -> out.add("The ATL transformation did not finish within 300 s: look in the generator"
                    + " output for the last rule that was applied.");
            case "IMPORT_ERROR" -> {
                out.add("Frama-C could not parse the generated annotations: open the file and line named in the first"
                        + " error of the generator output and fix the translation of that construct.");
                out.add("The generated files are kept in " + work + "/" + side + "/lang/c of the run folder ("
                        + outcome.run().root() + ").");
            }
            case "IMPORT_TIMEOUT" -> out.add("Frama-C -acsl-import did not finish within 120 s. Try a smaller"
                    + " project or check the generated .acsl files for very large axiomatics.");
            case "NO_C_FILES" -> out.add("Generate the C code of the project with Atelier B (lang/c/*.c) and try again.");
            case "NO_ACSL_FILES" -> out.add("The generator produced no .acsl file: check that bdp/ contains the BXML"
                    + " of the implementations.");
            case "GENERATION_ERROR" -> out.add("The plugin could not translate the B project: see the first error in"
                    + " the generator output (BXML file and construct).");
            case "INTERRUPTED" -> out.add("Click Try Again to run the generation again.");
            default -> out.add("See the generator output for the exception and its stack trace.");
        }
        return out;
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
                List.of(
                        "Install Frama-C (for example \"opam install frama-c\") and make sure frama-c is on the PATH of"
                                + " the environment that starts this tool.",
                        "Click Try Again after installing it."),
                null,
                true);
    }

    /** A selected folder is not a usable project. */
    public static ErrorReport invalidProject(java.nio.file.Path folder, String problem) {
        Map<String, String> details = new LinkedHashMap<>();
        details.put("Stage", "Project selection");
        details.put("Folder", String.valueOf(folder));
        return new ErrorReport(
                "Invalid Project",
                "This folder is not a B project",
                problem,
                List.of(
                        new PipelineStep("Reading the project folder", StepState.FAILED),
                        new PipelineStep("Specification not generated", StepState.NOT_STARTED)),
                details,
                problem,
                List.of(
                        "Choose the root folder of an Atelier B project: it contains bdp/ (with the .bxml files) and"
                                + " lang/c/ (with the generated C code).",
                        "Functions must follow the <Machine>__<Operation> naming of the Atelier B C code generator."),
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
                interrupted
                        ? List.of("Run the verification again from step 3.")
                        : List.of("Check that Frama-C and the selected provers are installed and on the PATH, then"
                                + " run the verification again."),
                null,
                true);
    }
}
