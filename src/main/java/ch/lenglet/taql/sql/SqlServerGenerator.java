package ch.lenglet.taql.sql;

import ch.lenglet.taql.Backend;
import ch.lenglet.taql.PhysicalType;
import ch.lenglet.taql.TaqlType;
import ch.lenglet.taql.catalog.Catalog;
import ch.lenglet.taql.plan.Plan;
import ch.lenglet.taql.sem.Tam;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Typed analytics model -> T-SQL.
 *
 * A single sequential pass: SQL text is appended in final statement order, and
 * every time a placeholder is emitted the matching {@link Plan.ParamSlot} is
 * appended to {@code parameters}. That keeps slot order and JDBC parameter
 * index in lockstep for free, including in the awkward case where the same
 * expression is rendered twice (a computed group key appears in both SELECT and
 * GROUP BY, and so contributes its parameters twice).
 *
 * Nothing user-supplied is ever concatenated into the text: identifiers come
 * from the catalog, operators from a closed set, and values only ever as '?'.
 */
public final class SqlServerGenerator implements Backend {

    // Aliases the generator gives its own levels. They must differ from each
    // other -- all three can nest in one statement -- and no table may be given
    // one, which allocateAliases() enforces by reserving them first.
    private static final String DERIVED = "g";
    private static final String WINDOWED = "q";
    private static final String RANKED = "w";
    private static final String RANK_COLUMN = "__rank";
    private static final String ROOT = "";

    private final StringBuilder sql = new StringBuilder();
    private final List<Plan.ParamSlot> parameters = new ArrayList<>();

    /** Source name (ROOT, or a join name) -> the alias this statement gives it. */
    private Map<String, String> tableAliases = Map.of();

    /** When set, column references resolve to the derived table instead of the base tables. */
    private Map<Catalog.Field, String> derivedNames;

    @Override
    public String name() {
        return "sqlserver";
    }

    /**
     * What a value binds as when no column typed it. Wide enough not to
     * truncate, narrow enough to be a real type -- a literal that never met a
     * column is not being compared to one, so there is no index seek to lose.
     */
    @Override
    public PhysicalType defaultTypeFor(TaqlType type) {
        return switch (type.kind()) {
            case STRING -> new SqlType.VarChar(400);
            case INTEGER -> new SqlType.BigInt();
            case DECIMAL -> new SqlType.Decimal(38, 10);
            case DATE -> new SqlType.Date();
            case TIMESTAMP -> new SqlType.DateTime2(7);
            case BOOLEAN -> new SqlType.Bit();
            case NULL -> new SqlType.VarChar(400);
            case LIST -> defaultTypeFor(type.element());
        };
    }

    @Override
    public Plan generate(Tam.Query query, Map<String, TaqlType> variables, String shapeKey) {
        // Emission state is per statement, so each call builds its own
        // generator: the instance a compiler holds stays stateless and shared.
        SqlServerGenerator emission = new SqlServerGenerator();
        emission.query(query);
        List<Plan.Column> columns = query.outputs().stream()
                .map(o -> new Plan.Column(o.alias(), o.expr().type()))
                .toList();
        return new Plan(emission.sql.toString(), emission.parameters, columns, variables, shapeKey);
    }

    private void query(Tam.Query q) {
        tableAliases = allocateAliases(q.entity());
        if (q.kind() == Tam.Kind.ANALYSIS && q.hasWindowLevel()) {
            analysisWithWindows(q);
            return;
        }
        if (q.kind() == Tam.Kind.ANALYSIS && needsDerivedTable(q)) {
            analysisOverDerivedTable(q);
            return;
        }
        selectClause(q);
        fromClause(q);
        whereClause(q);
        groupByClause(q);
        orderByClause(q);
    }

