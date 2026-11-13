package com.example.bxml;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * Formata o bloco {@code axiomatic NomeMaquina_variables { logic … }} de uma máquina B, com tipos
 * inferidos preferencialmente do invariante e recurso ao {@code typref} de
 * {@code Abstract_Variables}/{@code Concrete_Variables}. Extraído de
 * {@code BxmlMachineVariables} (o maior WMC do projeto) por extract-class puro: nenhuma linha de
 * lógica mudou, só o arquivo em que vive.
 */
public final class MachineAxiomaticBlockFormatter {

    private MachineAxiomaticBlockFormatter() {}

    /**
     * Bloco {@code axiomatic NomeMaquina_variables { logic … }} ou vazio se não houver variáveis
     * declaradas ({@code name} do {@code <Machine>}).
     */
    public static String formatAxiomaticBlock(Element machineEl, BxmlTranslateContext ctx) {
        return formatAxiomaticBlock(machineEl, ctx, null, null, Map.of(), (Set<String>) null);
    }

    /**
     * Bloco de variáveis da raiz com {@code reads dummy_ghost_<v>} nas variáveis abstratas listadas.
     */
    public static String formatAxiomaticBlockWithGhostDummyReads(
            Element machineEl, BxmlTranslateContext ctx, Set<String> abstractVarNamesForGhostRead) {
        return formatAxiomaticBlockWithGhostDummyReads(
                machineEl, ctx, null, abstractVarNamesForGhostRead);
    }

    /**
     * Como {@link #formatAxiomaticBlockWithGhostDummyReads(Element, BxmlTranslateContext, Set)} com
     * ligação opcional {@code logic v = Raiz__v;} quando {@code rootAbstractForConcreteLink} não é
     * nulo (implementação sem variáveis próprias).
     */
    public static String formatAxiomaticBlockWithGhostDummyReads(
            Element machineEl,
            BxmlTranslateContext ctx,
            String rootAbstractForConcreteLink,
            Set<String> abstractVarNamesForGhostRead) {
        return formatAxiomaticBlock(
                machineEl,
                ctx,
                rootAbstractForConcreteLink,
                null,
                Map.of(),
                abstractVarNamesForGhostRead,
                Map.of());
    }

    /**
     * Como {@link #formatAxiomaticBlockWithGhostDummyReads(Element, BxmlTranslateContext, String, Set)}
     * com sobreposições de RHS por variável (ex.: quando o estado concreto vem de máquina importada
     * via linking invariant e o nome C real é diferente de {@code Raiz__v}).
     */
    public static String formatAxiomaticBlockWithGhostDummyReads(
            Element machineEl,
            BxmlTranslateContext ctx,
            String rootAbstractForConcreteLink,
            Set<String> abstractVarNamesForGhostRead,
            Map<String, String> varRhsOverrides) {
        return formatAxiomaticBlockWithGhostDummyReads(
                machineEl, ctx, rootAbstractForConcreteLink, abstractVarNamesForGhostRead,
                varRhsOverrides, null);
    }

    /**
     * Como {@link #formatAxiomaticBlockWithGhostDummyReads(Element, BxmlTranslateContext, String,
     * Set, Map)}, mas com {@code bxmlDirectory} para resolver a cardinalidade de conjuntos nomeados
     * (deferred sets como {@code GOODS}) valorados em máquinas VISTAS (SEES) — ver
     * {@link #BxmlMachineVariables.lookupSetCardinalityFromValues(String, Element, BxmlTranslateContext, Path)}.
     */
    public static String formatAxiomaticBlockWithGhostDummyReads(
            Element machineEl,
            BxmlTranslateContext ctx,
            String rootAbstractForConcreteLink,
            Set<String> abstractVarNamesForGhostRead,
            Map<String, String> varRhsOverrides,
            Path bxmlDirectory) {
        return formatAxiomaticBlockWithGhostDummyReads(
                machineEl, ctx, rootAbstractForConcreteLink, abstractVarNamesForGhostRead,
                varRhsOverrides, bxmlDirectory, Set.of());
    }

