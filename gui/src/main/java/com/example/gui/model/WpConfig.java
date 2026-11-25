package com.example.gui.model;

import com.example.B2AcslConfig;
import com.example.ui.WpOptionsDialog.WpOptions;
import java.util.List;

/**
 * Step 3 settings.
 *
 * @param provers prover names as passed to {@code -wp-prover} ("Alt-Ergo", "CVC5", "Z3")
 * @param timeoutSeconds {@code -wp-timeout}, per goal
 * @param outputType "status" ({@code -wp-status}) or "print" ({@code -wp-print})
 */
public record WpConfig(
        List<String> provers, int timeoutSeconds, String outputType, boolean smokeTests, boolean counterExamples) {

    public static final List<String> OUTPUT_TYPES = List.of("status", "print");

    public WpConfig {
        provers = List.copyOf(provers);
    }

    /**
     * The options of the existing pipeline ({@code FramaCRunner.buildWpCommand}) for these settings:
     * one WP run per function, no goal splitting, no loop simplification, and the wall-clock limit
     * per function of {@link B2AcslConfig}.
     */
    public WpOptions toWpOptions(String projectName) {
        return new WpOptions(
                projectName,
                provers,
                timeoutSeconds,
                functionTimeoutSeconds(),
                "print".equals(outputType) ? "-wp-print" : "-wp-status",
                false,
                smokeTests,
                true,
                counterExamples,
                false);
    }

    /** Wall-clock limit for one {@code frama-c -wp} process. */
    public static int functionTimeoutSeconds() {
        return B2AcslConfig.fromSystemProperties().wpFunctionTimeoutSeconds();
    }
}
