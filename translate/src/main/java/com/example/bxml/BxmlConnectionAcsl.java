package com.example.bxml;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * Gera {@code connection.acsl}: funções {@code return_valid_<var>} e axiomas ao longo da cadeia de
 * refinamento (abstração → refinamento apenas; não gera elo refinamento → implementação).
 */
public final class BxmlConnectionAcsl {

    private BxmlConnectionAcsl() {}

    /**
     * Variável de estado do pai que o invariante do filho liga a {@code refinedVariableName}
     * (ex. {@code ss} para {@code ss_r}), se existir.
     */
    public static Optional<String> linkingAbstractVariableName(
            Element parent,
            Element child,
            String refinedVariableName,
            Map<String, String> gluing) {
        BxmlTranslateContext pctx = BxmlTranslateContext.forMachine(parent, gluing);
        Map<String, String> parentTypes =
                BxmlMachineVariables.inferVariableLogicTypes(parent, pctx);
        Set<String> parentDeclared = new LinkedHashSet<>(parentTypes.keySet());
        Set<String> invIds = collectIdValuesInInvariant(child);
        return pickLinkingAbstractName(parentDeclared, invIds, refinedVariableName);
    }

    /**
     * Uma função {@code return_valid_<v>} para a variável {@code v} introduzida pelo refinamento
     * {@code owner} (forma do B2ACSLRules, helpers {@code refinedVars}/{@code glueVars}/{@code
     * relConjuncts}/{@code absConjuncts}).
     *
     * @param glueVariables variáveis abstratas de componentes anteriores citadas no invariante de
     *     {@code owner} (ordem da cadeia, sem repetir nomes): os parâmetros da função
     * @param glueOwners componente que declara cada variável colada (mesma ordem), para o tipo
     * @param relConjuncts conjunctos do invariante de {@code owner} que citam {@code v} e nenhuma
     *     outra variável nova de {@code owner}
     * @param absConjuncts conjunctos dos invariantes anteriores que só falam das variáveis coladas,
     *     com o componente de cada um
     */
    public record Link(
            Element owner,
            String variable,
            List<String> glueVariables,
            List<Element> glueOwners,
            List<Element> relConjuncts,
            List<Map.Entry<Element, Element>> absConjuncts) {

        /** Nome do parâmetro que representa a variável colada {@code g}: {@code s} ou {@code s_<g>}. */
        public String param(String g) {
            return glueVariables.size() == 1 ? "s" : "s_" + g;
        }

        /** {@code return_valid_<v>(<variáveis coladas>)}, o valor de {@code v} no bloco {@code _variables}. */
        public String callWithGlueVariables() {
            return "return_valid_" + variable + "(" + String.join(", ", glueVariables) + ")";
        }
    }

    /**
     * Elos de colagem da cadeia {@code root → merged} (só refinamentos): para cada variável que o
     * refinamento introduz (não declarada antes na cadeia; uma redeclaração com o mesmo nome é a
     * mesma variável, como no B), as variáveis coladas e os conjunctos que as relacionam.
     * Estrutural: não traduz nada (pode correr antes da tradução dos invariantes).
     */
    public static List<Link> plan(Element rootMachine, List<Element> mergedOrdered) {
        List<Element> chain = new ArrayList<>();
        chain.add(rootMachine);
        if (mergedOrdered != null) chain.addAll(mergedOrdered);
        Set<String> chainVarNames = new LinkedHashSet<>();
        for (Element m : chain) {
            chainVarNames.addAll(listIdValuesInSection(m, "Abstract_Variables"));
            chainVarNames.addAll(listIdValuesInSection(m, "Concrete_Variables"));
        }
        List<Link> links = new ArrayList<>();
        Set<String> earlierNames = new LinkedHashSet<>();
        LinkedHashMap<String, Element> earlierAbstract = new LinkedHashMap<>();
        for (int i = 0; i < chain.size(); i++) {
            Element m = chain.get(i);
            List<String> av = listIdValuesInSection(m, "Abstract_Variables");
            if (i > 0 && "refinement".equalsIgnoreCase(m.getAttribute("type").trim())) {
                List<String> newVars = av.stream().filter(v -> !earlierNames.contains(v)).toList();
                List<Element> conjuncts = invariantConjuncts(m);
                Set<String> used = new LinkedHashSet<>();
                for (Element c : conjuncts) used.addAll(varNames(c, chainVarNames));
                List<String> glue = new ArrayList<>();
                List<Element> glueOwners = new ArrayList<>();
                for (Map.Entry<String, Element> e : earlierAbstract.entrySet()) {
                    if (used.contains(e.getKey())) {
                        glue.add(e.getKey());
                        glueOwners.add(e.getValue());
                    }
                }
                for (String v : newVars) {
                    List<Element> rel = new ArrayList<>();
                    for (Element c : conjuncts) {
                        Set<String> names = varNames(c, chainVarNames);
                        if (!names.contains(v)) continue;
                        boolean other = names.stream().anyMatch(n -> !n.equals(v) && newVars.contains(n));
                        if (!other) rel.add(c);
                    }
                    if (rel.isEmpty() || glue.isEmpty()) continue;
                    List<Map.Entry<Element, Element>> abs = new ArrayList<>();
                    for (int j = 0; j < i; j++) {
                        Element earlier = chain.get(j);
                        for (Element c : invariantConjuncts(earlier)) {
                            Set<String> names = varNames(c, chainVarNames);
                            if (!names.isEmpty() && glue.containsAll(names)) {
                                abs.add(Map.entry(earlier, c));
                            }
                        }
                    }
                    links.add(new Link(m, v, List.copyOf(glue), List.copyOf(glueOwners), rel, abs));
                }
            }
            for (String v : av) {
                if (!earlierNames.contains(v)) earlierAbstract.put(v, m);
            }
            earlierNames.addAll(av);
            earlierNames.addAll(listIdValuesInSection(m, "Concrete_Variables"));
        }
        return links;
    }

