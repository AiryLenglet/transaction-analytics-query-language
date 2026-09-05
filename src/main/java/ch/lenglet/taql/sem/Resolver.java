package ch.lenglet.taql.sem;

import ch.lenglet.taql.Diagnostic;
import ch.lenglet.taql.TaqlException;
import ch.lenglet.taql.SqlType;
import ch.lenglet.taql.TaqlType;
import ch.lenglet.taql.ast.Ast;
import ch.lenglet.taql.catalog.Catalog;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Untyped syntax model -> typed analytics model.
 *
 * Three jobs, all of which have to happen before SQL can be generated:
 *
 *  1. <b>Name resolution.</b> Identifiers become catalog fields, and the joins
 *     needed to reach them are collected. Names that resolve to nothing produce
 *     a diagnostic with a position, not a SQL error at runtime.
 *  2. <b>Type checking and elaboration.</b> Implicit conversions are made
 *     explicit here rather than left to the database: `date in '2010-01-01'..`
 *     types those string literals as DATE, so they bind as dates and the query
 *     can still seek on an index instead of converting the column.
 *  3. <b>Variable typing.</b> Every {@code $name} gets a type inferred from its
 *     use site, so the plan can tell a REST caller exactly which variables it
 *     needs and what shape they must be.
 */
public final class Resolver {

    /**
     * @param defaultRowLimit row cap applied to queries that state no 'top'.
     *                        Zero -- the default -- means no cap, so the
     *                        generated statement mirrors the TAQL and carries no
     *                        TOP the query did not ask for. Setting it is a
     *                        deployment choice: a REST endpoint that can return
     *                        an unbounded result is an outage waiting to happen.
     */
    public record Options(int defaultRowLimit) {
        public static final Options DEFAULTS = new Options(0);
    }

    private final Catalog catalog;
    private final Options options;
    private final List<Diagnostic> errors = new ArrayList<>();
    private final Set<String> requiredJoins = new LinkedHashSet<>();
    private final Map<String, TaqlType> variableTypes = new LinkedHashMap<>();

    private Catalog.Entity entity;

    private Resolver(Catalog catalog, Options options) {
        this.catalog = catalog;
        this.options = options;
    }

    public record Result(Tam.Query query, Map<String, TaqlType> variables) {}

    public static Result resolve(Catalog catalog, Ast.Query parsed, Options options) {
        Resolver r = new Resolver(catalog, options);
        Tam.Query query = r.statement(parsed.stmt());
        r.checkAllVariablesTyped();
        if (!r.errors.isEmpty()) throw new TaqlException(r.errors);
        return new Result(query, Map.copyOf(r.variableTypes));
    }

    // ------------------------------------------------------------------
    // Statements
    // ------------------------------------------------------------------

    private Tam.Query statement(Ast.Stmt stmt) {
        entity = resolveEntity(stmt);
        return switch (stmt) {
            case Ast.Analysis a -> analysis(a);
            case Ast.Flat f -> flat(f);
        };
    }

    private Catalog.Entity resolveEntity(Ast.Stmt stmt) {
        if (stmt.entity() == null) return catalog.defaultEntity();
        return catalog.entity(stmt.entity()).orElseThrow(() -> fail(stmt.pos(),
                Diagnostic.Phase.RESOLUTION,
                "unknown entity '" + stmt.entity() + "'; known entities: " + catalog.entities().keySet()));
    }

