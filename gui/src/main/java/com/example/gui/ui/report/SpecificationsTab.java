package com.example.gui.ui.report;

import com.example.gui.comparison.ResultComparator;
import com.example.gui.comparison.SpecificationDiffer.Change;
import com.example.gui.comparison.SpecificationDiffer.DiffRow;
import com.example.gui.comparison.SpecificationDiffer.RowKind;
import com.example.gui.comparison.SpecificationDiffer.SpecDiff;
import com.example.gui.model.FunctionComparison;
import com.example.gui.model.FunctionResult;
import com.example.gui.model.VerificationRun;
import com.example.gui.parsing.ContractExtractor.ClauseLine;
import com.example.gui.ui.Palette;
import com.example.gui.ui.Theme;
import com.example.gui.ui.Ui;
import com.example.gui.ui.components.Callout;
import com.example.gui.ui.components.DiffPane;
import com.example.gui.ui.components.Icons;
import com.example.gui.ui.components.LinkLabel;
import com.example.gui.ui.components.StatusBadge;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridLayout;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import javax.swing.BorderFactory;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;

/** Specifications tab: side-by-side contracts of one function, what changed and the impact. */
final class SpecificationsTab extends JPanel {

    private final VerificationRun plugin;
    private final VerificationRun atl;
    private final List<FunctionComparison> rows;
    private final Map<String, SpecDiff> diffs;
    private final Consumer<String> showResults;
    private final JComboBox<String> function;
    private final StatusBadge count = new StatusBadge("", StatusBadge.Kind.WARNING);
    private final JCheckBox onlyDifferences = new JCheckBox("Show only differences");
    private final DiffPane left = new DiffPane();
    private final DiffPane right = new DiffPane();
    private final JPanel changes = Ui.stack(6);
    private final Callout impact = new Callout(Callout.Kind.WARNING, null, null, 700);

