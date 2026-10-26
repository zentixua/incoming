package ua.zentix.incoming.client.fx.layer;

import org.junit.jupiter.api.Test;
import ua.zentix.incoming.client.ClientWeaponSpec;
import ua.zentix.incoming.client.fx.plume.Flame;
import ua.zentix.incoming.entity.FlightPhase;
import ua.zentix.incoming.strike.WeaponType;
import ua.zentix.incoming.util.Air;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The light of the torch on the world in segments and the shadow of the passage {@link HeatPass}. */
class HeatPassTest {
    /** ∫ cos θ / d² along part of the segment above the face - numerically, in small steps. */
    private static double numeric(double[] a, double[] u, double t0, double t1, double[] n, double w2) {
        int steps = 200_000;
        double sum = 0, dt = (t1 - t0) / steps;
        for (int i = 0; i < steps; i++) {
            double t = t0 + (i + 0.5) * dt;
            double x = a[0] + u[0] * t, y = a[1] + u[1] * t, z = a[2] + u[2] * t;
            double cos = n[0] * x + n[1] * y + n[2] * z;
            if (cos <= 0) continue;
            // the same correction for the axis as the formula: d² → d² + w²
            double d2 = x * x + y * y + z * z + w2;
            sum += cos * Math.pow(d2, -1.5) * dt;
        }
        return sum;
    }

    private static double[] unit(Random r) {
        double x = r.nextGaussian(), y = r.nextGaussian(), z = r.nextGaussian(), l = Math.sqrt(x * x + y * y + z * z);
        return new double[]{x / l, y / l, z / l};
    }

    /**
     * The antiderivative of the direct light of a segment ({@link HeatPass#segment}) is the same as the integral numerically, for any position of the
     *  face: the segment is entirely above it, partly below it (stream into the ground) and at the axis itself.
     */
    @Test
    void segmentLightIsTheIntegralOverTheVisiblePart() {
        Random r = new Random(7);
        for (int i = 0; i < 200; i++) {
            double[] a = {r.nextGaussian() * 6, r.nextGaussian() * 6, r.nextGaussian() * 6}, u = unit(r), n = unit(r);
            double t0 = r.nextDouble() * 3, t1 = t0 + 0.2 + r.nextDouble() * 20, w2 = Math.pow(0.05 + r.nextDouble() * 0.5, 2);
            double exact = HeatPass.segment(a[0], a[1], a[2], u[0], u[1], u[2], t0, t1, n[0], n[1], n[2], w2);
            double num = numeric(a, u, t0, t1, n, w2);
            assertEquals(num, exact, 1e-4 * Math.max(num, 1e-3), "case " + i);
        }
        // at the axis itself: edge in centimeters and fractions of a millimeter from the straight jet, along it and obliquely
        double[][] near = {{0.01, 3, 0}, {0.0005, -2, 0.0003}, {0.02, 10, 0.01}};
        for (double[] q : near) {
            double[] a = {-q[0], -q[1], -q[2]}, u = {0, 1, 0};
            for (double[] n : new double[][]{{1, 0, 0}, {0.6, 0.8, 0}, {0, -1, 0}}) {
                double exact = HeatPass.segment(a[0], a[1], a[2], u[0], u[1], u[2], 0, 6, n[0], n[1], n[2], 0.01);
                double num = numeric(a, u, 0, 6, n, 0.01);
                assertEquals(num, exact, 1e-4 * Math.max(num, 1e-3), "at the axis " + java.util.Arrays.toString(q) + " " + java.util.Arrays.toString(n));
            }
        }
    }

