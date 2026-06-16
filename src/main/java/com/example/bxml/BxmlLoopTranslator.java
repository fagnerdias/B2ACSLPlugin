package com.example.bxml;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * Traduz loops {@code WHILE} do corpo de operações de implementação B para cláusulas ACSL
 * ({@code at loop N}, {@code loop invariant}, {@code loop assigns}, {@code loop variant}).
 */
public final class BxmlLoopTranslator {

    private BxmlLoopTranslator() {}

    public record LoopContract(int index, String invariant, String variant, List<String> assigns) {
        public LoopContract {
            assigns = assigns == null ? List.of() : List.copyOf(assigns);
        }
    }

    /**
     * Procura a operação homónima na última máquina {@code implementation} da cadeia fundida.
     */
    public static Element findImplementationOperation(List<Element> mergedRefinementChain, String opName) {
        if (mergedRefinementChain == null || opName == null || opName.isBlank()) {
            return null;
        }
        for (int i = mergedRefinementChain.size() - 1; i >= 0; i--) {
            Element machine = mergedRefinementChain.get(i);
            if (!"implementation".equalsIgnoreCase(machine.getAttribute("type"))) {
                continue;
            }
            Element op = findOperationByName(machine, opName);
            if (op != null) {
                return op;
            }
        }
        return null;
    }

    /** Extrai os loops do {@code <Body>} de uma operação de implementação (ordem de descoberta). */
    public static List<LoopContract> translateLoopsFromImplementationOperation(
            Element implementationOperation, BxmlTranslateContext ctx) {
        return translateLoopsFromImplementationOperation(implementationOperation, ctx, null);
    }

    /**
     * @param abstractMachineEl máquina abstrata; quando fornecida, variáveis de máquina no
     *     {@code loop assigns} são qualificadas com {@code machineName__varName} (e fatia de array).
     */
    public static List<LoopContract> translateLoopsFromImplementationOperation(
            Element implementationOperation, BxmlTranslateContext ctx, Element abstractMachineEl) {
        if (implementationOperation == null || ctx == null) {
            return List.of();
        }
        Element body = firstChildElement(implementationOperation, "Body");
        if (body == null) {
            return List.of();
        }
        List<LoopContract> loops = new ArrayList<>();
        walkForWhileLoops(body, ctx, abstractMachineEl, loops);
        return List.copyOf(loops);
    }

