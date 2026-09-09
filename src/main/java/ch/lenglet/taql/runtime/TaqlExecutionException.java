package ch.lenglet.taql.runtime;

import java.sql.SQLException;

/**
 * A failure while running a compiled plan, classified and stripped of anything
 * unsafe to return.
 *
 * {@link #getMessage()} is deliberately generic. The server's own message can
 * quote table names, column names and row values, so it stays on
 * {@link #databaseMessage()} and never becomes the default thing a handler
 * echoes back -- nor part of {@link #logDetail()}, because a log is not a safe
 * destination for a client identifier either.
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

    /**
     * The raw server message. <b>It can quote client data</b> -- error 245 is
     * "Conversion failed when converting the varchar value '...'", 8152 reports
     * the truncated value -- so it is neither returned to a caller nor written
     * to a log by this library. It is exposed for a deployment that has decided
     * where such a value may go; that decision is not one a library can make.
     *
     * It is also localised, which is why {@link SqlFailure} classifies on codes:
     * the message was never the diagnostic anyway.
     */
    public String databaseMessage() {
        return getCause().getMessage();
    }

    /**
     * Everything worth writing to a log line, and nothing that could carry a
     * value the query was asked about.
     *
     * The error number and SQL state identify the fault precisely -- that is the
     * whole premise of classifying on codes rather than on message text -- so
     * dropping the message costs no diagnosis and removes the one field that
     * could hold a client identifier.
     */
    public String logDetail() {
        return "failure=" + failure + " errorNumber=" + errorNumber
                + " sqlState=" + sqlState + " attempts=" + attempts;
    }
}
