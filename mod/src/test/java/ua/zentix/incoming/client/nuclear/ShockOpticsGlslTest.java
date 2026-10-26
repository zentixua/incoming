package ua.zentix.incoming.client.nuclear;

import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;
import ua.zentix.incoming.client.fx.layer.HeatPass;
import ua.zentix.incoming.nuclear.Detonation;
import ua.zentix.incoming.nuclear.model.ShockOptics;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Shock wave front in the jitter pass: shader laws ({@code fx_heat.fsh}, {@code front*}) - the same as in Java
 * ({@link ShockOptics}); The jump at the explosion front ({@link NukeHeat#jumps}) grows with pressure and disappears behind the edge of the dust wall.
 */
class ShockOpticsGlslTest {
    private static final String GLSL = read("core/fx_heat.fsh");
    private static final Detonation GROUND = new Detonation(1, new Vec3(0, 64, 0), 64, 15, true, 0, 0, 0, 20_000, 7, 1f, false, 0.2f);
    private static final Detonation AIR = new Detonation(2, new Vec3(0, 64 + 500, 0), 64, 15, false, 0, 0, 0, 20_000, 7, 1f, false, 0.2f);

    private static String read(String path) {
        try (InputStream in = ShockOpticsGlslTest.class.getResourceAsStream("/assets/incoming/shaders/" + path)) {
            assertNotNull(in, path);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8).replaceAll("//[^\n]*", "");
        } catch (IOException ex) {
            throw new IllegalStateException(ex);
        }
    }

    @Test
    void shaderLawsAreJavaLaws() {
        GlslFunction dawson = GlslFunction.parse(GLSL, "frontDawson"), path = GlslFunction.parse(GLSL, "frontPath"),
                bend = GlslFunction.parse(GLSL, "frontBend");
        for (double x = 0; x < 80; x += 0.37) assertEquals(ShockOptics.dawson(x), dawson.call(Map.of("x", x)), 1e-12, "x = " + x);
        double r = 2500, l = 30, dn = 5e-5;
        for (double b = 0; b < r + 10; b += 7.3) {
            assertEquals(ShockOptics.path(b, r, l, dn), path.call(Map.of("b", b, "r", r, "l", l, "dn", dn)), 1e-15, "b = " + b);
            for (double w : new double[]{0.01, 3, 50}) {
                assertEquals(ShockOptics.bend(b, r, l, dn, w), bend.call(Map.of("b", b, "r", r, "l", l, "dn", dn, "w", w)), 1e-12,
                        "b = " + b + ", w = " + w);
            }
        }
    }

    @Test
    void shiftThresholdIsTheJavaOne() {
        Matcher m = Pattern.compile("const float FRONT_SHIFT_MIN = ([\\d.]+);").matcher(GLSL);
        assertTrue(m.find());
        assertEquals(HeatPass.SHIFT_MIN, Double.parseDouble(m.group(1)));
    }

    /**
     * A jump on the sphere of the front: the further the front is, the less (the pressure drops) - for the ground at all altitudes, for the air
     *  - above the ground (falling wave; at the ground with the beginning of the Mach region the jump grows: a stem rises there); beyond the edge of the wall
     *  dust - zero.
     */
    @Test
    void jumpFallsWithTheFrontAndEndsAtTheEdge() {
        double[] dn = new double[4];
        for (Detonation d : new Detonation[]{GROUND, AIR}) {
            double reach = d.blastReach();
            double[] was = {Double.MAX_VALUE, Double.MAX_VALUE, Double.MAX_VALUE, Double.MAX_VALUE};
            for (double r = 600; r < reach; r *= 1.1) {
                double low = Math.max(-1, (d.groundY() - d.burst().y) / r);
                NukeHeat.jumps(d, r, low, dn);
                for (int k = d.surface() ? 0 : 1; k < 4; k++) {
                    assertTrue(dn[k] <= was[k] * (1 + 1e-9), "height " + k + " on " + r + ": " + dn[k] + " > " + was[k]);
                    was[k] = dn[k];
                }
            }
            // the air above the ground has an incident wave: weaker than that of the ground in the Mach region
            if (!d.surface()) {
                NukeHeat.jumps(d, 3000, (d.groundY() - d.burst().y) / 3000, dn);
                assertTrue(dn[3] < dn[0], "falling " + dn[3] + " against the stem " + dn[0]);
            }
            double r = reach * 1.001, low = Math.max(-1, (d.groundY() - d.burst().y) / r);
            assertTrue(!NukeHeat.jumps(d, r, low, dn), "over the edge " + reach + ": " + java.util.Arrays.toString(dn));
        }
    }

    @Test
    void fadeToTheEdgeIsSmooth() {
        assertEquals(0, NukeHeat.seen(Detonation.HIT_KPA));
        assertEquals(0, NukeHeat.seen(0.5 * Detonation.HIT_KPA));
        assertEquals(1, NukeHeat.seen(NukeHeat.FADE_FROM_KPA));
        double was = 0;
        for (double kpa = Detonation.HIT_KPA; kpa < 3 * NukeHeat.FADE_FROM_KPA; kpa += 0.01) {
            double s = NukeHeat.seen(kpa);
            assertTrue(s >= was && s - was < 0.01, kpa + " kPa");
            was = s;
        }
    }
}
