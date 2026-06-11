package com.example.bxml;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * Traduz a cláusula {@code <Initialisation>} (BXML 1.0) para contratos ACSL, usando funções da
 * biblioteca {@code ACSL_Lib} em resources (ex.: {@code empty}, {@code set_union}, {@code singleton}).
 *
 * <p>{@code <Initialisation>} contém exatamente uma substituição ({@code Sub}); em particular
 * {@code Assignement_Sub} representa {@code v := E}, e {@code Nary_Sub} com {@code op=";"} sequencia
 * substituições.
 *
 * @see <a href="https://www.atelierb.eu/wp-content/uploads/2023/10/bxml-1.0.html">BXML 1.0 — Initialisation</a>
 */
public final class BxmlInitialisationTranslator {

    private BxmlInitialisationTranslator() {}

    /**
     * Extrai {@code ensures} a partir do corpo de uma operação ({@code <Body>}), reutilizando a mesma tradução
     * de substituições que em {@code Initialisation}.
     */
    public static void appendEnsuresFromBody(Element body, List<String> ensures, BxmlTranslateContext ctx) {
        Element sub = firstSubChild(body);
        walkSubstitution(sub, ensures, ctx);
    }

    /**
     * @param machineEl elemento raiz {@code <Machine>}
     * @param additionalAssignTargets nomes para {@code assigns} (ex.: {@code NomeAbstrata__v} a partir
     *        de {@code Concrete_Variables} das máquinas de implementação fundidas)
     */
    public static InitialisationAcsl translate(
            Element machineEl, List<String> additionalAssignTargets, BxmlTranslateContext ctx) {
        return translate(machineEl, additionalAssignTargets, ctx, Map.of());
    }

    public static InitialisationAcsl translate(
            Element machineEl, List<String> additionalAssignTargets, BxmlTranslateContext ctx,
            Map<String, Long> knownIntegerConstants) {
        String machineName = machineEl.getAttribute("name");

        List<String> ensures = new ArrayList<>();
        Element init = firstChildElement(machineEl, "Initialisation");
        if (init != null) {
            walkSubstitution(firstSubChild(init), ensures, ctx);
        }
        Map<String, Long> constants = knownIntegerConstants == null ? Map.of() : knownIntegerConstants;
        String loopUnfoldSize = (init != null)
                ? detectCartesianProductUnfoldSize(firstSubChild(init), ctx, constants)
                : null;

        String functionName = machineName + "__INITIALISATION";
        return new InitialisationAcsl(
                functionName, ensures, new ArrayList<>(additionalAssignTargets), false, List.of(), loopUnfoldSize);
    }

    private static String detectCartesianProductUnfoldSize(
            Element sub, BxmlTranslateContext ctx, Map<String, Long> constants) {
        if (sub == null) return null;
        String ln = sub.getLocalName();
        return switch (ln) {
            case "Assignement_Sub" -> cartesianProductSizeFromAssignment(sub, ctx, constants);
            case "Nary_Sub" -> {
                NodeList children = sub.getChildNodes();
                for (int i = 0; i < children.getLength(); i++) {
                    Node n = children.item(i);
                    if (n.getNodeType() != Node.ELEMENT_NODE) continue;
                    Element ch = (Element) n;
                    if ("Attr".equals(ch.getLocalName())) continue;
                    String result = detectCartesianProductUnfoldSize(ch, ctx, constants);
                    if (result != null) yield result;
                }
                yield null;
            }
            case "Bloc_Sub" -> detectCartesianProductUnfoldSize(firstSubChild(sub), ctx, constants);
            default -> null;
        };
    }

    private static String cartesianProductSizeFromAssignment(
            Element assign, BxmlTranslateContext ctx, Map<String, Long> constants) {
        Element vals = firstChildElement(assign, "Values");
        if (vals == null) return null;
        for (Element rhsEl : directExpChildren(vals)) {
            String size = intervalCartesianProductSize(rhsEl, ctx, constants);
            if (size != null) return size;
        }
        return null;
    }

