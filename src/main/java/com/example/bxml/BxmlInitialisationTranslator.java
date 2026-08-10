package com.example.bxml;

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

import com.example.bxml.CartesianProductLoopSpecDetector.CartesianProductLoopSpec;

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
        Element sub = BxmlDomUtils.firstSubChild(body);
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
     *        — ver {@link CartesianProductLoopSpecDetector#resolveNamedSetInterval(String, Element, BxmlTranslateContext, Path)}.
     */
    public static InitialisationAcsl translate(
            Element machineEl, List<String> additionalAssignTargets, BxmlTranslateContext ctx,
            Map<String, Long> knownIntegerConstants, List<Element> mergedMachineElements,
            Path bxmlDirectory) {
        String machineName = machineEl.getAttribute("name");

        InitSource src = selectInitialisationSource(machineEl, mergedMachineElements);

        List<String> ensures = new ArrayList<>();
        if (src.initSource() != null) {
            walkSubstitution(BxmlDomUtils.firstSubChild(src.initSource()), ensures, ctx);
        }
        List<CartesianProductLoopSpec> loopSpecs = (src.initSource() != null)
                ? CartesianProductLoopSpecDetector.detectAllLoopSpecs(
                        BxmlDomUtils.firstSubChild(src.initSource()), src.initOwnerMachine(), ctx, bxmlDirectory)
                : List.of();
        // WHILE explícito (com INVARIANT/VARIANT do usuário) na inicialização — ex.: um array
        // preenchido por um laço escrito à mão em vez do açúcar `ARRAY := DOMAIN*{VALUE}` acima.
        // Só verificado quando não há loopSpecs: os dois padrões nunca coincidem no mesmo nó (um é
        // Assignement_Sub, o outro é While), e nenhum exemplo atual mistura os dois numa mesma
        // Initialisation — ver BxmlLoopTranslator#translateLoopsFromSubstitution.
        List<BxmlLoopTranslator.LoopContract> explicitLoops =
                (src.initSource() != null && loopSpecs.isEmpty())
                        ? BxmlLoopTranslator.translateLoopsFromSubstitution(
                                BxmlDomUtils.firstSubChild(src.initSource()), ctx, src.initOwnerMachine(), null,
                                bxmlDirectory)
                        : List.of();

        String functionName = machineName + "__INITIALISATION";
        return new InitialisationAcsl(
                functionName, ensures, new ArrayList<>(additionalAssignTargets), false, List.of(), loopSpecs,
                explicitLoops, false);
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
                    Element implInit = BxmlDomUtils.firstChildElement(mel, "Initialisation");
                    if (implInit != null && BxmlDomUtils.firstSubChild(implInit) != null
                            && !"Skip".equals(BxmlDomUtils.firstSubChild(implInit).getLocalName())) {
                        return new InitSource(implInit, mel);
                    }
                }
            }
        }
        return new InitSource(BxmlDomUtils.firstChildElement(machineEl, "Initialisation"), machineEl);
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
        Element initSource = BxmlDomUtils.firstChildElement(machineEl, "Initialisation");
        if (initSource == null) return List.of();
        Element sub = BxmlDomUtils.firstSubChild(initSource);
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
                    walkSubstitutionForVariables(BxmlDomUtils.firstSubChild(sub), ensures, ctx, keepNames, oldNames);
                }
            }
            case "Bloc_Sub" -> walkSubstitutionForVariables(BxmlDomUtils.firstSubChild(sub), ensures, ctx, keepNames, oldNames);
            default -> { /* If_Sub/Select/ANY_Sub/Skip: ver limitação no Javadoc de ensuresForNonGhostVariables */ }
        }
    }

    private static boolean leafVariableNamesMatch(Element leafSub, Set<String> keepNames) {
        Element vars = BxmlDomUtils.firstChildElement(leafSub, "Variables");
        if (vars == null) return false;
        for (Element id : BxmlDomUtils.directExpChildren(vars)) {
            if (!"Id".equals(id.getLocalName())) continue;
            String v = id.getAttribute("value");
            if (v != null && keepNames.contains(v.trim())) return true;
        }
        return false;
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
                    walkSubstitution(BxmlDomUtils.firstSubChild(sub), ensures, ctx, oldNames);
                }
            }
            case "Skip" -> { /* nada */ }
            case "Bloc_Sub" -> walkSubstitution(BxmlDomUtils.firstSubChild(sub), ensures, ctx, oldNames);
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
        Element sub = BxmlDomUtils.firstSubChild(body);
        while (sub != null && "Bloc_Sub".equals(sub.getLocalName())) {
            sub = BxmlDomUtils.firstSubChild(sub);
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
    /**
     * Análise de aliases de um {@code ANY_Sub}: quais variáveis ligadas ({@code WHERE aux = expr})
     * são alias PURO de uma variável REAL, via {@code real := aux} direto no {@code THEN} —
     * extraído de {@link #translateAnySubAsExists} (que consome {@code eliminated()} para omitir
     * binders desnecessários do {@code \exists} gerado) para também ser reutilizado por {@link
     * BxmlComprehensionRegistry}: uma compreensão referenciando o alias diretamente (ex. {@code ih}
     * em {@code ih(ii)}, dentro de {@code {ii|ii:player_islands(pp) & ih(ii)<=0}} em
     * RulerOfTheSeas's {@code InvestOnResources}) precisa ver a variável REAL ({@code
     * island_happiness}) como sua livre — não o alias, transitório e sem sentido fora do escopo do
     * próprio {@code ANY} (nem findável como uma variável de máquina, o que rejeitava o registo da
     * compreensão por tipo de binder não-simples).
     */
    record AnySubAliasInfo(
            List<Element> varIds,
            Set<String> anyVarNames,
            List<Element> guardConjuncts,
            List<Element> thenSubs,
            Map<String, Element> definingExpr,
            Map<String, Element> definingConjunct,
            Map<String, String> aliasRealName,
            Map<String, Element> aliasAssign,
            Set<String> eliminated) {

        /** {@code aux -> real} só para as variáveis efetivamente eliminadas (ver {@link #eliminated}). */
        Map<String, String> eliminatedAliasToRealNames() {
            Map<String, String> out = new LinkedHashMap<>();
            for (String alias : eliminated) {
                out.put(alias, aliasRealName.get(alias));
            }
            return out;
        }
    }

    /** {@code null} se {@code anySub} não tiver a forma esperada ({@code Variables}/{@code Pred}/{@code Then}). */
    static AnySubAliasInfo analyzeAnySubAliases(Element anySub) {
        if (anySub == null) return null;
        Element vars = BxmlDomUtils.firstChildElement(anySub, "Variables");
        Element predEl = BxmlDomUtils.firstChildElement(anySub, "Pred");
        Element thenEl = BxmlDomUtils.firstChildElement(anySub, "Then");
        if (vars == null || predEl == null || thenEl == null) {
            return null;
        }
        List<Element> varIds = new ArrayList<>();
        for (Element e : BxmlDomUtils.directExpChildren(vars)) {
            if ("Id".equals(e.getLocalName())) varIds.add(e);
        }
        if (varIds.isEmpty()) {
            return null;
        }
        Set<String> anyVarNames = new LinkedHashSet<>();
        for (Element vid : varIds) {
            String n = vid.getAttribute("value");
            if (n != null && !n.isBlank()) anyVarNames.add(n.trim());
        }

        List<Element> guardConjuncts = topLevelConjuncts(BxmlDomUtils.firstSubChild(predEl));
        Element thenSub = BxmlDomUtils.firstSubChild(thenEl);
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
            Element lhsEl = BxmlDomUtils.firstChildElement(s, "Variables");
            Element rhsEl = BxmlDomUtils.firstChildElement(s, "Values");
            if (lhsEl == null || rhsEl == null) continue;
            List<Element> lhsIds = BxmlDomUtils.directExpChildren(lhsEl);
            List<Element> rhsVals = BxmlDomUtils.directExpChildren(rhsEl);
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

        return new AnySubAliasInfo(
                varIds, anyVarNames, guardConjuncts, thenSubs, definingExpr, definingConjunct,
                aliasRealName, aliasAssign, eliminated);
    }

    public static String translateAnySubAsExists(Element anySub, BxmlTranslateContext ctx) {
        if (anySub == null) return "";
        AnySubAliasInfo info = analyzeAnySubAliases(anySub);
        if (info == null) return "";
        Element predEl = BxmlDomUtils.firstChildElement(anySub, "Pred");
        Element thenSub = BxmlDomUtils.firstSubChild(BxmlDomUtils.firstChildElement(anySub, "Then"));
        List<Element> varIds = info.varIds();
        List<Element> guardConjuncts = info.guardConjuncts();
        List<Element> thenSubs = info.thenSubs();
        Map<String, Element> definingExpr = info.definingExpr();
        Map<String, Element> definingConjunct = info.definingConjunct();
        Map<String, String> aliasRealName = info.aliasRealName();
        Map<String, Element> aliasAssign = info.aliasAssign();
        Set<String> eliminated = info.eliminated();

        // NÃO retorna cedo se binders ficar vazio: um ANY cuja(s) ÚNICA(S) variável(is) ligada(s)
        // é/são eliminada(s) (ex.: "ANY ih WHERE ih = island_happiness <+ %ii.(...) THEN
        // island_happiness := ih || ... END", RulerOfTheSeas's InvestOnResources/InvestOnHappiness)
        // ainda tem conteúdo genuíno a traduzir — a própria atribuição-alias vira
        // "island_happiness == <+ expressão>" dentro de thenParts abaixo (ver o ramo
        // "auxHere != null"), mais quaisquer OUTRAS atribuições paralelas no mesmo THEN. Retornar
        // "" aqui (como uma versão anterior fazia, para evitar o "\exists ;" sintaticamente inválido
        // de binders vazio) descartava esse conteúdo inteiro: o chamador
        // (GhostOperationsCiGenerator#buildGhostOperations) trata "" como "nada a traduzir" e
        // SILENCIOSAMENTE pula a operação toda — sem erro, sem log — daí InvestOnResources/
        // InvestOnHappiness nunca ganharem operação ghost nenhuma em ghost_operations.ci, apesar de
        // claramente mutarem várias variáveis abstratas. AttackPlayer nunca expôs isto porque as
        // suas próprias variáveis ligadas do ANY (attacker_happiness/victim_happiness, via SIGMA) só
        // aparecem em COMPARAÇÕES no THEN, nunca como alvo de "realVar := auxVar", logo nunca são
        // "eliminadas" e sempre sobra pelo menos um binder. Ver o retorno no fim desta função:
        // omite só o PREFIXO "\exists binders;" quando binders está vazio, preservando
        // guardText/thenText.
        List<String> binders = new ArrayList<>();
        for (Element vid : varIds) {
            String vn = vid.getAttribute("value").trim();
            if (vn.isBlank() || eliminated.contains(vn)) {
                continue;
            }
            String ty = BxmlPredicateToAcsl.acslQuantifierLogicTypeForAnyVariable(predEl, vid, ctx);
            binders.add(ty + " " + vn);
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
                Element realIdEl = BxmlDomUtils.directExpChildren(BxmlDomUtils.firstChildElement(s, "Variables")).get(0);
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
        if (binders.isEmpty()) {
            // Todas as variáveis do ANY foram eliminadas como alias puro — nada resta para
            // quantificar existencialmente, "\exists ;" seria sintaxe ACSL inválida. O conteúdo
            // (guarda + corpo, já com os aliases substituídos pela variável real) é uma conjunção
            // comum, não mais existencial.
            return guardText + " && " + thenText;
        }
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
            out.addAll(BxmlDomUtils.directExpChildren(predRoot));
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
            out.addAll(BxmlDomUtils.directExpChildren(thenSub));
        } else {
            out.add(thenSub);
        }
        return out;
    }

    /**
     * Troca (fronteira de palavra) cada variável eliminada pelo nome da variável real
     * correspondente. Visibilidade de pacote: também usada por {@code
     * BxmlComprehensionRegistry#appendComprehensionAxiom} para substituir, no CORPO da própria
     * compreensão (ex. {@code ih(ii)} -> {@code function_apply(island_happiness,ii)}), o mesmo
     * alias que {@code freeVarsForComprehension} já exclui da sua lista de parâmetros livres — sem
     * isto o corpo ainda referenciaria {@code ih} cru, sem declaração nenhuma no axioma gerado
     * ("unbound logic variable").
     */
    static String renameEliminatedAuxVars(
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
            case "Assignement_Sub", "Becomes_Such_That", "Becomes_In" ->
                    collectAssignmentLhsNamesForSub(sub, out);
            case "Nary_Sub", "Bloc_Sub" -> collectChildSubAssignedLhsNames(sub, out);
            case "If_Sub" -> collectIfSubAssignedLhsNames(sub, out);
            case "Select" -> collectSelectAssignedLhsNames(sub, out);
            default -> { /* Skip, ANY_Sub, etc. — nada a coletar */ }
        }
    }

    private static void collectAssignmentLhsNamesForSub(Element sub, Set<String> out) {
        Element vars = BxmlDomUtils.firstChildElement(sub, "Variables");
        if (vars == null) return;
        for (Element l : BxmlDomUtils.directExpChildren(vars)) {
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

    private static void collectChildSubAssignedLhsNames(Element sub, Set<String> out) {
        NodeList nl = sub.getChildNodes();
        for (int i = 0; i < nl.getLength(); i++) {
            Node n = nl.item(i);
            if (n.getNodeType() != Node.ELEMENT_NODE) continue;
            Element ch = (Element) n;
            if ("Attr".equals(ch.getLocalName())) continue;
            collectAssignedLhsNames(ch, out);
        }
    }

    private static void collectIfSubAssignedLhsNames(Element sub, Set<String> out) {
        Element thenEl = BxmlDomUtils.firstChildElement(sub, "Then");
        if (thenEl != null) collectAssignedLhsNames(BxmlDomUtils.firstSubChild(thenEl), out);
        Element elseEl = BxmlDomUtils.firstChildElement(sub, "Else");
        if (elseEl != null) collectAssignedLhsNames(BxmlDomUtils.firstSubChild(elseEl), out);
    }

    private static void collectSelectAssignedLhsNames(Element sub, Set<String> out) {
        Element whenClauses = BxmlDomUtils.firstChildElement(sub, "When_Clauses");
        if (whenClauses != null) {
            NodeList nl = whenClauses.getChildNodes();
            for (int i = 0; i < nl.getLength(); i++) {
                Node n = nl.item(i);
                if (n.getNodeType() != Node.ELEMENT_NODE) continue;
                Element when = (Element) n;
                if (!"When".equals(when.getLocalName())) continue;
                Element thenEl = BxmlDomUtils.firstChildElement(when, "Then");
                if (thenEl != null) collectAssignedLhsNames(BxmlDomUtils.firstSubChild(thenEl), out);
            }
        }
        Element elseEl = BxmlDomUtils.firstChildElement(sub, "Else");
        if (elseEl != null) collectAssignedLhsNames(BxmlDomUtils.firstSubChild(elseEl), out);
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
        Element condEl = BxmlDomUtils.firstChildElement(ifSub, "Condition");
        Element thenEl = BxmlDomUtils.firstChildElement(ifSub, "Then");
        if (condEl == null || thenEl == null) return;

        String cond = BxmlPredicateToAcsl.translateBodyPredicate(condEl, ctx);
        if (cond == null || cond.isBlank()) return;
        if (!oldNames.isEmpty()) {
            cond = applyOldWrapping(cond, oldNames);
        }

        List<String> thenEnsures = new ArrayList<>();
        walkSubstitution(BxmlDomUtils.firstSubChild(thenEl), thenEnsures, ctx, oldNames);
        for (String e : thenEnsures) {
            ensures.add("(" + cond + ") ==> (" + e + ")");
        }

        Element elseEl = BxmlDomUtils.firstChildElement(ifSub, "Else");
        if (elseEl != null) {
            List<String> elseEnsures = new ArrayList<>();
            walkSubstitution(BxmlDomUtils.firstSubChild(elseEl), elseEnsures, ctx, oldNames);
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
        Element whenClauses = BxmlDomUtils.firstChildElement(select, "When_Clauses");
        List<String> whenConds = new ArrayList<>();
        List<List<String>> whenEffects = new ArrayList<>();

        if (whenClauses != null) {
            NodeList children = whenClauses.getChildNodes();
            for (int i = 0; i < children.getLength(); i++) {
                Node n = children.item(i);
                if (n.getNodeType() != Node.ELEMENT_NODE) continue;
                Element when = (Element) n;
                if (!"When".equals(when.getLocalName())) continue;

                Element condEl = BxmlDomUtils.firstChildElement(when, "Condition");
                Element thenEl = BxmlDomUtils.firstChildElement(when, "Then");
                if (condEl == null || thenEl == null) continue;

                String cond = BxmlPredicateToAcsl.translateBodyPredicate(condEl, ctx);
                if (cond == null || cond.isBlank()) continue;
                if (!oldNames.isEmpty()) {
                    cond = applyOldWrapping(cond, oldNames);
                }

                List<String> branchEnsures = new ArrayList<>();
                walkSubstitution(BxmlDomUtils.firstSubChild(thenEl), branchEnsures, ctx, oldNames);
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

        Element elseEl = BxmlDomUtils.firstChildElement(select, "Else");
        if (elseEl != null) {
            List<String> elseEnsures = new ArrayList<>();
            walkSubstitution(BxmlDomUtils.firstSubChild(elseEl), elseEnsures, ctx, oldNames);
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
        Element predEl = BxmlDomUtils.firstChildElement(becomesSuch, "Pred");
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
        Element vars = BxmlDomUtils.firstChildElement(becomesIn, "Variables");
        Element value = BxmlDomUtils.firstChildElement(becomesIn, "Value");
        if (vars == null || value == null) {
            return;
        }
        Element setExp = null;
        for (Element valExp : BxmlDomUtils.directExpChildren(value)) {
            setExp = valExp;
            break;
        }
        if (setExp == null) {
            return;
        }
        List<String> parts = new ArrayList<>();
        for (Element varExp : BxmlDomUtils.directExpChildren(vars)) {
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

    /**
     * @param extraLhsVarNames nomes adicionais de variáveis sendo atribuídas simultaneamente
     *        (substituição paralela {@code ||}) — também precisam de {@code \old} nas RHS.
     */
    private static void parseAssignementSub(
            Element assign, List<String> ensures, BxmlTranslateContext ctx,
            Set<String> extraLhsVarNames) {
        Element vars = BxmlDomUtils.firstChildElement(assign, "Variables");
        Element vals = BxmlDomUtils.firstChildElement(assign, "Values");
        if (vars == null || vals == null) return;
        List<Element> lhs = BxmlDomUtils.directExpChildren(vars);
        List<Element> rhs = BxmlDomUtils.directExpChildren(vals);

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
        List<Element> children = BxmlDomUtils.directExpChildren(exp);
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

}
