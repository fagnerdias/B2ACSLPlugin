package com.example.comparator.spec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import org.junit.jupiter.api.Test;

class ClauseNormalizerTest {

    @Test
    void whitespaceDifferencesDoNotMatter() {
        assertEquals(
                ClauseNormalizer.canonical("ensures  equals(ss,  empty(NAT));"),
                ClauseNormalizer.canonical("ensures equals( ss , empty( NAT ) )"));
    }

    @Test
    void spacesBetweenIdentifiersAreKept() {
        assertNotEquals(
                ClauseNormalizer.canonical("logic integer MM"),
                ClauseNormalizer.canonical("logic integerMM"));
    }

    @Test
    void parenthesesWrappingTheWholeBodyAreIgnored() {
        assertEquals(
                ClauseNormalizer.canonical("requires (card(ss) <= MM);"),
                ClauseNormalizer.canonical("requires card(ss) <= MM;"));
        assertNotEquals(
                ClauseNormalizer.canonical("requires (a) && (b)"),
                ClauseNormalizer.canonical("requires a) && (b"));
    }

    @Test
    void boundVariablesAreAlphaRenamed() {
        assertEquals(
                ClauseNormalizer.canonical("ensures \\forall int i; P(i);"),
                ClauseNormalizer.canonical("ensures \\forall int j; P(j);"));
        assertEquals(
                ClauseNormalizer.canonical("\\forall integer b, e; f(b, e) ==> \\exists \\list<integer> out; g(out, b)"),
                ClauseNormalizer.canonical("\\forall integer x, y; f(x, y) ==> \\exists \\list<integer> l; g(l, x)"));
        assertNotEquals(
                ClauseNormalizer.canonical("\\forall integer i, j; f(i, j)"),
                ClauseNormalizer.canonical("\\forall integer i, j; f(j, i)"));
    }

    @Test
    void renamingDoesNotTouchOldOrFreeVariables() {
        assertEquals(
                "ensures \\forall integer _b0;f(_b0,\\old(ss),k)",
                ClauseNormalizer.canonical("ensures \\forall integer k2; f(k2, \\old(ss), k);").replace("k2", "_b0"));
    }

    @Test
    void displayCollapsesWhitespaceAndDropsSemicolon() {
        assertEquals("assigns *rr", ClauseNormalizer.display("  assigns   *rr ;\n"));
    }
}
