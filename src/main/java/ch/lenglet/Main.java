package ch.lenglet;

import ch.lenglet.taql.TaqlCompiler;
import ch.lenglet.taql.TaqlException;
import ch.lenglet.taql.catalog.DemoCatalog;
import ch.lenglet.taql.plan.Plan;
import ch.lenglet.taql.runtime.TaqlExecutor;
import com.zaxxer.hikari.HikariDataSource;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Compiles every query in example.taql, prints the generated T-SQL and its
 * bindings, and -- if a SQL Server is reachable -- runs them.
 *
 * Run with the database:      ./runMsSqlServer.sh  then  mvn compile exec:java
 * Run without the database:   the SQL and bindings still print.
 */
public final class Main {

    private static final Map<String, Object> VARIABLES = new LinkedHashMap<>(Map.of(
            "clients", List.of("1", "3"),
            "from", "2010-01-01",
            "to", "2019-12-31",
            "limit", 10));

    public static void main(String[] args) throws Exception {
        TaqlCompiler compiler = new TaqlCompiler(DemoCatalog.create());
        List<String> queries = loadExamples();

        HikariDataSource dataSource = tryConnect();
        TaqlExecutor executor = dataSource == null ? null : new TaqlExecutor(compiler, dataSource);
        if (dataSource != null) initialiseSchema(dataSource);

        for (int i = 0; i < queries.size(); i++) {
            System.out.println("=".repeat(78));
            System.out.println("QUERY " + (i + 1));
            System.out.println("=".repeat(78));
            System.out.println(queries.get(i).strip());
            System.out.println();

            try {
                TaqlCompiler.Compiled compiled = compiler.compile(queries.get(i));
                printPlan(compiled);
                if (executor != null) printRows(executor.run(queries.get(i), VARIABLES));
            } catch (TaqlException e) {
                System.out.println("-- compile error --");
                e.diagnostics().forEach(d -> System.out.println("  " + d));
            } catch (SQLException e) {
                System.out.println("-- execution failed: " + e.getMessage());
            }
            System.out.println();
        }

        demonstratePlanCache(compiler, queries);
        demonstrateInjectionAttempt(compiler);

        if (dataSource != null) dataSource.close();
    }

    // ------------------------------------------------------------------

    private static void printPlan(TaqlCompiler.Compiled compiled) {
        Plan plan = compiled.plan();
        System.out.println("-- generated T-SQL --");
        System.out.println(plan.sql().strip());
        System.out.println();

        if (!plan.variables().isEmpty()) {
            System.out.println("-- variables this plan requires --");
            plan.variables().forEach((name, type) -> System.out.println("  $" + name + " : " + type));
            System.out.println();
        }

        System.out.println("-- bound parameters --");
        List<Object> values;
        try {
            values = compiled.bind(VARIABLES);
        } catch (TaqlException e) {
            System.out.println("  " + e.getMessage());
            return;
        }
        for (int i = 0; i < plan.parameters().size(); i++) {
            Plan.ParamSlot slot = plan.parameters().get(i);
            String origin = switch (slot) {
                case Plan.Auto a -> "literal #" + a.index();
                case Plan.Variable v -> "$" + v.name();
                case Plan.VariableList v -> "$" + v.name() + " (json list)";
                case Plan.Constant ignored -> "compiler default";
            };
            System.out.printf("  ?%-3d %-22s %-12s %s%n",
                    i + 1, origin, slot.sqlType(), render(values.get(i)));
        }
        System.out.println();
    }

    private static void printRows(TaqlExecutor.Rows result) {
        System.out.println("-- result (" + result.rows().size() + " rows) --");
        List<String> names = result.columns().stream().map(Plan.Column::name).toList();
        System.out.println("  " + String.join(" | ", names));
        for (Map<String, Object> row : result.rows()) {
            System.out.println("  " + names.stream()
                    .map(n -> String.valueOf(row.get(n)))
                    .reduce((a, b) -> a + " | " + b).orElse(""));
        }
        System.out.println();
    }

