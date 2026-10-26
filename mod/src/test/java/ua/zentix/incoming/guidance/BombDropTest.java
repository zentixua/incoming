package ua.zentix.incoming.guidance;

import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BombDropTest {
    /** B-2: echelon 170, drop range on it 145, 12 blocks/tick. */
    private static final double RATIO = 145.0 / 170, SPEED = 12;
    /** Speed B-2 and bombs when dropped: horizontally, heading +z. */
    private static final Vec3 LEVEL = new Vec3(0, 0, SPEED);

    /**
     * Where B-2 will drop the bomb, walking in a straight line at a height of {@code h} above a point (at {@code side} off course) from
     * {@code from} blocks before it; -1 - nowhere.
     */
    private static double releaseAt(double h, double from, double side) {
        Vec3 aim = Vec3.ZERO;
        for (double z = -from; z < 400; z += SPEED) {
            Vec3 pos = new Vec3(side, h, z);
            if (BombDrop.releaseNow(pos, LEVEL, aim, RATIO)) return pos.horizontalDistance();
        }
        return -1;
    }

    /**
     * At echelon altitude, release near the 145-block line (B-2 travels 12 per tick), regardless of approach origin:
     * bomb releases at B-2 speed; closer than ~126 blocks from height 170 it cannot lower its nose and overshoots.
     */
    @Test
    void releasesAtTheLineOnLevel() {
        for (int phase = 0; phase < SPEED; phase++) {
            double at = releaseAt(170, 1200 + phase, 0);
            assertTrue(at > 145 - SPEED && at <= 145 + 3, "reset to " + at + " (shift " + phase + ")");
        }
    }

    /**
     * From any height from 20 to 490 blocks above the point (returned to the world above the mountain, the point was retargeted higher or lower) B-2
     *  resets on the first pass and no further than the edge of the window: above the echelon on the line for its height, below - on the edge, and he is
     *  on a course no further than the altitude, nose-down arc and {@link BombDrop#EDGE_REACH} (further - torn hits). A point on the side
     *  of the course - also if there is enough height above it: the bomb turns towards it while falling, and low (below ~50 blocks
     *  above the indentation) does not have time - then the approach is again. That the bomb from there comes to a point in the world is checked by GameTest
     * {@code bomberReleasePointsHitInWorld}.
     */
    @Test
    void releasesFromAnyHeight() {
        for (int h = 20; h <= 490; h += 10) {
            for (double side : new double[]{0, 20, -35, 60}) {
                if (side != 0 && h < 50 + Math.abs(side)) continue;
                for (int phase = 0; phase < SPEED; phase++) {
                    double at = releaseAt(h, 1200 + phase, side);
                    String where = "s " + h + " (side " + side + ", shift " + phase + ")";
                    assertTrue(at > 0, where + " didn't reset");
                    double along = Math.sqrt(at * at - side * side);
                    assertTrue(at <= h * RATIO || along <= h + BombDrop.pullOver(SPEED) + BombDrop.EDGE_REACH, where + " dropped in " + at);
                }
            }
        }
    }

    /**
     * The near edge of the window is where the nose of the bomb touches the ground before it approaches the point: from 140 blocks with 126 and from
     * 170 with 118, the bomb flies over the point to 11–23 blocks.
     */
    @Test
    void edgeCountsTheNoseAndGround() {
        assertTrue(BombDrop.miss(new Vec3(0, 140, -126), LEVEL, Vec3.ZERO) > 10);
        assertTrue(BombDrop.miss(new Vec3(0, 170, -118), LEVEL, Vec3.ZERO) > 10);
    }

    /**
     * Misses that make release position-dependent: from 170 with 85 (old line, when bomb release was slower than B-2),
     * the bomb overshoots the point by ~100 blocks, and also by tens for a point 40 blocks ahead. From 214, B-2 releases
     * beyond the echelon line, but inside the near window edge it does not release at all and makes another approach.
     */
    @Test
    void doesNotReleaseWhereTheBombMisses() {
        assertTrue(BombDrop.miss(new Vec3(0, 170, -85), LEVEL, Vec3.ZERO) > 50);
        assertTrue(releaseAt(214, 700, 0) > 145, "with 214 - beyond the line");
        assertTrue(BombDrop.miss(new Vec3(0, 170, -40), LEVEL, Vec3.ZERO) > 30);
        assertEquals(-1, releaseAt(170, 100, 0), "inside the edge of the window does not reset");
        assertEquals(-1, releaseAt(170, 40, 0), "point at 40 blocks ahead");
    }

    /** The point is behind: the bomb falls on course and does not steer back - do not drop it. */
    @Test
    void neverReleasesOverPointBehind() {
        Vec3 pos = new Vec3(0, 170, 60);
        assertTrue(BombDrop.passed(pos, Vec3.ZERO, 0f));
        assertTrue(BombDrop.miss(pos, LEVEL, Vec3.ZERO) > 0);
    }

    /** B-2 below the point (retargeted to the top of the mountain above it): the bomb will not reach there - do not drop it. */
    @Test
    void neverReleasesBelowThePoint() {
        assertEquals(-1, releaseAt(-20, 1000, 0));
    }
}
