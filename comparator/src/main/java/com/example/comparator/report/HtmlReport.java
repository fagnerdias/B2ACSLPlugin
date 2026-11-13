package com.example.comparator.report;

import com.example.comparator.report.Reports.ExampleReport;
import com.example.comparator.report.Reports.FunctionResult;
import com.example.comparator.report.Reports.GenerationReport;
import com.example.comparator.report.Reports.Summary;
import com.example.comparator.report.Reports.SummaryRow;
import com.example.comparator.report.Reports.VerificationReport;
import com.example.comparator.util.Json;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * HTML view of a comparator output directory: {@code index.html} (one row per example) and
 * {@code <example>/report.html}. Both are rendered from {@code summary.json} and the {@code
 * report.json} files, so the pages show exactly what the JSON reports hold and can be rebuilt
 * from an old output directory ({@code --html DIR}).
 */
public final class HtmlReport {

    static final String INDEX_FILE = "index.html";
    static final String EXAMPLE_FILE = "report.html";

    /** Splits Frama-C output into its {@code [kernel]}, {@code [acsl-import]}, ... messages. */
    private static final Pattern FRAMA_C_TAG = Pattern.compile("\\[(?:kernel|acsl-import|wp|b2acsl|B2ACSL)[^\\]]*\\]");
    private static final Pattern ERROR_WORD = Pattern.compile("Failure|[Ss]yntax error|User Error|[Ee]rror:");
    /** {@code /tmp/ghost_operations.ci709a21.pp} (Frama-C's preprocessed copy) → {@code ghost_operations.ci}. */
    private static final Pattern PREPROCESSED = Pattern.compile("/tmp/(\\S+?)[0-9a-f]{6}\\.pp");

    /** Reason recorded by {@code SideRunner} when verification is turned off: not a failure. */
    private static final String NO_VERIFY = "--no-verify";

    private HtmlReport() {}

    /** Where an example got: which sides finished verification. */
    enum Outcome {
        BOTH("both", "Verified by both", "Both"),
        PLUGIN("plugin", "Plugin only", "Plugin only"),
        RULES("rules", "Rules only", "Rules only"),
        PARTIAL("partial", "Partial", "Partial"),
        NEITHER("neither", "Neither", "Neither");

        final String id;
        final String label;
        /** For table cells. */
        final String shortLabel;

        Outcome(String id, String label, String shortLabel) {
            this.id = id;
            this.label = label;
            this.shortLabel = shortLabel;
        }

        static Outcome of(SummaryRow row) {
            boolean plugin = "COMPLETED".equals(row.pluginVerification());
            boolean rules = "COMPLETED".equals(row.rulesVerification());
            if (plugin && rules) {
                return BOTH;
            }
            if (plugin) {
                return PLUGIN;
            }
            if (rules) {
                return RULES;
            }
            return row.pluginTotal() > 0 || row.rulesTotal() > 0 ? PARTIAL : NEITHER;
        }

        String swatches() {
            return switch (this) {
                case BOTH -> Html.swatch("plugin") + Html.swatch("rules");
                case PLUGIN -> Html.swatch("plugin");
                case RULES -> Html.swatch("rules");
                case PARTIAL, NEITHER -> Html.swatch("none");
            };
        }
    }

    /**
     * Writes {@code index.html} and every {@code <example>/report.html} of {@code outDir}.
     *
     * @return the index page
     */
    public static Path write(Path outDir) throws IOException {
        Summary summary = Json.read(outDir.resolve("summary.json"), Summary.class);
        Map<String, ExampleReport> reports = new LinkedHashMap<>();
        for (SummaryRow row : summary.examples()) {
            Path exampleDir = outDir.resolve(row.example());
            Path json = exampleDir.resolve("report.json");
            if (Files.isRegularFile(json)) {
                ExampleReport report = Json.read(json, ExampleReport.class);
                reports.put(row.example(), report);
                Files.writeString(
                        exampleDir.resolve(EXAMPLE_FILE),
                        HtmlExamplePage.render(report, exampleDir),
                        StandardCharsets.UTF_8);
            }
        }
        Path index = outDir.resolve(INDEX_FILE);
        Files.writeString(index, HtmlIndexPage.render(summary, reports), StandardCharsets.UTF_8);
        return index;
    }

    /**
     * Why a side did not finish, in one line: the generation message, the first Frama-C error of
     * the import output, or the function that hit the WP time limit. {@code null} when it finished.
     */
    static String failureReason(GenerationReport generation, VerificationReport verification, int functionTimeout) {
        if (generation != null && !"OK".equals(generation.status())) {
            return firstNonBlank(generation.message(), generation.status());
        }
        if (verification == null) {
            return null;
        }
        return switch (verification.status()) {
            case "COMPLETED" -> null;
            case "SKIPPED" -> NO_VERIFY.equals(verification.importError()) ? null : verification.importError();
            case "WP_TIMEOUT" -> "WP exceeded the per-function limit (" + functionTimeout + " s)"
                    + lastFunction(verification.functions()) + "; later functions were not verified";
            default -> firstNonBlank(keyError(verification.importError()), verification.status());
        };
    }

    /** The first error message of a Frama-C output, whitespace collapsed; {@code null} if none is found. */
    static String keyError(String output) {
        if (output == null || output.isBlank()) {
            return null;
        }
        String flat = PREPROCESSED.matcher(output.replaceAll("\\s+", " ")).replaceAll("$1");
        for (String message : FRAMA_C_TAG.split(flat)) {
            String m = message.trim();
            if (ERROR_WORD.matcher(m).find() && !m.startsWith("User Error: warning")) {
                return m;
            }
        }
        return null;
    }

    private static String lastFunction(List<FunctionResult> functions) {
        return functions == null || functions.isEmpty() ? "" : " on " + functions.get(functions.size() - 1).name();
    }

    private static String firstNonBlank(String a, String b) {
        return a != null && !a.isBlank() ? a : b;
    }
}
