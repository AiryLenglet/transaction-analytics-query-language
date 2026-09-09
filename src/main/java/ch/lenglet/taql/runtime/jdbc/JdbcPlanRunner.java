package ch.lenglet.taql.runtime.jdbc;

import ch.lenglet.taql.Diagnostic;
import ch.lenglet.taql.TaqlException;
import ch.lenglet.taql.plan.Plan;
import ch.lenglet.taql.runtime.FailureCategory;
import ch.lenglet.taql.runtime.PlanRunner;
import ch.lenglet.taql.runtime.TaqlExecutionException;
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

/**
 * Runs a plan over JDBC. The only place in the library that opens a connection.
 *
 * Everything dialect-specific about execution lives here: the {@link DataSource},
 * the statement setup, and the {@link SQLException} taxonomy that
 * {@link SqlFailure} maps onto neutral {@link FailureCategory} values. Retrying
 * is not here -- that is policy, and it belongs to
 * {@link ch.lenglet.taql.runtime.TaqlTemplate}, which is why this runs once and
 * throws.
 *
 * <h2>Bounds every statement</h2>
 * A query with no timeout can hold a pooled connection indefinitely and
 * eventually starve every other endpoint sharing the pool. A query with no row
 * ceiling can buffer a whole table into the heap and take the process down
 * rather than just the request. Both are set before the statement runs, and the
 * ceiling is a failure rather than a silent truncation: an analytical answer
 * quietly missing rows is worse than an error saying so.
 */
public final class JdbcPlanRunner implements PlanRunner {

    private static final Logger log = LoggerFactory.getLogger(JdbcPlanRunner.class);

    /**
     * @param queryTimeoutSeconds per-statement timeout; 0 disables it, which is
     *                            almost never what a service wants.
     * @param maxRows             hard ceiling on rows pulled into the heap. Not
     *                            a default that can be set to "unlimited" --
     *                            that is the setting that takes the process down.
     * @param fetchSize           so the driver streams instead of buffering the
     *                            whole result before the first row is read.
     */
    public record Options(int queryTimeoutSeconds, int maxRows, int fetchSize) {

        public static final Options DEFAULTS = new Options(30, 10_000, 1_000);

        public Options {
            if (maxRows < 1) throw new IllegalArgumentException("maxRows must be at least 1");
            if (fetchSize < 1) throw new IllegalArgumentException("fetchSize must be at least 1");
        }
    }

    private final DataSource dataSource;
    private final Options options;

    public JdbcPlanRunner(DataSource dataSource) {
        this(dataSource, Options.DEFAULTS);
    }

    public JdbcPlanRunner(DataSource dataSource, Options options) {
        this.dataSource = dataSource;
        this.options = options;
    }

    @Override
    public List<Map<String, Object>> run(Plan plan, List<Object> values) {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(plan.statement())) {
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
        } catch (SQLException e) {
            // Classified here, because only this runner knows what these codes
            // mean; the template above reads the category, not the exception.
            throw new TaqlExecutionException(SqlFailure.classify(e), codeOf(e), e, 1);
        }
    }

    /** Identifies the fault without quoting anything from the data. */
    private static String codeOf(SQLException e) {
        return e.getErrorCode() + "/" + e.getSQLState();
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
}
