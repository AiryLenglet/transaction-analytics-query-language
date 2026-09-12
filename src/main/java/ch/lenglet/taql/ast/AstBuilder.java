package ch.lenglet.taql.ast;

import ch.lenglet.taql.Diagnostic;
import ch.lenglet.taql.TaqlException;
import ch.lenglet.taql.grammar.TaqlParser;
import org.antlr.v4.runtime.ParserRuleContext;
import org.antlr.v4.runtime.Token;
import org.antlr.v4.runtime.tree.TerminalNode;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * Parse tree -> untyped syntax model.
 *
 * Also performs literal lifting: every literal token is appended to
 * {@link #literals} and replaced by an {@link Ast.Lit} hole. Because the walk
 * is deterministic, two queries with the same shape assign the same slot
 * indices, which is what lets the shape key stay value-independent.
 */
public final class AstBuilder {

    private final List<Object> literals = new ArrayList<>();

    /**
     * Nesting budget. Every later pass -- {@link AstPrinter}, the resolver, the
     * SQL generator -- walks this tree by recursion, so a tree they could not
     * survive must never be built in the first place. Checked in {@link #expr}
     * and {@link #pred}, which is where all nesting goes through.
     */
    // Spelled out: the generated parser is what TaqlParser means in this file.
    private final ch.lenglet.taql.ast.TaqlParser.Limits limits;
    private int depth;

    private AstBuilder(ch.lenglet.taql.ast.TaqlParser.Limits limits) {
        this.limits = limits;
    }

    public static Ast.Query build(TaqlParser.QueryContext tree,
                                  ch.lenglet.taql.ast.TaqlParser.Limits limits) {
        AstBuilder builder = new AstBuilder(limits);
        Ast.Stmt stmt = builder.statement(tree.statement());
        return new Ast.Query(stmt, List.copyOf(builder.literals), AstPrinter.canonical(stmt));
    }

    // ------------------------------------------------------------------
    // Statements
    // ------------------------------------------------------------------

    private Ast.Stmt statement(TaqlParser.StatementContext ctx) {
        if (ctx.analysisStatement() != null) return analysis(ctx.analysisStatement());
        return flat(ctx.flatStatement());
    }

    private Ast.Stmt analysis(TaqlParser.AnalysisStatementContext ctx) {
        Clauses clauses = clauses(ctx.queryClause(), false);

        List<Ast.GroupKey> groups = new ArrayList<>();
        for (TaqlParser.GroupKeyContext g : ctx.groupKeyList().groupKey()) {
            Ast.Expr expr = expr(g.expression());
            String alias = alias(g.identifier() != null ? name(g.identifier())
                    : impliedAlias(expr, g.expression(), "group"), g);
            groups.add(new Ast.GroupKey(alias, expr, pos(g)));
        }

        List<Ast.Measure> measures = new ArrayList<>();
        for (TaqlParser.MeasureContext m : ctx.measureBlock().measure()) {
            TaqlParser.AggregateCallContext agg = aggregateCall(m);
            String function = name(agg.identifier());
            Ast.Expr argument = agg.expression() != null ? expr(agg.expression()) : null;
            String alias = alias(m.identifier() != null ? name(m.identifier())
                    : impliedMeasureAlias(function, argument, agg), m);
            Ast.Pred filter = m.predicate() != null ? pred(m.predicate()) : null;
            measures.add(new Ast.Measure(alias, function, agg.DISTINCT() != null, argument, filter,
                    within(m.withinClause()), ordering(m.orderedClause()), pos(m)));
        }

        // Canonical order -- see the note on Clauses.
        Ast.Pred filter = filter(clauses);
        Ast.Top top = top(clauses);

        return new Ast.Analysis(clauses.entity, groups, measures, filter, top, pos(ctx));
    }

    private Ast.Stmt flat(TaqlParser.FlatStatementContext ctx) {
        Clauses clauses = clauses(ctx.queryClause(), true);

        List<Ast.Projection> projections = new ArrayList<>();
        for (TaqlParser.ProjectionContext p : ctx.projectionBlock().projection()) {
            Ast.Expr expr = expr(p.expression());
            String alias = alias(p.identifier() != null ? name(p.identifier())
                    : impliedAlias(expr, p.expression(), "column"), p);
            projections.add(new Ast.Projection(alias, expr, pos(p)));
        }

        // Canonical order -- see the note on Clauses.
        Ast.Pred filter = filter(clauses);
        List<Ast.SortItem> sort = sort(clauses);
        Ast.Top top = top(clauses);

        return new Ast.Flat(clauses.entity, projections, filter, sort, top, pos(ctx));
    }

    /**
     * A measure has to aggregate, and this is where a bare column is turned
     * away.
     *
     * It is the one thing a grouped query cannot select: every column has to be
     * a group key or sit inside an aggregate, so a measure that is neither has
     * no meaning to give it. The grammar lets it through on purpose -- refusing
     * it there produces "no viable alternative at input", and the useful answer
     * is that the writer has to choose between measuring the column and grouping
     * on it.
     */
    private TaqlParser.AggregateCallContext aggregateCall(TaqlParser.MeasureContext m) {
        if (m.measureBody() instanceof TaqlParser.AggregateCallContext agg) return agg;
        String written = m.measureBody().getText();
        if (written.length() > 40) written = written.substring(0, 40) + "...";
        throw error(m.measureBody(), "'" + written + "' is not aggregated; measure it with"
                + " something like sum(" + written + "), or move it into 'by' to group on it");
    }

    // ------------------------------------------------------------------
    // Trailing clauses (order-insensitive, duplicates rejected here)
    // ------------------------------------------------------------------

    /**
     * The trailing clauses a statement carries, as unbuilt parse contexts.
     *
     * <h2>One order, and it is analytical</h2>
     * Clauses must be written {@code from ... over ... sort by ... top}, and
     * anything else is a positioned error. {@code from} and {@code over} are the
     * same concern -- they name the population being analysed -- so nothing may
     * come between them; what follows is what the query does <em>to</em> that
     * population. {@code top 10 by revenue} has no meaning until the rows it
     * ranks are known, so the scope is stated first and the query reads the way
     * the question is asked: measure this, over that population, show the top N.
     *
     * <h2>Why they are collected rather than built</h2>
     * Building is what allocates literal slots, and <b>slot order has to be
     * canonical</b>: {@link ch.lenglet.taql.plan.Plan.Auto} indexes the literal
     * table of whichever query is running, so two texts sharing a cached plan
     * must number their literals identically. Ordering is enforced above, which
     * makes that hold anyway -- collecting here and building in
     * {@link #analysis} / {@link #flat} keeps it true by construction rather
     * than by coincidence, so relaxing the rule later cannot silently reintroduce
     * a query bound to another query's values.
     */
    private static final class Clauses {
        String entity;
        TaqlParser.QueryClauseContext over;
        TaqlParser.QueryClauseContext sort;
        TaqlParser.QueryClauseContext top;
    }

    private Clauses clauses(List<TaqlParser.QueryClauseContext> list, boolean sortAllowed) {
        Clauses out = new Clauses();
        int previousRank = -1;
        String previous = null;
        for (TaqlParser.QueryClauseContext c : list) {
            int rank = rank(c);
            if (rank < previousRank) {
                throw error(c, "'" + clauseName(c) + "' must come before '" + previous
                        + "'; a query reads " + shape(sortAllowed));
            }
            previousRank = rank;
            previous = clauseName(c);

            if (c.fromClause() != null) {
                if (out.entity != null) throw error(c, "duplicate 'from' clause");
                out.entity = name(c.fromClause().identifier());
            } else if (c.overClause() != null) {
                if (out.over != null) throw error(c, "duplicate 'over' clause");
                out.over = c;
            } else if (c.topClause() != null) {
                if (out.top != null) throw error(c, "duplicate 'top' clause");
                out.top = c;
            } else if (c.sortClause() != null) {
                if (!sortAllowed) throw error(c, "'sort by' is not valid on an analysis query; use 'top N by <measure>'");
                if (out.sort != null) throw error(c, "duplicate 'sort by' clause");
                out.sort = c;
            }
        }
        return out;
    }

    /** Position in the canonical order; see {@link Clauses}. */
    private static int rank(TaqlParser.QueryClauseContext c) {
        if (c.fromClause() != null) return 0;
        if (c.overClause() != null) return 1;
        if (c.sortClause() != null) return 2;
        return 3;
    }

    private static String clauseName(TaqlParser.QueryClauseContext c) {
        if (c.fromClause() != null) return "from";
        if (c.overClause() != null) return "over";
        if (c.sortClause() != null) return "sort by";
        return "top";
    }

    private static String shape(boolean sortAllowed) {
        return sortAllowed
                ? "'from ... over { ... } sort by ... top N'"
                : "'from ... over { ... } top N by <measure>'";
    }

    private Ast.Pred filter(Clauses clauses) {
        if (clauses.over == null) return null;
        List<Ast.Pred> parts = new ArrayList<>();
        for (TaqlParser.PredicateContext p : clauses.over.overClause().predicate()) parts.add(pred(p));
        // Filters written on separate lines are implicitly ANDed.
        return parts.isEmpty() ? null
                : parts.size() == 1 ? parts.getFirst()
                : new Ast.And(parts, pos(clauses.over));
    }

    private List<Ast.SortItem> sort(Clauses clauses) {
        if (clauses.sort == null) return List.of();
        List<Ast.SortItem> items = new ArrayList<>();
        for (TaqlParser.SortItemContext s : clauses.sort.sortClause().sortItem()) {
            items.add(new Ast.SortItem(expr(s.expression()), s.DESC() != null, pos(s)));
        }
        return items;
    }

    private Ast.Top top(Clauses clauses) {
        if (clauses.top == null) return null;
        TaqlParser.TopClauseContext t = clauses.top.topClause();
        Ast.Expr count = lit(integer(t.countExpr().INT(), pos(t)), Ast.LitKind.INTEGER, pos(t));
        return new Ast.Top(count, t.identifier() != null ? name(t.identifier()) : null,
                within(t.withinClause()), pos(t));
    }

    // ------------------------------------------------------------------
    // Predicates
    // ------------------------------------------------------------------

    private Ast.Pred pred(TaqlParser.PredicateContext ctx) {
        if (++depth > limits.maxNestingDepth()) throw tooDeep(ctx);
        try {
            return or(ctx.orPredicate());
        } finally {
            depth--;
        }
    }

    private Ast.Pred or(TaqlParser.OrPredicateContext ctx) {
        List<Ast.Pred> parts = new ArrayList<>();
        for (TaqlParser.AndPredicateContext a : ctx.andPredicate()) parts.add(and(a));
        return parts.size() == 1 ? parts.getFirst() : new Ast.Or(parts, pos(ctx));
    }

    private Ast.Pred and(TaqlParser.AndPredicateContext ctx) {
        List<Ast.Pred> parts = new ArrayList<>();
        for (TaqlParser.UnaryPredicateContext u : ctx.unaryPredicate()) parts.add(unary(u));
        return parts.size() == 1 ? parts.getFirst() : new Ast.And(parts, pos(ctx));
    }

    private Ast.Pred unary(TaqlParser.UnaryPredicateContext ctx) {
        // 'not' recurses here rather than through pred(), so it is counted here
        // too -- otherwise its only bound would be maxSourceLength by accident.
        if (++depth > limits.maxNestingDepth()) throw tooDeep(ctx);
        try {
            return unaryPredicate(ctx);
        } finally {
            depth--;
        }
    }

    private Ast.Pred unaryPredicate(TaqlParser.UnaryPredicateContext ctx) {
        return switch (ctx) {
            case TaqlParser.NotPredicateContext n -> new Ast.Not(unary(n.unaryPredicate()), pos(n));
            case TaqlParser.ParenPredicateContext p -> pred(p.predicate());
            case TaqlParser.ComparisonPredicateContext c -> comparison(c.comparison());
            default -> throw error(ctx, "unsupported predicate");
        };
    }

    private Ast.Pred comparison(TaqlParser.ComparisonContext ctx) {
        return switch (ctx) {
            case TaqlParser.OpComparisonContext c ->
                    new Ast.Compare(c.compareOp().getText(), expr(c.expression(0)), expr(c.expression(1)), pos(c));
            case TaqlParser.NullComparisonContext c ->
                    new Ast.IsNull(expr(c.expression()), c.NOT() != null, pos(c));
            case TaqlParser.LikeComparisonContext c ->
                    new Ast.Like(expr(c.expression(0)), expr(c.expression(1)), c.NOT() != null, pos(c));
            case TaqlParser.InComparisonContext c -> in(c);
            default -> throw error(ctx, "unsupported comparison");
        };
    }

    private Ast.Pred in(TaqlParser.InComparisonContext ctx) {
        Ast.Expr subject = expr(ctx.expression());
        boolean negated = ctx.NOT() != null;
        return switch (ctx.inSource()) {
            case TaqlParser.InListContext l -> {
                List<Ast.Expr> items = new ArrayList<>();
                for (TaqlParser.ExpressionContext e : l.listLiteral().expression()) items.add(expr(e));
                yield new Ast.InList(subject, items, negated, pos(ctx));
            }
            case TaqlParser.InRangeContext r ->
                    new Ast.InRange(subject, expr(r.expression(0)), expr(r.expression(1)), negated, pos(ctx));
            default -> throw error(ctx, "unsupported 'in' source");
        };
    }

    // ------------------------------------------------------------------
    // Expressions
    // ------------------------------------------------------------------

    private Ast.Expr expr(TaqlParser.ExpressionContext ctx) {
        if (++depth > limits.maxNestingDepth()) throw tooDeep(ctx);
        try {
            return expression(ctx);
        } finally {
            depth--;
        }
    }

    private Ast.Expr expression(TaqlParser.ExpressionContext ctx) {
        return switch (ctx) {
            case TaqlParser.ParenExprContext c -> expr(c.expression());
            case TaqlParser.UnaryExprContext c ->
                    new Ast.Unary(c.getChild(0).getText(), expr(c.expression()), pos(c));
            case TaqlParser.MulExprContext c ->
                    new Ast.Binary(c.getChild(1).getText(), expr(c.expression(0)), expr(c.expression(1)), pos(c));
            case TaqlParser.AddExprContext c ->
                    new Ast.Binary(c.getChild(1).getText(), expr(c.expression(0)), expr(c.expression(1)), pos(c));
            case TaqlParser.MatchWrapperContext c -> match(c.matchExpr());
            case TaqlParser.CallExprContext c -> {
                List<Ast.Expr> args = new ArrayList<>();
                for (TaqlParser.ExpressionContext e : c.expression()) args.add(expr(e));
                yield new Ast.Call(name(c.identifier()), false, args, pos(c));
            }
            case TaqlParser.LiteralExprContext c -> literal(c.literal());
            case TaqlParser.FieldExprContext c -> new Ast.FieldRef(name(c.identifier()), pos(c));
            default -> throw error(ctx, "unsupported expression");
        };
    }

    private Ast.Expr match(TaqlParser.MatchExprContext ctx) {
        return switch (ctx) {
            case TaqlParser.MatchOnValueContext m -> {
                Ast.Expr scrutinee = expr(m.expression());
                List<Ast.ValueArm> arms = new ArrayList<>();
                Ast.Expr otherwise = null;
                for (TaqlParser.ValueArmContext a : m.valueArm()) {
                    if (a instanceof TaqlParser.ValueDefaultArmContext d) {
                        if (otherwise != null) throw error(d, "duplicate '_' arm");
                        otherwise = expr(d.expression());
                    } else {
                        TaqlParser.ValuePatternArmContext p = (TaqlParser.ValuePatternArmContext) a;
                        if (otherwise != null) throw error(p, "'_' must be the last arm");
                        List<Ast.Expr> patterns = new ArrayList<>();
                        if (p.valuePattern().listLiteral() != null) {
                            for (TaqlParser.ExpressionContext e : p.valuePattern().listLiteral().expression()) {
                                patterns.add(expr(e));
                            }
                        } else {
                            patterns.add(literal(p.valuePattern().literal()));
                        }
                        arms.add(new Ast.ValueArm(patterns, expr(p.expression())));
                    }
                }
                yield new Ast.MatchValue(scrutinee, arms, otherwise, pos(m));
            }
            case TaqlParser.MatchOnConditionContext m -> {
                List<Ast.CondArm> arms = new ArrayList<>();
                Ast.Expr otherwise = null;
                for (TaqlParser.CondArmContext a : m.condArm()) {
                    if (a instanceof TaqlParser.CondDefaultArmContext d) {
                        if (otherwise != null) throw error(d, "duplicate '_' arm");
                        otherwise = expr(d.expression());
                    } else {
                        TaqlParser.CondPredicateArmContext p = (TaqlParser.CondPredicateArmContext) a;
                        if (otherwise != null) throw error(p, "'_' must be the last arm");
                        arms.add(new Ast.CondArm(pred(p.predicate()), expr(p.expression())));
                    }
                }
                yield new Ast.MatchCond(arms, otherwise, pos(m));
            }
            default -> throw error(ctx, "unsupported match");
        };
    }

    // ------------------------------------------------------------------
    // Literal lifting
    // ------------------------------------------------------------------

    private Ast.Expr literal(TaqlParser.LiteralContext ctx) {
        Ast.Pos pos = pos(ctx);
        if (ctx.NULL() != null) return new Ast.Lit(-1, Ast.LitKind.NULL, pos);
        if (ctx.TRUE() != null) return lit(Boolean.TRUE, Ast.LitKind.BOOLEAN, pos);
        if (ctx.FALSE() != null) return lit(Boolean.FALSE, Ast.LitKind.BOOLEAN, pos);
        if (ctx.INT() != null) return lit(integer(ctx.INT(), pos), Ast.LitKind.INTEGER, pos);
        if (ctx.DECIMAL_LIT() != null) return lit(new BigDecimal(ctx.DECIMAL_LIT().getText()), Ast.LitKind.DECIMAL, pos);
        return lit(unquote(ctx.STRING().getText()), Ast.LitKind.STRING, pos);
    }

    private Ast.Lit lit(Object value, Ast.LitKind kind, Ast.Pos pos) {
        literals.add(value);
        return new Ast.Lit(literals.size() - 1, kind, pos);
    }

    /**
     * The lexer accepts {@code [0-9]+}, which is wider than a {@code long}. An
     * over-large constant is something the user typed, so it owes them a
     * positioned diagnostic -- {@code Long.parseLong} would throw
     * NumberFormatException straight past every handler and become a 500.
     */
    private Long integer(TerminalNode token, Ast.Pos pos) {
        try {
            return Long.parseLong(token.getText());
        } catch (NumberFormatException tooBig) {
            throw new TaqlException(new Diagnostic(Diagnostic.Phase.TYPE, pos.line(), pos.column(),
                    "'" + token.getText() + "' is too large; whole numbers run to " + Long.MAX_VALUE));
        }
    }

    private static String unquote(String raw) {
        return raw.substring(1, raw.length() - 1).replace("''", "'");
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private static List<String> within(TaqlParser.WithinClauseContext ctx) {
        if (ctx == null) return List.of();
        List<String> keys = new ArrayList<>();
        for (TaqlParser.IdentifierContext i : ctx.identifier()) keys.add(name(i));
        return keys;
    }

    private static Ast.Ordering ordering(TaqlParser.OrderedClauseContext ctx) {
        if (ctx == null) return null;
        return new Ast.Ordering(name(ctx.identifier()), ctx.DESC() != null, pos(ctx));
    }

    private static String name(TaqlParser.IdentifierContext ctx) {
        String text = ctx.getText();
        return ctx.QUOTED_IDENT() != null ? text.substring(1, text.length() - 1) : text;
    }

    /** {@code list { TransactionId }} names its column after the field it reads. */
    private String impliedAlias(Ast.Expr expr, ParserRuleContext ctx, String what) {
        if (expr instanceof Ast.FieldRef f) return f.name();
        if (expr instanceof Ast.Call c && c.args().size() == 1 && c.args().getFirst() instanceof Ast.FieldRef f) {
            return c.name() + "_" + f.name();
        }
        throw error(ctx, "this " + what + " needs a name, e.g. 'myName = " + ctx.getText() + "'");
    }

    private String impliedMeasureAlias(String function, Ast.Expr argument, ParserRuleContext ctx) {
        if (argument == null) return function;
        if (argument instanceof Ast.FieldRef f) return function + "_" + f.name();
        throw error(ctx, "this measure needs a name, e.g. 'myMeasure = " + ctx.getText() + "'");
    }

    private static Ast.Pos pos(ParserRuleContext ctx) {
        Token t = ctx.getStart();
        return new Ast.Pos(t.getLine(), t.getCharPositionInLine() + 1);
    }

    /**
     * An output name is the only caller text that reaches the statement as an
     * identifier rather than as a parameter, so it is the only one whose length
     * the store cares about. Refused here, with a position, rather than by the
     * database at run time -- where it arrives as error 103, is classified
     * UNKNOWN because no rule covers it, and is reported to the caller as a
     * server fault for a mistake that was theirs.
     */
    private String alias(String alias, ParserRuleContext ctx) {
        if (alias.length() > limits.maxAliasLength()) {
            throw error(ctx, "the name '" + alias.substring(0, 20) + "...' is " + alias.length()
                    + " characters; the limit is " + limits.maxAliasLength());
        }
        return alias;
    }

    private TaqlException tooDeep(ParserRuleContext ctx) {
        Ast.Pos p = pos(ctx);
        return new TaqlException(new Diagnostic(Diagnostic.Phase.LIMIT, p.line(), p.column(),
                "this query nests more than " + limits.maxNestingDepth() + " levels deep"));
    }

    private static TaqlException error(ParserRuleContext ctx, String message) {
        Ast.Pos p = pos(ctx);
        return new TaqlException(new Diagnostic(Diagnostic.Phase.SYNTAX, p.line(), p.column(), message));
    }
}
