package com.example.bxml;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
        return translate(machineEl, additionalAssignTargets, ctx, knownIntegerConstants, List.of());
    }

    /**
     * @param mergedMachineElements cadeia de refinamentos/implementações — o {@code Initialisation}
     *        da implementação é preferido ao da abstrata, pois o código C deriva dela.
     */
    public static InitialisationAcsl translate(
            Element machineEl, List<String> additionalAssignTargets, BxmlTranslateContext ctx,
            Map<String, Long> knownIntegerConstants, List<Element> mergedMachineElements) {
        String machineName = machineEl.getAttribute("name");

        // Preferir o Initialisation da implementação (código C vem dela), se disponível.
        Element initSource = null;
        Element initOwnerMachine = null;
        if (mergedMachineElements != null) {
            for (Element mel : mergedMachineElements) {
                if ("implementation".equals(mel.getAttribute("type"))) {
                    Element implInit = firstChildElement(mel, "Initialisation");
                    if (implInit != null && firstSubChild(implInit) != null
                            && !"Skip".equals(firstSubChild(implInit).getLocalName())) {
                        initSource = implInit;
                        initOwnerMachine = mel;
                        break;
                    }
                }
            }
        }
        if (initSource == null) {
            initSource = firstChildElement(machineEl, "Initialisation");
            initOwnerMachine = machineEl;
        }

        List<String> ensures = new ArrayList<>();
        if (initSource != null) {
            walkSubstitution(firstSubChild(initSource), ensures, ctx);
        }
        List<CartesianProductLoopSpec> loopSpecs = (initSource != null)
                ? detectAllLoopSpecs(firstSubChild(initSource), initOwnerMachine, ctx)
                : List.of();

        String functionName = machineName + "__INITIALISATION";
        return new InitialisationAcsl(
                functionName, ensures, new ArrayList<>(additionalAssignTargets), false, List.of(), loopSpecs, false);
    }

    /**
     * Descrição de um loop de inicialização gerado por uma atribuição do padrão
     * {@code ARRAY := DOMAIN * {VALUE}} em B — o compilador C emite um loop {@code while(i <= HI)}.
     * O domínio pode ser um intervalo literal ({@code LO..HI}) ou um conjunto nomeado ({@code COPY},
     * {@code BOOK}, etc.) cujos limites são resolvidos via seção {@code <Values>} da implementação.
     */
    public record CartesianProductLoopSpec(
            String counterVar,
            String loExpr,
            String hiExpr,
            String cArrayName,
            String valueExpr) {}

    /** Coleta todas as especificações de loop da sequência de substituições da inicialização. */
    private static List<CartesianProductLoopSpec> detectAllLoopSpecs(
            Element sub, Element implMachineEl, BxmlTranslateContext ctx) {
        if (sub == null) return List.of();
        List<CartesianProductLoopSpec> result = new ArrayList<>();
        collectLoopSpecs(sub, implMachineEl, ctx, result);
        return List.copyOf(result);
    }

    private static void collectLoopSpecs(
            Element sub, Element implMachineEl, BxmlTranslateContext ctx,
            List<CartesianProductLoopSpec> result) {
        if (sub == null) return;
        switch (sub.getLocalName()) {
            case "Assignement_Sub" -> {
                CartesianProductLoopSpec spec = cartesianProductLoopSpecFromAssignment(sub, implMachineEl, ctx);
                if (spec != null) result.add(spec);
            }
            case "Nary_Sub" -> {
                NodeList children = sub.getChildNodes();
                for (int i = 0; i < children.getLength(); i++) {
                    Node n = children.item(i);
                    if (n.getNodeType() != Node.ELEMENT_NODE) continue;
                    Element ch = (Element) n;
                    if ("Attr".equals(ch.getLocalName())) continue;
                    collectLoopSpecs(ch, implMachineEl, ctx, result);
                }
            }
            case "Bloc_Sub" -> collectLoopSpecs(firstSubChild(sub), implMachineEl, ctx, result);
        }
    }

    private static CartesianProductLoopSpec cartesianProductLoopSpecFromAssignment(
            Element assign, Element implMachineEl, BxmlTranslateContext ctx) {
        Element varsEl = firstChildElement(assign, "Variables");
        if (varsEl == null) return null;
        String arrayVarName = null;
        for (Element ch : directExpChildren(varsEl)) {
            if ("Id".equals(ch.getLocalName())) {
                arrayVarName = ch.getAttribute("value");
                break;
            }
        }
        if (arrayVarName == null || arrayVarName.isBlank()) return null;
        String cArrayName = ctx.machineName() + "__" + arrayVarName.trim();

        Element valsEl = firstChildElement(assign, "Values");
        if (valsEl == null) return null;
        for (Element rhs : directExpChildren(valsEl)) {
            CartesianProductLoopSpec spec = cartesianProductLoopSpec(rhs, cArrayName, implMachineEl, ctx);
            if (spec != null) return spec;
        }
        return null;
    }

    /**
     * Tenta extrair uma especificação de loop de uma expressão {@code DOMAIN * {VALUE}}.
     * O domínio pode ser:
     * <ul>
     *   <li>Um intervalo literal: {@code Binary_Exp op='..'} (ex.: {@code 0..9})</li>
     *   <li>Um conjunto nomeado: {@code Id} cujo valor está em {@code <Values>} da implementação</li>
     * </ul>
     */
    private static CartesianProductLoopSpec cartesianProductLoopSpec(
            Element el, String cArrayName, Element implMachineEl, BxmlTranslateContext ctx) {
        if (!"Binary_Exp".equals(el.getLocalName())) return null;
        if (!"*s".equals(el.getAttribute("op"))) return null;
        List<Element> children = directExpChildren(el);
        if (children.size() < 2) return null;
        Element domainEl = children.get(0);
        Element singletonEl = children.get(1);
        if (!"Nary_Exp".equals(singletonEl.getLocalName())) return null;
        if (!"{".equals(singletonEl.getAttribute("op"))) return null;
        List<Element> singletonChildren = directExpChildren(singletonEl);
        if (singletonChildren.size() != 1) return null;

        String lo, hi;
        if (BxmlExpressionToAcsl.isIntervalBinaryExp(domainEl)) {
            // Intervalo literal: Binary_Exp op='..'
            List<Element> bounds = directExpChildren(domainEl);
            if (bounds.size() < 2) return null;
            lo = BxmlExpressionToAcsl.translate(bounds.get(0), ctx);
            hi = BxmlExpressionToAcsl.translate(bounds.get(1), ctx);
        } else if ("Id".equals(domainEl.getLocalName())) {
            // Conjunto nomeado: resolve via <Values> da implementação
            String[] interval = resolveNamedSetInterval(domainEl.getAttribute("value"), implMachineEl, ctx);
            if (interval == null) return null;
            lo = interval[0];
            hi = interval[1];
        } else {
            return null;
        }

        String value = BxmlExpressionToAcsl.translate(singletonChildren.get(0), ctx);
        if (lo == null || lo.isBlank() || hi == null || hi.isBlank() || value == null) return null;
        return new CartesianProductLoopSpec("i", lo.trim(), hi.trim(), cArrayName, value.trim());
    }

    /**
     * Resolve um conjunto nomeado para seu intervalo {@code [lo, hi]} via seção {@code <Values>}
     * da máquina de implementação. Retorna {@code null} se não encontrado ou se a valoração
     * não for um intervalo literal.
     */
    private static String[] resolveNamedSetInterval(
            String setName, Element implMachineEl, BxmlTranslateContext ctx) {
        if (setName == null || setName.isBlank() || implMachineEl == null) return null;
        Element valuesEl = firstChildElement(implMachineEl, "Values");
        if (valuesEl == null) return null;
        NodeList nl = valuesEl.getChildNodes();
        for (int i = 0; i < nl.getLength(); i++) {
            Node n = nl.item(i);
            if (n.getNodeType() != Node.ELEMENT_NODE) continue;
            Element valEl = (Element) n;
            if (!"Valuation".equals(valEl.getLocalName())) continue;
            if (!setName.equals(valEl.getAttribute("ident"))) continue;
            // Primeiro filho não-Attr é a expressão de valor
            for (Element valExpr : directExpChildren(valEl)) {
                if (!BxmlExpressionToAcsl.isIntervalBinaryExp(valExpr)) return null;
                List<Element> bounds = directExpChildren(valExpr);
                if (bounds.size() < 2) return null;
                String lo = BxmlExpressionToAcsl.translate(bounds.get(0), ctx);
                String hi = BxmlExpressionToAcsl.translate(bounds.get(1), ctx);
                if (lo == null || lo.isBlank() || hi == null || hi.isBlank()) return null;
                return new String[]{lo.trim(), hi.trim()};
            }
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
        NodeList children = narySub.getChildNodes();

        // Em substituição paralela (||), todas as RHS usam o pré-estado de TODAS as variáveis LHS.
        Set<String> allLhsNames = new LinkedHashSet<>();
        for (int i = 0; i < children.getLength(); i++) {
            Node n = children.item(i);
            if (n.getNodeType() != Node.ELEMENT_NODE) continue;
            Element ch = (Element) n;
            if ("Attr".equals(ch.getLocalName()) || !"Assignement_Sub".equals(ch.getLocalName())) continue;
            Element vars = firstChildElement(ch, "Variables");
            if (vars == null) continue;
            for (Element l : directExpChildren(vars)) {
                if ("Id".equals(l.getLocalName())) {
                    String v = l.getAttribute("value");
                    if (v != null && !v.isBlank()) {
                        allLhsNames.add(BxmlExpressionToAcsl.translateBNamedConstant(v.trim()));
                    }
                }
            }
        }

        for (int i = 0; i < children.getLength(); i++) {
            Node n = children.item(i);
            if (n.getNodeType() != Node.ELEMENT_NODE) continue;
            Element ch = (Element) n;
            if ("Attr".equals(ch.getLocalName())) continue;
            if ("Assignement_Sub".equals(ch.getLocalName())) {
                parseAssignementSub(ch, ensures, ctx, allLhsNames);
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
        parseAssignementSub(assign, ensures, ctx, Set.of());
    }

    /**
     * @param extraLhsVarNames nomes adicionais de variáveis sendo atribuídas simultaneamente
     *        (substituição paralela {@code ||}) — também precisam de {@code \old} nas RHS.
     */
    private static void parseAssignementSub(
            Element assign, List<String> ensures, BxmlTranslateContext ctx,
            Set<String> extraLhsVarNames) {
        Element vars = firstChildElement(assign, "Variables");
        Element vals = firstChildElement(assign, "Values");
        if (vars == null || vals == null) return;
        List<Element> lhs = directExpChildren(vars);
        List<Element> rhs = directExpChildren(vals);

        // Nomes das variáveis LHS desta atribuição (em ACSL) — a RHS as usa em pré-estado.
        Set<String> lhsNames = new LinkedHashSet<>();
        for (Element l : lhs) {
            if ("Id".equals(l.getLocalName())) {
                String v = l.getAttribute("value");
                if (v != null && !v.isBlank()) {
                    lhsNames.add(BxmlExpressionToAcsl.translateBNamedConstant(v.trim()));
                }
            }
        }
        lhsNames.addAll(extraLhsVarNames);

        int n = Math.min(lhs.size(), rhs.size());
        for (int i = 0; i < n; i++) {
            String e = BxmlExpressionToAcsl.formatEquality(lhs.get(i), rhs.get(i), ctx);
            if (!lhsNames.isEmpty()) {
                e = wrapLhsVarsInRhsWithOld(e, lhsNames);
            }
            ensures.add(e);
        }
    }

    /**
     * Reescreve a RHS de um ensures ({@code l == r} ou {@code equals(l, r)}) substituindo
     * ocorrências de variáveis LHS por {@code \old(v)}, pois em ACSL a RHS usa o pré-estado.
     */
    private static String wrapLhsVarsInRhsWithOld(String ensure, Set<String> lhsVarNames) {
        int eqIdx = ensure.indexOf(" == ");
        if (eqIdx >= 0) {
            String lhsPart = ensure.substring(0, eqIdx);
            String rhsPart = ensure.substring(eqIdx + 4);
            return lhsPart + " == " + applyOldWrapping(rhsPart, lhsVarNames);
        }
        if (ensure.startsWith("equals(")) {
            int comma = topLevelCommaIndex(ensure, 7);
            if (comma >= 0) {
                String lhsPart = ensure.substring(0, comma);
                String rhsPart = ensure.substring(comma + 1, ensure.length() - 1);
                return lhsPart + ", " + applyOldWrapping(rhsPart, lhsVarNames) + ")";
            }
        }
        return ensure;
    }

    private static String applyOldWrapping(String expr, Set<String> varNames) {
        List<String> sorted = new ArrayList<>(varNames);
        sorted.sort((a, b) -> Integer.compare(b.length(), a.length()));
        String out = expr;
        for (String v : sorted) {
            Matcher m = Pattern.compile("\\b" + Pattern.quote(v) + "\\b").matcher(out);
            StringBuffer sb = new StringBuffer();
            while (m.find()) {
                m.appendReplacement(sb, Matcher.quoteReplacement("\\old(" + v + ")"));
            }
            m.appendTail(sb);
            out = sb.toString();
        }
        return out;
    }

    private static int topLevelCommaIndex(String s, int start) {
        int depth = 0;
        for (int i = start; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '(') depth++;
            else if (c == ')') { if (depth == 0) return -1; depth--; }
            else if (c == ',' && depth == 0) return i;
        }
        return -1;
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
             * Especificações de loop gerados por {@code ARRAY := DOMAIN * {VALUE}}; uma entrada por
             * atribuição desse padrão na inicialização — cada uma emite {@code at loop N:} com
             * {@code loop invariant}, {@code loop assigns} e {@code loop variant}.
             */
            List<CartesianProductLoopSpec> loopSpecs,
            /**
             * {@code true} para máquinas que não importam outras máquinas: emite um contrato mínimo
             * com {@code assigns \nothing;} mesmo que não haja outros conteúdos.
             */
            boolean emitMinimalContract) {

        public InitialisationAcsl {
            dummyGhostEnsureVarNames =
                    dummyGhostEnsureVarNames == null ? List.of() : List.copyOf(dummyGhostEnsureVarNames);
            loopSpecs = loopSpecs == null ? List.of() : List.copyOf(loopSpecs);
        }

        /** Compatibilidade com chamadas que ainda usam o campo singular (agora lista). */
        public CartesianProductLoopSpec loopSpec() {
            return loopSpecs.isEmpty() ? null : loopSpecs.get(0);
        }

        public String toContractText() {
            boolean hasContent = !ensures.isEmpty()
                    || !dummyGhostEnsureVarNames.isEmpty()
                    || !assignsTargets.isEmpty()
                    || !loopSpecs.isEmpty()
                    || includeGhostBehaviorAssert;
            if (!hasContent && !emitMinimalContract) return "";
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
            for (int idx = 0; idx < loopSpecs.size(); idx++) {
                CartesianProductLoopSpec ls = loopSpecs.get(idx);
                String lo  = ls.loExpr();
                String hi  = ls.hiExpr();
                String arr = ls.cArrayName();
                String val = ls.valueExpr();
                String v   = ls.counterVar();
                sb.append("    at loop ").append(idx + 1).append(":\n");
                sb.append("        loop invariant ").append(lo).append(" <= ").append(v)
                  .append(" <= ").append(hi).append(" + 1;\n");
                sb.append("        loop invariant \\forall integer k; ").append(lo)
                  .append(" <= k < ").append(v).append(" ==> ").append(arr)
                  .append("[k] == ").append(val).append(";\n");
                sb.append("        loop assigns ").append(v).append(", ")
                  .append(arr).append("[").append(lo).append(" .. ").append(hi).append("];\n");
                sb.append("        loop variant ").append(hi).append(" + 1 - ").append(v).append(";\n");
            }
            if (includeGhostBehaviorAssert) {
                String machinePart = functionName.toLowerCase().replace("__initialisation", "");
                sb.append("    at return: assert ghost__").append(machinePart).append("__initialisation;\n");
            }
            return sb.toString();
        }
    }
}
