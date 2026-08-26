package com.example.bxml;

import java.util.ArrayList;
import java.util.List;

import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * Traduz sub-árvores de expressão BXML ({@code Exp}) para texto ACSL usando símbolos alinhados a {@code ACSL_Lib}
 * (ex.: {@code empty}, {@code set_union} em {@code set_functions/}). Para sequências ({@code \\list}), o
 * operador unário B {@code size} → {@code \\length(...)} na especificação ACSL (ex.
 * {@code size(myseq) > 0} → {@code \\length(myseq) > 0}).
 *
 * @see <a href="https://www.atelierb.eu/wp-content/uploads/2023/10/bxml-1.0.html">BXML 1.0</a>
 */
public final class BxmlExpressionToAcsl {

    private BxmlExpressionToAcsl() {}

    /**
     * Traduz uma expressão B (filho de {@code Values} ou {@code Variables}).
     */
    /**
     * Igualdade ACSL: {@code equals} para dois valores conjunto ({@code Set<…>}, {@code Relation_*},
     * expressões como {@code ran(…)}, {@code empty}, …); {@code ==} para escalares (ex.: inteiro,
     * {@code card(…)}).
     */
    public static String formatEquality(Element leftExp, Element rightExp, BxmlTranslateContext ctx) {
        String functionOverride = formatFunctionOverrideAssignment(leftExp, rightExp, ctx);
        if (functionOverride != null) {
            return functionOverride;
        }
        String overwriteWithLambda = formatOverwriteWithLambdaAssignment(leftExp, rightExp, ctx);
        if (overwriteWithLambda != null) {
            return overwriteWithLambda;
        }
        if (isListValued(leftExp, ctx) && isRelationOrFunctionValued(rightExp, ctx)) {
            return translate(rightExp, ctx)
                    + " == list_to_function("
                    + translate(leftExp, ctx)
                    + ")";
        }
        if (isListValued(rightExp, ctx) && isRelationOrFunctionValued(leftExp, ctx)) {
            return translate(leftExp, ctx)
                    + " == list_to_function("
                    + translate(rightExp, ctx)
                    + ")";
        }
        String l = translate(leftExp, ctx);
        String r = translate(rightExp, ctx);
        if ("Boolean_Literal".equals(rightExp.getLocalName())) {
            r = booleanLiteralRhsForVariable(leftExp, rightExp.getAttribute("value"), ctx);
        } else if ("Boolean_Literal".equals(leftExp.getLocalName())) {
            l = booleanLiteralRhsForVariable(rightExp, leftExp.getAttribute("value"), ctx);
        }
        if (isSetValued(leftExp, ctx) && isSetValued(rightExp, ctx)) {
            return "equals(" + l + ", " + r + ")";
        }
        return l + " == " + r;
    }

    /**
     * B açúcar de atribuição por sobrescrita relacional {@code f(x) := y} → {@code equals(f,
     * overwrite(f, singleton(couple(x, y))))} (ACSL_Lib/relation_functions/overwrite.acsl), em vez
     * do mais fraco {@code function_apply(f, x) == y} (que nada diz sobre {@code f} fora de {@code
     * x}). Só se aplica quando o LHS é ele próprio uma aplicação funcional ({@code Binary_Exp
     * op="("}); {@code null} caso contrário, para {@link #formatEquality} cair no caminho genérico.
     *
     * <p>O "f" repetido (uma vez como LHS de {@code equals}, outra dentro de {@code overwrite}) é
     * texto ainda não distinguido entre pré/pós-estado — quem chama {@link #formatEquality} para
     * este padrão de atribuição (ex.: {@code BxmlInitialisationTranslator#parseAssignementSub}) já
     * embrulha a ocorrência de {@code f} usada como RHS em {@code \old(...)} via {@code
     * wrapLhsVarsInRhsWithOld}, desde que {@code f} entre no conjunto de nomes LHS coletado
     * (função-aplicação como alvo de atribuição também precisa ser reconhecida lá).
     */
    private static String formatFunctionOverrideAssignment(
            Element leftExp, Element rightExp, BxmlTranslateContext ctx) {
        if (leftExp == null || rightExp == null || !"Binary_Exp".equals(leftExp.getLocalName())) {
            return null;
        }
        if (!isFunctionApplicationOp(leftExp.getAttribute("op"))) {
            return null;
        }
        Element[] pair = twoDirectExpChildren(leftExp);
        if (pair[0] == null || pair[1] == null) {
            return null;
        }
        String f = translate(pair[0], ctx);
        String x = translate(pair[1], ctx);
        String y = translate(rightExp, ctx);
        return "equals(" + f + ", overwrite(" + f + ", singleton(couple(" + x + ", " + y + "))))";
    }

