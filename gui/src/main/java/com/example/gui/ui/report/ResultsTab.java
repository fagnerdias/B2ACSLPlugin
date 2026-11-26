package com.example.gui.ui.report;

import com.example.gui.comparison.ResultComparator;
import com.example.gui.model.FunctionComparison;
import com.example.gui.model.FunctionResult;
import com.example.gui.model.Generator;
import com.example.gui.model.Verdict;
import com.example.gui.model.VerificationRun;
import com.example.gui.ui.Palette;
import com.example.gui.ui.Theme;
import com.example.gui.ui.Ui;
import com.example.gui.ui.components.Callout;
import com.example.gui.ui.components.LinkLabel;
import com.example.gui.ui.components.StatusBadge;
import com.example.gui.ui.components.TitledGroup;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridLayout;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import javax.swing.BorderFactory;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.ListSelectionModel;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.TableCellRenderer;

/** Results tab: verdict, summary of each specification, per-function table, output details. */
final class ResultsTab extends JPanel {

    private static final String[] COLUMNS = {
        "Function", "Plugin proved / total", "ATL Rules proved / total", "Result", "Action"
    };
    private static final int RESULT = 3;
    private static final int ACTION = 4;
    private final List<FunctionComparison> rows;
    private final JTable table;

    ResultsTab(VerificationRun plugin, VerificationRun atl, List<FunctionComparison> rows, Consumer<String> compare) {
        super(new BorderLayout());
        this.rows = rows;
        setBorder(Ui.padding(12, 12, 12, 12));

        Verdict verdict = ResultComparator.verdict(rows);
        Callout callout = new Callout(verdict.allSame() ? Callout.Kind.INFO : Callout.Kind.WARNING, null, null, 700);
        List<Object> extra = new ArrayList<>();
        if (verdict.firstDifferent() != null) {
            extra.add(new LinkLabel("Compare the specifications of " + verdict.firstDifferent() + " ›",
                    () -> compare.accept(verdict.firstDifferent())));
        }
        callout.set(verdict.title(), verdict.sentence(), extra);
        if (verdict.allSame()) {
            callout.setSuccess();
        }

        JPanel summary = new JPanel(new GridLayout(1, 2, 12, 0));
        summary.setOpaque(false);
        summary.add(column(plugin));
        summary.add(column(atl));

        table = new JTable(new Model());
        table.setRowHeight(30);
        table.setShowVerticalLines(false);
        table.setGridColor(Palette.TABLE_GRID);
        table.setIntercellSpacing(new Dimension(0, 1));
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.setFillsViewportHeight(true);
        table.getTableHeader().setReorderingAllowed(false);
        table.getTableHeader().setFont(Theme.font(Font.BOLD, 12f));
        table.getTableHeader().setBackground(Palette.TABLE_HEADER);
        table.getTableHeader().setPreferredSize(new Dimension(10, 30));
        java.awt.FontMetrics bold = table.getFontMetrics(Theme.font(Font.BOLD, 12.5f));
        int fnWidth = rows.stream().mapToInt(r -> bold.stringWidth(r.function())).max().orElse(120) + 28;
        int linkWidth = bold.stringWidth("Compare ›") + 28;
        int[] widths = {Math.max(fnWidth, 160), 170, 190, 130, Math.max(linkWidth, 90)};
        int[] minimum = {Math.min(fnWidth, 260), 145, 165, 110, Math.max(linkWidth, 80)};
        for (int i = 0; i < widths.length; i++) {
            table.getColumnModel().getColumn(i).setPreferredWidth(widths[i]);
            table.getColumnModel().getColumn(i).setMinWidth(minimum[i]);
        }
        Renderer renderer = new Renderer();
        for (int i = 0; i < COLUMNS.length; i++) {
            table.getColumnModel().getColumn(i).setCellRenderer(renderer);
        }
        table.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                int row = table.rowAtPoint(e.getPoint());
                int col = table.columnAtPoint(e.getPoint());
                if (row >= 0 && (col == ACTION || e.getClickCount() == 2)) {
                    compare.accept(rows.get(row).function());
                }
            }
        });
        table.addMouseMotionListener(new MouseAdapter() {
            @Override
            public void mouseMoved(MouseEvent e) {
                table.setCursor(Cursor.getPredefinedCursor(
                        table.columnAtPoint(e.getPoint()) == ACTION ? Cursor.HAND_CURSOR : Cursor.DEFAULT_CURSOR));
            }
        });
        JScrollPane tableScroll = new JScrollPane(table);
        tableScroll.setBorder(BorderFactory.createLineBorder(Palette.TABLE_GRID));
        table.setAutoResizeMode(JTable.AUTO_RESIZE_SUBSEQUENT_COLUMNS);
        tableScroll.setPreferredSize(new Dimension(
                java.util.Arrays.stream(widths).sum(), 31 + 31 * Math.min(Math.max(rows.size(), 2), 8)));

        List<String> details = new ArrayList<>();
        details.addAll(ReportSupport.issueLines(atl, Generator.ATL.reportPrefix()));
        details.addAll(ReportSupport.issueLines(plugin, Generator.PLUGIN.reportPrefix()));
        if (ReportSupport.issueLines(atl, null).isEmpty()) {
            details.add(Generator.ATL.reportPrefix() + "  " + ReportSupport.NO_OCCURRENCES);
        }
        if (ReportSupport.issueLines(plugin, null).isEmpty()) {
            details.add(Generator.PLUGIN.reportPrefix() + "     " + ReportSupport.NO_OCCURRENCES);
        }
        JScrollPane detailScroll = Ui.scroll(ReportSupport.details(details, 5));

        JPanel body = Ui.stack(12,
                callout,
                new TitledGroup("Verification Summary", summary),
                new TitledGroup("Per-function comparison", tableScroll),
                new TitledGroup("Output Details", detailScroll));
        body.setBorder(Ui.padding(0, 0, 0, 6));
        add(Ui.page(body), BorderLayout.CENTER);
    }

    void select(String function) {
        for (int i = 0; i < rows.size(); i++) {
            if (rows.get(i).function().equals(function)) {
                table.setRowSelectionInterval(i, i);
                table.scrollRectToVisible(table.getCellRect(i, 0, true));
            }
        }
    }

    private static JPanel column(VerificationRun run) {
        boolean ok = run.allProved();
        StatusBadge badge = new StatusBadge(ResultComparator.badge(run), ok ? StatusBadge.Kind.SUCCESS : StatusBadge.Kind.ERROR);
        return new TitledGroup(run.generator().label(), ReportSupport.cards(run)).boldTitle().withHeader(badge);
    }

    private final class Model extends AbstractTableModel {
        @Override
        public int getRowCount() {
            return rows.size();
        }

        @Override
        public int getColumnCount() {
            return COLUMNS.length;
        }

        @Override
        public String getColumnName(int column) {
            return COLUMNS[column];
        }

        @Override
        public Object getValueAt(int row, int column) {
            FunctionComparison c = rows.get(row);
            return switch (column) {
                case 0 -> c.function();
                case 1 -> provedTotal(c.plugin());
                case 2 -> provedTotal(c.atl());
                case RESULT -> c.same() ? "Same result" : "Different";
                default -> "Compare ›";
            };
        }

        private String provedTotal(FunctionResult r) {
            return r == null ? "—" : r.proved() + " / " + r.totalGoals();
        }
    }

    private final class Renderer implements TableCellRenderer {
        private final DefaultTableCellRenderer text = new DefaultTableCellRenderer();
        private final JPanel badgeCell = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 5));
        private final StatusBadge badge = new StatusBadge("", StatusBadge.Kind.SUCCESS);

        Renderer() {
            badgeCell.add(badge);
        }

        @Override
        public Component getTableCellRendererComponent(
                JTable t, Object value, boolean selected, boolean focus, int row, int column) {
            FunctionComparison c = rows.get(row);
            Color bg = selected ? new Color(0xDCE7F7) : c.same() ? Palette.WHITE : Palette.ROW_DIFFERENT;
            if (column == RESULT) {
                badge.set(String.valueOf(value), c.same() ? StatusBadge.Kind.SUCCESS : StatusBadge.Kind.WARNING);
                badgeCell.setBackground(bg);
                badgeCell.setToolTipText(c.same() ? null : c.difference());
                return badgeCell;
            }
            JLabel l = (JLabel) text.getTableCellRendererComponent(t, value, false, false, row, column);
            l.setBackground(bg);
            l.setBorder(Ui.padding(0, 8, 0, 8));
            l.setForeground(Palette.TEXT);
            l.setFont(Theme.font(Font.PLAIN, 12.5f));
            l.setToolTipText(null);
            switch (column) {
                case 0 -> l.setFont(Theme.font(Font.BOLD, 12.5f));
                case 1, 2 -> {
                    FunctionResult r = column == 1 ? c.plugin() : c.atl();
                    if (!c.same() && r != null && (r.failures() > 0 || r.timeouts() > 0 || r.processFailed())) {
                        l.setForeground(Palette.RED);
                        l.setFont(Theme.font(Font.BOLD, 12.5f));
                    }
                }
                case ACTION -> {
                    l.setForeground(Palette.BLUE);
                    l.setFont(Theme.font(Font.BOLD, 12.5f));
                }
                default -> {
                }
            }
            return l;
        }
    }
}
