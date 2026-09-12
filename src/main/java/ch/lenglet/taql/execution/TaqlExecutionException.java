package ch.lenglet.taql.execution;

/**
 * A failure while running a compiled plan, classified and stripped of anything
 * unsafe to return.
 *
 * {@link #getMessage()} is deliberately generic. The store's own message can
 * quote table names, column names and row values, so it stays on
 * {@link #databaseMessage()} and never becomes the default thing a handler
 * echoes back -- nor part of {@link #logDetail()}, because a log is not a safe
 * destination for a client identifier either.
 *
 * Nothing here names a dialect: {@link #code()} is whatever string the runner
 * uses to identify a fault, which for JDBC is the error number and SQL state
 * and elsewhere will be something else entirely.
 */
public class TaqlExecutionException extends RuntimeException {

    private final FailureCategory failure;
    private final String code;
    private final int attempts;

    public TaqlExecutionException(FailureCategory failure, String code, Throwable cause, int attempts) {
        super(failure.safeMessage(), cause);
        this.failure = failure;
        this.code = code;
        this.attempts = attempts;
    }

    public FailureCategory failure() {
        return failure;
    }

    /** The runner's own identifier for this fault. Never a value from the data. */
    public String code() {
        return code;
    }

    /** How many times the query was attempted before giving up. */
    public int attempts() {
        return attempts;
    }

    /**
     * The raw message from the store. <b>It can quote client data</b> -- SQL
     * Server's error 245 is "Conversion failed when converting the varchar value
     * '...'" -- so it is neither returned to a caller nor written to a log by
     * this library. It is exposed for a deployment that has decided where such a
     * value may go; that decision is not one a library can make.
     *
     * It is also localised, which is why failures are classified on codes: the
     * message was never the diagnostic anyway.
     */
    public String databaseMessage() {
        return getCause() == null ? null : getCause().getMessage();
    }

    /**
     * Everything worth writing to a log line, and nothing that could carry a
     * value the query was asked about.
     *
     * The category and the code identify the fault precisely -- that is the
     * whole premise of classifying on codes rather than on message text -- so
     * omitting the message costs no diagnosis and removes the one field that
     * could hold a client identifier.
     */
    public String logDetail() {
        return "failure=" + failure + " code=" + code + " attempts=" + attempts;
    }
}
