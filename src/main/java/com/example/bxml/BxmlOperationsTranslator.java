package com.example.bxml;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
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
 * Traduz {@code <Operations>} (lista de {@code <Operation name="...">}) para especificações ACSL.
 * As operações referenciam funções da {@code ACSL_Lib} via {@link BxmlExpressionToAcsl}.
 *
 * <p>Uma {@code Operation} contém opcionalmente {@code Input_Parameters}, {@code Output_Parameters}
 * (cada saída vira {@code assigns *nome}, {@code requires \valid(nome)} e o correspondente {@code ensures} usa {@code *nome} à esquerda de {@code ==}),
 * {@code Precondition} (predicado) e obrigatoriamente {@code Body} (substituição).
 *
 * @see <a href="https://www.atelierb.eu/wp-content/uploads/2023/10/bxml-1.0.html">BXML 1.0 — Operation</a>
 */
public final class BxmlOperationsTranslator {

    private BxmlOperationsTranslator() {}

    /**
     * Lista esboços de contratos por operação (nome B → nome função {@code Machine__op}).
     *
     * @param invariantPredicateNames nomes ACSL dos predicados de {@code <Invariant>} (ex.: {@code M_invariant}),
     *        repetidos em cada operação como {@code requires} e {@code ensures}
     */
    public static List<OperationAcsl> translateOperations(
            Element machineEl,
            BxmlTranslateContext ctx,
            List<String> invariantPredicateNames) {
        return translateOperations(
                machineEl, ctx, invariantPredicateNames, null, null, null, null, null);
    }

    /**
     * @param abstractVariableNames se não nulo, operações que atribuem a estas variáveis recebem
     *        {@code at return: assert ghost__<op>(p1, …);} no contrato (parâmetros de entrada) e, nesse caso,
     *        removem-se os {@code ensures} vindos do {@code Body} — mantêm-se só os que repetem o
     *        invariante (nomes em {@code invariantPredicateNames}).
     */
    public static List<OperationAcsl> translateOperations(
            Element machineEl,
            BxmlTranslateContext ctx,
            List<String> invariantPredicateNames,
            Set<String> abstractVariableNames) {
        return translateOperations(
                machineEl, ctx, invariantPredicateNames, abstractVariableNames, null, null, null, null);
    }

    /**
     * @param libScanGhostOperationBodies se não nulo, acrescenta os {@code ensures} do {@code Body}
     *        das operações ghost (antes de os retirar do contrato) para deteção de includes da
     *        ACSL_Lib ({@link com.example.AcslLibIncludes}).
     */
    public static List<OperationAcsl> translateOperations(
            Element machineEl,
            BxmlTranslateContext ctx,
            List<String> invariantPredicateNames,
            Set<String> abstractVariableNames,
            StringBuilder libScanGhostOperationBodies) {
        return translateOperations(
                machineEl,
                ctx,
                invariantPredicateNames,
                abstractVariableNames,
                libScanGhostOperationBodies,
                null,
                null,
                null);
    }

    /**
     * @param rootAbstractMachineName nome da máquina abstrata raiz do {@code .acsl} (ex. {@code SetTest})
     * @param mergedRefinementChain máquinas fundidas na ordem do pipeline (refinamentos + implementações)
     * @param gluing substituições de invariantes
     */
    public static List<OperationAcsl> translateOperations(
            Element machineEl,
            BxmlTranslateContext ctx,
            List<String> invariantPredicateNames,
            Set<String> abstractVariableNames,
            StringBuilder libScanGhostOperationBodies,
            String rootAbstractMachineName,
            List<Element> mergedRefinementChain,
            Map<String, String> gluing) {
        boolean useGhost =
                mergedRefinementChain == null
                        || mergedRefinementChain.isEmpty()
                        || BxmlMachineVariables.needsGhostAbstraction(
                                machineEl, mergedRefinementChain);
        return translateOperations(
                machineEl,
                ctx,
                invariantPredicateNames,
                abstractVariableNames,
                libScanGhostOperationBodies,
                rootAbstractMachineName,
                mergedRefinementChain,
                gluing,
                useGhost,
                null);
    }

    public static List<OperationAcsl> translateOperations(
            Element machineEl,
            BxmlTranslateContext ctx,
            List<String> invariantPredicateNames,
            Set<String> abstractVariableNames,
            StringBuilder libScanGhostOperationBodies,
            String rootAbstractMachineName,
            List<Element> mergedRefinementChain,
            Map<String, String> gluing,
            boolean useGhostAbstraction) {
        return translateOperations(
                machineEl,
                ctx,
                invariantPredicateNames,
                abstractVariableNames,
                libScanGhostOperationBodies,
                rootAbstractMachineName,
                mergedRefinementChain,
                gluing,
                useGhostAbstraction,
                null);
    }

    /**
     * @param useGhostAbstraction {@code false} quando a implementação usa as mesmas variáveis C
     *        ({@link BxmlMachineVariables#usesDirectImplementationVariables}): contratos com
     *        {@code ensures} do corpo e sem {@code assert ghost__…}
     * @param importedOpAssigns mapa de nome-local-de-operação → targets {@code assigns} da máquina
     *        importada; quando não nulo, propaga assigns de {@code Operation_Call} na implementação
     */
    public static List<OperationAcsl> translateOperations(
            Element machineEl,
            BxmlTranslateContext ctx,
            List<String> invariantPredicateNames,
            Set<String> abstractVariableNames,
            StringBuilder libScanGhostOperationBodies,
            String rootAbstractMachineName,
            List<Element> mergedRefinementChain,
            Map<String, String> gluing,
            boolean useGhostAbstraction,
            Map<String, List<String>> importedOpAssigns) {
        return translateOperations(machineEl, ctx, invariantPredicateNames, abstractVariableNames,
                libScanGhostOperationBodies, rootAbstractMachineName, mergedRefinementChain,
                gluing, useGhostAbstraction, importedOpAssigns, List.of());
    }

    /**
     * @param requiresOnlyPredicateNames predicados de invariante de máquinas importadas —
     *        adicionados apenas ao {@code requires}, não ao {@code ensures}.
     */
    public static List<OperationAcsl> translateOperations(
            Element machineEl,
            BxmlTranslateContext ctx,
            List<String> invariantPredicateNames,
            Set<String> abstractVariableNames,
            StringBuilder libScanGhostOperationBodies,
            String rootAbstractMachineName,
            List<Element> mergedRefinementChain,
            Map<String, String> gluing,
            boolean useGhostAbstraction,
            Map<String, List<String>> importedOpAssigns,
            List<String> requiresOnlyPredicateNames) {
        return translateOperations(machineEl, ctx, invariantPredicateNames, abstractVariableNames,
                libScanGhostOperationBodies, rootAbstractMachineName, mergedRefinementChain,
                gluing, useGhostAbstraction, importedOpAssigns, requiresOnlyPredicateNames,
                Map.of());
    }

    public static List<OperationAcsl> translateOperations(
            Element machineEl,
            BxmlTranslateContext ctx,
            List<String> invariantPredicateNames,
            Set<String> abstractVariableNames,
            StringBuilder libScanGhostOperationBodies,
            String rootAbstractMachineName,
            List<Element> mergedRefinementChain,
            Map<String, String> gluing,
            boolean useGhostAbstraction,
            Map<String, List<String>> importedOpAssigns,
            List<String> requiresOnlyPredicateNames,
            Map<String, String> varRhsOverrides) {
        return translateOperations(machineEl, ctx, invariantPredicateNames, abstractVariableNames,
                libScanGhostOperationBodies, rootAbstractMachineName, mergedRefinementChain,
                gluing, useGhostAbstraction, importedOpAssigns, requiresOnlyPredicateNames,
                varRhsOverrides, null);
    }

