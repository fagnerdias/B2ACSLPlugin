package com.example.gui.model;

import java.awt.Color;

/**
 * State of one function, the same six states (labels and classification) as the pipeline's
 * {@code VerificationProgressDialog}: Pending, Running…, Proved, Partial, Timeout, Failed.
 */
public enum FunctionStatus {
    PENDING("Pending", new Color(0x8A8A8A)),
    RUNNING("Running…", new Color(0x2A62C9)),
    PROVED("Proved", new Color(0x2E8540)),
    PARTIAL("Partial", new Color(255, 127, 36)),
    TIMEOUT("Timeout", new Color(176, 128, 18)),
    FAILED("Failed", new Color(0xC62828));

    private final String label;
    private final Color color;

    FunctionStatus(String label, Color color) {
        this.label = label;
        this.color = color;
    }

    public String label() {
        return label;
    }

    public Color color() {
        return color;
    }
}
