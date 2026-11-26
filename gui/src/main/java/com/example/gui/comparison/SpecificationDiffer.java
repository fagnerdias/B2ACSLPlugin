package com.example.gui.comparison;

import com.example.gui.parsing.ContractExtractor.ClauseLine;
import com.example.gui.parsing.ContractExtractor.Contract;
import com.github.difflib.DiffUtils;
import com.github.difflib.patch.AbstractDelta;
import com.github.difflib.patch.Patch;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Line diff of the contracts of one function (java-diff-utils over the whitespace-normalised
 * clauses), aligned for two side-by-side panes. A changed pair of clauses of different kinds
 * (e.g. a requires against an ensures) is split into two one-sided rows.
 */
public final class SpecificationDiffer {

    public enum RowKind {
        EQUAL,
        ONLY_PLUGIN,
        ONLY_ATL,
        CHANGED,
        /** The same clause on both sides, at different positions (same meaning). */
        MOVED,
        /** "/*@" or "@*&#47;" around the clauses: shown, never counted. */
        DELIMITER
    }

    /** One aligned row; a {@code null} side is an empty placeholder. */
    public record DiffRow(RowKind kind, ClauseLine plugin, ClauseLine atl) {}

    /** The rows, and the differences explained in plain language (one per non-equal row). */
    public record SpecDiff(String function, Contract plugin, Contract atl, List<DiffRow> rows, List<Change> changes) {

        public SpecDiff {
            rows = List.copyOf(rows);
            changes = List.copyOf(changes);
        }

        public int differenceCount() {
            return changes.size();
        }
    }

    public record Change(RowKind kind, String sentence) {}

    private SpecificationDiffer() {}

    public static SpecDiff diff(String function, Contract plugin, Contract atl) {
        List<DiffRow> rows = new ArrayList<>();
        rows.add(new DiffRow(
                RowKind.DELIMITER, delimiter(plugin, true), delimiter(atl, true)));
        rows.addAll(reclassifyMoved(clauseRows(plugin.clauses(), atl.clauses())));
        rows.add(new DiffRow(RowKind.DELIMITER, delimiter(plugin, false), delimiter(atl, false)));
        ClauseLine ps = plugin.signature();
        ClauseLine as = atl.signature();
        if (ps != null || as != null) {
            boolean same = ps != null && as != null && ps.text().equals(as.text());
            RowKind kind = same ? RowKind.EQUAL : ps == null ? RowKind.ONLY_ATL : as == null ? RowKind.ONLY_PLUGIN : RowKind.CHANGED;
            rows.add(new DiffRow(kind, ps, as));
        }
        List<Change> changes = new ArrayList<>();
        java.util.Set<String> movedDescribed = new java.util.HashSet<>();
        for (DiffRow row : rows) {
            if (row.kind() == RowKind.MOVED) {
                for (ClauseLine side : new ClauseLine[] {row.plugin(), row.atl()}) {
                    if (side != null && movedDescribed.add(side.text())) {
                        changes.add(new Change(RowKind.MOVED, ChangeDescriber.moved(side.text())));
                    }
                }
            } else if (row.kind() != RowKind.EQUAL && row.kind() != RowKind.DELIMITER) {
                changes.add(new Change(row.kind(), ChangeDescriber.describe(row, row == rows.get(rows.size() - 1) && isSignatureRow(row, ps, as))));
            }
        }
        return new SpecDiff(function, plugin, atl, rows, changes);
    }

    private static boolean isSignatureRow(DiffRow row, ClauseLine ps, ClauseLine as) {
        return row.plugin() == ps && row.atl() == as;
    }

    /**
     * A clause present on both sides at different positions comes out of the line diff as an
     * only-Plugin row plus an only-ATL row (or inside a changed pair). Clause order does not change
     * the meaning of an ACSL contract, so such rows become MOVED; a changed pair keeps only the side
     * that really differs.
     */
    static List<DiffRow> reclassifyMoved(List<DiffRow> rows) {
        java.util.Map<String, Integer> left = new java.util.HashMap<>();
        java.util.Map<String, Integer> right = new java.util.HashMap<>();
        for (DiffRow r : rows) {
            if (r.kind() == RowKind.ONLY_PLUGIN || r.kind() == RowKind.CHANGED) {
                left.merge(r.plugin().text(), 1, Integer::sum);
            }
            if (r.kind() == RowKind.ONLY_ATL || r.kind() == RowKind.CHANGED) {
                right.merge(r.atl().text(), 1, Integer::sum);
            }
        }
        java.util.Map<String, Integer> movedLeft = new java.util.HashMap<>();
        for (var e : left.entrySet()) {
            int n = Math.min(e.getValue(), right.getOrDefault(e.getKey(), 0));
            if (n > 0) {
                movedLeft.put(e.getKey(), n);
            }
        }
        java.util.Map<String, Integer> movedRight = new java.util.HashMap<>(movedLeft);
        List<DiffRow> out = new ArrayList<>();
        for (DiffRow r : rows) {
            boolean lm = (r.kind() == RowKind.ONLY_PLUGIN || r.kind() == RowKind.CHANGED) && take(movedLeft, r.plugin().text());
            boolean rm = (r.kind() == RowKind.ONLY_ATL || r.kind() == RowKind.CHANGED) && take(movedRight, r.atl().text());
            RowKind kind = r.kind();
            switch (r.kind()) {
                case ONLY_PLUGIN -> kind = lm ? RowKind.MOVED : kind;
                case ONLY_ATL -> kind = rm ? RowKind.MOVED : kind;
                case CHANGED -> kind = lm && rm ? RowKind.MOVED : lm ? RowKind.ONLY_ATL : rm ? RowKind.ONLY_PLUGIN : kind;
                default -> {
                }
            }
            if (kind == RowKind.ONLY_ATL && r.kind() == RowKind.CHANGED) {
                // the Plugin clause of this pair exists elsewhere on the ATL side: show it as moved there
                out.add(new DiffRow(RowKind.MOVED, r.plugin(), null));
                out.add(new DiffRow(RowKind.ONLY_ATL, null, r.atl()));
            } else if (kind == RowKind.ONLY_PLUGIN && r.kind() == RowKind.CHANGED) {
                out.add(new DiffRow(RowKind.ONLY_PLUGIN, r.plugin(), null));
                out.add(new DiffRow(RowKind.MOVED, null, r.atl()));
            } else {
                out.add(new DiffRow(kind, r.plugin(), r.atl()));
            }
        }
        return out;
    }

