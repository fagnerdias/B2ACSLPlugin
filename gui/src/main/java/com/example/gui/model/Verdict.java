package com.example.gui.model;

/**
 * The plain-language verdict at the top of the comparison report.
 *
 * @param firstDifferent the first function whose results differ, {@code null} when all match
 */
public record Verdict(boolean allSame, int differing, int total, String title, String sentence, String firstDifferent) {}
