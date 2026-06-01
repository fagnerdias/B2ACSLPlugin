package com.example.ui;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Agrega métricas do resumo WP do Frama-C, por exemplo:
 *
 * <pre>
 * [wp] Proved goals:   15 / 21
 *   Unknown:         6
 *   Smoke Tests:     4 / 4
 * </pre>
 *
 * <p>Total, Proved, Failures ({@code Unknown}), Timeouts e Smoke Tests vêm sempre deste bloco de
 * resumo quando presente; não se contam linhas soltas com a palavra "unknown" no log.
 */
public final class VerificationReportData {

    private static final Pattern PROVED_GOALS_PATTERN =
            Pattern.compile("(?i)proved goals\\s*:\\s*(\\d+)\\s*/\\s*(\\d+)");
    private static final Pattern UNKNOWN_SUMMARY_PATTERN =
            Pattern.compile("(?i)^\\s*unknown\\s*:\\s*(\\d+)\\b");
    private static final Pattern TIMEOUT_SUMMARY_PATTERN =
            Pattern.compile("(?i)^\\s*timeout\\s*:\\s*(\\d+)\\b");
    private static final Pattern SMOKE_TESTS_SUMMARY_PATTERN =
            Pattern.compile("(?i)^\\s*smoke tests\\s*:\\s*(\\d+)\\s*/\\s*(\\d+)\\b");

    private int wpTotalGoals;
    private int wpProvedGoals;
    private int wpUnknownGoals;
    private int wpTimeoutGoals;
    private int wpSmokeTestsPassed;
    private int wpSmokeTestsTotal;
    private boolean wpSmokeTestsPresent;
    private boolean wpSummaryPresent;
    private int extraFailures;
    private int extraTimeouts;
    private final List<String> details = new ArrayList<>();
    private final StringBuilder fullOutput = new StringBuilder();

    public void absorbOutput(String output, String sourceName) {
        if (output == null || output.isBlank()) {
            return;
        }
        if (fullOutput.length() > 0 && fullOutput.charAt(fullOutput.length() - 1) != '\n') {
            fullOutput.append('\n');
        }
        fullOutput.append("===== ").append(sourceName).append(" =====").append('\n');
        fullOutput.append(output);
        if (!output.endsWith("\n")) {
            fullOutput.append('\n');
        }
        for (String line : output.split("\\R")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) {
                continue;
            }

            Matcher provedMatcher = PROVED_GOALS_PATTERN.matcher(trimmed);
            if (provedMatcher.find()) {
                wpProvedGoals += Integer.parseInt(provedMatcher.group(1));
                wpTotalGoals += Integer.parseInt(provedMatcher.group(2));
                wpSummaryPresent = true;
                continue;
            }
            Matcher unknownMatcher = UNKNOWN_SUMMARY_PATTERN.matcher(line);
            if (unknownMatcher.find()) {
                wpUnknownGoals += Integer.parseInt(unknownMatcher.group(1));
                wpSummaryPresent = true;
                continue;
            }
            Matcher timeoutMatcher = TIMEOUT_SUMMARY_PATTERN.matcher(line);
            if (timeoutMatcher.find()) {
                wpTimeoutGoals += Integer.parseInt(timeoutMatcher.group(1));
                wpSummaryPresent = true;
                continue;
            }
            Matcher smokeMatcher = SMOKE_TESTS_SUMMARY_PATTERN.matcher(line);
            if (smokeMatcher.find()) {
                wpSmokeTestsPassed += Integer.parseInt(smokeMatcher.group(1));
                wpSmokeTestsTotal += Integer.parseInt(smokeMatcher.group(2));
                wpSmokeTestsPresent = true;
                wpSummaryPresent = true;
                continue;
            }

            if (isDetailFailureLine(trimmed)) {
                details.add("[FAILURE] [" + sourceName + "] " + trimmed);
            } else if (trimmed.toLowerCase(Locale.ROOT).contains("[timeout]")) {
                details.add("[TIMEOUT] [" + sourceName + "] " + trimmed);
            }
        }
    }

    /**
     * Linhas de detalhe para objetivos não provados; exclui o resumo {@code Unknown: N} e mensagens
     * do provador {@code returns Unknown}.
     */
    private static boolean isDetailFailureLine(String trimmed) {
        if (UNKNOWN_SUMMARY_PATTERN.matcher(trimmed).find()
                || PROVED_GOALS_PATTERN.matcher(trimmed).find()
                || SMOKE_TESTS_SUMMARY_PATTERN.matcher(trimmed).find()) {
            return false;
        }
        String lower = trimmed.toLowerCase(Locale.ROOT);
        if (lower.contains("returns unknown")) {
            return false;
        }
        if (lower.contains("proved goals:")) {
            return false;
        }
        if (lower.contains("smoke tests:")) {
            return false;
        }
        return lower.contains("[wp] no proof")
                || lower.contains("failed")
                || (lower.contains("[wp]") && lower.contains("unknown"));
    }

    public void addFailure(String message) {
        extraFailures++;
        details.add("[FAILURE] " + message);
    }

    public void addTimeout(String message) {
        extraTimeouts++;
        details.add("[TIMEOUT] " + message);
    }

    public int totalGoals() {
        if (wpSummaryPresent && wpTotalGoals > 0) {
            return wpTotalGoals;
        }
        return wpProvedGoals + failures() + timeouts();
    }

    public int provedGoals() {
        return wpProvedGoals;
    }

    /** Objetivos não provados ({@code Unknown: N} no resumo WP). */
    public int failures() {
        if (wpSummaryPresent) {
            return wpUnknownGoals + extraFailures;
        }
        return extraFailures;
    }

    public int timeouts() {
        if (wpSummaryPresent) {
            return wpTimeoutGoals + extraTimeouts;
        }
        return extraTimeouts;
    }

    public boolean smokeTestsPresent() {
        return wpSmokeTestsPresent;
    }

    public int smokeTestsPassed() {
        return wpSmokeTestsPassed;
    }

    public int smokeTestsTotal() {
        return wpSmokeTestsTotal;
    }

    /** Texto do cartão de resumo, ex. {@code 4 / 4} ou {@code —} se não houver smoke tests no log. */
    public String smokeTestsDisplay() {
        if (!wpSmokeTestsPresent) {
            return "—";
        }
        return wpSmokeTestsPassed + " / " + wpSmokeTestsTotal;
    }

    public List<String> details() {
        return List.copyOf(details);
    }

    public String detailsAsText() {
        if (details.isEmpty()) {
            return "No relevant occurrences were found in WP output.";
        }
        return String.join(System.lineSeparator(), details);
    }

    public String fullOutputAsText() {
        if (fullOutput.isEmpty()) {
            return "No WP output was captured.";
        }
        return fullOutput.toString();
    }
}
