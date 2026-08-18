package com.example.bxml;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * Resolve alvos de {@code assigns} de variáveis concretas ligadas a variáveis abstratas por
 * refinamento — inicialização, operação, e por-laço, incluindo a faixa de índice ({@code [low..high]})
 * quando a variável é array/função total. Extraído de {@code BxmlMachineVariables} (WMC=488, God-class
 * por WMC) por extract-class puro: nenhuma linha de lógica mudou, só o arquivo em que vive.
 */
public final class ConcreteAssignTargetResolver {

    private ConcreteAssignTargetResolver() {}

    /**
     * Alvos {@code Raiz__c} para {@code assigns} quando uma operação na abstrata altera variáveis em
     * {@code assignedAbstractNames}: propaga-se a ligação ao longo da cadeia fundida (refinamento →
     * implementação) e escolhem-se variáveis concretas cujo invariante referencia nomes já ligados.
     */
    public static List<String> listConcreteAssignTargetsForAbstractMutation(
            String rootAbstractMachineName,
            Element rootMachine,
            List<Element> mergedOrdered,
            Set<String> assignedAbstractNames,
            Map<String, String> gluing,
            BxmlTranslateContext ctx) {
        return listConcreteAssignTargetsForAbstractMutation(
                rootAbstractMachineName, rootMachine, mergedOrdered, assignedAbstractNames, gluing,
                ctx, null);
    }

    public static List<String> listConcreteAssignTargetsForAbstractMutation(
            String rootAbstractMachineName,
            Element rootMachine,
            List<Element> mergedOrdered,
            Set<String> assignedAbstractNames,
            Map<String, String> gluing,
            BxmlTranslateContext ctx,
            Path bxmlDirectory) {
        if (rootAbstractMachineName == null
                || rootAbstractMachineName.isBlank()
                || rootMachine == null
                || mergedOrdered == null
                || mergedOrdered.isEmpty()
                || assignedAbstractNames == null
                || assignedAbstractNames.isEmpty()) {
            return List.of();
        }
        Map<String, String> g = gluing == null ? Map.of() : gluing;
        Set<String> linked = new LinkedHashSet<>(assignedAbstractNames);
        Element prev = rootMachine;
        LinkedHashSet<String> targets = new LinkedHashSet<>();
        for (Element mel : mergedOrdered) {
            if (BxmlMachineVariables.isRefinementMachine(mel)) {
                for (String refined : BxmlConnectionAcsl.introducedStateVariableIds(mel)) {
                    Optional<String> abs =
                            BxmlConnectionAcsl.linkingAbstractVariableName(prev, mel, refined, g);
                    if (abs.isPresent() && linked.contains(abs.get())) {
                        linked.add(refined);
                    }
                }
                prev = mel;
            } else if (BxmlMachineVariables.isImplementationMachine(mel)) {
                Set<String> invIds = BxmlConnectionAcsl.invariantReferencedIdentifiers(mel);
                if (!Collections.disjoint(invIds, linked)) {
                    for (String c : BxmlConnectionAcsl.introducedStateVariableIds(mel)) {
                        if (invIds.contains(c)) {
                            String base = rootAbstractMachineName.trim() + "__" + c;
                            String ranged =
                                    implementationAssignTargetWithRange(
                                            base, c, mel, ctx, bxmlDirectory);
                            targets.add(ranged == null ? base : ranged);
                        }
                    }
                }
                prev = mel;
            } else {
                prev = mel;
            }
        }
        return new ArrayList<>(targets);
    }