    /**
     * A computed group key has to appear in both SELECT and GROUP BY, and T-SQL
     * matches those two occurrences syntactically. Once the key's constants are
     * parameterised the copies no longer match -- the SELECT copy binds @P1..
     * and the GROUP BY copy binds @P13.., so SQL Server reports the underlying
     * column as "not contained in either an aggregate function or the GROUP BY
     * clause". Emitting the key once inside a derived table and grouping by its
     * alias sidesteps the whole question.
     *
     * Only keys that actually carry parameters need this; grouping by a plain
     * column, or by something like YEAR(date), repeats harmlessly.
     */
    private static boolean needsDerivedTable(Tam.Query q) {
        return q.groups().stream().anyMatch(g -> carriesParameter(g.expr()));
    }

    private static boolean carriesParameter(Tam.Expr e) {
        return switch (e) {
            case Tam.LiteralRef ignored -> true;
            case Tam.Variable ignored -> true;
            case Tam.Constant ignored -> true;
            case Tam.Column ignored -> false;
            case Tam.OutputRef ignored -> false;
            case Tam.NullValue ignored -> false;
            case Tam.Unary u -> carriesParameter(u.operand());
            case Tam.Binary b -> carriesParameter(b.left()) || carriesParameter(b.right());
            case Tam.Func f -> f.args().stream().anyMatch(SqlServerGenerator::carriesParameter);
            case Tam.Case c -> c.whens().stream().anyMatch(w -> carriesParameter(w.result()) || carriesParameter(w.condition()))
                    || (c.otherwise() != null && carriesParameter(c.otherwise()));
            case Tam.Aggregate a -> (a.argument() != null && carriesParameter(a.argument()))
                    || (a.filter() != null && carriesParameter(a.filter()));
            case Tam.Window ignored -> false;
        };
    }

    private static boolean carriesParameter(Tam.Pred p) {
        return switch (p) {
            case Tam.And a -> a.operands().stream().anyMatch(SqlServerGenerator::carriesParameter);
            case Tam.Or o -> o.operands().stream().anyMatch(SqlServerGenerator::carriesParameter);
            case Tam.Not n -> carriesParameter(n.operand());
            case Tam.Compare c -> carriesParameter(c.left()) || carriesParameter(c.right());
            case Tam.InList i -> carriesParameter(i.subject()) || i.items().stream().anyMatch(SqlServerGenerator::carriesParameter);
            case Tam.Between b -> carriesParameter(b.subject()) || carriesParameter(b.low()) || carriesParameter(b.high());
            case Tam.InVariable ignored -> true;
            case Tam.IsNull n -> carriesParameter(n.subject());
            case Tam.Like l -> carriesParameter(l.subject()) || carriesParameter(l.pattern());
        };
    }

    private void analysisOverDerivedTable(Tam.Query q) {
        // Columns the measures read have to be carried through the derived table.
        Map<Catalog.Field, String> names = derivedColumnNames(q);

        selectKeyword(q);

        List<Tam.Output> outputs = q.outputs();
        for (int i = 0; i < outputs.size(); i++) {
            Tam.Output o = outputs.get(i);
            sql.append("       ");
            if (i < q.groups().size()) {
                sql.append(DERIVED).append(".").append(quote(o.alias()));
            } else {
                derivedNames = names;      // rewrite measure columns onto the derived table
                expr(o.expr());
                derivedNames = null;
            }
            sql.append(" AS ").append(quote(o.alias()));
            if (i < outputs.size() - 1) sql.append(",");
            sql.append("\n");
        }

        sql.append("FROM (\n    SELECT ");
        for (int i = 0; i < q.groups().size(); i++) {
            if (i > 0) sql.append(",\n           ");
            expr(q.groups().get(i).expr());
            sql.append(" AS ").append(quote(q.groups().get(i).alias()));
        }
        for (Map.Entry<Catalog.Field, String> e : names.entrySet()) {
            sql.append(",\n           ")
               .append(aliasOf(e.getKey())).append(".").append(quote(e.getKey().column()))
               .append(" AS ").append(quote(e.getValue()));
        }
        sql.append("\n    ");

        int from = sql.length();
        fromClause(q);
        whereClause(q);
        // Indent the block we just appended so the derived table reads as one unit.
        String body = sql.substring(from).stripTrailing().replace("\n", "\n    ");
        sql.setLength(from);
        sql.append(body).append("\n");

        sql.append(") AS ").append(DERIVED).append("\n");

        sql.append("GROUP BY ");
        for (int i = 0; i < q.groups().size(); i++) {
            if (i > 0) sql.append(", ");
            sql.append(DERIVED).append(".").append(quote(q.groups().get(i).alias()));
        }
        sql.append("\n");

        orderByClause(q);
    }

