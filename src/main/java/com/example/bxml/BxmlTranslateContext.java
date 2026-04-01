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
        reg.setGluingSubstitutions(gluingSubstitutions);
        reg.assignDedupIndices(types);
        BxmlTranslateContext tmp = new BxmlTranslateContext(types, reg, Map.of());
        LinkedHashMap<String, String> merged = new LinkedHashMap<>();
        merged.putAll(BxmlMachineVariables.inferConcreteConstantsLogicTypes(machineEl, types));
        merged.putAll(BxmlMachineVariables.inferVariableLogicTypes(machineEl, tmp));
        return new BxmlTranslateContext(types, reg, merged);
    }
}
