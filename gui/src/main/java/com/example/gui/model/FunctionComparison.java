package com.example.gui.model;

/**
 * One row of the per-function comparison.
 *
 * @param plugin result with the Plugin specification, {@code null} if it was not verified
 * @param atl result with the ATL Rules specification, {@code null} if it was not verified
 * @param difference plain-language difference ("ATL Rules: 3 more goals, 1 failure and 1 timeout")
 *     or "—" when the results are the same
 */
public record FunctionComparison(
        String function, FunctionResult plugin, FunctionResult atl, boolean same, String difference) {

    public FunctionResult result(Generator generator) {
        return generator == Generator.PLUGIN ? plugin : atl;
    }
}
