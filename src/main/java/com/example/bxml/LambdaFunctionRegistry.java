package com.example.bxml;

import java.util.ArrayList;
import java.util.List;

/**
 * Registo de funções lambda B ({@code % x.(P | E)}) extraídas durante a tradução BXML→ACSL.
 *
 * <p>Cada lambda é transformada numa declaração nomeada — {@code predicate} quando o corpo é
 * booleano, ou {@code logic <tipo>} (função lógica definida, não {@code \lambda} nativo do ACSL —
 * este não compõe com o resto do sistema de tipos B2ACSL baseado em ADTs como {@code Function<A,B>})
 * quando o corpo é um valor — nomes {@code lambda_func01}, {@code lambda_func02}, … recolhidos num
 * bloco {@code axiomatic lambda_functions { … }} inserido no {@code .acsl} gerado.
 *
 * <p>O registo é mutável e partilhado via {@link BxmlTranslateContext#lambdaRegistry()}.
 */
public final class LambdaFunctionRegistry {

    /**
     * Definição de uma função lambda extraída. {@code returnType == null} para um predicado.
     *
     * @param guard predicado B do lado esquerdo do {@code |} em {@code % x.(P | E)} — domínio da
     *     lambda ({@code null}/vazio se trivial, ex. {@code \true}). Como B trata {@code %} como
     *     função PARCIAL (indefinida fora de {@code P}), quando presente a entrada é emitida como
     *     declaração sem corpo + axioma guardado ({@code P ==> nome(...) == corpo}), não como
     *     {@code nome(...) = corpo} incondicional (que seria total, errado fora de {@code P}).
     */
    public record LambdaEntry(
            String name,
            List<String> freeVarNames,
            List<String> boundVarNames,
            String body,
            String returnType,
            String guard) {}

    private int counter = 0;
    private final List<LambdaEntry> entries = new ArrayList<>();

    /**
     * Regista uma nova função lambda de corpo booleano e retorna o nome gerado
     * ({@code lambda_funcNN}), declarada como {@code predicate}.
     *
     * @param freeVarNames  variáveis livres (parâmetros da operação referenciados no corpo)
     * @param boundVarNames variáveis ligadas pelo operador {@code %}
     * @param bodyPredicate corpo já traduzido para ACSL (predicado)
     * @param guard ver {@link LambdaEntry#guard()}
     */
    public String register(
            List<String> freeVarNames, List<String> boundVarNames, String bodyPredicate, String guard) {
        return registerEntry(freeVarNames, boundVarNames, bodyPredicate, null, guard);
    }

    /**
     * Regista uma nova função lambda de corpo valorado (não-booleano) e retorna o nome gerado
     * ({@code lambda_funcNN}), declarada como {@code logic returnType name(...) = body;} (ou
     * declaração+axioma guardado se {@code guard} não for trivial) — nunca {@code \lambda} nativo
     * do ACSL, que não compõe com {@code Function<A,B>}/{@code \list} etc.
     *
     * @param returnType tipo lógico ACSL do corpo (ex.: {@code integer})
     * @param guard ver {@link LambdaEntry#guard()}
     */
    public String registerFunction(
            List<String> freeVarNames, List<String> boundVarNames, String body, String returnType,
            String guard) {
        return registerEntry(freeVarNames, boundVarNames, body, returnType, guard);
    }

    private String registerEntry(
            List<String> freeVarNames, List<String> boundVarNames, String body, String returnType,
            String guard) {
        counter++;
        String name = String.format("lambda_func%02d", counter);
        entries.add(
                new LambdaEntry(
                        name,
                        List.copyOf(freeVarNames),
                        List.copyOf(boundVarNames),
                        body,
                        returnType,
                        guard));
        return name;
    }

    public boolean isEmpty() {
        return entries.isEmpty();
    }

    public int size() {
        return entries.size();
    }

    /**
     * Formata o bloco {@code axiomatic lambda_functions { … }} com todos os predicados registados.
     */
    public String formatAxiomaticBlock() {
        return formatAxiomaticBlockFrom(0);
    }

    /**
     * Formata o bloco incluindo apenas as entradas a partir de {@code fromIndex} (exclusive das
     * anteriores, já emitidas). Retorna {@code ""} se não houver entradas novas.
     */
    public String formatAxiomaticBlockFrom(int fromIndex) {
        List<LambdaEntry> slice = entries.subList(fromIndex, entries.size());
        if (slice.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        sb.append("axiomatic lambda_functions {\n");
        for (LambdaEntry e : slice) {
            boolean isFunction = e.returnType() != null && !e.returnType().isBlank();
            String kind = isFunction ? "logic " + e.returnType() + " " : "predicate ";
            List<String> argNames = new ArrayList<>(e.freeVarNames());
            argNames.addAll(e.boundVarNames());
            List<String> typedParams = new ArrayList<>();
            for (String v : argNames) typedParams.add("integer " + v);
            String paramList = String.join(", ", typedParams);
            String call = e.name() + "(" + String.join(", ", argNames) + ")";

            boolean hasGuard = e.guard() != null && !e.guard().isBlank()
                    && !"\\true".equals(e.guard().trim());
            if (hasGuard) {
                // % é PARCIAL: declara sem corpo e restringe só dentro do domínio (guarda) — fora
                // dele nome(...) fica sem valor definido, tal como em B.
                sb.append("  ").append(kind).append(e.name()).append("(").append(paramList).append(");\n");
                sb.append("  axiom ").append(e.name()).append("_def:\n");
                sb.append("    \\forall ").append(paramList).append(";\n");
                sb.append("      (").append(e.guard()).append(") ==> (")
                        .append(call).append(isFunction ? " == " : " <==> ").append(e.body()).append(");\n\n");
            } else {
                sb.append("  ").append(kind).append(call).append(" =\n    ").append(e.body()).append(";\n\n");
            }
        }
        sb.append("}\n");
        return sb.toString();
    }
}
