package com.example.comparator.verify;

import com.example.B2AcslConfig;
import com.example.FramaCRunner;
import com.example.FramaCRunner.VerificationOutcome;
import com.example.comparator.gen.GeneratedSpec;
import com.example.comparator.report.Reports.FunctionResult;
import com.example.comparator.report.Reports.VerificationReport;
import com.example.ui.VerificationReportData;
import com.example.ui.WpOptionsDialog.WpOptions;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Verifies a {@link GeneratedSpec} with the plugin's own {@link FramaCRunner} ({@code
 * -acsl-import} → post-processing of {@code merged_code.c} → {@code -wp}). Both sides use the same
 * instance of this class, hence the same {@link WpOptions}.
 */
public final class SharedVerifier {

    private static final int MAX_IMPORT_ERROR_CHARS = 20_000;
    private static final String MERGED_CODE_SUFFIX = " (" + FramaCRunner.MERGED_CODE_FILE_NAME + ")";

    private final List<String> provers;
    private final int timeoutSeconds;
    private final int functionTimeoutSeconds;
    private final boolean perOperation;

    public SharedVerifier(String provers, int timeoutSeconds, int functionTimeoutSeconds, boolean perOperation) {
        this.provers = Arrays.stream(provers.split("[,;]")).map(String::trim).filter(s -> !s.isEmpty()).toList();
        this.timeoutSeconds = timeoutSeconds;
        this.functionTimeoutSeconds = functionTimeoutSeconds;
        this.perOperation = perOperation;
    }

    /**
     * WP options: prover, timeouts and per-operation mode from the command line; smoke tests,
     * counterexamples, goal splitting and loop simplification from the plugin's {@code
     * -Db2acsl.wp.*} properties (same defaults as a headless plugin run).
     */
    public WpOptions wpOptions(String projectName) {
        B2AcslConfig cfg = B2AcslConfig.fromSystemProperties();
        return new WpOptions(
                projectName,
                provers,
                timeoutSeconds,
                functionTimeoutSeconds,
                "-wp-status",
                cfg.wpLoopSimplification(),
                cfg.wpSmokeTests(),
                perOperation,
                cfg.wpCounterExamples(),
                cfg.wpSplitGoals());
    }

    public VerificationReport verify(GeneratedSpec spec, String projectName) {
        try {
            VerificationOutcome outcome =
                    FramaCRunner.verifyWithReport(
                            spec.acslImportFiles(),
                            spec.ghostCi(),
                            spec.cFiles(),
                            spec.cDir(),
                            spec.specificationUsedTypes(),
                            wpOptions(projectName),
                            projectName,
                            false);
            return toReport(outcome);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return VerificationReport.skipped("interrupted");
        } catch (Exception e) {
            return new VerificationReport("ERROR", -1, 0, 0, 0, 0, null, 0, null, e.toString(), List.of());
        }
    }

    private static VerificationReport toReport(VerificationOutcome outcome) {
        VerificationReportData data = outcome.report();
        List<FunctionResult> functions = new ArrayList<>();
        for (VerificationReportData.FunctionSummary f : data.functionSummaries()) {
            String name = f.functionName();
            if (name.endsWith(MERGED_CODE_SUFFIX)) {
                name = name.substring(0, name.length() - MERGED_CODE_SUFFIX.length());
            }
            functions.add(
                    new FunctionResult(
                            name, f.provedGoals(), f.totalGoals(), f.failures(), f.timeouts(), smoke(f.smokeTestsDisplay())));
        }
        String importError = outcome.importOutput();
        if (importError != null && importError.length() > MAX_IMPORT_ERROR_CHARS) {
            importError = importError.substring(0, MAX_IMPORT_ERROR_CHARS) + "\n[... truncated]";
        }
        return new VerificationReport(
                outcome.status().name(),
                outcome.exitCode(),
                data.provedGoals(),
                data.totalGoals(),
                data.failures(),
                data.timeouts(),
                smoke(data.smokeTestsDisplay()),
                outcome.elapsedMs(),
                outcome.mergedCode() == null ? null : outcome.mergedCode().toString(),
                importError == null || importError.isEmpty() ? null : importError,
                functions);
    }

    /** {@code "4 / 4"} becomes {@code "4/4"}; the report's {@code "—"} (no smoke tests) becomes {@code null}. */
    private static String smoke(String display) {
        return display == null || !display.contains("/") ? null : display.replace(" ", "");
    }
}