    /**
     * @param bxmlDirectory diretório das fontes {@code .bxml} — necessário para resolver a fatia
     *        {@code [low .. high]} dos {@code assigns} de conjuntos nomeados (deferred sets como
     *        {@code GOODS}) valorados em máquinas VISTAS (SEES), não na própria implementação — ver
     *        {@link BxmlMachineVariables#listConcreteAssignTargetsForAbstractMutation}.
     */
    public static List<OperationAcsl> translateOperations(
            Element machineEl,
            BxmlTranslateContext ctx,
            List<String> invariantPredicateNames,
            Set<String> abstractVariableNames,
            StringBuilder libScanGhostOperationBodies,
            String rootAbstractMachineName,
            List<Element> mergedRefinementChain,
            Map<String, String> gluing,
            boolean useGhostAbstraction,
            Map<String, List<String>> importedOpAssigns,
            List<String> requiresOnlyPredicateNames,
            Map<String, String> varRhsOverrides,
            Path bxmlDirectory) {
        return translateOperations(machineEl, ctx, invariantPredicateNames, abstractVariableNames,
                libScanGhostOperationBodies, rootAbstractMachineName, mergedRefinementChain,
                gluing, useGhostAbstraction, importedOpAssigns, requiresOnlyPredicateNames,
                varRhsOverrides, bxmlDirectory, List.of());
    }

    /**
     * @param arraySeparationMachineNames nomes de máquinas transitivamente {@code IMPORTS}/{@code
     *        SEES}/{@code USES} desta máquina (tipicamente a união de {@code
     *        listImportedMachineNamesTransitive} + {@code listSeenMachineNamesTransitive} do
     *        chamador) — usados para {@code requires \separated(saída, Array + (low .. high));} de
     *        cada parâmetro de saída (ponteiro C) contra cada variável array-backed alcançável,
     *        própria ou dessas máquinas. Ver {@link
     *        BxmlMachineVariables#listArraySeparationTargets}.
     */
    public static List<OperationAcsl> translateOperations(
            Element machineEl,
            BxmlTranslateContext ctx,
            List<String> invariantPredicateNames,
            Set<String> abstractVariableNames,
            StringBuilder libScanGhostOperationBodies,
            String rootAbstractMachineName,
            List<Element> mergedRefinementChain,
            Map<String, String> gluing,
            boolean useGhostAbstraction,
            Map<String, List<String>> importedOpAssigns,
            List<String> requiresOnlyPredicateNames,
            Map<String, String> varRhsOverrides,
            Path bxmlDirectory,
            List<String> arraySeparationMachineNames) {
        return translateOperations(machineEl, ctx, invariantPredicateNames, abstractVariableNames,
                libScanGhostOperationBodies, rootAbstractMachineName, mergedRefinementChain,
                gluing, useGhostAbstraction, importedOpAssigns, requiresOnlyPredicateNames,
                varRhsOverrides, bxmlDirectory, arraySeparationMachineNames, Set.of());
    }

