package ch.lenglet.taql;

/**
 * A value's type in whatever store the query will run against.
 *
 * The DSL reasons in {@link TaqlType} -- string, integer, date -- and knows
 * nothing else. A backend reasons in its own vocabulary: T-SQL in
 * {@code varchar(50)} and {@code decimal(10,2)}, another store in something
 * that need not resemble either.
 *
 * The typed model and the plan have to <em>carry</em> a physical type, because
 * a parameter must be sent as the thing the column actually is -- that is the
 * difference between seeking an index and converting every row. But they have
 * no business knowing whose vocabulary it is: naming {@code SqlType} in
 * {@code Tam} put T-SQL in the middle of a pipeline whose whole point is that
 * only its last stage is dialect-specific.
 *
 * Deliberately almost empty. Everything between the resolver and the generator
 * only carries these and compares them for equality; anything a backend needs
 * to <em>do</em> with one belongs on that backend's own type, where the code
 * that does it already lives.
 */
public interface PhysicalType {

    /** How this type is written in its own dialect. For diagnostics and logs. */
    String describe();
}
