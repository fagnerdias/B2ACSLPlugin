package com.example.gui.ui;

import com.example.gui.model.GenerationMode;
import com.example.gui.model.WpConfig;
import java.util.Arrays;
import java.util.List;
import java.util.prefs.Preferences;

/** The last configuration (generation mode, provers, timeout, ...), kept between runs. */
public final class AppPreferences {

    private final Preferences node = Preferences.userRoot().node("com/example/b2acsl-gui");

    public GenerationMode mode() {
        try {
            return GenerationMode.valueOf(node.get("mode", GenerationMode.PLUGIN.name()));
        } catch (IllegalArgumentException e) {
            return GenerationMode.PLUGIN;
        }
    }

    public void setMode(GenerationMode mode) {
        node.put("mode", mode.name());
    }

    public WpConfig wpConfig() {
        List<String> provers = Arrays.stream(node.get("provers", "CVC5").split(","))
                .map(String::trim).filter(s -> !s.isEmpty()).toList();
        String output = node.get("outputType", "status");
        return new WpConfig(
                provers,
                Math.max(1, node.getInt("timeout", 10)),
                WpConfig.OUTPUT_TYPES.contains(output) ? output : "status",
                node.getBoolean("smokeTests", true),
                node.getBoolean("counterExamples", false));
    }

    public void setWpConfig(WpConfig c) {
        node.put("provers", String.join(",", c.provers()));
        node.putInt("timeout", c.timeoutSeconds());
        node.put("outputType", c.outputType());
        node.putBoolean("smokeTests", c.smokeTests());
        node.putBoolean("counterExamples", c.counterExamples());
    }

    public void flush() {
        try {
            node.flush();
        } catch (Exception e) {
            // preferences are a convenience: losing them is not an error
        }
    }
}
