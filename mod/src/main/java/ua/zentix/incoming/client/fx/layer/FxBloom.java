package ua.zentix.incoming.client.fx.layer;

import com.mojang.blaze3d.platform.GlConst;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferUploader;
import net.minecraft.client.renderer.ShaderInstance;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;
import org.joml.Vector4f;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import ua.zentix.incoming.Incoming;
import ua.zentix.incoming.IncomingConfig;

import java.util.Locale;

/**
 * Glow (bloom) of fire, fireball, flash and torch: our own, after the shader pack. The effects layer runs after the
 * pack's composite ({@code AFTER_LEVEL}), and there is no legitimate path into the pack's buffer on 1.21.1 (Iris
 * without an API, "unknown shaders" are off by default). The same way as Complementary Reimagined/Unbound r5.9.3
 * ({@code composite4/5}: frame HDR → blur pyramid {@code lod} 2–8 → {@code mix(color, pyramid, 0.12)} without threshold
 * → curve), only HDR is not the whole frame but the emission of our layer. Player setting {@code bloom}: {@code OFF},
 * {@code FAST} (default), {@code FULL} — performance, not consequence.
 * <ul>
 * <li>Emission. After the layer's quads, one more pass with the same shader ({@code fx.fsh}, {@code FxGlowPass}) at the
 * pyramid level {@link #glowLevel}: {@code FULL} — one octave finer than the first halo level (at 1080 — half the
 * frame, at 1600 — a quarter; further — a descent in 13 samples), {@code FAST} — the first level itself, four times
 * fewer fragments. Over the whole frame (emission exists only within the frame of the glowing objects, but the halo
 * spreads beyond it, and smoke there dims it the same way) and quads not further than the farthest glowing one with its
 * half-size ({@link FxQuads#glowStart}: what is farther is not covered by the emission and halo of lights) — from the
 * same vertex buffer: emission (R11F_G11F_B10F) and the share of the pixel covered in front of the halo (R8,
 * {@code bloomCovers}: by glowing objects and smoke in front of it), with the same blending as the color ({@code ONE,
 * ONE_MINUS_SRC_ALPHA}): smoke in front of fire dims its emission by the same share, the soft edge at the world is the
 * same, and what is behind a wall and terrain is cut by the distance to the world ({@link SceneDepth}; there is no
 * hardware depth in the pass). Smoke there is only opacity, without light. A second target in the layer itself (MRT)
 * came out more expensive: every smoke fragment over the whole frame blended two more targets. Light is linear, white
 * screen is 1: the shown light times the overexposure that the screen clipped ({@link FxQuads#emission}: for cores,
 * flashes and torches — brightness against white up to {@code Sight.onScreen}; for a fire frame — the model ball's
 * brightness against its frame on screen), no brighter than the white point {@link #WHITE} of the fire's film curve:
 * brighter than that the frame shows no difference.</li>
 * <li>Small things. The pass takes the emission at the centres of its pixels: a quad smaller than a pixel (a spark, a
 * distant torch, a core far away) hits a centre sometimes and sometimes not — the glow would vanish and flicker. Once
 * {@link #SMALL} pass pixels across the glowing quad, it goes into the pass as spots ({@link #spots}: the
 * {@code far/glow} halo by formula, half-size {@link #SPOT_HALF} pixel — samples at the centres carry the same energy
 * at any shift, within 1 %) with its energy: the mean light of its place on the sheet ({@link FxAtlas#means}) × color ×
 * overexposure × solid angle; the quad itself does not go into the pass. The light of a spot is in the 16 bits of
 * {@code UV2} as a logarithm ({@link #magnitude}, step 0.07 %), the hue is in the color: in the color byte (1/255) a
 * weak spot was zeroed, and one slightly stronger got almost twice as much.</li>
 * <li>Pyramid — the descent of the dual filter by Jimenez 2014 (Call of Duty: Advanced Warfare; likewise Unity URP,
 * Blender EEVEE): 13 samples, the convolution keeps energy, and each level carries all the emission energy; gathering
 * of the levels in one pass — B-spline, 4 samples per level, equal weights: equal energy per octave of angle, that is a
 * 1/θ² falloff, like the CIE eye scattering function (Vos 1984, CIE 135/1999: L/E = 10/θ², θ in degrees). Levels — as
 * in the pack: 2–8 at frame height 1080 (2^k pixels — ~0.2°…17° at a field of view of 70°), above — one octave deeper
 * ({@link #first}). Reduced copies 2×2 ({@code glGenerateMipmap}) instead of the descent were tested and rejected: for
 * a moving source the distant halo jumps several times.</li>
 * <li>Share {@link #SHARE} without threshold — as in the pack by default; for the CIE eye at angles 0.1°–50° it comes
 * to 0.12 (0.0191·ln(θ₂/θ₁)). One normalisation: the gather divides the sum of levels by their count, the output
 * multiplies by the share ({@link #gatherUniforms}, {@link #outputUniforms}; a second division by the level count made
 * the share 0.017). The halo is added around the glowing object: the curve 1 − e^(−x) and "screen" blending ({@code
 * ONE, ONE_MINUS_SRC_COLOR}) — exactly the addition of light before this curve. The halo is fire's light scattered by
 * the air around it (in the eye — glare and the Stiles–Holladay veil, with their own quads), and in a pixel it passes
 * with the share not covered by the layer in front of it ({@code bloomThrough}): on the glowing object itself there is
 * none — the fire frame is shown with its own exposure, tens of times lower than the model's overexposure, and a halo
 * computed with the overexposure would bleach its folds and soot into "soap"; smoke, dust and debris closer than the
 * farthest glowing object dim it with their opacity — otherwise the halo over the open parts of the ball would lie as a
 * milky veil on the smoke streamers in front of it (review #442). It covers what is no farther than the fire with its
 * half-size: the halo is the light of the air around the whole fire, and the ball's frame stands at the front half of
 * the ball, while streamers at the side at the depth of its middle would otherwise stay under the halo. ⚠
 * Approximation: there is no depth of the halo in a pixel — what is in front of a far fire but behind a near one also
 * dims the near one's halo. In the pack the source gives its share to the halo; here it does not lose it — its frame is
 * already shown on its own curve.</li>
 * </ul>
 * Presentation, not physics: the brightness and energy of the fire in the model do not change; light on the world and
 * smoke is its own ({@code FxLights}, {@code HeatPass}). Glare and the Stiles–Holladay veil at the eye ({@code
 * FarSprites.glare}) stay: they see the source also outside the frame and beyond it. Works with any pack and without
 * shaders; the frame the layer draws into is the bound one (in Flashback when exporting video — its size). The nuclear
 * ball (the pall's volume) does not write here.
 */
