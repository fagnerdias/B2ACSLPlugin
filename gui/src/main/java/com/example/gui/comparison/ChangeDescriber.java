package com.example.gui.comparison;

import com.example.gui.comparison.SpecificationDiffer.DiffRow;
import com.example.gui.comparison.SpecificationDiffer.RowKind;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** One plain-language sentence per contract difference ("What changed"). */
public final class ChangeDescriber {

    private ChangeDescriber() {}

    static String describe(DiffRow row, boolean signature) {
        if (signature) {
            return switch (row.kind()) {
                case ONLY_PLUGIN -> "The function exists only in the Plugin specification.";
                case ONLY_ATL -> "The function exists only in the ATL Rules specification.";
                default -> "The signature is different: Plugin “" + row.plugin().text() + "”, ATL Rules “"
                        + row.atl().text() + "”.";
            };
        }
        if (row.kind() == RowKind.ONLY_PLUGIN || row.kind() == RowKind.ONLY_ATL) {
            String text = row.kind() == RowKind.ONLY_PLUGIN ? row.plugin().text() : row.atl().text();
            String kind = SpecificationDiffer.kind(text);
            return "The " + clauseName(kind) + " “" + body(text, kind) + "” exists only in the "
                    + (row.kind() == RowKind.ONLY_PLUGIN ? "Plugin" : "ATL Rules") + " specification.";
        }
        String kind = SpecificationDiffer.kind(row.plugin().text());
        String p = body(row.plugin().text(), kind);
        String a = body(row.atl().text(), kind);
        if (kind.equals("assigns")) {
            Set<String> pl = locations(p);
            Set<String> al = locations(a);
            if (al.containsAll(pl) && !pl.containsAll(al)) {
                al.removeAll(pl);
                return "The assigns clause is different: the ATL Rules specification also allows changes to "
                        + String.join(", ", al) + ".";
            }
            if (pl.containsAll(al) && !al.containsAll(pl)) {
                pl.removeAll(al);
                return "The assigns clause is different: the Plugin specification also allows changes to "
                        + String.join(", ", pl) + ".";
            }
            if (pl.equals(al)) {
                return "The assigns clause lists the same locations in a different order (same meaning): Plugin “"
                        + p + "”, ATL Rules “" + a + "”.";
            }
        }
        return "The " + clauseName(kind) + " is different: Plugin “" + p + "”, ATL Rules “" + a + "”.";
    }

    /** requires → precondition, ensures → postcondition, assigns → assigns clause, ... */
    static String clauseName(String kind) {
        return switch (kind) {
            case "requires" -> "precondition";
            case "ensures" -> "postcondition";
            case "assigns" -> "assigns clause";
            case "behavior" -> "behavior";
            case "" -> "clause";
            default -> kind + " clause";
        };
    }

    /** The clause without its keyword and final semicolon: "requires x > 0;" → "x > 0". */
    static String body(String text, String kind) {
        String t = text.trim();
        if (t.endsWith(";") || t.endsWith(":")) {
            t = t.substring(0, t.length() - 1).trim();
        }
        String lower = t.toLowerCase(java.util.Locale.ROOT);
        for (String prefix : new String[] {"check " + kind, "admit " + kind, kind}) {
            if (!kind.isEmpty() && lower.startsWith(prefix)) {
                return t.substring(prefix.length()).trim();
            }
        }
        return t;
    }

    /** Top-level comma-separated locations of an assigns clause body. */
    static Set<String> locations(String body) {
        List<String> out = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        int depth = 0;
        for (char c : body.toCharArray()) {
            if (c == '(' || c == '[' || c == '{') {
                depth++;
            } else if ((c == ')' || c == ']' || c == '}') && depth > 0) {
                depth--;
            }
            if (c == ',' && depth == 0) {
                out.add(current.toString().trim());
                current.setLength(0);
            } else {
                current.append(c);
            }
        }
        if (!current.toString().isBlank()) {
            out.add(current.toString().trim());
        }
        return new LinkedHashSet<>(out);
    }
}
