package com.example.bxml;

import java.util.LinkedHashMap;
import java.util.Map;

import org.w3c.dom.Element;

/**
 * Contexto compartilhado na tradução BXML → ACSL (tipos + índice de conjuntos em compreensão +
 * tipos {@code logic} das variáveis para igualdade conjunto vs escalar).
 */
public record BxmlTranslateContext(
        BxmlTypeRegistry types,
        BxmlComprehensionRegistry comprehensions,
        Map<String, String> variableLogicTypes) {

    public BxmlTranslateContext(BxmlTypeRegistry types, BxmlComprehensionRegistry comprehensions) {
        this(types, comprehensions, Map.of());
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
        return new BxmlTranslateContext(types, reg, merged);
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
        return new BxmlTranslateContext(types, sharedComprehensions, merged);
    }
}
