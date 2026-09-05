package ch.lenglet.taql.runtime;

import ch.lenglet.taql.TaqlCompiler;
import ch.lenglet.taql.plan.Plan;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Runs a compiled plan. The only place JDBC appears.
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
public final class TaqlExecutor {

    /**
     * @param queryTimeoutSeconds per-statement timeout; 0 disables it, which is
     *                            almost never what a service wants.
     * @param maxAttempts         total attempts for a retryable failure.
     * @param retryBackoffMillis  base delay, doubled per attempt.
     */
    public record Options(int queryTimeoutSeconds, int maxAttempts, long retryBackoffMillis) {

        public static final Options DEFAULTS = new Options(30, 3, 50);

        public Options {
            if (maxAttempts < 1) throw new IllegalArgumentException("maxAttempts must be at least 1");
        }
    }

    private final TaqlCompiler compiler;
    private final DataSource dataSource;
    private final Options options;

    public TaqlExecutor(TaqlCompiler compiler, DataSource dataSource) {
        this(compiler, dataSource, Options.DEFAULTS);
    }

    public TaqlExecutor(TaqlCompiler compiler, DataSource dataSource, Options options) {
        this.compiler = compiler;
        this.dataSource = dataSource;
        this.options = options;
    }

    public record Rows(List<Plan.Column> columns, List<Map<String, Object>> rows) {}

    public Rows run(String source) {
        return run(source, Map.of());
    }

    /**
     * Compiles and runs {@code source}.
     *
     * @throws ch.lenglet.taql.TaqlException  the query is invalid -- a 400, with
     *                                        diagnostics that are safe to return
     * @throws TaqlExecutionException         the query is valid but did not run
     */
    public Rows run(String source, Map<String, Object> variables) {
        TaqlCompiler.Compiled compiled = compiler.compile(source);
        Plan plan = compiled.plan();
        List<Object> values = compiled.bind(variables);

        SQLException last = null;
        for (int attempt = 1; attempt <= options.maxAttempts(); attempt++) {
            try {
                return execute(plan, values);
            } catch (SQLException e) {
                last = e;
                SqlFailure failure = SqlFailure.classify(e);
                if (!failure.worthRetrying() || attempt == options.maxAttempts()) {
                    throw new TaqlExecutionException(failure, e, attempt);
                }
                backoff(attempt, e);
            }
        }
        throw new TaqlExecutionException(SqlFailure.classify(last), last, options.maxAttempts());
    }

    private Rows execute(Plan plan, List<Object> values) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(plan.sql())) {
            statement.setQueryTimeout(options.queryTimeoutSeconds());
            Binder.apply(statement, plan, values);
            try (ResultSet rs = statement.executeQuery()) {
                List<Map<String, Object>> rows = new ArrayList<>();
                while (rs.next()) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    for (Plan.Column column : plan.columns()) {
                        row.put(column.name(), rs.getObject(column.name()));
                    }
                    rows.add(row);
                }
                return new Rows(plan.columns(), rows);
            }
        }
    }

    private void backoff(int attempt, SQLException cause) {
        try {
            Thread.sleep(options.retryBackoffMillis() << (attempt - 1));
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new TaqlExecutionException(SqlFailure.classify(cause), cause, attempt);
        }
    }
}
