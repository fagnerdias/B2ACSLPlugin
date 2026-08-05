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

        return "\\forall " + zType + " " + z + "; function_apply(" + lhsText + ", " + z + ") == ("
                + qText + " ? (" + eText + ") : function_apply(" + wText + ", " + z + "))";
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
                yield "ran".equals(op) || "dom".equals(op) || "id".equals(op)
                        || "closure".equals(op) || "closure1".equals(op);
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
                        || isIntervalBinaryExp(exp)
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

    private static boolean isSetLikeVariableType(String t) {
        if (t == null || t.isBlank()) return false;
        if (t.startsWith("Set<")) return true;
        return t.startsWith("Relation_");
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
            String n = ctx.comprehensions().referenceName(ctx.machineName(), e);
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

    /** B maplet {@code cc |-> bb} → {@code couple(cc, bb)} (ACSL_Lib/tuple_functions/tuple_couple.acsl). */
    private static boolean isMapletOp(String op) {
        if (op == null) return false;
        String o = op.trim();
        return "|->".equals(o) || "|-&gt;".equals(o);
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
            case "Quantified_Exp" -> translateQuantifiedExp(exp, ctx);
            default -> "/* TODO: " + ln + " */";
        };
    }

    /**
     * Conjunto em compreensão {@code { x | P }} — referência a {@code set_comprehension_k} do bloco axiomatic.
     */
    static String translateQuantifiedSet(Element qs, BxmlTranslateContext ctx) {
        String named = ctx.comprehensions().referenceName(ctx.machineName(), qs);
        if (named != null) {
            return named;
        }
        Element vars = firstChildElement(qs, "Variables");
        Element body = firstChildElement(qs, "Body");
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

    private static Element firstChildElement(Element parent, String localName) {
        NodeList nl = parent.getChildNodes();
        for (int i = 0; i < nl.getLength(); i++) {
            Node n = nl.item(i);
            if (n.getNodeType() != Node.ELEMENT_NODE) continue;
            Element e = (Element) n;
            if (localName.equals(e.getLocalName())) return e;
        }
        return null;
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
            if (parts.size() >= 2) {
                String acc = buildEmptySetWitnessExpr(parts.get(0), ctx);
                for (int i = 1; i < parts.size(); i++) {
                    acc = "cartesian_product(" + acc + ", " + buildEmptySetWitnessExpr(parts.get(i), ctx) + ")";
                }
                return acc;
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
        if (isMapletOp(op)) {
            String left = translate(pair[0], ctx);
            String right = translate(pair[1], ctx);
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
        // r[{x}] → apply(r, x)  (ACSL_Lib/relation_functions/apply.acsl)
        if ("[".equals(op == null ? "" : op.trim())) {
            String rel = translate(pair[0], ctx);
            String arg = unwrapSingletonOrTranslate(pair[1], ctx);
            return "apply(" + rel + ", " + arg + ")";
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
        return "/* nary " + op + " */";
    }

    private static Element firstExpChild(Element parent) {
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

    private static boolean isCartesianProduct(String op) {
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

    /**
     * Nomes B que nunca são variáveis livres de uma lambda (conjuntos primitivos, literais, etc.).
     * Filtrados durante a detecção de variáveis livres em {@link #translateQuantifiedExp}.
     */
    private static final java.util.Set<String> B_NON_PARAM_IDS = java.util.Set.of(
            "NAT", "NAT1", "INTEGER", "INT", "BOOL", "REAL", "STRING",
            "TRUE", "FALSE", "MAXINT", "MININT");

    /**
     * B lambda {@code % x.(P | E)} → função lógica nomeada registada em {@link LambdaFunctionRegistry}.
     * Nunca {@code \lambda} nativo do ACSL (não compõe com o sistema de tipos B2ACSL baseado em ADTs).
     *
     * <p>Classifica o domínio {@code P} (ver {@link #intervalDomainBounds}) para escolher a forma:
     * <ul>
     *   <li>Uma só variável ligada com domínio {@code x : lo..hi} (intervalo literal) → {@code E} é
     *       o elemento de uma SEQUÊNCIA B — forma "sequence builder" ({@link
     *       #translateSequenceBuilderLambda}): função lógica recursiva sobre {@code \list}, com
     *       lemas-ponte {@code _length}/{@code _nth}.</li>
     *   <li>Caso contrário (múltiplas variáveis ligadas, ou domínio não é um intervalo literal) →
     *       modelo de MAPA ({@link #translateMapLambda}): função/predicado ponto-a-ponto, guardado
     *       pelo domínio quando este não é trivial.</li>
     * </ul>
     */
    private static String translateQuantifiedExp(Element qe, BxmlTranslateContext ctx) {
        String type = qe.getAttribute("type");
        SigmaFunctionRegistry.Op generalizedOp = switch (type) {
            case "iSIGMA" -> SigmaFunctionRegistry.Op.SIGMA;
            case "iPI" -> SigmaFunctionRegistry.Op.PI;
            case "iMIN" -> SigmaFunctionRegistry.Op.MIN;
            case "iMAX" -> SigmaFunctionRegistry.Op.MAX;
            default -> null;
        };
        if (generalizedOp != null) {
            return translateGeneralizedQuantifiedExp(qe, generalizedOp, ctx);
        }
        UnionInterFunctionRegistry.Op setOp = switch (type) {
            case "UNION" -> UnionInterFunctionRegistry.Op.UNION;
            case "INTER" -> UnionInterFunctionRegistry.Op.INTER;
            default -> null;
        };
        if (setOp != null) {
            return translateUnionInterExp(qe, setOp, ctx);
        }
        if (!"%".equals(type)) {
            return "/* TODO: Quantified_Exp type=" + type + " */";
        }

        // 1. Variáveis ligadas pelo %
        Element varsEl = childByLocalName(qe, "Variables");
        java.util.List<String> boundVarNames = new java.util.ArrayList<>();
        if (varsEl != null) {
            NodeList nl = varsEl.getChildNodes();
            for (int i = 0; i < nl.getLength(); i++) {
                Node n = nl.item(i);
                if (n.getNodeType() != Node.ELEMENT_NODE) continue;
                Element idEl = (Element) n;
                if ("Attr".equals(idEl.getLocalName()) || !"Id".equals(idEl.getLocalName())) continue;
                boundVarNames.add(idEl.getAttribute("value"));
            }
        }
        if (boundVarNames.isEmpty()) return "/* TODO: lambda vars */";

        // 2. Guarda (domínio) do % — lado esquerdo do "|" em "% x.(P | E)". B trata % como função
        // PARCIAL: fora de P a lambda é indefinida, por isso a guarda tem de restringir a definição
        // gerada, não pode ser descartada.
        Element guardEl = childByLocalName(qe, "Pred");
        Element guardPred = guardEl != null ? firstExpChild(guardEl) : null;

        // 3. Corpo da lambda
        Element bodyEl = childByLocalName(qe, "Body");
        if (bodyEl == null) return "/* TODO: lambda body */";
        Element bodyExp = firstExpChild(bodyEl);
        if (bodyExp == null) return "/* TODO: lambda body */";

        // 4. Classificação: sequência (caso a/b) vs mapa (caso c) — ver javadoc.
        String[] intervalBounds = boundVarNames.size() == 1
                ? intervalDomainBounds(guardPred, boundVarNames.get(0), ctx)
                : null;
        if (intervalBounds != null) {
            return translateSequenceBuilderLambda(
                    boundVarNames.get(0), intervalBounds[0], intervalBounds[1], bodyExp, guardPred, ctx);
        }
        return translateMapLambda(boundVarNames, guardPred, bodyExp, ctx);
    }

    /**
     * Se {@code guardPred} for exactamente {@code boundVarName : lo..hi} ({@code Exp_Comparison
     * op=':'} com o lado direito um {@code Binary_Exp op='..'} literal), devolve {@code [lo, hi]}
     * já traduzidos para ACSL; senão {@code null} (domínio não é um intervalo simples — cai no
     * modelo de mapa). {@code lo} pode ser {@code 1} (caso "a" da classificação, sequência B
     * padrão) ou outro valor (caso "b", sequência reindexada).
     */
    private static String[] intervalDomainBounds(
            Element guardPred, String boundVarName, BxmlTranslateContext ctx) {
        if (guardPred == null || !"Exp_Comparison".equals(guardPred.getLocalName())) {
            return null;
        }
        if (!":".equals(guardPred.getAttribute("op"))) {
            return null;
        }
        Element[] pair = twoDirectExpChildren(guardPred);
        if (pair[0] == null || pair[1] == null) {
            return null;
        }
        if (!"Id".equals(pair[0].getLocalName()) || !boundVarName.equals(pair[0].getAttribute("value"))) {
            return null;
        }
        Element domain = pair[1];
        if (!isIntervalBinaryExp(domain)) {
            return null;
        }
        Element[] bounds = twoDirectExpChildren(domain);
        if (bounds[0] == null || bounds[1] == null) {
            return null;
        }
        String lo = translate(bounds[0], ctx);
        String hi = translate(bounds[1], ctx);
        if (lo == null || lo.isBlank() || hi == null || hi.isBlank()) {
            return null;
        }
        return new String[] {lo.trim(), hi.trim()};
    }

    // ══════════════ SIGMA / PI / MIN / MAX (Quantified_Exp type='iSIGMA'/'iPI'/'iMIN'/'iMAX') ══════════════

    private static String opBSyntax(SigmaFunctionRegistry.Op op) {
        return switch (op) {
            case SIGMA -> "SIGMA";
            case PI -> "PI";
            case MIN -> "MIN";
            case MAX -> "MAX";
        };
    }

    /** Classificação do domínio {@code D} de {@code OP(z).(z : D | E)} — ver {@link #classifyDomain}. */
    private enum DomainKind { INTERVAL, SET }

    private record DomainClassification(
            DomainKind kind, String lo, String hi, String filterTemplate, String domainExprText) {}

    /**
     * B {@code SIGMA/PI/MIN/MAX(z).(P | E)} → função lógica nomeada registada em {@link
     * SigmaFunctionRegistry}. Classifica {@code P} (ver {@link #classifyDomain}) para escolher entre
     * recursão em intervalo (com filtro opcional) e axiomatização sobre conjunto qualquer; duas
     * variáveis ligadas geram um par de funções aninhadas (ver {@link
     * #translateNestedGeneralizedSum}).
     */
    private static String translateGeneralizedQuantifiedExp(
            Element qe, SigmaFunctionRegistry.Op op, BxmlTranslateContext ctx) {
        Element varsEl = childByLocalName(qe, "Variables");
        java.util.List<String> boundVarNames = new java.util.ArrayList<>();
        java.util.List<Integer> boundVarTyprefs = new java.util.ArrayList<>();
        if (varsEl != null) {
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
        }
        if (boundVarNames.isEmpty()) {
            return "/* TODO(b2acsl): " + opBSyntax(op) + " sem variáveis ligadas */";
        }

        Element guardEl = childByLocalName(qe, "Pred");
        Element guardPred = guardEl != null ? firstExpChild(guardEl) : null;

        Element bodyEl = childByLocalName(qe, "Body");
        if (bodyEl == null) return "/* TODO(b2acsl): " + opBSyntax(op) + " sem corpo */";
        Element bodyExp = firstExpChild(bodyEl);
        if (bodyExp == null) return "/* TODO(b2acsl): " + opBSyntax(op) + " sem corpo */";

        SigmaFunctionRegistry registry = ctx.sigmaRegistry();
        if (registry == null) {
            return "/* TODO(b2acsl): " + opBSyntax(op) + " sem registro no contexto */";
        }

        if (boundVarNames.size() > 2) {
            return "/* TODO(b2acsl): " + opBSyntax(op) + " com mais de 2 variáveis ligadas não suportado */";
        }
        if (boundVarNames.size() == 2) {
            return translateNestedGeneralizedSum(
                    op, boundVarNames.get(0), boundVarNames.get(1), guardPred, bodyExp, ctx, registry);
        }

        String boundVarName = boundVarNames.get(0);
        DomainClassification dc = classifyDomain(guardPred, boundVarName, ctx);
        if (dc == null) {
            return "/* TODO(b2acsl): domínio de " + opBSyntax(op) + "(" + boundVarName + ") não reconhecido */";
        }
        String sourceComment = describeGeneralizedSum(op, boundVarName, guardPred, bodyExp, ctx);
        if (dc.kind() == DomainKind.SET) {
            return registerSetDomainSum(
                    op, boundVarName, boundVarTyprefs.get(0), dc, bodyExp, guardPred, sourceComment, ctx,
                    registry);
        }
        return registerIntervalDomainSum(op, boundVarName, dc, bodyExp, guardPred, sourceComment, ctx, registry);
    }

    /** Comentário {@code OP(z).(P | E)} (reconstruído do BXML) citando a construção B de origem. */
    private static String describeGeneralizedSum(
            SigmaFunctionRegistry.Op op, String boundVarNames, Element guardPred, Element bodyExp,
            BxmlTranslateContext ctx) {
        String predText;
        try {
            predText = guardPred != null ? BxmlPredicateToAcsl.translatePropertyPred(guardPred, ctx) : "?";
        } catch (RuntimeException ex) {
            predText = "?";
        }
        String bodyText;
        try {
            bodyText = translate(bodyExp, ctx);
        } catch (RuntimeException ex) {
            bodyText = "?";
        }
        return opBSyntax(op) + "(" + boundVarNames + ").(" + predText + " | " + bodyText + ")";
    }

    /** Predicados conjuntados por {@code Nary_Pred op='&'}, ou {@code [pred]} se não for uma conjunção. */
    private static java.util.List<Element> conjunctsOf(Element pred) {
        java.util.List<Element> out = new java.util.ArrayList<>();
        if (pred == null) return out;
        if ("Nary_Pred".equals(pred.getLocalName()) && "&".equals(pred.getAttribute("op"))) {
            NodeList nl = pred.getChildNodes();
            for (int i = 0; i < nl.getLength(); i++) {
                Node n = nl.item(i);
                if (n.getNodeType() != Node.ELEMENT_NODE) continue;
                Element e = (Element) n;
                if ("Attr".equals(e.getLocalName())) continue;
                out.add(e);
            }
            return out;
        }
        out.add(pred);
        return out;
    }

    /**
     * {@code z : S} com {@code S} um conjunto qualquer NÃO intervalo (caso (c)/(b) de §1) — {@code
     * null} se {@code c} não for dessa forma.
     */
    private static String membershipSetExpr(Element c, String boundVarName, BxmlTranslateContext ctx) {
        if (c == null || !"Exp_Comparison".equals(c.getLocalName()) || !":".equals(c.getAttribute("op"))) {
            return null;
        }
        Element[] pair = twoDirectExpChildren(c);
        if (pair[0] == null || pair[1] == null) return null;
        if (!"Id".equals(pair[0].getLocalName()) || !boundVarName.equals(pair[0].getAttribute("value"))) {
            return null;
        }
        if (isIntervalBinaryExp(pair[1])) return null;
        return translate(pair[1], ctx);
    }

    /**
     * Classifica o domínio {@code P} de {@code OP(z).(P | E)} — ver §1 da especificação:
     * <ul>
     *   <li>(a) {@code z : lo..hi} → {@link DomainKind#INTERVAL}, sem filtro.</li>
     *   <li>(b) {@code z : lo..hi & Q(z)} (ou {@code z:lo..hi & z:S}) → {@link DomainKind#INTERVAL},
     *       filtro = conjunção dos restantes conjuntos traduzida normalmente.</li>
     *   <li>(c) {@code z : S} (conjunto qualquer, ou interseção de vários {@code z:S_i}) → {@link
     *       DomainKind#SET}.</li>
     * </ul>
     * {@code null} se nenhuma forma reconhecida se aplicar (não improvisa — cai em TODO no chamador).
     */
    private static DomainClassification classifyDomain(
            Element guardPred, String boundVarName, BxmlTranslateContext ctx) {
        java.util.List<Element> conjuncts = conjunctsOf(guardPred);

        int intervalIdx = -1;
        String lo = null;
        String hi = null;
        for (int i = 0; i < conjuncts.size(); i++) {
            String[] bounds = intervalDomainBounds(conjuncts.get(i), boundVarName, ctx);
            if (bounds != null) {
                intervalIdx = i;
                lo = bounds[0];
                hi = bounds[1];
                break;
            }
        }
        if (intervalIdx >= 0) {
            java.util.List<Element> rest = new java.util.ArrayList<>(conjuncts);
            rest.remove(intervalIdx);
            if (rest.isEmpty()) {
                return new DomainClassification(DomainKind.INTERVAL, lo, hi, null, null);
            }
            java.util.List<String> parts = new java.util.ArrayList<>();
            for (Element r : rest) parts.add(BxmlPredicateToAcsl.translatePropertyPred(r, ctx));
            return new DomainClassification(DomainKind.INTERVAL, lo, hi, String.join(" && ", parts), null);
        }

        int setIdx = -1;
        String setExpr = null;
        for (int i = 0; i < conjuncts.size(); i++) {
            String s = membershipSetExpr(conjuncts.get(i), boundVarName, ctx);
            if (s != null) {
                setIdx = i;
                setExpr = s;
                break;
            }
        }
        if (setIdx < 0) return null;
        java.util.List<Element> rest = new java.util.ArrayList<>(conjuncts);
        rest.remove(setIdx);
        for (Element r : rest) {
            String extra = membershipSetExpr(r, boundVarName, ctx);
            if (extra == null) {
                // Conjunto extra não é 'z:S_i' — forma não coberta por §1; não improvisa.
                return null;
            }
            setExpr = "set_intersection(" + setExpr + ", " + extra + ")";
        }
        return new DomainClassification(DomainKind.SET, null, null, null, setExpr);
    }

    /** Variáveis livres de {@code E}/{@code P} (§2): IDs de {@code scanRoots} menos ligadas/estado/conjuntos. */
    private static java.util.List<String>[] freeVarsAndTypes(
            java.util.List<String> boundVarNames, BxmlTranslateContext ctx, Element... scanRoots) {
        java.util.LinkedHashSet<String> allIds = new java.util.LinkedHashSet<>();
        for (Element root : scanRoots) {
            if (root != null) collectIdValues(root, allIds);
        }
        allIds.removeAll(boundVarNames);
        allIds.removeAll(ctx.variableLogicTypes().keySet());
        allIds.removeAll(ctx.declaredSetNames());
        allIds.removeAll(B_NON_PARAM_IDS);
        java.util.List<String> freeVarNames = new java.util.ArrayList<>(allIds);
        java.util.List<String> freeVarTypes = new java.util.ArrayList<>();
        for (String v : freeVarNames) {
            String t = ctx.variableLogicTypes().get(v);
            if (t == null || t.isBlank()) t = ctx.crossMachineVariableLogicTypes().get(v);
            freeVarTypes.add(t == null || t.isBlank() ? "integer" : t);
        }
        @SuppressWarnings("unchecked")
        java.util.List<String>[] out = new java.util.List[] {freeVarNames, freeVarTypes};
        return out;
    }

    /** §2/§2b: domínio-intervalo — recursão (SIGMA/PI) ou declaração+axiomas guardados (MIN/MAX). */
    private static String registerIntervalDomainSum(
            SigmaFunctionRegistry.Op op, String boundVarName, DomainClassification dc, Element bodyExp,
            Element guardPred, String sourceComment, BxmlTranslateContext ctx, SigmaFunctionRegistry registry) {
        String bodyStr = translate(bodyExp, ctx);
        String bodyForLo = replaceWordBoundary(bodyStr, boundVarName, "lo");
        String bodyForHi = replaceWordBoundary(bodyStr, boundVarName, "hi");
        String filterForLo = dc.filterTemplate() != null
                ? replaceWordBoundary(dc.filterTemplate(), boundVarName, "lo") : null;
        String filterForHi = dc.filterTemplate() != null
                ? replaceWordBoundary(dc.filterTemplate(), boundVarName, "hi") : null;

        java.util.List<String>[] ft = freeVarsAndTypes(
                java.util.List.of(boundVarName), ctx, bodyExp, guardPred);
        java.util.List<String> freeVarNames = ft[0];
        java.util.List<String> freeVarTypes = ft[1];

        String[] congr = SigmaFunctionRegistry.detectCongrVar(bodyStr, freeVarNames);
        String congrVar = null;
        String congrType = null;
        if (congr != null) {
            congrVar = congr[0];
            int idx = freeVarNames.indexOf(congrVar);
            congrType = idx >= 0 && idx < freeVarTypes.size() ? freeVarTypes.get(idx) : "integer";
        }

        String name = registry.registerInterval(
                op, freeVarNames, freeVarTypes, sourceComment, bodyForLo, bodyForHi, filterForLo, filterForHi,
                congrVar, congrType);
        String freeArgs = freeVarNames.isEmpty() ? "" : String.join(", ", freeVarNames) + ", ";
        return name + "(" + freeArgs + dc.lo() + ", " + dc.hi() + ")";
    }

    /** §3: domínio-conjunto qualquer — axiomatização estrutural sobre {@code empty}/{@code singleton+union}. */
    private static String registerSetDomainSum(
            SigmaFunctionRegistry.Op op, String boundVarName, int boundVarTypref, DomainClassification dc,
            Element bodyExp, Element guardPred, String sourceComment, BxmlTranslateContext ctx,
            SigmaFunctionRegistry registry) {
        String bodyStr = translate(bodyExp, ctx);
        String bodyForX = replaceWordBoundary(bodyStr, boundVarName, "x");

        java.util.List<String>[] ft = freeVarsAndTypes(
                java.util.List.of(boundVarName), ctx, bodyExp, guardPred);
        java.util.List<String> freeVarNames = ft[0];
        java.util.List<String> freeVarTypes = ft[1];

        // acslVariableLogicTypeFromTypref (não acslLogicTypeForValueTypref): conjuntos B diferidos
        // (ex. ISLAND) não têm tipo ACSL próprio declarado — a máquina declara-os como Set<integer>
        // (ver RulerOfTheSeas_ISLAND), logo o elemento tem de ser 'integer', não 'island'.
        String boundVarType = ctx.types().acslVariableLogicTypeFromTypref(boundVarTypref);

        String name = registry.registerSet(
                op, freeVarNames, freeVarTypes, sourceComment, boundVarType, bodyForX, dc.domainExprText());
        String freeArgs = freeVarNames.isEmpty() ? "" : String.join(", ", freeVarNames) + ", ";
        return name + "(" + freeArgs + dc.domainExprText() + ")";
    }

    /**
     * §4: duas variáveis ligadas — gera uma função interna (indexada pelo valor corrente da externa,
     * parâmetro extra {@code x}) e uma externa que a chama, ambas via {@link
     * #registerIntervalDomainSum}-like recursão em {@link SigmaFunctionRegistry#registerInterval}.
     * Suporta apenas a forma mais simples: ambas as variáveis com domínio {@code lo..hi} literal, sem
     * filtro adicional (os limites da interna podem referenciar a externa) — qualquer outra forma cai
     * em TODO, não é improvisada.
     */
    private static String translateNestedGeneralizedSum(
            SigmaFunctionRegistry.Op op, String outerVar, String innerVar, Element guardPred, Element bodyExp,
            BxmlTranslateContext ctx, SigmaFunctionRegistry registry) {
        java.util.List<Element> conjuncts = conjunctsOf(guardPred);
        String[] outerBounds = null;
        String[] innerBounds = null;
        java.util.List<Element> rest = new java.util.ArrayList<>();
        for (Element c : conjuncts) {
            if (outerBounds == null) {
                String[] b = intervalDomainBounds(c, outerVar, ctx);
                if (b != null) {
                    outerBounds = b;
                    continue;
                }
            }
            if (innerBounds == null) {
                String[] b = intervalDomainBounds(c, innerVar, ctx);
                if (b != null) {
                    innerBounds = b;
                    continue;
                }
            }
            rest.add(c);
        }
        if (outerBounds == null || innerBounds == null || !rest.isEmpty()) {
            return "/* TODO(b2acsl): domínio de " + opBSyntax(op) + "(" + outerVar + "," + innerVar
                    + ") não reconhecido — suportado apenas '" + outerVar + ":lo..hi & " + innerVar
                    + ":lo..hi' (sem filtro extra) */";
        }

        String bodyStr = translate(bodyExp, ctx);
        java.util.List<String>[] ft = freeVarsAndTypes(
                java.util.List.of(outerVar, innerVar), ctx, bodyExp, guardPred);
        java.util.List<String> freeVarNames = ft[0];
        java.util.List<String> freeVarTypes = ft[1];
        String freeArgs = freeVarNames.isEmpty() ? "" : String.join(", ", freeVarNames) + ", ";

        // Interna: parâmetro extra "x" representa o valor corrente da variável externa.
        java.util.List<String> innerFreeNames = new java.util.ArrayList<>(freeVarNames);
        innerFreeNames.add("x");
        java.util.List<String> innerFreeTypes = new java.util.ArrayList<>(freeVarTypes);
        innerFreeTypes.add("integer");
        String innerBodyForLo =
                replaceWordBoundary(replaceWordBoundary(bodyStr, innerVar, "lo"), outerVar, "x");
        String innerBodyForHi =
                replaceWordBoundary(replaceWordBoundary(bodyStr, innerVar, "hi"), outerVar, "x");
        String innerComment = opBSyntax(op) + "(" + innerVar + ") interno de "
                + describeGeneralizedSum(op, outerVar + "," + innerVar, guardPred, bodyExp, ctx);
        String innerName = registry.registerInterval(
                op, innerFreeNames, innerFreeTypes, innerComment, innerBodyForLo, innerBodyForHi, null, null,
                null, null);

        String innerLoAtLo = replaceWordBoundary(innerBounds[0], outerVar, "lo");
        String innerHiAtLo = replaceWordBoundary(innerBounds[1], outerVar, "lo");
        String innerLoAtHi = replaceWordBoundary(innerBounds[0], outerVar, "hi");
        String innerHiAtHi = replaceWordBoundary(innerBounds[1], outerVar, "hi");
        String outerBodyForLo = innerName + "(" + freeArgs + "lo, " + innerLoAtLo + ", " + innerHiAtLo + ")";
        String outerBodyForHi = innerName + "(" + freeArgs + "hi, " + innerLoAtHi + ", " + innerHiAtHi + ")";

        String outerComment = describeGeneralizedSum(op, outerVar + "," + innerVar, guardPred, bodyExp, ctx);
        String outerName = registry.registerInterval(
                op, freeVarNames, freeVarTypes, outerComment, outerBodyForLo, outerBodyForHi, null, null,
                null, null);
        return outerName + "(" + freeArgs + outerBounds[0] + ", " + outerBounds[1] + ")";
    }

    // ══════════════ UNION / INTER (Quantified_Exp type='UNION'/'INTER') ══════════════

    private static String unionInterOpName(UnionInterFunctionRegistry.Op op) {
        return op == UnionInterFunctionRegistry.Op.UNION ? "UNION" : "INTER";
    }

    /**
     * B {@code UNION/INTER(z).(P | E)} → função lógica nomeada registada em {@link
     * UnionInterFunctionRegistry}. {@code E} tem de ser set-valued (verificado aqui); classifica o
     * domínio {@code P} reusando {@link #classifyDomain} (mesma lógica de {@code
     * translateGeneralizedQuantifiedExp}) para escolher entre recursão em intervalo e
     * axiomatização sobre conjunto qualquer.
     */
    private static String translateUnionInterExp(
            Element qe, UnionInterFunctionRegistry.Op op, BxmlTranslateContext ctx) {
        Element varsEl = childByLocalName(qe, "Variables");
        java.util.List<String> boundVarNames = new java.util.ArrayList<>();
        java.util.List<Integer> boundVarTyprefs = new java.util.ArrayList<>();
        if (varsEl != null) {
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
        }
        if (boundVarNames.isEmpty()) {
            return "/* TODO(b2acsl): " + unionInterOpName(op) + " sem variáveis ligadas */";
        }

        Element guardEl = childByLocalName(qe, "Pred");
        Element guardPred = guardEl != null ? firstExpChild(guardEl) : null;

        Element bodyEl = childByLocalName(qe, "Body");
        if (bodyEl == null) return "/* TODO(b2acsl): " + unionInterOpName(op) + " sem corpo */";
        Element bodyExp = firstExpChild(bodyEl);
        if (bodyExp == null) return "/* TODO(b2acsl): " + unionInterOpName(op) + " sem corpo */";

        // isSetValued (genérico) só reconhece operadores ESTRUTURAIS de conjunto (união/interseção/
        // …), não uma aplicação funcional cujo CODOMÍNIO é um conjunto (ex.: player_islands(pp) :
        // Set<integer>, via player_islands : Relation<integer, Set<integer>>) — por isso a
        // verificação aqui é pelo TIPO (typref do próprio corpo), não pela forma estrutural.
        if (!isUnionInterBodySetValued(bodyExp, ctx)) {
            return "/* TODO(b2acsl): " + unionInterOpName(op)
                    + " com corpo não set-valued — construção B mal tipada, não improvisado */";
        }

        UnionInterFunctionRegistry registry = ctx.unionInterRegistry();
        if (registry == null) {
            return "/* TODO(b2acsl): " + unionInterOpName(op) + " sem registro no contexto */";
        }

        if (boundVarNames.size() > 1) {
            return "/* TODO(b2acsl): " + unionInterOpName(op)
                    + " com mais de 1 variável ligada não suportado */";
        }

        String boundVarName = boundVarNames.get(0);
        DomainClassification dc = classifyDomain(guardPred, boundVarName, ctx);
        if (dc == null) {
            return "/* TODO(b2acsl): domínio de " + unionInterOpName(op) + "(" + boundVarName
                    + ") não reconhecido */";
        }
        String elementType = elementTypeOfSetValuedExp(bodyExp, ctx);
        String sourceComment = describeUnionInter(op, boundVarName, guardPred, bodyExp, ctx);

        if (dc.kind() == DomainKind.SET) {
            return registerUnionInterSetDomain(
                    op, boundVarName, boundVarTyprefs.get(0), dc, bodyExp, guardPred, elementType,
                    sourceComment, ctx, registry);
        }
        return registerUnionInterIntervalDomain(
                op, boundVarName, dc, bodyExp, guardPred, elementType, sourceComment, ctx, registry);
    }

    /** Comentário {@code OP(z).(P | E)} citando a construção B de origem — ver {@link #describeGeneralizedSum}. */
    private static String describeUnionInter(
            UnionInterFunctionRegistry.Op op, String boundVarNames, Element guardPred, Element bodyExp,
            BxmlTranslateContext ctx) {
        String predText;
        try {
            predText = guardPred != null ? BxmlPredicateToAcsl.translatePropertyPred(guardPred, ctx) : "?";
        } catch (RuntimeException ex) {
            predText = "?";
        }
        String bodyText;
        try {
            bodyText = translate(bodyExp, ctx);
        } catch (RuntimeException ex) {
            bodyText = "?";
        }
        return unionInterOpName(op) + "(" + boundVarNames + ").(" + predText + " | " + bodyText + ")";
    }

    /**
     * {@code true} se o TIPO de {@code exp} (via o seu próprio {@code typref}) for {@code Set<...>}
     * — ao contrário de {@link #isSetValued}, que só reconhece operadores ESTRUTURAIS de conjunto
     * (união/interseção/…), esta verificação cobre também uma aplicação funcional cujo CODOMÍNIO é
     * um conjunto (ex.: {@code player_islands(pp)}, via {@code player_islands :
     * Relation<integer, Set<integer>>}), o caso mais comum de corpo de {@code UNION}/{@code INTER}.
     */
    private static boolean isUnionInterBodySetValued(Element exp, BxmlTranslateContext ctx) {
        String tr = exp.getAttribute("typref");
        if (tr.isBlank()) return false;
        int typref = Integer.parseInt(tr.trim());
        String full = ctx.types().acslVariableLogicTypeFromTypref(typref);
        return full != null && full.startsWith("Set<") && full.endsWith(">");
    }

    /**
     * Tipo ACSL do elemento de uma expressão set-valued (o {@code T} de {@code Set<T>}), a partir do
     * seu próprio {@code typref} (representa {@code POW(X)}) — {@link
     * BxmlTypeRegistry#acslVariableLogicTypeFromTypref} já resolve {@code POW(X)} para {@code
     * Set<...>}; extrai-se aqui só o elemento.
     */
    private static String elementTypeOfSetValuedExp(Element exp, BxmlTranslateContext ctx) {
        String tr = exp.getAttribute("typref");
        int typref = tr.isBlank() ? -1 : Integer.parseInt(tr.trim());
        String full = typref >= 0 ? ctx.types().acslVariableLogicTypeFromTypref(typref) : "Set<integer>";
        if (full.startsWith("Set<") && full.endsWith(">")) {
            return full.substring(4, full.length() - 1).trim();
        }
        return "integer";
    }

    /**
     * Nome B cru do elemento de uma expressão set-valued (ex. {@code ISLAND}, não {@code integer}) —
     * para procurar um universo tipado {@code U_T} em {@link BxmlTranslateContext#enumeratedSetRenames()}
     * (só tem entradas para conjuntos diferidos/enumerados da máquina, nunca para {@code INTEGER}/
     * {@code NAT}/{@code BOOL} — por isso a procura falha "naturalmente" para tipos sem universo finito).
     */
    private static String rawElementNameOfSetValuedExp(Element exp, BxmlTranslateContext ctx) {
        String tr = exp.getAttribute("typref");
        if (tr.isBlank()) return null;
        int typref = Integer.parseInt(tr.trim());
        return ctx.types().elementTypeNameForSetTypref(typref);
    }

    /** §2/§2b/§3/§3b: domínio-intervalo. */
    private static String registerUnionInterIntervalDomain(
            UnionInterFunctionRegistry.Op op, String boundVarName, DomainClassification dc, Element bodyExp,
            Element guardPred, String elementType, String sourceComment, BxmlTranslateContext ctx,
            UnionInterFunctionRegistry registry) {
        String bodyStr = translate(bodyExp, ctx);
        String bodyForLo = replaceWordBoundary(bodyStr, boundVarName, "lo");
        String bodyForHi = replaceWordBoundary(bodyStr, boundVarName, "hi");
        String filterForLo = dc.filterTemplate() != null
                ? replaceWordBoundary(dc.filterTemplate(), boundVarName, "lo") : null;
        String filterForHi = dc.filterTemplate() != null
                ? replaceWordBoundary(dc.filterTemplate(), boundVarName, "hi") : null;

        java.util.List<String>[] ft = freeVarsAndTypes(
                java.util.List.of(boundVarName), ctx, bodyExp, guardPred);
        java.util.List<String> freeVarNames = ft[0];
        java.util.List<String> freeVarTypes = ft[1];

        boolean hasUniverse = false;
        String universeExpr = null;
        if (op == UnionInterFunctionRegistry.Op.INTER) {
            String rawElem = rawElementNameOfSetValuedExp(bodyExp, ctx);
            String qualified = rawElem != null ? ctx.enumeratedSetRenames().get(rawElem) : null;
            if (qualified != null) {
                hasUniverse = true;
                universeExpr = qualified;
            }
        }

        String[] congr = UnionInterFunctionRegistry.detectCongrVar(bodyStr, freeVarNames);
        String congrVar = null;
        String congrType = null;
        if (congr != null) {
            congrVar = congr[0];
            int idx = freeVarNames.indexOf(congrVar);
            congrType = idx >= 0 && idx < freeVarTypes.size() ? freeVarTypes.get(idx) : "integer";
        }

        String name = registry.registerInterval(
                op, freeVarNames, freeVarTypes, sourceComment, elementType, bodyForLo, bodyForHi,
                filterForLo, filterForHi, hasUniverse, universeExpr, congrVar, congrType);
        String freeArgs = freeVarNames.isEmpty() ? "" : String.join(", ", freeVarNames) + ", ";
        return name + "(" + freeArgs + dc.lo() + ", " + dc.hi() + ")";
    }

    /** §4: domínio-conjunto qualquer. */
    private static String registerUnionInterSetDomain(
            UnionInterFunctionRegistry.Op op, String boundVarName, int boundVarTypref, DomainClassification dc,
            Element bodyExp, Element guardPred, String elementType, String sourceComment,
            BxmlTranslateContext ctx, UnionInterFunctionRegistry registry) {
        String bodyStr = translate(bodyExp, ctx);
        String bodyForX = replaceWordBoundary(bodyStr, boundVarName, "x");

        java.util.List<String>[] ft = freeVarsAndTypes(
                java.util.List.of(boundVarName), ctx, bodyExp, guardPred);
        java.util.List<String> freeVarNames = ft[0];
        java.util.List<String> freeVarTypes = ft[1];

        // Tipo do elemento do DOMÍNIO (não de E) — para \forall x/Set<T> dom da axiomatização §4.
        // Mesma resolução que SIGMA usa (registerSetDomainSum): deferred sets não têm tipo ACSL
        // próprio declarado (só Set<integer>), por isso acslVariableLogicTypeFromTypref (não
        // acslLogicTypeForValueTypref) — cai corretamente em "integer" via o seu fallback.
        String boundVarType = ctx.types().acslVariableLogicTypeFromTypref(boundVarTypref);

        String name = registry.registerSet(
                op, freeVarNames, freeVarTypes, sourceComment, elementType, boundVarType, bodyForX,
                dc.domainExprText());
        String freeArgs = freeVarNames.isEmpty() ? "" : String.join(", ", freeVarNames) + ", ";
        return name + "(" + freeArgs + dc.domainExprText() + ")";
    }

    /**
     * Forma "sequence builder": {@code % x.(x : lo..hi | E)} → função lógica recursiva sobre
     * {@code \list}, registada em {@link LambdaFunctionRegistry#registerSequenceBuilder}. Ver
     * {@link #translateQuantifiedExp}.
     */
    private static String translateSequenceBuilderLambda(
            String boundVarName, String loExpr, String hiExpr, Element bodyExp, Element guardPred,
            BxmlTranslateContext ctx) {
        String bodyStr = translate(bodyExp, ctx);

        // Variáveis livres: IDs no corpo E na guarda (que contém lo/hi, ex. card(array)) que não
        // são a variável ligada nem estado da PRÓPRIA máquina (reads directo, sem parametrizar).
        // Ao contrário do modelo de mapa, aqui NÃO se exclui crossMachineVariableNames: a função
        // gerada é pura e genérica (nunca usa reads de variável global — ver
        // LambdaFunctionRegistry#registerSequenceBuilder), logo uma variável de OUTRA máquina (ex.
        // array, de VArray, importada por Fifo_i_2) tem de entrar como parâmetro tipado explícito.
        java.util.LinkedHashSet<String> allIds = new java.util.LinkedHashSet<>();
        collectIdValues(bodyExp, allIds);
        if (guardPred != null) collectIdValues(guardPred, allIds);
        allIds.remove(boundVarName);
        allIds.removeAll(ctx.variableLogicTypes().keySet());
        allIds.removeAll(ctx.declaredSetNames());
        allIds.removeAll(B_NON_PARAM_IDS);
        java.util.List<String> freeVarNames = new java.util.ArrayList<>(allIds);
        java.util.List<String> freeVarTypes = new java.util.ArrayList<>();
        for (String v : freeVarNames) {
            String t = ctx.crossMachineVariableLogicTypes().get(v);
            freeVarTypes.add(t == null || t.isBlank() ? "integer" : t);
        }

        String bodyForLo = replaceWordBoundary(bodyStr, boundVarName, "lo");
        String bodyForLoPlusK = replaceWordBoundary(bodyStr, boundVarName, "(lo + k)");

        LambdaFunctionRegistry registry = ctx.lambdaRegistry();
        if (registry == null) {
            return "/* TODO: sequence lambda sem registro no contexto */";
        }
        String name = registry.registerSequenceBuilder(
                freeVarNames, freeVarTypes, "integer", bodyForLo, bodyForLoPlusK);
        java.util.List<String> args = new java.util.ArrayList<>(freeVarNames);
        args.add(loExpr);
        args.add(hiExpr);
        return name + "(" + String.join(", ", args) + ")";
    }

    /**
     * Forma "mapa": {@code % x.(P | E)} com domínio não-intervalo, ou múltiplas variáveis ligadas —
     * função/predicado ponto-a-ponto registada em {@link LambdaFunctionRegistry#register}/{@link
     * LambdaFunctionRegistry#registerFunction}. Comportamento anterior a {@link
     * #translateSequenceBuilderLambda} existir; ver {@link #translateQuantifiedExp}.
     */
    private static String translateMapLambda(
            java.util.List<String> boundVarNames, Element guardPred, Element bodyExp,
            BxmlTranslateContext ctx) {
        String guardStr = guardPred != null ? BxmlPredicateToAcsl.translatePropertyPred(guardPred, ctx) : null;

        boolean isBooleanBody = "Boolean_Exp".equals(bodyExp.getLocalName());
        String bodyStr;
        Element scanRoot; // elemento cuja sub-árvore será varrida para IDs livres
        if (isBooleanBody) {
            Element innerPred = firstExpChild(bodyExp);
            if (innerPred == null) return "/* TODO: lambda body */";
            bodyStr = BxmlPredicateToAcsl.translatePropertyPred(innerPred, ctx);
            scanRoot = innerPred;
        } else {
            bodyStr = translate(bodyExp, ctx);
            scanRoot = bodyExp;
        }

        // Variáveis livres: IDs no corpo E na guarda que não são ligadas nem estado
        // abstracto/concreto (ex.: card(array) na guarda referencia array, tal como o corpo pode).
        java.util.Set<String> boundSet = new java.util.LinkedHashSet<>(boundVarNames);
        java.util.LinkedHashSet<String> allIds = new java.util.LinkedHashSet<>();
        collectIdValues(scanRoot, allIds);
        if (guardPred != null) collectIdValues(guardPred, allIds);
        allIds.removeAll(boundSet);
        allIds.removeAll(ctx.variableLogicTypes().keySet());
        allIds.removeAll(ctx.crossMachineVariableNames());
        allIds.removeAll(ctx.declaredSetNames());
        allIds.removeAll(B_NON_PARAM_IDS);
        java.util.List<String> freeVarNames = new java.util.ArrayList<>(allIds);
        java.util.List<String> freeVarTypes = new java.util.ArrayList<>();
        for (String v : freeVarNames) {
            String t = ctx.variableLogicTypes().get(v);
            if (t == null || t.isBlank()) t = ctx.crossMachineVariableLogicTypes().get(v);
            freeVarTypes.add(t == null || t.isBlank() ? "integer" : t);
        }

        LambdaFunctionRegistry registry = ctx.lambdaRegistry();
        if (registry != null) {
            String name = isBooleanBody
                    ? registry.register(freeVarNames, freeVarTypes, boundVarNames, bodyStr, guardStr)
                    : registry.registerFunction(
                            freeVarNames, freeVarTypes, boundVarNames, bodyStr, "integer", guardStr);
            java.util.List<String> args = new java.util.ArrayList<>(freeVarNames);
            args.addAll(boundVarNames);
            return name + "(" + String.join(", ", args) + ")";
        }
        // Fallback inline (sem registro disponível no contexto)
        java.util.List<String> decls = new java.util.ArrayList<>();
        for (String v : boundVarNames) decls.add("integer " + v);
        return "\\lambda " + String.join(", ", decls) + "; " + bodyStr;
    }

    /** Substitui {@code name} por {@code replacement} em {@code text}, por fronteira de palavra. */
    private static String replaceWordBoundary(String text, String name, String replacement) {
        return text.replaceAll(
                "(?<![A-Za-z0-9_])" + java.util.regex.Pattern.quote(name) + "(?![A-Za-z0-9_])",
                java.util.regex.Matcher.quoteReplacement(replacement));
    }

    /** Recolhe recursivamente todos os valores {@code value} de nós {@code Id}. */
    private static void collectIdValues(Element e, java.util.Set<String> out) {
        if ("Id".equals(e.getLocalName())) {
            String v = e.getAttribute("value");
            if (v != null && !v.isBlank()) out.add(v.trim());
        }
        NodeList nl = e.getChildNodes();
        for (int i = 0; i < nl.getLength(); i++) {
            Node n = nl.item(i);
            if (n.getNodeType() != Node.ELEMENT_NODE) continue;
            collectIdValues((Element) n, out);
        }
    }

    /** Primeiro filho elemento cujo {@code localName} corresponda a {@code name}. */
    private static Element childByLocalName(Element parent, String name) {
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
     * exactamente um elemento, retorna a tradução desse elemento (descarta a envolvente singleton);
     * caso contrário traduz normalmente.
     */
    private static String unwrapSingletonOrTranslate(Element e, BxmlTranslateContext ctx) {
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
            if (elems.size() == 1) return translate(elems.get(0), ctx);
        }
        return translate(e, ctx);
    }
}
