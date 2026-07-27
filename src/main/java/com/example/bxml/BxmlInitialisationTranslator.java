package com.example.bxml;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
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
        return translate(
                machineEl, additionalAssignTargets, ctx, knownIntegerConstants,
                mergedMachineElements, null);
    }

    /**
     * @param bxmlDirectory diretório das fontes {@code .bxml} — necessário para resolver o
     *        intervalo de conjuntos nomeados (deferred sets como {@code GOODS}) valorados em
     *        máquinas VISTAS (SEES), usado pela especificação de loop {@code ARRAY := DOMAIN * {V}}
     *        — ver {@link #resolveNamedSetInterval(String, Element, BxmlTranslateContext, Path)}.
     */
    public static InitialisationAcsl translate(
            Element machineEl, List<String> additionalAssignTargets, BxmlTranslateContext ctx,
            Map<String, Long> knownIntegerConstants, List<Element> mergedMachineElements,
            Path bxmlDirectory) {
        String machineName = machineEl.getAttribute("name");

        InitSource src = selectInitialisationSource(machineEl, mergedMachineElements);

        List<String> ensures = new ArrayList<>();
        if (src.initSource() != null) {
            walkSubstitution(firstSubChild(src.initSource()), ensures, ctx);
        }
        List<CartesianProductLoopSpec> loopSpecs = (src.initSource() != null)
                ? detectAllLoopSpecs(
                        firstSubChild(src.initSource()), src.initOwnerMachine(), ctx, bxmlDirectory)
                : List.of();

        String functionName = machineName + "__INITIALISATION";
        return new InitialisationAcsl(
                functionName, ensures, new ArrayList<>(additionalAssignTargets), false, List.of(), loopSpecs, false);
    }

    private record InitSource(Element initSource, Element initOwnerMachine) {}

    /**
     * Seleciona a fonte do {@code <Initialisation>} a traduzir: prefere a da implementação fundida
     * (o código C deriva dela) e só cai para a da máquina abstrata se nenhuma implementação tiver
     * uma substituição própria não-trivial.
     */
    private static InitSource selectInitialisationSource(
            Element machineEl, List<Element> mergedMachineElements) {
        if (mergedMachineElements != null) {
            for (Element mel : mergedMachineElements) {
                if ("implementation".equals(mel.getAttribute("type"))) {
                    Element implInit = firstChildElement(mel, "Initialisation");
                    if (implInit != null && firstSubChild(implInit) != null
                            && !"Skip".equals(firstSubChild(implInit).getLocalName())) {
                        return new InitSource(implInit, mel);
                    }
                }
            }
        }
        return new InitSource(firstChildElement(machineEl, "Initialisation"), machineEl);
    }

    /**
     * Cláusulas {@code ensures} da inicialização para variáveis SEM camada ghost (ex.: colapsadas
     * na implementação — ver {@link BxmlMachineVariables#collapsedIntoImplementationVariableNames}):
     * quando a máquina usa abstração ghost para OUTRAS variáveis, o contrato real da INITIALISATION
     * descarta {@code translate(...).ensures()} por inteiro (fica só a garantia ghost via {@code
     * assert ghost__…}) — o que apagaria também a especificação de variáveis que já não têm
     * rastreio ghost algum.
     *
     * <p>Ao contrário de {@link #translate}, usa sempre o {@code <Initialisation>} da máquina
     * ABSTRATA (nunca o da implementação): a implementação tipicamente REFINA uma escolha
     * não-determinística B ({@code v :: S --> T}) num valor concreto específico ({@code v := S*{c}}),
     * e {@code v := S*{c}} não é a especificação — é só UMA testemunha válida dela. Para uma
     * variável sem camada ghost, o contrato real deve continuar a prometer a ESPECIFICAÇÃO
     * abstrata ({@code is_total_function(v, S, T)}), não o valor concreto de uma implementação
     * particular (que seria sobre-específico e desnecessariamente mais difícil de provar).
     *
     * <p>Filtra a substituição da inicialização abstrata para produzir só as cláusulas cujo LHS
     * esteja em {@code nonGhostVariableNames}, reutilizando os parsers de folha já existentes
     * ({@link #parseAssignementSub}/{@link #parseBecomesInSub}/{@link #parseBecomesSuchThatSub}).
     *
     * <p>Limitação aceite: só cobre folhas diretas de {@code ;}/{@code ||} no nível de topo —
     * {@code If_Sub}/{@code Select}/{@code ANY_Sub} no topo da inicialização não são filtrados
     * (produzem nada aqui, em vez de arriscar duplicar/perder {@code \old} incorretamente); não
     * ocorre em nenhum exemplo atual.
     */
    public static List<String> ensuresForNonGhostVariables(
            Element machineEl, BxmlTranslateContext ctx, Set<String> nonGhostVariableNames) {
        if (nonGhostVariableNames == null || nonGhostVariableNames.isEmpty()) return List.of();
        Element initSource = firstChildElement(machineEl, "Initialisation");
        if (initSource == null) return List.of();
        Element sub = firstSubChild(initSource);
        if (sub == null) return List.of();
        Set<String> allLhsNames = new LinkedHashSet<>();
        collectAssignedLhsNames(sub, allLhsNames);
        List<String> out = new ArrayList<>();
        walkSubstitutionForVariables(sub, out, ctx, nonGhostVariableNames, allLhsNames);
        return out;
    }

    private static void walkSubstitutionForVariables(
            Element sub,
            List<String> ensures,
            BxmlTranslateContext ctx,
            Set<String> keepNames,
            Set<String> oldNames) {
        if (sub == null) return;
        switch (sub.getLocalName()) {
            case "Assignement_Sub" -> {
                if (leafVariableNamesMatch(sub, keepNames)) {
                    parseAssignementSub(sub, ensures, ctx, oldNames);
                }
            }
            case "Becomes_In" -> {
                if (leafVariableNamesMatch(sub, keepNames)) {
                    parseBecomesInSub(sub, ensures, ctx);
                }
            }
            case "Becomes_Such_That" -> {
                if (leafVariableNamesMatch(sub, keepNames)) {
                    parseBecomesSuchThatSub(sub, ensures, ctx);
                }
            }
            case "Nary_Sub" -> {
                String op = sub.getAttribute("op");
                if (";".equals(op) || "||".equals(op)) {
                    NodeList children = sub.getChildNodes();
                    for (int i = 0; i < children.getLength(); i++) {
                        Node n = children.item(i);
                        if (n.getNodeType() != Node.ELEMENT_NODE) continue;
                        Element ch = (Element) n;
                        if ("Attr".equals(ch.getLocalName())) continue;
                        walkSubstitutionForVariables(ch, ensures, ctx, keepNames, oldNames);
                    }
                } else {
                    walkSubstitutionForVariables(firstSubChild(sub), ensures, ctx, keepNames, oldNames);
                }
            }
            case "Bloc_Sub" -> walkSubstitutionForVariables(firstSubChild(sub), ensures, ctx, keepNames, oldNames);
            default -> { /* If_Sub/Select/ANY_Sub/Skip: ver limitação no Javadoc de ensuresForNonGhostVariables */ }
        }
    }

    private static boolean leafVariableNamesMatch(Element leafSub, Set<String> keepNames) {
        Element vars = firstChildElement(leafSub, "Variables");
        if (vars == null) return false;
        for (Element id : directExpChildren(vars)) {
            if (!"Id".equals(id.getLocalName())) continue;
            String v = id.getAttribute("value");
            if (v != null && keepNames.contains(v.trim())) return true;
        }
        return false;
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
            Element sub, Element implMachineEl, BxmlTranslateContext ctx, Path bxmlDirectory) {
        if (sub == null) return List.of();
        List<CartesianProductLoopSpec> result = new ArrayList<>();
        collectLoopSpecs(sub, implMachineEl, ctx, bxmlDirectory, result);
        return List.copyOf(result);
    }

    private static void collectLoopSpecs(
            Element sub, Element implMachineEl, BxmlTranslateContext ctx, Path bxmlDirectory,
            List<CartesianProductLoopSpec> result) {
        if (sub == null) return;
        switch (sub.getLocalName()) {
            case "Assignement_Sub" -> {
                CartesianProductLoopSpec spec =
                        cartesianProductLoopSpecFromAssignment(sub, implMachineEl, ctx, bxmlDirectory);
                if (spec != null) result.add(spec);
            }
            case "Nary_Sub" -> {
                NodeList children = sub.getChildNodes();
                for (int i = 0; i < children.getLength(); i++) {
                    Node n = children.item(i);
                    if (n.getNodeType() != Node.ELEMENT_NODE) continue;
                    Element ch = (Element) n;
                    if ("Attr".equals(ch.getLocalName())) continue;
                    collectLoopSpecs(ch, implMachineEl, ctx, bxmlDirectory, result);
                }
            }
            case "Bloc_Sub" -> collectLoopSpecs(firstSubChild(sub), implMachineEl, ctx, bxmlDirectory, result);
        }
    }

    private static CartesianProductLoopSpec cartesianProductLoopSpecFromAssignment(
            Element assign, Element implMachineEl, BxmlTranslateContext ctx, Path bxmlDirectory) {
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
            CartesianProductLoopSpec spec =
                    cartesianProductLoopSpec(rhs, cArrayName, implMachineEl, ctx, bxmlDirectory);
            if (spec != null) return spec;
        }
        return null;
    }

    /**
     * Tenta extrair uma especificação de loop de uma expressão {@code DOMAIN * {VALUE}}.
     * O domínio pode ser:
     * <ul>
     *   <li>Um intervalo literal: {@code Binary_Exp op='..'} (ex.: {@code 0..9})</li>
     *   <li>Um conjunto nomeado: {@code Id} cujo valor está em {@code <Values>} da implementação,
     *       ou (quando {@code bxmlDirectory} não é nulo) de uma máquina VISTA (SEES) — ver
     *       {@link #resolveNamedSetInterval(String, Element, BxmlTranslateContext, Path)}</li>
     * </ul>
     */
    private static CartesianProductLoopSpec cartesianProductLoopSpec(
            Element el, String cArrayName, Element implMachineEl, BxmlTranslateContext ctx,
            Path bxmlDirectory) {
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
            // Conjunto nomeado: resolve via <Values> da implementação (ou de uma máquina SEES)
            String[] interval =
                    resolveNamedSetInterval(
                            domainEl.getAttribute("value"), implMachineEl, ctx, bxmlDirectory);
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
        return resolveNamedSetInterval(setName, implMachineEl, ctx, null);
    }

    /**
     * Como acima, mas quando o conjunto nomeado não está valorado na própria {@code implMachineEl}
     * (ex. {@code GOODS} visto por {@code Price}/{@code Price_i} mas valorado em {@code Goods_i}),
     * procura também nas máquinas VISTAS (SEES) — mesmo problema e mesma solução de
     * {@code BxmlMachineVariables#lookupSetCardinalityFromValues}/{@code resolveNamedSetDomainRange}.
     * Sem isto, o loop de inicialização ({@code price_i := GOODS*{1}}) não recebe nenhuma
     * especificação ({@code loop invariant}/{@code loop assigns}/{@code loop variant}), e o WP não
     * consegue provar nem o pós-estado nem a terminação (assume "assigns everything").
     */
    private static String[] resolveNamedSetInterval(
            String setName, Element implMachineEl, BxmlTranslateContext ctx, Path bxmlDirectory) {
        if (setName == null || setName.isBlank() || implMachineEl == null) return null;
        String[] local = intervalFromOwnValues(setName, implMachineEl, ctx);
        if (local != null || bxmlDirectory == null) {
            return local;
        }
        for (String seenName : BxmlSetsTranslator.listReferencedMachineNames(implMachineEl)) {
            Element seenEl = BxmlSetsTranslator.findImplementationMachineElement(seenName, bxmlDirectory);
            if (seenEl == null) continue;
            String[] found = intervalFromOwnValues(setName, seenEl, ctx);
            if (found != null) return found;
        }
        return null;
    }

    private static String[] intervalFromOwnValues(
            String setName, Element machineEl, BxmlTranslateContext ctx) {
        Element valuesEl = firstChildElement(machineEl, "Values");
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
        walkSubstitution(sub, ensures, ctx, Set.of());
    }

    /**
     * @param oldNames nomes de variáveis atribuídas em qualquer ramo da substituição paralela
     *        ({@code ||}) que engloba {@code sub} (pode ser vazio fora de {@code ||}) — RHS e
     *        condições de {@code If_Sub}/{@code Select} aninhados também precisam de {@code \old}
     *        nessas variáveis, não só as atribuições diretas de {@code ||}.
     */
    private static void walkSubstitution(
            Element sub, List<String> ensures, BxmlTranslateContext ctx, Set<String> oldNames) {
        if (sub == null) return;
        String ln = sub.getLocalName();
        switch (ln) {
            case "Assignement_Sub" -> parseAssignementSub(sub, ensures, ctx, oldNames);
            case "Nary_Sub" -> {
                String op = sub.getAttribute("op");
                if (";".equals(op)) {
                    NodeList children = sub.getChildNodes();
                    for (int i = 0; i < children.getLength(); i++) {
                        Node n = children.item(i);
                        if (n.getNodeType() != Node.ELEMENT_NODE) continue;
                        Element ch = (Element) n;
                        if ("Attr".equals(ch.getLocalName())) continue;
                        walkSubstitution(ch, ensures, ctx, oldNames);
                    }
                } else if ("||".equals(op)) {
                    // simultâneo: um ensures conjuntivo
                    parseSimultaneous(sub, ensures, ctx, oldNames);
                } else {
                    walkSubstitution(firstSubChild(sub), ensures, ctx, oldNames);
                }
            }
            case "Skip" -> { /* nada */ }
            case "Bloc_Sub" -> walkSubstitution(firstSubChild(sub), ensures, ctx, oldNames);
            case "ANY_Sub" -> {
                String t = translateAnySubAsExists(sub, ctx);
                if (!t.isBlank()) {
                    ensures.add(t);
                }
            }
            case "Becomes_In" -> parseBecomesInSub(sub, ensures, ctx);
            case "Becomes_Such_That" -> parseBecomesSuchThatSub(sub, ensures, ctx);
            case "Select" -> parseSelectAsConditionalEnsures(sub, ensures, ctx, oldNames);
            case "If_Sub" -> parseIfSubAsConditionalEnsures(sub, ensures, ctx, oldNames);
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
     * {@code ANY v WHERE P THEN Q} → {@code \exists T v; P && (tradução de Q)} (uma única cláusula).
     *
     * <p>{@code ANY} escolhe UM valor válido (não "para todo"), e o estado resultante reflete essa
     * escolha — {@code \exists} com conjunção é a tradução fiel; {@code \forall ==>} (forma anterior)
     * era logicamente mais fraco e escondia o mesmo problema descrito abaixo.
     *
     * <p>Quando uma variável de {@code ANY} é definida por uma igualdade no {@code WHERE} (ex.
     * {@code new_Todo = Todo - {chosen}}) e usada como único RHS de uma atribuição direta no
     * {@code THEN} (ex. {@code Todo := new_Todo}), essa variável é eliminada do quantificador: a
     * igualdade do {@code WHERE} não é emitida, a atribuição correspondente traduz a expressão
     * definidora diretamente, e as demais referências à variável auxiliar (se houver) são
     * substituídas pelo nome da variável real (pós-estado) — evita um existencial supérfluo quando
     * ele é só um alias para o novo valor de outra variável, e usa o nome mais natural (a própria
     * variável de estado) nas cláusulas seguintes.
     *
     * <p>Referências no {@code WHERE} a variáveis atribuídas no {@code THEN} são pré-estado (B
     * avalia a guarda antes da substituição) — por isso levam {@code \old(...)}, o que a tradução
     * anterior não fazia.
     *
     * <p>A tradução das atribuições em {@code Then} reusa {@link #walkSubstitution} (mesmas regras
     * que {@code Initialisation} / corpos de operação).
     */
    public static String translateAnySubAsExists(Element anySub, BxmlTranslateContext ctx) {
        if (anySub == null) return "";
        Element vars = firstChildElement(anySub, "Variables");
        Element predEl = firstChildElement(anySub, "Pred");
        Element thenEl = firstChildElement(anySub, "Then");
        if (vars == null || predEl == null || thenEl == null) {
            return "";
        }
        List<Element> varIds = new ArrayList<>();
        for (Element e : directExpChildren(vars)) {
            if ("Id".equals(e.getLocalName())) varIds.add(e);
        }
        if (varIds.isEmpty()) {
            return "";
        }
        Set<String> anyVarNames = new LinkedHashSet<>();
        for (Element vid : varIds) {
            String n = vid.getAttribute("value");
            if (n != null && !n.isBlank()) anyVarNames.add(n.trim());
        }

        List<Element> guardConjuncts = topLevelConjuncts(firstSubChild(predEl));
        Element thenSub = firstSubChild(thenEl);
        List<Element> thenSubs = topLevelParallelSubs(thenSub);

        // auxVar -> (expressão definidora, conjunto do WHERE que a declara) para "auxVar = expr".
        Map<String, Element> definingExpr = new LinkedHashMap<>();
        Map<String, Element> definingConjunct = new LinkedHashMap<>();
        for (Element c : guardConjuncts) {
            if (!"Exp_Comparison".equals(c.getLocalName()) || !"=".equals(c.getAttribute("op"))) {
                continue;
            }
            Element[] pair = BxmlExpressionToAcsl.twoDirectExpChildren(c);
            if (pair[0] == null || pair[1] == null) continue;
            String lhsName = soleIdName(pair[0]);
            String rhsName = soleIdName(pair[1]);
            if (lhsName != null && anyVarNames.contains(lhsName) && !definingExpr.containsKey(lhsName)) {
                definingExpr.put(lhsName, pair[1]);
                definingConjunct.put(lhsName, c);
            } else if (rhsName != null && anyVarNames.contains(rhsName) && !definingExpr.containsKey(rhsName)) {
                definingExpr.put(rhsName, pair[0]);
                definingConjunct.put(rhsName, c);
            }
        }

        // auxVar -> nome da variável real atribuída via "real := auxVar" (alias direto) no THEN.
        Map<String, String> aliasRealName = new LinkedHashMap<>();
        Map<String, Element> aliasAssign = new LinkedHashMap<>();
        for (Element s : thenSubs) {
            if (!"Assignement_Sub".equals(s.getLocalName())) continue;
            Element lhsEl = firstChildElement(s, "Variables");
            Element rhsEl = firstChildElement(s, "Values");
            if (lhsEl == null || rhsEl == null) continue;
            List<Element> lhsIds = directExpChildren(lhsEl);
            List<Element> rhsVals = directExpChildren(rhsEl);
            if (lhsIds.size() != 1 || rhsVals.size() != 1 || !"Id".equals(lhsIds.get(0).getLocalName())) {
                continue;
            }
            String auxName = soleIdName(rhsVals.get(0));
            if (auxName == null || !definingExpr.containsKey(auxName) || aliasRealName.containsKey(auxName)) {
                continue;
            }
            String realName = lhsIds.get(0).getAttribute("value").trim();
            if (realName.isBlank()) continue;
            aliasRealName.put(auxName, realName);
            aliasAssign.put(auxName, s);
        }

        Set<String> eliminated = new LinkedHashSet<>(definingExpr.keySet());
        eliminated.retainAll(aliasRealName.keySet());

        List<String> binders = new ArrayList<>();
        for (Element vid : varIds) {
            String vn = vid.getAttribute("value").trim();
            if (vn.isBlank() || eliminated.contains(vn)) {
                continue;
            }
            String ty = BxmlPredicateToAcsl.acslQuantifierLogicTypeForAnyVariable(predEl, vid, ctx);
            binders.add(ty + " " + vn);
        }
        if (binders.isEmpty()) {
            return "";
        }

        Set<String> assignedRealNames = new LinkedHashSet<>();
        collectAssignedLhsNames(thenSub, assignedRealNames);

        List<String> guardParts = new ArrayList<>();
        for (Element c : guardConjuncts) {
            if (eliminated.stream().anyMatch(v -> c == definingConjunct.get(v))) {
                continue;
            }
            String t = BxmlPredicateToAcsl.translatePropertyPred(c, ctx).trim();
            if (t.isBlank()) continue;
            t = applyOldWrapping(t, assignedRealNames);
            t = renameEliminatedAuxVars(t, eliminated, aliasRealName);
            guardParts.add(t);
        }

        List<String> thenParts = new ArrayList<>();
        for (Element s : thenSubs) {
            String auxHere = null;
            for (String aux : eliminated) {
                if (aliasAssign.get(aux) == s) {
                    auxHere = aux;
                    break;
                }
            }
            if (auxHere != null) {
                // "realVar := auxVar" com auxVar eliminada -> "realVar := <expressão definidora>".
                // Só o RHS (a expressão definidora) é pré-estado; o LHS é a própria variável real
                // sendo definida (pós-estado) e não pode levar \old — por isso wrapLhsVarsInRhsWithOld
                // (que separa LHS/RHS) em vez de applyOldWrapping (que embrulharia os dois lados).
                Element realIdEl = directExpChildren(firstChildElement(s, "Variables")).get(0);
                String eq = BxmlExpressionToAcsl.formatEquality(realIdEl, definingExpr.get(auxHere), ctx);
                thenParts.add(wrapLhsVarsInRhsWithOld(eq, assignedRealNames));
                continue;
            }
            List<String> tmp = new ArrayList<>();
            walkSubstitution(s, tmp, ctx, assignedRealNames);
            for (String t : tmp) {
                thenParts.add(renameEliminatedAuxVars(t, eliminated, aliasRealName));
            }
        }

        // Cada parte (guarda ou then) pode já ser uma implicação inteira "(cond) ==> (concl)" (ex.:
        // vinda de parseIfSubAsConditionalEnsures, quando o THEN do ANY tem um If_Sub com ambos os
        // ramos). Em ACSL "&&" liga mais forte que "==>": sem parênteses à VOLTA de CADA implicação
        // completa antes do join, "A ==> B && C ==> D" (2 implicações concatenadas) analisa como
        // "(A && B) ==> (C ==> D)... " (associação errada, à direita) em vez de "(A==>B) && (C==>D)"
        // — corrompendo silenciosamente o significado (sem erro de parse) sempre que o THEN do ANY
        // tiver mais de uma cláusula condicional (ex.: If_Sub com Then E Else).
        String guardText =
                guardParts.isEmpty() ? "\\true" : String.join(" && ", parenthesizeEach(guardParts));
        String thenText =
                thenParts.isEmpty() ? "\\true" : String.join(" && ", parenthesizeEach(thenParts));
        return "\\exists " + String.join(", ", binders) + "; " + guardText + " && " + thenText;
    }

    private static List<String> parenthesizeEach(List<String> parts) {
        List<String> out = new ArrayList<>(parts.size());
        for (String p : parts) {
            out.add("(" + p + ")");
        }
        return out;
    }

    private static String soleIdName(Element e) {
        return e != null && "Id".equals(e.getLocalName()) ? e.getAttribute("value").trim() : null;
    }

    /** Filhos diretos de um {@code Nary_Pred op='&'}, ou {@code [predRoot]} se não for uma conjunção. */
    private static List<Element> topLevelConjuncts(Element predRoot) {
        List<Element> out = new ArrayList<>();
        if (predRoot == null) return out;
        if ("Nary_Pred".equals(predRoot.getLocalName()) && "&".equals(predRoot.getAttribute("op"))) {
            out.addAll(directExpChildren(predRoot));
        } else {
            out.add(predRoot);
        }
        return out;
    }

    /** Filhos diretos de um {@code Nary_Sub op='||'}, ou {@code [thenSub]} se não for paralela. */
    private static List<Element> topLevelParallelSubs(Element thenSub) {
        List<Element> out = new ArrayList<>();
        if (thenSub == null) return out;
        if ("Nary_Sub".equals(thenSub.getLocalName()) && "||".equals(thenSub.getAttribute("op"))) {
            out.addAll(directExpChildren(thenSub));
        } else {
            out.add(thenSub);
        }
        return out;
    }

    /** Troca (fronteira de palavra) cada variável eliminada pelo nome da variável real correspondente. */
    private static String renameEliminatedAuxVars(
            String text, Set<String> eliminated, Map<String, String> aliasRealName) {
        String out = text;
        for (String aux : eliminated) {
            String real = aliasRealName.get(aux);
            if (real == null) continue;
            out =
                    out.replaceAll(
                            "(?<![A-Za-z0-9_])" + Pattern.quote(aux) + "(?![A-Za-z0-9_])",
                            Matcher.quoteReplacement(real));
        }
        return out;
    }

    private static void parseSimultaneous(
            Element narySub, List<String> ensures, BxmlTranslateContext ctx, Set<String> outerOldNames) {
        NodeList children = narySub.getChildNodes();

        // Em substituição paralela (||), todas as RHS — e as condições/ramos de If_Sub/Select
        // aninhados, mesmo dentro de Then/Else — usam o pré-estado de TODAS as variáveis
        // atribuídas em qualquer ramo deste grupo (não só as de Assignement_Sub diretos).
        Set<String> allLhsNames = new LinkedHashSet<>(outerOldNames);
        for (int i = 0; i < children.getLength(); i++) {
            Node n = children.item(i);
            if (n.getNodeType() != Node.ELEMENT_NODE) continue;
            Element ch = (Element) n;
            if ("Attr".equals(ch.getLocalName())) continue;
            collectAssignedLhsNames(ch, allLhsNames);
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
                parseIfSubAsConditionalEnsures(ch, ensures, ctx, allLhsNames);
            } else if ("Select".equals(ch.getLocalName())) {
                parseSelectAsConditionalEnsures(ch, ensures, ctx, allLhsNames);
            }
        }
    }

    /**
     * Recolhe recursivamente os nomes de variáveis atribuídas em toda a árvore de substituição
     * (LHS de {@code Assignement_Sub}, {@code Variables} de {@code Becomes_Such_That}/{@code
     * Becomes_In}), incluindo dentro de ramos {@code Then}/{@code Else}/{@code When} — para que uma
     * {@code ||} que engloba um {@code If_Sub}/{@code Select} saiba de TODAS as variáveis
     * simultaneamente atribuídas, mesmo as só atribuídas num dos ramos condicionais.
     */
    private static void collectAssignedLhsNames(Element sub, Set<String> out) {
        if (sub == null) return;
        switch (sub.getLocalName()) {
            case "Assignement_Sub", "Becomes_Such_That", "Becomes_In" -> {
                Element vars = firstChildElement(sub, "Variables");
                if (vars == null) return;
                for (Element l : directExpChildren(vars)) {
                    if ("Id".equals(l.getLocalName())) {
                        String v = l.getAttribute("value");
                        if (v != null && !v.isBlank()) {
                            out.add(BxmlExpressionToAcsl.translateBNamedConstant(v.trim()));
                        }
                    } else {
                        String fname = functionOverrideTargetName(l);
                        if (fname != null) {
                            out.add(fname);
                        }
                    }
                }
            }
            case "Nary_Sub", "Bloc_Sub" -> {
                NodeList nl = sub.getChildNodes();
                for (int i = 0; i < nl.getLength(); i++) {
                    Node n = nl.item(i);
                    if (n.getNodeType() != Node.ELEMENT_NODE) continue;
                    Element ch = (Element) n;
                    if ("Attr".equals(ch.getLocalName())) continue;
                    collectAssignedLhsNames(ch, out);
                }
            }
            case "If_Sub" -> {
                Element thenEl = firstChildElement(sub, "Then");
                if (thenEl != null) collectAssignedLhsNames(firstSubChild(thenEl), out);
                Element elseEl = firstChildElement(sub, "Else");
                if (elseEl != null) collectAssignedLhsNames(firstSubChild(elseEl), out);
            }
            case "Select" -> {
                Element whenClauses = firstChildElement(sub, "When_Clauses");
                if (whenClauses != null) {
                    NodeList nl = whenClauses.getChildNodes();
                    for (int i = 0; i < nl.getLength(); i++) {
                        Node n = nl.item(i);
                        if (n.getNodeType() != Node.ELEMENT_NODE) continue;
                        Element when = (Element) n;
                        if (!"When".equals(when.getLocalName())) continue;
                        Element thenEl = firstChildElement(when, "Then");
                        if (thenEl != null) collectAssignedLhsNames(firstSubChild(thenEl), out);
                    }
                }
                Element elseEl = firstChildElement(sub, "Else");
                if (elseEl != null) collectAssignedLhsNames(firstSubChild(elseEl), out);
            }
            default -> { /* Skip, ANY_Sub, etc. — nada a coletar */ }
        }
    }

    /**
     * {@code IF cond THEN body [ELSE body] END} → cláusulas {@code ensures} condicionais:
     * {@code (cond) ==> (efeito_then)} e, se existir {@code Else},
     * {@code !(cond) ==> (efeito_else)}.
     *
     * @param oldNames variáveis atribuídas simultaneamente noutro ramo da {@code ||} que engloba
     *        este {@code If_Sub} (vazio fora de {@code ||}) — a condição e os efeitos herdam o
     *        mesmo pré-estado para essas variáveis.
     */
    private static void parseIfSubAsConditionalEnsures(
            Element ifSub, List<String> ensures, BxmlTranslateContext ctx, Set<String> oldNames) {
        Element condEl = firstChildElement(ifSub, "Condition");
        Element thenEl = firstChildElement(ifSub, "Then");
        if (condEl == null || thenEl == null) return;

        String cond = BxmlPredicateToAcsl.translateBodyPredicate(condEl, ctx);
        if (cond == null || cond.isBlank()) return;
        if (!oldNames.isEmpty()) {
            cond = applyOldWrapping(cond, oldNames);
        }

        List<String> thenEnsures = new ArrayList<>();
        walkSubstitution(firstSubChild(thenEl), thenEnsures, ctx, oldNames);
        for (String e : thenEnsures) {
            ensures.add("(" + cond + ") ==> (" + e + ")");
        }

        Element elseEl = firstChildElement(ifSub, "Else");
        if (elseEl != null) {
            List<String> elseEnsures = new ArrayList<>();
            walkSubstitution(firstSubChild(elseEl), elseEnsures, ctx, oldNames);
            for (String e : elseEnsures) {
                ensures.add("!(" + cond + ") ==> (" + e + ")");
            }
        }
    }

    /**
     * {@code SELECT cond1 THEN body1 WHEN cond2 THEN body2 … [ELSE body] END} → cláusulas
     * {@code ensures} sequenciais: o i-ésimo {@code WHEN} só dispara se os anteriores falharam.
     *
     * @param oldNames variáveis atribuídas simultaneamente noutro ramo da {@code ||} que engloba
     *        este {@code Select} (vazio fora de {@code ||}).
     */
    private static void parseSelectAsConditionalEnsures(
            Element select, List<String> ensures, BxmlTranslateContext ctx, Set<String> oldNames) {
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
                if (!oldNames.isEmpty()) {
                    cond = applyOldWrapping(cond, oldNames);
                }

                List<String> branchEnsures = new ArrayList<>();
                walkSubstitution(firstSubChild(thenEl), branchEnsures, ctx, oldNames);
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
            walkSubstitution(firstSubChild(elseEl), elseEnsures, ctx, oldNames);
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
        Element setExp = null;
        for (Element valExp : directExpChildren(value)) {
            setExp = valExp;
            break;
        }
        if (setExp == null) {
            return;
        }
        List<String> parts = new ArrayList<>();
        for (Element varExp : directExpChildren(vars)) {
            String v = BxmlExpressionToAcsl.translate(varExp, ctx);
            if (v == null || v.isBlank()) continue;
            String part = becomesInMembershipEnsure(v, setExp, ctx);
            if (part != null && !part.isBlank()) {
                parts.add(part);
            }
        }
        if (!parts.isEmpty()) {
            ensures.add(String.join(" && ", parts));
        }
    }

    /**
     * {@code v :: S} — traduz o operador B {@code ::} (Becomes_In) para o predicado da B2ACSLLib
     * {@code becomes_element_of}, que tem duas sobrecargas conforme o tipo de {@code v}:
     * <ul>
     *   <li>{@code S} é o tipo função total B ({@code S --> T} / {@code S -->> T}): {@code v} é
     *       função — usa {@code becomes_element_of(v, dom, rng)}
     *       ({@code function_functions/becomes_element_of.acsl}); {@code -->} não existe como
     *       operador ACSL, então traduzir literalmente (como o tradutor genérico de expressões
     *       faria) produz sintaxe inválida. {@code -->>} (sobrejeção total) acrescenta
     *       {@code is_surjective(v, rng)}.</li>
     *   <li>{@code S} é uma expressão de conjunto literal: {@code v} é elemento simples — usa
     *       {@code becomes_element_of(v, S)} ({@code set_functions/becomes_element_of.acsl}).</li>
     * </ul>
     * Espelha {@code GhostOperationsCiGenerator#ghostMembershipEnsure} para o universo NÃO-ghost —
     * a mesma lacuna que aquele método já resolvia do lado ghost nunca tinha sido replicada aqui.
     */
    private static String becomesInMembershipEnsure(String v, Element setExp, BxmlTranslateContext ctx) {
        if (BxmlExpressionToAcsl.isFunctionArrowType(setExp)
                || BxmlExpressionToAcsl.isTotalSurjectionArrowType(setExp)) {
            Element[] domRng = BxmlExpressionToAcsl.twoDirectExpChildren(setExp);
            if (domRng[0] == null || domRng[1] == null) return null;
            String domainSet = BxmlExpressionToAcsl.intervalOrSetComprehensionRef(domRng[0], ctx);
            String rangeSet = BxmlExpressionToAcsl.translate(domRng[1], ctx);
            String base = "becomes_element_of(" + v + ", " + domainSet + ", " + rangeSet + ")";
            if (BxmlExpressionToAcsl.isTotalSurjectionArrowType(setExp)) {
                return base + " && is_surjective(" + v + ", " + rangeSet + ")";
            }
            return base;
        }
        String setExprText = BxmlExpressionToAcsl.translate(setExp, ctx);
        if (setExprText == null || setExprText.isBlank()) return null;
        return "becomes_element_of(" + v + ", " + setExprText + ")";
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
            } else {
                String fname = functionOverrideTargetName(l);
                if (fname != null) {
                    lhsNames.add(fname);
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
     * Nome da função sendo sobrescrita num LHS de açúcar de atribuição {@code f(x) := y} (BXML
     * {@code Binary_Exp op="("} cujo primeiro filho é o {@code Id} de {@code f}) — usado para
     * incluir {@code f} no conjunto de nomes LHS que precisam de {@code \old(...)} no RHS
     * (ver {@link BxmlExpressionToAcsl#formatEquality}, que traduz esta forma para {@code
     * equals(f, overwrite(f, singleton(couple(x, y))))}). {@code null} se {@code exp} não tiver
     * esta forma.
     */
    private static String functionOverrideTargetName(Element exp) {
        if (exp == null || !"Binary_Exp".equals(exp.getLocalName())) {
            return null;
        }
        if (!"(".equals(exp.getAttribute("op").trim())) {
            return null;
        }
        List<Element> children = directExpChildren(exp);
        if (children.isEmpty()) {
            return null;
        }
        Element f = children.get(0);
        if (!"Id".equals(f.getLocalName())) {
            return null;
        }
        String v = f.getAttribute("value");
        if (v == null || v.isBlank()) {
            return null;
        }
        return BxmlExpressionToAcsl.translateBNamedConstant(v.trim());
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
