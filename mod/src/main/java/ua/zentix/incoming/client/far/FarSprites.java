package ua.zentix.incoming.client.far;

import net.minecraft.client.renderer.LightTexture;
import org.joml.Vector3f;
import ua.zentix.incoming.client.fx.layer.FxAtlas;
import ua.zentix.incoming.client.fx.layer.FxQuads;
import ua.zentix.incoming.client.fx.layer.SourceProbes;
import ua.zentix.incoming.client.render.FarDraw;
import ua.zentix.incoming.client.render.FarModels;
import ua.zentix.incoming.client.render.ProjectilePose;
import ua.zentix.incoming.client.render.WeaponModels;

import java.util.Arrays;

/**
 * All distant things are squares in the shared frame of the effects layer ({@link FxQuads}): trails (as ribbons), smoke
 * puffs, projectile models
 * (atlas tiles {@link FarModels}), fireballs and fires — as animation frames and flame puffs; bodies smaller than a few
 * pixels — as circles (projectile bodies smaller than the model, distant balls and torches); light — as halos (glare
 * and veil
 * around flashes, balls and torches, glow). The layer sorts them together with the near particles from
 * far to near by true distance, so a fireball and a model behind a near smoke column are hidden by the smoke,
 * and a launch within ten blocks is in front of the distant columns.
 * Coordinates are relative to the camera; beyond the far plane a point is moved closer at the same angular size,
 * and the layer is told by how many times (by true distance it hides distant things behind terrain and Distant Horizons
 * LOD).
 * <p>
 * Vertex colour is light already multiplied by opacity; alpha is how much of what is behind the sprite is covered.
 * Smoke and body
 * cover and shine in their own colour; hot matter covers and shines; a halo only shines (alpha 0 — addition). Haze of
 * the
 * air is in the opacity ({@link Sight}: L = L₀·t + sky·(1 − t)).
 * <p>
 * Textures ({@code tools/gen_particles.py}, on the layer's sheet): disc {@code far/disc} — solid, soft edge; halo
 * {@code far/glow} — Gaussian, without core; ribbons — its middle row; fireball — frames {@code fx/particle/fireball},
 * flame — {@code fx/particle/fire}. How much of the square the texture covers is how much light it carries — the
 * factors
 * {@link #DISC}, {@link #GLOW}, {@link #FIREBALL}, {@link #FIRE} convert the radius of a disc of the same luminous flux
 * into the square's half-size ({@code FarSpritesTest}).
 */
public final class FarSprites implements SmokePuffs.Out {
    /**
     * Half-size of the square per radius of a disc of the same flux: the disc {@code far/disc} covers 0.632 of a square
     * (√(π/(4·0.632))),
     * halo {@code far/glow} — 0.133 (peak in the middle — 1).
     */
    public static final double DISC = 1.115, GLOW = 2.43;
    /**
     * Half-size of the fireball frame per its radius: a glowing ball in the frame is 0.72–0.79 half-size (later the
     * smoke
     * bumps are wider); near, particles take the same size ({@code Explosions#fireball}). A flame puff covers 0.27 of a
     * square.
     */
    public static final double FIREBALL = 1.3, FIRE = 1.7;
    /**
     * A fireball is a volume: its frame stands this many radii nearer than the middle, at the front half ({@link
     * #front}).
     * A plane through the middle would from above drop the half nearer the eye into the ground beneath it (a ground
     * ball is a hemisphere,
     * the middle is 0.45 radius above the ground: at 54° it would cut a third of the ball), while a real dome is seen
     * whole from above.
     */
    public static final double BALL_FRONT = 0.5;
    /**
     * A body smaller than this many pixels (radius) is a circle: the shape is not visible, but the light of a point is
     * right; larger — an animation frame.
     */
    private static final double SHAPE_FROM = 2, SHAPE_FULL = 4;
    /**
     * Glare and veil — light scattered in the eye itself: their square is moved toward the eye by this many blocks at
     * the same
     * angular size, and near terrain and buildings do not cut it (otherwise at the edge of a loaded world the halo
     * would end in a
     * straight line).
     * Whether the body itself is covered: in the frame — by what is on screen ({@link SourceProbes}: Minecraft depth
     * and the LOD of
     * Distant Horizons at the source itself); outside the frame — by the caller's rays (beyond the draw distance — by
     * terrain, within it — by blocks).
     */
    static final double EYE = 0.25;
    /**
     * The halo at the eye — in the screen plane at depth {@link #EYE}·cos θ; at the side (θ above ~78°) that is nearer
     * than the Minecraft near
     * plane (0.05), and the square would be cut entirely. The depth is not less than this.
     */
    static final double EYE_DEPTH = 0.1;
    /** A halo in the air ({@link #glow}) — not nearer than this many blocks along the view axis. */
    static final double AIR_NEAR = 1.5;
    /** Half-width of a ribbon for half the trail: the middle of {@code far/glow} across covers 0.365 of the width. */
    public static final double RIBBON = 2.74;
    /**
     * Softness of the edge at terrain: a puff — {@link FxQuads#SOFT} of the half-size, a body — a quarter of the
     * radius.
     */
    private static final float BODY_SOFT = 0.25f;

    /** Model frames in the atlas. */
    final FarModels models = new FarModels();
    private final float[] tile = new float[FarModels.OUT];
    private FxQuads out;
    private SourceProbes probes;
    private double far;
    /** Where the camera looks (for the depth of the halo at the eye). */
    private float fx, fy, fz;
    /** How many puffs, discs, lights, ribbons, models and substance veils at the eye are recorded in this frame. */
    private final int[] counts = new int[6];

    /** New frame: write to out, bright sources to the visibility probes probes. */
    void begin(FarView view, FxQuads out, SourceProbes probes) {
        this.out = out;
        this.probes = probes;
        far = view.far();
        fx = view.forward().x();
        fy = view.forward().y();
        fz = view.forward().z();
        Arrays.fill(counts, 0);
        models.begin();
    }

