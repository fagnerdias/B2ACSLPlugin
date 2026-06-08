package com.example.bxml;

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
                if ("ran".equals(op)) {
                    yield true;
                }
                if ("card".equals(op)) {
                    yield false;
                }
                yield false;
            }
            case "EmptySet" -> true;
            case "Quantified_Set" -> true;
            case "Binary_Exp" ->
                    isSetUnion(exp.getAttribute("op")) || isIntervalBinaryExp(exp);
            case "Nary_Exp" -> "{".equals(exp.getAttribute("op"));
            default -> false;
        };
    }

    /**
     * Variável de outra máquina (ex. {@code ss} no invariante de refinamento) não entra em
     * {@link BxmlTranslateContext#variableLogicTypes()}; usa-se então o {@code typref} do {@code Id} no BXML.
     */
    private static boolean isSetValuedId(Element idEl, BxmlTranslateContext ctx) {
        String name = idEl.getAttribute("value");
        if (isSetLikeVariableType(ctx.variableLogicTypes().get(name))) {
            return true;
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
        if (t != null && t.startsWith("\\list")) {
            return true;
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
        if (isRelationLikeVariableType(t)) {
            return true;
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
        if (isIntervalBinaryExp(e) || "Quantified_Set".equals(e.getLocalName())) {
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

    /** B diferença de conjuntos {@code s -s t} → {@code difference(s, t)} (ACSL_Lib/set_functions/difference.acsl). */
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
                // Valores enumerados: usar o nome prefixado (ex. switch__normal)
                String renamed = ctx.enumValueRenames().get(idVal);
                if (renamed != null) yield renamed;
                // Conjuntos enumerados: usar o nome prefixado (ex. ctx__ALARM_STATUS)
                String setRenamed = ctx.enumeratedSetRenames().get(idVal);
                if (setRenamed != null) yield setRenamed;
                // Parâmetros/variáveis de tipo enum C: cast (integer) para compatibilidade com Set<integer>
                if (!ctx.enumeratedSetNames().isEmpty()) {
                    String tr = exp.getAttribute("typref");
                    if (!tr.isBlank()) {
                        String bTypeName = ctx.types().getRawType(Integer.parseInt(tr.trim()));
                        if (ctx.enumeratedSetNames().contains(bTypeName)) {
                            yield "(integer)" + translateBNamedConstant(idVal);
                        }
                    }
                }
                yield translateBNamedConstant(idVal);
            }
            case "Integer_Literal" -> exp.getAttribute("value");
            case "Boolean_Literal" -> translateBooleanLiteral(exp.getAttribute("value"));
            case "EmptySet" -> translateEmptySet(exp, ctx.types());
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

    private static String translateEmptySet(Element emptySet, BxmlTypeRegistry types) {
        String tr = emptySet.getAttribute("typref");
        int typref = tr.isBlank() ? -1 : Integer.parseInt(tr.trim());
        String elem = typref >= 0 ? types.elementTypeNameForSetTypref(typref) : "NAT";
        // ACSL_Lib/set_functions/empty.acsl — lógica overloaded: empty(Set<τ> witness)
        return "empty(" + elem + ")";
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
            return "difference(" + left + ", " + right + ")";
        }
        if (isSetUnion(op)) {
            // B: \/ — união → set_union (ACSL_Lib/set_functions/union.acsl)
            return "set_union(" + left + ", " + right + ")";
        }
        if ("..".equals(op == null ? "" : op.trim())) {
            String named = ctx.comprehensions().referenceName(ctx.machineName(), b);
            return named != null ? named : "/* interval .. */";
        }
        if (isSequenceAppendOp(op)) {
            return "(" + "\\concat(" + left + ", [|" + right + "|])" + ")";
        }
        if (isSequencePrependOp(op)) {
            return "\\concat([|" + left + "|], " + right + ")";
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
            java.util.List<String> parts = new java.util.ArrayList<>();
            NodeList children = n.getChildNodes();
            for (int i = 0; i < children.getLength(); i++) {
                Node node = children.item(i);
                if (node.getNodeType() != Node.ELEMENT_NODE) continue;
                Element e = (Element) node;
                if ("Attr".equals(e.getLocalName())) continue;
                parts.add(translate(e, ctx));
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
     * B lambda {@code % x.(P | E)} → predicado nomeado registado em {@link LambdaFunctionRegistry}.
     *
     * <p>Se o corpo for um {@code Boolean_Exp}, o predicado interno é usado directamente como corpo
     * do predicado ACSL. Caso contrário usa {@code \lambda} inline como fallback.
     *
     * <p>As variáveis livres são detectadas varrendo os nós {@code Id} do corpo e subtraindo as
     * variáveis ligadas e os identificadores de estado abstracto/concreto em
     * {@link BxmlTranslateContext#variableLogicTypes()}.
     */
    private static String translateQuantifiedExp(Element qe, BxmlTranslateContext ctx) {
        String type = qe.getAttribute("type");
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

        // 2. Corpo da lambda
        Element bodyEl = childByLocalName(qe, "Body");
        if (bodyEl == null) return "/* TODO: lambda body */";
        Element bodyExp = firstExpChild(bodyEl);
        if (bodyExp == null) return "/* TODO: lambda body */";

        // 3. Determina se o corpo é Boolean_Exp → predicado directo
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

        // 4. Variáveis livres: IDs no corpo que não são ligadas nem estado abstracto/concreto
        java.util.Set<String> boundSet = new java.util.LinkedHashSet<>(boundVarNames);
        java.util.LinkedHashSet<String> allIds = new java.util.LinkedHashSet<>();
        collectIdValues(scanRoot, allIds);
        allIds.removeAll(boundSet);
        allIds.removeAll(ctx.variableLogicTypes().keySet());
        allIds.removeAll(B_NON_PARAM_IDS);
        java.util.List<String> freeVarNames = new java.util.ArrayList<>(allIds);

        // 5. Regista no LambdaFunctionRegistry ou cai no inline \lambda
        LambdaFunctionRegistry registry = ctx.lambdaRegistry();
        if (registry != null && isBooleanBody) {
            String name = registry.register(freeVarNames, boundVarNames, bodyStr);
            java.util.List<String> args = new java.util.ArrayList<>(freeVarNames);
            args.addAll(boundVarNames);
            return name + "(" + String.join(", ", args) + ")";
        }
        // Fallback inline
        java.util.List<String> decls = new java.util.ArrayList<>();
        for (String v : boundVarNames) decls.add("integer " + v);
        return "\\lambda " + String.join(", ", decls) + "; " + bodyStr;
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
