package ch.lenglet.taql.runtime;

import ch.lenglet.taql.plan.Plan;

import java.util.List;
import java.util.Map;

/**
 * Runs a compiled plan against a store. The counterpart to
 * {@link ch.lenglet.taql.Backend}: that one turns a query into a statement,
 * this one runs it.
 *
 * The two are separate because they need different things. A backend is pure
 * and shareable -- it holds a dialect, nothing more. A runner holds resources:
 * a connection pool, a driver, a timeout budget. Bundling them would make a
 * compiler depend on a live connection, which is exactly what a validation
 * endpoint must not need.
 *
 * <h2>What an implementation owes the caller</h2>
 * One attempt, no retrying -- {@link TaqlTemplate} owns that, because "retry
 * what is worth retrying" is a policy rather than a dialect. Failures must
 * arrive as a {@link TaqlExecutionException} carrying a
 * {@link FailureCategory}, since that is what the retry loop reads; leaking a
 * driver's own exception type would put the dialect back in the caller.
 */
public interface PlanRunner {

    /**
     * Runs {@code plan} once with {@code values} bound in parameter order.
     *
     * @throws TaqlExecutionException          the statement did not complete
     * @throws ch.lenglet.taql.TaqlException   the caller asked for something the
     *                                         runner will not do, such as more
     *                                         rows than its ceiling allows
     */
    List<Map<String, Object>> run(Plan plan, List<Object> values);
}
