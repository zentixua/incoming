package ua.zentix.incoming.client.fx.layer;

import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.vertex.VertexFormatElement;
import net.minecraft.util.Mth;
import org.joml.Vector3f;
import org.lwjgl.system.MemoryUtil;

import java.util.Arrays;

/**
 * Quads for the effects-layer frame: accumulated in reusable off-heap memory, then sorted far to near by their true
 * camera distance and submitted in one draw call. Transparency blends correctly only in this order: a near puff covers
 * a distant one, regardless of which was created later. <p> A vertex is 36 bytes ({@link #FORMAT}): camera-relative
 * position, atlas coordinates, premultiplied color, terrain softness or source probe ({@link #seenBy}) and near-fold
 * factor ({@code UV1}), world light ({@code UV2}; baked puffs store their lighting basis instead, {@link
 * PuffGeometry#faceLight}), Minecraft fog ({@code Normal.x}), texture mode ({@code Normal.y}: 1 for nearest-texel
 * particles, {@link #nearestTexels}; −1 for distant-model atlas tiles, {@link #modelTiles}; ½ for analytic glow, {@link
 * #glowShape}; −¼ for baked puffs with six-way lighting, {@link #sixWay}; ⅝ for blended fire frames, {@link
 * #fireballMix}; 0 for smooth effects-atlas sampling), and particle opacity before texturing or puff-frame blend
 * ({@code Normal.z}, {@link #opacity}), plus emission ({@code Emit}, {@link #emission}).
 */
public final class FxQuads {
    /**
     * Vertex glow ({@link #emission}): an unnormalized byte (in the shader, a number 0…255). Its own format element —
     * under a free number ({@code findNextId}), as Iris adds its own elements.
     */
    public static final VertexFormatElement EMIT = VertexFormatElement.register(VertexFormatElement.findNextId(), 0, VertexFormatElement.Type.UBYTE,
            VertexFormatElement.Usage.GENERIC, 1);
    public static final VertexFormat FORMAT = VertexFormat.builder()
            .add("Position", VertexFormatElement.POSITION)
            .add("UV0", VertexFormatElement.UV0)
            .add("Color", VertexFormatElement.COLOR)
            .add("UV1", VertexFormatElement.UV1)
            .add("UV2", VertexFormatElement.UV2)
            .add("Normal", VertexFormatElement.NORMAL)
            .add("Emit", EMIT)
            .build();
    private static final int VERTEX = 36, QUAD = 4 * VERTEX;
    /** Softness of the puff's edge at the ground and walls — a fraction of its half-size. */
    public static final float SOFT = 0.5f;

    private long stage;
    private int capacity, n;
    private long write;
    private float[] keys = new float[0];
    /** The quad glows (at least one vertex with {@link #emission}). */
    private boolean[] emits = new boolean[0];
    /** After {@link #draw}: start of the glow pass in sorted order ({@link #glowStart}; n — no glowing ones). */
    private int glowFrom;
    /**
     * Depth nearer than which the quads cover the halo of the glowing ones ({@link #glowStart}): the greatest, among
     * the frame's glowing ones, of distance with the quad's half-size.
     */
    private float glowReach;
    /** Bounding box of the vertices of the frame's glowing quads (from the camera): x, y, z smallest, then largest. */
    private final float[] glowBox = new float[6];
    /** Pixel angle of the glow pass, rad ({@link FxBloom#texel}); 0 — no glow. */
    private double glowTexel;
    /** Vertices written for the current quad; spots are being written ({@link #spots}). */
    private int corner;
    private boolean spotting;
    private final FarFirst sort = new FarFirst();
    private final ByteBufferBuilder out = new ByteBufferBuilder(QUAD * 256);
    private Vector3f left = new Vector3f(1, 0, 0), up = new Vector3f(0, 1, 0);
    /** Puff vertices in world axes; one scratch buffer for the whole frame, as with other quads. */
    private final double[] puff = new double[12];
    private byte nearest, opacity, emit;
    private short probe;
    /** The frame has quads with glow ({@link #emission}). */
    private boolean glows;
    /** Layer part of the next quads ({@link #part}), and each quad of the frame has its part number. */
    private Part part = Part.OTHER;
    private byte[] parts = new byte[0];
    /** After {@link #draw}: order of the quads in the buffer (from the far ones), until the next sort. */
    private int[] drawn = new int[0];

    /**
     * The layer part a quad belongs to — for GPU time by parts ({@link LayerTimes}): puffs of smoke and dust, the
     * substance veil near the eye, trails of plumes, fire and flame, glare and halos in the eye, particles, smoke tails
     * of debris, other (distant models, rings, the nuke).
     */
    public enum Part {
        CLUBS, VEIL, RIBBONS, FIRE, GLARE, PARTICLES, DEBRIS, OTHER;

        final String label = name().toLowerCase(java.util.Locale.ROOT);
    }

    /** The next quads are part p ({@link Part}); the previous one — returned, to restore it after its own quads. */
    public Part part(Part p) {
        Part was = part;
        part = p;
        return was;
    }

    /** New frame: screen axes (left, up) for quads facing the camera. */
    public void begin(Vector3f left, Vector3f up) {
        this.left = left;
        this.up = up;
        n = 0;
        nearest = opacity = emit = 0;
        probe = 0;
        part = Part.OTHER;
        glows = false;
        glowReach = -Float.MAX_VALUE;
        Arrays.fill(glowBox, 0, 3, Float.MAX_VALUE);
        Arrays.fill(glowBox, 3, 6, -Float.MAX_VALUE);
    }

