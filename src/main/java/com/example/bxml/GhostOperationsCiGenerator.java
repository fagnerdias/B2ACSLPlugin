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
 * com tipo paralelo ao da especificação ({@code DSet<…>} para {@code Set<…>}, {@code \\list<…>} para
 * sequências), declarações {@code logic DSet<integer> set_comprehension_k} para índices usados nos
 * {@code ensures} ghost (e até ao máximo do registo de compreensões da máquina), e contratos ghost
 * para inicialização e operações que atribuem a variáveis abstratas.
 *
 * <p>Para atribuições do tipo sequência definida por restrição de domínio sobre um array (função parcial em
 * B), o {@code ensures} ghost compara relações com {@code dummy_list_to_function(dummy_<seq>)} e
 * {@code array_to_function(<param>, <tamanho>)} — o tamanho infere-se do intervalo do domínio na pré-condição
 * ({@code low..high}); constantes concretas da máquina aparecem na axiomatica como {@code dummy_<c>} e nas
 * expressões de comprimento.
 */
public final class GhostOperationsCiGenerator {

    private static final String GHOST_FILE = "ghost_operations.ci";

    private GhostOperationsCiGenerator() {}

    public static Path targetPath(Path cDir) {
        return cDir.resolve(GHOST_FILE);
    }

    /** Slug C/ACSL da operação (ex. {@code Add} → {@code add}, {@code INITIALISATION} → {@code initialisation}). */
    public static String ghostOperationSlug(String bxmlOperationName) {
        return sanitizeGhostFunctionName(bxmlOperationName);
    }

    public static boolean initialisationAssignsAbstract(
            Element abstractMachineEl, Set<String> abstractVariableNames) {
        if (abstractMachineEl == null
                || abstractVariableNames == null
                || abstractVariableNames.isEmpty()) {
            return false;
        }
        Set<String> assigned = new LinkedHashSet<>();
        collectAssignedAbstractVarsInInit(abstractMachineEl, abstractVariableNames, assigned);
        return !assigned.isEmpty();
    }

    public static boolean operationAssignsAbstract(
            Element operation, Set<String> abstractVariableNames) {
        if (operation == null
                || abstractVariableNames == null
                || abstractVariableNames.isEmpty()) {
            return false;
        }
        return !assignedAbstractVariablesInOperation(operation, abstractVariableNames).isEmpty();
    }

    /** Variáveis abstratas atribuídas no {@code Body} da operação (lado esquerdo de {@code :=}). */
    public static Set<String> assignedAbstractVariablesInOperation(
            Element operation, Set<String> abstractVariableNames) {
        Set<String> out = new LinkedHashSet<>();
        if (operation == null
                || abstractVariableNames == null
                || abstractVariableNames.isEmpty()) {
            return out;
        }
        Element body = firstChildElement(operation, "Body");
        if (body == null) return out;
        collectAssignedAbstractVarsInBody(body, abstractVariableNames, out);
        return out;
    }

    /**
     * Bloco {@code axiomatic Nome_ghost_patterns} com {@code logic integer dummy_ghost_<v>} e predicados
     * {@code ghost_<op>} para inicialização e operações não puras.
     */
    public static String formatGhostPatternsAxiomaticBlock(
            Element abstractMachineEl, String machineNamePrefix) {
        if (abstractMachineEl == null
                || machineNamePrefix == null
                || machineNamePrefix.isBlank()) {
            return "";
        }
        List<String> vars = listAbstractVariableNames(abstractMachineEl);
        if (vars.isEmpty()) return "";

        Set<String> abstractSet = new LinkedHashSet<>(vars);
        StringBuilder sb = new StringBuilder();
        sb.append("axiomatic ").append(machineNamePrefix).append("_ghost_patterns {\n\n");
        for (String v : vars) {
            sb.append("    logic int dummy_ghost_").append(v).append(";\n\n");
        }
        if (initialisationAssignsAbstract(abstractMachineEl, abstractSet)) {
            sb.append("    predicate ghost__initialisation;\n\n");
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
                if (!operationAssignsAbstract(op, abstractSet)) continue;
                String slug = ghostOperationSlug(opName);
                sb.append("    predicate ghost__").append(slug);
                List<Param> params = listInputParameters(op);
                if (params.isEmpty()) {
                    sb.append(";\n\n");
                } else {
                    sb.append("(");
                    List<String> parts = new ArrayList<>();
                    for (Param p : params) {
                        parts.add(formatCParameterDecl(p.type(), p.name()));
                    }
                    sb.append(String.join(", ", parts)).append(");\n\n");
                }
            }
        }
        sb.append("}\n");
        return sb.toString();
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
        Set<String> concreteConstants = concreteConstantNames(abstractMachineEl);

