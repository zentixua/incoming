package ua.zentix.incoming.strike;

import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Salvo stripes ({@link WeaponSpec.Lanes}): each projectile has its own salvo seed, in spec width. */
class SalvoLanesTest {
    private static final WeaponSpec.Lanes LANES = new WeaponSpec.Lanes(32, 100, 300);

    private static double[] lanes(long seed, int total) {
        double[] out = new double[total];
        for (int i = 0; i < total; i++) out[i] = LANES.offset(seed, i, total);
        return out;
    }

    @Test
    void sameSeedSameLanes() {
        assertArrayEquals(lanes(42, 7), lanes(42, 7));
        assertFalse(Arrays.equals(lanes(42, 7), lanes(43, 7)), "different seed - same stripes");
    }

    @Test
    void singleRoundFliesThePathItself() {
        assertEquals(0, LANES.offset(42, 0, 1));
        assertEquals(0, WeaponSpec.Lanes.NONE.offset(42, 0, 5));
    }

    /** Each in its own lane: no wider than spec, neighbors no closer than half the lane, wave - almost the entire width. */
    @Test
    void everyRoundHasItsOwnLaneWithinTheBand() {
        for (int total = 2; total <= 30; total++) {
            for (long seed = 0; seed < 50; seed++) {
                double[] l = lanes(seed * 7919, total);
                Arrays.sort(l);
                double slot = 2 * LANES.halfWidth() / total;
                assertTrue(l[0] > -LANES.halfWidth() && l[total - 1] < LANES.halfWidth(), "wider spec: " + Arrays.toString(l));
                for (int i = 1; i < total; i++) {
                    assertTrue(l[i] - l[i - 1] >= slot / 2 - 1e-9, "neighbors closer than half the strip: " + Arrays.toString(l));
                }
                assertTrue(l[total - 1] - l[0] >= LANES.halfWidth() * 2 * (total - 1.5) / total - 1e-9,
                        "the wave is narrower than the stripes: " + Arrays.toString(l));
            }
        }
    }

    /** The order of the stripes is mixed: the first shells of the salvo do not come from one edge (otherwise the wave is an oblique chain). */
    @Test
    void lanesAreShuffledBySeed() {
        int firstOnEdge = 0, seeds = 200;
        for (long seed = 0; seed < seeds; seed++) {
            double[] l = lanes(seed, 5);
            if (l[0] < l[1] && l[1] < l[2] && l[2] < l[3] && l[3] < l[4]) firstOnEdge++;
        }
        assertTrue(firstOnEdge < seeds / 10, "in order from left to right in " + firstOnEdge + " volley of " + seeds);
    }

    /**
     * Stripes - for weapons that fly along the route (Shahed, rocket, “Lancet”); "Grad", B-2 and ICBM do not have a route. The exit to the
     *  strip is further than the radius of capturing the point (otherwise the projectile would have taken it immediately), the convergence is not shorter than the attack geometry:
     *  approaches the target and dives - already on the way to its aiming point.
     */
    @Test
    void passportLanes() {
        for (WeaponType w : WeaponType.values()) {
            WeaponSpec spec = w.spec();
            WeaponSpec.Lanes lanes = spec.salvo().lanes();
            boolean routed = w == WeaponType.DRONE || w == WeaponType.MISSILE || w == WeaponType.LOITER;
            assertEquals(routed, lanes.halfWidth() > 0, w + ": stripes " + lanes);
            if (!routed) continue;
            assertTrue(lanes.exit() >= 2 * spec.capture(0), w + ": exit to the lane " + lanes.exit() + " upon capture " + spec.capture(0));
            assertTrue(lanes.converge() >= spec.airframe().attack(0).terminalRange(), w + ": toe is shorter than attack");
            // stripe of the order of the distance between adjacent projectiles: a wave, not a chain or a fan
            double spacing = spec.salvo().gapMin() * spec.airframe().cruiseSpeed(0);
            assertTrue(lanes.halfWidth() <= spacing && 2 * lanes.halfWidth() >= spacing / 2,
                    w + ": band ±" + lanes.halfWidth() + " when walking " + spacing);
        }
    }

    /** The lengths of the stripes increase with the march, like the distance between the shells ({@code Airframe.stretch}). */
    @Test
    void lanesScaleWithCruise() {
        WeaponSpec.Lanes k2 = LANES.scaled(2);
        assertEquals(64, k2.halfWidth());
        assertEquals(200, k2.exit());
        assertEquals(600, k2.converge());
        assertEquals(2 * LANES.offset(9, 2, 5), k2.offset(9, 2, 5), 1e-9);
    }
}