    /**
     * @param collapsedVariableNames variáveis abstratas com o mesmo nome na implementação
     *        (array-backed, sem camada ghost — ver {@code BxmlMachineVariables#collapsedIntoImplementationVariableNames});
     *        já excluídas de {@code abstractVariableNames} pelo chamador (não devem disparar
     *        {@code ghostSlug}/{@code dummyGhostEnsureVarNames}), mas uma operação que as atribui
     *        (ex.: açúcar de sobrescrita {@code array(ii) := vv}) ainda precisa de um {@code assigns}
     *        concreto — resolvido aqui pela mesma ligação de refinamento usada para
     *        {@code abstractVariableNames}.
     */
    public static List<OperationAcsl> translateOperations(
            Element machineEl,
            BxmlTranslateContext ctx,
            List<String> invariantPredicateNames,
            Set<String> abstractVariableNames,
            StringBuilder libScanGhostOperationBodies,
            String rootAbstractMachineName,
            List<Element> mergedRefinementChain,
            Map<String, String> gluing,
            boolean useGhostAbstraction,
            Map<String, List<String>> importedOpAssigns,
            List<String> requiresOnlyPredicateNames,
            Map<String, String> varRhsOverrides,
            Path bxmlDirectory,
            List<String> arraySeparationMachineNames,
            Set<String> collapsedVariableNames) {
        String machineName = machineEl.getAttribute("name");
        List<OperationAcsl> out = new ArrayList<>();
        List<String> separationArrayTargets =
                BxmlMachineVariables.listArraySeparationTargets(
                        machineName, mergedRefinementChain, ctx, arraySeparationMachineNames,
                        bxmlDirectory);

        NodeList ops = machineEl.getElementsByTagNameNS("*", "Operations");
        if (ops.getLength() == 0) return out;
        Element operationsEl = (Element) ops.item(0);

        Set<String> promotedOpNames = listPromotedOperationNames(mergedRefinementChain);
        Set<String> declaredSetNamesForParams = BxmlSetsTranslator.declaredSetNames(machineEl);

        NodeList children = operationsEl.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node n = children.item(i);
            if (n.getNodeType() != Node.ELEMENT_NODE) continue;
            Element child = (Element) n;
            if (!"Operation".equals(child.getLocalName())) continue;

            String opName = child.getAttribute("name");

            // Operações promovidas viram macro #define no header C — sem função C real.
            if (promotedOpNames.contains(opName)) continue;
            String funcName = machineName + "__" + sanitize(opName);

            Element pre = BxmlDomUtils.firstChildElement(child, "Precondition");
            // Por-operação, não por máquina inteira: nomes de parâmetro colidem entre operações
            // diferentes com papéis diferentes (ex.: Biblioteca's "cc" é escalar COPY em addBook
            // mas array de saída em copiesQuery) — ver javadoc de
            // BxmlMachineVariables#deferredSetTypedOperationParameterNames.
            BxmlTranslateContext opCtx =
                    ctx.withDeferredSetTypedParameterNames(
                            BxmlMachineVariables.deferredSetTypedOperationParameterNames(
                                    pre, declaredSetNamesForParams));
            List<String> outputParams = parseOutputParameterNames(child);
            Map<String, ArrayFunctionParamInfo> arrayParamLens = inferArrayFunctionParamLengths(child, opCtx);

            OperationRequiresResult requiresResult =
                    buildRequiresForOperation(
                            child, pre, opCtx, invariantPredicateNames, requiresOnlyPredicateNames,
                            outputParams, arrayParamLens, separationArrayTargets);
            List<String> requires = requiresResult.requires();
            Set<String> funcTypedOutputs = requiresResult.funcTypedOutputs();

            Element body = BxmlDomUtils.firstChildElement(child, "Body");
            OperationEnsuresResult ensuresResult =
                    buildEnsuresForOperation(
                            child, opCtx, body, outputParams, funcTypedOutputs, invariantPredicateNames);
            List<String> ensures = ensuresResult.ensures();
            List<String> bodyEnsuresOnly = ensuresResult.bodyEnsuresOnly();

            GhostSlugInfo ghostInfo =
                    computeGhostSlugInfo(child, opName, useGhostAbstraction, abstractVariableNames);
            String ghostSlug = ghostInfo.ghostSlug();
            boolean assignsAbstract = ghostInfo.assignsAbstract();
            List<String> ghostBehaviorArgs = ghostInfo.ghostBehaviorArgs();

            appendLibScanGhostOperationBodies(libScanGhostOperationBodies, ghostSlug, bodyEnsuresOnly);
            // ensures mantém o conteúdo funcional do corpo (bodyEnsuresOnly, já acrescentado acima)
            // MESMO quando a operação também ganha um predicado ghost — quem chama esta função só
            // vê o contrato real (o "at return: assert ghost__…" é interno ao corpo), então sem
            // isto o chamador só sabia do invariante genérico, não do que a operação de facto faz.
            // Usa abstractVariableNames (já filtrado pelo chamador, sem as variáveis colapsadas na
            // implementação — ver BxmlMachineVariables#collapsedIntoImplementationVariableNames),
            // não a lista COMPLETA de variáveis abstratas: uma variável colapsada não tem
            // dummy_ghost_<v> declarado (não há camada ghost paralela para ela), então referenciá-la
            // aqui geraria "unbound logic variable" no Frama-C.
            List<String> dummyGhostEnsureVars =
                    useGhostAbstraction && assignsAbstract && abstractVariableNames != null
                            ? new ArrayList<>(abstractVariableNames)
                            : List.of();

            List<String> connectionConcreteAssigns =
                    resolveConnectionConcreteAssigns(
                            child, opCtx, assignsAbstract, abstractVariableNames, collapsedVariableNames,
                            rootAbstractMachineName, machineEl, mergedRefinementChain, gluing,
                            useGhostAbstraction, varRhsOverrides, bxmlDirectory);

            ImplementationLoopsResult implResult =
                    resolveImplementationLoops(
                            opName, opCtx, machineEl, mergedRefinementChain, importedOpAssigns, bxmlDirectory);
            Element implOp = implResult.implOp();
            List<BxmlLoopTranslator.LoopContract> loops = implResult.loops();

            connectionConcreteAssigns =
                    mergeCalledOperationAssigns(connectionConcreteAssigns, importedOpAssigns, implOp);

            // Becomes_In (v :: S) compila para rand() → Frama-C exige __fc_random_counter no assigns.
            // Apenas o corpo da implementação é compilado para C; a abstrata pode ter Becomes_In
            // como especificação não-determinística sem que rand() apareça no código gerado.
            connectionConcreteAssigns = addRandomCounterAssignIfNeeded(connectionConcreteAssigns, implOp, body);

            loops = rewriteEnsuresAndLoopsForArrayBackedParams(ensures, arrayParamLens, loops);
            // Intervalo do domínio de cada saída array-backed (ex.: cc -> "MAX_COPY", de
            // "loop variant (MAX_COPY - ii) + 1"), derivado ANTES da reescrita do assigns do
            // laço para funcTypedOutputs (que já consome este mesmo padrão por laço).
            FunctionTypedOutputHandling functionTypedHandling =
                    finalizeFunctionTypedOutputHandling(requires, loops, funcTypedOutputs, outputParams);
            Map<String, String> functionTypedOutputBounds = functionTypedHandling.functionTypedOutputBounds();
            loops = functionTypedHandling.loops();

            boolean skipBody = resolveSkipBody(body, implOp);

            out.add(
                    new OperationAcsl(
                            funcName,
                            requires,
                            ensures,
                            outputParams,
                            funcTypedOutputs,
                            functionTypedOutputBounds,
                            ghostSlug,
                            ghostBehaviorArgs,
                            dummyGhostEnsureVars,
                            connectionConcreteAssigns,
                            loops,
                            skipBody));
        }
        return out;
    }

    private record OperationRequiresResult(List<String> requires, Set<String> funcTypedOutputs) {}

    private record OperationEnsuresResult(List<String> ensures, List<String> bodyEnsuresOnly) {}

    private record GhostSlugInfo(
            String ghostSlug, boolean assignsAbstract, List<String> ghostBehaviorArgs) {}

    private record ImplementationLoopsResult(
            Element implOp, List<BxmlLoopTranslator.LoopContract> loops) {}

    private record FunctionTypedOutputHandling(
            Map<String, String> functionTypedOutputBounds,
            List<BxmlLoopTranslator.LoopContract> loops) {}

    private static OperationRequiresResult buildRequiresForOperation(
            Element child,
            Element pre,
            BxmlTranslateContext opCtx,
            List<String> invariantPredicateNames,
            List<String> requiresOnlyPredicateNames,
            List<String> outputParams,
            Map<String, ArrayFunctionParamInfo> arrayParamLens,
            List<String> separationArrayTargets) {
        List<String> requires = new ArrayList<>();
        for (String inv : invariantPredicateNames) {
            requires.add(inv);
        }
        if (requiresOnlyPredicateNames != null) {
            requires.addAll(requiresOnlyPredicateNames);
        }
        if (pre != null) {
            requires.addAll(BxmlPredicateToAcsl.translatePredicateBlock(pre, opCtx));
            rewriteAcslListForArrayBackedParams(requires, arrayParamLens);
            rewriteRequiresForOutputParameters(requires, outputParams, boolOutputParameterNames(child, opCtx));
        }

        Set<String> funcTypedOutputs = functionTypedOutputParamNames(child, opCtx);
        for (String p : outputParams) {
            // Saídas array-backed (função/relação B) têm o \valid deferido: precisa do
            // intervalo do domínio (ex.: "0 .. MAX_COPY"), só conhecido após o laço da
            // implementação ser traduzido (ver functionTypedOutputBounds mais abaixo).
            // \valid(p) sozinho só cobre *p; sem o intervalo, o Frama-C aceita o assigns
            // p[..] mas o WP não tem base para validar p[1..N-1].
            if (!funcTypedOutputs.contains(p)) {
                requires.add("\\valid(" + p + ")");
            }
        }
        // Parâmetros de saída são ponteiros C — em memória, podem em princípio apontar para
        // qualquer região global do programa. \separated torna explícita a não-aliasing que o
        // modelo de memória "typed" do WP já assume implicitamente (ver aviso "Memory model
        // hypotheses" do Frama-C), tanto contra cada variável array-backed alcançável (própria
        // máquina + IMPORTS/SEES/USES transitivos, já resolvidos pelo chamador em
        // separationArrayTargets) quanto, com múltiplas saídas, entre si.
        List<String> scalarOutputParams = new ArrayList<>();
        for (String p : outputParams) {
            if (!funcTypedOutputs.contains(p)) {
                scalarOutputParams.add(p);
            }
        }
        if (!separationArrayTargets.isEmpty()) {
            for (String p : scalarOutputParams) {
                for (String arrayTarget : separationArrayTargets) {
                    requires.add("\\separated(" + p + ", " + arrayTarget + ")");
                }
            }
        }
        for (int a = 0; a < scalarOutputParams.size(); a++) {
            for (int b = a + 1; b < scalarOutputParams.size(); b++) {
                requires.add(
                        "\\separated(" + scalarOutputParams.get(a) + ", " + scalarOutputParams.get(b) + ")");
            }
        }
        return new OperationRequiresResult(requires, funcTypedOutputs);
    }

    private static OperationEnsuresResult buildEnsuresForOperation(
            Element child,
            BxmlTranslateContext opCtx,
            Element body,
            List<String> outputParams,
            Set<String> funcTypedOutputs,
            List<String> invariantPredicateNames) {
        List<String> ensures = new ArrayList<>();
        if (body != null) {
            BxmlInitialisationTranslator.appendEnsuresFromBody(body, ensures, opCtx);
        }
        applyStarPrefixToEnsures(ensures, outputParams);
        // Antes de rewriteEnsuresBoolOutputEquality (que só troca o LHS "*p" por "(integer)(*p !=
        // 0)"): "*p == (pred ? \true : \false)" pode ter um predicado da lib (ex.: equals(...))
        // na condição do ternário, que o Frama-C rejeita ("symbol X is a predicate, not a
        // function") — precisa virar bicondicional ANTES, enquanto o LHS ainda é "*p" (o regex
        // desta função exige esse formato). Mesma correção já usada no lado ghost; só nunca
        // apareceu aqui porque este ensures era descartado do contrato real (ver abaixo).
        // rewriteIntegerTernaryPredicateConditionForGhost cobre a forma irmã (ternário INTEIRO,
        // não booleano — ex. "v := v <+ %z.(...)" via formatOverwriteWithLambdaAssignment,
        // mesmo erro do kernel "symbol X is a predicate, not a function" quando a guarda B
        // testa pertença a uma variável de máquina em vez de um conjunto literal).
        for (int ei = 0; ei < ensures.size(); ei++) {
            String e = GhostOperationsCiGenerator.rewriteBoolOutputPredicateTernary(ensures.get(ei));
            e = GhostOperationsCiGenerator.rewriteIntegerTernaryPredicateConditionForGhost(e);
            ensures.set(ei, e);
        }
        rewriteEnsuresBoolOutputEquality(ensures, outputParams, child, opCtx);
        // Parâmetros de entrada escalares (C "int") ficam sem cast no ensures cru — comparados
        // com um Set<integer> (ex. "singleton(bb)" dentro de "equals(books, ...)") o Frama-C
        // infere o tipo lógico do parâmetro a partir do seu uso e rejeita ("invalid cast from
        // Set<Biblioteca__BOOK> to Set<integer>"). O lado ghost já resolve isto com o mesmo
        // "(integer)bb"; reaproveitado aqui pelo mesmo motivo do bloco acima.
        List<GhostOperationsCiGenerator.Param> scalarCastParams =
                GhostParamTypeResolver.appendOutputParametersAsPointers(
                        GhostParamTypeResolver.listInputParameters(child), child);
        for (int ei = 0; ei < ensures.size(); ei++) {
            ensures.set(
                    ei,
                    GhostParamTypeResolver.castScalarIntGhostParamsInEnsure(
                            ensures.get(ei), scalarCastParams));
        }
        removeEnsuresForFunctionTypedOutputs(ensures, funcTypedOutputs);
        List<String> bodyEnsuresOnly = new ArrayList<>(ensures);
        for (String inv : invariantPredicateNames) {
            ensures.add(inv);
        }
        return new OperationEnsuresResult(ensures, bodyEnsuresOnly);
    }

    private static GhostSlugInfo computeGhostSlugInfo(
            Element child,
            String opName,
            boolean useGhostAbstraction,
            Set<String> abstractVariableNames) {
        List<String> inputParamNames = parseInputParameterNames(child);
        String ghostSlug = "";
        boolean assignsAbstract =
                abstractVariableNames != null
                        && !abstractVariableNames.isEmpty()
                        && GhostContractPredicates.operationAssignsAbstract(
                                child, abstractVariableNames);
        boolean bodyHasAnySub = GhostContractPredicates.operationBodyHasAnySub(child);
        if (useGhostAbstraction && (assignsAbstract || bodyHasAnySub)) {
            ghostSlug = GhostContractPredicates.ghostOperationSlug(opName);
        }
        List<String> ghostBehaviorArgs = new ArrayList<>(inputParamNames);
        if (bodyHasAnySub || assignsAbstract) {
            ghostBehaviorArgs.addAll(GhostContractPredicates.listOutputParameterNames(child));
        }
        return new GhostSlugInfo(ghostSlug, assignsAbstract, ghostBehaviorArgs);
    }

    private static void appendLibScanGhostOperationBodies(
            StringBuilder libScanGhostOperationBodies, String ghostSlug, List<String> bodyEnsuresOnly) {
        if (libScanGhostOperationBodies != null && !ghostSlug.isBlank()) {
            for (String line : bodyEnsuresOnly) {
                if (line != null && !line.isBlank()) {
                    libScanGhostOperationBodies.append(line).append('\n');
                }
            }
        }
    }

    private static List<String> resolveConnectionConcreteAssigns(
            Element child,
            BxmlTranslateContext opCtx,
            boolean assignsAbstract,
            Set<String> abstractVariableNames,
            Set<String> collapsedVariableNames,
            String rootAbstractMachineName,
            Element machineEl,
            List<Element> mergedRefinementChain,
            Map<String, String> gluing,
            boolean useGhostAbstraction,
            Map<String, String> varRhsOverrides,
            Path bxmlDirectory) {
        List<String> connectionConcreteAssigns = List.of();
        if (rootAbstractMachineName == null
                || rootAbstractMachineName.isBlank()
                || mergedRefinementChain == null
                || mergedRefinementChain.isEmpty()) {
            return connectionConcreteAssigns;
        }
        Set<String> assignedAbs = new LinkedHashSet<>();
        if (assignsAbstract) {
            assignedAbs.addAll(
                    GhostContractPredicates.assignedAbstractVariablesInOperation(
                            child, abstractVariableNames));
        }
        // Variáveis colapsadas (mesmo nome abstrata/implementação, array-backed sem ghost twin) não
        // entram em abstractVariableNames/assignsAbstract (ver javadoc de collapsedVariableNames no
        // translateOperations acima) mas ainda precisam de assigns concreto quando a operação as
        // atribui — mesma resolução por ligação de refinamento, união separada para não acionar
        // ghostSlug/dummyGhostEnsureVarNames (que continuam a olhar só para assignsAbstract).
        if (collapsedVariableNames != null && !collapsedVariableNames.isEmpty()) {
            assignedAbs.addAll(
                    GhostContractPredicates.assignedAbstractVariablesInOperation(
                            child, collapsedVariableNames));
        }
        if (assignedAbs.isEmpty()) {
            return connectionConcreteAssigns;
        }
        connectionConcreteAssigns =
                ConcreteAssignTargetResolver.listConcreteAssignTargetsForAbstractMutation(
                        rootAbstractMachineName,
                        machineEl,
                        mergedRefinementChain,
                        assignedAbs,
                        gluing,
                        opCtx,
                        bxmlDirectory);
        if (connectionConcreteAssigns.isEmpty() && !useGhostAbstraction) {
            connectionConcreteAssigns =
                    ConcreteAssignTargetResolver.listImplementationAssignTargetsForAbstractVariables(
                            rootAbstractMachineName,
                            mergedRefinementChain,
                            assignedAbs,
                            opCtx);
        }
        if (connectionConcreteAssigns.isEmpty() && !useGhostAbstraction) {
            connectionConcreteAssigns =
                    ConcreteAssignTargetResolver.listLinkedConcreteAssignTargetsForOperation(
                            rootAbstractMachineName,
                            machineEl,
                            mergedRefinementChain,
                            assignedAbs,
                            opCtx,
                            varRhsOverrides);
        }
        return connectionConcreteAssigns;
    }

    private static ImplementationLoopsResult resolveImplementationLoops(
            String opName,
            BxmlTranslateContext opCtx,
            Element machineEl,
            List<Element> mergedRefinementChain,
            Map<String, List<String>> importedOpAssigns,
            Path bxmlDirectory) {
        List<BxmlLoopTranslator.LoopContract> loops = List.of();
        Element implOp = null;
        if (mergedRefinementChain != null && !mergedRefinementChain.isEmpty()) {
            implOp = BxmlLoopTranslator.findImplementationOperation(mergedRefinementChain, opName);
            if (implOp != null) {
                loops = BxmlLoopTranslator.translateLoopsFromImplementationOperation(
                        implOp, opCtx, machineEl, importedOpAssigns, bxmlDirectory);
            }
        }
        return new ImplementationLoopsResult(implOp, loops);
    }

    private static List<String> mergeCalledOperationAssigns(
            List<String> connectionConcreteAssigns,
            Map<String, List<String>> importedOpAssigns,
            Element implOp) {
        if (importedOpAssigns != null && !importedOpAssigns.isEmpty() && implOp != null) {
            List<String> calledAssigns = collectAssignsFromOperationCalls(implOp, importedOpAssigns);
            if (!calledAssigns.isEmpty()) {
                LinkedHashSet<String> merged = new LinkedHashSet<>(connectionConcreteAssigns);
                merged.addAll(calledAssigns);
                return new ArrayList<>(merged);
            }
        }
        return connectionConcreteAssigns;
    }

    private static List<String> addRandomCounterAssignIfNeeded(
            List<String> connectionConcreteAssigns, Element implOp, Element body) {
        boolean hasBecomesIn = implOp != null
                ? bodyHasBecomesIn(BxmlDomUtils.firstChildElement(implOp, "Body"))
                : bodyHasBecomesIn(body);
        if (hasBecomesIn) {
            LinkedHashSet<String> withRandom = new LinkedHashSet<>(connectionConcreteAssigns);
            withRandom.add("__fc_random_counter");
            return new ArrayList<>(withRandom);
        }
        return connectionConcreteAssigns;
    }

    private static List<BxmlLoopTranslator.LoopContract> rewriteEnsuresAndLoopsForArrayBackedParams(
            List<String> ensures,
            Map<String, ArrayFunctionParamInfo> arrayParamLens,
            List<BxmlLoopTranslator.LoopContract> loops) {
        rewriteAcslListForArrayBackedParams(ensures, arrayParamLens);
        if (!arrayParamLens.isEmpty() && !loops.isEmpty()) {
            List<BxmlLoopTranslator.LoopContract> rewrittenLoops = new ArrayList<>();
            for (BxmlLoopTranslator.LoopContract lc : loops) {
                String inv = rewriteAcslStringForArrayBackedParams(lc.invariant(), arrayParamLens);
                rewrittenLoops.add(new BxmlLoopTranslator.LoopContract(
                        lc.index(), inv, lc.variant(), lc.assigns()));
            }
            return rewrittenLoops;
        }
        return loops;
    }

    private static FunctionTypedOutputHandling finalizeFunctionTypedOutputHandling(
            List<String> requires,
            List<BxmlLoopTranslator.LoopContract> loops,
            Set<String> funcTypedOutputs,
            List<String> outputParams) {
        Map<String, String> functionTypedOutputBounds =
                extractFunctionTypedOutputBounds(loops, funcTypedOutputs);
        for (String p : outputParams) {
            if (!funcTypedOutputs.contains(p)) {
                continue;
            }
            String bound = functionTypedOutputBounds.get(p);
            requires.add(
                    bound != null
                            ? "\\valid(" + p + " + (0 .. " + bound + "))"
                            : "\\valid(" + p + ")");
        }
        if (!funcTypedOutputs.isEmpty() && !loops.isEmpty()) {
            loops = rewriteLoopsForFunctionTypedOutputs(loops, funcTypedOutputs);
        }
        return new FunctionTypedOutputHandling(functionTypedOutputBounds, loops);
    }

    private static boolean resolveSkipBody(Element body, Element implOp) {
        boolean skipBody = isBodyPureSkip(body);
        // Se a abstrata tem Skip mas a implementação tem corpo real (ex.: Operation_Call),
        // gerar contrato completo em vez do esqueleto requires \true / ensures \true.
        if (skipBody && implOp != null) {
            Element implBody = BxmlDomUtils.firstChildElement(implOp, "Body");
            if (implBody != null && !isBodyPureSkip(implBody)) {
                skipBody = false;
            }
        }
        return skipBody;
    }

    /** Nomes de operações promovidas via {@code <Promotes>} em qualquer elemento da cadeia de refinamento. */
    private static Set<String> listPromotedOperationNames(List<Element> chain) {
        Set<String> out = new HashSet<>();
        if (chain == null || chain.isEmpty()) return out;
        for (Element el : chain) {
            NodeList promotes = el.getElementsByTagNameNS("*", "Promotes");
            for (int p = 0; p < promotes.getLength(); p++) {
                NodeList promoted = ((Element) promotes.item(p))
                        .getElementsByTagNameNS("*", "Promoted_Operation");
                for (int q = 0; q < promoted.getLength(); q++) {
                    String name = promoted.item(q).getTextContent();
                    if (name != null && !name.isBlank()) out.add(name.trim());
                }
            }
        }
        return out;
    }

    /** Nomes dos {@code Id} em {@code Input_Parameters} (ordem do BXML), identificadores C-sanitizados. */
    private static List<String> parseInputParameterNames(Element operation) {
        Element inEl = BxmlDomUtils.firstChildElement(operation, "Input_Parameters");
        if (inEl == null) return List.of();
        List<String> names = new ArrayList<>();
        NodeList ch = inEl.getChildNodes();
        for (int i = 0; i < ch.getLength(); i++) {
            Node n = ch.item(i);
            if (n.getNodeType() != Node.ELEMENT_NODE) continue;
            Element e = (Element) n;
            if ("Attr".equals(e.getLocalName())) continue;
            if ("Id".equals(e.getLocalName())) {
                String v = e.getAttribute("value");
                if (v != null && !v.isBlank()) names.add(sanitize(v.trim()));
            }
        }
        return names;
    }

    /** Nomes dos {@code Id} em {@code Output_Parameters} (ordem do BXML). */
    private static List<String> parseOutputParameterNames(Element operation) {
        Element outEl = BxmlDomUtils.firstChildElement(operation, "Output_Parameters");
        if (outEl == null) return List.of();
        List<String> names = new ArrayList<>();
        NodeList ch = outEl.getChildNodes();
        for (int i = 0; i < ch.getLength(); i++) {
            Node n = ch.item(i);
            if (n.getNodeType() != Node.ELEMENT_NODE) continue;
            Element e = (Element) n;
            if ("Attr".equals(e.getLocalName())) continue;
            if ("Id".equals(e.getLocalName())) {
                String v = e.getAttribute("value");
                if (!v.isBlank()) names.add(v);
            }
        }
        return names;
    }

    private static void applyStarPrefixToEnsures(List<String> ensures, List<String> outputParams) {
        if (outputParams.isEmpty()) return;
        Set<String> out = new HashSet<>(outputParams);
        for (int i = 0; i < ensures.size(); i++) {
            String s = ensures.get(i);
            int eq = s.indexOf(" == ");
            // Forma simples v == E (Assignement_Sub): a LHS é exatamente um parâmetro de saída
            boolean handled = false;
            if (eq >= 0) {
                String lhs = s.substring(0, eq).trim();
                if (out.contains(lhs) && !lhs.startsWith("*")) {
                    ensures.set(i, "*" + lhs + " == " + s.substring(eq + 4));
                    handled = true;
                }
            }
            if (!handled) {
                // Predicado geral (Becomes_Such_That): substitui toda ocorrência word-boundary do parâmetro
                for (String param : out) {
                    s = s.replaceAll(
                            "(?<![\\w*])" + java.util.regex.Pattern.quote(param) + "(?!\\w)",
                            "*" + param);
                }
                ensures.set(i, s);
            }
        }
    }

    /**
     * Saída C {@code bool}/{@code _Bool} vs variável lógica {@code integer} (0/1): evita mistura bool/int no WP.
     */
    private static void rewriteEnsuresBoolOutputEquality(
            List<String> ensures, List<String> outputParams, Element operation, BxmlTranslateContext ctx) {
        if (ensures == null || ensures.isEmpty() || outputParams == null || outputParams.isEmpty()) {
            return;
        }
        Set<String> boolOut = boolOutputParameterNames(operation, ctx);
        if (boolOut.isEmpty()) {
            return;
        }
        for (int i = 0; i < ensures.size(); i++) {
            String s = ensures.get(i);
            if (s == null || s.isBlank()) {
                continue;
            }
            int eq = s.indexOf(" == ");
            if (eq < 0) {
                continue;
            }
            String lhs = s.substring(0, eq).trim();
            if (!lhs.startsWith("*")) {
                continue;
            }
            String param = lhs.substring(1).trim();
            if (!boolOut.contains(param)) {
                continue;
            }
            String rhs = s.substring(eq + 4).trim();
            ensures.set(i, "(integer)(*" + param + " != 0) == " + rhs);
        }
    }

    private static Set<String> functionTypedOutputParamNames(Element operation, BxmlTranslateContext ctx) {
        Set<String> out = new HashSet<>();
        Element outEl = BxmlDomUtils.firstChildElement(operation, "Output_Parameters");
        if (outEl == null || ctx == null) return out;
        NodeList ch = outEl.getChildNodes();
        for (int i = 0; i < ch.getLength(); i++) {
            Node n = ch.item(i);
            if (n.getNodeType() != Node.ELEMENT_NODE) continue;
            Element e = (Element) n;
            if (!"Id".equals(e.getLocalName())) continue;
            String name = e.getAttribute("value");
            if (name == null || name.isBlank()) continue;
            String trAttr = e.getAttribute("typref");
            if (trAttr == null || trAttr.isBlank()) continue;
            try {
                int tr = Integer.parseInt(trAttr.trim());
                String rawType = ctx.types().getRawType(tr);
                // POW(X*Y) representa uma função ou relação B → output é array em C
                if (rawType != null && rawType.startsWith("POW(") && rawType.contains("*")) {
                    out.add(name.trim());
                }
            } catch (NumberFormatException ignored) {
                // skip
            }
        }
        return out;
    }

    private static void removeEnsuresForFunctionTypedOutputs(
            List<String> ensures, Set<String> funcTypedOutputs) {
        if (ensures == null || ensures.isEmpty() || funcTypedOutputs == null || funcTypedOutputs.isEmpty()) {
            return;
        }
        ensures.removeIf(e -> {
            if (e == null) return false;
            int eq = e.indexOf(" == ");
            if (eq < 0) return false;
            String lhs = e.substring(0, eq).trim();
            if (lhs.startsWith("*")) {
                return funcTypedOutputs.contains(lhs.substring(1).trim());
            }
            return false;
        });
    }

    private static List<BxmlLoopTranslator.LoopContract> rewriteLoopsForFunctionTypedOutputs(
            List<BxmlLoopTranslator.LoopContract> loops, Set<String> funcTypedOutputs) {
        List<BxmlLoopTranslator.LoopContract> result = new ArrayList<>();
        for (BxmlLoopTranslator.LoopContract lc : loops) {
            String inv = rewriteArrayOutputFunctionApply(lc.invariant(), funcTypedOutputs);
            String upperBound = extractLoopUpperBound(lc.variant(), lc.assigns(), funcTypedOutputs);
            List<String> newAssigns = new ArrayList<>();
            for (String a : lc.assigns()) {
                if (funcTypedOutputs.contains(a)) {
                    if (upperBound != null) {
                        newAssigns.add(a + "[0.." + upperBound + "]");
                    } else {
                        newAssigns.add(a + "[..]");
                    }
                } else {
                    newAssigns.add(a);
                }
            }
            result.add(new BxmlLoopTranslator.LoopContract(lc.index(), inv, lc.variant(), newAssigns));
        }
        return List.copyOf(result);
    }

    /**
     * Para cada saída array-backed atribuída num laço, o limite superior do seu domínio (mesma
     * extração usada por {@link #rewriteLoopsForFunctionTypedOutputs}), usado para o {@code
     * requires \valid(p + (0 .. bound))} e o {@code assigns *(p + (0 .. bound))} ao nível da função
     * — sem isto, {@code \valid(p)}/{@code p[..]} só garantem {@code *p} e o Frama-C pode reduzir o
     * {@code assigns} ao reimprimir o merge, deixando {@code p[1..N-1]} sem cobertura de validade.
     */
    private static Map<String, String> extractFunctionTypedOutputBounds(
            List<BxmlLoopTranslator.LoopContract> loops, Set<String> funcTypedOutputs) {
        Map<String, String> out = new LinkedHashMap<>();
        if (loops == null || loops.isEmpty() || funcTypedOutputs == null || funcTypedOutputs.isEmpty()) {
            return out;
        }
        for (BxmlLoopTranslator.LoopContract lc : loops) {
            String upperBound = extractLoopUpperBound(lc.variant(), lc.assigns(), funcTypedOutputs);
            if (upperBound == null) {
                continue;
            }
            for (String a : lc.assigns()) {
                if (funcTypedOutputs.contains(a)) {
                    out.putIfAbsent(a, upperBound);
                }
            }
        }
        return out;
    }

    /**
     * Tenta extrair o limite superior do loop a partir da expressão do variante.
     * Para variantes da forma {@code (UPPER - counter + 1)} ou {@code (UPPER - counter)},
     * retorna {@code UPPER} como string ACSL.
     */
    private static String extractLoopUpperBound(String variant, List<String> assigns,
            Set<String> funcTypedOutputs) {
        if (variant == null || variant.isBlank()) return null;
        // Loop counter: assign that is not a function-typed output
        String counter = assigns.stream()
                .filter(a -> !funcTypedOutputs.contains(a))
                .findFirst()
                .orElse(null);
        if (counter == null) return null;
        // Match pattern: WORD - counter (anywhere in the variant)
        Pattern p = Pattern.compile("(\\w+)\\s*-\\s*" + Pattern.quote(counter));
        Matcher m = p.matcher(variant);
        if (m.find()) return m.group(1);
        return null;
    }

    private static String rewriteArrayOutputFunctionApply(String s, Set<String> params) {
        if (s == null || s.isBlank() || params == null || params.isEmpty()) return s;
        for (String p : params) {
            String q = Pattern.quote(p);
            // function_apply(p, X) == \true  →  p[X] != 0
            s = s.replaceAll("function_apply\\(" + q + ",\\s*([^)]+)\\)\\s*==\\s*\\\\true",
                    p + "[$1] != 0");
            // function_apply(p, X) == \false  →  p[X] == 0
            s = s.replaceAll("function_apply\\(" + q + ",\\s*([^)]+)\\)\\s*==\\s*\\\\false",
                    p + "[$1] == 0");
            // remaining function_apply(p, X)  →  p[X]
            s = s.replaceAll("function_apply\\(" + q + ",\\s*([^)]+)\\)", p + "[$1]");
        }
        return s;
    }

    private static Set<String> boolOutputParameterNames(Element operation, BxmlTranslateContext ctx) {
        Set<String> out = new HashSet<>();
        Element outEl = BxmlDomUtils.firstChildElement(operation, "Output_Parameters");
        if (outEl == null || ctx == null) {
            return out;
        }
        NodeList ch = outEl.getChildNodes();
        for (int i = 0; i < ch.getLength(); i++) {
            Node n = ch.item(i);
            if (n.getNodeType() != Node.ELEMENT_NODE) {
                continue;
            }
            Element e = (Element) n;
            if (!"Id".equals(e.getLocalName())) {
                continue;
            }
            String name = e.getAttribute("value");
            if (name == null || name.isBlank()) {
                continue;
            }
            String trAttr = e.getAttribute("typref");
            if (trAttr.isBlank()) {
                continue;
            }
            try {
                int tr = Integer.parseInt(trAttr.trim());
                if ("BOOL".equals(ctx.types().getRawType(tr))) {
                    out.add(name.trim());
                }
            } catch (NumberFormatException ignored) {
                // skip
            }
        }
        return out;
    }

    private static String sanitize(String name) {
        return name.replace('-', '_');
    }

    private static boolean isBodyPureSkip(Element body) {
        if (body == null) return false;
        Element sub = firstNonAttrChild(body);
        while (sub != null && "Bloc_Sub".equals(sub.getLocalName())) {
            sub = firstNonAttrChild(sub);
        }
        return sub != null && "Skip".equals(sub.getLocalName());
    }

    private static Element firstNonAttrChild(Element parent) {
        if (parent == null) return null;
        NodeList nl = parent.getChildNodes();
        for (int i = 0; i < nl.getLength(); i++) {
            Node n = nl.item(i);
            if (n.getNodeType() != Node.ELEMENT_NODE) continue;
            Element e = (Element) n;
            if (!"Attr".equals(e.getLocalName())) return e;
        }
        return null;
    }

    private static List<String> collectAssignsFromOperationCalls(
            Element implOp, Map<String, List<String>> importedOpAssigns) {
        Element body = BxmlDomUtils.firstChildElement(implOp, "Body");
        if (body == null) return List.of();
        Set<String> calledOps = new LinkedHashSet<>();
        collectOperationCallNames(body, calledOps);
        List<String> result = new ArrayList<>();
        for (String opName : calledOps) {
            List<String> assigns = importedOpAssigns.get(opName);
            if (assigns != null) result.addAll(assigns);
        }
        return result;
    }

    private static void collectOperationCallNames(Element sub, Set<String> out) {
        if (sub == null) return;
        if ("Operation_Call".equals(sub.getLocalName())) {
            Element nameEl = BxmlDomUtils.firstChildElement(sub, "Name");
            if (nameEl != null) {
                Element idEl = BxmlDomUtils.firstChildElement(nameEl, "Id");
                if (idEl != null) {
                    String v = idEl.getAttribute("value");
                    if (v != null && !v.isBlank()) out.add(v.trim());
                }
            }
            return;
        }
        NodeList nl = sub.getChildNodes();
        for (int i = 0; i < nl.getLength(); i++) {
            Node n = nl.item(i);
            if (n.getNodeType() != Node.ELEMENT_NODE) continue;
            Element ch = (Element) n;
            if (!"Attr".equals(ch.getLocalName())) collectOperationCallNames(ch, out);
        }
    }

    /** Verifica recursivamente se {@code el} contém algum {@code Becomes_In} (B: {@code v :: S}). */
    private static boolean bodyHasBecomesIn(Element el) {
        if (el == null) return false;
        if ("Becomes_In".equals(el.getLocalName())) return true;
        NodeList nl = el.getChildNodes();
        for (int i = 0; i < nl.getLength(); i++) {
            Node n = nl.item(i);
            if (n.getNodeType() != Node.ELEMENT_NODE) continue;
            if (bodyHasBecomesIn((Element) n)) return true;
        }
        return false;
    }

    /**
     * Parâmetros de saída em C são ponteiros: {@code ret : S} no B traduz-se para
     * {@code belongs(*ret, BOOL)} ou {@code belongs((integer)*ret, S)} nos {@code requires}.
     */
    private static final Pattern REQUIRES_BELONGS =
            Pattern.compile(
                    "^belongs\\s*\\(\\s*(?:\\(integer\\)\\s*)?([A-Za-z_]\\w*)\\s*,\\s*([^)]+)\\s*\\)$");

    private static void rewriteRequiresForOutputParameters(
            List<String> requires, List<String> outputParams, Set<String> boolOutputParams) {
        if (requires == null || requires.isEmpty() || outputParams == null || outputParams.isEmpty()) {
            return;
        }
        Set<String> out = new HashSet<>(outputParams);
        Set<String> boolOut = boolOutputParams == null ? Set.of() : boolOutputParams;
        for (int i = 0; i < requires.size(); i++) {
            String req = requires.get(i);
            if (req == null || req.isBlank()) {
                continue;
            }
            Matcher m = REQUIRES_BELONGS.matcher(req.trim());
            if (!m.matches()) {
                continue;
            }
            String var = m.group(1).trim();
            String set = m.group(2).trim();
            if (!out.contains(var)) {
                continue;
            }
            // Saídas bool (C _Bool*) correspondem diretamente ao tipo lógico ACSL "boolean" — sem
            // cast. O cast (integer) só se aplica a saídas int/enum (ex.: cc : COPY em Biblioteca),
            // cujo domínio (Set<integer>) exige elevar o valor C para o tipo lógico "integer".
            // Aplicá-lo também a saídas bool comparadas contra um domínio Set<boolean> (ex.: BOOL)
            // provoca "invalid implicit conversion from integer to boolean" no Frama-C.
            String value = boolOut.contains(var) ? "*" + var : "(integer)*" + var;
            requires.set(i, "belongs(" + value + ", " + set + ")");
        }
    }

    /**
     * Comprimento e tipo do codomínio de um parâmetro array-backed ({@code xx : A --> B} na
     * precondição): {@code array_to_function_bool} quando {@code B} é {@code BOOL}, senão
     * {@code array_to_function_int}.
     */
    /**
     * Visibilidade de pacote: também usada por {@link BxmlSetsTranslator#collectMachineTextForIncludeScan}
     * para reproduzir a mesma reescrita {@code array_to_function_*(...)} na varredura de símbolos de
     * includes quando o {@code .acsl} real da máquina dependente ainda não foi gerado.
     */
    record ArrayFunctionParamInfo(String lengthExpr, boolean boolCodomain) {
        String functionName() {
            return boolCodomain ? "array_to_function_bool" : "array_to_function_int";
        }
    }

    /**
     * Em operações cuja entrada concreta é array/pointer representando função total em B
     * ({@code xx : A --> B}), reescreve chamadas de contrato que esperam {@code Function_int_int}
     * para usar {@code array_to_function_int(xx, len)}/{@code array_to_function_bool(xx, len)}.
     */
    /**
     * Reescreve em {@code strings} todas as ocorrências de parâmetros array-backed ({@code param}
     * como palavra isolada) para {@code array_to_function_int(param, len)}/{@code
     * array_to_function_bool(param, len)}. Não re-envolve se já está dentro de {@code
     * array_to_function_int(}/{@code array_to_function_bool(}.
     */
    static void rewriteAcslListForArrayBackedParams(
            List<String> strings, Map<String, ArrayFunctionParamInfo> infoByParam) {
        if (strings == null || strings.isEmpty() || infoByParam == null || infoByParam.isEmpty()) {
            return;
        }
        for (int i = 0; i < strings.size(); i++) {
            strings.set(i, rewriteAcslStringForArrayBackedParams(strings.get(i), infoByParam));
        }
    }

    private static String rewriteAcslStringForArrayBackedParams(
            String s, Map<String, ArrayFunctionParamInfo> infoByParam) {
        if (s == null || s.isBlank() || infoByParam == null || infoByParam.isEmpty()) return s;
        for (Map.Entry<String, ArrayFunctionParamInfo> e : infoByParam.entrySet()) {
            String p = e.getKey();
            ArrayFunctionParamInfo info = e.getValue();
            String fn = info.functionName();
            // Substitui ocorrências isoladas de p que não estejam já dentro de array_to_function_*(
            s = s.replaceAll(
                    "(?<!array_to_function_int\\()(?<!array_to_function_bool\\()\\b" + Pattern.quote(p) + "\\b",
                    fn + "(" + p + ", " + info.lengthExpr() + ")");
        }
        return s;
    }

    static Map<String, ArrayFunctionParamInfo> inferArrayFunctionParamLengths(
            Element operation, BxmlTranslateContext ctx) {
        Map<String, ArrayFunctionParamInfo> out = new LinkedHashMap<>();
        Element pre = BxmlDomUtils.firstChildElement(operation, "Precondition");
        if (pre == null) {
            return out;
        }
        collectArrayFunctionParamLengths(pre, ctx, out);
        return out;
    }

    private static void collectArrayFunctionParamLengths(
            Element pred, BxmlTranslateContext ctx, Map<String, ArrayFunctionParamInfo> out) {
        if (pred == null || ctx == null) {
            return;
        }
        String ln = pred.getLocalName();
        if ("Exp_Comparison".equals(ln)) {
            String op = pred.getAttribute("op");
            if (":".equals(op == null ? "" : op.trim())) {
                Element[] pair = BxmlExpressionToAcsl.twoDirectExpChildren(pred);
                if (pair[0] != null
                        && "Id".equals(pair[0].getLocalName())
                        && pair[1] != null
                        && BxmlExpressionToAcsl.isFunctionArrowType(pair[1])) {
                    String p = pair[0].getAttribute("value");
                    String len = arrayDomainCardinalityAcsl(pair[1], ctx);
                    if (p != null && !p.isBlank() && len != null && !len.isBlank()) {
                        out.put(p.trim(), new ArrayFunctionParamInfo(len, arrowCodomainIsBool(pair[1])));
                    }
                }
            }
        }
        NodeList nl = pred.getChildNodes();
        for (int i = 0; i < nl.getLength(); i++) {
            Node n = nl.item(i);
            if (n.getNodeType() != Node.ELEMENT_NODE) continue;
            Element ch = (Element) n;
            if ("Attr".equals(ch.getLocalName())) continue;
            collectArrayFunctionParamLengths(ch, ctx, out);
        }
    }

    /** Verdadeiro se o codomínio da seta {@code -->} for {@code BOOL}. */
    private static boolean arrowCodomainIsBool(Element arrowEl) {
        if (arrowEl == null) return false;
        Element[] domRng = BxmlExpressionToAcsl.twoDirectExpChildren(arrowEl);
        if (domRng[1] == null) return false;
        Element codomain = domRng[1];
        return "Id".equals(codomain.getLocalName()) && "BOOL".equals(codomain.getAttribute("value"));
    }

    private static String arrayDomainCardinalityAcsl(Element arrowEl, BxmlTranslateContext ctx) {
        Element[] domRng = BxmlExpressionToAcsl.twoDirectExpChildren(arrowEl);
        if (domRng[0] == null) {
            return null;
        }
        Element domain = domRng[0];
        if (!BxmlExpressionToAcsl.isIntervalBinaryExp(domain)) {
            return null;
        }
        Element[] lr = BxmlExpressionToAcsl.twoDirectExpChildren(domain);
        if (lr[0] == null || lr[1] == null) {
            return null;
        }
        String low = BxmlExpressionToAcsl.translate(lr[0], ctx).trim();
        String high = BxmlExpressionToAcsl.translate(lr[1], ctx).trim();
        if (low.isBlank() || high.isBlank()) {
            return null;
        }
        return "(" + high + " - (" + low + ") + 1)";
    }

    public record OperationAcsl(
            String functionName,
            List<String> requires,
            List<String> ensures,
            /** Parâmetros de saída B (sem {@code *}); viram {@code assigns *nome}. */
            List<String> outputParameters,
            /** Subconjunto de {@code outputParameters} cujo tipo B é função/relação (POW(X*Y)); viram {@code assigns nome[..]}. */
            Set<String> functionTypedOutputParameters,
            /**
             * Para cada nome em {@code functionTypedOutputParameters} com limite superior conhecido
             * (derivado do laço da implementação, ex. {@code "MAX_COPY"}), o {@code requires}/{@code
             * assigns} usa {@code p + (0 .. bound)} em vez de {@code \valid(p)}/{@code p[..]} — que só
             * cobrem {@code *p} e podem ser reduzidos pelo Frama-C ao reimprimir o merge.
             */
            Map<String, String> functionTypedOutputBounds,
            /** Vazio se a operação for pura face às variáveis abstratas; senão slug para {@code ghost__<slug>(…)}. */
            String ghostBehaviorSlug,
            /** Nomes dos parâmetros de entrada para o {@code assert ghost__…}; ordem do BXML. */
            List<String> ghostBehaviorInputNames,
            /**
             * Sufixos {@code v} para {@code ensures dummy_ghost_<v>;} (operações não puras ghost); vazio se pura.
             */
            List<String> dummyGhostEnsureVarNames,
            /**
             * {@code assigns Raiz__v} para variáveis concretas ligadas por refinamento quando a operação
             * ghost altera a abstrata (omitidas se já redundantes).
             */
            List<String> connectionConcreteAssigns,
            List<BxmlLoopTranslator.LoopContract> loops,
            /** Verdadeiro quando o corpo da operação é somente {@code skip}. */
            boolean skipBody) {

        public OperationAcsl {
            functionTypedOutputParameters =
                    functionTypedOutputParameters == null ? Set.of() : Set.copyOf(functionTypedOutputParameters);
            functionTypedOutputBounds =
                    functionTypedOutputBounds == null ? Map.of() : Map.copyOf(functionTypedOutputBounds);
            dummyGhostEnsureVarNames =
                    dummyGhostEnsureVarNames == null ? List.of() : List.copyOf(dummyGhostEnsureVarNames);
            connectionConcreteAssigns =
                    connectionConcreteAssigns == null ? List.of() : List.copyOf(connectionConcreteAssigns);
            loops = loops == null ? List.of() : List.copyOf(loops);
        }

        public OperationAcsl(
                String functionName,
                List<String> requires,
                List<String> ensures,
                List<String> outputParameters,
                String ghostBehaviorSlug,
                List<String> ghostBehaviorInputNames,
                List<String> dummyGhostEnsureVarNames,
                List<String> connectionConcreteAssigns) {
            this(
                    functionName,
                    requires,
                    ensures,
                    outputParameters,
                    Set.of(),
                    Map.of(),
                    ghostBehaviorSlug,
                    ghostBehaviorInputNames,
                    dummyGhostEnsureVarNames,
                    connectionConcreteAssigns,
                    List.of(),
                    false);
        }

        /** Mesmo esquema que {@link com.example.bxml.InitialisationAcsl#toContractText()}. */
        public String toContractSketch() {
            if (skipBody) {
                return "function " + functionName + ":\ncontract:    \n"
                        + "    requires \\true;\n"
                        + "    ensures \\true;\n";
            }
            StringBuilder sb = new StringBuilder();
            sb.append("function ").append(functionName).append(":\n");
            sb.append("contract:    \n");
            for (String r : requires) {
                sb.append("    requires  ").append(r).append(";\n");
            }
            for (String e : ensures) {
                sb.append("    ensures  ").append(e).append(";\n");
            }
            for (String v : dummyGhostEnsureVarNames) {
                sb.append("    ensures  dummy_ghost_").append(v).append(";\n");
            }
            boolean hasAssigns = false;
            for (String p : outputParameters) {
                if (functionTypedOutputParameters.contains(p)) {
                    String bound = functionTypedOutputBounds.get(p);
                    if (bound != null) {
                        sb.append("    assigns *(").append(p).append(" + (0 .. ").append(bound).append("));\n");
                    } else {
                        sb.append("    assigns ").append(p).append("[..];\n");
                    }
                } else {
                    sb.append("    assigns *").append(p).append(";\n");
                }
                hasAssigns = true;
            }
            LinkedHashSet<String> connectionEmitted = new LinkedHashSet<>();
            for (String ca : connectionConcreteAssigns) {
                if (ca == null || ca.isBlank()) {
                    continue;
                }
                if (!connectionEmitted.add(ca)) {
                    continue;
                }
                String clause = "assigns " + ca + ";";
                if (sb.indexOf(clause) >= 0) {
                    continue;
                }
                sb.append("    assigns ").append(ca).append(";\n");
                hasAssigns = true;
            }
            if (!hasAssigns) {
                sb.append("    assigns \\nothing;\n");
            }
            if (ghostBehaviorSlug != null && !ghostBehaviorSlug.isBlank()) {
                sb.append("    at return: assert ghost__").append(ghostBehaviorSlug);
                if (ghostBehaviorInputNames != null && !ghostBehaviorInputNames.isEmpty()) {
                    sb.append("(").append(String.join(", ", ghostBehaviorInputNames)).append(")");
                }
                sb.append(";\n");
            }
            for (BxmlLoopTranslator.LoopContract loop : loops) {
                sb.append("    at loop ").append(loop.index()).append(":\n");
                if (loop.invariant() != null && !loop.invariant().isBlank()) {
                    sb.append("        loop invariant (").append(loop.invariant()).append(");\n");
                }
                if (!loop.assigns().isEmpty()) {
                    sb.append("        loop assigns ").append(String.join(", ", loop.assigns())).append(";\n");
                }
                if (loop.variant() != null && !loop.variant().isBlank()) {
                    sb.append("        loop variant (").append(loop.variant()).append(");\n");
                }
            }
            return sb.toString();
        }
    }
}
