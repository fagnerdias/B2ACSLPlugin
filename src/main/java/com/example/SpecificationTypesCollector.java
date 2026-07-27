package com.example;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import com.example.bxml.BxmlMachineVariables;
import com.example.bxml.BxmlTranslateContext;
import com.example.bxml.BxmlTypeRegistry;

/**
 * Identifica tipos utilizados na especificação ACSL gerada (texto {@code .acsl},
 * {@code ghost_operations.ci}) e nos {@code TypeInfos} BXML da máquina.
 */
public final class SpecificationTypesCollector {

    public static final String OUTPUT_FILE_NAME = "specification_types.txt";

    private static final Pattern LOGIC_TYPE_DECL =
            Pattern.compile("\\blogic\\s+([^;={]+?)\\s+[A-Za-z_]\\w*");

    private static final Pattern SET_INSTANTIATION =
            Pattern.compile("(?<![A-Za-z_])Set<([^>]+)>");

    private static final Pattern LIST_INSTANTIATION =
            Pattern.compile("\\\\list<([^>]+)>");

    private static final Pattern RELATION_LEGACY =
            Pattern.compile("\\bRelation_([a-z][a-z0-9_]*)_([a-z][a-z0-9_]*)\\b");

    private static final Pattern FUNCTION_LEGACY =
            Pattern.compile("\\bFunction_([a-z][a-z0-9_]*)_([a-z][a-z0-9_]*)\\b");

    private static final Pattern AXIOMATIC_TYPE_DECL = Pattern.compile("\\btype\\s+(\\w+)");

    private static final Pattern FORALL_LOGIC_TYPE =
            Pattern.compile("\\\\forall\\s+(integer|boolean|real)\\s+");

    private static final Pattern TUPLE_INSTANTIATION =
            Pattern.compile("(?<![A-Za-z_])Tuple<([^>]+)>");

    private SpecificationTypesCollector() {}

    /**
     * Recolhe tipos ACSL e tipos B usados, a partir do texto da especificação e das máquinas BXML.
     *
     * @param specificationTexts conteúdos ACSL ({@code .acsl}, {@code ghost_operations.ci}, …)
     * @param abstractMachineRoots elementos {@code Machine} abstratas (raiz)
     * @return lista ordenada e sem duplicados (tipos ACSL primeiro, depois pares B → ACSL)
     */
    public static List<String> collectUsedTypes(
            Iterable<String> specificationTexts, Iterable<Element> abstractMachineRoots) {
        LinkedHashSet<String> acslTypes = new LinkedHashSet<>();
        LinkedHashSet<String> bxmlEntries = new LinkedHashSet<>();

        if (specificationTexts != null) {
            for (String text : specificationTexts) {
                if (text != null && !text.isBlank()) {
                    collectFromAcslText(text, acslTypes);
                }
            }
        }
        if (abstractMachineRoots != null) {
            for (Element machine : abstractMachineRoots) {
                if (machine != null) {
                    collectFromMachine(machine, acslTypes, bxmlEntries);
                }
            }
        }

        List<String> out = new ArrayList<>();
        out.addAll(acslTypes);
        out.addAll(bxmlEntries);
        return List.copyOf(out);
    }

