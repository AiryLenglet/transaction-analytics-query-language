package ch.lenglet.taql.runtime;

import java.sql.SQLException;

/**
 * A failure while running a compiled plan, classified and stripped of anything
 * unsafe to return.
 *
 * {@link #getMessage()} is deliberately generic. The server's own message can
 * quote table names, column names and row values, so it stays on
 * {@link #databaseMessage()} for logging and never becomes the default thing a
 * handler echoes back.
 */
public class TaqlExecutionException extends RuntimeException {

    private final SqlFailure failure;
    private final int errorNumber;
    private final String sqlState;
    private final int attempts;

    public TaqlExecutionException(SqlFailure failure, SQLException cause, int attempts) {
        super(failure.safeMessage(), cause);
        this.failure = failure;
        this.errorNumber = cause.getErrorCode();
        this.sqlState = cause.getSQLState();
        this.attempts = attempts;
    }

    public SqlFailure failure() {
        return failure;
    }

    /** SQL Server's error number, or 0 for a driver-side failure. */
    public int errorNumber() {
        return errorNumber;
    }

    public String sqlState() {
        return sqlState;
    }

    /** How many times the query was attempted before giving up. */
    public int attempts() {
        return attempts;
    }

    /** The raw server message. Log it; do not return it to a caller. */
    public String databaseMessage() {
        return getCause().getMessage();
    }

    /** Everything worth writing to a log line, with no result data in it. */
    public String logDetail() {
        return "failure=" + failure + " errorNumber=" + errorNumber + " sqlState=" + sqlState
                + " attempts=" + attempts + " message=" + databaseMessage();
    }
}
