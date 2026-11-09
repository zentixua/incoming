package ua.zentix.incoming.nuclear;

import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** World rule {@code nuclear.black_rain}: off means no black rain from the detonation at all, not just no contamination. */
class DetonationRainTest {
    /** 15 kt airburst 600 m over the ground, fires on, fallout on, real scale. */
    private static final Detonation AIR = new Detonation(1, new Vec3(0, 664, 0), 64, 15, false, 0, 0f, 2.5f, 20_000, 0, 1f,
            true, 0.25f, 0L, true);
    /** Over the fires while the storm rains (rain starts 80 storm-clock seconds after the burst). */
    private static final long RAINING = 100 * 20;

    @Test
    void rainFallsByDefault() {
        assertTrue(AIR.rain());
        assertTrue(AIR.blackRain(0, 0, RAINING));
        assertTrue(AIR.blackRainEndTicks() > RAINING);
        assertTrue(AIR.hasFallout(), "airburst rain washes radioactivity down");
        assertTrue(AIR.falloutRate(0, 0, RAINING * 4) > 0);
    }

    @Test
    void ruleOffMeansNoRainNoWashout() {
        Detonation dry = AIR.withRain(false);
        for (long t = 0; t < 20 * 2000; t += 100) assertFalse(dry.blackRain(0, 0, t));
        assertEquals(0, dry.blackRainEndTicks(), 0);
        assertFalse(dry.hasFallout(), "an airburst without rain leaves no fallout");
        assertEquals(0, dry.falloutRate(0, 0, RAINING * 4), 0);
        assertTrue(dry.fires(), "fires stay");
    }
}