    private static Element findOperationByName(Element machineEl, String opName) {
        NodeList ops = machineEl.getElementsByTagNameNS("*", "Operations");
        if (ops.getLength() == 0) {
            return null;
        }
        Element operationsEl = (Element) ops.item(0);
        NodeList children = operationsEl.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node n = children.item(i);
            if (n.getNodeType() != Node.ELEMENT_NODE) {
                continue;
            }
            Element child = (Element) n;
            if ("Operation".equals(child.getLocalName()) && opName.equals(child.getAttribute("name"))) {
                return child;
            }
        }
        return null;
    }

    private static void walkForWhileLoops(
            Element sub, BxmlTranslateContext ctx, Element abstractMachineEl, List<LoopContract> out) {
        if (sub == null) {
            return;
        }
        if ("While".equals(sub.getLocalName())) {
            out.add(translateWhile(sub, ctx, abstractMachineEl, out.size() + 1));
            walkForWhileLoops(firstChildElement(sub, "Body"), ctx, abstractMachineEl, out);
            return;
        }
        NodeList nl = sub.getChildNodes();
        for (int i = 0; i < nl.getLength(); i++) {
            Node n = nl.item(i);
            if (n.getNodeType() != Node.ELEMENT_NODE) {
                continue;
            }
            Element ch = (Element) n;
            if ("Attr".equals(ch.getLocalName())) {
                continue;
            }
            walkForWhileLoops(ch, ctx, abstractMachineEl, out);
        }
    }

    private static LoopContract translateWhile(
            Element whileEl, BxmlTranslateContext ctx, Element abstractMachineEl, int index) {
        List<String> abstractVarNames = abstractMachineEl != null
                ? GhostOperationsCiGenerator.listAbstractVariableNames(abstractMachineEl)
                : List.of();

        Element invEl = firstChildElement(whileEl, "Invariant");
        String invariant = "";
        if (invEl != null) {
            invariant = BxmlPredicateToAcsl.translateInvariantContent(invEl, ctx).trim();
            invariant = prefixAbstractVars(invariant, abstractVarNames);
        }

        Element varEl = firstChildElement(whileEl, "Variant");
        String variant = "";
        if (varEl != null) {
            Element exp = firstExpressionChild(varEl);
            if (exp != null) {
                variant = BxmlExpressionToAcsl.translate(exp, ctx).trim();
                variant = prefixAbstractVars(variant, abstractVarNames);
            }
        }

        LinkedHashSet<String> assigns = new LinkedHashSet<>();
        Element body = firstChildElement(whileEl, "Body");
        collectLoopAssigns(body, ctx, abstractMachineEl, assigns);

        return new LoopContract(index, invariant, variant, List.copyOf(assigns));
    }

    private static void collectLoopAssigns(
            Element sub, BxmlTranslateContext ctx, Element abstractMachineEl, Set<String> out) {
        if (sub == null) {
            return;
        }
        if ("Assignement_Sub".equals(sub.getLocalName())
                || "Becomes_In".equals(sub.getLocalName())
                || "Becomes_Such_That".equals(sub.getLocalName())) {
            Element vars = firstChildElement(sub, "Variables");
            if (vars != null) {
                for (Element lhs : directExpChildren(vars)) {
                    if ("Id".equals(lhs.getLocalName())) {
                        // atribuição simples: x := ...
                        String name = lhs.getAttribute("value");
                        if (name != null && !name.isBlank()) {
                            out.add(qualify(name.trim(), ctx, abstractMachineEl));
                        }
                    } else if ("Binary_Exp".equals(lhs.getLocalName())
                            && "(".equals(lhs.getAttribute("op"))) {
                        // atribuição funcional: f(idx) := val → extrai o nome base f
                        List<Element> args = directExpChildren(lhs);
                        if (!args.isEmpty() && "Id".equals(args.get(0).getLocalName())) {
                            String name = args.get(0).getAttribute("value");
                            if (name != null && !name.isBlank()) {
                                out.add(qualify(name.trim(), ctx, abstractMachineEl));
                            }
                        }
                    }
                }
            }
            return;
        }
        if ("Operation_Call".equals(sub.getLocalName())) {
            Element outParams = firstChildElement(sub, "Output_Parameters");
            if (outParams != null) {
                for (Element id : directExpChildren(outParams)) {
                    if ("Id".equals(id.getLocalName())) {
                        String name = id.getAttribute("value");
                        if (name != null && !name.isBlank()) {
                            // parâmetros de saída são variáveis locais C — sem prefixo de máquina
                            out.add(name.trim());
                        }
                    }
                }
            }
            return;
        }
        if ("VAR_IN".equals(sub.getLocalName())) {
            Set<String> localNames = new LinkedHashSet<>();
            Element vars = firstChildElement(sub, "Variables");
            if (vars != null) {
                for (Element id : directExpChildren(vars)) {
                    if ("Id".equals(id.getLocalName())) {
                        String v = id.getAttribute("value");
                        if (v != null && !v.isBlank()) localNames.add(v.trim());
                    }
                }
            }
            Set<String> inner = new LinkedHashSet<>();
            Element bodyEl = firstChildElement(sub, "Body");
            if (bodyEl != null) {
                collectLoopAssigns(bodyEl, ctx, abstractMachineEl, inner);
            }
            inner.removeIf(v -> localNames.stream().anyMatch(loc -> v.equals(loc) || v.startsWith(loc + "[")));
            out.addAll(inner);
            return;
        }
        NodeList nl = sub.getChildNodes();
        for (int i = 0; i < nl.getLength(); i++) {
            Node n = nl.item(i);
            if (n.getNodeType() != Node.ELEMENT_NODE) {
                continue;
            }
            Element ch = (Element) n;
            if ("Attr".equals(ch.getLocalName())) {
                continue;
            }
            collectLoopAssigns(ch, ctx, abstractMachineEl, out);
        }
    }

    /**
     * Qualifica o nome de uma variável assignada no loop: variáveis de máquina recebem prefixo
     * {@code machineName__} (e fatia de array se aplicável); variáveis locais ficam como-estão.
     */
    private static String qualify(String varName, BxmlTranslateContext ctx, Element abstractMachineEl) {
        if (ctx == null || abstractMachineEl == null) {
            return varName;
        }
        return BxmlMachineVariables.qualifyLoopAssignTarget(
                varName, ctx.machineName(), abstractMachineEl, ctx);
    }

    private static String prefixAbstractVars(String text, List<String> abstractVarNames) {
        if (text == null || text.isBlank() || abstractVarNames.isEmpty()) return text;
        String result = text;
        for (String v : abstractVarNames) {
            result = result.replaceAll(
                    "\\b" + Pattern.quote(v) + "\\b",
                    "dummy_ghost_" + v);
        }
        return result;
    }

    private static Element firstChildElement(Element parent, String localName) {
        NodeList nl = parent.getChildNodes();
        for (int i = 0; i < nl.getLength(); i++) {
            Node n = nl.item(i);
            if (n.getNodeType() != Node.ELEMENT_NODE) {
                continue;
            }
            Element e = (Element) n;
            if (localName.equals(e.getLocalName())) {
                return e;
            }
        }
        return null;
    }

    private static Element firstExpressionChild(Element parent) {
        NodeList nl = parent.getChildNodes();
        for (int i = 0; i < nl.getLength(); i++) {
            Node n = nl.item(i);
            if (n.getNodeType() != Node.ELEMENT_NODE) {
                continue;
            }
            Element e = (Element) n;
            if ("Attr".equals(e.getLocalName())) {
                continue;
            }
            return e;
        }
        return null;
    }

    private static List<Element> directExpChildren(Element parent) {
        List<Element> out = new ArrayList<>();
        NodeList nl = parent.getChildNodes();
        for (int i = 0; i < nl.getLength(); i++) {
            Node n = nl.item(i);
            if (n.getNodeType() != Node.ELEMENT_NODE) {
                continue;
            }
            Element e = (Element) n;
            if ("Attr".equals(e.getLocalName())) {
                continue;
            }
            out.add(e);
        }
        return out;
    }
}
