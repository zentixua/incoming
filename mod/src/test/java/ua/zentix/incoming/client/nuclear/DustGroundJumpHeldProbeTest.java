package ua.zentix.incoming.client.nuclear;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A jump of the eye within the coarse tile (17-127 coarse cells, 0.55-4 km: a respawn, a /tp, the next detonation after the
 * player walked away while no detonation ticked the tiles) restarts the sweep and drops the held heights of the cells it
 * carries over (review step 8, round 2): kept, they came back once the new sweep wrapped, the ground next to the old known
 * area instead of the new one, a 22.5 m pop of the floor past the new drawn chunks' edge.
 *
 * <p>Ground: a plateau at 120 around the old eye (x = 0) and a valley (floor 70) around the new one (x = 1000). Drawn chunks
 * 192 m around the eye. Unbounded sweeps (a full coarse sweep in 32 ticks), so the bound plays no part.
 */
class DustGroundJumpHeldProbeTest {
    private static double ground(double x, double z) {
        return 70 + 50 * (1 - Math.exp(-Math.pow(Math.hypot(x - 1000, z) / 200, 2)));
    }

    private static ua.zentix.incoming.client.far.Sightline.Heights drawn(double ex) {
        return (x, z) -> Math.hypot(x - ex, z) <= 192 ? Math.floor(ground(x, z)) : Double.NaN;
    }

    @AfterEach
    void clear() {
        DustGround.reset();
    }

    @Test
    void afterAJumpWithinTheTileTheFloorContinuesTheNewKnownEdge() {
        DustGround.reset();
        // the old place: 40 ticks (a full coarse sweep), then a step of 70 m (the fine tile re-centres: the refill after the
        // sweep takes the held heights)
        for (int i = 0; i < 40; i++) DustGround.update(drawn(0), 0, ground(0, 0) + 1.62, 0, 12, false, Long.MAX_VALUE);
        for (int i = 0; i < 40; i++) DustGround.update(drawn(70), 70, ground(70, 0) + 1.62, 0, 12, false, Long.MAX_VALUE);
        double edgeNew = Math.floor(ground(808, 0));
        // the jump: 1 km east (31 coarse cells: kept, the sweep restarted)
        var h = drawn(1000);
        double eyeY = ground(1000, 0) + 1.62;
        double beforeWrap = Double.NaN;
        for (int i = 0; i < 40; i++) {
            DustGround.update(h, 1000, eyeY, 0, 12, true, Long.MAX_VALUE);
            if (i == 20) beforeWrap = DustGround.floor(640, 0, 70);
        }
        // the sweep has wrapped; the player walks 70 m on (the fine tile re-centres, the tiles refill)
        var h2 = drawn(1070);
        for (int i = 0; i < 40; i++) DustGround.update(h2, 1070, ground(1070, 0) + 1.62, 0, 12, false, Long.MAX_VALUE);
        double west = DustGround.floor(640, 0, 70), north = DustGround.floor(1000, 400, 70);
        System.out.printf(java.util.Locale.ROOT,
                "new known edge (808 m) %.1f; floor at 640 m before the sweep wrapped %.1f, after %.1f; at (1000, 400) %.1f%n",
                edgeNew, beforeWrap, west, north);
        // past the new drawn chunks the floor continues the new known edge (the cells there are 3-6 times nearer to it)
        assertEquals(edgeNew, west, 6.0, "floor 170 m west of the new drawn chunks' edge");
        assertEquals(beforeWrap, west, 1.0, "no pop when the new sweep wraps");
    }
}
