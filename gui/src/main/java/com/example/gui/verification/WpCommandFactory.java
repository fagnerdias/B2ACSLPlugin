package com.example.gui.verification;

import com.example.FramaCRunner;
import com.example.ui.WpOptionsDialog.WpOptions;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/**
 * The per-function WP command of the existing pipeline ({@link FramaCRunner#buildWpCommand}), so the
 * GUI's goal counts match the CLI and the comparator: {@code frama-c -wp merged_code.c -wp-fct F
 * -wp-prover P [-wp-smoke-tests] [-wp-counter-examples] -wp-rte -wp-timeout S -wp-status|-wp-print},
 * run in the folder of the merged file.
 */
public final class WpCommandFactory {

    private WpCommandFactory() {}

    public static List<String> command(Path mergedFile, WpOptions options, String function) throws IOException {
        return FramaCRunner.buildWpCommand(mergedFile, options, function, mergedFile.getFileName().toString());
    }
}