    /**
     * B {@code V := W <+ %z.(z : D | E(z))} — sobreposição relacional cujo lado direito de {@code <+}
     * é um lambda CRU (não uma variável/expressão já calculada). O caminho genérico ({@code
     * translateBinary}'s {@code <+} → {@code overwrite}, mais {@link LambdaFunctionRegistry}) produz
     * {@code equals(V, overwrite(W, lambda_funcNN(z)))} — semanticamente errado de DUAS formas: (1)
     * {@code z} fica por ligar fora de um quantificador (sem sentido); (2) a axiomática de {@code
     * lambda_funcNN} é global (fora do contrato desta operação) e por isso NUNCA pode levar {@code
     * \old(...)} — uma referência a estado da máquina no corpo {@code E(z)} (ex.: a própria {@code W}
     * sendo actualizada) fica presa ao pós-estado sempre que {@code lambda_funcNN(z)} for chamado
     * dentro de um {@code ensures}, mesmo que semanticamente devesse ler o pré-estado (RHS de B usa
     * sempre pré-estado). {@code wrapLhsVarsInRhsWithOld} (pós-processamento textual do chamador,
     * ver {@link BxmlInitialisationTranslator}) não alcança isto: o texto gerado é só {@code
     * "lambda_funcNN(z)"}, sem a substring do nome da variável em lado nenhum para envolver.
     *
     * <p>Em vez disso, reescreve directamente como uma única cláusula {@code \forall} pontual (o
     * mesmo que {@code overwrite}'s próprio axioma expressa, sem precisar de construir um
     * valor-relação):
     * <pre>
     *   \forall T z; function_apply(V, z) == (Q(z) ? E(z) : function_apply(W, z))
     * </pre>
     * com {@code Q}/{@code E}/{@code W} traduzidos mas SEM {@code \old(...)} aplicado aqui: o
     * texto resultante é propositadamente uma única igualdade de topo ({@code " == "} uma só vez,
     * logo após {@code function_apply(V, z)}), a MESMA forma que {@code wrapLhsVarsInRhsWithOld}
     * (pós-processamento textual do chamador — ver {@link BxmlInitialisationTranslator}, reusado
     * para operações normais via {@code appendEnsuresFromBody}) já espera de QUALQUER equality
     * gerada por {@link #formatEquality}: encontra o {@code " == "} de topo e envolve tudo à
     * direita em {@code \old(...)} para os nomes atribuídos nesta atribuição (aqui, {@code V}).
     * Uma primeira tentativa aplicava {@code \old(...)} aqui directamente com DUAS cláusulas {@code
     * \forall} separadas por {@code &&} — quebrado: o texto tinha vários {@code " == "} (um por
     * cláusula), e {@code wrapLhsVarsInRhsWithOld} corta ingenuamente no PRIMEIRO que encontra
     * (sem contar parênteses), envolvendo em {@code \old(...)} tudo o resto incluindo o LHS da
     * SEGUNDA cláusula (que não devia — representa o pós-estado sendo definido) e re-envolvendo o
     * que já tinha sido embrulhado à mão ({@code \old(\old(...))}). Uma única igualdade de topo
     * evita o problema por construção, em vez de replicar/contornar essa lógica frágil aqui.
     *
     * <p>Só dispara para uma única variável ligada; formas com mais variáveis ligadas ou domínio
     * mais complexo caem no caminho genérico (não é meta cobrir cada forma possível de B aqui — é
     * corrigir o padrão concreto de sobreposição relacional por lambda, que é o único caso onde o
     * caminho genérico produz texto sem sentido).
     */
    private static String formatOverwriteWithLambdaAssignment(
            Element leftExp, Element rightExp, BxmlTranslateContext ctx) {
        if (leftExp == null || rightExp == null || !"Binary_Exp".equals(rightExp.getLocalName())) {
            return null;
        }
        if (!"<+".equals(rightExp.getAttribute("op"))) {
            return null;
        }
        Element[] pair = twoDirectExpChildren(rightExp);
        if (pair[0] == null || pair[1] == null || !"Quantified_Exp".equals(pair[1].getLocalName())) {
            return null;
        }
        Element lambda = pair[1];
        if (!"%".equals(lambda.getAttribute("type"))) {
            return null;
        }
        Element varsEl = childByLocalName(lambda, "Variables");
        if (varsEl == null) {
            return null;
        }
        java.util.List<String> boundVarNames = new java.util.ArrayList<>();
        java.util.List<Integer> boundVarTyprefs = new java.util.ArrayList<>();
        NodeList nl = varsEl.getChildNodes();
        for (int i = 0; i < nl.getLength(); i++) {
            Node n = nl.item(i);
            if (n.getNodeType() != Node.ELEMENT_NODE) continue;
            Element idEl = (Element) n;
            if (!"Id".equals(idEl.getLocalName())) continue;
            boundVarNames.add(idEl.getAttribute("value"));
            String tr = idEl.getAttribute("typref");
            boundVarTyprefs.add(tr.isBlank() ? -1 : Integer.parseInt(tr.trim()));
        }
        if (boundVarNames.size() != 1) {
            return null;
        }
        String z = boundVarNames.get(0);
        int zTypref = boundVarTyprefs.get(0);

        Element guardEl = childByLocalName(lambda, "Pred");
        Element guardPred = guardEl != null ? firstExpChild(guardEl) : null;
        Element bodyEl = childByLocalName(lambda, "Body");
        Element bodyExp = bodyEl != null ? firstExpChild(bodyEl) : null;
        if (guardPred == null || bodyExp == null) {
            return null;
        }

        String lhsText = translate(leftExp, ctx);
        String wText = translate(pair[0], ctx);
        String qText = BxmlPredicateToAcsl.translatePropertyPred(guardPred, ctx);
        String eText = translate(bodyExp, ctx);
        String zType = ctx.types().acslVariableLogicTypeFromTypref(zTypref);

        String result = "\\forall " + zType + " " + z + "; function_apply(" + lhsText + ", " + z + ") == ("
                + qText + " ? (" + eText + ") : function_apply(" + wText + ", " + z + "))";
        // qText pode ser uma chamada a predicado da lib (ex. belongs) — inválido em posição de
        // termo (condição de ternário). Reescreve AQUI, enquanto o resultado ainda é a string
        // "\forall ...; X == (P?A:B)" completa e isolada que GhostOperationsCiGenerator's
        // rewriteIntegerTernaryPredicateConditionForGhost espera — se deixado para o chamador
        // (ex. translateAnySubAsExists, quando este V:=W<+lambda é a expressão definidora de um
        // alias ANY eliminado), este texto vira só UM conjunto entre vários unidos por "&&", e o
        // reescritor (que exige a string INTEIRA começar com "\forall ") nunca mais o encontra —
        // "symbol dummy_belongs is a predicate, not a function" no lado ghost, só descoberto ao
        // corrigir RulerOfTheSeas's InvestOnResources (primeiro caso de <+lambda como expressão
        // definidora de um ANY, em vez de RHS direto de uma atribuição de topo).
        return GhostOperationsCiGenerator.rewriteIntegerTernaryPredicateConditionForGhost(result);
    }

    /**
     * Expressão cuja sorte lógica é conjunto (não inteiro nem lista) — usado para escolher
     * {@code equals} vs {@code ==}.
     */
    public static boolean isSetValued(Element exp, BxmlTranslateContext ctx) {
        if (exp == null) return false;
        String ln = exp.getLocalName();
        return switch (ln) {
            case "Id" -> isSetValuedId(exp, ctx);
            case "Unary_Exp" -> {
                String op = exp.getAttribute("op");
                // "rel"/"fnc" (B: transforma relação<->função, ver relation_functions/rel.acsl e
                // fnc.acsl) caem no fallback genérico "opname(arg)" de translateUnary — nunca
                // precisaram de um case próprio ali para GERAR o texto certo, mas isSetValued não
                // tem fallback genérico (é uma lista fechada) e sem esta entrada
                // translateEqualityComparison nunca reconhecia "rel(FF) == RC"/"fnc(RC) == FF"
                // como comparação entre dois valores Set-valued, caindo no "==" nativo em vez de
                // equals(...) — ambos Relation<A,B>/Function<A,B> (Set<Tuple<A,B>>), == não
                // compara extensionalmente.
                yield "ran".equals(op) || "dom".equals(op) || "id".equals(op)
                        || "closure".equals(op) || "closure1".equals(op)
                        || "union".equals(op) || "inter".equals(op)
                        || "rel".equals(op) || "fnc".equals(op);
            }
            case "EmptySet" -> true;
            case "Quantified_Set" -> true;
            case "Binary_Exp" -> {
                String op = exp.getAttribute("op");
                yield isSetUnion(op)
                        || isSetIntersection(op)
                        || isSetDifferenceOp(op)
                        || isCartesianProduct(op)
                        || isDomainRestrictionOp(op)
                        || isRangeRestrictionOp(op)
                        || isDomainSubtractionOp(op)
                        || isRangeSubtractionOp(op)
                        || isIntervalBinaryExp(exp)
                        || isDirectProductOp(op)
                        || "prj1".equals(op) || "prj2".equals(op)
                        || "<+".equals(op);
            }
            case "Nary_Exp" -> "{".equals(exp.getAttribute("op"));
            default -> false;
        };
    }

    /**
     * Variável de outra máquina (ex. {@code ss} no invariante de refinamento) não entra em
     * {@link BxmlTranslateContext#variableLogicTypes()}; usa-se então o {@code typref} do {@code Id} no BXML.
     * Se a variável já está no mapa, usa-se apenas esse tipo (o typref do elemento BXML pode vir de
     * uma implementação com numeração diferente da máquina abstrata usada no ctx).
     */
    private static boolean isSetValuedId(Element idEl, BxmlTranslateContext ctx) {
        String name = idEl.getAttribute("value");
        String knownType = ctx.variableLogicTypes().get(name);
        if (knownType != null) {
            return isSetLikeVariableType(knownType);
        }
        String trAttr = idEl.getAttribute("typref");
        if (trAttr == null || trAttr.isBlank()) {
            return false;
        }
        try {
            int tr = Integer.parseInt(trAttr.trim());
            String inferred = ctx.types().acslVariableLogicTypeFromTypref(tr);
            return isSetLikeVariableType(inferred);
        } catch (NumberFormatException e) {
            return false;
        }
    }

    /**
     * Como {@link #isSetValuedId}, mas a partir só do nome (sem o {@code Id} original do BXML) —
     * usado por quem só tem a variável já resolvida como {@code String} (ex.: {@link
     * BxmlInitialisationTranslator}'s condições de "frame" para variáveis intocadas num ramo de
     * {@code If_Sub}). Sem {@code typref} disponível, cai em {@code false} (equalidade escalar) se
     * a variável não estiver em {@link BxmlTranslateContext#variableLogicTypes()} — mesmo padrão de
     * "resposta autoritativa só quando presente" usado nos outros pontos deste ficheiro.
     */
    public static boolean isSetValuedVariableName(String name, BxmlTranslateContext ctx) {
        if (name == null || ctx == null) return false;
        return isSetLikeVariableType(ctx.variableLogicTypes().get(name));
    }

    private static boolean isSetLikeVariableType(String t) {
        if (t == null || t.isBlank()) return false;
        if (t.startsWith("Set<")) return true;
        return t.startsWith("Relation_") || t.startsWith("Relation<")
                || t.startsWith("Function_") || t.startsWith("Function<");
    }

