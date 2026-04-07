package com.example.bxml;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * Gera {@code ghost_operations.ci}: variáveis ghost por variável abstrata, axiomatica {@code dummy_ghost}
 * com tipo {@code DSet} paralelo a {@code Set}, e contratos ghost para inicialização e operações que
 * atribuem a variáveis abstratas.
 */
public final class GhostOperationsCiGenerator {

    private static final String GHOST_FILE = "ghost_operations.ci";

    private GhostOperationsCiGenerator() {}

    public static Path targetPath(Path cDir) {
        return cDir.resolve(GHOST_FILE);
    }

    /**
     * Escreve {@link #GHOST_FILE} em {@code cDir} a partir da máquina abstrata raiz.
     */
    public static void write(Path cDir, Element abstractMachineEl, Map<String, String> gluing)
            throws IOException {
        if (cDir == null || abstractMachineEl == null) return;
        String machineName = abstractMachineEl.getAttribute("name");
        if (machineName == null || machineName.isBlank()) return;

        BxmlTranslateContext ctx = BxmlTranslateContext.forMachine(abstractMachineEl, gluing);
        List<String> abstractVarNames = listAbstractVariableNames(abstractMachineEl);
        if (abstractVarNames.isEmpty()) {
            Files.deleteIfExists(cDir.resolve(GHOST_FILE));
            return;
        }
        Set<String> abstractSet = new LinkedHashSet<>(abstractVarNames);
        Map<String, String> varTypes =
                BxmlMachineVariables.inferVariableLogicTypes(abstractMachineEl, ctx);

        StringBuilder sb = new StringBuilder();
        sb.append("/* ghost_operations.ci — operações ghost não puras (gerado) — ")
                .append(machineName)
                .append(" */\n\n");

        for (String v : abstractVarNames) {
            sb.append("//@ ghost int ghost_").append(v).append(";\n");
        }
        sb.append("\n");

        sb.append(formatDummyAxiomatic(abstractVarNames, varTypes));

        List<GhostOp> ops = new ArrayList<>();
        List<String> initEnsures =
                collectGhostEnsuresFromInit(abstractMachineEl, abstractSet, ctx);
        Set<String> initAssigned = new LinkedHashSet<>();
        collectAssignedAbstractVarsInInit(abstractMachineEl, abstractSet, initAssigned);
        if (!initAssigned.isEmpty() && !initEnsures.isEmpty()) {
            ops.add(new GhostOp("initialisation", List.of(), initAssigned, initEnsures));
        }

        Element operationsEl = firstChildElement(abstractMachineEl, "Operations");
        if (operationsEl != null) {
            NodeList opNodes = operationsEl.getChildNodes();
            for (int i = 0; i < opNodes.getLength(); i++) {
                Node n = opNodes.item(i);
                if (n.getNodeType() != Node.ELEMENT_NODE) continue;
                Element op = (Element) n;
                if (!"Operation".equals(op.getLocalName())) continue;
                String opName = op.getAttribute("name");
                if (opName == null || opName.isBlank()) continue;

                Element body = firstChildElement(op, "Body");
                if (body == null) continue;
                Set<String> assigned = new LinkedHashSet<>();
                collectAssignedAbstractVarsInBody(body, abstractSet, assigned);
                if (assigned.isEmpty()) continue;

                List<String> ensures = new ArrayList<>();
                BxmlInitialisationTranslator.appendEnsuresFromBody(body, ensures, ctx);
                List<String> ghostEnsures = new ArrayList<>();
                for (String e : ensures) {
                    ghostEnsures.add(toGhostEnsure(e, abstractSet));
                }
                if (ghostEnsures.isEmpty()) continue;

                List<Param> params = listInputParameters(op);
                ops.add(
                        new GhostOp(
                                sanitizeGhostFunctionName(opName),
                                params,
                                assigned,
                                ghostEnsures));
            }
        }

        for (GhostOp go : ops) {
            sb.append(go.format());
        }

        Files.createDirectories(cDir);
        Files.writeString(cDir.resolve(GHOST_FILE), sb.toString(), StandardCharsets.UTF_8);
    }

    private static String formatDummyAxiomatic(List<String> abstractVarNames, Map<String, String> varTypes) {
        StringBuilder sb = new StringBuilder();
        sb.append("/*@\n");
        sb.append("    axiomatic dummy_ghost {\n\n");
        sb.append("        type DSet<A>;\n\n");
        for (String v : abstractVarNames) {
            String t = varTypes.getOrDefault(v, "Set<integer>");
            String d = toDSetType(t);
            sb.append("        logic ").append(d).append(" dummy_").append(v).append(";\n\n");
        }
        sb.append("        logic DSet<integer> empty(Set<integer> witness);\n");
        sb.append("        predicate belongs(integer a, integer b);\n");
        sb.append("        predicate equals(DSet<integer> a, DSet<integer> b);\n");
        sb.append("        logic DSet<integer> set_union(DSet<integer> a, DSet<integer> b);\n");
        sb.append("        logic integer card(DSet<integer> a);\n");
        sb.append("        logic DSet<integer> singleton(integer a);\n");
        sb.append("        predicate is_finite(DSet<integer> a);\n\n");
        sb.append("    }\n*/\n");
        return sb.toString();
    }

