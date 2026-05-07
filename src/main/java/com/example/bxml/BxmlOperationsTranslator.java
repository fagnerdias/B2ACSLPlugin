package com.example.bxml;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * Traduz {@code <Operations>} (lista de {@code <Operation name="...">}) para especificações ACSL.
 * As operações referenciam funções da {@code ACSL_Lib} via {@link BxmlExpressionToAcsl}.
 *
 * <p>Uma {@code Operation} contém opcionalmente {@code Input_Parameters}, {@code Output_Parameters}
 * (cada saída vira {@code assigns *nome}, {@code requires \valid(nome)} e o correspondente {@code ensures} usa {@code *nome} à esquerda de {@code ==}),
 * {@code Precondition} (predicado) e obrigatoriamente {@code Body} (substituição).
 *
 * @see <a href="https://www.atelierb.eu/wp-content/uploads/2023/10/bxml-1.0.html">BXML 1.0 — Operation</a>
 */
public final class BxmlOperationsTranslator {

    private BxmlOperationsTranslator() {}

    /**
     * Lista esboços de contratos por operação (nome B → nome função {@code Machine__op}).
     *
     * @param invariantPredicateNames nomes ACSL dos predicados de {@code <Invariant>} (ex.: {@code M_invariant}),
     *        repetidos em cada operação como {@code requires} e {@code ensures}
     */
    public static List<OperationAcsl> translateOperations(
            Element machineEl,
            BxmlTranslateContext ctx,
            List<String> invariantPredicateNames) {
        return translateOperations(
                machineEl, ctx, invariantPredicateNames, null, null, null, null, null);
    }

    /**
     * @param abstractVariableNames se não nulo, operações que atribuem a estas variáveis recebem
     *        {@code at 1: assert ghost__<op>(p1, …);} no contrato (parâmetros de entrada) e, nesse caso,
     *        removem-se os {@code ensures} vindos do {@code Body} — mantêm-se só os que repetem o
     *        invariante (nomes em {@code invariantPredicateNames}).
     */
    public static List<OperationAcsl> translateOperations(
            Element machineEl,
            BxmlTranslateContext ctx,
            List<String> invariantPredicateNames,
            Set<String> abstractVariableNames) {
        return translateOperations(
                machineEl, ctx, invariantPredicateNames, abstractVariableNames, null, null, null, null);
    }

    /**
     * @param libScanGhostOperationBodies se não nulo, acrescenta os {@code ensures} do {@code Body}
     *        das operações ghost (antes de os retirar do contrato) para deteção de includes da
     *        ACSL_Lib ({@link com.example.AcslLibIncludes}).
     */
    public static List<OperationAcsl> translateOperations(
            Element machineEl,
            BxmlTranslateContext ctx,
            List<String> invariantPredicateNames,
            Set<String> abstractVariableNames,
            StringBuilder libScanGhostOperationBodies) {
        return translateOperations(
                machineEl,
                ctx,
                invariantPredicateNames,
                abstractVariableNames,
                libScanGhostOperationBodies,
                null,
                null,
                null);
    }

