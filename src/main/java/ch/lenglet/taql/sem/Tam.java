package ch.lenglet.taql.sem;

import ch.lenglet.taql.SqlType;
import ch.lenglet.taql.TaqlType;
import ch.lenglet.taql.catalog.Catalog;

import java.util.List;
import java.util.Set;

/**
 * The typed analytics model.
 *
 * Everything here is resolved and physical: names have become
 * {@link Column}s carrying a table alias, implicit conversions have been made
 * explicit, and every value is either a {@link LiteralRef} (index into the
 * per-query literal table) or a {@link Variable} (a named $param). There are no
 * strings in this tree that came from the user, which is what makes the SQL
 * generator a pure, boring rendering pass.
 */
public final class Tam {

    private Tam() {}

    public enum Kind { ANALYSIS, FLAT }

    // ------------------------------------------------------------------
    // Typed expressions
    // ------------------------------------------------------------------

    public sealed interface Expr {
        TaqlType type();
    }

    public record Column(Catalog.Field field, TaqlType type) implements Expr {}

    /** Auto-parameterised constant: the value is literals[slot] of the *calling* query. */
    public record LiteralRef(int slot, TaqlType type, SqlType sqlType) implements Expr {}

    /** A named query variable supplied at execution time. */
    public record Variable(String name, TaqlType type, SqlType sqlType) implements Expr {}

    public record NullValue(TaqlType type) implements Expr {}

    /**
     * A constant the *compiler* supplied rather than the user (today: the
     * default row cap). It is shape-invariant, so unlike a {@link LiteralRef}
     * it is safe to store its value inside a cached plan.
     */
    public record Constant(Object value, TaqlType type, SqlType sqlType) implements Expr {}

    public record Unary(String op, Expr operand, TaqlType type) implements Expr {}

    public record Binary(String op, Expr left, Expr right, TaqlType type) implements Expr {}

    public record Func(String name, List<Expr> args, TaqlType type) implements Expr {}

    public record Case(List<When> whens, Expr otherwise, TaqlType type) implements Expr {}

    public record When(Pred condition, Expr result) {}

    public record Aggregate(String function, boolean distinct, Expr argument, Pred filter, TaqlType type)
            implements Expr {}

    /** Reference to an output column by alias -- only legal in ORDER BY. */
    public record OutputRef(String alias, TaqlType type) implements Expr {}

    // ------------------------------------------------------------------
    // Typed predicates
    // ------------------------------------------------------------------

    public sealed interface Pred {}

    public record And(List<Pred> operands) implements Pred {}

    public record Or(List<Pred> operands) implements Pred {}

    public record Not(Pred operand) implements Pred {}

    public record Compare(String op, Expr left, Expr right) implements Pred {}

    public record InList(Expr subject, List<Expr> items, boolean negated) implements Pred {}

    public record Between(Expr subject, Expr low, Expr high, boolean negated) implements Pred {}

    /** {@code x in $list}: arity is unknown at plan time, so this lowers to a set-returning join. */
    public record InVariable(Expr subject, Variable variable, boolean negated) implements Pred {}

    public record IsNull(Expr subject, boolean negated) implements Pred {}

    public record Like(Expr subject, Expr pattern, boolean negated) implements Pred {}

    // ------------------------------------------------------------------
    // Typed statement
    // ------------------------------------------------------------------

    public record Output(String alias, Expr expr) {}

    public record Sort(Expr expr, boolean descending) {}

    /**
     * @param joins join names required by the resolved fields, in catalog order
     *              and already de-duplicated.
     * @param limit row cap, or null when the query stated no 'top' and no
     *              default cap is configured -- in which case the statement is
     *              emitted without a TOP clause at all.
     */
    public record Query(Kind kind,
                        Catalog.Entity entity,
                        Set<String> joins,
                        List<Output> groups,
                        List<Output> measures,
                        List<Output> projections,
                        Pred filter,
                        List<Sort> sort,
                        Expr limit) {

        public List<Output> outputs() {
            return kind == Kind.ANALYSIS
                    ? java.util.stream.Stream.concat(groups.stream(), measures.stream()).toList()
                    : projections;
        }
    }
}
