package ua.zentix.incoming.warhead;

import java.util.Arrays;

/**
 * Rock/structure cover above bunker-buster charge, without Minecraft for testing. Charge-column heightmap is bomb
 * bore bottom (bore extends up to one block sideways), so top comes from column ring 2 blocks around: structure
 * roof, ground. Surface breakout uses scaled depth ({@link BuriedBlast#breaches}).
 */
public final class BunkerCover {
    /** Ring columns: (dx, dz) offsets from charge column. */
    static final int[][] RING = {{-2, -2}, {-2, 0}, {-2, 2}, {0, -2}, {0, 2}, {2, -2}, {2, 0}, {2, 2}};
    private BunkerCover() {}

    /**
     * Top above charge from ring column heights (heightmap first air above top), lower median: up to three
     * high/low columns (trunk, wall edge, nearby hole) do not shift it.
     */
    public static int surface(int[] ring) {
        int[] h = ring.clone();
        Arrays.sort(h);
        return h[(h.length - 1) / 2];
    }

    /** Charge depth below top, blocks. */
    public static int depth(int surface, double chargeY) {
        return (int) Math.floor(surface - chargeY);
    }
}
