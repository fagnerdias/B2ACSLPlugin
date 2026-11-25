package com.example.gui.generation;

import com.example.gui.model.Generator;
import java.io.IOException;
import java.util.List;

/** The ATL transformation rules (bundled B2ACSLRules jar), run in a child JVM. */
public final class AtlSpecificationGenerator extends ChildJvmSpecificationGenerator {

    /** Limit for the rules translation itself (the comparator's default). */
    static final int RULES_TIMEOUT_SECONDS = 300;

    @Override
    public Generator generator() {
        return Generator.ATL;
    }

    @Override
    protected List<String> extraArgs() throws IOException {
        return List.of(
                "--rules-jar", BundledTools.rulesJar().toString(),
                "--lib-dir", BundledTools.acslLibDir().toString(),
                "--rules-timeout", Integer.toString(RULES_TIMEOUT_SECONDS));
    }
}