    /**
     * Expressão cuja sorte em ACSL é lista ({@code \\list<…>}) — para {@code Unary_Exp size} →
     * {@code \\length(expr)} (invariantes, pré/pós-condições, etc.).
     */
    public static boolean isListValued(Element exp, BxmlTranslateContext ctx) {
        if (exp == null || ctx == null) {
            return false;
        }
        String ln = exp.getLocalName();
        return switch (ln) {
            case "Id" -> isListValuedId(exp, ctx);
            case "EmptySeq" -> true;
            case "Binary_Exp" -> {
                String bOp = exp.getAttribute("op");
                yield isSequenceAppendOp(bOp) || isSequencePrependOp(bOp);
            }
            default -> false;
        };
    }

    private static boolean isListValuedId(Element idEl, BxmlTranslateContext ctx) {
        String name = idEl.getAttribute("value");
        String t = ctx.variableLogicTypes().get(name);
        if (t == null) {
            // Variável de OUTRA máquina (ex.: "contents" de Fifo, referenciada num invariante de
            // colagem de RobustFifo): variableLogicTypes() só cobre a própria máquina + cadeia de
            // refinamento; sem este fallback, uma \list cross-machine caía sempre no ramo typref
            // abaixo (tipo B cru POW(INTEGER*T), indistinguível de relação) e o "=" gerava
            // list_to_function(...) ao contrário, produzindo "incompatible types" no Frama-C.
            t = ctx.crossMachineVariableLogicTypes().get(name);
        }
        // t != null é uma resposta AUTORITATIVA (inclui a deteção de "v : seq(T)"/"v : iseq(T)" do
        // invariante, que o fallback por typref abaixo NÃO conhece — esse fallback só olha o tipo B
        // cru, onde uma sequência é POW(INTEGER*T), indistinguível de uma relação genérica). Confiar
        // nela SEMPRE que presente (mesmo quando diz "não é lista") evita cair no fallback errado.
        if (t != null) {
            return t.startsWith("\\list");
        }
        String trAttr = idEl.getAttribute("typref");
        if (trAttr == null || trAttr.isBlank()) {
            return false;
        }
        try {
            int tr = Integer.parseInt(trAttr.trim());
            String inferred = ctx.types().acslVariableLogicTypeFromTypref(tr);
            return inferred != null && inferred.startsWith("\\list");
        } catch (NumberFormatException e) {
            return false;
        }
    }

    /**
     * Valor relação/função parcial em ACSL ({@code Relation_*}, {@code Function_*}) ou restrição de
     * domínio B {@code <|} — para igualdade com {@code \\list} via {@code list_to_function}.
     */
    public static boolean isRelationOrFunctionValued(Element exp, BxmlTranslateContext ctx) {
        if (exp == null || ctx == null) {
            return false;
        }
        String ln = exp.getLocalName();
        return switch (ln) {
            case "Id" -> isRelationOrFunctionValuedId(exp, ctx);
            case "Binary_Exp" -> isDomainRestrictionOp(exp.getAttribute("op"));
            default -> false;
        };
    }

    private static boolean isRelationLikeVariableType(String t) {
        if (t == null || t.isBlank()) {
            return false;
        }
        return t.startsWith("Relation_") || t.startsWith("Function_");
    }

    private static boolean isRelationOrFunctionValuedId(Element idEl, BxmlTranslateContext ctx) {
        String name = idEl.getAttribute("value");
        String t = ctx.variableLogicTypes().get(name);
        if (t == null) {
            // Variável de OUTRA máquina — ver comentário análogo em isListValuedId.
            t = ctx.crossMachineVariableLogicTypes().get(name);
        }
        // t != null é uma resposta AUTORITATIVA (ver comentário análogo em isListValuedId) — sem
        // isto, uma variável \list<...> conhecida (ex.: "queue : seq(ELEM)") caía no fallback por
        // typref, que via o tipo B cru POW(INTEGER*INTEGER) (a codificação de "seq" em B) e
        // classificava erradamente "queue" como relação — acionando o wrap list_to_function(...)
        // errado em "queue == queue <- ee" e gerando "incompatible types DRelation<...> and
        // \list<integer>" no Frama-C.
        if (t != null) {
            return isRelationLikeVariableType(t);
        }
        String trAttr = idEl.getAttribute("typref");
        if (trAttr == null || trAttr.isBlank()) {
            return false;
        }
        try {
            int tr = Integer.parseInt(trAttr.trim());
            String inferred = ctx.types().acslVariableLogicTypeFromTypref(tr);
            return isRelationLikeVariableType(inferred);
        } catch (NumberFormatException e) {
            return false;
        }
    }

    /** Intervalo B {@code a..b} em {@code Binary_Exp} (conjunto de inteiros). */
    public static boolean isIntervalBinaryExp(Element e) {
        if (e == null || !"Binary_Exp".equals(e.getLocalName())) {
            return false;
        }
        String op = e.getAttribute("op");
        return "..".equals(op == null ? "" : op.trim());
    }

    /**
     * Conjunto nomeado {@code set_comprehension_k} para intervalo ou {@code Quantified_Set} registado;
     * caso contrário delega em {@link #translate}.
     */
    public static String intervalOrSetComprehensionRef(Element e, BxmlTranslateContext ctx) {
        if (e == null) {
            return "/* null */";
        }
        if (isIntervalBinaryExp(e)) {
            Element[] pair = twoDirectExpChildren(e);
            if (pair[0] != null && pair[1] != null) {
                return "interval_set(" + translate(pair[0], ctx) + ", " + translate(pair[1], ctx) + ")";
            }
        }
        if ("Quantified_Set".equals(e.getLocalName())) {
            String n = ctx.comprehensions().referenceName(ctx.machineName(), e, ctx);
            return n != null ? n : translate(e, ctx);
        }
        return translate(e, ctx);
    }

    /** Tipo conjunto de funções B {@code S --> T} em {@code Binary_Exp}. */
    public static boolean isFunctionArrowType(Element e) {
        if (e == null || !"Binary_Exp".equals(e.getLocalName())) {
            return false;
        }
        return isFunctionArrowOp(e.getAttribute("op"));
    }

    private static boolean isFunctionArrowOp(String op) {
        if (op == null) {
            return false;
        }
        String o = op.trim();
        return "-->".equals(o) || "--&gt;".equals(o);
    }

    /** Tipo conjunto de funções parciais B {@code S +-> T} em {@code Binary_Exp}. */
    public static boolean isPartialFunctionArrowType(Element e) {
        if (e == null || !"Binary_Exp".equals(e.getLocalName())) {
            return false;
        }
        String o = e.getAttribute("op");
        if (o == null) return false;
        o = o.trim();
        return "+->".equals(o) || "+-&gt;".equals(o);
    }

    /** Tipo conjunto de funções surjetivas totais B {@code S -->> T} em {@code Binary_Exp}. */
    public static boolean isTotalSurjectionArrowType(Element e) {
        if (e == null || !"Binary_Exp".equals(e.getLocalName())) {
            return false;
        }
        String o = e.getAttribute("op");
        if (o == null) return false;
        o = o.trim();
        return "-->>".equals(o) || "--&gt;&gt;".equals(o);
    }

    /** Tipo conjunto de injeções parciais B {@code S >+> T} em {@code Binary_Exp}. */
    public static boolean isPartialInjectionArrowType(Element e) {
        if (e == null || !"Binary_Exp".equals(e.getLocalName())) {
            return false;
        }
        String o = e.getAttribute("op");
        if (o == null) return false;
        o = o.trim();
        return ">+>".equals(o) || "&gt;+&gt;".equals(o);
    }

    /** Tipo conjunto de injeções totais B {@code S >-> T} em {@code Binary_Exp}. */
    public static boolean isTotalInjectionArrowType(Element e) {
        if (e == null || !"Binary_Exp".equals(e.getLocalName())) {
            return false;
        }
        String o = e.getAttribute("op");
        if (o == null) return false;
        o = o.trim();
        return ">->".equals(o) || "&gt;-&gt;".equals(o);
    }

