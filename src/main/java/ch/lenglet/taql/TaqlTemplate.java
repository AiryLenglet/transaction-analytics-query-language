package ch.lenglet.taql;

import ch.lenglet.taql.policy.QueryPolicy;
import ch.lenglet.taql.spi.PlanRunner;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;

/**
 * Compiles and runs TAQL queries.
 *
 * Named for {@code JdbcTemplate}, and for the same reason: it is the thing you
 * hold. It knows nothing about any store, though -- compiling belongs to
 * {@link TaqlCompiler} and a {@link ch.lenglet.taql.spi.QueryTranslator}, running belongs to
 * a {@link PlanRunner}, and what is left here is the part that is the same
 * wherever a query runs: bind, attempt, decide whether a failure is worth
 * another go.
 *
 * <h2>What it does not do</h2>
 * Retry, or shed load. Both are {@link PlanRunner}s that wrap another one, so
 * how much of either a deployment wants is something it composes:
 *
 * <pre>{@code
 * new TaqlTemplate(compiler,
 *         new CircuitBreakingPlanRunner(
 *                 new RetryingPlanRunner(
 *                         new JdbcPlanRunner(dataSource))));
 * }</pre>
 *
 * The order is deliberate; see {@link CircuitBreakingPlanRunner}.
 */
public final class TaqlTemplate {

    /**
     * Log lines carry {@link Plan#id()}, which correlates them with the line
     * that compiled the plan and with its statement text. Bound values never
     * appear -- see TaqlCompiler for why.
     */
    private static final Logger log = LoggerFactory.getLogger(TaqlTemplate.class);

    private final TaqlCompiler compiler;
    private final PlanRunner runner;
    private final QueryPolicy policy;

    public TaqlTemplate(TaqlCompiler compiler, PlanRunner runner) {
        this(compiler, runner, QueryPolicy.PERMIT_ALL);
    }

    /**
     * @param runner where a compiled plan goes. Retrying and circuit breaking
     *               are {@link PlanRunner}s that wrap another, so a deployment
     *               composes what it wants rather than configuring it here.
     * @param policy consulted for every query, before the store is touched.
     *               {@link QueryPolicy#PERMIT_ALL} is the default, so a
     *               deployment that wants a rule has to say so.
     */
    public TaqlTemplate(TaqlCompiler compiler, PlanRunner runner, QueryPolicy policy) {
        this.compiler = compiler;
        this.runner = runner;
        this.policy = policy;
    }

    /**
     * Compiles and runs {@code query}.
     *
     * Rows are keyed by output name, in the order the query projects them, so
     * they serialise directly to the JSON an API returns. The column types are
     * a property of the query rather than of its result, so they live on the
     * {@link Plan} -- ask the compiler, which answers without a database and
     * answers for an empty result too.
     *
     * @throws ch.lenglet.taql.TaqlException  the query is invalid, or asks for
     *                                        more rows than the runner allows --
     *                                        a 400, with diagnostics that are
     *                                        safe to return
     * @throws TaqlExecutionException         the query is valid but did not run
     */
    public List<Map<String, Object>> execute(TaqlQuery query) {
        TaqlCompiler.Compiled compiled = compiler.compile(query.source());
        Plan plan = compiled.plan();
        // Before the store is touched, because refusing afterwards is not
        // refusing. The parsed query is what the policy reads: it holds the
        // shape and, since there are no placeholders, the values too.
        List<Diagnostic> refusals = policy.check(compiled.parsed());
        if (!refusals.isEmpty()) throw new TaqlException(refusals);

        List<Object> values = compiled.bind();
        return runner.run(plan, values);
    }
}
