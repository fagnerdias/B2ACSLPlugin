package com.example.bxml;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.w3c.dom.Element;
import org.w3c.dom.Node;

/**
 * AVISOs da tradução: cada unidade omitida por conter uma construção sem tradução ({@link
 * Unsupported}). Mesmo texto do B2ACSLRules (ex. {@code C: invariante nao traduzido (linha N)}),
 * para o comparador pôr lado a lado. Sem repetição (a mesma unidade é traduzida mais de uma vez:
 * nomes, varreduras). Zerado no início de cada geração ({@link #reset}); impresso no fim como
 * {@code AVISO: <mensagem>}.
 */
public final class TranslationWarnings {

    private static final Set<String> MESSAGES = new LinkedHashSet<>();

    private TranslationWarnings() {}

    public static synchronized void reset() {
        MESSAGES.clear();
    }

    public static synchronized void add(String message) {
        if (message != null && !message.isBlank()) MESSAGES.add(message);
    }

    public static synchronized List<String> all() {
        return new ArrayList<>(MESSAGES);
    }

    /** Linha do elemento no fonte B ({@code <Attr><Pos l=...>}), ou do ancestral mais próximo que a tenha. */
    public static String line(Element el) {
        for (Node n = el; n instanceof Element e; n = n.getParentNode()) {
            Element attr = BxmlDomUtils.firstChildElement(e, "Attr");
            Element pos = attr == null ? null : BxmlDomUtils.firstChildElement(attr, "Pos");
            if (pos != null && !pos.getAttribute("l").isBlank()) {
                return pos.getAttribute("l").trim();
            }
        }
        return "?";
    }

    /** Nome do componente B (atributo {@code name} da raiz {@code <Machine>}) que contém o elemento. */
    public static String component(Element el) {
        Element root = el.getOwnerDocument() == null ? null : el.getOwnerDocument().getDocumentElement();
        return root == null ? "?" : root.getAttribute("name");
    }
}