public final class FxBloom {
    /**
     * Share of emission in the halo: {@code BLOOM_STRENGTH} of Complementary by default; for the CIE eye at 0.1°–50° —
     * 0.12.
     */
    public static final double SHARE = 0.12;
    /** Pyramid levels in the halo (in the pack — lod 2–8). */
    public static final int LEVELS = 7;
    /** First level at frame height {@link #REFERENCE} (in the pack — lod 2). */
    static final int FIRST = 2;
    /**
     * Frame height the levels are tied to (in the pack — 1080: the halo's size on screen does not depend on
     * resolution).
     */
    static final int REFERENCE = 1080;
    /** Film curve of the fire bake ({@code tools/bake_fx/pack.py film}: ACES, Narkowicz 2016 approximation). */
    static final double FA = 2.51, FB = 0.03, FC = 2.43, FD = 0.59, FE = 0.14;
    /** White point of the fire's film curve: the light it shows as white (≈ 7.24); the glow takes light no brighter. */
    public static final double WHITE = filmInverse(1);
    /**
     * Byte {@code Emit} of a vertex: 0 — does not glow; {@value #SKIP} — glows through its spots ({@link #spots}: in
     * the glow pass it is absent); {@value #EMITTER} + code — glows with overexposure {@link #gain}(code), code —
     * 0…{@value #STEPS}; {@value #SPOT} — spot of a small glowing object (only in the glow pass; light —
     * {@link #magnitude}).
     */
    static final int SKIP = 1, EMITTER = 2, SPOT = 130, STEPS = 125;
    /**
     * Light of a spot — in the 16 bits of the vertex {@code UV2} (a spot needs no light map): magnitude
     * 2^((code − {@value #MAG_ZERO}) / {@value #MAG_STEP}), step 0.07 %, from 2⁻¹⁶ to 2¹⁶; code 0 — zero. In the color
     * byte (1/255) the light of a weak spot was zeroed or doubled — the energy of a small source jumped with its size.
     */
    static final int MAG_ZERO = 16384, MAG_STEP = 1024;

    /** Code of magnitude v ≥ 0 ({@link #MAG_ZERO}): 0 — zero, 1…32767. */
    static int magnitude(double v) {
        if (!(v > 0)) return 0;
        double c = MAG_ZERO + MAG_STEP * Math.log(v) / Math.log(2);
        return c < 0.5 ? 0 : (int) Math.min(Short.MAX_VALUE, Math.round(c));
    }

    /** Magnitude by code ({@link #magnitude}). */
    static double magnitudeOf(int code) {
        return code <= 0 ? 0 : Math.pow(2, (code - MAG_ZERO) / (double) MAG_STEP);
    }

    private FxBloom() {}

    // ---------------------------------------------------------------- laws (shared with the shaders: FxBloomTest)

    /** Film curve of the bake: shown linear light 0…1 for light x. */
    public static double film(double x) {
        x = Math.max(0, x);
        return Math.min(1, x * (FA * x + FB) / (x * (FC * x + FD) + FE));
    }

    /** Inverse film curve: the light it shows as y (0…1); y = 1 — {@link #WHITE}. */
    public static double filmInverse(double y) {
        double t = Math.max(0, Math.min(1, y));
        double a = FA - FC * t, b = FB - FD * t, c = -FE * t;
        return (-b + Math.sqrt(b * b - 4 * a * c)) / (2 * a);
    }

