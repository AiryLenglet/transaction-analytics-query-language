package ch.lenglet.taql.runtime;

import java.sql.SQLException;
import java.util.Set;

/**
 * What a failed execution means, and what the caller should do about it.
 *
 * Because the compiler has already guaranteed the statement is well-formed and
 * type-consistent, a failure here is almost never "bad query" -- it is the
 * database telling us about the world. Those cases want very different
 * responses (retry / give up / page someone), so they are classified rather
 * than collapsed into one 500.
 *
 * <h2>Why classify on codes rather than exception type or message</h2>
 * Measured against SQL Server 2019 with mssql-jdbc 13.4:
 * <ul>
 *   <li>Nearly everything arrives as a plain {@code SQLServerException}. The
 *       JDBC 4 subclasses ({@code SQLDataException},
 *       {@code SQLSyntaxErrorException}, ...) are not used for server errors,
 *       so {@code instanceof} tells you almost nothing.</li>
 *   <li>Messages are localised by the server's language setting -- the same
 *       divide-by-zero came back in French here. Never match on message text,
 *       and never hand it to an API caller.</li>
 *   <li>{@link SQLException#getErrorCode()} carries the SQL Server error number
 *       for server-side errors, and 0 for driver-side ones (timeout,
 *       connection loss). Those need {@link SQLException#getSQLState()}, whose
 *       first two characters <em>are</em> standard: 08 = connection failure,
 *       40 = transaction rollback, HY008 = cancelled.</li>
 * </ul>
 */
public enum SqlFailure {

    /**
     * Deadlock victim, snapshot conflict, dropped connection. A retry may well
     * succeed. The message stays generic across all of those: a caller cannot
     * act on the difference between a lock conflict and a network drop, and the
     * distinction is in the log where an operator can see it.
     */
    RETRYABLE(true, "The query did not complete and may succeed if retried."),

    /** The server is short of memory, tempdb or log space. Retry, but back off first. */
    RESOURCE(true, "The database is temporarily out of resources."),

    /** The query ran too long and was cancelled. Retrying the same query will do the same thing. */
    TIMEOUT(false, "The query took too long and was cancelled."),

    /** Overflow, divide by zero, a value that would not convert. Caused by the request or the data. */
    INVALID_DATA(false, "A value in this query could not be processed by the database."),

    /**
     * The catalog describes a table or column the database does not have. This
     * is a deployment fault, not a caller fault, and no retry will fix it.
     */
    SCHEMA_MISMATCH(false, "This query cannot be served against the current database schema."),

    /** The connection's login may not read what the query asked for. */
    PERMISSION(false, "This query is not permitted."),

    /** Unrecognised. Treat as a server fault and look at the logs. */
    UNKNOWN(false, "The query could not be completed.");

    private final boolean worthRetrying;
    private final String safeMessage;

    SqlFailure(boolean worthRetrying, String safeMessage) {
        this.worthRetrying = worthRetrying;
        this.safeMessage = safeMessage;
    }

    /** True if the same query, run again, might succeed. */
    public boolean worthRetrying() {
        return worthRetrying;
    }

    /**
     * A message safe to return over HTTP: it names no table, column or value.
     * Server messages can quote both schema and data, so they belong in the log,
     * not in the response.
     */
    public String safeMessage() {
        return safeMessage;
    }

    // SQL Server error numbers -- see sys.messages.
    private static final Set<Integer> RETRYABLE_CODES = Set.of(
            1205,   // deadlock victim
            1222,   // lock request timeout
            3960,   // snapshot isolation update conflict
            10054,  // transport-level error on send
            10053,  // transport-level error on receive
            40197, 40501, 40613); // Azure SQL: service busy / unavailable

    private static final Set<Integer> RESOURCE_CODES = Set.of(
            701,    // out of memory
            802,    // out of buffer memory
            1101, 1105, // could not allocate space in filegroup
            9002,   // transaction log full
            8645);  // timed out waiting for a memory grant

    private static final Set<Integer> INVALID_DATA_CODES = Set.of(
            220,    // arithmetic overflow for the data type
            232,    // arithmetic overflow for the type
            241,    // conversion failed when converting from a character string
            242,    // conversion out of range
            245,    // conversion failed converting varchar to int
            8114,   // error converting data type
            8115,   // arithmetic overflow converting
            8134,   // divide by zero
            8152,   // string or binary data would be truncated
            2628);  // string or binary data would be truncated (with detail)

    private static final Set<Integer> SCHEMA_CODES = Set.of(
            207,    // invalid column name
            208,    // invalid object name
            209,    // ambiguous column name
            1087,   // must declare the table variable
            4104);  // multi-part identifier could not be bound

    private static final Set<Integer> PERMISSION_CODES = Set.of(
            229,    // permission denied on object
            230,    // permission denied on column
            262,    // permission denied in database
            297,    // user does not have permission to perform this action
            18456); // login failed

    /** Classifies a failure, walking the exception chain for the first code we recognise. */
    public static SqlFailure classify(SQLException exception) {
        SqlFailure fallback = null;
        for (SQLException e = exception; e != null; e = e.getNextException()) {
            SqlFailure classified = classifyOne(e);
            if (classified != UNKNOWN) return classified;
            if (fallback == null) fallback = classified;
        }
        return fallback == null ? UNKNOWN : fallback;
    }

    private static SqlFailure classifyOne(SQLException e) {
        int code = e.getErrorCode();
        if (code != 0) {
            if (RETRYABLE_CODES.contains(code)) return RETRYABLE;
            if (RESOURCE_CODES.contains(code)) return RESOURCE;
            if (INVALID_DATA_CODES.contains(code)) return INVALID_DATA;
            if (SCHEMA_CODES.contains(code)) return SCHEMA_MISMATCH;
            if (PERMISSION_CODES.contains(code)) return PERMISSION;
        }

        // Driver-side failures report code 0; the SQLState class is what carries
        // the meaning, and its first two characters are standard.
        String state = e.getSQLState();
        if (state == null || state.length() < 2) return UNKNOWN;
        return switch (state.substring(0, 2)) {
            case "08" -> RETRYABLE;        // connection exception
            case "40" -> RETRYABLE;        // transaction rollback (deadlock arrives as 40001)
            case "53" -> RESOURCE;         // insufficient resources
            case "22" -> INVALID_DATA;     // data exception
            case "42" -> SCHEMA_MISMATCH;  // syntax error or access rule violation
            case "28" -> PERMISSION;       // invalid authorization
            case "HY" -> "HY008".equals(state) ? TIMEOUT : UNKNOWN;
            default -> UNKNOWN;
        };
    }
}
