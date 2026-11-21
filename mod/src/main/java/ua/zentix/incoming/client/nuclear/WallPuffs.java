package ua.zentix.incoming.client.nuclear;

import java.util.IdentityHashMap;
import java.util.Map;

import net.minecraft.client.Minecraft;
import org.joml.Vector3f;
import ua.zentix.incoming.client.fx.layer.FxAtlas;
import ua.zentix.incoming.client.fx.smoke.CloudModel;
import ua.zentix.incoming.client.fx.smoke.SmokeGrid;
import ua.zentix.incoming.nuclear.Detonation;

/**
 * presentation: not physical. The young dust wall — from the shock front to {@link #YOUNG_S} seconds behind it — as one look
 * model drawn by soft dust billows of the effects layer ({@code far/SmokePuffs}, the CC0 JangaFX "Grenade Dust Impact" frames,
 * {@link SmokeGrid.Look#EJECTA}). Its hump, rise, curl and settling are its own (built for the look Artem described, LOOK.md
 * 21.11), not a law with corrections. Physics enters only as what the screen shows: where and when the front touches the
 * ground ({@link Detonation#arrivalTicks}), the layer the wall settles into and how dense it is there (the pall's own
 * {@code DustModel} column at the hand-over age, so the pall continues it without a seam), dust colour of the ground. Behind
 * {@link #YOUNG_S} the pall ({@link PallField}) draws the layer; its head ({@link BlastWall}) is not drawn while this is on.
 * <p>
 * Billows sit on world-fixed square cells around ground zero (tier k: {@link #CELL}·2^k m), each a vertical Gaussian segment
 * from the ground to the wall top at its place; the tier follows distance from the eye (cell ≈ {@link #PER_DISTANCE} of it, two
 * tiers cross-faded by mass), so the screen holds a few billows per wall height at any distance and the count stays bounded.
 */
public final class WallPuffs implements SmokeGrid.Source {
    /** Probe switch (dust-wall-pass, probe B): {@code -Dincoming.wallPuffs=false} restores the pall head. */
    public static final boolean ON = !"false".equals(System.getProperty("incoming.wallPuffs"));
    /** Young wall span behind the front, s; the pall fades in over the last {@link #HANDOFF_S}. */
    public static final double YOUNG_S = 10, HANDOFF_S = 2.5;
    /** Age at which the wall has settled, s: its height is the layer's then (input of scale). */
    static final double SETTLED_S = 1;
    /** Finest cell, m, and cell size per metre of eye distance. */
    static final double CELL = 16, PER_DISTANCE = 0.12;
    static final int TIERS = 9;
    /** Rise time constant, s; hump: extra height share (±{@link #HUMP_SPREAD} along the front), its time and width, s. */
    static final double RISE_S = 0.12, HUMP = 0.42, HUMP_SPREAD = 0.12, HUMP_AT = 0.32, HUMP_W = 0.28;
    /** Unevenness of the settled top along and behind the front (±), and its scale, m. */
    static final double UNEVEN = 0.22, UNEVEN_M = 230;
    /** Young dust is denser than the settled layer: extra optical depth share decaying over {@link #DENSE_S}. */
    static final double DENSE = 1.6, DENSE_S = 2.5;
    /** Curl: the top is thrown outward by the wind behind the front, then pulled back (share of the top). */
    static final double CURL = 0.45, CURL_S = 0.45, BACK = 0.12;

    private static final Map<ClientNuclear.Active, WallPuffs> WALLS = new IdentityHashMap<>();

    private final ClientNuclear.Active a;
    private final float dustR, dustG, dustB;
    private final double dustK;
    /** Hand-over layer per pall node: top (blocks) and vertical optical depth. */
    private final double[] top = new double[PallField.BINS], tau = new double[PallField.BINS];
    private boolean gone;
    private int drawn;

    private WallPuffs(ClientNuclear.Active a) {
        this.a = a;
        dustR = SmokeGrid.dustAlbedo((float) NukePall.DUST[0]);
        dustG = SmokeGrid.dustAlbedo((float) NukePall.DUST[1]);
        dustB = SmokeGrid.dustAlbedo((float) NukePall.DUST[2]);
        dustK = SmokeGrid.dustK(dustR, dustG, dustB);
        // scale inputs: the lofted layer's height when the hump has settled ({@link #SETTLED_S}: the dusty boundary layer, Kuhl —
        // 58 m at 4.5 km from 1 Mt) and its density at the hand-over (the pall continues it; its later rise over flash-heated
        // ground is the pall's own law, cross-faded over the hand-over band)
        double settledAt = SETTLED_S / a.d.scale(), handover = YOUNG_S / a.d.scale();
        for (int i = 0; i < PallField.BINS; i++) {
            top[i] = Math.max(a.d.blocks(a.pall.layer(i).height(settledAt)), PallField.MIN_TOP_BLOCKS);
            tau[i] = a.pall.layer(i).depth(handover);
        }
    }

