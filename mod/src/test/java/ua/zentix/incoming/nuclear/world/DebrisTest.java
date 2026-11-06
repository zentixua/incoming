package ua.zentix.incoming.nuclear.world;

import org.junit.jupiter.api.Test;
import ua.zentix.incoming.nuclear.model.BlastModel;

import static org.junit.jupiter.api.Assertions.*;

class DebrisTest {
    /**
     * 5 psi wind ≈ 72 m/s: masonry from a 10 m storey lands a few metres downwind (15 kt phase 0.7 s), wood farther;
     * 20 psi with a megaton's 2.5 s phase carries it out of the chunk ring; 1 psi barely moves it.
     */
    @Test
    void driftGrowsWithWindHeightAndLightness() {
        double u5 = BlastModel.windSpeed(BlastModel.kpa(5)), u20 = BlastModel.windSpeed(BlastModel.kpa(20)), u1 = BlastModel.windSpeed(BlastModel.kpa(1));
        double low = Debris.driftMetres(0, u5, 0.7, Debris.MASONRY), storey = Debris.driftMetres(10, u5, 0.7, Debris.MASONRY);
        assertTrue(low > 0 && low < storey, low + " vs " + storey);
        assertTrue(storey > 2 && storey < Debris.REACH, "10 m masonry at 5 psi drifts " + storey + " m");
        assertTrue(Debris.driftMetres(10, u5, 0.7, Debris.WOOD) > 2 * storey, "wood drifts farther");
        assertTrue(Debris.driftMetres(10, u20, 2.5, Debris.MASONRY) > Debris.REACH, "20 psi exports");
        assertTrue(Debris.driftMetres(10, u1, 2.5, Debris.MASONRY) < 1, "1 psi barely moves masonry");
        assertEquals(0, Debris.driftMetres(10, 0, 2, Debris.MASONRY));
    }

    /** The fragment never outruns the peak wind: drift at most peak wind × fall time. */
    @Test
    void driftNeverExceedsWindRun() {
        for (double u = 10; u < 600; u *= 1.7) {
            for (double h = 0; h < 200; h += 13) {
                double fall = Math.sqrt(2 * h / 9.81) + Debris.SKID_S;
                assertTrue(Debris.driftMetres(h, u, 10, Debris.WOOD) <= u * fall + 1e-9);
            }
        }
    }

    /** Swept near the epicentre, balanced far out. */
    @Test
    void importShareFollowsRingGeometry() {
        assertEquals(0, Debris.importFraction(50, 80));
        assertEquals(0.5, Debris.importFraction(100, 50), 1e-12);
        assertTrue(Debris.importFraction(3000, 10) > 0.99);
        assertTrue(Debris.spread(0) >= 0.5 && Debris.spread(1000) == Debris.SPREAD_MAX);
    }
}