    public boolean isEmpty() {
        return counts[0] + counts[1] + counts[2] + counts[3] + counts[4] + counts[5] == 0;
    }

    /** How much of each (puffs, discs, lights, ribbons, models) is in the frame — in out, without allocating memory. */
    void counts(int[] into) {
        System.arraycopy(counts, 0, into, 0, counts.length);
    }

    /**
     * A smoke or dust puff (frame tex from the 4×2 {@code nuke/puffs.png}) centred at (dx, dy, dz) from the camera: its
     * own colour
     * (r, g, b) with opacity a (already with the air's haze).
     */
    public void puff(double dx, double dy, double dz, double half, float rot, int tex, float r, float g, float b, float a) {
        FxQuads.Part was = out.part(FxQuads.Part.CLUBS);
        if (billboard(dx, dy, dz, half, rot, FxAtlas.puff(tex), r * a, g * a, b * a, a, (float) half * FxQuads.SOFT)) counts[0]++;
        out.part(was);
    }

    /**
     * Baked puff ({@link FxAtlas#puff6}: frame sprite, blend mix with the next frame), centered at (dx, dy, dz) from the camera,
     * with half-sizes halfA across and halfB up. Color (r, g, b) is the material in skylight at the eye; the shader applies
     * its directional light from the frame maps, raising their shadows to shade ({@link SmokePuffs#shade}). Opacity a
     * already includes atmospheric haze. The plane faces the eye position with world up; map axes follow its geometry.
     */
    public void puff6(double dx, double dy, double dz, double halfA, double halfB, FxAtlas.Sprite sprite, float mix, float shade, float r, float g,
                      float b, float a) {
        if (a < 0.002f) return;
        double d = Math.sqrt(dx * dx + dy * dy + dz * dz), unfold = FarDraw.fold(d, far);
        FxQuads.Part was = out.part(FxQuads.Part.CLUBS);
        out.sixWay(mix, shade);
        out.facePosition((float) (dx * unfold), (float) (dy * unfold), (float) (dz * unfold), (float) d, (float) (halfA * unfold),
                (float) (halfB * unfold), sprite, Math.min(1, r * a), Math.min(1, g * a), Math.min(1, b * a), Math.min(1, a),
                (float) Math.max(halfA, halfB) * FxQuads.SOFT, (float) unfold, LightTexture.FULL_BRIGHT, 0);
        out.sixWay(-1);
        out.part(was);
        counts[0]++;
    }

    /**
     * Baked puff of a flat Gaussian (ground dust layer), lying horizontally ({@link FxQuads#flat}):
     * {@code halfA} and {@code halfB} are horizontal world half-sizes. Terrain softness {@code soft} is the ray's path
     * through the layer to the ground: the gap behind a horizontal quad is the same across its area, so softness based
     * on its half-size would fade the entire layer. Its horizontal angle is fixed by the puff's seed.
     */
    public void puff6Flat(double dx, double dy, double dz, double halfA, double halfB, double soft, double angle, FxAtlas.Sprite sprite, float mix, float shade,
                          float r, float g, float b, float a) {
        if (a < 0.002f) return;
        double d = Math.sqrt(dx * dx + dy * dy + dz * dz), unfold = FarDraw.fold(d, far);
        FxQuads.Part was = out.part(FxQuads.Part.CLUBS);
        out.sixWay(mix, shade);
        out.flat((float) (dx * unfold), (float) (dy * unfold), (float) (dz * unfold), (float) d, (float) (halfA * unfold), (float) (halfB * unfold),
                angle, sprite, Math.min(1, r * a), Math.min(1, g * a), Math.min(1, b * a), Math.min(1, a),
                (float) soft, (float) unfold, LightTexture.FULL_BRIGHT, 0);
        out.sixWay(-1);
        out.part(was);
        counts[0]++;
    }

    /**
     * Substance veil around the eye (the cloud the camera is in, {@link SmokePuffs}): a flat square of half-size half
     * facing
     * the camera at (dx, dy, dz) from it — its own colour (r, g, b) with opacity a, without a soft edge at the world
     * (what is nearer —
     * is nearer than the square anyway).
     */
    public void veil(double dx, double dy, double dz, double half, float r, float g, float b, float a) {
        veil(dx, dy, dz, half, r, g, b, a, LightTexture.FULL_BRIGHT);
    }

    /**
     * The same with the world's light {@code light} (packed, for the light map): the substance of the trail's ribbon
     * lights the world at the eye.
     */
    public void veil(double dx, double dy, double dz, double half, float r, float g, float b, float a, int light) {
        if (a < 0.002f) return;
        double d = Math.sqrt(dx * dx + dy * dy + dz * dz);
        FxQuads.Part was = out.part(FxQuads.Part.VEIL);
        out.billboard((float) dx, (float) dy, (float) dz, (float) d, (float) half, 0, FxAtlas.white(), Math.min(1, r * a), Math.min(1, g * a),
                Math.min(1, b * a), Math.min(1, a), 0, 1, light, 0);
        out.part(was);
        counts[5]++;
    }

    /**
     * Veil of cloud material near the eye ({@link SmokePuffs.Veil}): a full-screen grid at one depth, with opacity,
     * color (optical depth along its ray) and terrain softness (material location along the ray) at each vertex,
     * interpolated linearly; empty cells are not drawn. The mesh blends at the material's depth, not the nearby grid's
     * depth (otherwise the veil from distant budget entries erased the lighting and texture of puffs in front of them),
     * one depth for all its cells ({@link SmokePuffs.Veil#depth}).
     */
    @Override
    public void veil(SmokePuffs.Veil veil, int light) {
        FxAtlas.Sprite w = FxAtlas.white();
        float u = (w.u0() + w.u1()) / 2, v = (w.v0() + w.v1()) / 2;
        int n = SmokePuffs.Veil.N;
        float depth = veil.depth();
        FxQuads.Part was = out.part(FxQuads.Part.VEIL);
        for (int j = 0; j + 1 < n; j++) {
            for (int i = 0; i + 1 < n; i++) {
                int a = SmokePuffs.Veil.index(i, j), b = SmokePuffs.Veil.index(i + 1, j), c = SmokePuffs.Veil.index(i + 1, j + 1),
                        d = SmokePuffs.Veil.index(i, j + 1);
                if (Math.max(Math.max(veil.alpha(a), veil.alpha(b)), Math.max(veil.alpha(c), veil.alpha(d))) < 0.002f) continue;
                out.quad(depth);
                veilCorner(veil, i, j, a, u, v, light);
                veilCorner(veil, i, j + 1, d, u, v, light);
                veilCorner(veil, i + 1, j + 1, c, u, v, light);
                veilCorner(veil, i + 1, j, b, u, v, light);
            }
        }
        out.part(was);
        counts[5]++;
    }