    /** Tipo conjunto de sobrejeções parciais B {@code S +->> T} em {@code Binary_Exp}. */
    public static boolean isPartialSurjectionArrowType(Element e) {
        if (e == null || !"Binary_Exp".equals(e.getLocalName())) {
            return false;
        }
        String o = e.getAttribute("op");
        if (o == null) return false;
        o = o.trim();
        return "+->>".equals(o) || "+-&gt;&gt;".equals(o);
    }

    /** Tipo conjunto de bijeções totais B {@code S >->> T} em {@code Binary_Exp}. */
    public static boolean isTotalBijectionArrowType(Element e) {
        if (e == null || !"Binary_Exp".equals(e.getLocalName())) {
            return false;
        }
        String o = e.getAttribute("op");
        if (o == null) return false;
        o = o.trim();
        return ">->>".equals(o) || "&gt;-&gt;&gt;".equals(o);
    }

    /** B maplet {@code cc |-> bb} → {@code couple(cc, bb)} (ACSL_Lib/tuple_functions/tuple_couple.acsl). */
    private static boolean isMapletOp(String op) {
        if (op == null) return false;
        String o = op.trim();
        return "|->".equals(o) || "|-&gt;".equals(o);
    }

    /**
     * Um componente {@code couple(...)} que é um identificador B NU (não já explicitamente
     * convertido por outro caminho) precisa de {@code (integer)} explícito para unificar com o
     * parâmetro genérico de {@code Tuple<A,B>} — o tipo C real de uma variável B (ex. {@code
     * int32_t} para uma variável local simples, ou um tipo {@code enum} para uma tipada por
     * conjunto diferido — ver {@link BxmlTranslateContext#deferredSetTypedParameterNames}) nunca é
     * automaticamente ACSL {@code integer} em posição de INSTANCIAÇÃO GENÉRICA, ao contrário de uma
     * comparação escalar simples (onde o Frama-C promove implicitamente) — confirmado com
     * {@code "invalid cast from Tuple<integer, int32_t> to Tuple<integer, integer>"} para {@code
     * ii_index} (variável local de loop, sem NENHUM outro sinal de "precisa de cast" disponível —
     * ao contrário de {@code pp}, não é parâmetro de operação nem tipada por conjunto diferido). B
     * não tem OUTRO tipo numérico além de {@code integer}, logo este cast é sempre seguro/correto
     * para qualquer {@code Id} nu — só não se aplica a expressões mais complexas (maplet aninhado,
     * {@code function_apply}, …), que podem legitimamente ser {@code Set}/{@code Tuple}-valoradas,
     * nem a algo já explicitamente convertido (evita {@code (integer)(integer)x} redundante, e
     * NUNCA entra dentro de um {@code \old(...)} já aplicado pelo caso {@code "Id"}).
     */
    private static String castBareIntegerIdForTuple(Element component, String translated) {
        if (component == null || translated == null || !"Id".equals(component.getLocalName())) {
            return translated;
        }
        if (translated.startsWith("(integer)")
                || translated.startsWith("(boolean)")
                || translated.startsWith("\\old(")) {
            return translated;
        }
        return "(integer)" + translated;
    }

    /**
     * Desfaz a normalização B de chamada multi-argumento {@code f(a,b,c)} → {@code f(a |-> b |->
     * c)} (maplets aninhados à esquerda) de volta para a lista plana {@code [a, b, c]}, cada
     * elemento já traduzido. Usado só para constantes lambda multi-argumento (ver {@link
     * BxmlTranslateContext#multiArgLambdaConstantNames()}) — para uma variável função/relação B
     * normal, o maplet fica como {@code couple(...)} via {@link #isMapletOp}, não é desfeito.
     */
    private static List<String> flattenMapletChain(Element exp, BxmlTranslateContext ctx) {
        List<String> out = new ArrayList<>();
        flattenMapletChainInto(exp, ctx, out);
        return out;
    }

    private static void flattenMapletChainInto(Element exp, BxmlTranslateContext ctx, List<String> out) {
        if (exp != null && "Binary_Exp".equals(exp.getLocalName()) && isMapletOp(exp.getAttribute("op"))) {
            Element[] pair = twoDirectExpChildren(exp);
            if (pair[0] != null && pair[1] != null) {
                flattenMapletChainInto(pair[0], ctx, out);
                out.add(translate(pair[1], ctx));
                return;
            }
        }
        out.add(translate(exp, ctx));
    }

    /** B diferença de conjuntos {@code s -s t} → {@code set_difference(s, t)} (ACSL_Lib/set_functions/difference.acsl). */
    private static boolean isSetDifferenceOp(String op) {
        if (op == null) return false;
        return "-s".equals(op.trim());
    }

    /** B aplicação funcional {@code f(x)} em BXML como {@code Binary_Exp op="("} → {@code function_apply(f, x)}. */
    private static boolean isFunctionApplicationOp(String op) {
        if (op == null) return false;
        return "(".equals(op.trim());
    }

    /** B restrição de alcance {@code r |> S} → {@code range_restriction(r, S)} (ACSL_Lib/relation_functions/range_restriction.acsl). */
    public static boolean isRangeRestrictionOp(String op) {
        if (op == null) return false;
        String o = op.trim();
        return "|>".equals(o) || "|&gt;".equals(o);
    }

    private static boolean isDomainRestrictionOp(String op) {
        if (op == null) {
            return false;
        }
        String o = op.trim();
        return "<|".equals(o) || "&lt;|".equals(o);
    }

    /** B subtração de domínio {@code S <<| r} → {@code domain_subtraction(r, S)} (ACSL_Lib/relation_functions/domain_subtraction.acsl). */
    private static boolean isDomainSubtractionOp(String op) {
        if (op == null) {
            return false;
        }
        String o = op.trim();
        return "<<|".equals(o) || "&lt;&lt;|".equals(o);
    }

    /** B subtração de imagem {@code r |>> S} → {@code range_subtraction(r, S)} (ACSL_Lib/relation_functions/range_subtraction.acsl). */
    private static boolean isRangeSubtractionOp(String op) {
        if (op == null) {
            return false;
        }
        String o = op.trim();
        return "|>>".equals(o) || "|&gt;&gt;".equals(o);
    }

    /** B tipo conjunto de relações {@code S <-> T} → conjunto de todas as relações de S para T. */
    private static boolean isRelationArrowOp(String op) {
        if (op == null) {
            return false;
        }
        String o = op.trim();
        return "<->".equals(o) || "&lt;-&gt;".equals(o);
    }

    /** Tipo conjunto de relações B {@code S <-> T} em {@code Binary_Exp}. */
    public static boolean isRelationArrowType(Element e) {
        if (e == null || !"Binary_Exp".equals(e.getLocalName())) {
            return false;
        }
        return isRelationArrowOp(e.getAttribute("op"));
    }

    /** B produto direto {@code R1 >< R2} → {@code direct_product(R1, R2)} (ACSL_Lib/relation_functions/direct_product.acsl). */
    private static boolean isDirectProductOp(String op) {
        if (op == null) return false;
        String o = op.trim();
        return "><".equals(o) || "&gt;&lt;".equals(o);
    }

    /** B append à sequência {@code s <- x} → {@code \\concat(s, [| x |])} (E-ACSL / listas). */
    private static boolean isSequenceAppendOp(String op) {
        if (op == null) {
            return false;
        }
        String o = op.trim();
        return "<-".equals(o) || "&lt;-".equals(o);
    }

    /**
     * B cons à sequência {@code x -> s} → {@code \\concat([| x |], s)}. Não confundir com
     * {@code --&gt;} / {@code -->} (tipo conjunto de funções).
     */
    private static boolean isSequencePrependOp(String op) {
        if (op == null) {
            return false;
        }
        String o = op.trim();
        return "->".equals(o) || "-&gt;".equals(o);
    }

