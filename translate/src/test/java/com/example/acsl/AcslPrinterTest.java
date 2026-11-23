package com.example.acsl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * {@link AcslPrinter} e {@link AcslFileFormatter} contra as formas que o printer do rules
 * ({@code ACSL2Text.atl}) escreve, a partir das formas com parênteses defensivos do plugin.
 */
class AcslPrinterTest {

    private static String print(String s) {
        return AcslPrinter.print(AcslParser.parse(s));
    }

    @Test
    void conjunctionsAndComparisons() {
        assertEquals("belongs(bb, S) && (bb != dummy) && belongs(cc, C)",
                print("belongs(bb, S) && ((bb != dummy)) && belongs(cc, C)"));
        assertEquals("(rv__fld1 == 0) && (rv__fld2 == \\true)", print("rv__fld1 == 0 && rv__fld2 == \\true"));
        assertEquals("(a == b) || (c == d)", print("(a == b || c == d)"));
        assertEquals("a && b ==> c", print("((a && b) ==> c)"));
        assertEquals("a ==> (b ==> c)", print("a ==> b ==> c"));
        assertEquals("(a || b) && c", print("(a || b) && c"));
    }

    @Test
    void arithmeticAndChains() {
        assertEquals("(9 - 0) + 1", print("(9 - (0) + 1)"));
        assertEquals("0 <= i <= (9 + 1)", print("0 <= i <= 9 + 1"));
        assertEquals("b <==> lo <= x <= hi", print("b <==> (lo <= x <= hi)"));
        assertEquals("(lo <= k) && (k <= hi)", print("lo <= k && k <= hi"));
    }

    @Test
    void unaryCastsAndCalls() {
        assertEquals("!((\\old(saldo) + juros) <= SALDO_MAX)", print("!(((\\old(saldo) + juros) <= SALDO_MAX))"));
        assertEquals("!equals(a, b)", print("!(equals(a, b))"));
        assertEquals("array_to_function_int((int*)p, (9 - 0) + 1)", print("array_to_function_int((int*)(p), (9 - (0) + 1))"));
        assertEquals("(integer)(*p != 0) == E", print("(integer)(*p != 0) == E"));
        assertEquals("f(lo + 1, hi)", print("f((lo + 1), hi)"));
        assertEquals("\\valid(cc + (0 .. 9))", print("\\valid(cc + (0 .. 9))"));
        assertEquals("a[0 .. n - 1]", print("a[0 .. (n - 1)]"));
        assertEquals("[| (integer)ee |]", print("[| (integer)ee |]"));
    }

    @Test
    void quantifiersTernaryLet() {
        assertEquals("\\forall integer cc; belongs(cc, S) ==> (f(cc) == g(cc))",
                print("(\\forall integer cc; (belongs(cc, S) ==> (f(cc) == g(cc))))"));
        assertEquals("belongs(x, S) && (\\forall integer x; P(x))", print("belongs(x, S) && \\forall integer x; P(x)"));
        assertEquals("\\forall integer lo, hi; (lo > hi) ==> (s(lo, hi) == 0)",
                print("\\forall integer lo, hi; lo > hi ==> s(lo, hi) == 0"));
        assertEquals("(lo > hi) ? 0 : (lo + s(lo + 1, hi))", print("lo > hi ? 0 : (lo) + (s(lo + 1, hi))"));
        assertEquals("((lo > hi) ? 0 : (lo + s(lo + 1, hi)))",
                AcslPrinter.printLogicBody(AcslParser.parse("lo > hi ? 0 : (lo) + (s(lo + 1, hi))")));
        assertEquals("(x == 1)", AcslPrinter.printTopLevelPredicateBody(AcslParser.parse("x == 1")));
    }

    @Test
    void fileFormatterKeepsLayoutAndRewritesBodies() {
        String in = """
                /* comentario */
                axiomatic A {
                  logic integer s(integer lo, integer hi) =
                      lo > hi ? 0
                              : (lo) + (s(lo + 1, hi));
                  // decreases hi - lo;
                  predicate P = (x == 1);
                  axiom A_1:
                    \\forall integer x; (belongs(x, S) ==> ((x > 0)));
                }

                predicate Q = (x == 1);

                function F:
                contract:
                    requires  ((a == b)) && c;
                    assigns  a, b[0 .. (n - 1)];
                at loop 1:
                    loop invariant 0 <= i <= 9 + 1;
                    loop variant 9 + 1 - i;
                at return: assert ghost__f((x));
                """;
        List<String> warnings = new ArrayList<>();
        String out = AcslFileFormatter.formatAcsl(in, warnings);
        assertTrue(warnings.isEmpty(), warnings.toString());
        assertTrue(out.contains("logic integer s(integer lo, integer hi) =\n      ((lo > hi) ? 0 : (lo + s(lo + 1, hi)));"), out);
        assertTrue(out.contains("// decreases hi - lo;"), out);
        assertTrue(out.contains("predicate P = x == 1;"), out);
        assertTrue(out.contains("axiom A_1:\n    \\forall integer x; belongs(x, S) ==> (x > 0);"), out);
        assertTrue(out.contains("predicate Q = (x == 1);"), out);
        assertTrue(out.contains("requires  (a == b) && c;"), out);
        assertTrue(out.contains("assigns  a, b[0 .. n - 1];"), out);
        assertTrue(out.contains("loop invariant 0 <= i <= (9 + 1);"), out);
        assertTrue(out.contains("loop variant (9 + 1) - i;"), out);
        assertTrue(out.contains("at return: assert ghost__f(x);"), out);
        assertTrue(out.startsWith("/* comentario */\naxiomatic A {"), out);
    }

    @Test
    void ghostFormatterTouchesOnlyClauses() {
        String in = """
                //@ ghost int ghost_x;
                /*@
                    axiomatic dummy_ghost {
                        logic DSet<A> dummy_empty<A>(DSet<A> witness);
                    }
                */
                /*@ ghost
                  /@ assigns ghost_x;
                  @ ensures ((dummy_card(dummy_x) == 1)) ==> (dummy_equals(dummy_y,  \\old(dummy_y)));
                    @/
                  void f(int cc);
                */
                """;
        List<String> warnings = new ArrayList<>();
        String out = AcslFileFormatter.formatGhost(in, warnings);
        assertTrue(warnings.isEmpty(), warnings.toString());
        assertTrue(out.contains("  @ ensures (dummy_card(dummy_x) == 1) ==> dummy_equals(dummy_y, \\old(dummy_y));"), out);
        assertTrue(out.contains("logic DSet<A> dummy_empty<A>(DSet<A> witness);"), out);
        assertTrue(out.contains("  /@ assigns ghost_x;"), out);
        assertTrue(out.contains("  void f(int cc);"), out);
    }
}