    private void veilCorner(SmokePuffs.Veil veil, int i, int j, int k, float u, float v, int light) {
        float a = Math.min(1, veil.alpha(k));
        out.vertex((float) veil.place(i, j, 0), (float) veil.place(i, j, 1), (float) veil.place(i, j, 2), u, v, Math.min(1, veil.r[k] * a),
                Math.min(1, veil.g[k] * a), Math.min(1, veil.b[k] * a), a, veil.soft[k], 1, light, 0);
    }

    /**
     * A projectile model in pose pose centred at (dx, dy, dz) from the camera: an atlas tile {@link FarModels} as a
     * square across the ray
     * toward it, covers and shines the share a (air haze, visibility above terrain, transition from the point).
     *
     * @param size  model size ({@code FarLook.size})
     * @param camUp top of the screen: the tile is rotated as the screen is
     * @return false — no model (atlas full, model too close to the camera): a point is needed
     */
    public boolean model(WeaponModels.Look look, ProjectilePose pose, double dx, double dy, double dz, double size, double pixel, Vector3f camUp,
                         float a) {
        if (!models.add(look, pose, dx, dy, dz, size, pixel, camUp, tile)) return false;
        double d = Math.sqrt(dx * dx + dy * dy + dz * dz), k = FarDraw.fold(d, far);
        float x = (float) (dx * k), y = (float) (dy * k), z = (float) (dz * k), h = (float) (tile[FarModels.HALF] * k);
        float rx = tile[FarModels.RIGHT] * h, ry = tile[FarModels.RIGHT + 1] * h, rz = tile[FarModels.RIGHT + 2] * h;
        float ux = tile[FarModels.UP] * h, uy = tile[FarModels.UP + 1] * h, uz = tile[FarModels.UP + 2] * h;
        float u0 = tile[FarModels.U0], v0 = tile[FarModels.V0], u1 = tile[FarModels.U1], v1 = tile[FarModels.V1];
        float c = Math.min(1, a), soft = tile[FarModels.HALF] * BODY_SOFT, unfold = (float) k;
        int light = LightTexture.FULL_BRIGHT;
        // lower-left corner — (u0, v0) of the atlas: the bottom of the atlas is the bottom of the tile
        out.modelTiles(true);
        out.quad((float) d);
        out.vertex(x - rx - ux, y - ry - uy, z - rz - uz, u0, v0, c, c, c, c, soft, unfold, light, 0);
        out.vertex(x + rx - ux, y + ry - uy, z + rz - uz, u1, v0, c, c, c, c, soft, unfold, light, 0);
        out.vertex(x + rx + ux, y + ry + uy, z + rz + uz, u1, v1, c, c, c, c, soft, unfold, light, 0);
        out.vertex(x - rx + ux, y - ry + uy, z - rz + uz, u0, v1, c, c, c, c, soft, unfold, light, 0);
        out.modelTiles(false);
        counts[4]++;
        return true;
    }

    /**
     * A disc of radius radius (of the same flux: the square is {@link #DISC} times larger) of its colour with opacity a
     * over
     * what is behind it: a projectile body, a fireball, a flash core.
     */
    public void disc(double dx, double dy, double dz, double radius, float r, float g, float b, float a) {
        if (billboard(dx, dy, dz, radius * DISC, 0, FxAtlas.disc(), r * a, g * a, b * a, a, (float) radius * BODY_SOFT)) counts[1]++;
    }

    /**
     * A halo in the air that carries the light of a disc of radius radius with brightness a (peak in the middle — a):
     * adds to what
     * is behind it — glow from over a ridge and on clouds. Air at depth deep in front of (dx, dy, dz) glows: the square
     * stands where
     * this air begins — what is nearer, it covers; what is inside, it covers smoothly (soft edge by distance to the
     * world, with DH LOD).
     * At the source's place the square would cut straight through the buildings at the same distance: at night the glow
     * from over a
     * ridge the width of the screen would lie on houses in patches and stripes, and on DH LODs, which the hardware
     * depth does not see, it would lie over them.
     * Glare and veil — at the eye ({@link #EYE}).
     */
    public void glow(double dx, double dy, double dz, double radius, double deep, float r, float g, float b, float a) {
        if (r * a + g * a + b * a < 0.006f) return;
        double d = Math.sqrt(dx * dx + dy * dy + dz * dz);
        double cos = (dx * fx + dy * fy + dz * fz) / Math.max(d, 1e-9);
        // no nearer than the camera, any square of the layer fades (fx.fsh: fully visible from 1.5 blocks along the
        // view axis)
        double front = Math.max(d - deep, AIR_NEAR / Math.max(cos, 0.1));
        FxQuads.Part was = out.part(FxQuads.Part.GLARE);
        out.glowShape(true);
        if (front >= d) {
            if (billboard(dx, dy, dz, radius * GLOW, 0, FxAtlas.glow(), r * a, g * a, b * a, 0, 0)) counts[2]++;
        } else {
            double unfold = FarDraw.fold(front, far), k = front / d * unfold;
            out.billboard((float) (dx * k), (float) (dy * k), (float) (dz * k), (float) d, (float) (radius * GLOW * k), 0, FxAtlas.glow(), Math.min(1, r * a),
                    Math.min(1, g * a), Math.min(1, b * a), 0, (float) (d - front), (float) unfold, LightTexture.FULL_BRIGHT, 0);
            counts[2]++;
        }
        out.glowShape(false);
        out.part(was);
    }

