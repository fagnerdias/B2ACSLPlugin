package com.example.comparator.spec.meaning;

import com.example.comparator.spec.meaning.AcslLexer.ParseException;
import com.example.comparator.spec.meaning.AcslLexer.Token;
import com.example.comparator.spec.meaning.AcslLexer.Type;
import com.example.comparator.spec.meaning.Expr.Decl;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Pratt parser for the ACSL terms and predicates the two translators write, with the operator
 * precedence of the ACSL manual (binders lowest, then {@code ? :}, {@code <==>}, {@code ==>}
 * (right-associative), {@code ||}, {@code ^^}, {@code &&}, bitwise, equality, order, shifts,
 * additive, multiplicative, unary, postfix). {@code a <= b <= c} becomes a {@link Expr.Kind#CHAIN}.
 *
 * <p>Also reads the headers of {@code predicate}/{@code logic} declarations ({@link #header}).
 */
final class AcslParser {

    /** Words that make {@code (...)} a cast rather than a parenthesized expression. */
    static final Set<String> TYPE_WORDS =
            Set.of(
                    "integer", "real", "boolean", "int", "unsigned", "signed", "char", "short", "long", "float",
                    "double", "_Bool", "bool", "void", "size_t", "int8_t", "int16_t", "int32_t", "int64_t",
                    "uint8_t", "uint16_t", "uint32_t", "uint64_t");

    private static final Set<String> ORDER = Set.of("<", "<=", ">", ">=");
    private static final int RANGE_BP = 5;
    private static final int TERNARY_BP = 10;
    private static final int UNARY_BP = 130;

    private final String src;
    private final List<Token> tokens;
    private int p;

    private AcslParser(String src) {
        this.src = src;
        this.tokens = AcslLexer.lex(src);
    }

    /** Parses the whole text as one term or predicate. */
    static Expr parse(String text) {
        AcslParser parser = new AcslParser(text);
        Expr e = parser.expr(0);
        if (parser.peek().type() != Type.EOF) {
            throw new ParseException("unexpected '" + parser.peek().text() + "'");
        }
        return e;
    }

    // ---- expressions --------------------------------------------------------------------------

    private Expr expr(int minBp) {
        int from = p;
        Expr left = prefix();
        while (true) {
            Token t = peek();
            if (t.type() != Type.OP) {
                return left;
            }
            String op = t.text();
            if (op.equals("[")) {
                p++;
                Expr index = expr(0);
                expect("]");
                left = span(Expr.index(left, index), from);
                continue;
            }
            if (op.equals(".") || op.equals("->")) {
                p++;
                left = span(Expr.field(left, op + expectIdent().text()), from);
                continue;
            }
            boolean shift = (op.equals("<") || op.equals(">")) && peek(1).is(op) && !peek(1).spaceBefore();
            if (shift) {
                op = op + op;
            }
            int lbp = infixBp(op);
            if (lbp < 0 || lbp < minBp) {
                return left;
            }
            p += shift ? 2 : 1;
            switch (op) {
                case "?" -> {
                    Expr a = expr(0);
                    expect(":");
                    Expr b = expr(TERNARY_BP);
                    left = span(Expr.ternary(left, a, b), from);
                }
                case ".." -> {
                    Expr hi = atRangeEnd() ? Expr.EMPTY : expr(RANGE_BP + 1);
                    left = span(Expr.range(left, hi), from);
                }
                default -> {
                    boolean rightAssoc = op.equals("==>") || op.equals("-->");
                    Expr rhs = expr(rightAssoc ? lbp : lbp + 1);
                    left = span(binary(op, left, rhs), from);
                }
            }
        }
    }

    private static int infixBp(String op) {
        return switch (op) {
            case ".." -> RANGE_BP;
            case "?" -> TERNARY_BP;
            case "<==>" -> 20;
            case "==>" -> 30;
            case "||" -> 40;
            case "^^" -> 45;
            case "&&" -> 50;
            case "<-->" -> 52;
            case "-->" -> 54;
            case "|" -> 60;
            case "^" -> 65;
            case "&" -> 70;
            case "==", "!=" -> 80;
            case "<", "<=", ">", ">=" -> 90;
            case "<<", ">>" -> 100;
            case "+", "-" -> 110;
            case "*", "/", "%" -> 120;
            default -> -1;
        };
    }

    /** {@code a <= b <= c} (not parenthesized) continues a relation chain. */
    private static Expr binary(String op, Expr l, Expr r) {
        if (ORDER.contains(op) && !l.parenthesized) {
            if (l.kind == Expr.Kind.CHAIN) {
                List<Expr> operands = new ArrayList<>(l.args);
                operands.add(r);
                List<String> ops = new ArrayList<>(List.of(l.op.split(",")));
                ops.add(op);
                return Expr.chain(operands, ops);
            }
            if (l.kind == Expr.Kind.BIN && ORDER.contains(l.op)) {
                return Expr.chain(List.of(l.args.get(0), l.args.get(1), r), List.of(l.op, op));
            }
        }
        return Expr.bin(op, l, r);
    }

    private Expr prefix() {
        int from = p;
        Token t = next();
        if (t.type() == Type.LITERAL) {
            return span(Expr.num(t.text()), from);
        }
        if (t.type() == Type.IDENT) {
            String name = t.text();
            switch (name) {
                case "\\forall", "\\exists", "\\lambda" -> {
                    List<Decl> decls = decls();
                    expect(";");
                    Expr body = expr(0);
                    return span(Expr.binder(name, decls, body), from);
                }
                case "\\let" -> {
                    String variable = expectIdent().text();
                    expect("=");
                    Expr value = expr(0);
                    expect(";");
                    Expr body = expr(0);
                    return span(Expr.let(variable, value, body), from);
                }
                default -> {
                    if (peek().is("(")) {
                        p++;
                        return span(Expr.call(name, arguments(")")), from);
                    }
                    return span(Expr.id(name), from);
                }
            }
        }
        if (t.type() == Type.OP) {
            switch (t.text()) {
                case "(" -> {
                    if (isCast()) {
                        String type = typeUntilClose();
                        return span(Expr.cast(type, expr(UNARY_BP)), from);
                    }
                    Expr inner = expr(0);
                    expect(")");
                    if (inner != Expr.EMPTY) {
                        inner.parenthesized = true;
                    }
                    return inner;
                }
                case "[|" -> {
                    return span(Expr.list(arguments("|]")), from);
                }
                case "{" -> {
                    return span(Expr.raw(balanced(t)), from);
                }
                case ".." -> {
                    Expr hi = atRangeEnd() ? Expr.EMPTY : expr(RANGE_BP + 1);
                    return span(Expr.range(Expr.EMPTY, hi), from);
                }
                case "!", "-", "+", "~", "*", "&" -> {
                    return span(Expr.un(t.text(), expr(UNARY_BP)), from);
                }
                default -> {
                    // falls through to the error below
                }
            }
        }
        throw new ParseException("unexpected '" + t.text() + "'");
    }

    private List<Expr> arguments(String close) {
        List<Expr> args = new ArrayList<>();
        if (peek().is(close)) {
            p++;
            return args;
        }
        while (true) {
            args.add(expr(0));
            if (peek().is(",")) {
                p++;
            } else {
                expect(close);
                return args;
            }
        }
    }

    /** {@code integer x, y} / {@code Set<Tuple<A,B> > s, integer i}: a missing type repeats the previous one. */
    private List<Decl> decls() {
        List<List<Token>> parts = new ArrayList<>();
        List<Token> current = new ArrayList<>();
        int depth = 0;
        while (true) {
            Token t = peek();
            if (t.type() == Type.EOF) {
                throw new ParseException("binder without ';'");
            }
            if (depth == 0 && t.is(";")) {
                break;
            }
            if (t.is("<") || t.is("(")) {
                depth++;
            } else if (t.is(">") || t.is(")")) {
                depth--;
            }
            if (depth == 0 && t.is(",")) {
                parts.add(current);
                current = new ArrayList<>();
            } else {
                current.add(t);
            }
            p++;
        }
        parts.add(current);
        List<Decl> decls = new ArrayList<>();
        String previousType = null;
        for (List<Token> part : parts) {
            if (part.isEmpty() || part.get(part.size() - 1).type() != Type.IDENT) {
                throw new ParseException("bad binder declaration");
            }
            String name = part.get(part.size() - 1).text();
            String type = part.size() > 1 ? compact(part.subList(0, part.size() - 1)) : previousType;
            if (type == null) {
                throw new ParseException("binder variable without type");
            }
            decls.add(new Decl(type, name));
            previousType = type;
        }
        return decls;
    }

    /** At {@code (}: a cast if the parenthesized tokens are C/ACSL type words (and {@code *}) followed by an operand. */
    private boolean isCast() {
        int j = p;
        boolean word = false;
        while (j < tokens.size()) {
            Token t = tokens.get(j);
            if (t.is(")")) {
                break;
            }
            if (t.type() == Type.IDENT && TYPE_WORDS.contains(t.text())) {
                word = true;
            } else if (!t.is("*")) {
                return false;
            }
            j++;
        }
        if (!word || j + 1 >= tokens.size()) {
            return false;
        }
        Token after = tokens.get(j + 1);
        return after.isWord() || after.is("(") || after.is("!") || after.is("-") || after.is("+") || after.is("~")
                || after.is("*") || after.is("&") || after.is("[|");
    }

    private String typeUntilClose() {
        List<Token> type = new ArrayList<>();
        while (!peek().is(")")) {
            type.add(next());
        }
        p++;
        return compact(type);
    }

    /** Text of a {@code { ... }} group, kept as is (only spacing is normalized). */
    private String balanced(Token open) {
        int depth = 1;
        List<Token> inner = new ArrayList<>();
        while (depth > 0) {
            Token t = next();
            if (t.type() == Type.EOF) {
                throw new ParseException("unbalanced '{'");
            }
            if (t.is("{")) {
                depth++;
            } else if (t.is("}")) {
                depth--;
            }
            if (depth > 0) {
                inner.add(t);
            }
        }
        return "{" + compact(inner) + "}";
    }

    private boolean atRangeEnd() {
        Token t = peek();
        return t.type() == Type.EOF || t.is("]") || t.is(")") || t.is(",");
    }

    // ---- declarations -------------------------------------------------------------------------

    /**
     * Header of {@code predicate N<G>(T x, ...) reads l, ... = body} or {@code logic T N<G>(...) ...
     * = body}; {@code bodyOffset} is -1 without {@code =}.
     */
    record Header(
            String keyword,
            String type,
            String name,
            List<String> generics,
            List<Decl> params,
            List<String> reads,
            int bodyOffset) {}

    static Header header(String text) {
        AcslParser parser = new AcslParser(text);
        return parser.header();
    }

    private Header header() {
        String keyword = expectIdent().text();
        String type = null;
        if (keyword.equals("logic")) {
            type = typeAt();
        } else if (!keyword.equals("predicate")) {
            throw new ParseException("not a predicate or logic declaration");
        }
        String name = expectIdent().text();
        List<String> generics = new ArrayList<>();
        if (peek().is("<")) {
            p++;
            while (!peek().is(">")) {
                Token t = next();
                if (t.type() == Type.IDENT) {
                    generics.add(t.text());
                } else if (!t.is(",")) {
                    throw new ParseException("bad generic parameters");
                }
            }
            p++;
        }
        List<Decl> params = new ArrayList<>();
        if (peek().is("(")) {
            p++;
            if (!peek().is(")")) {
                params = paramDecls();
            } else {
                p++;
            }
        }
        List<String> reads = new ArrayList<>();
        if (peek().type() == Type.IDENT && peek().text().equals("reads")) {
            p++;
            List<Token> current = new ArrayList<>();
            int depth = 0;
            while (peek().type() != Type.EOF && !(depth == 0 && peek().is("="))) {
                Token t = next();
                if (t.is("(") || t.is("[")) {
                    depth++;
                } else if (t.is(")") || t.is("]")) {
                    depth--;
                }
                if (depth == 0 && t.is(",")) {
                    reads.add(compact(current));
                    current = new ArrayList<>();
                } else {
                    current.add(t);
                }
            }
            reads.add(compact(current));
        }
        int bodyOffset = -1;
        if (peek().is("=")) {
            bodyOffset = next().end();
        } else if (peek().type() != Type.EOF) {
            throw new ParseException("unexpected '" + peek().text() + "' in declaration");
        }
        return new Header(keyword, type, name, generics, params, reads, bodyOffset);
    }

    /** Parameters after {@code (} up to the closing {@code )}, which is consumed. */
    private List<Decl> paramDecls() {
        List<Decl> params = new ArrayList<>();
        List<Token> current = new ArrayList<>();
        int depth = 0;
        while (true) {
            Token t = next();
            if (t.type() == Type.EOF) {
                throw new ParseException("unterminated parameter list");
            }
            if (depth == 0 && (t.is(",") || t.is(")"))) {
                if (current.size() < 2 || current.get(current.size() - 1).type() != Type.IDENT) {
                    throw new ParseException("bad parameter");
                }
                params.add(new Decl(
                        compact(current.subList(0, current.size() - 1)), current.get(current.size() - 1).text()));
                current = new ArrayList<>();
                if (t.is(")")) {
                    return params;
                }
                continue;
            }
            if (t.is("<") || t.is("(")) {
                depth++;
            } else if (t.is(">") || t.is(")")) {
                depth--;
            }
            current.add(t);
        }
    }

    /** A type at the current position: {@code Name}, {@code Name<T, ...>} or {@code \list<T>}, then {@code *}s. */
    private String typeAt() {
        List<Token> type = new ArrayList<>();
        type.add(expectIdent());
        if (peek().is("<")) {
            int depth = 0;
            do {
                Token t = next();
                if (t.is("<")) {
                    depth++;
                } else if (t.is(">")) {
                    depth--;
                } else if (t.type() == Type.EOF) {
                    throw new ParseException("unterminated type");
                }
                type.add(t);
            } while (depth > 0);
        }
        while (peek().is("*")) {
            type.add(next());
        }
        return compact(type);
    }

    // ---- helpers ------------------------------------------------------------------------------

    /** Token texts joined with a space only between two words ({@code unsigned int}, {@code integer x}). */
    static String compact(List<Token> tokens) {
        StringBuilder b = new StringBuilder();
        Token previous = null;
        for (Token t : tokens) {
            if (previous != null && previous.isWord() && t.isWord()) {
                b.append(' ');
            }
            b.append(t.text());
            previous = t;
        }
        return b.toString();
    }

    private Expr span(Expr e, int fromToken) {
        if (e != Expr.EMPTY && p > fromToken) {
            e.start = tokens.get(fromToken).start();
            e.end = tokens.get(p - 1).end();
        }
        return e;
    }

    private Token peek() {
        return tokens.get(p);
    }

    private Token peek(int ahead) {
        return tokens.get(Math.min(p + ahead, tokens.size() - 1));
    }

    private Token next() {
        Token t = tokens.get(p);
        if (t.type() != Type.EOF) {
            p++;
        }
        return t;
    }

    private void expect(String op) {
        Token t = next();
        if (!t.is(op)) {
            throw new ParseException("expected '" + op + "' but found '" + t.text() + "'");
        }
    }

    private Token expectIdent() {
        Token t = next();
        if (t.type() != Type.IDENT) {
            throw new ParseException("expected an identifier but found '" + t.text() + "'");
        }
        return t;
    }

    String source() {
        return src;
    }
}
