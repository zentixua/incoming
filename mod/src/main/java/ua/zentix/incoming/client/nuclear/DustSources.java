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

    /** Per detonation: its ground material tiles. */
    private static final Map<ClientNuclear.Active, DustMaterialTiles> MATERIAL = new WeakHashMap<>();
    /** Per detonation: the dust exposure its eye has reached (value, the detonation clock's second). */
    private static final Map<ClientNuclear.Active, double[]> ADAPTED = new WeakHashMap<>();
    /**
     * Per detonation: when it was first drawn (client millis) and whether it was then already aged (a late join, a relog into a
     * running show, programs ready after the wave began): such a source fades in over {@link #FADE_IN_MS} of own time.
     */
    private static final Map<ClientNuclear.Active, long[]> FIRST = new WeakHashMap<>();
    /** Fade-in of a source first drawn already aged, client milliseconds (plan 4.10: presentation, live clients only). */
    static final long FADE_IN_MS = 1000;
    /** Model seconds after the burst from which a first draw counts as already aged. */
    static final double AGED_S = 1.0;
    /** Per detonation: frames its visible dust went undrawn, and whether that was reported (A3). */
    private static final Map<ClientNuclear.Active, int[]> UNDRAWN = new WeakHashMap<>();
    /** Frames a visible show's dust may go undrawn before the WARN (A3). */
    static final int UNDRAWN_FRAMES = 20;
    /**
     * Diagnostics (A3's scenario check): the pass counts as not ready for this many frames after the first frame with visible
     * dust ({@code -Dincoming.dustwave.notReadyFrames}); nothing else may draw the dust meanwhile.
     */
    private static final int FORCE_NOT_READY = Integer.getInteger("incoming.dustwave.notReadyFrames", 0);
    private static int visibleFrames;
    /** A4: once a second under {@code -Dincoming.nukeDiag=true}, per view: the eligible and the drawn sources. */
    private static final boolean DIAG = Boolean.getBoolean("incoming.nukeDiag");
    private static long lastSummary;

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

    /**
     * The dust wave's inputs for this frame into {@code f.dustWall} (frame light already in {@code f}); false — no source (none
     * visible, dust OFF, or the pass not ready: the latter warns after {@link #UNDRAWN_FRAMES} frames, once per detonation).
     */
    static boolean frame(List<ClientNuclear.Active> all, Vec3 eye, float partial, PallPass.Frame f) {
        DustWallPass.Frame w = f.dustWall;
        w.active = false;
        w.count = 0;
        if (!wanted() || all.isEmpty()) return false;
        List<ClientNuclear.Active> drawn = new ArrayList<>();
        for (ClientNuclear.Active a : all) if (visible(a, partial)) drawn.add(a);
        if (drawn.isEmpty()) return false;
        visibleFrames++;
        boolean ready = DustWallPass.ready() && visibleFrames > FORCE_NOT_READY;
        for (ClientNuclear.Active a : drawn) undrawn(a, ready);
        if (!ready) {
            summary(drawn, w);
            return false;
        }
        drawn.sort(Comparator.comparingDouble(a -> outside(a, eye, partial)));
        double adapt = 1;
        for (ClientNuclear.Active a : drawn) {
            if (w.count >= DustWallPass.MAX_SOURCES) break;
            DustWallPass.Source s = w.sources[w.count];
            double target = source(a, eye, partial, f, s, w.count == 0 ? w : null);
            adapt = Math.max(adapt, target);
            w.count++;
        }
        light(f, w);
        w.adapt = (float) adapt;
        w.active = w.count > 0;
        summary(drawn, w);
        return w.active;
    }

    /** A4: the eligible and drawn sources of this view (once a second under nukeDiag); every eligible source beyond the frame's cap is named. */
    private static void summary(List<ClientNuclear.Active> eligible, DustWallPass.Frame w) {
        long now = net.minecraft.Util.getMillis();
        if (!DIAG || now - lastSummary < 1000) return;
        lastSummary = now;
        StringBuilder e = new StringBuilder(), d = new StringBuilder();
        for (ClientNuclear.Active a : eligible) e.append(e.isEmpty() ? "" : ",").append(a.d.id());
        for (int k = 0; k < w.count; k++) d.append(d.isEmpty() ? "" : ",").append(((ClientNuclear.Active) w.sources[k].id).d.id());
        // A1: the wave's ground-dust channel (readback of its last image; none while it does not draw) and the pall's dust
        // rows (its table holds the haze alone)
        Incoming.LOG.info("Nuclear dust wave: {} view, eligible [{}], drawn [{}]{}; wave channel {}; pall ground-dust rows {}",
                ua.zentix.incoming.client.fx.layer.FxViews.secondary() ? "secondary" : "primary", e, d,
                eligible.size() == w.count ? "" : " MISMATCH", w.count > 0 ? DustWallPass.channel() : "0 (not drawn)", PallField.ROWS - 1);
    }

    /** Whether detonation a has dust on its table now: the front has left ground zero and some node's dust is younger than the table's end. */
    static boolean visible(ClientNuclear.Active a, float partial) {
        double t = a.seconds(partial);
        if (a.d.blocks(a.wall.shock(t)) <= 1) return false;
        float[] table = table(a);
        double end = a.pall.lifetimeEnd(), last = 0;
        for (int j = 0; j < DustWallPass.NODES; j++) last = Math.max(last, table[4 * j]);
        return t <= last + end;
    }

    /** How far the eye is outside a's dust (along the ground), blocks; 0 — inside. Nearest first in the frame. */
    private static double outside(ClientNuclear.Active a, Vec3 eye, float partial) {
        double t = a.seconds(partial);
        double r = a.d.blocks(a.wall.shock(t)) + a.d.blocks(a.d.windSpeed()) * PallField.DRIFT * a.d.scale() * t;
        return Math.max(0, Math.hypot(eye.x - a.d.burst().x, eye.z - a.d.burst().z) - r);
    }

    static float[] table(ClientNuclear.Active a) {
        return a.pall.waveTable();
    }

    /** A3: a visible show's dust undrawn for more than {@link #UNDRAWN_FRAMES} frames warns once per detonation, with the reason. */
    private static void undrawn(ClientNuclear.Active a, boolean ready) {
        int[] n = UNDRAWN.computeIfAbsent(a, k -> new int[2]);
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
        double t = a.seconds(partial), ticks = a.ticks(partial);
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
        double[] surge = new double[3];
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
        float[] fc = {f.fireLight.x, f.fireLight.y, f.fireLight.z};
        float[] lit = new float[3];
        for (int c = 0; c < 3; c++) lit[c] = fc[0] <= 0 ? 0 : (float) PallLight.flame(f.base, fl * fc[c], fc[c] / fc[0]);
        put(p, 5, lit[0], lit[1], lit[2], 0);
        a.ground.frame(s.ground);
        put(p, 6, s.ground.on() ? s.ground.params.y : 0, s.ground.on() ? s.ground.params.z : 0, 0, 0);
        put(p, 7, (float) surge[0], (float) surge[1], (float) surge[2], 0);
        if (s.ground.on()) put(p, 8, s.ground.params.x, s.ground.params.y, s.ground.params.z, s.ground.params.w);
        else put(p, 8, 0, 0, 0, 0);
        DustMaterialTiles m = MATERIAL.computeIfAbsent(a, k -> new DustMaterialTiles(k.d, k.sourceMaterial()));
        m.fill();
        s.material = m;
        m.tile(0, p, 36, d.burst().x, d.burst().z);
        m.tile(1, p, 40, d.burst().x, d.burst().z);
        // the dust's exposure: DUST_ADAPT once sun and sky alone light it, 1 while the fireball outshines them (its light at the
        // eye, R²/d²), smooth as the fireball fades
        double dx = bx - ex, dy = by - ey, dz = bz - ez;
        double ballAt = luminance(lr, lg, lb) * Math.min(br * br / Math.max(dx * dx + dy * dy + dz * dz, 1), 1);
        double ambient = luminance(f.sunLight.x, f.sunLight.y, f.sunLight.z) + luminance(f.skyLight.x, f.skyLight.y, f.skyLight.z);
        double adapt = 1 + (DUST_ADAPT - 1) * (1 - Math.min(ballAt / Math.max(ambient, 1e-6), 1));
        // inside the dust the eye adapts to the light that reaches it: the drawn column's optical depth above the eye, the
        // two streams' diffuse decay e^(−kτ) (k of the reference dust), the eye taking back DUST_ADAPT_INSIDE of it (presentation)
        // (the haze level's light only: the textured level's multiply scattered light never falls below its floor inside the
        // young dust, and the boost on it washed a noon view from inside the young wave to flat cream, Silo 24.11; its share is
        // the textured share at the eye's own age)
        float[] at = {ex, ey, ez};
        double xe = Math.min(Math.hypot(ex, ez) / Math.max(reach, 1), 1) * (n - 1);
        int je = (int) Math.min(Math.floor(xe), n - 2);
        double haze = 1 - PallField.textured(t - (table[4 * je] + (xe - je) * (table[4 * (je + 1)] - table[4 * je])));
        double target = adapt * Math.exp(DUST_ADAPT_INSIDE * DUST_K * eyeDepth(table, n, end, reach, t, at) * haze);
        // the eye takes it over time (presentation, as NukeExposure and Sight.adapted: opening ~2 s, closing ~8 s, est.), on the
        // detonation's clock (the time the player watches; a seek or jump restarts it at the target)
        double[] state = ADAPTED.computeIfAbsent(a, k -> new double[] {target, t});
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
            double ue = Math.min(Math.log1p(ageEye / PallField.AGE0) / Math.log1p(end / PallField.AGE0) * (PallField.AGES - 1), PallField.AGES - 1);
            lead.layerTop = table[4 * ((1 + (int) Math.round(ue)) * n + j0)];
            // the haze level alone once the youngest dust anywhere is past the textured level's window (dwTextured: 0 from 60 s)
            double last = 0;
            for (int j = 0; j < n; j++) last = Math.max(last, table[4 * j]);
            DustWallPass.hazeOnly = t > last + 60;
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
    static double eyeDepth(float[] table, int n, double end, double reach, double t, float[] eye) {
        double x = Math.min(Math.hypot(eye[0], eye[2]) / Math.max(reach, 1), 1) * (n - 1);
        int j = (int) Math.min(Math.floor(x), n - 2);
        double age = t - (table[4 * j] + (x - j) * (table[4 * (j + 1)] - table[4 * j]));
        if (age <= 0) return 0;
        double u = Math.log1p(age / PallField.AGE0) / Math.log1p(end / PallField.AGE0) * (PallField.AGES - 1);
        if (u >= PallField.AGES - 1) return 0;
        int k = (int) Math.floor(u);
        double fr = u - k;
        int o0 = 4 * ((1 + k) * n + j), o1 = 4 * ((2 + k) * n + j);
        double top = 0.95 * (table[o0] + fr * (table[o1] - table[o0])), tau = table[o0 + 1] + fr * (table[o1 + 1] - table[o0 + 1]);
        double h = Math.max(eye[1], 0) / Math.max(top, 1);
        if (h >= 1) return 0;
        double above = 1.25 * (1 - h) - 0.25 * (1 - h * h);
        return PallField.transfer(tau) * (1 + 0.5 * Math.exp(-age / 1.1)) * above;
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

    static void reset() {
        visibleFrames = 0;
        ADAPTED.clear();
        FIRST.clear();
        UNDRAWN.clear();
    }

}
