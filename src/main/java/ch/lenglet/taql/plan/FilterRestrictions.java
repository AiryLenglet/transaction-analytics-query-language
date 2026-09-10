package ch.lenglet.taql.plan;

import ch.lenglet.taql.sem.Tam;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reads a resolved filter and records what it pins each field down to.
 *
 * <h2>Conservative on purpose</h2>
 * A policy authorising a query reads {@link Restrictions#on} as "these are the
 * only values this query can return", so being wrong here fails <b>open</b>.
 * Only two shapes are recognised, and only where they cannot be escaped:
 *
 * <ul>
 *   <li><b>Top-level conjuncts only.</b> {@code and} is flattened through, so
 *       {@code A and (ClientId = 'x' and B)} counts. Anything under an
 *       {@code or} does not: in {@code ClientId = 'x' or Country = 'CH'} the
 *       second branch matches every client, so the filter pins nothing.</li>
 *   <li><b>Positive equality and membership only.</b> {@code =} and
 *       {@code in [...]}. Not {@code !=}, {@code like}, ranges, {@code is null}
 *       or anything negated -- none of those has a set to enumerate.</li>
 * </ul>
 *
 * A conjunct that is not recognised is simply not recorded, which is safe:
 * every additional condition narrows the rows a query returns, so ignoring one
 * can only make the recorded set larger than the truth, never smaller.
 *
 * {@code ClientId = 'a' or ClientId = 'b'} is therefore refused even though it
 * is equivalent to {@code in ['a','b']}. Unioning across disjuncts is sound only
 * when every branch restricts the field, and the version that checks that is
 * worth writing when someone actually wants to write the query that way.
 */
public final class FilterRestrictions {

    private FilterRestrictions() {}

    public static Map<String, List<Plan.Restriction>> of(Tam.Query query) {
        Map<String, List<Plan.Restriction>> found = new LinkedHashMap<>();
        if (query.filter() != null) collect(query.filter(), found);
        return found;
    }

    /** Walks the conjunction, and only the conjunction. */
    private static void collect(Tam.Pred pred, Map<String, List<Plan.Restriction>> into) {
        switch (pred) {
            case Tam.And a -> a.operands().forEach(operand -> collect(operand, into));

            case Tam.Compare c when c.op().equals("=") -> {
                // Either side may be the column: 'x' = ClientId is the same claim.
                if (c.left() instanceof Tam.Column column) record(column, List.of(c.right()), into);
                else if (c.right() instanceof Tam.Column column) record(column, List.of(c.left()), into);
            }

            case Tam.InList i when !i.negated() -> {
                if (i.subject() instanceof Tam.Column column) record(column, i.items(), into);
            }

            // Everything else pins nothing this can enumerate: or, not, !=, <, >,
            // like, between, is null, and the negated forms of the above.
            default -> { }
        }
    }

    private static void record(Tam.Column column, List<Tam.Expr> values,
                               Map<String, List<Plan.Restriction>> into) {
        List<Plan.ValueRef> refs = new ArrayList<>(values.size());
        for (Tam.Expr value : values) {
            switch (value) {
                case Tam.LiteralRef l -> refs.add(new Plan.ValueRef.Lit(l.slot()));
                // A computed value -- ClientId = upper(Country) -- is not a set
                // of constants, so the whole conjunct stops being enumerable.
                default -> {
                    return;
                }
            }
        }
        if (refs.isEmpty()) return;
        into.computeIfAbsent(column.field().name(), field -> new ArrayList<>())
                .add(new Plan.Restriction(refs));
    }
}