    /**
     * The next quads are particles: up close, the nearest pixel of the picture, as from the Minecraft atlas (the
     * smoothed puff soapy), and without a pale edge ({@link #opacity}); half as small — smooth. Far away — always
     * smooth (a halo of a hundred pixels from a 64×64 picture would otherwise be a staircase).
     */
    public void nearestTexels(boolean on) {
        nearest = (byte) (on ? 127 : 0);
        opacity = 0;
    }

    /**
     * The next quads are tiles of the atlas of distant models ({@code FarModels}): the texture is its own, the light is
     * already in it (the lightmap is not multiplied).
     */
    public void modelTiles(boolean on) {
        nearest = (byte) (on ? -127 : 0);
        opacity = 0;
    }

    /**
     * The next quads are the halo {@code far/glow} ({@link FxAtlas#glow}; glare, veil, blaze, trails of plumes): larger
     * than a quarter of the picture on screen — by the same formula in the shader, not from the 64×64 picture. The halo
     * near the eye can cross the screen across, and bilinearly from the picture it would come out in steps, squares of
     * one picture pixel (25 screen pixels each: a kink in the sample at each, and 8 bits in the pale tail). Smaller —
     * from the picture and its reduced copies (smooth).
     */
    /**
     * The next quads are the baked puff with six-sided light ({@link FxAtlas#puff6}, {@code Normal.y} = −¼): sunlight
     * or moonlight, of sun or moon, sky and fireballs — by its maps in the shader; between a frame and the next — a mix
     * by the motion vectors ({@code Normal.z}). Below zero — as usual again.
     */
    public void sixWay(float mix) {
        sixWay(mix, 1);
    }

    /**
     * The same with the shadow of the maps in the shade power (0…{@link #SHADE_MAX}, {@code SmokePuffs.shade}): it is
     * in {@code Normal.y} within the band of baked puffs (−0.4…−0.1), {@link #SHADE_STEPS} steps.
     */
    public void sixWay(float mix, float shade) {
        nearest = (byte) (mix < 0 ? 0 : -13 - Math.round(Mth.clamp(shade / SHADE_MAX, 0, 1) * SHADE_STEPS));
        opacity = mix < 0 ? 0 : (byte) Math.round(Mth.clamp(mix, 0, 1) * 127);
    }

    /** Greatest degree of the puffs' map shadow (soot — ~1.3) and steps in the byte band −13…−50 ({@code fx.fsh}). */
    public static final float SHADE_MAX = 1.5f;
    public static final int SHADE_STEPS = 37;

    /**
     * Fire frames — a blend in the shader with multiplied alpha, not two quads with a brightness dip between frames.
     */
    public void fireballMix(float mix) {
        nearest = (byte) (mix < 0 ? 0 : 80);
        opacity = mix < 0 ? 0 : (byte) Math.round(Mth.clamp(mix, 0, 1) * 127);
    }

    public void glowShape(boolean on) {
        nearest = (byte) (on ? 64 : 0);
        opacity = 0;
    }

    /**
     * Opacity of the next vertices of a particle without a texture (sparks and flashes too, although they only glow):
     * where together with the texture it is below 0.1, the particle is not drawn up close — as in the vanilla particle
     * shader; otherwise the pale halo of each puff in a dense cloud would add up into a murky haze around it. Far away
     * there is no cutoff.
     */
    public void opacity(float a) {
        opacity = (byte) Math.round(Mth.clamp(a, 0, 1) * 127);
    }

    /**
     * The next quads are light in the eye (glare, veil) from a source with the probe {@code probe} ({@link
     * SourceProbes#add}): they are visible as far as the source itself is visible in the frame; what is behind the quad
     * itself does not matter (the edge softness at the relief is not taken). 0 — as usual again.
     */
    public void seenBy(int probe) {
        this.probe = (short) Mth.clamp(probe, 0, SourceProbes.MAX);
    }

    /**
     * The next vertices glow ({@link FxBloom}): fire, fireball, flash, torch. {@code hdr} — how many times brighter
     * their light is than shown (overexposure that the screen clipped: brightness against a white screen is above 1,
     * for a fire frame — the model's ball against the frame); 1 — light as shown; 0 and less — no glow (smoke, halos in
     * the eye, models).
     */
    public void emission(float hdr) {
        emit = (byte) (hdr > 0 && glowTexel > 0 ? FxBloom.EMITTER + FxBloom.code(hdr) : 0);
    }

    /**
     * Pixel angle of the frame's glow pass, rad ({@link FxBloom#texel}): a glowing quad already {@link FxBloom#SMALL}
     * such pixels goes into the glow as spots with the same energy ({@link #spots}); 0 — no glow (setting, shaders).
     */
    public void glowTexel(double texel) {
        glowTexel = texel;
    }

    /** The frame has glow: the glow pass is needed. */
    public boolean glows() {
        return glows;
    }

    /**
     * Bounding box of the vertices of the frame's glowing quads (from the camera): x, y, z smallest, then largest;
     * without them — empty.
     */
    float[] glowBox() {
        return glowBox;
    }

    public int size() {
        return n;
    }

    /** Place of the vertices of quad i in the frame's memory (checks: what is written is what goes to the shader). */
    long address(int i) {
        return stage + (long) i * QUAD;
    }

    /** Start a quad at distance {@code key} from the camera; then exactly four {@link #vertex}. */
    public void quad(float key) {
        if (n == capacity) grow();
        keys[n] = key > 0 ? key : 0;
        emits[n] = false;
        parts[n] = (byte) part.ordinal();
        write = stage + (long) n * QUAD;
        n++;
        corner = 0;
    }

