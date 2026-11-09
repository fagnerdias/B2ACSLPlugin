package com.example.comparator.report;

import static com.example.comparator.report.Html.esc;
import static com.example.comparator.report.Html.num;

import com.example.comparator.report.HtmlReport.Outcome;
import com.example.comparator.report.Reports.ClauseSets;
import com.example.comparator.report.Reports.ExampleReport;
import com.example.comparator.report.Reports.FunctionComparison;
import com.example.comparator.report.Reports.FunctionResult;
import com.example.comparator.report.Reports.GenerationReport;
import com.example.comparator.report.Reports.MeaningClause;
import com.example.comparator.report.Reports.SpecComparison;
import com.example.comparator.report.Reports.SpecSummary;
import com.example.comparator.report.Reports.SummaryRow;
import com.example.comparator.report.Reports.UnitComparison;
import com.example.comparator.report.Reports.VerificationReport;
import com.example.comparator.spec.meaning.MeaningComparator;
import com.example.comparator.spec.meaning.MeaningRule;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * {@code <example>/report.html}: each side's generation and verification, per-function WP results,
 * the unit-by-unit specification comparison (filterable) and the text diffs.
 */
final class HtmlExamplePage {

    /** Status filter selected when the page opens: every unit that is not equal. */
    private static final String DIFFERENCES = "DIFFERENT ONLY_PLUGIN ONLY_RULES";

    private HtmlExamplePage() {}

    static String render(ExampleReport report, Path exampleDir) {
        int functionTimeout = report.config() == null ? 0 : report.config().functionTimeout();
        Outcome outcome = Outcome.of(summaryRow(report));
        StringBuilder b = new StringBuilder();
        b.append("<a class=\"crumb\" href=\"../").append(HtmlReport.INDEX_FILE).append("\">← All examples</a>\n")
                .append("<header class=\"top\"><h1>").append(esc(report.example())).append("</h1><span class=\"outcome\">")
                .append(outcome.swatches()).append(esc(outcome.label)).append("</span></header>\n")
                .append("<p class=\"sub\">Compared ").append(esc(HtmlRunInfo.when(report.generatedAt()))).append(" · ")
                .append(esc(HtmlRunInfo.settings(report.config()))).append("</p>\n");

        b.append("<h2>Verification</h2>\n<div class=\"sides\">\n")
                .append(sideCard("plugin", generation(report, "plugin"), verification(report, "plugin"), functionTimeout, exampleDir))
                .append(sideCard("rules", generation(report, "rules"), verification(report, "rules"), functionTimeout, exampleDir))
                .append("</div>\n");
        b.append(functions(report));
        b.append(spec(report.spec()));
        b.append(files(report.spec()));
        return Html.page(report.example() + " · B2ACSL comparison", b.toString());
    }

    // ---- verification -------------------------------------------------------------------------

