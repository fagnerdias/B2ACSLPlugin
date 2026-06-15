package com.example.bxml;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * Declara variáveis de máquina B em blocos {@code axiomatic …_variables}, com tipos inferidos
 * preferencialmente a partir do invariante (ex.: {@code numbers <: NAT} → {@code Set<integer>}) e
 * recurso ao {@code typref} de {@code Abstract_Variables} / {@code Concrete_Variables}.
 * Em refinamento fundido: {@code logic T v = return_valid_v(abs);} quando há ligação no invariante
 * ({@link BxmlConnectionAcsl#linkingAbstractVariableName}).
 * Constantes concretas têm bloco próprio ({@link #inferConcreteConstantsLogicTypes} + contexto).
 *
 * <p>Sequências B ({@code iseq}, {@code POW(T*T)}) traduzem-se para {@code \list<elemento>} em ACSL.
 */
public final class BxmlMachineVariables {

    private BxmlMachineVariables() {}

    /**
     * Bloco {@code axiomatic NomeMaquina_variables { logic … }} ou vazio se não houver variáveis
     * declaradas ({@code name} do {@code <Machine>}).
     */
    public static String formatAxiomaticBlock(Element machineEl, BxmlTranslateContext ctx) {
        return formatAxiomaticBlock(machineEl, ctx, null, null, Map.of(), null);
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
                abstractVarNamesForGhostRead);
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
        return formatAxiomaticBlock(machineEl, ctx, rootAbstractMachineName, null, Map.of(), null);
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
                machineEl, ctx, rootAbstractMachineName, refinementParent, gluing, null);
    }

    private static String formatAxiomaticBlock(
            Element machineEl,
            BxmlTranslateContext ctx,
            String rootAbstractMachineName,
            Element refinementParent,
            Map<String, String> gluing,
            Set<String> ghostDummyReadsForAbstractVars) {
        String machineName = machineEl.getAttribute("name");
        if (machineName == null || machineName.isBlank()) return "";
        boolean linkConcrete =
                rootAbstractMachineName != null
                        && !rootAbstractMachineName.isBlank()
                        && (isImplementationMachine(machineEl) || isAbstractionMachine(machineEl));
        boolean linkRefinement =
                refinementParent != null && isRefinementMachine(machineEl);
        Map<String, String> gl =
                gluing == null ? Map.of() : gluing;
        return formatVariablesBlock(
                machineName,
                inferVariableLogicTypes(machineEl, ctx),
                linkConcrete ? rootAbstractMachineName.trim() : null,
                linkRefinement,
                refinementParent,
                machineEl,
                gl,
                ghostDummyReadsForAbstractVars,
                ctx);
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
            BxmlTranslateContext ctx) {
        if (types.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        sb.append("axiomatic ").append(blockName).append("_variables {\n");
        for (Map.Entry<String, String> e : types.entrySet()) {
            String var = e.getKey();
            String logicType = e.getValue();
            String implRhs = null;
            if (rootAbstractForImplRhs != null
                    && refinementChild != null
                    && ctx != null
                    && concreteVariableInvIsTotalArrowFunction(refinementChild, var)) {
                logicType = relationLogicTypeToFunctionLogicType(logicType);
                implRhs =
                        implementationRhsTotalFunctionFromArray(
                                rootAbstractForImplRhs.trim(), var, refinementChild, ctx);
            }
            sb.append("    logic ").append(logicType).append(" ").append(var);
            if (rootAbstractForImplRhs != null) {
                String rhs =
                        implRhs != null
                                ? implRhs
                                : rootAbstractForImplRhs + "__" + var;
                sb.append(" = ").append(rhs);
            } else if (refinementWithParent) {
                Optional<String> abs =
                        BxmlConnectionAcsl.linkingAbstractVariableName(
                                refinementParent, refinementChild, var, gluing);
                abs.ifPresent(
                        a -> sb.append(" = return_valid_").append(var).append("(").append(a).append(")"));
            }
            if (ghostDummyReadsForAbstractVars != null
                    && ghostDummyReadsForAbstractVars.contains(var)) {
                sb.append(" reads dummy_ghost_").append(var);
            }
            sb.append(";\n");
        }
        sb.append("}\n");
        return sb.toString();
    }

    /**
     * Ordem: declaração em {@code Abstract_Variables} / {@code Concrete_Variables}; tipos do
     * invariante sobrepõem-se ao {@code typref} quando há informação compatível.
     */
    public static Map<String, String> inferVariableLogicTypes(
            Element machineEl, BxmlTranslateContext ctx) {
        BxmlTypeRegistry types = ctx.types();
        List<Element> varIds = listDeclaredVariableIds(machineEl);
        Map<String, String> fromInvariant = inferTypesFromInvariants(machineEl, types);

        LinkedHashMap<String, String> out = new LinkedHashMap<>();
        for (Element idEl : varIds) {
            String name = idEl.getAttribute("value");
            if (name == null || name.isBlank()) continue;
            String trAttr = idEl.getAttribute("typref");
            int typref = trAttr.isBlank() ? -1 : Integer.parseInt(trAttr.trim());

            String t = fromInvariant.get(name);
            if (t == null) {
                t = types.acslVariableLogicTypeFromTypref(typref);
            }
            out.put(name, t);
        }
        return out;
    }

    /**
     * Tipos {@code logic} só para {@code Concrete_Constants} (para fundir no
     * {@link BxmlTranslateContext#variableLogicTypes()} sem duplicar no bloco {@code _variables}).
     */
    public static Map<String, String> inferConcreteConstantsLogicTypes(
            Element machineEl, BxmlTypeRegistry types) {
        LinkedHashMap<String, String> out = new LinkedHashMap<>();
        Element block = firstChildElement(machineEl, "Concrete_Constants");
        if (block == null) return out;
        NodeList ch = block.getChildNodes();
        for (int i = 0; i < ch.getLength(); i++) {
            Node n = ch.item(i);
            if (n.getNodeType() != Node.ELEMENT_NODE) continue;
            Element e = (Element) n;
            if ("Attr".equals(e.getLocalName())) continue;
            if (!"Id".equals(e.getLocalName())) continue;
            String name = e.getAttribute("value");
            if (name == null || name.isBlank()) continue;
            String trAttr = e.getAttribute("typref");
            int typref = trAttr.isBlank() ? -1 : Integer.parseInt(trAttr.trim());
            out.put(name, types.acslVariableLogicTypeFromTypref(typref));
        }
        return out;
    }

    /** Nomes de variáveis declaradas ({@code Abstract_Variables} / {@code Concrete_Variables}). */
    public static Set<String> declaredVariableNames(Element machineEl) {
        LinkedHashSet<String> names = new LinkedHashSet<>();
        for (Element idEl : listDeclaredVariableIds(machineEl)) {
            String name = idEl.getAttribute("value");
            if (name != null && !name.isBlank()) {
                names.add(name.trim());
            }
        }
        return names;
    }

    /**
     * Verdadeiro quando alguma implementação fundida não declara variáveis próprias e reutiliza o
     * estado da abstrata (liga {@code logic v = Raiz__v;} no bloco {@code Raiz_variables}).
     */
    public static boolean anyImplementationUsesAbstractVariablesOnly(
            Element abstractMachineEl, List<Element> mergedMachineElements) {
        if (abstractMachineEl == null || mergedMachineElements == null || mergedMachineElements.isEmpty()) {
            return false;
        }
        if (declaredVariableNames(abstractMachineEl).isEmpty()) {
            return false;
        }
        for (Element mel : mergedMachineElements) {
            if (!isImplementationMachine(mel)) {
                continue;
            }
            if (declaredVariableNames(mel).isEmpty()) {
                return true;
            }
        }
        return false;
    }

    /**
     * Verdadeiro quando alguma máquina fundida do tipo {@code implementation} declara exactamente
     * o mesmo conjunto de variáveis que a abstrata raiz (evita duplicar {@code logic v} no ACSL).
     */
    public static boolean implementationMirrorsAbstractVariables(
            Element abstractMachineEl, List<Element> mergedMachineElements) {
        if (abstractMachineEl == null || mergedMachineElements == null || mergedMachineElements.isEmpty()) {
            return false;
        }
        Set<String> abstractNames = declaredVariableNames(abstractMachineEl);
        if (abstractNames.isEmpty()) {
            return false;
        }
        for (Element mel : mergedMachineElements) {
            if (!isImplementationMachine(mel)) {
                continue;
            }
            if (abstractNames.equals(declaredVariableNames(mel))) {
                return true;
            }
        }
        return false;
    }

    /**
     * Implementação com o mesmo conjunto de variáveis que a abstrata: o estado C é o da especificação
     * ({@code logic v = Raiz__v;}) e não se usa camada ghost paralela.
     */
    public static boolean usesDirectImplementationVariables(
            Element abstractMachineEl, List<Element> mergedMachineElements) {
        return implementationMirrorsAbstractVariables(abstractMachineEl, mergedMachineElements)
                || anyImplementationUsesAbstractVariablesOnly(
                        abstractMachineEl, mergedMachineElements);
    }

    /** Ghost só quando há refinamento/implementação com estado distinto do da abstrata. */
    public static boolean needsGhostAbstraction(
            Element abstractMachineEl, List<Element> mergedMachineElements) {
        return !usesDirectImplementationVariables(abstractMachineEl, mergedMachineElements);
    }

    /** {@code Abstract_Variables} e {@code Concrete_Variables} (filhos diretos de {@code Machine}). */
    public static List<Element> listDeclaredVariableIds(Element machineEl) {
        List<Element> out = new ArrayList<>();
        String[] sections = {"Abstract_Variables", "Concrete_Variables"};
        for (String sec : sections) {
            Element block = firstChildElement(machineEl, sec);
            if (block == null) continue;
            NodeList ch = block.getChildNodes();
            for (int i = 0; i < ch.getLength(); i++) {
                Node n = ch.item(i);
                if (n.getNodeType() != Node.ELEMENT_NODE) continue;
                Element e = (Element) n;
                if ("Attr".equals(e.getLocalName())) continue;
                if ("Id".equals(e.getLocalName())) out.add(e);
            }
        }
        return out;
    }

    /**
     * Nomes para cláusulas {@code assigns} na inicialização: variáveis de implementação
     * ({@code Concrete_Variables}) de cada máquina fundida com {@code type="implementation"},
     * no formato {@code NomeMaquinaAbstrata__nomeVar} (contrato C alinhado à raiz abstrata).
     */
    /**
     * Alvos {@code Raiz__c} para {@code assigns} quando uma operação na abstrata altera variáveis em
     * {@code assignedAbstractNames}: propaga-se a ligação ao longo da cadeia fundida (refinamento →
     * implementação) e escolhem-se variáveis concretas cujo invariante referencia nomes já ligados.
     */
    public static List<String> listConcreteAssignTargetsForAbstractMutation(
            String rootAbstractMachineName,
            Element rootMachine,
            List<Element> mergedOrdered,
            Set<String> assignedAbstractNames,
            Map<String, String> gluing,
            BxmlTranslateContext ctx) {
        if (rootAbstractMachineName == null
                || rootAbstractMachineName.isBlank()
                || rootMachine == null
                || mergedOrdered == null
                || mergedOrdered.isEmpty()
                || assignedAbstractNames == null
                || assignedAbstractNames.isEmpty()) {
            return List.of();
        }
        Map<String, String> g = gluing == null ? Map.of() : gluing;
        Set<String> linked = new LinkedHashSet<>(assignedAbstractNames);
        Element prev = rootMachine;
        LinkedHashSet<String> targets = new LinkedHashSet<>();
        for (Element mel : mergedOrdered) {
            if (isRefinementMachine(mel)) {
                for (String refined : BxmlConnectionAcsl.introducedStateVariableIds(mel)) {
                    Optional<String> abs =
                            BxmlConnectionAcsl.linkingAbstractVariableName(prev, mel, refined, g);
                    if (abs.isPresent() && linked.contains(abs.get())) {
                        linked.add(refined);
                    }
                }
                prev = mel;
            } else if (isImplementationMachine(mel)) {
                Set<String> invIds = BxmlConnectionAcsl.invariantReferencedIdentifiers(mel);
                if (!Collections.disjoint(invIds, linked)) {
                    for (String c : BxmlConnectionAcsl.introducedStateVariableIds(mel)) {
                        if (invIds.contains(c)) {
                            String base = rootAbstractMachineName.trim() + "__" + c;
                            String ranged = implementationAssignTargetWithRange(base, c, mel, ctx);
                            targets.add(ranged == null ? base : ranged);
                        }
                    }
                }
                prev = mel;
            } else {
                prev = mel;
            }
        }
        return new ArrayList<>(targets);
    }

    /**
     * Alvos {@code Raiz__v} da implementação para variáveis abstratas atribuídas na operação (modo
     * directo, sem invariante na implementação).
     */
    public static List<String> listImplementationAssignTargetsForAbstractVariables(
            String abstractMachineName,
            List<Element> mergedMachineElements,
            Set<String> abstractVariableNames,
            BxmlTranslateContext ctx) {
        if (abstractMachineName == null
                || abstractMachineName.isBlank()
                || abstractVariableNames == null
                || abstractVariableNames.isEmpty()) {
            return List.of();
        }
        String prefix = abstractMachineName.trim() + "__";
        List<String> out = new ArrayList<>();
        for (String target :
                listImplementationAssignTargets(abstractMachineName, mergedMachineElements, ctx)) {
            if (target == null || !target.startsWith(prefix)) {
                continue;
            }
            String var = target.substring(prefix.length());
            int bracket = var.indexOf('[');
            if (bracket >= 0) {
                var = var.substring(0, bracket);
            }
            if (abstractVariableNames.contains(var)) {
                out.add(target);
            }
        }
        return out;
    }

    /**
     * Alvos {@code Raiz__v} para {@code assigns} na inicialização quando a implementação fundida não
     * declara variáveis e reutiliza o estado da abstração ligado ao C ({@code logic v = Raiz__v;}).
     */
    public static List<String> listLinkedConcreteAssignTargetsForInitialisation(
            String abstractMachineName,
            Element abstractMachineEl,
            List<Element> mergedMachineElements) {
        return listLinkedConcreteAssignTargetsForInitialisation(
                abstractMachineName, abstractMachineEl, mergedMachineElements, null);
    }

    public static List<String> listLinkedConcreteAssignTargetsForInitialisation(
            String abstractMachineName,
            Element abstractMachineEl,
            List<Element> mergedMachineElements,
            BxmlTranslateContext ctx) {
        if (abstractMachineName == null
                || abstractMachineName.isBlank()
                || abstractMachineEl == null
                || !anyImplementationUsesAbstractVariablesOnly(
                        abstractMachineEl, mergedMachineElements)) {
            return List.of();
        }
        Set<String> declared = declaredVariableNames(abstractMachineEl);
        Set<String> assigned =
                GhostOperationsCiGenerator.variablesAssignedInInitialisation(
                        abstractMachineEl, declared);
        if (assigned.isEmpty()) {
            return List.of();
        }
        if (ctx == null) {
            return linkedConcreteAssignTargetsForVariableNames(abstractMachineName, assigned);
        }
        String prefix = abstractMachineName.trim() + "__";
        List<String> out = new ArrayList<>();
        for (String v : assigned) {
            if (v == null || v.isBlank()) continue;
            String base = prefix + v.trim();
            String ranged = implementationAssignTargetWithRange(base, v.trim(), abstractMachineEl, ctx);
            out.add(ranged == null ? base : ranged);
        }
        return out;
    }

    /**
     * Alvos {@code Raiz__v} para {@code assigns} numa operação quando a implementação fundida não
     * declara variáveis e a operação altera variáveis da abstração ligadas ao C.
     */
    public static List<String> listLinkedConcreteAssignTargetsForOperation(
            String abstractMachineName,
            Element abstractMachineEl,
            List<Element> mergedMachineElements,
            Set<String> assignedVariableNames) {
        return listLinkedConcreteAssignTargetsForOperation(
                abstractMachineName, abstractMachineEl, mergedMachineElements, assignedVariableNames, null);
    }

    public static List<String> listLinkedConcreteAssignTargetsForOperation(
            String abstractMachineName,
            Element abstractMachineEl,
            List<Element> mergedMachineElements,
            Set<String> assignedVariableNames,
            BxmlTranslateContext ctx) {
        if (abstractMachineName == null
                || abstractMachineName.isBlank()
                || abstractMachineEl == null
                || assignedVariableNames == null
                || assignedVariableNames.isEmpty()
                || !anyImplementationUsesAbstractVariablesOnly(
                        abstractMachineEl, mergedMachineElements)) {
            return List.of();
        }
        Set<String> declared = declaredVariableNames(abstractMachineEl);
        Set<String> filtered = new LinkedHashSet<>();
        for (String v : assignedVariableNames) {
            if (v != null && declared.contains(v.trim())) {
                filtered.add(v.trim());
            }
        }
        if (ctx == null) {
            return linkedConcreteAssignTargetsForVariableNames(abstractMachineName, filtered);
        }
        String prefix = abstractMachineName.trim() + "__";
        List<String> out = new ArrayList<>();
        for (String v : filtered) {
            String base = prefix + v;
            String ranged = implementationAssignTargetWithRange(base, v, abstractMachineEl, ctx);
            out.add(ranged == null ? base : ranged);
        }
        return out;
    }

    private static List<String> linkedConcreteAssignTargetsForVariableNames(
            String abstractMachineName, Set<String> variableNames) {
        if (abstractMachineName == null
                || abstractMachineName.isBlank()
                || variableNames == null
                || variableNames.isEmpty()) {
            return List.of();
        }
        String prefix = abstractMachineName.trim() + "__";
        List<String> out = new ArrayList<>();
        for (String v : variableNames) {
            if (v != null && !v.isBlank()) {
                out.add(prefix + v.trim());
            }
        }
        return out;
    }

    /**
     * Alvos {@code assigns} da inicialização: variáveis concretas da implementação fundida, ou
     * {@code Raiz__v} para variáveis da abstração atribuídas na init quando a implementação não
     * declara estado próprio.
     */
    public static List<String> listInitialisationAssignTargets(
            String abstractMachineName,
            Element abstractMachineEl,
            List<Element> mergedMachineElements,
            BxmlTranslateContext ctx) {
        List<String> fromImpl =
                listImplementationAssignTargets(abstractMachineName, mergedMachineElements, ctx);
        if (!fromImpl.isEmpty()) {
            return fromImpl;
        }
        return listLinkedConcreteAssignTargetsForInitialisation(
                abstractMachineName, abstractMachineEl, mergedMachineElements, ctx);
    }

    public static List<String> listImplementationAssignTargets(
            String abstractMachineName, List<Element> mergedMachineElements) {
        return listImplementationAssignTargets(abstractMachineName, mergedMachineElements, null);
    }

    /**
     * Como {@link #listImplementationAssignTargets(String, List)}, mas quando a variável concreta
     * é array (domínio de função total com intervalo), gera alvo com fatia ACSL:
     * {@code Raiz__v[low .. high]}.
     */
    public static List<String> listImplementationAssignTargets(
            String abstractMachineName,
            List<Element> mergedMachineElements,
            BxmlTranslateContext ctx) {
        if (abstractMachineName == null || abstractMachineName.isBlank()) return List.of();
        List<String> out = new ArrayList<>();
        for (Element mel : mergedMachineElements) {
            if (!isImplementationMachine(mel)) continue;
            Element concrete = firstChildElement(mel, "Concrete_Variables");
            if (concrete == null) continue;
            NodeList ch = concrete.getChildNodes();
            for (int i = 0; i < ch.getLength(); i++) {
                Node n = ch.item(i);
                if (n.getNodeType() != Node.ELEMENT_NODE) continue;
                Element e = (Element) n;
                if ("Attr".equals(e.getLocalName())) continue;
                if (!"Id".equals(e.getLocalName())) continue;
                String v = e.getAttribute("value");
                if (v != null && !v.isBlank()) {
                    String base = abstractMachineName + "__" + v;
                    String ranged = implementationAssignTargetWithRange(base, v, mel, ctx);
                    out.add(ranged == null ? base : ranged);
                }
            }
        }
        return out;
    }

    /**
     * Qualifica um nome de variável para uso em {@code loop assigns}: se for uma variável declarada
     * na máquina abstrata, retorna {@code machineName__varName} (com {@code [range]} para arrays);
     * caso contrário (variável local {@code VAR_IN} ou parâmetro de saída), retorna o nome como-está.
     */
    public static String qualifyLoopAssignTarget(
            String varName, String machineName, Element abstractMachineEl, BxmlTranslateContext ctx) {
        if (varName == null || varName.isBlank() || machineName == null || abstractMachineEl == null) {
            return varName;
        }
        if (!declaredVariableNames(abstractMachineEl).contains(varName)) {
            return varName;
        }
        String base = machineName.trim() + "__" + varName;
        String ranged = implementationAssignTargetWithRange(base, varName, abstractMachineEl, ctx);
        return ranged != null ? ranged : base;
    }

    private static String implementationAssignTargetWithRange(
            String baseTarget, String varName, Element implMachineEl, BxmlTranslateContext ctx) {
        if (baseTarget == null || varName == null || implMachineEl == null || ctx == null) {
            return null;
        }
        Element arrow = concreteVariableFunctionArrowElement(implMachineEl, varName);
        String range = arrayDomainRangeAcsl(arrow, ctx);
        if (range == null || range.isBlank()) {
            return null;
        }
        return baseTarget + "[" + range + "]";
    }

    /** Intervalo do domínio de {@code -->} (ex. {@code 0..maximum} -> {@code 0 .. maximum}). */
    private static String arrayDomainRangeAcsl(Element arrowEl, BxmlTranslateContext ctx) {
        if (arrowEl == null || ctx == null) {
            return null;
        }
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
        return low + " .. " + high;
    }

    private static boolean isImplementationMachine(Element machineEl) {
        String t = machineEl.getAttribute("type");
        return t != null && "implementation".equalsIgnoreCase(t.trim());
    }

    private static boolean isAbstractionMachine(Element machineEl) {
        String t = machineEl.getAttribute("type");
        return t != null && "abstraction".equalsIgnoreCase(t.trim());
    }

    private static boolean isRefinementMachine(Element machineEl) {
        String t = machineEl.getAttribute("type");
        return t != null && "refinement".equalsIgnoreCase(t.trim());
    }

    /**
     * Percorre todos os {@code <Invariant>} e extrai restrições de tipo:
     * <ul>
     *   <li>{@code v <: T} com {@code T} nome de tipo base → {@code Set<…>}</li>
     *   <li>{@code v : T} com {@code T} primitivo → elemento escalar</li>
     *   <li>{@code v : iseq(T)} → {@code \list<elemento(T)>} (sequências em ACSL)</li>
     * </ul>
     */
    static Map<String, String> inferTypesFromInvariants(Element machineEl, BxmlTypeRegistry types) {
        LinkedHashMap<String, String> acc = new LinkedHashMap<>();
        NodeList children = machineEl.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node n = children.item(i);
            if (n.getNodeType() != Node.ELEMENT_NODE) continue;
            Element e = (Element) n;
            if (!"Invariant".equals(e.getLocalName())) continue;
            Element pred = firstPredChild(e);
            if (pred != null) walkPredForVariableTypes(pred, types, acc);
        }
        return acc;
    }

    private static void walkPredForVariableTypes(
            Element p, BxmlTypeRegistry types, Map<String, String> acc) {
        String ln = p.getLocalName();
        switch (ln) {
            case "Exp_Comparison" -> handleExpComparisonForTypes(p, types, acc);
            case "Nary_Pred", "Unary_Pred", "Binary_Pred" -> {
                NodeList nl = p.getChildNodes();
                for (int i = 0; i < nl.getLength(); i++) {
                    Node n = nl.item(i);
                    if (n.getNodeType() != Node.ELEMENT_NODE) continue;
                    Element ch = (Element) n;
                    if ("Attr".equals(ch.getLocalName())) continue;
                    walkPredForVariableTypes(ch, types, acc);
                }
            }
            default -> {
            }
        }
    }

    private static void handleExpComparisonForTypes(
            Element cmp, BxmlTypeRegistry types, Map<String, String> acc) {
        String op = normalizeComparisonOp(cmp.getAttribute("op"));
        Element[] pair = BxmlExpressionToAcsl.twoDirectExpChildren(cmp);
        if (pair[0] == null || pair[1] == null) return;

        if ("<:".equals(op)) {
            if ("Id".equals(pair[0].getLocalName())) {
                String v = pair[0].getAttribute("value");
                if ("Id".equals(pair[1].getLocalName())) {
                    String rhs = pair[1].getAttribute("value");
                    if (isNamedBaseType(rhs)) {
                        acc.put(v, "Set<" + types.acslElementTypeName(rhs) + ">");
                    }
                }
            }
            return;
        }
        if (":".equals(op)) {
            if ("Id".equals(pair[0].getLocalName())) {
                String v = pair[0].getAttribute("value");
                if ("Id".equals(pair[1].getLocalName())) {
                    String rhs = pair[1].getAttribute("value");
                    if (isNamedBaseType(rhs)) {
                        acc.put(v, types.acslElementTypeName(rhs));
                    }
                    return;
                }
                if ("Unary_Exp".equals(pair[1].getLocalName())
                        && ("seq".equals(pair[1].getAttribute("op"))
                            ||"iseq".equals(pair[1].getAttribute("op")))) {
                    acc.put(v, acslListTypeForIseqUnary(pair[1], types));
                }
            }
        }
    }

    /** Argumento de {@code iseq(T)} no B → {@code \list<…>} com o tipo elemento de {@code T}. */
    private static String acslListTypeForIseqUnary(Element unaryIseq, BxmlTypeRegistry types) {
        Element arg = firstNonAttrElementChild(unaryIseq);
        if (arg != null && "Id".equals(arg.getLocalName())) {
            String rhs = arg.getAttribute("value");
            if (isNamedBaseType(rhs)) {
                return "\\list<" + types.acslElementTypeName(rhs) + ">";
            }
        }
        return "\\list<integer>";
    }

    private static Element firstNonAttrElementChild(Element parent) {
        NodeList nl = parent.getChildNodes();
        for (int i = 0; i < nl.getLength(); i++) {
            Node n = nl.item(i);
            if (n.getNodeType() != Node.ELEMENT_NODE) continue;
            Element el = (Element) n;
            if ("Attr".equals(el.getLocalName())) continue;
            return el;
        }
        return null;
    }

    private static String normalizeComparisonOp(String op) {
        if (op == null) return "";
        String o = op.trim();
        if ("&lt;:".equals(o)) return "<:";
        return o;
    }

    private static boolean isNamedBaseType(String name) {
        if (name == null || name.isBlank()) return false;
        return switch (name) {
            case "NAT", "INTEGER", "INT", "BOOL" -> true;
            default -> false;
        };
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

    /**
     * B: {@code v : Dom --> Cod} num invariante → tipo {@code logic} como função total ({@code
     * Function_*_*}) em vez de {@code Relation_*_*}.
     */
    private static String relationLogicTypeToFunctionLogicType(String relationLogicType) {
        if (relationLogicType == null || relationLogicType.isBlank()) {
            return "Function_int_int";
        }
        return switch (relationLogicType.trim()) {
            case "Relation_int_int" -> "Function_int_int";
            case "Relation_int_bool" -> "Function_int_bool";
            case "Relation_bool_int" -> "Function_bool_int";
            default -> relationLogicType;
        };
    }

    private static boolean concreteVariableInvIsTotalArrowFunction(Element implMachineEl, String varName) {
        return concreteVariableFunctionArrowElement(implMachineEl, varName) != null;
    }

    /** {@code Binary_Exp} {@code -->} que tipa {@code varName}, ou null. */
    private static Element concreteVariableFunctionArrowElement(Element implMachineEl, String varName) {
        if (implMachineEl == null || varName == null || varName.isBlank()) {
            return null;
        }
        Element inv = firstChildElement(implMachineEl, "Invariant");
        if (inv == null) {
            return null;
        }
        Element pred = firstPredChild(inv);
        return findFunctionArrowExpForVariableMembership(pred, varName);
    }

    private static Element findFunctionArrowExpForVariableMembership(Element pred, String varName) {
        if (pred == null) {
            return null;
        }
        String ln = pred.getLocalName();
        if ("Exp_Comparison".equals(ln)) {
            String op = normalizeColonLikeOp(pred.getAttribute("op"));
            if (":".equals(op)) {
                Element[] pair = BxmlExpressionToAcsl.twoDirectExpChildren(pred);
                if (pair[0] != null
                        && "Id".equals(pair[0].getLocalName())
                        && varName.equals(pair[0].getAttribute("value").trim())
                        && pair[1] != null
                        && BxmlExpressionToAcsl.isFunctionArrowType(pair[1])) {
                    return pair[1];
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
                Element found = findFunctionArrowExpForVariableMembership(ch, varName);
                if (found != null) {
                    return found;
                }
            }
            return null;
        }
        if ("Unary_Pred".equals(ln)) {
            return findFunctionArrowExpForVariableMembership(firstPredChild(pred), varName);
        }
        if ("Binary_Pred".equals(ln)) {
            Element[] pair = twoDirectPredChildren(pred);
            if (pair[0] != null) {
                Element f = findFunctionArrowExpForVariableMembership(pair[0], varName);
                if (f != null) {
                    return f;
                }
            }
            if (pair[1] != null) {
                return findFunctionArrowExpForVariableMembership(pair[1], varName);
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
     * Lado direito da ligação à implementação C: array {@code Raiz__v} como função (índices 0..n-1).
     */
    private static String implementationRhsTotalFunctionFromArray(
            String rootAbstractName,
            String varName,
            Element implMachineEl,
            BxmlTranslateContext ctx) {
        Element arrow = concreteVariableFunctionArrowElement(implMachineEl, varName);
        String len = arrow != null ? arrayDomainCardinalityAcsl(arrow, ctx) : null;
        if (len == null || len.isBlank()) {
            len = "1";
        }
        String q = rootAbstractName + "__" + varName;
        return "array_to_function((int32_t*)(" + q + "), " + len + ")";
    }

    /** Cardinalidade do intervalo/domínio de {@code -->} (ex. {@code 0..maximum} → {@code (maximum + 1)}). */
    private static String arrayDomainCardinalityAcsl(Element arrowEl, BxmlTranslateContext ctx) {
        if (arrowEl == null || ctx == null) {
            return null;
        }
        Element[] domRng = BxmlExpressionToAcsl.twoDirectExpChildren(arrowEl);
        if (domRng[0] == null) {
            return null;
        }
        Element domain = domRng[0];
        if (BxmlExpressionToAcsl.isIntervalBinaryExp(domain)) {
            Element[] lr = BxmlExpressionToAcsl.twoDirectExpChildren(domain);
            if (lr[0] != null && lr[1] != null) {
                String low = BxmlExpressionToAcsl.translate(lr[0], ctx).trim();
                String high = BxmlExpressionToAcsl.translate(lr[1], ctx).trim();
                return "(" + high + " - (" + low + ") + 1)";
            }
        }
        return null;
    }

    /**
     * Targets {@code assigns machineName__varName} for ALL concrete variables of each imported
     * machine's implementation BXML ({@code name_i.bxml}).  Added to INITIALISATION assigns because
     * Atelier B always calls imported-machine INITIALISATION in the generated C code.
     */
    public static List<String> listImportedMachineConcreteAssigns(
            List<String> importedMachineNames, Path bxmlDirectory) {
        if (importedMachineNames == null || importedMachineNames.isEmpty() || bxmlDirectory == null) {
            return List.of();
        }
        List<String> result = new ArrayList<>();
        for (String name : importedMachineNames) {
            result.addAll(loadConcreteAssignsForImportedMachine(name, bxmlDirectory));
        }
        return result;
    }

    /**
     * Map from each imported-machine operation local name to the concrete assign targets of that
     * machine.  Used to union called-operation assigns into the caller's {@code assigns} clause.
     */
    public static Map<String, List<String>> buildImportedOperationAssignsMap(
            List<String> importedMachineNames, Path bxmlDirectory) {
        if (importedMachineNames == null || importedMachineNames.isEmpty() || bxmlDirectory == null) {
            return Map.of();
        }
        LinkedHashMap<String, List<String>> result = new LinkedHashMap<>();
        for (String machineName : importedMachineNames) {
            List<String> machineAssigns = loadConcreteAssignsForImportedMachine(machineName, bxmlDirectory);
            if (machineAssigns.isEmpty()) continue;
            for (String opName : loadOperationNamesForMachine(machineName, bxmlDirectory)) {
                result.computeIfAbsent(opName, k -> new ArrayList<>()).addAll(machineAssigns);
            }
        }
        return result;
    }

    private static List<String> loadConcreteAssignsForImportedMachine(String machineName, Path bxmlDirectory) {
        Element abstractEl = null;
        Path abstractPath = bxmlDirectory.resolve(machineName + ".bxml");
        if (Files.exists(abstractPath)) {
            try { abstractEl = BxmlSetsTranslator.parseMachineElement(abstractPath); } catch (Exception ignored) {}
        }

        Element implEl = null;
        for (String suffix : new String[]{"_i", "_imp"}) {
            Path path = bxmlDirectory.resolve(machineName + suffix + ".bxml");
            if (!Files.exists(path)) continue;
            try {
                Element el = BxmlSetsTranslator.parseMachineElement(path);
                if (!"implementation".equalsIgnoreCase(el.getAttribute("type"))) continue;
                implEl = el;
                break;
            } catch (Exception ignored) {}
        }

        if (implEl == null) return List.of();

        List<String> result = new ArrayList<>(listImplementationAssignTargets(machineName, List.of(implEl), null));

        if (abstractEl != null && needsGhostAbstraction(abstractEl, List.of(implEl))) {
            for (String v : GhostOperationsCiGenerator.listAbstractVariableNames(abstractEl)) {
                result.add("ghost_" + v);
            }
        }

        return result;
    }

    private static List<String> loadOperationNamesForMachine(String machineName, Path bxmlDirectory) {
        Path path = bxmlDirectory.resolve(machineName + ".bxml");
        if (!Files.exists(path)) return List.of();
        try {
            Element machineEl = BxmlSetsTranslator.parseMachineElement(path);
            List<String> names = new ArrayList<>();
            NodeList ops = machineEl.getElementsByTagNameNS("*", "Operations");
            if (ops.getLength() == 0) return List.of();
            Element operationsEl = (Element) ops.item(0);
            NodeList children = operationsEl.getChildNodes();
            for (int i = 0; i < children.getLength(); i++) {
                Node n = children.item(i);
                if (n.getNodeType() != Node.ELEMENT_NODE) continue;
                Element ch = (Element) n;
                if ("Operation".equals(ch.getLocalName())) {
                    String name = ch.getAttribute("name");
                    if (name != null && !name.isBlank()) names.add(name.trim());
                }
            }
            return names;
        } catch (Exception ignored) {
            return List.of();
        }
    }
}
