package ua.zentix.incoming.nuclear.material;

import it.unimi.dsi.fastutil.HashCommon;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;

/** Ready-chunk samples for an unannounced detonation. A revision pins the buckets it saw at birth. */
public final class LoadedSurfaces {
    private static final int BUCKETS = 64;
    private final Long2IntOpenHashMap[] buckets = new Long2IntOpenHashMap[BUCKETS];
    private long shared;

    public void put(long key, int value) { change(key, value); }
    public void remove(long key) { change(key, -1); }
    private void change(long key, int value) {
        int i = (int) HashCommon.mix(key) & (BUCKETS - 1);
        Long2IntOpenHashMap bucket = buckets[i];
        if (bucket == null) {
            if (value < 0) return;
            buckets[i] = bucket = new Long2IntOpenHashMap();
            bucket.defaultReturnValue(-1);
        }
        if (bucket.get(key) == value) return;
        if ((shared & 1L << i) != 0) {
            buckets[i] = bucket = bucket.clone();
            shared &= ~(1L << i);
        }
        if (value < 0) bucket.remove(key);
        else bucket.put(key, value);
    }

    public Snapshot snapshot() {
        shared = -1L;
        return new Snapshot(buckets.clone());
    }
    public static final class Snapshot {
        private final Long2IntOpenHashMap[] pages;
        private Snapshot(Long2IntOpenHashMap[] pages) { this.pages = pages; }
        void overlay(Long2IntOpenHashMap target) {
            for (var page : pages) if (page != null) target.putAll(page);
        }
    }
}
