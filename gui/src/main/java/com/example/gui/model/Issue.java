package com.example.gui.model;

/**
 * A relevant WP occurrence for one function, already phrased for the user (e.g. {@code
 * AddRunner__Add: postcondition (ensures) could not be proved (atl/merged_code.c:87)}).
 */
public record Issue(Kind kind, String function, String text) {

    public enum Kind {
        UNPROVED,
        TIMEOUT,
        PROVER_ERROR,
        SMOKE_TEST,
        PROCESS,
        NO_CONTRACT
    }
}
