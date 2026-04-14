package com.example.bxml;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * Percorre a(s) máquina(s) BXML, numera {@code Quantified_Set} e intervalos {@code ..} com deduplicação
 * por fingerprint e gera o bloco {@code axiomatic ..._comprehension_sets}. Vários ficheiros BXML
 * (abstração + refinamentos + implementações) podem fundir-se num único registo com
 * {@link #fromMachineChain(List, Map)} para um único conjunto de {@code set_comprehension_k} sem duplicar
 * lógicas equivalentes entre máquinas.
 */
public final class BxmlComprehensionRegistry {

    private final List<Element> ordered = new ArrayList<>();
    private final Map<Element, Integer> elementToIndex = new IdentityHashMap<>();
    /** Tipos B por nó de compreensão (cada máquina tem o seu {@link BxmlTypeRegistry}). */
    private final IdentityHashMap<Element, BxmlTypeRegistry> elementTypes = new IdentityHashMap<>();
    private Map<String, String> gluingSubstitutions = Map.of();

    private BxmlComprehensionRegistry() {}

    /** Igualdades do invariante (ex. {@code ran(numbers_s)} → {@code numbers}) para alinhar fingerprints. */
    public void setGluingSubstitutions(Map<String, String> gluing) {
        this.gluingSubstitutions = gluing == null ? Map.of() : gluing;
    }

    public static BxmlComprehensionRegistry fromMachine(Element machineEl) {
        BxmlComprehensionRegistry r = new BxmlComprehensionRegistry();
        BxmlTypeRegistry types = BxmlTypeRegistry.fromMachine(machineEl);
        walk(machineEl, r, types);
        return r;
    }

    /**
     * Percorre várias raízes {@code <Machine>} em sequência (ex. abstrata, refinamentos, implementações)
     * e acumula todas as compreensões num único registo, para deduplicação e um único bloco axiomatic no
     * {@code .acsl} raiz.
     */
    public static BxmlComprehensionRegistry fromMachineChain(
            List<Element> machineRoots, Map<String, String> gluing) {
        BxmlComprehensionRegistry r = new BxmlComprehensionRegistry();
        r.setGluingSubstitutions(gluing == null ? Map.of() : gluing);
        if (machineRoots != null) {
            for (Element root : machineRoots) {
                if (root == null) {
                    continue;
                }
                BxmlTypeRegistry types = BxmlTypeRegistry.fromMachine(root);
                walk(root, r, types);
            }
        }
        return r;
    }

    /** Registo vazio para calcular fingerprints de predicados (compreensões aninhadas caem em fallback). */
    public static BxmlComprehensionRegistry emptyForFingerprinting() {
        return new BxmlComprehensionRegistry();
    }

    private static void walk(Element e, BxmlComprehensionRegistry r, BxmlTypeRegistry types) {
        if ("Quantified_Set".equals(e.getLocalName())) {
            r.ordered.add(e);
            r.elementTypes.put(e, types);
        } else if (BxmlExpressionToAcsl.isIntervalBinaryExp(e)) {
            r.ordered.add(e);
            r.elementTypes.put(e, types);
        }
        NodeList ch = e.getChildNodes();
        for (int i = 0; i < ch.getLength(); i++) {
            Node n = ch.item(i);
            if (n.getNodeType() != Node.ELEMENT_NODE) continue;
            walk((Element) n, r, types);
        }
    }

    /**
     * Atribui o mesmo índice a compreensões com o mesmo fingerprint (extensão lógica equivalente na
     * tradução ACSL), usando o {@link BxmlTypeRegistry} da máquina de origem de cada nó.
     */
    public void assignDedupIndices() {
        elementToIndex.clear();
        Map<String, Integer> fpToIndex = new LinkedHashMap<>();
        int[] next = {1};
        for (Element qs : ordered) {
            BxmlTypeRegistry types = elementTypes.get(qs);
            if (types == null) {
                continue;
            }
            String fp = axiomFingerprint(qs, types, gluingSubstitutions);
            int idx = fpToIndex.computeIfAbsent(fp, k -> next[0]++);
            elementToIndex.put(qs, idx);
        }
    }

    /**
     * Fingerprint estável para comparar duas compreensões (tipo do conjunto, variáveis ligadas, predicado).
     */
    public static String axiomFingerprint(Element qs, BxmlTypeRegistry types) {
        return axiomFingerprint(qs, types, Map.of());
    }

    public static String axiomFingerprint(Element qs, BxmlTypeRegistry types, Map<String, String> gluing) {
        Map<String, String> g = gluing == null ? Map.of() : gluing;
        if (BxmlExpressionToAcsl.isIntervalBinaryExp(qs)) {
            String tr = qs.getAttribute("typref");
            String bounds = intervalBoundsFingerprint(qs, types);
            return tr + "|interval|integer:x|(" + BxmlGluingNormalizer.applySubstitutions(bounds, g) + ")";
        }
        Element vars = firstChildElement(qs, "Variables");
        Element body = firstChildElement(qs, "Body");
        String tr = qs.getAttribute("typref");
        if (vars == null || body == null) {
            return tr + "|invalid";
        }
        BxmlComprehensionRegistry stub = emptyForFingerprinting();
        BxmlTranslateContext ctx = new BxmlTranslateContext(types, stub, Map.of());
        String pred = BxmlPredicateToAcsl.translateBodyPredicate(body, ctx);
        pred = BxmlGluingNormalizer.applySubstitutions(pred, g);
        String varSig = boundVariablesSignature(vars);
        return tr + "|" + varSig + "|" + pred;
    }

    /**
     * Assinatura estável dos extremos de {@code a..b} para deduplicação (intervalos aninhados como
     * {@code I(lo,hi)} sem expandir o interior com índices de compreensão).
     */
    private static String intervalBoundsFingerprint(Element intervalEl, BxmlTypeRegistry types) {
        Element[] pair = BxmlExpressionToAcsl.twoDirectExpChildren(intervalEl);
        if (pair[0] == null || pair[1] == null) {
            return "?|?";
        }
        return fingerprintBoundExpr(pair[0], types) + "|" + fingerprintBoundExpr(pair[1], types);
    }

    private static String fingerprintBoundExpr(Element exp, BxmlTypeRegistry types) {
        if (BxmlExpressionToAcsl.isIntervalBinaryExp(exp)) {
            Element[] p = BxmlExpressionToAcsl.twoDirectExpChildren(exp);
            if (p[0] == null || p[1] == null) {
                return "I(?,?)";
            }
            return "I("
                    + fingerprintBoundExpr(p[0], types)
                    + ","
                    + fingerprintBoundExpr(p[1], types)
                    + ")";
        }
        BxmlTranslateContext stub = new BxmlTranslateContext(types, emptyForFingerprinting(), Map.of());
        return BxmlExpressionToAcsl.translate(exp, stub);
    }

    private static String boundVariablesSignature(Element vars) {
        StringBuilder sb = new StringBuilder();
        NodeList vnodes = vars.getChildNodes();
        for (int i = 0; i < vnodes.getLength(); i++) {
            Node n = vnodes.item(i);
            if (n.getNodeType() != Node.ELEMENT_NODE) continue;
            Element e = (Element) n;
            if (!"Id".equals(e.getLocalName())) continue;
            if (sb.length() > 0) sb.append(",");
            sb.append(e.getAttribute("typref").trim())
                    .append(":")
                    .append(e.getAttribute("value").trim());
        }
        return sb.toString();
    }

    /** Regista um fingerprint por índice global (após {@link #assignDedupIndices()}). */
    public void collectDistinctFingerprints(Set<String> target) {
        int max = maxIndex();
        for (int i = 1; i <= max; i++) {
            Element qs = firstWithIndex(i);
            if (qs == null) {
                continue;
            }
            BxmlTypeRegistry types = elementTypes.get(qs);
            if (types == null) {
                continue;
            }
            target.add(axiomFingerprint(qs, types, gluingSubstitutions));
        }
    }

    private int maxIndex() {
        return elementToIndex.values().stream().mapToInt(Integer::intValue).max().orElse(0);
    }

    private Element firstWithIndex(int index) {
        for (Element qs : ordered) {
            Integer v = elementToIndex.get(qs);
            if (v != null && v == index) return qs;
        }
        return null;
    }

    public boolean isEmpty() {
        return ordered.isEmpty();
    }

    /**
     * Maior índice {@code k} usado em {@code set_comprehension_k} após {@link #assignDedupIndices()} (0 se
     * não houver compreensões).
     */
    public int maxComprehensionIndex() {
        return maxIndex();
    }

    /** Nome ACSL do conjunto, ex. {@code set_comprehension_1} ({@code Quantified_Set} ou intervalo {@code ..}). */
    public String referenceName(Element comprehensionElement) {
        Integer idx = elementToIndex.get(comprehensionElement);
        if (idx == null) return null;
        return "set_comprehension_" + idx;
    }

    public int size() {
        return ordered.size();
    }

    public String formatAxiomaticBlock(String machineName) {
        if (isEmpty()) return "";
        int maxIdx = maxIndex();
        if (maxIdx == 0) return "";

        StringBuilder logics = new StringBuilder();
        StringBuilder axioms = new StringBuilder();
        for (int idx = 1; idx <= maxIdx; idx++) {
            Element qs = firstWithIndex(idx);
            if (qs == null) continue;
            BxmlTypeRegistry types = elementTypes.get(qs);
            if (types == null) continue;
            String setType = acslSetTypeForElement(qs, types);
            logics.append("    logic ")
                    .append(setType)
                    .append(" set_comprehension_")
                    .append(idx)
                    .append(";\n");
            if (axioms.length() > 0) axioms.append("\n");
            appendComprehensionAxiom(axioms, qs, idx, types);
        }

        String blockName = machineName + "_comprehension_sets";
        StringBuilder sb = new StringBuilder();
        sb.append("axiomatic ").append(blockName).append(" {\n");
        sb.append(logics);
        sb.append("\n");
        sb.append(axioms);
        sb.append("}\n");
        return sb.toString();
    }

    /**
     * Se <strong>todos</strong> os conjuntos desta máquina já estiverem representados em {@code seen}
     * (mesmo fingerprint que noutro bloco já emitido), não gera o bloco {@code axiomatic}.
     * Caso contrário gera o bloco completo e regista os fingerprints em {@code seen}.
     */
    public String formatAxiomaticBlockUnlessFullyCovered(String machineName, Set<String> seen) {
        int maxIdx = maxIndex();
        if (maxIdx == 0) return "";
        Set<String> local = new HashSet<>();
        for (int idx = 1; idx <= maxIdx; idx++) {
            Element qs = firstWithIndex(idx);
            if (qs == null) {
                continue;
            }
            BxmlTypeRegistry types = elementTypes.get(qs);
            if (types == null) {
                continue;
            }
            local.add(axiomFingerprint(qs, types, gluingSubstitutions));
        }
        if (!local.isEmpty() && seen.containsAll(local)) {
            return "";
        }
        String out = formatAxiomaticBlock(machineName);
        seen.addAll(local);
        return out;
    }

    private void appendComprehensionAxiom(
            StringBuilder sb, Element qs, int index, BxmlTypeRegistry types) {
        if (BxmlExpressionToAcsl.isIntervalBinaryExp(qs)) {
            appendIntervalComprehensionAxiom(sb, qs, index, types);
            return;
        }
        Element vars = firstChildElement(qs, "Variables");
        Element body = firstChildElement(qs, "Body");
        if (vars == null || body == null) return;

        List<Element> idNodes = new ArrayList<>();
        NodeList vnodes = vars.getChildNodes();
        for (int i = 0; i < vnodes.getLength(); i++) {
            Node n = vnodes.item(i);
            if (n.getNodeType() != Node.ELEMENT_NODE) continue;
            Element e = (Element) n;
            if ("Id".equals(e.getLocalName())) idNodes.add(e);
        }
        if (idNodes.isEmpty()) return;

        BxmlTranslateContext ctx = new BxmlTranslateContext(types, this, Map.of());
        String pred = BxmlPredicateToAcsl.translateBodyPredicate(body, ctx);

        String ref = "set_comprehension_" + index;
        String axiomName = "set_comp_" + index + "_values";

        sb.append("    axiom ").append(axiomName).append(":\n");

        if (idNodes.size() == 1) {
            Element id = idNodes.get(0);
            String v = id.getAttribute("value");
            String tr = id.getAttribute("typref");
            int typref = tr.isBlank() ? -1 : Integer.parseInt(tr.trim());
            String acslT = types.acslLogicTypeForValueTypref(typref);
            sb.append("        \\forall ").append(acslT).append(" ").append(v).append(";\n");
            sb.append("        belongs(").append(v).append(", ").append(ref).append(") <==>\n");
            sb.append("            ").append(pred).append(";\n");
        } else {
            StringBuilder forall = new StringBuilder("        \\forall ");
            for (int i = 0; i < idNodes.size(); i++) {
                if (i > 0) forall.append(", ");
                Element id = idNodes.get(i);
                String tr = id.getAttribute("typref");
                int typref = tr.isBlank() ? -1 : Integer.parseInt(tr.trim());
                forall.append(types.acslLogicTypeForValueTypref(typref))
                        .append(" ")
                        .append(id.getAttribute("value"));
            }
            forall.append(";\n");
            sb.append(forall);
            String v0 = idNodes.get(0).getAttribute("value");
            sb.append("        belongs(").append(v0).append(", ").append(ref).append(") <==>\n");
            sb.append("            ").append(pred).append(";\n");
        }
    }

    private void appendIntervalComprehensionAxiom(
            StringBuilder sb, Element intervalEl, int index, BxmlTypeRegistry types) {
        Element[] pair = BxmlExpressionToAcsl.twoDirectExpChildren(intervalEl);
        if (pair[0] == null || pair[1] == null) {
            return;
        }
        BxmlTranslateContext ctx = new BxmlTranslateContext(types, this, Map.of());
        String left = BxmlExpressionToAcsl.translate(pair[0], ctx);
        String right = BxmlExpressionToAcsl.translate(pair[1], ctx);
        left = BxmlGluingNormalizer.applySubstitutions(left, gluingSubstitutions);
        right = BxmlGluingNormalizer.applySubstitutions(right, gluingSubstitutions);

        String tr = intervalEl.getAttribute("typref");
        int typref = tr.isBlank() ? -1 : Integer.parseInt(tr.trim());
        String elem = types.elementTypeNameForSetTypref(typref);
        String acslT = types.acslElementTypeName(elem);

        String ref = "set_comprehension_" + index;
        String axiomName = "set_comp_" + index + "_values";
        sb.append("    axiom ").append(axiomName).append(":\n");
        sb.append("        \\forall ").append(acslT).append(" x;\n");
        sb.append("        belongs(x, ").append(ref).append(") <==>\n");
        sb.append("            (").append(left).append(") <= x && x <= (").append(right).append(");\n");
    }

    private static String acslSetTypeForElement(Element el, BxmlTypeRegistry types) {
        String tr = el.getAttribute("typref");
        int typref = tr.isBlank() ? -1 : Integer.parseInt(tr.trim());
        String elem = types.elementTypeNameForSetTypref(typref);
        String inner = types.acslElementTypeName(elem);
        return "Set<" + inner + ">";
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
