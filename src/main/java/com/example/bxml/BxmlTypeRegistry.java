package com.example.bxml;

import java.util.HashMap;
import java.util.Map;

import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * Constrói o mapa {@code typref} → descrição de tipo a partir de {@code <TypeInfos>} (BXML 1.0).
 *
 * @see <a href="https://www.atelierb.eu/wp-content/uploads/2023/10/bxml-1.0.html">BXML 1.0</a>
 */
public final class BxmlTypeRegistry {

    private final Map<Integer, String> idToDisplay = new HashMap<>();

    public static BxmlTypeRegistry fromMachine(Element machineEl) {
        BxmlTypeRegistry r = new BxmlTypeRegistry();
        NodeList typeInfos = machineEl.getElementsByTagNameNS("*", "TypeInfos");
        if (typeInfos.getLength() == 0) return r;
        Element ti = (Element) typeInfos.item(0);
        NodeList types = ti.getElementsByTagNameNS("*", "Type");
        for (int i = 0; i < types.getLength(); i++) {
            Element t = (Element) types.item(i);
            String idStr = t.getAttribute("id");
            if (idStr.isBlank()) continue;
            int id = Integer.parseInt(idStr.trim());
            r.idToDisplay.put(id, typeToString(t));
        }
        return r;
    }

    /**
     * Elemento de tipo de um conjunto vazio / expressão, ex.: {@code POW(INTEGER)} → usado para {@code empty(...)}.
     */
    public String elementTypeNameForSetTypref(int typref) {
        String full = idToDisplay.getOrDefault(typref, "UNKNOWN");
        // POW(T) → T
        if (full.startsWith("POW(") && full.endsWith(")")) {
            String inner = full.substring(4, full.length() - 1).trim();
            // B: NAT ⊆ ℤ; POW(NAT) para conjuntos de naturais
            if ("INTEGER".equals(inner)) return "NAT";
            return inner;
        }
        return full;
    }

    public String getRawType(int typref) {
        return idToDisplay.getOrDefault(typref, "UNKNOWN");
    }

    /** Tipo em lógica ACSL para valores (ex.: {@code \forall integer x}). */
    public String acslLogicTypeForValueTypref(int typref) {
        if (typref < 0) return "integer";
        return acslElementTypeName(getRawType(typref));
    }

    /** Nome de elemento ACSL (minúsculas) para {@code Set<...>}. */
    public String acslElementTypeName(String bName) {
        if (bName == null || bName.isBlank()) return "integer";
        return switch (bName) {
            case "INTEGER", "INT", "NAT" -> "integer";
            case "BOOL" -> "boolean";
            default -> bName.toLowerCase();
        };
    }

    private static String typeToString(Element typeEl) {
        NodeList children = typeEl.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node n = children.item(i);
            if (n.getNodeType() != Node.ELEMENT_NODE) continue;
            Element child = (Element) n;
            String ln = child.getLocalName();
            if ("Id".equals(ln)) {
                return child.getAttribute("value");
            }
            if ("Unary_Exp".equals(ln) && "POW".equals(child.getAttribute("op"))) {
                NodeList inner = child.getElementsByTagNameNS("*", "Id");
                if (inner.getLength() > 0) {
                    String innerName = ((Element) inner.item(0)).getAttribute("value");
                    return "POW(" + innerName + ")";
                }
            }
            if ("Unary_Exp".equals(ln) && "POW".equals(child.getAttribute("op"))) {
                NodeList nested = child.getElementsByTagNameNS("*", "Unary_Exp");
                if (nested.getLength() > 0) {
                    return "POW(POW(...))"; // simplificado
                }
            }
        }
        return "UNKNOWN";
    }
}
