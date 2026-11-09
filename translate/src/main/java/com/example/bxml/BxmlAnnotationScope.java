package com.example.bxml;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Onde a cláusula que está sendo traduzida vai parar, e o que isso muda na forma de um
 * identificador.
 *
 * <p>O mesmo identificador B sai diferente conforme o lugar:
 * <ul>
 *   <li>{@code x$0} (valor antes da operação) é {@code \old(x)} num contrato, mas o ACSL proíbe
 *       {@code \old} numa anotação de laço, onde a forma é {@code \at(x, Pre)};
 *   <li>um parâmetro de saída da operação é um ponteiro em C, então toda cláusula que lê o seu
 *       valor precisa de {@code *p}.
 * </ul>
 *
 * <p>Antes estas decisões eram pós-passes de texto aplicados só a algumas cláusulas. Aqui elas
 * ficam no contexto de tradução ({@link BxmlTranslateContext#annotationScope()}) e valem para toda
 * cláusula que passa pelo caso {@code Id} de {@link BxmlExpressionToAcsl#translate}.
 *
 * @param kind onde a cláusula vai parar
 * @param outputParams parâmetros de saída escalares da operação, lidos como {@code *p}
 * @param preStateNames identificadores que, mesmo sem o sufixo {@code $0}, devem ser lidos no
 *     pré-estado (ex. a variável atribuída, quando aparece no lado direito da própria atribuição)
 */
public record BxmlAnnotationScope(Kind kind, Set<String> outputParams, Set<String> preStateNames) {

    /** Destino da cláusula. */
    public enum Kind {
        /** {@code requires}/{@code ensures} de um contrato de função. */
        CONTRACT,
        /** {@code loop invariant}/{@code loop variant}/{@code loop assigns}. */
        LOOP,
        /** Corpo de uma função lógica ou axioma gerado (compreensão, SIGMA, lambda, ...). */
        HELPER_BODY,
        /** Texto do {@code ghost_operations.ci}. */
        GHOST
    }

    /** O escopo padrão: contrato, sem saídas e sem nomes de pré-estado. */
    public static final BxmlAnnotationScope CONTRACT = new BxmlAnnotationScope(Kind.CONTRACT, Set.of(), Set.of());

    public BxmlAnnotationScope {
        kind = kind == null ? Kind.CONTRACT : kind;
        outputParams = outputParams == null ? Set.of() : Set.copyOf(outputParams);
        preStateNames = preStateNames == null ? Set.of() : Set.copyOf(preStateNames);
    }

    public BxmlAnnotationScope withKind(Kind newKind) {
        return new BxmlAnnotationScope(newKind, outputParams, preStateNames);
    }

    public BxmlAnnotationScope withOutputParams(Set<String> names) {
        return new BxmlAnnotationScope(kind, names, preStateNames);
    }

    public BxmlAnnotationScope withPreStateNames(Set<String> names) {
        return new BxmlAnnotationScope(kind, outputParams, names);
    }

    /** Acrescenta {@code names} aos nomes de pré-estado já presentes. */
    public BxmlAnnotationScope plusPreStateNames(Set<String> names) {
        if (names == null || names.isEmpty()) {
            return this;
        }
        Set<String> all = new LinkedHashSet<>(preStateNames);
        all.addAll(names);
        return new BxmlAnnotationScope(kind, outputParams, all);
    }

    /**
     * Forma ACSL do uso do identificador B {@code bName}, cuja tradução sem contexto é {@code
     * acsl} (já com eventual cast, ex. {@code (integer)cc}).
     *
     * @param preState {@code true} para {@code x$0}
     */
    public String render(String bName, String acsl, boolean preState) {
        if (preState || preStateNames.contains(bName)) {
            return preStateTerm(acsl);
        }
        if (outputParams.contains(bName)) {
            return dereference(acsl);
        }
        return acsl;
    }

    /** O valor de {@code acsl} no estado de entrada da função, na forma que este escopo aceita. */
    public String preStateTerm(String acsl) {
        return kind == Kind.LOOP ? "\\at(" + acsl + ", Pre)" : "\\old(" + acsl + ")";
    }

    /** {@code *p}, mantendo um cast de prefixo do lado de fora: {@code (integer)p} → {@code (integer)*p}. */
    static String dereference(String acsl) {
        for (String cast : new String[] {"(integer)", "(boolean)"}) {
            if (acsl.startsWith(cast)) {
                return cast + "*" + acsl.substring(cast.length());
            }
        }
        return "*" + acsl;
    }
}