    /** B concatenação de sequências {@code s1 ^ s2} → {@code \\concat(s1, s2)} (E-ACSL / listas). */
    private static boolean isSequenceConcatOp(String op) {
        if (op == null) {
            return false;
        }
        return "^".equals(op.trim());
    }

    /** B restrição de frente {@code s /|\ n} (mantém os primeiros n) → {@code restrict_front(s, n)}. */
    private static boolean isSequenceRestrictFrontOp(String op) {
        if (op == null) {
            return false;
        }
        return "/|\\".equals(op.trim());
    }

    /** B restrição de cauda {@code s \|/ n} (descarta os primeiros n) → {@code restrict_tail(s, n)}. */
    private static boolean isSequenceRestrictTailOp(String op) {
        if (op == null) {
            return false;
        }
        return "\\|/".equals(op.trim());
    }

    /**
     * Constantes nomeadas do B que não existem como símbolos ACSL — traduzem-se para literais
     * {@code integer} compatíveis com {@code int} de 32 bits ({@code INT_MAX} / {@code INT_MIN}),
     * coerentes com o limite superior de {@code NAT} usado na lib de exemplos
     * ({@code 2147483647} em {@code Deck/code_commented.c}).
     */
    static String translateBNamedConstant(String id) {
        if (id == null) {
            return "";
        }
        return switch (id.trim()) {
            case "MAXINT" -> "2147483647"; // INT_MAX (32-bit int)
            case "MININT" -> "-2147483648"; // INT_MIN (32-bit int)
            default -> id;
        };
    }

    /** Literais booleanos B ({@code TRUE}/{@code FALSE}) → ACSL {@code \\true}/{@code \\false}. */
    static String translateBooleanLiteral(String value) {
        if (value == null) {
            return "";
        }
        return switch (value.trim().toUpperCase()) {
            case "TRUE" -> "\\true";
            case "FALSE" -> "\\false";
            default -> value.trim();
        };
    }

    /**
     * Comparação com variável BOOL modelada como {@code integer} (0/1) alinhada a {@code _Bool} em C.
     */
    public static String booleanLiteralRhsForVariable(
            Element varExp, String bLiteralValue, BxmlTranslateContext ctx) {
        if (varExp != null
                && "Id".equals(varExp.getLocalName())
                && ctx != null
                && ctx.variableLogicTypes() != null) {
            String v = varExp.getAttribute("value");
            if ("integer".equals(ctx.variableLogicTypes().get(v))) {
                return "TRUE".equalsIgnoreCase(bLiteralValue == null ? "" : bLiteralValue.trim())
                        ? "1"
                        : "0";
            }
        }
        return translateBooleanLiteral(bLiteralValue);
    }

    public static String translate(Element exp, BxmlTranslateContext ctx) {
        String ln = exp.getLocalName();
        return switch (ln) {
            case "Id" -> {
                String idVal = exp.getAttribute("value");
                boolean isPreState = "0".equals(exp.getAttribute("suffix"));
                // Valores enumerados: usar o nome prefixado com cast (ex. (integer)switch__normal).
                // Constantes C de enumeração têm tipo unsigned int; o cast evita erros de tipo em ACSL.
                String renamed = ctx.enumValueRenames().get(idVal);
                if (renamed != null) yield "(integer)" + renamed;
                // Conjuntos enumerados: usar o nome prefixado (ex. ctx__ALARM_STATUS)
                String setRenamed = ctx.enumeratedSetRenames().get(idVal);
                if (setRenamed != null) yield setRenamed;
                // Parâmetros/variáveis de tipo enum C: cast (integer) para compatibilidade com Set<integer>
                String base;
                if (!ctx.enumeratedSetNames().isEmpty()) {
                    String tr = exp.getAttribute("typref");
                    if (!tr.isBlank()) {
                        String bTypeName = ctx.types().getRawType(Integer.parseInt(tr.trim()));
                        if (ctx.enumeratedSetNames().contains(bTypeName)) {
                            base = "(integer)" + translateBNamedConstant(idVal);
                            yield isPreState ? "\\old(" + base + ")" : base;
                        }
                    }
                }
                // Parâmetro de operação tipado por um conjunto diferido/enumerado NA ABSTRATA (ex.
                // "pp" de "AddPlayer(pp) = PRE pp:PLAYER & ... END") cujo typref NESTE ponto (corpo/
                // loop da implementação) já não aponta para o tipo enum — o próprio AtelierB pode
                // ter refinado a inferência para INTEGER (ex. usado em indexação de array), mas a
                // assinatura C real gerada continua a usar o tipo enum (ex. "RulerOfTheSeas__PLAYER
                // pp"). Sem este sinal adicional (ver BxmlTranslateContext#deferredSetTypedParameterNames),
                // o cast acima nunca dispara para este caso — "invalid cast from Tuple<EnumType,...>
                // to Tuple<integer,...>" ao entrar num slot ACSL genérico (ex. couple<A,B>).
                if (ctx.deferredSetTypedParameterNames().contains(idVal)) {
                    base = "(integer)" + translateBNamedConstant(idVal);
                    yield isPreState ? "\\old(" + base + ")" : base;
                }
                base = translateBNamedConstant(idVal);
                yield isPreState ? "\\old(" + base + ")" : base;
            }
            case "Integer_Literal" -> exp.getAttribute("value");
            case "Boolean_Literal" -> translateBooleanLiteral(exp.getAttribute("value"));
            case "EmptySet" -> translateEmptySet(exp, ctx);
            case "EmptySeq" -> "\\Nil"; // lista ACSL vazia (E-ACSL / lógica de sequências)
            case "Unary_Exp" -> translateUnary(exp, ctx);
            case "Binary_Exp" -> translateBinary(exp, ctx);
            case "Nary_Exp" -> translateNary(exp, ctx);
            case "Quantified_Set" -> translateQuantifiedSet(exp, ctx);
            case "Boolean_Exp" -> translateBooleanExp(exp, ctx);
            case "Quantified_Exp" -> GeneralizedQuantifierTranslator.translateQuantifiedExp(exp, ctx);
            default -> "/* TODO: " + ln + " */";
        };
    }

    /**
     * Conjunto em compreensão {@code { x | P }} — referência a {@code set_comprehension_k} do bloco axiomatic.
     */
    static String translateQuantifiedSet(Element qs, BxmlTranslateContext ctx) {
        String named = ctx.comprehensions().referenceName(ctx.machineName(), qs, ctx);
        if (named != null) {
            return named;
        }
        Element vars = BxmlDomUtils.firstChildElement(qs, "Variables");
        Element body = BxmlDomUtils.firstChildElement(qs, "Body");
        if (vars == null || body == null) return "/* quantified_set */";
        java.util.List<String> names = new java.util.ArrayList<>();
        NodeList vnodes = vars.getChildNodes();
        for (int i = 0; i < vnodes.getLength(); i++) {
            Node n = vnodes.item(i);
            if (n.getNodeType() != Node.ELEMENT_NODE) continue;
            Element e = (Element) n;
            if ("Id".equals(e.getLocalName())) names.add(e.getAttribute("value"));
        }
        String pred = BxmlPredicateToAcsl.translateBodyPredicate(body, ctx);
        String vs = String.join(", ", names);
        return "comprehension(" + vs + ", " + pred + ")";
    }