    /**
     * Grava a lista de tipos em {@code outputPath} (um tipo por linha; entradas BXML com {@code →}).
     */
    public static void writeTypesList(Path outputPath, List<String> types) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("# B2ACSL — tipos utilizados na especificação\n");
        sb.append("# Gerado automaticamente\n\n");
        if (types == null || types.isEmpty()) {
            sb.append("# (nenhum tipo identificado)\n");
        } else {
            for (String t : types) {
                sb.append(t).append('\n');
            }
        }
        Files.createDirectories(outputPath.getParent());
        Files.writeString(outputPath, sb.toString(), StandardCharsets.UTF_8);
    }

    private static void collectFromAcslText(String text, Set<String> out) {
        Matcher mLogic = LOGIC_TYPE_DECL.matcher(text);
        while (mLogic.find()) {
            addNormalizedType(out, mLogic.group(1));
        }
        Matcher mSet = SET_INSTANTIATION.matcher(text);
        while (mSet.find()) {
            addNormalizedType(out, "Set<" + mSet.group(1).trim() + ">");
        }
        Matcher mList = LIST_INSTANTIATION.matcher(text);
        while (mList.find()) {
            addNormalizedType(out, "\\list<" + mList.group(1).trim() + ">");
        }
        Matcher mTuple = TUPLE_INSTANTIATION.matcher(text);
        while (mTuple.find()) {
            addNormalizedType(out, "Tuple<" + mTuple.group(1).trim() + ">");
        }
        Matcher mRel = RELATION_LEGACY.matcher(text);
        while (mRel.find()) {
            addNormalizedType(
                    out, "Relation_" + mRel.group(1) + "_" + mRel.group(2));
        }
        Matcher mFun = FUNCTION_LEGACY.matcher(text);
        while (mFun.find()) {
            addNormalizedType(
                    out, "Function_" + mFun.group(1) + "_" + mFun.group(2));
        }
        Matcher mType = AXIOMATIC_TYPE_DECL.matcher(text);
        while (mType.find()) {
            addNormalizedType(out, mType.group(1));
        }
        Matcher mForall = FORALL_LOGIC_TYPE.matcher(text);
        while (mForall.find()) {
            addNormalizedType(out, mForall.group(1));
        }
        if (text.contains("Set<") || text.contains("Set ")) {
            addNormalizedType(out, "Set");
        }
        if (text.contains("Tuple<")) {
            addNormalizedType(out, "Tuple");
        }
        if (text.contains("Relation<") || text.contains("Relation_")) {
            addNormalizedType(out, "Relation");
        }
        if (text.contains("Function<") || text.contains("Function_")) {
            addNormalizedType(out, "Function");
        }
    }

    private static void collectFromMachine(
            Element machineEl, Set<String> acslTypes, Set<String> bxmlEntries) {
        BxmlTypeRegistry registry = BxmlTypeRegistry.fromMachine(machineEl);

        BxmlTranslateContext ctx = BxmlTranslateContext.forMachine(machineEl);
        for (String acsl : BxmlMachineVariables.inferVariableLogicTypes(machineEl, ctx).values()) {
            addNormalizedType(acslTypes, acsl);
        }
        for (String acsl :
                BxmlMachineVariables.inferConcreteConstantsLogicTypes(machineEl, registry)
                        .values()) {
            addNormalizedType(acslTypes, acsl);
        }
        collectTyprefsFromIds(machineEl, "Abstract_Variables", registry, acslTypes, bxmlEntries);
        collectTyprefsFromIds(machineEl, "Concrete_Variables", registry, acslTypes, bxmlEntries);
        collectTyprefsFromIds(machineEl, "Concrete_Constants", registry, acslTypes, bxmlEntries);
    }

    private static void collectTyprefsFromIds(
            Element machineEl,
            String sectionName,
            BxmlTypeRegistry registry,
            Set<String> acslTypes,
            Set<String> bxmlEntries) {
        Element block = firstChildElement(machineEl, sectionName);
        if (block == null) {
            return;
        }
        NodeList ch = block.getChildNodes();
        for (int i = 0; i < ch.getLength(); i++) {
            Node n = ch.item(i);
            if (n.getNodeType() != Node.ELEMENT_NODE) {
                continue;
            }
            Element e = (Element) n;
            if ("Attr".equals(e.getLocalName()) || !"Id".equals(e.getLocalName())) {
                continue;
            }
            String tr = e.getAttribute("typref");
            if (tr == null || tr.isBlank()) {
                continue;
            }
            try {
                int typref = Integer.parseInt(tr.trim());
                String raw = registry.getRawType(typref);
                if (!"UNKNOWN".equals(raw)) {
                    String acsl = registry.acslVariableLogicTypeFromTypref(typref);
                    addNormalizedType(acslTypes, acsl);
                    bxmlEntries.add(raw.trim() + "  →  " + acsl.trim());
                }
            } catch (NumberFormatException ignored) {
                // skip
            }
        }
    }

    private static void addNormalizedType(Set<String> out, String type) {
        if (type == null) {
            return;
        }
        String t = type.replaceAll("\\s+", " ").trim();
        if (t.isEmpty() || "void".equals(t)) {
            return;
        }
        if (t.endsWith(",") || t.endsWith("<")) {
            return;
        }
        if (t.contains("<") && !t.contains(">")) {
            return;
        }
        // Rejeita capturas que incluem conteúdo de predicado (e.g. "integer x; is_finite(s) ==>")
        if (t.contains(";") || t.contains("==>") || t.contains("(")) {
            return;
        }
        // Rejeita tipos legados do ghost_operations.ci (DTuple, DRelation, DSet, dummy_*)
        if (t.startsWith("DTuple") || t.startsWith("DRelation") || t.startsWith("DSet")
                || t.startsWith("dummy_")) {
            return;
        }
        if (t.length() == 1 && Character.isUpperCase(t.charAt(0))) {
            return;
        }
        out.add(t);
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
}
