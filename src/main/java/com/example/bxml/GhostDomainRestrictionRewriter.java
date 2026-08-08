package com.example.bxml;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * Caso de borda de {@link GhostOperationsCiGenerator}: sequência atribuída por restrição de domínio
 * sobre um array (função parcial em B) — o {@code ensures} ghost compara relações via
 * {@code list_to_function}/{@code array_to_function} em vez de comparação direta de sequência.
 * Extraído de {@code GhostOperationsCiGenerator} (que tinha WMC=746, o maior do projeto) por
 * extract-class puro: nenhuma linha de lógica mudou, só o arquivo em que vive.
 */
final class GhostDomainRestrictionRewriter {

    private GhostDomainRestrictionRewriter() {}

    /**
     * Após {@link GhostOperationsCiGenerator#toGhostEnsure}: {@code \old(dummy_seq)==domain_restriction(rel, S)} vindo de
     * {@code myseq == domain_restriction(...)}.
     */
    private static final Pattern GHOST_ENSURE_OLD_EQ_DOMAIN_RESTRICTION =
            Pattern.compile(
                    "^\\\\old\\(dummy_(\\w+)\\)\\s*==\\s*domain_restriction\\((\\w+)\\s*,\\s*([^)]+)\\)\\s*$");

    /** Idem no formato {@code equals(dummy_seq, domain_restriction(rel, S))}. */
    private static final Pattern GHOST_ENSURE_EQUALS_DOMAIN_RESTRICTION =
            Pattern.compile(
                    "^equals\\(dummy_(\\w+)\\s*,\\s*domain_restriction\\((\\w+)\\s*,\\s*([^)]+)\\)\\)\\s*$");

    /**
     * Variante equivalente após {@link GhostOperationsCiGenerator#toGhostEnsure}: {@code domain_restriction(rel, S) ==
     * dummy_list_to_function(\old(dummy_seq))}.
     */
    private static final Pattern GHOST_ENSURE_DOMAIN_RESTRICTION_EQ_LIST_OLD =
            Pattern.compile(
                    "^domain_restriction\\((\\w+)\\s*,\\s*([^)]+)\\)\\s*==\\s*(?:dummy_)?list_to_function\\(\\\\old\\(dummy_(\\w+)\\)\\)\\s*$");

    /**
     * Variante simétrica: {@code dummy_list_to_function(\old(dummy_seq)) == domain_restriction(rel, S)}.
     */
    private static final Pattern GHOST_ENSURE_LIST_OLD_EQ_DOMAIN_RESTRICTION =
            Pattern.compile(
                    "^(?:dummy_)?list_to_function\\(\\\\old\\(dummy_(\\w+)\\)\\)\\s*==\\s*domain_restriction\\((\\w+)\\s*,\\s*([^)]+)\\)\\s*$");

    /**
     * Caso legado de listas: o tradutor pode produzir {@code lhs == dummy_list_to_function((list_expr))}.
     * Para variáveis de tipo lista, a comparação deve permanecer lista-vs-lista.
     */
    private static final Pattern GHOST_ENSURE_EQ_LIST_TO_FUNCTION_OF_LIST_EXPR =
            Pattern.compile("^(.+?)\\s*==\\s*(?:dummy_)?list_to_function\\(\\((.+)\\)\\)\\s*$");