    /** Overexposure code 0…{@value #STEPS}: 0 — light as shown, {@value #STEPS} — {@link #WHITE} and brighter. */
    public static int code(double hdr) {
        double g = Math.max(1, Math.min(WHITE, hdr));
        return (int) Math.round(STEPS * Math.log(g) / Math.log(WHITE));
    }

    /** Overexposure by code ({@link #code}). */
    public static double gain(int code) {
        return Math.pow(WHITE, Math.clamp(code, 0, STEPS) / (double) STEPS);
    }

    /** Halo curve: light x (white screen — 1) → share up to white; "screen" blending — addition of light before it. */
    public static double curve(double x) {
        return -Math.expm1(-Math.max(0, x));
    }

    /**
     * First level of the pyramid for a frame of height {@code height}: as in the pack — {@link #FIRST} at
     * {@link #REFERENCE}, higher — that many octaves deeper (at 1600 — the third), lower — not finer.
     */
    static int first(int height) {
        return FIRST + (int) Math.max(0, Math.round(Math.log(height / (double) REFERENCE) / Math.log(2)));
    }

    /** Quality from the player's settings ({@code bloom}); without settings (checks) — {@code FAST}. */
    static IncomingConfig.Client.Bloom quality() {
        try {
            return IncomingConfig.CLIENT.bloom.get();
        } catch (IllegalStateException | NullPointerException e) {
            return IncomingConfig.Client.Bloom.FAST;
        }
    }

    /**
     * Level of the emission pass for a frame of height {@code height}: {@code FULL} — one octave finer than
     * {@link #first} (further it goes into the pyramid through a descent in 13 samples: at 1080 — half the frame, at
     * 1600 — a quarter), {@code FAST} — the first level itself (four times fewer fragments; small things — as spots of
     * the same size in its pixels, {@link #spots}).
     */
    static int glowLevel(int height, IncomingConfig.Client.Bloom quality) {
        return first(height) - (quality == IncomingConfig.Client.Bloom.FULL ? 1 : 0);
    }

    /**
     * Angle of a pixel of the emission pass, rad, for the frame pixel angle {@code pixel} and height {@code height}; 0
     * — no glow.
     */
    static double texel(double pixel, int height) {
        IncomingConfig.Client.Bloom q = quality();
        if (q == IncomingConfig.Client.Bloom.OFF || !ready()) return 0;
        return pixel * (1 << glowLevel(height, q));
    }

    /** Glow shaders are loaded and the targets have not failed. */
    static boolean ready() {
        return !failed && down != null && up != null && gather != null && out != null;
    }

    /**
     * Spot of a small glowing object — half-size, in pixels of the emission pass: the Gaussian halo {@code far/glow} (σ
     * ≈ 0.3 of the half-size, ≈ 0.9 pixel) sampled at the pixel centres carries the same energy at any shift (for a
     * quad of a fraction of a pixel the sample hits a pixel centre or not — the glow vanishes and flickers). Smaller
     * than {@value #SMALL} pixels across — spots.
     */
    static final double SPOT_HALF = 3, SMALL = 3;
    /** Spots per quad along — no more (a long thin strip — as beads, through the half-size of a spot). */
    static final int SPOTS = 8;
    /**
     * Mean share of its halo over a spot's quad ({@code glowAlpha} in {@code fx.fsh}; centres of the edge pixels —
     * 64/63).
     */
    static final double SPOT_COVER = spotCover();

    private static double spotCover() {
        int n = 1024;
        double sum = 0, edge = 64.0 / 63;
        for (int j = 0; j < n; j++) {
            for (int i = 0; i < n; i++) {
                double x = ((i + 0.5) / n * 2 - 1) * edge, y = ((j + 0.5) / n * 2 - 1) * edge;
                sum += glowAlpha(Math.sqrt(x * x + y * y));
            }
        }
        return sum / ((double) n * n);
    }

    /** Halo {@code far/glow} ({@code fx.fsh: glowAlpha}) at distance r in fractions of the half-size. */
    static double glowAlpha(double r) {
        double t = Math.max(0, Math.min(1, (1 - r) / 0.25));
        return Math.exp(-(r / 0.42) * (r / 0.42)) * t * t * (3 - 2 * t) / 0.997147;
    }

