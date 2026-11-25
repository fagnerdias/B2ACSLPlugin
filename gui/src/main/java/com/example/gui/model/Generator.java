package com.example.gui.model;

/** One of the two specification generators. */
public enum Generator {
    PLUGIN("Plugin", "plugin", "[Plugin]"),
    ATL("ATL Rules", "atl", "[ATL]   ");

    private final String label;
    private final String id;
    private final String logPrefix;

    Generator(String label, String id, String logPrefix) {
        this.label = label;
        this.id = id;
        this.logPrefix = logPrefix;
    }

    /** Name shown to the user ("Plugin", "ATL Rules"). */
    public String label() {
        return label;
    }

    /** Folder name inside a run directory and command-line id ("plugin", "atl"). */
    public String id() {
        return id;
    }

    /** Prefix of this generator's lines in the generation log (aligned to the same width). */
    public String logPrefix() {
        return logPrefix;
    }

    /** Prefix of this generator's excerpts in the comparison report ("[Plugin]", "[ATL Rules]"). */
    public String reportPrefix() {
        return "[" + label + "]";
    }

    public Generator other() {
        return this == PLUGIN ? ATL : PLUGIN;
    }

    public static Generator fromId(String id) {
        for (Generator g : values()) {
            if (g.id.equals(id)) {
                return g;
            }
        }
        throw new IllegalArgumentException("unknown generator: " + id);
    }
}
