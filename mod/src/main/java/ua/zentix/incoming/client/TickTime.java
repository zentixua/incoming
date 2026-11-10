package ua.zentix.incoming.client;

/**
 * A tick count plus the frame's partial tick, for anything drawn between ticks. Java computes {@code long + float} in
 * float (24-bit mantissa): at a world's game time of 2·10⁹ ticks the sum moves in steps of 128 ticks (6.4 s), which
 * froze the nuclear detonation countdown for 6.4 s and then jumped it (Zearth, 3.12.0 and earlier); client tick
 * counters lose the fraction the same way after hours. Always add the partial tick through here.
 */
public final class TickTime {
    private TickTime() {}

    /** The tick count plus the partial tick in double: exact for every game time a world reaches. */
    public static double at(long ticks, float partial) {
        return ticks + (double) partial;
    }

    /** The same for a partial tick already in double. */
    public static double at(long ticks, double partial) {
        return ticks + partial;
    }
}