    /**
     * A bright body centred at (dx, dy, dz) from the camera with radius radius, brightness seen against the white
     * screen (with eye adaptation
     * and air), colours (r, g, b) ({@link Sight#light}): the core — its own light over what is behind it (overexposure
     * goes to white,
     * dimmer than white — fades and melts: a cooled ball is already smoke), glare and veil — halos of the same colour.
     *
     * @param t     share of light that came through the air: how much the body covers what is behind it
     * @param w     share of the core (visible above terrain, transition to the near picture)
     * @param glare share of glare and veil: their depth does not cut, so what is covered along the caller's rays is
     * here (in the frame, also
     *              the {@link SourceProbes} probe)
     * @param out   numbers of {@link Sight#light} (5) — for the log
     */
    public void light(double dx, double dy, double dz, double radius, double seen, double t, double pixel, float r, float g, float b, double w,
                      double glare, double[] out) {
        double d = Math.sqrt(dx * dx + dy * dy + dz * dz);
        Sight.light(radius, seen, d, pixel, out);
        core(dx, dy, dz, out, t, r, g, b, w);
        glare(dx, dy, dz, radius, out, seen, r, g, b, glare, 1);
    }

    /**
     * A bright body with a shape, as {@link #light}: larger than {@link #SHAPE_FULL} pixels — a picture of half-size
     * half (a fireball frame,
     * a flame puff: the colour is in it); smaller than {@link #SHAPE_FROM} — a disc with the same light; between them —
     * a mix.
     * The body and the light at the eye — each with its own share: up close the body is drawn by particles, and glare
     * and veil here.
     *
     * @param radius radius of the glowing body now (light, glare, veil, point), blocks
     * @param half   half-size of the picture, blocks (whether the body grows — in its frames)
     * @param mix    mix of the fire frame with the next (one fragment, with multiplied alpha)
     * @param body   share of the body (visible above terrain, transition to particles, fading)
     * @param glare  share of glare and veil
     * @param shown  the body on screen in the picture (share of white): glare and veil are not brighter — a ball cooled
     * to soot does not dazzle
     * @param hdr    how many times the body is brighter than its picture on screen (the fire frame is shown at its own
     * exposure): glow
     *               ({@code FxBloom}) takes the overexposure, not more than the white point
     */
    public void shaped(double dx, double dy, double dz, double radius, FxAtlas.Sprite sprite, double half, float mix, double seen, double t, double pixel,
                       float r, float g, float b, double body, double glare, double shown, double hdr, double[] out) {
        double d = Math.sqrt(dx * dx + dy * dy + dz * dz);
        Sight.light(radius, seen, d, pixel, out);
        double shape = smoothstep(SHAPE_FROM, SHAPE_FULL, radius / Math.max(d * pixel, 1e-12));
        if (shape < 1) core(dx, dy, dz, out, t, r, g, b, body * (1 - shape));
        float a = (float) (t * body * shape);
        // the fire frame glows (FxBloom: the halo follows the fire's shape, with the model's overexposure against the
        // frame)
        FxQuads.Part was = this.out.part(FxQuads.Part.FIRE);
        this.out.fireballMix(mix);
        this.out.emission((float) Math.max(1, hdr));
        if (shape > 0 && billboard(dx, dy, dz, half, fireballRotation(), sprite, a, a, a, a, (float) (radius * BODY_SOFT), BALL_FRONT * radius)) counts[1]++;
        this.out.emission(0);
        this.out.fireballMix(-1);
        this.out.part(was);
        glare(dx, dy, dz, radius, out, seen, r, g, b, glare, shown);
    }

    /** A baked thermal has a top: a random rotation of the ball would point the flame sideways and down. */
    static float fireballRotation() {
        return 0;
    }

    /**
     * Glare and veil of a bright body ({@link #light}) without the body itself: the body is drawn by the volume (a nuke
     * fireball —
     * in the pall pass, {@code fx_pall}); the light at the eye is here, with the body's visibility probe ({@link
     * SourceProbes}: world,
     * pall and dust wall, mushrooms in front of it).
     *
     * @param seen  brightness of the body against the white screen (with eye adaptation and air)
     * @param shown the body on screen (share of white): glare and veil are not brighter
     * @param out   numbers of {@link Sight#light} (5)
     */
    public void eyeLight(double dx, double dy, double dz, double radius, double seen, double pixel, float r, float g, float b, double shown,
                         double[] out) {
        Sight.light(radius, seen, Math.sqrt(dx * dx + dy * dy + dz * dz), pixel, out);
        glare(dx, dy, dz, radius, out, seen, r, g, b, 1, shown);
    }

    /**
     * The core of {@link #light} as a disc: its own light over what is behind it; overexposure goes to white, dimmer
     * than white — darker in linear
     * light and to the screen in sRGB ({@link Sight#onScreen}, as a jet and flame tongues up close: at 0.8 of the draw
     * distance without a jump) and melts.
     */
    private void core(double dx, double dy, double dz, double[] o, double t, float r, float g, float b, double w) {
        double s = o[2];
        float[] c = Sight.onScreen(r, g, b, s, (float) w);
        float a = (float) (o[1] * t * Math.min(1, 4 * s) * w);
        // glows (FxBloom) with its own overexposure: the screen showed it white
        FxQuads.Part was = out.part(FxQuads.Part.FIRE);
        out.emission((float) Math.max(1, s));
        if (billboard(dx, dy, dz, o[0] * DISC, 0, FxAtlas.disc(), c[0], c[1], c[2], a, (float) (o[0] * BODY_SOFT))) counts[1]++;
        out.emission(0);
        out.part(was);
    }

