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

    @AfterEach
    void clear() {
        DustGround.reset();
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
        assertEquals(before, DustGround.floor(-100, 0, 0), 1e-3);
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