    private static String toDSetType(String setOrLogicType) {
        if (setOrLogicType != null && setOrLogicType.startsWith("Set<") && setOrLogicType.endsWith(">")) {
            return "DSet" + setOrLogicType.substring(3);
        }
        return "DSet<integer>";
    }

    /** Traduz {@code equals(ss, …)} para o universo {@code dummy_*} com {@code \\old} no RHS. */
    private static String toGhostEnsure(String translatedEnsure, Set<String> abstractVars) {
        String s = translatedEnsure.trim();
        if (!s.startsWith("equals(")) {
            return rewriteAbstractIdsWithOld(s, abstractVars);
        }
        int open = s.indexOf('(');
        int comma = findTopLevelComma(s, open + 1);
        if (comma < 0) return rewriteAbstractIdsWithOld(s, abstractVars);
        String lhs = s.substring(open + 1, comma).trim();
        String rhs = s.substring(comma + 1, s.length() - 1).trim();
        String lhsGhost = lhs;
        for (String v : abstractVars) {
            if (lhs.equals(v)) {
                lhsGhost = "dummy_" + v;
                break;
            }
        }
        String rhsGhost = rewriteAbstractIdsWithOld(rhs, abstractVars);
        return "equals(" + lhsGhost + ", " + rhsGhost + ")";
    }