    /**
     * In the distance, a jet is a point: light per meter × length × cos/d² - the same ball of the same area on the side ({@link Flame#lightRadius},
     * brightness {@link Flame#brightness}); close up - in parts, and there is more than one spot on the ground under the stream in its middle.
     */
    @Test
    void jetFarAwayIsTheBallOfTheSameArea() {
        Flame f = ClientWeaponSpec.of(WeaponType.MISSILE).airframe(false).far().stage(FlightPhase.BOOST).flame(Air.SEA);
        assertNotNull(f);
        double[] parts = f.partAreas();
        double core = f.core(), fl = Math.max(f.flame(), core), end = f.tailEnd();
        double[] lum = {f.coreLuminance(), f.flameLuminance(), f.tailLuminance()};
        double[][] span = {{0, core}, {0, fl}, {fl, end}};
        // jet along x, face facing it in d along y
        double d = 2000, sum = 0;
        for (int k = 0; k < 3; k++) {
            double q = lum[k] * parts[k] / ((span[k][1] - span[k][0]) * Math.PI);
            sum += q * HeatPass.segment(-f.middle(), d, 0, 1, 0, 0, span[k][0], span[k][1], 0, 1, 0, 0);
        }
        double r = f.lightRadius(), ball = f.brightness() * r * r / (d * d);
        assertEquals(ball, sum, 1e-3 * ball, "in the distance is a ball of the same area");
        // at the exit of the nozzle on the ground at 2 m, a core shines under the jet: the light there is no less than under the middle of the jet near the ball
        double atNozzle = 0, h = 2;
        for (int k = 0; k < 3; k++) {
            double q = lum[k] * parts[k] / ((span[k][1] - span[k][0]) * Math.PI);
            atNozzle += q * HeatPass.segment(0, h, 0, 1, 0, 0, span[k][0], span[k][1], 0, 1, 0, 0);
        }
        double ballThere = f.brightness() * r * r / Math.max(f.middle() * f.middle() + h * h, r * r) * h / Math.hypot(f.middle(), h);
        assertTrue(atNozzle > ballThere, "at the nozzle along the segment " + atNozzle + " against the ball in the middle " + ballThere);
    }

    /** A jet that has gone under the edge shines only from the part above it: the edge just under the nozzle of the jet into the ground is no brighter than from one above-ground part. */
    @Test
    void partBelowTheFaceDoesNotLight() {
        // vertical stream from 3 m above ground, length 10 m: underground 7 m
        double whole = HeatPass.segment(1, 3, 0, 0, -1, 0, 0, 10, 0, 1, 0, 0.01);
        double aboveOnly = HeatPass.segment(1, 3, 0, 0, -1, 0, 0, 3, 0, 1, 0, 0.01);
        assertEquals(aboveOnly, whole, 1e-12);
        double[] t = HeatPass.above(1, 3, 0, 0, -1, 0, 0, 10, 0, 1, 0);
        assertEquals(3, t[1], 1e-12);
        assertEquals(0, HeatPass.segment(1, -3, 0, 0, -1, 0, 0, 10, 0, 1, 0, 0.01), "all under the brink");
    }

    /** Screen window: block boxes (inclusively and exclusively on blocks), ground - everything below y = groundY. */
    private record Box(int x0, int y0, int z0, int x1, int y1, int z1) {
        boolean has(int x, int y, int z) {
            return x >= x0 && x < x1 && y >= y0 && y < y1 && z >= z0 && z < z1;
        }
    }

    private static final int[] SIZE = {192, 96, 192};

    private static HeatPass.Cells world(int groundY, Box... boxes) {
        return (x, y, z) -> {
            if (y < groundY) return true;
            for (Box b : boxes) if (b.has(x, y, z)) return true;
            return false;
        };
    }

    private static boolean shadowed(HeatPass.Cells w, double[] p, double[] q) {
        return HeatPass.blocked(p[0], p[1], p[2], q[0], q[1], q[2], SIZE, w);
    }

