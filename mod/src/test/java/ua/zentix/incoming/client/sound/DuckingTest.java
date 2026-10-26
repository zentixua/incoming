package ua.zentix.incoming.client.sound;

import org.junit.jupiter.api.Test;
import ua.zentix.incoming.client.fx.BlastSource;
import ua.zentix.incoming.strike.WeaponType;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Motors under a loud explosion: how far they go down, how long they last, how they return. */
class DuckingTest {
    private static final BlastSource SHAHED = BlastSource.of(WeaponType.DRONE, false);

    @Test
    void distantBlastLeavesEnginesAlone() {
        Ducking d = new Ducking();
        d.blast(BlastMix.loudness(BlastMix.pressure(SHAHED, Outdoor.NEAR)));
        assertEquals(1, d.factor(), 1e-6);
    }

    @Test
    void closeBlastDucksFullyThenReleasesInAboutASecondAndAHalf() {
        Ducking d = new Ducking();
        d.blast(BlastMix.NEAR_LUFS);
        assertEquals(Math.pow(10, -Ducking.DEPTH / 20), d.factor(), 1e-6);
        for (int i = 0; i < Ducking.HOLD; i++) d.tick();
        assertEquals(Math.pow(10, -Ducking.DEPTH / 20), d.factor(), 1e-6, "holds on");
        float prev = d.factor();
        int ticks = Ducking.HOLD;
        while (d.factor() < 1) {
            d.tick();
            ticks++;
            assertTrue(d.factor() >= prev, "just coming back");
            assertTrue(20 * Math.log10(d.factor() / prev) <= Ducking.RELEASE + 1e-6, "no jump");
            prev = d.factor();
        }
        assertTrue(ticks >= 25 && ticks <= 40, "came back for " + ticks + " tick");
    }

    @Test
    void quieterBlastDoesNotExtendALouderOne() {
        Ducking d = new Ducking();
        d.blast(BlastMix.NEAR_LUFS);
        for (int i = 0; i < 10; i++) d.tick();
        float before = d.factor();
        double quieter = BlastMix.loudness(BlastMix.pressure(SHAHED, 150));
        Ducking alone = new Ducking();
        alone.blast(quieter);
        assertTrue(alone.factor() < 0.9 && alone.factor() > before, "by itself it muffles, but less");
        // the explosion is quieter, but still muffled: the depth and release are the same, without a new shutter speed
        assertTrue(Math.pow(10, -Ducking.DEPTH / 20) < before && before < 0.99);
        d.blast(quieter);
        assertEquals(before, d.factor(), 1e-6);
        d.tick();
        assertEquals(Ducking.RELEASE, 20 * Math.log10(d.factor() / before), 1e-4, "lets go further instead of holding");
        d.blast(BlastMix.NEAR_LUFS);
        assertEquals(Math.pow(10, -Ducking.DEPTH / 20), d.factor(), 1e-6, "new close explosion - all the depth again");
    }
}
