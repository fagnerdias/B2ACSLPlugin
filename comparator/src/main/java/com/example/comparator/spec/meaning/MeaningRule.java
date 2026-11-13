package com.example.comparator.spec.meaning;

import java.util.EnumSet;
import java.util.Set;

/**
 * The meaning-preserving rewrites under which two clauses are taken to say the same thing, and the
 * three tags ({@link #AXIOM_GROUPING}, {@link #ORGANIZATION}, {@link #LAYOUT}) that name the other
 * reasons a unit can differ only in form.
 *
 * <p>Each rewrite holds in ACSL, or under the definitions of B2ACSLLib with B's set semantics (sets
 * are equal when they have the same elements). A difference that no rewrite explains is reported
 * as a difference in meaning, so that category also holds equivalences these rules cannot see.
 */
public enum MeaningRule {
    CONJUNCTION(
            "conjunction",
            "Conjunctions split or reordered",
            "requires A && B ≡ requires A; requires B. Order, repetition and \\true conjuncts are ignored;"
                    + " \\forall and ==> distribute over &&."),
    ASSIGNS_LIST("assigns-list", "Assigns lists split", "assigns a, b ≡ assigns a; assigns b."),
    SYMMETRY(
            "symmetry",
            "Operands of symmetric operators swapped",
            "a == b ≡ b == a, a > b ≡ b < a, a || b ≡ b || a."),
    RELATION_CHAIN("relation-chain", "Chained comparisons", "a <= x <= b ≡ a <= x && x <= b."),
    NEGATION("negation", "Negated comparisons", "!(a == b) ≡ a != b, !(a < b) ≡ a >= b, !!P ≡ P."),
    IF_FORM(
            "if-form",
            "Conditional written as implications",
            "c ? A : B (as a predicate) ≡ (c ==> A) && (!c ==> B); P ==> (Q ==> R) ≡ P && Q ==> R."),
    INTEGER_CAST(
            "integer-cast",
            "(integer) casts",
            "(integer)e ≡ e: in the logic, C integer values are already promoted to integer."),
    POINTER_INDEX("pointer-index", "Pointer arithmetic", "*(p + i) ≡ p[i] (also with a range: *(p + (0 .. n)) ≡ p[0 .. n])."),
    ARITHMETIC(
            "arithmetic",
            "Integer arithmetic",
            "Linear expressions are compared in normal form: 10 - (0) + 1 ≡ 11, n - 1 + 1 ≡ n."),
    MACHINE_PREFIX(
            "machine-prefix",
            "Machine-qualified names",
            "Goods_GOODS, Airlock__ACQ ≡ GOODS, ACQ: a name qualified by a machine of the project and the"
                    + " bare name, when each one is used by only one of the two sides."),
    INVARIANT_NAME(
            "invariant-name",
            "Invariant predicate numbering",
            "M_invariant ≡ M_invariant_1, when each name is used by only one of the two sides."),
    STATE_PARAMETER(
            "state-parameter",
            "State passed as a parameter",
            "f(v, x) ≡ f(x) when one side's helper f takes the state variable v as an extra parameter and the"
                    + " other side's f reads v directly; axioms quantified over that parameter are taken at v."),
    TYPE_ALIAS(
            "type-alias",
            "Type aliases",
            "Relation<A,B>, Function<A,B> and the declared type aliases are replaced by their definition"
                    + " (Set<Tuple<A,B> >)."),
    LIST_LITERAL(
            "list-literal",
            "List literals",
            "[| a, b |] ≡ \\Cons(a, \\Cons(b, \\Nil)); \\concat of two literal lists ≡ the joined list."),
    SET_UNION(
            "set-union",
            "Set union order",
            "set_union is associative and commutative; pair(a, b) ≡ set_union(singleton(a), singleton(b))."),
    SET_EQUALITY(
            "set-equality",
            "equals versus ==",
            "equals(s, t) ≡ s == t: equal B sets are the same set. B2ACSLLib defines equals extensionally"
                    + " and has no axiom from equals to ==, so the provers may still treat them differently."),
    EMPTY_WITNESS(
            "empty-witness",
            "Witness of empty()",
            "empty(NAT) ≡ empty(INT): the argument only fixes the element type (axiom empty_unique)."),
    SET_VALUATION(
            "set-valuation",
            "Interval valuation of a set",
            "\\forall integer x; belongs(x, S) <==> lo <= x <= hi ≡ equals(S, interval_set(lo, hi)),"
                    + " by the definition of equals and the axiom interval_set_mem."),
    CARD_VALUATION(
            "card-valuation",
            "Cardinality of a valued set",
            "card(S) ≡ hi - lo + 1 outside axioms, when both sides value S as interval_set(lo, hi)."),
    CONSTANT_VALUE(
            "constant-value",
            "Valued constants",
            "C ≡ n outside axioms, when both sides state C == n."),
    AXIOM_GROUPING(
            "axiom-grouping",
            "Axioms regrouped or renamed",
            "Axioms and lemmas are compared by content: their names, and how the facts are spread over"
                    + " them, carry no meaning."),
    ORGANIZATION(
            "organization",
            "Organization only",
            "Includes, axiomatic blocks and type-alias declarations carry no meaning of their own; what they"
                    + " declare is compared where it is used."),
    LAYOUT(
            "layout",
            "Layout",
            "Spacing, parentheses, operator precedence or names of bound variables.");

    /** The rewrites (every rule but the three tags). */
    public static final Set<MeaningRule> REWRITES =
            EnumSet.complementOf(EnumSet.of(AXIOM_GROUPING, ORGANIZATION, LAYOUT));

    private final String id;
    private final String label;
    private final String description;

    MeaningRule(String id, String label, String description) {
        this.id = id;
        this.label = label;
        this.description = description;
    }

    public String id() {
        return id;
    }

    public String label() {
        return label;
    }

    public String description() {
        return description;
    }

    public static MeaningRule byId(String id) {
        for (MeaningRule r : values()) {
            if (r.id.equals(id)) {
                return r;
            }
        }
        return null;
    }
}