    /**
     * Glare and veil of {@link #light} of a body with radius radius as halos of its colour at the eye itself ({@link
     * #EYE}), not brighter than the body on
     * the screen shown: share w — how much of the body is open along the caller's rays; in the frame — also the body's
     * probe ({@link SourceProbes}).
     */
    private void glare(double dx, double dy, double dz, double radius, double[] o, double seen, float r, float g, float b, double w, double shown) {
        if (w <= 0) return;
        int probe = probes.add(dx, dy, dz, radius);
        FxQuads.Part was = out.part(FxQuads.Part.GLARE);
        eye(dx, dy, dz, o[3], r, g, b, (float) (Math.min(Math.min(Sight.HALO, Sight.SCATTER * seen), shown) * w), probe);
        if (o[4] > 0) eye(dx, dy, dz, o[4], r, g, b, (float) (Math.min(Sight.VEIL_PEAK, shown) * w), probe);
        out.part(was);
    }

    /**
     * A halo {@link #glow} moved to the eye: the same rays from the eye, sorted by true distance; depth
     * not less than {@link #EYE_DEPTH} (at the side — farther from the eye); visible as far as the source is seen
     * through the probe probe.
     */
    private void eye(double dx, double dy, double dz, double radius, float r, float g, float b, float a, int probe) {
        if (r * a + g * a + b * a < 0.006f) return;
        double d = Math.sqrt(dx * dx + dy * dy + dz * dz);
        double cos = (dx * fx + dy * fy + dz * fz) / Math.max(d, 1e-9);
        double k = Math.max(EYE, EYE_DEPTH / Math.max(cos, 1e-3)) / Math.max(d, 1e-9);
        if (k >= 1) {
            glow(dx, dy, dz, radius, 0, r, g, b, a);
            return;
        }
        out.seenBy(probe);
        out.glowShape(true);
        out.billboard((float) (dx * k), (float) (dy * k), (float) (dz * k), (float) d, (float) (radius * GLOW * k), 0, FxAtlas.glow(), Math.min(1, r * a),
                Math.min(1, g * a), Math.min(1, b * a), 0, 0, (float) k, LightTexture.FULL_BRIGHT, 0);
        out.glowShape(false);
        out.seenBy(0);
        counts[2]++;
    }

    private static double smoothstep(double a, double b, double x) {
        double t = Math.max(0, Math.min(1, (x - a) / (b - a)));
        return t * t * (3 - 2 * t);
    }

    /**
     * A segment of a trail as a ribbon facing the camera: from (ax..) to (bx..), half-widths (already with {@link
     * #RIBBON}), opacity and
     * the world light of the ends (packed, for the light map), its own colour. Across the ribbon — a perpendicular to
     * the segment and to the view
     * ray (any point on it gives the same); joint on input — across at end a, common with the previous segment of the
     * same ribbon
     * (NaN in [0] — none: by this segment), on output — across at end b.
     * <p>
     * Looking along the segment, the smoke tube is seen end-on: on screen it is shorter than its width, and the ribbon
     * degenerates —
     * the perpendicular to a segment and a ray almost parallel is random; neighbouring segments lie in a fan of bands
     * with a straight edge
     * (near the nozzle they are also lit by the torch). But the cross-section of the tube is seen — a disc of its
     * width, while the thickness along the view is the segment's length
     * against its width. Shorter than the width on screen, the segment is drawn as a disc (the halo of the same trail)
     * of that thickness; longer —
     * as a ribbon; between, smoothly ({@link #endOn}).
     *
     * @return the segment is written as a ribbon (joint — new)
     */
    public boolean ribbon(double ax, double ay, double az, double halfA, float alphaA, int lightA, double bx, double by, double bz, double halfB,
                          float alphaB, int lightB, float r, float g, float b, float[] joint) {
        RibbonShape q = SHAPE;
        FxQuads.Part was = out.part(FxQuads.Part.RIBBONS);
        if (!q.set(far, fx, fy, fz, ax, ay, az, halfA, alphaA, bx, by, bz, halfB, alphaB, joint)) {
            if (q.cap) joint[0] = Float.NaN;
            emitVeilAndCap(q, r, g, b, lightA);
            out.part(was);
            return false;
        }
        emitVeilAndCap(q, r, g, b, lightA);
        float aa = Math.min(1, q.alphaA), ab = Math.min(1, q.alphaB), cr = Math.min(1, r), cg = Math.min(1, g), cb = Math.min(1, b);
        float soft = (float) Math.max(halfA, halfB), ua = (float) q.ka, ub = (float) q.kb;
        FxAtlas.Sprite s = FxAtlas.glow();
        float v = s.vMid();
        out.glowShape(true);
        out.quad((float) Math.max(q.da, q.db));
        out.vertex(q.x0 - q.px * q.ha, q.y0 - q.py * q.ha, q.z0 - q.pz * q.ha, s.u0(), v, cr * aa, cg * aa, cb * aa, aa, soft, ua, lightA, 0);
        out.vertex(q.x0 + q.px * q.ha, q.y0 + q.py * q.ha, q.z0 + q.pz * q.ha, s.u1(), v, cr * aa, cg * aa, cb * aa, aa, soft, ua, lightA, 0);
        out.vertex(q.x1 + q.nx * q.hb, q.y1 + q.ny * q.hb, q.z1 + q.nz * q.hb, s.u1(), v, cr * ab, cg * ab, cb * ab, ab, soft, ub, lightB, 0);
        out.vertex(q.x1 - q.nx * q.hb, q.y1 - q.ny * q.hb, q.z1 - q.nz * q.hb, s.u0(), v, cr * ab, cg * ab, cb * ab, ab, soft, ub, lightB, 0);
        out.glowShape(false);
        out.part(was);
        counts[3]++;
        joint[0] = q.nx;
        joint[1] = q.ny;
        joint[2] = q.nz;
        return true;
    }

    private static final RibbonShape SHAPE = new RibbonShape();

