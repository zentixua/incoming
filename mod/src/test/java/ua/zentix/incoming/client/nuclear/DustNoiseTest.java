package ua.zentix.incoming.client.nuclear;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@link DustNoise}'s swell field on the lattice is {@code dustwall.glsl}'s {@code dwSwellField}: the same points, ages and
 * values as {@code tools/dust_wall_gl.py}'s noise case, which renders the shader's on the uploaded lattice (Mesa llvmpipe).
 */
class DustNoiseTest {
    /** (x, z, age; swell, large-scale value L) — the shader's, rendered by the harness. */
    static final double[][] POINTS = {
            {1564, 0, 300, 0.177846, 0.647493},
            {1500, 120, 60, 0.247608, 0.750244},
            {-800, 2300, 600, -0.156478, 0.375046},
            {3100, -410, 30, 0.166536, 0.635245},
            {220, -75, 1200, -0.048085, 0.465325},
            {0, 0, 5, 0.042303, 0.530606},
            {777, 777, 120, -0.411438, 0.491826},
            {-2500, -1300, 3000, -0.059747, 0.456688}};

    @Test
    void swellIsTheShaders() {
        double[] l = new double[1];
        for (double[] p : POINTS) {
            double swell = DustNoise.swell(p[0], p[1], p[2], l);
            assertEquals(p[3], swell, 1e-6, "swell at " + p[0] + ", " + p[1] + ", " + p[2] + " s");
            assertEquals(p[4], l[0], 1e-6, "L at " + p[0] + ", " + p[1] + ", " + p[2] + " s");
        }
    }

    /** The lattice is the fixed-seed one, x fastest: its first values (seed 0xD057). */
    @Test
    void latticeIsFixed() {
        byte[] v = DustNoise.values();
        assertEquals(DustNoise.SIDE * DustNoise.SIDE * DustNoise.SIDE, v.length);
        byte[] again = new byte[16];
        new java.util.Random(0xD057L).nextBytes(again);
        for (int i = 0; i < 16; i++) assertEquals(again[i], v[i]);
    }
}