    /** Distinct base columns the measures need, given stable names free of the group aliases. */
    private static Map<Catalog.Field, String> derivedColumnNames(Tam.Query q) {
        Set<Catalog.Field> used = new LinkedHashSet<>();
        for (Tam.Output m : q.measures()) collectColumns(m.expr(), used);

        Set<String> taken = new LinkedHashSet<>();
        for (Tam.Output g : q.groups()) taken.add(g.alias());

        Map<Catalog.Field, String> names = new LinkedHashMap<>();
        int next = 0;
        for (Catalog.Field f : used) {
            String candidate;
            do {
                candidate = "c" + next++;
            } while (!taken.add(candidate));
            names.put(f, candidate);
        }
        return names;
    }

    private static void collectColumns(Tam.Expr e, Set<Catalog.Field> out) {
        switch (e) {
            case Tam.Column c -> out.add(c.field());
            case Tam.Unary u -> collectColumns(u.operand(), out);
            case Tam.Binary b -> {
                collectColumns(b.left(), out);
                collectColumns(b.right(), out);
            }
            case Tam.Window ignored -> { }
            case Tam.Func f -> f.args().forEach(a -> collectColumns(a, out));
            case Tam.Case c -> {
                for (Tam.When w : c.whens()) {
                    collectColumns(w.condition(), out);
                    collectColumns(w.result(), out);
                }
                if (c.otherwise() != null) collectColumns(c.otherwise(), out);
            }
            case Tam.Aggregate a -> {
                if (a.argument() != null) collectColumns(a.argument(), out);
                if (a.filter() != null) collectColumns(a.filter(), out);
            }
            default -> { }
        }
    }

    private static void collectColumns(Tam.Pred p, Set<Catalog.Field> out) {
        switch (p) {
            case Tam.And a -> a.operands().forEach(o -> collectColumns(o, out));
            case Tam.Or o -> o.operands().forEach(x -> collectColumns(x, out));
            case Tam.Not n -> collectColumns(n.operand(), out);
            case Tam.Compare c -> {
                collectColumns(c.left(), out);
                collectColumns(c.right(), out);
            }
            case Tam.InList i -> {
                collectColumns(i.subject(), out);
                i.items().forEach(x -> collectColumns(x, out));
            }
            case Tam.Between b -> {
                collectColumns(b.subject(), out);
                collectColumns(b.low(), out);
                collectColumns(b.high(), out);
            }
            case Tam.InVariable v -> collectColumns(v.subject(), out);
            case Tam.IsNull n -> collectColumns(n.subject(), out);
            case Tam.Like l -> {
                collectColumns(l.subject(), out);
                collectColumns(l.pattern(), out);
            }
        }
    }

    // ------------------------------------------------------------------

    private void selectClause(Tam.Query q) {
        selectKeyword(q);

        List<Tam.Output> outputs = q.outputs();
        for (int i = 0; i < outputs.size(); i++) {
            sql.append("       ");
            expr(outputs.get(i).expr());
            sql.append(" AS ").append(quote(outputs.get(i).alias()));
            if (i < outputs.size() - 1) sql.append(",");
            sql.append("\n");
        }
    }

