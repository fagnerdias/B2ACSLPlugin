package com.example.gui.model;

import java.util.List;

/** What step 2 generates: one specification, or both to compare them. */
public enum GenerationMode {
    PLUGIN(
            "Plugin",
            "Generate the ACSL annotations using the specification plugin.",
            "Plugin",
            List.of(Generator.PLUGIN)),
    ATL(
            "ATL Rules",
            "Generate the ACSL annotations using the ATL transformation rules.",
            "ATL Rules",
            List.of(Generator.ATL)),
    COMPARE(
            "Plugin + ATL Rules (compare)",
            "Generate both specifications, verify each one and compare the results side by side.",
            "Plugin + ATL Rules",
            List.of(Generator.PLUGIN, Generator.ATL));

    private final String label;
    private final String description;
    private final String shortLabel;
    private final List<Generator> generators;

    GenerationMode(String label, String description, String shortLabel, List<Generator> generators) {
        this.label = label;
        this.description = description;
        this.shortLabel = shortLabel;
        this.generators = generators;
    }

    /** Radio button text, also used as "Specification: ..." in step 3. */
    public String label() {
        return label;
    }

    public String description() {
        return description;
    }

    /** Suffix of the progress title ("Verifying “X” · Plugin + ATL Rules"). */
    public String shortLabel() {
        return shortLabel;
    }

    public List<Generator> generators() {
        return generators;
    }

    public boolean isCompare() {
        return this == COMPARE;
    }

    public static GenerationMode single(Generator generator) {
        return generator == Generator.PLUGIN ? PLUGIN : ATL;
    }
}