    /** The "eye in the tube" veil and the tube's end as a disc ({@link RibbonShape}), if any. */
    private void emitVeilAndCap(RibbonShape q, float r, float g, float b, int lightA) {
        if (q.veil > 0.004f) veil(fx * VEIL_AT, fy * VEIL_AT, fz * VEIL_AT, VEIL_AT * 4, Math.min(1, r), Math.min(1, g), Math.min(1, b), q.veil, lightA);
        if (q.capAlpha > 0) {
            float k = q.capAlpha;
            out.glowShape(true);
            // softness of the edge at the world — in real blocks, as for all squares (halfW — in those moved nearer)
            out.billboard(q.mx, q.my, q.mz, (float) (0.5 * (q.da + q.db)), (float) (q.halfW * GLOW), 0, FxAtlas.glow(), Math.min(1, r) * k,
                    Math.min(1, g) * k, Math.min(1, b) * k, k, (float) q.softCap, (float) (0.5 * (q.ka + q.kb)), lightA, 0);
            out.glowShape(false);
            counts[3]++;
        }
    }

    /**
     * What the ribbon segment ({@link #ribbon}) will draw — one geometry for drawing, the frame's plan ({@code
     * SmokePuffs}: filling the ribbon
     * is within the budget) and checks: ends (moved nearer, {@link FarDraw#fold}), cross-sections at the ends,
     * half-widths,
     * the "eye in the tube" veil and the disc of the end with their opacity, the strip's opacities; {@link #set} —
     * whether the strip will be laid.
     */
    static final class RibbonShape {
        float x0, y0, z0, x1, y1, z1, mx, my, mz, px, py, pz, nx, ny, nz, ha, hb, alphaA, alphaB, veil, capAlpha;
        double da, db, ka, kb, halfW, softCap;
        /** The segment entirely end-on (a disc without a strip): no cross-section at b. */
        boolean cap;

        /**
         * Geometry of the segment from (ax..) to (bx..) when looking at (fx, fy, fz) with the move far; joint — across
         * at a (does not change).
         */
        boolean set(double far, float fx, float fy, float fz, double ax, double ay, double az, double halfA, float alphaA, double bx, double by,
                    double bz, double halfB, float alphaB, float[] joint) {
            veil = capAlpha = 0;
            cap = false;
            this.alphaA = alphaA;
            this.alphaB = alphaB;
            if (alphaA < 0.002f && alphaB < 0.002f) return false;
            da = Math.sqrt(ax * ax + ay * ay + az * az);
            db = Math.sqrt(bx * bx + by * by + bz * bz);
            ka = FarDraw.fold(da, far);
            kb = FarDraw.fold(db, far);
            x0 = (float) (ax * ka);
            y0 = (float) (ay * ka);
            z0 = (float) (az * ka);
            x1 = (float) (bx * kb);
            y1 = (float) (by * kb);
            z1 = (float) (bz * kb);
            float tx = x1 - x0, ty = y1 - y0, tz = z1 - z0;
            mx = (x0 + x1) * 0.5f;
            my = (y0 + y1) * 0.5f;
            mz = (z0 + z1) * 0.5f;
            nx = ty * mz - tz * my;
            ny = tz * mx - tx * mz;
            nz = tx * my - ty * mx;
            float len = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
            // length of the segment on screen (across the ray to its midpoint) against its width: end-on — a disc
            double seg = Math.sqrt(tx * tx + ty * ty + tz * tz), across = len / Math.max(Math.sqrt(mx * mx + my * my + mz * mz), 1e-9);
            // the eye in the tube itself (nearer than the half-width to the segment's axis, from the half-width —
            // smoothly): the ribbon is a plane through the eye; its
            // substance around the eye — as a veil: thickness along the view — the path in the tube (the segment's
            // length or its width times the sine of the angle to it)
            // in widths
            double hw = 0.5 * (halfA * ka + halfB * kb) / RIBBON, gap = distance(x0, y0, z0, tx, ty, tz);
            double inside = 1 - smoothstep(0.5 * hw, hw, gap);
            if (inside > 0) {
                double sin = Math.sqrt(Math.max(0, 1 - sq((tx * fx + ty * fy + tz * fz) / Math.max(seg, 1e-9))));
                double path = Math.min(seg, 2 * hw / Math.max(sin, 0.1));
                double a = 0.5 * (Math.min(1, this.alphaA) + Math.min(1, this.alphaB));
                // thickness — the path of this segment in widths, without the floor: for neighbouring segments the
                // paths add up to the true view path
                veil = (float) (inside * (1 - Math.pow(1 - Math.min(a, 0.999), path / (2 * Math.max(hw, 1e-6)))));
                this.alphaA *= (float) (1 - inside);
                this.alphaB *= (float) (1 - inside);
                if (this.alphaA < 0.002f && this.alphaB < 0.002f) return false;
            }
            // end-on — only when the segment and the ray are almost parallel (sin up to 0.35, toward 0.5 — smoothly to
            // the ribbon): sideways a short segment
            // of a wide old trail is a ribbon, otherwise the discs of neighbouring segments would lie over each other
            // and thicken the trail
            double tilt = Math.max(0, Math.min(1, (across / Math.max(seg, 1e-9) - 0.35) / 0.15));
            double side = Math.max(endOn(across, 0.5 * (halfA * ka + halfB * kb) / RIBBON), tilt * tilt * (3 - 2 * tilt));
            if (side < 1) {
                // end of the tube: a disc of its width, thickness along the view — the segment's length in widths (the
                // ribbon's opacity — across)
                halfW = 0.5 * (halfA * ka + halfB * kb) / RIBBON;
                softCap = 0.5 * (halfA + halfB) / RIBBON;
                double a = 0.5 * (Math.min(1, this.alphaA) + Math.min(1, this.alphaB));
                double along = 1 - Math.pow(1 - Math.min(a, 0.999), seg / (2 * Math.max(halfW, 1e-6)));
                capAlpha = (float) ((1 - side) * along);
                if (side <= 0 || len < 1e-6f) {
                    cap = true;
                    return false;
                }
                this.alphaA *= (float) side;
                this.alphaB *= (float) side;
            }
            if (len < 1e-6f) return false;
            nx /= len;
            ny /= len;
            nz /= len;
            px = nx;
            py = ny;
            pz = nz;
            if (!Float.isNaN(joint[0]) && joint[0] * nx + joint[1] * ny + joint[2] * nz > 0) {
                px = joint[0];
                py = joint[1];
                pz = joint[2];
            }
            ha = (float) (halfA * ka);
            hb = (float) (halfB * kb);
            return true;
        }