    /**
     * Alvos {@code Raiz__v} da implementação para variáveis abstratas atribuídas na operação (modo
     * directo, sem invariante na implementação).
     */
    public static List<String> listImplementationAssignTargetsForAbstractVariables(
            String abstractMachineName,
            List<Element> mergedMachineElements,
            Set<String> abstractVariableNames,
            BxmlTranslateContext ctx) {
        if (abstractMachineName == null
                || abstractMachineName.isBlank()
                || abstractVariableNames == null
                || abstractVariableNames.isEmpty()) {
            return List.of();
        }
        String prefix = abstractMachineName.trim() + "__";
        List<String> out = new ArrayList<>();
        for (String target :
                listImplementationAssignTargets(abstractMachineName, mergedMachineElements, ctx)) {
            if (target == null || !target.startsWith(prefix)) {
                continue;
            }
            String var = target.substring(prefix.length());
            int bracket = var.indexOf('[');
            if (bracket >= 0) {
                var = var.substring(0, bracket);
            }
            if (abstractVariableNames.contains(var)) {
                out.add(target);
            }
        }
        return out;
    }

    /**
     * Alvos {@code Raiz__v} para {@code assigns} na inicialização quando a implementação fundida não
     * declara variáveis e reutiliza o estado da abstração ligado ao C ({@code logic v = Raiz__v;}).
     */
    public static List<String> listLinkedConcreteAssignTargetsForInitialisation(
            String abstractMachineName,
            Element abstractMachineEl,
            List<Element> mergedMachineElements) {
        return listLinkedConcreteAssignTargetsForInitialisation(
                abstractMachineName, abstractMachineEl, mergedMachineElements, null);
    }

    public static List<String> listLinkedConcreteAssignTargetsForInitialisation(
            String abstractMachineName,
            Element abstractMachineEl,
            List<Element> mergedMachineElements,
            BxmlTranslateContext ctx) {
        return listLinkedConcreteAssignTargetsForInitialisation(
                abstractMachineName, abstractMachineEl, mergedMachineElements, ctx, Map.of());
    }

    public static List<String> listLinkedConcreteAssignTargetsForInitialisation(
            String abstractMachineName,
            Element abstractMachineEl,
            List<Element> mergedMachineElements,
            BxmlTranslateContext ctx,
            Map<String, String> varRhsOverrides) {
        // Duas formas de a máquina abstrata ligar as suas variáveis diretamente ao C, sem camada
        // ghost: uma implementação separada que replica exatamente as variáveis abstratas
        // (anyImplementationUsesAbstractVariablesOnly), OU a máquina abstrata não ter NENHUMA
        // implementação e ligar o seu próprio array C sozinha (abstractMachineIsSelfContainedArrayBacked,
        // ex.: cv_rel, sem _i.bxml separado). Faltava o segundo caso aqui: sem ele,
        // cv_rel__INITIALISATION (que escreve cv_rel__fmap[0..2]) ficava sem nenhum assigns
        // target, caindo no fallback "assigns \nothing" de InitialisationAcsl — contrato UNSOUND
        // (a função escreve memória global que a assinatura diz não escrever).
        if (abstractMachineName == null
                || abstractMachineName.isBlank()
                || abstractMachineEl == null
                || !(BxmlMachineVariables.anyImplementationUsesAbstractVariablesOnly(
                                abstractMachineEl, mergedMachineElements)
                        || BxmlMachineVariables.abstractMachineIsSelfContainedArrayBacked(
                                abstractMachineEl, mergedMachineElements))) {
            return List.of();
        }
        Set<String> declared = BxmlMachineVariables.declaredVariableNames(abstractMachineEl);
        Set<String> assigned =
                GhostContractPredicates.variablesAssignedInInitialisation(
                        abstractMachineEl, declared);
        if (assigned.isEmpty()) {
            return List.of();
        }
        // Skip vars whose C name comes from an imported machine (covered by importedAssigns)
        if (varRhsOverrides != null && !varRhsOverrides.isEmpty()) {
            Set<String> filtered = new LinkedHashSet<>();
            for (String v : assigned) {
                if (v != null && !varRhsOverrides.containsKey(v.trim())) filtered.add(v);
            }
            assigned = filtered;
        }
        if (assigned.isEmpty()) return List.of();
        if (ctx == null) {
            return linkedConcreteAssignTargetsForVariableNames(abstractMachineName, assigned);
        }
        String prefix = abstractMachineName.trim() + "__";
        List<String> out = new ArrayList<>();
        for (String v : assigned) {
            if (v == null || v.isBlank()) continue;
            String base = prefix + v.trim();
            String ranged = implementationAssignTargetWithRange(base, v.trim(), abstractMachineEl, ctx);
            out.add(ranged == null ? base : ranged);
        }
        return out;
    }

