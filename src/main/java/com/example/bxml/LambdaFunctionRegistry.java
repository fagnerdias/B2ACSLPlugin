package com.example.bxml;

import java.util.ArrayList;
import java.util.List;

/**
 * Registo de funções lambda B ({@code % x.(P | E)}) extraídas durante a tradução BXML→ACSL.
 *
 * <p>Cada lambda é transformado numa declaração {@code predicate} nomeada ({@code lambda_func01},
 * {@code lambda_func02}, …) recolhida num bloco {@code axiomatic lambda_functions { … }} inserido
 * no {@code .acsl} gerado.
 *
 * <p>O registo é mutável e partilhado via {@link BxmlTranslateContext#lambdaRegistry()}.
 */
public final class LambdaFunctionRegistry {

    /** Definição de uma função lambda extraída. */
    public record LambdaEntry(
            String name,
            List<String> freeVarNames,
            List<String> boundVarNames,
            String bodyPredicate) {}

    private int counter = 0;
    private final List<LambdaEntry> entries = new ArrayList<>();

    /**
     * Regista uma nova função lambda e retorna o nome gerado ({@code lambda_funcNN}).
     *
     * @param freeVarNames  variáveis livres (parâmetros da operação referenciados no corpo)
     * @param boundVarNames variáveis ligadas pelo operador {@code %}
     * @param bodyPredicate corpo já traduzido para ACSL (predicado)
     */
    public String register(
            List<String> freeVarNames, List<String> boundVarNames, String bodyPredicate) {
        counter++;
        String name = String.format("lambda_func%02d", counter);
        entries.add(
                new LambdaEntry(
                        name,
                        List.copyOf(freeVarNames),
                        List.copyOf(boundVarNames),
                        bodyPredicate));
        return name;
    }

    public boolean isEmpty() {
        return entries.isEmpty();
    }

    /**
     * Formata o bloco {@code axiomatic lambda_functions { … }} com todos os predicados registados.
     */
    public String formatAxiomaticBlock() {
        if (entries.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        sb.append("axiomatic lambda_functions {\n");
        for (LambdaEntry e : entries) {
            sb.append("  predicate ").append(e.name()).append("(");
            List<String> params = new ArrayList<>();
            for (String v : e.freeVarNames()) params.add("integer " + v);
            for (String v : e.boundVarNames()) params.add("integer " + v);
            sb.append(String.join(", ", params));
            sb.append(") =\n    ").append(e.bodyPredicate()).append(";\n\n");
        }
        sb.append("}\n");
        return sb.toString();
    }
}
