package com.example.gui.model;

import java.util.List;

/**
 * WP result of one function with one specification.
 *
 * @param failures unknown + failed goals (as the existing report counts them)
 * @param smokePassed smoke tests that passed (WP could not prove the smoke goal), {@code -1} if
 *     none ran
 * @param smokeTotal smoke tests run, {@code -1} if none ran
 * @param exitCode Frama-C exit code ({@code -1} when it was killed)
 * @param timedOut Frama-C was killed because it exceeded the wall-clock limit of one function
 */
public record FunctionResult(
        String function,
        Generator generator,
        int totalGoals,
        int proved,
        int failures,
        int timeouts,
        int smokePassed,
        int smokeTotal,
        int exitCode,
        boolean timedOut,
        List<Issue> issues,
        long elapsedMs) {

    public FunctionResult {
        issues = List.copyOf(issues);
    }

    /** Frama-C did not finish normally (non-zero exit or wall-clock timeout). */
    public boolean processFailed() {
        return timedOut || exitCode != 0;
    }

    /**
     * The classification of the pipeline ({@code FramaCRunner.markCompletedFromSummary}): wall-clock
     * timeout → Timeout; WP error → Failed; no goals or all proved → Proved; timeouts without
     * failures → Timeout; some proved → Partial; otherwise Failed.
     */
    public FunctionStatus status() {
        if (timedOut) {
            return FunctionStatus.TIMEOUT;
        }
        if (exitCode != 0) {
            return FunctionStatus.FAILED;
        }
        if (totalGoals == 0 || proved >= totalGoals) {
            return FunctionStatus.PROVED;
        }
        if (timeouts > 0 && failures == 0) {
            return FunctionStatus.TIMEOUT;
        }
        return proved > 0 ? FunctionStatus.PARTIAL : FunctionStatus.FAILED;
    }

    public boolean hasSmokeTests() {
        return smokeTotal >= 0;
    }

    /** Smoke tests that failed (dead code or inconsistent precondition found), 0 if none ran. */
    public int smokeFailed() {
        return hasSmokeTests() ? smokeTotal - smokePassed : 0;
    }

    /** {@code "passed / total"}, or {@code "—"} when no smoke test ran. */
    public String smokeDisplay() {
        return hasSmokeTests() ? smokePassed + " / " + smokeTotal : "—";
    }

    /** Same counts (proved/total, failures, timeouts, smoke tests). */
    public boolean sameCountsAs(FunctionResult other) {
        return other != null
                && totalGoals == other.totalGoals
                && proved == other.proved
                && failures == other.failures
                && timeouts == other.timeouts
                && smokePassed == other.smokePassed
                && smokeTotal == other.smokeTotal
                && processFailed() == other.processFailed();
    }
}
