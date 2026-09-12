package ch.lenglet.taql.runtime;

import ch.lenglet.taql.Diagnostic;
import ch.lenglet.taql.QueryPolicy;
import ch.lenglet.taql.TaqlCompiler;
import ch.lenglet.taql.TaqlException;
import ch.lenglet.taql.TaqlQuery;
import ch.lenglet.taql.plan.Plan;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Compiles and runs TAQL queries.
 *
 * Named for {@code JdbcTemplate}, and for the same reason: it is the thing you
 * hold. It knows nothing about any store, though -- compiling belongs to
 * {@link TaqlCompiler} and a {@link ch.lenglet.taql.QueryTranslator}, running belongs to
 * a {@link PlanRunner}, and what is left here is the part that is the same
 * wherever a query runs: bind, attempt, decide whether a failure is worth
 * another go.
 *
 * <h2>Retrying is policy, not dialect</h2>
 * Every TAQL query is a {@code SELECT}, so re-running one is side-effect free --
 * which makes a deadlock victim or a dropped connection genuinely safe to retry.
 * A timeout is not: the same query will take just as long again. That reasoning
 * holds for any store, so it lives here, reading the {@link FailureCategory} a
 * runner attached rather than any exception type of its own.
 */
public final class TaqlTemplate {

    /**
     * Log lines carry {@link Plan#id()}, which correlates them with the line
     * that compiled the plan and with its statement text. Bound values never
     * appear -- see TaqlCompiler for why.
     */
    private static final Logger log = LoggerFactory.getLogger(TaqlTemplate.class);

    /**
     * @param maxAttempts        total attempts for a retryable failure.
     * @param retryBackoffMillis base delay, doubled per attempt and jittered.
     */
    public record Options(int maxAttempts, long retryBackoffMillis) {

        public static final Options DEFAULTS = new Options(3, 50);

        public Options {
            if (maxAttempts < 1) throw new IllegalArgumentException("maxAttempts must be at least 1");
            if (retryBackoffMillis < 0) throw new IllegalArgumentException("retryBackoffMillis cannot be negative");
        }
    }

    private final TaqlCompiler compiler;
    private final PlanRunner runner;
    private final QueryPolicy policy;
    private final Options options;

    public TaqlTemplate(TaqlCompiler compiler, PlanRunner runner) {
        this(compiler, runner, QueryPolicy.PERMIT_ALL, Options.DEFAULTS);
    }

    public TaqlTemplate(TaqlCompiler compiler, PlanRunner runner, Options options) {
        this(compiler, runner, QueryPolicy.PERMIT_ALL, options);
    }

    /**
     * @param policy consulted for every query, after binding and before the store
     *               is touched. {@link QueryPolicy#PERMIT_ALL} is the default, so
     *               a deployment that wants a rule has to say so.
     */
    public TaqlTemplate(TaqlCompiler compiler, PlanRunner runner, QueryPolicy policy, Options options) {
        this.compiler = compiler;
        this.runner = runner;
        this.policy = policy;
        this.options = options;
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

        for (int attempt = 1; ; attempt++) {
            try {
                return runner.run(plan, values);
            } catch (TaqlExecutionException e) {
                if (!e.failure().worthRetrying() || attempt == options.maxAttempts()) {
                    // Re-thrown so the attempt count reflects the whole call; a
                    // runner only ever knows about the one attempt it made.
                    TaqlExecutionException giveUp =
                            new TaqlExecutionException(e.failure(), e.code(), e.getCause(), attempt);
                    log.error("query failed: {}", giveUp.logDetail());
                    throw giveUp;
                }
                log.warn("attempt {} of {} failed as {} (code {}); retrying",
                        attempt, options.maxAttempts(), e.failure(), e.code());
                backoff(attempt, e);
            }
        }
    }

    /**
     * Exponential backoff, jittered.
     *
     * Without the jitter this is synchronised retry: every caller that lost the
     * same deadlock, or that was holding a connection when the store went away,
     * sleeps exactly the same doubling interval and collides again on each
     * wake-up. Spreading them is most of the value of backing off at all.
     *
     * Half the interval is fixed and half is random, so there is still a floor
     * under the wait -- full jitter can pick a delay near zero and retry into a
     * server that has not recovered.
     */
    private void backoff(int attempt, TaqlExecutionException cause) {
        // Shift capped so a generous maxAttempts cannot overflow into a
        // negative delay, which Thread.sleep rejects.
        long ceiling = options.retryBackoffMillis() << Math.min(attempt - 1, 16);
        long delay = ceiling / 2 + ThreadLocalRandom.current().nextLong(ceiling / 2 + 1);
        try {
            Thread.sleep(delay);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw cause;
        }
    }
}
