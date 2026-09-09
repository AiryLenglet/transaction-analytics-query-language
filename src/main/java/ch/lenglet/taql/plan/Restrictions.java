package ch.lenglet.taql.plan;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * What a query's filter pins each field down to, with the values resolved.
 *
 * Produced after binding, because that is the only point where the values exist:
 * literals are lifted out of the tree at parse time and a cached plan holds slot
 * indices, so two callers asking about two different clients share one plan.
 *
 * <h2>Reading {@link #on(String)}</h2>
 * <ul>
 *   <li>{@code Optional.of(values)} -- the query cannot return a row whose field
 *       is outside this set. Safe to authorise against.</li>
 *   <li>{@code Optional.empty()} -- <b>the field is not pinned down in any way
 *       this can compute</b>. A policy must read that as "refuse", never as
 *       "nothing to check".</li>
 * </ul>
 *
 * The empty case covers more than "no filter": a field compared with
 * {@code like}, {@code !=} or a range has no enumerable set, and so does one
 * mentioned only under an {@code or} -- {@code ClientId = '1' or Country = 'CH'}
 * restricts nothing at all, because the second branch matches any client.
 */
public final class Restrictions {

    public static final Restrictions NONE = new Restrictions(Map.of());

    private final Map<String, Set<Object>> byField;

    public Restrictions(Map<String, Set<Object>> byField) {
        Map<String, Set<Object>> normalised = new LinkedHashMap<>();
        byField.forEach((field, values) -> normalised.put(lower(field), Set.copyOf(values)));
        this.byField = Collections.unmodifiableMap(normalised);
    }

    /**
     * The values {@code field} is pinned to, or empty when it is not pinned down
     * knowably. Field names match the way the catalog matches them: case-insensitively.
     */
    public Optional<Set<Object>> on(String field) {
        return Optional.ofNullable(byField.get(lower(field)));
    }

    /** Every field this query pins down, by canonical catalog name. */
    public Set<String> fields() {
        return byField.keySet();
    }

    private static String lower(String field) {
        return field.toLowerCase(Locale.ROOT);
    }

    /**
     * Names fields, never values -- the values are client data, and this is the
     * kind of object someone logs while debugging a policy.
     */
    @Override
    public String toString() {
        return "Restrictions" + byField.keySet();
    }
}