    /**
     * A window function cannot see the aggregate it reads in the same SELECT, and
     * ROW_NUMBER cannot be filtered where it is defined -- SQL Server rejects
     * both. So the grouped query becomes a subquery, the window level computes
     * over its columns, and (when 'top N ... within' asked for a per-group cap)
     * one more level applies the rank predicate.
     *
     *   SELECT ... FROM ( SELECT ..., ROW_NUMBER() OVER (...) AS rank
     *                     FROM ( ...GROUP BY... ) g ) w
     *   WHERE w.[rank] <= ?
     */
    private void analysisWithWindows(Tam.Query q) {
        boolean ranked = q.rankFilter() != null;

        if (ranked) {
            selectKeyword(q);
            List<Tam.Output> outputs = q.outputs();
            for (int i = 0; i < outputs.size(); i++) {
                sql.append("       ").append(RANKED).append(".").append(quote(outputs.get(i).alias()))
                   .append(" AS ").append(quote(outputs.get(i).alias()));
                if (i < outputs.size() - 1) sql.append(",");
                sql.append("\n");
            }
            sql.append("FROM (\n");
        }

        // The window level: group keys and measures passed through, windows computed.
        String body = capture(() -> {
            if (!ranked) selectKeyword(q);
            else sql.append("SELECT\n");

            for (Tam.Output o : q.outputs()) {
                sql.append("       ");
                if (o.expr() instanceof Tam.Window w) window(w);
                else sql.append(WINDOWED).append(".").append(quote(o.alias()));
                sql.append(" AS ").append(quote(o.alias())).append(",\n");
            }
            if (ranked) {
                sql.append("       ");
                rowNumber(q.rankFilter());
                sql.append(" AS ").append(quote(RANK_COLUMN)).append(",\n");
            }
            sql.setLength(sql.length() - 2);
            sql.append("\n");

            sql.append("FROM (\n");
            sql.append(indent(capture(() -> groupedQuery(q))));
            sql.append(") AS ").append(WINDOWED).append("\n");
        });

        sql.append(ranked ? indent(body) : body);

        if (ranked) {
            sql.append(") AS ").append(RANKED).append("\n");
            sql.append("WHERE ").append(RANKED).append(".").append(quote(RANK_COLUMN)).append(" <= ");
            value(q.rankFilter().limit());
            sql.append("\n");
        }
        orderByClause(q);
    }

    /** The grouped query, without TOP or ORDER BY -- those belong to the outermost level. */
    private void groupedQuery(Tam.Query q) {
        Tam.Query grouped = new Tam.Query(q.kind(), q.entity(), q.joins(), q.groups(), q.measures(),
                List.of(), List.of(), q.filter(), List.of(), null, null);
        if (needsDerivedTable(grouped)) {
            analysisOverDerivedTable(grouped);
        } else {
            selectClause(grouped);
            fromClause(grouped);
            whereClause(grouped);
            groupByClause(grouped);
        }
    }

    private void window(Tam.Window w) {
        switch (w.function()) {
            // A share of zero would divide by zero (error 8134), so it yields NULL instead.
            case "share" -> {
                sql.append(WINDOWED).append(".").append(quote(w.argument().alias()))
                   .append(" * 1.0 / NULLIF(SUM(").append(WINDOWED).append(".")
                   .append(quote(w.argument().alias())).append(")");
                over(w.partition(), null, false);
                sql.append(", 0)");
            }
            case "rank" -> {
                sql.append("RANK()");
                over(w.partition(), w.order(), w.descending());
            }
            case "lag", "lead" -> {
                sql.append(w.function().toUpperCase()).append("(")
                   .append(WINDOWED).append(".").append(quote(w.argument().alias())).append(")");
                over(w.partition(), w.order(), w.descending());
            }
            default -> throw new IllegalStateException("no SQL mapping for window function " + w.function());
        }
    }

    private void rowNumber(Tam.RankFilter rank) {
        sql.append("ROW_NUMBER()");
        over(rank.partition(), rank.order(), rank.descending());
    }

    private void over(List<Tam.OutputRef> partition, Tam.OutputRef order, boolean descending) {
        sql.append(" OVER (");
        if (!partition.isEmpty()) {
            sql.append("PARTITION BY ");
            for (int i = 0; i < partition.size(); i++) {
                if (i > 0) sql.append(", ");
                sql.append(WINDOWED).append(".").append(quote(partition.get(i).alias()));
            }
        }
        if (order != null) {
            if (!partition.isEmpty()) sql.append(" ");
            sql.append("ORDER BY ").append(WINDOWED).append(".").append(quote(order.alias()))
               .append(descending ? " DESC" : " ASC");
        }
        sql.append(")");
    }