    /** Two queries differing only in their constants must share one plan. */
    private static void demonstratePlanCache(TaqlCompiler compiler, List<String> queries) {
        System.out.println("=".repeat(78));
        System.out.println("PLAN CACHE");
        System.out.println("=".repeat(78));

        Plan second = compiler.compile(queries.get(1)).plan();
        Plan third = compiler.compile(queries.get(2)).plan();
        System.out.println("  query 2 and query 3 differ only in their constants");
        System.out.println("  same shape key : " + second.shapeKey().equals(third.shapeKey()));
        System.out.println("  same SQL text  : " + second.sql().equals(third.sql()));
        System.out.println("  same Plan object (L2 hit) : " + (second == third));
        System.out.println();
        System.out.printf("  L1 (exact text) : %d entries, %d hits, %d misses%n",
                compiler.textCache().size(), compiler.textCache().hits(), compiler.textCache().misses());
        System.out.printf("  L2 (query shape): %d entries, %d hits, %d misses%n",
                compiler.shapeCache().size(), compiler.shapeCache().hits(), compiler.shapeCache().misses());
        System.out.println();
    }

    /** What a hostile value actually does to the generated SQL: nothing. */
    private static void demonstrateInjectionAttempt(TaqlCompiler compiler) {
        System.out.println("=".repeat(78));
        System.out.println("INJECTION ATTEMPT");
        System.out.println("=".repeat(78));

        String hostile = """
                list { transactionId }
                over { clientId = '1''; DROP TABLE dbo.Transactions; --' }
                """;
        TaqlCompiler.Compiled compiled = compiler.compile(hostile);
        System.out.println(hostile.strip());
        System.out.println();
        System.out.println("-- generated T-SQL --");
        System.out.println(compiled.plan().sql().strip());
        System.out.println();
        System.out.println("-- bound parameters --");
        List<Object> values = compiled.bind();
        for (int i = 0; i < values.size(); i++) {
            System.out.println("  ?" + (i + 1) + " = " + render(values.get(i)));
        }
        System.out.println();
        System.out.println("  The payload is a value, never text. It also never reaches the SQL");
        System.out.println("  string: the AST holds a slot index, not the characters.");
        System.out.println();

        System.out.println("-- and an identifier that is not in the catalog --");
        try {
            compiler.compile("list { transactionId } over { Password = 'x' }");
        } catch (TaqlException e) {
            e.diagnostics().forEach(d -> System.out.println("  " + d));
        }
        System.out.println();
    }

    private static String render(Object value) {
        if (value == null) return "NULL";
        if (value instanceof String s) return "'" + s + "'";
        return value.toString();
    }

    // ------------------------------------------------------------------

    private static List<String> loadExamples() throws IOException {
        try (InputStream in = Main.class.getResourceAsStream("/example.taql")) {
            String all = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            return Arrays.stream(all.split("(?m)^-{5,}\\s*$"))
                    .map(String::strip)
                    .filter(s -> !s.isEmpty())
                    .toList();
        }
    }

    private static HikariDataSource tryConnect() {
        HikariDataSource dataSource = new HikariDataSource();
        dataSource.setUsername("sa");
        dataSource.setPassword("Password22");
        // sendStringParametersAsUnicode=false matters: the columns are VARCHAR, and
        // binding NVARCHAR would force a per-row conversion and lose the index seek.
        dataSource.setJdbcUrl("jdbc:sqlserver://localhost:1433;encrypt=false"
                + ";sendStringParametersAsUnicode=false"
                + ";disableStatementPooling=false;statementPoolingCacheSize=64");
        dataSource.setAutoCommit(true);
        dataSource.setTransactionIsolation("TRANSACTION_READ_COMMITTED");
        dataSource.setMaximumPoolSize(2);
        dataSource.setPoolName("taql-pool");
        dataSource.setConnectionTimeout(3000);
        dataSource.setInitializationFailTimeout(-1);

        try (Connection ignored = dataSource.getConnection()) {
            System.out.println("connected to SQL Server; queries will be executed\n");
            return dataSource;
        } catch (SQLException e) {
            System.out.println("no SQL Server on localhost:1433 -- printing SQL only");
            System.out.println("(start one with ./runMsSqlServer.sh)\n");
            dataSource.close();
            return null;
        }
    }

    private static void initialiseSchema(HikariDataSource dataSource) throws IOException, SQLException {
        String script;
        try (InputStream in = Main.class.getResourceAsStream("/init.sql")) {
            script = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            for (String batch : script.split("(?m)^\\s*GO\\s*$")) {
                if (!batch.isBlank()) statement.execute(batch);
            }
        }
    }
}
