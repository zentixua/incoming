package ua.zentix.incoming.client.nuclear;

import net.minecraft.util.Mth;
import ua.zentix.incoming.nuclear.Detonation;
import ua.zentix.incoming.nuclear.model.CloudModel;
import ua.zentix.incoming.nuclear.model.DustModel;
import ua.zentix.incoming.nuclear.model.FireballModel;
import ua.zentix.incoming.nuclear.model.HobBlast;
import ua.zentix.incoming.nuclear.model.WaterModel;

/**
 * Head of the shock-wave dust layer (DESIGN-nuke §6): youngest, lowest and densest part of the same layer that later remains
 * as pall ({@link PallField}), governed by the same laws ({@link DustModel}). The front itself is invisible — a pressure jump in transparent
 * air; wind behind it along the ground ({@link Detonation#groundWind}) lofts ground dust and debris, or spray over water
 * ({@link WaterModel#spray}). The wave has no matter of its own.
 * <p>
 * The head is a wedge from the front back to where the positive phase ended (different at every distance, {@link HobBlast},
 * {@link #length}; shorter for the first ~0.6 s of a surface burst: the head and its pall transition do not enter the ground-level fireball,
 * {@link #underBall}): no dust yet at the leading edge, and zero layer height; rearward, lofted material grows with dynamic-pressure
 * impulse ({@link DustModel#lifted}), while the top follows Kuhl's dusty boundary layer, u^(5/6) ({@link #top}); thus
 * ground density peaks near the edge, one tenth into the wedge (2.6 times denser than at its end,
 * {@link #shape}), while the column above ground grows rearward. Behind the wedge, dust remains as a pall layer (its table holds the same dust,
 * {@link PallField}): the head hands over across half a wedge length ({@link #handoff}). Where wind no longer lofts dust,
 * edge density vanishes and the head fades, separating from the front — dust remains in place (MET: "stopped moving along the ground at
 * 945 m").
 * <p>
 * The wedge is a volume in the pall pass ({@code PallPass}: {@code pallWall*} in {@code pall.glsl}, slot parameters — {@link #slot}),
 * with layer eddies — noise in dust coordinates ({@link #carried}): billows form where the front tore dust from the ground and
 * stay there, shifted by wind behind the front by its phase displacement (tens of meters), while the front moves on. Relative to the front,
 * edge dust moves up and backward — the roller rolls this way. Model meters and seconds; world conversion through {@link Detonation#blocks}.
 */
public final class BlastWall {
    /**
     * Head at a time: dust leading edge (ground blocks from ground zero), wedge top (blocks), density 0..1 relative to the former
     * densest pall dust ({@link PallField#SIGMA_DENSE}, visibility 40 m; 0 — no head).
     */
    public record Wall(double head, double height, double density) {
        static final Wall NONE = new Wall(0, 0, 0);
    }

    /**
     * Head-billow noise tile is a fraction of the layer top at wave phase end ({@link DustModel.Column#first}, maximum over
     * distance): billows (quarter tile) are about a fifth of it; layer eddies match layer thickness (Kuhl §3). One tile per detonation
     * for its lifetime: noise uses dust coordinates, and a tile growing with the head would move billows along the ground.
     */
    static final double TILE = 0.9;
    /**
     * Air displacement behind the front over the local positive phase, fraction of U·t₊ (U — wind immediately behind the front): particle speed
     * follows Friedlander u(t) = U(1 − t/t₊)e^(−t/t₊), ∫₀^t₊ = U·t₊/e (Glasstone &amp; Dolan §3.57: decay shape; decay
     * coefficient 1 for weak distant waves; closer to ground zero decay is steeper and displacement smaller — upper estimate).
     */
    static final double CARRY = Math.exp(-1);
    /** Head hands over to pall from the wedge end (fraction of its length) to this fraction ({@link #handoff}). */
    static final double HANDOFF_FROM = 1, HANDOFF_TO = 1.5;
    /** No head when its extinction is below this, 1/m (visibility 4 km). */
    static final double GONE = 1e-3;
    /** Layer-table nodes by distance. */
    private static final int NODES = 256;
    /** Wilson cloud: shell center is this front-radius fraction (behind the front, in the negative phase), half-thickness is this fraction. */
    static final double WILSON_AT = 0.93, WILSON_WIDTH = 0.06;
    /** Wilson-cloud optical depth across the shell at its densest time. */
    static final double WILSON_TAU = 0.5;
    /** Wall slot parameters for the shader ({@link #slot}): head (mat4) and Wilson cloud (vec4). */
    public static final int SLOT = 20;

