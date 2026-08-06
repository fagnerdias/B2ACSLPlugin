package com.example.bxml;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
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
        return translateLoopsFromImplementationOperation(implementationOperation, ctx, null);
    }

    /**
     * @param abstractMachineEl máquina abstrata; quando fornecida, variáveis de máquina no
     *     {@code loop assigns} são qualificadas com {@code machineName__varName} (e fatia de array).
     */
    public static List<LoopContract> translateLoopsFromImplementationOperation(
            Element implementationOperation, BxmlTranslateContext ctx, Element abstractMachineEl) {
        return translateLoopsFromImplementationOperation(
                implementationOperation, ctx, abstractMachineEl, null);
    }

    /**
     * @param importedOpAssigns mapa de nome-local-de-operação → assigns concretos da máquina importada;
     *     usado para incluir no {@code loop assigns} os alvos modificados por {@code Operation_Call}
     *     dentro do corpo do loop.
     */
    public static List<LoopContract> translateLoopsFromImplementationOperation(
            Element implementationOperation, BxmlTranslateContext ctx, Element abstractMachineEl,
            Map<String, List<String>> importedOpAssigns) {
        return translateLoopsFromImplementationOperation(
                implementationOperation, ctx, abstractMachineEl, importedOpAssigns, null);
    }

    /**
     * @param bxmlDirectory diretório das fontes {@code .bxml} — necessário para resolver a fatia
     *     {@code [low .. high]} de um array no {@code loop assigns} quando o conjunto do seu domínio
     *     só está valorado numa máquina VISTA (SEES), não na própria implementação (ex.: {@code
     *     PERSON} valorado em {@code ContextI}, visto por {@code RegisterI}); sem isto, cai para
     *     {@code [..]} (equivalente para o WP, mas menos legível — ver {@link
     *     BxmlMachineVariables#qualifyLoopAssignTarget}).
     */
    public static List<LoopContract> translateLoopsFromImplementationOperation(
            Element implementationOperation, BxmlTranslateContext ctx, Element abstractMachineEl,
            Map<String, List<String>> importedOpAssigns, Path bxmlDirectory) {
        if (implementationOperation == null || ctx == null) {
            return List.of();
        }
        Element body = firstChildElement(implementationOperation, "Body");
        if (body == null) {
            return List.of();
        }
        return translateLoopsFromSubstitution(body, ctx, abstractMachineEl, importedOpAssigns, bxmlDirectory);
    }

    /**
     * Como {@link #translateLoopsFromImplementationOperation}, mas a partir de qualquer raiz de
     * substituição já resolvida (ex.: o {@code <Initialisation>} de uma máquina, que não tem o
     * wrapper {@code <Body>} de uma {@code <Operation>}) — usado para {@code WHILE} explícitos
     * dentro da inicialização (com {@code INVARIANT}/{@code VARIANT} escritos pelo usuário em B),
     * que antes só eram reconhecidos dentro de corpos de operação.
     */
    public static List<LoopContract> translateLoopsFromSubstitution(
            Element substitutionRoot, BxmlTranslateContext ctx, Element abstractMachineEl,
            Map<String, List<String>> importedOpAssigns) {
        return translateLoopsFromSubstitution(
                substitutionRoot, ctx, abstractMachineEl, importedOpAssigns, null);
    }

    /** Como acima, com {@code bxmlDirectory} — ver {@link #translateLoopsFromImplementationOperation}. */
    public static List<LoopContract> translateLoopsFromSubstitution(
            Element substitutionRoot, BxmlTranslateContext ctx, Element abstractMachineEl,
            Map<String, List<String>> importedOpAssigns, Path bxmlDirectory) {
        if (substitutionRoot == null || ctx == null) {
            return List.of();
        }
        // Máquina de implementação real (não abstractMachineEl) — só ela declara as suas próprias
        // Concrete_Variables (ex. player_islands_i), usada em qualifyLoopAssignTarget para
        // qualificar variáveis _i que a máquina abstrata nunca vê. Ver
        // BxmlMachineVariables#enclosingMachineElement.
        Element implementationMachineEl = BxmlMachineVariables.enclosingMachineElement(substitutionRoot);
        List<LoopContract> loops = new ArrayList<>();
        walkForWhileLoops(
                substitutionRoot, ctx, abstractMachineEl, implementationMachineEl, loops, importedOpAssigns,
                bxmlDirectory);
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
            Element sub, BxmlTranslateContext ctx, Element abstractMachineEl,
            Element implementationMachineEl, List<LoopContract> out,
            Map<String, List<String>> importedOpAssigns, Path bxmlDirectory) {
        if (sub == null) {
            return;
        }
        if ("While".equals(sub.getLocalName())) {
            out.add(translateWhile(
                    sub, ctx, abstractMachineEl, implementationMachineEl, out.size() + 1,
                    importedOpAssigns, bxmlDirectory));
            walkForWhileLoops(
                    firstChildElement(sub, "Body"), ctx, abstractMachineEl, implementationMachineEl, out,
                    importedOpAssigns, bxmlDirectory);
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
            walkForWhileLoops(
                    ch, ctx, abstractMachineEl, implementationMachineEl, out, importedOpAssigns,
                    bxmlDirectory);
        }
    }

    private static LoopContract translateWhile(
            Element whileEl, BxmlTranslateContext ctx, Element abstractMachineEl,
            Element implementationMachineEl, int index, Map<String, List<String>> importedOpAssigns,
            Path bxmlDirectory) {
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
        collectLoopAssigns(
                body, ctx, abstractMachineEl, implementationMachineEl, assigns, importedOpAssigns, Set.of(),
                bxmlDirectory);

        return new LoopContract(index, invariant, variant, List.copyOf(assigns));
    }

    private static void collectLoopAssigns(
            Element sub, BxmlTranslateContext ctx, Element abstractMachineEl,
            Element implementationMachineEl, Set<String> out, Map<String, List<String>> importedOpAssigns,
            Set<String> localVars, Path bxmlDirectory) {
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
                        if (name != null && !name.isBlank() && !localVars.contains(name.trim())) {
                            out.add(qualify(
                                    name.trim(), ctx, abstractMachineEl, implementationMachineEl,
                                    bxmlDirectory));
                        }
                    } else if ("Binary_Exp".equals(lhs.getLocalName())
                            && "(".equals(lhs.getAttribute("op"))) {
                        // atribuição funcional: f(idx) := val → extrai o nome base f
                        List<Element> args = directExpChildren(lhs);
                        if (!args.isEmpty() && "Id".equals(args.get(0).getLocalName())) {
                            String name = args.get(0).getAttribute("value");
                            if (name != null && !name.isBlank() && !localVars.contains(name.trim())) {
                                out.add(qualify(
                                        name.trim(), ctx, abstractMachineEl, implementationMachineEl,
                                        bxmlDirectory));
                            }
                        }
                    }
                }
            }
            return;
        }
        if ("VAR_IN".equals(sub.getLocalName())) {
            // Variáveis declaradas em VAR_IN são locais ao bloco; não devem aparecer em loop assigns.
            Set<String> innerLocals = new java.util.LinkedHashSet<>(localVars);
            Element declaredVars = firstChildElement(sub, "Variables");
            if (declaredVars != null) {
                for (Element id : directExpChildren(declaredVars)) {
                    if ("Id".equals(id.getLocalName())) {
                        String name = id.getAttribute("value");
                        if (name != null && !name.isBlank()) innerLocals.add(name.trim());
                    }
                }
            }
            Element innerBody = firstChildElement(sub, "Body");
            if (innerBody != null) {
                collectLoopAssigns(
                        innerBody, ctx, abstractMachineEl, implementationMachineEl, out, importedOpAssigns,
                        innerLocals, bxmlDirectory);
            }
            return;
        }
        if ("Operation_Call".equals(sub.getLocalName())) {
            // Parâmetros de saída: variáveis locais C modificadas pela chamada.
            Element outParams = firstChildElement(sub, "Output_Parameters");
            if (outParams != null) {
                for (Element id : directExpChildren(outParams)) {
                    if ("Id".equals(id.getLocalName())) {
                        String name = id.getAttribute("value");
                        if (name != null && !name.isBlank() && !localVars.contains(name.trim())) {
                            out.add(name.trim());
                        }
                    }
                }
            }
            // Assigns concretos da operação importada chamada (ex.: Body__loopnn para body(rr,ii,&ii)).
            if (importedOpAssigns != null && !importedOpAssigns.isEmpty()) {
                String calledName = extractOperationCallName(sub);
                if (calledName != null) {
                    List<String> calledAssigns = importedOpAssigns.get(calledName);
                    if (calledAssigns != null) {
                        out.addAll(calledAssigns);
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
            collectLoopAssigns(
                    ch, ctx, abstractMachineEl, implementationMachineEl, out, importedOpAssigns, localVars,
                    bxmlDirectory);
        }
    }

    /** Nome da operação em {@code <Operation_Call><Name><Id value="..."/></Name>}. */
    private static String extractOperationCallName(Element opCall) {
        Element nameEl = firstChildElement(opCall, "Name");
        if (nameEl == null) return null;
        NodeList nl = nameEl.getChildNodes();
        for (int i = 0; i < nl.getLength(); i++) {
            Node n = nl.item(i);
            if (n.getNodeType() != Node.ELEMENT_NODE) continue;
            Element e = (Element) n;
            if ("Id".equals(e.getLocalName())) {
                String v = e.getAttribute("value");
                if (v != null && !v.isBlank()) return v.trim();
            }
        }
        return null;
    }

    /**
     * Qualifica o nome de uma variável assignada no loop: variáveis de máquina recebem prefixo
     * {@code machineName__} (e fatia de array se aplicável); variáveis locais ficam como-estão.
     */
    private static String qualify(
            String varName, BxmlTranslateContext ctx, Element abstractMachineEl,
            Element implementationMachineEl, Path bxmlDirectory) {
        if (ctx == null || abstractMachineEl == null) {
            return varName;
        }
        return BxmlMachineVariables.qualifyLoopAssignTarget(
                varName, ctx.machineName(), abstractMachineEl, implementationMachineEl, ctx, bxmlDirectory);
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