    /**
     * Vertex: position from the camera, position on the sheet, light (r, g, b), already multiplied by opacity, how much
     * is covered behind it (a), edge softness at the relief (blocks, 0 — no softness; for the light from a source,
     * {@link #seenBy}, — not taken), how many times the point is moved closer (1 — none), world light (packed),
     * Minecraft fog 0..1.
     */
    public void vertex(float x, float y, float z, float u, float v, float r, float g, float b, float a, float soft, float unfold, int light, float fog) {
        long p = write;
        MemoryUtil.memPutFloat(p, x);
        MemoryUtil.memPutFloat(p + 4, y);
        MemoryUtil.memPutFloat(p + 8, z);
        MemoryUtil.memPutFloat(p + 12, u);
        MemoryUtil.memPutFloat(p + 16, v);
        MemoryUtil.memPutByte(p + 20, unorm(r));
        MemoryUtil.memPutByte(p + 21, unorm(g));
        MemoryUtil.memPutByte(p + 22, unorm(b));
        MemoryUtil.memPutByte(p + 23, unorm(a));
        // softness — not less than zero, the source probe — minus its number
        MemoryUtil.memPutShort(p + 24, probe > 0 ? (short) -probe : (short) Mth.clamp(Math.round(soft * 16), 0, Short.MAX_VALUE));
        MemoryUtil.memPutShort(p + 26, (short) Mth.clamp(Math.round(unfold * Short.MAX_VALUE), 1, Short.MAX_VALUE));
        MemoryUtil.memPutShort(p + 28, (short) (light & 0xFFFF));
        MemoryUtil.memPutShort(p + 30, (short) (light >>> 16 & 0xFFFF));
        MemoryUtil.memPutByte(p + 32, (byte) Math.round(Mth.clamp(fog, 0, 1) * 127));
        MemoryUtil.memPutByte(p + 33, nearest);
        MemoryUtil.memPutByte(p + 34, opacity);
        MemoryUtil.memPutByte(p + 35, emit);
        if (emit != 0) {
            glows = true;
            emits[n - 1] = true;
            glowBox[0] = Math.min(glowBox[0], x);
            glowBox[1] = Math.min(glowBox[1], y);
            glowBox[2] = Math.min(glowBox[2], z);
            glowBox[3] = Math.max(glowBox[3], x);
            glowBox[4] = Math.max(glowBox[4], y);
            glowBox[5] = Math.max(glowBox[5], z);
        }
        write = p + VERTEX;
        if (++corner == 4 && emits[n - 1]) {
            reach(n - 1);
            if (!spotting) spots(n - 1);
        }
    }

    /**
     * A glowing quad i reaches in depth as far as its distance with its half-size (the real one: vertices far away are
     * moved closer): the frame of the ball stands at the front half of the ball, and the halo is the light of the air
     * around all the fire, and smoke streamers sideways at the depth of its middle stand in front of part of that air.
     */
    private void reach(int i) {
        long q = stage + (long) i * QUAD;
        float cx = 0, cy = 0, cz = 0;
        for (int k = 0; k < 4; k++) {
            long p = q + (long) k * VERTEX;
            cx += MemoryUtil.memGetFloat(p) / 4;
            cy += MemoryUtil.memGetFloat(p + 4) / 4;
            cz += MemoryUtil.memGetFloat(p + 8) / 4;
        }
        float half = 0;
        for (int k = 0; k < 4; k++) {
            long p = q + (long) k * VERTEX;
            float dx = MemoryUtil.memGetFloat(p) - cx, dy = MemoryUtil.memGetFloat(p + 4) - cy, dz = MemoryUtil.memGetFloat(p + 8) - cz;
            half = Math.max(half, (float) Math.sqrt(dx * dx + dy * dy + dz * dz));
        }
        float unfold = Math.max((MemoryUtil.memGetShort(q + 26) & 0xFFFF) / (float) Short.MAX_VALUE, 1e-4f);
        glowReach = Math.max(glowReach, keys[i] + half / unfold);
    }

    private final float[] corners = new float[12];
    private final double[] places = new double[3 * FxBloom.SPOTS + 1];
    private final float[] means = new float[FxAtlas.MEANS];

