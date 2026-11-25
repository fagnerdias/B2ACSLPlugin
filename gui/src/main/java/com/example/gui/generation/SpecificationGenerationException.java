package com.example.gui.generation;

import com.example.gui.model.Generator;

/**
 * A specification could not be generated.
 *
 * @see #stage() e.g. "Specification generation (ATL Rules)", "Frama-C -acsl-import (Plugin)"
 */
public final class SpecificationGenerationException extends Exception {

    private final Generator generator;
    private final String stage;
    private final String status;
    private final String function;
    private final String rule;
    private final int exitCode;
    private final String output;
    private final boolean interrupted;

    /**
     * @param status short machine-readable cause (TRANSLATION_ERROR, IMPORT_ERROR, NOT_AVAILABLE, ...)
     * @param function the function the error refers to, {@code null} if unknown
     * @param rule the ATL rule the error refers to, {@code null} if unknown or not ATL
     * @param output the generator output (error part)
     */
    public SpecificationGenerationException(
            Generator generator,
            String stage,
            String status,
            String message,
            String function,
            String rule,
            int exitCode,
            String output,
            boolean interrupted) {
        super(message);
        this.generator = generator;
        this.stage = stage;
        this.status = status;
        this.function = function;
        this.rule = rule;
        this.exitCode = exitCode;
        this.output = output == null ? "" : output;
        this.interrupted = interrupted;
    }

    public Generator generator() {
        return generator;
    }

    public String stage() {
        return stage;
    }

    public String status() {
        return status;
    }

    public String function() {
        return function;
    }

    public String rule() {
        return rule;
    }

    public int exitCode() {
        return exitCode;
    }

    public String output() {
        return output;
    }

    public boolean interrupted() {
        return interrupted;
    }
}