    private static boolean take(java.util.Map<String, Integer> pool, String text) {
        Integer n = pool.get(text);
        if (n == null || n == 0) {
            return false;
        }
        pool.put(text, n - 1);
        return true;
    }

    static List<DiffRow> clauseRows(List<ClauseLine> left, List<ClauseLine> right) {
        List<String> l = left.stream().map(ClauseLine::text).toList();
        List<String> r = right.stream().map(ClauseLine::text).toList();
        Patch<String> patch = DiffUtils.diff(l, r);
        List<DiffRow> rows = new ArrayList<>();
        int li = 0;
        int ri = 0;
        for (AbstractDelta<String> delta : patch.getDeltas()) {
            int ls = delta.getSource().getPosition();
            int rs = delta.getTarget().getPosition();
            while (li < ls && ri < rs) {
                rows.add(new DiffRow(RowKind.EQUAL, left.get(li++), right.get(ri++)));
            }
            int le = ls + delta.getSource().size();
            int re = rs + delta.getTarget().size();
            pairChanged(rows, left.subList(ls, le), right.subList(rs, re));
            li = le;
            ri = re;
        }
        while (li < left.size() && ri < right.size()) {
            rows.add(new DiffRow(RowKind.EQUAL, left.get(li++), right.get(ri++)));
        }
        while (li < left.size()) {
            rows.add(new DiffRow(RowKind.ONLY_PLUGIN, left.get(li++), null));
        }
        while (ri < right.size()) {
            rows.add(new DiffRow(RowKind.ONLY_ATL, null, right.get(ri++)));
        }
        return rows;
    }

    /**
     * Pairs the clauses of a changed block: same clause kind → one CHANGED row; otherwise the
     * Plugin clause and the ATL clause get one-sided rows.
     */
    private static void pairChanged(List<DiffRow> rows, List<ClauseLine> left, List<ClauseLine> right) {
        int i = 0;
        int j = 0;
        while (i < left.size() && j < right.size()) {
            ClauseLine a = left.get(i);
            ClauseLine b = right.get(j);
            if (kind(a.text()).equals(kind(b.text()))) {
                rows.add(new DiffRow(RowKind.CHANGED, a, b));
                i++;
                j++;
            } else if (laterRightHasKind(right, j, kind(a.text()))) {
                rows.add(new DiffRow(RowKind.ONLY_ATL, null, b));
                j++;
            } else {
                rows.add(new DiffRow(RowKind.ONLY_PLUGIN, a, null));
                i++;
            }
        }
        while (i < left.size()) {
            rows.add(new DiffRow(RowKind.ONLY_PLUGIN, left.get(i++), null));
        }
        while (j < right.size()) {
            rows.add(new DiffRow(RowKind.ONLY_ATL, null, right.get(j++)));
        }
    }

    private static boolean laterRightHasKind(List<ClauseLine> right, int from, String kind) {
        for (int k = from + 1; k < right.size(); k++) {
            if (kind(right.get(k).text()).equals(kind)) {
                return true;
            }
        }
        return false;
    }

    /** Clause keyword: "requires", "ensures", "assigns", "behavior", "complete behaviors", ... */
    public static String kind(String clause) {
        String t = clause.trim();
        String[] words = t.split("[\\s(]+", 3);
        if (words.length == 0 || words[0].isEmpty()) {
            return "";
        }
        String first = words[0].toLowerCase(Locale.ROOT);
        if ((first.equals("check") || first.equals("admit")) && words.length > 1) {
            return words[1].toLowerCase(Locale.ROOT);
        }
        if ((first.equals("complete") || first.equals("disjoint")) && words.length > 1) {
            return first + " behaviors";
        }
        if (first.endsWith(":")) {
            return first.substring(0, first.length() - 1);
        }
        return first;
    }

    private static ClauseLine delimiter(Contract contract, boolean open) {
        if (!contract.hasContract()) {
            return null;
        }
        return new ClauseLine(open ? contract.openLine() : contract.closeLine(), open ? "/*@" : "@*/");
    }
}
