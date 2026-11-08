package ua.zentix.incoming.client.fx.layer;

import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Light carried by entities over OpenAir's window ({@link MovingLight}): the box goes into the texture whole and once
 * wherever the window wraps, and the level between grid points is LambDynamicLights' level at the points, linear between.
 */
class MovingLightTest {
    /** Every block of the box in exactly one piece, each piece inside the texture without wrapping, at any box position. */
    @Test
    void boxGoesInOnceWhereverTheWindowWraps() {
        for (int x : new int[]{-200, -97, -96, -95, -17, -9, 0, 80, 87, 88, 95, 96, 1000}) {
            for (int y : new int[]{-64, -60, -10, 50, 56, 63, 64, 300}) {
                int z = 3 * x + 7;
                int[] seen = new int[MovingLight.SIZE * MovingLight.SIZE * MovingLight.SIZE];
                MovingLight.split(x, y, z, (px, py, pz, w, h, d) -> {
                    assertTrue(w > 0 && h > 0 && d > 0, "empty piece");
                    assertTrue(OpenAir.slot(px, OpenAir.SIZE_XZ) + w <= OpenAir.SIZE_XZ, "x piece wraps at " + px + " + " + w);
                    assertTrue(OpenAir.slot(py, OpenAir.SIZE_Y) + h <= OpenAir.SIZE_Y, "y piece wraps at " + py + " + " + h);
                    assertTrue(OpenAir.slot(pz, OpenAir.SIZE_XZ) + d <= OpenAir.SIZE_XZ, "z piece wraps at " + pz + " + " + d);
                    for (int k = 0; k < d; k++) for (int j = 0; j < h; j++) for (int i = 0; i < w; i++) {
                        int bi = px + i - x, bj = py + j - y, bk = pz + k - z;
                        seen[(bk * MovingLight.SIZE + bj) * MovingLight.SIZE + bi]++;
                    }
                });
                int[] once = new int[seen.length];
                Arrays.fill(once, 1);
                assertTrue(Arrays.equals(once, seen), "box at " + x + " " + y + " " + z + " not covered once");
            }
        }
    }

    /** Grid points read back exactly; a linear field (as LambDynamicLights' falloff along a line) reads exactly between them. */
    @Test
    void levelIsLinearBetweenGridPoints() {
        int n = MovingLight.POINTS;
        for (int k = 0; k < n; k++) for (int j = 0; j < n; j++) for (int i = 0; i < n; i++) {
            MovingLight.GRID[(k * n + j) * n + i] = 15 - 0.5f * i - 0.25f * j - 0.125f * k;
        }
        for (int k = 0; k < MovingLight.SIZE; k++) for (int j = 0; j < MovingLight.SIZE; j++) for (int i = 0; i < MovingLight.SIZE; i++) {
            float expected = 15 - 0.5f * i / MovingLight.STEP - 0.25f * j / MovingLight.STEP - 0.125f * k / MovingLight.STEP;
            assertEquals(expected, MovingLight.at(i, j, k), 1e-4, "block " + i + " " + j + " " + k);
        }
    }
}
