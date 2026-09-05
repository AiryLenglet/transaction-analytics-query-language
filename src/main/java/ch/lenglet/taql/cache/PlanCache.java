package ch.lenglet.taql.cache;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;

/**
 * A bounded LRU keyed by whatever the caller decides identifies a plan.
 *
 * Deliberately small: the interesting part of plan caching is the *key*, not
 * the map. In production this would be Caffeine, for per-entry stats and
 * non-blocking reads.
 */
public final class PlanCache<K, V> {

    private final int capacity;
    private final Map<K, V> entries;
    private final AtomicLong hits = new AtomicLong();
    private final AtomicLong misses = new AtomicLong();

    public PlanCache(int capacity) {
        this.capacity = capacity;
        this.entries = new LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<K, V> eldest) {
                return size() > PlanCache.this.capacity;
            }
        };
    }

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

    public long hits() {
        return hits.get();
    }

    public long misses() {
        return misses.get();
    }

    public int size() {
        synchronized (entries) {
            return entries.size();
        }
    }

    public void clear() {
        synchronized (entries) {
            entries.clear();
        }
        hits.set(0);
        misses.set(0);
    }
}
