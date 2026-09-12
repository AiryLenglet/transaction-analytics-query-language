package ch.lenglet.taql.sqlserver;

import ch.lenglet.taql.Diagnostic;
import ch.lenglet.taql.Plan;
import ch.lenglet.taql.TaqlType;
import ch.lenglet.taql.TaqlException;
import ch.lenglet.taql.execution.TaqlExecutionException;
import ch.lenglet.taql.spi.PlanRunner;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.sql.Types;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;

/**
 * Runs a plan over JDBC. The only place in the library that opens a connection.
 *
 * Everything dialect-specific about execution lives here: the {@link DataSource},
 * the statement setup, and the {@link SQLException} taxonomy that
 * {@link SqlFailure} maps onto neutral {@link FailureCategory} values. Retrying
 * is not here -- that is policy, and it belongs to
 * {@link ch.lenglet.taql.TaqlTemplate}, which is why this runs once and
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
            bind(statement, plan, values);

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

    private static void bind(PreparedStatement statement, Plan plan, List<Object> values) throws SQLException {
        for (int i = 0; i < values.size(); i++) {
            Plan.ParamSlot slot = plan.parameters().get(i);
            Object value = values.get(i);
            int index = i + 1;
            if (value == null) {
                // The physical type, not the DSL type, is what the server expects.
                statement.setNull(index, sqlType(slot).jdbcType());
                continue;
            }
            switch (value) {
                // Sending a varchar column an NVARCHAR parameter makes SQL Server
                // convert the column rather than seek on it, so the decision is
                // made per parameter from its own type rather than by a
                // connection-wide sendStringParametersAsUnicode switch.
                case String s -> {
                    if (sqlType(slot).unicode()) statement.setNString(index, s);
                    else statement.setString(index, s);
                }
                case Long l -> statement.setLong(index, l);
                case Integer n -> statement.setInt(index, n);
                case BigDecimal d -> statement.setBigDecimal(index, d);
                case Boolean b -> statement.setBoolean(index, b);
                case LocalDate d -> statement.setObject(index, d, Types.DATE);
                case LocalDateTime d -> statement.setObject(index, d, Types.TIMESTAMP);
                default -> statement.setObject(index, value);
            }
        }
    }

    /**
     * This binder speaks JDBC, so it needs a T-SQL type. A plan built by another
     * backend's generator would carry that backend's types and has no business
     * reaching here -- an internal fault, not something a caller can provoke.
     */
    private static SqlType sqlType(Plan.ParamSlot slot) {
        if (slot.physicalType() instanceof SqlType sql) return sql;
        throw new IllegalStateException("the JDBC binder needs a SQL type, got "
                + slot.physicalType().describe());
    }
}
