package ua.zentix.incoming.client.nuclear;

import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.joml.FrustumIntersection;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;
import ua.zentix.incoming.client.far.Sight;
import ua.zentix.incoming.client.fx.layer.PallPass;
import ua.zentix.incoming.client.fx.layer.WaterDecal;
import ua.zentix.incoming.nuclear.Detonation;
import ua.zentix.incoming.nuclear.model.BlackBody;
import ua.zentix.incoming.nuclear.model.DustModel;
import ua.zentix.incoming.nuclear.model.FireballModel;
import ua.zentix.incoming.nuclear.model.HobBlast;
import ua.zentix.incoming.nuclear.model.MushroomModel;
import ua.zentix.incoming.nuclear.model.ThermalModel;
import ua.zentix.incoming.nuclear.model.WaterModel;

import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Frame mushrooms for the volume ({@code mushroom.glsl}, {@code PallPass} passes): up to {@link #MUSHROOMS} detonations largest on screen
 * whose bounds or (by day) shadow are in frame — shape parameters ({@link MushroomModel}; for a water burst also water column,
 * {@link WaterModel#column}) in blocks relative to eye, matter and temperature, sun-shadow map tile, one table row
 * per mushroom; last row is black-body light by temperature ({@link BlackBody}). Model meters → world blocks through
 * {@link Detonation#blocks}; time is detonation model seconds.
 */
public final class MushroomField {
    /** Maximum mushrooms per frame. */
    public static final int MUSHROOMS = 4;
    /** First texel of lofted-dust columns: 64 heights and 128 wall offsets, four per texel ({@link FootColumn}). */
    public static final int FOOT_COLUMNS = 20;
    /** RGBA texels per mushroom ({@code mushroom.glsl}: mushroom table). */
    public static final int TEXELS = FOOT_COLUMNS + (FootColumn.NODES + FootColumn.EDGE_NODES) / 4;
    /** Black-body light nodes (row {@link #BB_ROW}): temperatures from {@link #BB_FROM} to {@link #BB_TO} K, logarithmically spaced. */
    public static final int BB_NODES = 256;
    static final double BB_FROM = 500, BB_TO = 30_000;
    /** Table width and rows: mushrooms and black-body light. */
    public static final int WIDTH = BB_NODES, ROWS = MUSHROOMS + 1, BB_ROW = MUSHROOMS;
    /**
     * Billow edge-displacement amplitude — cap, stem, skirt ({@code MUSH_*_LOBES}), in each part's billow units: cap — smaller
     * semiaxis of the ring cross-section; stem — radius at that height; skirt — smaller semiaxis of its cross-section. Cap billows are
     * bubbles half its cross-section, stem and skirt looser. Displacement up to ±{@link #LOBES_MAX} of the amplitude, in blocks (equal
     * in every direction: round billows, not stretched along the larger semiaxis).
     */
    static final double LOBES_CAP = 0.4, LOBES_STEM = 0.4, LOBES_SKIRT = 0.8;
    /** Edge softness — cap, stem, skirt ({@code MUSH_*_EDGE}), in billow units. */
    static final double EDGE_CAP = 0.07, EDGE_STEM = 0.2, EDGE_SKIRT = 0.25;
    /** Maximum billow edge displacement, fraction of amplitude: large noise ±0.5, small ±0.175 ({@code mushLobes}). */
    static final double LOBES_MAX = 0.68;
    /** Ring–dome union smoothing ({@code MUSH_BLEND}), in cap-billow units: smooth minimum lowers the edge by at most a quarter of it. */
    static final double BLEND = 0.25;
    /**
     * Matter reach beyond the edge without billows — cap (with smooth minimum), stem, skirt ({@code MUSH_*_REACH}), in
     * billow units: billow displacement plus edge softness. Mushroom bounds use these.
     */
    static final double REACH_CAP = LOBES_MAX * LOBES_CAP + EDGE_CAP + BLEND / 4, REACH_STEM = LOBES_MAX * LOBES_STEM + EDGE_STEM,
            REACH_SKIRT = LOBES_MAX * LOBES_SKIRT + EDGE_SKIRT;
    /**
     * Water column ({@code MUSH_COLUMN_*}): billow amplitude and edge softness in wall-thickness units — wall jets and crown
     * domes displace its surfaces by ±0.4 thickness without piercing the wall; matter reach includes the smooth minimum
     * of wall and crown.
     */
    static final double LOBES_COLUMN = 0.6, EDGE_COLUMN = 0.15, REACH_COLUMN = LOBES_MAX * LOBES_COLUMN + EDGE_COLUMN + BLEND / 4;
    /**
     * Optical depth across the column wall (σ' after delta-Eddington, like pall): Baker had ~10⁶ t of water in the wall over
     * ~3·10⁸ m³ — kilograms of drops per cubic meter, optical depth in the hundreds. Here, as for a mature cap: wall opaque, light
     * inside calculated without overflow. Decreases with water not yet fallen.
     */
    static final double COLUMN_TAU = 40;
    /** Column-drop scattering asymmetry, like pall mist. */
    static final double G_WATER = 0.85;
    /** Column billow tile is a fraction of its radius before narrowing: wall jets and crown domes as wide as wall thickness. */
    static final double COLUMN_TILE = 1.2;
    /**
     * Thick-layer color (on screen; albedo derived as for pall): soil-bearing cap ({@link MushroomModel#soil}) without
     * NO₂ is gray with dust (its absorption adds to NO₂, {@link #capMatter}); steam for a burst over water. A soil-free cap contains
     * drops and NO₂ ({@link #capMatter}), with no color of its own.
     */
    static final int CAP_DUSTY = 0xC9C4BE, STEAM = 0xD9D9D6;
    /**
     * Local burst dust in stem and skirt is the same matter as wave dust layer and pall ({@link NukePall#DUST}): the stem
     * is fed only by dust drawn from that layer by inflow ({@code StemDust}: "sweep-up and thermal dust", NRC 1985),
     * and skirt is the same at ground level. Previously the stem had a separate tuned "dust with smoke" color (0x8E7F70, darker and grayer than the layer
     * beneath it), although its model contains no smoke.
     */
    static final int DUST = display(NukePall.DUST);
    /**
     * Steam share of cap extinction with full water beneath the burst and a rolled ring ({@link #capMatter}). (est.):
     * sources do not give how much water the ball lofts over water; value is the previous steam share in layer color.
     */
    static final double STEAM_SHARE = 0.45;
    /** Mushroom dust and smoke scattering asymmetry, like pall. */
    static final double G = 0.6;
    /**
     * Sun-shadow map ({@code mushroom.glsl}: {@code MUSH_SHADOW}): {@link #SHADOW}²-texel tile per mushroom in a plane
     * perpendicular to the sun; below elevation {@link #SHADOW_LOW} (sine), sun casts as if at that elevation ({@code MUSH_SHADOW_LOW}).
     */
    public static final int SHADOW = 128;
    static final double SHADOW_LOW = 0.05;
    /** Shadow-tile margin beyond projected bounds, texels per side: edge lines miss the mushroom. */
    static final int SHADOW_PAD = 2;
    /** Mushroom-row texel with shadow-map tile (side 0 — no shadow) and world top above ground zero. */
    public static final int SHADOW_RECT = 17, SHADOW_TOP = 18, FOOT = 19;
    /** Frame-queue priority offset for a mushroom with only its shadow in frame: larger than any angular size. */
    private static final double SHADOW_ONLY = 1e6;

    /** Maximum balls lighting the world ({@link #balls}). */
    public static final int BALLS = 4;
    /**
     * A ball lights the world while its light on ground directly below is at least this fraction of frame open-sky light: less cannot be
     * distinguished on screen (~12 s at night and ~9 s by day for 15 kt).
     */
    static final double BALL_SEEN = 0.005;

    private static final float[] BB = blackBody();
    private static final double[] BALL = new double[4], CENTRE = new double[3], BALL_WEIGHT = new double[BALLS];
    private static final Vector4f COLUMN = new Vector4f();
    private static final double[] BALL_SURFACE = new double[2];
    private static final double[] RGB = new double[3], CAP = new double[4], DRIFT = new double[2], RAYS = new double[3], AXIS_A = new double[3],
            AXIS_B = new double[3], RECT = new double[4];
    private static final Vector3f LO = new Vector3f(), HI = new Vector3f();
    private static final int[] ORDER = new int[MUSHROOMS];
    private static final Map<Detonation, MushroomModel.Churn> CHURN = new WeakHashMap<>();
    /** Detonations of frame rows ({@link #frame}): {@link #fire} writes fire light beneath them after frame lighting. */
    private static final ClientNuclear.Active[] ROW = new ClientNuclear.Active[MUSHROOMS];
    private static final double[] SIZE = new double[MUSHROOMS];
    /**
     * Frustum planes except far (far plane is 4 render distances, mushroom visible tens of kilometers away).
     */
    private static final int SIDES_AND_NEAR = FrustumIntersection.PLANE_MASK_NX | FrustumIntersection.PLANE_MASK_PX
            | FrustumIntersection.PLANE_MASK_NY | FrustumIntersection.PLANE_MASK_PY | FrustumIntersection.PLANE_MASK_NZ;

    private MushroomField() {}

    /** Black-body light at table nodes: rgb and brightness Y in frame units (noon white wall = 1). */
    private static float[] blackBody() {
        float[] row = new float[BB_NODES * 4];
        double[] c = new double[4];
        for (int i = 0; i < BB_NODES; i++) {
            BlackBody.light(nodeKelvin(i), c);
            for (int k = 0; k < 4; k++) row[4 * i + k] = (float) c[k];
        }
        return row;
    }

    /** Table node i temperature, K. */
    static double nodeKelvin(int i) {
        return BB_FROM * Math.pow(BB_TO / BB_FROM, i / (double) (BB_NODES - 1));
    }

    /** Black-body light row into table {@code into} (WIDTH × ROWS × 4): unchanging, uploaded once. */
    public static void blackBodyRow(float[] into) {
        System.arraycopy(BB, 0, into, BB_ROW * WIDTH * 4, BB.length);
    }

    /**
     * Frame mushrooms into table {@code into} rows (0..{@link #MUSHROOMS}−1, {@link #TEXELS} texels each; rows without
     * a mushroom are zero): largest on screen whose bounds are in the frustum ({@code frustum} — from eye, without far
     * plane), or by day whose world shadow enters it (after all directly visible mushrooms). {@code sun} is sun direction and daylight strength ({@code PallSun}); {@code bottom}, {@code top} are world bottom
     * and top (y): shadow falls on everything between. Returns mushroom count.
     */
    public static int frame(List<ClientNuclear.Active> all, Vec3 eye, float partial, FrustumIntersection frustum, Vector4f sun,
                            double bottom, double top, float[] into) {
        for (int k = 0; k < MUSHROOMS; k++) java.util.Arrays.fill(into, k * WIDTH * 4, k * WIDTH * 4 + TEXELS * 4, 0f);
        shadowSun(sun.x, sun.y, sun.z, RAYS);
        shadowAxes(RAYS, AXIS_A, AXIS_B);
        boolean day = sun.w > 0;
        int n = 0;
        for (int i = 0; i < all.size(); i++) {
            ClientNuclear.Active a = all.get(i);
            MushroomModel.Shape s = shape(a, partial);
            if (s == null) continue;
            Detonation d = a.d;
            double size = d.blocks(s.capR()) / Math.max(1, Math.hypot(Math.hypot(d.burst().x - eye.x, d.burst().z - eye.z), d.groundY() + d.blocks(s.capY()) - eye.y));
            if (!inView(a, s, partial, eye, null, bottom, frustum)) {
                if (!day || !inView(a, s, partial, eye, RAYS, bottom, frustum)) continue;
                // only shadow in frame — after all directly visible ones: only this volume draws mushroom bodies, so a mushroom behind the viewer
                // must not displace a visible one
                size -= SHADOW_ONLY;
            }
            // by angular size: replace the smallest if this is larger
            int at = n < MUSHROOMS ? n++ : -1;
            if (at < 0) {
                int min = 0;
                for (int k = 1; k < MUSHROOMS; k++) if (SIZE[k] < SIZE[min]) min = k;
                if (SIZE[min] >= size) continue;
                at = min;
            }
            ORDER[at] = i;
            SIZE[at] = size;
        }
        for (int k = 0; k < MUSHROOMS; k++) {
            ClientNuclear.Active a = k < n ? all.get(ORDER[k]) : null;
            ROW[k] = a;
            if (a != null) write(a, shape(a, partial), eye, partial, day, top, into, k * WIDTH * 4);
        }
        return n;
    }

    /**
     * Fire light beneath frame mushrooms (after {@link #frame} and frame lighting: {@code f.sun}, {@code f.fireLight}) — texel
     * 12 (rgb, retaining churn time), at ground level, on screen, like pall glow from below: storm glow
     * ({@link FireStorms#glow}: fire-model trust, day and night) per mean flame over the fire zone from the fire map (water and unburned
     * areas emit no light); until the map is trusted, use haze-table glow like the pall-slot haze row
     * ({@code FireStorms.slot}: × (1 − trust)). Above, light from the fire disk and storm smoke below the point ({@code mushAt}).
     */
    static void fire(float partial, PallPass.Frame f) {
        double night = 1 - f.sun.w;
        for (int k = 0; k < MUSHROOMS; k++) {
            ClientNuclear.Active a = ROW[k];
            if (a == null) continue;
            // fire map for this frame: a storm without a pall slot (third detonation) does not update it itself
            a.storm.refresh(a.ticks(partial));
            double gate = a.storm.gate(), storm = FireStorms.glow(gate, night) * a.storm.meanFlame();
            double table = (1 - gate) * a.pall.fireBelow(a.ticks(partial));
            // texel w holds cap churn time (write): leave it intact or the cap will stop
            int o = k * WIDTH * 4;
            float churn = f.mushrooms[o + 4 * 12 + 3];
            put(f.mushrooms, o, 12, storm * FireStorms.FIRE_R + table * f.fireLight.x, storm * FireStorms.FIRE_G + table * f.fireLight.y,
                    storm * FireStorms.FIRE_B + table * f.fireLight.z, churn);
        }
    }

    /**
     * Fireballs and caps as world light sources ({@code fx_pall_composite}: {@code PallBalls}, {@code PallBallLight}):
     * up to {@link #BALLS} strongest at the eye, from all detonations — ground in frame is lit even if the ball is behind the viewer.
     * Column i of {@code balls} — cap center relative to eye (xyz), cap radius (w), blocks; {@code light} — black-body light at its
     * visible temperature on a white wall at its surface, with eye adaptation ({@code exposure}, rgb, like the beam in
     * {@code fx_pall}), and air extinction, 1/block (w). Empty columns are zero. Returns whether any ball lights the ground
     * directly beneath it (nearest to it) by at least {@link #BALL_SEEN} of frame open-sky light {@code open}
     * (brightness, linear); otherwise the composite pass skips it.
     */
    static boolean balls(List<ClientNuclear.Active> all, Vec3 eye, float partial, double airSigma, double exposure, double open,
                         Matrix4f balls, Matrix4f light) {
        balls.zero();
        light.zero();
        java.util.Arrays.fill(BALL_WEIGHT, 0);
        boolean seen = false;
        for (ClientNuclear.Active a : all) {
            MushroomModel.Shape s = shape(a, partial);
            if (s == null) continue;
            Detonation d = a.d;
            BlackBody.light(MushroomModel.edgeTemperature(s.t(), d.yieldKt(), d.surface()), BALL);
            double r = d.blocks(s.capR()), h = Math.max(d.blocks(s.capY()), r), surface = BALL[3] * exposure;
            // ground directly below ball is nearest to it
            boolean lit = surface * r * r / (h * h) >= BALL_SEEN * Math.max(open, 1e-4);
            if (!lit) continue;
            centre(a, s, CENTRE);
            double dx = CENTRE[0] - eye.x, dy = CENTRE[1] - eye.y, dz = CENTRE[2] - eye.z;
            double w = surface * r * r / Math.max(dx * dx + dy * dy + dz * dz, r * r);
            int slot = 0;
            for (int i = 1; i < BALLS; i++) if (BALL_WEIGHT[i] < BALL_WEIGHT[slot]) slot = i;
            if (w <= BALL_WEIGHT[slot]) continue;
            BALL_WEIGHT[slot] = w;
            seen = true;
            balls.setColumn(slot, COLUMN.set((float) dx, (float) dy, (float) dz, (float) r));
            light.setColumn(slot, COLUMN.set((float) (BALL[0] * exposure), (float) (BALL[1] * exposure), (float) (BALL[2] * exposure),
                    (float) (airSigma / d.scale())));
        }
        return seen;
    }

    /**
     * Ball light on a world face ({@code mushBallFace} in {@code mushroom.glsl}), fraction of ball light on a white wall facing it
     * without air: direct beam e^(−σd) by face cosine {@code nv}; air-scattered light is combined direct and
     * scattered transmission ({@link ThermalModel#transmittance}, Glasstone fig. 7.104) minus direct, from sky around the ball, onto
     * the face by (1 + nv)/2 (est.) (forward scatter lights from the ball side); ball light reflected by ground of albedo {@code albedo}
     * onto horizontal (ball elevation sine {@code vy}) uses the face's visible ground share, (1 − ny)/2. Under
     * pall — direct and scattered near the ball through pall ({@code through}), and pall-scattered light downward onto horizontal
     * ({@code diffuse} per unit light above pall), onto face by (1 + ny)/2; without pall — 1 and 0. {@code sd} is air σd to
     * the ball (σ = 3.912/V).
     * <p>
     * Without scatter and reflection, faces turned away from the ball stayed in daylight alone, while faces toward it at 3–5 km
     * from 15 kt at the second maximum were ~250 times brighter than noon (18.10 complaint: "second sun", hard shadows over the whole
     * map): in clear weather at 4 km, scattered light is 0.2 of ball light (direct 0.46); ground reflects one fifth of the light
     * falling on it. Below ~0.05 V (1 km in clear weather), Glasstone's formula gives less transmittance than direct light — no scattered light
     * there, leaving ground reflection (deviation: nearby direct light is already much stronger).
     */
    static double ballFace(double nv, double vy, double ny, double sd, double albedo, double through, double diffuse) {
        double direct = Math.exp(-sd);
        double scattered = Math.max(ThermalModel.transmittance(Math.max(sd, 0), Sight.KOSCHMIEDER) - direct, 0);
        double beam = direct * Math.max(nv, 0) + scattered * 0.5 * (1 + nv);
        double ground = direct * Math.max(vy, 0) + scattered * 0.5 * (1 + vy);
        double sky = (direct + scattered) * diffuse;
        return beam * through + sky * 0.5 * (1 + ny) + albedo * (ground * through + sky) * 0.5 * (1 - ny);
    }

    /**
     * One linear world-pixel channel under ball light ({@code relit} in {@code fx_pall_composite}): on-screen world
     * {@code world}, share of its previous on-screen light {@code kept} (shadow), on-screen open-sky light on a white block
     * {@code open} (Minecraft lightmap), ball light on a white wall {@code added} (linear). Block color is pixel
     * divided by open-sky light, capped at white.
     */
    static double relit(double world, double kept, double open, double added) {
        double lin = PallLight.linear(world);
        return lin * PallLight.linear(kept) + Math.min(lin / Math.max(PallLight.linear(open), 1e-4), 1) * added;
    }

    /** Detonation mushroom at frame time; null — not yet present or dispersed. */
    static MushroomModel.Shape shape(ClientNuclear.Active a, float partial) {
        Detonation d = a.d;
        double t = a.seconds(partial);
        // ground front reach (skirt is dust at the stem base, no farther than the front)
        double front = d.metres(d.frontRadius(a.ticks(partial)));
        double hob = Math.max(0, d.hobMetres());
        return MushroomModel.shape(t, d.yieldKt(), hob, d.surface(), Math.sqrt(Math.max(0, front * front - hob * hob)),
                ua.zentix.incoming.nuclear.model.StemDust.ready(d.yieldKt(), hob, d.surface(), d.visibility()));
    }

    /**
     * World cap center (ball center while a ball), blocks: x, y, z into {@code out}; mushroom axis at this height has drifted
     * by its wind share ({@code mushLocal}). Ball light source ({@link MushroomModel#light}).
     */
    static void centre(ClientNuclear.Active a, MushroomModel.Shape s, double[] out) {
        Detonation d = a.d;
        double along = driftAlong(d, s);
        out[0] = d.burst().x + Math.cos(d.windDir()) * along;
        out[1] = d.groundY() + d.blocks(s.capY());
        out[2] = d.burst().z + Math.sin(d.windDir()) * along;
    }

    /** Downwind cap-center drift, blocks: mushroom axis at its height ({@link #centre}). */
    static double driftAlong(Detonation d, MushroomModel.Shape s) {
        double share = MushroomModel.driftShare(d.blocks(s.capY()), Math.max(d.blocks(s.endTop()), 1));
        return d.blocks(d.windSpeed() * s.t()) * share;
    }

    /** Full-share wind drift, blocks: x, z into {@code out}. */
    private static void drift(Detonation d, double t, double[] out) {
        double w = d.blocks(d.windSpeed() * t);
        out[0] = Math.cos(d.windDir()) * w;
        out[1] = Math.sin(d.windDir()) * w;
    }

    /**
     * Mushroom bound radius around its axis, m (without drift): matter reach of cap, stem and skirt with billows. A part's
     * billowed edge is where its measure is no greater than 1 + {@code REACH}·unit/smaller semiaxis ({@code mushGap}: measure grows no
     * faster than distance divided by the smaller semiaxis). Stem is widest at ground or cap ({@link MushroomModel#stemRadius}
     * is convex).
     */
    static double reach(MushroomModel.Shape s) {
        double u = capUnit(s);
        double cap = Math.max(s.ringR() + s.ringA() * (1 + REACH_CAP * u / Math.min(s.ringA(), s.ringB())),
                s.domeR() * (1 + REACH_CAP * u / Math.min(s.domeR(), s.domeH())));
        double flare = MushroomModel.dustFlare(s);
        double stem = s.stemTau() > 0
                ? (1 + REACH_STEM) * Math.max(MushroomModel.stemRadius(0, s.stemR(), flare), MushroomModel.stemRadius(1, s.stemR(), flare)) : 0;
        double skirt = s.skirtTau() > 0 ? s.skirtR() + s.skirtW() * (1 + REACH_SKIRT * skirtUnit(s) / Math.min(s.skirtW(), s.skirtH())) : 0;
        return Math.max(1, Math.max(cap, Math.max(stem, skirt)));
    }

    /** Mushroom bound top above ground zero, m: cap with dome, stem, skirt. */
    static double top(MushroomModel.Shape s) {
        double u = capUnit(s);
        double cap = Math.max(s.capY() + s.ringB() * (1 + REACH_CAP * u / Math.min(s.ringA(), s.ringB())),
                s.domeY() + s.domeH() * (1 + REACH_CAP * u / Math.min(s.domeR(), s.domeH())));
        double skirt = s.skirtTau() > 0 ? s.skirtH() * (1 + REACH_SKIRT * skirtUnit(s) / Math.min(s.skirtW(), s.skirtH())) : 0;
        return Math.max(1, Math.max(cap, Math.max(stemTop(s), skirt)));
    }

    /** Transported dust front plus its existing billow/soft-edge reach, as in {@code mushGap}. */
    static double stemTop(MushroomModel.Shape s) {
        double flare = MushroomModel.dustFlare(s);
        double radius = Math.max(MushroomModel.stemRadius(0, s.stemR(), flare), MushroomModel.stemRadius(1, s.stemR(), flare));
        return s.stemTop() + REACH_STEM * radius;
    }

    /** Detonation water column at mushroom time; null — not a "water burst", column not yet risen or already fallen. */
    @Nullable
    static WaterModel.Column column(ClientNuclear.Active a, MushroomModel.Shape s) {
        return a.water.wet() > 0 ? WaterModel.column(s.t(), a.d.yieldKt()) : null;
    }

    /**
     * Bound radius with water column, m: billowed wall and crown, like mushroom parts, using the elliptical cross-section measure of the crown
     * ring ({@code mushGap}); wall uses distance.
     */
    static double reach(MushroomModel.Shape s, @Nullable WaterModel.Column c) {
        if (c == null) return reach(s);
        double r = REACH_COLUMN * columnUnit(c), a = c.crownA();
        return Math.max(reach(s), Math.max(c.mid() + c.half() + r, c.mid() + a * (1 + r / Math.min(a, c.crownH()))));
    }

    /** Bound top with water column above water, m. */
    static double top(MushroomModel.Shape s, @Nullable WaterModel.Column c) {
        if (c == null) return top(s);
        double r = REACH_COLUMN * columnUnit(c);
        return Math.max(top(s), c.wallTop() + c.crownH() * (1 + r / Math.min(c.crownA(), c.crownH())));
    }

    /** Column billow unit, m ({@code mushColumnUnit}): wall thickness. */
    static double columnUnit(WaterModel.Column c) {
        return 2 * c.half();
    }

    /**
     * Bounds ({@code mushSpan}): cylinder along an axis tilted by {@code drift} at rise-end height {@code end}.
     * Drift fraction grows with height only up to {@code end} ({@code mushShare}); above the top and below ground (down to 0.05 top), the mushroom
     * axis no longer tilts, while the inclined axis keeps receding: widen bounds by that amount. All values use the same units.
     */
    static double boundRadius(double reach, double drift, double top, double end) {
        return reach + drift * Math.max(0.05 * top, top - end) / end;
    }

    /** Cap billow unit, m ({@code mushCapUnit}): smaller semiaxis of ring cross-section. */
    static double capUnit(MushroomModel.Shape s) {
        return Math.min(s.ringA(), s.ringB());
    }

    /** Skirt billow unit, m ({@code mushSkirtUnit}): smaller semiaxis of its cross-section. */
    static double skirtUnit(MushroomModel.Shape s) {
        return Math.min(s.skirtW(), s.skirtH());
    }

    /**
     * Mushroom bounds in frustum; with {@code rays} (daytime: shadow direction, {@link #shadowSun}), include its shadow: bounds
     * extended away from the sun to world bottom {@code bottom} (y) — mushroom shadow falls on ground even if the mushroom itself is out of frame
     * (looking down while in its shadow).
     */
    private static boolean inView(ClientNuclear.Active a, MushroomModel.Shape s, float partial, Vec3 eye, double[] rays, double bottom,
                                  FrustumIntersection frustum) {
        Detonation d = a.d;
        double[] w = DRIFT;
        drift(d, s.t(), w);
        WaterModel.Column c = column(a, s);
        foot(a, s, partial, FOOT_LAYER);
        double r = reach(d, s, c, FOOT_LAYER) + 0.5 * Math.hypot(w[0], w[1]);
        float cx = (float) (d.burst().x + 0.5 * w[0] - eye.x), cz = (float) (d.burst().z + 0.5 * w[1] - eye.z), ground = (float) (d.groundY() - eye.y);
        LO.set((float) (cx - r), ground - 4, (float) (cz - r));
        HI.set((float) (cx + r), (float) (ground + d.blocks(top(s, c))), (float) (cz + r));
        if (rays != null) {
            // bound top descends along sunlight to world bottom: horizontal distance is height divided by tangent of sun elevation
            double fall = (HI.y - (bottom - eye.y)) / rays[1];
            float sx = (float) (-rays[0] * fall), sz = (float) (-rays[2] * fall);
            LO.set(Math.min(LO.x, LO.x + sx), (float) Math.min(LO.y, bottom - eye.y), Math.min(LO.z, LO.z + sz));
            HI.set(Math.max(HI.x, HI.x + sx), HI.y, Math.max(HI.z, HI.z + sz));
        }
        return frustum.intersectAab(LO, HI, SIDES_AND_NEAR) < 0;
    }

    /**
     * Unit sun direction for shadow ({@code mushShadowSun}): elevation sine at least {@link #SHADOW_LOW},
     * horizontal direction matches sun. Into {@code out}.
     */
    static void shadowSun(double x, double y, double z, double[] out) {
        double e = Math.clamp(y, SHADOW_LOW, 1), h = Math.hypot(x, z);
        if (h < 1e-6) {
            out[0] = 0;
            out[1] = 1;
            out[2] = 0;
            return;
        }
        double c = Math.sqrt(1 - e * e) / h;
        out[0] = x * c;
        out[1] = e;
        out[2] = z * c;
    }

    /** Axes of the plane perpendicular to direction {@code s} ({@code mushShadowAxes}): a across s and world z axis, b = s × a. */
    static void shadowAxes(double[] s, double[] a, double[] b) {
        double cx = s[1], cy = -s[0], cz = 0;
        if (cx * cx + cy * cy < 0.01) {
            cx = 0;
            cy = s[2];
            cz = -s[1];
        }
        double l = Math.sqrt(cx * cx + cy * cy + cz * cz);
        a[0] = cx / l;
        a[1] = cy / l;
        a[2] = cz / l;
        b[0] = s[1] * a[2] - s[2] * a[1];
        b[1] = s[2] * a[0] - s[0] * a[2];
        b[2] = s[0] * a[1] - s[1] * a[0];
    }

    /**
     * Sun light above a point at height h over ground zero from three shadow-map layers ({@code mushShadowLayer}):
     * t0 above ground zero, t1 midway between it and world top, t2 above world top ({@code top} — world top above that same ground).
     */
    static double shadowLayer(double h, double top, double t0, double t1, double t2) {
        double x = Math.clamp(h / Math.max(top, 1), 0, 1);
        return x < 0.5 ? t0 + (t1 - t0) * 2 * x : t1 + (t2 - t1) * (2 * x - 1);
    }

    /**
     * Cloud half-width across the sun ({@code mushAcross}): radius of a circle with the same area as the shadow of a column of radius r
     * and height h on a plane perpendicular to the sun at elevation {@code mu} (sine): πr²·μ from top, 2rh·√(1 − μ²) from side.
     */
    static double across(double r, double h, double mu) {
        return Math.sqrt(r * (r * mu + 2 / Math.PI * h * Math.sqrt(Math.max(1 - mu * mu, 0))));
    }

    /**
     * Fraction of cloud-forward-scattered light reaching horizontal ground, in units of incident beam on it (beam cosine
     * {@code mu}), relative to an edgeless layer ({@code mushSeen}): a cloud of half-width w across at distance d along the
     * sun direction appears as a disk with hemispherical flux fraction v = w²/(w² + d²); onto ground, v·(μ + (1 − μ)·v/2) (infinite
     * layer (1 + μ)/2, narrow cloud like a beam, v·μ).
     */
    static double seen(double w, double d, double mu) {
        double v = w * w / Math.max(w * w + d * d, 1e-6);
        return v * (mu + (1 - mu) * 0.5 * v) / mu;
    }

    /**
     * Fraction of sunlight transmitted by mushroom matter to a shadow-map layer ({@code through} in {@code fx_mush_shadow}):
     * direct beam through optical depth {@code tau}, plus light scattered forward by it (two streams in a column along the sun, albedo {@code w},
     * asymmetry {@code g} after peak truncation), with {@link #seen} fraction — cloud of half-width {@code width}, centered
     * {@code d} from the layer.
     */
    static double shadowThrough(double tau, double d, double width, double w, double g, double mu) {
        if (tau < 1e-4) return 1;
        double scattered = Math.max(PallLight.diffuse(w, g, tau, tau, 0, 1, 1, 1, 0, 0, 0, -2.0 / 3), 0);
        return Math.exp(-tau) + scattered * seen(width, d, mu);
    }

    /**
     * Mushroom shadow-map tile in the plane across the sun (axes {@code a}, {@code b}, {@link #shadowAxes}), relative to ground zero:
     * projection of bounds ({@code mushLine}: cylinder of radius {@code boundR} around an axis tilted by drift {@code dx}, {@code dz}
     * at height {@code end}, from −0.05 top {@code top} to top), with {@link #SHADOW_PAD} texel margin on each side.
     * Into {@code out}: u, v origin and sides. All values in blocks.
     */
    static void shadowRect(double[] a, double[] b, double boundR, double dx, double dz, double top, double end, double[] out) {
        double kx = dx / Math.max(end, 1), kz = dz / Math.max(end, 1), lo = -0.05 * top;
        for (int i = 0; i < 2; i++) {
            double[] e = i == 0 ? a : b;
            // axis is a line from bottom to top: projection extrema at endpoints; cross-section circle gives ± radius × plane-axis horizontal component
            double p0 = e[0] * kx * lo + e[1] * lo + e[2] * kz * lo, p1 = e[0] * kx * top + e[1] * top + e[2] * kz * top;
            double r = boundR * Math.hypot(e[0], e[2]);
            double min = Math.min(p0, p1) - r, size = Math.max(Math.abs(p1 - p0) + 2 * r, 1);
            double padded = size * SHADOW / (SHADOW - 2.0 * SHADOW_PAD);
            out[i] = min - SHADOW_PAD * padded / SHADOW;
            out[2 + i] = padded;
        }
    }

    /**
     * Ball surface pattern at t (detonation seconds), into {@code out}: pattern frame position and its cap share
     * ({@code mushroom.glsl}: texel 16 z, w). Position: −1 before brightness minimum (surface is the smooth shock front,
     * no pattern); −1 to 0 until breakaway (uneven interior emerges through the clearing front,
     * {@code FireballModel.unmasked}); then frame follows rise distance ({@code FireballModel.surfaceRise}). Share while the ball is
     * a ball: 1 − ring fraction ({@code MushroomModel.rollUp}); billow tongues color ring edges (MUSH_SKIN). Pattern not loaded
     * ({@code ready}) means share 0: ball as before.
     */
    static void ballSurface(double t, double hobM, double yieldKt, boolean surface, boolean ready, double[] out) {
        ballSurface(t, hobM, yieldKt, surface, ready ? NukeBallSurface.rises() : null, out);
    }

    /** Same from pattern frame distances {@code rises} (null — pattern not loaded). */
    static void ballSurface(double t, double hobM, double yieldKt, boolean surface, float[] rises, double[] out) {
        double unmasked = FireballModel.unmasked(t, yieldKt);
        double frame = rises == null ? 0 : NukeBallSurface.frame(rises, FireballModel.surfaceRise(t, hobM, yieldKt, surface));
        out[0] = unmasked < 1 ? unmasked - 1 : frame;
        out[1] = rises != null ? 1 - MushroomModel.rollUp(t, yieldKt, surface) : 0;
    }

    /**
     * Mushroom into a table row starting at {@code o} ({@code mushroom.glsl}: mushroom table); by day ({@code day}) also its shadow-map
     * tile, whose upper layer is world top {@code worldTop} (y).
     */
    private static void write(ClientNuclear.Active a, MushroomModel.Shape s, Vec3 eye, float partial, boolean day, double worldTop,
                              float[] into, int o) {
        Detonation d = a.d;
        double t = s.t(), y = d.yieldKt();
        double[] w = DRIFT;
        drift(d, t, w);
        WaterModel.Column c = column(a, s);
        double top = d.blocks(top(s, c)), end = Math.max(d.blocks(s.endTop()), 1);
        foot(a, s, partial, FOOT_LAYER);
        double boundR = boundRadius(reach(d, s, c, FOOT_LAYER), Math.hypot(w[0], w[1]), top, end);
        double ringA = d.blocks(s.ringA()), ringB = d.blocks(s.ringB());
        // cap matter (drops, NO₂, soil; over water the ball lofts water, not soil), and its extinction: optical depth across
        // the smaller ring-section diameter divided by that diameter in blocks
        double wet = a.water.wet(), soil = MushroomModel.soil(y, d.hobMetres(), d.surface()) * (1 - a.water.under());
        double across = 2 * Math.min(ringA, ringB);
        // steam follows water picked up by the rolling ring: absent for the ball
        double capSigma = capTau(s, capMatter(s, soil, wet * MushroomModel.rollUp(t, y, d.surface()), CAP)) / Math.max(across, 1);
        double skirtSigma = s.skirtTau() / Math.max(2 * d.blocks(Math.min(s.skirtW(), s.skirtH())), 1);
        double tCore = MushroomModel.coreTemperature(t, y, d.surface());
        put(into, o, 0, d.burst().x - eye.x, d.burst().z - eye.z, d.groundY() - eye.y, 1);
        put(into, o, 1, w[0], w[1], d.blocks(s.endTop()), boundR);
        put(into, o, 2, d.blocks(s.capY()), d.blocks(s.capR()), d.blocks(s.ringR()), top);
        put(into, o, 3, ringA, ringB, d.blocks(s.domeY()), d.blocks(s.domeR()));
        // cap billow tile is two smaller cross-section diameters: each billow a bubble half the cross-section
        put(into, o, 4, d.blocks(s.domeH()), capSigma, Math.max(across, 1), t);
        put(into, o, 5, CAP[0], CAP[1], CAP[2], CAP[3]);
        put(into, o, 6, d.blocks(s.stemTop()), d.blocks(s.stemR()), d.blocks(MushroomModel.dustFlare(s)), s.stemTau());
        matter(into, o, 7, mix(DUST, STEAM, 0.8 * wet));
        put(into, o, 8, d.blocks(s.skirtR()), d.blocks(s.skirtW()), d.blocks(s.skirtH()), skirtSigma);
        matter(into, o, 9, mix(DUST, CloudPuffs.SPRAY, a.water.under()));
        put(into, o, 10, MushroomModel.AMBIENT_K, tCore - MushroomModel.AMBIENT_K, d.blocks(s.capBottom()), Math.max(1, 3 * d.blocks(s.stemR())));
        // stem jets rise with air drawn by the ring (MushroomModel.stemRise); fires below mushroom: storm smoke top
        // (the same top reached by its pall columns, FireStorms.slot), and fire-zone radius (light is fire, after frame
        // lighting)
        put(into, o, 11, d.blocks(MushroomModel.stemRise(t, d.hobMetres(), y, d.surface())), a.pall.hazeTop(), a.pall.fireReach(), FOOT_LAYER[4]);
        // cap churn time (fire light beneath mushroom is fire, after frame lighting)
        put(into, o, 12, 0, 0, 0, churn(d).at(t));
        // water column: wall extinction is transverse optical depth divided by wall thickness, × water not yet fallen and no longer steam, × degree of
        // "water burst"; wall jets move with water — vertical noise offset by column top (up while growing, down
        // while falling)
        if (c != null) {
            double half = d.blocks(c.half()), water = c.water() * WaterModel.columnShow(t, y) * wet;
            put(into, o, 13, d.blocks(c.mid()), half, d.blocks(c.wallTop()), COLUMN_TAU * water / Math.max(2 * half, 1));
            matter(into, o, 14, CloudPuffs.SPRAY, G_WATER);
            put(into, o, 15, d.blocks(c.crownR()), d.blocks(c.crownH()), d.blocks(c.top()), d.blocks(COLUMN_TILE * WaterModel.columnRadius(y)));
        }
        // ball and cap outside: edge share of core temperature contrast (full for ball), edge-billow amplitude (smooth sphere for ball);
        // ball surface pattern (NukeBallSurface): frame position and pattern share versus billow tongues (ballSurface)
        double[] ball = BALL_SURFACE;
        ballSurface(t, d.hobMetres(), y, d.surface(), NukeBallSurface.ready(), ball);
        put(into, o, 16, MushroomModel.edgeShare(t, y, d.surface()), MushroomModel.billows(t, y, d.surface()), ball[0], ball[1]);
        if (day) {
            // sun shadow: map tile projects bounds across the sun; layers above ground zero, midway
            // between it and world top, and above world top
            shadowRect(AXIS_A, AXIS_B, boundR, w[0], w[1], top, end, RECT);
            put(into, o, SHADOW_RECT, RECT[0], RECT[1], RECT[2], RECT[3]);
        }
        // ground beneath mushroom is dust-layer top at stem base (reflection from below onto stem, skirt and cap underside)
        groundUnder(a, d.blocks(s.stemR()), partial, GROUND);
        put(into, o, SHADOW_TOP, Math.max(worldTop - d.groundY(), 16), GROUND[0], GROUND[1], GROUND[2]);
        put(into, o, FOOT, FOOT_LAYER[0], FOOT_LAYER[1], FOOT_LAYER[2], FOOT_LAYER[3]);
        new FootColumn(FOOT_LAYER[0], FOOT_LAYER[1], FOOT_LAYER[2], Math.min(FOOT_LAYER[3], d.blocks(s.stemTop())), FOOT_LAYER[4])
                .pack(FOOT_LAYER[4], into, o + 4 * FOOT_COLUMNS);
    }

    private static final double[] FOOT_LAYER = new double[5], LAYER_AT = new double[2];

    /**
     * Stem-base inflow into {@code out} ({@code mushroom.glsl}: texel 19), blocks: wave dust-layer top at stem
     * wall, ground extinction × mushroom share (1/block; {@link PallField#footLayer}; inflow disperses with mushroom),
     * inflow share at wall ({@link DustModel#stemShare}: same as dust carried by stem), and how far stem air has risen since inflow began at
     * the wall ({@link MushroomModel#stemRise} since front arrival + {@link DustModel#stemFrom}: immediate for surface burst, for
     * airburst after the ball downdraft subsides). Without stem dust or pall, no inflow (share 0).
     */
    static void foot(ClientNuclear.Active a, MushroomModel.Shape s, float partial, double[] out) {
        foot(a.d, a.pall, NukePall.water(a), s, a.ticks(partial), out);
    }

    /** Same for detonation {@code d} with pall {@code pall} and water beneath it {@code water} at {@code ticks} (client ticks). */
    static void foot(Detonation d, PallField pall, PallField.Water water, MushroomModel.Shape s, double ticks, double[] out) {
        out[0] = 1;
        out[1] = out[2] = out[3] = out[4] = 0;
        if (s.stemTau() <= 0 || !pall.visible(ticks)) return;
        double hob = Math.max(0, d.hobMetres()), y = d.yieldKt(), t = s.t();
        double wall = MushroomModel.stemRadius(0, s.stemR(), MushroomModel.dustFlare(s));
        pall.footLayer(d.blocks(wall), ticks, water, LAYER_AT);
        double from = HobBlast.arrivalSeconds(wall, hob, y) + DustModel.stemFrom(hob, y, FireballModel.maxRadius(y, d.surface()));
        if (t <= from || LAYER_AT[0] <= 0) return;
        out[0] = LAYER_AT[1];
        out[1] = LAYER_AT[0] * s.life();
        out[2] = DustModel.stemShare(wall, y);
        out[3] = d.blocks(MushroomModel.stemRise(t, hob, y, d.surface()) - MushroomModel.stemRise(from, hob, y, d.surface()));
        out[4] = footMerge(out[0], out[1], Math.min(out[3], d.blocks(s.stemTop())),
                s.stemTau() / (2 * d.blocks(MushroomModel.stemRadius(d.metres(out[0]) / Math.max(s.capBottom(), 1), s.stemR(), MushroomModel.dustFlare(s)))));
    }

    /**
     * Height at which inflow merges into stem, blocks: where layer plus inflow at wall ({@link #footBoth}: σ/(1 + (h/top)²), below inflow
     * dust front {@code rise}) matches stem {@code stem} (extinction at layer top, 1/block; barely varies with height at the
     * base); above this, layer dust is mixed into the stem and the band behind the wall has faded
     * ({@link #footBand}). No higher than the inflow dust front.
     */
    static double footMerge(double top, double sigma, double rise, double stem) {
        double t = Math.max(top, 1), ratio = stem > 0 ? sigma / stem : Double.POSITIVE_INFINITY;
        double h = ratio > 1 ? t * Math.sqrt(ratio - 1) : 0;
        return Math.max(0, Math.min(h, rise));
    }

    /**
     * Mushroom bound radius in blocks including base inflow ({@link #foot}: {@code foot}): inflow band beyond the stem wall
     * is no wider than layer top ({@link #footWidth} at ground); billowed stem no wider than at ground or cap.
     */
    static double reach(Detonation d, MushroomModel.Shape s, @Nullable WaterModel.Column c, double[] foot) {
        double r = d.blocks(reach(s, c));
        if (foot[2] <= 0) return r;
        double flare = MushroomModel.dustFlare(s);
        double stem = (1 + REACH_STEM) * Math.max(MushroomModel.stemRadius(0, s.stemR(), flare), MushroomModel.stemRadius(1, s.stemR(), flare));
        return Math.max(r, d.blocks(stem) + foot[2] * footWidth(0, foot[0]));
    }

    /**
     * Inflow band beyond the stem wall at height {@code h} above ground, for a layer of height {@code top} (same units;
     * {@code mushFootWidth}): ground-level wind toward the stem carries wave dust into it and turns upward along its wall —
     * flow in a "ground–wall" corner (stream function ψ = x·z): the streamline along the distant layer top turns upward
     * at the wall, x·z → top². Smoothed near ground: top/√(1 + (h/top)²). (est.) Planar corner, while stem radius is of the same order as the layer.
     */
    static double footWidth(double h, double top) {
        double t = Math.max(top, 1), u = h / t;
        return t / Math.sqrt(1 + u * u);
    }

    /**
     * Fraction of ground-level layer extinction carried by inflow at height {@code h} ({@code mushFootShare}): a thinner band means
     * 1/(1 + (h/top)²), also diluted by the stem (est.).
     */
    static double footShare(double h, double top) {
        double u = h / Math.max(top, 1);
        return 1 / (1 + u * u);
    }

    /** Layer plus inflow at wall at height {@code h} ({@code mushFootBoth}): below inflow dust front {@code rise}, layer dust; above, none yet. */
    static double footBoth(double h, double top, double sigma, double rise) {
        double t = Math.max(top, 1);
        return sigma * footShare(h, t) * Math.max(0, Math.min(1, (rise - h) / t));
    }

    /**
     * Inflow extinction at height {@code h} without edge fraction ({@code mushFootDensity}): ground-level layer dust {@code sigma} ×
     * {@link #footShare} minus what the layer itself holds at this height ({@link PallField#profile}); at the wall, layer plus
     * inflow give sigma·footShare, without a step at layer top (air carries dust without dilution: inflow share
     * {@code share} sets band width, {@link #footBand}, not density; without inflow, 0); above {@code rise} (stem-air rise
     * since inflow began), no layer dust yet.
     */
    static double footDensity(double h, double top, double sigma, double share, double rise) {
        if (h < 0 || share <= 0) return 0;
        return Math.max(footBoth(h, top, sigma, rise) - sigma * PallField.profile(h / Math.max(top, 1)), 0);
    }

    /**
     * Inflow band beyond the stem wall at height {@code h} ({@code mushFootBand}): {@link #footWidth} × inflow share; from layer
     * top (or half merge height if lower), narrows toward the wall up to merge height {@code merge}
     * ({@link #footMerge}): above it, layer dust is mixed into the stem (carried by the stem, {@code StemDust}). Thus the inflow surface
     * merges into the stem surface rather than ending at a ledge (est.) (narrowing shape).
     */
    static double footBand(double h, double top, double share, double merge) {
        if (share <= 0) return 0;
        return share * footWidth(Math.max(h, 0), top) * footNarrow(h, top, merge);
    }

    /**
     * Inflow share at height {@code h} ({@code mushFootNarrow}): from layer top (or half merge height if lower)
     * fades by merge height {@code merge} (band beyond wall, {@link #footBand}; not density, which already matches
     * the stem there).
     */
    static double footNarrow(double h, double top, double merge) {
        return 1 - smooth(Math.min(top, 0.5 * merge), merge + 1e-6, h);
    }

    /**
     * Inflow share at {@code rho} from axis for stem radius {@code r} ({@code mushFootWall}): at the wall and outward; inside
     * the stem, dust is its own ({@code StemDust}). Inflow throughout the stem would fill it at blanket density up to the inflow dust
     * front — a ring across the stem (sandbox frames, review).
     */
    static double footWall(double rho, double r) {
        return smooth(0.6 * r, r, rho);
    }

    private static double smooth(double e0, double e1, double x) {
        double f = Math.max(0, Math.min(1, (x - e0) / (e1 - e0)));
        return f * f * (3 - 2 * f);
    }

    /** Where around the stem the mushroom samples the underlying dust layer, in stem radii ({@link #groundUnder}). (est.). */
    static final double GROUND_AT = 2;
    private static final double[] GROUND = new double[3];

    /**
     * Ground reflectance beneath mushroom per channel (rgb into {@code out}, linear, in diffuse light): what stem, skirt and cap
     * underside see below is not bare ground ({@code PALL_LAND}, {@code PALL_SEA} over water), but the top of the wave dust layer
     * lying on it ({@link PallField#dustTau}: dust and spray optical depth, no haze, at {@link #GROUND_AT} stem radii from ground zero; (est.) location
     * is estimated: stem bottom mostly sees nearby ground). Layer above ground uses two streams: ground at τ = 0 toward dust R∞
     * (thick-layer color is dust color itself, {@link NukePall#DUST}; spray over water), as e^(−2kτ) ({@link #slabOver}).
     */
    static void groundUnder(ClientNuclear.Active a, double stemR, float partial, double[] out) {
        double under = a.water.under(), land = PallLight.LAND + (PallLight.SEA - PallLight.LAND) * under, ticks = a.ticks(partial);
        double tau = a.pall.visible(ticks) ? a.pall.dustTau(GROUND_AT * stemR, 0, ticks, NukePall.water(a)) : 0;
        int top = mix(DUST, CloudPuffs.SPRAY, under);
        double g = PallLight.truncated(G);
        for (int i = 0; i < 3; i++) out[i] = slabOver(PallLight.linear(channel(top, i)), g, tau, land);
    }

    /**
     * Diffuse-light reflectance of a layer of optical depth tau above ground of albedo ground: thin layer gives ground, thick layer gives its R∞
     * (linear thick-layer color r), between them e^(−2kτ) (two streams: light to ground and back decays as e^(−kτ)
     * on each trip; (est.) no multiple reflection between layer and ground — upper estimate of ground share).
     */
    static double slabOver(double r, double g, double tau, double ground) {
        double k = PallLight.k(PallLight.albedo(r, g), g);
        return r + (ground - r) * Math.exp(-2 * k * Math.max(tau, 0));
    }

    /**
     * Air from eye to detonation d's mushroom ({@link Sight.Air}: haze thins with height), in model meters: in blocks the mushroom
     * is effects_scale times smaller, but haze to it is as for the real mushroom at its real altitude above sea level.
     */
    static Sight.Air air(Detonation d, Vec3 eye, double range) {
        return Sight.Air.at(d.metres(eye.y - WaterDecal.SEA_LEVEL), range);
    }

    /**
     * Same for shaders ({@code pall.glsl}: {@code PallAir}) in blocks: haze, clean air and weather at eye, 1 / haze-height
     * scale.
     */
    static void air(Detonation d, Vec3 eye, double range, Vector4f out) {
        Sight.Air a = air(d, eye, range);
        double scale = d.scale();
        out.set((float) (a.haze() / scale), (float) (a.clean() / scale), (float) (a.weather() / scale), (float) (1 / (Sight.HAZE_HEIGHT * scale)));
    }

    /** Detonation cap churn clock ({@link MushroomModel#churn}): one table per detonation for its lifetime. */
    static MushroomModel.Churn churn(Detonation d) {
        return CHURN.computeIfAbsent(d, k -> MushroomModel.churn(k.yieldKt(), k.hobMetres(), k.surface()));
    }

    /**
     * Cap matter from its contents ({@link MushroomModel}: drops, NO₂) and soil fraction {@code soil}: into {@code out},
     * albedo ω' per R, G, B channel and asymmetry g' (delta-Eddington, {@link PallLight}); returns extinction σ',
     * 1/m. Drops scatter (σ, asymmetry {@link #G_WATER}); NO₂ absorbs per channel (cross-section × molecules per cubic meter);
     * soil absorbs like a cap of color {@link #CAP_DUSTY} without NO₂. After forward-peak truncation, scattering is σ·(1 − g²), while
     * absorption remains: ω' = σ(1 − g²) / (σ(1 − g²) + Σ absorption) — same as (1 − f)ω/(1 − fω).
     * <p>
     * (est.): soil shifts asymmetry from drops toward pall dust ({@link #G} = 0.6) by soil fraction — no scattering measurements
     * for a dusty cap. After truncation, scattering is σ(1 − 0.36) versus σ(1 − 0.72): a surface-burst cap has
     * σ' 2.3 times that of a drop-only cap of equal water content.
     * <p>
     * One extinction for all channels (shader ray uses one optical depth), from green. NO₂ absorption in red and blue differs
     * from it: by multiples in the ball (no drops); in a freshly rolled 15 kt ring, red σ' is 17 % below
     * green, blue 74 % above; after a minute, 1.4 and 6 %. Color still uses each channel's ω'.
     * Ball before drops is NO₂ only: absorbs and emits its own light (ω' = 0).
     * <p>
     * Steam over water is drops of water picked up by the rolling ring: {@code steam} is water fraction beneath burst × ring
     * fraction ({@link MushroomModel#rollUp}). Material mixture: ω' = (σs₁ + σs₂)/(σe₁ + σe₂), mean ω' weighted by extinction
     * shares; steam gets {@link #STEAM_SHARE}·steam of cap extinction, its ω' from thick-layer color {@link #STEAM}.
     * Extinction and asymmetry stay the cap's: steam is drops like the cap's own drops. Previously steam mixed into on-screen layer color, and
     * inverting Eddington reflectance clamped to zero (negative at ω' &lt; 1/(4 − 3g'), {@link #wallReflectance})
     * raised every channel to ω' = 0.38 in the first steam frame: edge glow fell to ×0.77, and a fresh 1 kt ring turned gray.
     */
    static double capMatter(MushroomModel.Shape s, double soil, double steam, double[] out) {
        double g = G_WATER + (G - G_WATER) * Math.clamp(soil, 0, 1), gt = PallLight.truncated(g);
        double scatter = s.capDrops() * (1 - g * g), green = 0, share = Math.clamp(STEAM_SHARE * steam, 0, 1);
        for (int i = 0; i < 3; i++) {
            double dusty = PallLight.albedo(PallLight.linear(channel(CAP_DUSTY, i)), gt);
            double absorb = MushroomModel.capAbsorption(s, i) + soil * scatter * (1 / dusty - 1);
            double w = scatter / Math.max(scatter + absorb, 1e-30);
            out[i] = w + (steamAlbedo(i, gt) - w) * share;
            if (i == 1) green = absorb;
        }
        out[3] = gt;
        return scatter + green;
    }

    /** Steam albedo ω' in channel i at asymmetry g', from thick-layer color {@link #STEAM}. */
    static double steamAlbedo(int i, double g) {
        return PallLight.albedo(PallLight.linear(channel(STEAM, i)), g);
    }

    /**
     * Diffuse-light reflectance of a semi-infinite cap-matter layer, R∞ ({@code mushWallR} in {@code mushroom.glsl}:
     * walls of hollows between billows): two streams with hemispheric-mean closure (Toon et al. 1989, table 1),
     * (1 − s)/(1 + s), s = √((1 − ω')/(1 − ω'g')), from 0 for pure absorption to 1 for pure scattering. With Eddington closure
     * ({@link PallLight#reflectance}), backscatter γ₂ = (ω(4 − 3g) − 1)/4 is negative at ω' &lt; 1/(4 − 3g'), making R∞
     * negative (down to −0.07 at ω' = 0): a fresh 1 kt ring would have green and blue −0.02 and −0.06 instead of 0.05
     * and 0.02; 15 kt blue, 0.006 instead of 0.08.
     */
    static double wallReflectance(double w, double g) {
        double s = Math.sqrt(Math.max(1 - w, 0) / Math.max(1 - w * g, 1e-4));
        return (1 - s) / (1 + s);
    }

    /** Per-channel screen color 0..1 → RGB. */
    private static int display(double[] rgb) {
        int out = 0;
        for (int i = 0; i < 3; i++) out = out << 8 | (int) Math.round(255 * Math.clamp(rgb[i], 0, 1));
        return out;
    }

    /** Channel i (0 — R, 1 — G, 2 — B) of RGB color, 0..1. */
    private static double channel(int rgb, int i) {
        return ((rgb >> (16 - 8 * i)) & 0xFF) / 255.0;
    }

    /** Cap optical depth across the smaller ring-section diameter at extinction {@code sigma} (1/m, {@link #capMatter}). */
    static double capTau(MushroomModel.Shape s, double sigma) {
        return sigma * 2 * Math.min(s.ringA(), s.ringB());
    }

    /** Matter from thick-layer color (on screen): per-channel albedo ω' and asymmetry g', like pall ({@link PallLight}). */
    private static void matter(float[] into, int o, int texel, int rgb) {
        matter(into, o, texel, rgb, G);
    }

    /** Same with matter scattering asymmetry g. */
    private static void matter(float[] into, int o, int texel, int rgb, double asymmetry) {
        double g = PallLight.truncated(asymmetry);
        for (int i = 0; i < 3; i++) RGB[i] = PallLight.albedo(PallLight.linear(channel(rgb, i)), g);
        put(into, o, texel, RGB[0], RGB[1], RGB[2], g);
    }

    private static void put(float[] into, int o, int texel, double x, double y, double z, double w) {
        int i = o + 4 * texel;
        into[i] = (float) x;
        into[i + 1] = (float) y;
        into[i + 2] = (float) z;
        into[i + 3] = (float) w;
    }

    /** RGB color blend: fraction f of the second. */
    static int mix(int a, int b, double f) {
        double t = Math.max(0, Math.min(1, f));
        int r = 0;
        for (int sh = 16; sh >= 0; sh -= 8) {
            double c = ((a >> sh) & 0xFF) * (1 - t) + ((b >> sh) & 0xFF) * t;
            r |= (int) Math.round(c) << sh;
        }
        return r;
    }
}
