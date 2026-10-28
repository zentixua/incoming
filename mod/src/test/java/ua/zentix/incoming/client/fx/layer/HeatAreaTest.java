package ua.zentix.incoming.client.fx.layer;

import org.joml.Matrix4f;
import org.joml.Vector4f;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The heat pass runs only where it can change a pixel ({@link HeatArea}): outside, the full pass would write back what it read. */
class HeatAreaTest {
    private static final int W = 2560, H = 1600;
    private static final Matrix4f VIEW = new Matrix4f();
    private static final Matrix4f PROJECTION = new Matrix4f().perspective((float) Math.toRadians(70), (float) W / H, 0.05f, 1e5f);

    /** fx_heat: relit (include/scene.glsl) and pallDisplay for one channel, written to 8 bits. */
    private static int relit(int w, double open, double added) {
        double lin = Math.pow(w / 255.0, 2.2);
        double albedo = Math.min(lin / Math.max(Math.pow(open, 2.2), HeatArea.OPEN_MIN), 1);
        return (int) Math.round(Math.pow(lin + albedo * added, 1 / 2.2) * 255);
    }

    @Test
    void lightBelowTheThresholdMovesNoPixel() {
        boolean moved = false;
        for (int o = 0; o <= 255; o += 3) {
            for (double shade : new double[]{0.5, 0.6, 0.8, 1}) {
                double open = o / 255.0 * shade, t = HeatArea.visible(o / 255.0, o / 255.0, o / 255.0, shade);
                for (int w = 0; w <= 255; w++) {
                    assertEquals(w, Math.min(255, relit(w, open, 0.999 * t)), "open " + open + ", pixel " + w);
                    moved |= Math.min(255, relit(w, open, 1.1 * t)) != w;
                }
            }
        }
        assertTrue(moved, "the threshold is tight: slightly more light moves some pixel");
        // a darker channel or face only lowers the threshold
        assertTrue(HeatArea.visible(0.3, 0.5, 0.5, 1) < HeatArea.visible(0.5, 0.5, 0.5, 1));
        assertTrue(HeatArea.visible(0.5, 0.5, 0.5, 0.5) < HeatArea.visible(0.5, 0.5, 0.5, 1));
    }

    private static HeatPass.Frame ball(double x, double y, double z, double r, double light) {
        HeatPass.Frame f = new HeatPass.Frame();
        f.lights.setColumn(0, new Vector4f((float) x, (float) y, (float) z, (float) r));
        f.lightColor.setColumn(0, new Vector4f((float) light, (float) (0.6 * light), (float) (0.3 * light), 0));
        return f;
    }

    /** Pixel of eye-relative point p, or null behind the eye or off screen. */
    private static int[] pixel(double x, double y, double z) {
        Vector4f c = PROJECTION.transform(VIEW.transform(new Vector4f((float) x, (float) y, (float) z, 1)));
        if (c.w <= 0) return null;
        double nx = c.x / c.w, ny = c.y / c.w;
        if (Math.abs(nx) > 1 || Math.abs(ny) > 1) return null;
        return new int[]{(int) ((nx + 1) * 0.5 * W), (int) ((ny + 1) * 0.5 * H)};
    }

    private static boolean inside(HeatArea a, int from, int to, int[] p) {
        int[] r = new int[4];
        for (int i = from; i < to; i++) {
            a.pixels(i, W, H, 0, r);
            if (p[0] >= r[0] && p[0] < r[2] && p[1] >= r[1] && p[1] < r[3]) return true;
        }
        return false;
    }

