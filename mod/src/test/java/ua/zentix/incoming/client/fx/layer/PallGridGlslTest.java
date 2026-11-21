package ua.zentix.incoming.client.fx.layer;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The pall march's sample boundaries: the ordinary steps span the pall's bounds, snapped to quarter octaves ({@link PallPass#snapBounds}),
 * so they stay put while the wall and pall grow; the dust head's interval is filled from its entry with the steps it may take. A head
 * grid anchored at the eye with a power-of-two step (up to twice the wanted one) aliased the thin 1 Mt head flank into ribs and a
 * toothed edge (sandbox variants rib/rib2: gone with the entry fill alone, streaks, hump and young motion irrelevant).
 */
class PallGridGlslTest {
    private static String read(String name) {
        try (InputStream in = PallGridGlslTest.class.getResourceAsStream("/assets/incoming/shaders/include/" + name)) {
            assertNotNull(in);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8).replaceAll("//[^\n]*", "");
        } catch (IOException ex) {
            throw new IllegalStateException(ex);
        }
    }

    /** The head's steps fill its interval from the entry at its wanted count; no eye-anchored power-of-two grid. */
    @Test
    void headIsFilledFromItsEntry() {
        String march = read("fx_pall.glsl"), law = read("pall.glsl");
        assertTrue(march.contains("fineStep[k] = (leave[k] - enter[k]) / max(floor(want[k] * fit), 1.0);"), "the head's step fills its interval");
        assertTrue(march.contains("next = min(next, min(enter[k] + (j + jitter) * fineStep[k], leave[k]));"), "boundaries counted from the entry");
        assertFalse(march.contains("pallGridNext") || law.contains("pallGridStep") || march.contains("log2(max(want"), "no eye-anchored power-of-two head grid");
    }

    /** The pall's bounds the ordinary march spans stay put while the pall grows within a quarter octave, at most a fifth wider. */
    @Test
    void marchBoundsStayPutWhileThePallGrows() {
        // one quarter octave: 1024 < r ≤ 1217.9 all snap to 2^10.25
        for (double r = 1025; r < 1217; r += 3) assertEquals(Math.pow(2, 10.25), PallPass.snapBounds(r), 1e-6);
        for (double r = 2; r < 1e5; r *= 1.07) {
            double s = PallPass.snapBounds(r);
            assertTrue(s >= r - 1e-9 && s <= r * Math.pow(2, 0.25) + 1e-6, r + " → " + s);
        }
    }
}
