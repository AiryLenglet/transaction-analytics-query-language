package ch.lenglet.taql;

import ch.lenglet.taql.runtime.SqlFailure;
import ch.lenglet.taql.runtime.TaqlExecutionException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.sql.SQLTimeoutException;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The codes and SQL states asserted here were observed against SQL Server 2019
 * with mssql-jdbc 13.4, by provoking each condition for real -- not read off a
 * reference. See SqlFailure's javadoc for why classification uses them rather
 * than exception types or message text.
 */
@DisplayName("SQL failure classification")
class SqlFailureTest {

    private static SQLException serverError(int number, String state) {
        return new SQLException("localised server text", state, number);
    }

    @Test
    void deadlockIsRetryable() {
        // Observed: code=1205 state=40001
        SqlFailure f = SqlFailure.classify(serverError(1205, "40001"));
        assertEquals(SqlFailure.RETRYABLE, f);
        assertTrue(f.worthRetrying());
    }

    @Test
    void connectionLossIsRetryable() {
        // Observed: code=0 state=08S01 -- driver-side, so only the state carries meaning.
        assertEquals(SqlFailure.RETRYABLE, SqlFailure.classify(serverError(0, "08S01")));
    }

    @Test
    void timeoutIsNotRetryable() {
        // Observed: code=0 state=HY008. Re-running takes just as long.
        SqlFailure f = SqlFailure.classify(new SQLTimeoutException("timed out", "HY008", 0));
        assertEquals(SqlFailure.TIMEOUT, f);
        assertFalse(f.worthRetrying());
    }

    @Test
    void dataErrorsAreTheCallersProblem() {
        assertAll(
                () -> assertEquals(SqlFailure.INVALID_DATA, SqlFailure.classify(serverError(8134, "S0001"))),
                () -> assertEquals(SqlFailure.INVALID_DATA, SqlFailure.classify(serverError(8115, "S0006"))),
                () -> assertEquals(SqlFailure.INVALID_DATA, SqlFailure.classify(serverError(241, "S0001"))),
                () -> assertFalse(SqlFailure.INVALID_DATA.worthRetrying()));
    }

    @Test
    void aMissingTableOrColumnMeansTheCatalogIsWrong() {
        // Not a caller fault and never fixed by retrying: the catalog claims
        // something the database does not have.
        assertAll(
                () -> assertEquals(SqlFailure.SCHEMA_MISMATCH, SqlFailure.classify(serverError(208, "S0002"))),
                () -> assertEquals(SqlFailure.SCHEMA_MISMATCH, SqlFailure.classify(serverError(207, "S0001"))),
                () -> assertFalse(SqlFailure.SCHEMA_MISMATCH.worthRetrying()));
    }

    @Test
    void permissionFailuresAreDistinctFromSchemaFailures() {
        assertEquals(SqlFailure.PERMISSION, SqlFailure.classify(serverError(229, "S0001")));
    }

    @Test
    void resourceExhaustionIsWorthRetryingAfterABackoff() {
        assertAll(
                () -> assertEquals(SqlFailure.RESOURCE, SqlFailure.classify(serverError(9002, "S0001"))),
                () -> assertEquals(SqlFailure.RESOURCE, SqlFailure.classify(serverError(701, "S0001"))),
                () -> assertTrue(SqlFailure.RESOURCE.worthRetrying()));
    }

    @Test
    void anUnrecognisedErrorIsNotRetried() {
        SqlFailure f = SqlFailure.classify(serverError(99999, "S0001"));
        assertEquals(SqlFailure.UNKNOWN, f);
        assertFalse(f.worthRetrying(), "retrying something we do not understand is not safe");
    }

    @Test
    void theChainIsWalkedForTheFirstRecognisedCode() {
        SQLException outer = serverError(0, null);
        outer.setNextException(serverError(1205, "40001"));
        assertEquals(SqlFailure.RETRYABLE, SqlFailure.classify(outer));
    }

    @Test
    void theSafeMessageLeaksNothingAndTheDetailIsKeptForLogs() {
        SQLException cause = new SQLException(
                "Invalid column name 'Salary'. Row value was 'alice@example.com'.", "S0001", 207);
        TaqlExecutionException e = new TaqlExecutionException(SqlFailure.classify(cause), cause, 1);

        assertAll(
                () -> assertFalse(e.getMessage().contains("Salary"), e.getMessage()),
                () -> assertFalse(e.getMessage().contains("alice@example.com")),
                () -> assertEquals(SqlFailure.SCHEMA_MISMATCH.safeMessage(), e.getMessage()),
                // ...but nothing is lost: the detail is still there for the log.
                () -> assertTrue(e.databaseMessage().contains("Salary")),
                () -> assertEquals(207, e.errorNumber()),
                () -> assertEquals("S0001", e.sqlState()),
                () -> assertTrue(e.logDetail().contains("errorNumber=207")));
    }
}