    private Tam.Query analysis(Ast.Analysis a) {
        List<Tam.Output> groups = new ArrayList<>();
        Set<String> aliases = new LinkedHashSet<>();
        for (Ast.GroupKey g : a.groups()) {
            Tam.Expr expr = expr(g.expr(), false);
            rejectDuplicateAlias(aliases, g.alias(), g.pos());
            groups.add(new Tam.Output(g.alias(), expr));
        }

        List<Tam.Output> measures = new ArrayList<>();
        Map<String, TaqlType> measureTypes = new LinkedHashMap<>();
        for (Ast.Measure m : a.measures()) {
            rejectDuplicateAlias(aliases, m.alias(), m.pos());
            Tam.Aggregate agg = aggregate(m);
            measureTypes.put(m.alias(), agg.type());
            measures.add(new Tam.Output(m.alias(), agg));
        }

        Tam.Pred filter = a.filter() == null ? null : pred(a.filter());

        // 'top N by <measure>' is an ordering over an output alias, not a field.
        List<Tam.Sort> sort = List.of();
        if (a.top() != null && a.top().byMeasure() != null) {
            String alias = a.top().byMeasure();
            TaqlType type = measureTypes.get(alias);
            if (type == null) {
                error(a.top().pos(), Diagnostic.Phase.RESOLUTION,
                        "'top ... by " + alias + "' must name one of the measures: " + measureTypes.keySet());
                type = TaqlType.DECIMAL;
            }
            sort = List.of(new Tam.Sort(new Tam.OutputRef(alias, type), true));
        }

        return new Tam.Query(Tam.Kind.ANALYSIS, entity, Set.copyOf(requiredJoins),
                groups, measures, List.of(), filter, sort, limit(a.top()));
    }

    private Tam.Query flat(Ast.Flat f) {
        List<Tam.Output> projections = new ArrayList<>();
        Map<String, TaqlType> outputTypes = new LinkedHashMap<>();
        Set<String> aliases = new LinkedHashSet<>();
        for (Ast.Projection p : f.projections()) {
            rejectDuplicateAlias(aliases, p.alias(), p.pos());
            Tam.Expr expr = expr(p.expr(), false);
            outputTypes.put(p.alias(), expr.type());
            projections.add(new Tam.Output(p.alias(), expr));
        }

        Tam.Pred filter = f.filter() == null ? null : pred(f.filter());

        List<Tam.Sort> sort = new ArrayList<>();
        for (Ast.SortItem s : f.sort()) {
            // Sorting by an output alias is more useful than re-resolving the
            // expression, so aliases shadow catalog fields here.
            if (s.expr() instanceof Ast.FieldRef ref && outputTypes.containsKey(ref.name())) {
                sort.add(new Tam.Sort(new Tam.OutputRef(ref.name(), outputTypes.get(ref.name())), s.descending()));
            } else {
                sort.add(new Tam.Sort(expr(s.expr(), false), s.descending()));
            }
        }

        if (f.top() != null && f.top().byMeasure() != null) {
            error(f.top().pos(), Diagnostic.Phase.RESOLUTION,
                    "'top N by ...' is only valid on an analysis query; use 'sort by ... top N'");
        }

        return new Tam.Query(Tam.Kind.FLAT, entity, Set.copyOf(requiredJoins),
                List.of(), List.of(), projections, filter, sort, limit(f.top()));
    }

    /** Null when the query states no 'top' and no cap is configured; the statement then has no TOP. */
    private Tam.Expr limit(Ast.Top top) {
        if (top == null) {
            return options.defaultRowLimit() > 0
                    ? new Tam.Constant((long) options.defaultRowLimit(), TaqlType.INTEGER, new SqlType.Int())
                    : null;
        }
        return switch (top.count()) {
            case Ast.Lit l -> new Tam.LiteralRef(l.slot(), TaqlType.INTEGER, new SqlType.Int());
            case Ast.Param p -> variable(p, TaqlType.INTEGER, new SqlType.Int());
            default -> throw fail(top.pos(), Diagnostic.Phase.TYPE, "'top' expects a number or a $variable");
        };
    }

    private void rejectDuplicateAlias(Set<String> seen, String alias, Ast.Pos pos) {
        if (!seen.add(alias)) {
            error(pos, Diagnostic.Phase.RESOLUTION, "duplicate output name '" + alias + "'");
        }
    }

    // ------------------------------------------------------------------
    // Aggregates
    // ------------------------------------------------------------------

    private Tam.Aggregate aggregate(Ast.Measure m) {
        Optional<Functions.Aggregate> spec = Functions.aggregate(m.function());
        if (spec.isEmpty()) {
            throw fail(m.pos(), Diagnostic.Phase.RESOLUTION,
                    "'" + m.function() + "' is not an aggregate; expected one of " + Functions.aggregateNames());
        }
        Functions.Aggregate agg = spec.get();

        Tam.Expr argument = null;
        TaqlType argType = TaqlType.INTEGER;
        if (m.argument() != null) {
            argument = expr(m.argument(), false);
            argType = argument.type();
            if (agg.numericOnly() && !argType.isNumeric()) {
                error(m.pos(), Diagnostic.Phase.TYPE,
                        m.function() + "() needs a numeric argument but got " + argType);
            }
        } else if (agg.requiresArgument()) {
            error(m.pos(), Diagnostic.Phase.TYPE, m.function() + "() needs an argument");
        }

        Tam.Pred filter = m.filter() == null ? null : pred(m.filter());
        return new Tam.Aggregate(agg.name(), m.distinct(), argument, filter, agg.resultType().apply(argType));
    }

