package com.example.bxml;

import java.util.ArrayList;
import java.util.List;

/**
 * Registo de funções lambda B ({@code % x.(P | E)}) extraídas durante a tradução BXML→ACSL.
 *
 * <p>Cada lambda é transformada numa declaração nomeada — {@code lambda_func01}, {@code
 * lambda_func02}, … (contador único, partilhado pelas três formas abaixo, na ordem de registo) —
 * recolhida num bloco {@code axiomatic lambda_functions { … }} inserido no {@code .acsl} gerado.
 * Nunca {@code \lambda} nativo do ACSL: este não compõe com o resto do sistema de tipos B2ACSL
 * baseado em ADTs ({@code Function<A,B>}, {@code \list}, …) — não pode ser passado a funções da lib
 * como {@code restrict_front}/{@code function_to_list}, que esperam esses ADTs, não uma função ACSL
 * nativa de primeira classe.
 *
 * <p>Três formas, conforme o domínio {@code D} de {@code % x.(x : D | E)}:
 * <ul>
 *   <li><b>predicate</b> — corpo booleano, {@code D} não-intervalo/múltiplas variáveis ligadas
 *       (modelo de mapa): {@code predicate nome(params) = corpo;}, ou declaração + axioma guardado
 *       se {@code D} não for {@code \true} trivial (função PARCIAL de B — indefinida fora de
 *       {@code D}).</li>
 *   <li><b>function</b> — como acima, mas corpo valorado: {@code logic tipo nome(params) = corpo;}.</li>
 *   <li><b>sequence builder</b> — {@code D = lo..hi} (uma só variável ligada, domínio intervalo
 *       literal): {@code D} é uma sequência B, modelada como função lógica RECURSIVA que constrói
 *       um {@code \list<T>} elemento a elemento via {@code \concat}, acompanhada de dois lemas-ponte
 *       ({@code _length}, {@code _nth}) sem os quais o WP não consegue relacionar a recursão com
 *       igualdade de listas.</li>
 * </ul>
 *
 * <p>O registo é mutável e partilhado via {@link BxmlTranslateContext#lambdaRegistry()}.
 */
public final class LambdaFunctionRegistry {

    /**
     * Definição de uma função lambda extraída.
     *
     * @param freeVarTypes tipo ACSL de cada entrada de {@code freeVarNames} (mesma ordem/tamanho);
     *     {@code null}/vazio em qualquer posição cai para {@code integer}.
     * @param body para predicate/function: o corpo completo; para sequence builder: o corpo com a
     *     variável ligada substituída pelo parâmetro de recursão {@code lo} (ex. {@code
     *     function_apply(array, (lo - 1))}).
     * @param returnType {@code null} ⇒ predicate; senão o tipo de retorno (function) ou o tipo do
     *     ELEMENTO {@code T} de {@code \list<T>} (sequence builder).
     * @param guard predicado de domínio B (predicate/function apenas) — {@code null}/{@code \true}
     *     ⇒ definição incondicional; senão declaração sem corpo + axioma guardado (função PARCIAL).
     * @param sequenceBuilder {@code true} ⇒ forma "sequence builder" (recursiva sobre {@code \list}).
     * @param bodyForLoPlusK sequence builder apenas: corpo com a variável ligada substituída por
     *     {@code (lo + k)}, usado no lema {@code _nth}.
     */
    public record LambdaEntry(
            String name,
            List<String> freeVarNames,
            List<String> freeVarTypes,
            List<String> boundVarNames,
            String body,
            String returnType,
            String guard,
            boolean sequenceBuilder,
            String bodyForLoPlusK) {}

    private int counter = 0;
    private final List<LambdaEntry> entries = new ArrayList<>();

    /**
     * Regista uma nova função lambda de corpo booleano (modelo de mapa) e retorna o nome gerado
     * ({@code lambda_funcNN}), declarada como {@code predicate}.
     *
     * @param freeVarNames  variáveis livres (parâmetros da operação referenciados no corpo/guarda)
     * @param freeVarTypes  tipo ACSL de cada uma (mesma ordem); {@code null} ⇒ {@code integer}
     * @param boundVarNames variáveis ligadas pelo operador {@code %}
     * @param bodyPredicate corpo já traduzido para ACSL (predicado)
     * @param guard ver {@link LambdaEntry#guard()}
     */
    public String register(
            List<String> freeVarNames, List<String> freeVarTypes, List<String> boundVarNames,
            String bodyPredicate, String guard) {
        return registerEntry(
                freeVarNames, freeVarTypes, boundVarNames, bodyPredicate, null, guard, false, null);
    }

    /**
     * Regista uma nova função lambda de corpo valorado (modelo de mapa) e retorna o nome gerado
     * ({@code lambda_funcNN}), declarada como {@code logic returnType nome(...) = corpo;} (ou
     * declaração+axioma guardado se {@code guard} não for trivial).
     *
     * @param returnType tipo lógico ACSL do corpo (ex.: {@code integer})
     * @param guard ver {@link LambdaEntry#guard()}
     */
    public String registerFunction(
            List<String> freeVarNames, List<String> freeVarTypes, List<String> boundVarNames,
            String body, String returnType, String guard) {
        return registerEntry(
                freeVarNames, freeVarTypes, boundVarNames, body, returnType, guard, false, null);
    }