    /**
     * A glowing quad i already spans {@link FxBloom#SMALL} pixels of the glow pass across ({@link FxBloom#spots}): in
     * the glow pass the sample at the middles of its pixels sometimes hits it and sometimes does not — the glow
     * vanishes and flickers. Instead, spots go into the pass (the halo {@code far/glow} by its formula, half-size
     * {@link FxBloom#SPOT_HALF} pixel) with its energy: the mean light of its place on the sheet ({@link
     * FxAtlas#means}, by the same law as {@code bloomLight} in {@code fx.fsh}) × the vertices' colour × overexposure ×
     * its solid angle, split equally among the spots; the covered share is the same. Light and covered share go in
     * {@code UV2} ({@link FxBloom#magnitude}), the tint in colour (the largest channel — 1). The quad itself does not
     * go into the pass ({@link FxBloom#SKIP}).
     */
    private void spots(int i) {
        long q = stage + (long) i * QUAD;
        for (int k = 0; k < 4; k++) {
            long p = q + (long) k * VERTEX;
            corners[3 * k] = MemoryUtil.memGetFloat(p);
            corners[3 * k + 1] = MemoryUtil.memGetFloat(p + 4);
            corners[3 * k + 2] = MemoryUtil.memGetFloat(p + 8);
        }
        int count = FxBloom.spots(corners, glowTexel, places);
        if (count == 0) return;
        float u0 = Float.MAX_VALUE, v0 = Float.MAX_VALUE, u1 = -Float.MAX_VALUE, v1 = -Float.MAX_VALUE, r = 0, g = 0, b = 0, a = 0, unfold = 0;
        double gain = 0;
        for (int k = 0; k < 4; k++) {
            long p = q + (long) k * VERTEX;
            float u = MemoryUtil.memGetFloat(p + 12), v = MemoryUtil.memGetFloat(p + 16);
            u0 = Math.min(u0, u);
            u1 = Math.max(u1, u);
            v0 = Math.min(v0, v);
            v1 = Math.max(v1, v);
            r += (MemoryUtil.memGetByte(p + 20) & 255) / 1020f;
            g += (MemoryUtil.memGetByte(p + 21) & 255) / 1020f;
            b += (MemoryUtil.memGetByte(p + 22) & 255) / 1020f;
            a += (MemoryUtil.memGetByte(p + 23) & 255) / 1020f;
            unfold += (MemoryUtil.memGetShort(p + 26) & 0xFFFF) / (4f * Short.MAX_VALUE);
            gain += FxBloom.gain((MemoryUtil.memGetByte(p + 35) & 255) - FxBloom.EMITTER) / 4;
        }
        if (!FxAtlas.means(u0, v0, u1, v1, means)) return;
        // as bloomLight: for particles and fire frames (multiplied alpha) — light of the colour by a share, for the
        // rest — light on top
        boolean cover = (MemoryUtil.memGetByte(q + 33) & 255) > 69 && a > 0.004f;
        double[] e = new double[3];
        double alpha;
        if (cover) {
            e[0] = Math.pow(Math.min(1, r / a), 2.2) * a * means[0];
            e[1] = Math.pow(Math.min(1, g / a), 2.2) * a * means[1];
            e[2] = Math.pow(Math.min(1, b / a), 2.2) * a * means[2];
            alpha = a * means[6];
        } else {
            e[0] = Math.pow(r, 2.2) * means[3];
            e[1] = Math.pow(g, 2.2) * means[4];
            e[2] = Math.pow(b, 2.2) * means[5];
            alpha = 0;
        }
        // onto a spot: the quad's light (mean × solid angle) equally, by the halo area of the spot
        double solid = places[3 * count], spot = FxBloom.SPOT_COVER * Math.pow(2 * FxBloom.SPOT_HALF * glowTexel, 2), share = solid / count / spot;
        double er = e[0] * gain * share, eg = e[1] * gain * share, eb = e[2] * gain * share, cover2 = Math.min(1, alpha * share);
        double top = Math.max(er, Math.max(eg, eb));
        if (top <= 0 && cover2 <= 0) return;
        // the spot's light — in the 16 bits of UV2 (FxBloom.magnitude), in colour — only the tint: in the colour byte a
        // weak spot was zeroed
        int light = FxBloom.magnitude(top) & 0xFFFF | FxBloom.magnitude(cover2) << 16;
        float hr = top > 0 ? (float) (er / top) : 0, hg = top > 0 ? (float) (eg / top) : 0, hb = top > 0 ? (float) (eb / top) : 0;
        float soft = Math.max(0, MemoryUtil.memGetShort(q + 24)) / 16f, fog = (MemoryUtil.memGetByte(q + 32) & 255) / 127f, key = keys[i];
        // the quad itself — only in the layer
        for (int k = 0; k < 4; k++) MemoryUtil.memPutByte(q + (long) k * VERTEX + 35, (byte) FxBloom.SKIP);
        byte n0 = nearest, o0 = opacity, e0 = emit;
        short p0 = probe;
        spotting = true;
        nearest = 64;
        opacity = 0;
        probe = 0;
        emit = (byte) FxBloom.SPOT;
        FxAtlas.Sprite glow = FxAtlas.glow();
        for (int k = 0; k < count; k++) {
            double x = places[3 * k], y = places[3 * k + 1], z = places[3 * k + 2], half = FxBloom.SPOT_HALF * glowTexel * Math.sqrt(x * x + y * y + z * z);
            billboard((float) x, (float) y, (float) z, key, (float) half, 0, glow, hr, hg, hb, 1, soft, unfold, light, fog);
        }
        nearest = n0;
        opacity = o0;
        emit = e0;
        probe = p0;
        spotting = false;
    }

    /**
     * A quad facing the camera with centre (x, y, z) from the camera, half-size half, rotated by rot, at distance key
     * (for sorting), texture — place on the sheet.
     */
    public void billboard(float x, float y, float z, float key, float half, float rot, FxAtlas.Sprite s, float r, float g, float b, float a,
                          float soft, float unfold, int light, float fog) {
        billboard(x, y, z, key, half, half, rot, s, r, g, b, a, soft, unfold, light, fog);
    }

    /**
     * The same with different half-sizes along the quad's axes (before rotation): {@code halfA} — along the screen's
     * “left”, {@code halfB} — along “up”. Ash flakes tumble — the compressed axis.
     */
    public void billboard(float x, float y, float z, float key, float halfA, float halfB, float rot, FxAtlas.Sprite s, float r, float g, float b,
                          float a, float soft, float unfold, int light, float fog) {
        float c = Mth.cos(rot), sn = Mth.sin(rot);
        // axes of the quad in the screen plane, rotated by rot
        float ax = (left.x * c + up.x * sn) * halfA, ay = (left.y * c + up.y * sn) * halfA, az = (left.z * c + up.z * sn) * halfA;
        float bx = (-left.x * sn + up.x * c) * halfB, by = (-left.y * sn + up.y * c) * halfB, bz = (-left.z * sn + up.z * c) * halfB;
        quad(key);
        vertex(x - ax - bx, y - ay - by, z - az - bz, s.u1(), s.v1(), r, g, b, a, soft, unfold, light, fog);
        vertex(x - ax + bx, y - ay + by, z - az + bz, s.u1(), s.v0(), r, g, b, a, soft, unfold, light, fog);
        vertex(x + ax + bx, y + ay + by, z + az + bz, s.u0(), s.v0(), r, g, b, a, soft, unfold, light, fog);
        vertex(x + ax - bx, y + ay - by, z + az - bz, s.u0(), s.v1(), r, g, b, a, soft, unfold, light, fog);
    }

