package com.example.gui.ui;

import com.example.gui.ui.components.FlatButtonUI;
import java.awt.Color;
import java.awt.Font;
import java.awt.GraphicsEnvironment;
import java.util.Arrays;
import javax.swing.BorderFactory;
import javax.swing.UIManager;
import javax.swing.UnsupportedLookAndFeelException;
import javax.swing.border.Border;
import javax.swing.plaf.BorderUIResource;
import javax.swing.plaf.ColorUIResource;
import javax.swing.plaf.FontUIResource;
import javax.swing.plaf.metal.DefaultMetalTheme;
import javax.swing.plaf.metal.MetalLookAndFeel;

/**
 * Standard Swing look and feel (Metal, without the Ocean gradients) customised with the mockups'
 * palette; the system default sans-serif font for the UI and a monospaced font for logs and code.
 */
public final class Theme {

    public static final int BASE_SIZE = 13;
    private static Font base = new Font(Font.SANS_SERIF, Font.PLAIN, BASE_SIZE);
    private static Font mono = new Font(Font.MONOSPACED, Font.PLAIN, 12);

    private Theme() {}

    public static Font font() {
        return base;
    }

    public static Font font(int style, float size) {
        return base.deriveFont(style, size);
    }

    public static Font mono() {
        return mono;
    }

    public static Font mono(float size) {
        return mono.deriveFont(size);
    }

