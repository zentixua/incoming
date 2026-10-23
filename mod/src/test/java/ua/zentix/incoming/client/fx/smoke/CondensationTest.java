package ua.zentix.incoming.client.fx.smoke;

import org.junit.jupiter.api.Test;
import ua.zentix.incoming.nuclear.model.BlastModel;
import ua.zentix.incoming.nuclear.model.ChemicalFront;

import static org.junit.jupiter.api.Assertions.*;

class CondensationTest {
    @Test
    void saturationRequiresCoolingBelowTheDewPoint() {
        assertEquals(1702, Condensation.saturation(288.15), 5);
        assertTrue(Condensation.temperature(20, -20000 / Math.exp(2)) < 287);
        assertEquals(0, Condensation.water(20, -20000 / Math.exp(2), 0.5));
        double wet = Condensation.water(20, -20000 / Math.exp(2), 1);
        assertTrue(wet > 0.0001 && wet < 0.001, "latent heat limits condensed water: " + wet);
        assertEquals(0, Condensation.water(20, 20000, 1), "compressed air does not condense");
        assertEquals(0, Condensation.water(20, 0, 1), "ambient pressure evaporates the droplets");
    }

    @Test
    void rainCloudAppearsBehindCompressionAndEvaporates() {
        ChemicalFront f = ChemicalFront.cached(50, true);
        Condensation wet = new Condensation(50, true, 1), dry = new Condensation(50, true, 0.1);
        assertTrue(wet.reach > 20 && wet.life > 0.1);
        assertEquals(0, dry.reach);
        for (double r : new double[]{20, 50, 100}) {
            double arrive = f.arrivalSeconds(r), plus = BlastModel.chemicalPhaseSeconds(r, 50, true);
            assertEquals(0, wet.density(r, arrive));
            assertEquals(0, wet.density(r, arrive + 0.9 * plus));
            assertTrue(wet.density(r, arrive + 2 * plus) > 0);
            assertEquals(0, wet.density(r, arrive + 8 * plus));
        }
    }

    @Test
    void renderedShellCannotPrecedeTheShock() {
        ChemicalFront f = ChemicalFront.cached(50, true);
        Condensation cloud = new Condensation(50, true, 1);
        int[] count = {0};
        for (double t = 0.001; t <= cloud.life; t += 0.005) {
            double front = f.radiusAt(t);
            cloud.blobs(t, (x, y, z, sh, sv, soot, dust, water, temp, rise) -> {
                count[0]++;
                assertTrue(Math.sqrt(x*x + y*y + z*z) + 4*sh <= front + 1e-8);
                assertTrue(water >= 0 && sh > 0);
            });
        }
        assertTrue(count[0] > 100, "rain produces an actual shell");
    }
}