    private static String intervalCartesianProductSize(
            Element el, BxmlTranslateContext ctx, Map<String, Long> constants) {
        if (!"Binary_Exp".equals(el.getLocalName())) return null;
        if (!"*s".equals(el.getAttribute("op"))) return null;
        List<Element> children = directExpChildren(el);
        if (children.size() < 2) return null;
        Element intervalEl = children.get(0);
        Element singletonEl = children.get(1);
        if (!"Binary_Exp".equals(intervalEl.getLocalName())) return null;
        if (!"..".equals(intervalEl.getAttribute("op"))) return null;
        if (!"Nary_Exp".equals(singletonEl.getLocalName())) return null;
        if (!"{".equals(singletonEl.getAttribute("op"))) return null;
        if (directExpChildren(singletonEl).size() != 1) return null;
        List<Element> bounds = directExpChildren(intervalEl);
        if (bounds.size() < 2) return null;
        Long lowerLit = resolveIntegerBound(bounds.get(0), constants);
        Long upperLit = resolveIntegerBound(bounds.get(1), constants);
        if (lowerLit != null && upperLit != null) {
            return String.valueOf(upperLit - lowerLit + 1);
        }
        // Fallback: use ACSL expressions (only valid if they resolve to compile-time constants)
        String lower = BxmlExpressionToAcsl.translate(bounds.get(0), ctx);
        String upper = BxmlExpressionToAcsl.translate(bounds.get(1), ctx);
        if (lower == null || upper == null) return null;
        lower = lower.trim();
        upper = upper.trim();
        if ("0".equals(lower)) {
            return "(" + upper + " + 1)";
        }
        return "(" + upper + " - " + lower + " + 1)";
    }

    private static Long resolveIntegerBound(Element boundEl, Map<String, Long> constants) {
        if ("Integer_Literal".equals(boundEl.getLocalName())) {
            String val = boundEl.getAttribute("value");
            try { return Long.parseLong(val.trim()); } catch (NumberFormatException e) { return null; }
        }
        if ("Id".equals(boundEl.getLocalName())) {
            return constants.get(boundEl.getAttribute("value"));
        }
        return null;
    }

    private static void walkSubstitution(Element sub, List<String> ensures, BxmlTranslateContext ctx) {
        if (sub == null) return;
        String ln = sub.getLocalName();
        switch (ln) {
            case "Assignement_Sub" -> parseAssignementSub(sub, ensures, ctx);
            case "Nary_Sub" -> {
                String op = sub.getAttribute("op");
                if (";".equals(op)) {
                    NodeList children = sub.getChildNodes();
                    for (int i = 0; i < children.getLength(); i++) {
                        Node n = children.item(i);
                        if (n.getNodeType() != Node.ELEMENT_NODE) continue;
                        Element ch = (Element) n;
                        if ("Attr".equals(ch.getLocalName())) continue;
                        walkSubstitution(ch, ensures, ctx);
                    }
                } else if ("||".equals(op)) {
                    // simultâneo: um ensures conjuntivo
                    parseSimultaneous(sub, ensures, ctx);
                } else {
                    walkSubstitution(firstSubChild(sub), ensures, ctx);
                }
            }
            case "Skip" -> { /* nada */ }
            case "Bloc_Sub" -> walkSubstitution(firstSubChild(sub), ensures, ctx);
            case "ANY_Sub" -> {
                /* Tratado como contrato ghost por GhostOperationsCiGenerator; nada a emitir aqui. */
            }
            case "Becomes_In" -> parseBecomesInSub(sub, ensures, ctx);
            case "Becomes_Such_That" -> parseBecomesSuchThatSub(sub, ensures, ctx);
            case "Select" -> parseSelectAsConditionalEnsures(sub, ensures, ctx);
            case "If_Sub" -> parseIfSubAsConditionalEnsures(sub, ensures, ctx);
            default -> { /* outras substituições: extensão futura */ }
        }
    }

    /**
     * Procura {@code ANY_Sub} no topo de um {@code <Body>} (atravessando {@code Bloc_Sub}); útil para o
     * gerador de operações ghost decidir se a operação tem contrato ghost derivado de {@code ANY_Sub}.
     */
    public static Element findTopLevelAnySub(Element body) {
        if (body == null) return null;
        Element sub = firstSubChild(body);
        while (sub != null && "Bloc_Sub".equals(sub.getLocalName())) {
            sub = firstSubChild(sub);
        }
        if (sub != null && "ANY_Sub".equals(sub.getLocalName())) {
            return sub;
        }
        return null;
    }

