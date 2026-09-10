package ch.lenglet.taql;

/**
 * A TAQL query.
 *
 * The wrapper exists so that "this text is a query" is stated in the type
 * rather than assumed. At a REST boundary every interesting string is a
 * {@code String} -- a body, a header, a path segment -- and a bare
 * {@code execute(String)} accepts all of them equally. Someone has to write
 * {@code TaqlQuery.of(...)} for text to become a query, which is a small,
 * greppable place where that decision is made.
 *
 * <h2>A query carries its own values</h2>
 * There is nothing else to supply. TAQL has no placeholders: what a query asks
 * for is written in it, so reading the text tells you the whole question, and
 * there is only one way to express a value. That is not a limitation of the
 * plan cache -- literals are lifted out of the tree during parsing, so a
 * thousand queries differing only in their constants still share one compiled
 * plan.
 */
public record TaqlQuery(String source) {

    public TaqlQuery {
        if (source == null) throw new IllegalArgumentException("source is required");
    }

    public static TaqlQuery of(String source) {
        return new TaqlQuery(source);
    }

    /**
     * Deliberately carries no source text.
     *
     * A record's generated {@code toString} would render it, so a single
     * {@code log.debug("{}", query)} would put client identifiers in a log --
     * a query may state its constants inline, and those are the values it is
     * asking about.
     */
    @Override
    public String toString() {
        return "TaqlQuery[source=" + source.length() + " chars]";
    }
}
