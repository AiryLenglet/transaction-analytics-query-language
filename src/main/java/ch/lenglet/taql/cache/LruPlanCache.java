package ch.lenglet.taql.cache;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;

/**
 * A bounded LRU, and the default {@link PlanCache}.
 *
 * Deliberately small -- see the interface for why the map is the boring part.
 * A production deployment should hand the compiler a Caffeine-backed
 * implementation instead: this one takes a lock on every read, and evicts by
 * entry count rather than by the size of what it is holding.
 */
public final class LruPlanCache<K, V> implements PlanCache<K, V> {

    private final int capacity;
    private final Map<K, V> entries;
    private final AtomicLong hits = new AtomicLong();
    private final AtomicLong misses = new AtomicLong();

    public LruPlanCache(int capacity) {
        this.capacity = capacity;
        this.entries = new LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<K, V> eldest) {
                return size() > LruPlanCache.this.capacity;
            }
        };
    }

    @Override
    public V get(K key, Function<K, V> compute) {
        synchronized (entries) {
            V existing = entries.get(key);
            if (existing != null) {
                hits.incrementAndGet();
                return existing;
            }
        }
        misses.incrementAndGet();
        V value = compute.apply(key);
        synchronized (entries) {
            entries.put(key, value);
        }
        return value;
    }

    @Override
    public Stats stats() {
        synchronized (entries) {
            return new Stats(hits.get(), misses.get(), entries.size());
        }
    }

    @Override
    public void clear() {
        synchronized (entries) {
            entries.clear();
        }
        hits.set(0);
        misses.set(0);
    }
}
