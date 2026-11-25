package com.example.gui.model;

/** State of one function in the progress window. */
public enum FunctionStatus {
    PENDING("Pending"),
    RUNNING("Running..."),
    PROVED("Proved"),
    FAILED("Failed");

    private final String label;

    FunctionStatus(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