    /**
     * @param rootAbstractMachineName nome da máquina abstrata raiz do {@code .acsl} (ex. {@code SetTest})
     * @param mergedRefinementChain máquinas fundidas na ordem do pipeline (refinamentos + implementações)
     * @param gluing substituições de invariantes
     */
    public static List<OperationAcsl> translateOperations(
            Element machineEl,
            BxmlTranslateContext ctx,
            List<String> invariantPredicateNames,
            Set<String> abstractVariableNames,
            StringBuilder libScanGhostOperationBodies,
            String rootAbstractMachineName,
            List<Element> mergedRefinementChain,
            Map<String, String> gluing) {
        String machineName = machineEl.getAttribute("name");
        List<OperationAcsl> out = new ArrayList<>();

        NodeList ops = machineEl.getElementsByTagNameNS("*", "Operations");
        if (ops.getLength() == 0) return out;
        Element operationsEl = (Element) ops.item(0);
        NodeList children = operationsEl.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node n = children.item(i);
            if (n.getNodeType() != Node.ELEMENT_NODE) continue;
            Element child = (Element) n;
            if (!"Operation".equals(child.getLocalName())) continue;

            String opName = child.getAttribute("name");
            String funcName = machineName + "__" + sanitize(opName);

            List<String> requires = new ArrayList<>();
            for (String inv : invariantPredicateNames) {
                requires.add(inv);
            }
            Element pre = firstChildElement(child, "Precondition");
            if (pre != null) {
                requires.addAll(BxmlPredicateToAcsl.translatePredicateBlock(pre, ctx));
                rewriteRequiresForArrayBackedFunctionParams(requires, child, ctx);
            }

            List<String> outputParams = parseOutputParameterNames(child);
            for (String p : outputParams) {
                requires.add("\\valid(" + p + ")");
            }

            List<String> ensures = new ArrayList<>();
            Element body = firstChildElement(child, "Body");
            if (body != null) {
                BxmlInitialisationTranslator.appendEnsuresFromBody(body, ensures, ctx);
            }
            applyStarPrefixToEnsures(ensures, outputParams);
            List<String> bodyEnsuresOnly = new ArrayList<>(ensures);
            for (String inv : invariantPredicateNames) {
                ensures.add(inv);
            }

            List<String> inputParamNames = parseInputParameterNames(child);
            String ghostSlug = "";
            boolean assignsAbstract =
                    abstractVariableNames != null
                            && !abstractVariableNames.isEmpty()
                            && GhostOperationsCiGenerator.operationAssignsAbstract(
                                    child, abstractVariableNames);
            boolean bodyHasAnySub = GhostOperationsCiGenerator.operationBodyHasAnySub(child);
            if (assignsAbstract || bodyHasAnySub) {
                ghostSlug = GhostOperationsCiGenerator.ghostOperationSlug(opName);
            }
            List<String> ghostBehaviorArgs = new ArrayList<>(inputParamNames);
            if (bodyHasAnySub) {
                ghostBehaviorArgs.addAll(GhostOperationsCiGenerator.listOutputParameterNames(child));
            }
            if (libScanGhostOperationBodies != null && !ghostSlug.isBlank()) {
                for (String line : bodyEnsuresOnly) {
                    if (line != null && !line.isBlank()) {
                        libScanGhostOperationBodies.append(line).append('\n');
                    }
                }
            }
            if (assignsAbstract
                    && invariantPredicateNames != null
                    && !invariantPredicateNames.isEmpty()) {
                Set<String> invariantOnly = new HashSet<>(invariantPredicateNames);
                ensures.removeIf(e -> !invariantOnly.contains(e));
            }
            List<String> dummyGhostEnsureVars =
                    assignsAbstract
                            ? GhostOperationsCiGenerator.listAbstractVariableNames(machineEl)
                            : List.of();

            List<String> connectionConcreteAssigns = List.of();
            if (assignsAbstract
                    && rootAbstractMachineName != null
                    && !rootAbstractMachineName.isBlank()
                    && mergedRefinementChain != null
                    && !mergedRefinementChain.isEmpty()) {
                Set<String> assignedAbs =
                        GhostOperationsCiGenerator.assignedAbstractVariablesInOperation(
                                child, abstractVariableNames);
                if (!assignedAbs.isEmpty()) {
                    connectionConcreteAssigns =
                            BxmlMachineVariables.listConcreteAssignTargetsForAbstractMutation(
                                    rootAbstractMachineName,
                                    machineEl,
                                    mergedRefinementChain,
                                    assignedAbs,
                                    gluing,
                                    ctx);
                }
            }

            out.add(
                    new OperationAcsl(
                            funcName,
                            requires,
                            ensures,
                            outputParams,
                            ghostSlug,
                            ghostBehaviorArgs,
                            dummyGhostEnsureVars,
                            connectionConcreteAssigns));
        }
        return out;
    }

    /** Nomes dos {@code Id} em {@code Input_Parameters} (ordem do BXML), identificadores C-sanitizados. */
    private static List<String> parseInputParameterNames(Element operation) {
        Element inEl = firstChildElement(operation, "Input_Parameters");
        if (inEl == null) return List.of();
        List<String> names = new ArrayList<>();
        NodeList ch = inEl.getChildNodes();
        for (int i = 0; i < ch.getLength(); i++) {
            Node n = ch.item(i);
            if (n.getNodeType() != Node.ELEMENT_NODE) continue;
            Element e = (Element) n;
            if ("Attr".equals(e.getLocalName())) continue;
            if ("Id".equals(e.getLocalName())) {
                String v = e.getAttribute("value");
                if (v != null && !v.isBlank()) names.add(sanitize(v.trim()));
            }
        }
        return names;
    }

    /** Nomes dos {@code Id} em {@code Output_Parameters} (ordem do BXML). */
    private static List<String> parseOutputParameterNames(Element operation) {
        Element outEl = firstChildElement(operation, "Output_Parameters");
        if (outEl == null) return List.of();
        List<String> names = new ArrayList<>();
        NodeList ch = outEl.getChildNodes();
        for (int i = 0; i < ch.getLength(); i++) {
            Node n = ch.item(i);
            if (n.getNodeType() != Node.ELEMENT_NODE) continue;
            Element e = (Element) n;
            if ("Attr".equals(e.getLocalName())) continue;
            if ("Id".equals(e.getLocalName())) {
                String v = e.getAttribute("value");
                if (!v.isBlank()) names.add(v);
            }
        }
        return names;
    }

    /**
     * Para cada {@code ensures} da forma {@code v == E}, se {@code v} for parâmetro de saída, gera
     * {@code *v == E}.
     */
    private static void applyStarPrefixToEnsures(List<String> ensures, List<String> outputParams) {
        if (outputParams.isEmpty()) return;
        Set<String> out = new HashSet<>(outputParams);
        for (int i = 0; i < ensures.size(); i++) {
            String s = ensures.get(i);
            int eq = s.indexOf(" == ");
            if (eq < 0) continue;
            String lhs = s.substring(0, eq).trim();
            if (out.contains(lhs) && !lhs.startsWith("*")) {
                ensures.set(i, "*" + lhs + " == " + s.substring(eq + 4));
            }
        }
    }

    private static String sanitize(String name) {
        return name.replace('-', '_');
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

    /**
     * Em operações cuja entrada concreta é array/pointer representando função total em B
     * ({@code xx : A --> B}), reescreve chamadas de contrato que esperam {@code Function_int_int}
     * para usar {@code array_to_function(xx, len)}.
     */
    private static void rewriteRequiresForArrayBackedFunctionParams(
            List<String> requires, Element operation, BxmlTranslateContext ctx) {
        if (requires == null || requires.isEmpty() || operation == null || ctx == null) {
            return;
        }
        Map<String, String> lensByParam = inferArrayFunctionParamLengths(operation, ctx);
        if (lensByParam.isEmpty()) {
            return;
        }
        for (int i = 0; i < requires.size(); i++) {
            String req = requires.get(i);
            if (req == null || req.isBlank()) {
                continue;
            }
            String rewritten = req;
            for (Map.Entry<String, String> e : lensByParam.entrySet()) {
                String p = e.getKey();
                String len = e.getValue();
                rewritten =
                        rewritten.replaceAll(
                                "\\bis_total_function\\s*\\(\\s*"
                                        + Pattern.quote(p)
                                        + "\\b",
                                "is_total_function(array_to_function(" + p + ", " + len + ")");
            }
            requires.set(i, rewritten);
        }
    }

    private static Map<String, String> inferArrayFunctionParamLengths(
            Element operation, BxmlTranslateContext ctx) {
        Map<String, String> out = new LinkedHashMap<>();
        Element pre = firstChildElement(operation, "Precondition");
        if (pre == null) {
            return out;
        }
        collectArrayFunctionParamLengths(pre, ctx, out);
        return out;
    }

    private static void collectArrayFunctionParamLengths(
            Element pred, BxmlTranslateContext ctx, Map<String, String> out) {
        if (pred == null || ctx == null) {
            return;
        }
        String ln = pred.getLocalName();
        if ("Exp_Comparison".equals(ln)) {
            String op = pred.getAttribute("op");
            if (":".equals(op == null ? "" : op.trim())) {
                Element[] pair = BxmlExpressionToAcsl.twoDirectExpChildren(pred);
                if (pair[0] != null
                        && "Id".equals(pair[0].getLocalName())
                        && pair[1] != null
                        && BxmlExpressionToAcsl.isFunctionArrowType(pair[1])) {
                    String p = pair[0].getAttribute("value");
                    String len = arrayDomainCardinalityAcsl(pair[1], ctx);
                    if (p != null && !p.isBlank() && len != null && !len.isBlank()) {
                        out.put(p.trim(), len);
                    }
                }
            }
        }
        NodeList nl = pred.getChildNodes();
        for (int i = 0; i < nl.getLength(); i++) {
            Node n = nl.item(i);
            if (n.getNodeType() != Node.ELEMENT_NODE) continue;
            Element ch = (Element) n;
            if ("Attr".equals(ch.getLocalName())) continue;
            collectArrayFunctionParamLengths(ch, ctx, out);
        }
    }

    private static String arrayDomainCardinalityAcsl(Element arrowEl, BxmlTranslateContext ctx) {
        Element[] domRng = BxmlExpressionToAcsl.twoDirectExpChildren(arrowEl);
        if (domRng[0] == null) {
            return null;
        }
        Element domain = domRng[0];
        if (!BxmlExpressionToAcsl.isIntervalBinaryExp(domain)) {
            return null;
        }
        Element[] lr = BxmlExpressionToAcsl.twoDirectExpChildren(domain);
        if (lr[0] == null || lr[1] == null) {
            return null;
        }
        String low = BxmlExpressionToAcsl.translate(lr[0], ctx).trim();
        String high = BxmlExpressionToAcsl.translate(lr[1], ctx).trim();
        if (low.isBlank() || high.isBlank()) {
            return null;
        }
        return "(" + high + " - (" + low + ") + 1)";
    }

    public record OperationAcsl(
            String functionName,
            List<String> requires,
            List<String> ensures,
            /** Parâmetros de saída B (sem {@code *}); viram {@code assigns *nome}. */
            List<String> outputParameters,
            /** Vazio se a operação for pura face às variáveis abstratas; senão slug para {@code ghost__<slug>(…)}. */
            String ghostBehaviorSlug,
            /** Nomes dos parâmetros de entrada para o {@code assert ghost__…}; ordem do BXML. */
            List<String> ghostBehaviorInputNames,
            /**
             * Sufixos {@code v} para {@code ensures dummy_ghost_<v>;} (operações não puras ghost); vazio se pura.
             */
            List<String> dummyGhostEnsureVarNames,
            /**
             * {@code assigns Raiz__v} para variáveis concretas ligadas por refinamento quando a operação
             * ghost altera a abstrata (omitidas se já redundantes).
             */
            List<String> connectionConcreteAssigns) {

        public OperationAcsl {
            dummyGhostEnsureVarNames =
                    dummyGhostEnsureVarNames == null ? List.of() : List.copyOf(dummyGhostEnsureVarNames);
            connectionConcreteAssigns =
                    connectionConcreteAssigns == null ? List.of() : List.copyOf(connectionConcreteAssigns);
        }

        /** Mesmo esquema que {@link com.example.bxml.BxmlInitialisationTranslator.InitialisationAcsl#toContractText()}. */
        public String toContractSketch() {
            StringBuilder sb = new StringBuilder();
            sb.append("function ").append(functionName).append(":\n");
            sb.append("contract:    \n");
            for (String r : requires) {
                sb.append("    requires  ").append(r).append(";\n");
            }
            for (String e : ensures) {
                sb.append("    ensures  ").append(e).append(";\n");
            }
            for (String v : dummyGhostEnsureVarNames) {
                sb.append("    ensures  dummy_ghost_").append(v).append(";\n");
            }
            for (String p : outputParameters) {
                sb.append("    assigns *").append(p).append(";\n");
            }
            LinkedHashSet<String> connectionEmitted = new LinkedHashSet<>();
            for (String ca : connectionConcreteAssigns) {
                if (ca == null || ca.isBlank()) {
                    continue;
                }
                if (!connectionEmitted.add(ca)) {
                    continue;
                }
                String clause = "assigns " + ca + ";";
                if (sb.indexOf(clause) >= 0) {
                    continue;
                }
                sb.append("    assigns ").append(ca).append(";\n");
            }
            if (ghostBehaviorSlug != null && !ghostBehaviorSlug.isBlank()) {
                sb.append("    at 1: assert ghost__").append(ghostBehaviorSlug);
                if (ghostBehaviorInputNames != null && !ghostBehaviorInputNames.isEmpty()) {
                    sb.append("(").append(String.join(", ", ghostBehaviorInputNames)).append(")");
                }
                sb.append(";\n");
            }
            return sb.toString();
        }
    }
}
