package com.example.bxml;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

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
        String machineName = machineEl.getAttribute("name");
        List<OperationAcsl> out = new ArrayList<>();

        NodeList ops = machineEl.getElementsByTagNameNS("*", "Operations");
        if (ops.getLength() == 0) return out;
        Element operationsEl = (Element) ops.item(0);
        NodeList children = operationsEl.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node n = children.item(i);
            if (n.getNodeType() != Node.ELEMENT_NODE) continue;
            Element child = (Element) n;
            if (!"Operation".equals(child.getLocalName())) continue;

            String opName = child.getAttribute("name");
            String funcName = machineName + "__" + sanitize(opName);

            List<String> requires = new ArrayList<>();
            for (String inv : invariantPredicateNames) {
                requires.add(inv);
            }
            Element pre = firstChildElement(child, "Precondition");
            if (pre != null) {
                requires.addAll(BxmlPredicateToAcsl.translatePredicateBlock(pre, ctx));
            }

            List<String> outputParams = parseOutputParameterNames(child);
            for (String p : outputParams) {
                requires.add("\\valid(" + p + ")");
            }

            List<String> ensures = new ArrayList<>();
            Element body = firstChildElement(child, "Body");
            if (body != null) {
                BxmlInitialisationTranslator.appendEnsuresFromBody(body, ensures, ctx);
            }
            applyStarPrefixToEnsures(ensures, outputParams);
            for (String inv : invariantPredicateNames) {
                ensures.add(inv);
            }

            out.add(new OperationAcsl(funcName, requires, ensures, outputParams));
        }
        return out;
    }

    /** Nomes dos {@code Id} em {@code Output_Parameters} (ordem do BXML). */
    private static List<String> parseOutputParameterNames(Element operation) {
        Element outEl = firstChildElement(operation, "Output_Parameters");
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

    /**
     * Para cada {@code ensures} da forma {@code v == E}, se {@code v} for parâmetro de saída, gera
     * {@code *v == E}.
     */
    private static void applyStarPrefixToEnsures(List<String> ensures, List<String> outputParams) {
        if (outputParams.isEmpty()) return;
        Set<String> out = new HashSet<>(outputParams);
        for (int i = 0; i < ensures.size(); i++) {
            String s = ensures.get(i);
            int eq = s.indexOf(" == ");
            if (eq < 0) continue;
            String lhs = s.substring(0, eq).trim();
            if (out.contains(lhs) && !lhs.startsWith("*")) {
                ensures.set(i, "*" + lhs + " == " + s.substring(eq + 4));
            }
        }
    }

    private static String sanitize(String name) {
        return name.replace('-', '_');
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

    public record OperationAcsl(
            String functionName,
            List<String> requires,
            List<String> ensures,
            /** Parâmetros de saída B (sem {@code *}); viram {@code assigns *nome}. */
            List<String> outputParameters) {

        /** Mesmo esquema que {@link com.example.bxml.BxmlInitialisationTranslator.InitialisationAcsl#toContractText()}. */
        public String toContractSketch() {
            StringBuilder sb = new StringBuilder();
            sb.append("function ").append(functionName).append(":\n");
            sb.append("contract:    \n");
            for (String r : requires) {
                sb.append("    requires  ").append(r).append(";\n");
            }
            for (String e : ensures) {
                sb.append("    ensures  ").append(e).append(";\n");
            }
            for (String p : outputParameters) {
                sb.append("    assigns *").append(p).append(";\n");
            }
            return sb.toString();
        }
    }
}