    SpecificationsTab(
            VerificationRun plugin,
            VerificationRun atl,
            List<FunctionComparison> rows,
            Map<String, SpecDiff> diffs,
            Consumer<String> showResults) {
        super(new BorderLayout(0, 10));
        this.plugin = plugin;
        this.atl = atl;
        this.rows = rows;
        this.diffs = diffs;
        this.showResults = showResults;
        setBorder(Ui.padding(12, 12, 12, 12));

        function = new JComboBox<>(rows.stream().map(FunctionComparison::function).toArray(String[]::new));
        function.addActionListener(e -> refresh());
        onlyDifferences.setOpaque(false);
        onlyDifferences.addActionListener(e -> refresh());
        JPanel top = new JPanel(new BorderLayout());
        top.setOpaque(false);
        top.add(Ui.row(10, Ui.bold("Function"), function, count), BorderLayout.WEST);
        top.add(onlyDifferences, BorderLayout.EAST);

        JPanel legend = Ui.row(18,
                legendItem("Only in Plugin", Palette.DIFF_ONLY_PLUGIN),
                legendItem("Changed", Palette.DIFF_CHANGED),
                legendItem("Only in ATL Rules", Palette.DIFF_ONLY_ATL));

        JScrollPane leftScroll = paneScroll(left);
        JScrollPane rightScroll = paneScroll(right);
        DiffPane.synchronize(leftScroll, rightScroll);
        JPanel panes = new JPanel(new GridLayout(1, 2, 12, 0));
        panes.setOpaque(false);
        panes.add(titled("Plugin", plugin.spec().displayPath(), leftScroll));
        panes.add(titled("ATL Rules", atl.spec().displayPath(), rightScroll));

        JPanel changesBox = new JPanel(new BorderLayout(0, 6));
        changesBox.setBackground(new Color(0xF7F7F7));
        changesBox.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(Palette.TABLE_GRID), Ui.padding(8, 10, 10, 10)));
        changesBox.add(Ui.label("What changed"), BorderLayout.NORTH);
        changesBox.add(changes, BorderLayout.CENTER);

        add(Ui.stack(10, top, legend), BorderLayout.NORTH);
        add(panes, BorderLayout.CENTER);
        add(Ui.stack(10, changesBox, impact), BorderLayout.SOUTH);
        refresh();
    }

    void select(String fn) {
        function.setSelectedItem(fn);
    }

    private void refresh() {
        String fn = (String) function.getSelectedItem();
        if (fn == null) {
            return;
        }
        SpecDiff diff = diffs.get(fn);
        List<DiffPane.Line> l = new ArrayList<>();
        List<DiffPane.Line> r = new ArrayList<>();
        if (diff != null) {
            // Shown as in the source: "/*@ first clause;", "  @ next clause;", "  @*/", then the signature.
            boolean[] firstLeft = {true};
            boolean[] firstRight = {true};
            boolean afterClose = false;
            boolean opening = true;
            for (DiffRow row : diff.rows()) {
                if (row.kind() == RowKind.DELIMITER) {
                    if (!opening) {
                        afterClose = true;
                        if (!onlyDifferences.isSelected()) {
                            l.add(delimiter(row.plugin()));
                            r.add(delimiter(row.atl()));
                        }
                    }
                    opening = false;
                    continue;
                }
                Color bg = switch (row.kind()) {
                    case ONLY_PLUGIN -> Palette.DIFF_ONLY_PLUGIN;
                    case ONLY_ATL -> Palette.DIFF_ONLY_ATL;
                    case CHANGED -> Palette.DIFF_CHANGED;
                    default -> null;
                };
                DiffPane.Line pl = line(row.plugin(), afterClose, firstLeft, bg);
                DiffPane.Line al = line(row.atl(), afterClose, firstRight, bg);
                if (onlyDifferences.isSelected() && row.kind() == RowKind.EQUAL) {
                    continue;
                }
                l.add(pl);
                r.add(al);
            }
        }
        left.setLines(l);
        right.setLines(r);

        int n = diff == null ? 0 : diff.differenceCount();
        count.set(n == 0 ? "No differences" : n + (n == 1 ? " difference" : " differences"),
                n == 0 ? StatusBadge.Kind.SUCCESS : StatusBadge.Kind.WARNING);

        changes.removeAll();
        if (diff == null || diff.changes().isEmpty()) {
            changes.add(Ui.secondary("The contracts of this function are identical in both specifications."));
        } else {
            for (Change c : diff.changes()) {
                Color color = switch (c.kind()) {
                    case ONLY_PLUGIN -> Palette.DIFF_ONLY_PLUGIN;
                    case ONLY_ATL -> Palette.DIFF_ONLY_ATL;
                    default -> Palette.DIFF_CHANGED;
                };
                JLabel s = Ui.wrap(c.sentence(), 720, Palette.TEXT, Theme.font(Font.PLAIN, 12.5f));
                s.setIcon(Icons.square(color, color.darker()));
                s.setIconTextGap(8);
                s.setVerticalTextPosition(JLabel.TOP);
                changes.add(s);
            }
        }

        FunctionComparison cmp = rows.stream().filter(x -> x.function().equals(fn)).findFirst().orElse(null);
        String text = "Plugin: " + goals(cmp == null ? null : cmp.plugin()) + " ATL Rules: "
                + goals(cmp == null ? null : cmp.atl());
        impact.setKind(cmp != null && cmp.same() ? Callout.Kind.INFO : Callout.Kind.WARNING);
        impact.set("Impact on the verification of " + fn, text,
                List.of(new LinkLabel("See the results of this function ›", () -> showResults.accept(fn))));
        revalidate();
        repaint();
    }

    /** "15 / 15 goals proved." or "16 / 18 goals proved, with 1 failure and 1 timeout." */
    private static String goals(FunctionResult r) {
        if (r == null) {
            return "not verified.";
        }
        List<String> parts = new ArrayList<>();
        if (r.failures() > 0) {
            parts.add(r.failures() + " " + ResultComparator.plural(r.failures(), "failure"));
        }
        if (r.timeouts() > 0) {
            parts.add(r.timeouts() + " " + ResultComparator.plural(r.timeouts(), "timeout"));
        }
        String base = r.proved() + " / " + r.totalGoals() + " goals proved";
        if (parts.isEmpty()) {
            return base + ".";
        }
        return base + ", with " + String.join(" and ", parts) + ".";
    }

    /** A clause row: "/*@ " before the first clause of a side, "  @ " before the others; the signature as is. */
    private static DiffPane.Line line(ClauseLine clause, boolean signature, boolean[] first, Color bg) {
        if (clause == null) {
            return new DiffPane.Line("", null, null);
        }
        String text = clause.text();
        if (!signature) {
            text = (first[0] ? "/*@ " : "  @ ") + text;
            first[0] = false;
        }
        return new DiffPane.Line(Integer.toString(clause.fileLine()), text, bg);
    }

    private static DiffPane.Line delimiter(ClauseLine close) {
        return close == null ? new DiffPane.Line("", null, null) : new DiffPane.Line(Integer.toString(close.fileLine()), "  @*/", null);
    }

    private static JScrollPane paneScroll(DiffPane pane) {
        JScrollPane s = new JScrollPane(pane);
        s.setBorder(null);
        s.getVerticalScrollBar().setUnitIncrement(16);
        s.setPreferredSize(new Dimension(380, 220));
        return s;
    }

    private static JPanel titled(String name, String file, JScrollPane content) {
        JPanel header = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 4));
        header.setBackground(Palette.PANE_HEADER);
        header.add(Ui.bold(name));
        header.add(Ui.file(file));
        JPanel p = new JPanel(new BorderLayout());
        p.setBorder(BorderFactory.createLineBorder(Palette.INPUT_BORDER));
        p.add(header, BorderLayout.NORTH);
        p.add(content, BorderLayout.CENTER);
        return p;
    }

    private static JLabel legendItem(String text, Color color) {
        JLabel l = Ui.label(text);
        l.setFont(Theme.font(Font.PLAIN, 12f));
        l.setIcon(Icons.square(color, color.darker()));
        l.setIconTextGap(6);
        return l;
    }
}
