package ua.zentix.incoming.client.fx.layer;

import org.joml.Matrix4fc;
import org.joml.Vector4f;

/**
 * Pixels the heat pass ({@link HeatPass}) can change this frame. Each source reaches a sphere: a ball or a jet as far as its
 * light can still move a display channel by half an 8-bit step ({@link #visible}), a hot-gas column and a conventional
 * front as far as their own volume (only rays through it shift). The pass, its frame copy and its shadow rays run only in
 * the screen rectangles of those spheres; outside them it would write back the very pixel it read. No rectangle in view —
 * no pass at all (a far fire 3 km away cost the full-screen pass 0.94 ms at 2560×1600 with nothing lit on screen).
 * A nuclear front keeps the whole screen (its shift has no cap).
 */
final class HeatArea {
    /** Sources: four balls, four jets, four hot columns, two fronts. */
    static final int MAX = 14;
    /** Half an 8-bit step of a display channel. */
    static final double HALF_STEP = 0.5 / 255;
    /** Least open-sky light {@code relit} divides by (linear, {@code include/scene.glsl}). */
    static final double OPEN_MIN = 1e-4;
    /**
     * Largest image shift a pixel inside a partial area may take, pixels: the frame copy covers each rectangle with this
     * margin and the pass clamps shifts to it. Shimmer is fractions of a pixel, conventional fronts are capped at 3 px
     * ({@code chemicalFrontScale}); only the nuclear front shifts more, and it takes the whole screen.
     */
    static final int SHIFT_REACH = 32;

    /** Rectangles in NDC (x0, y0, x1, y1); the first {@link #lit} hold ball and jet light (the shadow pass needs only those). */
    final float[] rects = new float[4 * MAX];
    int count, lit;
    /** The whole screen (then a single rectangle). */
    boolean whole;
    private final Vector4f v = new Vector4f(), c = new Vector4f();
    private final boolean[] litFlags = new boolean[MAX];

    /**
     * Least added light (linear, on a white wall, with eye adaptation) that changes some pixel: {@code relit} adds light ×
     * albedo, albedo = pixel / open-sky light, capped at 1. The display change is largest where albedo first reaches 1, at
     * the pixel equal to the open-sky light o of the face: (o^2.2 + light)^(1/2.2) − o. Below
     * (o + half step)^2.2 − o^2.2 no channel of any 8-bit pixel moves (rounding returns the byte it read; brighter pixels move
     * less, the 2.2 power is concave). o is the darkest face (Minecraft face shade) of the darkest channel; inputs are the
     * display open-sky light (rgb) and the face shades.
     */
    static double visible(double r, double g, double b, double shadeMin) {
        double floor = Math.pow(OPEN_MIN, 1 / 2.2);
        return Math.min(step(Math.max(r * shadeMin, floor)), Math.min(step(Math.max(g * shadeMin, floor)), step(Math.max(b * shadeMin, floor))));
    }

    private static double step(double open) {
        return Math.pow(open + HALF_STEP, 2.2) - Math.pow(open, 2.2);
    }

