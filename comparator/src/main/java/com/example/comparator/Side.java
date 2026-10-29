package com.example.comparator;

/** The two translators being compared. */
public enum Side {
    PLUGIN,
    RULES;

    /** Directory/JSON name: {@code plugin} or {@code rules}. */
    public String id() {
        return name().toLowerCase();
    }

    public static Side fromId(String id) {
        return valueOf(id.trim().toUpperCase());
    }
}