    /**
     * Complaint about the light behind the house: the house between fire and earth leaves the earth in the shadow - from whichever side you look (by blocks, and
     *  not by the depth of the frame: review #399 - the beam across the view through a solid house passed in 84% of cases), both in a solid house and in a
     *  hollow house with walls into the block, and at the hill. The ground is open on the side; the pillar next to the beam does not provide a shadow; the cell of the
     *  point itself (the edge on which it lies) is not a barrier; part of the beam outside the window is free.
     */
    @Test
    void blocksBetweenGroundAndFireCastAShadow() {
        int g = 40;
        // ground - top of block y = 39 (y = 40 - air); a point on the ground - just above the edge
        double[] ground = {100.5, 40.02, 40.5};
        double[] fire = {100.5, 43, 70.5};
        Box house = new Box(96, 40, 50, 105, 45, 59);
        assertTrue(shadowed(world(g, house), ground, fire), "whole house - shadow");
        Box[] hollow = {new Box(96, 40, 50, 105, 45, 51), new Box(96, 40, 58, 105, 45, 59), new Box(96, 40, 50, 97, 45, 59),
                new Box(104, 40, 50, 105, 45, 59), new Box(96, 44, 50, 105, 45, 59)};
        assertTrue(shadowed(world(g, hollow), ground, fire), "hollow house with block walls - shadow");
        assertFalse(shadowed(world(g), ground, fire), "without a home - light");
        assertFalse(shadowed(world(g, house), new double[]{120.5, 40.02, 40.5}, new double[]{120.5, 43, 70.5}), "on the side of the house - light");
        Box pole = new Box(102, 40, 55, 103, 50, 56);
        assertFalse(shadowed(world(g, pole), ground, fire), "a pillar one and a half blocks from the beam - past");
        // hill: the beam to the fire behind the ridge goes into the ground
        Box hill = new Box(80, 40, 52, 120, 50, 60);
        assertTrue(shadowed(world(g, hill), ground, fire), "behind the hill there is a shadow");
        // a point in a cell of the wall block itself (the place in depth is on the edge, the shift along the normal is small): its own cell is not a barrier,
        //  the light in front of the wall is open; without the passage of its cell, the wall would shade itself
        assertFalse(shadowed(world(g, house), new double[]{100.5, 42, 50.01}, new double[]{100.5, 43, 30}), "your own cage is not a barrier");
        assertTrue(HeatPass.blocked(100.5, 42, 50.01, 100.5, 43, 30, SIZE, (x, y, z) -> x == 100 && y == 42 && z == 50) == false);
        // and the far wall is a barrier for the point in its cell
        assertTrue(shadowed(world(g, house), new double[]{100.5, 42, 50.01}, new double[]{100.5, 43, 70}), "through the house - shadow");
        // outside the window - free: the barrier outside the window is unknown
        assertFalse(shadowed((x, y, z) -> x >= SIZE[0] || x < 0, new double[]{100, 50, 100}, new double[]{300, 50, 100}), "outside the window - free");
    }

    /**
     * fireball at the edge of a hill is an extended source: the point from where the middle of the ball is behind the edge and the top is visible receives a
     *  share of its light, not zero; the ball is entirely behind the hill - nothing; open - all.
     */
    @Test
    void ballBehindARidgeIsPartlySeen() {
        int g = 40;
        // a ball of radius 6 with a middle at 5 above the ground behind the ridge of height 5 (top of the ridge - y 45), point - in front of the ridge
        double[] ball = {100.5, 45, 90.5}, ground = {100.5, 40.02, 40.5};
        Box ridge = new Box(80, 40, 70, 120, 45, 72);
        double half = HeatPass.ballSeen(ground[0], ground[1], ground[2], ball[0], ball[1], ball[2], 6, SIZE, world(g, ridge));
        // from a point above the edge the top of the disk is visible above 0,58 r (area ~0,13); quadrature sees the top node - ¼
        assertEquals(0.25, half, 1e-12, "the top of the ball is visible - a fraction, not zero");
        Box wall = new Box(80, 40, 70, 120, 60, 72);
        assertEquals(0, HeatPass.ballSeen(ground[0], ground[1], ground[2], ball[0], ball[1], ball[2], 6, SIZE, world(g, wall)), 1e-12, "behind the wall - nothing");
        assertEquals(1, HeatPass.ballSeen(ground[0], ground[1], ground[2], ball[0], ball[1], ball[2], 6, SIZE, world(g)), 1e-12, "open - all");
        // edge below the middle: the bottom of the disc is closed, the middle is visible - three quarters
        Box low = new Box(80, 40, 70, 120, 42, 72);
        assertEquals(0.75, HeatPass.ballSeen(ground[0], ground[1], ground[2], ball[0], ball[1], ball[2], 6, SIZE, world(g, low)), 1e-12, "the middle is visible, the bottom is closed");
    }