    // ------------------------------------------------------------------
    // Predicates
    // ------------------------------------------------------------------

    private Tam.Pred pred(Ast.Pred p) {
        return switch (p) {
            case Ast.And a -> new Tam.And(a.operands().stream().map(this::pred).toList());
            case Ast.Or o -> new Tam.Or(o.operands().stream().map(this::pred).toList());
            case Ast.Not n -> new Tam.Not(pred(n.operand()));
            case Ast.Compare c -> {
                Tam.Expr left = expr(c.left(), false);
                Tam.Expr right = expr(c.right(), false);
                yield new Tam.Compare(c.op(), left, unify(left, right, c.pos()));
            }
            case Ast.InList i -> {
                Tam.Expr subject = expr(i.subject(), false);
                List<Tam.Expr> items = new ArrayList<>();
                for (Ast.Expr item : i.items()) items.add(unify(subject, expr(item, false), i.pos()));
                if (items.isEmpty()) error(i.pos(), Diagnostic.Phase.TYPE, "'in []' matches nothing");
                yield new Tam.InList(subject, items, i.negated());
            }
            case Ast.InRange r -> {
                Tam.Expr subject = expr(r.subject(), false);
                yield new Tam.Between(subject,
                        unify(subject, expr(r.low(), false), r.pos()),
                        unify(subject, expr(r.high(), false), r.pos()),
                        r.negated());
            }
            case Ast.InVariable v -> {
                Tam.Expr subject = expr(v.subject(), false);
                // The variable holds a *list* of whatever the subject is.
                Tam.Variable var = variable(v.variable(), TaqlType.listOf(subject.type()), sqlTypeOf(subject));
                yield new Tam.InVariable(subject, var, v.negated());
            }
            case Ast.IsNull n -> new Tam.IsNull(expr(n.subject(), false), n.negated());
            case Ast.Like l -> {
                Tam.Expr subject = expr(l.subject(), false);
                if (subject.type().kind() != TaqlType.Kind.STRING) {
                    error(l.pos(), Diagnostic.Phase.TYPE, "'like' needs a text field but got " + subject.type());
                }
                yield new Tam.Like(subject, coerce(expr(l.pattern(), false), TaqlType.STRING, new SqlType.VarChar(400), l.pos()),
                        l.negated());
            }
        };
    }

    // ------------------------------------------------------------------
    // Expressions
    // ------------------------------------------------------------------

    private Tam.Expr expr(Ast.Expr e, boolean insideAggregate) {
        return switch (e) {
            case Ast.FieldRef f -> field(f);

            case Ast.Lit l -> l.kind() == Ast.LitKind.NULL
                    ? new Tam.NullValue(TaqlType.NULL)
                    : new Tam.LiteralRef(l.slot(), litType(l.kind()), sqlTypeFor(litType(l.kind())));

            // Untyped for now; a use site will refine it via coerce()/unify().
            case Ast.Param p -> variable(p, TaqlType.NULL, null);

            case Ast.Unary u -> {
                Tam.Expr operand = expr(u.operand(), insideAggregate);
                if (!operand.type().isNumeric()) {
                    error(u.pos(), Diagnostic.Phase.TYPE, "unary '" + u.op() + "' needs a number but got " + operand.type());
                }
                yield new Tam.Unary(u.op(), operand, operand.type());
            }

            case Ast.Binary b -> {
                Tam.Expr left = expr(b.left(), insideAggregate);
                Tam.Expr right = expr(b.right(), insideAggregate);
                TaqlType type = arithmeticType(left, right, b.op(), b.pos());
                yield new Tam.Binary(b.op(), coerce(left, type, null, b.pos()), coerce(right, type, null, b.pos()), type);
            }

            case Ast.Call c -> call(c, insideAggregate);

            case Ast.MatchValue m -> matchValue(m, insideAggregate);

            case Ast.MatchCond m -> matchCond(m, insideAggregate);
        };
    }