    /**
     * A quad lying in the horizontal plane, with centre (x, y, z) from the camera: the axes — the screen's “left”, laid
     * on the horizontal, and the horizontal across it (from the camera), half-sizes {@code halfA} and {@code halfB}. A
     * flat puff (a layer of dust at the ground) — such a quad: a quad facing the camera had its lower half going into
     * the ground, and its upper half stood as a wall above it; for a lying one each point is at its own depth, and the
     * soft edge at the relief is computed from that.
     */
    public void flat(float x, float y, float z, float key, float halfA, float halfB, FxAtlas.Sprite s, float r, float g, float b, float a,
                     float soft, float unfold, int light, float fog) {
        float lx = left.x, lz = left.z, len = Mth.sqrt(lx * lx + lz * lz);
        if (len < 1e-6f) {
            lx = 1;
            lz = 0;
        } else {
            lx /= len;
            lz /= len;
        }
        float ax = lx * halfA, az = lz * halfA, bx = -lz * halfB, bz = lx * halfB;
        quad(key);
        vertex(x - ax - bx, y, z - az - bz, s.u1(), s.v1(), r, g, b, a, soft, unfold, light, fog);
        vertex(x - ax + bx, y, z - az + bz, s.u1(), s.v0(), r, g, b, a, soft, unfold, light, fog);
        vertex(x + ax + bx, y, z + az + bz, s.u0(), s.v0(), r, g, b, a, soft, unfold, light, fog);
        vertex(x + ax - bx, y, z + az - bz, s.u0(), s.v1(), r, g, b, a, soft, unfold, light, fog);
    }

    /**
     * Puff facing the eye position with world up ({@link PuffGeometry#face}); turning the camera does not rotate it.
     */
    public void facePosition(float x, float y, float z, float key, float halfA, float halfB, FxAtlas.Sprite s, float r, float g, float b, float a,
                             float soft, float unfold, int light, float fog) {
        PuffGeometry.face(x, y, z, halfA, halfB, puff);
        if (nearest <= -13 && nearest >= -13 - SHADE_STEPS) light = PuffGeometry.faceLight(x, y, z, s.u1() < s.u0());
        puff(key, s, r, g, b, a, soft, unfold, light, fog);
    }

    /** Horizontal puff at a fixed seeded angle; ordinary {@link #flat} retains the axes of its existing callers. */
    public void flat(float x, float y, float z, float key, float halfA, float halfB, double angle, FxAtlas.Sprite s, float r, float g, float b, float a,
                     float soft, float unfold, int light, float fog) {
        PuffGeometry.flat(x, y, z, halfA, halfB, angle, puff);
        if (nearest <= -13 && nearest >= -13 - SHADE_STEPS) light = PuffGeometry.flatLight(angle, s.u1() < s.u0());
        puff(key, s, r, g, b, a, soft, unfold, light, fog);
    }

    private void puff(float key, FxAtlas.Sprite s, float r, float g, float b, float a, float soft, float unfold, int light, float fog) {
        quad(key);
        for (int i = 0; i < 4; i++) {
            vertex((float) puff[3 * i], (float) puff[3 * i + 1], (float) puff[3 * i + 2], i < 2 ? s.u1() : s.u0(),
                    i == 0 || i == 3 ? s.v1() : s.v0(), r, g, b, a, soft, unfold, light, fog);
        }
    }

    /**
     * Sort the frame's quads far to near and upload them for {@link #drawRange} (and the glow pass, {@link #drawGlow});
     * the frame's memory stays for the next frame. false — nothing to draw.
     */
    boolean prepare() {
        glowFrom = n;
        if (n == 0) return false;
        int[] order = sort.sort(keys, n);
        drawn = order;
        glowFrom = glowStart(order);
        long dst = out.reserve(n * QUAD);
        for (int i = 0; i < n; i++) MemoryUtil.memCopy(stage + (long) order[i] * QUAD, dst + (long) i * QUAD, QUAD);
        ByteBufferBuilder.Result result = out.build();
        if (result == null) {
            glowFrom = n;
            return false;
        }
        com.mojang.blaze3d.vertex.VertexBuffer vb = FORMAT.getImmediateDrawVertexBuffer();
        vb.bind();
        vb.upload(new MeshData(result, new MeshData.DrawState(FORMAT, n * 4, n * 6, VertexFormat.Mode.QUADS, VertexFormat.IndexType.least(n * 4))));
        return true;
    }

    /**
     * Sorted quads from..to (after {@link #prepare}) with shader s and the current state: at half resolution ({@link
     * HalfPass}) when {@code halfDepth} is that pass's world distance texture (≥ 0), otherwise as the layer; {@code
     * split} — 0 whole quads, 1 only where they lie behind their tile's last full-resolution quad, 2 only in front
     * (tile cut texture {@code cut}, tiles of {@code tile} frame pixels).
     */
    /**
     * Texture unit of the half-resolution tile cuts in the layer program (12 named samplers take 0–11; 13, 14 are
     * PallOpen, smoke grid).
     */
    static final int CUT_UNIT = 12;
    /** Texture unit of the quads' clip rectangles ({@code HalfRect}, vertex shader). */
    static final int RECT_UNIT = 15;

    void drawRange(net.minecraft.client.renderer.ShaderInstance s, int from, int to, int halfDepth, int split, int cut, int tile) {
        drawRange(s, from, to, halfDepth, split, cut, tile, -1, 0);
    }

