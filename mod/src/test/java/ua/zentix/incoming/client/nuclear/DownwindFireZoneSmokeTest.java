package ua.zentix.incoming.client.nuclear;

import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;
import ua.zentix.incoming.nuclear.Detonation;
import ua.zentix.incoming.nuclear.model.SmokeParcelsTest;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The fires grow alike over the whole fire zone on the strike clock, so smoke stands over every burning part of it: over the
 * zone the haze is the smoke leaving now, downwind and upwind alike (a point read at shift = its downwind distance showed smoke
 * that left down / wind ago, none on the downwind half while the fires grew; 15 kt, 2.5 m/s, 24-hour day).
 */
class DownwindFireZoneSmokeTest {
    @Test
    void smokeStandsOverTheWholeBurningZone() {
        Detonation air = new Detonation(5, new Vec3(0, 664, 0), 64, 15, false, 0, 0f, 2.5f, 20_000, 0, 1f, true, 0.25f, 0L, true);
        PallField p = new PallField(air);
        p.parcels(t -> SmokeParcelsTest.day(1, t / 20.0));
        double r = p.fireReach();
        StringBuilder bad = new StringBuilder();
        for (double t : new double[]{600, 900, 1200}) {
            for (double f : new double[]{0.5, 0.75}) {
                double up = p.hazeSigma(-f * r, 0, 20 * t), down = p.hazeSigma(f * r, 0, 20 * t);
                if (down < 0.5 * up)
                    bad.append(String.format(java.util.Locale.ROOT, "%n%.0f s, %.0f m from ground zero: upwind %.3g, downwind %.3g /block",
                            t, f * r, up, down));
            }
        }
        assertTrue(bad.isEmpty(), "fire zone " + Math.round(r) + " m; downwind half of the burning zone without its smoke:" + bad);
    }
}
