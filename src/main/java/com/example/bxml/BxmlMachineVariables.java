package com.example.bxml;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * Declara variáveis de máquina B em blocos {@code axiomatic …_variables}, com tipos inferidos
 * preferencialmente a partir do invariante (ex.: {@code numbers <: NAT} → {@code Set<integer>}) e
 * recurso ao {@code typref} de {@code Abstract_Variables} / {@code Concrete_Variables}.
 *
 * <p>Sequências B ({@code iseq}, {@code POW(T*T)}) traduzem-se para {@code \list<elemento>} em ACSL.
 */
public final class BxmlMachineVariables {

    private BxmlMachineVariables() {}

    /**
     * Bloco {@code axiomatic NomeMaquina_variables { logic … }} ou vazio se não houver variáveis
     * declaradas ({@code name} do {@code <Machine>}).
     */
    public static String formatAxiomaticBlock(Element machineEl, BxmlTranslateContext ctx) {
        String machineName = machineEl.getAttribute("name");
        if (machineName == null || machineName.isBlank()) return "";
        return formatVariablesBlock(machineName, inferVariableLogicTypes(machineEl, ctx));
    }

    private static String formatVariablesBlock(String blockName, Map<String, String> types) {
        if (types.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        sb.append("axiomatic ").append(blockName).append("_variables {\n");
        for (Map.Entry<String, String> e : types.entrySet()) {
            sb.append("    logic ").append(e.getValue()).append(" ").append(e.getKey()).append(";\n");
        }
        sb.append("}\n");
        return sb.toString();
    }

    /**
     * Ordem: declaração em {@code Abstract_Variables} / {@code Concrete_Variables}; tipos do
     * invariante sobrepõem-se ao {@code typref} quando há informação compatível.
     */
    public static Map<String, String> inferVariableLogicTypes(
            Element machineEl, BxmlTranslateContext ctx) {
        BxmlTypeRegistry types = ctx.types();
        List<Element> varIds = listDeclaredVariableIds(machineEl);
        Map<String, String> fromInvariant = inferTypesFromInvariants(machineEl, types);

        LinkedHashMap<String, String> out = new LinkedHashMap<>();
        for (Element idEl : varIds) {
            String name = idEl.getAttribute("value");
            if (name == null || name.isBlank()) continue;
            String trAttr = idEl.getAttribute("typref");
            int typref = trAttr.isBlank() ? -1 : Integer.parseInt(trAttr.trim());

            String t = fromInvariant.get(name);
            if (t == null) {
                t = types.acslVariableLogicTypeFromTypref(typref);
            }
            out.put(name, t);
        }
        return out;
    }

    /** {@code Abstract_Variables} e {@code Concrete_Variables} (filhos diretos de {@code Machine}). */
    public static List<Element> listDeclaredVariableIds(Element machineEl) {
        List<Element> out = new ArrayList<>();
        String[] sections = {"Abstract_Variables", "Concrete_Variables"};
        for (String sec : sections) {
            Element block = firstChildElement(machineEl, sec);
            if (block == null) continue;
            NodeList ch = block.getChildNodes();
            for (int i = 0; i < ch.getLength(); i++) {
                Node n = ch.item(i);
                if (n.getNodeType() != Node.ELEMENT_NODE) continue;
                Element e = (Element) n;
                if ("Attr".equals(e.getLocalName())) continue;
                if ("Id".equals(e.getLocalName())) out.add(e);
            }
        }
        return out;
    }

    /**
     * Nomes para cláusulas {@code assigns} na inicialização: variáveis de implementação
     * ({@code Concrete_Variables}) de cada máquina fundida com {@code type="implementation"},
     * no formato {@code NomeMaquinaAbstrata__nomeVar} (contrato C alinhado à raiz abstrata).
     */
    public static List<String> listImplementationAssignTargets(
            String abstractMachineName, List<Element> mergedMachineElements) {
        if (abstractMachineName == null || abstractMachineName.isBlank()) return List.of();
        List<String> out = new ArrayList<>();
        for (Element mel : mergedMachineElements) {
            if (!isImplementationMachine(mel)) continue;
            Element concrete = firstChildElement(mel, "Concrete_Variables");
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
                    out.add(abstractMachineName + "__" + v);
                }
            }
        }
        return out;
    }

    private static boolean isImplementationMachine(Element machineEl) {
        String t = machineEl.getAttribute("type");
        return t != null && "implementation".equalsIgnoreCase(t.trim());
    }

    /**
     * Percorre todos os {@code <Invariant>} e extrai restrições de tipo:
     * <ul>
     *   <li>{@code v <: T} com {@code T} nome de tipo base → {@code Set<…>}</li>
     *   <li>{@code v : T} com {@code T} primitivo → elemento escalar</li>
     *   <li>{@code v : iseq(T)} → {@code \list<elemento(T)>} (sequências em ACSL)</li>
     * </ul>
     */
    static Map<String, String> inferTypesFromInvariants(Element machineEl, BxmlTypeRegistry types) {
        LinkedHashMap<String, String> acc = new LinkedHashMap<>();
        NodeList children = machineEl.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node n = children.item(i);
            if (n.getNodeType() != Node.ELEMENT_NODE) continue;
            Element e = (Element) n;
            if (!"Invariant".equals(e.getLocalName())) continue;
            Element pred = firstPredChild(e);
            if (pred != null) walkPredForVariableTypes(pred, types, acc);
        }
        return acc;
    }

    private static void walkPredForVariableTypes(
            Element p, BxmlTypeRegistry types, Map<String, String> acc) {
        String ln = p.getLocalName();
        switch (ln) {
            case "Exp_Comparison" -> handleExpComparisonForTypes(p, types, acc);
            case "Nary_Pred", "Unary_Pred", "Binary_Pred" -> {
                NodeList nl = p.getChildNodes();
                for (int i = 0; i < nl.getLength(); i++) {
                    Node n = nl.item(i);
                    if (n.getNodeType() != Node.ELEMENT_NODE) continue;
                    Element ch = (Element) n;
                    if ("Attr".equals(ch.getLocalName())) continue;
                    walkPredForVariableTypes(ch, types, acc);
                }
            }
            default -> {
            }
        }
    }

    private static void handleExpComparisonForTypes(
            Element cmp, BxmlTypeRegistry types, Map<String, String> acc) {
        String op = normalizeComparisonOp(cmp.getAttribute("op"));
        Element[] pair = BxmlExpressionToAcsl.twoDirectExpChildren(cmp);
        if (pair[0] == null || pair[1] == null) return;

        if ("<:".equals(op)) {
            if ("Id".equals(pair[0].getLocalName())) {
                String v = pair[0].getAttribute("value");
                if ("Id".equals(pair[1].getLocalName())) {
                    String rhs = pair[1].getAttribute("value");
                    if (isNamedBaseType(rhs)) {
                        acc.put(v, "Set<" + types.acslElementTypeName(rhs) + ">");
                    }
                }
            }
            return;
        }
        if (":".equals(op)) {
            if ("Id".equals(pair[0].getLocalName())) {
                String v = pair[0].getAttribute("value");
                if ("Id".equals(pair[1].getLocalName())) {
                    String rhs = pair[1].getAttribute("value");
                    if (isNamedBaseType(rhs)) {
                        acc.put(v, types.acslElementTypeName(rhs));
                    }
                    return;
                }
                if ("Unary_Exp".equals(pair[1].getLocalName())
                        && ("seq".equals(pair[1].getAttribute("op"))
                            ||"iseq".equals(pair[1].getAttribute("op")))) {
                    acc.put(v, acslListTypeForIseqUnary(pair[1], types));
                }
            }
        }
    }

    /** Argumento de {@code iseq(T)} no B → {@code \list<…>} com o tipo elemento de {@code T}. */
    private static String acslListTypeForIseqUnary(Element unaryIseq, BxmlTypeRegistry types) {
        Element arg = firstNonAttrElementChild(unaryIseq);
        if (arg != null && "Id".equals(arg.getLocalName())) {
            String rhs = arg.getAttribute("value");
            if (isNamedBaseType(rhs)) {
                return "\\list<" + types.acslElementTypeName(rhs) + ">";
            }
        }
        return "\\list<integer>";
    }

    private static Element firstNonAttrElementChild(Element parent) {
        NodeList nl = parent.getChildNodes();
        for (int i = 0; i < nl.getLength(); i++) {
            Node n = nl.item(i);
            if (n.getNodeType() != Node.ELEMENT_NODE) continue;
            Element el = (Element) n;
            if ("Attr".equals(el.getLocalName())) continue;
            return el;
        }
        return null;
    }

    private static String normalizeComparisonOp(String op) {
        if (op == null) return "";
        String o = op.trim();
        if ("&lt;:".equals(o)) return "<:";
        return o;
    }

    private static boolean isNamedBaseType(String name) {
        if (name == null || name.isBlank()) return false;
        return switch (name) {
            case "NAT", "INTEGER", "INT", "BOOL" -> true;
            default -> false;
        };
    }

    private static Element firstPredChild(Element parent) {
        NodeList nl = parent.getChildNodes();
        for (int i = 0; i < nl.getLength(); i++) {
            Node n = nl.item(i);
            if (n.getNodeType() != Node.ELEMENT_NODE) continue;
            Element e = (Element) n;
            if ("Attr".equals(e.getLocalName())) continue;
            return e;
        }
        return null;
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
