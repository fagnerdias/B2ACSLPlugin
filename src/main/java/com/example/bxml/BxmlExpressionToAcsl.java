package com.example.bxml;

import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * Traduz sub-árvores de expressão BXML ({@code Exp}) para texto ACSL usando símbolos alinhados a {@code ACSL_Lib}
 * (ex.: {@code empty}, {@code set_union} em {@code set_functions/}).
 *
 * @see <a href="https://www.atelierb.eu/wp-content/uploads/2023/10/bxml-1.0.html">BXML 1.0</a>
 */
public final class BxmlExpressionToAcsl {

    private BxmlExpressionToAcsl() {}

    /**
     * Traduz uma expressão B (filho de {@code Values} ou {@code Variables}).
     */
    public static String translate(Element exp, BxmlTranslateContext ctx) {
        String ln = exp.getLocalName();
        return switch (ln) {
            case "Id" -> exp.getAttribute("value");
            case "Integer_Literal" -> exp.getAttribute("value");
            case "Boolean_Literal" -> exp.getAttribute("value");
            case "EmptySet" -> translateEmptySet(exp, ctx.types());
            case "EmptySeq" -> "empty_seq"; // placeholder: sequência vazia
            case "Unary_Exp" -> translateUnary(exp, ctx);
            case "Binary_Exp" -> translateBinary(exp, ctx);
            case "Nary_Exp" -> translateNary(exp, ctx);
            case "Quantified_Set" -> translateQuantifiedSet(exp, ctx);
            default -> "/* TODO: " + ln + " */";
        };
    }

    /**
     * Conjunto em compreensão {@code { x | P }} — referência a {@code set_comprehension_k} do bloco axiomatic.
     */
    static String translateQuantifiedSet(Element qs, BxmlTranslateContext ctx) {
        String named = ctx.comprehensions().referenceName(qs);
        if (named != null) {
            return named;
        }
        Element vars = firstChildElement(qs, "Variables");
        Element body = firstChildElement(qs, "Body");
        if (vars == null || body == null) return "/* quantified_set */";
        java.util.List<String> names = new java.util.ArrayList<>();
        NodeList vnodes = vars.getChildNodes();
        for (int i = 0; i < vnodes.getLength(); i++) {
            Node n = vnodes.item(i);
            if (n.getNodeType() != Node.ELEMENT_NODE) continue;
            Element e = (Element) n;
            if ("Id".equals(e.getLocalName())) names.add(e.getAttribute("value"));
        }
        String pred = BxmlPredicateToAcsl.translateBodyPredicate(body, ctx);
        String vs = String.join(", ", names);
        return "comprehension(" + vs + ", " + pred + ")";
    }

    private static Element firstChildElement(Element parent, String localName) {
        NodeList nl = parent.getChildNodes();
        for (int i = 0; i < nl.getLength(); i++) {
            Node n = nl.item(i);
            if (n.getNodeType() != Node.ELEMENT_NODE) continue;
            Element e = (Element) n;
            if (localName.equals(e.getLocalName())) return e;
        }
        return null;
    }

    private static String translateEmptySet(Element emptySet, BxmlTypeRegistry types) {
        String tr = emptySet.getAttribute("typref");
        int typref = tr.isBlank() ? -1 : Integer.parseInt(tr.trim());
        String elem = typref >= 0 ? types.elementTypeNameForSetTypref(typref) : "NAT";
        // ACSL_Lib/set_functions/empty.acsl — lógica overloaded: empty(Set<τ> witness)
        return "empty(" + elem + ")";
    }

    private static String translateUnary(Element u, BxmlTranslateContext ctx) {
        String op = u.getAttribute("op");
        Element arg = firstExpChild(u);
        if (arg == null) return "/* unary */";
        if ("card".equals(op) && "Quantified_Set".equals(arg.getLocalName())) {
            return "card(" + translateQuantifiedSet(arg, ctx) + ")"; // card.acsl
        }
        String a = translate(arg, ctx);
        return switch (op) {
            case "card" -> "card(" + a + ")";
            default -> op + "(" + a + ")";
        };
    }

    private static String translateBinary(Element b, BxmlTranslateContext ctx) {
        String op = b.getAttribute("op");
        Element[] pair = twoDirectExpChildren(b);
        if (pair[0] == null || pair[1] == null) return "/* binary */";
        String left = translate(pair[0], ctx);
        String right = translate(pair[1], ctx);
        if (isSetUnion(op)) {
            // B: \/ — união → set_union (ACSL_Lib/set_functions/union.acsl)
            return "set_union(" + left + ", " + right + ")";
        }
        if ("mod".equals(op)) return "(" + left + " % " + right + ")";
        return "(" + left + " " + op + " " + right + ")";
    }

    private static String translateNary(Element n, BxmlTranslateContext ctx) {
        String op = n.getAttribute("op");
        if ("{".equals(op)) {
            // enumeração finita { e1, e2, ... } — ACSL_Lib/set_functions/singleton.acsl
            java.util.List<String> parts = new java.util.ArrayList<>();
            NodeList children = n.getChildNodes();
            for (int i = 0; i < children.getLength(); i++) {
                Node node = children.item(i);
                if (node.getNodeType() != Node.ELEMENT_NODE) continue;
                Element e = (Element) node;
                if ("Attr".equals(e.getLocalName())) continue;
                parts.add(translate(e, ctx));
            }
            if (parts.size() == 1) return "singleton(" + parts.get(0) + ")";
            return "set_enum(" + String.join(", ", parts) + ")";
        }
        return "/* nary " + op + " */";
    }

    private static Element firstExpChild(Element parent) {
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

    /** Dois primeiros elementos filhos que pertencem ao grupo Exp (aproximação por ordem DOM). */
    private static boolean isSetUnion(String op) {
        if (op == null || op.isEmpty()) return false;
        // BXML grava o operador de união como \/
        return "\\/".equals(op) || "/".equals(op);
    }

    /** Usado também por {@link BxmlPredicateToAcsl} para {@code Exp_Comparison}. */
    static Element[] twoDirectExpChildren(Element parent) {
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