    /**
     * Regista uma nova função lambda como "sequence builder" (domínio {@code lo..hi}, uma variável
     * ligada) — função lógica RECURSIVA que constrói {@code \list<elementType>} via {@code \concat},
     * mais os lemas-ponte {@code _length}/{@code _nth}. Retorna o nome gerado.
     *
     * @param freeVarNames  variáveis livres (ex.: {@code array})
     * @param freeVarTypes  tipo ACSL de cada uma (ex.: {@code Function_int_int})
     * @param elementType tipo do elemento da lista (tipo do corpo {@code E})
     * @param bodyForLo corpo com a variável ligada substituída por {@code lo}
     * @param bodyForLoPlusK corpo com a variável ligada substituída por {@code (lo + k)}
     */
    public String registerSequenceBuilder(
            List<String> freeVarNames, List<String> freeVarTypes, String elementType,
            String bodyForLo, String bodyForLoPlusK) {
        return registerEntry(
                freeVarNames, freeVarTypes, List.of(), bodyForLo, elementType, null, true, bodyForLoPlusK);
    }

    private String registerEntry(
            List<String> freeVarNames, List<String> freeVarTypes, List<String> boundVarNames,
            String body, String returnType, String guard, boolean sequenceBuilder,
            String bodyForLoPlusK) {
        counter++;
        String name = String.format("lambda_func%02d", counter);
        entries.add(
                new LambdaEntry(
                        name,
                        List.copyOf(freeVarNames),
                        freeVarTypes == null ? List.of() : List.copyOf(freeVarTypes),
                        List.copyOf(boundVarNames),
                        body,
                        returnType,
                        guard,
                        sequenceBuilder,
                        bodyForLoPlusK));
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
            if (e.sequenceBuilder()) {
                appendSequenceBuilder(sb, e);
                continue;
            }
            boolean isFunction = e.returnType() != null && !e.returnType().isBlank();
            String kind = isFunction ? "logic " + e.returnType() + " " : "predicate ";
            List<String> argNames = new ArrayList<>(e.freeVarNames());
            argNames.addAll(e.boundVarNames());
            String paramList = String.join(", ", typedParams(e.freeVarNames(), e.freeVarTypes(), e.boundVarNames()));
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

    private static List<String> typedParams(
            List<String> freeVarNames, List<String> freeVarTypes, List<String> boundVarNames) {
        List<String> out = new ArrayList<>();
        for (int i = 0; i < freeVarNames.size(); i++) {
            out.add(freeVarType(freeVarTypes, i) + " " + freeVarNames.get(i));
        }
        for (String v : boundVarNames) {
            out.add("integer " + v);
        }
        return out;
    }

    private static String freeVarType(List<String> freeVarTypes, int index) {
        if (freeVarTypes == null || index >= freeVarTypes.size()) return "integer";
        String t = freeVarTypes.get(index);
        return t == null || t.isBlank() ? "integer" : t;
    }

    /**
     * Emite a forma "sequence builder": função lógica recursiva {@code \list<T>} + lemas-ponte
     * {@code _length}/{@code _nth} — ver {@link #registerSequenceBuilder}.
     */
    private static void appendSequenceBuilder(StringBuilder sb, LambdaEntry e) {
        List<String> typedFree = typedParams(e.freeVarNames(), e.freeVarTypes(), List.of());
        String freeParamsPrefix = typedFree.isEmpty() ? "" : String.join(", ", typedFree) + ", ";
        String freeArgsPrefix = e.freeVarNames().isEmpty() ? "" : String.join(", ", e.freeVarNames()) + ", ";
        String elementType = (e.returnType() == null || e.returnType().isBlank()) ? "integer" : e.returnType();
        String name = e.name();

        sb.append("  logic \\list<").append(elementType).append("> ").append(name)
                .append("(").append(freeParamsPrefix).append("integer lo, integer hi) =\n")
                .append("      lo > hi ? \\Nil\n")
                .append("              : \\concat([| ").append(e.body()).append(" |],\n")
                .append("                        ").append(name).append("(").append(freeArgsPrefix)
                .append("lo + 1, hi));\n\n");

        sb.append("  lemma ").append(name).append("_length:\n")
                .append("    \\forall ").append(freeParamsPrefix).append("integer lo, hi;\n")
                .append("      \\length(").append(name).append("(").append(freeArgsPrefix)
                .append("lo, hi)) == (hi < lo ? 0 : hi - lo + 1);\n\n");

        sb.append("  lemma ").append(name).append("_nth:\n")
                .append("    \\forall ").append(freeParamsPrefix).append("integer lo, hi, k;\n")
                .append("      0 <= k <= hi - lo ==>\n")
                .append("        \\nth(").append(name).append("(").append(freeArgsPrefix)
                .append("lo, hi), k) == ").append(e.bodyForLoPlusK()).append(";\n\n");
    }
}