    private Tam.Expr field(Ast.FieldRef ref) {
        Catalog.Field f = entity.field(ref.name()).orElseThrow(() -> fail(ref.pos(),
                Diagnostic.Phase.RESOLUTION,
                "unknown field '" + ref.name() + "' on " + entity.name() + "; available: " + entity.fieldNames()));
        requireJoinFor(f);
        return new Tam.Column(f, f.type());
    }

    /** Using a field that lives on a joined table is what pulls the join into the plan. */
    private void requireJoinFor(Catalog.Field f) {
        if (f.isJoined()) requiredJoins.add(f.source());
    }

    private Tam.Expr call(Ast.Call c, boolean insideAggregate) {
        if (Functions.aggregate(c.name()).isPresent() && !insideAggregate) {
            throw fail(c.pos(), Diagnostic.Phase.TYPE,
                    "'" + c.name() + "()' is an aggregate and can only appear in an analysis measure block");
        }
        Functions.Scalar spec = Functions.scalar(c.name()).orElseThrow(() -> fail(c.pos(),
                Diagnostic.Phase.RESOLUTION, "unknown function '" + c.name() + "'"));

        List<Tam.Expr> args = new ArrayList<>();
        for (Ast.Expr a : c.args()) args.add(expr(a, insideAggregate));

        if (spec.name().equals("coalesce")) {
            if (args.isEmpty()) throw fail(c.pos(), Diagnostic.Phase.TYPE, "coalesce() needs at least one argument");
            TaqlType type = args.getFirst().type();
            List<Tam.Expr> coerced = args.stream().map(a -> coerce(a, type, null, c.pos())).toList();
            return new Tam.Func("coalesce", coerced, type);
        }

        if (!spec.variadic() && args.size() != spec.parameters().size()) {
            throw fail(c.pos(), Diagnostic.Phase.TYPE,
                    c.name() + "() takes " + spec.parameters().size() + " argument(s), got " + args.size());
        }
        List<Tam.Expr> coerced = new ArrayList<>();
        for (int i = 0; i < args.size(); i++) {
            TaqlType want = i < spec.parameters().size()
                    ? spec.parameters().get(i)
                    : spec.parameters().isEmpty() ? args.get(i).type() : spec.parameters().getLast();
            coerced.add(coerce(args.get(i), want, null, c.pos()));
        }
        return new Tam.Func(spec.name(), coerced, spec.result());
    }

    private Tam.Expr matchValue(Ast.MatchValue m, boolean insideAggregate) {
        Tam.Expr scrutinee = expr(m.scrutinee(), insideAggregate);
        List<Tam.When> whens = new ArrayList<>();
        TaqlType resultType = null;

        for (Ast.ValueArm arm : m.arms()) {
            List<Tam.Expr> patterns = new ArrayList<>();
            for (Ast.Expr p : arm.patterns()) patterns.add(unify(scrutinee, expr(p, insideAggregate), m.pos()));
            Tam.Expr result = expr(arm.result(), insideAggregate);
            resultType = mergeResultType(resultType, result.type(), m.pos());
            whens.add(new Tam.When(new Tam.InList(scrutinee, patterns, false), result));
        }

        Tam.Expr otherwise = m.otherwise() == null ? null : expr(m.otherwise(), insideAggregate);
        if (otherwise != null) resultType = mergeResultType(resultType, otherwise.type(), m.pos());
        if (resultType == null) resultType = TaqlType.STRING;

        TaqlType finalType = resultType;
        List<Tam.When> typed = whens.stream()
                .map(w -> new Tam.When(w.condition(), coerce(w.result(), finalType, null, m.pos())))
                .toList();
        return new Tam.Case(typed, otherwise == null ? null : coerce(otherwise, finalType, null, m.pos()), finalType);
    }