    private static String translateEmptySet(Element emptySet, BxmlTranslateContext ctx) {
        String tr = emptySet.getAttribute("typref");
        int typref = tr.isBlank() ? -1 : Integer.parseInt(tr.trim());
        String elem = typref >= 0 ? ctx.types().elementTypeNameForSetTypref(typref) : "NAT";
        // Conjunto diferido/enumerado da própria máquina (ex. PLAYER) é declarado com nome
        // qualificado (RulerOfTheSeas_PLAYER) — sem a renomeação, "PLAYER" bare fica sem declaração
        // ("unbound logic variable") em qualquer contexto que não reexporte o nome cru (ex. o
        // front-end isolado de ghost_operations.ci; o .acsl raiz tolera-o silenciosamente até à
        // fase de WP, mas continua semanticamente errado). "elem" pode ser um único nome (ISLAND) ou
        // um produto cartesiano achatado (PLAYER*INTEGER, PLAYER*POW(ISLAND) — testemunha do tipo de
        // uma RELAÇÃO diferida/enumerada, ex. player_coins : PLAYER +-> INTEGER): "A*B" NÃO é ACSL
        // válido aqui (não existe operador de produto cartesiano de CONJUNTOS em ACSL — "*" é só
        // multiplicação aritmética; "invalid operands to binary *" se usado literalmente) — tem de
        // se construir o VALOR testemunha via cartesian_product(valorA, valorB) (ACSL_Lib/
        // set_functions/cartesian_product.acsl) — ver buildEmptySetWitnessExpr.
        String witness = buildEmptySetWitnessExpr(elem, ctx);
        // ACSL_Lib/set_functions/empty.acsl — lógica overloaded: empty(Set<τ> witness)
        return "empty(" + witness + ")";
    }

    /**
     * Constrói recursivamente um VALOR testemunha ({@code Set<τ>}, para {@code empty<A>(Set<A>
     * witness)}) a partir de uma expressão de tipo B achatada (ex. {@code PLAYER}, {@code
     * PLAYER*INTEGER}, {@code PLAYER*POW(ISLAND)}):
     * <ul>
     *   <li>nome simples → o próprio conjunto qualificado (ex. {@code RulerOfTheSeas_PLAYER}), ou a
     *       testemunha pré-declarada da lib para primitivos B ({@code INTEGER → INT}; {@code
     *       NAT}/{@code NAT1}/{@code BOOL} mantêm o nome — ver {@code set_functions/variables.acsl});</li>
     *   <li>{@code A*B} (associando à esquerda para N&gt;=3 partes, mesma convenção de {@link
     *       BxmlTypeRegistry#powCartesianProductToAcslRelationType}) → {@code
     *       cartesian_product(witness(A), witness(B))}, {@code Set<Tuple<...>>};</li>
     *   <li>{@code POW(X)} (parte ela própria um conjunto, ex. o codomínio de {@code PLAYER -->
     *       POW(ISLAND)}) → {@code singleton(witness(X))}, {@code Set<Set<...>>} — qualquer valor
     *       serve como testemunha (só fixa o parâmetro genérico), {@code singleton} do witness de X
     *       já tem o tipo certo sem precisar de inventar mais nenhum símbolo novo.</li>
     * </ul>
     */
    private static String buildEmptySetWitnessExpr(String typeExpr, BxmlTranslateContext ctx) {
        if (typeExpr == null || typeExpr.isBlank()) return typeExpr;
        String t = typeExpr.trim();
        if (t.startsWith("POW(") && t.endsWith(")")) {
            return "singleton(" + buildEmptySetWitnessExpr(t.substring(4, t.length() - 1).trim(), ctx) + ")";
        }
        if (t.contains("*")) {
            java.util.List<String> parts = new java.util.ArrayList<>();
            for (String p : t.split("\\*")) {
                String trimmed = p.trim();
                if (!trimmed.isEmpty()) parts.add(trimmed);
            }
            if (parts.size() == 2) {
                return "cartesian_product(" + buildEmptySetWitnessExpr(parts.get(0), ctx) + ", "
                        + buildEmptySetWitnessExpr(parts.get(1), ctx) + ")";
            }
            if (parts.size() >= 3) {
                // Domínio escalar + codomínio tupla de N-1>=2 elementos (ex.: PERSON +->
                // (DAY*MONTH*YEAR) achata para "PERSON*DAY*MONTH*YEAR") — MESMA convenção de
                // BxmlTypeRegistry#powCartesianProductToAcslRelationType/registerCartesianRelationType:
                // grupo 1 = domínio, grupo 2 = codomínio aninhado à esquerda. Um fold uniforme das
                // N partes (grupamento anterior) construía Set<Tuple<Tuple<Tuple<domínio,c1>,c2>,c3>>
                // — testemunha de tipo diferente de Relation_X (Set<Tuple<domínio,
                // Tuple<Tuple<c1,c2>,c3>>>), sem overload "equals" correspondente.
                String domainWitness = buildEmptySetWitnessExpr(parts.get(0), ctx);
                String codomainWitness = buildEmptySetWitnessExpr(parts.get(1), ctx);
                for (int i = 2; i < parts.size(); i++) {
                    codomainWitness = "cartesian_product(" + codomainWitness + ", "
                            + buildEmptySetWitnessExpr(parts.get(i), ctx) + ")";
                }
                return "cartesian_product(" + domainWitness + ", " + codomainWitness + ")";
            }
        }
        switch (t) {
            case "INTEGER":
                return "INT";
            case "NAT", "NAT1", "BOOL":
                return t;
            default:
                break;
        }
        return ctx.enumeratedSetRenames().getOrDefault(t, t);
    }

    private static String translateUnary(Element u, BxmlTranslateContext ctx) {
        String op = u.getAttribute("op");
        Element arg = firstExpChild(u);
        if (arg == null) return "/* unary */";
        if ("card".equals(op) && "Quantified_Set".equals(arg.getLocalName())) {
            return "card(" + translateQuantifiedSet(arg, ctx) + ")"; // card.acsl
        }
        String a = translate(arg, ctx);
        String opTrim = op == null ? "" : op.trim();
        return switch (opTrim) {
            case "card" ->
                    isListValued(arg, ctx) ? "\\length(" + a + ")" : "card(" + a + ")";
            case "size" ->
                    isListValued(arg, ctx) ? "\\length(" + a + ")" : opTrim + "(" + a + ")";
            // B ran: sequence_ran (\list, ACSL_Lib/sequence_functions/range.acsl) vs
            // relation_ran (Relation<A,B>, ACSL_Lib/relation_functions/range.acsl) — "ran" sozinho
            // não existe mais na lib (ambíguo entre os dois tipos).
            case "ran" ->
                    (isListValued(arg, ctx) ? "sequence_ran" : "relation_ran") + "(" + a + ")";
            case "imax" -> "set_max(" + a + ")";
            case "imin" -> "set_min(" + a + ")";
            case "~" -> "relation_inverse(" + a + ")";
            // B POW(X) em posição de VALOR (ex. codomínio de "f : D --> POW(X)", usado como
            // range_set de is_total_function) — distinto de POW em posição de TIPO (ver
            // translateEmptySet/buildEmptySetWitnessExpr, um caminho separado). Sem caso próprio
            // caía no default abaixo, reproduzindo "POW" cru (não existe como símbolo ACSL) —
            // "unbound logic function POW". set_functions/pow_set.acsl: novo primitivo genérico
            // pow_set<A>(Set<A> universe) : Set<Set<A>>, belongs(s,pow_set(u)) <==> inclusion(s,u).
            case "POW" -> "pow_set(" + a + ")";
            // B seq(D) em posição de VALOR — mesmo padrão de POW acima, e também o caso GERAL
            // usado agora por "X : seq(D)" (translateMembershipComparison traduz sempre para
            // belongs(X, seq(D)), nunca mais is_seq_of diretamente — ver lá). Sem caso próprio,
            // uma sequência-de-sequências ficava sem tradução alguma (nenhum Set<\list<A>>
            // disponível). sequence_functions/seq.acsl: novo primitivo genérico seq<A>(Set<A> d) :
            // Set<\list<A>>, belongs(l,seq(d)) <==> is_seq_of(l,d) (is_seq_of fica só como
            // implementação interna do axioma-ponte, nunca mais emitido diretamente).
            case "seq" -> "seq(" + a + ")";
            // B: union(ENS)/inter(ENS) — união/interseção generalizadas de uma família de conjuntos
            // (ENS : Set<Set<A>>), distinto do quantificador UNION(z).(D|E)/INTER(z).(D|E) (esse vai
            // por GeneralizedQuantifierTranslator/UnionInterFunctionRegistry). "union" é palavra
            // reservada do léxico ACSL (mesmo keyword de "union" de C); sem caso próprio caía no
            // default abaixo, produzindo "union(FAM)" cru — símbolo não declarado, rejeitado pelo
            // Frama-C. set_functions/generalized_union_intersection.acsl: general_union<A>/
            // general_inter<A>(Set<Set<A>>) : Set<A>.
            case "union" -> "general_union(" + a + ")";
            case "inter" -> "general_inter(" + a + ")";
            default -> opTrim + "(" + a + ")";
        };
    }

