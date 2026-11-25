package com.example.gui;

import com.example.gui.ui.AppPreferences;
import com.example.gui.ui.Theme;
import com.example.gui.ui.wizard.WizardFrame;
import java.nio.file.Path;
import javax.swing.SwingUtilities;

/**
 * Entry point of the Frama-C WP wizard. As the pipeline ({@code com.example.Main}), the project comes
 * from the command line: {@code java -jar b2acsl-gui-…-all.jar <project folder or its bdp/>} (or
 * {@code make gui PROJECT=<folder>}).
 */
public final class WpGuiMain {

    private WpGuiMain() {}

    public static void main(String[] args) {
        Path project = args.length >= 1 ? Path.of(args[0]).toAbsolutePath().normalize() : null;
        Theme.install();
        SwingUtilities.invokeLater(() -> new WizardFrame(new AppPreferences(), project).start());
    }
}