    /**
     * Spots of quad v (four vertices from the camera, x y z in a row) with a pixel of the emission pass of texel rad:
     * smaller than {@link #SMALL} pixels across — spots along its length (no more than {@link #SPOTS}), otherwise 0
     * (the quad is drawn itself). In out — the centres of the spots (x y z in a row), after them — the solid angle of
     * the quad (its light is divided between the spots evenly).
     */
    static int spots(float[] v, double texel, double[] out) {
        double cx = 0, cy = 0, cz = 0;
        for (int i = 0; i < 4; i++) {
            cx += v[3 * i] / 4;
            cy += v[3 * i + 1] / 4;
            cz += v[3 * i + 2] / 4;
        }
        double d = Math.sqrt(cx * cx + cy * cy + cz * cz);
        if (d < 1e-4 || texel <= 0) return 0;
        double nx = cx / d, ny = cy / d, nz = cz / d;
    // area of the quad — half the cross product of the diagonals, solid angle — its share across the ray
        double ax = v[6] - v[0], ay = v[7] - v[1], az = v[8] - v[2], bx = v[9] - v[3], by = v[10] - v[4], bz = v[11] - v[5];
        double sx = 0.5 * (ay * bz - az * by), sy = 0.5 * (az * bx - ax * bz), sz = 0.5 * (ax * by - ay * bx);
        double solid = Math.abs(sx * nx + sy * ny + sz * nz) / (d * d);
    // half-length across the ray — to the farthest vertex, width — area per length
        double reach = 0, ux = 0, uy = 0, uz = 0;
        for (int i = 0; i < 4; i++) {
            double px = v[3 * i] - cx, py = v[3 * i + 1] - cy, pz = v[3 * i + 2] - cz, along = px * nx + py * ny + pz * nz;
            px -= along * nx;
            py -= along * ny;
            pz -= along * nz;
            double len = Math.sqrt(px * px + py * py + pz * pz) / d;
            if (len > reach) {
                reach = len;
                ux = px;
                uy = py;
                uz = pz;
            }
        }
        double width = reach > 0 ? solid / (2 * reach) : 0;
        if (width >= SMALL * texel) return 0;
        int n = (int) Math.clamp(Math.ceil(2 * reach / (SPOT_HALF * texel)), 1, SPOTS);
        double ul = Math.sqrt(ux * ux + uy * uy + uz * uz);
        for (int k = 0; k < n; k++) {
            double t = n == 1 || ul <= 0 ? 0 : ((k + 0.5) / n * 2 - 1) * reach * d / ul;
            out[3 * k] = cx + t * ux;
            out[3 * k + 1] = cy + t * uy;
            out[3 * k + 2] = cz + t * uz;
        }
        out[3 * n] = solid;
        return n;
    }

    /** Last level: {@link #LEVELS} from the first, but not finer than two pixels on the shorter side. */
    static int last(int width, int height) {
        int f = first(height), k = f;
        while (k < f + LEVELS - 1 && size(Math.min(width, height), k + 1) >= 2) k += 1;
        return k;
    }

    /** Size of level k along side n: half as small per level, rounding up. */
    static int size(int n, int k) {
        for (int i = 0; i < k; i++) n = (n + 1) / 2;
        return Math.max(1, n);
    }

    // ---------------------------------------------------------------- pyramid on the processor (same samples as the
    // shaders)

    /** Pixels of a level: light of one channel by rows, width and height. */
    record Image(float[] v, int w, int h) {
        static Image of(int w, int h) {
            return new Image(new float[w * h], w, h);
        }

        float at(int x, int y) {
            return v[Math.max(0, Math.min(h - 1, y)) * w + Math.max(0, Math.min(w - 1, x))];
        }

        /** Sample with a linear filter and clamp-to-edge at point (x, y) in pixels (centre of pixel i — i + ½). */
        float sample(double x, double y) {
            double u = x - 0.5, t = y - 0.5;
            int i = (int) Math.floor(u), j = (int) Math.floor(t);
            double fu = u - i, ft = t - j;
            return (float) ((at(i, j) * (1 - fu) + at(i + 1, j) * fu) * (1 - ft) + (at(i, j + 1) * (1 - fu) + at(i + 1, j + 1) * fu) * ft);
        }

        double sum() {
            double s = 0;
            for (float f : v) s += f;
            return s;
        }
    }

    /**
     * Down ({@code fx_bloom_down}): pixel (x, y) of the level — point (2x + 1, 2y + 1) of the previous one, 13 samples.
     */
    static Image down(Image s) {
        Image o = Image.of((s.w + 1) / 2, (s.h + 1) / 2);
        for (int y = 0; y < o.h; y++) {
            for (int x = 0; x < o.w; x++) {
                double cx = 2 * x + 1, cy = 2 * y + 1;
                double e = s.sample(cx, cy);
                double corners = s.sample(cx - 2, cy + 2) + s.sample(cx + 2, cy + 2) + s.sample(cx - 2, cy - 2) + s.sample(cx + 2, cy - 2);
                double sides = s.sample(cx, cy + 2) + s.sample(cx - 2, cy) + s.sample(cx + 2, cy) + s.sample(cx, cy - 2);
                double inner = s.sample(cx - 1, cy + 1) + s.sample(cx + 1, cy + 1) + s.sample(cx - 1, cy - 1) + s.sample(cx + 1, cy - 1);
                o.v[y * o.w + x] = (float) (e * 0.125 + corners * 0.03125 + sides * 0.0625 + inner * 0.125);
            }
        }
        return o;
    }

