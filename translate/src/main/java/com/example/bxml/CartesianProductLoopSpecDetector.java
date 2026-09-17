package com.example.bxml;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * Deteta e traduz o padrão {@code ARRAY := DOMAIN * {VALUE}} em B (inicialização de array/função
 * total por produto cartesiano) para a especificação de loop {@code while(i <= HI)} equivalente,
 * incluindo resolução de domínio nomeado via {@code <Values>} da implementação. Extraído de
 * {@code BxmlInitialisationTranslator} (WMC=315, o maior God Class do projeto) por extract-class
 * puro: nenhuma linha de lógica mudou, só o arquivo em que vive.
 */
public final class CartesianProductLoopSpecDetector {

    private CartesianProductLoopSpecDetector() {}

    /**
     * Especificação de loop(s) de inicialização gerada por uma atribuição do padrão
     * {@code ARRAY := DOMAIN * {VALUE}} (1D, ver {@link CartesianProductLoopSpec}) ou
     * {@code ARRAY := DOMAIN1 * DOMAIN2 * {VALUE}} (2D — matriz característica, ver {@link
     * CartesianProduct2DLoopSpec}) em B — o compilador C emite, respetivamente, um único
     * {@code while(i <= HI)} ou um {@code while(i <= HI1) { while(j <= HI2) {...} }} aninhado.
     */
    public sealed interface LoopInitSpec permits CartesianProductLoopSpec, CartesianProduct2DLoopSpec {}

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
            String valueExpr) implements LoopInitSpec {}

    /**
     * Como {@link CartesianProductLoopSpec}, mas para o padrão 2D {@code ARRAY := DOMAIN1 * DOMAIN2
     * * {VALUE}} (matriz característica, ex.: {@code player_islands_i := PLAYER * ISLAND *
     * {FALSE}}) — o compilador C emite um loop {@code while(i <= HI1) { while(j <= HI2) {
     * array[i][j] = VALUE; j++; } i++; }} aninhado (índices row-major, mesma convenção de {@link
     * BxmlMachineVariables#isCompoundProductExp}/{@code array2d_to_relation_*}). Duas entradas
     * {@code at loop N:} são necessárias (uma por loop, externo depois interno — o
     * {@code -acsl-import} do Frama-C pareia por ORDEM de leitura do corpo C, não por nome), ver
     * {@link InitialisationAcsl#toContractText()}.
     */
    public record CartesianProduct2DLoopSpec(
            String outerCounterVar,
            String outerLoExpr,
            String outerHiExpr,
            String innerCounterVar,
            String innerLoExpr,
            String innerHiExpr,
            String cArrayName,
            String valueExpr) implements LoopInitSpec {}

    /** Coleta todas as especificações de loop da sequência de substituições da inicialização. */
    static List<LoopInitSpec> detectAllLoopSpecs(
            Element sub, Element implMachineEl, BxmlTranslateContext ctx, Path bxmlDirectory) {
        if (sub == null) return List.of();
        List<LoopInitSpec> result = new ArrayList<>();
        collectLoopSpecs(sub, implMachineEl, ctx, bxmlDirectory, result);
        return List.copyOf(result);
    }

    private static void collectLoopSpecs(
            Element sub, Element implMachineEl, BxmlTranslateContext ctx, Path bxmlDirectory,
            List<LoopInitSpec> result) {
        if (sub == null) return;
        switch (sub.getLocalName()) {
            case "Assignement_Sub" -> {
                LoopInitSpec spec =
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
            case "Bloc_Sub" -> collectLoopSpecs(BxmlDomUtils.firstSubChild(sub), implMachineEl, ctx, bxmlDirectory, result);
        }
    }

    private static LoopInitSpec cartesianProductLoopSpecFromAssignment(
            Element assign, Element implMachineEl, BxmlTranslateContext ctx, Path bxmlDirectory) {
        Element varsEl = BxmlDomUtils.firstChildElement(assign, "Variables");
        if (varsEl == null) return null;
        String arrayVarName = null;
        for (Element ch : BxmlDomUtils.directExpChildren(varsEl)) {
            if ("Id".equals(ch.getLocalName())) {
                arrayVarName = ch.getAttribute("value");
                break;
            }
        }
        if (arrayVarName == null || arrayVarName.isBlank()) return null;
        String cArrayName = ctx.machineName() + "__" + arrayVarName.trim();

        Element valsEl = BxmlDomUtils.firstChildElement(assign, "Values");
        if (valsEl == null) return null;
        for (Element rhs : BxmlDomUtils.directExpChildren(valsEl)) {
            LoopInitSpec spec =
                    cartesianProductLoopSpec(rhs, cArrayName, implMachineEl, ctx, bxmlDirectory);
            if (spec != null) return spec;
        }
        return null;
    }

    /**
     * Tenta extrair uma especificação de loop de uma expressão {@code DOMAIN * {VALUE}} (1D) ou
     * {@code DOMAIN1 * DOMAIN2 * {VALUE}} (2D — B associa {@code *} à esquerda, então a árvore é
     * {@code (DOMAIN1*DOMAIN2)*{VALUE}}, primeiro filho ELE PRÓPRIO um {@code Binary_Exp op="*s"}).
     * Cada domínio pode ser:
     * <ul>
     *   <li>Um intervalo literal: {@code Binary_Exp op='..'} (ex.: {@code 0..9})</li>
     *   <li>Um conjunto nomeado: {@code Id} cujo valor está em {@code <Values>} da implementação,
     *       ou (quando {@code bxmlDirectory} não é nulo) de uma máquina VISTA (SEES) — ver
     *       {@link #resolveNamedSetInterval(String, Element, BxmlTranslateContext, Path)}</li>
     * </ul>
     */
    private static LoopInitSpec cartesianProductLoopSpec(
            Element el, String cArrayName, Element implMachineEl, BxmlTranslateContext ctx,
            Path bxmlDirectory) {
        if (!"Binary_Exp".equals(el.getLocalName())) return null;
        if (!"*s".equals(el.getAttribute("op"))) return null;
        List<Element> children = BxmlDomUtils.directExpChildren(el);
        if (children.size() < 2) return null;
        Element domainEl = children.get(0);
        Element singletonEl = children.get(1);
        if (!"Nary_Exp".equals(singletonEl.getLocalName())) return null;
        if (!"{".equals(singletonEl.getAttribute("op"))) return null;
        List<Element> singletonChildren = BxmlDomUtils.directExpChildren(singletonEl);
        if (singletonChildren.size() != 1) return null;
        String value = BxmlExpressionToAcsl.translate(singletonChildren.get(0), ctx);
        if (value == null) return null;
        value = value.trim();

        if (BxmlMachineVariables.isCompoundProductExp(domainEl)) {
            // 2D: domainEl é ELE PRÓPRIO DOMAIN1*DOMAIN2 (ex. PLAYER*ISLAND).
            List<Element> factors = BxmlDomUtils.directExpChildren(domainEl);
            if (factors.size() < 2) return null;
            String[] outer = domainInterval(factors.get(0), implMachineEl, ctx, bxmlDirectory);
            String[] inner = domainInterval(factors.get(1), implMachineEl, ctx, bxmlDirectory);
            if (outer == null || inner == null) return null;
            return new CartesianProduct2DLoopSpec(
                    "i", outer[0], outer[1], "j", inner[0], inner[1], cArrayName, value);
        }

        String[] interval = domainInterval(domainEl, implMachineEl, ctx, bxmlDirectory);
        if (interval == null) return null;
        return new CartesianProductLoopSpec("i", interval[0], interval[1], cArrayName, value);
    }

    /** Intervalo {@code [lo, hi]} de UM domínio de array — intervalo literal ou conjunto nomeado. */
    private static String[] domainInterval(
            Element domainEl, Element implMachineEl, BxmlTranslateContext ctx, Path bxmlDirectory) {
        String lo, hi;
        if (BxmlExpressionToAcsl.isIntervalBinaryExp(domainEl)) {
            // Intervalo literal: Binary_Exp op='..'
            List<Element> bounds = BxmlDomUtils.directExpChildren(domainEl);
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
        if (lo == null || lo.isBlank() || hi == null || hi.isBlank()) return null;
        return new String[] {lo.trim(), hi.trim()};
    }

    /**
     * Resolve um conjunto nomeado para seu intervalo {@code [lo, hi]} via seção {@code <Values>}
     * da máquina de implementação. Retorna {@code null} se não encontrado ou se a valoração
     * não for um intervalo literal. Quando o conjunto nomeado não está valorado na própria
     * {@code implMachineEl} (ex. {@code GOODS} visto por {@code Price}/{@code Price_i} mas
     * valorado em {@code Goods_i}), procura também nas máquinas VISTAS (SEES) — mesmo problema e
     * mesma solução de
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
        Element valuesEl = BxmlDomUtils.firstChildElement(machineEl, "Values");
        if (valuesEl == null) return null;
        NodeList nl = valuesEl.getChildNodes();
        for (int i = 0; i < nl.getLength(); i++) {
            Node n = nl.item(i);
            if (n.getNodeType() != Node.ELEMENT_NODE) continue;
            Element valEl = (Element) n;
            if (!"Valuation".equals(valEl.getLocalName())) continue;
            if (!setName.equals(valEl.getAttribute("ident"))) continue;
            // Primeiro filho não-Attr é a expressão de valor
            for (Element valExpr : BxmlDomUtils.directExpChildren(valEl)) {
                if (!BxmlExpressionToAcsl.isIntervalBinaryExp(valExpr)) return null;
                List<Element> bounds = BxmlDomUtils.directExpChildren(valExpr);
                if (bounds.size() < 2) return null;
                String lo = BxmlExpressionToAcsl.translate(bounds.get(0), ctx);
                String hi = BxmlExpressionToAcsl.translate(bounds.get(1), ctx);
                if (lo == null || lo.isBlank() || hi == null || hi.isBlank()) return null;
                return new String[]{lo.trim(), hi.trim()};
            }
        }
        return null;
    }

}