    private final Detonation d;
    /** Burst height above ground, m; yield as cube-root ratio to 15 kt (shape scale). */
    private final double hob, ys;
    /** Ground reach of the layer table, m. */
    private final double tableM;
    /**
     * Layer by distance (nodes 0 to {@link #tableM}): optical depth of all lofted material ({@link DustModel.Column#lifted}) and when the local
     * positive phase ends — game front arrival plus local phase ({@link DustModel.Column#positive}), in
     * model seconds (nondecreasing with distance).
     */
    private final double[] lifted = new double[NODES], phaseEnd = new double[NODES];
    /** Positive phase at the table end, model s: beyond the table, wedge end follows front travel over this phase. */
    private final double lastPositive;
    /** Humid air means a Wilson cloud exists ({@link CloudModel#wilsonCloud}). */
    private final boolean humid;
    /** Head-billow noise tile, m ({@link #TILE}). */
    private final double tileM;

    public BlastWall(Detonation d) {
        this(d, CloudModel.wilsonCloud(humidity(d)));
    }

    BlastWall(Detonation d, boolean humid) {
        this.d = d;
        this.humid = humid;
        hob = Math.max(0, d.hobMetres());
        ys = Math.cbrt(d.yieldKt() / 15);
        tableM = Math.max(1, HobBlast.rangeFor(0.2, hob, d.yieldKt()));
        double positive = 0, first = 0;
        for (int i = 0; i < NODES; i++) {
            double g = tableM * i / (NODES - 1);
            DustModel.Column c = DustModel.column(g, hob, d.yieldKt(), d.surface(), d.visibility(), DustModel.Surface.REFERENCE);
            lifted[i] = c.lifted();
            positive = c.positive();
            first = Math.max(first, c.first());
            phaseEnd[i] = arrival(g) + positive;
            if (i > 0) phaseEnd[i] = Math.max(phaseEnd[i], phaseEnd[i - 1]);
        }
        lastPositive = positive;
        tileM = TILE * first;
    }

    /** Ground-zero humidity (Wilson cloud visible in humid air): from client biome; 1 over water. */
    static double humidity(Detonation d) {
        var level = net.minecraft.client.Minecraft.getInstance().level;
        if (level == null) return 0.5;
        // surface block: on land, below first air; for crosshair water height (surface, 62.889), the water itself
        var pos = new net.minecraft.core.BlockPos(Mth.floor(d.burst().x), NukeWater.surfaceBlock(d.groundY()), Mth.floor(d.burst().z));
        if (!level.getFluidState(pos).isEmpty()) return 1;
        return level.getBiome(pos).value().getModifiedClimateSettings().downfall();
    }

    /** Layer table at {@code groundM} m from ground zero (linear between nodes; no dust beyond the table). */
    private double at(double[] table, double groundM) {
        double x = Math.max(0, groundM) / tableM * (NODES - 1);
        if (x >= NODES - 1) return 0;
        int i = (int) x;
        return Mth.lerp(x - i, table[i], table[i + 1]);
    }

    /** Game-front arrival at ground distance {@code groundM} m from ground zero, model seconds. */
    private double arrival(double groundM) {
        return d.arrivalTicks(d.slantOf(d.blocks(groundM))) / (20 * d.scale());
    }

    /** Ground front at time t (model seconds), m: game-front slant range converted to ground distance. */
    double shock(double t) {
        if (t <= 0) return 0;
        double front = d.metres(d.frontRadius(t * 20 * d.scale()));
        return Math.sqrt(Math.max(0, front * front - hob * hob));
    }

    /** Dust leading edge on ground at time t (model seconds), m: the game front itself — dust rises right behind it. */
    double head(double t) {
        return shock(t);
    }

    /**
     * Wedge length at time t, m: from the game front to where the local positive phase has ended ({@link #tail});
     * dust behind the edge is younger than its phase and lifted by wave wind; until phase end anywhere, the entire front travel.
     * The head plus pall transition ({@link #HANDOFF_TO} lengths) stays outside the ground fireball ({@link #underBall}): there is ball,
     * not dust, so the wedge is shorter — ending where dust is still younger than its phase ({@link #backAge}).
     */
    double length(double t) {
        double shock = shock(t), len = shock - tail(t), ball = underBall(t);
        if (ball > 0) len = Math.min(len, (shock - ball) / HANDOFF_TO);
        return Math.max(1e-3, len);
    }