    private static String translateBinary(Element b, BxmlTranslateContext ctx) {
        String op = b.getAttribute("op");
        Element[] pair = twoDirectExpChildren(b);
        if (pair[0] == null || pair[1] == null) return "/* binary */";
        if (isDomainRestrictionOp(op)) {
            String setRef = intervalOrSetComprehensionRef(pair[0], ctx);
            String rel = translate(pair[1], ctx);
            return "domain_restriction(" + rel + ", " + setRef + ")";
        }
        if (isRangeRestrictionOp(op)) {
            String rel = translate(pair[0], ctx);
            String setRef = intervalOrSetComprehensionRef(pair[1], ctx);
            return "range_restriction(" + rel + ", " + setRef + ")";
        }
        if (isDomainSubtractionOp(op)) {
            String setRef = intervalOrSetComprehensionRef(pair[0], ctx);
            String rel = translate(pair[1], ctx);
            return "domain_subtraction(" + rel + ", " + setRef + ")";
        }
        if (isRangeSubtractionOp(op)) {
            String rel = translate(pair[0], ctx);
            String setRef = intervalOrSetComprehensionRef(pair[1], ctx);
            return "range_subtraction(" + rel + ", " + setRef + ")";
        }
        if (isMapletOp(op)) {
            String left = castBareIntegerIdForTuple(pair[0], translate(pair[0], ctx));
            String right = castBareIntegerIdForTuple(pair[1], translate(pair[1], ctx));
            return "couple(" + left + ", " + right + ")";
        }
        if (isFunctionApplicationOp(op)) {
            // f(a,b,c) numa constante lambda multi-argumento (ex.: IS_VALID, declarada via
            // ABSTRACT_CONSTANTS + PROPERTIES IS_VALID = %(dd,mm,aa).(...)) chega aqui já
            // B-normalizada como f(a |-> b |-> c) — chamada direta multi-argumento f(a, b, c), NÃO
            // function_apply(f, couple(couple(a,b),c)): a assinatura declarada para estas
            // constantes é "logic boolean f(integer, integer, …)", não Function<A,B> (ver
            // BxmlConstantsAndProperties#formatAbstractConstantsBlock / BxmlTranslateContext#multiArgLambdaConstantNames).
            if ("Id".equals(pair[0].getLocalName())
                    && ctx.multiArgLambdaConstantNames().contains(pair[0].getAttribute("value"))) {
                String name = translateBNamedConstant(pair[0].getAttribute("value"));
                List<String> args = flattenMapletChain(pair[1], ctx);
                return name + "(" + String.join(", ", args) + ")";
            }
            // f(x) em B → function_apply(f, x) em ACSL
            String left = translate(pair[0], ctx);
            String right = translate(pair[1], ctx);
            return "function_apply(" + left + ", " + right + ")";
        }
        // B imagem relacional r[E]: r[{x}] (E singleton em extensão) → apply(r, x) — apply<A,B> só
        // aceita um elemento A, não um Set<A> (ACSL_Lib/relation_functions/apply.acsl). Para E
        // genérico (variável/comprehension/intervalo tipados Set<A>) usa-se a equivalência
        // r[E] = ran(E <| r) já suportada pela lib: relation_ran(domain_restriction(r, E))
        // (ACSL_Lib/relation_functions/{domain_restriction,range}.acsl) — passar E direto a apply
        // como se fosse um elemento causa "incompatible types Set<integer> and integer" no Frama-C.
        if ("[".equals(op == null ? "" : op.trim())) {
            String rel = translate(pair[0], ctx);
            Element singletonElem = singletonElementOrNull(pair[1]);
            if (singletonElem != null) {
                return "apply(" + rel + ", " + translate(singletonElem, ctx) + ")";
            }
            String setRef = intervalOrSetComprehensionRef(pair[1], ctx);
            return "relation_ran(domain_restriction(" + rel + ", " + setRef + "))";
        }
        String left = translate(pair[0], ctx);
        String right = translate(pair[1], ctx);
        if (isSetDifferenceOp(op)) {
            return "set_difference(" + left + ", " + right + ")";
        }
        if (isSetUnion(op)) {
            // B: \/ — união → set_union (ACSL_Lib/set_functions/union.acsl)
            return "set_union(" + left + ", " + right + ")";
        }
        if (isSetIntersection(op)) {
            // B: /\ — interseção → set_intersection (ACSL_Lib/set_functions/intersection.acsl)
            return "set_intersection(" + left + ", " + right + ")";
        }
        if (isCartesianProduct(op)) {
            // B: *s — produto cartesiano → cartesian_product (ACSL_Lib/set_functions/cartesian_product.acsl)
            return "cartesian_product(" + left + ", " + right + ")";
        }
        if ("<+".equals(op == null ? "" : op.trim())) {
            // B: q <+ r — sobreposição relacional (relation overriding) → overwrite
            // (ACSL_Lib/relation_functions/overwrite.acsl); NÃO confundir com o açúcar de atribuição
            // "f(x) := y" (formatFunctionOverrideAssignment), que constrói o segundo argumento como
            // singleton(couple(x,y)) — aqui ambos os lados já são relações completas.
            return "overwrite(" + left + ", " + right + ")";
        }
        if ("..".equals(op == null ? "" : op.trim())) {
            return "interval_set(" + left + ", " + right + ")";
        }
        if (isSequenceAppendOp(op)) {
            return "(" + "\\concat(" + left + ", [|" + right + "|])" + ")";
        }
        if (isSequencePrependOp(op)) {
            return "\\concat([|" + left + "|], " + right + ")";
        }
        if (isSequenceConcatOp(op)) {
            return "\\concat(" + left + ", " + right + ")";
        }
        if (isSequenceRestrictFrontOp(op)) {
            // B: s /|\ n (mantém os primeiros n elementos) → restrict_front (ACSL_Lib/sequence_functions/restrict_front.acsl)
            return "restrict_front(" + left + ", " + right + ")";
        }
        if (isSequenceRestrictTailOp(op)) {
            // B: s \|/ n (descarta os primeiros n elementos) → restrict_tail (ACSL_Lib/sequence_functions/restrict_tail.acsl)
            return "restrict_tail(" + left + ", " + right + ")";
        }
        if ("mod".equals(op)) return "(" + left + " % " + right + ")";
        if ("**i".equals(op == null ? "" : op.trim())) {
            return "integer_pow(" + left + ", " + right + ")";
        }
        String opTrimmed = op == null ? "" : op.trim();
        // B: prj1(E,F)/prj2(E,F) — relações de projeção de E*F (ACSL_Lib/relation_functions/
        // prj1.acsl,prj2.acsl). Binary_Exp com dois filhos diretos (E, F), não chamada função —
        // sem caso próprio caía no infixo cru "(E prj1 F)" (default abaixo), inválido em ACSL.
        if ("prj1".equals(opTrimmed)) {
            return "prj1(" + left + ", " + right + ")";
        }
        if ("prj2".equals(opTrimmed)) {
            return "prj2(" + left + ", " + right + ")";
        }
        // B: R1 >< R2 — produto direto (ACSL_Lib/relation_functions/direct_product.acsl).
        if (isDirectProductOp(opTrimmed)) {
            return "direct_product(" + left + ", " + right + ")";
        }
        // B: iterate(r,n) — r composta consigo própria n vezes (ACSL_Lib/relation_functions/
        // iterate.acsl). Binary_Exp com dois filhos diretos (r, n), mesma forma AST de prj1/prj2
        // acima (chamada de função com 2 argumentos, não operador infixo B) — sem caso próprio
        // caía no infixo cru "(r iterate n)" (default abaixo), inválido em ACSL: o -acsl-import
        // rejeitava-o como erro de SINTAXE (não "unbound function"), e o mecanismo de auto-cura de
        // colisão de palavra reservada (FramaCRunner) ficava a renomear "iterate" -> "iterate_b" ->
        // "iterate_b_b" -> ... para sempre, sem nunca convergir (o problema nunca foi o NOME, era a
        // FORMA da expressão).
        if ("iterate".equals(opTrimmed)) {
            return "iterate(" + left + ", " + right + ")";
        }
        String infix = integerBinaryOpToAcsl(op);
        return "(" + left + " " + infix + " " + right + ")";
    }

