package ua.zentix.incoming.client.fx.layer;

import org.joml.Matrix4f;
import org.joml.Vector4f;

import java.util.Arrays;

/**
 * Frame fireballs as light sources for particles and trail ribbons: layer shader ({@code fx.vsh}) adds their light
 * to the lightmap. Otherwise nearby dust and smoke at night would have only blue moonlight, and translucent billows over
 * a yellow fireball would appear lilac. A Lambertian sphere of brightness b (relative to daytime white) lights its surface like b suns,
 * farther away as (r/d)²; lit screen matter is no brighter than its screen fire ({@link FxAtlas#fireLightAt}): nighttime light ×1200
 * would keep dust below an already soot-black fireball as an orange ring. At most {@link #MAX} balls, the most prominent: screen fireball
 * light divided by squared distance to the eye.
 * Emissive matter (fireball, sparks, flashes, custom — {@code FULL_BRIGHT}) receives no fireball light.
 */
public final class FxLights {
    /** Maximum particle light sources (budget: four matrix columns — up to four light calculations per fragment). */
    public static final int MAX = 4;
    /** Column i: camera-relative centre of ball i (xyz), radius (w; 0 — no ball). */
    final Matrix4f balls = new Matrix4f().zero();
    /** Column i: light at ball i surface relative to light around the eye, coloured (rgb); its screen fire (w). */
    final Matrix4f light = new Matrix4f().zero();
    private final double[] weight = new double[MAX];
    private final Vector4f column = new Vector4f();

    /** New frame: no balls. */
    void begin() {
        balls.zero();
        light.zero();
        Arrays.fill(weight, 0);
    }

    /**
     * Ball at camera-relative (x, y, z), radius r, brightness b relative to daytime screen white with eye adaptation
     * ({@code Sight#adapted}), screen fire value fire (white fraction), colour (cr, cg, cb).
     */
    public void add(double x, double y, double z, double r, double b, double fire, float cr, float cg, float cb) {
        if (r <= 0 || b <= 0 || fire <= 0) return;
        double w = b / (1 + b / fire) * r * r / Math.max(x * x + y * y + z * z, r * r);
        int slot = 0;
        for (int i = 1; i < MAX; i++) if (weight[i] < weight[slot]) slot = i;
        if (w <= weight[slot]) return;
        weight[slot] = w;
        balls.setColumn(slot, column.set((float) x, (float) y, (float) z, (float) r));
        light.setColumn(slot, column.set((float) (b * cr), (float) (b * cg), (float) (b * cb), (float) fire));
    }

    /** Ball radius in column i (0 — empty), for checks. */
    float radius(int i) {
        return balls.getColumn(i, column).w;
    }
}