    /**
     * Sequência atribuída por restrição de domínio sobre array (função parcial em B): compara como
     * relações via {@code list_to_function} / {@code array_to_function}.
     */
    static String rewriteGhostEnsureForListDomainRestriction(
            String ensure,
            Element operation,
            Map<String, String> varTypes,
            List<GhostOperationsCiGenerator.Param> params,
            BxmlTranslateContext ctx,
            Set<String> concreteConstantNames) {
        if (ensure == null || operation == null || varTypes == null || ctx == null) {
            return ensure;
        }
        String t = ensure.trim();
        Matcher mOld = GHOST_ENSURE_OLD_EQ_DOMAIN_RESTRICTION.matcher(t);
        if (mOld.matches()) {
            String r =
                    tryRewriteListDomainRestrictionEquality(
                            mOld.group(1),
                            mOld.group(2),
                            mOld.group(3).trim(),
                            varTypes,
                            params,
                            operation,
                            ctx,
                            concreteConstantNames);
            if (r != null) {
                return r;
            }
        }
        Matcher mEq = GHOST_ENSURE_EQUALS_DOMAIN_RESTRICTION.matcher(t);
        if (mEq.matches()) {
            String r =
                    tryRewriteListDomainRestrictionEquality(
                            mEq.group(1),
                            mEq.group(2),
                            mEq.group(3).trim(),
                            varTypes,
                            params,
                            operation,
                            ctx,
                            concreteConstantNames);
            if (r != null) {
                return r;
            }
        }
        // Balanced-paren-aware fallback for domain_restriction(param, <nested-expr>) == list_to_function(\old(...))
        // Regex patterns above fail when the second arg of domain_restriction contains nested parens.
        String drBalanced = tryRewriteDomainRestrictionParamBalanced(
                t, varTypes, params, operation, ctx, concreteConstantNames);
        if (drBalanced != null) return drBalanced;

        Matcher mDrEqListOld = GHOST_ENSURE_DOMAIN_RESTRICTION_EQ_LIST_OLD.matcher(t);
        if (mDrEqListOld.matches()) {
            String r =
                    tryRewriteListDomainRestrictionEquality(
                            mDrEqListOld.group(3),
                            mDrEqListOld.group(1),
                            mDrEqListOld.group(2).trim(),
                            varTypes,
                            params,
                            operation,
                            ctx,
                            concreteConstantNames);
            if (r != null) {
                return r;
            }
        }
        Matcher mListOldEqDr = GHOST_ENSURE_LIST_OLD_EQ_DOMAIN_RESTRICTION.matcher(t);
        if (mListOldEqDr.matches()) {
            String r =
                    tryRewriteListDomainRestrictionEquality(
                            mListOldEqDr.group(1),
                            mListOldEqDr.group(2),
                            mListOldEqDr.group(3).trim(),
                            varTypes,
                            params,
                            operation,
                            ctx,
                            concreteConstantNames);
            if (r != null) {
                return r;
            }
        }
        String listEq =
                tryRewriteListEqualityWithoutListToFunction(
                        t, varTypes, concreteConstantNames);
        if (listEq != null) {
            return listEq;
        }
        return ensure;
    }

    private static String tryRewriteListEqualityWithoutListToFunction(
            String ensure,
            Map<String, String> varTypes,
            Set<String> concreteConstantNames) {
        if (ensure == null || varTypes == null) {
            return null;
        }
        Matcher m = GHOST_ENSURE_EQ_LIST_TO_FUNCTION_OF_LIST_EXPR.matcher(ensure);
        if (!m.matches()) {
            return null;
        }
        String lhs = m.group(1).trim();
        String rhsListExpr = m.group(2).trim();
        String seqVar = extractOldDummyListVarFromExpr(lhs);
        if (seqVar == null) {
            return null;
        }
        String listType = varTypes.get(seqVar);
        if (listType == null || !listType.startsWith("\\list")) {
            return null;
        }
        return lhs + " == " + GhostOperationsCiGenerator.ghostDummyConcreteRefs(rhsListExpr, concreteConstantNames);
    }

    private static String extractOldDummyListVarFromExpr(String expr) {
        if (expr == null) {
            return null;
        }
        String t = expr.trim();
        Matcher mOld = Pattern.compile("^\\\\old\\(dummy_(\\w+)\\)$").matcher(t);
        if (mOld.matches()) {
            return mOld.group(1);
        }
        // Pós-estado ghost: toGhostEnsure produz dummy_<seq> == list_to_function((…))
        Matcher mDummy = Pattern.compile("^dummy_(\\w+)$").matcher(t);
        if (mDummy.matches()) {
            return mDummy.group(1);
        }
        return null;
    }

