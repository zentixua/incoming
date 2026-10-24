package ua.zentix.incoming.client.fx;

/** Ordinary effects share the client pre-tick birth stamp, irrespective of client post-tick listener order. */
public final class BlastTime {
    private static long ticks;

    private BlastTime() {}

    public static void advance() { ticks++; }
    public static long ticks() { return ticks; }
    public static void reset() { ticks = 0; }

    public static double seconds(long clientTick, long bornTick, double partial) {
        return Math.max(0, clientTick - bornTick + partial) / 20;
    }
}