    private Tam.Expr matchCond(Ast.MatchCond m, boolean insideAggregate) {
        List<Tam.When> whens = new ArrayList<>();
        TaqlType resultType = null;
        for (Ast.CondArm arm : m.arms()) {
            Tam.Pred condition = pred(arm.condition());
            Tam.Expr result = expr(arm.result(), insideAggregate);
            resultType = mergeResultType(resultType, result.type(), m.pos());
            whens.add(new Tam.When(condition, result));
        }
        Tam.Expr otherwise = m.otherwise() == null ? null : expr(m.otherwise(), insideAggregate);
        if (otherwise != null) resultType = mergeResultType(resultType, otherwise.type(), m.pos());
        if (resultType == null) resultType = TaqlType.STRING;

        TaqlType finalType = resultType;
        List<Tam.When> typed = whens.stream()
                .map(w -> new Tam.When(w.condition(), coerce(w.result(), finalType, null, m.pos())))
                .toList();
        return new Tam.Case(typed, otherwise == null ? null : coerce(otherwise, finalType, null, m.pos()), finalType);
    }

    // ------------------------------------------------------------------
    // Typing rules
    // ------------------------------------------------------------------

    private static TaqlType litType(Ast.LitKind kind) {
        return switch (kind) {
            case STRING -> TaqlType.STRING;
            case INTEGER -> TaqlType.INTEGER;
            case DECIMAL -> TaqlType.DECIMAL;
            case BOOLEAN -> TaqlType.BOOLEAN;
            case NULL -> TaqlType.NULL;
        };
    }

    /** Types the right-hand side against the left, so literals adopt the column's type. */
    private Tam.Expr unify(Tam.Expr anchor, Tam.Expr other, Ast.Pos pos) {
        return coerce(other, anchor.type(), sqlTypeOf(anchor), pos);
    }

    private static SqlType sqlTypeOf(Tam.Expr e) {
        return e instanceof Tam.Column c ? c.field().sqlType() : null;
    }

    /**
     * Widens a value expression to {@code target}. Only literals, variables and
     * NULL can change type: a column never silently converts, because that is
     * exactly the implicit conversion that stops SQL Server using an index.
     */
    private Tam.Expr coerce(Tam.Expr e, TaqlType target, SqlType sqlType, Ast.Pos pos) {
        if (target == null) return e;

        SqlType effective = sqlType != null ? sqlType : sqlTypeFor(target);

        // Same logical type, but we now know the physical type of the column
        // this value is compared against. Adopting it is the difference between
        // binding 'C' as varchar(1) and as varchar(400)/nvarchar -- the latter
        // makes SQL Server convert the column instead of seeking on it.
        if (e.type().equals(target)) {
            if (sqlType == null) return e;
            return switch (e) {
                case Tam.LiteralRef l -> l.sqlType().equals(sqlType) ? l : new Tam.LiteralRef(l.slot(), target, sqlType);
                case Tam.Variable v -> v.sqlType() != null && v.sqlType().equals(sqlType)
                        ? v : retypeVariable(v, target, sqlType, pos);
                default -> e;
            };
        }

        switch (e) {
            case Tam.LiteralRef l -> {
                if (convertible(l.type(), target)) return new Tam.LiteralRef(l.slot(), target, effective);
            }
            case Tam.Variable v -> {
                if (v.type().kind() == TaqlType.Kind.NULL || convertible(v.type(), target)) {
                    return retypeVariable(v, target, effective, pos);
                }
            }
            case Tam.NullValue ignored -> {
                return new Tam.NullValue(target);
            }
            default -> {
                if (e.type().kind() == TaqlType.Kind.INTEGER && target.kind() == TaqlType.Kind.DECIMAL) return e;
            }
        }

        if (comparable(e.type(), target)) return e;
        error(pos, Diagnostic.Phase.TYPE, "cannot use " + e.type() + " where " + target + " is expected");
        return e;
    }

    /** Which conversions we are willing to perform on a user-supplied constant. */
    private static boolean convertible(TaqlType from, TaqlType to) {
        if (from.equals(to)) return true;
        if (from.kind() == TaqlType.Kind.NULL) return true;
        return switch (to.kind()) {
            case DATE, TIMESTAMP -> from.kind() == TaqlType.Kind.STRING;
            case DECIMAL -> from.isNumeric() || from.kind() == TaqlType.Kind.STRING;
            case INTEGER -> from.kind() == TaqlType.Kind.INTEGER || from.kind() == TaqlType.Kind.STRING;
            case STRING -> from.kind() == TaqlType.Kind.STRING;
            case BOOLEAN -> from.kind() == TaqlType.Kind.BOOLEAN;
            case LIST -> from.isList();
            case NULL -> false;
        };
    }