    private static String sideCard(
            String side, GenerationReport g, VerificationReport v, int functionTimeout, Path exampleDir) {
        StringBuilder b = new StringBuilder("<div class=\"card side\"><h3>")
                .append(Html.swatch(side)).append(Html.label(side))
                .append(" <span class=\"muted small\">").append("plugin".equals(side) ? "B2ACSLPlugin" : "B2ACSLRules")
                .append("</span></h3>\n");
        if (v != null && v.total() > 0) {
            b.append("<div class=\"duel\"><div><span class=\"big\">").append(num(v.proved())).append("/").append(num(v.total()))
                    .append("</span> <span class=\"muted\">goals proved · ").append(Html.pct((double) v.proved() / v.total()))
                    .append("</span>")
                    .append(Html.meter(side, (double) v.proved() / v.total(), Html.label(side) + ": " + num(v.proved()) + " of "
                            + num(v.total()) + " goals proved"))
                    .append("</div></div>\n");
        }
        String reason = HtmlReport.failureReason(g, v, functionTimeout);
        if (reason != null) {
            boolean warn = v != null && Html.Tone.of(v.status()) == Html.Tone.WARNING;
            b.append("<div class=\"reason").append(warn ? " warn" : "").append("\">").append(esc(reason)).append("</div>\n");
        }
        b.append("<dl class=\"kv\">");
        if (g != null) {
            b.append(kv("Generation", Html.chip(g.status()) + " <span class=\"muted\">" + Html.seconds(g.elapsedMs()) + "</span>"));
        }
        if (v != null) {
            b.append(kv("Verification", Html.chip(v.status()) + " <span class=\"muted\">" + Html.seconds(v.elapsedMs()) + "</span>"));
            if (v.total() > 0) {
                b.append(kv("Failures / timeouts", num(v.failures()) + " / " + num(v.timeouts())));
            }
            if (v.smokeTests() != null) {
                b.append(kv("Smoke tests", esc(v.smokeTests())));
            }
        }
        b.append("</dl>\n");
        if (g != null) {
            b.append(list("Generated files", g.files(), false, false));
            b.append(list("-acsl-import order", g.acslImportOrder(), true, false));
            b.append(list("Translator warnings", g.warnings(), false, false));
            // a failed generation's message is already the reason shown above
            if ("OK".equals(g.status()) && g.message() != null && !g.message().isBlank()) {
                b.append(output("Generation message", g.message(), false));
            }
        }
        if (v != null && v.importError() != null && !"SKIPPED".equals(v.status())) {
            b.append(output("Frama-C output", v.importError(), false));
        }
        b.append(links(side, v, exampleDir));
        return b.append("</div>\n").toString();
    }

    private static String kv(String key, String valueHtml) {
        return "<dt>" + esc(key) + "</dt><dd>" + valueHtml + "</dd>";
    }

    private static String list(String title, List<String> items, boolean ordered, boolean open) {
        if (items == null || items.isEmpty()) {
            return "";
        }
        String tag = ordered ? "ol" : "ul";
        StringBuilder b = new StringBuilder("<details class=\"block\"" + (open ? " open" : "") + "><summary>")
                .append(esc(title)).append(" (").append(num(items.size())).append(")</summary><").append(tag)
                .append(" class=\"plain mono\">");
        items.forEach(item -> b.append("<li>").append(esc(stripQuotes(item))).append("</li>"));
        return b.append("</").append(tag).append("></details>\n").toString();
    }

    private static String output(String title, String text, boolean open) {
        return "<details class=\"block\"" + (open ? " open" : "") + "><summary>" + esc(title)
                + "</summary><pre class=\"out\">" + esc(text) + "</pre></details>\n";
    }

    /** Links to the side's log, spec snapshot, merged code and raw result, when they exist. */
    private static String links(String side, VerificationReport v, Path exampleDir) {
        List<String> out = new ArrayList<>();
        addLink(out, exampleDir, side + "/run.log", "run.log");
        addLink(out, exampleDir, side + "/spec", "spec/");
        if (v != null && v.mergedCode() != null) {
            Path merged = Path.of(v.mergedCode());
            if (merged.startsWith(exampleDir.toAbsolutePath()) && Files.exists(merged)) {
                addLink(out, exampleDir, exampleDir.toAbsolutePath().relativize(merged).toString().replace('\\', '/'), "merged_code.c");
            }
        }
        addLink(out, exampleDir, side + "/side-result.json", "side-result.json");
        return out.isEmpty() ? "" : "<div class=\"links\">" + String.join("", out) + "</div>\n";
    }

    private static void addLink(List<String> out, Path exampleDir, String relative, String label) {
        if (Files.exists(exampleDir.resolve(relative))) {
            out.add("<a href=\"" + esc(relative) + (Files.isDirectory(exampleDir.resolve(relative)) ? "/" : "") + "\">"
                    + esc(label) + "</a>");
        }
    }

