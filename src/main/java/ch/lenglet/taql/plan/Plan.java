package ch.lenglet.taql.plan;

import ch.lenglet.taql.TaqlType;

import java.util.List;
import java.util.Map;

/**
 * A compiled, immutable, cacheable query plan.
 *
 * The plan holds SQL text with {@code ?} placeholders and, crucially, a
 * <em>recipe</em> for filling them rather than the values themselves. That
 * separation is what makes the cache useful: the same plan serves
 * {@code ClientId in ['1','3']} and {@code ClientId in ['7','9']}, because each
 * of those supplies its own literal table at execution time.
 *
 * @param parameters  in JDBC order -- slot i binds to parameter index i+1.
 * @param variables   the $variables this plan needs, and their inferred types.
 *                    A REST layer can publish this as the endpoint's contract.
 */
public record Plan(String sql,
                   List<ParamSlot> parameters,
                   List<Column> columns,
                   Map<String, TaqlType> variables,
                   String shapeKey) {

    public Plan {
        parameters = List.copyOf(parameters);
        columns = List.copyOf(columns);
        variables = Map.copyOf(variables);
    }

    public record Column(String name, TaqlType type) {}

    /** Where one bound parameter's value comes from. */
    public sealed interface ParamSlot {
        TaqlType type();
        String sqlType();
    }

    /** Auto-parameterised user literal: value = literals[index] of the query being run. */
    public record Auto(int index, TaqlType type, String sqlType) implements ParamSlot {}

    /** A named $variable supplied by the caller. */
    public record Variable(String name, TaqlType type, String sqlType) implements ParamSlot {}

    /** A compiler-supplied constant, e.g. the default row cap. Shape-invariant, so it lives in the plan. */
    public record Constant(Object value, TaqlType type, String sqlType) implements ParamSlot {}

    /** A whole list bound as a single JSON parameter -- see SqlServerGenerator. */
    public record VariableList(String name, TaqlType elementType, String sqlType) implements ParamSlot {
        @Override
        public TaqlType type() {
            return TaqlType.listOf(elementType);
        }
    }
}