    /**
     * Como acima, mas omitindo por completo (sem {@code reads dummy_ghost_v} NEM declaração bare)
     * variáveis em {@code excludeVariableNames} — usado para as variáveis que
     * {@link #collapsedIntoImplementationVariableNames} identifica como devendo colapsar numa única
     * definição array-backed na camada da implementação (sem gerar uma camada ghost paralela para
     * elas): a declaração delas fica inteiramente a cargo do bloco da máquina fundida
     * correspondente, com o nome ORIGINAL (sem colisão a resolver, já que esta camada não as declara).
     */
    public static String formatAxiomaticBlockWithGhostDummyReads(
            Element machineEl,
            BxmlTranslateContext ctx,
            String rootAbstractForConcreteLink,
            Set<String> abstractVarNamesForGhostRead,
            Map<String, String> varRhsOverrides,
            Path bxmlDirectory,
            Set<String> excludeVariableNames) {
        return formatAxiomaticBlock(
                machineEl,
                ctx,
                rootAbstractForConcreteLink,
                null,
                Map.of(),
                abstractVarNamesForGhostRead,
                varRhsOverrides == null ? Map.of() : varRhsOverrides,
                bxmlDirectory,
                excludeVariableNames);
    }

    /**
     * Como {@link #formatAxiomaticBlock(Element, BxmlTranslateContext)}, mas para máquinas fundidas com
     * {@code type="implementation"}: cada variável concreta fica {@code logic T v = Raiz__v;} alinhada ao
     * contrato C ({@link #listImplementationAssignTargets}).
     *
     * @param rootAbstractMachineName nome da máquina abstrata raiz do ficheiro {@code .acsl} (ex. {@code SetTest});
     *        ignorado se a máquina não for implementação
     */
    public static String formatAxiomaticBlock(
            Element machineEl, BxmlTranslateContext ctx, String rootAbstractMachineName) {
        return formatAxiomaticBlock(
                machineEl, ctx, rootAbstractMachineName, null, Map.of(), (Set<String>) null);
    }

    /**
     * Variáveis fundidas: implementação com {@code Raiz__v}, ou refinamento com {@code = return_valid_v(abs)}
     * quando {@code refinementParent} não é nulo ({@link BxmlConnectionAcsl}).
     */
    public static String formatAxiomaticBlock(
            Element machineEl,
            BxmlTranslateContext ctx,
            String rootAbstractMachineName,
            Element refinementParent,
            Map<String, String> gluing) {
        return formatAxiomaticBlock(
                machineEl, ctx, rootAbstractMachineName, refinementParent, gluing, null, null);
    }

    /**
     * Como {@link #formatAxiomaticBlock(Element, BxmlTranslateContext, String, Element, Map)}, mas
     * com {@code bxmlDirectory} para resolver a cardinalidade de conjuntos nomeados (deferred sets
     * como {@code GOODS}) valorados em máquinas VISTAS (SEES) — ver
     * {@link #BxmlMachineVariables.lookupSetCardinalityFromValues(String, Element, BxmlTranslateContext, Path)}.
     */
    public static String formatAxiomaticBlock(
            Element machineEl,
            BxmlTranslateContext ctx,
            String rootAbstractMachineName,
            Element refinementParent,
            Map<String, String> gluing,
            Path bxmlDirectory) {
        return formatAxiomaticBlock(
                machineEl, ctx, rootAbstractMachineName, refinementParent, gluing, null,
                Map.of(), bxmlDirectory);
    }

    private static String formatAxiomaticBlock(
            Element machineEl,
            BxmlTranslateContext ctx,
            String rootAbstractMachineName,
            Element refinementParent,
            Map<String, String> gluing,
            Set<String> ghostDummyReadsForAbstractVars) {
        return formatAxiomaticBlock(
                machineEl, ctx, rootAbstractMachineName, refinementParent,
                gluing, ghostDummyReadsForAbstractVars, Map.of());
    }

    private static String formatAxiomaticBlock(
            Element machineEl,
            BxmlTranslateContext ctx,
            String rootAbstractMachineName,
            Element refinementParent,
            Map<String, String> gluing,
            Set<String> ghostDummyReadsForAbstractVars,
            Map<String, String> varRhsOverrides) {
        return formatAxiomaticBlock(
                machineEl, ctx, rootAbstractMachineName, refinementParent, gluing,
                ghostDummyReadsForAbstractVars, varRhsOverrides, null);
    }

