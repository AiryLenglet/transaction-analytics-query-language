package ch.lenglet.taql.plan;

import ch.lenglet.taql.PhysicalType;
import ch.lenglet.taql.TaqlType;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
 * @param variables   the $variables this plan needs, and their inferred types.
 *                    A REST layer can publish this as the endpoint's contract.
 */
public record Plan(String statement,
                   List<ParamSlot> parameters,
                   List<Column> columns,
                   Map<String, TaqlType> variables,
                   String shapeKey) {

    public Plan {
        parameters = List.copyOf(parameters);
        columns = List.copyOf(columns);
        // Not Map.copyOf: its iteration order is randomised per JVM, and this
        // map is published as the endpoint's contract. A generated schema that
        // reorders its own properties on every restart breaks caching, diffs
        // and snapshot tests for no reason. The resolver hands it over in order
        // of first use, which is a sensible order for a human to read; keep it.
        variables = Collections.unmodifiableMap(new LinkedHashMap<>(variables));
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

    /** A named $variable supplied by the caller. */
    public record Variable(String name, TaqlType type, PhysicalType physicalType) implements ParamSlot {}

    /** A compiler-supplied constant, e.g. the default row cap. Shape-invariant, so it lives in the plan. */
    public record Constant(Object value, TaqlType type, PhysicalType physicalType) implements ParamSlot {}

    /** A whole list bound as a single JSON parameter -- see SqlServerGenerator. */
    public record VariableList(String name, TaqlType elementType, PhysicalType physicalType) implements ParamSlot {
        @Override
        public TaqlType type() {
            return TaqlType.listOf(elementType);
        }
    }
}
