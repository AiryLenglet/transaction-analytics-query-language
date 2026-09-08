package ch.lenglet.taql.ast;

import java.util.List;
import java.util.StringJoiner;

/**
 * Renders an AST to a canonical string used as the plan cache key.
 *
 * Because {@link AstBuilder} has already replaced literal values with slot
 * holes, this rendering is value-independent: whitespace, comments, clause
 * order and the actual constants all wash out, while anything that changes the
 * generated SQL -- field names, operators, list arity, variable names -- is
 * preserved.
 *
 * <h2>Why the slot index is in the key</h2>
 * A cached plan's {@link ch.lenglet.taql.plan.Plan.Auto} slots index into the
 * literal table of whichever query is <em>currently running</em>, so two texts
 * may share a plan only if they number their literals identically. That holds
 * because {@link AstBuilder} allocates slots in this same canonical order --
 * which is an agreement between two files, and agreements drift.
 *
 * Rendering the slot index makes the key <em>self-checking</em>: if the two
 * walks ever disagree, the keys differ and the queries simply compile separate
 * plans. The failure mode is a redundant plan, never a query bound to another
 * query's values. It costs nothing while the walks agree, because then equal
 * shapes already imply equal numbering.
 */
final class AstPrinter {

    private AstPrinter() {}

    static String canonical(Ast.Stmt stmt) {
        StringBuilder sb = new StringBuilder();
        switch (stmt) {
            case Ast.Analysis a -> {
                sb.append("analysis|from=").append(a.entity() == null ? "*" : a.entity());
                sb.append("|by=");
                StringJoiner keys = new StringJoiner(",");
                for (Ast.GroupKey g : a.groups()) keys.add(g.alias() + ":" + expr(g.expr()));
                sb.append(keys);
                sb.append("|measures=");
                StringJoiner measures = new StringJoiner(",");
                for (Ast.Measure m : a.measures()) {
                    measures.add(m.alias() + ":" + m.function() + (m.distinct() ? "!d" : "")
                            + "(" + (m.argument() == null ? "" : expr(m.argument())) + ")"
                            + (m.filter() == null ? "" : "?" + pred(m.filter()))
                            + window(m.within(), m.ordered()));
                }
                sb.append(measures);
                sb.append("|where=").append(a.filter() == null ? "-" : pred(a.filter()));
                sb.append("|top=").append(top(a.top()));
            }
            case Ast.Flat f -> {
                sb.append("list|from=").append(f.entity() == null ? "*" : f.entity());
                sb.append("|select=");
                StringJoiner cols = new StringJoiner(",");
                for (Ast.Projection p : f.projections()) cols.add(p.alias() + ":" + expr(p.expr()));
                sb.append(cols);
                sb.append("|where=").append(f.filter() == null ? "-" : pred(f.filter()));
                sb.append("|sort=");
                StringJoiner sort = new StringJoiner(",");
                for (Ast.SortItem s : f.sort()) sort.add(expr(s.expr()) + (s.descending() ? " desc" : " asc"));
                sb.append(sort);
                sb.append("|top=").append(top(f.top()));
            }
        }
        return sb.toString();
    }

    private static String top(Ast.Top top) {
        if (top == null) return "-";
        return expr(top.count()) + (top.byMeasure() == null ? "" : " by " + top.byMeasure())
                + window(top.within(), null);
    }

    private static String window(List<String> within, Ast.Ordering ordered) {
        StringBuilder sb = new StringBuilder();
        if (within != null && !within.isEmpty()) sb.append(" within ").append(String.join(",", within));
        if (ordered != null) sb.append(" ordered by ").append(ordered.name())
                               .append(ordered.descending() ? " desc" : " asc");
        return sb.toString();
    }

    private static String expr(Ast.Expr e) {
        return switch (e) {
            case Ast.FieldRef f -> f.name();
            // The *kind* matters (it drives literal typing) and so does the slot
            // (it is what Plan.Auto indexes with); the value never does.
            case Ast.Lit l -> "#" + l.slot() + l.kind().name().charAt(0);
            case Ast.Param p -> "$" + p.name();
            case Ast.Unary u -> "(" + u.op() + expr(u.operand()) + ")";
            case Ast.Binary b -> "(" + expr(b.left()) + b.op() + expr(b.right()) + ")";
            case Ast.Call c -> c.name() + "(" + join(c.args()) + ")";
            case Ast.MatchValue m -> {
                StringBuilder sb = new StringBuilder("match(").append(expr(m.scrutinee())).append("){");
                for (Ast.ValueArm arm : m.arms()) {
                    sb.append("[").append(join(arm.patterns())).append("]->").append(expr(arm.result())).append(";");
                }
                yield sb.append("_->").append(m.otherwise() == null ? "null" : expr(m.otherwise())).append("}").toString();
            }
            case Ast.MatchCond m -> {
                StringBuilder sb = new StringBuilder("match{");
                for (Ast.CondArm arm : m.arms()) {
                    sb.append(pred(arm.condition())).append("->").append(expr(arm.result())).append(";");
                }
                yield sb.append("_->").append(m.otherwise() == null ? "null" : expr(m.otherwise())).append("}").toString();
            }
        };
    }

    private static String pred(Ast.Pred p) {
        return switch (p) {
            case Ast.And a -> "(" + joinPreds(a.operands(), " and ") + ")";
            case Ast.Or o -> "(" + joinPreds(o.operands(), " or ") + ")";
            case Ast.Not n -> "not " + pred(n.operand());
            case Ast.Compare c -> expr(c.left()) + c.op() + expr(c.right());
            // List arity is part of the shape: it changes the number of '?' emitted.
            case Ast.InList i -> expr(i.subject()) + (i.negated() ? " not in[" : " in[") + join(i.items()) + "]";
            case Ast.InRange r -> expr(r.subject()) + (r.negated() ? " not in " : " in ") + expr(r.low()) + ".." + expr(r.high());
            case Ast.InVariable v -> expr(v.subject()) + (v.negated() ? " not in $" : " in $") + v.variable().name();
            case Ast.IsNull n -> expr(n.subject()) + (n.negated() ? " is not null" : " is null");
            case Ast.Like l -> expr(l.subject()) + (l.negated() ? " not like " : " like ") + expr(l.pattern());
        };
    }

    private static String join(List<Ast.Expr> items) {
        StringJoiner j = new StringJoiner(",");
        for (Ast.Expr e : items) j.add(expr(e));
        return j.toString();
    }

    private static String joinPreds(List<Ast.Pred> items, String sep) {
        StringJoiner j = new StringJoiner(sep);
        for (Ast.Pred p : items) j.add(pred(p));
        return j.toString();
    }
}