    /** Up ({@code fx_bloom_up}): a 3×3 tent over the pixels of the finest level s into a level of size w×h. */
    static Image tent(Image s, int w, int h) {
        Image o = Image.of(w, h);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                double cx = (x + 0.5) / 2, cy = (y + 0.5) / 2;
                double corners = s.sample(cx - 1, cy - 1) + s.sample(cx + 1, cy - 1) + s.sample(cx - 1, cy + 1) + s.sample(cx + 1, cy + 1);
                double sides = s.sample(cx, cy - 1) + s.sample(cx - 1, cy) + s.sample(cx + 1, cy) + s.sample(cx, cy + 1);
                o.v[y * w + x] = (float) ((corners + 2 * sides + 4 * s.sample(cx, cy)) / 16);
            }
        }
        return o;
    }

    /**
     * Light of the halo on the frame before the curve ({@code fx_bloom.fsh}: {@code light}) from the halo ({@link
     * #halo}) — with the output numbers that the pass sets ({@link #outputUniforms}); there is no glowing-object share
     * here.
     */
    static Image light(Image halo, int w, int h, int g) {
        float[] share = new float[1];
        outputUniforms((name, v) -> {
            if (name.equals("BloomStep")) share[0] = v[2];
        }, size(w, 1), size(h, 1), g, size(w, g), size(h, g), 0, 0);
        Image out = Image.of(w, h);
        for (int i = 0; i < out.v.length; i++) out.v[i] = halo.v[i] * share[0];
        return out;
    }

    /** Cubic B-spline over pixels m at point (x, y) in its pixels (centre of pixel i — i + ½), clamp-to-edge. */
    static double bspline(Image m, double x, double y) {
        double tx = x - 0.5, ty = y - 0.5;
        int ix = (int) Math.floor(tx), iy = (int) Math.floor(ty);
        double[] wx = basis(tx - ix), wy = basis(ty - iy);
        double sum = 0;
        for (int j = 0; j < 4; j++) {
            for (int i = 0; i < 4; i++) sum += wx[i] * wy[j] * m.at(ix - 1 + i, iy - 1 + j);
        }
        return sum;
    }

    /** Cubic B-spline weights of the four neighbours at fraction f. */
    static double[] basis(double f) {
        double f2 = f * f, f3 = f2 * f;
        return new double[]{(-f3 + 3 * f2 - 3 * f + 1) / 6, (3 * f3 - 6 * f2 + 4) / 6, (-3 * f3 + 3 * f2 + 3 * f + 1) / 6, f3 / 6};
    }

    /**
     * Halo over the whole frame w×h from the emission of the glow pass e at level g — light before the curve (without
     * the share {@link #SHARE}): descent in 13 samples down to {@link #last}, gathering of levels {@link #first} …
     * {@link #last} with equal weights in one pass with a B-spline (4 samples each), a tent up to half the frame, to
     * the frame — linearly; the same samples as the passes. The sum over the frame is the sum of emission in the
     * frame's pixels (energy is preserved).
     */
    static Image halo(Image e, int w, int h, int g) {
        int first = first(h), last = last(w, h), n = last - first + 1;
        float[] count = new float[1];
        gatherUniforms((name, v) -> count[0] = v[0], n);
        Image[] level = new Image[last + 1];
        level[g] = e;
        for (int k = g + 1; k <= last; k++) level[k] = down(level[k - 1]);
        Image f = level[first];
        Image gather = Image.of(f.w, f.h);
        for (int y = 0; y < f.h; y++) {
            for (int x = 0; x < f.w; x++) {
                double sum = 0;
                for (int k = first; k <= last; k++) {
                    double s2 = 1 << (k - first);
                    sum += bspline(level[k], (x + 0.5) / s2, (y + 0.5) / s2);
                }
                gather.v[y * f.w + x] = (float) (sum / count[0]);
            }
        }
        Image up = gather;
        for (int k = first - 1; k >= 1; k--) up = tent(up, size(w, k), size(h, k));
        Image out = Image.of(w, h);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) out.v[y * w + x] = up.sample((x + 0.5) / 2, (y + 0.5) / 2);
        }
        return out;
    }

    /** Where the pass shader numbers go: to the GPU — the program's uniforms; in the check — a table. */
    interface Uniforms {
        void set(String name, float... v);
    }

    /**
     * Numbers of the gather pass ({@code fx_bloom_gather}): levels in the gather — by that many it divides the sum
     * (equal weights).
     */
    static void gatherUniforms(Uniforms u, int levels) {
        u.set("BloomCount", levels);
    }

    /**
     * Output numbers per frame ({@code fx_bloom}): step of half the frame w1×h1, share of the halo {@link #SHARE} — the
     * gather has already divided the sum of levels by their count (a second division made the share 0.12/7), start of
     * the output window, step of the share of the glowing level g of size wg×hg.
     */
    static void outputUniforms(Uniforms u, int w1, int h1, int g, int wg, int hg, int fromX, int fromY) {
        u.set("BloomStep", 1f / w1, 1f / h1, (float) SHARE, 0f);
        u.set("BloomFrom", fromX, fromY);
        u.set("BloomCover", 1f / ((1 << g) * wg), 1f / ((1 << g) * hg));
    }

    /** Numbers for shader s via the {@link Uniforms} table. */
    private static Uniforms of(ShaderInstance s) {
        return (name, v) -> {
            com.mojang.blaze3d.shaders.AbstractUniform at = s.safeGetUniform(name);
            switch (v.length) {
                case 1 -> at.set(v[0]);
                case 2 -> at.set(v[0], v[1]);
                case 3 -> at.set(v[0], v[1], v[2]);
                default -> at.set(v[0], v[1], v[2], v[3]);
            }
        };
    }

    // ---------------------------------------------------------------- passes

    @Nullable
    static ShaderInstance down, up, gather, out;
    /**
     * Targets of the glow pass: emission (level {@link #glowLevel} of the pyramid) and the share of the glowing object
     * of the same size; the gathered levels — their own texture of the size of the first level.
     */
    private static int fbo = -1, cover = -1, gathered = -1, gatheredFbo = -1, frameW, frameH, frameG = -1;
    private static boolean failed;
    private static final int MAX = 12;
    private static final int[] LEVEL = new int[MAX + 1], LEVEL_FBO = new int[MAX + 1], LEVEL_W = new int[MAX + 1], LEVEL_H = new int[MAX + 1];
    private static final int[] VIEWPORT = new int[4];
    private static final float[] ZERO = new float[4];
    /** GPU time of the glow passes (with the emission pass) and levels — for the scenario line. */
    private static final GpuTimer GPU = new GpuTimer();
    /** Parts: emission pass, pyramid, output to the frame. */
    private static final GpuTimer PASS = new GpuTimer(), PYRAMID = new GpuTimer(), OUT = new GpuTimer();
    private static int lastFirst, lastLast;

    static {
        java.util.Arrays.fill(LEVEL, -1);
        java.util.Arrays.fill(LEVEL_FBO, -1);
    }

    /**
     * After the layer's quads (the layer's shader {@code fx} — the current one, its blend state applies): quads from
     * the farthest glowing object — once more, at half the frame, with the glow pass ({@code FxGlowPass}: emission and
     * the share of the glowing object), then the pyramid and the halo over the frame. Nothing glows, the shaders did
     * not load, or the targets cannot be set up — nothing is done.
     */
    static void draw(FxQuads quads, ShaderInstance fx, Matrix4f viewProjection) {
        IncomingConfig.Client.Bloom q = quality();
        if (q == IncomingConfig.Client.Bloom.OFF || !quads.glows() || !ready()) return;
        int frame = GlStateManager._getInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
        GL11.glGetIntegerv(GL11.GL_VIEWPORT, VIEWPORT);
        int w = VIEWPORT[0] + VIEWPORT[2], h = VIEWPORT[1] + VIEWPORT[3];
        int g = glowLevel(h, q);
        if (w <= 0 || h <= 0 || !target(w, h, g)) return;
        // no glowing objects in the frame — no emission and no halo
        if (!inFrame(quads.glowBox(), viewProjection)) return;
        int timed = GPU.begin(), pass = PASS.begin();
        // emission and the cover in front of the halo — with the same shader and blending, at level g, without hardware
        // depth (what is behind the world — by the distance to the world in the shader), over the whole frame: emission
        // exists only within the frame of the glowing objects, but the halo spreads beyond it, and smoke there dims it
        // the same way (in the scissor rectangle dust near the ground dimmed the halo only inside it — a rectangular
        // edge of the halo)
        GlStateManager._glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, fbo);
        GlStateManager._viewport(0, 0, LEVEL_W[g], LEVEL_H[g]);
        GL30.glClearBufferfv(GL11.GL_COLOR, 0, ZERO);
        GL30.glClearBufferfv(GL11.GL_COLOR, 1, ZERO);
        fx.safeGetUniform("FxGlowPass").set(1f, (float) w / LEVEL_W[g]);
        boolean any = quads.drawGlow(fx);
        fx.safeGetUniform("FxGlowPass").set(0f, 1f);
        PASS.end(pass);
        if (any) halo(frame, w, h, g);
        GlStateManager._glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, frame);
        GlStateManager._viewport(VIEWPORT[0], VIEWPORT[1], VIEWPORT[2], VIEWPORT[3]);
        // as for the layer after its quads
        RenderSystem.enableBlend();
        RenderSystem.blendFuncSeparate(GlStateManager.SourceFactor.ONE, GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA, GlStateManager.SourceFactor.ONE,
                GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA);
        RenderSystem.enableDepthTest();
        GPU.end(timed);
    }

    private static final Vector4f CORNER = new Vector4f();

    /**
     * Box (from the camera) in matrix viewProjection falls into the frame (a corner at the eye or behind it — falls
     * in).
     */
    static boolean inFrame(float[] box, Matrix4f viewProjection) {
        double x0 = 1, y0 = 1, x1 = -1, y1 = -1;
        for (int i = 0; i < 8; i++) {
            viewProjection.transform(CORNER.set(box[(i & 1) == 0 ? 0 : 3], box[(i & 2) == 0 ? 1 : 4], box[(i & 4) == 0 ? 2 : 5], 1));
            if (CORNER.w < 1e-3f) return true;
            x0 = Math.min(x0, CORNER.x / CORNER.w);
            y0 = Math.min(y0, CORNER.y / CORNER.w);
            x1 = Math.max(x1, CORNER.x / CORNER.w);
            y1 = Math.max(y1, CORNER.y / CORNER.w);
        }
        return x1 > -1 && x0 < 1 && y1 > -1 && y0 < 1;
    }

    /**
     * Pyramid from the emission (level g) and the halo onto a frame of size w×h: descent in 13 samples down to the last
     * level, gathering of all halo levels in one pass (B-spline, 4 samples per level), a tent up to half the frame,
     * output. The cost here is not the pixels but the chain of dependent passes: a shader applies once per series,
     * between passes only the target, texture and step change; the quad is a constant triangle over the whole frame.
     * Gathering instead of climbing the levels with addition saves 5 passes; reduced copies ({@code glGenerateMipmap},
     * 2×2) instead of the descent save still more, but they tie the deep levels to their grid: for a moving source the
     * distant halo jumps several times (FxBloomTest).
     */
    private static void halo(int frame, int w, int h, int g) {
        int first = first(h), last = last(w, h), n = last - first + 1;
        lastFirst = first;
        lastLast = last;
        int timed = PYRAMID.begin();
        RenderSystem.disableDepthTest();
        RenderSystem.depthMask(false);
        RenderSystem.disableBlend();
        triangle();
        // down: level k from level k − 1
        begin(down, LEVEL[g]);
        for (int k = g + 1; k <= last; k++) pass(down, k, LEVEL[k - 1], LEVEL_W[k - 1], LEVEL_H[k - 1]);
        // gather levels first … last into their own texture of the size of the first
        ShaderInstance c = gather;
        for (int i = 0; i < GATHER; i++) c.setSampler("Sampler" + i, LEVEL[first + Math.min(i, n - 1)]);
        gatherUniforms(of(c), n);
        c.apply();
        GlStateManager._glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, gatheredFbo);
        GlStateManager._viewport(0, 0, LEVEL_W[first], LEVEL_H[first]);
        GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, 3);
        // finer than the first level — a tent up to half the frame: a level stretched fourfold linearly is not round at
        // the source (~10 %)
        begin(up, gathered);
        for (int k = first - 1; k >= 1; k--) {
            int src = k == first - 1 ? gathered : LEVEL[k + 1];
            pass(up, k, src, LEVEL_W[k + 1], LEVEL_H[k + 1]);
        }
        PYRAMID.end(timed);
        // onto the frame: around the glowing object and behind the smoke in front of it, the curve and "screen"
        int shown = OUT.begin();
        GlStateManager._glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, frame);
        GlStateManager._viewport(VIEWPORT[0], VIEWPORT[1], VIEWPORT[2], VIEWPORT[3]);
        RenderSystem.enableBlend();
        RenderSystem.blendFuncSeparate(GlStateManager.SourceFactor.ONE, GlStateManager.DestFactor.ONE_MINUS_SRC_COLOR, GlStateManager.SourceFactor.ZERO,
                GlStateManager.DestFactor.ONE);
        ShaderInstance s = out;
        s.setSampler("Sampler1", cover);
        outputUniforms(of(s), LEVEL_W[1], LEVEL_H[1], g, LEVEL_W[g], LEVEL_H[g], VIEWPORT[0], VIEWPORT[1]);
        begin(s, LEVEL[1]);
        GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, 3);
        s.clear();
        // our vertex array was bound bypassing BufferUploader: it remembers that binding and would not re-issue it
        GlStateManager._glBindVertexArray(0);
        BufferUploader.invalidate();
        OUT.end(shown);
    }

    /** Levels in the gather pass — no more (one sample each, {@code fx_bloom_gather}). */
    static final int GATHER = 7;

    /**
     * Apply shader s with texture src in Sampler0 (the series of passes then changes only the target, texture and
     * step).
     */
    private static void begin(ShaderInstance s, int src) {
        s.setSampler("Sampler0", src);
        s.apply();
    }

    /** Pass into level k with the applied shader s from texture src of size sw×sh. */
    private static void pass(ShaderInstance s, int k, int src, int sw, int sh) {
        GlStateManager._glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, LEVEL_FBO[k]);
        GlStateManager._viewport(0, 0, LEVEL_W[k], LEVEL_H[k]);
        com.mojang.blaze3d.shaders.Uniform step = s.getUniform("BloomStep");
        if (step != null) {
            step.set(1f / sw, 1f / sh, 0f, 0f);
            step.upload();
        }
        GlStateManager._activeTexture(GL13.GL_TEXTURE0);
        GlStateManager._bindTexture(src);
        GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, 3);
    }

    /**
     * Our own vertex array: a triangle over the whole frame (−1, −1), (3, −1), (−1, 3) — without a seam along the
     * diagonal.
     */
    private static int triangle = -1;

    private static void triangle() {
        if (triangle >= 0) {
            GlStateManager._glBindVertexArray(triangle);
            return;
        }
        triangle = GlStateManager._glGenVertexArrays();
        GlStateManager._glBindVertexArray(triangle);
        int buffer = GlStateManager._glGenBuffers();
        GlStateManager._glBindBuffer(GL15.GL_ARRAY_BUFFER, buffer);
        GL15.glBufferData(GL15.GL_ARRAY_BUFFER, new float[]{-1, -1, 0, 3, -1, 0, -1, 3, 0}, GL15.GL_STATIC_DRAW);
        // the Position slot is first: the pass shaders are built with the POSITION format
        GlStateManager._enableVertexAttribArray(0);
        GlStateManager._vertexAttribPointer(0, 3, GL11.GL_FLOAT, false, 12, 0);
    }

    /**
     * Pyramid levels of frame w×h (R11F_G11F_B10F, linear sampling, clamp-to-edge; level {@link #glowLevel} — the
     * emission of the glow pass) and the share covered in front of the halo (R8, of the same size); false — cannot be
     * set up.
     */
    private static boolean target(int w, int h, int g) {
        if (fbo >= 0 && frameW == w && frameH == h && frameG == g) return true;
        int draw = GlStateManager._getInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
        boolean ok = true;
        for (int k = 1; k <= MAX; k++) {
            LEVEL_W[k] = size(w, k);
            LEVEL_H[k] = size(h, k);
            LEVEL[k] = texture(LEVEL[k], LEVEL_W[k], LEVEL_H[k], GL30.GL_R11F_G11F_B10F);
            if (LEVEL_FBO[k] < 0) LEVEL_FBO[k] = GlStateManager.glGenFramebuffers();
            GlStateManager._glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, LEVEL_FBO[k]);
            GlStateManager._glFramebufferTexture2D(GL30.GL_DRAW_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GlConst.GL_TEXTURE_2D, LEVEL[k], 0);
            ok &= GL30.glCheckFramebufferStatus(GL30.GL_DRAW_FRAMEBUFFER) == GL30.GL_FRAMEBUFFER_COMPLETE;
        }
        int first = first(h);
        gathered = texture(gathered, LEVEL_W[first], LEVEL_H[first], GL30.GL_R11F_G11F_B10F);
        if (gatheredFbo < 0) gatheredFbo = GlStateManager.glGenFramebuffers();
        GlStateManager._glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, gatheredFbo);
        GlStateManager._glFramebufferTexture2D(GL30.GL_DRAW_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GlConst.GL_TEXTURE_2D, gathered, 0);
        ok &= GL30.glCheckFramebufferStatus(GL30.GL_DRAW_FRAMEBUFFER) == GL30.GL_FRAMEBUFFER_COMPLETE;
        cover = texture(cover, LEVEL_W[g], LEVEL_H[g], GL30.GL_R8);
        if (fbo < 0) fbo = GlStateManager.glGenFramebuffers();
        GlStateManager._glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, fbo);
        GlStateManager._glFramebufferTexture2D(GL30.GL_DRAW_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GlConst.GL_TEXTURE_2D, LEVEL[g], 0);
        GlStateManager._glFramebufferTexture2D(GL30.GL_DRAW_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT1, GlConst.GL_TEXTURE_2D, cover, 0);
        GL20.glDrawBuffers(new int[]{GL30.GL_COLOR_ATTACHMENT0, GL30.GL_COLOR_ATTACHMENT1});
        ok &= GL30.glCheckFramebufferStatus(GL30.GL_DRAW_FRAMEBUFFER) == GL30.GL_FRAMEBUFFER_COMPLETE;
        GlStateManager._glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, draw);
        frameW = w;
        frameH = h;
        frameG = g;
        if (!ok) {
            Incoming.LOG.error("Effects bloom: targets for {}×{} not created, no bloom", w, h);
            failed = true;
        }
        return ok;
    }

    private static int texture(int id, int w, int h, int format) {
        if (id < 0) {
            id = GlStateManager._genTexture();
            GlStateManager._bindTexture(id);
            GlStateManager._texParameter(GlConst.GL_TEXTURE_2D, GlConst.GL_TEXTURE_MIN_FILTER, GlConst.GL_LINEAR);
            GlStateManager._texParameter(GlConst.GL_TEXTURE_2D, GlConst.GL_TEXTURE_MAG_FILTER, GlConst.GL_LINEAR);
            GlStateManager._texParameter(GlConst.GL_TEXTURE_2D, GlConst.GL_TEXTURE_WRAP_S, GlConst.GL_CLAMP_TO_EDGE);
            GlStateManager._texParameter(GlConst.GL_TEXTURE_2D, GlConst.GL_TEXTURE_WRAP_T, GlConst.GL_CLAMP_TO_EDGE);
        }
        GlStateManager._bindTexture(id);
        GlStateManager._texImage2D(GlConst.GL_TEXTURE_2D, 0, format, w, h, 0, GL11.GL_RGB, GL11.GL_FLOAT, null);
        return id;
    }

    /** Once a second (layer tick). */
    static void second() {
        GPU.second();
        PASS.second();
        PYRAMID.second();
        OUT.second();
    }

    /** For the scenario line: glow pass times and levels. */
    static String describe() {
        return String.format(Locale.ROOT, "bloom %s %.3f ms (longest %.3f, %d frames; glow pass %.3f, pyramid %.3f, out %.3f; %dx%d, glow level %d, levels %d-%d)",
                quality(), GPU.lastMean, GPU.lastMax, GPU.lastFrames, PASS.lastMean, PYRAMID.lastMean, OUT.lastMean, frameW, frameH, frameG, lastFirst, lastLast);
    }
}