        StringBuilder sb = new StringBuilder();
        sb.append("/* ghost_operations.ci — operações ghost não puras (gerado) — ")
                .append(machineName)
                .append(" */\n\n");

        for (String v : abstractVarNames) {
            sb.append("//@ ghost int ghost_").append(v).append(";\n");
        }
        sb.append("\n");

        List<GhostOp> ghostOps =
                buildGhostOperations(abstractMachineEl, abstractSet, ctx, varTypes, concreteConstants);
        List<String> allGhostEnsureLines = new ArrayList<>();
        for (GhostOp go : ghostOps) {
            allGhostEnsureLines.addAll(go.ghostEnsures());
        }
        int maxSetComp =
                Math.max(
                        ctx.comprehensions().maxComprehensionIndex(),
                        maxSetComprehensionIndexInGhostText(allGhostEnsureLines));

        sb.append(formatDummyAxiomatic(abstractVarNames, varTypes, maxSetComp, abstractMachineEl, ctx));

        for (GhostOp go : ghostOps) {
            sb.append(go.format());
        }

        Files.createDirectories(cDir);
        Files.writeString(cDir.resolve(GHOST_FILE), sb.toString(), StandardCharsets.UTF_8);
    }

    private static List<GhostOp> buildGhostOperations(
            Element abstractMachineEl,
            Set<String> abstractSet,
            BxmlTranslateContext ctx,
            Map<String, String> varTypes,
            Set<String> concreteConstants) {
        List<GhostOp> ops = new ArrayList<>();
        List<String> initEnsures = collectGhostEnsuresFromInit(abstractMachineEl, abstractSet, ctx);
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

                List<Param> params = listInputParameters(op);
                List<String> ensures = new ArrayList<>();
                BxmlInitialisationTranslator.appendEnsuresFromBody(body, ensures, ctx);
                List<String> ghostEnsures = new ArrayList<>();
                for (String e : ensures) {
                    String ge = toGhostEnsure(e, abstractSet);
                    ge = rewriteGhostEnsureForListDomainRestriction(
                            ge, op, varTypes, params, ctx, concreteConstants);
                    ghostEnsures.add(ge);
                }
                if (ghostEnsures.isEmpty()) continue;

                ops.add(
                        new GhostOp(
                                sanitizeGhostFunctionName(opName),
                                params,
                                assigned,
                                ghostEnsures));
            }
        }
        return ops;
    }

    private static final Pattern SET_COMPREHENSION_INDEX =
            Pattern.compile("dummy_set_comprehension_(\\d+)");

    /** ACSL sem prefixo ghost; no {@code dummy_ghost} usam-se {@link #dummySetComprehensionRef}. */
    private static final Pattern SET_COMPREHENSION_INDEX_UNPREFIXED =
            Pattern.compile("^set_comprehension_(\\d+)$");

    /** Para contar índices em ensures completos (subexpressão). */
    private static final Pattern SET_COMPREHENSION_INDEX_IN_TEXT =
            Pattern.compile("set_comprehension_(\\d+)");

    /**
     * Após {@link #toGhostEnsure}: {@code \\old(dummy_seq)==domain_restriction(rel, S)} vindo de
     * {@code myseq == domain_restriction(...)}.
     */
    private static final Pattern GHOST_ENSURE_OLD_EQ_DOMAIN_RESTRICTION =
            Pattern.compile(
                    "^\\\\old\\(dummy_(\\w+)\\)\\s*==\\s*domain_restriction\\((\\w+)\\s*,\\s*([^)]+)\\)\\s*$");

    /** Idem no formato {@code equals(dummy_seq, domain_restriction(rel, S))}. */
    private static final Pattern GHOST_ENSURE_EQUALS_DOMAIN_RESTRICTION =
            Pattern.compile(
                    "^equals\\(dummy_(\\w+)\\s*,\\s*domain_restriction\\((\\w+)\\s*,\\s*([^)]+)\\)\\)\\s*$");

    /**
     * Variante equivalente após {@link #toGhostEnsure}: {@code domain_restriction(rel, S) ==
     * dummy_list_to_function(\\old(dummy_seq))}.
     */
    private static final Pattern GHOST_ENSURE_DOMAIN_RESTRICTION_EQ_LIST_OLD =
            Pattern.compile(
                    "^domain_restriction\\((\\w+)\\s*,\\s*([^)]+)\\)\\s*==\\s*(?:dummy_)?list_to_function\\(\\\\old\\(dummy_(\\w+)\\)\\)\\s*$");

    /**
     * Variante simétrica: {@code dummy_list_to_function(\\old(dummy_seq)) == domain_restriction(rel, S)}.
     */
    private static final Pattern GHOST_ENSURE_LIST_OLD_EQ_DOMAIN_RESTRICTION =
            Pattern.compile(
                    "^(?:dummy_)?list_to_function\\(\\\\old\\(dummy_(\\w+)\\)\\)\\s*==\\s*domain_restriction\\((\\w+)\\s*,\\s*([^)]+)\\)\\s*$");

    /**
     * Caso legado de listas: o tradutor pode produzir {@code lhs == dummy_list_to_function((list_expr))}.
     * Para variáveis de tipo lista, a comparação deve permanecer lista-vs-lista.
     */
    private static final Pattern GHOST_ENSURE_EQ_LIST_TO_FUNCTION_OF_LIST_EXPR =
            Pattern.compile("^(.+?)\\s*==\\s*(?:dummy_)?list_to_function\\(\\((.+)\\)\\)\\s*$");

    private static int maxSetComprehensionIndexInGhostText(Iterable<String> lines) {
        int m = 0;
        if (lines == null) {
            return 0;
        }
        for (String line : lines) {
            if (line == null || line.isBlank()) {
                continue;
            }
            Matcher mm = SET_COMPREHENSION_INDEX.matcher(line);
            while (mm.find()) {
                try {
                    m = Math.max(m, Integer.parseInt(mm.group(1)));
                } catch (NumberFormatException ignored) {
                    // skip
                }
            }
            mm = SET_COMPREHENSION_INDEX_IN_TEXT.matcher(line);
            while (mm.find()) {
                try {
                    m = Math.max(m, Integer.parseInt(mm.group(1)));
                } catch (NumberFormatException ignored) {
                    // skip
                }
            }
        }
        return m;
    }

    /**
     * Segundo argumento de {@code domain_restriction(..., S)} no axiomatic ghost: nomes de set
     * comprehension vêm do tradutor como {@code set_comprehension_k}; aqui alinham-se a
     * {@code dummy_set_comprehension_k} declarado em {@link #formatDummyAxiomatic}.
     */
    private static String dummySetComprehensionRef(String domainRestrictionSecondArg) {
        if (domainRestrictionSecondArg == null) {
            return "";
        }
        String s = domainRestrictionSecondArg.trim();
        Matcher um = SET_COMPREHENSION_INDEX_UNPREFIXED.matcher(s);
        if (um.matches()) {
            return "dummy_set_comprehension_" + um.group(1);
        }
        return s;
    }

    private static String formatDummyAxiomatic(
            List<String> abstractVarNames,
            Map<String, String> varTypes,
            int maxSetComprehensionIndex,
            Element machineEl,
            BxmlTranslateContext ctx) {
        StringBuilder sb = new StringBuilder();
        sb.append("/*@\n");
        sb.append("    axiomatic dummy_ghost {\n\n");
        sb.append("        type DSet<A>;\n\n");
        sb.append("        type DRelation<A, B>;\n\n");
        for (String v : abstractVarNames) {
            String t = varTypes.getOrDefault(v, "Set<integer>");
            String d = dummyAxiomaticLogicType(t);
            sb.append("        logic ").append(d).append(" dummy_").append(v).append(";\n\n");
        }
        appendDummyConcreteConstantDeclarations(sb, machineEl, ctx);
        if (maxSetComprehensionIndex > 0) {
            for (int k = 1; k <= maxSetComprehensionIndex; k++) {
                sb.append("        logic DSet<integer> dummy_set_comprehension_").append(k).append(";\n\n");
            }
        }
        sb.append("        logic A empty<A>(A a);\n");
        sb.append("        predicate belongs<A, B>(A a, B b);\n");
        sb.append("        predicate equals<A,B>(A a, B b);\n");
        sb.append("        logic A set_union<A, B>(A a, B b);\n");
        sb.append("        logic integer card<A>(A a);\n");
        sb.append("        logic DSet<integer> singleton<A>(A a);\n");
        sb.append("        predicate is_finite<A>(A a);\n");
        sb.append(
                "        logic DRelation<A, B> domain_restriction<A, B>(DRelation<A, B> r, DSet<A> S);\n\n");
        sb.append("        logic DRelation<integer, A> dummy_list_to_function<A>(\\list<A> l);\n\n");
        sb.append("        logic DRelation<integer, integer> dummy_array_to_function(int *x, integer length);\n");
        sb.append("    }\n*/\n");
        return sb.toString();
    }

    /** Nomes em {@code Concrete_Constants} (ordem de declaração no BXML). */
    private static Set<String> concreteConstantNames(Element machineEl) {
        Set<String> out = new LinkedHashSet<>();
        Element block = firstChildElement(machineEl, "Concrete_Constants");
        if (block == null) {
            return out;
        }
        NodeList ch = block.getChildNodes();
        for (int i = 0; i < ch.getLength(); i++) {
            Node n = ch.item(i);
            if (n.getNodeType() != Node.ELEMENT_NODE) continue;
            Element e = (Element) n;
            if ("Attr".equals(e.getLocalName())) continue;
            if (!"Id".equals(e.getLocalName())) continue;
            String name = e.getAttribute("value");
            if (name != null && !name.isBlank()) {
                out.add(name.trim());
            }
        }
        return out;
    }

    /**
     * Declarações {@code logic τ dummy_<c>} para cada constante concreta (espelho ghost no universo
     * {@code dummy_ghost}).
     */
    private static void appendDummyConcreteConstantDeclarations(
            StringBuilder sb, Element machineEl, BxmlTranslateContext ctx) {
        if (machineEl == null || ctx == null) {
            return;
        }
        Element block = firstChildElement(machineEl, "Concrete_Constants");
        if (block == null) {
            return;
        }
        NodeList ch = block.getChildNodes();
        for (int i = 0; i < ch.getLength(); i++) {
            Node n = ch.item(i);
            if (n.getNodeType() != Node.ELEMENT_NODE) continue;
            Element e = (Element) n;
            if ("Attr".equals(e.getLocalName())) continue;
            if (!"Id".equals(e.getLocalName())) continue;
            String name = e.getAttribute("value");
            if (name == null || name.isBlank()) continue;
            String logicType = "integer";
            String tr = e.getAttribute("typref");
            if (!tr.isBlank()) {
                try {
                    int typref = Integer.parseInt(tr.trim());
                    String t = ctx.types().acslVariableLogicTypeFromTypref(typref);
                    if (t != null && !t.isBlank()) {
                        logicType = t;
                    }
                } catch (NumberFormatException ignored) {
                }
            }
            sb.append("        logic ").append(logicType).append(" dummy_").append(name.trim()).append(";\n\n");
        }
    }

    /** Substitui referências a constantes concretas por {@code dummy_<nome>} numa expressão ACSL já escrita. */
    private static String ghostDummyConcreteRefs(String acslExpr, Set<String> concreteConstantNames) {
        if (acslExpr == null || concreteConstantNames == null || concreteConstantNames.isEmpty()) {
            return acslExpr;
        }
        String out = acslExpr;
        List<String> names = new ArrayList<>(concreteConstantNames);
        names.sort((a, b) -> Integer.compare(b.length(), a.length()));
        for (String c : names) {
            Pattern pat = Pattern.compile("\\b" + Pattern.quote(c) + "\\b");
            Matcher m = pat.matcher(out);
            StringBuffer sb = new StringBuffer();
            while (m.find()) {
                m.appendReplacement(sb, Matcher.quoteReplacement("dummy_" + c));
            }
            m.appendTail(sb);
            out = sb.toString();
        }
        return out;
    }

    /**
     * Tipo ACSL da variável lógica {@code dummy_<v>} no axiomatica comentado: espelha {@code Set<…>}
     * como {@code DSet<…>}, {@code \\list<…>} inalterado, {@code Relation_*} inalterado.
     */
    private static String dummyAxiomaticLogicType(String inferred) {
        if (inferred == null || inferred.isBlank()) {
            return "DSet<integer>";
        }
        String t = inferred.trim();
        if (t.startsWith("\\list")) {
            return t;
        }
        if (t.startsWith("Set<") && t.endsWith(">")) {
            return "DSet" + t.substring(3);
        }
        if (t.startsWith("Relation_")) {
            return t;
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
        if (lhsGhost.startsWith("dummy_")
                && ("\\Nil".equals(rhsGhost) || "empty_seq".equals(rhsGhost))) {
            return lhsGhost + " == \\Nil";
        }
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

    /**
     * Sequência atribuída por restrição de domínio sobre array (função parcial em B): compara como
     * relações via {@code list_to_function} / {@code array_to_function}.
     */
    private static String rewriteGhostEnsureForListDomainRestriction(
            String ensure,
            Element operation,
            Map<String, String> varTypes,
            List<Param> params,
            BxmlTranslateContext ctx,
            Set<String> concreteConstantNames) {
        if (ensure == null || operation == null || varTypes == null || ctx == null) {
            return ensure;
        }
        String t = ensure.trim();
        Matcher mOld = GHOST_ENSURE_OLD_EQ_DOMAIN_RESTRICTION.matcher(t);
        if (mOld.matches()) {
            String r =
                    tryRewriteListDomainRestrictionEquality(
                            mOld.group(1),
                            mOld.group(2),
                            mOld.group(3).trim(),
                            varTypes,
                            params,
                            operation,
                            ctx,
                            concreteConstantNames);
            if (r != null) {
                return r;
            }
        }
        Matcher mEq = GHOST_ENSURE_EQUALS_DOMAIN_RESTRICTION.matcher(t);
        if (mEq.matches()) {
            String r =
                    tryRewriteListDomainRestrictionEquality(
                            mEq.group(1),
                            mEq.group(2),
                            mEq.group(3).trim(),
                            varTypes,
                            params,
                            operation,
                            ctx,
                            concreteConstantNames);
            if (r != null) {
                return r;
            }
        }
        Matcher mDrEqListOld = GHOST_ENSURE_DOMAIN_RESTRICTION_EQ_LIST_OLD.matcher(t);
        if (mDrEqListOld.matches()) {
            String r =
                    tryRewriteListDomainRestrictionEquality(
                            mDrEqListOld.group(3),
                            mDrEqListOld.group(1),
                            mDrEqListOld.group(2).trim(),
                            varTypes,
                            params,
                            operation,
                            ctx,
                            concreteConstantNames);
            if (r != null) {
                return r;
            }
        }
        Matcher mListOldEqDr = GHOST_ENSURE_LIST_OLD_EQ_DOMAIN_RESTRICTION.matcher(t);
        if (mListOldEqDr.matches()) {
            String r =
                    tryRewriteListDomainRestrictionEquality(
                            mListOldEqDr.group(1),
                            mListOldEqDr.group(2),
                            mListOldEqDr.group(3).trim(),
                            varTypes,
                            params,
                            operation,
                            ctx,
                            concreteConstantNames);
            if (r != null) {
                return r;
            }
        }
        String listEq =
                tryRewriteListEqualityWithoutListToFunction(
                        t, varTypes, concreteConstantNames);
        if (listEq != null) {
            return listEq;
        }
        return ensure;
    }

    private static String tryRewriteListEqualityWithoutListToFunction(
            String ensure,
            Map<String, String> varTypes,
            Set<String> concreteConstantNames) {
        if (ensure == null || varTypes == null) {
            return null;
        }
        Matcher m = GHOST_ENSURE_EQ_LIST_TO_FUNCTION_OF_LIST_EXPR.matcher(ensure);
        if (!m.matches()) {
            return null;
        }
        String lhs = m.group(1).trim();
        String rhsListExpr = m.group(2).trim();
        String seqVar = extractOldDummyListVarFromExpr(lhs);
        if (seqVar == null) {
            return null;
        }
        String listType = varTypes.get(seqVar);
        if (listType == null || !listType.startsWith("\\list")) {
            return null;
        }
        return lhs + " == " + ghostDummyConcreteRefs(rhsListExpr, concreteConstantNames);
    }

    private static String extractOldDummyListVarFromExpr(String expr) {
        if (expr == null) {
            return null;
        }
        Matcher m = Pattern.compile("^\\\\old\\(dummy_(\\w+)\\)$").matcher(expr.trim());
        if (m.matches()) {
            return m.group(1);
        }
        return null;
    }

    private static String tryRewriteListDomainRestrictionEquality(
            String seqVar,
            String relationParam,
            String domainRestrictionSecondArg,
            Map<String, String> varTypes,
            List<Param> params,
            Element operation,
            BxmlTranslateContext ctx,
            Set<String> concreteConstantNames) {
        String listType = varTypes.get(seqVar);
        if (listType == null || !listType.startsWith("\\list")) {
            return null;
        }
        if (!isPointerGhostParam(params, relationParam)) {
            return null;
        }
        String len =
                inferPartialFunctionDomainLengthAcsl(
                        operation, relationParam, ctx, concreteConstantNames);
        if (len == null || len.isBlank()) {
            return null;
        }
        return "domain_restriction(dummy_array_to_function("
                + relationParam
                + ", "
                + len
                + "), "
                + "dummy_"
                + domainRestrictionSecondArg
                + ") == dummy_list_to_function(\\old(dummy_"
                + seqVar
                + "))";
    }

    private static boolean isPointerGhostParam(List<Param> params, String name) {
        if (params == null || name == null || name.isBlank()) {
            return false;
        }
        for (Param p : params) {
            if (name.equals(p.name())) {
                String tp = p.type();
                return tp != null && tp.endsWith("*");
            }
        }
        return false;
    }

    /**
     * Expressão ACSL para o segundo argumento de {@code array_to_function}: obtida do domínio da função
     * parcial em B ({@code low..high}), tipicamente o nome da constante do limite superior (ex.
     * {@code maximum} para {@code 0..maximum}).
     */
    private static String inferPartialFunctionDomainLengthAcsl(
            Element operation,
            String paramName,
            BxmlTranslateContext ctx,
            Set<String> concreteConstantNames) {
        Element domain = partialFunctionDomainFromPrecondition(operation, paramName);
        return arrayLengthAcslFromDomain(domain, ctx, concreteConstantNames);
    }

    private static Element partialFunctionDomainFromPrecondition(Element operation, String paramName) {
        Element pre = firstChildElement(operation, "Precondition");
        if (pre == null) {
            return null;
        }
        return partialFunctionDomainFromPreconditionInPred(pre, paramName);
    }

    private static Element partialFunctionDomainFromPreconditionInPred(Element pred, String paramName) {
        if (pred == null) {
            return null;
        }
        String ln = pred.getLocalName();
        if ("Precondition".equals(ln)) {
            NodeList nl = pred.getChildNodes();
            for (int i = 0; i < nl.getLength(); i++) {
                Node n = nl.item(i);
                if (n.getNodeType() != Node.ELEMENT_NODE) continue;
                Element ch = (Element) n;
                if ("Attr".equals(ch.getLocalName())) continue;
                Element d = partialFunctionDomainFromPreconditionInPred(ch, paramName);
                if (d != null) {
                    return d;
                }
            }
            return null;
        }
        if ("Exp_Comparison".equals(ln)) {
            String op = normalizeColonLikeOp(pred.getAttribute("op"));
            if (":".equals(op)) {
                Element[] pair = BxmlExpressionToAcsl.twoDirectExpChildren(pred);
                if (pair[0] != null
                        && "Id".equals(pair[0].getLocalName())
                        && paramName.equals(pair[0].getAttribute("value").trim())
                        && pair[1] != null
                        && BxmlExpressionToAcsl.isFunctionArrowType(pair[1])) {
                    Element[] arrow = BxmlExpressionToAcsl.twoDirectExpChildren(pair[1]);
                    if (arrow[0] != null) {
                        return arrow[0];
                    }
                }
            }
            return null;
        }
        if ("Nary_Pred".equals(ln)) {
            NodeList nl = pred.getChildNodes();
            for (int i = 0; i < nl.getLength(); i++) {
                Node n = nl.item(i);
                if (n.getNodeType() != Node.ELEMENT_NODE) continue;
                Element ch = (Element) n;
                if ("Attr".equals(ch.getLocalName())) continue;
                Element d = partialFunctionDomainFromPreconditionInPred(ch, paramName);
                if (d != null) {
                    return d;
                }
            }
            return null;
        }
        if ("Unary_Pred".equals(ln)) {
            Element inner = firstPredChildElement(pred);
            return partialFunctionDomainFromPreconditionInPred(inner, paramName);
        }
        if ("Binary_Pred".equals(ln)) {
            Element[] pair = twoDirectPredChildren(pred);
            if (pair[0] != null) {
                Element d = partialFunctionDomainFromPreconditionInPred(pair[0], paramName);
                if (d != null) {
                    return d;
                }
            }
            if (pair[1] != null) {
                return partialFunctionDomainFromPreconditionInPred(pair[1], paramName);
            }
        }
        return null;
    }

    private static String arrayLengthAcslFromDomain(
            Element domain, BxmlTranslateContext ctx, Set<String> concreteConstantNames) {
        if (domain == null || ctx == null) {
            return null;
        }
        if (BxmlExpressionToAcsl.isIntervalBinaryExp(domain)) {
            Element[] lr = BxmlExpressionToAcsl.twoDirectExpChildren(domain);
            if (lr[0] == null || lr[1] == null) {
                return null;
            }
            String low = BxmlExpressionToAcsl.translate(lr[0], ctx).trim();
            String high = BxmlExpressionToAcsl.translate(lr[1], ctx).trim();
            String raw;
            if ("0".equals(low)) {
                raw = high;
            } else {
                raw = "(" + high + " - (" + low + ") + 1)";
            }
            return ghostDummyConcreteRefs(raw, concreteConstantNames);
        }
        return null;
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
        if ("EmptySeq".equals(rhsExp.getLocalName()) && isListLikeVariable(v, ctx)) {
            return "dummy_" + v + " == \\Nil";
        }
        String rhs = BxmlExpressionToAcsl.translate(rhsExp, ctx);
        String rhsGhost = rewriteAbstractIdsWithOld(rhs, abstractSet);
        return "equals(dummy_" + v + ", " + rhsGhost + ")";
    }

    private static boolean isListLikeVariable(String v, BxmlTranslateContext ctx) {
        String t = ctx.variableLogicTypes().get(v);
        return t != null && t.startsWith("\\list");
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

    /** Nomes em {@code Abstract_Variables} (ordem do BXML). */
    public static List<String> listAbstractVariableNames(Element machineEl) {
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
                String cType = ghostParamTypeFromPrecondition(operation, name.trim());
                if (cType == null || cType.isBlank()) {
                    cType = cGhostParamTypeFromTypref(operation, e);
                }
                out.add(new Param(cType, sanitizeCIdent(name)));
            }
        }
        return out;
    }

    /**
     * Tipo C do parâmetro ghost a partir do {@code Precondition} (ex. {@code ee : NAT} → {@code int};
     * {@code xx : S --> T} → {@code int *}; conjunto de relações {@code POW(A*B)} → {@code int *}).
     */
    private static String ghostParamTypeFromPrecondition(Element operation, String paramName) {
        Element pre = firstChildElement(operation, "Precondition");
        if (pre == null) {
            return null;
        }
        return findMembershipTypeForParam(pre, paramName);
    }

    private static String findMembershipTypeForParam(Element pred, String paramName) {
        if (pred == null) {
            return null;
        }
        String ln = pred.getLocalName();
        if ("Precondition".equals(ln)) {
            NodeList nl = pred.getChildNodes();
            for (int i = 0; i < nl.getLength(); i++) {
                Node n = nl.item(i);
                if (n.getNodeType() != Node.ELEMENT_NODE) continue;
                Element ch = (Element) n;
                if ("Attr".equals(ch.getLocalName())) continue;
                String t = findMembershipTypeForParam(ch, paramName);
                if (t != null && !t.isBlank()) {
                    return t;
                }
            }
            return null;
        }
        if ("Exp_Comparison".equals(ln)) {
            String op = normalizeColonLikeOp(pred.getAttribute("op"));
            if (":".equals(op)) {
                Element[] pair = BxmlExpressionToAcsl.twoDirectExpChildren(pred);
                if (pair[0] != null
                        && "Id".equals(pair[0].getLocalName())
                        && paramName.equals(pair[0].getAttribute("value").trim())
                        && pair[1] != null) {
                    return cGhostParamTypeFromMembershipRhs(pair[1]);
                }
            }
            return null;
        }
        if ("Nary_Pred".equals(ln)) {
            NodeList nl = pred.getChildNodes();
            for (int i = 0; i < nl.getLength(); i++) {
                Node n = nl.item(i);
                if (n.getNodeType() != Node.ELEMENT_NODE) continue;
                Element ch = (Element) n;
                if ("Attr".equals(ch.getLocalName())) continue;
                String t = findMembershipTypeForParam(ch, paramName);
                if (t != null && !t.isBlank()) {
                    return t;
                }
            }
            return null;
        }
        if ("Unary_Pred".equals(ln)) {
            Element inner = firstPredChildElement(pred);
            return findMembershipTypeForParam(inner, paramName);
        }
        if ("Binary_Pred".equals(ln)) {
            Element[] pair = twoDirectPredChildren(pred);
            if (pair[0] != null) {
                String t = findMembershipTypeForParam(pair[0], paramName);
                if (t != null && !t.isBlank()) {
                    return t;
                }
            }
            if (pair[1] != null) {
                return findMembershipTypeForParam(pair[1], paramName);
            }
        }
        return null;
    }

    private static String normalizeColonLikeOp(String raw) {
        if (raw == null) {
            return "";
        }
        String o = raw.trim();
        if (":".equals(o)) {
            return ":";
        }
        return o;
    }

    private static Element firstPredChildElement(Element parent) {
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

    private static Element[] twoDirectPredChildren(Element parent) {
        Element[] out = new Element[2];
        int k = 0;
        NodeList nl = parent.getChildNodes();
        for (int i = 0; i < nl.getLength(); i++) {
            Node n = nl.item(i);
            if (n.getNodeType() != Node.ELEMENT_NODE) continue;
            Element e = (Element) n;
            if ("Attr".equals(e.getLocalName())) continue;
            out[k++] = e;
            if (k == 2) {
                break;
            }
        }
        return out;
    }

    /**
     * Lado direito de {@code v : RHS} no precondition → tipo C do parâmetro (sem tipos lógicos ACSL
     * como {@code Relation_*}).
     */
    private static String cGhostParamTypeFromMembershipRhs(Element rhs) {
        if (rhs == null) {
            return null;
        }
        if ("Id".equals(rhs.getLocalName())) {
            String v = rhs.getAttribute("value");
            if (v == null) {
                v = "";
            }
            return switch (v.trim()) {
                case "NAT", "INTEGER", "INT" -> "int";
                case "BOOL" -> "int";
                default -> null;
            };
        }
        if (BxmlExpressionToAcsl.isFunctionArrowType(rhs)) {
            return "int *";
        }
        if ("Unary_Exp".equals(rhs.getLocalName()) && "POW".equals(rhs.getAttribute("op"))) {
            Element inner = firstNonAttrElementChild(rhs);
            if (inner != null
                    && "Binary_Exp".equals(inner.getLocalName())
                    && "*".equals(trimOp(inner.getAttribute("op")))) {
                return "int *";
            }
        }
        return null;
    }

    private static String trimOp(String op) {
        return op == null ? "" : op.trim();
    }

    private static Element firstNonAttrElementChild(Element parent) {
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

    /** Fallback a partir de {@code typref} + {@code TypeInfos}: relações → {@code int *}. */
    private static String cGhostParamTypeFromTypref(Element operation, Element paramId) {
        Element machine = findAncestorMachine(operation);
        if (machine == null) {
            return "int";
        }
        String tr = paramId.getAttribute("typref");
        if (tr == null || tr.isBlank()) {
            return "int";
        }
        try {
            int id = Integer.parseInt(tr.trim());
            BxmlTypeRegistry types = BxmlTypeRegistry.fromMachine(machine);
            String acsl = types.acslVariableLogicTypeFromTypref(id);
            if (acsl != null && acsl.startsWith("Relation_")) {
                return "int *";
            }
        } catch (NumberFormatException ignored) {
            // fall through
        }
        return "int";
    }

    private static Element findAncestorMachine(Element el) {
        Node n = el;
        while (n != null) {
            if (n.getNodeType() == Node.ELEMENT_NODE) {
                Element e = (Element) n;
                if ("Machine".equals(e.getLocalName())) {
                    return e;
                }
            }
            n = n.getParentNode();
        }
        return null;
    }

    /**
     * Declaração de parâmetro C: {@code int *xx} (sem espaço entre {@code *} e o identificador);
     * escalares mantêm espaço ({@code int ee}).
     */
    private static String formatCParameterDecl(String type, String name) {
        if (type == null || type.isBlank()) {
            return name;
        }
        String t = type.trim();
        if (t.endsWith("*")) {
            return t + name;
        }
        return t + " " + name;
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
                parts.add(formatCParameterDecl(p.type(), p.name()));
            }
            return String.join(", ", parts);
        }
    }
}
