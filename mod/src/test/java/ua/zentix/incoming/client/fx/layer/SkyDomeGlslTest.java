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
 * reflection under it: the underside of black smoke sees only the ground's dimmer reflection, not the sky.
 */
class SkyDomeGlslTest {
    private static final Path FX = Path.of("src/main/resources/assets/incoming/shaders/core/fx.fsh");

    // fx.fsh imports PALL_LAND from pall.glsl
    private static GlslFunction skyDome() throws IOException {
        String source = Files.readString(Path.of("src/main/resources/assets/incoming/shaders/include/pall.glsl")) + "\n"
                + Files.readString(FX);
        return GlslFunction.parse(source.replaceAll("//[^\n]*", ""), "skyDome");
    }

    private static double dome(GlslFunction f, double[] maps, double[] up) {
        // maps: +x, −x, +y, −y, +z, −z (a.r, b.r, a.g, b.g, b.b, a.b)
        double sum = 0;
        for (double m : maps) sum += m;
        double tilt = up[0] * (maps[0] - maps[1]) + up[1] * (maps[2] - maps[3]) + up[2] * (maps[4] - maps[5]);
        return f.call(Map.of("sum", sum, "tilt", tilt));
    }

    @Test
    void uniformLightKeepsTheSixSideMean() throws IOException {
        GlslFunction f = skyDome();
        double[] up = {0.3, 0.8, Math.sqrt(1 - 0.09 - 0.64)};
        assertEquals(0.7, dome(f, new double[]{0.7, 0.7, 0.7, 0.7, 0.7, 0.7}, up), 1e-9);
    }

    @Test
    void theUndersideSeesOnlyTheGround() throws IOException {
        GlslFunction f = skyDome();
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

    @Test
    void skyTiltUsesTheDirectLightsChannelMapping() throws IOException {
        // the map facing +axis in sixLight (the sun on that side) is the positive term of the tilt on that axis: a flipped
        // sign would light the underside with the sky and the top with the ground
        String fsh = Files.readString(FX);
        java.util.regex.Matcher m = java.util.regex.Pattern.compile(
                "float side = l\\.x > 0\\.0 \\? (\\w\\.r) : (\\w\\.r);\\s*"
                        + "float vertical = l\\.y > 0\\.0 \\? (\\w\\.g) : (\\w\\.g);\\s*"
                        + "float depth = l\\.z < 0\\.0 \\? (\\w\\.b) : (\\w\\.b);").matcher(fsh);
        assertTrue(m.find(), "sixLight channel mapping");
        // +x, +y: the first branch; +z: the second (the condition is l.z < 0)
        String expected = "vec3 tilt = vec3(" + m.group(1) + " - " + m.group(2) + ", " + m.group(3) + " - " + m.group(4)
                + ", " + m.group(6) + " - " + m.group(5) + ");";
        assertTrue(fsh.contains(expected), expected);
    }
}
