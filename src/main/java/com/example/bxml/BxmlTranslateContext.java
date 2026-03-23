package com.example.bxml;

import java.util.Map;

import org.w3c.dom.Element;

/**
 * Contexto compartilhado na tradução BXML → ACSL (tipos + índice de conjuntos em compreensão).
 */
public record BxmlTranslateContext(BxmlTypeRegistry types, BxmlComprehensionRegistry comprehensions) {

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
        return new BxmlTranslateContext(types, reg);
    }
}
