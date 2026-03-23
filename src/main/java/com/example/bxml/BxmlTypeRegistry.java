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

    /**
     * Tipo ACSL para declarações {@code logic} de variáveis B (a partir de {@code typref} em
     * {@code Abstract_Variables} / {@code Concrete_Variables}).
     *
     * <p>Ex.: {@code POW(INTEGER)} → {@code Set<integer>}; {@code POW(INTEGER*INTEGER)} (produto
     * sob {@code POW}, ex. sequência como relação) → {@code Relation_int_int}; {@code INTEGER} →
     * {@code integer}.
     */
    public String acslVariableLogicTypeFromTypref(int typref) {
        if (typref < 0) return "integer";
        return rawBTypeToAcslVariableLogicType(getRawType(typref));
    }

    /**
     * Converte o texto de tipo B (de {@link #getRawType}) num tipo de lógica ACSL para variáveis.
     */
    public static String rawBTypeToAcslVariableLogicType(String raw) {
        if (raw == null || raw.isBlank() || "UNKNOWN".equals(raw)) return "integer";
        String r = raw.trim();
        if (isScalarBTypeName(r)) {
            return acslElementTypeNameStatic(r);
        }
        if (r.startsWith("POW(") && r.endsWith(")")) {
            String inner = r.substring(4, r.length() - 1).trim();
            if (inner.contains("*")) {
                return powCartesianProductToAcslRelationType(inner);
            }
            if (inner.startsWith("POW(")) {
                return "Set<" + rawBTypeToAcslVariableLogicType(inner) + ">";
            }
            return "Set<" + acslElementTypeNameStatic(inner) + ">";
        }
        return "integer";
    }

    /**
     * Produto cartesiano B sob {@code POW} (ex.: {@code INTEGER*INTEGER}) → tipo relação na
     * ACSL_Lib ({@code Relation_int_int}, {@code Relation_int_bool}, …), não {@code \list}.
     */
    public static String powCartesianProductToAcslRelationType(String innerProduct) {
        if (innerProduct == null || !innerProduct.contains("*")) {
            return "Relation_int_int";
        }
        int star = innerProduct.indexOf('*');
        String lhs = innerProduct.substring(0, star).trim();
        String rhs = innerProduct.substring(star + 1).trim();
        String le = acslElementTypeNameStatic(lhs);
        String re = acslElementTypeNameStatic(rhs);
        if ("integer".equals(le) && "integer".equals(re)) {
            return "Relation_int_int";
        }
        if ("integer".equals(le) && "boolean".equals(re)) {
            return "Relation_int_bool";
        }
        if ("boolean".equals(le) && "integer".equals(re)) {
            return "Relation_bool_int";
        }
        return "Relation_int_int";
    }

    private static boolean isScalarBTypeName(String r) {
        return switch (r) {
            case "INTEGER", "INT", "NAT", "BOOL" -> true;
            default -> false;
        };
    }

    private static String acslElementTypeNameStatic(String bName) {
        if (bName == null || bName.isBlank()) return "integer";
        return switch (bName) {
            case "INTEGER", "INT", "NAT" -> "integer";
            case "BOOL" -> "boolean";
            default -> bName.toLowerCase();
        };
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
            if ("Attr".equals(child.getLocalName())) continue;
            return bxmlTypeExprToString(child);
        }
        return "UNKNOWN";
    }

    /** Árvore de tipo B em string (ex.: {@code POW(INTEGER*INTEGER)}, {@code POW(POW(INTEGER))}). */
    private static String bxmlTypeExprToString(Element e) {
        String ln = e.getLocalName();
        if ("Id".equals(ln)) {
            return e.getAttribute("value");
        }
        if ("Unary_Exp".equals(ln) && "POW".equals(e.getAttribute("op"))) {
            Element inner = firstNonAttrElementChild(e);
            return "POW(" + bxmlTypeExprToString(inner) + ")";
        }
        if ("Binary_Exp".equals(ln) && "*".equals(e.getAttribute("op"))) {
            Element[] pair = twoNonAttrElementChildren(e);
            if (pair[0] != null && pair[1] != null) {
                return bxmlTypeExprToString(pair[0]) + "*" + bxmlTypeExprToString(pair[1]);
            }
        }
        return "UNKNOWN";
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

    private static Element[] twoNonAttrElementChildren(Element parent) {
        Element[] out = new Element[2];
        int k = 0;
        NodeList nl = parent.getChildNodes();
        for (int i = 0; i < nl.getLength(); i++) {
            Node n = nl.item(i);
            if (n.getNodeType() != Node.ELEMENT_NODE) continue;
            Element el = (Element) n;
            if ("Attr".equals(el.getLocalName())) continue;
            out[k++] = el;
            if (k == 2) break;
        }
        return out;
    }
}
