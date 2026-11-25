package com.example.gui.parsing;

import com.example.gui.model.FunctionResult;
import com.example.gui.model.Generator;
import com.example.gui.model.Issue;
import com.example.ui.VerificationReportData;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads the output of one {@code frama-c -wp -wp-fct F} run.
 *
 * <p>The goal counts come from {@link VerificationReportData} (the parser of the CLI and of the
 * comparator), so the GUI shows the same numbers. This class adds the relevant occurrences, phrased
 * for the user: unproved and timed-out goals (from the {@code -wp-status}/{@code -wp-print} goal
 * blocks, or the {@code [wp] [Timeout] ...} lines when there are no blocks), failed smoke tests,
 * prover errors and counterexamples.
 */
public final class WpOutputParser {

    private static final Pattern GOAL_HEADER =
            Pattern.compile(
                    "^Goal (.+?) \\(\"([^\"]+)\", lines? (\\d+)(?:-\\d+)?\\)(?: in '([^']+)')?(?: \\(\\d+/\\d+\\))?:\\s*$");
    private static final Pattern PROVER_RESULT =
            Pattern.compile("^Prover (.+?) returns (Timeout|Unknown|Failed|Valid)\\b.*$");
    private static final Pattern MODEL = Pattern.compile("^Model (.+)$");
    private static final Pattern SEPARATOR = Pattern.compile("^-{20,}\\s*$");
    private static final Pattern STATUS_LINE =
            Pattern.compile("^\\[wp\\] \\[(Timeout|Unknown|Failure)\\] (\\S+)(.*)$");
    private static final Pattern SMOKE_FAILED =
            Pattern.compile("^\\[wp\\] (?:([^:\\s]+):(\\d+): )?Warning: Failed smoke-test.*$");
    private static final Pattern PROVER_CRASH_HEADER = Pattern.compile("^\\[wp\\] Warning: Goal ([^:]+):\\s*$");
    private static final Pattern PROVER_CRASH = Pattern.compile("^\\s+running prover (\\S+?)(?::\\S+)? failed.*$");
    private static final Pattern STATUS_PROVER = Pattern.compile("\\(([A-Za-z][\\w-]*)\\)");
    private static final int MAX_MODEL_ENTRIES = 6;

    private WpOutputParser() {}

    /**
     * @param output combined stdout/stderr of the WP run
     * @param displayFile how to show the analysed file in messages (e.g. {@code atl/merged_code.c})
     * @param exitCode Frama-C exit code, or {@code -1} when it was killed
     * @param killedReason non-null when the process did not finish (wall-clock limit, cancellation)
     */
    public static FunctionResult parse(
            String function,
            Generator generator,
            String output,
            String displayFile,
            int timeoutSeconds,
            int exitCode,
            String killedReason,
            long elapsedMs) {
        String text = output == null ? "" : output;
        VerificationReportData data = new VerificationReportData();
        data.absorbOutput(text, function);
        VerificationReportData.FunctionSummary summary =
                data.functionSummaries().isEmpty() ? null : data.functionSummaries().get(0);

        List<Issue> issues = new ArrayList<>(issues(function, text, displayFile, timeoutSeconds));
        boolean timedOut = killedReason != null;
        if (timedOut) {
            issues.add(new Issue(Issue.Kind.PROCESS, function, function + ": " + killedReason));
        } else if (exitCode != 0) {
            String headline = firstErrorLine(text);
            issues.add(
                    new Issue(
                            Issue.Kind.PROCESS,
                            function,
                            function + ": Frama-C exited with code " + exitCode
                                    + (headline.isEmpty() ? "" : " — " + headline)));
        }
        if (summary == null) {
            return new FunctionResult(function, generator, 0, 0, 0, 0, -1, -1, exitCode, timedOut, issues, elapsedMs);
        }
        return new FunctionResult(
                function,
                generator,
                summary.totalGoals(),
                summary.provedGoals(),
                summary.failures(),
                summary.timeouts(),
                summary.smokePassed(),
                summary.smokeTotal(),
                exitCode,
                timedOut,
                issues,
                elapsedMs);
    }

