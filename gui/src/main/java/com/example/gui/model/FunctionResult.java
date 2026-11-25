package com.example.gui.model;

import java.util.List;

/**
 * WP result of one function with one specification.
 *
 * @param failures unknown + failed goals (as the existing report counts them)
 * @param smokePassed smoke tests that passed (WP could not prove the smoke goal), {@code -1} if
 *     none ran
 * @param smokeTotal smoke tests run, {@code -1} if none ran
 * @param processFailed Frama-C did not finish normally (non-zero exit, wall-clock timeout)
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
        boolean processFailed,
        List<Issue> issues,
        long elapsedMs) {

    public FunctionResult {
        issues = List.copyOf(issues);
    }

    public FunctionStatus status() {
        boolean allProved = proved >= totalGoals && failures == 0 && timeouts == 0;
        return !processFailed && allProved ? FunctionStatus.PROVED : FunctionStatus.FAILED;
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
                && processFailed == other.processFailed;
    }
}
