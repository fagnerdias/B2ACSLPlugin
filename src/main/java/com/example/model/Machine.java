package com.example.model;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

import org.w3c.dom.Document;
import org.w3c.dom.Element;

public class Machine {
    private final String machineName;

    public Machine(String machineName) {
        this.machineName = machineName;
    }

    public String getMachineName() {
        return machineName;
    }

    /**
     * Lê um arquivo {@code .bxml} pelo caminho e inicializa a máquina.
     */
    public static Machine fromBxmlPath(Path bxmlPath) throws Exception {
        try (InputStream in = Files.newInputStream(bxmlPath)) {
            DocumentBuilder builder = newDocumentBuilder();
            Document doc = builder.parse(in);
            doc.getDocumentElement().normalize();

            Element machineEl = doc.getDocumentElement(); // <Machine ...>
            String machineName = machineEl.getAttribute("name");
            return new Machine(machineName);
        }
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
}
