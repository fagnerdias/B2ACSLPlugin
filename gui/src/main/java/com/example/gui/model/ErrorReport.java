package com.example.gui.model;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Content of the error dialog (generation failure, tool not found, invalid project, interrupted
 * process): only the texts change between uses.
 *
 * @param details label → value, in display order (Stage, Function, Rule, Exit code)
 * @param continueWith generator whose specification succeeded (compare mode only), offered as
 *     "Continue with &lt;generator&gt; Only"; {@code null} hides that button
 * @param canRetry shows "Try Again"
 */
public record ErrorReport(
        String windowTitle,
        String title,
        String sentence,
        List<PipelineStep> steps,
        Map<String, String> details,
        String output,
        List<String> howToFix,
        Generator continueWith,
        boolean canRetry) {

    public ErrorReport {
        steps = List.copyOf(steps);
        details = new LinkedHashMap<>(details);
        howToFix = List.copyOf(howToFix);
    }

    public enum StepState {
        DONE,
        FAILED,
        NOT_STARTED
    }

    public record PipelineStep(String label, StepState state) {}

    /** Everything as plain text, for "Copy Details". */
    public String asText() {
        StringBuilder sb = new StringBuilder();
        sb.append(title).append('\n').append(sentence).append("\n\nWhere it failed:\n");
        for (PipelineStep step : steps) {
            String mark =
                    switch (step.state()) {
                        case DONE -> "[ok]  ";
                        case FAILED -> "[FAIL]";
                        case NOT_STARTED -> "[ -- ]";
                    };
            sb.append("  ").append(mark).append(' ').append(step.label()).append('\n');
        }
        sb.append("\nError details:\n");
        details.forEach((k, v) -> sb.append("  ").append(k).append(": ").append(v).append('\n'));
        sb.append("\nOutput:\n").append(output == null ? "" : output).append('\n');
        if (!howToFix.isEmpty()) {
            sb.append("\nHow to fix:\n");
            howToFix.forEach(h -> sb.append("  - ").append(h).append('\n'));
        }
        return sb.toString();
    }
}
