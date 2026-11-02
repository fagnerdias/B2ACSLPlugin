package com.example.comparator.report;

import com.example.comparator.report.Reports.SpecSummary;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * Markup helpers shared by the pages of {@link HtmlReport}. Every page is self-contained: the
 * stylesheet and script are inlined from this package's resources, so it opens from disk with no
 * network access.
 */
final class Html {

    private static final String CSS = resource("report.css");
    private static final String JS = resource("report.js");

    private Html() {}

    /** Severity of a generation/verification status; shown as icon + label, never colour alone. */
    enum Tone {
        GOOD("good", "✓"),
        WARNING("warning", "!"),
        CRITICAL("critical", "✕"),
        NEUTRAL("neutral", "–");

        final String css;
        final String icon;

        Tone(String css, String icon) {
            this.css = css;
            this.icon = icon;
        }

        static Tone of(String status) {
            if (status == null) {
                return NEUTRAL;
            }
            return switch (status) {
                case "OK", "COMPLETED" -> GOOD;
                case "WP_TIMEOUT", "IMPORT_TIMEOUT", "TIMEOUT" -> WARNING;
                case "SKIPPED", "NOT_AVAILABLE", "NO_C_FILES", "NO_ACSL_FILES" -> NEUTRAL;
                default -> CRITICAL;
            };
        }
    }

    static String page(String title, String body) {
        return "<!doctype html>\n<html lang=\"en\">\n<head>\n<meta charset=\"utf-8\">\n"
                + "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">\n"
                + "<title>" + esc(title) + "</title>\n<style>\n" + CSS + "</style>\n</head>\n<body>\n"
                + "<div class=\"wrap\">\n" + body + "</div>\n"
                + "<div id=\"tip\" role=\"tooltip\" hidden></div>\n<script>\n" + JS + "</script>\n</body>\n</html>\n";
    }

    static String esc(String s) {
        if (s == null) {
            return "";
        }
        StringBuilder out = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '&' -> out.append("&amp;");
                case '<' -> out.append("&lt;");
                case '>' -> out.append("&gt;");
                case '"' -> out.append("&quot;");
                case '\'' -> out.append("&#39;");
                default -> out.append(c);
            }
        }
        return out.toString();
    }

    static String chip(String status) {
        Tone tone = Tone.of(status);
        return "<span class=\"chip tone-" + tone.css + "\"><span class=\"chip-icon\" aria-hidden=\"true\">"
                + tone.icon + "</span>" + esc(status == null ? "—" : status) + "</span>";
    }

    /** Swatch of a side ({@code plugin}/{@code rules}) or unit status ({@code equal}/{@code different}). */
    static String swatch(String kind) {
        return "<span class=\"sw sw-" + kind + "\" aria-hidden=\"true\"></span>";
    }

    /** Proved/total meter in the side's colour, followed by the numbers. */
    static String wp(String side, int proved, int total) {
        if (total <= 0) {
            return "<span class=\"wp\"><span class=\"num tabnum muted\">—</span></span>";
        }
        double ratio = (double) proved / total;
        String tip = label(side) + ": " + num(proved) + " of " + num(total) + " goals proved (" + pct(ratio) + ")";
        return "<span class=\"wp\">" + meter(side, ratio, tip) + "<span class=\"num tabnum\">" + num(proved) + "/"
                + num(total) + " <span class=\"muted\">" + pct(ratio) + "</span></span></span>";
    }

    static String meter(String side, double ratio, String tip) {
        double width = Math.max(0, Math.min(1, ratio)) * 100;
        return "<span class=\"meter m-" + side + (ratio >= 1 ? " full" : "") + "\" data-tip=\"" + esc(tip)
                + "\" role=\"img\" aria-label=\"" + esc(tip) + "\"><span style=\"width:"
                + String.format(Locale.ROOT, "%.2f", width) + "%\"></span></span>";
    }

    /** Stacked bar of unit statuses: equal, different, only plugin, only rules. */
    static String unitsBar(SpecSummary s, boolean wide) {
        if (s == null || s.units() == 0) {
            return "<span class=\"muted\">no units</span>";
        }
        String tip = "Units: " + num(s.units())
                + "\nEqual: " + num(s.equalUnits())
                + "\nDifferent: " + num(s.differentUnits())
                + "\nOnly plugin: " + num(s.onlyPluginUnits())
                + "\nOnly rules: " + num(s.onlyRulesUnits());
        return "<span class=\"ubar" + (wide ? " wide" : "") + "\" data-tip=\"" + esc(tip) + "\" role=\"img\" aria-label=\""
                + esc(tip.replace('\n', ',')) + "\">"
                + segment("equal", s.equalUnits())
                + segment("different", s.differentUnits())
                + segment("plugin", s.onlyPluginUnits())
                + segment("rules", s.onlyRulesUnits())
                + "</span>";
    }

    private static String segment(String kind, int count) {
        return count == 0 ? "" : "<span class=\"seg-" + kind + "\" style=\"flex-grow:" + count + "\"></span>";
    }

    static String unitsLegend() {
        return "<div class=\"legend\"><span>" + swatch("equal") + "Equal</span><span>" + swatch("different")
                + "Different</span><span>" + swatch("plugin") + "Only plugin</span><span>" + swatch("rules")
                + "Only rules</span></div>";
    }

    static String label(String side) {
        return "plugin".equals(side) ? "Plugin" : "Rules";
    }

    static String num(long n) {
        return String.format(Locale.ROOT, "%,d", n);
    }

    static String pct(double ratio) {
        return String.format(Locale.ROOT, "%.1f%%", ratio * 100);
    }

    static String seconds(long ms) {
        if (ms < 1000) {
            return ms + " ms";
        }
        long s = Math.round(ms / 1000.0);
        return s < 60 ? s + " s" : (s / 60) + " min " + (s % 60) + " s";
    }

    /** Text cut to {@code max} characters (with an ellipsis), for table cells whose full text goes to a tooltip. */
    static String cut(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }

    private static String resource(String name) {
        try (InputStream in = Html.class.getResourceAsStream(name)) {
            if (in == null) {
                throw new IllegalStateException("missing resource " + name);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