        /**
         * How many screens the segment's squares fill in the view view: the "eye in the tube" veil (square at the eye),
         * the disc of the end,
         * the strip ({@code drawn} — {@link #set} returned true) — each by its own vertices on screen ({@code
         * SmokePuffs.polygonFill}).
         */
        double fill(FarView view, boolean drawn) {
            double f = 0;
            org.joml.Vector3f fw = view.forward();
            if (veil > 0.004f) f += SmokePuffs.cover(view, fw.x * VEIL_AT, fw.y * VEIL_AT, fw.z * VEIL_AT, VEIL_AT * 4, VEIL_AT * 4);
            if (capAlpha > 0) f += SmokePuffs.cover(view, mx, my, mz, halfW * GLOW, halfW * GLOW);
            if (drawn) {
                double[] q = strip;
                q[0] = x0 - px * ha;
                q[1] = y0 - py * ha;
                q[2] = z0 - pz * ha;
                q[3] = x0 + px * ha;
                q[4] = y0 + py * ha;
                q[5] = z0 + pz * ha;
                q[6] = x1 + nx * hb;
                q[7] = y1 + ny * hb;
                q[8] = z1 + nz * hb;
                q[9] = x1 - nx * hb;
                q[10] = y1 - ny * hb;
                q[11] = z1 - nz * hb;
                f += SmokePuffs.polygonFill(view, q);
            }
            return f;
        }

        /** The strip's four vertices for {@link #fill}, without a per-call array. */
        private final double[] strip = new double[12];
    }

    /**
     * A glowing strip — a torch's jet up close ({@code PlumeRenderer}): corners a, b — at one end, c, d — at the other
     * (from the
     * camera), the light of these ends (on screen, with overexposure to white, multiplied by the share). Across — the
     * profile of the halo
     * {@code far/glow} (soft edge, like blending at a jet's edge), the square is wider by {@link #STRIP_CROSS} times:
     * the light across is
     * the same as for a flat strip of this width (with the disc {@code far/disc} the edge was hard, F6 frames). Only
     * shines (alpha 0):
     * what is behind it stays visible. Sorted by key key (all pieces of one jet have one: its nearest point minus the
     * puffs at the axis):
     * the smoke of its exhaust lies under the fire, not between its pieces; nearer smoke — over it. Glows (the {@code
     * FxBloom} glow):
     * hdr0, hdr1 — brightness of the ends against the white screen before the clip ({@link Sight#onScreen}).
     */
    public void glowStrip(Vector3f a, Vector3f b, Vector3f c, Vector3f d, float r0, float g0, float b0, float r1, float g1, float b1, float soft,
                          float key, float hdr0, float hdr1) {
        if (r0 + g0 + b0 < 0.006f && r1 + g1 + b1 < 0.006f) return;
        widen(a, b);
        widen(d, c);
        double da = a.length(), db = b.length(), dc = c.length(), dd = d.length();
        float ka = (float) FarDraw.fold(da, far), kb = (float) FarDraw.fold(db, far), kc = (float) FarDraw.fold(dc, far), kd = (float) FarDraw.fold(dd, far);
        FxAtlas.Sprite s = FxAtlas.glow();
        float v = s.vMid();
        r0 = Math.min(1, r0);
        g0 = Math.min(1, g0);
        b0 = Math.min(1, b0);
        r1 = Math.min(1, r1);
        g1 = Math.min(1, g1);
        b1 = Math.min(1, b1);
        out.glowShape(true);
        out.quad(key);
        out.emission(Math.max(1, hdr0));
        out.vertex(a.x * ka, a.y * ka, a.z * ka, s.u0(), v, r0, g0, b0, 0, soft, ka, LightTexture.FULL_BRIGHT, 0);
        out.vertex(b.x * kb, b.y * kb, b.z * kb, s.u1(), v, r0, g0, b0, 0, soft, kb, LightTexture.FULL_BRIGHT, 0);
        out.emission(Math.max(1, hdr1));
        out.vertex(c.x * kc, c.y * kc, c.z * kc, s.u1(), v, r1, g1, b1, 0, soft, kc, LightTexture.FULL_BRIGHT, 0);
        out.vertex(d.x * kd, d.y * kd, d.z * kd, s.u0(), v, r1, g1, b1, 0, soft, kd, LightTexture.FULL_BRIGHT, 0);
        out.emission(0);
        out.glowShape(false);
        counts[2]++;
    }

    /**
     * How many times the {@code far/glow} strip is wider than a flat one of the same light across: the middle of the
     * disc {@code far/disc} across
     * covers ~0.93 of the width, the halo exp(−(u/0.42)²) — 0.42·√π/2 ≈ 0.372 (share of the width, not an integral over
     * it), ≈ 2.5.
     */
    static final float STRIP_CROSS = (float) (0.93 / (0.42 * Math.sqrt(Math.PI) / 2));

    /** A segment p–q across the jet — wider by {@link #STRIP_CROSS} times than its middle. */
    private static void widen(Vector3f p, Vector3f q) {
        float mx = 0.5f * (p.x + q.x), my = 0.5f * (p.y + q.y), mz = 0.5f * (p.z + q.z);
        p.set(mx + (p.x - mx) * STRIP_CROSS, my + (p.y - my) * STRIP_CROSS, mz + (p.z - mz) * STRIP_CROSS);
        q.set(mx + (q.x - mx) * STRIP_CROSS, my + (q.y - my) * STRIP_CROSS, mz + (q.z - mz) * STRIP_CROSS);
    }