    /**
     * Area of frame f: view and projection map eye-relative positions to clip space; {@code exposure} is the frame eye
     * adaptation the pass applies to source light, {@code visible} the least visible added light ({@link #visible}).
     */
    HeatArea of(HeatPass.Frame f, Matrix4fc view, Matrix4fc projection, double exposure, double visible) {
        count = lit = 0;
        whole = false;
        for (int i = 0; i < 4; i++) {
            f.lights.getColumn(i, c);
            if (c.w <= 0) continue;
            f.lightColor.getColumn(i, v);
            double light = Math.max(v.x, Math.max(v.y, v.z)) * exposure;
            // inside the ball the face takes the full surface light, outside light·r²/d²
            if (light > visible) sphere(c.x, c.y, c.z, c.w * Math.sqrt(light / visible), view, projection);
        }
        for (int i = 0; i < 4; i++) {
            f.jets.getColumn(i, c);
            if (c.w <= 0) continue;
            double ex = c.x, ey = c.y, ez = c.z, core = c.w;
            f.jetAxis.getColumn(i, c);
            double ax = c.x, ay = c.y, az = c.z, flame = c.w;
            f.jetSize.getColumn(i, c);
            double end = c.x;
            // a uniform segment of light q per meter gives a face at distance ≥ D from all its points at most q·length/D²
            double sum = brightest(f.jetCore, i) * core + brightest(f.jetFlame, i) * flame + brightest(f.jetTail, i) * Math.max(0, end - flame);
            sum *= exposure;
            if (sum <= 0) continue;
            double half = 0.5 * end;
            sphere(ex + ax * half, ey + ay * half, ez + az * half, half + Math.sqrt(sum / visible), view, projection);
        }
        lit = count;
        for (int i = 0; i < 4; i++) {
            f.hot.getColumn(i, c);
            if (c.w <= 0) continue;
            double bx = c.x, by = c.y, bz = c.z, r0 = c.w;
            f.axis.getColumn(i, c);
            double ax = c.x, ay = c.y, az = c.z, grow = c.w;
            double len = f.shape.getColumn(i, v).x, half = 0.5 * len;
            sphere(bx + ax * half, by + ay * half, bz + az * half, half + r0 + Math.max(0, grow) * len, view, projection);
        }
        for (int i = 0; i < 2; i++) {
            f.front.getColumn(i, c);
            if (c.w <= 0) continue;
            if (f.nuke(i)) {
                full();
                break;
            }
            // compressed air lies inside the front sphere; its e-folding thickness is far below the radius
            sphere(c.x, c.y, c.z, c.w * 1.01 + 1, view, projection);
        }
        if (!whole) merge();
        return this;
    }

    private static double brightest(org.joml.Matrix4f m, int column) {
        return Math.max(0, Math.max(m.get(column, 0), Math.max(m.get(column, 1), m.get(column, 2))));
    }

    /**
     * Screen rectangle of the sphere at eye-relative (x, y, z), radius r: its view-space box corners projected (the
     * sphere lies in the box, the projection of a convex box in front of the eye is the hull of its corners). The eye in
     * or near the sphere, or a corner behind the eye — the whole screen; off screen — nothing.
     */
    void sphere(double x, double y, double z, double r, Matrix4fc view, Matrix4fc projection) {
        if (whole || !(r > 0)) return;
        view.transform(v.set((float) x, (float) y, (float) z, 1));
        double vx = v.x, vy = v.y, vz = v.z;
        if (vx * vx + vy * vy + vz * vz <= (r + 1) * (r + 1)) {
            full();
            return;
        }
        // wholly behind the eye or beyond a side of the view (no far plane: the far world and LODs lie past it)
        if (vz - r >= 0 || !sides(vx, vy, vz, r, projection)) return;
        float x0 = Float.MAX_VALUE, y0 = Float.MAX_VALUE, x1 = -Float.MAX_VALUE, y1 = -Float.MAX_VALUE;
        for (int k = 0; k < 8; k++) {
            projection.transform(v.set((float) (vx + ((k & 1) == 0 ? -r : r)), (float) (vy + ((k & 2) == 0 ? -r : r)),
                    (float) (vz + ((k & 4) == 0 ? -r : r)), 1));
            if (!(v.w > 1e-6f)) {
                full();
                return;
            }
            float nx = v.x / v.w, ny = v.y / v.w;
            x0 = Math.min(x0, nx);
            y0 = Math.min(y0, ny);
            x1 = Math.max(x1, nx);
            y1 = Math.max(y1, ny);
        }
        x0 = Math.max(x0, -1);
        y0 = Math.max(y0, -1);
        x1 = Math.min(x1, 1);
        y1 = Math.min(y1, 1);
        if (!(x0 < x1 && y0 < y1)) return;
        int o = 4 * count++;
        rects[o] = x0;
        rects[o + 1] = y0;
        rects[o + 2] = x1;
        rects[o + 3] = y1;
    }

