package ua.zentix.incoming.client.fx.layer;

import java.util.ArrayList;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.function.Predicate;

/** Render-thread ownership of secondary histories. The primary view never enters this cache. */
final class SecondaryViews<K, V> {
    static final int LIMIT = 4;
    static final long TTL_NS = 5_000_000_000L;

    private final ArrayList<Entry<K, V>> entries = new ArrayList<>(LIMIT);
    private final Consumer<V> destroy;

    SecondaryViews(Consumer<V> destroy) {
        this.destroy = destroy;
    }

    /** A suspended outer render keeps its resources until its invocation finishes. */
    static final class Entry<K, V> {
        final K key;
        final V value;
        private long touched;
        private int users;

        Entry(K key, V value, long now) {
            this.key = key;
            this.value = value;
            touched = now;
        }
    }

    /** Null means this target is recursive or all four history slots are occupied. */
    Entry<K, V> acquire(K key, long now, Supplier<V> create) {
        expire(now);
        for (Entry<K, V> entry : entries) {
            if (entry.key.equals(key)) {
                if (entry.users != 0) return null;
                entry.touched = now;
                entry.users++;
                return entry;
            }
        }
        // Do not cycle expensive GPU histories when five screens refresh round-robin. A new feed waits
        // until an existing feed has been absent for TTL_NS; currently rendered scopes remain pinned.
        if (entries.size() == LIMIT) return null;
        Entry<K, V> entry = new Entry<>(key, create.get(), now);
        entry.users = 1;
        entries.add(entry);
        return entry;
    }

    void release(Entry<K, V> entry, long now) {
        if (entry.users <= 0) throw new IllegalStateException("Secondary view released without acquisition");
        entry.users--;
        entry.touched = now;
    }

    void invalidate(Predicate<K> stale) {
        for (int i = entries.size() - 1; i >= 0; i--) {
            Entry<K, V> entry = entries.get(i);
            if (entry.users == 0 && stale.test(entry.key)) {
                entries.remove(i);
                destroy.accept(entry.value);
            }
        }
    }

    void expire(long now) {
        for (int i = entries.size() - 1; i >= 0; i--) {
            Entry<K, V> entry = entries.get(i);
            if (entry.users == 0 && now - entry.touched >= TTL_NS) {
                entries.remove(i);
                destroy.accept(entry.value);
            }
        }
    }

    /** Caller first unwinds render scopes, then destroys histories on world exit or resource reload. */
    void clear() {
        for (Entry<K, V> entry : entries) destroy.accept(entry.value);
        entries.clear();
    }

    int size() { return entries.size(); }
}
