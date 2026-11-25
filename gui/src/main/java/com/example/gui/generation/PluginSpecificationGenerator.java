package com.example.gui.generation;

import com.example.gui.model.Generator;
import java.util.List;

/** The B2ACSL Plugin (this repository's translator), run in a child JVM. */
public final class PluginSpecificationGenerator extends ChildJvmSpecificationGenerator {

    @Override
    public Generator generator() {
        return Generator.PLUGIN;
    }

    @Override
    protected List<String> extraArgs() {
        return List.of();
    }
}