    /** Client tick: one source per land detonation while its young wall lives. */
    static void tick(ClientNuclear.Active a) {
        if (!ON || a.pall == null || WALLS.containsKey(a)) return;
        WallPuffs w = new WallPuffs(a);
        WALLS.put(a, w);
        SmokeGrid.add(w);
    }

    static void reset() {
        for (WallPuffs w : WALLS.values()) w.gone = true;
        WALLS.clear();
    }

    static void clean() {
        WALLS.values().removeIf(WallPuffs::ended);
    }

    /** Ground distance of the front from ground zero at {@code ticks}, blocks. */
    private double front(double ticks) {
        double slant = a.d.frontRadius(ticks), hob = a.d.blocks(Math.max(0, a.d.hobMetres()));
        return Math.sqrt(Math.max(0, slant * slant - hob * hob));
    }

    private static double hash(long x, long z, long salt) {
        long h = x * 0x9E3779B97F4A7C15L ^ z * 0xC2B2AE3D27D4EB4FL ^ salt * 0x165667B19E3779F9L;
        h ^= h >>> 31;
        h *= 0xBF58476D1CE4E5B9L;
        h ^= h >>> 29;
        return (h >>> 11) * 0x1.0p-53;
    }

    /** Smooth value noise on the world plane, 0..1, cell {@code size} blocks. */
    private static double noise(double x, double z, double size, long salt) {
        double u = x / size, v = z / size;
        long i = (long) Math.floor(u), j = (long) Math.floor(v);
        double fu = u - i, fv = v - j;
        fu = fu * fu * (3 - 2 * fu);
        fv = fv * fv * (3 - 2 * fv);
        double a0 = hash(i, j, salt), a1 = hash(i + 1, j, salt), b0 = hash(i, j + 1, salt), b1 = hash(i + 1, j + 1, salt);
        return (a0 + (a1 - a0) * fu) + ((b0 + (b1 - b0) * fu) - (a0 + (a1 - a0) * fu)) * fv;
    }

    private static double smooth(double e0, double e1, double x) {
        double t = Math.clamp((x - e0) / (e1 - e0), 0, 1);
        return t * t * (3 - 2 * t);
    }

    /** Hand-over layer at ground distance r (blocks): node interpolation, into[0] top (blocks), into[1] optical depth. */
    private void layer(double r, double[] into) {
        double u = r / a.pall.reach() * PallField.BINS - 0.5;
        int i = (int) Math.floor(u);
        double f = u - i;
        int i0 = Math.clamp(i, 0, PallField.BINS - 1), i1 = Math.clamp(i + 1, 0, PallField.BINS - 1);
        into[0] = top[i0] + (top[i1] - top[i0]) * f;
        into[1] = u > PallField.BINS ? 0 : tau[i0] + (tau[i1] - tau[i0]) * f;
    }

    private final double[] lay = new double[2];

    @Override
    public boolean emit(double seconds, SmokeGrid.Sink out) {
        if (gone) return false;
        Detonation d = a.d;
        double t = a.ticks(0) + (seconds - SmokeGrid.seconds(0)) * 20;
        double outer = front(t), inner = front(t - 20 * YOUNG_S);
        drawn = 0;
        if (outer <= 0 || inner >= a.pall.reach()) return false;
        double gx = d.burst().x, gz = d.burst().z, gy = d.groundY();
        double ex = SmokeGrid.eyeX(), ey = SmokeGrid.eyeY(), ez = SmokeGrid.eyeZ();
        Vector3f look = Minecraft.getInstance().gameRenderer.getMainCamera().getLookVector();
        double lx = look.x(), lz = look.z(), ll = Math.hypot(lx, lz);
        if (ll > 1e-6) {
            lx /= ll;
            lz /= ll;
        }
        double metre = d.blocks(1), cell0 = CELL * metre, hobB = d.blocks(Math.max(0, d.hobMetres()));
        long seed = d.seed();
        out.look(SmokeGrid.Look.EJECTA, FxAtlas.lifeFrame(SmokeGrid.Look.EJECTA, 0.35));
        for (int k = 0; k < TIERS; k++) {
            double cell = cell0 * (1L << k);
            // tier k weight peaks at eye distance cell / PER_DISTANCE, triangle in log2 distance (tiers sum to one)
            double dk = cell / PER_DISTANCE, near = k == 0 ? 0 : dk * 0.5, far = k == TIERS - 1 ? Double.MAX_VALUE : dk * 2;
            double reach = Math.min(far, 4 * a.pall.reach());
            long i0 = (long) Math.floor((ex - gx - reach) / cell), i1 = (long) Math.ceil((ex - gx + reach) / cell);
            long j0 = (long) Math.floor((ez - gz - reach) / cell), j1 = (long) Math.ceil((ez - gz + reach) / cell);
            if ((i1 - i0) * (j1 - j0) > 40_000) continue;
            for (long i = i0; i <= i1; i++) {
                for (long j = j0; j <= j1; j++) {
                    double px = gx + (i + 0.15 + 0.7 * hash(i, j, seed + k)) * cell, pz = gz + (j + 0.15 + 0.7 * hash(i, j, seed + 31 + k)) * cell;
                    double rx = px - gx, rz = pz - gz, r = Math.hypot(rx, rz);
                    if (r > outer + cell || r < inner - cell) continue;
                    double vx = px - ex, vz = pz - ez, dist = Math.hypot(vx, vz);
                    double w;
                    if (dist < dk) w = k == 0 ? 1 : smooth(0, 1, Math.log(dist / near) / Math.log(2));
                    else w = k == TIERS - 1 ? 1 : 1 - smooth(0, 1, Math.log(dist / dk) / Math.log(2));
                    if (w <= 0.01) continue;
                    // outside 60° of the horizontal gaze (the frame is ±43° at the judging zoom), farther than two cells: not seen
                    if (dist > 2 * cell && (vx * lx + vz * lz) < 0.5 * dist) continue;
                    double age = (t - d.arrivalTicks(Math.hypot(r, hobB))) / 20;
                    if (age <= 0 || age > YOUNG_S) continue;
                    layer(r, lay);
                    if (lay[1] <= 0) continue;
                    emitPatch(out, px, gy, pz, rx / Math.max(r, 1e-6), rz / Math.max(r, 1e-6), age, cell, w, lay[0], lay[1], metre, seed);
                }
            }
        }
        return drawn > 0;
    }