    /**
     * Same; {@code rect} ≥ 0 — clip each quad to its rectangle from that texture ({@code HalfRect}), rows from {@code
     * row}.
     */
    void drawRange(net.minecraft.client.renderer.ShaderInstance s, int from, int to, int halfDepth, int split, int cut, int tile, int rect, int row) {
        if (to <= from) return;
        passBegin(s, halfDepth, split, cut, tile, rect, row);
        VertexFormat.IndexType type = com.mojang.blaze3d.systems.RenderSystem.getSequentialBuffer(VertexFormat.Mode.QUADS).type();
        org.lwjgl.opengl.GL11.glDrawElements(org.lwjgl.opengl.GL11.GL_TRIANGLES, (to - from) * 6, type.asGLType, (long) from * 6 * type.bytes);
        passEnd(s, rect, tile);
    }

    /** Bind the uploaded quads and shader s for a pass of {@link #drawRange} (arguments as there). */
    void passBegin(net.minecraft.client.renderer.ShaderInstance s, int halfDepth, int split, int cut, int tile, int rect, int row) {
        com.mojang.blaze3d.vertex.VertexBuffer vb = FORMAT.getImmediateDrawVertexBuffer();
        vb.bind();
        s.setDefaultUniforms(VertexFormat.Mode.QUADS, com.mojang.blaze3d.systems.RenderSystem.getModelViewMatrix(),
                com.mojang.blaze3d.systems.RenderSystem.getProjectionMatrix(), net.minecraft.client.Minecraft.getInstance().getWindow());
        boolean half = halfDepth >= 0;
        if (half) s.setSampler("Sampler1", halfDepth);
        // a half-resolution pixel spans two frame pixels: frame-space lookups (world distance, pall, tiles) scale by it
        s.safeGetUniform("FxGlowPass").set(0f, half ? 2f : 1f);
        s.safeGetUniform("FxHalf").set(half ? 1f : 0f);
        s.safeGetUniform("FxHalfSplit").set(split, 0f, tile, 0f);
        s.safeGetUniform("FxHalfClip").set(rect >= 0 ? 1f : 0f, row, 0f, 0f);
        s.apply();
        if (rect >= 0) {
            int unit = com.mojang.blaze3d.platform.GlStateManager._getActiveTexture();
            com.mojang.blaze3d.platform.GlStateManager._activeTexture(org.lwjgl.opengl.GL13.GL_TEXTURE0 + RECT_UNIT);
            org.lwjgl.opengl.GL11.glBindTexture(org.lwjgl.opengl.GL11.GL_TEXTURE_2D, rect);
            com.mojang.blaze3d.platform.GlStateManager._activeTexture(unit);
            com.mojang.blaze3d.shaders.Uniform.uploadInteger(com.mojang.blaze3d.shaders.Uniform.glGetUniformLocation(s.getId(), "HalfRect"), RECT_UNIT);
            for (int i = 0; i < 4; i++) org.lwjgl.opengl.GL11.glEnable(org.lwjgl.opengl.GL30.GL_CLIP_DISTANCE0 + i);
        }
        if (split > 0) {
            // tile cuts on their own unit, the unit into the program on every bind (Veil recompiles programs;
            // OpenAir.bind)
            int unit = com.mojang.blaze3d.platform.GlStateManager._getActiveTexture();
            com.mojang.blaze3d.platform.GlStateManager._activeTexture(org.lwjgl.opengl.GL13.GL_TEXTURE0 + CUT_UNIT);
            org.lwjgl.opengl.GL11.glBindTexture(org.lwjgl.opengl.GL11.GL_TEXTURE_2D, cut);
            com.mojang.blaze3d.platform.GlStateManager._activeTexture(unit);
            com.mojang.blaze3d.shaders.Uniform.uploadInteger(com.mojang.blaze3d.shaders.Uniform.glGetUniformLocation(s.getId(), "HalfCut"), CUT_UNIT);
        }
    }

    /** After a pass of {@link #passBegin}: the layer's ordinary state. */
    void passEnd(net.minecraft.client.renderer.ShaderInstance s, int rect, int tile) {
        s.clear();
        if (rect >= 0) for (int i = 0; i < 4; i++) org.lwjgl.opengl.GL11.glDisable(org.lwjgl.opengl.GL30.GL_CLIP_DISTANCE0 + i);
        s.safeGetUniform("FxGlowPass").set(0f, 1f);
        s.safeGetUniform("FxHalf").set(0f);
        s.safeGetUniform("FxHalfSplit").set(0f, 0f, tile, 0f);
        s.safeGetUniform("FxHalfClip").set(0f, 0f, 0f, 0f);
    }

    /** Runs of each part in the uploaded order ({@link #runs}). */
    int[][] partRuns() {
        return runs(parts, drawn, n);
    }

    /** Sorted runs r (pairs start, length) of the uploaded quads in one call. */
    static void multiDraw(int[] r) {
        int count = r.length / 2;
        if (count == 0) return;
        VertexFormat.IndexType type = com.mojang.blaze3d.systems.RenderSystem.getSequentialBuffer(VertexFormat.Mode.QUADS).type();
        java.nio.IntBuffer lengths = MemoryUtil.memAllocInt(count);
        org.lwjgl.PointerBuffer starts = MemoryUtil.memAllocPointer(count);
        try {
            for (int i = 0; i < count; i++) {
                lengths.put(r[2 * i + 1] * 6);
                starts.put((long) r[2 * i] * 6 * type.bytes);
            }
            org.lwjgl.opengl.GL14.glMultiDrawElements(org.lwjgl.opengl.GL11.GL_TRIANGLES, lengths.flip(), type.asGLType, starts.flip());
        } finally {
            MemoryUtil.memFree(lengths);
            MemoryUtil.memFree(starts);
        }
    }