    /** Must run before any Swing component is created. */
    public static void install() {
        System.setProperty("swing.boldMetal", "false");
        System.setProperty("awt.useSystemAAFontSettings", System.getProperty("awt.useSystemAAFontSettings", "lcd"));
        System.setProperty("swing.aatext", "true");
        if (!GraphicsEnvironment.isHeadless()) {
            String[] families = GraphicsEnvironment.getLocalGraphicsEnvironment().getAvailableFontFamilyNames();
            if (Arrays.asList(families).contains("Ubuntu Mono")) {
                mono = new Font("Ubuntu Mono", Font.PLAIN, 13);
            }
        }
        MetalLookAndFeel.setCurrentTheme(new PaletteTheme());
        try {
            UIManager.setLookAndFeel(new MetalLookAndFeel());
        } catch (UnsupportedLookAndFeelException e) {
            return;
        }
        Border input = new BorderUIResource(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(Palette.INPUT_BORDER), BorderFactory.createEmptyBorder(3, 5, 3, 5)));
        Border line = new BorderUIResource(BorderFactory.createLineBorder(Palette.INPUT_BORDER));
        put("ButtonUI", FlatButtonUI.class.getName());
        for (String k : new String[] {"Panel", "OptionPane", "CheckBox", "RadioButton", "Viewport", "Label",
                "TabbedPane", "SplitPane", "ScrollPane", "ToolBar"}) {
            put(k + ".background", new ColorUIResource(Palette.WINDOW));
        }
        for (String k : new String[] {"Label", "CheckBox", "RadioButton", "TextField", "TextArea", "List",
                "Table", "ComboBox", "Button", "TabbedPane", "TitledBorder"}) {
            put(k + ".foreground", new ColorUIResource(Palette.TEXT));
        }
        put("Viewport.background", new ColorUIResource(Palette.WHITE));
        put("TextField.background", new ColorUIResource(Palette.WHITE));
        put("TextField.inactiveBackground", new ColorUIResource(Palette.DIFF_PLACEHOLDER));
        put("TextField.border", input);
        put("FormattedTextField.border", BorderFactory.createEmptyBorder(3, 5, 3, 5));
        put("TextArea.background", new ColorUIResource(Palette.WHITE));
        put("TextArea.inactiveBackground", new ColorUIResource(Palette.WHITE));
        put("ScrollPane.border", line);
        put("Spinner.border", line);
        put("ComboBox.background", new ColorUIResource(Palette.WHITE));
        put("ComboBox.selectionBackground", new ColorUIResource(new Color(0xDCE7F7)));
        put("ComboBox.selectionForeground", new ColorUIResource(Palette.TEXT));
        put("List.selectionBackground", new ColorUIResource(new Color(0xDCE7F7)));
        put("List.selectionForeground", new ColorUIResource(Palette.TEXT));
        put("Table.selectionBackground", new ColorUIResource(new Color(0xDCE7F7)));
        put("Table.selectionForeground", new ColorUIResource(Palette.TEXT));
        put("Table.gridColor", new ColorUIResource(Palette.TABLE_GRID));
        put("TableHeader.background", new ColorUIResource(Palette.TABLE_HEADER));
        put("CheckBox.focus", new ColorUIResource(Palette.WINDOW));
        put("RadioButton.focus", new ColorUIResource(Palette.WINDOW));
        put("ProgressBar.foreground", new ColorUIResource(new Color(0xC3D6F2)));
        put("ProgressBar.background", new ColorUIResource(Palette.WHITE));
        put("ProgressBar.selectionForeground", new ColorUIResource(Palette.TEXT));
        put("ProgressBar.selectionBackground", new ColorUIResource(Palette.TEXT));
        put("ProgressBar.border", line);
        put("TabbedPane.selected", new ColorUIResource(Palette.WHITE));
        put("TabbedPane.contentAreaColor", new ColorUIResource(Palette.WINDOW));
        put("TabbedPane.focus", new ColorUIResource(Palette.WHITE));
        put("TabbedPane.unselectedBackground", new ColorUIResource(new Color(0xE2E2E2)));
        put("TabbedPane.borderHightlightColor", new ColorUIResource(Palette.GROUP_BORDER));
        put("TabbedPane.tabInsets", new javax.swing.plaf.InsetsUIResource(5, 14, 5, 14));
        put("ToolTip.background", new ColorUIResource(new Color(0xFFFFF0)));
        put("Separator.foreground", new ColorUIResource(new Color(0xCFCFCF)));
        put("Separator.background", new ColorUIResource(Palette.WHITE));
    }

    private static void put(String key, Object value) {
        UIManager.put(key, value);
    }

    /** Metal colours and fonts from the palette. */
    private static final class PaletteTheme extends DefaultMetalTheme {

        private final FontUIResource control = new FontUIResource(base);
        private final FontUIResource small = new FontUIResource(base.deriveFont(11f));

        @Override
        public String getName() {
            return "B2ACSL";
        }

        @Override
        protected ColorUIResource getPrimary1() {
            return new ColorUIResource(Palette.INPUT_BORDER);
        }

        @Override
        protected ColorUIResource getPrimary2() {
            return new ColorUIResource(new Color(0xC3D6F2));
        }

        @Override
        protected ColorUIResource getPrimary3() {
            return new ColorUIResource(new Color(0xDCE7F7));
        }

        @Override
        protected ColorUIResource getSecondary1() {
            return new ColorUIResource(Palette.GRAY);
        }

        @Override
        protected ColorUIResource getSecondary2() {
            return new ColorUIResource(new Color(0xC4C4C4));
        }

        @Override
        protected ColorUIResource getSecondary3() {
            return new ColorUIResource(Palette.WINDOW);
        }

        @Override
        public ColorUIResource getSystemTextColor() {
            return new ColorUIResource(Palette.TEXT);
        }

        @Override
        public ColorUIResource getControlTextColor() {
            return new ColorUIResource(Palette.TEXT);
        }

        @Override
        public ColorUIResource getUserTextColor() {
            return new ColorUIResource(Palette.TEXT);
        }

        @Override
        public FontUIResource getControlTextFont() {
            return control;
        }

        @Override
        public FontUIResource getSystemTextFont() {
            return control;
        }

        @Override
        public FontUIResource getUserTextFont() {
            return control;
        }

        @Override
        public FontUIResource getMenuTextFont() {
            return control;
        }

        @Override
        public FontUIResource getWindowTitleFont() {
            return control;
        }

        @Override
        public FontUIResource getSubTextFont() {
            return small;
        }
    }
}
