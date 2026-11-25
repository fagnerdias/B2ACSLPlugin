package com.example.gui.comparison;

import com.example.gui.model.FunctionComparison;
import com.example.gui.model.FunctionResult;
import com.example.gui.model.FunctionStatus;
import com.example.gui.model.Generator;
import com.example.gui.model.Verdict;
import com.example.gui.model.VerificationRun;
import java.util.ArrayList;
import java.util.List;

/** Compares the WP results of the two specifications, function by function, in plain language. */
public final class ResultComparator {

    public static final String NO_DIFFERENCE = "—";

    private ResultComparator() {}

    public static List<FunctionComparison> compare(List<String> functions, VerificationRun plugin, VerificationRun atl) {
        List<FunctionComparison> out = new ArrayList<>();
        for (String fn : functions) {
            out.add(compare(fn, plugin.result(fn).orElse(null), atl.result(fn).orElse(null)));
        }
        return out;
    }

    /** "Same result" when proved/total, failures, timeouts and smoke tests are all equal. */
    public static FunctionComparison compare(String function, FunctionResult plugin, FunctionResult atl) {
        if (plugin == null || atl == null) {
            String missing = plugin == null ? Generator.PLUGIN.label() : Generator.ATL.label();
            return new FunctionComparison(function, plugin, atl, false, "Not verified with " + missing);
        }
        boolean same = plugin.sameCountsAs(atl);
        return new FunctionComparison(function, plugin, atl, same, same ? NO_DIFFERENCE : difference(plugin, atl));
    }

    /**
     * Attributes each difference to the side that has more of it, e.g. "ATL Rules: 3 more goals, 1
     * failure and 1 timeout" (a count is "1 failure" when the other side has none, "1 more failure"
     * otherwise).
     */
    static String difference(FunctionResult plugin, FunctionResult atl) {
        List<String> pluginParts = new ArrayList<>();
        List<String> atlParts = new ArrayList<>();
        addGoals(plugin.totalGoals(), atl.totalGoals(), pluginParts, atlParts);
        addCount(plugin.failures(), atl.failures(), "failure", pluginParts, atlParts);
        addCount(plugin.timeouts(), atl.timeouts(), "timeout", pluginParts, atlParts);
        addCount(plugin.smokeFailed(), atl.smokeFailed(), "failed smoke test", pluginParts, atlParts);
        if (plugin.processFailed() != atl.processFailed()) {
            (plugin.processFailed() ? pluginParts : atlParts).add("Frama-C did not finish");
        }
        if (plugin.hasSmokeTests() && atl.hasSmokeTests() && plugin.smokeTotal() != atl.smokeTotal()) {
            int d = atl.smokeTotal() - plugin.smokeTotal();
            (d > 0 ? atlParts : pluginParts).add(Math.abs(d) + " more " + plural(Math.abs(d), "smoke test"));
        }
        if (pluginParts.isEmpty() && atlParts.isEmpty() && plugin.proved() != atl.proved()) {
            int d = atl.proved() - plugin.proved();
            (d > 0 ? atlParts : pluginParts).add(Math.abs(d) + " more proved " + plural(Math.abs(d), "goal"));
        }
        List<String> sentences = new ArrayList<>();
        if (!atlParts.isEmpty()) {
            sentences.add(Generator.ATL.label() + ": " + join(atlParts));
        }
        if (!pluginParts.isEmpty()) {
            sentences.add(Generator.PLUGIN.label() + ": " + join(pluginParts));
        }
        if (sentences.isEmpty()) {
            return "Plugin " + plugin.proved() + " / " + plugin.totalGoals() + ", ATL Rules "
                    + atl.proved() + " / " + atl.totalGoals();
        }
        return String.join("; ", sentences);
    }

    private static void addGoals(int plugin, int atl, List<String> pluginParts, List<String> atlParts) {
        if (plugin == atl) {
            return;
        }
        int d = Math.abs(atl - plugin);
        (atl > plugin ? atlParts : pluginParts).add(d + " more " + plural(d, "goal"));
    }