    /**
     * Ground reach of the fireball from ground zero at time t (model seconds), m; 0 if it does not touch the ground
     * ({@link FireballModel#groundRadius}: the same ball as in the mushroom). For a 15 kt surface burst the front exits the ball by ~0.13 s
     * (for the first 2 ms it is a meter ahead of the ball — the head is a centimeter high), the wedge reaches phase end by ~0.6 s, and the ball
     * lifts off the ground by ~4.4 s; an airburst (592 m) ball does not touch ground.
     */
    double underBall(double t) {
        return FireballModel.groundRadius(t, hob, d.yieldKt(), d.surface());
    }

    /** Wedge end on ground at time t (model seconds), m. */
    private double back(double t) {
        return Math.max(0, shock(t) - length(t));
    }

    /**
     * Dust age at wedge end at time t, model seconds: since game-front arrival, capped at the local positive phase
     * (from the model).
     */
    private double backSeconds(double t) {
        double back = back(t);
        return Mth.clamp(t - arrival(back), 0, HobBlast.positivePhaseSeconds(back, hob, d.yieldKt()));
    }

    /**
     * Fraction of local positive phase elapsed by t where the wedge ends, 0..1: 1 — wedge reaches phase end
     * ({@link #tail}). Until phase end anywhere, the wedge spans all front travel, and dust at its end (ground zero) is younger than its
     * phase; likewise for the shorter wedge near the fireball. Such dust is lower and optically thinner: the boundary layer behind a fresh front starts
     * at zero thickness.
     */
    double backAge(double t) {
        double back = back(t);
        return Mth.clamp((t - arrival(back)) / Math.max(1e-9, HobBlast.positivePhaseSeconds(back, hob, d.yieldKt())), 0, 1);
    }

    /**
     * Ground distance at which the positive phase has ended by t (model seconds), m: game-front arrival plus local phase
     * is no later than t (0 — nowhere). Beyond the table, front travel over the phase at its end.
     */
    double tail(double t) {
        if (t <= phaseEnd[0]) return 0;
        if (t >= phaseEnd[NODES - 1]) return Math.max(tableM, shock(t - lastPositive));
        int lo = 0, hi = NODES - 1;
        while (hi - lo > 1) {
            int m = (lo + hi) >>> 1;
            if (phaseEnd[m] <= t) lo = m;
            else hi = m;
        }
        double f = (t - phaseEnd[lo]) / Math.max(1e-12, phaseEnd[hi] - phaseEnd[lo]);
        return tableM * (lo + Mth.clamp(f, 0, 1)) / (NODES - 1);
    }

    /**
     * Wedge top at time t, m: layer top at wedge end at the dust age there — dusty boundary layer from front travel along
     * its own normal past the location ({@link DustModel#waveTop}; at phase end — {@link DustModel.Column#first}).
     */
    double height(double t) {
        // at least a millimeter, like wedge length: shader divisors (smoothstep over top) stay nondegenerate
        return Math.max(1e-3, DustModel.waveTop(back(t), hob, d.yieldKt(), backSeconds(t)));
    }

    /** Ground extinction at wave phase end where dust was lofted at {@code groundM}, with top {@code top} (1/m). */
    private double sigma(double groundM, double top) {
        return at(lifted, groundM) / (PallField.column(0) * Math.max(top, 1e-3));
    }

    /**
     * Head-dust position in its own coordinates — where it was lofted (copy — {@code pallWallOrigin}): point (rx, rz — ground distance from
     * ground zero, z — above terrain) minus displacement with wind behind the front ({@code carried} × {@link #carriedShare} at
     * phase fraction u); air is stationary at ground level, above it follows the turbulent boundary-layer 1/7 law (z relative to wedge-end
     * top {@code top}), upward — 0.02–0.08 of displacement (Kuhl §3.4: vertical layer speed is 0.02 u∞ at the bottom and 0.04–0.12 at
     * the top). Head-billow noise uses these coordinates: billows stay where dust was torn away rather than traveling with the front.
     */
    static double[] origin(double rx, double rz, double z, double u, double top, double carried) {
        double zr = Mth.clamp(z / Math.max(top, 0.001), 0, 1);
        double x = carried * carriedShare(u) * Math.pow(zr, 1.0 / 7);
        double len = Math.max(Math.hypot(rx, rz), 1e-3);
        return new double[] {rx - rx / len * x, z - x * (0.02 + 0.06 * zr), rz - rz / len * x};
    }