    @Test
    void everyVisiblyLitPixelIsInsideTheArea() {
        Random rnd = new Random(7);
        double t = HeatArea.visible(0.2, 0.2, 0.3, 0.5);
        int lit = 0;
        for (int k = 0; k < 40; k++) {
            double bx = (rnd.nextDouble() - 0.5) * 600, by = rnd.nextDouble() * 40 - 10, bz = -50 - rnd.nextDouble() * 800, r = 1 + rnd.nextDouble() * 8;
            double light = Math.pow(10, rnd.nextDouble() * 4 - 2);
            HeatArea a = new HeatArea().of(ball(bx, by, bz, r, light), VIEW, PROJECTION, 1, t);
            for (int s = 0; s < 4000; s++) {
                // world points around the ball, on faces turned toward it (cosine 1: the brightest case)
                double px = bx + (rnd.nextDouble() - 0.5) * 600, py = by + (rnd.nextDouble() - 0.5) * 300, pz = bz + (rnd.nextDouble() - 0.5) * 600;
                double d2 = (px - bx) * (px - bx) + (py - by) * (py - by) + (pz - bz) * (pz - bz);
                if (light * HeatPass.ballGain(d2, r * r, 0, 1) <= t) continue;
                int[] p = pixel(px, py, pz);
                if (p == null) continue;
                lit++;
                assertTrue(inside(a, 0, a.lit, p), "lit pixel " + p[0] + "," + p[1] + " outside the area of ball " + k);
            }
        }
        assertTrue(lit > 1000, "the check saw lit pixels: " + lit);
    }

    @Test
    void jetLightStaysInsideTheArea() {
        Random rnd = new Random(11);
        double t = HeatArea.visible(0.6, 0.6, 0.7, 0.5);
        int lit = 0;
        for (int k = 0; k < 20; k++) {
            HeatPass.Frame f = new HeatPass.Frame();
            double ex = (rnd.nextDouble() - 0.5) * 200, ey = rnd.nextDouble() * 60, ez = -40 - rnd.nextDouble() * 300;
            double ux = rnd.nextGaussian(), uy = rnd.nextGaussian(), uz = rnd.nextGaussian(), u = Math.sqrt(ux * ux + uy * uy + uz * uz);
            ux /= u;
            uy /= u;
            uz /= u;
            double core = 1 + rnd.nextDouble() * 3, flame = core + rnd.nextDouble() * 6, end = flame + rnd.nextDouble() * 20;
            double q = Math.pow(10, rnd.nextDouble() * 3 - 1);
            f.jets.setColumn(0, new Vector4f((float) ex, (float) ey, (float) ez, (float) core));
            f.jetAxis.setColumn(0, new Vector4f((float) ux, (float) uy, (float) uz, (float) flame));
            f.jetSize.setColumn(0, new Vector4f((float) end, 0.3f, 0.1f, 0));
            f.jetCore.setColumn(0, new Vector4f((float) (4 * q), (float) (3 * q), (float) (2 * q), 0));
            f.jetFlame.setColumn(0, new Vector4f((float) q, (float) (0.5 * q), (float) (0.2 * q), 0));
            f.jetTail.setColumn(0, new Vector4f((float) (0.1 * q), (float) (0.05 * q), (float) (0.02 * q), 0));
            HeatArea a = new HeatArea().of(f, VIEW, PROJECTION, 1, t);
            for (int s = 0; s < 3000; s++) {
                double px = ex + (rnd.nextDouble() - 0.5) * 200, py = ey + (rnd.nextDouble() - 0.5) * 120, pz = ez + (rnd.nextDouble() - 0.5) * 200;
                double ax = ex - px, ay = ey - py, az = ez - pz;
                // face turned toward the jet middle: near the brightest case
                double mx = ax + ux * 0.5 * end, my = ay + uy * 0.5 * end, mz = az + uz * 0.5 * end, m = Math.sqrt(mx * mx + my * my + mz * mz);
                double nx = mx / m, ny = my / m, nz = mz / m;
                double light = 4 * q * HeatPass.segment(ax, ay, az, ux, uy, uz, 0, core, nx, ny, nz, 0)
                        + q * HeatPass.segment(ax, ay, az, ux, uy, uz, 0, flame, nx, ny, nz, 0)
                        + 0.1 * q * HeatPass.segment(ax, ay, az, ux, uy, uz, flame, end, nx, ny, nz, 0);
                if (light <= t) continue;
                int[] p = pixel(px, py, pz);
                if (p == null) continue;
                lit++;
                assertTrue(inside(a, 0, a.lit, p), "jet-lit pixel outside the area of jet " + k);
            }
        }
        assertTrue(lit > 300, "the check saw lit pixels: " + lit);
    }

