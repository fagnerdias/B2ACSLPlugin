package com.example.acsl;

import com.example.acsl.Expr.Kind;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Reimprime um termo ou predicado ACSL ({@link AcslParser}) na convenção de parênteses do printer
 * do B2ACSLRules ({@code ACSL2Text.atl}), para os dois geradores escreverem o mesmo texto:
 *
 * <ul>
 *   <li>a cláusula não leva parênteses externos;
 *   <li>{@code &&}/{@code ||} encadeados no mesmo operador saem soltos; entre conectivos
 *       ({@code <==>} &lt; {@code ==>} &lt; {@code ||} &lt; {@code &&}), o filho que liga mais forte
 *       que o pai sai solto, o outro entre parênteses ({@code a ==> (b ==> c)});
 *   <li>a cadeia {@code lo <= x <= hi} sai solta dentro de um conectivo;
 *   <li>qualquer outro operador binário como operando vai entre parênteses: {@code (a == b) && c},
 *       {@code (9 - 0) + 1};
 *   <li>ternário, quantificador e {@code \let} como operando vão entre parênteses; o corpo do
 *       quantificador nunca;
 *   <li>operando de unário e de cast só entre parênteses se for composto: {@code !(x > 0)},
 *       {@code !equals(a, b)}, {@code (int*)p};
 *   <li>argumentos de função e índices nunca levam parênteses.
 * </ul>
 */
public final class AcslPrinter {

    private static final Map<String, Integer> CONNECTIVE_PRECEDENCE =
            Map.of("<==>", 1, "==>", 2, "||", 3, "&&", 4);
    private static final Set<String> COMPARISONS = Set.of("==", "!=", "<", "<=", ">", ">=");

    private AcslPrinter() {}

    /** Texto de uma cláusula, axioma ou lema: sem parênteses externos. */
    public static String print(Expr e) {
        return switch (e.kind) {
            case ID, NUM -> e.op;
            case RAW -> e.sourceText != null ? e.sourceText : e.op;
            case EMPTY -> "";
            case CALL -> e.op + "(" + join(e.args) + ")";
            case BIN -> operand(e.args.get(0), e.op) + " " + e.op + " " + operand(e.args.get(1), e.op);
            case CHAIN -> chain(e);
            case UN -> e.op + paren(e.args.get(0));
            case CAST -> "(" + e.op + ")" + paren(e.args.get(0));
            case TERNARY -> paren(e.args.get(0)) + " ? " + paren(e.args.get(1)) + " : " + paren(e.args.get(2));
            case BINDER -> e.op + " " + binderList(e) + "; " + print(e.args.get(0));
            case LET -> "\\let " + e.op + " = " + print(e.args.get(0)) + "; " + paren(e.args.get(1));
            case INDEX -> paren(e.args.get(0)) + "[" + print(e.args.get(1)) + "]";
            case FIELD -> paren(e.args.get(0)) + e.op;
            case RANGE -> range(e);
            case LIST -> "[| " + join(e.args) + " |]";
            case AND -> String.join(" && ", e.args.stream().map(a -> operand(a, "&&")).toList());
            case OR -> String.join(" || ", e.args.stream().map(a -> operand(a, "||")).toList());
        };
    }

    /** Corpo de {@code predicate P = ...} fora de um axiomatic: uma comparação vai entre parênteses. */
    public static String printTopLevelPredicateBody(Expr e) {
        return isComparison(e) ? "(" + print(e) + ")" : print(e);
    }

    /** Corpo de {@code logic T f(...) = ...}: um ternário vai entre parênteses. */
    public static String printLogicBody(Expr e) {
        return e.kind == Kind.TERNARY ? "(" + print(e) + ")" : print(e);
    }

    private static String operand(Expr c, String parentOp) {
        if (!isBinaryLike(c)) {
            return paren(c);
        }
        boolean parentConnective = CONNECTIVE_PRECEDENCE.containsKey(parentOp);
        if (c.kind == Kind.BIN && c.op.equals(parentOp) && (parentOp.equals("&&") || parentOp.equals("||"))) {
            return print(c);
        }
        if (parentConnective && c.kind == Kind.CHAIN) {
            return print(c);
        }
        if (parentConnective && c.kind == Kind.BIN && CONNECTIVE_PRECEDENCE.containsKey(c.op)
                && CONNECTIVE_PRECEDENCE.get(c.op) > CONNECTIVE_PRECEDENCE.get(parentOp)) {
            return print(c);
        }
        return "(" + print(c) + ")";
    }

    private static String paren(Expr c) {
        return isBinaryLike(c)
                        || c.kind == Kind.TERNARY
                        || c.kind == Kind.BINDER
                        || c.kind == Kind.LET
                ? "(" + print(c) + ")"
                : print(c);
    }

    private static boolean isBinaryLike(Expr c) {
        return c.kind == Kind.BIN || c.kind == Kind.CHAIN || c.kind == Kind.RANGE
                || c.kind == Kind.AND || c.kind == Kind.OR;
    }

    private static boolean isComparison(Expr e) {
        return e.kind == Kind.CHAIN || (e.kind == Kind.BIN && COMPARISONS.contains(e.op));
    }

    /** {@code lo <= x <= hi}: cada elemento como operando de uma comparação. */
    private static String chain(Expr e) {
        String[] ops = e.op.split(",");
        StringBuilder b = new StringBuilder(operand(e.args.get(0), ops[0]));
        for (int i = 0; i < ops.length; i++) {
            b.append(' ').append(ops[i]).append(' ').append(operand(e.args.get(i + 1), ops[i]));
        }
        return b.toString();
    }

    /** {@code lo .. hi}, extremos sem parênteses (como os índices); {@code ..} sem extremos. */
    private static String range(Expr e) {
        String lo = print(e.args.get(0));
        String hi = print(e.args.get(1));
        if (lo.isEmpty() && hi.isEmpty()) {
            return "..";
        }
        return (lo.isEmpty() ? "" : lo + " ") + ".." + (hi.isEmpty() ? "" : " " + hi);
    }

    /** A lista de binders como veio no texto ({@code integer lo, hi}). */
    private static String binderList(Expr e) {
        if (e.sourceText != null) {
            return e.sourceText;
        }
        StringBuilder b = new StringBuilder();
        for (Expr.Decl d : e.decls) {
            if (b.length() > 0) b.append(", ");
            b.append(d.type()).append(' ').append(d.name());
        }
        return b.toString();
    }

    private static String join(List<Expr> args) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < args.size(); i++) {
            if (i > 0) b.append(", ");
            b.append(print(args.get(i)));
        }
        return b.toString();
    }
}
