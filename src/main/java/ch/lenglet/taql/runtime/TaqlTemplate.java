package ch.lenglet.taql.runtime;

import ch.lenglet.taql.Diagnostic;
import ch.lenglet.taql.TaqlCompiler;
import ch.lenglet.taql.TaqlQuery;
import ch.lenglet.taql.TaqlException;
import ch.lenglet.taql.plan.Plan;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Runs TAQL queries. The only place JDBC appears.
 *
 * <p>Named for {@code JdbcTemplate}, and for the same reason: it owns a
 * resource. Compiling needs no database, so it stays on {@link TaqlCompiler}
 * -- a validation or schema endpoint has no business holding a DataSource.
 * Share one compiler between the two; it owns both plan caches.
 *
 * <h2>Error handling</h2>
 * Compilation has already ruled out malformed and mistyped queries, so a
 * failure here means something about the environment: a lock conflict, a
 * resource limit, a schema that no longer matches the catalog. Those need
 * different answers, so {@link SqlFailure} classifies them and the executor
 * acts on the classification:
 *
 * <ul>
 *   <li><b>Always bound the query.</b> Every statement gets a
 *       {@code queryTimeout}; an endpoint that can hold a pooled connection
 *       indefinitely will eventually exhaust the pool and take down every other
 *       endpoint sharing it.</li>
 *   <li><b>Retry only what is worth retrying.</b> Every TAQL query is a
 *       {@code SELECT}, so re-running one is side-effect free -- which makes a
 *       deadlock victim or a dropped connection genuinely safe to retry. A
 *       timeout is not: the same query will take just as long again.</li>
 *   <li><b>Never surface the server's message.</b> It can quote schema and row
 *       values, and it is localised, so it is unusable as an API contract.
 *       {@link TaqlExecutionException} exposes a safe message for the response
 *       and the raw detail for the log.</li>
 * </ul>
 */
public final class TaqlTemplate {

    /**
     * @param queryTimeoutSeconds per-statement timeout; 0 disables it, which is
     *                            almost never what a service wants.
     * @param maxAttempts         total attempts for a retryable failure.
     * @param retryBackoffMillis  base delay, doubled per attempt and jittered.
     * @param maxRows             hard ceiling on rows returned. A query that
     *                            would exceed it fails rather than returning a
     *                            silently truncated answer: an analytical result
     *                            missing rows nobody mentioned is worse than an
     *                            error saying so. Not a default that can be set
     *                            to "unlimited" -- that is the setting that
     *                            takes the process down.
     * @param fetchSize           JDBC fetch size, so the driver streams instead
     *                            of buffering the whole result before the first
     *                            row is read.
     */
    public record Options(int queryTimeoutSeconds, int maxAttempts, long retryBackoffMillis,
                          int maxRows, int fetchSize) {

        public static final Options DEFAULTS = new Options(30, 3, 50, 10_000, 1_000);

        public Options {
            if (maxAttempts < 1) throw new IllegalArgumentException("maxAttempts must be at least 1");
            if (retryBackoffMillis < 0) throw new IllegalArgumentException("retryBackoffMillis cannot be negative");
            if (maxRows < 1) throw new IllegalArgumentException("maxRows must be at least 1");
            if (fetchSize < 1) throw new IllegalArgumentException("fetchSize must be at least 1");
        }
    }

    /**
     * Log lines carry {@link Plan#id()}, which correlates them with the line
     * that compiled the plan and with its SQL. Bound values never appear -- see
     * TaqlCompiler for why.
     */
    private static final Logger log = LoggerFactory.getLogger(TaqlTemplate.class);

    private final TaqlCompiler compiler;
    private final DataSource dataSource;
    private final Options options;

    public TaqlTemplate(TaqlCompiler compiler, DataSource dataSource) {
        this(compiler, dataSource, Options.DEFAULTS);
    }