    /**
     * Texto de {@code connection.acsl} para os elos de {@link #plan}; cada conjuncto é traduzido no
     * contexto do seu componente ({@code contextFor}, com os registos de funções da raiz: um SIGMA
     * ou lambda da colagem reaproveita a função do invariante).
     */
    public static String render(
            String baseName, List<Link> links, Function<Element, BxmlTranslateContext> contextFor) {
        StringBuilder sb = new StringBuilder();
        sb.append("/* connection: funções lógicas refinamento (gerado) — máquina raiz ")
                .append(baseName)
                .append(" */\n");
        sb.append("axiomatic Connection_").append(baseName).append(" {\n\n");
        for (Link link : links) {
            BxmlTranslateContext cctx = contextFor.apply(link.owner());
            String v = link.variable();
            String vType = typeOrInteger(cctx.variableLogicTypes().get(v));
            String fn = "return_valid_" + v;
            List<String> typedParams = new ArrayList<>();
            Map<String, String> glueToParam = new LinkedHashMap<>();
            for (int k = 0; k < link.glueVariables().size(); k++) {
                String g = link.glueVariables().get(k);
                String gType = typeOrInteger(
                        contextFor.apply(link.glueOwners().get(k)).variableLogicTypes().get(g));
                typedParams.add(gType + " " + link.param(g));
                glueToParam.put(g, link.param(g));
            }
            String params = String.join(", ", typedParams);
            String args = String.join(", ", glueToParam.values());

            // Conjunção sem tradução (Unsupported) fica de fora, como no B2ACSLRules (invList).
            List<String> rel = new ArrayList<>();
            for (Element c : link.relConjuncts()) {
                String t = BxmlPredicateToAcsl.translateConjunct(c, cctx);
                if (!t.isBlank() && !Unsupported.in(t)) rel.add(t);
            }
            List<String> abs = new ArrayList<>();
            for (Map.Entry<Element, Element> e : link.absConjuncts()) {
                String t = BxmlPredicateToAcsl.translateConjunct(e.getValue(), contextFor.apply(e.getKey()));
                if (!t.isBlank() && !Unsupported.in(t)) abs.add(t);
            }
            if (rel.isEmpty()) {
                // sem invariante de colagem traduzível: a função fica só declarada (o bloco
                // _variables do refinamento a usa) e sem axiomas
                TranslationWarnings.add(TranslationWarnings.component(link.owner()) + ": " + v
                        + " sem invariante de colagem; return_valid_" + v + " nao gerada");
                sb.append("  logic ").append(vType).append(" ").append(fn).append("(").append(params).append(");\n\n");
                continue;
            }
            Map<String, String> outEnv = new LinkedHashMap<>(glueToParam);
            outEnv.put(v, "out");
            Map<String, String> retEnv = new LinkedHashMap<>(glueToParam);
            retEnv.put(v, fn + "(" + args + ")");
            String relOut = applyReplacements(String.join(" && ", rel), outEnv);
            String relRet = applyReplacements(String.join(" && ", rel), retEnv);
            String absText = applyReplacements(String.join(" && ", abs), glueToParam);

            sb.append("  logic ").append(vType).append(" ").append(fn).append("(").append(params).append(");\n\n");

            sb.append("  axiom ").append(fn).append("_spec:\n");
            sb.append("    \\forall ").append(params).append(";\n");
            sb.append("      (\\exists ").append(vType).append(" out; ").append(relOut).append(") ==>\n");
            sb.append("      ").append(relRet).append(";\n\n");

            sb.append("  axiom ").append(fn).append("_exists:\n");
            sb.append("    \\forall ").append(params).append(";\n");
            if (!absText.isBlank()) {
                sb.append("      ").append(absText).append(" ==>\n");
            }
            sb.append("      (\\exists ").append(vType).append(" out; ").append(relOut).append(");\n\n");
        }
        sb.append("}\n");
        return sb.toString();
    }

    /** Escreve {@code connection.acsl} em {@code outputDir} (várias raízes no mesmo diretório sobrescrevem). */
    public static Path write(Path outputDir, String text) throws IOException {
        Files.createDirectories(outputDir);
        Path out = outputDir.resolve("connection.acsl");
        Files.writeString(out, text);
        return out;
    }

