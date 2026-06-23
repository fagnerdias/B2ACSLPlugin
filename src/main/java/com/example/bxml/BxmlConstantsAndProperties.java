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
     * {@code axiomatic Nome_abstract_constants { logic boolean ID(…); … }} a partir de
     * {@code <Abstract_Constants>}. O tipo é inferido de {@code <Properties>}: se a constante é
     * definida por lambda {@code ID = %(v1,…,vn).(P|E)}, declara-se {@code logic boolean
     * ID(integer v1,…,vn)}; caso contrário cai no tipo ACSL derivado do {@code typref}.
     */
    public static String formatAbstractConstantsBlock(Element machineEl, BxmlTranslateContext ctx) {
        Element block = firstChildElement(machineEl, "Abstract_Constants");
        if (block == null) return "";

        String machineName = machineEl.getAttribute("name");
        if (machineName == null || machineName.isBlank()) return "";

        Map<String, List<String>> lambdaParams = collectLambdaDefsFromProperties(machineEl);

        List<String> decls = new ArrayList<>();
        NodeList ch = block.getChildNodes();
        for (int i = 0; i < ch.getLength(); i++) {
            Node n = ch.item(i);
            if (n.getNodeType() != Node.ELEMENT_NODE) continue;
            Element e = (Element) n;
            if ("Attr".equals(e.getLocalName()) || !"Id".equals(e.getLocalName())) continue;
            String name = e.getAttribute("value");
            if (name == null || name.isBlank()) continue;
            List<String> params = lambdaParams.get(name);
            if (params != null && !params.isEmpty()) {
                String paramStr = String.join(", ", params.stream()
                        .map(v -> "integer " + v).toList());
                decls.add("    logic boolean " + name + "(" + paramStr + ");");
            } else {
                String tr = e.getAttribute("typref");
                int typref = (tr == null || tr.isBlank()) ? -1 : Integer.parseInt(tr.trim());
                String logicType = ctx.types().acslVariableLogicTypeFromTypref(typref);
                decls.add("    logic " + logicType + " " + name + ";");
            }
        }
        if (decls.isEmpty()) return "";

        StringBuilder sb = new StringBuilder();
        sb.append("axiomatic ").append(machineName).append("_abstract_constants {\n");
        for (String line : decls) sb.append(line).append("\n");
        sb.append("}\n");
        return sb.toString();
    }

    private static Map<String, List<String>> collectLambdaDefsFromProperties(Element machineEl) {
        Map<String, List<String>> result = new LinkedHashMap<>();
        Element propsEl = firstChildElement(machineEl, "Properties");
        if (propsEl == null) return result;
        NodeList ch = propsEl.getChildNodes();
        for (int i = 0; i < ch.getLength(); i++) {
            Node n = ch.item(i);
            if (n.getNodeType() != Node.ELEMENT_NODE) continue;
            Element e = (Element) n;
            if (!"Exp_Comparison".equals(e.getLocalName()) || !"=".equals(e.getAttribute("op"))) continue;
            Element[] pair = BxmlExpressionToAcsl.twoDirectExpChildren(e);
            if (pair[0] == null || pair[1] == null) continue;
            if (!"Id".equals(pair[0].getLocalName())) continue;
            if (!"Quantified_Exp".equals(pair[1].getLocalName())) continue;
            if (!"%".equals(pair[1].getAttribute("type"))) continue;
            String idName = pair[0].getAttribute("value");
            List<String> vars = lambdaBoundVarNames(pair[1]);
            if (!vars.isEmpty()) result.put(idName, vars);
        }
        return result;
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

            // ID = %(v1,...,vn).(P|E): a declaração fica em abstract_constants; aqui só o axioma \forall
            if ("Exp_Comparison".equals(e.getLocalName()) && "=".equals(e.getAttribute("op"))) {
                Element[] pair = BxmlExpressionToAcsl.twoDirectExpChildren(e);
                if (pair[0] != null && pair[1] != null
                        && "Id".equals(pair[0].getLocalName())
                        && "Quantified_Exp".equals(pair[1].getLocalName())
                        && "%".equals(pair[1].getAttribute("type"))) {
                    String idName = pair[0].getAttribute("value");
                    List<String> boundVars = lambdaBoundVarNames(pair[1]);
                    if (!boundVars.isEmpty()) {
                        String lambdaCall = BxmlExpressionToAcsl.translate(pair[1], ctx);
                        String idCall = idName + "(" + String.join(", ", boundVars) + ")";
                        String quantVars = "integer " + String.join(", ", boundVars);
                        String axiomName = axiomNameForProperty(machineName, e, propIndex);
                        propIndex++;
                        axioms.add("    axiom " + axiomName + ":\n        \\forall "
                                + quantVars + "; " + idCall + " <==> " + lambdaCall + ";");
                        continue;
                    }
                }
            }

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
        for (String ax : axioms) sb.append(ax).append("\n");
        sb.append("}\n");
        return sb.toString();
    }

    private static List<String> lambdaBoundVarNames(Element quantifiedExp) {
        List<String> names = new ArrayList<>();
        NodeList nl = quantifiedExp.getChildNodes();
        for (int i = 0; i < nl.getLength(); i++) {
            Node n = nl.item(i);
            if (n.getNodeType() != Node.ELEMENT_NODE) continue;
            Element e = (Element) n;
            if (!"Variables".equals(e.getLocalName())) continue;
            NodeList vnl = e.getChildNodes();
            for (int j = 0; j < vnl.getLength(); j++) {
                Node vn = vnl.item(j);
                if (vn.getNodeType() != Node.ELEMENT_NODE) continue;
                Element ve = (Element) vn;
                if ("Attr".equals(ve.getLocalName()) || !"Id".equals(ve.getLocalName())) continue;
                String v = ve.getAttribute("value");
                if (v != null && !v.isBlank()) names.add(v.trim());
            }
            break;
        }
        return names;
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

        // Sets valuated in Values belong to the abstract machine; their ACSL logic name
        // is abstractMachineName_SETNAME (e.g. DateFields_HOUR).
        String abstractMachineName = null;
        Element abstractionEl = firstChildElement(machineEl, "Abstraction");
        if (abstractionEl != null) {
            String t = abstractionEl.getTextContent();
            if (t != null && !t.isBlank()) abstractMachineName = t.trim();
        }

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

            String equality = formatValuationEquality(ident, valueExp, ctx, abstractMachineName);
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

    private static String formatValuationEquality(
            String ident, Element valueExp, BxmlTranslateContext ctx, String abstractMachineName) {
        String lt = ctx.variableLogicTypes().get(ident);
        boolean isSet = (lt != null && (lt.startsWith("Set<") || lt.startsWith("Relation_")))
                || BxmlExpressionToAcsl.isSetValued(valueExp, ctx);
        if (isSet) {
            String acslIdent = qualifySetIdent(ident, abstractMachineName);
            // For B interval (lo..hi): generate range predicate directly — no interval_set needed.
            if ("Binary_Exp".equals(valueExp.getLocalName())
                    && "..".equals(valueExp.getAttribute("op"))) {
                Element[] bounds = intervalBounds(valueExp);
                if (bounds[0] != null && bounds[1] != null) {
                    String lo = BxmlExpressionToAcsl.translate(bounds[0], ctx);
                    String hi = BxmlExpressionToAcsl.translate(bounds[1], ctx);
                    if (lo != null && hi != null && !lo.isBlank() && !hi.isBlank()) {
                        return "\\forall integer x; belongs(x, " + acslIdent
                                + ") <==> " + lo + " <= x <= " + hi;
                    }
                }
            }
            // Fallback: membership via translated rhs (avoids equals(Set,Set) scoping issue).
            String rhs = BxmlExpressionToAcsl.translate(valueExp, ctx);
            if (rhs == null || rhs.isBlank()) return null;
            return "\\forall integer x; belongs(x, " + acslIdent + ") <==> belongs(x, " + rhs + ")";
        }
        String rhs = BxmlExpressionToAcsl.translate(valueExp, ctx);
        if (rhs == null || rhs.isBlank()) return null;
        return ident + " == " + rhs;
    }

    private static String qualifySetIdent(String ident, String abstractMachineName) {
        if (abstractMachineName == null || abstractMachineName.isBlank()) return ident;
        return abstractMachineName + "_" + ident;
    }

    private static Element[] intervalBounds(Element binaryExp) {
        Element[] bounds = new Element[2];
        int idx = 0;
        NodeList ch = binaryExp.getChildNodes();
        for (int i = 0; i < ch.getLength() && idx < 2; i++) {
            Node n = ch.item(i);
            if (n.getNodeType() != Node.ELEMENT_NODE) continue;
            Element e = (Element) n;
            if ("Attr".equals(e.getLocalName())) continue;
            bounds[idx++] = e;
        }
        return bounds;
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
