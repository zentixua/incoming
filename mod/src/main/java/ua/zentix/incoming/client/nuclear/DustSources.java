package ua.zentix.incoming.client.nuclear;

import net.minecraft.world.phys.Vec3;
import ua.zentix.incoming.Incoming;
import ua.zentix.incoming.client.fx.layer.DustWallPass;
import ua.zentix.incoming.client.fx.layer.PallPass;
import ua.zentix.incoming.nuclear.Detonation;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * The one owner of nuclear ground dust on screen: every detonation's lofted ground matter (soil dust, sand, water spray, a water
 * burst's base surge), from birth at the contact line to the last thin haze, is drawn by the dust wave ({@link DustWallPass},
 * {@code dustwall.glsl}) in every condition (water, several detonations, every preset); no other renderer draws ground dust
 * (the pall keeps the mushroom, the Wilson shell and fire smoke). Dust OFF draws none ({@link #wanted}).
 * <p>
 * Builds the pass's inputs per frame ({@link DustWallPass.Frame}): the detonations whose dust is still on their table, nearest
 * first, at most {@link DustWallPass#MAX_SOURCES}; per source the whole-lifetime law table ({@link PallField#lifetime}, built once),
 * the frame inputs, its terrain map and its committed ground material.
 */
public final class DustSources {
    private DustSources() {}

    /** Owners of ground dust that exist (A2): the dust wave alone. */
    public enum Owner { DUST_WAVE }

    /** Conditions the owner selection is asked about (water under the dust, several detonations, a secondary view, a preset, programs not ready). */
    public record Conditions(boolean water, int detonations, boolean secondaryView, PallPass.Quality quality, boolean ready) {}

    /**
     * The owner of the ground dust under {@code c}: the dust wave, or none (dust OFF, or no program to draw it: then nothing
     * draws it, and {@link #frame} warns). No condition selects another picture.
     */
    public static java.util.Optional<Owner> owner(Conditions c) {
        if (c.quality() == PallPass.Quality.OFF || !c.ready() || c.detonations() <= 0) return java.util.Optional.empty();
        return java.util.Optional.of(Owner.DUST_WAVE);
    }

    /** Whether the player's setting draws ground dust: OFF hides the dust wave (and nothing else draws it). */
    static boolean wanted() {
        return NukePall.quality() != PallPass.Quality.OFF;
    }

    private static final double[] DUST_W = albedo(NukePall.DUST, PallLight.DUST_G), SAND_W = albedo(NukePall.SAND, PallLight.DUST_G);
    /** Water droplets' thick-layer colour ({@link CloudPuffs#SPRAY}) and single-scattering albedo. */
    private static final double[] MIST = {(CloudPuffs.SPRAY >> 16 & 255) / 255.0, (CloudPuffs.SPRAY >> 8 & 255) / 255.0, (CloudPuffs.SPRAY & 255) / 255.0};
    private static final double[] MIST_W = albedo(MIST, PallLight.MIST_G);

    private static double[] albedo(double[] display, double g) {
        double[] w = new double[3];
        for (int c = 0; c < 3; c++) w[c] = PallLight.albedo(PallLight.linear(display[c]), PallLight.truncated(g));
        return w;
    }

    /** Per detonation: the dust exposure its eye has reached (value, the detonation clock's second). */
    private static final Map<Object, Map<ClientNuclear.Active, double[]>> ADAPTED = new WeakHashMap<>();
    /**
     * Per detonation: when it was first drawn (client millis) and whether it was then already aged (a late join, a relog into a
     * running show, programs ready after the wave began): such a source fades in over {@link #FADE_IN_MS} of own time.
     */
    private static final Map<ClientNuclear.Active, long[]> FIRST = new WeakHashMap<>();
    /** Fade-in of a source first drawn already aged, client milliseconds (presentation, live clients only). */
    static final long FADE_IN_MS = 1000;
    /** Model seconds after the burst from which a first draw counts as already aged. */
    static final double AGED_S = 1.0;
    /** Per detonation: frames its visible dust went undrawn, and whether that was reported (A3). */
    private static final Map<ClientNuclear.Active, int[]> UNDRAWN = new WeakHashMap<>(), UNDRAWN_SECONDARY = new WeakHashMap<>();
    /** Per view (primary, secondary): exposure belongs to the view, and each view's own undrawn frames warn. */
    private static <V> Map<ClientNuclear.Active, V> perView(Map<ClientNuclear.Active, V> primary, Map<ClientNuclear.Active, V> secondary) {
        return ua.zentix.incoming.client.fx.layer.FxViews.secondary() ? secondary : primary;
    }
    /** Frames a visible show's dust may go undrawn before the WARN (A3). */
    static final int UNDRAWN_FRAMES = 20;
    /**
     * Diagnostics (A3's scenario check): the pass counts as not ready for this many frames after the first frame with visible
     * dust ({@code -Dincoming.dustwave.notReadyFrames}); nothing else may draw the dust meanwhile.
     */
    private static final int FORCE_NOT_READY = Integer.getInteger("incoming.dustwave.notReadyFrames", 0);
    private static int visibleFrames;
    /** nukeDiag: the primary eye in the lead source's dust (its floor, material and drawn column) for the per-second line. */
    private static String eyeLine = "";

    /** A4: once a second under {@code -Dincoming.nukeDiag=true}, per view: the eligible and the drawn sources. */
    private static final boolean DIAG = Boolean.getBoolean("incoming.nukeDiag");
    private static final long[] lastSummary = new long[2];

    /** Presentation exposure of the dust lit by sun and sky alone ({@code DwAdapt}, tools/uw_probe: the reference curtain's luma). */
    static final double DUST_ADAPT = 3.1;
    /** Eye adaptation time constants, s (presentation, est.): opening to a darker scene, closing to a brighter one. */
    static final double ADAPT_UP_S = 2, ADAPT_DOWN_S = 8;
    /** Share of the diffuse light's decay inside the dust that the eye takes back ({@code DwAdapt}; presentation). */
    static final double DUST_ADAPT_INSIDE = 0.7;
    /** Diffuse decay of the reference dust, k² = 3(1 − ω')(1 − ω'g') at its green albedo ({@code pallK}). */
    static final double DUST_K = PallLight.k(0.8099, 0.375);
    /** Ground bounce under the dust: share of sun and sky light the burnt ground returns (est.). */
    private static final double GROUND_BOUNCE = 0.1;
    /** The wave's presentation height ({@link PallField#PRESENT_HEIGHT}). */
    static final double PRESENT_HEIGHT = PallField.PRESENT_HEIGHT;

    private static final float[] GLOW = new float[8];
    /** Per-frame scratch (client thread): the frame's drawn sources, a source's surge, fire colours, the eye's column. */
    private static final List<ClientNuclear.Active> DRAWN = new ArrayList<>();
    private static final double[] SURGE = new double[3], COLUMN = new double[2];
    private static final float[] FIRE_COLOUR = new float[3], LIT = new float[3];

    /**
     * The dust wave's inputs for this frame into {@code f.dustWall} (frame light already in {@code f}); false — no source (none
     * visible, dust OFF, or the pass not ready: the latter warns after {@link #UNDRAWN_FRAMES} frames, once per detonation).
     */
    static boolean frame(List<ClientNuclear.Active> all, Vec3 eye, float partial, PallPass.Frame f) {
        DustWallPass.Frame w = f.dustWall;
        w.active = false;
        w.count = 0;
        DustWallPass.hazeOnly = false;
        if (!wanted() || all.isEmpty()) return false;
        List<ClientNuclear.Active> drawn = DRAWN;
        drawn.clear();
        for (ClientNuclear.Active a : all) if (visible(a, partial)) drawn.add(a);
        if (drawn.isEmpty()) return false;
        visibleFrames++;
        boolean ready = DustWallPass.ready() && visibleFrames > FORCE_NOT_READY;
        for (ClientNuclear.Active a : drawn) undrawn(a, ready);
        // the one owner decision (A2, DustOwnershipTest): the dust wave, or nothing
        boolean water = false;
        for (ClientNuclear.Active a : drawn) water |= a.ground.hasWater();
        if (owner(new Conditions(water, drawn.size(), ua.zentix.incoming.client.fx.layer.FxViews.secondary(), NukePall.quality(),
                ready)).isEmpty()) {
            summary(drawn, w);
            return false;
        }
        drawn.sort(Comparator.comparingDouble(a -> outside(a, eye, partial)));
        if (drawn.size() > DustWallPass.MAX_SOURCES && !warnedCap) {
            warnedCap = true;
            Incoming.LOG.warn("Nuclear dust wave: {} detonations' dust in view, the nearest {} drawn (once per session)", drawn.size(),
                    DustWallPass.MAX_SOURCES);
        }
        // the haze level alone once every drawn source's youngest dust is past the textured level's window (dwTextured: 0 from
        // 60 s): the next frame's march takes a quarter of the rays
        boolean hazeOnly = true;
        for (int i = 0; i < drawn.size() && i < DustWallPass.MAX_SOURCES; i++) hazeOnly &= pastTexture(drawn.get(i), partial);
        DustWallPass.hazeOnly = hazeOnly;
        double adapt = 1;
        for (ClientNuclear.Active a : drawn) {
            if (w.count >= DustWallPass.MAX_SOURCES) break;
            DustWallPass.Source s = w.sources[w.count];
            double target = source(a, eye, partial, f, s, w.count == 0 ? w : null);
            adapt = Math.max(adapt, target);
            w.count++;
        }
        light(f, w);
        terrain(eye, w);
        w.adapt = (float) adapt;
        w.active = w.count > 0;
        summary(drawn, w);
        return w.active;
    }

    /**
     * The eye-centred ground tiles' places from this view's eye ({@code DwTerrain0/1}; the tiles follow the player's eye and
     * hold one height reference for every view, so a second view never re-uploads them).
     */
    private static void terrain(Vec3 eye, DustWallPass.Frame w) {
        double ref = DustGround.refY();
        for (int k = 0; k < 2; k++) {
            float[] t = k == 0 ? w.terrain0 : w.terrain1;
            double[] p = DustGround.place(k);
            if (p == null) {
                java.util.Arrays.fill(t, 0f);
                continue;
            }
            t[0] = (float) (p[0] - eye.x);
            t[1] = (float) (p[1] - eye.z);
            t[2] = (float) p[2];
            t[3] = (float) (ref - eye.y);
        }
    }

    /** A4: the eligible and drawn sources of this view (once a second under nukeDiag); every eligible source beyond the frame's cap is named. */
    private static void summary(List<ClientNuclear.Active> eligible, DustWallPass.Frame w) {
        long now = net.minecraft.Util.getMillis();
        boolean secondary = ua.zentix.incoming.client.fx.layer.FxViews.secondary();
        if (!DIAG || now - lastSummary[secondary ? 1 : 0] < 1000) return;
        lastSummary[secondary ? 1 : 0] = now;
        StringBuilder e = new StringBuilder(), d = new StringBuilder();
        for (ClientNuclear.Active a : eligible) e.append(e.isEmpty() ? "" : ",").append(a.d.id());
        for (int k = 0; k < w.count; k++) d.append(d.isEmpty() ? "" : ",").append(((ClientNuclear.Active) w.sources[k].id).d.id());
        // A1: the wave's ground-dust channel (readback of its last image; none while it does not draw) and the pall's dust
        // rows (its table holds the haze alone)
        // (the eye's ground tiles and their client-tick cost since the last line: primary view only)
        Incoming.LOG.info("Nuclear dust wave: {} view, eligible [{}], drawn [{}]{}; wave channel {}; pall ground-dust rows {}{}",
                secondary ? "secondary" : "primary", e, d,
                eligible.size() == w.count ? "" : " MISMATCH", w.count > 0 ? DustWallPass.channel() : "0 (not drawn)", PallField.ROWS - 1,
                secondary ? "" : "; " + DustGround.describe() + "; " + eyeLine);
    }

    /** Whether detonation a has dust on its table now: the front has left ground zero and some node's dust is younger than the table's end. */
    static boolean visible(ClientNuclear.Active a, float partial) {
        double t = seconds(a, partial);
        if (a.d.blocks(a.wall.shock(t)) <= 1) return false;
        float[] table = table(a);
        if (table == null) return false;
        double end = a.pall.lifetimeEnd(), last = 0;
        for (int j = 0; j < DustWallPass.NODES; j++) last = Math.max(last, table[4 * j]);
        return t <= last + end;
    }

    /** Diagnostics ({@code DustWallDiag.freezeAge}): each source's age when the freeze began. */
    private static final Map<ClientNuclear.Active, double[]> FROZEN = new WeakHashMap<>();

    /** Source a's model seconds now, or its frozen age under the diagnostic freeze. */
    private static double seconds(ClientNuclear.Active a, float partial) {
        if (!ua.zentix.incoming.client.fx.layer.DustWallDiag.freezeAge) {
            if (!FROZEN.isEmpty()) FROZEN.clear();
            return a.seconds(partial);
        }
        return FROZEN.computeIfAbsent(a, k -> new double[] {k.seconds(partial)})[0];
    }

    /** Whether every node of a's table is past the textured level's window ({@code dwTextured}: 0 from 60 s after its birth). */
    static boolean pastTexture(ClientNuclear.Active a, float partial) {
        float[] table = table(a);
        if (table == null) return false;
        double last = 0;
        for (int j = 0; j < DustWallPass.NODES; j++) last = Math.max(last, table[4 * j]);
        return seconds(a, partial) > last + 60;
    }

    /** How far the eye is outside a's dust (along the ground), blocks; 0 — inside. Nearest first in the frame. */
    private static double outside(ClientNuclear.Active a, Vec3 eye, float partial) {
        double t = seconds(a, partial);
        double r = a.d.blocks(a.wall.shock(t)) + a.d.blocks(a.d.windSpeed()) * PallField.DRIFT * a.d.scale() * t;
        return Math.max(0, Math.hypot(eye.x - a.d.burst().x, eye.z - a.d.burst().z) - r);
    }

    static float[] table(ClientNuclear.Active a) {
        return a.pall.waveTable();
    }

    /** A3: a visible show's dust undrawn for more than {@link #UNDRAWN_FRAMES} frames warns once per detonation, with the reason. */
    private static void undrawn(ClientNuclear.Active a, boolean ready) {
        int[] n = perView(UNDRAWN, UNDRAWN_SECONDARY).computeIfAbsent(a, k -> new int[2]);
        if (ready) {
            n[0] = 0;
            return;
        }
        if (++n[0] > UNDRAWN_FRAMES && n[1] == 0) {
            n[1] = 1;
            Incoming.LOG.warn("Nuclear dust wave: detonation {} has visible dust that is not drawn for {} frames: {}; nothing else draws it",
                    a.d.id(), n[0], DustWallPass.ready() ? "forced not ready (incoming.dustwave.notReadyFrames)" : DustWallPass.notReady());
        }
    }

    /** Fills source s for detonation a; returns its eye exposure target. lead: the frame, when a is its nearest source. */
    private static double source(ClientNuclear.Active a, Vec3 eye, float partial, PallPass.Frame f, DustWallPass.Source s,
            DustWallPass.Frame lead) {
        Detonation d = a.d;
        double t = seconds(a, partial), ticks = FROZEN.isEmpty() ? a.ticks(partial) : t * 20.0 * a.d.scale();
        int n = DustWallPass.NODES;
        float[] table = table(a);
        s.id = a;
        s.table = table;
        double end = a.pall.lifetimeEnd(), reach = a.pall.reach();
        double top = 0;
        for (int j = 0; j < n; j++) {
            double age = t - table[4 * j];
            if (age <= 0) continue;
            // the layer top at this node now: the age node just above its age (the top grows with age)
            double u = Math.min(Math.log1p(age / PallField.AGE0) / Math.log1p(end / PallField.AGE0) * (PallField.AGES - 1), PallField.AGES - 1);
            int k = (int) Math.min(Math.ceil(u), PallField.AGES - 1);
            top = Math.max(top, table[4 * ((1 + k) * n + j)]);
        }
        float[] p = s.params;
        float ex = (float) (eye.x - d.burst().x), ey = (float) (eye.y - d.groundY()), ez = (float) (eye.z - d.burst().z);
        double front = d.blocks(a.wall.shock(t));
        // the base surge of a water burst (radius, top, densest extinction; the same law and clock as the mushroom's column)
        double[] surge = SURGE;
        a.pall.surge(ticks, surge);
        put(p, 0, ex, ey, ez, (float) t);
        put(p, 1, (float) front, (float) (1 - fadeIn(a, t)),
                (float) Math.max(top * DustWallPass.TOP_OVER_LAYER + 6, surge[0] > 0 ? surge[1] * 1.3 + 6 : 0), (float) reach);
        double cosW = Math.cos(d.windDir()), sinW = Math.sin(d.windDir()), drift = d.blocks(d.windSpeed()) * PallField.DRIFT * d.scale();
        put(p, 2, (float) end, (float) PallField.AGE0, (float) (cosW * drift), (float) (sinW * drift));
        // fireball: its ball light (CloudPuffs.pallLight): radius, drift along the wind, height, black-body light × exposure
        a.puffs.pallLight(MushroomField.shape(a, partial), a.pall.fireLight(ticks), GLOW);
        float bx = (float) (cosW * GLOW[2]), by = GLOW[7], bz = (float) (sinW * GLOW[2]), br = GLOW[0];
        put(p, 3, bx, by, bz, br);
        float lr = GLOW[4] * f.exposure, lg = GLOW[5] * f.exposure, lb = GLOW[6] * f.exposure;
        put(p, 4, lr, lg, lb, (float) d.blocks(a.wall.underBall(t)));
        // fire light from below on a fully burning zone's dust (the pall's: pallFlame of the fire light at the detonation's fire
        // clock over the frame's sky base, capped at the flame's colour)
        double fl = a.pall.fireLight(ticks);
        float[] fc = FIRE_COLOUR, lit = LIT;
        fc[0] = f.fireLight.x;
        fc[1] = f.fireLight.y;
        fc[2] = f.fireLight.z;
        for (int c = 0; c < 3; c++) lit[c] = fc[0] <= 0 ? 0 : (float) PallLight.flame(f.base, fl * fc[c], fc[c] / fc[0]);
        put(p, 5, lit[0], lit[1], lit[2], 0);
        a.ground.frame(s.ground);
        boolean relief = s.ground.on() && ua.zentix.incoming.client.fx.layer.DustWallDiag.terrain;
        // the vertical slab the march and the ray-end search cover: the map's span and the eye tiles' (DustGround.range; a valley
        // under the map's lowest cell is dust ground too, and rays clipped at the map's bottom never reached it)
        float lo = s.ground.params.y, hi = s.ground.params.z;
        double[] range = DustGround.range();
        if (!Double.isNaN(range[0])) {
            lo = (float) Math.min(lo, range[0] - d.groundY());
            hi = (float) Math.max(hi, range[1] - d.groundY());
        }
        put(p, 6, relief ? lo : 0, relief ? hi : 0, 0, 0);
        put(p, 7, (float) surge[0], (float) surge[1], (float) surge[2], 0);
        if (relief) put(p, 8, s.ground.params.x, lo, hi, s.ground.params.w);
        else put(p, 8, 0, 0, 0, 0);
        DustMaterialTiles m = a.materialTiles();
        m.fill();
        s.material = m;
        m.tile(0, p, 36, d.burst().x, d.burst().z);
        m.tile(1, p, 40, d.burst().x, d.burst().z);
        if (!ua.zentix.incoming.client.fx.layer.DustWallDiag.material) java.util.Arrays.fill(p, 36, 44, 0f);
        // the dust's exposure: DUST_ADAPT once sun and sky alone light it, 1 while the fireball outshines them (its light at the
        // eye, R²/d²), smooth as the fireball fades
        double dx = bx - ex, dy = by - ey, dz = bz - ez;
        double ballAt = luminance(lr, lg, lb) * Math.min(br * br / Math.max(dx * dx + dy * dy + dz * dz, 1), 1);
        double ambient = luminance(f.sunLight.x, f.sunLight.y, f.sunLight.z) + luminance(f.skyLight.x, f.skyLight.y, f.skyLight.z);
        double adapt = 1 + (DUST_ADAPT - 1) * (1 - Math.min(ballAt / Math.max(ambient, 1e-6), 1));
        // inside the dust the eye adapts to the light that reaches it: the drawn column's optical depth above the eye, the
        // two streams' diffuse decay e^(−kτ) (k of the reference dust), the eye taking back DUST_ADAPT_INSIDE of it (presentation)
        // (the haze level's light only: the textured level's multiply scattered light never falls below its floor inside the
        // young dust, and the boost on it washed a noon view from inside the young wave to flat cream; its share is
        // the textured share at the eye's own age)
        // the eye's height above the dust's ground (the march's floor, DustGround), not above ground zero's ground
        float floor = (float) (DustGround.floor(eye.x, eye.z, d.groundY() + a.ground.at(eye.x, eye.z)) - d.groundY());
        float[] at = {ex, ey - floor, ez};
        double xe = Math.min(Math.hypot(ex, ez) / Math.max(reach, 1), 1) * (n - 1);
        int je = (int) Math.min(Math.floor(xe), n - 2);
        double haze = 1 - PallField.textured(t - (table[4 * je] + (xe - je) * (table[4 * (je + 1)] - table[4 * je])));
        double target = adapt * Math.exp(DUST_ADAPT_INSIDE * DUST_K * eyeDepth(table, n, end, reach, t, at, m.hard(at[0], at[2])) * haze);
        if (DIAG && lead != null && !ua.zentix.incoming.client.fx.layer.FxViews.secondary()) {
            double[] c = COLUMN;
            PallField.drawnColumn(table, n, end, reach, t, at[0], at[2], m.hard(at[0], at[2]), c);
            eyeLine = String.format(java.util.Locale.ROOT, "eye %.1f m over the dust's floor (map's %.1f), material water %.2f sand %.2f "
                    + "hard %.2f, drawn column depth %.2f top %.0f m", at[1], ey - a.ground.at(eye.x, eye.z), m.share(at[0], at[2], 0),
                    m.share(at[0], at[2], 1), m.share(at[0], at[2], 2), c[0], c[1]);
        }
        // the eye takes it over time (presentation, as NukeExposure and Sight.adapted: opening ~2 s, closing ~8 s, est.), on the
        // detonation's clock (the time the player watches; a seek or jump restarts it at the target)
        double[] state = ADAPTED.computeIfAbsent(ua.zentix.incoming.client.fx.layer.FxViews.viewKey(), k -> new WeakHashMap<>())
                .computeIfAbsent(a, k -> new double[] {target, t});
        double dt = t - state[1];
        if (dt < 0 || dt > 1) {
            state[0] = target;
            dt = 0;
        }
        state[0] = target + (state[0] - target) * Math.exp(-dt / (target > state[0] ? ADAPT_UP_S : ADAPT_DOWN_S));
        state[1] = t;
        if (lead != null) {
            lead.eye[0] = ex;
            lead.eye[1] = ey;
            lead.eye[2] = ez;
            lead.eye[3] = (float) t;
            lead.source = a;
            lead.clockJump = (float) (10 / (20.0 * d.scale()));
            // the layer top at the eye's radius (the history reprojects through it)
            double x = Math.min(Math.hypot(ex, ez) / Math.max(reach, 1), 1) * (n - 1);
            int j0 = (int) Math.min(Math.floor(x), n - 2);
            double ageEye = Math.max(t - table[4 * j0], 0);
            // (interpolated between age nodes and 0.95 of the table's, as drawn: the reprojection plane never steps)
            double ue = Math.min(Math.log1p(ageEye / PallField.AGE0) / Math.log1p(end / PallField.AGE0) * (PallField.AGES - 1), PallField.AGES - 1);
            int ke = (int) Math.min(Math.floor(ue), PallField.AGES - 2);
            double fe = ue - ke, top0 = table[4 * ((1 + ke) * n + j0)], top1 = table[4 * ((2 + ke) * n + j0)];
            lead.layerTop = (float) (0.95 * (top0 + fe * (top1 - top0)));
        }
        return state[0];
    }

    /**
     * Share of source a faded in (1 when it was first drawn young, in a replay, or 1 s after a late first draw): a source first
     * drawn already aged (a late join, a relog into a running show, programs ready after the wave began) fades in over 1 s of
     * own time, never appearing whole in one frame (presentation). A replay's dust is a function of its position: no fade.
     */
    private static double fadeIn(ClientNuclear.Active a, double t) {
        long now = net.minecraft.Util.getMillis();
        long[] first = FIRST.computeIfAbsent(a, k -> new long[] {now, t > AGED_S && !ua.zentix.incoming.client.replay.Replay.active() ? 1 : 0});
        if (first[1] == 0) return 1;
        return Math.min(1, (now - first[0]) / (double) FADE_IN_MS);
    }

    private static void light(PallPass.Frame f, DustWallPass.Frame w) {
        w.sunDir.set(f.sun.x, f.sun.y, 0);
        w.sunLight.set(f.sunLight);
        w.skyLight.set(f.skyLight);
        float up = Math.max(f.sun.y, 0);
        w.groundLight.set(f.sunLight).mul(up).add(f.skyLight).mul((float) GROUND_BOUNCE);
        // the reference soil's dust (the pall's PALL_DUST_W) and its bulk colour; sand's; water droplets'
        w.albedo.set((float) DUST_W[0], (float) DUST_W[1], (float) DUST_W[2]);
        w.bulk.set((float) NukePall.DUST[0], (float) NukePall.DUST[1], (float) NukePall.DUST[2]);
        w.sandAlbedo.set((float) SAND_W[0], (float) SAND_W[1], (float) SAND_W[2]);
        w.sandBulk.set((float) NukePall.SAND[0], (float) NukePall.SAND[1], (float) NukePall.SAND[2]);
        w.mistAlbedo.set((float) MIST_W[0], (float) MIST_W[1], (float) MIST_W[2]);
        w.mistBulk.set((float) PallLight.linear(MIST[0]), (float) PallLight.linear(MIST[1]), (float) PallLight.linear(MIST[2]));
    }

    private static void put(float[] p, int texel, float x, float y, float z, float w) {
        p[4 * texel] = x;
        p[4 * texel + 1] = y;
        p[4 * texel + 2] = z;
        p[4 * texel + 3] = w;
    }

    private static double luminance(double r, double g, double b) {
        return 0.2126 * r + 0.7152 * g + 0.0722 * b;
    }

    /**
     * Drawn optical depth of the dust above the eye (no drift: the eye's own ground point; the table's age nodes read linearly):
     * {@code dwHaze}'s column, its top 0.95 of the table's, the share above by the profile's stratification.
     */
    static double eyeDepth(float[] table, int n, double end, double reach, double t, float[] eye, double hard) {
        double[] c = COLUMN;
        PallField.drawnColumn(table, n, end, reach, t, eye[0], eye[2], hard, c);
        if (c[0] <= 0) return 0;
        double h = Math.max(eye[1], 0) / Math.max(c[1], 1);
        if (h >= 1) return 0;
        double above = 1.25 * (1 - h) - 0.25 * (1 - h * h);
        return c[0] * above;
    }

    /** One line for the timing log: the frame's sources. */
    static String describe(DustWallPass.Frame w) {
        if (!w.active) return "no dust wave";
        StringBuilder b = new StringBuilder("dust wave ").append(w.count).append(" source(s)");
        for (int k = 0; k < w.count; k++) {
            float[] p = w.sources[k].params;
            b.append(String.format(java.util.Locale.ROOT, " [#%d front %.0f fade %.2f surge %.0f]", k, p[4], 1 - p[5], p[28]));
        }
        return b.toString();
    }

    /** More than {@link DustWallPass#MAX_SOURCES} sources were visible once (warned). */
    private static boolean warnedCap;

    static void reset() {
        visibleFrames = 0;
        DRAWN.clear();
        ADAPTED.clear();
        UNDRAWN_SECONDARY.clear();
        FIRST.clear();
        UNDRAWN.clear();
    }

}