    private static String typeOrInteger(String t) {
        return t == null || t.isBlank() ? "integer" : t;
    }

    /** Conjunctos de topo de todos os {@code <Invariant>} da máquina. */
    private static List<Element> invariantConjuncts(Element machineEl) {
        List<Element> out = new ArrayList<>();
        for (Element inv : BxmlInvariantTranslator.listDirectInvariants(machineEl)) {
            out.addAll(BxmlPredicateToAcsl.topLevelConjunctElements(inv));
        }
        return out;
    }

    /** Nomes de variáveis da cadeia citados no elemento (helper {@code varNames} do rules). */
    private static Set<String> varNames(Element el, Set<String> chainVarNames) {
        Set<String> ids = new LinkedHashSet<>();
        collectIdsRecursive(el, ids);
        ids.retainAll(chainVarNames);
        return ids;
    }

    /** Identificadores {@code Id} referidos nos invariantes da máquina (para dependências de refinamento). */
    public static Set<String> invariantReferencedIdentifiers(Element machineEl) {
        return collectIdValuesInInvariant(machineEl);
    }

    /** Variáveis de estado introduzidas: {@code Abstract_Variables} (refinamento) ou {@code Concrete_Variables} (implementação). */
    public static List<String> introducedStateVariableIds(Element machineEl) {
        return listIntroducedVariableNames(machineEl);
    }

    /**
     * Variáveis introduzidas neste nível: {@code Abstract_Variables} em refinamento;
     * {@code Concrete_Variables} em implementação.
     */
    private static List<String> listIntroducedVariableNames(Element child) {
        String t = Optional.ofNullable(child.getAttribute("type")).orElse("").trim();
        String section;
        if ("implementation".equalsIgnoreCase(t)) {
            section = "Concrete_Variables";
        } else if ("refinement".equalsIgnoreCase(t)) {
            section = "Abstract_Variables";
        } else {
            return List.of();
        }
        return listIdValuesInSection(child, section);
    }

    private static List<String> listIdValuesInSection(Element machineEl, String sectionLocalName) {
        List<String> out = new ArrayList<>();
        Element block = BxmlDomUtils.firstChildElement(machineEl, sectionLocalName);
        if (block == null) return out;
        NodeList ch = block.getChildNodes();
        for (int i = 0; i < ch.getLength(); i++) {
            Node n = ch.item(i);
            if (n.getNodeType() != Node.ELEMENT_NODE) continue;
            Element e = (Element) n;
            if ("Attr".equals(e.getLocalName())) continue;
            if ("Id".equals(e.getLocalName())) {
                String v = e.getAttribute("value");
                if (v != null && !v.isBlank()) out.add(v.trim());
            }
        }
        return out;
    }

    private static Optional<String> pickLinkingAbstractName(
            Set<String> parentDeclared, Set<String> invIds, String refinedName) {
        List<String> candidates = new ArrayList<>();
        for (String p : parentDeclared) {
            if (p.equals(refinedName)) continue;
            if (invIds.contains(p)) candidates.add(p);
        }
        candidates.sort(Comparator.naturalOrder());
        return candidates.isEmpty() ? Optional.empty() : Optional.of(candidates.get(0));
    }

    private static Set<String> collectIdValuesInInvariant(Element machineEl) {
        Set<String> out = new LinkedHashSet<>();
        for (Element inv : BxmlInvariantTranslator.listDirectInvariants(machineEl)) {
            Element p = BxmlDomUtils.firstPredChild(inv);
            if (p != null) collectIdsRecursive(p, out);
        }
        return out;
    }

    private static void collectIdsRecursive(Element el, Set<String> out) {
        if ("Id".equals(el.getLocalName())) {
            String v = el.getAttribute("value");
            if (v != null && !v.isBlank()) out.add(v.trim());
        }
        NodeList nl = el.getChildNodes();
        for (int i = 0; i < nl.getLength(); i++) {
            Node n = nl.item(i);
            if (n.getNodeType() != Node.ELEMENT_NODE) continue;
            Element ch = (Element) n;
            if ("Attr".equals(ch.getLocalName())) continue;
            collectIdsRecursive(ch, out);
        }
    }

    /** Troca cada identificador inteiro de {@code repl} numa passada só (um substituto nunca é retrocado). */
    private static String applyReplacements(String expr, Map<String, String> repl) {
        if (repl.isEmpty() || expr.isEmpty()) return expr;
        List<String> keys = new ArrayList<>(repl.keySet());
        keys.sort((a, b) -> Integer.compare(b.length(), a.length()));
        StringBuilder alt = new StringBuilder();
        for (String k : keys) {
            if (alt.length() > 0) alt.append('|');
            alt.append(Pattern.quote(k));
        }
        Matcher m = Pattern.compile("\\b(?:" + alt + ")\\b").matcher(expr);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            m.appendReplacement(out, Matcher.quoteReplacement(repl.get(m.group())));
        }
        m.appendTail(out);
        return out.toString();
    }
}