    /** One billow column at (x, z) with ground y: age s since the front touched it, cell blocks, tier weight, layer top/depth. */
    private void emitPatch(SmokeGrid.Sink out, double x, double y, double z, double ux, double uz, double age, double cell, double w,
                           double settled, double depth, double metre, long seed) {
        // uneven along and behind the front (world-fixed, large scale): settled top and hump strength
        double n1 = noise(x, z, UNEVEN_M * metre, seed + 101), n2 = noise(x, z, 0.6 * UNEVEN_M * metre, seed + 202);
        double h = settled * (1 + UNEVEN * 2 * (n1 - 0.5));
        double hump = HUMP + HUMP_SPREAD * 2 * (n2 - 0.5);
        double rise = 1 - Math.exp(-age / RISE_S);
        double bump = hump * Math.exp(-(age - HUMP_AT) * (age - HUMP_AT) / (2 * HUMP_W * HUMP_W));
        double topH = Math.max(h * rise * (1 + bump), 0.5 * metre);
        // curl: top thrown outward by the post-front wind, then back with the negative phase
        double throwOut = CURL * (Math.exp(-age / CURL_S) - Math.exp(-age / 0.06)) - BACK * Math.exp(-(age - 2.2) * (age - 2.2) / 1.2);
        double lean = throwOut * topH;
        // density: young dust denser, then the layer's own; fades out as the pall takes over
        double fade = 1 - smooth(YOUNG_S - HANDOFF_S, YOUNG_S, age);
        double tauV = depth * (1 + DENSE * Math.exp(-age / DENSE_S)) * (0.75 + 0.5 * noise(x, z, 0.4 * UNEVEN_M * metre, seed + 303));
        double area = cell * cell / (metre * metre);
        double mass = tauV * area / dustK * w * fade * Math.min(1, age / 0.05);
        if (mass <= 0) return;
        double sh = 0.5 * cell, sv = Math.min(sh, 0.35 * topH);
        double half = Math.max(0, 0.5 * topH - sv);
        double cy = y + Math.max(sv, 0.5 * topH);
        out.segment(x + 0.5 * lean * ux, cy, z + 0.5 * lean * uz, 0.5 * lean * ux, half, 0.5 * lean * uz, sh, sv, 0, mass, 0,
                dustR, dustG, dustB, CloudModel.T_AIR_K, 0);
        drawn++;
    }

    /** Scenario line: front ground radius, eye ground distance and its age, hand-over layer top/depth there, billows drawn. */
    String describe() {
        double t = a.ticks(0);
        double ex = SmokeGrid.eyeX() - a.d.burst().x, ez = SmokeGrid.eyeZ() - a.d.burst().z, r = Math.hypot(ex, ez);
        double age = (t - a.d.arrivalTicks(Math.hypot(r, a.d.blocks(Math.max(0, a.d.hobMetres()))))) / 20;
        layer(r, lay);
        return String.format(java.util.Locale.ROOT, "wall puffs: front %.0f inner %.0f, eye r %.0f age %.2f s, layer top %.1f tau %.1f, drawn %d",
                front(t), front(t - 20 * YOUNG_S), r, age, lay[0], lay[1], drawn);
    }

    static String describeAll() {
        StringBuilder b = new StringBuilder();
        for (WallPuffs w : WALLS.values()) b.append(w.describe()).append(' ');
        return b.isEmpty() ? "wall puffs: none" : b.toString().trim();
    }

    @Override
    public boolean ended() {
        return gone || a.ticks(0) > 20 * 2 && front(a.ticks(0) - 20 * YOUNG_S) >= a.pall.reach();
    }

    @Override
    public long seed() {
        return a.d.seed() ^ 0x3A11L;
    }

    @Override
    public double puffGrowth(double seconds) {
        return 2;
    }
}