    /** Runs {@code emit} against a fresh buffer, keeping parameter order intact. */
    private String capture(Runnable emit) {
        int mark = sql.length();
        emit.run();
        String captured = sql.substring(mark);
        sql.setLength(mark);
        return captured;
    }

    private static String indent(String block) {
        return "    " + block.stripTrailing().replace("\n", "\n    ") + "\n";
    }

    /** TOP appears only when the query asked for one, so the SQL mirrors the TAQL. */
    private void selectKeyword(Tam.Query q) {
        sql.append("SELECT");
        if (q.limit() != null) {
            sql.append(" TOP (");
            value(q.limit());
            sql.append(")");
        }
        sql.append("\n");
    }

    private void fromClause(Tam.Query q) {
        Catalog.Entity e = q.entity();
        String root = tableAliases.get(ROOT);
        sql.append("FROM ").append(table(e.table(), root)).append("\n");
        // Emit joins in catalog order so the SQL text is stable for a given shape.
        for (Catalog.Join j : e.joins()) {
            if (!q.joins().contains(j.name())) continue;
            String alias = tableAliases.get(j.name());
            sql.append(j.inner() ? "INNER JOIN " : "LEFT JOIN ")
               .append(table(j.table(), alias))
               .append(" ON ");
            // Built from column pairs, so the condition is never SQL text from config.
            for (int i = 0; i < j.on().size(); i++) {
                if (i > 0) sql.append(" AND ");
                Catalog.Join.On on = j.on().get(i);
                sql.append(root).append(".").append(quote(on.rootColumn()))
                   .append(" = ")
                   .append(alias).append(".").append(quote(on.joinedColumn()));
            }
            sql.append("\n");
        }
    }

    private void whereClause(Tam.Query q) {
        if (q.filter() == null) return;
        sql.append("WHERE ");
        pred(q.filter());
        sql.append("\n");
    }

    private void groupByClause(Tam.Query q) {
        if (q.kind() != Tam.Kind.ANALYSIS || q.groups().isEmpty()) return;
        sql.append("GROUP BY ");
        for (int i = 0; i < q.groups().size(); i++) {
            if (i > 0) sql.append(", ");
            // T-SQL cannot group by a SELECT alias, so the expression is rendered again.
            expr(q.groups().get(i).expr());
        }
        sql.append("\n");
    }

    private void orderByClause(Tam.Query q) {
        if (q.sort().isEmpty()) return;
        sql.append("ORDER BY ");
        for (int i = 0; i < q.sort().size(); i++) {
            if (i > 0) sql.append(", ");
            Tam.Sort s = q.sort().get(i);
            expr(s.expr());
            sql.append(s.descending() ? " DESC" : " ASC");
        }
        sql.append("\n");
    }

    // ------------------------------------------------------------------
    // Expressions
    // ------------------------------------------------------------------

    private void expr(Tam.Expr e) {
        switch (e) {
            case Tam.Column c -> {
                String derived = derivedNames == null ? null : derivedNames.get(c.field());
                if (derived != null) sql.append(DERIVED).append(".").append(quote(derived));
                else sql.append(aliasOf(c.field())).append(".").append(quote(c.field().column()));
            }
            case Tam.OutputRef o -> sql.append(quote(o.alias()));
            case Tam.NullValue ignored -> sql.append("NULL");
            case Tam.LiteralRef l -> value(l);
            case Tam.Variable v -> value(v);
            case Tam.Constant c -> value(c);
            case Tam.Unary u -> {
                sql.append("(").append(u.op());
                expr(u.operand());
                sql.append(")");
            }
            case Tam.Binary b -> {
                sql.append("(");
                expr(b.left());
                sql.append(" ").append(b.op()).append(" ");
                expr(b.right());
                sql.append(")");
            }
            case Tam.Func f -> func(f);
            case Tam.Case c -> caseExpr(c);
            case Tam.Aggregate a -> aggregate(a);
            case Tam.Window w -> window(w);
        }
    }

