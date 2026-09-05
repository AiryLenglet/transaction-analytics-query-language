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

    public static Ast.Query build(TaqlParser.QueryContext tree) {
        AstBuilder builder = new AstBuilder();
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
            String alias = g.identifier() != null ? name(g.identifier()) : impliedAlias(expr, g.expression(), "group");
            groups.add(new Ast.GroupKey(alias, expr, pos(g)));
        }

        List<Ast.Measure> measures = new ArrayList<>();
        for (TaqlParser.MeasureContext m : ctx.measureBlock().measure()) {
            TaqlParser.AggregateContext agg = m.aggregate();
            String function = name(agg.identifier());
            Ast.Expr argument = agg.expression() != null ? expr(agg.expression()) : null;
            String alias = m.identifier() != null ? name(m.identifier()) : impliedMeasureAlias(function, argument, agg);
            Ast.Pred filter = m.predicate() != null ? pred(m.predicate()) : null;
            measures.add(new Ast.Measure(alias, function, agg.DISTINCT() != null, argument, filter,
                    within(m.withinClause()), ordering(m.orderedClause()), pos(m)));
        }

        return new Ast.Analysis(clauses.entity, groups, measures, clauses.filter, clauses.top, pos(ctx));
    }

    private Ast.Stmt flat(TaqlParser.FlatStatementContext ctx) {
        Clauses clauses = clauses(ctx.queryClause(), true);

        List<Ast.Projection> projections = new ArrayList<>();
        for (TaqlParser.ProjectionContext p : ctx.projectionBlock().projection()) {
            Ast.Expr expr = expr(p.expression());
            String alias = p.identifier() != null ? name(p.identifier()) : impliedAlias(expr, p.expression(), "column");
            projections.add(new Ast.Projection(alias, expr, pos(p)));
        }

        return new Ast.Flat(clauses.entity, projections, clauses.filter, clauses.sort, clauses.top, pos(ctx));
    }

    // ------------------------------------------------------------------
    // Trailing clauses (order-insensitive, duplicates rejected here)
    // ------------------------------------------------------------------

    private static final class Clauses {
        String entity;
        Ast.Pred filter;
        Ast.Top top;
        List<Ast.SortItem> sort = List.of();
    }

    private Clauses clauses(List<TaqlParser.QueryClauseContext> list, boolean sortAllowed) {
        Clauses out = new Clauses();
        for (TaqlParser.QueryClauseContext c : list) {
            if (c.fromClause() != null) {
                if (out.entity != null) throw error(c, "duplicate 'from' clause");
                out.entity = name(c.fromClause().identifier());
            } else if (c.overClause() != null) {
                if (out.filter != null) throw error(c, "duplicate 'over' clause");
                List<Ast.Pred> parts = new ArrayList<>();
                for (TaqlParser.PredicateContext p : c.overClause().predicate()) parts.add(pred(p));
                // Filters written on separate lines are implicitly ANDed.
                out.filter = parts.isEmpty() ? null
                        : parts.size() == 1 ? parts.getFirst()
                        : new Ast.And(parts, pos(c));
            } else if (c.topClause() != null) {
                if (out.top != null) throw error(c, "duplicate 'top' clause");
                TaqlParser.TopClauseContext t = c.topClause();
                Ast.Expr count = t.countExpr().PARAM() != null
                        ? new Ast.Param(paramName(t.countExpr().PARAM()), pos(t))
                        : lit(Long.parseLong(t.countExpr().INT().getText()), Ast.LitKind.INTEGER, pos(t));
                out.top = new Ast.Top(count, t.identifier() != null ? name(t.identifier()) : null,
                        within(t.withinClause()), pos(t));
            } else if (c.sortClause() != null) {
                if (!sortAllowed) throw error(c, "'sort by' is not valid on an analysis query; use 'top N by <measure>'");
                if (!out.sort.isEmpty()) throw error(c, "duplicate 'sort by' clause");
                List<Ast.SortItem> items = new ArrayList<>();
                for (TaqlParser.SortItemContext s : c.sortClause().sortItem()) {
                    items.add(new Ast.SortItem(expr(s.expression()), s.DESC() != null, pos(s)));
                }
                out.sort = items;
            }
        }
        return out;
    }

    // ------------------------------------------------------------------
    // Predicates
    // ------------------------------------------------------------------

    private Ast.Pred pred(TaqlParser.PredicateContext ctx) {
        return or(ctx.orPredicate());
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
            case TaqlParser.InVariableContext v -> {
                Ast.Expr e = expr(v.expression());
                if (!(e instanceof Ast.Param p)) {
                    throw error(v, "'in' expects a list [...], a range a..b, or a $variable");
                }
                yield new Ast.InVariable(subject, p, negated, pos(ctx));
            }
            default -> throw error(ctx, "unsupported 'in' source");
        };
    }

    // ------------------------------------------------------------------
    // Expressions
    // ------------------------------------------------------------------

    private Ast.Expr expr(TaqlParser.ExpressionContext ctx) {
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
            case TaqlParser.ParamExprContext c -> new Ast.Param(paramName(c.PARAM()), pos(c));
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
        if (ctx.INT() != null) return lit(Long.parseLong(ctx.INT().getText()), Ast.LitKind.INTEGER, pos);
        if (ctx.DECIMAL_LIT() != null) return lit(new BigDecimal(ctx.DECIMAL_LIT().getText()), Ast.LitKind.DECIMAL, pos);
        return lit(unquote(ctx.STRING().getText()), Ast.LitKind.STRING, pos);
    }

    private Ast.Lit lit(Object value, Ast.LitKind kind, Ast.Pos pos) {
        literals.add(value);
        return new Ast.Lit(literals.size() - 1, kind, pos);
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

    private static String paramName(TerminalNode param) {
        return param.getText().substring(1);
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

    private static TaqlException error(ParserRuleContext ctx, String message) {
        Ast.Pos p = pos(ctx);
        return new TaqlException(new Diagnostic(Diagnostic.Phase.SYNTAX, p.line(), p.column(), message));
    }
}
