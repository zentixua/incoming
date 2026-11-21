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

    /**
     * The head's steps fill its interval from the entry with a step continuous across pixels; no whole step count (a count jumping by one
     * along contours of equal span showed as rings about ground zero on the hump's sloped top) and no eye-anchored power-of-two grid.
     */
    @Test
    void headIsFilledFromItsEntry() {
        String march = read("fx_pall.glsl"), law = read("pall.glsl");
        assertTrue(march.contains("fineStep[k] = (leave[k] - enter[k]) / max(want[k] * fit, 1.0);"), "the head's step: continuous, no whole count");
        assertTrue(march.contains("next = min(next, min(enter[k] + (j + jitter) * fineStep[k], leave[k]));"), "boundaries counted from the entry");
        assertFalse(march.contains("pallGridNext") || law.contains("pallGridStep") || march.contains("log2(max(want"), "no eye-anchored power-of-two head grid");
    }

    /**
     * Tops are found by refinement, never by the step that happens to be there: a layer opaque within one step shows the light at its
     * first sample, so the march refuses a long step that meets matter after an empty sample and re-marches from that sample in fixed
     * small steps — the head's a quarter of its wanted step, the young layer's a twelfth of a lump, the first one jittered — and ends
     * once the ray is opaque. A step following the budget share drew rings about ground zero on the 1 Mt head (instrumented take:
     * first-hit step correlation +0.67…+0.75 with the rings); ordinary steps of 240–350 m averaged the young relief into a plain; a
     * refinement step of a third of a lump flickered it (23.8 levels/s against 16.6).
     */
    @Test
    void topsAreFoundByRefinement() {
        String march = read("fx_pall.glsl");
        assertTrue(march.contains("if (refine > 0.0 && ds > refine) return 2;"), "a long step meeting matter is refused");
        assertTrue(march.contains("bool refine = !refined && prevEmpty && surf < 1e8;"), "refinement after an empty sample, head and layer alike");
        assertTrue(march.contains("vec4 surfStep = vec4(0.03 * PallWall0[0].z, 0.03 * PallWall0[0].z, 0.03 * PallWall1[0].z, 0.03 * PallWall1[0].z);"),
                "the head's surface step: a quarter of its wanted step (0.12 of its top)");
        assertTrue(march.contains("if (fineYoung < 1e8) fineYoung *= 2.5 / (12.0 * PALL_LUMP_TEXEL);"), "the young layer's surface step: a twelfth of a lump");
        assertTrue(march.contains("if (firstFine) first = max(jitter, 0.05);") && march.contains("next = min(next, ta + first * fineSize);"), "the first fine step jittered");
        assertTrue(march.contains("bool pallLeft = i < pallSteps && ta < t1 && transmit >= 0.003;"), "the pall ends once opaque");
        assertTrue(march.contains("const int PALL_DEBUG = 0;"), "the product carries no debug output");
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