    /**
     * Alvos {@code Raiz__v} para {@code assigns} numa operação quando a implementação fundida não
     * declara variáveis e a operação altera variáveis da abstração ligadas ao C.
     */
    public static List<String> listLinkedConcreteAssignTargetsForOperation(
            String abstractMachineName,
            Element abstractMachineEl,
            List<Element> mergedMachineElements,
            Set<String> assignedVariableNames) {
        return listLinkedConcreteAssignTargetsForOperation(
                abstractMachineName, abstractMachineEl, mergedMachineElements, assignedVariableNames, null);
    }

    public static List<String> listLinkedConcreteAssignTargetsForOperation(
            String abstractMachineName,
            Element abstractMachineEl,
            List<Element> mergedMachineElements,
            Set<String> assignedVariableNames,
            BxmlTranslateContext ctx) {
        // Ver o mesmo OR em listLinkedConcreteAssignTargetsForInitialisation: sem
        // abstractMachineIsSelfContainedArrayBacked aqui, uma operação (ex. cv_rel__upd, que
        // escreve cv_rel__fmap[ii]) de uma máquina abstrata sem implementação separada ficava sem
        // assigns target, caindo em "assigns \nothing" — contrato UNSOUND.
        if (abstractMachineName == null
                || abstractMachineName.isBlank()
                || abstractMachineEl == null
                || assignedVariableNames == null
                || assignedVariableNames.isEmpty()
                || !(BxmlMachineVariables.anyImplementationUsesAbstractVariablesOnly(
                                abstractMachineEl, mergedMachineElements)
                        || BxmlMachineVariables.abstractMachineIsSelfContainedArrayBacked(
                                abstractMachineEl, mergedMachineElements))) {
            return List.of();
        }
        Set<String> declared = BxmlMachineVariables.declaredVariableNames(abstractMachineEl);
        Set<String> filtered = new LinkedHashSet<>();
        for (String v : assignedVariableNames) {
            if (v != null && declared.contains(v.trim())) {
                filtered.add(v.trim());
            }
        }
        if (ctx == null) {
            return linkedConcreteAssignTargetsForVariableNames(abstractMachineName, filtered);
        }
        String prefix = abstractMachineName.trim() + "__";
        List<String> out = new ArrayList<>();
        for (String v : filtered) {
            String base = prefix + v;
            String ranged = implementationAssignTargetWithRange(base, v, abstractMachineEl, ctx);
            out.add(ranged == null ? base : ranged);
        }
        return out;
    }

    /**
     * Como {@link #listLinkedConcreteAssignTargetsForOperation(String, Element, List, Set, BxmlTranslateContext)},
     * mas omite variáveis cujo estado C real provém de máquina importada (já cobertas por
     * {@code importedOpAssigns}), conforme indicado pelo mapa {@code varRhsOverrides}.
     */
    public static List<String> listLinkedConcreteAssignTargetsForOperation(
            String abstractMachineName,
            Element abstractMachineEl,
            List<Element> mergedMachineElements,
            Set<String> assignedVariableNames,
            BxmlTranslateContext ctx,
            Map<String, String> varRhsOverrides) {
        if (varRhsOverrides == null || varRhsOverrides.isEmpty()) {
            return listLinkedConcreteAssignTargetsForOperation(
                    abstractMachineName, abstractMachineEl, mergedMachineElements,
                    assignedVariableNames, ctx);
        }
        if (abstractMachineName == null
                || abstractMachineName.isBlank()
                || abstractMachineEl == null
                || assignedVariableNames == null
                || assignedVariableNames.isEmpty()
                || !(BxmlMachineVariables.anyImplementationUsesAbstractVariablesOnly(
                                abstractMachineEl, mergedMachineElements)
                        || BxmlMachineVariables.abstractMachineIsSelfContainedArrayBacked(
                                abstractMachineEl, mergedMachineElements))) {
            return List.of();
        }
        Set<String> declared = BxmlMachineVariables.declaredVariableNames(abstractMachineEl);
        Set<String> filtered = new LinkedHashSet<>();
        for (String v : assignedVariableNames) {
            if (v != null && declared.contains(v.trim()) && !varRhsOverrides.containsKey(v.trim())) {
                filtered.add(v.trim());
            }
        }
        if (filtered.isEmpty()) return List.of();
        if (ctx == null) {
            return linkedConcreteAssignTargetsForVariableNames(abstractMachineName, filtered);
        }
        String prefix = abstractMachineName.trim() + "__";
        List<String> out = new ArrayList<>();
        for (String v : filtered) {
            String base = prefix + v;
            String ranged = implementationAssignTargetWithRange(base, v, abstractMachineEl, ctx);
            out.add(ranged == null ? base : ranged);
        }
        return out;
    }