    private static String functions(ExampleReport report) {
        List<FunctionComparison> functions = report.verification() == null ? null : report.verification().functions();
        if (functions == null || functions.isEmpty()) {
            return "";
        }
        StringBuilder b = new StringBuilder("<h2>Per function</h2>\n<div class=\"tablewrap\"><table class=\"sortable\">\n")
                .append("<thead><tr><th data-sort=\"text\">Function</th><th data-sort=\"num\">").append(Html.swatch("plugin"))
                .append(" Plugin</th><th data-sort=\"num\">").append(Html.swatch("rules")).append(" Rules</th></tr></thead>\n<tbody>\n");
        for (FunctionComparison f : functions) {
            b.append("<tr><td class=\"mono\" data-v=\"").append(esc(f.name())).append("\">").append(esc(f.name())).append("</td>")
                    .append(functionCell("plugin", f.plugin())).append(functionCell("rules", f.rules())).append("</tr>\n");
        }
        return b.append("</tbody></table></div>\n").toString();
    }

    private static String functionCell(String side, FunctionResult f) {
        if (f == null) {
            return "<td data-v=\"-1\"><span class=\"muted\">not verified</span></td>";
        }
        double sortKey = f.total() > 0 ? (double) f.proved() / f.total() : -1;
        List<String> counts = new ArrayList<>();
        if (f.failures() > 0) {
            counts.add(num(f.failures()) + " failed");
        }
        if (f.timeouts() > 0) {
            counts.add(num(f.timeouts()) + " timed out");
        }
        if (f.smokeTests() != null) {
            counts.add("smoke " + f.smokeTests());
        }
        return "<td data-v=\"" + sortKey + "\">" + Html.wp(side, f.proved(), f.total())
                + (counts.isEmpty() ? "" : "<div class=\"counts\">" + esc(String.join(" · ", counts)) + "</div>") + "</td>";
    }

    // ---- specification ------------------------------------------------------------------------

    private static String spec(SpecComparison spec) {
        if (spec == null || spec.summary() == null) {
            return "";
        }
        SpecSummary s = spec.summary();
        if (!s.hasMeaning()) {
            return textualSpec(spec);
        }
        StringBuilder b = new StringBuilder("<h2>Specification</h2>\n<div class=\"card\">")
                .append(Html.unitsBar(s, true)).append(Html.unitsLegend(true))
                .append("<div class=\"muted small\">").append(num(s.units())).append(" units: ")
                .append(num(s.equalUnits())).append(" identical, ")
                .append(num(s.sameMeaningUnits())).append(" with the same meaning written differently, ")
                .append(num(s.meaningUnits())).append(" with a different meaning (")
                .append(num(s.meaningBothUnits())).append(" on both sides, ")
                .append(num(s.meaningOnlyPluginUnits())).append(" only in plugin, ")
                .append(num(s.meaningOnlyRulesUnits())).append(" only in rules). Clauses: ")
                .append(num(s.clausesEqual())).append(" identical, ")
                .append(num(s.clausesSameMeaning())).append(" written differently with the same meaning, ")
                .append(num(s.clausesMeaningPlugin())).append(" in plugin and ")
                .append(num(s.clausesMeaningRules())).append(" in rules with a meaning the other side lacks.</div>")
                .append("<p class=\"muted small\">Same meaning: every conjunct of the clause, after the rewrites below, ")
                .append("is also stated by the other side. Different meaning: no rewrite relates them, so this also holds ")
                .append("equivalences the rewrites cannot show.</p>")
                .append(formRules(spec.formRules(), s.sameMeaningUnits()))
                .append(renamings(spec.renamings()))
                .append("</div>\n");
        List<UnitComparison> units = spec.units() == null ? List.of() : spec.units();
        if (units.isEmpty()) {
            return b.toString();
        }
        b.append("<div data-filter-root>\n<div class=\"controls\">")
                .append(differenceFilter(s))
                .append(whereSelect(units))
                .append(kindSelect(units))
                .append("<input type=\"search\" data-search placeholder=\"Filter by name\" aria-label=\"Filter units by name\">")
                .append("<button type=\"button\" class=\"link\" data-toggle-all=\".units details.unit\">Expand all</button>")
                .append("<span class=\"shown\" data-shown></span></div>\n<div class=\"units\">\n");
        units.forEach(u -> b.append(meaningUnit(u)));
        return b.append("</div>\n</div>\n").toString();
    }