    /** The relevant occurrences of one WP run, deduplicated, in output order. */
    public static List<Issue> issues(String function, String output, String displayFile, int timeoutSeconds) {
        Set<Issue> out = new LinkedHashSet<>();
        List<Issue> fromStatusLines = new ArrayList<>();
        String[] lines = (output == null ? "" : output).split("\\R");
        GoalBlock block = null;
        String crashGoal = null;
        for (String line : lines) {
            Matcher header = GOAL_HEADER.matcher(line);
            if (header.matches()) {
                addBlock(out, block, function, displayFile, timeoutSeconds);
                block = new GoalBlock(header.group(1), Integer.parseInt(header.group(3)), header.group(4));
                continue;
            }
            if (block != null) {
                Matcher prover = PROVER_RESULT.matcher(line);
                if (prover.matches()) {
                    if (!"Valid".equals(prover.group(2)) && block.outcome == null) {
                        block.prover = shortProver(prover.group(1));
                        block.outcome = prover.group(2);
                    }
                    continue;
                }
                Matcher model = MODEL.matcher(line);
                if (model.matches()) {
                    block.model.add(model.group(1).trim());
                    continue;
                }
                if (SEPARATOR.matcher(line).matches()) {
                    addBlock(out, block, function, displayFile, timeoutSeconds);
                    block = null;
                    continue;
                }
            }
            Matcher status = STATUS_LINE.matcher(line);
            if (status.matches()) {
                fromStatusLines.add(statusLineIssue(function, status, timeoutSeconds));
                continue;
            }
            Matcher smoke = SMOKE_FAILED.matcher(line);
            if (smoke.matches()) {
                String where = smoke.group(2) == null ? "" : " (" + displayFile + ":" + smoke.group(2) + ")";
                out.add(
                        new Issue(
                                Issue.Kind.SMOKE_TEST,
                                function,
                                function + ": smoke test failed — dead code or inconsistent precondition" + where));
                continue;
            }
            Matcher crashHeader = PROVER_CRASH_HEADER.matcher(line);
            if (crashHeader.matches()) {
                crashGoal = crashHeader.group(1).trim();
                continue;
            }
            Matcher crash = PROVER_CRASH.matcher(line);
            if (crash.matches() && crashGoal != null) {
                out.add(
                        new Issue(
                                Issue.Kind.PROVER_ERROR,
                                function,
                                function + ": prover " + crash.group(1) + " crashed on goal "
                                        + crashGoal.toLowerCase(Locale.ROOT)));
                crashGoal = null;
            }
        }
        addBlock(out, block, function, displayFile, timeoutSeconds);
        boolean hasGoalIssues =
                out.stream().anyMatch(i -> i.kind() == Issue.Kind.UNPROVED || i.kind() == Issue.Kind.TIMEOUT);
        if (!hasGoalIssues) {
            out.addAll(fromStatusLines);
        }
        return List.copyOf(out);
    }

    private static final class GoalBlock {
        final String kind;
        final int line;
        final String inFunction;
        String prover;
        String outcome;
        final List<String> model = new ArrayList<>();

        GoalBlock(String kind, int line, String inFunction) {
            this.kind = kind;
            this.line = line;
            this.inFunction = inFunction;
        }
    }