    @Test
    void nothingInViewMeansNoPass() {
        double t = HeatArea.visible(0.2, 0.2, 0.3, 0.5);
        // a pool fire 3 km behind the eye at night: its light never reaches the screen
        assertEquals(0, new HeatArea().of(ball(0, 0, 3000, 2, 0.1 * 1000), VIEW, PROJECTION, 1, t).count);
        // a faint fire ahead: too weak to move a pixel anywhere
        assertEquals(0, new HeatArea().of(ball(0, 0, -300, 2, 0.5 * t), VIEW, PROJECTION, 1, t).count);
        // the same fire bright enough: a rectangle around it, far less than the screen
        HeatArea a = new HeatArea().of(ball(0, 0, -3000, 2, 0.1 * 1000), VIEW, PROJECTION, 1, t);
        assertEquals(1, a.count);
        assertFalse(a.whole);
        // the eye inside the reach: the whole screen
        assertTrue(new HeatArea().of(ball(0, 0, -20, 3, 50), VIEW, PROJECTION, 1, t).whole);
    }

    @Test
    void hotGasAndFrontsTakeTheirVolume() {
        HeatPass.Frame f = new HeatPass.Frame();
        f.hot.setColumn(0, new Vector4f(10, -5, -200, 4));
        f.axis.setColumn(0, new Vector4f(0, 1, 0, 0.1f));
        f.shape.setColumn(0, new Vector4f(60, 1, 2, 0));
        HeatArea a = new HeatArea().of(f, VIEW, PROJECTION, 1, 1e-4);
        assertEquals(1, a.count);
        assertEquals(0, a.lit, "hot gas needs no shadow rays");
        // any ray through the column lies inside
        int[] r = new int[4];
        a.pixels(0, W, H, 0, r);
        for (double h = 0; h <= 60; h += 5) {
            for (double s = -1; s <= 1; s += 0.25) {
                double rad = 4 + 0.1 * h;
                int[] p = pixel(10 + s * rad, -5 + h, -200 + s * rad);
                assertTrue(p != null && p[0] >= r[0] && p[0] < r[2] && p[1] >= r[1] && p[1] < r[3], "column point outside");
            }
        }
        HeatPass.Frame nuke = new HeatPass.Frame();
        nuke.front(0, -50, -4000, 1500, 30, 0, new double[]{1e-4, 1e-4, 1e-4, 1e-4});
        assertTrue(new HeatArea().of(nuke, VIEW, PROJECTION, 1, 1e-4).whole, "a nuclear front keeps the whole screen");
    }

    @Test
    void mergedRectanglesKeepLightFirst() {
        HeatPass.Frame f = ball(-200, 0, -1000, 2, 1);
        f.lights.setColumn(1, new Vector4f(200, 0, -1000, 2));
        f.lightColor.setColumn(1, new Vector4f(1, 1, 1, 0));
        f.hot.setColumn(0, new Vector4f(-205, 0, -1000, 3));
        f.axis.setColumn(0, new Vector4f(0, 1, 0, 0));
        f.shape.setColumn(0, new Vector4f(20, 1, 2, 0));
        f.hot.setColumn(1, new Vector4f(0, 400, -1000, 3));
        f.axis.setColumn(1, new Vector4f(0, 1, 0, 0));
        f.shape.setColumn(1, new Vector4f(20, 1, 2, 0));
        HeatArea a = new HeatArea().of(f, VIEW, PROJECTION, 1, 1e-3);
        // the hot column inside the first ball's rectangle merges into it; the distant one stays apart
        assertEquals(3, a.count);
        assertEquals(2, a.lit);
        int[] p = pixel(0, 410, -1000);
        assertTrue(p != null && inside(a, a.lit, a.count, p), "the lone hot column is the unlit rectangle");
    }
}
