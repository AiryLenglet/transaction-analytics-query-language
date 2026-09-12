package ch.lenglet.taql;

import ch.lenglet.taql.catalog.DemoCatalog;
import ch.lenglet.taql.runtime.TaqlTemplate;
import ch.lenglet.taql.runtime.jdbc.JdbcPlanRunner;
import ch.lenglet.taql.runtime.jdbc.JdbcPlanRunner;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The row ceiling, exercised against a stub driver.
 *
 * There is no database in the test suite, and the behaviour under test is the
 * executor's, not the server's: how many rows it is willing to pull into the
 * heap before it refuses.
 */
@DisplayName("template limits")
class TaqlTemplateTest {

    private final TaqlCompiler compiler = new TaqlCompiler(DemoCatalog.create());

    private TaqlTemplate templateOver(int availableRows, int maxRows) {
        return new TaqlTemplate(compiler,
                new JdbcPlanRunner(sourceOf(availableRows), new JdbcPlanRunner.Options(30, maxRows, 1_000)));
    }

    private static final TaqlQuery QUERY =
            TaqlQuery.of("list { TransactionId } over { Country = 'CH' }");

    @Test
    void returnsEverythingUpToTheCeiling() {
        var rows = templateOver(5, 5).execute(QUERY);
        assertEquals(5, rows.size());
        assertEquals(Map.of("TransactionId", "row"), rows.getFirst());
    }

    @Test
    void refusesRatherThanSilentlyTruncating() {
        // An analytical answer missing rows nobody mentioned is worse than an
        // error, so the ceiling is a failure and not a quiet cut-off.
        var tooMany = assertThrows(TaqlException.class,
                () -> templateOver(6, 5).execute(QUERY));

        assertTrue(tooMany.getMessage().contains("more than 5 rows"), tooMany.getMessage());
        assertTrue(tooMany.getMessage().contains("top N"), tooMany.getMessage());
        assertEquals(Diagnostic.Phase.LIMIT, tooMany.diagnostics().getFirst().phase());
    }

    @Test
    void aRefusedQueryNeverReachesTheStore() {
        // Refusing after the query has run is not refusing. The stub counts
        // connections, so this proves the order rather than assuming it.
        int[] connections = {0};
        var counted = countingSource(3, connections);
        QueryPolicy refuseEverything =
                parsed -> List.of(new Diagnostic(Diagnostic.Phase.POLICY, 1, 1, "no"));
        var template = new TaqlTemplate(compiler, new JdbcPlanRunner(counted),
                refuseEverything, TaqlTemplate.Options.DEFAULTS);

        assertThrows(TaqlException.class, () -> template.execute(QUERY));
        assertEquals(0, connections[0], "the policy must refuse before a connection is opened");

        // ...and a permitting policy still runs the query.
        var permitted = new TaqlTemplate(compiler, new JdbcPlanRunner(counted),
                QueryPolicy.PERMIT_ALL, TaqlTemplate.Options.DEFAULTS);
        assertEquals(3, permitted.execute(QUERY).size());
        assertEquals(1, connections[0]);
    }

    @Test
    void theCeilingCannotBeConfiguredAway() {
        assertThrows(IllegalArgumentException.class, () -> new JdbcPlanRunner.Options(30, 0, 1_000));
        assertThrows(IllegalArgumentException.class, () -> new JdbcPlanRunner.Options(30, -1, 1_000));
    }

    // ------------------------------------------------------------------
    // A driver that yields `rows` identical rows and records nothing else.
    // ------------------------------------------------------------------

    private static DataSource countingSource(int rows, int[] connections) {
        DataSource delegate = sourceOf(rows);
        return proxy(DataSource.class, (method, args) -> {
            if (!method.getName().equals("getConnection")) return null;
            connections[0]++;
            try {
                return delegate.getConnection();
            } catch (java.sql.SQLException e) {
                throw new IllegalStateException(e);
            }
        });
    }

    private static DataSource sourceOf(int rows) {
        int[] served = {0};
        ResultSet rs = proxy(ResultSet.class, (method, args) -> switch (method.getName()) {
            case "next" -> served[0]++ < rows;
            case "getObject" -> "row";
            default -> null;
        });
        PreparedStatement statement = proxy(PreparedStatement.class,
                (method, args) -> method.getName().equals("executeQuery") ? rs : null);
        Connection connection = proxy(Connection.class,
                (method, args) -> method.getName().equals("prepareStatement") ? statement : null);
        return proxy(DataSource.class,
                (method, args) -> method.getName().equals("getConnection") ? connection : null);
    }

    private interface Stub {
        Object invoke(Method method, Object[] args);
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type, Stub stub) {
        InvocationHandler handler = (self, method, args) -> {
            Object answer = stub.invoke(method, args);
            if (answer != null) return answer;
            // Anything the test does not care about: the harmless default for
            // the declared return type, so void setters and close() just work.
            Class<?> returns = method.getReturnType();
            if (returns == boolean.class) return false;
            if (returns == int.class) return 0;
            if (returns == long.class) return 0L;
            return null;
        };
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, handler);
    }
}
