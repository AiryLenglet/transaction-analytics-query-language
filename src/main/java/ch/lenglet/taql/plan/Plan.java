package ch.lenglet.taql.plan;

import ch.lenglet.taql.PhysicalType;
import ch.lenglet.taql.TaqlType;

import java.util.List;

/**
 * A compiled, immutable, cacheable query plan.
 *
 * The plan holds statement text with {@code ?} placeholders and, crucially, a
 * <em>recipe</em> for filling them rather than the values themselves. That
 * separation is what makes the cache useful: the same plan serves
 * {@code ClientId in ['1','3']} and {@code ClientId in ['7','9']}, because each
 * of those supplies its own literal table at execution time.
 *
 * @param parameters  in JDBC order -- slot i binds to parameter index i+1.
 */
public record Plan(String statement,
                   List<ParamSlot> parameters,
                   List<Column> columns,
                   String shapeKey) {

    public Plan {
        parameters = List.copyOf(parameters);
        columns = List.copyOf(columns);
    }

    /**
     * Short, stable identifier for logs. Correlates the line that compiled a
     * plan with every line that runs it, without repeating the shape key -- which
     * is long, and belongs in a log at most once per plan.
     */
    public String id() {
        return Integer.toHexString(shapeKey.hashCode());
    }

    public record Column(String name, TaqlType type) {}

    /** Where one bound parameter's value comes from. */
    public sealed interface ParamSlot {
        TaqlType type();
        PhysicalType physicalType();
    }

    /** Auto-parameterised user literal: value = literals[index] of the query being run. */
    public record Auto(int index, TaqlType type, PhysicalType physicalType) implements ParamSlot {}

    /** A compiler-supplied constant, e.g. the default row cap. Shape-invariant, so it lives in the plan. */
    public record Constant(Object value, TaqlType type, PhysicalType physicalType) implements ParamSlot {}

}
