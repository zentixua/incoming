package ua.zentix.incoming.client.far;

/** Bounded block-cell cache, including absent terrain and every LOD backend. No boxed coordinate keys. */
final class DustHeightCache {
    private static final int SIZE = 4096;
    private final long[] keys = new long[SIZE], until = new long[SIZE];
    private final double[] heights = new double[SIZE];
    private final boolean[] occupied = new boolean[SIZE];
    private final int[] lifetimes = new int[SIZE];
    private long lastTick = Long.MIN_VALUE;

    double at(int x, int z, long tick, int lifetime, Sightline.Heights read) {
        if (tick < lastTick) java.util.Arrays.fill(occupied, false);
        lastTick = tick;
        long key = ((long) x << 32) ^ (z & 0xffffffffL), hash = key;
        hash = (hash ^ (hash >>> 33)) * 0xff51afd7ed558ccdL;
        hash = (hash ^ (hash >>> 33)) * 0xc4ceb9fe1a85ec53L;
        int slot = (int) (hash ^ (hash >>> 33)) & (SIZE - 1);
        if (!occupied[slot] || keys[slot] != key || tick >= until[slot] || lifetimes[slot] != lifetime) {
            heights[slot] = read.at(x, z);
            keys[slot] = key;
            until[slot] = tick + lifetime;
            lifetimes[slot] = lifetime;
            occupied[slot] = true;
        }
        return heights[slot];
    }
}
