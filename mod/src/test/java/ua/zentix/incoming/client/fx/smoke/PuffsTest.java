package ua.zentix.incoming.client.fx.smoke;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Smoke emissions: thermal rise, mass, life in thickness, general calculation of thermals, wave duration and end of the source. */
class PuffsTest {
    /** billows one frame. */
    private record Blob(double y, double sh, double soot, double dust) {}

    private static List<Blob> frame(Puffs p, double seconds) {
        List<Blob> out = new ArrayList<>();
        p.emit(seconds, (x, y, z, ax, ay, az, sh, sv, soot, dust, water, r, g, b, kelvin, rise) -> out.add(new Blob(y, sh, soot, dust)));
        return out;
    }

    /**
     * Burning debris (as in {@code Exhaust.debris}): for 0,1 s - 17,5 kJ of wood fire, 70% - into the air, soot
     * 0,015 kg per 12,4 MJ. The ejection floats up with its heat, grows and melts in seconds; soot is the one that burned.
     */
    @Test
    void debrisPuffRisesAndFades() {
        Puffs p = new Puffs(CloudModel.Air.STANDARD, 0.2f, 0.2f, 0.2f, 1);
        double watts = FirePlume.BLOCK_WATTS * 0.1;
        p.add(0, 10, 0, 0, FirePlume.CONVECTIVE * watts, 800, 0.5, FirePlume.SOOT_PER_JOULE * watts, 0, 0, Double.NaN);
        assertEquals(1, p.size());
        Blob a = frame(p, 0.5).getFirst(), b = frame(p, 3).getFirst();
        assertEquals(FirePlume.SOOT_PER_JOULE * watts, a.soot, 1e-12, "soot - burnt wood");
        assertTrue(b.y > a.y, "pops up: " + a.y + " → " + b.y);
        assertTrue(b.sh > a.sh, "growing: " + a.sh + " → " + b.sh);
        // melts when the thickness across the middle is below the threshold: a gram of soot has seconds, not minutes
        List<Blob> late = frame(p, 120);
        assertTrue(late.isEmpty(), "still visible after 2 minutes");
    }

    /** The smoldering aerosol becomes dust with the same attenuation: the thickness of the billows is its mass × its attenuation. */
    @Test
    void aerosolKeepsItsOwnExtinction() {
        Puffs p = new Puffs(CloudModel.Air.STANDARD, 0.71f, 0.68f, 0.65f, 2);
        double kg = 0.2, k = 4.4e3;
        p.add(0, 0, 0, 0, 1e6, 600, 3, 0, kg, k, Double.NaN);
        Blob b = frame(p, 1).getFirst();
        float w = SmokeGrid.dustAlbedo(0.68f);
        assertEquals(kg * k, b.dust * SmokeGrid.dustK(SmokeGrid.dustAlbedo(0.71f), w, SmokeGrid.dustAlbedo(0.65f)), 1e-6 * kg * k);
    }

    /** The wave sweeps away the smoke: after the deadline there is no release. A source without new emissions ends on its own when the released ones have melted. */
    @Test
    void waveEndsThePuffAndTheSourceEndsByItself() {
        Puffs p = new Puffs(CloudModel.Air.STANDARD, 0.7f, 0.7f, 0.7f, 3);
        p.add(0, 0, 0, 0, 1e6, 600, 3, 0, 0.2, 4.4e3, 2.0);
        assertEquals(1, frame(p, 0.5).size());
        assertFalse(p.ended(), "just fed, release alive");
        assertEquals(1, frame(p, 1.9).size());
        assertTrue(frame(p, 2.1).isEmpty(), "the wave passed - the smoke remained");
        assertTrue(p.ended(), "without emissions longer than QUIET and without living ones - the end");
        // thinner than the threshold from the very beginning - not an outlier
        p = new Puffs(CloudModel.Air.STANDARD, 0.7f, 0.7f, 0.7f, 4);
        p.add(0, 0, 0, 0, 1e3, 600, 3, 1e-9, 0, 0, Double.NaN);
        assertEquals(0, p.size());
    }

    /** Thermals of close buoyancy and radius - one calculation (thousands of steps RK4 for release would cost frames). */
    @Test
    void similarPuffsShareOneThermal() {
        Thermal a = Puffs.thermal(CloudModel.Air.STANDARD, 1.0e5, 800, 0.5), b = Puffs.thermal(CloudModel.Air.STANDARD, 1.1e5, 810, 0.52);
        assertSame(a, b);
    }
}
