package ch.lenglet.taql;

import ch.lenglet.taql.plan.Restrictions;

/**
 * A rule every query must satisfy before it runs.
 *
 * Called after binding and before execution, which is the only point where both
 * halves of the question exist at once: the query's shape, and the values it is
 * about to run with. Literals are lifted out of the tree at parse time and a
 * cached plan holds slot indices, so nothing earlier knows which client was
 * asked about.
 *
 * <h2>Refusing</h2>
 * Throw {@link TaqlException} with {@link Diagnostic.Phase#POLICY}. It lands as a
 * 400 carrying a diagnostic, like every other rejection, and the message is
 * returned to the caller -- so say what rule was broken, not what the caller
 * would have needed to satisfy it. "You may not read client CH-9" tells someone
 * that CH-9 exists.
 *
 * <h2>Read the empty case as "no"</h2>
 * {@link Restrictions#on} returns empty when a field is not pinned down in any
 * computable way -- no filter, a {@code like}, a range, or a mention that only
 * appears under an {@code or}. A policy that treats empty as "nothing to check"
 * permits exactly the queries that read everything.
 *
 * <h2>Where the caller comes from</h2>
 * Not from here. This gets the query and its restrictions; an implementation
 * gets the principal from wherever it already lives. That keeps the library out
 * of the business of modelling identity -- and puts the burden on the
 * implementation to refuse when no principal is established, rather than sail
 * past the check.
 */
@FunctionalInterface
public interface QueryPolicy {

    /** Permits every query. The default, so adding a policy is a deliberate act. */
    QueryPolicy PERMIT_ALL = (query, restrictions) -> { };

    /**
     * @throws TaqlException if the query may not run as asked
     */
    void check(TaqlQuery query, Restrictions restrictions);
}