    /**
     * A glowing disc of radius radius facing the camera (a cross-section of a torch's jet end-on, {@code
     * PlumeRenderer}): light (r, g, b)
     * on screen, it only shines; hdr — its brightness against the white screen before the clip (the {@code FxBloom}
     * glow).
     */
    public void glowDisc(double dx, double dy, double dz, double radius, float r, float g, float b, float soft, float hdr) {
        FxQuads.Part was = out.part(FxQuads.Part.FIRE);
        out.emission(Math.max(1, hdr));
        if (billboard(dx, dy, dz, radius * DISC, 0, FxAtlas.disc(), r, g, b, 0, soft)) counts[2]++;
        out.emission(0);
        out.part(was);
    }

    /**
     * A glowing disc in its plane (a cut of a hot nozzle, {@code PlumeRenderer}): centre c from the camera, a and b —
     * its
     * radii along the two axes of the plane (the disc {@code far/disc} of the same flux; the square is {@link #DISC}
     * times larger); at the side
     * it degenerates into a segment, like a real cut. Light (r, g, bl) on screen, it only shines; without softness at
     * terrain: beyond the
     * cut, centimetres away, is the body itself. Sorted by key key; hdr — brightness against the white screen before
     * the clip (the glow
     * {@code FxBloom}).
     */
    public void glowPlate(Vector3f c, Vector3f a, Vector3f b, float r, float g, float bl, float key, float hdr) {
        if (r + g + bl < 0.006f) return;
        FxAtlas.Sprite s = FxAtlas.disc();
        r = Math.min(1, r);
        g = Math.min(1, g);
        bl = Math.min(1, bl);
        FxQuads.Part was = out.part(FxQuads.Part.FIRE);
        out.quad(key);
        out.emission(Math.max(1, hdr));
        plateCorner(c, a, b, -1, -1, s.u0(), s.v0(), r, g, bl);
        plateCorner(c, a, b, -1, 1, s.u0(), s.v1(), r, g, bl);
        plateCorner(c, a, b, 1, 1, s.u1(), s.v1(), r, g, bl);
        plateCorner(c, a, b, 1, -1, s.u1(), s.v0(), r, g, bl);
        out.emission(0);
        out.part(was);
        counts[2]++;
    }

    private void plateCorner(Vector3f c, Vector3f a, Vector3f b, float sa, float sb, float u, float v, float r, float g, float bl) {
        float k = (float) DISC;
        float x = c.x + (a.x * sa + b.x * sb) * k, y = c.y + (a.y * sa + b.y * sb) * k, z = c.z + (a.z * sa + b.z * sb) * k;
        float f = (float) FarDraw.fold(Math.sqrt(x * x + y * y + z * z), far);
        out.vertex(x * f, y * f, z * f, u, v, r, g, bl, 0, 0, f, LightTexture.FULL_BRIGHT, 0);
    }

    /**
     * The veil of the trail's tube around the eye — this many blocks in front of it (as for clouds, {@link
     * SmokePuffs#VEIL_AT}).
     */
    static final double VEIL_AT = SmokePuffs.VEIL_AT;

    /** Distance from the eye (the origin) to a segment from (x0, y0, z0) of length (tx, ty, tz). */
    static double distance(double x0, double y0, double z0, double tx, double ty, double tz) {
        double t2 = tx * tx + ty * ty + tz * tz;
        double u = t2 < 1e-12 ? 0 : Math.max(0, Math.min(1, -(x0 * tx + y0 * ty + z0 * tz) / t2));
        double px = x0 + tx * u, py = y0 + ty * u, pz = z0 + tz * u;
        return Math.sqrt(px * px + py * py + pz * pz);
    }

    private static double sq(double v) {
        return v * v;
    }

    /**
     * Share of a trail segment drawn as a ribbon, at its length on screen across (across the view ray, blocks in place)
     * and the
     * half-width of the tube halfWidth: longer than two widths — all of it; shorter than the width — a disc of the end
     * ({@link #ribbon}), between — smoothly.
     */
    static double endOn(double across, double halfWidth) {
        double u = Math.max(0, Math.min(1, (across / Math.max(2 * halfWidth, 1e-9) - 1)));
        return u * u * (3 - 2 * u);
    }

    /** Square: light (r, g, b) already multiplied by opacity a; with no light and no cover — it is not written. */
    private boolean billboard(double dx, double dy, double dz, double half, float rot, FxAtlas.Sprite sprite, float r, float g, float b, float a,
                              float soft) {
        return billboard(dx, dy, dz, half, rot, sprite, r, g, b, a, soft, 0);
    }

    /** A square of the same angular size moved {@code ahead} blocks nearer the eye ({@link #front}). */
    private boolean billboard(double dx, double dy, double dz, double half, float rot, FxAtlas.Sprite sprite, float r, float g, float b, float a,
                              float soft, double ahead) {
        if (a < 0.002f && r + g + b < 0.006f) return false;
        double d = Math.sqrt(dx * dx + dy * dy + dz * dz), shift = front(d, ahead), unfold = FarDraw.fold(d * shift, far), k = shift * unfold;
        // the vertex colour is a byte: more than 1 would overflow
        out.billboard((float) (dx * k), (float) (dy * k), (float) (dz * k), (float) d, (float) (half * k), rot, sprite, Math.min(1, r), Math.min(1, g),
                Math.min(1, b), Math.min(1, a), soft, (float) unfold, LightTexture.FULL_BRIGHT, 0);
        return true;
    }

    /**
     * How many times nearer to the eye to place a square at distance d so that it stands ahead blocks nearer, with the
     * same
     * angular size (size × the same number); not nearer than {@link #AIR_NEAR}, at the eye itself — in place.
     */
    public static double front(double d, double ahead) {
        if (ahead <= 0 || d <= AIR_NEAR) return 1;
        return Math.max(d - ahead, AIR_NEAR) / d;
    }
}
