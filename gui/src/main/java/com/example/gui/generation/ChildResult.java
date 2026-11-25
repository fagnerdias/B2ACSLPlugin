package com.example.gui.generation;

import java.util.List;

/**
 * What {@link GenerationChildMain} writes for the GUI process (JSON).
 *
 * @param stage the step that was running when it stopped
 * @param status OK, or the cause (TRANSLATION_ERROR, IMPORT_ERROR, NOT_AVAILABLE, ...)
 * @param output the relevant output of the failing step (import errors, exception)
 * @param command command line of the failing external step, if any
 * @param functions functions with an ACSL contract (on success)
 */
public record ChildResult(
        boolean ok,
        String generator,
        String stage,
        String status,
        int exitCode,
        String message,
        String output,
        String command,
        List<String> warnings,
        List<String> functions,
        long elapsedMs) {}