    private static List<String> linkedConcreteAssignTargetsForVariableNames(
            String abstractMachineName, Set<String> variableNames) {
        if (abstractMachineName == null
                || abstractMachineName.isBlank()
                || variableNames == null
                || variableNames.isEmpty()) {
            return List.of();
        }
        String prefix = abstractMachineName.trim() + "__";
        List<String> out = new ArrayList<>();
        for (String v : variableNames) {
            if (v != null && !v.isBlank()) {
                out.add(prefix + v.trim());
            }
        }
        return out;
    }

    /**
     * Alvos {@code assigns} da inicialização: variáveis concretas da implementação fundida, ou
     * {@code Raiz__v} para variáveis da abstração atribuídas na init quando a implementação não
     * declara estado próprio.
     */
    public static List<String> listInitialisationAssignTargets(
            String abstractMachineName,
            Element abstractMachineEl,
            List<Element> mergedMachineElements,
            BxmlTranslateContext ctx) {
        return listInitialisationAssignTargets(
                abstractMachineName, abstractMachineEl, mergedMachineElements, ctx, Map.of());
    }

    public static List<String> listInitialisationAssignTargets(
            String abstractMachineName,
            Element abstractMachineEl,
            List<Element> mergedMachineElements,
            BxmlTranslateContext ctx,
            Map<String, String> varRhsOverrides) {
        return listInitialisationAssignTargets(
                abstractMachineName, abstractMachineEl, mergedMachineElements, ctx, varRhsOverrides,
                null);
    }

    public static List<String> listInitialisationAssignTargets(
            String abstractMachineName,
            Element abstractMachineEl,
            List<Element> mergedMachineElements,
            BxmlTranslateContext ctx,
            Map<String, String> varRhsOverrides,
            Path bxmlDirectory) {
        List<String> fromImpl =
                listImplementationAssignTargets(
                        abstractMachineName, mergedMachineElements, ctx, bxmlDirectory);
        if (!fromImpl.isEmpty()) {
            return fromImpl;
        }
        return listLinkedConcreteAssignTargetsForInitialisation(
                abstractMachineName, abstractMachineEl, mergedMachineElements, ctx,
                varRhsOverrides == null ? Map.of() : varRhsOverrides);
    }

    public static List<String> listImplementationAssignTargets(
            String abstractMachineName, List<Element> mergedMachineElements) {
        return listImplementationAssignTargets(abstractMachineName, mergedMachineElements, null);
    }

    /**
     * Como {@link #listImplementationAssignTargets(String, List)}, mas quando a variável concreta
     * é array (domínio de função total com intervalo), gera alvo com fatia ACSL:
     * {@code Raiz__v[low .. high]}.
     */
    public static List<String> listImplementationAssignTargets(
            String abstractMachineName,
            List<Element> mergedMachineElements,
            BxmlTranslateContext ctx) {
        return listImplementationAssignTargets(abstractMachineName, mergedMachineElements, ctx, null);
    }

