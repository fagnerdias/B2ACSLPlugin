package com.example.bxml;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * Traduz {@code Concrete_Constants}, {@code Properties} e {@code Values} (BXML 1.0) para blocos
 * {@code axiomatic} ACSL.
 */
public final class BxmlConstantsAndProperties {

    private BxmlConstantsAndProperties() {}

    /**
     * {@code axiomatic Nome_constants { logic τ c; … }} a partir de {@code <Concrete_Constants>} (filhos
     * {@code Id} com {@code typref}).
     */
    public static String formatConcreteConstantsBlock(Element machineEl, BxmlTranslateContext ctx) {
        Element block = firstChildElement(machineEl, "Concrete_Constants");
        if (block == null) return "";

        String machineName = machineEl.getAttribute("name");
        if (machineName == null || machineName.isBlank()) return "";

        List<String> decls = new ArrayList<>();
        NodeList ch = block.getChildNodes();
        for (int i = 0; i < ch.getLength(); i++) {
            Node n = ch.item(i);
            if (n.getNodeType() != Node.ELEMENT_NODE) continue;
            Element e = (Element) n;
            if ("Attr".equals(e.getLocalName())) continue;
            if (!"Id".equals(e.getLocalName())) continue;
            String name = e.getAttribute("value");
            if (name == null || name.isBlank()) continue;
            String tr = e.getAttribute("typref");
            int typref = tr.isBlank() ? -1 : Integer.parseInt(tr.trim());
            String logicType = ctx.types().acslVariableLogicTypeFromTypref(typref);
            decls.add("    logic " + logicType + " " + name + ";");
        }
        if (decls.isEmpty()) return "";

        StringBuilder sb = new StringBuilder();
        sb.append("axiomatic ").append(machineName).append("_constants {\n");
        for (String line : decls) {
            sb.append(line).append("\n");
        }
        sb.append("}\n");
        return sb.toString();
    }

    /**
     * {@code axiomatic Nome_properties { axiom Nome_properties_id: P; … }} a partir dos filhos
     * preditivos de {@code <Properties>} (cada um traduzido com {@link BxmlPredicateToAcsl}).
     */
    public static String formatPropertiesBlock(Element machineEl, BxmlTranslateContext ctx) {
        Element block = firstChildElement(machineEl, "Properties");
        if (block == null) return "";

        String machineName = machineEl.getAttribute("name");
        if (machineName == null || machineName.isBlank()) return "";

        List<String> axioms = new ArrayList<>();
        int propIndex = 1;
        NodeList ch = block.getChildNodes();
        for (int i = 0; i < ch.getLength(); i++) {
            Node n = ch.item(i);
            if (n.getNodeType() != Node.ELEMENT_NODE) continue;
            Element e = (Element) n;
            if ("Attr".equals(e.getLocalName())) continue;

            String pred = BxmlPredicateToAcsl.translatePropertyPred(e, ctx);
            if (pred == null || pred.isBlank()) {
                continue;
            }
            String axiomName = axiomNameForProperty(machineName, e, propIndex);
            propIndex++;
            axioms.add("    axiom " + axiomName + ":\n        " + pred + ";");
        }
        if (axioms.isEmpty()) return "";

        StringBuilder sb = new StringBuilder();
        sb.append("axiomatic ").append(machineName).append("_properties {\n");
        for (String ax : axioms) {
            sb.append(ax).append("\n");
        }
        sb.append("}\n");
        return sb.toString();
    }

