package com.example.model;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

public class Machine {
    private final String machineName;
    private final String machineType;
    private final List<Variables> variables;
    private final List<Invariant> invariants;
    private final List<Operations> operations;

    public Machine(String machineName, String machineType,
                   List<Variables> variables,
                   List<Invariant> invariants,
                   List<Operations> operations) {
        this.machineName = machineName;
        this.machineType = machineType;
        this.variables = variables == null ? List.of() : List.copyOf(variables);
        this.invariants = invariants == null ? List.of() : List.copyOf(invariants);
        this.operations = operations == null ? List.of() : List.copyOf(operations);
    }

    public String getMachineName() {
        return machineName;
    }

    public String getMachineType() {
        return machineType;
    }

    public List<Variables> getVariables() {
        return variables;
    }

    public List<Invariant> getInvariants() {
        return invariants;
    }

    public List<Operations> getOperations() {
        return operations;
    }

    /**
     * Lê um arquivo BXML de {@code src/main/resources} e inicializa a máquina e suas variáveis.
     * Ex.: {@code Machine.fromBxmlResource("OddEvenCounter.bxml")}
     */
    public static Machine fromBxmlResource(String resourceName) throws Exception {
        try (InputStream in = Machine.class.getResourceAsStream("/" + resourceName)) {
            if (in == null) {
                throw new IllegalStateException("Recurso não encontrado no classpath: /" + resourceName);
            }
            return fromBxmlStream(in);
        }
    }

    /**
     * Lê um arquivo {@code .bxml} pelo caminho e inicializa a máquina e suas variáveis.
     */
    public static Machine fromBxmlPath(Path bxmlPath) throws Exception {
        try (InputStream in = Files.newInputStream(bxmlPath)) {
            return fromBxmlStream(in);
        }
    }

    private static Machine fromBxmlStream(InputStream in) throws Exception {
        DocumentBuilder builder = newDocumentBuilder();
        Document doc = builder.parse(in);
        doc.getDocumentElement().normalize();

        Element machineEl = doc.getDocumentElement(); // <Machine ...>
        String machineName = machineEl.getAttribute("name");
        String machineType = machineEl.getAttribute("type");

        // 1) Captura as variáveis (por enquanto apenas Abstract_Variables)
        List<Variables> variables = new ArrayList<>();
        Optional<Element> abstractVarsEl = firstChildElementByLocalName(machineEl, "Abstract_Variables");
        if (abstractVarsEl.isPresent()) {
            List<String> varNames = readIdValuesUnder(abstractVarsEl.get());
            for (String varName : varNames) {
                String varType = inferVariableTypeFromInvariant(machineEl, varName).orElse("UNKNOWN");
                Variables v = new Variables(machineType, machineName, varName, varType);
                v.setAbstract(true);
                v.setConcrete(false);
                variables.add(v);
            }
        }

        return new Machine(machineName, machineType, variables, List.of(), List.of());
    }

    private static DocumentBuilder newDocumentBuilder() throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        // Segurança básica para XML não-confiável (sem DTD/external entities)
        trySetFeature(factory, "http://apache.org/xml/features/disallow-doctype-decl", true);
        trySetFeature(factory, "http://xml.org/sax/features/external-general-entities", false);
        trySetFeature(factory, "http://xml.org/sax/features/external-parameter-entities", false);
        factory.setExpandEntityReferences(false);
        return factory.newDocumentBuilder();
    }

    private static void trySetFeature(DocumentBuilderFactory factory, String feature, boolean value) {
        try {
            factory.setFeature(feature, value);
        } catch (Exception ignored) {
            // Alguns parsers podem não suportar; seguimos sem falhar.
        }
    }

    private static Optional<Element> firstChildElementByLocalName(Element parent, String localName) {
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node n = children.item(i);
            if (n.getNodeType() == Node.ELEMENT_NODE) {
                Element el = (Element) n;
                if (localName.equals(el.getLocalName())) return Optional.of(el);
            }
        }
        return Optional.empty();
    }

    private static List<String> readIdValuesUnder(Element parent) {
        NodeList ids = parent.getElementsByTagNameNS("*", "Id");
        List<String> result = new ArrayList<>();
        for (int i = 0; i < ids.getLength(); i++) {
            Element id = (Element) ids.item(i);
            String value = id.getAttribute("value");
            if (value != null && !value.isBlank()) result.add(value.trim());
        }
        return result;
    }

    /**
     * Inferência simples usada no seu exemplo:
     * procura por um nó {@code <Exp_Comparison op="<:">} onde o 1º {@code <Id>} é a variável
     * e o 2º {@code <Id>} é o tipo (ex.: NAT).
     */
    private static Optional<String> inferVariableTypeFromInvariant(Element machineEl, String variableName) {
        NodeList comparisons = machineEl.getElementsByTagNameNS("*", "Exp_Comparison");
        for (int i = 0; i < comparisons.getLength(); i++) {
            Element cmp = (Element) comparisons.item(i);
            if (!"<:".equals(cmp.getAttribute("op"))) continue;

            NodeList ids = cmp.getElementsByTagNameNS("*", "Id");
            if (ids.getLength() < 2) continue;

            Element left = (Element) ids.item(0);
            Element right = (Element) ids.item(1);

            String leftVal = left.getAttribute("value");
            if (variableName.equals(leftVal)) {
                String typeVal = right.getAttribute("value");
                if (typeVal != null && !typeVal.isBlank()) return Optional.of(typeVal.trim());
            }
        }
        return Optional.empty();
    }
}