    /**
     * {@code ANY v WHERE P THEN Q} → {@code \forall T v; P ==> (tradução de Q)} (uma única cláusula).
     *
     * <p>A tradução das atribuições em {@code Then} reusa {@link #walkSubstitution} (mesmas regras
     * que {@code Initialisation} / corpos de operação).
     */
    public static String translateAnySubAsForall(Element anySub, BxmlTranslateContext ctx) {
        if (anySub == null) return "";
        Element vars = firstChildElement(anySub, "Variables");
        Element predEl = firstChildElement(anySub, "Pred");
        Element thenEl = firstChildElement(anySub, "Then");
        if (vars == null || predEl == null || thenEl == null) {
            return "";
        }
        String p = BxmlPredicateToAcsl.translateInvariantContent(predEl, ctx).trim();
        if (p.isBlank()) {
            p = "\\true";
        }
        List<Element> varIds = directExpChildren(vars);
        List<String> binders = new ArrayList<>();
        for (Element vid : varIds) {
            if (!"Id".equals(vid.getLocalName())) {
                continue;
            }
            String vn = vid.getAttribute("value").trim();
            if (vn.isBlank()) {
                continue;
            }
            String ty = BxmlPredicateToAcsl.acslQuantifierLogicTypeForAnyVariable(predEl, vid, ctx);
            binders.add(ty + " " + vn);
        }
        if (binders.isEmpty()) {
            return "";
        }
        List<String> inner = new ArrayList<>();
        walkSubstitution(firstSubChild(thenEl), inner, ctx);
        String q = inner.isEmpty() ? "\\true" : String.join(" && ", inner);
        return "\\forall " + String.join(", ", binders) + "; " + p + " ==> (" + q + ")";
    }

