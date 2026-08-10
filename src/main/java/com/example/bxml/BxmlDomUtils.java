package com.example.bxml;

import java.util.ArrayList;
import java.util.List;

import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * Utilitários genéricos de navegação DOM/BXML, consolidados a partir de ~30 cópias
 * quase-idênticas independentemente definidas (privadas) em 12+ arquivos deste pacote —
 * puro extract-duplicate-method: nenhuma linha de lógica mudou, só deixou de haver uma cópia
 * por arquivo. Cada método aqui preserva exatamente o comportamento da(s) cópia(s) original(is)
 * (confirmado por comparação byte-a-byte, ignorando só estilo de chaves/nomes de variável locais).
 */
final class BxmlDomUtils {

    private BxmlDomUtils() {}

    /** Primeiro filho elemento cujo {@code localName} corresponda a {@code localName}. */
    static Element firstChildElement(Element parent, String localName) {
        if (parent == null) return null;
        NodeList nl = parent.getChildNodes();
        for (int i = 0; i < nl.getLength(); i++) {
            Node n = nl.item(i);
            if (n.getNodeType() != Node.ELEMENT_NODE) continue;
            Element e = (Element) n;
            if (localName.equals(e.getLocalName())) return e;
        }
        return null;
    }

    /** Primeiro filho elemento que não seja {@code Attr} (tipicamente o predicado/expressão real). */
    static Element firstPredChild(Element parent) {
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

    /** Primeiro filho elemento que não seja {@code Attr}. */
    static Element firstNonAttrElementChild(Element parent) {
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

    /** Os até 2 primeiros filhos elemento que não sejam {@code Attr} (operandos de um predicado binário). */
    static Element[] twoDirectPredChildren(Element parent) {
        Element[] out = new Element[2];
        int k = 0;
        NodeList nl = parent.getChildNodes();
        for (int i = 0; i < nl.getLength(); i++) {
            Node n = nl.item(i);
            if (n.getNodeType() != Node.ELEMENT_NODE) continue;
            Element e = (Element) n;
            if ("Attr".equals(e.getLocalName())) continue;
            out[k++] = e;
            if (k == 2) {
                break;
            }
        }
        return out;
    }

    /** Todos os filhos elemento que não sejam {@code Attr} (operandos de um predicado/expressão n-ário). */
    static List<Element> directExpChildren(Element parent) {
        List<Element> out = new ArrayList<>();
        NodeList nl = parent.getChildNodes();
        for (int i = 0; i < nl.getLength(); i++) {
            Node n = nl.item(i);
            if (n.getNodeType() != Node.ELEMENT_NODE) continue;
            Element e = (Element) n;
            if ("Attr".equals(e.getLocalName())) continue;
            out.add(e);
        }
        return out;
    }

    /** Primeiro filho elemento de uma substituição que não seja {@code Attr} (a substituição real). */
    static Element firstSubChild(Element parent) {
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

    /** Normaliza variantes do operador B de tipagem/pertença {@code :} (aparado, vazio → {@code ""}). */
    static String normalizeColonLikeOp(String raw) {
        if (raw == null) {
            return "";
        }
        String o = raw.trim();
        if (":".equals(o)) {
            return ":";
        }
        return o;
    }
}