    /**
     * Sphere at view-space (x, y, z), radius r, reaches inside all four side planes of the projection (Gribb–Hartmann:
     * row 3 ± row 0, row 3 ± row 1).
     */
    private static boolean sides(double x, double y, double z, double r, Matrix4fc p) {
        for (int k = 0; k < 4; k++) {
            int row = k < 2 ? 0 : 1;
            double sign = (k & 1) == 0 ? 1 : -1;
            double a = p.m03() + sign * (row == 0 ? p.m00() : p.m01()), b = p.m13() + sign * (row == 0 ? p.m10() : p.m11());
            double c = p.m23() + sign * (row == 0 ? p.m20() : p.m21()), d = p.m33() + sign * (row == 0 ? p.m30() : p.m31());
            double n = Math.sqrt(a * a + b * b + c * c);
            if (n > 0 && (a * x + b * y + c * z + d) / n < -r) return false;
        }
        return true;
    }

    private void full() {
        whole = true;
        count = lit = 1;
        rects[0] = rects[1] = -1;
        rects[2] = rects[3] = 1;
    }

    /**
     * Overlapping rectangles whose union costs no more than both apart become one (the pass is idempotent: a pixel drawn
     * twice gets the same value, only the cost doubles); lit ones first afterwards.
     */
    private void merge() {
        boolean[] lights = litFlags;
        for (int i = 0; i < count; i++) lights[i] = i < lit;
        boolean merged = true;
        while (merged) {
            merged = false;
            for (int i = 0; i < count && !merged; i++) {
                for (int j = i + 1; j < count && !merged; j++) {
                    int a = 4 * i, b = 4 * j;
                    float x0 = Math.min(rects[a], rects[b]), y0 = Math.min(rects[a + 1], rects[b + 1]);
                    float x1 = Math.max(rects[a + 2], rects[b + 2]), y1 = Math.max(rects[a + 3], rects[b + 3]);
                    if ((x1 - x0) * (y1 - y0) > area(a) + area(b)) continue;
                    rects[a] = x0;
                    rects[a + 1] = y0;
                    rects[a + 2] = x1;
                    rects[a + 3] = y1;
                    lights[i] |= lights[j];
                    count--;
                    System.arraycopy(rects, 4 * count, rects, b, 4);
                    lights[j] = lights[count];
                    merged = true;
                }
            }
        }
        // lit rectangles first
        lit = 0;
        for (int i = 0; i < count; i++) {
            if (!lights[i]) continue;
            if (i != lit) {
                for (int k = 0; k < 4; k++) {
                    float t = rects[4 * lit + k];
                    rects[4 * lit + k] = rects[4 * i + k];
                    rects[4 * i + k] = t;
                }
                lights[i] = lights[lit];
                lights[lit] = true;
            }
            lit++;
        }
    }

    private float area(int o) {
        return (rects[o + 2] - rects[o]) * (rects[o + 3] - rects[o + 1]);
    }

    /** For the scenario line: rectangles and their summed share of the screen (overlaps counted twice). */
    String describe() {
        double sum = 0;
        for (int i = 0; i < count; i++) sum += area(4 * i) / 4;
        return String.format(java.util.Locale.ROOT, "%d rects (%d lit) %.1f%%", count, lit, 100 * sum);
    }

    /** Rectangle i in pixels of a w×h frame, widened by margin and one pixel of rounding, clamped: x0, y0, x1, y1 (ends exclusive). */
    void pixels(int i, int w, int h, int margin, int[] out) {
        int o = 4 * i;
        out[0] = Math.max(0, (int) Math.floor((rects[o] + 1) * 0.5 * w) - 1 - margin);
        out[1] = Math.max(0, (int) Math.floor((rects[o + 1] + 1) * 0.5 * h) - 1 - margin);
        out[2] = Math.min(w, (int) Math.ceil((rects[o + 2] + 1) * 0.5 * w) + 1 + margin);
        out[3] = Math.min(h, (int) Math.ceil((rects[o + 3] + 1) * 0.5 * h) + 1 + margin);
    }
}
