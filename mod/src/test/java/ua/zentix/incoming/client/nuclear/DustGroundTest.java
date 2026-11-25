package ua.zentix.incoming.client.nuclear;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The dust wave's ground around the eye ({@link DustGround}): step 7 measured a 1 Mt detonation's own map (508 m cells, 4–13 of
 * 16 384 known) standing 10 m over the eye in a valley, the drawn ground 13 m under the dust's floor and no dust on it. The eye's
 * tiles must put the floor on the real ground over the drawn chunks, keep it there as the eye moves, and hand over to the
 * detonation's map without a step.
 */
class DustGroundTest {
    /** A valley 40 m deep and 300 m wide along x, ridges at 110: the Silo valley spot's relief (sd 27 m within 400 m). */
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

    private static void sweep(double ex, double ey, double ez, double known) {
        for (int i = 0; i < 40; i++) {
            DustGround.update((x, z) -> Math.hypot(x - ex, z - ez) <= known ? Math.floor(valley(x, z)) : Double.NaN, ex, ey, ez, 12, i == 39);
        }
    }

    @Test
    void theFloorIsTheRealGroundNotTheDetonationsMap() {
        sweep(0, 71.6, 0, 192);
        // the detonation's map 10 m above the eye (the measured valley case)
        double map = 81.6;
        for (double x = -150; x <= 150; x += 7.3) {
            double floor = DustGround.floor(x, 3.1, map), real = Math.floor(valley(x, 3.1));
            assertEquals(real, floor, 3.0, "floor at x " + x);
        }
    }

    @Test
    void beyondWhatTheClientKnowsTheMapTakesOverWithoutAStep() {
        sweep(0, 71.6, 0, 192);
        double map = 90, last = DustGround.floor(150, 0, map);
        double biggest = 0;
        for (double x = 150; x <= 3000; x += 2) {
            double f = DustGround.floor(x, 0, map);
            biggest = Math.max(biggest, Math.abs(f - last));
            last = f;
        }
        assertEquals(map, DustGround.floor(3000, 0, map), 1e-6, "outside both tiles: the detonation's map");
        assertTrue(biggest < 1.0, "largest change over 2 m: " + biggest);
    }

    @Test
    void movingTheEyeKeepsWhatWasKnown() {
        sweep(0, 71.6, 0, 192);
        double before = DustGround.floor(-100, 0, 0);
        // the eye walks 100 m east (the spot stays inside the fine tile); nothing new is learned: the cells it saw keep their heights
        for (int i = 0; i < 40; i++) DustGround.update((x, z) -> Double.NaN, 100, 90, 0, 12, i == 39);
        assertEquals(before, DustGround.floor(-100, 0, 0), 0.25);
    }

    /**
     * The relief search past the drawn chunks ({@code dwRayFloor}) on a floor: a horizontal or slightly lowered ray from the eye
     * down a valley 300 m wide, the source's 508 m map a cell's mean (the ridges' height over the valley floor). The ray must end
     * where it meets the floor the dust lies on: searched on the map alone it ended 56–70 m past the drawn chunks and cut away the
     * dust the coarse tile lays on the valley floor beyond.
     */
    private static double rayEnd(java.util.function.DoubleUnaryOperator floorAlong, double eyeY, double pitch, double from, double fade) {
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

    @Test
    void raysPastTheDrawnChunksEndOnTheDustsFloor() throws IOException {
        // the eye in the valley, looking along it (the valley runs along z): the coarse tile knows the floor 192 m around the eye
        // and continues it 128 m past; the map stands over the valley floor by its cell's mean
        sweep(0, 71.62, 0, 192);
        double map = 0;
        for (double x = -254; x <= 254; x += 8) map += Math.floor(valley(x, 0)) / 64;
        final double cellMean = map;
        java.util.function.DoubleUnaryOperator floor = z -> DustGround.floor(0, z, cellMean);
        double pitch = Math.toRadians(-0.2), onFloor = rayEnd(floor, 71.62, pitch, 168, 4 * 508);
        // where the ray first meets the dust's floor (1 m steps past the drawn chunks)
        double meets = 168;
        while (meets < 4000 && 71.62 + Math.sin(pitch) * meets > floor.applyAsDouble(Math.cos(pitch) * meets)) meets++;
        assertEquals(meets, onFloor, 0.05 * meets, "the ray ends where it meets the floor the dust lies on");
        String s = Files.readString(Path.of("src/main/resources/assets/incoming/shaders/include/dustwall.glsl"));
        String search = s.substring(s.indexOf("float dwRayFloor("), s.indexOf("return 1e9;\n}", s.indexOf("float dwRayFloor(")));
        assertTrue(search.contains("q.y - dwFloorCoarse(q.xz)") && search.contains("q0.y - dwFloorCoarse(q0.xz)"),
                "the shader's relief search reads the floor the dust lies on");
    }

    @Test
    void theShaderBlendsAsTheJavaCopy() throws IOException {
        String s = Files.readString(Path.of("src/main/resources/assets/incoming/shaders/include/dustwall.glsl"));
        assertTrue(s.contains("DW_GROUND_SIDE - 1.0 - u.y)) / 8.0"), "edge fade over 8 cells (DustGround.edge)");
        assertTrue(s.contains("mix(dwFloorMap(xz), c.x, c.y)") && s.contains("mix(dwFloorCoarse(xz), f.x, f.y)"),
                "map, then coarse, then fine (DustGround.floor)");
        assertEquals(1f, DustGround.edge(8, 64));
        assertEquals(0.5f, DustGround.edge(4, 64));
    }
}
