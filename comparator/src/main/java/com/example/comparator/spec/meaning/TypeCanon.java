package com.example.comparator.spec.meaning;

import com.example.comparator.spec.meaning.AcslLexer.ParseException;
import com.example.comparator.spec.meaning.AcslLexer.Token;
import com.example.comparator.spec.meaning.AcslLexer.Type;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Logic types ({@code integer}, {@code Set<Tuple<A,B> >}, {@code \list<A>}, {@code int *}) with
 * type aliases expanded ({@code type Relation<A,B> = Set<Tuple<A,B> >}).
 */
final class TypeCanon {

    /** {@code name<args>} followed by {@code stars} pointer marks. */
    record TypeNode(String name, List<TypeNode> args, int stars) {

        String print() {
            StringBuilder b = new StringBuilder(name);
            if (!args.isEmpty()) {
                b.append('<');
                for (int i = 0; i < args.size(); i++) {
                    if (i > 0) {
                        b.append(',');
                    }
                    b.append(args.get(i).print());
                }
                b.append('>');
            }
            return b.append("*".repeat(stars)).toString();
        }
    }

    /** {@code type name<params> = body}. */
    record Alias(List<String> params, TypeNode body) {}

    private TypeCanon() {}

    static TypeNode parse(String text) {
        List<Token> tokens = AcslLexer.lex(text);
        int[] pos = {0};
        TypeNode t = parse(tokens, pos);
        if (tokens.get(pos[0]).type() != Type.EOF) {
            throw new ParseException("trailing tokens in type " + text);
        }
        return t;
    }

    private static TypeNode parse(List<Token> tokens, int[] pos) {
        Token first = tokens.get(pos[0]);
        if (first.type() != Type.IDENT) {
            throw new ParseException("bad type");
        }
        StringBuilder name = new StringBuilder(first.text());
        pos[0]++;
        // multi-word C types: unsigned int, long long, ...
        while (tokens.get(pos[0]).type() == Type.IDENT && AcslParser.TYPE_WORDS.contains(tokens.get(pos[0]).text())
                && AcslParser.TYPE_WORDS.contains(first.text())) {
            name.append(' ').append(tokens.get(pos[0]).text());
            pos[0]++;
        }
        List<TypeNode> args = new ArrayList<>();
        if (tokens.get(pos[0]).is("<")) {
            pos[0]++;
            while (true) {
                args.add(parse(tokens, pos));
                Token t = tokens.get(pos[0]);
                pos[0]++;
                if (t.is(">")) {
                    break;
                }
                if (!t.is(",")) {
                    throw new ParseException("bad type arguments");
                }
            }
        }
        int stars = 0;
        while (tokens.get(pos[0]).is("*")) {
            stars++;
            pos[0]++;
        }
        return new TypeNode(name.toString(), args, stars);
    }

    /** Canonical text of {@code text}; with {@code aliases} non-null, aliases are expanded. */
    static String canonical(String text, Map<String, Alias> aliases) {
        TypeNode t;
        try {
            t = parse(text);
        } catch (ParseException e) {
            return text.replaceAll("\\s+", "");
        }
        if (aliases != null) {
            t = expand(t, aliases, 0);
        }
        return t.print();
    }

    private static TypeNode expand(TypeNode t, Map<String, Alias> aliases, int depth) {
        List<TypeNode> args = new ArrayList<>();
        for (TypeNode a : t.args()) {
            args.add(expand(a, aliases, depth));
        }
        Alias alias = aliases.get(t.name());
        if (alias == null || alias.params().size() != args.size() || depth > 20) {
            return new TypeNode(t.name(), args, t.stars());
        }
        TypeNode body = substitute(alias.body(), alias.params(), args);
        TypeNode expanded = expand(body, aliases, depth + 1);
        return new TypeNode(expanded.name(), expanded.args(), expanded.stars() + t.stars());
    }

    private static TypeNode substitute(TypeNode body, List<String> params, List<TypeNode> args) {
        int i = params.indexOf(body.name());
        if (i >= 0 && body.args().isEmpty()) {
            TypeNode arg = args.get(i);
            return new TypeNode(arg.name(), arg.args(), arg.stars() + body.stars());
        }
        List<TypeNode> sub = new ArrayList<>();
        for (TypeNode a : body.args()) {
            sub.add(substitute(a, params, args));
        }
        return new TypeNode(body.name(), sub, body.stars());
    }
}
