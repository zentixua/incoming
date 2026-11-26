package ua.zentix.incoming.nuclear;

import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;
import ua.zentix.incoming.nuclear.model.BlackRainModel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** World rule {@code nuclear.black_rain}: off means no black rain from the detonation at all, not just no contamination. */
class DetonationRainTest {
    /** 15 kt airburst 600 m over the ground, fires on, fallout on, real scale. */
    private static final Detonation AIR = new Detonation(1, new Vec3(0, 664, 0), 64, 15, false, 0, 0f, 2.5f, 20_000, 0, 1f,
            true, 0.25f, 0L, true);
    /** Over the fires while the storm rains (rain starts 20 minutes after the burst; a 24-hour day, S = M). */
    private static final double RAINING = BlackRainModel.START_S + 600;

    @Test
    void rainFallsByDefault() {
        assertTrue(AIR.rain());
        assertTrue(AIR.blackRainAt(0, 0, ua.zentix.incoming.nuclear.model.SmokeParcels.Clock.of(RAINING, m -> m)));
        assertTrue(AIR.blackRainEndSeconds() > RAINING);
        assertTrue(AIR.hasFallout(), "airburst rain washes radioactivity down");
        assertTrue(AIR.rainH1(0, 0) > 0, "rain dust lands at ground zero");
    }

    @Test
    void ruleOffMeansNoRainNoWashout() {
        Detonation dry = AIR.withRain(false);
        for (double t = 0; t < 4 * 3600; t += 5) assertFalse(dry.blackRainAt(0, 0, ua.zentix.incoming.nuclear.model.SmokeParcels.Clock.of(t, m -> m)));
        assertEquals(0, dry.blackRainEndSeconds(), 0);
        assertFalse(dry.hasFallout(), "an airburst without rain leaves no fallout");
        assertEquals(0, dry.rainH1(0, 0) + dry.falloutH1(0, 0), 0);
        assertTrue(dry.fires(), "fires stay");
    }
}
