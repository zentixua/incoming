package ua.zentix.incoming.client.nuclear;

import org.junit.jupiter.api.Test;
import ua.zentix.incoming.nuclear.model.DustModel;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The young dust's drawn field (fx_pall: youngDust — Artem's approved v6 animation, 20.11) scaled to the law: at the approved scene's ring
 * (1 Mt surface burst, 4.5 km) its density scale — the drawn share {@code PALL_YOUNG_SHARE} of the law's column over the animation's layer
 * height ({@code PALL_PEAK_SHARE / (1 + PALL_WALL_HUMP)} of the law's top) — is the animation's 0.11/m (its 0.11 · strength, strength 1
 * at that ring), and its layer height is the animation's HPK 58 m within the law's spread (its physical layer 45–80 m).
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

    @Test
    void theApprovedSceneDrawsTheAnimationsDust() throws IOException {
        String src = src();
        DustModel.Column c = DustModel.column(4500, 0, 1000, true, 20_000, DustModel.Surface.REFERENCE);
        double top = c.height(c.positive()), tau = c.lifted();
        double hk = constant(src, "PALL_PEAK_SHARE") / (1 + constant(src, "PALL_WALL_HUMP")) * top;
        double rho = constant(src, "PALL_YOUNG_SHARE") * tau / hk;
        System.out.printf(java.util.Locale.ROOT, "law top %.1f m, column %.1f, layer %.1f m, share for 0.11/m %.4f%n", top, tau, hk, 0.11 * hk / tau);
        assertEquals(0.11, rho, 0.0055, "the animation's density scale at its ring, 1/m");
        assertTrue(hk > 45 && hk < 80, "the animation's layer at its ring within its physical 45–80 m: " + hk);
        assertEquals(PallField.YOUNG_TOP, constant(src, "PALL_YOUNG_TOP"), 1e-9, "the bounds' copy");
    }
}
