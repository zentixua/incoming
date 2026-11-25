package ua.zentix.incoming.client.nuclear;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.Locale;
import java.util.function.DoubleUnaryOperator;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Rays past the drawn chunks end on the dust's floor ({@code dwRayFloor} on {@code dwFloorCoarse}); past what the client knows
 * that floor is the ground where it was last seen. The eye stands in {@link DustGroundTest}'s valley (300 m wide, 40 m deep)
 * looking along it, the detonation's map a 508 m cell's mean: in vanilla (the client knows its 12 chunks) and with an LOD map
 * (the coarse tile knows its whole 4 km) a level ray runs on down the valley, as over the real floor. A floor blended into the
 * map from the known edge on ended it 14–31 m past the drawn chunks with a third of the sky left.
 */
class DustGroundRayEndTest {
    private static double valley(double x, double z) {
        return 70 + 40 * (1 - Math.exp(-Math.pow(x / 150, 2)));
    }

    @org.junit.jupiter.api.BeforeEach
    void unbounded() {
        DustGround.surveyNanos = Long.MAX_VALUE;
    }

    @AfterEach
    void clear() {
        DustGround.reset();
        DustGround.surveyNanos = DustGround.SURVEY_NANOS;
    }

    /** dwRayFloor as DustGroundTest.rayEnd (horizontal start 168 = floorFrom at 12 chunks, fade 4 map cells). */
    private static double rayEnd(DoubleUnaryOperator floorAlong, double eyeY, double pitch, double from, double fade) {
        double dy = Math.sin(pitch), dz = Math.cos(pitch);
        double ta = from / Math.max(Math.abs(dz), Math.abs(dy)), tb = 4000, ratio = Math.pow(tb / ta, 1 / 64.0);
        double d0 = eyeY + dy * ta - floorAlong.applyAsDouble(dz * ta), lift = Math.max(1 - d0, 0), prevT = ta, prevD = d0 + lift;
        double t = ta * Math.sqrt(ratio);
        for (int i = 0; i <= 64; i++) {
            double tt = Math.min(t, tb), d = eyeY + dy * tt - floorAlong.applyAsDouble(dz * tt) + lift * Math.max(1 - (tt - ta) / fade, 0);
            if (d <= 0) return prevT + (tt - prevT) * Math.max(prevD, 0) / Math.max(prevD - d, 1e-6);
            prevT = tt;
            prevD = d;
            t *= ratio;
        }
        return Double.POSITIVE_INFINITY;
    }

    /** The level ray's end; asserts every ray (level, lowered, raised) ends no nearer than 0.8 of where the real floor ends it. */
    private static double run(String name, double knownRadius) {
        DustGround.reset();
        for (int i = 0; i < 40; i++) {
            DustGround.update((x, z) -> Math.hypot(x, z) <= knownRadius ? Math.floor(valley(x, z)) : Double.NaN, 0, 71.62, 0, 12, i == 39);
        }
        double map = 0;
        for (double x = -254; x <= 254; x += 8) map += Math.floor(valley(x, 0)) / 64;
        final double cellMean = map;
        DoubleUnaryOperator floor = z -> DustGround.floor(0, z, cellMean);
        double level = 0;
        for (double deg : new double[] {0, -0.2, 0.5, 1, 2}) {
            double p = Math.toRadians(deg), end = rayEnd(floor, 71.62, p, 168, 4 * 508);
            // the real valley floor: a level or raised ray never meets it within the coarse tile, a lowered one at 1.62 / sin, or
            // 0.5 m sooner on the coarse tile's 32 m mean of the floor's blocks (a 0.2° ray meets that 115 m sooner)
            double real = deg < 0 ? (1.62 - 0.5) / -Math.sin(p) : 2000;
            assertTrue(end >= 0.8 * real, String.format(Locale.ROOT, "%s, pitch %+.1f: the ray ends at %.0f m, the real floor at %.0f m",
                    name, deg, end, real));
            if (deg == 0) level = end;
        }
        return level;
    }

    @Test
    void lodMapRaysRunDownTheValley() {
        double end = run("LOD", 1e9);
        assertTrue(end > 1500, "with an LOD map the level ray runs down the valley: " + end);
    }

    @Test
    void vanillaRaysRunDownTheValley() {
        double end = run("vanilla", 192);
        assertTrue(end > 1500, "vanilla, the level ray along the valley ends at " + end + " m");
    }
}
