package com.example.bxml;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import org.w3c.dom.Element;

/**
 * Contexto compartilhado na tradução BXML → ACSL (tipos + índice de conjuntos em compreensão +
 * tipos {@code logic} das variáveis para igualdade conjunto vs escalar).
 */
public record BxmlTranslateContext(
        BxmlTypeRegistry types,
        BxmlComprehensionRegistry comprehensions,
        Map<String, String> variableLogicTypes,
        LambdaFunctionRegistry lambdaRegistry,
        Map<String, String> enumValueRenames,
        Set<String> enumeratedSetNames) {

    /** Sem registo de lambdas nem renomeação de enumerados. */
    public BxmlTranslateContext(
            BxmlTypeRegistry types,
            BxmlComprehensionRegistry comprehensions,
            Map<String, String> variableLogicTypes) {
        this(types, comprehensions, variableLogicTypes, null, Map.of(), Set.of());
    }

    public BxmlTranslateContext(BxmlTypeRegistry types, BxmlComprehensionRegistry comprehensions) {
        this(types, comprehensions, Map.of(), null, Map.of(), Set.of());
    }

    /** Retorna uma cópia deste contexto com o registo de lambdas substituído. */
    public BxmlTranslateContext withLambdaRegistry(LambdaFunctionRegistry registry) {
        return new BxmlTranslateContext(types, comprehensions, variableLogicTypes, registry, enumValueRenames, enumeratedSetNames);
    }

    /** Retorna uma cópia deste contexto com o mapa de renomeação de valores enumerados substituído. */
    public BxmlTranslateContext withEnumRenames(Map<String, String> renames) {
        return new BxmlTranslateContext(
                types, comprehensions, variableLogicTypes, lambdaRegistry,
                renames == null ? Map.of() : renames,
                enumeratedSetNames);
    }

    /** Retorna uma cópia deste contexto com os nomes de conjuntos enumerados substituídos. */
    public BxmlTranslateContext withEnumeratedSetNames(Set<String> setNames) {
        return new BxmlTranslateContext(
                types, comprehensions, variableLogicTypes, lambdaRegistry,
                enumValueRenames,
                setNames == null ? Set.of() : setNames);
    }

    public static BxmlTranslateContext forMachine(Element machineEl) {
        return forMachine(machineEl, Map.of());
    }

    /**
     * @param gluingSubstitutions ex.: {@code ran(numbers_s) → numbers} extraído dos invariantes (todas as máquinas)
     */
    public static BxmlTranslateContext forMachine(Element machineEl, Map<String, String> gluingSubstitutions) {
        BxmlTypeRegistry types = BxmlTypeRegistry.fromMachine(machineEl);
        BxmlComprehensionRegistry reg = BxmlComprehensionRegistry.fromMachine(machineEl);
        Map<String, String> gl = gluingSubstitutions == null ? Map.of() : gluingSubstitutions;
        reg.setGluingSubstitutions(gl);
        reg.assignDedupIndices();
        BxmlTranslateContext tmp = new BxmlTranslateContext(types, reg, Map.of());
        LinkedHashMap<String, String> merged = new LinkedHashMap<>();
        merged.putAll(BxmlMachineVariables.inferConcreteConstantsLogicTypes(machineEl, types));
        merged.putAll(BxmlMachineVariables.inferVariableLogicTypes(machineEl, tmp));
        return new BxmlTranslateContext(types, reg, merged, null, Map.of(), Set.of());
    }

    /**
     * Como {@link #forMachine(Element, Map)}, mas usa um registo de compreensões já construído (ex.
     * {@link BxmlComprehensionRegistry#fromMachineChain(java.util.List, Map)}) e indexado — não chama
     * {@link BxmlComprehensionRegistry#assignDedupIndices()} de novo.
     */
    public static BxmlTranslateContext forMachineWithSharedComprehensions(
            Element machineEl,
            BxmlComprehensionRegistry sharedComprehensions,
            Map<String, String> gluingSubstitutions) {
        return forMachineWithSharedComprehensions(
                machineEl, sharedComprehensions, gluingSubstitutions, null);
    }

    /**
     * Como {@link #forMachineWithSharedComprehensions(Element, BxmlComprehensionRegistry, Map)}, mas
     * para refinamentos/implementações fundidas: funde os tipos {@code logic} das variáveis inferidos
     * na máquina abstrata {@code abstractMachineElForSharedVariableTypes} (ex. {@code myseq} como
     * {@code \\list}) antes dos tipos da máquina concreta, para invariantes que referenciam estado
     * abstrato.
     */
    public static BxmlTranslateContext forMachineWithSharedComprehensions(
            Element machineEl,
            BxmlComprehensionRegistry sharedComprehensions,
            Map<String, String> gluingSubstitutions,
            Element abstractMachineElForSharedVariableTypes) {
        BxmlTypeRegistry types = BxmlTypeRegistry.fromMachine(machineEl);
        Map<String, String> gl = gluingSubstitutions == null ? Map.of() : gluingSubstitutions;
        sharedComprehensions.setGluingSubstitutions(gl);
        BxmlTranslateContext tmp = new BxmlTranslateContext(types, sharedComprehensions, Map.of());
        LinkedHashMap<String, String> merged = new LinkedHashMap<>();
        merged.putAll(BxmlMachineVariables.inferConcreteConstantsLogicTypes(machineEl, types));
        if (abstractMachineElForSharedVariableTypes != null) {
            BxmlTypeRegistry absTypes =
                    BxmlTypeRegistry.fromMachine(abstractMachineElForSharedVariableTypes);
            BxmlTranslateContext tmpAbs =
                    new BxmlTranslateContext(absTypes, sharedComprehensions, Map.of());
            merged.putAll(
                    BxmlMachineVariables.inferVariableLogicTypes(
                            abstractMachineElForSharedVariableTypes, tmpAbs));
        }
        merged.putAll(BxmlMachineVariables.inferVariableLogicTypes(machineEl, tmp));
        return new BxmlTranslateContext(types, sharedComprehensions, merged, null, Map.of(), Set.of());
    }
}