    private void func(Tam.Func f) {
        switch (f.name()) {
            case "year", "month", "day" -> {
                sql.append(f.name().toUpperCase()).append("(");
                expr(f.args().getFirst());
                sql.append(")");
            }
            case "length" -> {
                sql.append("LEN(");
                expr(f.args().getFirst());
                sql.append(")");
            }
            case "upper", "lower", "abs", "round", "concat", "coalesce" -> {
                sql.append(f.name().toUpperCase()).append("(");
                for (int i = 0; i < f.args().size(); i++) {
                    if (i > 0) sql.append(", ");
                    expr(f.args().get(i));
                }
                sql.append(")");
            }
            default -> throw new IllegalStateException("no SQL mapping for function " + f.name());
        }
    }

    private void caseExpr(Tam.Case c) {
        sql.append("CASE");
        for (Tam.When w : c.whens()) {
            sql.append(" WHEN ");
            pred(w.condition());
            sql.append(" THEN ");
            expr(w.result());
        }
        if (c.otherwise() != null) {
            sql.append(" ELSE ");
            expr(c.otherwise());
        }
        sql.append(" END");
    }

    /**
     * {@code sum(x) when p} becomes a conditional aggregate rather than a
     * filtered subquery, so several differently-filtered measures still read
     * the table once.
     */
    private void aggregate(Tam.Aggregate a) {
        boolean isCount = a.function().equals("count");

        if (isCount && a.argument() == null) {
            if (a.filter() == null) {
                sql.append("COUNT(*)");
            } else {
                sql.append("COUNT(CASE WHEN ");
                pred(a.filter());
                sql.append(" THEN 1 END)");
            }
            return;
        }

        sql.append(a.function().toUpperCase()).append("(");
        if (a.distinct()) sql.append("DISTINCT ");
        if (a.filter() == null) {
            expr(a.argument());
        } else {
            // No ELSE: unmatched rows yield NULL and are skipped by the aggregate.
            sql.append("CASE WHEN ");
            pred(a.filter());
            sql.append(" THEN ");
            expr(a.argument());
            sql.append(" END");
        }
        sql.append(")");
    }

    // ------------------------------------------------------------------
    // Predicates
    // ------------------------------------------------------------------

    private void pred(Tam.Pred p) {
        switch (p) {
            case Tam.And a -> combine(a.operands(), " AND ");
            case Tam.Or o -> combine(o.operands(), " OR ");
            case Tam.Not n -> {
                sql.append("NOT (");
                pred(n.operand());
                sql.append(")");
            }
            case Tam.Compare c -> {
                expr(c.left());
                sql.append(" ").append(sqlOperator(c.op())).append(" ");
                expr(c.right());
            }
            case Tam.InList i -> {
                expr(i.subject());
                sql.append(i.negated() ? " NOT IN (" : " IN (");
                for (int k = 0; k < i.items().size(); k++) {
                    if (k > 0) sql.append(", ");
                    expr(i.items().get(k));
                }
                sql.append(")");
            }
            case Tam.Between b -> {
                expr(b.subject());
                sql.append(b.negated() ? " NOT BETWEEN " : " BETWEEN ");
                expr(b.low());
                sql.append(" AND ");
                expr(b.high());
            }
            case Tam.InVariable v -> inVariable(v);
            case Tam.IsNull n -> {
                expr(n.subject());
                sql.append(n.negated() ? " IS NOT NULL" : " IS NULL");
            }
            case Tam.Like l -> {
                expr(l.subject());
                sql.append(l.negated() ? " NOT LIKE " : " LIKE ");
                expr(l.pattern());
            }
        }
    }

