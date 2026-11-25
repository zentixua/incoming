package ua.zentix.incoming.client.nuclear;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A tile's origin and its filled heights change together ({@link DustGround}): a re-centred tile refills in the same tick, so a
 * walking or flying player's dust floor stays on the ground and a camera jump never lays the old place's ground under the new
 * eye (a refill only every FILL_TICKS read heights 16–40 cells off for up to 9 ticks: 24 m off while sprinting, 70 m after a jump).
 */
class DustGroundRecentreTest {
    /** DustGroundTest's valley, here across x: the eye walks out of it up the slope. */
    private static double ground(double x, double z) {
        return Math.floor(70 + 40 * (1 - Math.exp(-Math.pow(x / 150, 2))));
    }

    @AfterEach
    void clear() {
        DustGround.reset();
    }

    @Test
    void walkingPlayersFloorStaysOnTheGround() {
        // client chunks: 12 chunks around the eye (as DustGroundTest's sweep), so the edge keeps loading as the player walks and the
        // tiles refill every FILL_TICKS (an ordinary walk; ruins falling under the wave do the same)
        double ex = 0, ez = 0;
        for (int i = 0; i < 40; i++) {
            final double cx = ex, cz = ez;
            DustGround.update((x, z) -> Math.hypot(x - cx, z - cz) <= 192 ? ground(x, z) : Double.NaN, ex, ground(ex, ez) + 1.62, ez, 12, i == 39);
        }
        double worst = 0, worstAt = 0;
        int bad = 0;
        // sprint, 5.6 m/s = 0.28 blocks a tick, 400 m east
        for (int tick = 0; tick < 1430; tick++) {
            ex += 0.28;
            final double cx = ex, cz = ez;
            DustGround.update((x, z) -> Math.hypot(x - cx, z - cz) <= 192 ? ground(x, z) : Double.NaN, ex, ground(ex, ez) + 1.62, ez, 12, false);
            // the floor at the eye and 40 m around it (the dust the player stands in)
            double e = 0;
            for (double dx = -40; dx <= 40; dx += 20) e = Math.max(e, Math.abs(DustGround.floor(ex + dx, ez, 0) - ground(ex + dx, ez)));
            if (e > 2) bad++;
            if (e > worst) {
                worst = e;
                worstAt = ex;
            }
        }
        String msg = String.format(Locale.ROOT, "floor off the ground by up to %.1f m (at eye x %.0f), %d of 1430 ticks over 2 m", worst, worstAt, bad);
        assertTrue(worst < 2, msg);
    }

    @Test
    void cameraJumpDoesNotLayTheDustOnTheOldPlace() {
        // a camera jump (projectile camera, replay camera, /tp): the old place's heights stay readable at the new place
        double ex = 0;
        for (int i = 0; i < 40; i++) {
            DustGround.update((x, z) -> Math.hypot(x, z) <= 192 ? ground(x, z) : Double.NaN, 0, 71.62, 0, 12, i == 39);
        }
        // a refill happened a tick ago (sinceFill < FILL_TICKS): a block changed near the eye (a ruin, a chunk arriving)
        DustGround.update((x, z) -> Math.hypot(x, z) <= 192 ? ground(x, z) + 1 : Double.NaN, 0, 71.62, 0, 12, true);
        // the camera is now 3 km east over flat ground at y 140, nothing known there yet
        DustGround.update((x, z) -> Math.hypot(x - 3000, z) <= 192 ? 140.0 : Double.NaN, 3000, 141.62, 0, 12, false);
        double floor = DustGround.floor(3000, 0, 140);
        String msg = String.format(Locale.ROOT, "floor under the new eye %.1f, ground 140 (fallback map 140)", floor);
        assertTrue(Math.abs(floor - 140) < 2, msg);
    }
}