    private static String formatAxiomaticBlock(
            Element machineEl,
            BxmlTranslateContext ctx,
            String rootAbstractMachineName,
            Element refinementParent,
            Map<String, String> gluing,
            Set<String> ghostDummyReadsForAbstractVars,
            Map<String, String> varRhsOverrides,
            Path bxmlDirectory) {
        return formatAxiomaticBlock(
                machineEl, ctx, rootAbstractMachineName, refinementParent, gluing,
                ghostDummyReadsForAbstractVars, varRhsOverrides, bxmlDirectory, Set.of());
    }

    private static String formatAxiomaticBlock(
            Element machineEl,
            BxmlTranslateContext ctx,
            String rootAbstractMachineName,
            Element refinementParent,
            Map<String, String> gluing,
            Set<String> ghostDummyReadsForAbstractVars,
            Map<String, String> varRhsOverrides,
            Path bxmlDirectory,
            Set<String> excludeVariableNames) {
        String machineName = machineEl.getAttribute("name");
        if (machineName == null || machineName.isBlank()) return "";
        boolean linkConcrete =
                rootAbstractMachineName != null
                        && !rootAbstractMachineName.isBlank()
                        && (BxmlMachineVariables.isImplementationMachine(machineEl) || BxmlMachineVariables.isAbstractionMachine(machineEl));
        boolean linkRefinement =
                refinementParent != null && BxmlMachineVariables.isRefinementMachine(machineEl);
        Map<String, String> gl =
                gluing == null ? Map.of() : gluing;
        Map<String, String> types = BxmlMachineVariables.inferVariableLogicTypes(machineEl, ctx);
        if (excludeVariableNames != null && !excludeVariableNames.isEmpty()) {
            types = new LinkedHashMap<>(types);
            types.keySet().removeAll(excludeVariableNames);
        }
        return formatVariablesBlock(
                machineName,
                types,
                linkConcrete ? rootAbstractMachineName.trim() : null,
                linkRefinement,
                refinementParent,
                machineEl,
                gl,
                ghostDummyReadsForAbstractVars,
                ctx,
                varRhsOverrides,
                bxmlDirectory);
    }

