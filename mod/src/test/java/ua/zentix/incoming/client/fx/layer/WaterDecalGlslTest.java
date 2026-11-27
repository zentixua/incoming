package ua.zentix.incoming.client.fx.layer;

import org.junit.jupiter.api.Test;
import ua.zentix.incoming.client.nuclear.GlslFunction;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

    /** A common player frame: 70° vertical FOV over 1080 rows. */
    private static final double PIXEL = Math.toRadians(70) / 1080;

    /** The band under a pack at {@code len} m, eye {@code eyeAbove} m above the surface: tol and jitter as {@code main()} computes them. */
    private static double seen(double h, double len, double eyeAbove) throws IOException {
        double horizontal = Math.sqrt(Math.max(0, len * len - eyeAbove * eyeAbove));
        double tol = Math.clamp(0.01 + 2.4e-6 * len * eyeAbove, 0.01, 0.037);
        return band(h, true, tol, WaterDecal.PACK_JITTER * PIXEL * horizontal);
    }

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
    void crestCeilingIsBelowFarmlandTops() throws IOException {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("const float CREST_CEILING = ([0-9.]+);").matcher(Files.readString(FSH));
        assertTrue(m.find());
        assertEquals(15.0 / 16 - 8.0 / 9 - 0.004, Double.parseDouble(m.group(1)), 1e-5);
    }

    /**
     * Photon's crests (+0.025) are water at every distance: up close under the ceiling, farther inside the band's own error
     * (once the ceiling falls below a crest, depth error and jitter alone already exceed it).
     */
    @Test
    void photonCrestsAreWaterAtEveryDistance() throws IOException {
        for (double d = 5; d <= 400; d += 5)
            for (double eye : new double[] {1.7, 10, 30}) assertEquals(1, seen(photon(1, 1), d, eye), 0, "crest at " + d + " m, eye " + eye);
    }

    /** Farmland and dirt path tops (15/16) across a field, 2 m eye height, 20–250 m, read exactly, are not water. */
    @Test
    void farmlandAcrossAFieldIsNotWater() throws IOException {
        double h = 15.0 / 16 - 8.0 / 9;
        for (double d = 20; d <= 250; d += 10) assertEquals(0, seen(h, d, 2), 0, "farmland at " + d + " m is taken as water");
    }

    /**
     * A 15/16 top read low within the band's own error (the depth error beyond 0.01, or the pack's jitter) is water under a pack
     * only where it is water without one: the crest margin never admits it (eye 2, 10 and 30 m, 20–400 m).
     */
    @Test
    void farmlandReadWithinTheBandsOwnErrorIsNotWater() throws IOException {
        double farmland = 15.0 / 16 - 8.0 / 9;
        for (double eye : new double[] {2, 10, 30})
            for (double d = 20; d <= 400; d += 10) {
                double horizontal = Math.sqrt(d * d - eye * eye), tol = Math.clamp(0.01 + 2.4e-6 * d * eye, 0.01, 0.037);
                double jitter = WaterDecal.PACK_JITTER * PIXEL * horizontal;
                for (double low : new double[] {tol - 0.01, 0.5 * jitter, tol - 0.01 + jitter}) {
                    double h = farmland - low;
                    double without = band(h, true, tol, jitter) > 0.5 && pack(h, tol, jitter, 0) < 0.5 ? 1 : 0;
                    assertEquals(0, without, "farmland read low by " + low + " at " + d + " m, eye " + eye + " is water only under the crest margin");
                }
            }
    }

    private static double pack(double h, double tol, double jitter, double rise) throws IOException {
        GlslFunction f = GlslFunction.parse(Files.readString(FSH).replaceAll("//[^\n]*", ""), "surfaceBand");
        return f.call(Map.of("h", h, "tol", tol, "jitter", jitter, "drop", (double) WaterDecal.PACK_DROP, "rise", rise));
    }

    /** A full block top at the draw edge (400 m, eye 30 m above: tol 0.037) read low by that tolerance is not water. */
    @Test
    void fullBlockTopReadLowByTheBandsOwnToleranceIsNotWater() throws IOException {
        assertEquals(0, seen(1 - 8.0 / 9 - 0.037, 400, 30), 0);
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
        // at the draw edge a full block top read low wraps to +1/9 above the water top: never water
        assertEquals(0, band(1 - 8.0 / 9 - FAR_TOL, true, FAR_TOL, FAR_JITTER), 0);
    }
}
