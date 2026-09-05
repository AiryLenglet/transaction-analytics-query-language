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

/** Runs a compiled plan. The only place JDBC appears. */
public final class TaqlExecutor {

    private final TaqlCompiler compiler;
    private final DataSource dataSource;

    public TaqlExecutor(TaqlCompiler compiler, DataSource dataSource) {
        this.compiler = compiler;
        this.dataSource = dataSource;
    }

    public record Rows(List<Plan.Column> columns, List<Map<String, Object>> rows) {}

    public Rows run(String source) throws SQLException {
        return run(source, Map.of());
    }

    public Rows run(String source, Map<String, Object> variables) throws SQLException {
        TaqlCompiler.Compiled compiled = compiler.compile(source);
        Plan plan = compiled.plan();
        List<Object> values = compiled.bind(variables);

        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(plan.sql())) {
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
}
