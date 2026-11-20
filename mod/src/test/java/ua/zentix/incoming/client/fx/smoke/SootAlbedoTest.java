package ua.zentix.incoming.client.fx.smoke;

import org.junit.jupiter.api.Test;
import ua.zentix.incoming.client.fx.plume.Smoke;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Soot of the smoke grid ({@link CloudModel#SOOT_W}): every soot path (blast clouds, fuel fires, engine products through
 * {@link Matter}) takes its albedo from the one sourced constant.
 */
class SootAlbedoTest {
    @Test
    void albedoIsThatOfLargeFuelFireSmokeNearTheSource() {
        // Weiss & Hobbs 1992: 0.35–0.4 at 538 nm in the black plumes of single large oil fires
        assertTrue(CloudModel.SOOT_W >= 0.35 && CloudModel.SOOT_W <= 0.4, "albedo " + CloudModel.SOOT_W);
        // extinction 10 m²/g of fresh smoke
        assertEquals(1e4, CloudModel.SOOT_K, 0);
    }

    @Test
    void gridSootIsTheDeltaEddingtonFormOfTheSameMatter() {
        double f = CloudModel.SOOT_G * CloudModel.SOOT_G;
        assertEquals((1 - f) * CloudModel.SOOT_W / (1 - f * CloudModel.SOOT_W), SmokeGrid.SOOT_W, 1e-6);
        assertEquals(CloudModel.SOOT_K * (1 - CloudModel.SOOT_W * f), SmokeGrid.SOOT_K, 1e-3);
        // scaled extinction × (1 − scaled albedo) is the absorption: delta-Eddington keeps it
        assertEquals(CloudModel.SOOT_K * (1 - CloudModel.SOOT_W), SmokeGrid.SOOT_K * (1 - SmokeGrid.SOOT_W), 1e-2);
    }

    @Test
    void engineProductsKeepOpticalDepthAndAlbedo() {
        double[] out = new double[2];
        // grey-white salts: a soot/droplet mixture with the product's optical depth and albedo
        Matter.of(Smoke.SALTS, 1, out);
        double soot = out[0] * CloudModel.SOOT_K, drops = out[1] * CloudModel.DROP_K;
        assertEquals(Smoke.SALTS.extinction, soot + drops, 1e-6);
        assertEquals(Smoke.SALTS.albedo, (soot * CloudModel.SOOT_W + drops) / (soot + drops), 1e-9);
        // engine soot, darker than grid soot: all of it soot, the same optical depth
        Matter.of(Smoke.SOOT, 1, out);
        assertEquals(Smoke.SOOT.extinction, out[0] * CloudModel.SOOT_K, 1e-6);
        assertEquals(0, out[1], 0);
    }
}
