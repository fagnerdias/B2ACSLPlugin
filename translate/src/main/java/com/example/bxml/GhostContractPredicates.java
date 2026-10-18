package com.example.bxml;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * Predicados de introspecção BXML para {@code ghost_operations.ci}: se uma operação precisa de
 * contrato ghost (atribui variável abstrata, tem {@code ANY_Sub}), nomes/parâmetros de saída, e
 * formatação do bloco axiomático {@code dummy_ghost_patterns}. Extraído de
 * {@code GhostOperationsCiGenerator} (WMC=746, o maior do projeto) por extract-class puro:
 * nenhuma linha de lógica mudou, só o arquivo em que vive.
 */
public final class GhostContractPredicates {

    private GhostContractPredicates() {}

    /** Slug C/ACSL da operação (ex. {@code Add} → {@code add}, {@code INITIALISATION} → {@code initialisation}). */
    public static String ghostOperationSlug(String bxmlOperationName) {
        return GhostParamTypeResolver.sanitizeGhostFunctionName(bxmlOperationName);
    }

    public static boolean initialisationAssignsAbstract(
            Element abstractMachineEl, Set<String> abstractVariableNames) {
        return !variablesAssignedInInitialisation(abstractMachineEl, abstractVariableNames)
                .isEmpty();
    }