    private static boolean comparable(TaqlType a, TaqlType b) {
        return a.equals(b) || (a.isNumeric() && b.isNumeric()) || (a.isTemporal() && b.isTemporal());
    }

    private TaqlType arithmeticType(Tam.Expr left, Tam.Expr right, String op, Ast.Pos pos) {
        boolean numeric = (left.type().isNumeric() || left.type().kind() == TaqlType.Kind.NULL)
                && (right.type().isNumeric() || right.type().kind() == TaqlType.Kind.NULL);
        if (!numeric) {
            error(pos, Diagnostic.Phase.TYPE,
                    "'" + op + "' needs numbers but got " + left.type() + " and " + right.type());
            return TaqlType.DECIMAL;
        }
        if (op.equals("/")) return TaqlType.DECIMAL;
        return left.type().kind() == TaqlType.Kind.DECIMAL || right.type().kind() == TaqlType.Kind.DECIMAL
                ? TaqlType.DECIMAL : TaqlType.INTEGER;
    }

    private TaqlType mergeResultType(TaqlType current, TaqlType candidate, Ast.Pos pos) {
        if (current == null || current.kind() == TaqlType.Kind.NULL) return candidate;
        if (candidate.kind() == TaqlType.Kind.NULL || current.equals(candidate)) return current;
        if (current.isNumeric() && candidate.isNumeric()) return TaqlType.DECIMAL;
        error(pos, Diagnostic.Phase.TYPE,
                "match arms return different types (" + current + " and " + candidate + ")");
        return current;
    }

    // ------------------------------------------------------------------
    // Variables
    // ------------------------------------------------------------------

    private Tam.Variable variable(Ast.Param p, TaqlType type, SqlType sqlType) {
        TaqlType known = variableTypes.get(p.name());
        TaqlType resolved = known == null || known.kind() == TaqlType.Kind.NULL ? type : known;
        variableTypes.put(p.name(), resolved);
        return new Tam.Variable(p.name(), resolved, sqlType != null ? sqlType : sqlTypeFor(resolved));
    }

    private Tam.Variable retypeVariable(Tam.Variable v, TaqlType target, SqlType sqlType, Ast.Pos pos) {
        TaqlType known = variableTypes.get(v.name());
        if (known != null && known.kind() != TaqlType.Kind.NULL && !known.equals(target)
                && !(known.isNumeric() && target.isNumeric())) {
            error(pos, Diagnostic.Phase.TYPE,
                    "$" + v.name() + " is used as " + known + " and as " + target);
        }
        variableTypes.put(v.name(), target);
        return new Tam.Variable(v.name(), target, sqlType);
    }

    private void checkAllVariablesTyped() {
        variableTypes.forEach((name, type) -> {
            if (type.kind() == TaqlType.Kind.NULL) {
                error(Ast.Pos.NONE, Diagnostic.Phase.TYPE,
                        "cannot infer the type of $" + name + "; use it in a comparison with a field");
            }
        });
    }

    /** Fallback physical type for a value with no column to take one from. */
    static SqlType sqlTypeFor(TaqlType type) {
        return switch (type.kind()) {
            case STRING -> new SqlType.VarChar(400);
            case INTEGER -> new SqlType.BigInt();
            case DECIMAL -> new SqlType.Decimal(38, 10);
            case DATE -> new SqlType.Date();
            case TIMESTAMP -> new SqlType.DateTime2(7);
            case BOOLEAN -> new SqlType.Bit();
            case NULL -> new SqlType.VarChar(400);
            case LIST -> sqlTypeFor(type.element());
        };
    }

    // ------------------------------------------------------------------

    private void error(Ast.Pos pos, Diagnostic.Phase phase, String message) {
        errors.add(new Diagnostic(phase, pos.line(), pos.column(), message));
    }

    private TaqlException fail(Ast.Pos pos, Diagnostic.Phase phase, String message) {
        List<Diagnostic> all = new ArrayList<>(errors);
        all.add(new Diagnostic(phase, pos.line(), pos.column(), message));
        return new TaqlException(all);
    }
}
