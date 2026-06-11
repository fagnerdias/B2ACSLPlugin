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
    /** Máquina de origem de cada elemento de compreensão (para rastreamento do dono). */
    private final Map<Element, String> elementToMachineName = new IdentityHashMap<>();
    /**
     * Nome da máquina raiz usada ao gerar o bloco axiomatic (passado a
     * {@link #formatAxiomaticBlock}). Todos os {@code referenceName} devem usar este nome
     * para garantir consistência com o axiomatic emitido.
     */
    private String rootAxiomaticMachineName = null;
    private Map<String, String> gluingSubstitutions = Map.of();

    private BxmlComprehensionRegistry() {}

    /** Igualdades do invariante (ex. {@code ran(numbers_s)} → {@code numbers}) para alinhar fingerprints. */
    public void setGluingSubstitutions(Map<String, String> gluing) {
        this.gluingSubstitutions = gluing == null ? Map.of() : gluing;
    }

    public static BxmlComprehensionRegistry fromMachine(Element machineEl) {
        BxmlComprehensionRegistry r = new BxmlComprehensionRegistry();
        r.rootAxiomaticMachineName = machineEl.getAttribute("name");
        BxmlTypeRegistry types = BxmlTypeRegistry.fromMachine(machineEl);
        walk(machineEl, r, types, isImplementationMachine(machineEl), machineEl.getAttribute("name"));
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
            boolean first = true;
            for (Element root : machineRoots) {
                if (root == null) {
                    continue;
                }
                // A máquina raiz (primeira da cadeia) define o namespace do axiomatic.
                if (first) {
                    r.rootAxiomaticMachineName = root.getAttribute("name");
                    first = false;
                }
                BxmlTypeRegistry types = BxmlTypeRegistry.fromMachine(root);
                walk(root, r, types, isImplementationMachine(root), root.getAttribute("name"));
            }
        }
        return r;
    }

    /** Registo vazio para calcular fingerprints de predicados (compreensões aninhadas caem em fallback). */
    public static BxmlComprehensionRegistry emptyForFingerprinting() {
        return new BxmlComprehensionRegistry();
    }

    /**
     * Tags BXML que delimitam corpos de operações de máquinas de implementação —
     * compreensões dentro delas não devem ser globais (geração específica da implementação).
     */
    private static final java.util.Set<String> IMPL_OPERATION_SCOPE_TAGS = java.util.Set.of(
            "Operations", "Initialisation", "Local_Operations");

    /** Retorna {@code true} se a raiz da máquina for uma implementação B ({@code type='implementation'}). */
    private static boolean isImplementationMachine(Element machineRoot) {
        return "implementation".equals(machineRoot.getAttribute("type"));
    }

    private static void walk(Element e, BxmlComprehensionRegistry r, BxmlTypeRegistry types,
                             boolean skipOperations, String machineName) {
        if (skipOperations && "Operations".equals(e.getLocalName())) {
            NodeList ch = e.getChildNodes();
            for (int i = 0; i < ch.getLength(); i++) {
                Node n = ch.item(i);
                if (n.getNodeType() != Node.ELEMENT_NODE) {
                    continue;
                }
                Element op = (Element) n;
                if ("Operation".equals(op.getLocalName())) {
                    registerComprehensionsInImplementationLoops(op, r, types, machineName);
                }
            }
            return;
        }
        if (skipOperations && IMPL_OPERATION_SCOPE_TAGS.contains(e.getLocalName())) {
            return;
        }
        registerComprehensionElement(e, r, types, machineName);
        NodeList ch = e.getChildNodes();
        for (int i = 0; i < ch.getLength(); i++) {
            Node n = ch.item(i);
            if (n.getNodeType() != Node.ELEMENT_NODE) continue;
            walk((Element) n, r, types, skipOperations, machineName);
        }
    }

    private static void registerComprehensionElement(
            Element e, BxmlComprehensionRegistry r, BxmlTypeRegistry types, String machineName) {
        if ("Quantified_Set".equals(e.getLocalName())) {
            r.ordered.add(e);
            r.elementTypes.put(e, types);
            r.elementToMachineName.put(e, machineName);
        } else if (BxmlExpressionToAcsl.isIntervalBinaryExp(e)) {
            r.ordered.add(e);
            r.elementTypes.put(e, types);
            r.elementToMachineName.put(e, machineName);
        }
    }

    /** Intervalos e compreensões em {@code INVARIANT}/{@code VARIANT} de loops WHILE na implementação. */
    private static void registerComprehensionsInImplementationLoops(
            Element operation, BxmlComprehensionRegistry r, BxmlTypeRegistry types, String machineName) {
        Element body = firstChildElement(operation, "Body");
        if (body != null) {
            walkWhileLoopComprehensions(body, r, types, machineName);
        }
    }

    private static void walkWhileLoopComprehensions(
            Element sub, BxmlComprehensionRegistry r, BxmlTypeRegistry types, String machineName) {
        if (sub == null) {
            return;
        }
        if ("While".equals(sub.getLocalName())) {
            Element inv = firstChildElement(sub, "Invariant");
            if (inv != null) {
                walkComprehensionSubtree(inv, r, types, machineName);
            }
            Element variant = firstChildElement(sub, "Variant");
            if (variant != null) {
                walkComprehensionSubtree(variant, r, types, machineName);
            }
            walkWhileLoopComprehensions(firstChildElement(sub, "Body"), r, types, machineName);
            return;
        }
        NodeList nl = sub.getChildNodes();
        for (int i = 0; i < nl.getLength(); i++) {
            Node n = nl.item(i);
            if (n.getNodeType() != Node.ELEMENT_NODE) {
                continue;
            }
            Element ch = (Element) n;
            if ("Attr".equals(ch.getLocalName())) {
                continue;
            }
            walkWhileLoopComprehensions(ch, r, types, machineName);
        }
    }

    private static void walkComprehensionSubtree(
            Element e, BxmlComprehensionRegistry r, BxmlTypeRegistry types, String machineName) {
        registerComprehensionElement(e, r, types, machineName);
        NodeList nl = e.getChildNodes();
        for (int i = 0; i < nl.getLength(); i++) {
            Node n = nl.item(i);
            if (n.getNodeType() != Node.ELEMENT_NODE) {
                continue;
            }
            walkComprehensionSubtree((Element) n, r, types, machineName);
        }
    }

    private static Element firstChildElement(Element parent, String localName) {
        NodeList nl = parent.getChildNodes();
        for (int i = 0; i < nl.getLength(); i++) {
            Node n = nl.item(i);
            if (n.getNodeType() != Node.ELEMENT_NODE) {
                continue;
            }
            Element e = (Element) n;
            if (localName.equals(e.getLocalName())) {
                return e;
            }
        }
        return null;
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
            // Sem gluing: deduplicação apenas por identidade sintática do predicado.
            // Expressões como `numbers` e `ran(numbers_s)` (equivalentes via gluing) ficam
            // em conjuntos separados, preservando a origem de cada compreensão.
            String fp = axiomFingerprint(qs, types, Map.of());
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

    /**
     * Nome ACSL do conjunto, ex. {@code main_fuel__set_comprehension_1} ({@code Quantified_Set} ou
     * intervalo {@code ..}).
     */
    public static String comprehensionSetName(String machineName, int index) {
        if (machineName == null || machineName.isBlank()) {
            return "set_comprehension_" + index;
        }
        return machineName.trim() + "__set_comprehension_" + index;
    }

    private static String comprehensionAxiomName(String machineName, int index) {
        if (machineName == null || machineName.isBlank()) {
            return "set_comp_" + index + "_values";
        }
        return machineName.trim() + "__set_comp_" + index + "_values";
    }

    public String referenceName(String machineName, Element comprehensionElement) {
        Integer idx = elementToIndex.get(comprehensionElement);
        if (idx == null) {
            return null;
        }
        // Usa o nome do axiomatic raiz para garantir consistência com formatAxiomaticBlock.
        String name = rootAxiomaticMachineName != null ? rootAxiomaticMachineName : machineName;
        return comprehensionSetName(name, idx);
    }

    public int size() {
        return ordered.size();
    }

    /**
     * @param translateCtx contexto da máquina (tipos {@code logic} das variáveis); necessário para
     *     expressões como {@code size(myseq)} → {@code \\length(myseq)} nos axiomas de compreensão.
     */
    public String formatAxiomaticBlock(String machineName, BxmlTranslateContext translateCtx) {
        if (isEmpty()) return "";
        int maxIdx = maxIndex();
        if (maxIdx == 0) return "";

        StringBuilder logics = new StringBuilder();
        StringBuilder axioms = new StringBuilder();
        for (int idx = 1; idx <= maxIdx; idx++) {
            Element qs = firstWithIndex(idx);
            if (qs == null) continue;
            if (BxmlExpressionToAcsl.isIntervalBinaryExp(qs)) continue;
            BxmlTypeRegistry types = elementTypes.get(qs);
            if (types == null) continue;
            String setType = acslSetTypeForElement(qs, types);
            String setName = comprehensionSetName(machineName, idx);
            logics.append("    logic ").append(setType).append(" ").append(setName).append(";\n");
            if (axioms.length() > 0) axioms.append("\n");
            appendComprehensionAxiom(axioms, qs, idx, machineName, types, translateCtx);
        }
        if (logics.length() == 0 && axioms.length() == 0) return "";

        String blockName = machineName + "_comprehension_sets";
        StringBuilder sb = new StringBuilder();
        sb.append("axiomatic ").append(blockName).append(" {\n");
        sb.append(logics);
        sb.append("\n");
        sb.append(axioms);
        sb.append("}\n");
        return sb.toString();
    }

    /** Como {@link #formatAxiomaticBlock(String, BxmlTranslateContext)} sem tipos de variáveis (só para testes / fallback). */
    public String formatAxiomaticBlock(String machineName) {
        return formatAxiomaticBlock(machineName, null);
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
        String out = formatAxiomaticBlock(machineName, null);
        seen.addAll(local);
        return out;
    }

    /** Contexto para traduzir o predicado da compreensão com os mesmos {@code logic} que o resto da máquina. */
    private BxmlTranslateContext comprehensionCtx(
            BxmlTypeRegistry types, BxmlTranslateContext translateCtx) {
        Map<String, String> vt =
                translateCtx != null ? translateCtx.variableLogicTypes() : Map.of();
        return new BxmlTranslateContext(types, this, vt);
    }

    private void appendComprehensionAxiom(
            StringBuilder sb,
            Element qs,
            int index,
            String machineName,
            BxmlTypeRegistry types,
            BxmlTranslateContext translateCtx) {
        if (BxmlExpressionToAcsl.isIntervalBinaryExp(qs)) {
            appendIntervalComprehensionAxiom(sb, qs, index, machineName, types, translateCtx);
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

        BxmlTranslateContext ctx = comprehensionCtx(types, translateCtx);
        String pred = BxmlPredicateToAcsl.translateBodyPredicate(body, ctx);

        String ref = comprehensionSetName(machineName, index);
        String axiomName = comprehensionAxiomName(machineName, index);

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
            StringBuilder sb,
            Element intervalEl,
            int index,
            String machineName,
            BxmlTypeRegistry types,
            BxmlTranslateContext translateCtx) {
        Element[] pair = BxmlExpressionToAcsl.twoDirectExpChildren(intervalEl);
        if (pair[0] == null || pair[1] == null) {
            return;
        }
        BxmlTranslateContext ctx = comprehensionCtx(types, translateCtx);
        String left = BxmlExpressionToAcsl.translate(pair[0], ctx);
        String right = BxmlExpressionToAcsl.translate(pair[1], ctx);
        left = BxmlGluingNormalizer.applySubstitutions(left, gluingSubstitutions);
        right = BxmlGluingNormalizer.applySubstitutions(right, gluingSubstitutions);

        String tr = intervalEl.getAttribute("typref");
        int typref = tr.isBlank() ? -1 : Integer.parseInt(tr.trim());
        String elem = types.elementTypeNameForSetTypref(typref);
        String acslT = types.acslElementTypeName(elem);

        String ref = comprehensionSetName(machineName, index);
        String axiomName = comprehensionAxiomName(machineName, index);
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
}
