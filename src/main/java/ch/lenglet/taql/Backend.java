package ch.lenglet.taql;

import ch.lenglet.taql.plan.Plan;
import ch.lenglet.taql.sem.Tam;

import java.util.Map;

/**
 * Everything about a query that depends on where it will run.
 *
 * The pipeline in front of this is store-agnostic: text parses to an
 * {@link ch.lenglet.taql.ast.Ast}, resolves against a
 * {@link ch.lenglet.taql.catalog.Catalog} into {@link Tam}, and none of it
 * names a dialect. This interface is the one place it becomes T-SQL -- or, in
 * time, something else.
 *
 * <h2>Why the resolver needs one too</h2>
 * Typing a value is not purely logical. A literal compared against a column
 * takes that column's physical type, so it binds as the thing the column
 * actually is; a literal with no column to learn from needs a default, and what
 * "a reasonable default for a string" is belongs to the store, not the DSL.
 * Passing the backend into resolution is what stopped {@code Resolver} from
 * naming {@code varchar(400)} directly.
 *
 * <h2>What is deliberately not here</h2>
 * Running a plan. Execution needs a connection, an error taxonomy and a driver,
 * and the shape of that seam is not yet knowable from one implementation --
 * inventing it now would produce an interface shaped exactly like JDBC. The
 * runtime package is where that work goes when there is a second store to
 * measure it against.
 *
 * Nor a capability model. A backend that cannot express every TAQL construct --
 * window functions have no obvious analogue outside SQL -- needs to say so, and
 * the resolver needs to turn that into a positioned diagnostic rather than
 * letting the generator throw. That also wants a real second implementation
 * before it is designed.
 */
public interface Backend {

    /** Identifies the target in diagnostics and logs. */
    String name();

    /**
     * The physical type to bind a value as when no column says otherwise.
     *
     * @param type the DSL type the value resolved to; never a list -- a list
     *             variable is bound by its element type.
     */
    PhysicalType defaultTypeFor(TaqlType type);

    /**
     * Lowers a resolved query into a statement plus the recipe for filling its
     * placeholders. Implementations must be safe to share between threads;
     * whatever state an emission needs belongs to the call, not the instance.
     */
    Plan generate(Tam.Query query, Map<String, TaqlType> variables, String shapeKey);
}
