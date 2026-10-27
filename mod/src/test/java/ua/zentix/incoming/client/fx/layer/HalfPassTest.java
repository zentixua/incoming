package ua.zentix.incoming.client.fx.layer;

import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Half-resolution smoke keeps the layer's order ({@link HalfPass.Plan}): on every tile the frame gets its full-resolution
 * quads in order and the half-resolution smoke, laid once at the end, only from quads nearer than all of them.
 */
class HalfPassTest {
    private static final int TX = 12, TY = 8;

    /** Premultiplied colour of quad k over what lies behind (one channel, distinct per quad). */
    private static double over(double behind, int k) {
        double a = 0.15 + 0.5 * ((k * 7919) % 13) / 13.0, c = a * ((k * 104729) % 17) / 17.0;
        return c + (1 - a) * behind;
    }

    @Test
    void everyTileSeesTheLayerOrder() {
        Random rnd = new Random(3);
        for (int scene = 0; scene < 300; scene++) {
            int n = 1 + rnd.nextInt(80);
            boolean[] soft = new boolean[n];
            int[] t = new int[4 * n];
            for (int k = 0; k < n; k++) {
                soft[k] = rnd.nextDouble() < 0.8;
                int x0 = rnd.nextInt(TX), y0 = rnd.nextInt(TY);
                t[4 * k] = x0;
                t[4 * k + 1] = y0;
                t[4 * k + 2] = Math.min(TX - 1, x0 + rnd.nextInt(soft[k] ? 6 : 2));
                t[4 * k + 3] = Math.min(TY - 1, y0 + rnd.nextInt(soft[k] ? 5 : 2));
                if (rnd.nextDouble() < 0.03) t[4 * k] = TX; // off screen
            }
            HalfPass.Plan plan = new HalfPass.Plan();
            plan.begin(TX, TY);
            plan.classify(soft, t, n);
            for (int y = 0; y < TY; y++) {
                for (int x = 0; x < TX; x++) {
                    int tile = y * TX + x;
                    double layer = 0.3, frame = 0.3, target = 0, through = 1, last = Double.NaN;
                    boolean laid = false;
                    for (int k = 0; k < n; k++) {
                        if (x < t[4 * k] || x > t[4 * k + 2] || y < t[4 * k + 1] || y > t[4 * k + 3]) continue;
                        layer = over(layer, k);
                        int where = plan.where[k];
                        if (k >= plan.late) {
                            // nearer than every soft quad: drawn over the laid target, in order
                            assertEquals(HalfPass.Plan.LATE, where, "quad " + k + " after the last soft one");
                            assertFalse(soft[k]);
                            if (Double.isNaN(last)) last = target + through * frame;
                            last = over(last, k);
                            continue;
                        }
                        // the frame pass keeps a fragment where the quad lies behind (or is) the tile's cut, the target the rest
                        boolean behind = k <= plan.cut[tile];
                        if (!soft[k]) {
                            assertEquals(HalfPass.Plan.FRAME, where, "full-resolution quad " + k);
                            assertTrue(behind, "a full-resolution quad is behind or at its tiles' cuts");
                        }
                        boolean inFrame = (where & (HalfPass.Plan.FRAME | HalfPass.Plan.FRAME_BEHIND)) != 0 && behind;
                        boolean inTarget = (where & (HalfPass.Plan.TARGET | HalfPass.Plan.TARGET_FRONT)) != 0 && !behind;
                        assertTrue(inFrame != inTarget, "quad " + k + " drawn exactly once on tile " + tile);
                        if (inFrame) {
                            assertFalse(laid, "nothing at full resolution after half-resolution smoke on tile " + tile);
                            frame = over(frame, k);
                        } else {
                            laid = true;
                            double a = over(0, k) - over(1, k) + 1;
                            target = over(target, k);
                            through *= 1 - a;
                            assertTrue(plan.laid(tile), "tile " + tile + " holds smoke to lay");
                        }
                        // a split quad's rectangles cover its tiles on each side
                        if (where == (HalfPass.Plan.FRAME_BEHIND | HalfPass.Plan.TARGET_FRONT)) {
                            int[] r = inFrame ? plan.behind : plan.ahead;
                            assertTrue(x >= r[4 * k] && x <= r[4 * k + 2] && y >= r[4 * k + 1] && y <= r[4 * k + 3], "rectangle of quad " + k);
                        }
                    }
                    // the target laid over the frame, then the late quads, is the layer in order
                    assertEquals(layer, Double.isNaN(last) ? target + through * frame : last, 1e-9, "scene " + scene + " tile " + tile);
                }
            }
        }
    }

    /** Fire inside smoke: smoke behind the fire stays full and under it, smoke in front goes half and over it. */
    @Test
    void fireInsideSmoke() {
        // 0: club behind the fire, 1: the fire, 2: club in front of it; 3: a club elsewhere, nothing full on its tiles
        boolean[] soft = {true, false, true, true};
        int[] t = {2, 2, 6, 6, 4, 4, 4, 4, 2, 2, 6, 6, 9, 0, 11, 2};
        HalfPass.Plan plan = new HalfPass.Plan();
        plan.begin(TX, TY);
        plan.classify(soft, t, 4);
        assertEquals(HalfPass.Plan.FRAME_BEHIND | HalfPass.Plan.TARGET_FRONT, plan.where[0], "behind the fire on its tile, half elsewhere");
        assertEquals(4, plan.behind[0]);
        assertEquals(4, plan.behind[2]);
        assertEquals(HalfPass.Plan.FRAME, plan.where[1]);
        assertEquals(HalfPass.Plan.TARGET, plan.where[2], "in front of the fire: all half");
        assertEquals(HalfPass.Plan.TARGET, plan.where[3]);
        assertTrue(plan.any);
    }

    /** Glare in the eye is the nearest quad and covers the frame: it draws after the lay, the smoke behind it stays half. */
    @Test
    void glareInTheEyeComesAfterTheLay() {
        boolean[] soft = {true, true, false};
        int[] t = {0, 0, 5, 5, 3, 3, 9, 7, 0, 0, TX - 1, TY - 1};
        HalfPass.Plan plan = new HalfPass.Plan();
        plan.begin(TX, TY);
        plan.classify(soft, t, 3);
        assertEquals(2, plan.late);
        assertEquals(HalfPass.Plan.TARGET, plan.where[0]);
        assertEquals(HalfPass.Plan.TARGET, plan.where[1]);
        assertEquals(HalfPass.Plan.LATE, plan.where[2]);
    }
}