    public static List<String> listImplementationAssignTargets(
            String abstractMachineName,
            List<Element> mergedMachineElements,
            BxmlTranslateContext ctx,
            Path bxmlDirectory) {
        if (abstractMachineName == null || abstractMachineName.isBlank()) return List.of();
        List<String> out = new ArrayList<>();
        for (Element mel : mergedMachineElements) {
            if (!BxmlMachineVariables.isImplementationMachine(mel)) continue;
            Element concrete = BxmlDomUtils.firstChildElement(mel, "Concrete_Variables");
            if (concrete == null) continue;
            NodeList ch = concrete.getChildNodes();
            for (int i = 0; i < ch.getLength(); i++) {
                Node n = ch.item(i);
                if (n.getNodeType() != Node.ELEMENT_NODE) continue;
                Element e = (Element) n;
                if ("Attr".equals(e.getLocalName())) continue;
                if (!"Id".equals(e.getLocalName())) continue;
                String v = e.getAttribute("value");
                if (v != null && !v.isBlank()) {
                    String base = abstractMachineName + "__" + v;
                    String ranged =
                            implementationAssignTargetWithRange(base, v, mel, ctx, bxmlDirectory);
                    out.add(ranged == null ? base : ranged);
                }
            }
        }
        return out;
    }

    /**
     * Qualifica um nome de variável para uso em {@code loop assigns}: se for uma variável declarada
     * na máquina abstrata, retorna {@code machineName__varName} (com {@code [range]} para arrays);
     * caso contrário (variável local {@code VAR_IN} ou parâmetro de saída), retorna o nome como-está.
     */
    public static String qualifyLoopAssignTarget(
            String varName, String machineName, Element abstractMachineEl, BxmlTranslateContext ctx) {
        return qualifyLoopAssignTarget(varName, machineName, abstractMachineEl, ctx, null);
    }

    /**
     * Como acima, mas com {@code bxmlDirectory}: quando o domínio do array é um conjunto nomeado
     * valorado só numa máquina VISTA (SEES) — ex. {@code PERSON} valorado em {@code ContextI}, visto
     * por {@code RegisterI} — resolve o intervalo exato ({@code low .. high}) em vez de cair para
     * {@code [..]} (correto para WP, mas menos legível que a faixa explícita já usada no {@code
     * assigns} da função).
     */
    public static String qualifyLoopAssignTarget(
            String varName, String machineName, Element abstractMachineEl, BxmlTranslateContext ctx,
            Path bxmlDirectory) {
        return qualifyLoopAssignTarget(varName, machineName, abstractMachineEl, null, ctx, bxmlDirectory);
    }

