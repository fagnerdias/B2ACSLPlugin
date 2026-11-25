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
        rows.addAll(clauseRows(plugin.clauses(), atl.clauses()));
        rows.add(new DiffRow(RowKind.DELIMITER, delimiter(plugin, false), delimiter(atl, false)));
        ClauseLine ps = plugin.signature();
        ClauseLine as = atl.signature();
        if (ps != null || as != null) {
            boolean same = ps != null && as != null && ps.text().equals(as.text());
            RowKind kind = same ? RowKind.EQUAL : ps == null ? RowKind.ONLY_ATL : as == null ? RowKind.ONLY_PLUGIN : RowKind.CHANGED;
            rows.add(new DiffRow(kind, ps, as));
        }
        List<Change> changes = new ArrayList<>();
        for (DiffRow row : rows) {
            if (row.kind() != RowKind.EQUAL && row.kind() != RowKind.DELIMITER) {
                changes.add(new Change(row.kind(), ChangeDescriber.describe(row, row == rows.get(rows.size() - 1) && isSignatureRow(row, ps, as))));
            }
        }
        return new SpecDiff(function, plugin, atl, rows, changes);
    }

    private static boolean isSignatureRow(DiffRow row, ClauseLine ps, ClauseLine as) {
        return row.plugin() == ps && row.atl() == as;
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
