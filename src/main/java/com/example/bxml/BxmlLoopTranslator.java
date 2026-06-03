package com.example.bxml;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

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
        if (implementationOperation == null || ctx == null) {
            return List.of();
        }
        Element body = firstChildElement(implementationOperation, "Body");
        if (body == null) {
            return List.of();
        }
        List<LoopContract> loops = new ArrayList<>();
        walkForWhileLoops(body, ctx, loops);
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

    private static void walkForWhileLoops(Element sub, BxmlTranslateContext ctx, List<LoopContract> out) {
        if (sub == null) {
            return;
        }
        if ("While".equals(sub.getLocalName())) {
            out.add(translateWhile(sub, ctx, out.size() + 1));
            walkForWhileLoops(firstChildElement(sub, "Body"), ctx, out);
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
            walkForWhileLoops(ch, ctx, out);
        }
    }

    private static LoopContract translateWhile(Element whileEl, BxmlTranslateContext ctx, int index) {
        Element invEl = firstChildElement(whileEl, "Invariant");
        String invariant = "";
        if (invEl != null) {
            invariant = BxmlPredicateToAcsl.translateInvariantContent(invEl, ctx).trim();
        }

        Element varEl = firstChildElement(whileEl, "Variant");
        String variant = "";
        if (varEl != null) {
            Element exp = firstExpressionChild(varEl);
            if (exp != null) {
                variant = BxmlExpressionToAcsl.translate(exp, ctx).trim();
            }
        }

        LinkedHashSet<String> assigns = new LinkedHashSet<>();
        Element body = firstChildElement(whileEl, "Body");
        collectLoopAssigns(body, assigns);

        return new LoopContract(index, invariant, variant, List.copyOf(assigns));
    }

    private static void collectLoopAssigns(Element sub, Set<String> out) {
        if (sub == null) {
            return;
        }
        if ("Assignement_Sub".equals(sub.getLocalName())) {
            Element vars = firstChildElement(sub, "Variables");
            if (vars != null) {
                for (Element id : directExpChildren(vars)) {
                    if ("Id".equals(id.getLocalName())) {
                        String name = id.getAttribute("value");
                        if (name != null && !name.isBlank()) {
                            out.add(name.trim());
                        }
                    }
                }
            }
            return;
        }
        if ("Becomes_In".equals(sub.getLocalName()) || "Becomes_Such_That".equals(sub.getLocalName())) {
            Element vars = firstChildElement(sub, "Variables");
            if (vars != null) {
                for (Element id : directExpChildren(vars)) {
                    if ("Id".equals(id.getLocalName())) {
                        String name = id.getAttribute("value");
                        if (name != null && !name.isBlank()) {
                            out.add(name.trim());
                        }
                    }
                }
            }
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
            collectLoopAssigns(ch, out);
        }
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
