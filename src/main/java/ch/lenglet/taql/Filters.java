package ch.lenglet.taql;

import ch.lenglet.taql.ast.Ast;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Questions a {@link QueryPolicy} asks about what a query's filter guarantees.
 *
 * A rule that decides what a query may read has to reason about what its filter
 * <em>rules out</em>, and that reasoning is where such a rule fails open. It is
 * written once here, rather than in each policy, because every rule needs the
 * same answer and only one of them has to be wrong.
 *
 * <h2>Only the conjunction is walked</h2>
 * A condition restricts a result only if nothing escapes it. {@code and} is
 * walked through, at any depth. Everything else stops the walk:
 *
 * <ul>
 *   <li><b>{@code or}</b> -- the other branch matches anything.
 *       {@code ClientId = '1' or Country = 'CH'} returns every client's rows,
 *       and a rule that counted the first branch would authorise a table scan.</li>
 *   <li><b>{@code not}</b> -- names what is excluded, not what is included.</li>
 *   <li><b>anything not understood</b> -- {@code like}, {@code !=}, one-sided
 *       comparisons, {@code is null}. None of them bounds a field.</li>
 * </ul>
 *
 * A conjunct that is skipped can only make an answer <em>wider</em> than the
 * truth, never narrower, because every additional condition removes rows. So
 * being conservative is safe: the worst outcome is a policy refusing a query
 * that would have been fine.
 */
public final class Filters {

    private Filters() {}

    /** A closed interval, written {@code field in low..high}. */
    public record Range(Object low, Object high) {}

    /**
     * The values {@code field} is pinned to, or empty when that cannot be known.
     *
     * Recognises {@code field = x} and {@code field in [...]} in a top-level
     * conjunct. Several of them intersect: all the conditions hold at once, so
     * the query can return no more than what they agree on.
     *
     * <b>Empty means "cannot tell", which a policy must read as refuse</b> --
     * not as "nothing to check", which permits exactly the queries that read
     * everything.
     */
    public static Optional<Set<Object>> pinnedValues(Ast.Query query, String field) {
        List<Set<Object>> stated = new ArrayList<>();
        conjuncts(query.stmt().filter(), conjunct -> {
            Set<Object> values = valuesOf(conjunct, field, query.literals());
            if (values != null) stated.add(values);
        });
        if (stated.isEmpty()) return Optional.empty();

        Set<Object> pinned = new LinkedHashSet<>(stated.getFirst());
        stated.forEach(pinned::retainAll);
        return Optional.of(pinned);
    }

    /**
     * The interval {@code field} is confined to, or empty when it is not
     * confined to one.
     *
     * Recognises {@code field in low..high} in a top-level conjunct -- the only
     * form that bounds a field on both sides. A one-sided {@code >} leaves the
     * other end open, which for a date means "everything since", and that is
     * usually the whole table.
     */
    public static Optional<Range> range(Ast.Query query, String field) {
        List<Range> stated = new ArrayList<>();
        conjuncts(query.stmt().filter(), conjunct -> {
            if (!(conjunct instanceof Ast.InRange r) || r.negated() || !names(r.subject(), field)) return;
            Object low = literal(r.low(), query.literals());
            Object high = literal(r.high(), query.literals());
            if (low != null && high != null) stated.add(new Range(low, high));
        });
        // Two ranges on one field both hold, so the truth is their overlap --
        // but comparing the ends needs their type, which is the rule's to know.
        // The first is reported, and it is never wider than the overlap.
        return stated.stream().findFirst();
    }

    /** Visits each top-level conjunct, and nothing that an or or a not encloses. */
    private static void conjuncts(Ast.Pred pred, Consumer<Ast.Pred> visit) {
        if (pred == null) return;
        if (pred instanceof Ast.And and) {
            and.operands().forEach(operand -> conjuncts(operand, visit));
            return;
        }
        visit.accept(pred);
    }

    /** Null when this conjunct does not pin the field to a set this can enumerate. */
    private static Set<Object> valuesOf(Ast.Pred conjunct, String field, List<Object> literals) {
        List<Ast.Expr> items = switch (conjunct) {
            case Ast.Compare c when c.op().equals("=") && names(c.left(), field) -> List.of(c.right());
            case Ast.Compare c when c.op().equals("=") && names(c.right(), field) -> List.of(c.left());
            case Ast.InList i when !i.negated() && names(i.subject(), field) -> i.items();
            default -> null;
        };
        if (items == null || items.isEmpty()) return null;

        Set<Object> values = new LinkedHashSet<>();
        for (Ast.Expr item : items) {
            Object value = literal(item, literals);
            // A computed value is not a constant, so the conjunct stops being
            // something this can enumerate.
            if (value == null) return null;
            values.add(value);
        }
        return values;
    }

    /** Matched the way the catalog matches a field: case-insensitively. */
    private static boolean names(Ast.Expr e, String field) {
        return e instanceof Ast.FieldRef f && f.name().equalsIgnoreCase(field);
    }

    /** The constant behind a literal hole, or null if this is not one. */
    private static Object literal(Ast.Expr e, List<Object> literals) {
        if (!(e instanceof Ast.Lit lit) || lit.slot() < 0) return null;
        return literals.get(lit.slot());
    }
}