    /**
     * Screen of sorted quads (after {@link #prepare}) in a w×h frame: viewProjection maps their eye-relative vertices
     * to clip space. Per sorted place k: {@code half[k]} — a club or veil quad at least {@code wide} pixels across
     * along both edges on screen; {@code tiles[4k..4k+3]} — tiles of {@code tile} pixels it covers (x0, y0, x1, y1
     * inclusive; x0 > x1 off screen). A quad reaching behind the eye covers the whole frame.
     */
    void screen(org.joml.Matrix4fc viewProjection, int w, int h, int tile, double wide, boolean[] half, int[] tiles) {
        int tx = (w + tile - 1) / tile - 1, ty = (h + tile - 1) / tile - 1;
        org.joml.Vector4f c = SCREEN;
        float[] px = SCREEN_X, py = SCREEN_Y;
        for (int k = 0; k < n; k++) {
            int i = drawn[k];
            long q = stage + (long) i * QUAD;
            boolean behind = false;
            float x0 = Float.MAX_VALUE, y0 = Float.MAX_VALUE, x1 = -Float.MAX_VALUE, y1 = -Float.MAX_VALUE;
            for (int j = 0; j < 4; j++) {
                long p = q + (long) j * VERTEX;
                viewProjection.transform(c.set(MemoryUtil.memGetFloat(p), MemoryUtil.memGetFloat(p + 4), MemoryUtil.memGetFloat(p + 8), 1));
                if (!(c.w > 1e-5f)) {
                    behind = true;
                    continue;
                }
                px[j] = (c.x / c.w + 1) * 0.5f * w;
                py[j] = (c.y / c.w + 1) * 0.5f * h;
                x0 = Math.min(x0, px[j]);
                y0 = Math.min(y0, py[j]);
                x1 = Math.max(x1, px[j]);
                y1 = Math.max(y1, py[j]);
            }
            int o = 4 * k;
            if (behind) {
                tiles[o] = 0;
                tiles[o + 1] = 0;
                tiles[o + 2] = tx;
                tiles[o + 3] = ty;
            } else {
                tiles[o] = Math.max(0, (int) Math.floor(x0 / tile));
                tiles[o + 1] = Math.max(0, (int) Math.floor(y0 / tile));
                tiles[o + 2] = Math.min(tx, (int) Math.floor(x1 / tile));
                tiles[o + 3] = Math.min(ty, (int) Math.floor(y1 / tile));
                if (x1 < 0 || y1 < 0 || x0 >= w || y0 >= h) tiles[o] = tx + 1;
            }
            int part = parts[i];
            // clubs, the eye veil and glare in the eye (pure light with alpha 0, a smooth halo by formula; sorted by
            // its source's distance, it would otherwise cut every tile it covers — night glare covers most of the
            // frame)
            boolean soft = part == Part.CLUBS.ordinal() || part == Part.VEIL.ordinal() || part == Part.GLARE.ordinal();
            if (soft && !behind) {
                // both quad edges wide enough on screen: a lying club seen edge-on is a few pixels thin and would break
                // into half-resolution steps
                soft = Math.min(Math.hypot(px[1] - px[0], py[1] - py[0]), Math.hypot(px[2] - px[1], py[2] - py[1])) >= wide;
            }
            half[k] = soft;
        }
    }

    private static final org.joml.Vector4f SCREEN = new org.joml.Vector4f();
    private static final float[] SCREEN_X = new float[4], SCREEN_Y = new float[4];

    /**
     * Start of the glow pass in the order order (from the far ones): the first quad no farther than {@link #glowReach}.
     * Into the pass goes what covers the glowing ones' emission and halo: the glowing one itself and everything nearer
     * than the depth it reaches; what is farther does not cover them (the halo above it remains). n — no glowing ones.
     */
    private int glowStart(int[] order) {
        for (int i = 0; i < n; i++) {
            if (keys[order[i]] <= glowReach) return i;
        }
        return n;
    }

    /** {@link #glowStart} for the frame's quads (checks). */
    int glowStart() {
        return glowStart(sort.sort(keys, n));
    }

    /**
     * After {@link #draw}: once more with shader s — the quads from the start of the glow pass ({@link #glowStart}) to
     * the nearest (the glow pass, {@link FxBloom}): what is farther, the emission and halo of the glowing ones does not
     * cover. From the same buffer that {@link #draw} uploaded (its sorted quads — from the place {@code glowFrom}),
     * without a second upload. false — no glowing ones.
     */
    boolean drawGlow(net.minecraft.client.renderer.ShaderInstance s) {
        if (glowFrom >= n) return false;
        com.mojang.blaze3d.vertex.VertexBuffer vb = FORMAT.getImmediateDrawVertexBuffer();
        vb.bind();
        s.setDefaultUniforms(VertexFormat.Mode.QUADS, com.mojang.blaze3d.systems.RenderSystem.getModelViewMatrix(),
                com.mojang.blaze3d.systems.RenderSystem.getProjectionMatrix(), net.minecraft.client.Minecraft.getInstance().getWindow());
        s.apply();
        VertexFormat.IndexType type = com.mojang.blaze3d.systems.RenderSystem.getSequentialBuffer(VertexFormat.Mode.QUADS).type();
        org.lwjgl.opengl.GL11.glDrawElements(org.lwjgl.opengl.GL11.GL_TRIANGLES, (n - glowFrom) * 6, type.asGLType, (long) glowFrom * 6 * type.bytes);
        s.clear();
        return true;
    }