    /**
     * {@code axiomatic Nome_values { axiom Nome_values_id: id == E; … }} a partir de {@code <Values>}
     * (filhos {@code Valuation} com {@code ident} e expressão filha, p.ex. {@code Integer_Literal}).
     */
    public static String formatValuesBlock(Element machineEl, BxmlTranslateContext ctx) {
        Element block = firstChildElement(machineEl, "Values");
        if (block == null) return "";

        String machineName = machineEl.getAttribute("name");
        if (machineName == null || machineName.isBlank()) return "";

        List<String> axioms = new ArrayList<>();
        NodeList ch = block.getChildNodes();
        for (int i = 0; i < ch.getLength(); i++) {
            Node n = ch.item(i);
            if (n.getNodeType() != Node.ELEMENT_NODE) continue;
            Element e = (Element) n;
            if ("Attr".equals(e.getLocalName())) continue;
            if (!"Valuation".equals(e.getLocalName())) continue;

            String ident = e.getAttribute("ident");
            if (ident == null || ident.isBlank()) continue;
            Element valueExp = firstValuationValueExpression(e);
            if (valueExp == null) continue;

            String equality = formatValuationEquality(ident, valueExp, ctx);
            if (equality == null || equality.isBlank()) continue;
            String axiomName = machineName + "_values_" + ident;
            axioms.add("    axiom " + axiomName + ": " + equality + ";");
        }
        if (axioms.isEmpty()) return "";

        StringBuilder sb = new StringBuilder();
        sb.append("axiomatic ").append(machineName).append("_values {\n");
        for (String ax : axioms) {
            sb.append(ax).append("\n");
        }
        sb.append("}\n");
        return sb.toString();
    }

    /**
     * Extrai valuations com valores inteiros literais de {@code <Values>}.
     * Retorna mapa de nome → valor para uso em anotações que exigem constante literal (ex. {@code loop unfold}).
     */
    public static Map<String, Long> extractLiteralIntegerValuations(Element machineEl) {
        Map<String, Long> result = new LinkedHashMap<>();
        Element block = firstChildElement(machineEl, "Values");
        if (block == null) return result;
        NodeList ch = block.getChildNodes();
        for (int i = 0; i < ch.getLength(); i++) {
            Node n = ch.item(i);
            if (n.getNodeType() != Node.ELEMENT_NODE) continue;
            Element e = (Element) n;
            if (!"Valuation".equals(e.getLocalName())) continue;
            String ident = e.getAttribute("ident");
            if (ident == null || ident.isBlank()) continue;
            Element valueExp = firstValuationValueExpression(e);
            if (valueExp == null || !"Integer_Literal".equals(valueExp.getLocalName())) continue;
            String val = valueExp.getAttribute("value");
            if (val == null || val.isBlank()) continue;
            try {
                result.put(ident, Long.parseLong(val.trim()));
            } catch (NumberFormatException ignored) {}
        }
        return result;
    }

    private static Element firstValuationValueExpression(Element valuation) {
        NodeList ch = valuation.getChildNodes();
        for (int i = 0; i < ch.getLength(); i++) {
            Node n = ch.item(i);
            if (n.getNodeType() != Node.ELEMENT_NODE) continue;
            Element e = (Element) n;
            if ("Attr".equals(e.getLocalName())) continue;
            return e;
        }
        return null;
    }

    private static String formatValuationEquality(String ident, Element valueExp, BxmlTranslateContext ctx) {
        String rhs = BxmlExpressionToAcsl.translate(valueExp, ctx);
        if (rhs == null || rhs.isBlank()) return null;
        String lt = ctx.variableLogicTypes().get(ident);
        if (lt != null && (lt.startsWith("Set<") || lt.startsWith("Relation_"))) {
            return "equals(" + ident + ", " + rhs + ")";
        }
        if (BxmlExpressionToAcsl.isSetValued(valueExp, ctx)) {
            return "equals(" + ident + ", " + rhs + ")";
        }
        return ident + " == " + rhs;
    }

    private static String axiomNameForProperty(String machineName, Element predEl, int idx) {
        if ("Exp_Comparison".equals(predEl.getLocalName())) {
            String op = predEl.getAttribute("op");
            if (":".equals(op) || "&lt;:".equals(op)) {
                Element[] pair = BxmlExpressionToAcsl.twoDirectExpChildren(predEl);
                if (pair[0] != null && "Id".equals(pair[0].getLocalName())) {
                    String id = pair[0].getAttribute("value");
                    if (id != null && !id.isBlank()) {
                        return machineName + "_properties_" + id;
                    }
                }
            }
        }
        return machineName + "_properties_" + idx;
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
}