    /** Wind immediately behind the front along the ground at ground distance {@code groundM} from ground zero, m/s. */
    double wind(double groundM) {
        return d.groundWind(d.blocks(Math.max(0, groundM)));
    }

    /**
     * Dust displacement with wind behind the front at time t (model seconds), m: air displacement over the entire positive phase ({@link #CARRY})
     * where the wedge middle is now. At its own location dust covers this distance over the phase — {@link #carriedShare} by phase fraction; during the
     * negative phase, wind reverses, weaker and longer (Glasstone and Dolan 1977, §3.05: pressure no more than
     * ~4 psi below atmospheric, phase longer than positive) — same Friedlander, returning dust toward ground zero.
     */
    double carried(double t) {
        double mid = Math.max(0, head(t) - 0.5 * length(t));
        return CARRY * wind(mid) * HobBlast.positivePhaseSeconds(mid, hob, d.yieldKt());
    }

    /**
     * Fraction of wind displacement ({@link #carried}) covered by dust over fraction u of the positive phase since front arrival: air
     * speed follows Friedlander wave pressure ({@code BlastPulse.friedlander}, same law as wind noise behind the front),
     * ∫₀^u (1 − x)e^(−x) dx / e^(−1) = u·e^(1 − u). At positive-phase end — 1; negative-phase wind reverses
     * (peak e^(−2) of the maximum, at 2t₊), and dust returns: 0.74 at 2t₊, 0.09 at 5t₊; total wave impulse is zero,
     * and distant air returns to its original location. Deviation: a strong nearby wave decays more steeply (b &gt; 1), leaving residual
     * displacement — absent here. Copy — {@code pallWallCarried}.
     */
    static double carriedShare(double u) {
        if (u <= 0) return 0;
        return u * Math.exp(1 - u);
    }

    /**
     * Ground dust extinction at wave phase end at time t (1/m): at the leading edge (dust being lofted now)
     * and wedge end, into {@code into}; spray extinction over water is third. Returns the maximum; while the front is inside the fireball
     * ({@link #underBall}), all zero: no head yet.
     */
    double sigmas(double t, double[] into) {
        double edge = head(t), len = length(t), top = height(t);
        if (edge <= underBall(t)) {
            java.util.Arrays.fill(into, 0, 3, 0);
            return 0;
        }
        // wedge shorter than phase (near the ball, or no phase has ended yet): end dust contains only a fraction of phase-total lofted material
        // (DustModel.lifted), and its top is lower — layer at this dust age (height); same phase fraction at the edge: lofted material at the front
        // grows linearly with age
        double age = backAge(t);
        into[0] = age * sigma(edge, top);
        into[1] = DustModel.lifted(age) * sigma(Math.max(0, edge - len), top);
        into[2] = PallField.SIGMA_SPRAY * WaterModel.spray(wind(Math.max(0, edge - 0.5 * len)));
        return Math.max(into[0], Math.max(into[1], into[2]));
    }

    /** Head at time t (model seconds): blocks and density relative to the former densest pall dust. */
    public Wall wall(double t) {
        double[] s = new double[3];
        if (t <= 0 || sigmas(t, s) < GONE) return Wall.NONE;
        return new Wall(d.blocks(head(t)), d.blocks(height(t)), Math.min(1, Math.max(s[0], s[1]) / PallField.SIGMA_DENSE));
    }

