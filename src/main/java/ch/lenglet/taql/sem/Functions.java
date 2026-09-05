package ch.lenglet.taql.sem;

import ch.lenglet.taql.TaqlType;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

/** The scalar and aggregate functions the DSL exposes. Anything not listed here is a compile error. */
public final class Functions {

    private Functions() {}

    public record Scalar(String name, List<TaqlType> parameters, TaqlType result, boolean variadic) {}

    public record Aggregate(String name, boolean requiresArgument,
                            Function<TaqlType, TaqlType> resultType, boolean numericOnly) {}

    private static final Map<String, Scalar> SCALARS = Map.ofEntries(
            Map.entry("year",     new Scalar("year",     List.of(TaqlType.DATE),    TaqlType.INTEGER, false)),
            Map.entry("month",    new Scalar("month",    List.of(TaqlType.DATE),    TaqlType.INTEGER, false)),
            Map.entry("day",      new Scalar("day",      List.of(TaqlType.DATE),    TaqlType.INTEGER, false)),
            Map.entry("upper",    new Scalar("upper",    List.of(TaqlType.STRING),  TaqlType.STRING,  false)),
            Map.entry("lower",    new Scalar("lower",    List.of(TaqlType.STRING),  TaqlType.STRING,  false)),
            Map.entry("length",   new Scalar("length",   List.of(TaqlType.STRING),  TaqlType.INTEGER, false)),
            Map.entry("abs",      new Scalar("abs",      List.of(TaqlType.DECIMAL), TaqlType.DECIMAL, false)),
            Map.entry("round",    new Scalar("round",    List.of(TaqlType.DECIMAL, TaqlType.INTEGER), TaqlType.DECIMAL, false)),
            Map.entry("concat",   new Scalar("concat",   List.of(TaqlType.STRING),  TaqlType.STRING,  true)),
            Map.entry("coalesce", new Scalar("coalesce", List.of(),                 null,             true)));

    private static final Map<String, Aggregate> AGGREGATES = Map.of(
            "count", new Aggregate("count", false, t -> TaqlType.INTEGER, false),
            "sum",   new Aggregate("sum",   true,  t -> TaqlType.DECIMAL, true),
            "avg",   new Aggregate("avg",   true,  t -> TaqlType.DECIMAL, true),
            "min",   new Aggregate("min",   true,  t -> t,                false),
            "max",   new Aggregate("max",   true,  t -> t,                false));

    public static Optional<Scalar> scalar(String name) {
        return Optional.ofNullable(SCALARS.get(name.toLowerCase(Locale.ROOT)));
    }

    public static Optional<Aggregate> aggregate(String name) {
        return Optional.ofNullable(AGGREGATES.get(name.toLowerCase(Locale.ROOT)));
    }

    public static List<String> aggregateNames() {
        return AGGREGATES.keySet().stream().sorted().toList();
    }
}
