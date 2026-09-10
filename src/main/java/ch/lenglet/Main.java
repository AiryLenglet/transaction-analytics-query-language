package ch.lenglet;

import ch.lenglet.taql.TaqlCompiler;
import ch.lenglet.taql.TaqlException;
import ch.lenglet.taql.TaqlQuery;
import ch.lenglet.taql.catalog.DemoCatalog;
import ch.lenglet.taql.runtime.TaqlExecutionException;
import ch.lenglet.taql.runtime.TaqlTemplate;
import ch.lenglet.taql.runtime.jdbc.JdbcPlanRunner;
import com.zaxxer.hikari.HikariDataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * Runs every query in example.taql through a {@link TaqlTemplate}.
 *
 * The examples state their own constants, so there is nothing for this driver
 * to supply: a query arrives as text and runs. Values a caller would vary per
 * request are what {@code $variables} are for, and the compiler advertises them
 * on {@code Plan.variables()} -- but demonstrating that needs a caller, and this
 * is a file of queries.
 *
 * The template is the whole API, so this driver never touches the compiler: the
 * generated SQL, the plan cache hits and the parameter counts all arrive as
 * debug logs from the library itself, which is what an operator would see. Turn
 * them off and the same code prints only results.
 *
 * Run with the database:      ./runMsSqlServer.sh  then  mvn compile exec:java
 * Run without it:             every query still compiles -- and the compiled SQL
 *                             is still logged -- then fails with a classified
 *                             execution error, which is its own demonstration.
 */
public final class Main {

    private static final Logger log = LoggerFactory.getLogger(Main.class);

    public static void main(String[] args) throws Exception {
        TaqlTemplate template;
        try (HikariDataSource dataSource = dataSource()) {
            initialiseSchema(dataSource);
            template = new TaqlTemplate(new TaqlCompiler(DemoCatalog.create()),
                    new JdbcPlanRunner(dataSource));

            List<String> queries = loadExamples();
            for (int i = 0; i < queries.size(); i++) {
                log.info("query {} of {}\n{}", i + 1, queries.size(), queries.get(i).strip());
                run(template, queries.get(i));
            }

            log.info("the same query again -- the compiler logs no new plan, because both caches hit");
            run(template, queries.getFirst());

            log.info("a hostile value is a value: the payload never reaches the SQL above");
            run(template, """
                    list { transactionId }
                    over { clientId = '1''; DROP TABLE dbo.Transactions; --' }
                    """);

            log.info("and an identifier the catalog does not know is a compile error");
            run(template, "list { transactionId } over { Password = 'x' }");
        }
    }

    private static void run(TaqlTemplate template, String source) {
        try {
            report(template.execute(TaqlQuery.of(source)));
        } catch (TaqlException e) {
            // Everything the caller could have written differently, with a position.
            e.diagnostics().forEach(d -> log.info("  rejected: {}", d));
        } catch (TaqlExecutionException e) {
            // What a service would return, next to what it would log.
            log.info("  to caller : {} (retryable: {})", e.getMessage(), e.failure().worthRetrying());
            log.info("  to log    : {}", e.logDetail());
        }
    }

    /**
     * Rows are keyed by output name, so they print themselves. An empty result
     * has no keys to read, which is the honest cost of the result carrying no
     * schema -- the shape belongs to the query, and the compiler knows it.
     */
    private static void report(List<Map<String, Object>> rows) {
        if (rows.isEmpty()) {
            log.info("  no rows");
            return;
        }
        log.info("  {}", String.join(" | ", rows.getFirst().keySet()));
        for (Map<String, Object> row : rows) {
            log.info("  {}", row.values().stream().map(String::valueOf)
                    .reduce((a, b) -> a + " | " + b).orElse(""));
        }
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

    private static HikariDataSource dataSource() {
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
        // Do not probe the server at startup: without one, the demo still
        // compiles every query and shows how the failure is classified.
        dataSource.setInitializationFailTimeout(-1);
        return dataSource;
    }

    private static void initialiseSchema(HikariDataSource dataSource) throws IOException {
        String script;
        try (InputStream in = Main.class.getResourceAsStream("/init.sql")) {
            script = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            for (String batch : script.split("(?m)^\\s*GO\\s*$")) {
                if (!batch.isBlank()) statement.execute(batch);
            }
            log.info("connected to SQL Server; queries will be executed");
        } catch (SQLException e) {
            log.warn("no SQL Server on localhost:1433 -- queries will compile but not run"
                    + " (start one with ./runMsSqlServer.sh)");
        }
    }
}
