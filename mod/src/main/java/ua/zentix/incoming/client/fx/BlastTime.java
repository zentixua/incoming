package ua.zentix.incoming.client.fx;

/**
 * Ordinary effects share the client pre-tick birth stamp, irrespective of client post-tick listener order. The stamp is the
 * conventional air's motion age M ({@code util/StrikeClock}): client ticks plus the jumps nobody in this dimension saw
 * ({@link #jump}), so a cloud born before such a jump is that much older after it.
 */
public final class BlastTime {
    private static long ticks;

    private BlastTime() {}

    public static void advance() { ticks++; }
    public static long ticks() { return ticks; }
    public static void reset() { ticks = 0; }
    /** A jump nobody in this dimension saw, real ticks of the air ({@code CountedJump}). */
    public static void jump(long by) { ticks += Math.max(0, by); }

    public static double seconds(long clientTick, long bornTick, double partial) {
        return Math.max(0, clientTick - bornTick + partial) / 20;
    }
}