    /**
     * Operadores binários inteiros B (ex. {@code -i}, {@code +i}) → infixo C/ACSL ({@code -}, {@code +}).
     */
    private static String integerBinaryOpToAcsl(String bOp) {
        if (bOp == null) {
            return "";
        }
        String o = bOp.trim();
        return switch (o) {
            case "+i" -> "+";
            case "-i" -> "-";
            case "*i" -> "*";
            case "/i" -> "/";
            default -> o;
        };
    }

    private static String translateNary(Element n, BxmlTranslateContext ctx) {
        String op = n.getAttribute("op");
        if ("{".equals(op)) {
            // enumeração finita { e1, e2, ... } — ACSL_Lib/set_functions/singleton.acsl
            // Boolean_Literal dentro de {} como \true/\false: BOOL é Set<boolean> em B2ACSL.
            java.util.List<String> parts = new java.util.ArrayList<>();
            NodeList children = n.getChildNodes();
            for (int i = 0; i < children.getLength(); i++) {
                Node node = children.item(i);
                if (node.getNodeType() != Node.ELEMENT_NODE) continue;
                Element e = (Element) node;
                if ("Attr".equals(e.getLocalName())) continue;
                if ("Boolean_Literal".equals(e.getLocalName())) {
                    String bv = e.getAttribute("value");
                    parts.add("TRUE".equalsIgnoreCase(bv == null ? "" : bv.trim()) ? "\\true" : "\\false");
                } else {
                    parts.add(translate(e, ctx));
                }
            }
            if (parts.size() == 1) return "singleton(" + parts.get(0) + ")";
            // { e1, e2, ..., eN } → set_union(set_union(singleton(e1), singleton(e2)), singleton(eN))
            String acc = "singleton(" + parts.get(0) + ")";
            for (int k = 1; k < parts.size(); k++) {
                acc = "set_union(" + acc + ", singleton(" + parts.get(k) + "))";
            }
            return acc;
        }
        if ("[".equals(op)) {
            // B: sequência em extensão [e1, e2, ..., en] (5.7/5.17 B Language Manual) → literal de
            // lista ACSL nativa [| e1, e2, ..., en |] — mesma sintaxe já usada por \concat/insert
            // (ver isSequenceAppendOp etc. acima). O caso de 0 elementos não ocorre aqui: B trata a
            // sequência vazia "[]" como um nó BXML próprio (EmptySeq -> \Nil), nunca Nary_Exp "[".
            // Recursivo por construção (translate(e, ctx) em cada filho): cobre sequência de
            // sequências (ex. CC = [[3],[1,2]] -> [| [|3|], [|1,2|] |]), tal como o \list<\list<...>>
            // inferido para CC em BxmlMachineVariables#inferAbstractConstantsLogicTypesFromProperties.
            java.util.List<String> parts = new java.util.ArrayList<>();
            NodeList children = n.getChildNodes();
            for (int i = 0; i < children.getLength(); i++) {
                Node node = children.item(i);
                if (node.getNodeType() != Node.ELEMENT_NODE) continue;
                Element e = (Element) node;
                if ("Attr".equals(e.getLocalName())) continue;
                parts.add(translate(e, ctx));
            }
            return "[| " + String.join(", ", parts) + " |]";
        }
        return "/* nary " + op + " */";
    }

    static Element firstExpChild(Element parent) {
        NodeList nl = parent.getChildNodes();
        for (int i = 0; i < nl.getLength(); i++) {
            Node n = nl.item(i);
            if (n.getNodeType() != Node.ELEMENT_NODE) continue;
            Element e = (Element) n;
            if ("Attr".equals(e.getLocalName())) continue;
            return e;
        }
        return null;
    }

    /** Dois primeiros elementos filhos que pertencem ao grupo Exp (aproximação por ordem DOM). */
    private static boolean isSetUnion(String op) {
        if (op == null || op.isEmpty()) return false;
        // BXML grava o operador de união como \/
        return "\\/".equals(op) || "/".equals(op);
    }

    private static boolean isSetIntersection(String op) {
        if (op == null || op.isEmpty()) return false;
        // BXML grava o operador de interseção como /\
        return "/\\".equals(op);
    }

    /** Pacote-visível: reutilizado por {@link BxmlMachineVariables} (mesmo pacote). */
    static boolean isCartesianProduct(String op) {
        if (op == null || op.isEmpty()) return false;
        // BXML grava o produto cartesiano de conjuntos como *s
        return "*s".equals(op.trim());
    }

    /** Usado também por {@link BxmlPredicateToAcsl} para {@code Exp_Comparison}. */
    static Element[] twoDirectExpChildren(Element parent) {
        Element[] out = new Element[2];
        int k = 0;
        NodeList nl = parent.getChildNodes();
        for (int i = 0; i < nl.getLength(); i++) {
            Node n = nl.item(i);
            if (n.getNodeType() != Node.ELEMENT_NODE) continue;
            Element e = (Element) n;
            if ("Attr".equals(e.getLocalName())) continue;
            out[k++] = e;
            if (k == 2) break;
        }
        return out;
    }

    /**
     * B {@code bool(P)} → {@code (pred ? \\true : \\false)}.
     *
     * <p>O {@code Boolean_Exp} BXML encapsula um predicado; o resultado é um valor {@code BOOL}
     * (constante da ACSL_Lib).
     */
    private static String translateBooleanExp(Element e, BxmlTranslateContext ctx) {
        NodeList nl = e.getChildNodes();
        for (int i = 0; i < nl.getLength(); i++) {
            Node n = nl.item(i);
            if (n.getNodeType() != Node.ELEMENT_NODE) continue;
            Element child = (Element) n;
            if ("Attr".equals(child.getLocalName())) continue;
            String pred = BxmlPredicateToAcsl.translatePropertyPred(child, ctx);
            return "(" + pred + " ? \\true : \\false)";
        }
        return "/* bool_exp */";
    }

    /** Primeiro filho elemento cujo {@code localName} corresponda a {@code name}. */
    static Element childByLocalName(Element parent, String name) {
        NodeList nl = parent.getChildNodes();
        for (int i = 0; i < nl.getLength(); i++) {
            Node n = nl.item(i);
            if (n.getNodeType() != Node.ELEMENT_NODE) continue;
            Element e = (Element) n;
            if (name.equals(e.getLocalName())) return e;
        }
        return null;
    }

    /**
     * Para a imagem relacional {@code r[{x}]}: se {@code e} for um {@code Nary_Exp op='{'} com
     * exactamente um elemento, retorna esse elemento (não traduzido, descarta a envolvente
     * singleton); caso contrário retorna {@code null} ({@code e} é um {@code Set<A>} genérico, não
     * um elemento isolado).
     */
    private static Element singletonElementOrNull(Element e) {
        if (e != null
                && "Nary_Exp".equals(e.getLocalName())
                && "{".equals(e.getAttribute("op"))) {
            java.util.List<Element> elems = new java.util.ArrayList<>();
            NodeList nl = e.getChildNodes();
            for (int i = 0; i < nl.getLength(); i++) {
                Node n = nl.item(i);
                if (n.getNodeType() != Node.ELEMENT_NODE) continue;
                Element ch = (Element) n;
                if (!"Attr".equals(ch.getLocalName())) elems.add(ch);
            }
            if (elems.size() == 1) return elems.get(0);
        }
        return null;
    }
}
