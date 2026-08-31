package com.example;

import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * O mini-DSL {@code function X: contract: requires ...; ensures ...;} que o {@code -acsl-import}
 * usa para o {@code .acsl} raiz de cada máquina tem uma gramática de binder de {@code \exists}/
 * {@code \forall} restrita a tipos escalares embutidos ({@code integer}/{@code boolean}/
 * {@code real}) — um tipo definido pelo utilizador (genérico como {@code Function<A,B>}, ou uma
 * simples typedef) causa {@code [Syntax error] <Nome>.} (confirmado empiricamente até com um
 * alias trivial {@code type x = integer;}). Fora deste mini-DSL (bloco de anotação padrão, como
 * em {@code ghost_operations.ci}, ou no {@code merged_code.c} final) a gramática completa do ACSL
 * aceita normalmente.
 *
 * <p>Em vez de inline no {@code ensures}, uma cláusula assim é substituída por uma chamada a um
 * predicado nulário "marcador" (nome único por operação, declarado trivialmente no próprio
 * {@code .acsl} — ver {@code AcslGenerator#appendPreambleAndConstantsBlocks}). O texto REAL da
 * cláusula é transportado como uma SEGUNDA função ghost "gêmea" (mesmo {@code ensures}, nome
 * {@link #markerPredicateName}) dentro do próprio {@code ghost_operations.ci} — ver {@code
 * GhostOperationsCiGenerator}, que a regista logo a seguir à função ghost normal da operação. Um
 * passo de pós-processamento (ver {@code B2ACSLPipeline#spliceAnySubMarkerSpecsFromGhostCi}),
 * executado sobre {@code merged_code.c} DEPOIS do {@code -acsl-import} ter sucesso mas ANTES da
 * remoção do prefixo {@code dummy_}, troca cada chamada ao marcador pelo {@code ensures} dessa
 * função gêmea (ainda com {@code dummy_}/{@code DRelation<A,B>}) — a mesma limpeza global que já
 * corrige o resto do ficheiro trata esta cópia recém-inserida no mesmo passo.
 */
public final class AnySubMarkerSpec {

    private AnySubMarkerSpec() {}

    private static final Set<String> SCALAR_QUANTIFIER_TYPES = Set.of("integer", "boolean", "real");

    private static final Pattern NON_SCALAR_QUANTIFIER =
            Pattern.compile("^\\\\(?:exists|forall)\\s+([A-Za-z_]\\w*)\\b");

    /**
     * {@code true} se {@code clause} for uma cláusula {@code \exists}/{@code \forall} cujo PRIMEIRO
     * binder tem um tipo que não é {@code integer}/{@code boolean}/{@code real} — inaceitável no
     * mini-DSL {@code function X: contract:} do lado real (não-ghost).
     */
    public static boolean isNonScalarQuantifiedClause(String clause) {
        if (clause == null) {
            return false;
        }
        Matcher m = NON_SCALAR_QUANTIFIER.matcher(clause.trim());
        return m.find() && !SCALAR_QUANTIFIER_TYPES.contains(m.group(1));
    }

    /**
     * Prefixo de todo nome de marcador/função ghost gêmea — usado por {@code B2ACSLPipeline
     * #spliceAnySubMarkerSpecsFromGhostCi} para localizar a função ghost gêmea em {@code
     * ghost_operations.ci} (por {@code void any_sub_spec__<op>(...)}) e o marcador correspondente
     * em {@code merged_code.c} (por {@code ensures any_sub_spec__<op>;}).
     */
    public static final String MARKER_PREFIX = "any_sub_spec__";

    /** Nome do predicado nulário marcador / da função ghost gêmea para a operação {@code opSlug}. */
    public static String markerPredicateName(String opSlug) {
        return MARKER_PREFIX + opSlug;
    }

    /**
     * Chamada ACSL ao marcador, para usar no lugar da cláusula original em {@code ensures} — sem
     * parênteses, como qualquer predicado nulário no mini-DSL {@code function X: contract:} (ex.
     * {@code Registro_invariant}, {@code ghost__remove}; {@code predicate P();}/{@code P()} com
     * parênteses vazios explícitos é rejeitado pelo mesmo mini-DSL restrito).
     */
    public static String markerCall(String opSlug) {
        return markerPredicateName(opSlug);
    }
}
