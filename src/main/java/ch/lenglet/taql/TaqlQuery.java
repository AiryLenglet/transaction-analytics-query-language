package ch.lenglet.taql;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A TAQL query and the values it is to be run with.
 *
 * The wrapper exists so that "this text is a query" is stated in the type
 * rather than assumed. At a REST boundary every interesting string is a
 * {@code String} -- a body, a header, a path segment -- and a bare
 * {@code execute(String)} accepts all of them equally. Someone has to write
 * {@code TaqlQuery.of(...)} for text to become a query, which is a small,
 * greppable place where that decision is made.
 *
 * Source and values travel together because they are only meaningful together:
 * the values a query needs are a property of the query text, and passing them
 * as separate arguments is what lets them drift apart. The compiler's caches are
 * keyed on the source alone, so pairing them here costs nothing.
 */
public record TaqlQuery(String source, Map<String, Object> variables) {

    public TaqlQuery {
        if (source == null) throw new IllegalArgumentException("source is required");
        // Not Map.copyOf: it rejects null values, and binding a variable to null
        // is meaningful -- Binder tells "absent" from "present and null", and
        // only the first is an error.
        variables = Collections.unmodifiableMap(new LinkedHashMap<>(variables));
    }

    public static TaqlQuery of(String source) {
        return new TaqlQuery(source, Map.of());
    }

    public static TaqlQuery of(String source, Map<String, Object> variables) {
        return new TaqlQuery(source, variables);
    }

    /** This query with one more value bound. Returns a new instance. */
    public TaqlQuery with(String name, Object value) {
        Map<String, Object> next = new LinkedHashMap<>(variables);
        next.put(name, value);
        return new TaqlQuery(source, next);
    }

    /**
     * Deliberately carries no values.
     *
     * A record's generated {@code toString} would render both fields, so a
     * single {@code log.debug("{}", query)} would put client identifiers in a
     * log -- the values directly, and the source too, since a query may state
     * its constants inline. Variable <em>names</em> are safe: they are written
     * by whoever wrote the query and are part of its published contract.
     *
     * Making this impossible beats remembering not to do it, which is the same
     * reason literals are lifted out of the AST rather than carefully avoided.
     */
    @Override
    public String toString() {
        return "TaqlQuery[source=" + source.length() + " chars, variables=" + variables.keySet() + "]";
    }
}
