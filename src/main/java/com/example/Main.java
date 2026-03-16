package com.example;

import java.io.InputStream;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

public class Main {
    public static void main(String[] args) throws Exception {
        try (InputStream in = Main.class.getResourceAsStream("/example.xml")) {
            if (in == null) {
                throw new IllegalStateException("Não foi possível encontrar /example.xml no classpath.");
            }

            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            DocumentBuilder builder = factory.newDocumentBuilder();

            Document doc = builder.parse(in);
            doc.getDocumentElement().normalize();

            Element root = doc.getDocumentElement();
            NodeList personNodes = root.getElementsByTagName("person");
            if (personNodes.getLength() == 0) {
                throw new IllegalStateException("Elemento <person> não encontrado no XML.");
            }

            Element person = (Element) personNodes.item(0);
            String name = textContentOfFirst(person, "name");
            int age = Integer.parseInt(textContentOfFirst(person, "age"));

            System.out.println("XML lido com sucesso (DOM):");
            System.out.println("name=" + name + ", age=" + age);
        }
    }

    private static String textContentOfFirst(Element parent, String tagName) {
        NodeList nodes = parent.getElementsByTagName(tagName);
        if (nodes.getLength() == 0) {
            throw new IllegalStateException("Elemento <" + tagName + "> não encontrado no XML.");
        }
        return nodes.item(0).getTextContent().trim();
    }
}
