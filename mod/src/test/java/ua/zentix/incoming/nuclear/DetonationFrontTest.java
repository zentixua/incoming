package ua.zentix.incoming.nuclear;

import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Targeting-map shock-front ring ({@link Detonation#groundFront}): the in-game front where it meets the ground. */
class DetonationFrontTest {
    /** 15 kt airburst 600 m over the ground, wave 0.25 of sound speed, real scale. */
    private static final Detonation AIR = new Detonation(1, new Vec3(0, 664, 0), 64, 15, false, 0, 0f, 2.5f, 20_000, 0, 1f,
            true, 0.25f);
    /** 15 kt surface burst. */
    private static final Detonation SURFACE = new Detonation(2, new Vec3(0, 65, 0), 64, 15, true, 0, 0f, 2.5f, 20_000, 0, 1f,
            true, 0.25f);

    @Test
    void ringIsTheFrontOnTheGround() {
        for (Detonation d : new Detonation[]{AIR, SURFACE}) {
            double h = d.burst().y - d.groundY();
            double prev = -1;
            int shown = 0;
            for (double t = 0; t < 20 * 120; t += 2) {
                double g = d.groundFront(t);
                if (Double.isNaN(g)) continue;
                shown++;
                double slant = d.frontRadius(t);
                assertEquals(slant * slant, g * g + h * h, slant * slant * 1e-9 + 1e-6, "ground ring lies on the front sphere");
                assertTrue(slant <= d.blastReach(), "no ring where the wave no longer hits");
                assertTrue(g >= prev, "ring only grows");
                prev = g;
            }
            assertTrue(shown > 50, "ring shows while the wave travels");
        }
    }

    @Test
    void noRingBeforeTheFrontTouchesGroundNorPastTheHitReach() {
        double h = AIR.burst().y - AIR.groundY();
        assertTrue(Double.isNaN(AIR.groundFront(AIR.arrivalTicks(h) * 0.5)), "airburst front still aloft");
        assertTrue(Double.isNaN(AIR.groundFront(AIR.arrivalTicks(AIR.blastReach() * 1.01))), "past 0.3 psi only sound");
        assertTrue(AIR.groundFront(AIR.arrivalTicks(AIR.blastReach() * 0.99)) > 0);
    }
}
