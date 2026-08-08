package com.example.bxml;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
     * {@code alias -> nome real} herdado do {@code ANY_Sub} envolvente (se algum) no momento em que
     * o elemento foi registado — ver {@link BxmlInitialisationTranslator#analyzeAnySubAliases}. Uma
     * compreensão referenciando o alias de um {@code ANY} eliminado (ex. {@code ih} em {@code
     * ih(ii)}, RulerOfTheSeas's {@code InvestOnResources}) precisa que {@link
     * #freeVarsForComprehension} veja a variável REAL, não o alias transitório (sem tipo simples,
     * seria rejeitado por {@link #allSimpleForallBinderTypes}).
     */
    private final Map<Element, Map<String, String>> elementAnyAliases = new IdentityHashMap<>();
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
        walk(machineEl, r, types, isImplementationMachine(machineEl), machineEl.getAttribute("name"), Map.of());
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
                walk(root, r, types, isImplementationMachine(root), root.getAttribute("name"), Map.of());
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
                             boolean skipOperations, String machineName,
                             Map<String, String> anyAliases) {
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
        // ANY_Sub cujas variáveis ligadas são alias PURO de uma variável real (ex. "ih" por
        // "island_happiness", RulerOfTheSeas's InvestOnResources) — ver
        // BxmlInitialisationTranslator#analyzeAnySubAliases. Uma compreensão registada AQUI DENTRO
        // referenciando o alias precisa desta informação para resolver a variável real como sua
        // livre (não o alias, sem tipo simples aceite em posição de vinculação \forall) — ver o uso
        // em registerComprehensionElement/freeVarsForComprehension.
        Map<String, String> childAliases = anyAliases;
        if ("ANY_Sub".equals(e.getLocalName())) {
            BxmlInitialisationTranslator.AnySubAliasInfo info =
                    BxmlInitialisationTranslator.analyzeAnySubAliases(e);
            if (info != null && !info.eliminated().isEmpty()) {
                Map<String, String> merged = new java.util.LinkedHashMap<>(anyAliases);
                merged.putAll(info.eliminatedAliasToRealNames());
                childAliases = merged;
            }
        }
        registerComprehensionElement(e, r, types, machineName, anyAliases);
        NodeList ch = e.getChildNodes();
        for (int i = 0; i < ch.getLength(); i++) {
            Node n = ch.item(i);
            if (n.getNodeType() != Node.ELEMENT_NODE) continue;
            walk((Element) n, r, types, skipOperations, machineName, childAliases);
        }
    }

    private static void registerComprehensionElement(
            Element e, BxmlComprehensionRegistry r, BxmlTypeRegistry types, String machineName,
            Map<String, String> anyAliases) {
        if ("Quantified_Set".equals(e.getLocalName())) {
            r.ordered.add(e);
            r.elementTypes.put(e, types);
            r.elementToMachineName.put(e, machineName);
            if (!anyAliases.isEmpty()) r.elementAnyAliases.put(e, anyAliases);
        } else if (BxmlExpressionToAcsl.isIntervalBinaryExp(e)) {
            r.ordered.add(e);
            r.elementTypes.put(e, types);
            r.elementToMachineName.put(e, machineName);
        }
    }

    /** Intervalos e compreensões em {@code INVARIANT}/{@code VARIANT} de loops WHILE na implementação. */
    private static void registerComprehensionsInImplementationLoops(
            Element operation, BxmlComprehensionRegistry r, BxmlTypeRegistry types, String machineName) {
        Element body = BxmlDomUtils.firstChildElement(operation, "Body");
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
            Element inv = BxmlDomUtils.firstChildElement(sub, "Invariant");
            if (inv != null) {
                walkComprehensionSubtree(inv, r, types, machineName);
            }
            Element variant = BxmlDomUtils.firstChildElement(sub, "Variant");
            if (variant != null) {
                walkComprehensionSubtree(variant, r, types, machineName);
            }
            walkWhileLoopComprehensions(BxmlDomUtils.firstChildElement(sub, "Body"), r, types, machineName);
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
        registerComprehensionElement(e, r, types, machineName, Map.of());
        NodeList nl = e.getChildNodes();
        for (int i = 0; i < nl.getLength(); i++) {
            Node n = nl.item(i);
            if (n.getNodeType() != Node.ELEMENT_NODE) {
                continue;
            }
            walkComprehensionSubtree((Element) n, r, types, machineName);
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
        Element vars = BxmlDomUtils.firstChildElement(qs, "Variables");
        Element body = BxmlDomUtils.firstChildElement(qs, "Body");
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

    /**
     * @param ctx contexto do CHAMADOR (para detectar variáveis livres — ver {@link
     *     #freeVarsForComprehension}); pode ser {@code null} quando o chamador não tem um {@code
     *     BxmlTranslateContext} disponível, caso em que o sufixo de parâmetros é omitido (mantém o
     *     comportamento antigo, correto apenas para compreensões SEM variável livre).
     */
    public String referenceName(String machineName, Element comprehensionElement, BxmlTranslateContext ctx) {
        Integer idx = elementToIndex.get(comprehensionElement);
        if (idx == null) {
            return null;
        }
        // Usa o nome do axiomatic raiz para garantir consistência com formatAxiomaticBlock.
        String name = rootAxiomaticMachineName != null ? rootAxiomaticMachineName : machineName;
        String base = comprehensionSetName(name, idx);
        if (ctx == null) {
            return base;
        }
        BxmlTypeRegistry types = elementTypes.get(comprehensionElement);
        BxmlTranslateContext freeCtx = types != null ? comprehensionCtx(types, ctx) : ctx;
        Map<String, String> anyAliases =
                elementAnyAliases.getOrDefault(comprehensionElement, Map.of());
        List<String>[] free = freeVarsForComprehension(comprehensionElement, freeCtx, types, anyAliases);
        if (!allSimpleForallBinderTypes(free[1])) {
            // formatAxiomaticBlock omite esta compreensão inteira (ver o mesmo check lá) — não há
            // símbolo nenhum para referenciar; o chamador tem de cair para tradução inline.
            return null;
        }
        return base + callSuffix(free[0]);
    }

    /**
     * Nomes+tipos livres (universo REAL — ver {@link #freeVarsForComprehension}) da compreensão no
     * ÍNDICE {@code index}, ou duas listas vazias se sem livres ou índice não encontrado/omitido.
     * Usado por {@link DummyGhostAxiomaticBuilder} para declarar {@code dummy_set_comprehension_N}
     * com a MESMA aridade que {@link #referenceName}/{@link #formatAxiomaticBlock} usam do lado
     * real — sem isto, uma compreensão parametrizada (ex. {@code set_comprehension_2(pp)} em
     * RulerOfTheSeas's InvestOnResources) ficava com uma declaração ghost desencontrada, sempre
     * emitida como constante de aridade zero independentemente da real: "too many arguments"
     * quando o texto ghost chama {@code dummy_set_comprehension_2(pp)} contra essa declaração.
     * {@code dummyAxiomaticLogicType}-style conversão (Set→DSet) é responsabilidade do CHAMADOR —
     * esta função devolve os tipos do universo REAL tal e qual.
     */
    @SuppressWarnings("unchecked")
    public List<String>[] freeVarsForIndex(int index, BxmlTranslateContext ctx) {
        Element qs = firstWithIndex(index);
        if (qs == null || ctx == null) {
            return new List[] {List.of(), List.of()};
        }
        BxmlTypeRegistry types = elementTypes.get(qs);
        if (types == null) {
            return new List[] {List.of(), List.of()};
        }
        BxmlTranslateContext freeCtx = comprehensionCtx(types, ctx);
        Map<String, String> anyAliases = elementAnyAliases.getOrDefault(qs, Map.of());
        return freeVarsForComprehension(qs, freeCtx, types, anyAliases);
    }

    /**
     * {@code true} sse todos os tipos forem aceites pelo {@code -acsl-import} em posição de
     * vinculação {@code \forall} de um axioma NÃO genérico — confirmado empiricamente (testes
     * isolados) que só {@code integer}/{@code boolean} (e tipos ponteiro, não relevantes aqui)
     * passam; qualquer alias nomeado (ex. {@code Relation_int_int}) ou instanciação genérica (ex.
     * {@code Set<Tuple<integer,integer> >}) é rejeitado com "[Syntax error]" logo no token do tipo/
     * variável, mesmo sendo válido em posição de parâmetro de uma declaração {@code logic}.
     */
    private static boolean allSimpleForallBinderTypes(List<String> types) {
        for (String t : types) {
            if (!"integer".equals(t) && !"boolean".equals(t)) {
                return false;
            }
        }
        return true;
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
            BxmlTranslateContext ctx = comprehensionCtx(types, translateCtx);
            Map<String, String> anyAliases = elementAnyAliases.getOrDefault(qs, Map.of());
            List<String>[] free = freeVarsForComprehension(qs, ctx, types, anyAliases);
            if (!allSimpleForallBinderTypes(free[1])) {
                // Pelo menos uma variável livre precisa de um tipo composto/apelidado (ex.
                // Relation_int_int para um `ih` de um ANY WHERE ih = f <+ ...) que o parser do
                // -acsl-import categoricamente REJEITA em posição de vinculação `\forall` fora de um
                // axioma genérico ("[Syntax error] <tipo/variável>", confirmado empiricamente com
                // testes isolados — nem sequer a forma desaçucarada Set<Tuple<...>> passa, só
                // integer/boolean/ponteiro são aceites nessa posição). Sem forma válida de
                // axiomatizar, e como uma compreensão nesta situação só surge dentro de um
                // ANY/lambda ENVOLVENTE cujo corpo já é traduzido inline (nunca por referência ao
                // nome global — ver appendComprehensionAxiom), omite-se a compreensão inteira
                // (nem logic nem axiom) em vez de emitir algo que nunca vai parsear.
                continue;
            }
            String setType = acslSetTypeForElement(qs, types);
            String setName = comprehensionSetName(machineName, idx);
            logics.append("    logic ").append(setType).append(" ").append(setName)
                    .append(paramDeclList(free)).append(";\n");
            if (axioms.length() > 0) axioms.append("\n");
            appendComprehensionAxiom(axioms, qs, idx, machineName, types, ctx, free);
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

    /** Contexto para traduzir o predicado da compreensão com os mesmos {@code logic} que o resto da máquina. */
    private BxmlTranslateContext comprehensionCtx(
            BxmlTypeRegistry types, BxmlTranslateContext translateCtx) {
        Map<String, String> vt =
                translateCtx != null ? translateCtx.variableLogicTypes() : Map.of();
        BxmlTranslateContext ctx = new BxmlTranslateContext(types, this, vt);
        if (translateCtx != null) {
            // Necessário para #freeVarsForComprehension distinguir corretamente um nome de conjunto
            // diferido/enumerado (ex. ISLAND, sempre legítimo como referência global) de uma
            // variável realmente livre (ex. pp/ih vindos de um ANY/lambda envolvente) — sem isto
            // ISLAND era tratado como "livre" e virava um parâmetro espúrio da compreensão. Os 3
            // campos seguintes (enum*) são pela MESMA razão estrutural mas para a TRADUÇÃO do
            // predicado em si (BxmlPredicateToAcsl#translateBodyPredicate → translate() → caso "Id"):
            // sem enumeratedSetRenames, um `ISLAND` nu no corpo da compreensão fica sem o prefixo da
            // máquina (`RulerOfTheSeas_ISLAND`) e vira "unbound logic variable ISLAND" no kernel —
            // bug pré-existente, só nunca alcançado porque erros anteriores bloqueavam o parse antes.
            ctx = ctx.withDeclaredSetNames(translateCtx.declaredSetNames())
                    .withCrossMachineVariableLogicTypes(translateCtx.crossMachineVariableLogicTypes())
                    .withEnumRenames(translateCtx.enumValueRenames())
                    .withEnumeratedSetRenames(translateCtx.enumeratedSetRenames())
                    .withEnumeratedSetNames(translateCtx.enumeratedSetNames());
        }
        return ctx;
    }

    private void appendComprehensionAxiom(
            StringBuilder sb,
            Element qs,
            int index,
            String machineName,
            BxmlTypeRegistry types,
            BxmlTranslateContext ctx,
            List<String>[] free) {
        if (BxmlExpressionToAcsl.isIntervalBinaryExp(qs)) {
            appendIntervalComprehensionAxiom(sb, qs, index, machineName, types, ctx);
            return;
        }
        Element vars = BxmlDomUtils.firstChildElement(qs, "Variables");
        Element body = BxmlDomUtils.firstChildElement(qs, "Body");
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

        String pred = BxmlPredicateToAcsl.translateBodyPredicate(body, ctx);
        Map<String, String> anyAliases = elementAnyAliases.getOrDefault(qs, Map.of());
        if (!anyAliases.isEmpty()) {
            // ih(ii) -> function_apply(island_happiness, ii): free vars já excluiu o alias da lista
            // de parâmetros (ver freeVarsForComprehension), mas o CORPO traduzido acima ainda
            // referencia o nome cru — sem isto ficaria sem declaração nenhuma no axioma.
            pred = BxmlInitialisationTranslator.renameEliminatedAuxVars(pred, anyAliases.keySet(), anyAliases);
        }

        String ref = comprehensionSetName(machineName, index) + callSuffix(free[0]);
        String axiomName = comprehensionAxiomName(machineName, index);
        String outerForall = outerForallPrefix(free);

        sb.append("    axiom ").append(axiomName).append(":\n");

        if (idNodes.size() == 1) {
            Element id = idNodes.get(0);
            String v = id.getAttribute("value");
            String tr = id.getAttribute("typref");
            int typref = tr.isBlank() ? -1 : Integer.parseInt(tr.trim());
            String acslT = types.acslLogicTypeForValueTypref(typref);
            sb.append("        \\forall ").append(outerForall).append(acslT).append(" ").append(v).append(";\n");
            sb.append("        belongs(").append(v).append(", ").append(ref).append(") <==>\n");
            sb.append("            ").append(pred).append(";\n");
        } else {
            StringBuilder forall = new StringBuilder("        \\forall ").append(outerForall);
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

    /**
     * Variáveis livres do corpo de uma compreensão — fora das suas próprias variáveis ligadas e do
     * estado da máquina (ver {@link BxmlExpressionToAcsl#freeVarsAndTypes}). Quando não-vazio, a
     * compreensão referencia uma variável de um {@code ANY}/lambda ENVOLVENTE (ex. {@code pp} de um
     * parâmetro de operação, {@code ih} de um {@code ANY ih WHERE...}) e NÃO PODE ser axiomatizada
     * como constante global fechada — precisa de se tornar uma função lógica parametrizada por essas
     * variáveis (ver {@link #paramDeclList}/{@link #callSuffix}/{@link #outerForallPrefix}).
     *
     * <p>O TIPO de cada variável livre é resolvido pelo seu PRÓPRIO {@code typref} (autoritativo,
     * atribuído pela ferramenta B a toda ocorrência de {@code Id}), não pelo default {@code integer}
     * de {@link BxmlExpressionToAcsl#freeVarsAndTypes} (correto só por coincidência para variáveis
     * tipo elemento-de-conjunto-diferido, ex. {@code pp : PLAYER} — sempre {@code integer} nesta
     * codebase — mas ERRADO para uma variável tipo função/relação, ex. {@code ih}, ligada por
     * {@code ANY ih WHERE ih = f <+ %x.(...)}, cujo {@code typref} resolve a {@code POW(ISLAND*
     * INTEGER)} → {@code Relation_int_int}, não {@code integer}).
     */
    @SuppressWarnings("unchecked")
    private static List<String>[] freeVarsForComprehension(
            Element qs, BxmlTranslateContext ctx, BxmlTypeRegistry types,
            Map<String, String> anyAliases) {
        Element vars = BxmlDomUtils.firstChildElement(qs, "Variables");
        Element body = BxmlDomUtils.firstChildElement(qs, "Body");
        if (vars == null || body == null) {
            return new List[] {List.of(), List.of()};
        }
        List<String> boundNames = new ArrayList<>();
        NodeList vnodes = vars.getChildNodes();
        for (int i = 0; i < vnodes.getLength(); i++) {
            Node n = vnodes.item(i);
            if (n.getNodeType() != Node.ELEMENT_NODE) continue;
            Element e = (Element) n;
            if ("Id".equals(e.getLocalName())) boundNames.add(e.getAttribute("value"));
        }
        List<String>[] ft = GeneralizedQuantifierTranslator.freeVarsAndTypes(boundNames, ctx, body);
        List<String> names = new ArrayList<>();
        List<String> resolvedTypes = new ArrayList<>();
        for (int i = 0; i < ft[0].size(); i++) {
            String name = ft[0].get(i);
            // "Livre" só na aparência: nome é alias de um ANY já eliminado (ver walk/anyAliases) e
            // a variável REAL correspondente já é conhecida da máquina — a referência ao alias no
            // corpo é reescrita para o nome real em texto (renameEliminatedAuxVars, no chamador que
            // constrói o ensures), então não precisa virar parâmetro nenhum aqui; tratá-la como
            // livre resolvia o seu tipo (via <+ desaçucarado) a algo composto (ex.
            // Relation_int_int), sempre rejeitado por allSimpleForallBinderTypes — a compreensão
            // inteira nunca conseguia ser registada (ver o TODO logo abaixo do chamador).
            String realName = anyAliases.get(name);
            if (realName != null && ctx.variableLogicTypes().containsKey(realName)) {
                continue;
            }
            names.add(name);
            Integer typref = types != null ? findTyprefForIdName(body, name) : null;
            String t = typref != null ? types.acslVariableLogicTypeFromTypref(typref) : null;
            resolvedTypes.add(t != null && !t.isBlank() ? t : ft[1].get(i));
        }
        return new List[] {names, resolvedTypes};
    }

    /** {@code typref} da primeira ocorrência de {@code Id value=name} em {@code root} (ou nulo). */
    private static Integer findTyprefForIdName(Element root, String name) {
        if ("Id".equals(root.getLocalName()) && name.equals(root.getAttribute("value"))) {
            String tr = root.getAttribute("typref");
            if (!tr.isBlank()) {
                try {
                    return Integer.parseInt(tr.trim());
                } catch (NumberFormatException ignored) {
                    // ignora e continua a busca nos filhos (não deve acontecer em BXML válido)
                }
            }
        }
        NodeList nl = root.getChildNodes();
        for (int i = 0; i < nl.getLength(); i++) {
            Node n = nl.item(i);
            if (n.getNodeType() != Node.ELEMENT_NODE) continue;
            Integer found = findTyprefForIdName((Element) n, name);
            if (found != null) return found;
        }
        return null;
    }

    /** {@code "(tipo1 nome1, tipo2 nome2)"} para a declaração {@code logic}; vazio se sem livres. */
    private static String paramDeclList(List<String>[] free) {
        List<String> names = free[0];
        List<String> types = free[1];
        if (names.isEmpty()) return "";
        StringBuilder sb = new StringBuilder("(");
        for (int i = 0; i < names.size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append(types.get(i)).append(" ").append(names.get(i));
        }
        return sb.append(")").toString();
    }

    /** {@code "(nome1, nome2)"} para uma CHAMADA (sem tipos); vazio se sem livres. */
    private static String callSuffix(List<String> names) {
        return names.isEmpty() ? "" : "(" + String.join(", ", names) + ")";
    }

    /** {@code "tipo1 nome1, "} para prefixar o {@code \\forall} do axioma; vazio se sem livres. */
    private static String outerForallPrefix(List<String>[] free) {
        List<String> names = free[0];
        List<String> types = free[1];
        if (names.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < names.size(); i++) {
            sb.append(types.get(i)).append(" ").append(names.get(i)).append(", ");
        }
        return sb.toString();
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
