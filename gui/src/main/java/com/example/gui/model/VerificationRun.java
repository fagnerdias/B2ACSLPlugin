package com.example.gui.model;

import java.util.List;
import java.util.Optional;

/** All WP results of one specification. */
public record VerificationRun(
        GeneratedSpecification spec, List<FunctionResult> results, long elapsedMs, String rawLog) {

    public VerificationRun {
        results = List.copyOf(results);
    }

    public Generator generator() {
        return spec.generator();
    }

    public Optional<FunctionResult> result(String function) {
        return results.stream().filter(r -> r.function().equals(function)).findFirst();
    }

    public int totalGoals() {
        return results.stream().mapToInt(FunctionResult::totalGoals).sum();
    }

    public int proved() {
        return results.stream().mapToInt(FunctionResult::proved).sum();
    }

    public int failures() {
        return results.stream().mapToInt(FunctionResult::failures).sum();
    }

    public int timeouts() {
        return results.stream().mapToInt(FunctionResult::timeouts).sum();
    }

    public boolean hasSmokeTests() {
        return results.stream().anyMatch(FunctionResult::hasSmokeTests);
    }

    public int smokePassed() {
        return results.stream().filter(FunctionResult::hasSmokeTests).mapToInt(FunctionResult::smokePassed).sum();
    }

    public int smokeTotal() {
        return results.stream().filter(FunctionResult::hasSmokeTests).mapToInt(FunctionResult::smokeTotal).sum();
    }

    public String smokeDisplay() {
        return hasSmokeTests() ? smokePassed() + " / " + smokeTotal() : "—";
    }

    public boolean allProved() {
        return results.stream().allMatch(r -> r.status() == FunctionStatus.PROVED);
    }

    public List<Issue> issues() {
        return results.stream().flatMap(r -> r.issues().stream()).toList();
    }
}
