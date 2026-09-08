package ch.lenglet.taql;

import ch.lenglet.taql.catalog.DemoCatalog;
import ch.lenglet.taql.runtime.TaqlExecutor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
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
@DisplayName("executor limits")
class TaqlExecutorTest {

    private final TaqlCompiler compiler = new TaqlCompiler(DemoCatalog.create());

    private TaqlExecutor executorOver(int availableRows, int maxRows) {
        return new TaqlExecutor(compiler, sourceOf(availableRows),
                new TaqlExecutor.Options(30, 3, 50, maxRows, 1_000));
    }

    @Test
    void returnsEverythingUpToTheCeiling() {
        var rows = executorOver(5, 5).run("list { TransactionId } over { Country = 'CH' }").rows();
        assertEquals(5, rows.size());
        assertEquals(Map.of("TransactionId", "row"), rows.getFirst());
    }

    @Test
    void refusesRatherThanSilentlyTruncating() {
        // An analytical answer missing rows nobody mentioned is worse than an
        // error, so the ceiling is a failure and not a quiet cut-off.
        var tooMany = assertThrows(TaqlException.class,
                () -> executorOver(6, 5).run("list { TransactionId } over { Country = 'CH' }"));

        assertTrue(tooMany.getMessage().contains("more than 5 rows"), tooMany.getMessage());
        assertTrue(tooMany.getMessage().contains("top N"), tooMany.getMessage());
        assertEquals(Diagnostic.Phase.LIMIT, tooMany.diagnostics().getFirst().phase());
    }

    @Test
    void theCeilingCannotBeConfiguredAway() {
        assertThrows(IllegalArgumentException.class, () -> new TaqlExecutor.Options(30, 3, 50, 0, 1_000));
        assertThrows(IllegalArgumentException.class, () -> new TaqlExecutor.Options(30, 3, 50, -1, 1_000));
    }

    // ------------------------------------------------------------------
    // A driver that yields `rows` identical rows and records nothing else.
    // ------------------------------------------------------------------

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
