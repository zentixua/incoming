package ua.zentix.incoming.nuclear;

import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;
import ua.zentix.incoming.nuclear.model.BlackRainModel;
import ua.zentix.incoming.nuclear.model.SmokeParcels;
import ua.zentix.incoming.nuclear.model.SmokeParcelsTest;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The black-rain deposit must land with its rain, never before it and never where no rain
 * ever fell (time.md §3.1 item 7, §8.3 rule 4: "the same holds for black-rain deposit").
 */
class BlackRainDepositSeamTest {
    /** Hiroshima-like 15 kt airburst (600 m), wind 2.5 m/s toward +x, scale 1, fallout on, fires and rain on. */
    private static Detonation airburst(double yield, float wind) {
        return new Detonation(9, new Vec3(0, 64 + 600 * Math.cbrt(yield / 15), 0), 64, yield, false, 0, 0f, wind, 20_000, 0,
                1f, true, 0.25f, 0L, true, true);
    }

    /**
     * The server lands the deposit at the motion age its rain reaches the point ({@link Detonation#rainStartSeconds},
     * {@code GroundFallout} through {@code NuclearEvents.landingByMotion}; GameTest {@code probe_rain_landing}), with its
     * law's amplitude ({@link Detonation#rainH1}). On a vanilla day S runs 72× after the show: the deposit still lands with
     * its rain, not when S passes the rain's onset (15 kt airburst, 5 and 11 km downwind; no jumps, so M is real time).
     */
    @Test
    void depositLandsWithItsRainOnAVanillaDay() {
        Detonation d = airburst(15, 2.5f);
        for (double x : new double[]{5000, 11_000}) {
            double deposit = d.rainStartSeconds(x, 0), rain = Double.NaN;
            assertTrue(Double.isFinite(deposit) && d.rainH1(x, 0) > 0, x + " m downwind: no deposit");
            for (double t = 0; t < 4 * 3600 && Double.isNaN(rain); t += 5) {
                if (d.blackRainAt(x, 0, SmokeParcelsTest.day(72, t))) rain = t;
            }
            assertTrue(deposit >= rain - 5, x + " m downwind: deposit at " + deposit / 60 + " real min, its rain at " + rain / 60 + " real min");
        }
    }

    /** Wherever {@link BlackRainModel#startSeconds} is finite (a deposit lands), rain must have fallen there at some time. */
    @Test
    void depositOnlyWhereItRained() {
        StringBuilder bad = new StringBuilder();
        for (double[] c : new double[][]{{15, 2.5}, {15, 10}, {1, 5}, {5, 8}}) {
            Detonation d = airburst(c[0], (float) c[1]);
            BlackRainModel m = d.blackRainModel();
            double lastStart = 0, lastRain = 0;
            for (double x = 0; x <= 150_000; x += 500) {
                if (!Double.isFinite(m.startSeconds(x, 0))) continue;
                lastStart = x;
                for (double t = BlackRainModel.START_S; t < m.endSeconds(); t += 20) {
                    if (m.raining(x, 0, SmokeParcelsTest.day(1, t))) {
                        lastRain = x;
                        break;
                    }
                }
            }
            if (lastStart > lastRain + 500)
                bad.append(String.format(java.util.Locale.ROOT, "%n%.0f kt, %.1f m/s: deposit band to %.1f km, rain only to %.1f km",
                        c[0], c[1], lastStart / 1000, lastRain / 1000));
        }
        assertTrue(bad.isEmpty(), "deposit without rain:" + bad);
    }
}