    private static String formatVariablesBlock(
            String blockName,
            Map<String, String> types,
            String rootAbstractForImplRhs,
            boolean refinementWithParent,
            Element refinementParent,
            Element refinementChild,
            Map<String, String> gluing,
            Set<String> ghostDummyReadsForAbstractVars,
            BxmlTranslateContext ctx,
            Map<String, String> varRhsOverrides,
            Path bxmlDirectory) {
        if (types.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        sb.append("axiomatic ").append(blockName).append("_variables {\n");
        for (Map.Entry<String, String> e : types.entrySet()) {
            String var = e.getKey();
            String logicType = e.getValue();
            // Variável tipada por registo B (ver BxmlTypeRegistry#structTypeElement): sem forma
            // ACSL de "logic struct T v = ...;" com valor agregado (ACSL não suporta literais de
            // struct em termos lógicos), então em vez de UMA declaração emite-se um espelho logic
            // escalar POR-CAMPO ("v__campo"); só suportado no caminho com C real por trás
            // (rootAbstractForImplRhs != null) — o único exercitado até agora (cv_rec, máquina
            // fundida sem gluing entre camadas de abstração).
            if (logicType != null
                    && logicType.startsWith("struct:")
                    && rootAbstractForImplRhs != null
                    && ctx != null) {
                appendStructFieldMirrors(sb, var, logicType, rootAbstractForImplRhs.trim(), ctx);
                continue;
            }
            appendScalarVariableMirror(
                    sb,
                    var,
                    logicType,
                    rootAbstractForImplRhs,
                    refinementWithParent,
                    refinementParent,
                    refinementChild,
                    gluing,
                    ghostDummyReadsForAbstractVars,
                    ctx,
                    varRhsOverrides,
                    bxmlDirectory);
        }
        sb.append("}\n");
        return sb.toString();
    }

    private static void appendScalarVariableMirror(
            StringBuilder sb,
            String var,
            String logicType,
            String rootAbstractForImplRhs,
            boolean refinementWithParent,
            Element refinementParent,
            Element refinementChild,
            Map<String, String> gluing,
            Set<String> ghostDummyReadsForAbstractVars,
            BxmlTranslateContext ctx,
            Map<String, String> varRhsOverrides,
            Path bxmlDirectory) {
        String implRhs = null;
        if (rootAbstractForImplRhs != null
                && refinementChild != null
                && ctx != null
                && BxmlMachineVariables.concreteVariableInvIsTotalArrowFunction(refinementChild, var)) {
            logicType = BxmlMachineVariables.relationLogicTypeToFunctionLogicType(logicType);
            implRhs =
                    BxmlMachineVariables.implementationRhsTotalFunctionFromArray(
                            rootAbstractForImplRhs.trim(), var, refinementChild, ctx, bxmlDirectory);
        }
        sb.append("    logic ").append(logicType).append(" ").append(var);
        // reads dummy_ghost_v só quando a variável não tem definição concreta (= rhs):
        // as duas cláusulas são mutuamente exclusivas em ACSL.
        boolean hasConcreteRhs = rootAbstractForImplRhs != null;
        if (!hasConcreteRhs
                && ghostDummyReadsForAbstractVars != null
                && ghostDummyReadsForAbstractVars.contains(var)) {
            sb.append(" reads dummy_ghost_").append(var);
        }
        if (rootAbstractForImplRhs != null) {
            String rhs;
            String override = varRhsOverrides == null ? null : varRhsOverrides.get(var);
            if (override != null) {
                rhs = override;
            } else if (implRhs != null) {
                rhs = implRhs;
            } else {
                rhs = rootAbstractForImplRhs + "__" + var;
                // "logic boolean v = <scalar _Bool C global>;" faz o Frama-C/WP falhar em
                // TODOS os provers (CVC5/Alt-Ergo/Z3) com "[Why3 Error] Type mismatch between
                // bool and int" — reproduzido isoladamente sem qualquer código B2ACSL: o
                // modelo de memória do WP não converte corretamente o valor lido do chunk C
                // para o tipo lógico "boolean" nessa forma de definição direta. Envolver a
                // mesma leitura num ternário ACSL (\true/\false) contorna o problema (a
                // conversão passa a ocorrer via if-then-else, que o WP já trata bem).
                if ("boolean".equals(logicType)) {
                    rhs = "(" + rhs + " ? \\true : \\false)";
                }
            }
            sb.append(" = ").append(rhs);
        } else if (refinementWithParent) {
            Optional<String> abs =
                    BxmlConnectionAcsl.linkingAbstractVariableName(
                            refinementParent, refinementChild, var, gluing);
            abs.ifPresent(
                    a -> sb.append(" = return_valid_").append(var).append("(").append(a).append(")"));
        }
        sb.append(";\n");
    }

    /**
     * Espelhos {@code logic} por-campo de uma variável tipada por registo B ({@code v : struct(...)})
     * — ver comentário no chamador. {@code structSentinel} é {@code "struct:<typref>"} (ver
     * {@link BxmlMachineVariables#inferVariableLogicTypes}); {@code rootAbstractForImplRhs} já vem
     * "trim()"-ado.
     */
    private static void appendStructFieldMirrors(
            StringBuilder sb,
            String var,
            String structSentinel,
            String rootAbstractForImplRhs,
            BxmlTranslateContext ctx) {
        int typref = Integer.parseInt(structSentinel.substring("struct:".length()));
        Element structEl = ctx.types().structTypeElement(typref);
        if (structEl == null) return;
        NodeList children = structEl.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node n = children.item(i);
            if (n.getNodeType() != Node.ELEMENT_NODE) continue;
            Element item = (Element) n;
            if (!"Record_Item".equals(item.getLocalName())) continue;
            String label = item.getAttribute("label");
            Element fieldTypeEl = BxmlDomUtils.firstNonAttrElementChild(item);
            if (label == null || label.isBlank() || fieldTypeEl == null) continue;
            String fieldLogicType = ctx.types().fieldTypeToAcslLogicType(fieldTypeEl);
            String rhs = rootAbstractForImplRhs + "__" + var + "." + label;
            if ("boolean".equals(fieldLogicType)) {
                rhs = "(" + rhs + " ? \\true : \\false)";
            }
            sb.append("    logic ")
                    .append(fieldLogicType)
                    .append(" ")
                    .append(var)
                    .append("__")
                    .append(label)
                    .append(" = ")
                    .append(rhs)
                    .append(";\n");
        }
    }

}