    /**
     * Como acima, mas também recebe a máquina de IMPLEMENTAÇÃO real ({@link
     * BxmlMachineVariables#enclosingMachineElement}) — necessária para variáveis concretas {@code _i}
     * (ex. {@code player_islands_i}), declaradas SÓ na implementação (a sua {@code Concrete_Variables}
     * própria), nunca em {@code abstractMachineEl}. Sem isto, {@code
     * BxmlMachineVariables.declaredVariableNames(abstractMachineEl)}
     * nunca as contém e o nome sai NÃO-qualificado (sem prefixo {@code machineName__}, sem faixa) —
     * o Frama-C tolera isso (impreciso) para arrays 1D, mas rejeita de vez para 2D genuíno ("not an
     * assignable left value"). {@code implementationMachineEl} também substitui
     * {@code abstractMachineEl} como fonte da faixa ({@link #implementationAssignTargetWithRange}
     * procura o tipo {@code -->} no {@code Invariant} PRÓPRIO do elemento recebido — só a
     * implementação declara o tipo das suas próprias variáveis concretas).
     */
    public static String qualifyLoopAssignTarget(
            String varName, String machineName, Element abstractMachineEl, Element implementationMachineEl,
            BxmlTranslateContext ctx, Path bxmlDirectory) {
        if (varName == null || varName.isBlank() || machineName == null || abstractMachineEl == null) {
            return varName;
        }
        boolean declaredAbstract = BxmlMachineVariables.declaredVariableNames(abstractMachineEl).contains(varName);
        boolean declaredConcrete =
                implementationMachineEl != null
                        && BxmlMachineVariables.declaredVariableNames(implementationMachineEl).contains(varName);
        if (!declaredAbstract && !declaredConcrete) {
            return varName;
        }
        String base = machineName.trim() + "__" + varName;
        // Tenta a implementação PRIMEIRO (variáveis concretas só-_i, ex. player_islands_i, cujo
        // tipo -->  só existe no Invariant da implementação), mas cai para a abstrata se não achar
        // — variáveis COLAPSADAS (mesmo nome abstrata/concreta, sem Concrete_Variables própria, ex.
        // filling_array's "Array") têm o seu tipo --> SÓ no Invariant abstrato; preferir SEMPRE a
        // implementação (uma versão anterior deste fix fazia isso) as deixava sem faixa nenhuma
        // (implementationAssignTargetWithRange retornava null por não achar Array no Invariant da
        // implementação) — "loop assigns array__Array" sem "[0..NN]", que o Frama-C rejeita para
        // arrays reais ("not an assignable left value"). Descoberto ao rodar filling_array pela
        // primeira vez nesta sessão (regressão real do fix de player_islands_i/RulerOfTheSeas).
        String ranged = implementationMachineEl != null
                ? implementationAssignTargetWithRange(base, varName, implementationMachineEl, ctx, bxmlDirectory)
                : null;
        if (ranged == null) {
            ranged = implementationAssignTargetWithRange(base, varName, abstractMachineEl, ctx, bxmlDirectory);
        }
        return ranged != null ? ranged : base;
    }

    private static String implementationAssignTargetWithRange(
            String baseTarget, String varName, Element implMachineEl, BxmlTranslateContext ctx) {
        return implementationAssignTargetWithRange(baseTarget, varName, implMachineEl, ctx, null);
    }

    static String implementationAssignTargetWithRange(
            String baseTarget,
            String varName,
            Element implMachineEl,
            BxmlTranslateContext ctx,
            Path bxmlDirectory) {
        if (baseTarget == null || varName == null || implMachineEl == null || ctx == null) {
            return null;
        }
        Element arrow = BxmlMachineVariables.concreteVariableFunctionArrowElement(implMachineEl, varName);
        if (arrow == null) {
            return null;
        }
        Element domain = BxmlExpressionToAcsl.twoDirectExpChildren(arrow)[0];
        if (BxmlMachineVariables.isCompoundProductExp(domain)) {
            // Domínio composto (matriz característica 2D, ex. player_islands_i : PLAYER*ISLAND -->
            // BOOL, ver implementationRhsTotalFunctionFromArray) — "assigns X[..];" é sintaxe de UMA
            // dimensão só; para um array C genuinamente 2D o kernel rejeita com "not an assignable
            // left value" (não infere a segunda dimensão implicitamente). "[..][..]" (ambas as
            // dimensões por extenso) é o análogo direto do "[..]" de 1D já usado abaixo — mesma
            // equivalência WP já confirmada para arrays de tamanho fixo (ver
            // project_assigns_range_seen_machine), só precisa de UM "[..]" por dimensão.
            return baseTarget + "[..][..]";
        }
        String range = arrayDomainRangeAcsl(arrow, ctx);
        if (range == null || range.isBlank()) {
            // Domínio é um conjunto nomeado (Id) — tenta resolver via <Values> da implementação
            // (ou, se ausente, das máquinas VISTAS — ver resolveNamedSetDomainRange).
            range = resolveNamedSetDomainRange(arrow, implMachineEl, ctx, bxmlDirectory);
        }
        if (range == null || range.isBlank()) {
            return baseTarget + "[..]";
        }
        return baseTarget + "[" + range + "]";
    }