    private static String tryRewriteListDomainRestrictionEquality(
            String seqVar,
            String relationParam,
            String domainRestrictionSecondArg,
            Map<String, String> varTypes,
            List<GhostOperationsCiGenerator.Param> params,
            Element operation,
            BxmlTranslateContext ctx,
            Set<String> concreteConstantNames) {
        String listType = varTypes.get(seqVar);
        if (listType == null || !listType.startsWith("\\list")) {
            return null;
        }
        if (!isPointerGhostParam(params, relationParam)) {
            return null;
        }
        String len =
                inferPartialFunctionDomainLengthAcsl(
                        operation, relationParam, ctx, concreteConstantNames);
        if (len == null || len.isBlank()) {
            return null;
        }
        return "domain_restriction(dummy_array_to_function("
                + relationParam
                + ", "
                + len
                + "), "
                + "dummy_"
                + domainRestrictionSecondArg
                + ") == dummy_list_to_function(\\old(dummy_"
                + seqVar
                + "))";
    }

    /**
     * Balanced-paren-aware version of the domain_restriction(param, …) == list_to_function(\old(…))
     * check. The regex patterns fail when the second arg of {@code domain_restriction} contains
     * nested parentheses (e.g. {@code interval_set(1, \length(\old(dummy_myseq)))}). Also corrects
     * the double-\old that arises when {@code rewriteAbstractIdsWithOld} wraps an already-wrapped
     * {@code \old(myseq)}.
     */
    private static String tryRewriteDomainRestrictionParamBalanced(
            String t,
            Map<String, String> varTypes,
            List<GhostOperationsCiGenerator.Param> params,
            Element operation,
            BxmlTranslateContext ctx,
            Set<String> concreteConstantNames) {
        if (t == null || !t.startsWith("domain_restriction(")) return null;
        int open = "domain_restriction".length(); // index of '('
        // First arg must be a plain identifier
        int comma1 = GhostOperationsCiGenerator.findTopLevelComma(t, open + 1);
        if (comma1 < 0) return null;
        String firstArg = t.substring(open + 1, comma1).trim();
        if (!firstArg.matches("\\w+")) return null;
        // Find matching close of domain_restriction(...)
        int close = GhostOperationsCiGenerator.findMatchingClose(t, open);
        if (close < 0) return null;
        String rest = t.substring(close + 1).trim();
        if (!rest.startsWith("==")) return null;
        String rhs = rest.substring(2).trim();
        // RHS: (dummy_)?list_to_function(\old(dummy_seqVar)) — single old (normal case after fix)
        // or list_to_function(\old(\old(dummy_seqVar))) — double old (legacy/defensive)
        Pattern rhsPat = Pattern.compile(
                "^(?:dummy_)?list_to_function\\(\\\\old\\(\\\\old\\(dummy_(\\w+)\\)\\)\\)$"
                + "|^(?:dummy_)?list_to_function\\(\\\\old\\(dummy_(\\w+)\\)\\)$");
        Matcher m = rhsPat.matcher(rhs);
        if (!m.matches()) return null;
        String seqVar = m.group(1) != null ? m.group(1) : m.group(2);
        String listType = varTypes == null ? null : varTypes.get(seqVar);
        if (listType == null || !listType.startsWith("\\list")) return null;
        if (!isPointerGhostParam(params, firstArg)) return null;
        String len = inferPartialFunctionDomainLengthAcsl(operation, firstArg, ctx, concreteConstantNames);
        if (len == null || len.isBlank()) return null;
        String domainArg = t.substring(comma1 + 1, close).trim();
        return "domain_restriction(dummy_array_to_function("
                + firstArg + ", " + len + "), "
                + domainArg
                + ") == dummy_list_to_function(\\old(dummy_" + seqVar + "))";
    }

    private static boolean isPointerGhostParam(List<GhostOperationsCiGenerator.Param> params, String name) {
        if (params == null || name == null || name.isBlank()) {
            return false;
        }
        for (GhostOperationsCiGenerator.Param p : params) {
            if (name.equals(p.name())) {
                String tp = p.type();
                return tp != null && tp.endsWith("*");
            }
        }
        return false;
    }

