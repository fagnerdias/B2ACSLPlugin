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
 * <p>Estado de instância, âmbito de UMA chamada a {@code AcslGenerator#generateAcsl}: uma instância
 * nova é criada e vinculada (ver {@link #bindForCurrentCall}) no início dessa chamada, e lida (e
 * limpa) uma única vez no fim, via {@link #snapshotAndClear()} — o pipeline processa máquinas
 * sequencialmente, não há concorrência. Todo {@link BxmlTypeRegistry} construído durante essa
 * chamada (em qualquer classe do pacote {@code bxml}) captura, no momento em que é criado, a
 * instância vinculada por {@link #bindForCurrentCall} — reproduzindo exatamente o âmbito que antes
 * era dado por um {@code Map} estático único, mas agora sobre um objeto instanciável.
 */
public final class TupleCodomainTypeRegistry {

    private final Map<String, String> needed = new LinkedHashMap<>();

    private static TupleCodomainTypeRegistry current;

    public TupleCodomainTypeRegistry() {}

    /**
     * Vincula {@code instance} como o registo ativo para a chamada em curso a {@code
     * AcslGenerator#generateAcsl}; chamado exclusivamente por {@code AcslGenerator} no início dessa
     * chamada (substitui o antigo {@code clear()} estático: em vez de esvaziar um mapa partilhado,
     * troca a instância ativa por uma nova e vazia).
     */
    public static void bindForCurrentCall(TupleCodomainTypeRegistry instance) {
        current = instance;
    }

    /**
     * Registo ativo para a chamada em curso, ou um registo novo e descartável se nenhum foi
     * vinculado ainda (uso fora do pipeline principal do {@code AcslGenerator}).
     */
    static TupleCodomainTypeRegistry current() {
        return current != null ? current : new TupleCodomainTypeRegistry();
    }

    /**
     * @param name nome achatado (ver {@link BxmlTypeRegistry#flattenGenericTypeExprToIdentifier})
     * @param setTupleDefinition ex.: {@code "Set<Tuple<integer, Tuple<boolean,integer>> >"}
     */
    void register(String name, String setTupleDefinition) {
        needed.putIfAbsent(name, setTupleDefinition);
    }

    public Map<String, String> snapshotAndClear() {
        Map<String, String> copy = Map.copyOf(needed);
        needed.clear();
        return copy;
    }
}
