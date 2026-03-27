package com.example.bxml;

import java.util.ArrayList;
import java.util.List;

import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * Traduz predicados BXML ({@code pred_group}) para expressões ACSL.
 *
 * <p>{@code v : iseq(T)} / {@code v : seq(T)} → {@code iSeq} / {@code is_seq_of};
 * {@code ss : POW(S)} → {@code inclusion(ss, S)}; {@code ss : FIN(ss)} → {@code is_finite(ss)};
 * {@code x /: s} → {@code not_belongs(x, s)}; comparadores inteiros {@code <=i}, {@code <i} →
 * {@code <=}, {@code <}.
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
        String op = normalizeExpComparisonOp(cmp.getAttribute("op"));
        Element[] pair = BxmlExpressionToAcsl.twoDirectExpChildren(cmp);
        if (pair[0] == null || pair[1] == null) return "";
        Element leftEl = pair[0];
        Element rightEl = pair[1];
        if ("/:".equals(op)) {
            String left = BxmlExpressionToAcsl.translate(leftEl, ctx);
            String right = BxmlExpressionToAcsl.translate(rightEl, ctx);
            return "not_belongs(" + left + ", " + right + ")";
        }
        if (":".equals(op)) {
            // ss : POW(S) → inclusion(ss, S); ss : FIN(ss) → is_finite(ss)
            if ("Unary_Exp".equals(rightEl.getLocalName())) {
                String uop = rightEl.getAttribute("op");
                if ("POW".equals(uop)) {
                    String left = BxmlExpressionToAcsl.translate(leftEl, ctx);
                    Element inner = firstNonAttrElementChild(rightEl);
                    String setAtom = bTypeArgToSeqOfSetName(inner);
                    return "inclusion(" + left + ", " + setAtom + ")";
                }
                if ("FIN".equals(uop) || "fin".equals(uop)) {
                    String left = BxmlExpressionToAcsl.translate(leftEl, ctx);
                    return "is_finite(" + left + ")";
                }
                if ("iseq".equals(uop)) {
                    String left = BxmlExpressionToAcsl.translate(leftEl, ctx);
                    return "iSeq(" + left + ")";
                }
                if ("seq".equals(uop)) {
                    String left = BxmlExpressionToAcsl.translate(leftEl, ctx);
                    Element typeArg = firstNonAttrElementChild(rightEl);
                    String setAtom = bTypeArgToSeqOfSetName(typeArg);
                    return "is_seq_of(" + left + ", " + setAtom + ")";
                }
            }
            String left = BxmlExpressionToAcsl.translate(leftEl, ctx);
            String right = BxmlExpressionToAcsl.translate(rightEl, ctx);
            // x : T — pertença (ex.: nn : NAT → belongs(nn, NAT)) — ACSL_Lib/set_functions/belongs.acsl
            if (isPrimitiveTypeName(right)) {
                if ("NAT".equals(right)) return "belongs(" + left + ", NAT)";
                return "(" + left + " /* : " + right + " */)";
            }
            return "belongs(" + left + ", " + right + ")";
        }
        String left = BxmlExpressionToAcsl.translate(leftEl, ctx);
        String right = BxmlExpressionToAcsl.translate(rightEl, ctx);
        if ("<:".equals(op)) {
            return "inclusion(" + left + ", " + right + ")";
        }
        if ("=".equals(op)) return "(" + left + " == " + right + ")";
        return "(" + left + " " + op + " " + right + ")";
    }

    /**
     * Operadores BXML (ex.: {@code &lt;=i}, {@code &lt;i}) → ACSL numérico {@code <=}, {@code <}.
     */
    private static String normalizeExpComparisonOp(String raw) {
        if (raw == null) return "";
        String o = raw.trim();
        return switch (o) {
            case "&lt;:" -> "<:";
            case "&lt;=i", "<=i" -> "<=";
            case "&lt;i", "<i" -> "<";
            case "&gt;=i", ">=i" -> ">=";
            case "&gt;i", ">i" -> ">";
            default -> o;
        };
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

    private static Element firstNonAttrElementChild(Element parent) {
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

    /** Segundo argumento de {@code is_seq_of} (conjunto ACSL da lib, ex. {@code NAT}). */
    private static String bTypeArgToSeqOfSetName(Element typeArg) {
        if (typeArg != null && "Id".equals(typeArg.getLocalName())) {
            String v = typeArg.getAttribute("value");
            if (v == null || v.isBlank()) return "NAT";
            return switch (v) {
                case "NAT", "INTEGER", "INT" -> "NAT";
                case "BOOL" -> "BOOL";
                default -> v;
            };
        }
        return "NAT";
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