    /**
     * A jet that has gone into the ground does not shine through it (review #399): at the launch of a rocket from 2 m under 30°, the axis goes into the ground at 4 m,
     *  there is no torch to pass beyond - the ends of the parts end there, they have light per meter former.
     */
    @Test
    void jetEndsWhereItsAxisMeetsTheGround() {
        Flame f = ClientWeaponSpec.of(WeaponType.MISSILE).airframe(false).far().stage(FlightPhase.BOOST).flame(Air.SEA);
        assertNotNull(f);
        HeatPass.Frame frame = new HeatPass.Frame();
        double sin = Math.sin(Math.toRadians(30)), cos = Math.cos(Math.toRadians(30)), cut = 2 / sin;
        frame.jet(f.middle() * cos, 10 - f.middle() * sin, 0, cos, -sin, 0, f, 0, 2, cut);
        org.joml.Vector4f c = new org.joml.Vector4f();
        assertEquals(cut, frame.jetSize.getColumn(0, c).x, 1e-5, "the end of the stream is near the ground");
        assertEquals(Math.min(f.flame(), cut), frame.jetAxis.getColumn(0, c).w, 1e-5, "flame to the ground");
        assertEquals(Math.min(f.core(), cut), frame.jets.getColumn(0, c).w, 1e-5);
        HeatPass.Frame whole = new HeatPass.Frame();
        whole.jet(f.middle() * cos, 10 - f.middle() * sin, 0, cos, -sin, 0, f, 0, 2, Double.POSITIVE_INFINITY);
        assertEquals(frame.jetFlame.getColumn(0, c).x, whole.jetFlame.getColumn(0, new org.joml.Vector4f()).x, 1e-9, "light per meter - same");
        HeatPass.Frame buried = new HeatPass.Frame();
        buried.jet(0, 0, 0, 0, -1, 0, f, 0, 2, 0);
        assertFalse(buried.lit(), "nozzle in the ground - no light");
    }
    @Test
    void conventionalFrontKeepsPhysicalOpticsAndNukePriority() {
        HeatPass.Frame frame = new HeatPass.Frame();
        double[] jump = {1e-4, 1e-4, 1e-4, 1e-4};
        frame.front(0, 0, 50, 20, 1, 0, jump);
        frame.chemicalFront(0, 0, 50, 25, 2, 0, jump);
        org.joml.Vector4f c = new org.joml.Vector4f();
        assertEquals(20, frame.front.getColumn(0, c).w, 1e-6);
        assertEquals(25, frame.front.getColumn(1, c).w, 1e-6);
        assertEquals(1e-4, frame.front.getColumn(2, c).x, 1e-10, "nuke uses physical index jump");
        assertEquals(1e-4, frame.front.getColumn(3, c).x, 1e-10, "gain belongs to bounded screen displacement");
        assertEquals(1e-4, jump[0], 1e-15, "does not mutate shared input");
        frame.chemicalFront(0, 0, 26, 25, 2, 0, jump);
        assertEquals(20, frame.front.getColumn(0, c).w, 1e-6, "cannot replace the nuke slot");
    }

}
