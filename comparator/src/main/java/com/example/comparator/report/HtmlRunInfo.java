package com.example.comparator.report;

import static com.example.comparator.report.Html.esc;

import com.example.comparator.report.Reports.RunConfig;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Map;

/** Run settings and component revisions, as shown at the top of every page. */
final class HtmlRunInfo {

    private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private HtmlRunInfo() {}

    /** {@code 2026-11-01T12:41:17.85-03:00} → {@code 2026-11-01 12:41}; anything unparsable is kept as is. */
    static String when(String iso) {
        if (iso == null) {
            return "";
        }
        try {
            return OffsetDateTime.parse(iso).format(WHEN);
        } catch (DateTimeParseException e) {
            return iso;
        }
    }

    /** {@code CVC5 · 10 s per goal · 600 s per function · one WP run per operation}. */
    static String settings(RunConfig config) {
        if (config == null) {
            return "";
        }
        if (!config.verify()) {
            return "verification skipped (--no-verify)";
        }
        return config.prover() + " · " + config.timeout() + " s per goal · " + config.functionTimeout()
                + " s per function · " + (config.perOperation() ? "one WP run per operation" : "one WP run for the whole file");
    }

    static String configCard(RunConfig config) {
        if (config == null) {
            return "";
        }
        StringBuilder b = new StringBuilder("<div class=\"card meta\">");
        b.append("<span>WP <b>").append(esc(settings(config))).append("</b></span>");
        Map<String, String> commits = config.commits();
        if (commits != null) {
            commits.forEach((component, rev) -> b.append("<span>").append(esc(componentName(component))).append(" <b class=\"mono\">")
                    .append(esc(rev)).append("</b></span>"));
        }
        b.append("</div>\n");
        if (config.warnings() != null) {
            config.warnings().forEach(w -> b.append("<div class=\"notice\">").append(esc(w)).append("</div>\n"));
        }
        return b.toString();
    }

    private static String componentName(String key) {
        return switch (key) {
            case "plugin" -> "B2ACSLPlugin";
            case "rules" -> "B2ACSLRules";
            case "libPlugin" -> "B2ACSLLib (plugin)";
            case "libRules" -> "B2ACSLLib (rules)";
            default -> key;
        };
    }
}
