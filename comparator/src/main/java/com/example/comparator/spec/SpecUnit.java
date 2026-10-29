package com.example.comparator.spec;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One comparable piece of a specification, identified by a stable {@code key} such as {@code
 * function:AddRunner__Add}, {@code predicate:AddRunner_invariant_1} or {@code
 * ghost-fn:addrunner__initialisation}.
 *
 * <p>{@link #clauses} maps each clause's canonical form (see {@link ClauseNormalizer#canonical}),
 * used for matching, to its display form, shown in reports.
 */
public final class SpecUnit {

    private final String key;
    private final Map<String, String> clauses = new LinkedHashMap<>();

    public SpecUnit(String kind, String name) {
        this.key = kind + ":" + name;
    }

    public String key() {
        return key;
    }

    public String kind() {
        return key.substring(0, key.indexOf(':'));
    }

    public Map<String, String> clauses() {
        return clauses;
    }

    /** Adds a clause; {@code section} (e.g. {@code at loop 1}) is kept as a prefix. */
    public void addClause(String section, String clause) {
        String display = ClauseNormalizer.display(clause);
        if (display.isEmpty()) {
            return;
        }
        String prefix = section == null || section.isBlank() ? "" : "[" + ClauseNormalizer.display(section) + "] ";
        clauses.putIfAbsent(prefix + ClauseNormalizer.canonical(clause), prefix + display);
    }

    /** Merges the clauses of another occurrence of the same key (e.g. overloads, repeated blocks). */
    public void mergeFrom(SpecUnit other) {
        other.clauses.forEach(clauses::putIfAbsent);
    }
}