    private static void addCount(int plugin, int atl, String noun, List<String> pluginParts, List<String> atlParts) {
        if (plugin == atl) {
            return;
        }
        boolean atlMore = atl > plugin;
        int d = Math.abs(atl - plugin);
        int other = atlMore ? plugin : atl;
        String part = other == 0 ? d + " " + plural(d, noun) : d + " more " + plural(d, noun);
        (atlMore ? atlParts : pluginParts).add(part);
    }

    /** The verdict callout: warning style when some function differs, success style otherwise. */
    public static Verdict verdict(List<FunctionComparison> rows) {
        List<FunctionComparison> different = rows.stream().filter(r -> !r.same()).toList();
        int n = rows.size();
        if (different.isEmpty()) {
            return new Verdict(
                    true, 0, n,
                    "Same results in all " + n + " " + plural(n, "function"),
                    "Both specifications produced the same verification results for every function.",
                    null);
        }
        FunctionComparison first = different.get(0);
        String sentence = describe(first);
        if (different.size() > 1) {
            int others = different.size() - 1;
            sentence += " " + others + " other " + plural(others, "function") + " also "
                    + (others == 1 ? "differs." : "differ.");
        }
        return new Verdict(
                false, different.size(), n,
                "Results differ in " + different.size() + " of " + n + " " + plural(n, "function"),
                sentence,
                first.function());
    }

    /**
     * "AddRunner__Add has 1 failure and 1 timeout with the ATL Rules specification, while the Plugin
     * specification proved all of its goals."
     */
    static String describe(FunctionComparison row) {
        FunctionResult p = row.plugin();
        FunctionResult a = row.atl();
        if (p == null || a == null) {
            return row.function() + " was not verified with the "
                    + (p == null ? Generator.PLUGIN : Generator.ATL).label() + " specification.";
        }
        boolean pOk = p.status() == FunctionStatus.PROVED;
        boolean aOk = a.status() == FunctionStatus.PROVED;
        if (pOk != aOk) {
            FunctionResult bad = pOk ? a : p;
            FunctionResult good = pOk ? p : a;
            return row.function() + " has " + problems(bad) + " with the " + bad.generator().label()
                    + " specification, while the " + good.generator().label()
                    + " specification proved all of its goals.";
        }
        if (!pOk) {
            return row.function() + " has " + problems(p) + " with the Plugin specification and "
                    + problems(a) + " with the ATL Rules specification.";
        }
        return row.function() + " is proved with both specifications, but the goals differ: "
                + row.difference() + ".";
    }

    /** "1 failure and 1 timeout", "2 unproved goals", "a Frama-C error". */
    static String problems(FunctionResult r) {
        List<String> parts = new ArrayList<>();
        if (r.failures() > 0) {
            parts.add(r.failures() + " " + plural(r.failures(), "failure"));
        }
        if (r.timeouts() > 0) {
            parts.add(r.timeouts() + " " + plural(r.timeouts(), "timeout"));
        }
        if (parts.isEmpty() && r.processFailed()) {
            parts.add("a Frama-C error");
        }
        if (parts.isEmpty()) {
            int unproved = Math.max(0, r.totalGoals() - r.proved());
            parts.add(unproved + " unproved " + plural(unproved, "goal"));
        }
        return join(parts);
    }

    /** Badge of a summary column: "All goals proved" or "N failure(s) · N timeout(s)". */
    public static String badge(VerificationRun run) {
        if (run.allProved()) {
            return "All goals proved";
        }
        return run.failures() + " " + plural(run.failures(), "failure") + " · " + run.timeouts() + " "
                + plural(run.timeouts(), "timeout");
    }

    static String join(List<String> parts) {
        if (parts.size() == 1) {
            return parts.get(0);
        }
        return String.join(", ", parts.subList(0, parts.size() - 1)) + " and " + parts.get(parts.size() - 1);
    }

    public static String plural(int n, String noun) {
        return n == 1 ? noun : noun + "s";
    }
}
