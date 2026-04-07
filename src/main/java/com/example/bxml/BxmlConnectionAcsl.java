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
     * Escreve {@code connection.acsl} em {@code outputDir} (junto ao {@code <baseName>.acsl}) e devolve o
     * path se houver pelo menos um elo abstração→refinamento com invariante traduzível. Várias raízes no mesmo
     * diretório sobrescrevem o ficheiro.
     */
    public static Optional<Path> writeConnectionAcsl(
            Path outputDir,
            String baseName,
            Element rootMachine,
            List<Element> mergedOrdered,
            Map<String, String> gluing)
            throws IOException {
        if (baseName == null || baseName.isBlank()) return Optional.empty();
        List<Element> chain = new ArrayList<>();
        chain.add(rootMachine);
        if (mergedOrdered != null) {
            chain.addAll(mergedOrdered);
        }
        if (chain.size() < 2) {
            return Optional.empty();
        }

        StringBuilder sb = new StringBuilder();
        sb.append("/* connection: funções lógicas refinamento (gerado) — máquina raiz ")
                .append(baseName)
                .append(" */\n");
        sb.append("axiomatic Connection_").append(baseName).append(" {\n\n");

        boolean any = false;
        for (int i = 1; i < chain.size(); i++) {
            Element parent = chain.get(i - 1);
            Element child = chain.get(i);
            if (isImplementationMachine(child)) {
                continue;
            }
            BxmlTranslateContext pctx = BxmlTranslateContext.forMachine(parent, gluing);
            BxmlTranslateContext cctx = BxmlTranslateContext.forMachine(child, gluing);

            String childInvRaw = firstInvariantBody(child, cctx);
            if (childInvRaw.isBlank()) continue;

            List<Element> parentInvEls = BxmlInvariantTranslator.listDirectInvariants(parent);
            if (parentInvEls.isEmpty()) continue;
            String parentInvRaw =
                    BxmlPredicateToAcsl.translateInvariantContent(parentInvEls.get(0), pctx);
            if (parentInvRaw.isBlank()) continue;

            Map<String, String> parentTypes =
                    BxmlMachineVariables.inferVariableLogicTypes(parent, pctx);
            Map<String, String> childTypes =
                    BxmlMachineVariables.inferVariableLogicTypes(child, cctx);

            Set<String> invIds = collectIdValuesInInvariant(child);
            Set<String> parentDeclared = new LinkedHashSet<>(parentTypes.keySet());

            for (String refinedName : listIntroducedVariableNames(child)) {
                if (!childTypes.containsKey(refinedName)) continue;
                Optional<String> abstractName = pickLinkingAbstractName(parentDeclared, invIds, refinedName);
                if (abstractName.isEmpty()) continue;
                String abs = abstractName.get();
                if (!parentTypes.containsKey(abs)) continue;

                String absType = parentTypes.get(abs);
                String refType = childTypes.get(refinedName);
                String fn = "return_valid_" + refinedName;

                String hypExists = replaceIdentifier(childInvRaw, abs, "s", refinedName, "out");
                String hypConc = replaceIdentifier(childInvRaw, abs, "s", refinedName, fn + "(s)");
                String parentScoped = replaceIdentifierSingle(parentInvRaw, abs, "s");

                sb.append("  logic ").append(refType).append(" ").append(fn).append("(").append(absType).append(" s);\n\n");

                sb.append("  axiom ").append(fn).append("_spec:\n");
                sb.append("    \\forall ").append(absType).append(" s;\n");
                sb.append("      (\\exists ").append(refType).append(" out; ").append(hypExists).append(") ==>\n");
                sb.append("      ").append(hypConc).append(";\n\n");

                sb.append("  axiom ").append(fn).append("_exists:\n");
                sb.append("    \\forall ").append(absType).append(" s;\n");
                sb.append("      ").append(parentScoped).append(" ==>\n");
                sb.append("      (\\exists ").append(refType).append(" out; ").append(hypExists).append(");\n\n");

                any = true;
            }
        }

        sb.append("}\n");
        if (!any) {
            return Optional.empty();
        }

        Files.createDirectories(outputDir);
        Path out = outputDir.resolve("connection.acsl");
        Files.writeString(out, sb.toString());
        return Optional.of(out);
    }

    /** Identificadores {@code Id} referidos nos invariantes da máquina (para dependências de refinamento). */
    public static Set<String> invariantReferencedIdentifiers(Element machineEl) {
        return collectIdValuesInInvariant(machineEl);
    }

    /** Variáveis de estado introduzidas: {@code Abstract_Variables} (refinamento) ou {@code Concrete_Variables} (implementação). */
    public static List<String> introducedStateVariableIds(Element machineEl) {
        return listIntroducedVariableNames(machineEl);
    }

    private static String firstInvariantBody(Element machineEl, BxmlTranslateContext ctx) {
        List<Element> invs = BxmlInvariantTranslator.listDirectInvariants(machineEl);
        if (invs.isEmpty()) return "";
        return BxmlPredicateToAcsl.translateInvariantContent(invs.get(0), ctx);
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
        Element block = firstChildElement(machineEl, sectionLocalName);
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
            Element p = firstPredChild(inv);
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

    private static boolean isImplementationMachine(Element machineEl) {
        String t = machineEl.getAttribute("type");
        return t != null && "implementation".equalsIgnoreCase(t.trim());
    }

    /**
     * Substitui {@code abstractName} e {@code refinedName} por tokens ACSL (ex. {@code s}, {@code out},
     * {@code fn(s)}). Ordem: nomes mais longos primeiro para não cortar prefixos ({@code ss} vs {@code
     * ss_r}).
     */
    private static String replaceIdentifier(
            String expr,
            String abstractName,
            String abstractTok,
            String refinedName,
            String refinedReplacement) {
        Map<String, String> map = new LinkedHashMap<>();
        map.put(refinedName, refinedReplacement);
        map.put(abstractName, abstractTok);
        return applyReplacements(expr, map);
    }

    private static String replaceIdentifierSingle(String expr, String name, String tok) {
        return applyReplacements(expr, Map.of(name, tok));
    }

    private static String applyReplacements(String expr, Map<String, String> repl) {
        List<Map.Entry<String, String>> entries = new ArrayList<>(repl.entrySet());
        entries.sort((a, b) -> Integer.compare(b.getKey().length(), a.getKey().length()));
        String result = expr;
        for (Map.Entry<String, String> e : entries) {
            String k = e.getKey();
            String v = e.getValue();
            Pattern pat = Pattern.compile("\\b" + Pattern.quote(k) + "\\b");
            result = pat.matcher(result).replaceAll(Matcher.quoteReplacement(v));
        }
        return result;
    }
}
