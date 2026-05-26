package com.example.ui;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class VerificationReportData {

    private static final Pattern PROVED_GOALS_PATTERN =
            Pattern.compile("(?i)proved goals\\s*:\\s*(\\d+)\\s*/\\s*(\\d+)");
    private static final Pattern TIMEOUT_SUMMARY_PATTERN =
            Pattern.compile("(?i)^timeout\\s*:\\s*(\\d+)\\b");
    private static final Pattern FAILURE_SUMMARY_PATTERN =
            Pattern.compile("(?i)^(failed|failures?)\\s*:\\s*(\\d+)\\b");

    private int totalGoals;
    private int provedGoals;
    private int failures;
    private int timeouts;
    private int failureSummaryTotal;
    private boolean failureSummaryPresent;
    private int timeoutSummaryTotal;
    private boolean timeoutSummaryPresent;
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
            if (trimmed.isEmpty()) continue;

            Matcher provedMatcher = PROVED_GOALS_PATTERN.matcher(trimmed);
            if (provedMatcher.find()) {
                provedGoals += Integer.parseInt(provedMatcher.group(1));
                totalGoals += Integer.parseInt(provedMatcher.group(2));
            }
            Matcher timeoutSummaryMatcher = TIMEOUT_SUMMARY_PATTERN.matcher(trimmed);
            if (timeoutSummaryMatcher.find()) {
                timeoutSummaryTotal += Integer.parseInt(timeoutSummaryMatcher.group(1));
                timeoutSummaryPresent = true;
                continue;
            }
            Matcher failureSummaryMatcher = FAILURE_SUMMARY_PATTERN.matcher(trimmed);
            if (failureSummaryMatcher.find()) {
                failureSummaryTotal += Integer.parseInt(failureSummaryMatcher.group(2));
                failureSummaryPresent = true;
                continue;
            }

            String lower = trimmed.toLowerCase(Locale.ROOT);
            if (lower.contains("[timeout]")) {
                timeouts++;
                details.add("[TIMEOUT] [" + sourceName + "] " + trimmed);
                continue;
            }
            if (lower.contains("failed") || lower.contains("[wp] no proof") || lower.contains("unknown")) {
                failures++;
                details.add("[FAILURE] [" + sourceName + "] " + trimmed);
            }
        }
    }

    public void addFailure(String message) {
        failures++;
        details.add("[FAILURE] " + message);
    }

    public void addTimeout(String message) {
        timeouts++;
        details.add("[TIMEOUT] " + message);
    }

    public int totalGoals() {
        int derivedTotal = provedGoals + failures + timeouts;
        return totalGoals > 0 ? totalGoals : derivedTotal;
    }

    public int provedGoals() {
        return provedGoals;
    }

    public int failures() {
        if (failureSummaryPresent) {
            return failureSummaryTotal;
        }
        return failures;
    }

    public int timeouts() {
        if (timeoutSummaryPresent) {
            return timeoutSummaryTotal;
        }
        return timeouts;
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