    private static void addBlock(
            Set<Issue> out, GoalBlock block, String function, String displayFile, int timeoutSeconds) {
        if (block == null || block.outcome == null) {
            return;
        }
        String fn = block.inFunction != null ? block.inFunction : function;
        String what = describeGoalKind(block.kind);
        String where = " (" + displayFile + ":" + block.line + ")";
        String text;
        Issue.Kind kind;
        switch (block.outcome) {
            case "Timeout" -> {
                kind = Issue.Kind.TIMEOUT;
                text = fn + ": " + what + " timed out after " + timeoutSeconds + "s (" + block.prover + ")" + where;
            }
            case "Failed" -> {
                kind = Issue.Kind.PROVER_ERROR;
                text = fn + ": " + what + " could not be checked — prover " + block.prover + " failed" + where;
            }
            default -> {
                kind = Issue.Kind.UNPROVED;
                text = fn + ": " + what + " could not be proved" + where;
            }
        }
        if (!block.model.isEmpty()) {
            List<String> shown = block.model.subList(0, Math.min(MAX_MODEL_ENTRIES, block.model.size()));
            text += " — counterexample: " + String.join(", ", shown)
                    + (block.model.size() > MAX_MODEL_ENTRIES ? ", …" : "");
        }
        out.add(new Issue(kind, function, text));
    }

    private static Issue statusLineIssue(String function, Matcher status, int timeoutSeconds) {
        String goal = status.group(2);
        Matcher prover = STATUS_PROVER.matcher(status.group(3));
        String proverName = null;
        while (prover.find()) {
            String p = prover.group(1);
            if (!p.equals("Qed") && !p.startsWith("Cache") && !p.equals("Cached")) {
                proverName = p;
                break;
            }
        }
        String by = proverName == null ? "" : " (" + proverName + ")";
        return switch (status.group(1)) {
            case "Timeout" -> new Issue(
                    Issue.Kind.TIMEOUT, function,
                    function + ": goal " + goal + " timed out after " + timeoutSeconds + "s" + by);
            case "Failure" -> new Issue(
                    Issue.Kind.PROVER_ERROR, function, function + ": goal " + goal + " could not be checked" + by);
            default -> new Issue(Issue.Kind.UNPROVED, function, function + ": goal " + goal + " could not be proved" + by);
        };
    }

    /** "Post-condition" → "postcondition (ensures)", "Assertion 'rte,mem_access'" → "runtime check (mem_access)". */
    static String describeGoalKind(String kind) {
        String k = kind.trim();
        if (k.startsWith("Post-condition")) {
            return "postcondition (ensures)";
        }
        if (k.startsWith("Pre-condition")) {
            return "precondition (requires)";
        }
        if (k.startsWith("Preservation of Invariant")) {
            return "loop invariant (preservation)";
        }
        if (k.startsWith("Establishment of Invariant")) {
            return "loop invariant (establishment)";
        }
        if (k.startsWith("Loop assigns")) {
            return "loop assigns clause";
        }
        if (k.startsWith("Assigns")) {
            return "assigns clause";
        }
        if (k.startsWith("Decreasing of Loop variant")) {
            return "loop variant (decrease)";
        }
        if (k.startsWith("Positivity of Loop variant")) {
            return "loop variant (positivity)";
        }
        if (k.startsWith("Termination-condition")) {
            return "termination condition";
        }
        if (k.startsWith("Instance of")) {
            return "precondition of a called function";
        }
        if (k.startsWith("Assertion 'rte,")) {
            int end = k.indexOf('\'', "Assertion 'rte,".length());
            return "runtime check (" + k.substring("Assertion 'rte,".length(), end < 0 ? k.length() : end) + ")";
        }
        if (k.startsWith("Assertion")) {
            return "assertion";
        }
        return k.toLowerCase(Locale.ROOT);
    }

    private static String shortProver(String prover) {
        int space = prover.indexOf(' ');
        return space < 0 ? prover : prover.substring(0, space);
    }

    private static String firstErrorLine(String output) {
        for (String line : output.split("\\R")) {
            String l = line.toLowerCase(Locale.ROOT);
            if (l.contains("user error") || l.contains("[kernel] failure") || l.contains("fatal error")
                    || l.contains("syntax error")) {
                return line.trim();
            }
        }
        return "";
    }
}
