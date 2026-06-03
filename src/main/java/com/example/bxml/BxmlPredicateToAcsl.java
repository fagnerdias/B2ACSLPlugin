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
 * {@code x : BOOL} → {@code belongs(x, BOOL)}; {@code x : NAT} → {@code belongs(x, NAT)};
 * {@code x : INT} → {@code belongs(x, INT)};
 * {@code x /: s} → {@code not_belongs(x, s)}; comparadores inteiros {@code <=i}, {@code <i} →
 * {@code <=}, {@code <}; igualdade entre conjuntos ({@code Set<…>}, {@code ran} sobre relação, …) →
 * {@code equals(a, b)}; escalares (ex.: {@code card}) mantêm {@code ==}.
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

    /**
     * Filho preditivo direto de {@code Properties} (ex.: {@code Exp_Comparison}, {@code Nary_Pred}).
     */
    public static String translatePropertyPred(Element predElement, BxmlTranslateContext ctx) {
        return translatePred(predElement, ctx);
    }

    /**
     * Lado direito {@code S --> T} de uma restrição {@code v : S --> T} dentro do predicado (ex.
     * cláusula {@code WHERE} de {@code ANY_Sub}), ou {@code null}.
     */
    public static Element findFunctionArrowRhsForVariable(Element predRoot, String varName) {
        if (predRoot == null || varName == null || varName.isBlank()) {
            return null;
        }
        return findFunctionArrowRhsForVariable0(predRoot, varName.trim());
    }

    /**
     * Tipo {@code logic} para quantificador {@code \\forall} (ex. {@code Function_int_int}) a partir
     * de {@code <Pred>} e do {@code Id} da variável de {@code ANY_Sub}: usa {@code v : A --> B} no
     * predicado; senão cai no {@code typref} (ex. {@code Relation_int_int} → {@code Function_int_int}).
     */
    public static String acslQuantifierLogicTypeForAnyVariable(
            Element predParent, Element varId, BxmlTranslateContext ctx) {
        if (varId == null || ctx == null) {
            return "integer";
        }
        String name = varId.getAttribute("value");
        if (name == null) {
            name = "";
        }
        name = name.trim();
        Element p0 = firstPredChild(predParent);
        Element arrow = p0 != null ? findFunctionArrowRhsForVariable(p0, name) : null;
        if (arrow != null) {
            return functionArrowBinaryToAcslFunctionType(arrow);
        }
        String trAttr = varId.getAttribute("typref");
        if (trAttr != null && !trAttr.isBlank()) {
            try {
                int tr = Integer.parseInt(trAttr.trim());
                String t = ctx.types().acslVariableLogicTypeFromTypref(tr);
                return relationOrFallbackQuantifierType(t);
            } catch (NumberFormatException ignored) {
                // fall through
            }
        }
        return "integer";
    }

    private static String relationOrFallbackQuantifierType(String t) {
        if (t == null || t.isBlank()) {
            return "integer";
        }
        return switch (t.trim()) {
            case "Relation_int_int" -> "Function_int_int";
            case "Relation_int_bool" -> "Function_int_bool";
            case "Relation_bool_int" -> "Function_bool_int";
            case "Relation_int_tuple_int_int_int" -> "Function_int_tuple_int_int_int";
            default -> {
                if (t.startsWith("Relation_")) {
                    yield "Function_" + t.substring("Relation_".length());
                }
                yield t;
            }
        };
    }

    private static String functionArrowBinaryToAcslFunctionType(Element arrow) {
        Element[] dr = BxmlExpressionToAcsl.twoDirectExpChildren(arrow);
        if (dr[0] == null || dr[1] == null) {
            return "Function_int_int";
        }
        String lhs = arrowEndToBNameForProduct(dr[0]);
        String rhs = arrowEndToBNameForProduct(dr[1]);
        String relation = BxmlTypeRegistry.powCartesianProductToAcslRelationType(lhs + "*" + rhs);
        return relationOrFallbackQuantifierType(relation);
    }

    /** Extremidade B de {@code -->} como nome de tipo escalar para {@link BxmlTypeRegistry}. */
    private static String arrowEndToBNameForProduct(Element e) {
        if (e == null) {
            return "INTEGER";
        }
        String ln = e.getLocalName();
        if ("Binary_Exp".equals(ln) && BxmlExpressionToAcsl.isIntervalBinaryExp(e)) {
            return "INTEGER";
        }
        if ("Id".equals(ln)) {
            String v = e.getAttribute("value").trim();
            return switch (v) {
                case "BOOL" -> "BOOL";
                case "NAT", "NAT1", "INTEGER", "INT" -> "INTEGER";
                default -> "INTEGER";
            };
        }
        if ("Unary_Exp".equals(ln)) {
            String u = e.getAttribute("op");
            if ("seq".equals(u) || "iseq".equals(u)) {
                return "INTEGER";
            }
        }
        return "INTEGER";
    }

    private static Element findFunctionArrowRhsForVariable0(Element pred, String varName) {
        if (pred == null) {
            return null;
        }
        String ln = pred.getLocalName();
        if ("Exp_Comparison".equals(ln) && isColonTypingComparison(pred)) {
            Element[] pair = BxmlExpressionToAcsl.twoDirectExpChildren(pred);
            if (pair[0] != null
                    && "Id".equals(pair[0].getLocalName())
                    && varName.equals(pair[0].getAttribute("value").trim())
                    && pair[1] != null
                    && BxmlExpressionToAcsl.isFunctionArrowType(pair[1])) {
                return pair[1];
            }
            return null;
        }
        if ("Nary_Pred".equals(ln)) {
            NodeList nl = pred.getChildNodes();
            for (int i = 0; i < nl.getLength(); i++) {
                Node n = nl.item(i);
                if (n.getNodeType() != Node.ELEMENT_NODE) continue;
                Element ch = (Element) n;
                if ("Attr".equals(ch.getLocalName())) continue;
                Element found = findFunctionArrowRhsForVariable0(ch, varName);
                if (found != null) {
                    return found;
                }
            }
            return null;
        }
        if ("Unary_Pred".equals(ln)) {
            return findFunctionArrowRhsForVariable0(firstPredChild(pred), varName);
        }
        if ("Binary_Pred".equals(ln)) {
            Element[] pair = twoDirectPredChildren(pred);
            if (pair[0] != null) {
                Element f = findFunctionArrowRhsForVariable0(pair[0], varName);
                if (f != null) {
                    return f;
                }
            }
            if (pair[1] != null) {
                return findFunctionArrowRhsForVariable0(pair[1], varName);
            }
        }
        return null;
    }

    private static boolean isColonTypingComparison(Element cmp) {
        String op = cmp.getAttribute("op");
        return op != null && ":".equals(op.trim());
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
            case "Quantified_Pred" -> translateQuantifiedPred(p, ctx);
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
            // f : (S --> T) com S intervalo/compreensão — função total (ACSL_Lib function_functions/is_total.acsl)
            if (BxmlExpressionToAcsl.isFunctionArrowType(rightEl)) {
                Element[] domRng = BxmlExpressionToAcsl.twoDirectExpChildren(rightEl);
                if (domRng[0] == null || domRng[1] == null) return "";
                String fun = BxmlExpressionToAcsl.translate(leftEl, ctx);
                String domainSet = BxmlExpressionToAcsl.intervalOrSetComprehensionRef(domRng[0], ctx);
                String rangeSet = functionArrowRangeSet(domRng[1], ctx);
                return "is_total_function(" + fun + ", " + domainSet + ", " + rangeSet + ")";
            }
            // f : (S -->> T) — função total surjetiva (ACSL_Lib function_functions/is_total.acsl + is_surjective.acsl)
            if (BxmlExpressionToAcsl.isTotalSurjectionArrowType(rightEl)) {
                Element[] domRng = BxmlExpressionToAcsl.twoDirectExpChildren(rightEl);
                if (domRng[0] == null || domRng[1] == null) return "";
                String fun = BxmlExpressionToAcsl.translate(leftEl, ctx);
                String domainSet = BxmlExpressionToAcsl.intervalOrSetComprehensionRef(domRng[0], ctx);
                String rangeSet = functionArrowRangeSet(domRng[1], ctx);
                return "is_total_function(" + fun + ", " + domainSet + ", " + rangeSet + ")"
                        + " && is_surjective(" + fun + ", " + rangeSet + ")";
            }
            String left = BxmlExpressionToAcsl.translate(leftEl, ctx);
            if (BxmlExpressionToAcsl.isIntervalBinaryExp(rightEl)) {
                String right = BxmlExpressionToAcsl.intervalOrSetComprehensionRef(rightEl, ctx);
                return "belongs(" + left + ", " + right + ")";
            }
            String right = BxmlExpressionToAcsl.translate(rightEl, ctx);
            // x : T — pertença (ex.: nn : NAT → belongs(nn, NAT)) — ACSL_Lib/set_functions/belongs.acsl
            if (isPrimitiveTypeName(right)) {
                if ("NAT".equals(right)) return "belongs(" + left + ", NAT)";
                if ("BOOL".equals(right)) return "belongs(" + left + ", BOOL)";
                if ("INT".equals(right)) return "belongs(" + left + ", INT)";
                return "(" + left + " /* : " + right + " */)";
            }
            return "belongs(" + left + ", " + right + ")";
        }
        String left = BxmlExpressionToAcsl.translate(leftEl, ctx);
        String right = BxmlExpressionToAcsl.translate(rightEl, ctx);
        if ("<:".equals(op)) {
            return "inclusion(" + left + ", " + right + ")";
        }
        if ("=".equals(op)) {
            if ("Id".equals(leftEl.getLocalName())
                    && "Boolean_Literal".equals(rightEl.getLocalName())) {
                return "("
                        + BxmlExpressionToAcsl.translate(leftEl, ctx)
                        + " == "
                        + BxmlExpressionToAcsl.booleanLiteralRhsForVariable(
                                leftEl, rightEl.getAttribute("value"), ctx)
                        + ")";
            }
            if ("Id".equals(rightEl.getLocalName())
                    && "Boolean_Literal".equals(leftEl.getLocalName())) {
                return "("
                        + BxmlExpressionToAcsl.translate(rightEl, ctx)
                        + " == "
                        + BxmlExpressionToAcsl.booleanLiteralRhsForVariable(
                                rightEl, leftEl.getAttribute("value"), ctx)
                        + ")";
            }
            if (BxmlExpressionToAcsl.isListValued(leftEl, ctx)
                    && BxmlExpressionToAcsl.isRelationOrFunctionValued(rightEl, ctx)) {
                return "(" + right + " == list_to_function(" + left + "))";
            }
            if (BxmlExpressionToAcsl.isListValued(rightEl, ctx)
                    && BxmlExpressionToAcsl.isRelationOrFunctionValued(leftEl, ctx)) {
                return "(" + left + " == list_to_function(" + right + "))";
            }
            if (BxmlExpressionToAcsl.isSetValued(leftEl, ctx)
                    && BxmlExpressionToAcsl.isSetValued(rightEl, ctx)) {
                return "equals(" + left + ", " + right + ")";
            }
            return "(" + left + " == " + right + ")";
        }
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
            case "/=" -> "!=";
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

    /** Codomínio B (ex. {@code NAT}) → nome do conjunto ACSL da lib (ex. {@code NAT}). */
    private static String functionArrowRangeSet(Element codomainEl, BxmlTranslateContext ctx) {
        if (codomainEl != null && "Id".equals(codomainEl.getLocalName())) {
            String v = codomainEl.getAttribute("value");
            if (v == null) {
                v = "";
            }
            return switch (v.trim()) {
                case "NAT", "INTEGER", "INT" -> "NAT";
                case "BOOL" -> "BOOL";
                default -> BxmlExpressionToAcsl.translate(codomainEl, ctx);
            };
        }
        return BxmlExpressionToAcsl.translate(codomainEl, ctx);
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

    /**
     * B {@code !x.(P => Q)} / {@code #x.(P)} → ACSL {@code (\forall integer x; body)} /
     * {@code (\exists integer x; body)}.
     *
     * <p>Suporta múltiplas variáveis ({@code !x,y.(…)}).
     */
    private static String translateQuantifiedPred(Element qp, BxmlTranslateContext ctx) {
        String type = qp.getAttribute("type");
        String quant = "!".equals(type) ? "\\forall" : "\\exists";

        Element varsEl = childByName(qp, "Variables");
        List<String> varDecls = new ArrayList<>();
        if (varsEl != null) {
            NodeList nl = varsEl.getChildNodes();
            for (int i = 0; i < nl.getLength(); i++) {
                Node n = nl.item(i);
                if (n.getNodeType() != Node.ELEMENT_NODE) continue;
                Element e = (Element) n;
                if ("Attr".equals(e.getLocalName()) || !"Id".equals(e.getLocalName())) continue;
                varDecls.add("integer " + e.getAttribute("value"));
            }
        }
        if (varDecls.isEmpty()) return "/* quantified pred */";

        Element bodyEl = childByName(qp, "Body");
        if (bodyEl == null) return "/* quantified pred */";
        Element bodyPred = firstPredChild(bodyEl);
        if (bodyPred == null) return "/* quantified pred */";
        String body = translatePred(bodyPred, ctx);

        return "(" + quant + " " + String.join(", ", varDecls) + "; " + body + ")";
    }

    private static Element childByName(Element parent, String localName) {
        NodeList nl = parent.getChildNodes();
        for (int i = 0; i < nl.getLength(); i++) {
            Node n = nl.item(i);
            if (n.getNodeType() != Node.ELEMENT_NODE) continue;
            Element e = (Element) n;
            if (localName.equals(e.getLocalName())) return e;
        }
        return null;
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