    /**
     * A list variable has unknown arity at plan time, so {@code IN (?, ?, ...)}
     * is not an option -- the SQL text would depend on the caller's data and
     * every distinct length would compile a new plan (and a new plan in SQL
     * Server's own cache too). Binding the list as one JSON parameter and
     * shredding it with OPENJSON keeps both plans stable, and the WITH clause
     * gives the values the column's own type so the comparison can still seek.
     */
    private void inVariable(Tam.InVariable v) {
        Tam.Variable var = v.variable();
        // A variable resolved against a column carries that column's type; one
        // that never met a column falls back to something wide enough to hold it.
        SqlType elementType = var.physicalType() instanceof SqlType sql ? sql : new SqlType.VarChar(400);
        expr(v.subject());
        sql.append(v.negated() ? " NOT IN (" : " IN (");
        // The rendered type comes from SqlType, not from a string in the catalog.
        sql.append("SELECT [value] FROM OPENJSON(?) WITH ([value] ").append(elementType.sql()).append(" '$')");
        sql.append(")");
        parameters.add(new Plan.VariableList(var.name(), var.type().element(), elementType));
    }

    private void combine(List<Tam.Pred> operands, String separator) {
        for (int i = 0; i < operands.size(); i++) {
            if (i > 0) sql.append(separator);
            boolean parenthesise = operands.get(i) instanceof Tam.And || operands.get(i) instanceof Tam.Or;
            if (parenthesise) sql.append("(");
            pred(operands.get(i));
            if (parenthesise) sql.append(")");
        }
    }

    // ------------------------------------------------------------------
    // Placeholders
    // ------------------------------------------------------------------

    /** The only path by which a value reaches the statement -- and it is always a '?'. */
    private void value(Tam.Expr e) {
        switch (e) {
            case Tam.LiteralRef l -> {
                parameters.add(new Plan.Auto(l.slot(), l.type(), l.physicalType()));
                sql.append("?");
            }
            case Tam.Variable v -> {
                parameters.add(new Plan.Variable(v.name(), v.type(), v.physicalType()));
                sql.append("?");
            }
            case Tam.Constant c -> {
                parameters.add(new Plan.Constant(c.value(), c.type(), c.physicalType()));
                sql.append("?");
            }
            default -> expr(e);
        }
    }

    private static String sqlOperator(String op) {
        return switch (op) {
            case "=" -> "=";
            case "!=", "<>" -> "<>";
            case "<" -> "<";
            case "<=" -> "<=";
            case ">" -> ">";
            case ">=" -> ">=";
            default -> throw new IllegalStateException("unknown operator " + op);
        };
    }

    /**
     * Aliases are assigned here rather than stored in the catalog, so a table can
     * appear in a catalog without committing to a name in every statement.
     *
     * Every table of the entity gets one, whether or not this query uses it, so a
     * given table reads the same across every plan on that entity. Allocation is
     * a pure function of the entity, so the SQL text stays byte-identical for a
     * given query shape and the plan cache is unaffected.
     *
     * The generator's own level aliases are reserved before any table is named,
     * so a Groups or Wires table cannot be handed the name a derived, window or
     * rank level is about to use.
     */
    private static Map<String, String> allocateAliases(Catalog.Entity entity) {
        Set<String> taken = new LinkedHashSet<>();
        taken.add(DERIVED);
        taken.add(WINDOWED);
        taken.add(RANKED);
        Map<String, String> aliases = new LinkedHashMap<>();
        aliases.put(ROOT, allocate(entity.table().name(), taken));
        for (Catalog.Join j : entity.joins()) aliases.put(j.name(), allocate(j.table().name(), taken));
        return Map.copyOf(aliases);
    }

    private static String allocate(String tableName, Set<String> taken) {
        String base = "x";
        for (int i = 0; i < tableName.length(); i++) {
            if (Character.isLetter(tableName.charAt(i))) {
                base = String.valueOf(Character.toLowerCase(tableName.charAt(i)));
                break;
            }
        }
        String candidate = base;
        for (int n = 2; !taken.add(candidate); n++) candidate = base + n;
        return candidate;
    }

    private String aliasOf(Catalog.Field field) {
        return tableAliases.get(field.source());
    }

    private String table(Catalog.Table t, String alias) {
        return quote(t.schema()) + "." + quote(t.name()) + " AS " + alias;
    }

    /** Catalog-sourced identifiers only; the bracket-doubling is belt and braces. */
    private static String quote(String identifier) {
        return "[" + identifier.replace("]", "]]") + "]";
    }
}
