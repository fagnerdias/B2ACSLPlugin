package com.example.bxml;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Tipos {@code Relation<domínio, TuplaAninhada>} de codomínio-tupla (B {@code v : Dom +-> (A*B*...)},
 * N&gt;=2 elementos, quaisquer tipos escalares — não só inteiros) descobertos durante a tradução de
 * UMA máquina, para os quais não há (nem pode haver, combinatoriamente) um alias pré-declarado em
 * {@code types.acsl} (ver {@link BxmlTypeRegistry#powCartesianProductToAcslRelationType}).
 *
 * <p>{@code -acsl-import} não aceita instanciação genérica inline ({@code Relation<A,B>} com tipos
 * concretos) num ficheiro de TOPO — só funciona dentro de um ficheiro alcançado via {@code include}
 * (confirmado empiricamente). Por isso estes tipos são escritos num {@code .acsl} próprio da
 * máquina que os precisa e incluídos localmente por ela (ver {@link AcslGenerator#generateAcsl}),
 * em vez de exigir um alias estático pré-existente para CADA combinação de tipos/aridade possível.
 *
 * <p>Estado estático, âmbito de UMA chamada a {@code AcslGenerator#generateAcsl}: limpo no início e
 * lido (e limpo de novo) no fim dessa mesma chamada — o pipeline processa máquinas sequencialmente,
 * não há concorrência.
 */
public final class TupleCodomainTypeRegistry {

    private static final Map<String, String> NEEDED = new LinkedHashMap<>();

    private TupleCodomainTypeRegistry() {}

    public static void clear() {
        NEEDED.clear();
    }

    /**
     * @param name nome achatado (ver {@link BxmlTypeRegistry#flattenGenericTypeExprToIdentifier})
     * @param setTupleDefinition ex.: {@code "Set<Tuple<integer, Tuple<boolean,integer>> >"}
     */
    static void register(String name, String setTupleDefinition) {
        NEEDED.putIfAbsent(name, setTupleDefinition);
    }

    public static Map<String, String> snapshotAndClear() {
        Map<String, String> copy = Map.copyOf(NEEDED);
        NEEDED.clear();
        return copy;
    }
}