    private static void parseSimultaneous(Element narySub, List<String> ensures, BxmlTranslateContext ctx) {
        // Cada filho Sub é uma substituição paralela
        NodeList children = narySub.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node n = children.item(i);
            if (n.getNodeType() != Node.ELEMENT_NODE) continue;
            Element ch = (Element) n;
            if ("Attr".equals(ch.getLocalName())) continue;
            if ("Assignement_Sub".equals(ch.getLocalName())) {
                parseAssignementSub(ch, ensures, ctx);
            } else if ("Becomes_In".equals(ch.getLocalName())) {
                parseBecomesInSub(ch, ensures, ctx);
            } else if ("Becomes_Such_That".equals(ch.getLocalName())) {
                parseBecomesSuchThatSub(ch, ensures, ctx);
            } else if ("If_Sub".equals(ch.getLocalName())) {
                parseIfSubAsConditionalEnsures(ch, ensures, ctx);
            } else if ("Select".equals(ch.getLocalName())) {
                parseSelectAsConditionalEnsures(ch, ensures, ctx);
            }
        }
    }

    /**
     * {@code IF cond THEN body [ELSE body] END} → cláusulas {@code ensures} condicionais:
     * {@code (cond) ==> (efeito_then)} e, se existir {@code Else},
     * {@code !(cond) ==> (efeito_else)}.
     */
    private static void parseIfSubAsConditionalEnsures(
            Element ifSub, List<String> ensures, BxmlTranslateContext ctx) {
        Element condEl = firstChildElement(ifSub, "Condition");
        Element thenEl = firstChildElement(ifSub, "Then");
        if (condEl == null || thenEl == null) return;

        String cond = BxmlPredicateToAcsl.translateBodyPredicate(condEl, ctx);
        if (cond == null || cond.isBlank()) return;

        List<String> thenEnsures = new ArrayList<>();
        walkSubstitution(firstSubChild(thenEl), thenEnsures, ctx);
        for (String e : thenEnsures) {
            ensures.add("(" + cond + ") ==> (" + e + ")");
        }

        Element elseEl = firstChildElement(ifSub, "Else");
        if (elseEl != null) {
            List<String> elseEnsures = new ArrayList<>();
            walkSubstitution(firstSubChild(elseEl), elseEnsures, ctx);
            for (String e : elseEnsures) {
                ensures.add("!(" + cond + ") ==> (" + e + ")");
            }
        }
    }

    /**
     * {@code SELECT cond1 THEN body1 WHEN cond2 THEN body2 … [ELSE body] END} → cláusulas
     * {@code ensures} sequenciais: o i-ésimo {@code WHEN} só dispara se os anteriores falharam.
     */
    private static void parseSelectAsConditionalEnsures(
            Element select, List<String> ensures, BxmlTranslateContext ctx) {
        Element whenClauses = firstChildElement(select, "When_Clauses");
        List<String> whenConds = new ArrayList<>();
        List<List<String>> whenEffects = new ArrayList<>();

        if (whenClauses != null) {
            NodeList children = whenClauses.getChildNodes();
            for (int i = 0; i < children.getLength(); i++) {
                Node n = children.item(i);
                if (n.getNodeType() != Node.ELEMENT_NODE) continue;
                Element when = (Element) n;
                if (!"When".equals(when.getLocalName())) continue;

                Element condEl = firstChildElement(when, "Condition");
                Element thenEl = firstChildElement(when, "Then");
                if (condEl == null || thenEl == null) continue;

                String cond = BxmlPredicateToAcsl.translateBodyPredicate(condEl, ctx);
                if (cond == null || cond.isBlank()) continue;

                List<String> branchEnsures = new ArrayList<>();
                walkSubstitution(firstSubChild(thenEl), branchEnsures, ctx);
                whenConds.add(cond);
                whenEffects.add(branchEnsures);
            }
        }

        List<String> priorConds = new ArrayList<>();
        for (int i = 0; i < whenConds.size(); i++) {
            String effectiveCond = selectWhenCondition(whenConds.get(i), priorConds);
            for (String e : whenEffects.get(i)) {
                ensures.add("(" + effectiveCond + ") ==> (" + e + ")");
            }
            priorConds.add(whenConds.get(i));
        }

        Element elseEl = firstChildElement(select, "Else");
        if (elseEl != null) {
            List<String> elseEnsures = new ArrayList<>();
            walkSubstitution(firstSubChild(elseEl), elseEnsures, ctx);
            String elseCond = selectElseCondition(whenConds);
            for (String e : elseEnsures) {
                ensures.add("(" + elseCond + ") ==> (" + e + ")");
            }
        }
    }

    /** {@code Ci && !(C1) && … && !(C(i-1))}. */
    private static String selectWhenCondition(String cond, List<String> priorConds) {
        if (priorConds.isEmpty()) {
            return cond;
        }
        StringBuilder sb = new StringBuilder("(").append(cond).append(")");
        for (String prior : priorConds) {
            sb.append(" && !(").append(prior).append(")");
        }
        return sb.toString();
    }

    /** {@code !(C1 || C2 || … || Cn)}; {@code \\true} se não houver ramos {@code WHEN}. */
    private static String selectElseCondition(List<String> whenConds) {
        if (whenConds.isEmpty()) {
            return "\\true";
        }
        if (whenConds.size() == 1) {
            return "!(" + whenConds.get(0) + ")";
        }
        StringBuilder sb = new StringBuilder("!(");
        for (int i = 0; i < whenConds.size(); i++) {
            if (i > 0) {
                sb.append(" || ");
            }
            sb.append("(").append(whenConds.get(i)).append(")");
        }
        sb.append(")");
        return sb.toString();
    }

    /**
     * {@code Becomes_Such_That} (B: {@code v1,…,vn : (P)}) → {@code ensures P} com {@code P} no pós-estado.
     */
    private static void parseBecomesSuchThatSub(
            Element becomesSuch, List<String> ensures, BxmlTranslateContext ctx) {
        Element predEl = firstChildElement(becomesSuch, "Pred");
        if (predEl == null) {
            return;
        }
        String p = BxmlPredicateToAcsl.translateInvariantContent(predEl, ctx);
        if (p != null && !p.isBlank()) {
            ensures.add(p.trim());
        }
    }

    /**
     * {@code Becomes_In} (B: {@code v :: E}) → {@code belongs(v, E)} para cada variável em
     * {@code Variables} (pós-estado da operação / inicialização).
     */
    private static void parseBecomesInSub(Element becomesIn, List<String> ensures, BxmlTranslateContext ctx) {
        Element vars = firstChildElement(becomesIn, "Variables");
        Element value = firstChildElement(becomesIn, "Value");
        if (vars == null || value == null) {
            return;
        }
        String setExpr = null;
        for (Element valExp : directExpChildren(value)) {
            setExpr = BxmlExpressionToAcsl.translate(valExp, ctx);
            break;
        }
        if (setExpr == null || setExpr.isBlank()) {
            return;
        }
        List<String> parts = new ArrayList<>();
        for (Element varExp : directExpChildren(vars)) {
            String v = BxmlExpressionToAcsl.translate(varExp, ctx);
            if (v != null && !v.isBlank()) {
                parts.add("belongs(" + v + ", " + setExpr + ")");
            }
        }
        if (!parts.isEmpty()) {
            ensures.add(String.join(" && ", parts));
        }
    }

    private static void parseAssignementSub(Element assign, List<String> ensures, BxmlTranslateContext ctx) {
        Element vars = firstChildElement(assign, "Variables");
        Element vals = firstChildElement(assign, "Values");
        if (vars == null || vals == null) return;
        List<Element> lhs = directExpChildren(vars);
        List<Element> rhs = directExpChildren(vals);
        int n = Math.min(lhs.size(), rhs.size());
        for (int i = 0; i < n; i++) {
            ensures.add(BxmlExpressionToAcsl.formatEquality(lhs.get(i), rhs.get(i), ctx));
        }
    }

    private static List<Element> directExpChildren(Element parent) {
        List<Element> out = new ArrayList<>();
        NodeList nl = parent.getChildNodes();
        for (int i = 0; i < nl.getLength(); i++) {
            Node n = nl.item(i);
            if (n.getNodeType() != Node.ELEMENT_NODE) continue;
            Element e = (Element) n;
            if ("Attr".equals(e.getLocalName())) continue;
            out.add(e);
        }
        return out;
    }

    private static Element firstSubChild(Element parent) {
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

    /**
     * Texto de contrato no estilo pedido (função + contract + ensures + assigns).
     */
    public record InitialisationAcsl(
            String functionName,
            List<String> ensures,
            List<String> assignsTargets,
            boolean includeGhostBehaviorAssert,
            /**
             * Sufixos de variável abstrata (ex. {@code ss}) para cláusulas {@code ensures dummy_ghost_<v>;}
             * em inicialização não pura face ao modelo ghost.
             */
            List<String> dummyGhostEnsureVarNames,
            /**
             * Expressão ACSL para o número de iterações de loop gerado por um produto cartesiano
             * {@code (a..b) * {v}}. Quando não-nulo, emite {@code at loop 1: loop unfold n;} no contrato.
             */
            String loopUnfoldSize) {

        public InitialisationAcsl {
            dummyGhostEnsureVarNames =
                    dummyGhostEnsureVarNames == null ? List.of() : List.copyOf(dummyGhostEnsureVarNames);
        }

        public String toContractText() {
            StringBuilder sb = new StringBuilder();
            sb.append("function ").append(functionName).append(":\n");
            sb.append("contract:\n");
            for (String e : ensures) {
                sb.append("    ensures  ").append(e).append(";\n");
            }
            for (String v : dummyGhostEnsureVarNames) {
                sb.append("    ensures  dummy_ghost_").append(v).append(";\n");
            }
            if (assignsTargets.isEmpty()) {
                sb.append("    assigns \\nothing;\n");
            } else {
                for (String a : assignsTargets) {
                    sb.append("    assigns ").append(a).append(";\n");
                }
            }
            if (loopUnfoldSize != null && !loopUnfoldSize.isBlank()) {
                sb.append("    at loop 1:\n        loop unfold ").append(loopUnfoldSize).append(";\n");
            }
            if (includeGhostBehaviorAssert) {
                String machinePart = functionName.toLowerCase().replace("__initialisation", "");
                sb.append("    at 1: assert ghost__").append(machinePart).append("__initialisation;\n");
            }
            return sb.toString();
        }
    }
}
