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
 * Diffuse sky light on the baked puffs ({@code fx.fsh}: {@code skyDome}): the sky over the upper hemisphere, the ground's
 * reflection under it, so the underside of black smoke is darker than its top (a plain six-side mean lights it from the
 * ground as brightly as the sky lights the top).
 */
class SkyDomeGlslTest {
    private static final Path FX = Path.of("src/main/resources/assets/incoming/shaders/core/fx.fsh");

    private static double dome(GlslFunction f, double[] maps, double[] up) {
        // maps: +x, −x, +y, −y, +z, −z (a.r, b.r, a.g, b.g, b.b, a.b)
        double sum = 0;
        for (double m : maps) sum += m;
        double tilt = up[0] * (maps[0] - maps[1]) + up[1] * (maps[2] - maps[3]) + up[2] * (maps[4] - maps[5]);
        return f.call(Map.of("sum", sum, "tilt", tilt));
    }

    @Test
    void uniformLightKeepsTheSixSideMean() throws IOException {
        GlslFunction f = GlslFunction.parse(Files.readString(FX).replaceAll("//[^\n]*", ""), "skyDome");
        double[] up = {0.3, 0.8, Math.sqrt(1 - 0.09 - 0.64)};
        assertEquals(0.7, dome(f, new double[]{0.7, 0.7, 0.7, 0.7, 0.7, 0.7}, up), 1e-9);
    }

    @Test
    void theUndersideSeesOnlyTheGround() throws IOException {
        GlslFunction f = GlslFunction.parse(Files.readString(FX).replaceAll("//[^\n]*", ""), "skyDome");
        double[] up = {0, 1, 0};
        double top = dome(f, new double[]{0, 0, 1, 0, 0, 0}, up), bottom = dome(f, new double[]{0, 0, 0, 1, 0, 0}, up),
                side = dome(f, new double[]{1, 0, 0, 0, 0, 0}, up);
        // top against bottom — sky against its reflection by the ground (albedo 0.2)
        assertEquals(0.2, bottom / top, 1e-6);
        // a side sees half the sky and half the ground: the six-side mean
        assertEquals(1 / 6.0, side, 1e-9);
        assertTrue(top > side && side > bottom);
        // the same for any orientation of the puff's axes: up along map −z (a lying puff seen from below)
        double[] down = {0, 0, -1};
        assertEquals(top, dome(f, new double[]{0, 0, 0, 0, 0, 1}, down), 1e-9);
    }

    @Test
    void theVertexShaderGivesTheUpDirection() throws IOException {
        String vsh = Files.readString(Path.of("src/main/resources/assets/incoming/shaders/core/fx.vsh"));
        assertTrue(vsh.contains("sixUpDir = puffDirection(vec3(0.0, 1.0, 0.0), axes, UV2.y < 0);"));
        assertTrue(Files.readString(FX).contains("dot(sixUpDir, tilt)"));
    }
}
