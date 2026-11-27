package ua.zentix.incoming.client.fx.layer;

import org.junit.jupiter.api.Test;
import ua.zentix.incoming.client.nuclear.GlslFunction;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The water decal's height band ({@code fx_water.fsh} {@code surfaceBand}): every height a shader pack's waves give the
 * still-water top is water, block tops beside it are not. Photon 1.4 raised its crests above the band, and the dark nuclear
 * ring left them undarkened in straight light lines along z (15 kt clip from a lake shore, 6–35 s).
 */
class WaterDecalGlslTest {
    private static final Path FSH = Path.of("src/main/resources/assets/incoming/shaders/core/fx_water.fsh");
    /** {@code fx_water.fsh}: the depth error near the eye and at the draw edge, blocks. */
    private static final double NEAR_TOL = 0.01, FAR_TOL = 0.037;
    /** Jitter allowance at the draw edge: {@link WaterDecal#PACK_JITTER} pixels × a 70° frame's pixel angle at 1080 rows × 400 m. */
    private static final double FAR_JITTER = WaterDecal.PACK_JITTER * Math.toRadians(70) / 1080 * 400;

    private static double band(double h, boolean pack, double tol, double jitter) throws IOException {
        GlslFunction f = GlslFunction.parse(Files.readString(FSH).replaceAll("//[^\n]*", ""), "surfaceBand");
        return f.call(Map.of("h", h, "tol", tol, "jitter", jitter, "drop", pack ? (double) WaterDecal.PACK_DROP : 0.0,
                "rise", pack ? (double) WaterDecal.PACK_RISE : 0.0));
    }

    /** Photon 1.4 water-top displacement, blocks ({@code include/vertex/displacement.glsl}): (wave·0.05 − 0.025)·(0.9·skylight + 0.1). */
    private static double photon(double wave, double skylight) {
        return (wave * 0.05 - 0.025) * (skylight * 0.9 + 0.1);
    }

    @Test
    void photonCrestsAndTroughsAreWater() throws IOException {
        for (double wave = 0; wave <= 1.0001; wave += 0.05) {
            for (double sky : new double[] {0, 0.5, 1}) {
                double h = photon(wave, sky);
                assertEquals(1, band(h, true, NEAR_TOL, 0), 0, "Photon wave " + wave + " sky " + sky + ": " + h);
                assertEquals(1, band(h, true, FAR_TOL, FAR_JITTER), 0, "far, Photon wave " + wave + " sky " + sky);
            }
        }
    }

    @Test
    void complementaryTroughsStayWater() throws IOException {
        // WAVING_WATER_VERTEX: −0.05 ± 0.025 under open sky, −0.05 under a roof
        for (double h = -0.075; h <= -0.025; h += 0.005) assertEquals(1, band(h, true, NEAR_TOL, 0), 0, "Complementary " + h);
    }

    @Test
    void neighbouringTopsAreNotWater() throws IOException {
        // without a pack: the still-water top only; a chest or 7 snow layers (14/16) and a farmland or path (15/16) are not water
        assertEquals(1, band(0, false, NEAR_TOL, 0), 0);
        assertEquals(0, band(14.0 / 16 - 8.0 / 9, false, NEAR_TOL, 0), 0);
        assertEquals(0, band(15.0 / 16 - 8.0 / 9, false, NEAR_TOL, 0), 0);
        // under a pack up close: farmland and path tops stay out
        assertEquals(0, band(15.0 / 16 - 8.0 / 9, true, NEAR_TOL, 0), 0);
        // at the draw edge a full block top read slightly low wraps to +1/9 above the water top: never water
        assertEquals(0, band(1 - 8.0 / 9 - 0.01, true, FAR_TOL, FAR_JITTER), 0);
    }
}
