package ch.lenglet.taql.cache;

import java.util.function.Function;

/**
 * A cache keyed by whatever the caller decides identifies a plan.
 *
 * The interesting part of plan caching is the <em>key</em>, not the map --
 * which is exactly why this is an interface. {@link LruPlanCache} is a few
 * dozen lines of {@code LinkedHashMap} and serves the demo; a deployment wants
 * Caffeine for per-entry statistics, size-aware eviction and non-blocking
 * reads, and should be able to have it without the compiler noticing.
 *
 * <h2>What an implementation must guarantee</h2>
 * <ul>
 *   <li><b>Safe for concurrent use.</b> One compiler is shared by every request
 *       thread.</li>
 *   <li><b>Nothing else.</b> In particular {@code compute} may run more than
 *       once for the same key: {@link LruPlanCache} deliberately computes
 *       outside its lock, so a race compiles the same plan twice and discards
 *       one. Callers must therefore keep {@code compute} free of side effects --
 *       and they do, because compiling is a pure function of the query text.
 *       An implementation that computes exactly once (Caffeine does) satisfies
 *       this contract too; the weaker promise is the one to code against.</li>
 * </ul>
 */
public interface PlanCache<K, V> {

    /** The value for {@code key}, computing and storing it if it is absent. */
    V get(K key, Function<K, V> compute);

    /** Drops every entry. */
    void clear();

    /**
     * Counters for metrics. An implementation that does not record them --
     * Caffeine only does when asked -- reports zeros rather than guessing.
     */
    Stats stats();

    record Stats(long hits, long misses, int size) {

        /** Hit rate in 0..1, or 0 when nothing has been asked for yet. */
        public double hitRate() {
            long total = hits + misses;
            return total == 0 ? 0 : (double) hits / total;
        }
    }
}
