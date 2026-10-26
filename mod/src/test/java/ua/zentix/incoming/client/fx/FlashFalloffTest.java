package ua.zentix.incoming.client.fx;

import org.junit.jupiter.api.Test;
import ua.zentix.incoming.client.fx.smoke.CloudModel;
import ua.zentix.incoming.strike.WeaponType;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FlashFalloffTest {
    /** Rocket and Shahed flares - based on the model of their charge cloud (a ball of detonation products). */
    private static final CloudModel MISSILE = new CloudModel(BlastSource.of(WeaponType.MISSILE, false), CloudModel.Air.STANDARD),
            DRONE = new CloudModel(BlastSource.of(WeaponType.DRONE, false), CloudModel.Air.STANDARD);
    private static final double MISSILE_NEAR = BlastEffects.flashNear(MISSILE), MISSILE_RANGE = BlastEffects.flashRange(MISSILE);
    private static final double DRONE_NEAR = BlastEffects.flashNear(DRONE), DRONE_RANGE = BlastEffects.flashRange(DRONE);

    /** At the edge of the fireball, facing the explosion - full force (0.85), behind the barrier - 0.3. */
    @Test
    void closeBlastKeepsItsStrength() {
        assertEquals(0.85f, FlashFalloff.strength(MISSILE_NEAR, MISSILE_NEAR, MISSILE_RANGE, 1, true), 0.01f);
        assertEquals(0.30f, FlashFalloff.strength(MISSILE_NEAR, MISSILE_NEAR, MISSILE_RANGE, 1, false), 0.01f);
        assertEquals(0.85f, FlashFalloff.strength(0, DRONE_NEAR, DRONE_RANGE, 1, true), 0.01f);
    }

    /** Close up, the explosion is blinding, no matter where you look: from the side and behind Shahed in his fireball - no less than 0.75. */
    @Test
    void blastAtHandBlindsWhateverTheView() {
        double at = 0.15 * DRONE_NEAR;
        assertEquals(0.85f * (0.5f + 0.5f * (float) (1 - at / DRONE_NEAR)), FlashFalloff.strength(at, DRONE_NEAR, DRONE_RANGE, -1, true), 0.01f);
        assertTrue(FlashFalloff.strength(at, DRONE_NEAR, DRONE_RANGE, -1, true) >= 0.75f);
        float prev = 0;
        for (double d = DRONE_NEAR; d >= 0; d -= 0.5) {
            float s = FlashFalloff.strength(d, DRONE_NEAR, DRONE_RANGE, 0, true);
            assertTrue(s >= prev - 1e-6f, "d=" + d);
            prev = s;
        }
    }

    /** A salvo in 200 blocks (trailer plan barrage) does not whiten the screen: the fill is no more than 0.12 from the rocket and 0.05 from Shahed. */
    @Test
    void farSalvoDoesNotWashOut() {
        assertTrue(FlashFalloff.strength(200, MISSILE_NEAR, MISSILE_RANGE, 1, true) <= 0.12f);
        assertTrue(FlashFalloff.strength(200, DRONE_NEAR, DRONE_RANGE, 1, true) <= 0.05f);
    }

    /** During the day, the ball illuminates the surrounding area at a percentage of the sun - the screen barely brightens; at dusk and at night there is a flash. */
    @Test
    void daylightTamesTheScreenFlash() {
        assertTrue(FlashFalloff.daylight(1) <= 0.1f, "during the day " + FlashFalloff.daylight(1));
        assertEquals(1f, FlashFalloff.daylight(0.5), 1e-6f, "twilight");
        assertEquals(1f, FlashFalloff.daylight(0.17), 1e-6f, "night");
        float prev = 0;
        for (double a = 1; a >= 0.12; a -= 0.01) {
            float k = FlashFalloff.daylight(a);
            assertTrue(k >= prev, "sky light " + a);
            prev = k;
        }
    }

    /** Decreases monotonically with distance and reaches zero towards the edge without a jump. */
    @Test
    void monotonicAndContinuousAtRange() {
        for (double cos : new double[] {1, 0, -1}) {
            for (boolean visible : new boolean[] {true, false}) {
                float prev = Float.MAX_VALUE;
                for (double d = 0; d <= MISSILE_RANGE + 10; d += 1) {
                    float s = FlashFalloff.strength(d, MISSILE_NEAR, MISSILE_RANGE, cos, visible);
                    assertTrue(s <= prev + 1e-6f, "d=" + d + " cos=" + cos + " visible=" + visible);
                    prev = s;
                }
            }
        }
        assertTrue(FlashFalloff.strength(MISSILE_RANGE - 1, MISSILE_NEAR, MISSILE_RANGE, 1, true) < 0.001f);
        assertEquals(0f, FlashFalloff.strength(MISSILE_RANGE, MISSILE_NEAR, MISSILE_RANGE, 1, true));
    }

    /** An explosion blinds your field of vision; behind you there is only a reflection, behind the barrier it is even weaker. */
    @Test
    void glareNeedsTheBlastInView() {
        float front = FlashFalloff.strength(60, MISSILE_NEAR, MISSILE_RANGE, 1, true);
        float side = FlashFalloff.strength(60, MISSILE_NEAR, MISSILE_RANGE, Math.cos(Math.toRadians(45)), true);
        float back = FlashFalloff.strength(60, MISSILE_NEAR, MISSILE_RANGE, -1, true);
        float hidden = FlashFalloff.strength(60, MISSILE_NEAR, MISSILE_RANGE, 1, false);
        assertTrue(front > side && side > back, front + " " + side + " " + back);
        assertEquals(FlashFalloff.AMBIENT, back / front, 1e-4f);
        assertTrue(hidden <= back);
    }
}