    public TaqlTemplate(TaqlCompiler compiler, DataSource dataSource, Options options) {
        this.compiler = compiler;
        this.dataSource = dataSource;
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
     *                                        more rows than the ceiling allows
     *                                        -- a 400, with diagnostics that are
     *                                        safe to return
     * @throws TaqlExecutionException         the query is valid but did not run
     */
    public List<Map<String, Object>> execute(TaqlQuery query) {
        TaqlCompiler.Compiled compiled = compiler.compile(query.source());
        Plan plan = compiled.plan();
        List<Object> values = compiled.bind(query.variables());

        SQLException last = null;
        for (int attempt = 1; attempt <= options.maxAttempts(); attempt++) {
            try {
                return run(plan, values);
            } catch (SQLException e) {
                last = e;
                SqlFailure failure = SqlFailure.classify(e);
                if (!failure.worthRetrying() || attempt == options.maxAttempts()) {
                    TaqlExecutionException giveUp = new TaqlExecutionException(failure, e, attempt);
                    log.error("query failed: {}", giveUp.logDetail());
                    throw giveUp;
                }
                log.warn("attempt {} of {} failed as {} (error {}, state {}); retrying",
                        attempt, options.maxAttempts(), failure, e.getErrorCode(), e.getSQLState());
                backoff(attempt, e);
            }
        }
        throw new TaqlExecutionException(SqlFailure.classify(last), last, options.maxAttempts());
    }

    private List<Map<String, Object>> run(Plan plan, List<Object> values) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(plan.sql())) {
            // Every TAQL query is a SELECT; saying so lets the driver and any
            // proxy in front of it route and optimise accordingly.
            connection.setReadOnly(true);
            statement.setQueryTimeout(options.queryTimeoutSeconds());
            statement.setFetchSize(options.fetchSize());
            // One row past the ceiling: enough to know it was exceeded, and it
            // stops the server sending the rest.
            statement.setMaxRows(options.maxRows() + 1);
            Binder.apply(statement, plan, values);

            log.debug("running plan {} with {} parameters", plan.id(), values.size());
            long startedAt = System.nanoTime();
            try (ResultSet rs = statement.executeQuery()) {
                List<Map<String, Object>> rows = new ArrayList<>();
                while (rs.next()) {
                    if (rows.size() == options.maxRows()) throw tooManyRows();
                    Map<String, Object> row = new LinkedHashMap<>();
                    for (Plan.Column column : plan.columns()) {
                        row.put(column.name(), rs.getObject(column.name()));
                    }
                    rows.add(row);
                }
                log.debug("plan {} returned {} rows in {} ms", plan.id(), rows.size(),
                        (System.nanoTime() - startedAt) / 1_000_000);
                return rows;
            }
        }
    }

    /**
     * The caller could have written the query differently -- narrow the filter,
     * add a 'top', group it -- so this is a 400 with an actionable message, not
     * a server fault.
     */
    private TaqlException tooManyRows() {
        return new TaqlException(new Diagnostic(Diagnostic.Phase.LIMIT, 0, 0,
                "this query returns more than " + options.maxRows()
                        + " rows; add 'top N', group it, or narrow the filter"));
    }

    /**
     * Exponential backoff, jittered.
     *
     * Without the jitter this is synchronised retry: every caller that lost the
     * same deadlock, or that was holding a connection when the server went away,
     * sleeps exactly the same doubling interval and collides again on each
     * wake-up. Spreading them is most of the value of backing off at all.
     *
     * Half the interval is fixed and half is random, so there is still a floor
     * under the wait -- full jitter can pick a delay near zero and retry into a
     * server that has not recovered.
     */
    private void backoff(int attempt, SQLException cause) {
        // Shift capped so a generous maxAttempts cannot overflow into a
        // negative delay, which Thread.sleep rejects.
        long ceiling = options.retryBackoffMillis() << Math.min(attempt - 1, 16);
        long delay = ceiling / 2 + ThreadLocalRandom.current().nextLong(ceiling / 2 + 1);
        try {
            Thread.sleep(delay);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new TaqlExecutionException(SqlFailure.classify(cause), cause, attempt);
        }
    }
}
