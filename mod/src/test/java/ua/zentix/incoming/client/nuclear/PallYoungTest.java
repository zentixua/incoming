package ua.zentix.incoming.client.nuclear;

import org.junit.jupiter.api.Test;
import ua.zentix.incoming.nuclear.model.DustModel;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The young dust's drawn field (fx_pall: youngDust — Artem's approved v6 animation, 20.11) scaled to the law. The animation's wind strength
 * ({@code pallYoungStrength}: Kinney–Graham overpressure, Rankine–Hugoniot wind, (u / 75 m/s)^1.5, at most 2) is its own at 1 Mt and 1 at
 * the 4.5 km ring; the young layer's height is PALL_PEAK_SHARE / (1 + PALL_WALL_HUMP) of the law's top there ({@link BlastWall#ringTop})
 * times it — the animation's HPK 58 m within the law's spread at that ring, its layer doubling toward ground zero (the law's top falls
 * there: the band at −1 s was 100 px against the animation's 170 px); its density scale there is the animation's 0.11/m; the bounds'
 * reach covers the tallest drawn layer.
 */
class PallYoungTest {
    private static String src() throws IOException {
        try (InputStream in = PallYoungTest.class.getResourceAsStream("/assets/incoming/shaders/include/pall.glsl")) {
            assertNotNull(in);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static double constant(String src, String name) {
        Matcher m = Pattern.compile("const float " + name + " = ([0-9.]+);").matcher(src);
        assertTrue(m.find(), name);
        return Double.parseDouble(m.group(1));
    }

    /** The animation's strength(r) at 1 Mt (approved-v6 shader). */
    static double animation(double r) {
        double z = Math.max(r, 200) / 1000;
        double x = 808 * (1 + (z / 4.5) * (z / 4.5)) / Math.sqrt((1 + (z / 0.048) * (z / 0.048)) * (1 + (z / 0.32) * (z / 0.32)) * (1 + (z / 1.35) * (z / 1.35)));
        double u = 343 * (5 * x / 7) / Math.sqrt(1 + 6 * x / 7);
        return Math.min(2, Math.max(0, Math.pow(u / 75, 1.5)));
    }

    @Test
    void strengthIsTheAnimations() throws IOException {
        GlslFunction f = GlslFunction.parse(src(), "pallYoungStrength");
        for (double r = 200; r <= 12000; r += 50) assertEquals(animation(r), f.call(Map.of("r", r, "size", 1.0)), 1e-4, "r " + r);
        assertEquals(1, animation(4500), 0.01, "1 at the 4.5 km ring");
        // scaled distance: 15 kt at the same overpressure is ∛(1000/15) closer
        double k = Math.cbrt(15 / 1000.0);
        assertEquals(animation(3000), f.call(Map.of("r", 3000 * k, "size", k)), 1e-4);
    }

    @Test
    void theApprovedSceneDrawsTheAnimationsDust() throws IOException {
        String src = src();
        DustModel.Column c = DustModel.column(4500, 0, 1000, true, 20_000, DustModel.Surface.REFERENCE);
        double top = c.height(c.positive()), tau = c.lifted(), share = constant(src, "PALL_PEAK_SHARE") / (1 + constant(src, "PALL_WALL_HUMP"));
        double hk = share * top * animation(4500), rho = constant(src, "PALL_YOUNG_SHARE") * tau / hk;
        assertEquals(0.11, rho, 0.0055, "the animation's density scale at its ring, 1/m");
        assertTrue(hk > 45 && hk < 80, "the animation's layer at its ring within its physical 45–80 m: " + hk);
        DustModel.Column inner = DustModel.column(3000, 0, 1000, true, 20_000, DustModel.Surface.REFERENCE);
        assertTrue(share * top * animation(3000) > 1.8 * share * inner.height(inner.positive()), "toward ground zero the drawn layer outgrows the law's");
        // the tallest drawn dust: strength 2, the hump (1 + HUMP) and the cutoff at 1.6 of it, under the reach
        assertTrue(2.0 * share * (1 + constant(src, "PALL_WALL_HUMP")) * 1.6 <= constant(src, "PALL_YOUNG_REACH"), "the reach covers the tallest layer");
        assertEquals(PallField.YOUNG_REACH, constant(src, "PALL_YOUNG_REACH"), 1e-9, "the bounds' copy");
    }
}
