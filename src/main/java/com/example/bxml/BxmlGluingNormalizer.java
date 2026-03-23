package com.example.bxml;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * Extrai igualdades do invariante B (ex.: {@code numbers = ran(numbers_s)}) na tradução ACSL e
 * aplica substituições {@code ran(numbers_s) → numbers} para comparar compreensões entre
 * abstração e refinamento/implementação.
 */
public final class BxmlGluingNormalizer {

    private BxmlGluingNormalizer() {}

    /**
     * Percorre todos os {@code .bxml} e agrega substituições {@code exprRan → idSimples} a partir de
     * comparações {@code =} no invariante.
     */
    public static Map<String, String> collectFromAllBxmlFiles(List<Path> bxmlPaths) {
        Map<String, String> acc = new HashMap<>();
        for (Path p : bxmlPaths) {
            try {
                Element machineEl = parseMachineRoot(p);
                Element inv = firstChildElement(machineEl, "Invariant");
                if (inv == null) continue;
                BxmlTypeRegistry types = BxmlTypeRegistry.fromMachine(machineEl);
                BxmlComprehensionRegistry stub = BxmlComprehensionRegistry.emptyForFingerprinting();
                BxmlTranslateContext ctx = new BxmlTranslateContext(types, stub);
                collectFromInvariantSubtree(inv, ctx, acc);
            } catch (Exception ignored) {
            }
        }
        return acc;
    }

    public static String applySubstitutions(String pred, Map<String, String> gluing) {
        if (pred == null || gluing == null || gluing.isEmpty()) return pred;
        List<String> keys = new ArrayList<>(gluing.keySet());
        keys.sort(Comparator.comparing(String::length).reversed());
        String s = pred;
        for (String k : keys) {
            String v = gluing.get(k);
            if (v != null) s = s.replace(k, v);
        }
        return s;
    }

    private static void collectFromInvariantSubtree(Element invariantEl, BxmlTranslateContext ctx, Map<String, String> out) {
        Element p = firstPredChild(invariantEl);
        if (p != null) walkPred(p, ctx, out);
    }

    private static void walkPred(Element p, BxmlTranslateContext ctx, Map<String, String> out) {
        String ln = p.getLocalName();
        switch (ln) {
            case "Exp_Comparison" -> {
                if ("=".equals(p.getAttribute("op"))) {
                    Element[] pair = BxmlExpressionToAcsl.twoDirectExpChildren(p);
                    if (pair[0] != null && pair[1] != null) {
                        String l = BxmlExpressionToAcsl.translate(pair[0], ctx);
                        String r = BxmlExpressionToAcsl.translate(pair[1], ctx);
                        registerRanToCanonical(l, r, out);
                    }
                }
            }
            case "Nary_Pred", "Unary_Pred", "Binary_Pred" -> {
                NodeList nl = p.getChildNodes();
                for (int i = 0; i < nl.getLength(); i++) {
                    Node n = nl.item(i);
                    if (n.getNodeType() != Node.ELEMENT_NODE) continue;
                    Element e = (Element) n;
                    if ("Attr".equals(e.getLocalName())) continue;
                    walkPred(e, ctx, out);
                }
            }
            default -> {
            }
        }
    }

    private static void registerRanToCanonical(String a, String b, Map<String, String> out) {
        if (isRanCall(a) && isSimpleIdentifier(b)) {
            out.putIfAbsent(a, b);
        }
        if (isRanCall(b) && isSimpleIdentifier(a)) {
            out.putIfAbsent(b, a);
        }
    }

    private static boolean isRanCall(String s) {
        return s != null && s.startsWith("ran(") && s.endsWith(")");
    }

    private static boolean isSimpleIdentifier(String s) {
        return s != null && s.matches("[a-zA-Z_][a-zA-Z0-9_]*");
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

    private static Element parseMachineRoot(Path path) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        try {
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        } catch (Exception ignored) {
        }
        DocumentBuilder builder = factory.newDocumentBuilder();
        try (InputStream in = Files.newInputStream(path)) {
            Document doc = builder.parse(in);
            doc.getDocumentElement().normalize();
            return doc.getDocumentElement();
        }
    }
}
