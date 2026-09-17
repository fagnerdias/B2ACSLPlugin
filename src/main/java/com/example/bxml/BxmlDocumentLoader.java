package com.example.bxml;

import java.nio.file.Path;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

import org.w3c.dom.Document;
import org.w3c.dom.Element;

/**
 * Entrada de leitura BXML → DOM usada em todo o pacote {@code bxml} (extraída de {@link
 * BxmlSetsTranslator}, que nominalmente é um "tradutor de conjuntos" mas também acumulou este
 * ponto de entrada de parsing genérico — extract-class puro: nenhuma linha de lógica mudou, só o
 * ficheiro em que vive).
 */
final class BxmlDocumentLoader {

    private BxmlDocumentLoader() {}

    /** Raiz {@code <Machine>} com parser namespace-aware (BXML 1.0 com {@code xmlns}). */
    static Element parseMachineElement(Path bxmlPath) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        try {
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        } catch (Exception ignored) {
            // opcional
        }
        DocumentBuilder builder = factory.newDocumentBuilder();
        Document doc = builder.parse(bxmlPath.toFile());
        Element root = doc.getDocumentElement();
        root.normalize();
        return root;
    }
}
