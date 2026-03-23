package com.example.bxml;

import java.util.ArrayList;
import java.util.List;

import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * Traduz predicados BXML ({@code pred_group}) para expressões ACSL.
 *
 * @see <a href="https://www.atelierb.eu/wp-content/uploads/2023/10/bxml-1.0.html">BXML 1.0</a>
 */
public final class BxmlPredicateToAcsl {

    private BxmlPredicateToAcsl() {}

    public static List<String> translatePredicateBlock(Element predParent, BxmlTranslateContext ctx) {
        List<String> out = new ArrayList<>();
        Element first = firstPredChild(predParent);
        if (first != null) {
            String t = translatePred(first, ctx);
            if (!t.isBlank()) out.add(t);
        }
        return out;
    }

    /**
     * Conteúdo de {@code <Invariant>}: primeiro elemento preditivo (ex.: {@code Exp_Comparison}, {@code Nary_Pred}).
     */
    public static String translateInvariantContent(Element invariantEl, BxmlTranslateContext ctx) {
        Element p = firstPredChild(invariantEl);
        if (p == null) return "";
        return translatePred(p, ctx);
    }

    private static Element firstPredChild(Element parent) {
        NodeList nl = parent.getChildNodes();
        for (int i = 0; i < nl.getLength(); i++) {
            Node n = nl.item(i);
            if (n.getNodeType() != Node.ELEMENT_NODE) continue;
            Element e = (Element) n;
            if ("Attr".equals(e.getLocalName())) continue;
            return e;
        }
        return null;
    }

    private static String translatePred(Element p, BxmlTranslateContext ctx) {
        String ln = p.getLocalName();
        return switch (ln) {
            case "Exp_Comparison" -> translateExpComparison(p, ctx);
            case "Nary_Pred" -> translateNaryPred(p, ctx);
            case "Unary_Pred" -> translateUnaryPred(p, ctx);
            case "Binary_Pred" -> translateBinaryPred(p, ctx);
            case "Quantified_Pred" -> "/* quantified pred */";
            default -> "";
        };
    }

    private static String translateExpComparison(Element cmp, BxmlTranslateContext ctx) {
        String op = cmp.getAttribute("op");
        Element[] pair = BxmlExpressionToAcsl.twoDirectExpChildren(cmp);
        if (pair[0] == null || pair[1] == null) return "";
        String left = BxmlExpressionToAcsl.translate(pair[0], ctx);
        String right = BxmlExpressionToAcsl.translate(pair[1], ctx);
        if (":".equals(op)) {
            // x : T — pertença (ex.: nn : NAT → belongs(nn, NAT)) — ACSL_Lib/set_functions/belongs.acsl
            if (isPrimitiveTypeName(right)) {
                if ("NAT".equals(right)) return "belongs(" + left + ", NAT)";
                return "(" + left + " /* : " + right + " */)";
            }
            return "belongs(" + left + ", " + right + ")";
        }
        if ("<:".equals(op) || "&lt;:".equals(op)) {
            // subconjunto — ACSL_Lib/set_functions/inclusion.acsl
            return "inclusion(" + left + ", " + right + ")";
        }
        if ("=".equals(op)) return "(" + left + " == " + right + ")";
        return "(" + left + " " + op + " " + right + ")";
    }

    private static String translateNaryPred(Element np, BxmlTranslateContext ctx) {
        String op = np.getAttribute("op");
        List<String> parts = new ArrayList<>();
        NodeList nl = np.getChildNodes();
        for (int i = 0; i < nl.getLength(); i++) {
            Node n = nl.item(i);
            if (n.getNodeType() != Node.ELEMENT_NODE) continue;
            Element e = (Element) n;
            if ("Attr".equals(e.getLocalName())) continue;
            parts.add(translatePred(e, ctx));
        }
        if ("&".equals(op)) return String.join(" && ", parts);
        if ("or".equals(op)) return String.join(" || ", parts);
        return String.join(" && ", parts);
    }

    private static String translateUnaryPred(Element up, BxmlTranslateContext ctx) {
        String op = up.getAttribute("op");
        Element child = firstPredChild(up);
        if (child == null) return "";
        String c = translatePred(child, ctx);
        if ("not".equals(op)) return "!(" + c + ")";
        return c;
    }

    private static String translateBinaryPred(Element bp, BxmlTranslateContext ctx) {
        String op = bp.getAttribute("op");
        Element[] pair = twoDirectPredChildren(bp);
        if (pair[0] == null || pair[1] == null) return "";
        String a = translatePred(pair[0], ctx);
        String b = translatePred(pair[1], ctx);
        if ("=>".equals(op)) return "(" + a + " ==> " + b + ")";
        if ("<=>".equals(op)) return "(" + a + " <==> " + b + ")";
        return "(" + a + " " + op + " " + b + ")";
    }

    private static boolean isPrimitiveTypeName(String right) {
        return switch (right) {
            case "NAT", "INTEGER", "BOOL", "INT", "REAL" -> true;
            default -> false;
        };
    }

    /** Predicado completo de um {@code <Body>} de operação (ex.: dentro de {@code Quantified_Set}). */
    public static String translateBodyPredicate(Element body, BxmlTranslateContext ctx) {
        Element p = firstPredChild(body);
        if (p == null) return "";
        return translatePred(p, ctx);
    }

    private static Element[] twoDirectPredChildren(Element parent) {
        Element[] out = new Element[2];
        int k = 0;
        NodeList nl = parent.getChildNodes();
        for (int i = 0; i < nl.getLength(); i++) {
            Node n = nl.item(i);
            if (n.getNodeType() != Node.ELEMENT_NODE) continue;
            Element e = (Element) n;
            if ("Attr".equals(e.getLocalName())) continue;
            out[k++] = e;
            if (k == 2) break;
        }
        return out;
    }
}