    private static int findTopLevelComma(String s, int start) {
        int depth = 0;
        for (int i = start; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '(') depth++;
            else if (c == ')') {
                if (depth == 0) return -1;
                depth--;
            } else if (c == ',' && depth == 0) {
                return i;
            }
        }
        return -1;
    }

    private static String rewriteAbstractIdsWithOld(String expr, Set<String> abstractVars) {
        List<String> names = new ArrayList<>(abstractVars);
        names.sort((a, b) -> Integer.compare(b.length(), a.length()));
        String out = expr;
        for (String v : names) {
            Pattern pat = Pattern.compile("\\b" + Pattern.quote(v) + "\\b");
            Matcher m = pat.matcher(out);
            StringBuffer sb = new StringBuffer();
            while (m.find()) {
                m.appendReplacement(sb, Matcher.quoteReplacement("\\old(dummy_" + v + ")"));
            }
            m.appendTail(sb);
            out = sb.toString();
        }
        return out;
    }

    private static List<String> collectGhostEnsuresFromInit(
            Element machineEl, Set<String> abstractSet, BxmlTranslateContext ctx) {
        List<String> out = new ArrayList<>();
        Element init = firstChildElement(machineEl, "Initialisation");
        if (init == null) return out;
        Element sub = firstSubChild(init);
        collectGhostEnsuresFromSubstitution(sub, abstractSet, ctx, out);
        return out;
    }

    private static void collectGhostEnsuresFromSubstitution(
            Element sub, Set<String> abstractSet, BxmlTranslateContext ctx, List<String> out) {
        if (sub == null) return;
        String ln = sub.getLocalName();
        switch (ln) {
            case "Assignement_Sub" -> {
                Element vars = firstChildElement(sub, "Variables");
                Element vals = firstChildElement(sub, "Values");
                if (vars == null || vals == null) return;
                List<Element> lhs = directExpChildren(vars);
                List<Element> rhs = directExpChildren(vals);
                int n = Math.min(lhs.size(), rhs.size());
                for (int i = 0; i < n; i++) {
                    String g =
                            ghostEnsureFromAssignment(lhs.get(i), rhs.get(i), abstractSet, ctx);
                    if (g != null && !g.isBlank()) out.add(g);
                }
            }
            case "Nary_Sub" -> {
                if (";".equals(sub.getAttribute("op"))) {
                    NodeList children = sub.getChildNodes();
                    for (int i = 0; i < children.getLength(); i++) {
                        Node n = children.item(i);
                        if (n.getNodeType() != Node.ELEMENT_NODE) continue;
                        Element ch = (Element) n;
                        if ("Attr".equals(ch.getLocalName())) continue;
                        collectGhostEnsuresFromSubstitution(ch, abstractSet, ctx, out);
                    }
                } else {
                    collectGhostEnsuresFromSubstitution(firstSubChild(sub), abstractSet, ctx, out);
                }
            }
            case "Bloc_Sub" -> collectGhostEnsuresFromSubstitution(firstSubChild(sub), abstractSet, ctx, out);
            default -> { }
        }
    }

    private static String ghostEnsureFromAssignment(
            Element lhsExp, Element rhsExp, Set<String> abstractSet, BxmlTranslateContext ctx) {
        if (!"Id".equals(lhsExp.getLocalName())) return null;
        String v = lhsExp.getAttribute("value");
        if (!abstractSet.contains(v)) return null;
        if ("EmptySet".equals(rhsExp.getLocalName())) {
            return "equals(dummy_" + v + ", empty(\\old(dummy_" + v + ")))";
        }
        String rhs = BxmlExpressionToAcsl.translate(rhsExp, ctx);
        String rhsGhost = rewriteAbstractIdsWithOld(rhs, abstractSet);
        return "equals(dummy_" + v + ", " + rhsGhost + ")";
    }

    private static void collectAssignedAbstractVarsInInit(
            Element machineEl, Set<String> abstractSet, Set<String> out) {
        Element init = firstChildElement(machineEl, "Initialisation");
        if (init == null) return;
        collectAssignedInSubstitution(firstSubChild(init), abstractSet, out);
    }

    private static void collectAssignedAbstractVarsInBody(
            Element body, Set<String> abstractSet, Set<String> out) {
        Element sub = firstSubChild(body);
        collectAssignedInSubstitution(sub, abstractSet, out);
    }

    private static void collectAssignedInSubstitution(Element sub, Set<String> abstractSet, Set<String> out) {
        if (sub == null) return;
        String ln = sub.getLocalName();
        switch (ln) {
            case "Assignement_Sub" -> {
                Element vars = firstChildElement(sub, "Variables");
                if (vars == null) return;
                for (Element id : directExpChildren(vars)) {
                    if (!"Id".equals(id.getLocalName())) continue;
                    String v = id.getAttribute("value");
                    if (abstractSet.contains(v)) out.add(v);
                }
            }
            case "Nary_Sub" -> {
                if (";".equals(sub.getAttribute("op"))) {
                    NodeList children = sub.getChildNodes();
                    for (int i = 0; i < children.getLength(); i++) {
                        Node n = children.item(i);
                        if (n.getNodeType() != Node.ELEMENT_NODE) continue;
                        Element ch = (Element) n;
                        if ("Attr".equals(ch.getLocalName())) continue;
                        collectAssignedInSubstitution(ch, abstractSet, out);
                    }
                } else {
                    collectAssignedInSubstitution(firstSubChild(sub), abstractSet, out);
                }
            }
            case "Bloc_Sub" -> collectAssignedInSubstitution(firstSubChild(sub), abstractSet, out);
            default -> { }
        }
    }

    private static List<String> listAbstractVariableNames(Element machineEl) {
        List<String> out = new ArrayList<>();
        Element block = firstChildElement(machineEl, "Abstract_Variables");
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

    private static List<Param> listInputParameters(Element operation) {
        List<Param> out = new ArrayList<>();
        Element in = firstChildElement(operation, "Input_Parameters");
        if (in == null) return out;
        NodeList ch = in.getChildNodes();
        for (int i = 0; i < ch.getLength(); i++) {
            Node n = ch.item(i);
            if (n.getNodeType() != Node.ELEMENT_NODE) continue;
            Element e = (Element) n;
            if ("Attr".equals(e.getLocalName())) continue;
            if ("Id".equals(e.getLocalName())) {
                String name = e.getAttribute("value");
                if (name == null || name.isBlank()) continue;
                String cType = cTypeFromTypref(e.getAttribute("typref"));
                out.add(new Param(cType, sanitizeCIdent(name)));
            }
        }
        return out;
    }

    private static String cTypeFromTypref(String typrefAttr) {
        if (typrefAttr == null || typrefAttr.isBlank()) return "int";
        return "int";
    }

    private static String sanitizeCIdent(String name) {
        return name.replace('-', '_');
    }

    private static String sanitizeGhostFunctionName(String opName) {
        if (opName == null || opName.isBlank()) return "operation";
        if ("INITIALISATION".equalsIgnoreCase(opName)) return "initialisation";
        return opName.replace('-', '_').toLowerCase();
    }

    private static List<Element> directExpChildren(Element parent) {
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

    private static Element firstSubChild(Element parent) {
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

    private record Param(String type, String name) {}

    private record GhostOp(
            String cName, List<Param> params, Set<String> assignsAbstract, List<String> ghostEnsures) {

        String format() {
            StringBuilder sb = new StringBuilder();
            sb.append("/*@ ghost\n");
            sb.append("  /@ assigns ");
            List<String> ghosts = new ArrayList<>();
            for (String v : assignsAbstract) {
                ghosts.add("ghost_" + v);
            }
            sb.append(String.join(", ", ghosts)).append(";\n");
            for (String e : ghostEnsures) {
                sb.append("   @ ensures ").append(e).append(";\n");
            }
            sb.append("   @/\n");
            sb.append("  void ").append(cName).append("(").append(formatParams()).append(");\n");
            sb.append("*/\n\n");
            return sb.toString();
        }

        private String formatParams() {
            if (params.isEmpty()) return "void";
            List<String> parts = new ArrayList<>();
            for (Param p : params) {
                parts.add(p.type + " " + p.name);
            }
            return String.join(", ", parts);
        }
    }
}
