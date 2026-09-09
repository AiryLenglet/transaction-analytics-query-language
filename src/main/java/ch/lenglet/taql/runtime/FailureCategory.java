package ch.lenglet.taql.runtime;

/**
 * What a failed execution means, and what the caller should do about it.
 *
 * Because the compiler has already guaranteed the statement is well-formed and
 * type-consistent, a failure here is almost never "bad query" -- it is the store
 * telling us about the world. Those cases want very different responses (retry /
 * give up / page someone), so they are classified rather than collapsed into one
 * 500.
 *
 * The categories are deliberately store-neutral: a deadlock, a resource limit
 * and a permission refusal are facts about running a query anywhere, and only
 * the codes that identify them are dialect-specific. Mapping those codes is a
 * {@link PlanRunner}'s job -- see {@code jdbc.SqlFailure} for the SQL Server
 * one.
 */
public enum FailureCategory {

    /**
     * Deadlock victim, snapshot conflict, dropped connection. A retry may well
     * succeed. The message stays generic across all of those: a caller cannot
     * act on the difference between a lock conflict and a network drop, and the
     * distinction is in the log where an operator can see it.
     */
    RETRYABLE(true, "The query did not complete and may succeed if retried."),

    /** The store is short of memory or working space. Retry, but back off first. */
    RESOURCE(true, "The database is temporarily out of resources."),

    /** The query ran too long and was cancelled. Retrying the same query will do the same thing. */
    TIMEOUT(false, "The query took too long and was cancelled."),

    /** Overflow, divide by zero, a value that would not convert. Caused by the request or the data. */
    INVALID_DATA(false, "A value in this query could not be processed by the database."),

    /**
     * The catalog describes something the store does not have. This is a
     * deployment fault, not a caller fault, and no retry will fix it.
     */
    SCHEMA_MISMATCH(false, "This query cannot be served against the current database schema."),

    /** The connection's login may not read what the query asked for. */
    PERMISSION(false, "This query is not permitted."),

    /** Unrecognised. Treat as a server fault and look at the logs. */
    UNKNOWN(false, "The query could not be completed.");

    private final boolean worthRetrying;
    private final String safeMessage;

    FailureCategory(boolean worthRetrying, String safeMessage) {
        this.worthRetrying = worthRetrying;
        this.safeMessage = safeMessage;
    }

    /** True if the same query, run again, might succeed. */
    public boolean worthRetrying() {
        return worthRetrying;
    }

    /**
     * A message safe to return over HTTP: it names no table, column or value.
     * Store messages can quote both schema and data, so they belong in neither
     * the response nor the log.
     */
    public String safeMessage() {
        return safeMessage;
    }
}
