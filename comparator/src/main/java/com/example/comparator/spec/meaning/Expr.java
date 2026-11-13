package com.example.comparator.spec.meaning;

import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Node of a parsed ACSL term or predicate. The parser builds trees with source spans; {@link
 * Canonicalizer} builds new trees (spans unset) in canonical form, and {@link #print} turns them
 * into the strings that are compared.
 */
final class Expr {

    enum Kind {
        /** identifier or keyword constant ({@code \true}, {@code \result}); {@code op} = name */
        ID,
        /** number, character or string literal; {@code op} = text */
        NUM,
        /** {@code op(args)} */
        CALL,
        /** binary operator {@code op} */
        BIN,
        /** {@code a <= b < c}: operands in {@code args}, operators in {@code op}, comma-separated */
        CHAIN,
        /** prefix operator {@code op} */
        UN,
        /** {@code args[0] ? args[1] : args[2]} */
        TERNARY,
        /** {@code \forall}/{@code \exists}/{@code \lambda} ({@code op}) over {@code decls}; body in {@code args[0]} */
        BINDER,
        /** {@code \let op = args[0]; args[1]} */
        LET,
        /** {@code args[0][args[1]]} */
        INDEX,
        /** {@code args[0]} followed by {@code op} ({@code .f} or {@code ->f}) */
        FIELD,
        /** {@code (op) args[0]} */
        CAST,
        /** {@code args[0] .. args[1]} (either may be {@link #EMPTY}) */
        RANGE,
        /** {@code [| args |]} */
        LIST,
        /** text kept as is ({@code { ... }} set expressions) */
        RAW,
        /** canonical n-ary conjunction */
        AND,
        /** canonical n-ary disjunction */
        OR,
        /** missing operand of a range */
        EMPTY
    }

    record Decl(String type, String name) {}

    static final Expr EMPTY = new Expr(Kind.EMPTY, "", List.of(), List.of());

    final Kind kind;
    final String op;
    final List<Expr> args;
    final List<Decl> decls;
    /** Source span (character offsets in the parsed text); -1 for built nodes. */
    int start = -1;
    int end = -1;
    /** The source wrote this node inside parentheses (a relation chain never continues through them). */
    boolean parenthesized;

    private String printed;

    private Expr(Kind kind, String op, List<Expr> args, List<Decl> decls) {
        this.kind = kind;
        this.op = op;
        this.args = args;
        this.decls = decls;
    }

    static Expr id(String name) {
        return new Expr(Kind.ID, name, List.of(), List.of());
    }

    static Expr num(String text) {
        return new Expr(Kind.NUM, text, List.of(), List.of());
    }

    static Expr call(String name, List<Expr> args) {
        return new Expr(Kind.CALL, name, List.copyOf(args), List.of());
    }

    static Expr bin(String op, Expr l, Expr r) {
        return new Expr(Kind.BIN, op, List.of(l, r), List.of());
    }

    static Expr chain(List<Expr> operands, List<String> ops) {
        return new Expr(Kind.CHAIN, String.join(",", ops), List.copyOf(operands), List.of());
    }

    static Expr un(String op, Expr x) {
        return new Expr(Kind.UN, op, List.of(x), List.of());
    }

    static Expr ternary(Expr c, Expr a, Expr b) {
        return new Expr(Kind.TERNARY, "?", List.of(c, a, b), List.of());
    }

    static Expr binder(String binder, List<Decl> decls, Expr body) {
        return new Expr(Kind.BINDER, binder, List.of(body), List.copyOf(decls));
    }

    static Expr let(String name, Expr value, Expr body) {
        return new Expr(Kind.LET, name, List.of(value, body), List.of());
    }

    static Expr index(Expr array, Expr index) {
        return new Expr(Kind.INDEX, "[]", List.of(array, index), List.of());
    }

    static Expr field(Expr object, String access) {
        return new Expr(Kind.FIELD, access, List.of(object), List.of());
    }

    static Expr cast(String type, Expr x) {
        return new Expr(Kind.CAST, type, List.of(x), List.of());
    }

    static Expr range(Expr lo, Expr hi) {
        return new Expr(Kind.RANGE, "..", List.of(lo, hi), List.of());
    }

    static Expr list(List<Expr> items) {
        return new Expr(Kind.LIST, "[||]", List.copyOf(items), List.of());
    }

    static Expr raw(String text) {
        return new Expr(Kind.RAW, text, List.of(), List.of());
    }

    static Expr and(List<Expr> parts) {
        return new Expr(Kind.AND, "&&", List.copyOf(parts), List.of());
    }

    static Expr or(List<Expr> parts) {
        return new Expr(Kind.OR, "||", List.copyOf(parts), List.of());
    }

    boolean isId(String name) {
        return kind == Kind.ID && op.equals(name);
    }

    boolean isBin(String operator) {
        return kind == Kind.BIN && op.equals(operator);
    }

    /** Canonical text; bound variables are renumbered {@code _v0}, {@code _v1}, ... in order of appearance. */
    String print() {
        if (printed == null) {
            printed = new Printer().print(this);
        }
        return printed;
    }

    @Override
    public String toString() {
        return print();
    }

    /** Names of the identifiers that occur in this tree (bound or free). */
    void collectIdentifiers(java.util.Set<String> out) {
        if (kind == Kind.ID) {
            out.add(op);
        }
        for (Expr a : args) {
            a.collectIdentifiers(out);
        }
    }

    private static final class Printer {
        private final Deque<Map<String, String>> scopes = new ArrayDeque<>();
        private int counter;

        String print(Expr e) {
            return switch (e.kind) {
                case ID -> lookup(e.op);
                case NUM, RAW -> e.op;
                case EMPTY -> "";
                case CALL -> e.op + "(" + join(e.args, ",") + ")";
                case BIN -> "(" + print(e.args.get(0)) + e.op + print(e.args.get(1)) + ")";
                case CHAIN -> chain(e);
                case UN -> e.op + print(e.args.get(0));
                case TERNARY -> "(" + print(e.args.get(0)) + "?" + print(e.args.get(1)) + ":" + print(e.args.get(2)) + ")";
                case BINDER -> binder(e);
                case LET -> let(e);
                case INDEX -> print(e.args.get(0)) + "[" + print(e.args.get(1)) + "]";
                case FIELD -> print(e.args.get(0)) + e.op;
                case CAST -> "(" + e.op + ")" + print(e.args.get(0));
                case RANGE -> print(e.args.get(0)) + ".." + print(e.args.get(1));
                case LIST -> "[|" + join(e.args, ",") + "|]";
                case AND -> "(" + join(e.args, "&&") + ")";
                case OR -> "(" + join(e.args, "||") + ")";
            };
        }

        private String chain(Expr e) {
            String[] ops = e.op.split(",");
            StringBuilder b = new StringBuilder("(").append(print(e.args.get(0)));
            for (int i = 0; i < ops.length; i++) {
                b.append(ops[i]).append(print(e.args.get(i + 1)));
            }
            return b.append(")").toString();
        }

        private String binder(Expr e) {
            Map<String, String> scope = new HashMap<>();
            List<String> decls = new ArrayList<>();
            for (Decl d : e.decls) {
                String fresh = "_v" + counter++;
                scope.put(d.name(), fresh);
                decls.add(d.type() + " " + fresh);
            }
            scopes.push(scope);
            String body = print(e.args.get(0));
            scopes.pop();
            return "(" + e.op + " " + String.join(",", decls) + ";" + body + ")";
        }

        private String let(Expr e) {
            String value = print(e.args.get(0));
            String fresh = "_v" + counter++;
            scopes.push(Map.of(e.op, fresh));
            String body = print(e.args.get(1));
            scopes.pop();
            return "(\\let " + fresh + "=" + value + ";" + body + ")";
        }

        private String lookup(String name) {
            for (Map<String, String> scope : scopes) {
                String mapped = scope.get(name);
                if (mapped != null) {
                    return mapped;
                }
            }
            return name;
        }

        private String join(List<Expr> items, String separator) {
            StringBuilder b = new StringBuilder();
            for (int i = 0; i < items.size(); i++) {
                if (i > 0) {
                    b.append(separator);
                }
                b.append(print(items.get(i)));
            }
            return b.toString();
        }
    }
}
