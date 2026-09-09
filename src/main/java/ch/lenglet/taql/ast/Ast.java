package ch.lenglet.taql.ast;

import java.util.List;

/**
 * The untyped syntax model.
 *
 * One thing here is not obvious and is the crux of the whole design: this tree
 * contains <b>no literal values</b>. Every literal the user wrote is lifted out
 * into a side table during AST construction and replaced by a {@link Lit} node
 * holding only a slot index and the literal's syntactic kind.
 *
 * Two consequences fall out of that:
 *   1. The tree is a pure *shape*. `ClientId in ['1','3']` and `ClientId in
 *      ['7','9']` produce identical trees, so they produce identical plans and
 *      share one cache entry.
 *   2. There is no code path that can splice a user value into SQL text,
 *      because by the time the SQL generator runs the values are simply not
 *      reachable from the tree it is walking.
 */
public final class Ast {

    private Ast() {}

    public record Pos(int line, int column) {
        public static final Pos NONE = new Pos(0, 0);
    }

    public enum LitKind { STRING, INTEGER, DECIMAL, BOOLEAN, NULL }

    // ------------------------------------------------------------------
    // Expressions
    // ------------------------------------------------------------------

    public sealed interface Expr permits FieldRef, Lit, Param, Unary, Binary, Call, MatchValue, MatchCond {
        Pos pos();
    }

    /** An unresolved name. Becomes a catalog field or an output alias later. */
    public record FieldRef(String name, Pos pos) implements Expr {}

    /** A literal *hole*: the value lives in {@link Query#literals()} at {@code slot}. */
    public record Lit(int slot, LitKind kind, Pos pos) implements Expr {}

    /** An explicit query variable, e.g. {@code $clientIds}. */
    public record Param(String name, Pos pos) implements Expr {}

    public record Unary(String op, Expr operand, Pos pos) implements Expr {}

    public record Binary(String op, Expr left, Expr right, Pos pos) implements Expr {}

    /** Scalar function or aggregate; {@code distinct} only ever set for aggregates. */
    public record Call(String name, boolean distinct, List<Expr> args, Pos pos) implements Expr {}

    public record MatchValue(Expr scrutinee, List<ValueArm> arms, Expr otherwise, Pos pos) implements Expr {}

    public record ValueArm(List<Expr> patterns, Expr result) {}

    public record MatchCond(List<CondArm> arms, Expr otherwise, Pos pos) implements Expr {}

    public record CondArm(Pred condition, Expr result) {}

    // ------------------------------------------------------------------
    // Predicates
    // ------------------------------------------------------------------

    public sealed interface Pred permits And, Or, Not, Compare, InList, InRange, InVariable, IsNull, Like {
        Pos pos();
    }

    public record And(List<Pred> operands, Pos pos) implements Pred {}

    public record Or(List<Pred> operands, Pos pos) implements Pred {}

    public record Not(Pred operand, Pos pos) implements Pred {}

    public record Compare(String op, Expr left, Expr right, Pos pos) implements Pred {}

    public record InList(Expr subject, List<Expr> items, boolean negated, Pos pos) implements Pred {}

    public record InRange(Expr subject, Expr low, Expr high, boolean negated, Pos pos) implements Pred {}

    /** {@code x in $someList} -- arity unknown until bind time. */
    public record InVariable(Expr subject, Param variable, boolean negated, Pos pos) implements Pred {}

    public record IsNull(Expr subject, boolean negated, Pos pos) implements Pred {}

    public record Like(Expr subject, Expr pattern, boolean negated, Pos pos) implements Pred {}

    // ------------------------------------------------------------------
    // Statements
    // ------------------------------------------------------------------

    public sealed interface Stmt permits Analysis, Flat {
        String entity();
        Pred filter();
        Pos pos();
    }

    public record GroupKey(String alias, Expr expr, Pos pos) {}

    /**
     * An aggregate or a window function -- which one depends on {@code function},
     * and the resolver decides. {@code within}/{@code ordered} are only legal on
     * a window function, {@code filter} only on an aggregate.
     */
    public record Measure(String alias, String function, boolean distinct, Expr argument, Pred filter,
                          List<String> within, Ordering ordered, Pos pos) {}

    /** {@code ordered by <name> [asc|desc]} */
    public record Ordering(String name, boolean descending, Pos pos) {}

    public record Projection(String alias, Expr expr, Pos pos) {}

    public record SortItem(Expr expr, boolean descending, Pos pos) {}

    /** {@code count} is a {@link Lit} or {@link Param}; {@code byMeasure} names a measure alias. */
    /** {@code within} turns a global TOP into a per-group rank filter. */
    public record Top(Expr count, String byMeasure, List<String> within, Pos pos) {}

    public record Analysis(String entity, List<GroupKey> groups, List<Measure> measures,
                           Pred filter, Top top, Pos pos) implements Stmt {}

    public record Flat(String entity, List<Projection> projections, Pred filter,
                       List<SortItem> sort, Top top, Pos pos) implements Stmt {}

    /**
     * A parsed statement plus the literal values that were lifted out of it.
     * The {@code shapeKey} is the canonical rendering of {@code stmt} and is the
     * plan cache key -- it is by construction independent of the literal values.
     */
    public record Query(Stmt stmt, List<Object> literals, String shapeKey) {

        /**
         * Carries no literal values. This record is the one place they are all
         * gathered, so its generated toString would be the easiest accidental
         * way to log every constant a caller wrote.
         */
        @Override
        public String toString() {
            return "Query[literals=" + literals.size() + ", shape=" + shapeKey + "]";
        }
    }
}