    /** Intervalo do domínio de {@code -->} (ex. {@code 0..maximum} -> {@code 0 .. maximum}). */
    private static String arrayDomainRangeAcsl(Element arrowEl, BxmlTranslateContext ctx) {
        if (arrowEl == null || ctx == null) {
            return null;
        }
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
        return low + " .. " + high;
    }

    /**
     * Quando o domínio da seta {@code -->} é um conjunto nomeado (elemento {@code Id}),
     * procura a sua valoração na secção {@code <Values>} da máquina de implementação e,
     * se for um intervalo literal ({@code Binary_Exp op='..'}), retorna {@code "low .. high"}.
     * Quando o conjunto nomeado não está valorado na própria {@code implMachineEl} (ex.
     * {@code GOODS} visto por {@code Price}/{@code Price_i} mas valorado em {@code Goods_i}),
     * procura também nas máquinas VISTAS (SEES) — mesmo problema e mesma solução de
     * {@link #lookupSetCardinalityFromValues(String, Element, BxmlTranslateContext, Path)}, mas
     * devolvendo o intervalo {@code "low .. high"} em vez da cardinalidade.
     */
    private static String resolveNamedSetDomainRange(
            Element arrowEl, Element implMachineEl, BxmlTranslateContext ctx, Path bxmlDirectory) {
        if (arrowEl == null || implMachineEl == null || ctx == null) return null;
        Element[] domRng = BxmlExpressionToAcsl.twoDirectExpChildren(arrowEl);
        if (domRng[0] == null) return null;
        Element domain = domRng[0];
        if (!"Id".equals(domain.getLocalName())) return null;
        String setName = domain.getAttribute("value");
        if (setName == null || setName.isBlank()) return null;

        String local = rangeFromOwnValues(setName, implMachineEl, ctx);
        if (local != null || bxmlDirectory == null) {
            return local;
        }
        for (String seenName : BxmlSetsTranslator.listReferencedMachineNames(implMachineEl)) {
            Element seenEl = BxmlSetsTranslator.findImplementationMachineElement(seenName, bxmlDirectory);
            if (seenEl == null) continue;
            String found = rangeFromOwnValues(setName, seenEl, ctx);
            if (found != null) return found;
        }
        return null;
    }

    private static String rangeFromOwnValues(String setName, Element machineEl, BxmlTranslateContext ctx) {
        Element valuesEl = BxmlDomUtils.firstChildElement(machineEl, "Values");
        if (valuesEl == null) return null;

        // Procura <Valuation ident='setName'>
        NodeList nl = valuesEl.getChildNodes();
        for (int i = 0; i < nl.getLength(); i++) {
            org.w3c.dom.Node n = nl.item(i);
            if (n.getNodeType() != org.w3c.dom.Node.ELEMENT_NODE) continue;
            Element valEl = (Element) n;
            if (!"Valuation".equals(valEl.getLocalName())) continue;
            if (!setName.equals(valEl.getAttribute("ident"))) continue;

            // Pega o primeiro filho não-Attr (a expressão de valor)
            NodeList children = valEl.getChildNodes();
            for (int j = 0; j < children.getLength(); j++) {
                org.w3c.dom.Node cn = children.item(j);
                if (cn.getNodeType() != org.w3c.dom.Node.ELEMENT_NODE) continue;
                Element valExpr = (Element) cn;
                if ("Attr".equals(valExpr.getLocalName())) continue;
                if (!BxmlExpressionToAcsl.isIntervalBinaryExp(valExpr)) return null;
                Element[] lr = BxmlExpressionToAcsl.twoDirectExpChildren(valExpr);
                if (lr[0] == null || lr[1] == null) return null;
                String low = BxmlExpressionToAcsl.translate(lr[0], ctx).trim();
                String high = BxmlExpressionToAcsl.translate(lr[1], ctx).trim();
                if (low.isBlank() || high.isBlank()) return null;
                return low + " .. " + high;
            }
        }
        return null;
    }

}