    /**
     * Expressão ACSL para o segundo argumento de {@code array_to_function}: obtida do domínio da função
     * parcial em B ({@code low..high}), tipicamente o nome da constante do limite superior (ex.
     * {@code maximum} para {@code 0..maximum}).
     */
    private static String inferPartialFunctionDomainLengthAcsl(
            Element operation,
            String paramName,
            BxmlTranslateContext ctx,
            Set<String> concreteConstantNames) {
        Element domain = partialFunctionDomainFromPrecondition(operation, paramName);
        return arrayLengthAcslFromDomain(domain, ctx, concreteConstantNames);
    }

    private static Element partialFunctionDomainFromPrecondition(Element operation, String paramName) {
        Element pre = BxmlDomUtils.firstChildElement(operation, "Precondition");
        if (pre == null) {
            return null;
        }
        return partialFunctionDomainFromPreconditionInPred(pre, paramName);
    }

    static Element partialFunctionDomainFromPreconditionInPred(Element pred, String paramName) {
        if (pred == null) {
            return null;
        }
        String ln = pred.getLocalName();
        if ("Precondition".equals(ln)) {
            NodeList nl = pred.getChildNodes();
            for (int i = 0; i < nl.getLength(); i++) {
                Node n = nl.item(i);
                if (n.getNodeType() != Node.ELEMENT_NODE) continue;
                Element ch = (Element) n;
                if ("Attr".equals(ch.getLocalName())) continue;
                Element d = partialFunctionDomainFromPreconditionInPred(ch, paramName);
                if (d != null) {
                    return d;
                }
            }
            return null;
        }
        if ("Exp_Comparison".equals(ln)) {
            String op = BxmlDomUtils.normalizeColonLikeOp(pred.getAttribute("op"));
            if (":".equals(op)) {
                Element[] pair = BxmlExpressionToAcsl.twoDirectExpChildren(pred);
                if (pair[0] != null
                        && "Id".equals(pair[0].getLocalName())
                        && paramName.equals(pair[0].getAttribute("value").trim())
                        && pair[1] != null
                        && BxmlExpressionToAcsl.isFunctionArrowType(pair[1])) {
                    Element[] arrow = BxmlExpressionToAcsl.twoDirectExpChildren(pair[1]);
                    if (arrow[0] != null) {
                        return arrow[0];
                    }
                }
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
                Element d = partialFunctionDomainFromPreconditionInPred(ch, paramName);
                if (d != null) {
                    return d;
                }
            }
            return null;
        }
        if ("Unary_Pred".equals(ln)) {
            Element inner = GhostOperationsCiGenerator.firstPredChildElement(pred);
            return partialFunctionDomainFromPreconditionInPred(inner, paramName);
        }
        if ("Binary_Pred".equals(ln)) {
            Element[] pair = BxmlDomUtils.twoDirectPredChildren(pred);
            if (pair[0] != null) {
                Element d = partialFunctionDomainFromPreconditionInPred(pair[0], paramName);
                if (d != null) {
                    return d;
                }
            }
            if (pair[1] != null) {
                return partialFunctionDomainFromPreconditionInPred(pair[1], paramName);
            }
        }
        return null;
    }

    static String arrayLengthAcslFromDomain(
            Element domain, BxmlTranslateContext ctx, Set<String> concreteConstantNames) {
        if (domain == null || ctx == null) {
            return null;
        }
        if (BxmlExpressionToAcsl.isIntervalBinaryExp(domain)) {
            Element[] lr = BxmlExpressionToAcsl.twoDirectExpChildren(domain);
            if (lr[0] == null || lr[1] == null) {
                return null;
            }
            String low = BxmlExpressionToAcsl.translate(lr[0], ctx).trim();
            String high = BxmlExpressionToAcsl.translate(lr[1], ctx).trim();
            String raw;
            if ("0".equals(low)) {
                raw = high;
            } else {
                raw = "(" + high + " - (" + low + ") + 1)";
            }
            return GhostOperationsCiGenerator.ghostDummyConcreteRefs(raw, concreteConstantNames);
        }
        return null;
    }
}