    /**
     * Wall slot parameters for {@code pall.glsl} at time t (model seconds), blocks and 1/block; false — neither head nor Wilson
     * cloud ({@code into} zeroed). Head is column-major mat4 ({@code pallWall*}):
     * <ul>
     *     <li>[0..3] — dust leading edge from ground zero, wedge length, top at its end; extinction scale (1/block; 0 — no
     *     head);</li>
     *     <li>[4..7] — dust displacement with wind behind the front ({@link #carried(double)}); spray over water: lag behind dust
     *     edge, top fraction, extinction (1/block);</li>
     *     <li>[8..11] — phase fraction elapsed at wedge end ({@link #backAge}: wedge fraction u means phase fraction u × this), 0, 0;
     *     billow noise tile ({@link #TILE});</li>
     *     <li>[12..15] — 0, 0; ground dust extinction at wave phase end, at leading edge and wedge end — fractions of
     *     scale.</li>
     * </ul>
     * [16..19] — Wilson cloud ({@code pallWilson}): shell-center radius from the detonation point, half-thickness, central
     * extinction (0 — no cloud), detonation-point height above ground zero.
     */
    public boolean slot(double t, float[] into) {
        java.util.Arrays.fill(into, 0, SLOT, 0f);
        boolean any = false;
        double[] s = new double[3];
        double ref = t > 0 ? sigmas(t, s) : 0;
        if (ref >= GONE) {
            any = true;
            double head = head(t), len = length(t), top = height(t);
            into[0] = (float) d.blocks(head);
            into[1] = (float) d.blocks(len);
            into[2] = (float) d.blocks(top);
            into[3] = (float) (ref / d.scale());
            into[4] = (float) d.blocks(carried(t));
            // over water, spray: lower than dust (crest stripping height), behind dust edge by front travel over SPRAY_LAG, but also
            // outside the ground fireball: closer to the front there
            double u = wind(Math.max(0, head - 0.5 * len)), ball = underBall(t);
            double lag = Math.max(0, head - head(Math.max(0, t - WaterModel.SPRAY_LAG)));
            if (ball > 0) lag = Math.min(lag, Math.max(0, head - ball - HANDOFF_TO * len));
            into[5] = (float) d.blocks(lag);
            into[6] = (float) Mth.clamp(WaterModel.sprayHeight(u) / Math.max(1, top), 0.15, 1);
            into[7] = (float) (s[2] / d.scale());
            into[8] = (float) backAge(t);
            // shader tile at least one block
            into[11] = (float) Math.max(1, d.blocks(tileM));
            into[14] = (float) (s[0] / ref);
            into[15] = (float) (s[1] / ref);
        }
        double wilson = wilson(t);
        if (wilson > 0) {
            any = true;
            double front = d.metres(d.frontRadius(t * 20 * d.scale())), w = WILSON_WIDTH * front;
            into[16] = (float) d.blocks(WILSON_AT * front);
            into[17] = (float) d.blocks(w);
            // optical depth across shell is WILSON_TAU at its densest time: Gaussian-shell ∫ = √π · half-thickness · σ
            into[18] = (float) (WILSON_TAU * wilson / (Math.sqrt(Math.PI) * Math.max(1e-3, d.blocks(w))));
            into[19] = (float) d.blocks(hob);
        }
        return any;
    }


    /**
     * Wilson cloud 0..1 at time t (model seconds): in humid air during the negative phase behind the front, air
     * expands and cools below dew point — a fog shell. Appears from 0.15·∛(Y/15) s, fades by 3.5·∛(Y/15) s (15 kt:
     * 0.15–3.5 s); absent in dry air.
     */
    double wilson(double t) {
        if (!humid) return 0;
        return smooth(0.15 * ys, 0.5 * ys, t) * (1 - smooth(1.5 * ys, 3.5 * ys, t));
    }

    /** Maximum ground distance from ground zero (without drift) for the head at time t, blocks; 0 — no head. */
    public double reach(double t) {
        double[] s = new double[3];
        return t > 0 && sigmas(t, s) >= GONE ? d.blocks(head(t)) : 0;
    }

    /** Maximum distance from the detonation point for a Wilson cloud at time t, blocks; 0 — no cloud. */
    public double wilsonReach(double t) {
        if (wilson(t) <= 0) return 0;
        return d.blocks((WILSON_AT + 2 * WILSON_WIDTH) * d.metres(d.frontRadius(t * 20 * d.scale())));
    }

    // ---------------------------------------------------------------- wedge laws (shared with shader: pallWall* in pall.glsl)

    /** Wedge top at fraction u of its length behind the edge, fraction of end top: u^(5/6) (Kuhl); full behind the wedge. Copy {@code pallWallTop}. */
    static double top(double u) {
        return Math.pow(Mth.clamp(u, 0, 1), 5 / 6.0);
    }