    /**
     * After {@link #draw}, on the probe frame ({@link LayerTimes}): each layer part ({@link Part}) separately, with the
     * same shader from the same buffer — its segments in sort order in one call (glMultiDrawElements), without the
     * other parts. Each pixel of the part passes the same shader, blending and depth test as in the common call, so its
     * time is its cost in the frame (the blend order between parts does not change the cost). The flush and marks
     * before and after — {@code clock}.
     */
    void drawParts(net.minecraft.client.renderer.ShaderInstance s, LayerTimes.PartClock clock) {
        // the frame split smoke at half resolution: each part as the frame drew it
        if (HalfPass.drawParts(this, s, clock)) return;
        int[][] runs = runs(parts, drawn, n);
        com.mojang.blaze3d.vertex.VertexBuffer vb = FORMAT.getImmediateDrawVertexBuffer();
        vb.bind();
        s.setDefaultUniforms(VertexFormat.Mode.QUADS, com.mojang.blaze3d.systems.RenderSystem.getModelViewMatrix(),
                com.mojang.blaze3d.systems.RenderSystem.getProjectionMatrix(), net.minecraft.client.Minecraft.getInstance().getWindow());
        s.apply();
        VertexFormat.IndexType type = com.mojang.blaze3d.systems.RenderSystem.getSequentialBuffer(VertexFormat.Mode.QUADS).type();
        clock.start();
        for (int p = 0; p < runs.length; p++) {
            int[] r = runs[p];
            int count = r.length / 2, quads = 0;
            java.nio.IntBuffer lengths = count > 0 ? MemoryUtil.memAllocInt(count) : null;
            org.lwjgl.PointerBuffer starts = count > 0 ? MemoryUtil.memAllocPointer(count) : null;
            try {
                for (int i = 0; i < count; i++) {
                    lengths.put(r[2 * i + 1] * 6);
                    starts.put((long) r[2 * i] * 6 * type.bytes);
                    quads += r[2 * i + 1];
                }
                clock.begin(p);
                if (count > 0) org.lwjgl.opengl.GL14.glMultiDrawElements(org.lwjgl.opengl.GL11.GL_TRIANGLES, lengths.flip(), type.asGLType, starts.flip());
                clock.end(p, quads, count);
            } finally {
                if (lengths != null) MemoryUtil.memFree(lengths);
                if (starts != null) MemoryUtil.memFree(starts);
            }
        }
        s.clear();
    }

    /**
     * Segments of each part in the order order (the first n quads): for part p — pairs “start, length” by places in the
     * order; segments of a part do not touch (neighbors are merged), each quad is in exactly one segment of its part.
     */
    static int[][] runs(byte[] parts, int[] order, int n) {
        int[] counts = new int[Part.values().length];
        for (int i = 0; i < n; i++) {
            if (i == 0 || parts[order[i - 1]] != parts[order[i]]) counts[parts[order[i]]]++;
        }
        int[][] runs = new int[counts.length][];
        for (int p = 0; p < counts.length; p++) runs[p] = new int[2 * counts[p]];
        int[] at = new int[counts.length];
        for (int i = 0; i < n; ) {
            int p = parts[order[i]], from = i;
            while (i < n && parts[order[i]] == p) i++;
            runs[p][at[p]++] = from;
            runs[p][at[p]++] = i - from;
        }
        return runs;
    }

    /** Sort key (distance) of quad i in writing order (checks). */
    public float key(int i) {
        return keys[i];
    }

    /** Quad written at index i of the sorted place k (after {@link #prepare}). */
    int sortedIndex(int k) {
        return drawn[k];
    }

    /** Part of quad i in the write order (checks). */
    public Part partOf(int i) {
        return Part.values()[parts[i]];
    }

    private void grow() {
        int cap = Math.max(1024, capacity * 2);
        long p = stage == 0 ? MemoryUtil.nmemAlloc((long) cap * QUAD) : MemoryUtil.nmemRealloc(stage, (long) cap * QUAD);
        if (p == 0) throw new OutOfMemoryError("Effects layer: out of memory for " + cap + " quads");
        stage = p;
        capacity = cap;
        keys = Arrays.copyOf(keys, cap);
        emits = Arrays.copyOf(emits, cap);
        parts = Arrays.copyOf(parts, cap);
    }

    private static byte unorm(float f) {
        return (byte) (int) (Mth.clamp(f, 0, 1) * 255 + 0.5f);
    }

    /**
     * Order from far to near: a radix sort (three passes of 11, 11 and 10 bits) by the bits of the distance — for
     * non-negative numbers the order of the bits is the order of the values, inversion gives descending; equal ones —
     * in the write order. The arrays live between frames.
     */
    static final class FarFirst {
        private static final int RADIX = 11, BUCKETS = 1 << RADIX;
        private final int[] counts = new int[BUCKETS];
        private int[] order = new int[0], spare = new int[0], bits = new int[0], spareBits = new int[0];

        /** Indices of the first n keys (non-negative) in descending order; the array — until the next call. */
        int[] sort(float[] keys, int n) {
            if (order.length < n) {
                int cap = Math.max(n, order.length * 2);
                order = new int[cap];
                spare = new int[cap];
                bits = new int[cap];
                spareBits = new int[cap];
            }
            int[] idx = order, idx2 = spare, k = bits, k2 = spareBits;
            for (int i = 0; i < n; i++) {
                idx[i] = i;
                k[i] = ~Float.floatToRawIntBits(keys[i]);
            }
            for (int shift = 0; shift < 32; shift += RADIX) {
                Arrays.fill(counts, 0);
                for (int i = 0; i < n; i++) counts[k[i] >>> shift & BUCKETS - 1]++;
                for (int i = 0, sum = 0; i < BUCKETS; i++) {
                    int c = counts[i];
                    counts[i] = sum;
                    sum += c;
                }
                for (int i = 0; i < n; i++) {
                    int to = counts[k[i] >>> shift & BUCKETS - 1]++;
                    idx2[to] = idx[i];
                    k2[to] = k[i];
                }
                int[] t = idx;
                idx = idx2;
                idx2 = t;
                t = k;
                k = k2;
                k2 = t;
            }
            order = idx;
            spare = idx2;
            bits = k;
            spareBits = k2;
            return idx;
        }
    }
}