    /** The rewrites that explain the units with the same meaning, most used first. */
    private static String formRules(Map<String, Integer> rules, int sameMeaningUnits) {
        if (rules == null || rules.isEmpty()) {
            return "";
        }
        StringBuilder b = new StringBuilder("<details class=\"block\"><summary>Why ").append(num(sameMeaningUnits))
                .append(" unit").append(sameMeaningUnits == 1 ? "" : "s")
                .append(" differ only in form</summary><div class=\"tablewrap\"><table class=\"rules-table\"><tbody>");
        rules.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed().thenComparing(Map.Entry.comparingByKey()))
                .forEach(e -> {
                    MeaningRule rule = MeaningRule.byId(e.getKey());
                    b.append("<tr><td>").append(esc(rule == null ? e.getKey() : rule.label())).append("</td><td class=\"n\">")
                            .append(num(e.getValue())).append("</td><td class=\"d\">")
                            .append(esc(rule == null ? "" : rule.description())).append("</td></tr>");
                });
        return b.append("</tbody></table></div><p class=\"muted small\">A unit counts for each rewrite it needs: ")
                .append("turning that rewrite off leaves some of its clauses without a counterpart.</p></details>").toString();
    }

    private static String renamings(Map<String, String> renamings) {
        if (renamings == null || renamings.isEmpty()) {
            return "";
        }
        StringBuilder b = new StringBuilder("<details class=\"block\"><summary>Names taken to be the same symbol (")
                .append(num(renamings.size())).append(")</summary><ul class=\"plain mono\">");
        renamings.forEach((from, to) -> b.append("<li>").append(esc(from)).append(" (plugin) = ").append(esc(to)).append("</li>"));
        return b.append("</ul></details>").toString();
    }

    private static String differenceFilter(SpecSummary s) {
        int meaning = s.meaningUnits();
        return "<div class=\"seg-ctl\" data-group=\"difference\" role=\"group\" aria-label=\"Kind of difference\">"
                + statusButton("MEANING", Html.swatch("different"), "Different meaning", meaning, meaning > 0)
                + statusButton("FORM", Html.swatch("form"), "Same meaning", s.sameMeaningUnits(), false)
                + statusButton("NONE", Html.swatch("equal"), "Identical", s.equalUnits(), false)
                + statusButton("*", "", "All", s.units(), meaning == 0)
                + "</div>";
    }

    private static String whereSelect(List<UnitComparison> units) {
        Map<String, Integer> counts = new TreeMap<>();
        units.forEach(u -> counts.merge(u.status(), 1, Integer::sum));
        return "<select data-select=\"status\" aria-label=\"Where the unit is\"><option value=\"\">Both or one side</option>"
                + whereOption("DIFFERENT", "On both sides, different", counts)
                + whereOption("ONLY_PLUGIN", "Only in plugin", counts)
                + whereOption("ONLY_RULES", "Only in rules", counts)
                + "</select>";
    }

    private static String whereOption(String value, String label, Map<String, Integer> counts) {
        return "<option value=\"" + value + "\">" + esc(label) + " (" + num(counts.getOrDefault(value, 0)) + ")</option>";
    }

    private static String meaningUnit(UnitComparison u) {
        String kind = kind(u.key());
        int colon = u.key().indexOf(':');
        String name = u.key().substring(colon + 1);
        String difference = u.difference() == null ? "NONE" : u.difference();
        String text = (u.key() + (u.rulesKey() == null ? "" : " " + u.rulesKey())).toLowerCase(Locale.ROOT);
        String attrs = " data-item data-difference=\"" + difference + "\" data-status=\"" + esc(u.status()) + "\" data-kind=\""
                + esc(kind) + "\" data-text=\"" + esc(text) + "\"";
        String renamed = u.rulesKey() == null ? ""
                : "<span class=\"renamed\">rules: " + esc(u.rulesKey().substring(u.rulesKey().indexOf(':') + 1)) + "</span>";
        String head = "<span class=\"ustat\">" + meaningLabel(u) + "</span><span class=\"ukey mono\">"
                + (colon < 0 ? "" : "<span class=\"kind\">" + esc(kind) + ":</span>") + esc(name) + renamed
                + "</span><span class=\"ucount\">" + meaningCounts(u) + "</span>";
        if (u.clauses() == null) {
            return "<div class=\"unit\"" + attrs + "><div class=\"row\">" + head + "</div></div>\n";
        }
        return "<details class=\"unit\"" + attrs + "><summary>" + head + "</summary><div class=\"ubody\">" + meaningClauses(u)
                + "</div></details>\n";
    }

    private static String meaningLabel(UnitComparison u) {
        if ("EQUAL".equals(u.status())) {
            return Html.swatch("equal") + "Identical";
        }
        if (MeaningComparator.FORM.equals(u.difference())) {
            return Html.swatch("form") + "Same meaning";
        }
        return switch (u.status()) {
            case "ONLY_PLUGIN" -> Html.swatch("plugin") + "Only plugin";
            case "ONLY_RULES" -> Html.swatch("rules") + "Only rules";
            default -> Html.swatch("different") + "Different";
        };
    }

    private static String meaningCounts(UnitComparison u) {
        ClauseSets c = u.clauses();
        if (c == null) {
            return num(u.clauseCount()) + (u.clauseCount() == 1 ? " clause" : " clauses");
        }
        int meaning = size(c.meaningPlugin()) + size(c.meaningRules());
        int form = size(c.onlyPlugin()) + size(c.onlyRules()) - meaning;
        List<String> parts = new ArrayList<>();
        if (meaning > 0) {
            parts.add(num(meaning) + " meaning");
        }
        if (form > 0) {
            parts.add(num(form) + " form");
        }
        if (size(c.equal()) > 0) {
            parts.add(num(size(c.equal())) + " identical");
        }
        return String.join(" · ", parts);
    }

    private static String meaningClauses(UnitComparison u) {
        ClauseSets c = u.clauses();
        StringBuilder b = new StringBuilder();
        boolean plugin = size(c.meaningPlugin()) > 0;
        boolean rules = size(c.meaningRules()) > 0;
        if (plugin || rules) {
            b.append("<div class=\"cols\">");
            if (plugin) {
                b.append(meaningList("c-plugin", Html.swatch("plugin") + "Meaning only in plugin", c.meaningPlugin()));
            }
            if (rules) {
                b.append(meaningList("c-rules", Html.swatch("rules") + "Meaning only in rules", c.meaningRules()));
            }
            b.append("</div>");
        }
        List<String> formPlugin = formOnly(c.onlyPlugin(), c.meaningPlugin());
        List<String> formRules = formOnly(c.onlyRules(), c.meaningRules());
        if (!formPlugin.isEmpty() || !formRules.isEmpty() || (u.formRules() != null && !u.formRules().isEmpty())) {
            boolean open = MeaningComparator.FORM.equals(u.difference());
            int n = formPlugin.size() + formRules.size();
            b.append("<details class=\"more\"").append(open ? " open" : "").append("><summary class=\"muted small\">")
                    .append(num(n)).append(" clause").append(n == 1 ? "" : "s")
                    .append(" with the same meaning, written differently</summary>");
            if (u.formRules() != null && !u.formRules().isEmpty()) {
                b.append("<div class=\"rules\">");
                u.formRules().forEach(id -> b.append(Html.formRule(id)));
                b.append("</div>");
            }
            if (n > 0) {
                b.append("<div class=\"cols\">");
                if (!formPlugin.isEmpty()) {
                    b.append(clauseList("c-form", Html.swatch("plugin") + "Plugin", formPlugin));
                }
                if (!formRules.isEmpty()) {
                    b.append(clauseList("c-form", Html.swatch("rules") + "Rules", formRules));
                }
                b.append("</div>");
            }
            b.append("</details>");
        }
        if (size(c.equal()) > 0) {
            b.append("<details class=\"more\"><summary class=\"muted small\">").append(num(size(c.equal())))
                    .append(" identical clause").append(size(c.equal()) == 1 ? "" : "s").append("</summary>")
                    .append(clauseList("c-equal", "", c.equal())).append("</details>");
        }
        return b.toString();
    }

    private static List<String> formOnly(List<String> only, List<MeaningClause> meaning) {
        if (only == null) {
            return List.of();
        }
        java.util.Set<String> different = new java.util.HashSet<>();
        if (meaning != null) {
            meaning.forEach(m -> different.add(m.clause()));
        }
        return only.stream().filter(c -> !different.contains(c)).toList();
    }

    private static String meaningList(String css, String titleHtml, List<MeaningClause> clauses) {
        StringBuilder b = new StringBuilder("<div class=\"clauses " + css + "\"><h4>").append(titleHtml)
                .append(" <span class=\"muted\">").append(num(clauses.size())).append("</span></h4><ol>");
        for (MeaningClause c : clauses) {
            b.append("<li class=\"mono\">").append(esc(c.clause()));
            if (c.conjuncts() != null && !c.conjuncts().isEmpty()) {
                b.append("<span class=\"conj-label\">without a counterpart:</span><ul class=\"conj\">");
                c.conjuncts().forEach(x -> b.append("<li>").append(esc(x)).append("</li>"));
                b.append("</ul>");
            }
            b.append("</li>");
        }
        return b.append("</ol></div>").toString();
    }

    /** Reports written before the meaning comparison: units by textual status only. */
    private static String textualSpec(SpecComparison spec) {
        SpecSummary s = spec.summary();
        StringBuilder b = new StringBuilder("<h2>Specification</h2>\n<div class=\"card\">")
                .append(Html.unitsBar(s, true)).append(Html.unitsLegend(false))
                .append("<div class=\"muted small\">").append(num(s.units())).append(" units: ")
                .append(num(s.equalUnits())).append(" equal, ").append(num(s.differentUnits())).append(" different, ")
                .append(num(s.onlyPluginUnits())).append(" only plugin, ").append(num(s.onlyRulesUnits())).append(" only rules · clauses: ")
                .append(num(s.clausesEqual())).append(" equal, ").append(num(s.clausesOnlyPlugin())).append(" only plugin, ")
                .append(num(s.clausesOnlyRules())).append(" only rules. Clauses are matched on a canonical form ")
                .append("(comments, spacing, outer parentheses and bound-variable names ignored).</div></div>\n");
        List<UnitComparison> units = spec.units() == null ? List.of() : spec.units();
        if (units.isEmpty()) {
            return b.toString();
        }
        b.append("<div data-filter-root>\n<div class=\"controls\">")
                .append(statusFilter(s))
                .append(kindSelect(units))
                .append("<input type=\"search\" data-search placeholder=\"Filter by name\" aria-label=\"Filter units by name\">")
                .append("<button type=\"button\" class=\"link\" data-toggle-all=\".units details.unit\">Expand all</button>")
                .append("<span class=\"shown\" data-shown></span></div>\n<div class=\"units\">\n");
        units.forEach(u -> b.append(unit(u)));
        return b.append("</div>\n</div>\n").toString();
    }

    private static String statusFilter(SpecSummary s) {
        int differences = s.differentUnits() + s.onlyPluginUnits() + s.onlyRulesUnits();
        return "<div class=\"seg-ctl\" data-group=\"status\" role=\"group\" aria-label=\"Unit status\">"
                + statusButton(DIFFERENCES, "", "Differences", differences, differences > 0)
                + statusButton("*", "", "All", s.units(), differences == 0)
                + statusButton("DIFFERENT", Html.swatch("different"), "Different", s.differentUnits(), false)
                + statusButton("ONLY_PLUGIN", Html.swatch("plugin"), "Only plugin", s.onlyPluginUnits(), false)
                + statusButton("ONLY_RULES", Html.swatch("rules"), "Only rules", s.onlyRulesUnits(), false)
                + statusButton("EQUAL", Html.swatch("equal"), "Equal", s.equalUnits(), false)
                + "</div>";
    }

    private static String statusButton(String value, String swatch, String label, int count, boolean pressed) {
        return "<button type=\"button\" data-value=\"" + value + "\" aria-pressed=\"" + pressed + "\">" + swatch + esc(label)
                + " <span class=\"muted tabnum\">" + num(count) + "</span></button>";
    }

    private static String kindSelect(List<UnitComparison> units) {
        Map<String, Integer> kinds = new TreeMap<>();
        units.forEach(u -> kinds.merge(kind(u.key()), 1, Integer::sum));
        StringBuilder b = new StringBuilder("<select data-select=\"kind\" aria-label=\"Unit kind\"><option value=\"\">All kinds</option>");
        kinds.forEach((k, n) -> b.append("<option value=\"").append(esc(k)).append("\">").append(esc(k)).append(" (")
                .append(num(n)).append(")</option>"));
        return b.append("</select>").toString();
    }

    private static String unit(UnitComparison u) {
        String kind = kind(u.key());
        int colon = u.key().indexOf(':');
        String name = u.key().substring(colon + 1);
        String attrs = " data-item data-status=\"" + esc(u.status()) + "\" data-kind=\"" + esc(kind) + "\" data-text=\""
                + esc(u.key().toLowerCase(Locale.ROOT)) + "\"";
        String head = "<span class=\"ustat\">" + statusLabel(u.status()) + "</span><span class=\"ukey mono\">"
                + (colon < 0 ? "" : "<span class=\"kind\">" + esc(kind) + ":</span>") + esc(name)
                + "</span><span class=\"ucount\">" + clauseCounts(u) + "</span>";
        if ("EQUAL".equals(u.status()) || u.clauses() == null) {
            return "<div class=\"unit\"" + attrs + "><div class=\"row\">" + head + "</div></div>\n";
        }
        return "<details class=\"unit\"" + attrs + "><summary>" + head + "</summary><div class=\"ubody\">" + clauses(u.clauses())
                + "</div></details>\n";
    }

    private static String statusLabel(String status) {
        return switch (status) {
            case "EQUAL" -> Html.swatch("equal") + "Equal";
            case "DIFFERENT" -> Html.swatch("different") + "Different";
            case "ONLY_PLUGIN" -> Html.swatch("plugin") + "Only plugin";
            case "ONLY_RULES" -> Html.swatch("rules") + "Only rules";
            default -> esc(status);
        };
    }

    private static String clauseCounts(UnitComparison u) {
        ClauseSets c = u.clauses();
        if (c == null || !"DIFFERENT".equals(u.status())) {
            return num(u.clauseCount()) + (u.clauseCount() == 1 ? " clause" : " clauses");
        }
        return num(size(c.onlyPlugin())) + " plugin · " + num(size(c.onlyRules())) + " rules · " + num(size(c.equal())) + " equal";
    }

    private static String clauses(ClauseSets c) {
        StringBuilder b = new StringBuilder();
        boolean plugin = size(c.onlyPlugin()) > 0;
        boolean rules = size(c.onlyRules()) > 0;
        if (plugin || rules) {
            b.append("<div class=\"cols\">");
            if (plugin) {
                b.append(clauseList("c-plugin", Html.swatch("plugin") + "Only in plugin", c.onlyPlugin()));
            }
            if (rules) {
                b.append(clauseList("c-rules", Html.swatch("rules") + "Only in rules", c.onlyRules()));
            }
            b.append("</div>");
        }
        if (size(c.equal()) > 0) {
            b.append("<details class=\"more\"><summary class=\"muted small\">").append(num(size(c.equal())))
                    .append(" equal clause").append(size(c.equal()) == 1 ? "" : "s").append("</summary>")
                    .append(clauseList("c-equal", "", c.equal())).append("</details>");
        }
        return b.toString();
    }

    private static String clauseList(String css, String titleHtml, List<String> clauses) {
        StringBuilder b = new StringBuilder("<div class=\"clauses " + css + "\">");
        if (!titleHtml.isEmpty()) {
            b.append("<h4>").append(titleHtml).append(" <span class=\"muted\">").append(num(clauses.size())).append("</span></h4>");
        }
        b.append("<ol>");
        clauses.forEach(clause -> b.append("<li class=\"mono\">").append(esc(clause)).append("</li>"));
        return b.append("</ol></div>").toString();
    }

    // ---- files --------------------------------------------------------------------------------

    private static String files(SpecComparison spec) {
        if (spec == null) {
            return "";
        }
        StringBuilder b = new StringBuilder("<h2>Files</h2>\n");
        Map<String, List<String>> onlyIn = spec.filesOnlyIn();
        if (onlyIn != null) {
            onlyIn.forEach((side, names) -> {
                if (names != null && !names.isEmpty()) {
                    b.append("<p>").append(Html.swatch(side)).append(" Only in ").append(esc(side)).append(": <span class=\"mono\">")
                            .append(esc(String.join(", ", names))).append("</span></p>\n");
                }
            });
        }
        Map<String, String> diffs = spec.textDiff();
        if (diffs == null || diffs.isEmpty()) {
            return b.append("<p class=\"muted\">No file with the same name on both sides.</p>\n").toString();
        }
        b.append("<div class=\"legend\"><span>Whitespace-normalized diff of the files both sides wrote:</span><span>")
                .append(Html.swatch("plugin")).append("− only in plugin</span><span>").append(Html.swatch("rules"))
                .append("+ only in rules</span></div>\n");
        diffs.forEach((file, diff) -> b.append(diffBlock(file, diff)));
        return b.toString();
    }

    private static String diffBlock(String file, String diff) {
        if (diff == null || diff.isBlank()) {
            return "<p><span class=\"mono\">" + esc(file) + "</span> <span class=\"muted\">identical</span></p>\n";
        }
        int added = 0;
        int removed = 0;
        StringBuilder lines = new StringBuilder();
        for (String line : diff.split("\n", -1)) {
            String css;
            if (line.startsWith("+++") || line.startsWith("---")) {
                css = "meta";
            } else if (line.startsWith("@@")) {
                css = "hunk";
            } else if (line.startsWith("+")) {
                css = "add";
                added++;
            } else if (line.startsWith("-")) {
                css = "del";
                removed++;
            } else {
                css = "";
            }
            lines.append("<span").append(css.isEmpty() ? "" : " class=\"" + css + "\"").append(">").append(esc(line)).append("</span>");
        }
        return "<details class=\"block\"><summary><span class=\"file-sum\"><span class=\"mono\">" + esc(file)
                + "</span><span class=\"counts tabnum\">−" + num(removed) + " +" + num(added) + "</span></span></summary>"
                + "<pre class=\"diff\">" + lines + "</pre></details>\n";
    }

    // ---- helpers ------------------------------------------------------------------------------

    private static GenerationReport generation(ExampleReport report, String side) {
        return report.generation() == null ? null : report.generation().get(side);
    }

    private static VerificationReport verification(ExampleReport report, String side) {
        if (report.verification() == null) {
            return null;
        }
        return "plugin".equals(side) ? report.verification().plugin() : report.verification().rules();
    }

    /** The {@link SummaryRow} fields {@link Outcome#of} needs, rebuilt from the full report. */
    private static SummaryRow summaryRow(ExampleReport report) {
        VerificationReport p = verification(report, "plugin");
        VerificationReport r = verification(report, "rules");
        return new SummaryRow(
                report.example(), null, null, null,
                p == null ? null : p.status(), p == null ? 0 : p.proved(), p == null ? 0 : p.total(),
                r == null ? null : r.status(), r == null ? 0 : r.proved(), r == null ? 0 : r.total());
    }

    private static String kind(String key) {
        int colon = key.indexOf(':');
        return colon < 0 ? "other" : key.substring(0, colon);
    }

    private static int size(List<?> list) {
        return list == null ? 0 : list.size();
    }

    /** Translator warnings arrive quoted ({@code 'Customer.buy: ...'}). */
    private static String stripQuotes(String s) {
        return s.length() >= 2 && s.startsWith("'") && s.endsWith("'") ? s.substring(1, s.length() - 1) : s;
    }
}