    /**
     * Ground density at wedge fraction u relative to its end: material lofted by this phase fraction ({@link DustModel#lifted}) in a
     * layer with top {@link #top}; column above ground grows rearward with lofted material. Densest at u ≈ 0.1 (2.6 times); behind the wedge,
     * 1. Copy {@code pallWallShape}.
     */
    static double shape(double u) {
        if (u <= 0) return 0;
        double x = Math.min(u, 1);
        return DustModel.lifted(x) / Math.pow(Math.max(x, 1e-4), 5 / 6.0);
    }

    /** Head share behind the wedge: from {@link #HANDOFF_FROM} to {@link #HANDOFF_TO} of its length, hand over to pall. Copy {@code pallWallFade}. */
    static double handoff(double u) {
        return 1 - smooth(HANDOFF_FROM, HANDOFF_TO, u);
    }

    /**
     * Head density share without noise (billow mean), relative to ground density at wedge end: {@code s} — behind the dust leading
     * edge (blocks; negative ahead), {@code z} — above terrain (blocks), {@code len} — wedge length, {@code top} — its
     * end top. Vertically, pall profile ({@link PallField#profile}) up to the local wedge top; below map terrain, down to
     * half the top (map is coarser than the world). Copy {@code pallWallMean}.
     */
    static double mean(double s, double z, double len, double top) {
        double u = s / Math.max(len, 0.001);
        if (u <= 0) return 0;
        double h = top * top(u), k = shape(u) * handoff(u);
        if (z < 0) return k * smooth(-0.5 * top, -0.25 * top, z);
        return k * PallField.profile(z / Math.max(h, 0.001));
    }

    /**
     * Mean-head extinction (without billows) at a point, 1/block, as in {@code pallWallAt} and {@code pallWallMean}: {@code slot}
     * contains {@link #slot} parameters; {@code relX}, {@code relZ} — point relative to ground zero (blocks); {@code up} — above its terrain;
     * {@code wet} — water fraction below it (over water the head is spray: lower and behind the dust edge).
     */
    static double sigma(float[] slot, double relX, double relZ, double up, double wet) {
        if (slot[3] <= 0) return 0;
        double s = slot[0] - Math.hypot(relX, relZ), len = slot[1], top = slot[2];
        if (s < -1 || s > HANDOFF_TO * len + slot[5] || up > top || up < -0.5 * top) return 0;
        double back = slot[5] * wet, at = s - back;
        double k = density(slot, at, wet);
        return k * mean(at, up, len, top * (1 + (slot[6] - 1) * wet));
    }

    /** Head extinction multiplier {@code s} blocks behind the edge (including spray lag): dust from edge to wedge end; spray has its own. */
    private static double density(float[] slot, double s, double wet) {
        double u = Mth.clamp(s / Math.max(slot[1], 0.001), 0, 1);
        double dust = slot[3] * (slot[14] + (slot[15] - slot[14]) * u);
        return dust + (slot[7] - dust) * wet;
    }

    /**
     * Mean-head vertical optical depth above a point from {@code from} to {@code to} (blocks above local terrain; {@code to} may be
     * infinite): height integral of {@link #sigma}, using Simpson separately below and above terrain (a kink there), up to the
     * local wedge top. Head dust above and below the eye determines pall-ray reach ({@link NukePall}).
     */
    static double tau(float[] slot, double relX, double relZ, double from, double to, double wet) {
        if (slot[3] <= 0) return 0;
        double s = slot[0] - Math.hypot(relX, relZ), len = slot[1];
        double full = slot[2] * (1 + (slot[6] - 1) * wet), back = slot[5] * wet;
        double here = full * top((s - back) / Math.max(len, 0.001));
        return simpson(slot, relX, relZ, Math.max(from, -0.5 * full), Math.min(to, 0), wet)
                + simpson(slot, relX, relZ, Math.max(from, 0), Math.min(to, here), wet);
    }

    private static double simpson(float[] slot, double relX, double relZ, double a, double b, double wet) {
        if (!(b > a)) return 0;
        int n = 16;
        double h = (b - a) / n, sum = sigma(slot, relX, relZ, a, wet) + sigma(slot, relX, relZ, b, wet);
        for (int k = 1; k < n; k++) sum += (k % 2 == 1 ? 4 : 2) * sigma(slot, relX, relZ, a + k * h, wet);
        return sum * h / 3;
    }

    static double smooth(double e0, double e1, double x) {
        double f = Mth.clamp((x - e0) / (e1 - e0), 0, 1);
        return f * f * (3 - 2 * f);
    }
}
