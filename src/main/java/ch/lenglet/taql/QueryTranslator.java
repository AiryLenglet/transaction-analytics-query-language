package ch.lenglet.taql;

import ch.lenglet.taql.plan.Plan;
import ch.lenglet.taql.sem.Tam;

import java.util.Map;

/**
 * Turns a resolved query into a statement in some target query language.
 *
 * The pipeline in front of this is language-agnostic: text parses to an
 * {@link ch.lenglet.taql.ast.Ast}, resolves against a
 * {@link ch.lenglet.taql.catalog.Catalog} into {@link Tam}, and none of it names
 * a dialect. This interface is the one place a query becomes T-SQL -- or, in
 * time, Cypher.
 *
 * <h2>Why the resolver needs one</h2>
 * Typing a value is not purely logical. A literal compared against a column
 * takes that column's physical type, so it binds as the thing the column
 * actually is; a literal with no column to learn from needs a default, and what
 * "a reasonable default for a string" is belongs to the target language, not to
 * the DSL. Passing a translator into resolution is what stopped {@code Resolver}
 * from naming {@code varchar(400)} directly.
 *
 * <h2>Translating, not running</h2>
 * Nothing here touches a store: no connection, no driver, no error taxonomy.
 * That is {@link ch.lenglet.taql.runtime.PlanRunner}, and the two are separate
 * because a translator is pure and shareable while a runner holds resources.
 * Bundling them would make a compiler depend on a live connection, which is
 * exactly what a validation endpoint must not need.
 *
 * <h2>What is deliberately still missing</h2>
 * A capability model. A translator that cannot express every TAQL construct --
 * window functions have no obvious analogue outside SQL -- needs to say so, and
 * the resolver needs to turn that into a positioned diagnostic rather than
 * letting the translator throw {@code IllegalStateException} at a caller. The
 * open question is the granularity: per function, per construct, or one check
 * over a whole {@link Tam.Query}. That wants a real second implementation to
 * answer, because an interface extracted from one is shaped like that one.
 */
public interface QueryTranslator {

    /** Identifies the target language in diagnostics and logs. */
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
     * whatever state one translation needs belongs to the call, not the
     * instance.
     */
    Plan translate(Tam.Query query, Map<String, TaqlType> variables, String shapeKey);
}