    /**
     * Variáveis da máquina (em {@code candidateNames}) atribuídas na cláusula {@code Initialisation}.
     */
    public static Set<String> variablesAssignedInInitialisation(
            Element machineEl, Set<String> candidateNames) {
        Set<String> out = new LinkedHashSet<>();
        if (machineEl == null || candidateNames == null || candidateNames.isEmpty()) {
            return out;
        }
        GhostOperationsCiGenerator.collectAssignedAbstractVarsInInit(machineEl, candidateNames, out);
        return out;
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

    /**
     * Operação cujo corpo é (ou contém no topo) {@code ANY_Sub}: tem contrato ghost mesmo sem
     * atribuir variável abstrata (ex.: {@code get OUT ee, ii := ANY xx WHERE … THEN ee := xx || ii := …}).
     */
    public static boolean operationBodyHasAnySub(Element operation) {
        if (operation == null) return false;
        Element body = BxmlDomUtils.firstChildElement(operation, "Body");
        return BxmlInitialisationTranslator.findTopLevelAnySub(body) != null;
    }

    /** Tem contrato ghost por mutar variável abstrata ou por usar {@code ANY_Sub}. */
    public static boolean operationNeedsGhostContract(
            Element operation, Set<String> abstractVariableNames) {
        return operationAssignsAbstract(operation, abstractVariableNames)
                || operationBodyHasAnySub(operation);
    }

    /**
     * Nomes dos {@code Output_Parameters} (na ordem do BXML); usado pelos geradores ghost que
     * precisam construir a assinatura {@code void op(... outputs ...)} ou listar argumentos para
     * {@code at return: assert ghost__op(... outputs ...)}.
     */
    public static List<String> listOutputParameterNames(Element operation) {
        List<String> out = new ArrayList<>();
        if (operation == null) return out;
        Element outEl = BxmlDomUtils.firstChildElement(operation, "Output_Parameters");
        if (outEl == null) return out;
        NodeList ch = outEl.getChildNodes();
        for (int i = 0; i < ch.getLength(); i++) {
            Node n = ch.item(i);
            if (n.getNodeType() != Node.ELEMENT_NODE) continue;
            Element e = (Element) n;
            if ("Attr".equals(e.getLocalName())) continue;
            if (!"Id".equals(e.getLocalName())) continue;
            String name = e.getAttribute("value");
            if (name == null || name.isBlank()) continue;
            out.add(GhostParamTypeResolver.sanitizeCIdent(name.trim()));
        }
        return out;
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
        Element body = BxmlDomUtils.firstChildElement(operation, "Body");
        if (body == null) return out;
        GhostOperationsCiGenerator.collectAssignedAbstractVarsInBody(body, abstractVariableNames, out);
        return out;
    }

    /**
     * Bloco {@code axiomatic Nome_ghost_patterns} com {@code logic … dummy_ghost_<v>} e predicados
     * {@code ghost_<op>} para inicialização e operações não puras.
     */
    public static String formatGhostPatternsAxiomaticBlock(
            Element abstractMachineEl, String machineNamePrefix) {
        return formatGhostPatternsAxiomaticBlock(abstractMachineEl, machineNamePrefix, Map.of());
    }

    public static String formatGhostPatternsAxiomaticBlock(
            Element abstractMachineEl,
            String machineNamePrefix,
            Map<String, String> variableLogicTypes) {
        return formatGhostPatternsAxiomaticBlock(
                abstractMachineEl, machineNamePrefix, variableLogicTypes, Set.of());
    }

    /**
     * Como acima, mas excluindo {@code collapsedVariableNames} — variáveis abstratas de mesmo nome
     * que uma {@code Concrete_Variables} da implementação (ver {@code
     * BxmlMachineVariables#collapsedIntoImplementationVariableNames}): sem camada ghost paralela
     * para elas (regra B: nome igual = mesma variável, só concreta), {@code dummy_ghost_<v>} nunca é
     * lido por ninguém, e uma operação cuja ÚNICA variável abstrata atribuída está nesse conjunto não
     * precisa de {@code predicate ghost__<op>} nenhum — sem este filtro, as duas declarações ficavam
     * como lixo morto (nunca referenciado) depois do colapso, embora inofensivas para o WP.
     */
    public static String formatGhostPatternsAxiomaticBlock(
            Element abstractMachineEl,
            String machineNamePrefix,
            Map<String, String> variableLogicTypes,
            Set<String> collapsedVariableNames) {
        if (abstractMachineEl == null
                || machineNamePrefix == null
                || machineNamePrefix.isBlank()) {
            return "";
        }
        Set<String> collapsed = collapsedVariableNames == null ? Set.of() : collapsedVariableNames;
        List<String> vars = new ArrayList<>(GhostOperationsCiGenerator.listAbstractVariableNames(abstractMachineEl));
        vars.removeAll(collapsed);
        if (vars.isEmpty()) return "";

        Map<String, String> types =
                variableLogicTypes == null ? Map.of() : variableLogicTypes;
        Set<String> abstractSet = new LinkedHashSet<>(vars);
        StringBuilder sb = new StringBuilder();
        sb.append("axiomatic ").append(machineNamePrefix).append("_ghost_patterns {\n\n");
        for (String v : vars) {
            sb.append("    logic ")
                    .append(GhostParamTypeResolver.ghostLogicTypeFromInferred(types.get(v)))
                    .append(" dummy_ghost_")
                    .append(v)
                    .append(";\n\n");
        }
        if (initialisationAssignsAbstract(abstractMachineEl, abstractSet)) {
            sb.append("    predicate ghost__")
              .append(machineNamePrefix.toLowerCase())
              .append("__initialisation;\n\n");
        }
        Element operationsEl = BxmlDomUtils.firstChildElement(abstractMachineEl, "Operations");
        if (operationsEl != null) {
            NodeList opNodes = operationsEl.getChildNodes();
            for (int i = 0; i < opNodes.getLength(); i++) {
                Node n = opNodes.item(i);
                if (n.getNodeType() != Node.ELEMENT_NODE) continue;
                Element op = (Element) n;
                if (!"Operation".equals(op.getLocalName())) continue;
                String opName = op.getAttribute("name");
                if (opName == null || opName.isBlank()) continue;
                if (!operationNeedsGhostContract(op, abstractSet)) continue;
                String slug = ghostOperationSlug(opName);
                sb.append("    predicate ghost__").append(slug);
                List<GhostOperationsCiGenerator.Param> params = GhostParamTypeResolver.listInputParameters(op);
                if (operationBodyHasAnySub(op) || operationAssignsAbstract(op, abstractSet)) {
                    params = GhostParamTypeResolver.appendOutputParametersAsPointers(params, op);
                }
                if (params.isEmpty()) {
                    sb.append(";\n\n");
                } else {
                    sb.append("(");
                    List<String> parts = new ArrayList<>();
                    for (GhostOperationsCiGenerator.Param p : params) {
                        parts.add(GhostParamTypeResolver.formatCParameterDecl(p.type(), p.name()));
                    }
                    sb.append(String.join(", ", parts)).append(");\n\n");
                }
            }
        }
        sb.append("}\n");
        return sb.toString();
    }

}
