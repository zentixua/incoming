package ua.zentix.incoming.client.nuclear;

import net.minecraft.util.Mth;
import ua.zentix.incoming.client.fx.smoke.Condensation;
import ua.zentix.incoming.nuclear.Detonation;
import ua.zentix.incoming.nuclear.model.BlastModel;
import ua.zentix.incoming.nuclear.model.DustModel;
import ua.zentix.incoming.nuclear.model.FireballModel;
import ua.zentix.incoming.nuclear.model.HobBlast;
import ua.zentix.incoming.nuclear.model.WaterModel;
import ua.zentix.incoming.util.Air;

/**
 * Head of the shock-wave dust layer (DESIGN-nuke §6): youngest, lowest and densest part of the same layer that later remains
 * as pall ({@link PallField}), governed by the same laws ({@link DustModel}). The front itself is invisible — a pressure jump in transparent
 * air; wind behind it along the ground ({@link Detonation#groundWind}) lofts ground dust and debris, or spray over water
 * ({@link WaterModel#spray}). The wave has no matter of its own.
 * <p>
 * The head is a wedge from the front back to where the positive phase ended (different at every distance, {@link HobBlast},
 * {@link #length}; shorter for the first ~0.6 s of a surface burst: the head and its pall transition do not enter the ground-level fireball,
 * {@link #underBall}): no dust yet at the leading edge, and zero layer height. Right behind the front the wind and the ground friction
 * velocity peak: the lofting burst throws most of the dust up there, fast ({@link DustModel#loftMass}, {@link DustModel#loftTop}); rearward
 * the wind decays, the dust slows, and by the phase end the layer stands at Kuhl's top ({@link #top}). So the head's front is tall and dense
 * (about twice the density of its end, {@link #shape}; saltation still develops over the first metres behind the front, {@link #develop}),
 * and the dense, steep front emerges from the lofting itself. Over hard ground (rock, roofs, roads, snow) only debris dust rises
 * ({@link #pavedShare}, the pall map's hard share): the foot follows the terrain instead of an even ring. Behind the wedge, dust remains as a pall layer (its table holds the same dust,
 * {@link PallField}): the head hands over across half a wedge length ({@link #handoff}). Where wind no longer lofts dust,
 * edge density vanishes and the head fades, separating from the front — dust remains in place (MET: "stopped moving along the ground at
 * 945 m").
 * <p>
 * The wedge is a volume in the pall pass ({@code PallPass}: {@code pallWall*} in {@code pall.glsl}, slot parameters — {@link #slot}).
 * Its billows ride the dust parcels ({@link #origin}): each place's dust is thrown up as the front arrives, the layer top over it grows
 * fast at first and then stands (the head's face, {@link #top}), the heated layer then rises slowly ({@link #heated}), and the wind behind
 * the front carries it outward far less than the front runs ({@link #carried}). Seen from the front every parcel goes up and back: the
 * wall curls over backward like the crest of a breaking wave seen from behind — a kinematic curl of the trajectories, not an eddy.
 * Model meters and seconds; world conversion through {@link Detonation#blocks}.
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
     * Air displacement behind the front over the local positive phase, fraction of U·t₊ (U — wind immediately behind the front): particle speed
     * follows Friedlander u(t) = U(1 − t/t₊)e^(−t/t₊), ∫₀^t₊ = U·t₊/e (Glasstone &amp; Dolan §3.57: decay shape; decay
     * coefficient 1 for weak distant waves; closer to ground zero decay is steeper and displacement smaller — upper estimate).
     */
    static final double CARRY = Math.exp(-1);
    /**
     * presentation: the lofting burst at the front runs this many times faster — dust is thrown up twice as fast and the layer stands at its
     * phase-end top and mass after half the phase ({@link #top}, {@link #shape}); where dust is lofted, how much and how high the layer ends
     * stay the law's. Within sources: the law gives a 15 kt layer 1.5 km out 12 m high 0.1 s after the front, this 22 m (GRABLE: 6–25 m in
     * the first tenths of a second, DESIGN-nuke §6); 3 would give 30 m, beyond them. On screen the 1 Mt head 3 km out reaches half its top
     * 28 m behind the front instead of 55 m (Kuhl's x^(5/6): 118 m).
     */
    static final double LOFT_GAIN = 2;
    /** Deepest negative phase of a nuclear blast wave, kPa: never more than about 4 psi below ambient (Glasstone &amp; Dolan 1977, §3.05). */
    static final double UNDER_MAX_KPA = BlastModel.kpa(4);
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
    /**
     * Per node: optical depth of all lofted material over hard ground ({@link DustModel.Surface#PAVED}: rock, roofs, roads, snow),
     * and the share of dust rising at once behind the front ({@link DustModel#prompt}; the rest develops over
     * {@link DustModel#SATURATION_M}).
     */
    private final double[] paved = new double[NODES], prompt = new double[NODES];
    /** Per node: the top the flash-heated ground lifts the layer toward ({@link DustModel.Column#buoyant}), m. */
    private final double[] buoyant = new double[NODES];
    /** Positive phase at the table end, model s: beyond the table, wedge end follows front travel over this phase. */
    private final double lastPositive;
    /** The negative phase condenses water: a Wilson cloud exists ({@link #condenses}). */
    private final boolean humid;

    public BlastWall(Detonation d) {
        this(d, condenses(d, humidity()));
    }

    BlastWall(Detonation d, boolean humid) {
        this.d = d;
        this.humid = humid;
        hob = Math.max(0, d.hobMetres());
        ys = Math.cbrt(d.yieldKt() / 15);
        tableM = Math.max(1, HobBlast.rangeFor(0.2, hob, d.yieldKt()));
        double positive = 0;
        for (int i = 0; i < NODES; i++) {
            double g = tableM * i / (NODES - 1);
            DustModel.Column c = DustModel.column(g, hob, d.yieldKt(), d.surface(), d.visibility(), DustModel.Surface.REFERENCE);
            lifted[i] = c.lifted();
            paved[i] = DustModel.column(g, hob, d.yieldKt(), d.surface(), d.visibility(), DustModel.Surface.PAVED).lifted();
            prompt[i] = DustModel.prompt(g, hob, d.yieldKt(), DustModel.Surface.REFERENCE);
            buoyant[i] = c.buoyant();
            positive = c.positive();
            phaseEnd[i] = arrival(g) + positive;
            if (i > 0) phaseEnd[i] = Math.max(phaseEnd[i], phaseEnd[i - 1]);
        }
        lastPositive = positive;
    }

    /**
     * Surface humidity of the world air at detonation ({@link Air#surfaceHumidity}: 70 % in clear weather, saturated in rain), the same air as
     * flight, smoke and sound; {@link Air#SURFACE_CLEAR} without a world.
     */
    static double humidity() {
        var level = net.minecraft.client.Minecraft.getInstance().level;
        return level == null ? Air.SURFACE_CLEAR : Air.of(level).surfaceHumidity();
    }

    /**
     * Whether the wave's negative phase condenses water in air of relative humidity {@code rh} — a Wilson cloud — by the same law as for
     * conventional blasts ({@link Condensation#water}: shock heating, isentropic expansion to the negative-phase minimum
     * of a Friedlander b = 1 wave, p/e², no deeper than {@link #UNDER_MAX_KPA}; saturation by Magnus, latent heat; Birk 2017): at some
     * ground range between the fireball and the 0.3 psi dust edge the expanded air holds condensed water. The 4 psi negative phase near the
     * fireball condenses from 51 % relative humidity at any yield (15 °C air, {@code CloudModel.T_AIR_K}): yes at the clear-weather 70 % and
     * in rain, not in dry desert air.
     */
    static boolean condenses(Detonation d, double rh) {
        double hob = Math.max(0, d.hobMetres());
        double from = Math.max(1, FireballModel.maxRadius(d.yieldKt(), d.surface())), to = Math.max(from * 1.01, HobBlast.rangeFor(0.3, hob, d.yieldKt()));
        for (int i = 0; i <= 64; i++) {
            double m = from * Math.pow(to / from, i / 64.0), kpa = d.groundOverpressureKpa(d.blocks(m));
            if (kpa <= 0) continue;
            double under = 1000 * Math.min(UNDER_MAX_KPA, kpa / Math.exp(2));
            if (Condensation.water(kpa, -under, rh) > 0) return true;
        }
        return false;
    }

    /** Layer table at {@code groundM} m from ground zero (linear between nodes; no dust beyond the table). */
    private double at(double[] table, double groundM) {
        double x = Math.max(0, groundM) / tableM * (NODES - 1);
        if (x >= NODES - 1) return 0;
        int i = (int) x;
        return Mth.lerp(x - i, table[i], table[i + 1]);
    }

    /** Node value at {@code groundM} m (linear between nodes; beyond the table — the last node). */
    private double clamped(double[] table, double groundM) {
        return at(table, Math.min(Math.max(0, groundM), tableM * (1 - 1e-9)));
    }

    /**
     * Dust over hard ground relative to soil at {@code groundM} m ({@link DustModel.Surface#PAVED} against
     * {@link DustModel.Surface#REFERENCE}): debris dust only, where buildings collapse; 1 where nothing is lofted.
     */
    double pavedShare(double groundM) {
        double soil = clamped(lifted, groundM);
        return soil > 0 ? Math.min(1, clamped(paved, groundM) / soil) : 1;
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
     * Wedge top at time t, m: layer top at wedge end — the dusty boundary layer's top at phase end there ({@link DustModel#waveTop} over
     * the local phase, Kuhl), reached by the lofting burst ({@link #top}) at the dust age there.
     */
    double height(double t) {
        double back = back(t), first = DustModel.waveTop(back, hob, d.yieldKt(), HobBlast.positivePhaseSeconds(back, hob, d.yieldKt()));
        // at least a millimeter, like wedge length: shader divisors (smoothstep over top) stay nondegenerate
        return Math.max(1e-3, first * DustModel.loftTop(LOFT_GAIN * backAge(t)));
    }

    /** Ground extinction at wave phase end where dust was lofted at {@code groundM}, with top {@code top} (1/m). */
    private double sigma(double groundM, double top) {
        return at(lifted, groundM) / (PallField.column(0) * Math.max(top, 1e-3));
    }

    /**
     * Head-dust parcel in its own coordinates — where it was lofted and at which share of the layer height it rides (copy
     * {@code pallWallOrigin}): point (rx, rz — ground distance from ground zero, z — above terrain) minus its displacement with the wind behind
     * the front ({@code carried} × {@link #carriedShare} at phase fraction u; air stands still at the ground and moves by the turbulent
     * boundary-layer 1/7 law above); height is the share of the layer top over the place, {@code local} (blocks; {@link #top} ×
     * {@link #heated}): the dusty layer grows as one body (Kuhl's self-similar layer), so a parcel keeps its share while the top over it rises.
     * Head-billow noise uses these coordinates, so billows ride the parcels — thrown up at the front, lagging behind it, rising ever slower —
     * instead of standing where the front left them or turning in place. Settling is left out: the mixture's mass-mean fall speed
     * ({@link DustModel}, bins) is about 0.3 m/s, a metre over the head's seconds — below a pixel at the distances it is watched from.
     */
    static double[] origin(double rx, double rz, double z, double u, double local, double carried) {
        double zr = z / Math.max(local, 0.001);
        double x = carried * carriedShare(u) * Math.pow(Mth.clamp(zr, 0, 1), 1.0 / 7);
        double len = Math.max(Math.hypot(rx, rz), 1e-3);
        return new double[] {rx - rx / len * x, zr, rz - rz / len * x};
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
        // (DustModel.loftMass), and its top is lower — layer at this dust age (height); same phase fraction at the edge: lofted material at the
        // front grows linearly with age
        double age = backAge(t);
        into[0] = age * sigma(edge, top);
        into[1] = DustModel.loftMass(LOFT_GAIN * age) * sigma(Math.max(0, edge - len), top);
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
     *     <li>[8..11] — phase fraction elapsed at wedge end ({@link #backAge}: wedge fraction u means phase fraction u × this);
     *     saltation e-folds over the wedge length (the grains' path at the wind behind the front over {@link DustModel#SATURATION_M}) and the share of dust
     *     rising at once ({@link #develop}); how fast the heated ground lifts the layer after the phase ({@link #heated}: lift);</li>
     *     <li>[12..15] — dust over hard ground relative to soil ({@link #pavedShare}) at leading edge and wedge end; ground dust
     *     extinction at wave phase end, at leading edge and wedge end — fractions of scale.</li>
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
            double age = backAge(t), back = Math.max(0, head - len);
            into[8] = (float) age;
            into[9] = (float) (age * HobBlast.positivePhaseSeconds(back, hob, d.yieldKt()) * u / DustModel.SATURATION_M);
            into[10] = (float) clamped(prompt, Math.max(0, head - 0.5 * len));
            into[12] = (float) pavedShare(head);
            into[13] = (float) pavedShare(back);
            // the heated layer: h² = h₁² + b²·(age − t₊)/RISE_S (DustModel.Column.height), here per phase fraction and end top
            double b = clamped(buoyant, back), tp = HobBlast.positivePhaseSeconds(back, hob, d.yieldKt());
            into[11] = (float) (b * b * tp / (DustModel.RISE_S * Math.max(top * top, 1e-6)));
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

    /** Lofting phase fraction at the wedge end, with the burst gain ({@link #LOFT_GAIN}): the wedge end's phase fraction {@code ph}, at least 10⁻³. */
    private static double loft(double ph) {
        return LOFT_GAIN * Math.max(ph, 1e-3);
    }

    /**
     * Wedge top at fraction u of its length behind the edge, fraction of end top (copy {@code pallWallTop}): the lofting burst
     * ({@link DustModel#loftTop}) at the dust age there — u of the wedge end's phase fraction {@code ph}; none ahead of the dust edge, full
     * behind the wedge.
     */
    static double top(double u, double ph) {
        if (u <= 0) return 0;
        double p = loft(ph);
        return DustModel.loftTop(Math.min(u, 1) * p) / DustModel.loftTop(p);
    }

    /**
     * Wedge fraction where the layer top reaches height share {@code zr} of the end top (inverse of {@link #top}; copy {@code pallWallFace}):
     * x·e^(1 − x) = y by two Newton steps from a guess (error below 10⁻³ of the phase).
     */
    static double face(double zr, double ph) {
        double p = loft(ph), y = Math.pow(Mth.clamp(zr, 0, 1) * DustModel.loftTop(p), 1 / DustModel.LOFT_EXP);
        double x = y < 0.7 ? y / Math.E * (1 + 0.6 * y) : 1 - Math.sqrt(2 * (1 - y));
        for (int i = 0; i < 2; i++) {
            double e = Math.exp(1 - x);
            x = Mth.clamp(x - (x * e - y) / Math.max((1 - x) * e, 1e-3), 0, 1);
        }
        return Math.min(x / p, 1);
    }

    /**
     * Layer top over dust of phase fraction {@code ph}, relative to the top at phase end (copy {@code pallWallHeated}): after the phase the
     * flash-heated ground lifts it, h² = h₁² + b²·x over {@link DustModel#RISE_S} ({@link DustModel.Column#height}), here √(1 + lift·(ph − 1))
     * with lift = b²·t₊ / (RISE_S·h₁²) ({@link #slot}; the head hands over to the pall within half a phase, long before RISE_S); before, 1.
     */
    static double heated(double ph, double lift) {
        return Math.sqrt(1 + lift * Math.max(ph - 1, 0));
    }

    /**
     * Ground density at wedge fraction u relative to its end (copy {@code pallWallShape}): mass lofted by then ({@link DustModel#loftMass},
     * with the burst gain) in a layer of top {@link #top}. The entrainment law lofts most of the mass right behind the front, so the head is
     * about twice as dense there as at its end; behind the wedge, 1.
     */
    static double shape(double u, double ph) {
        if (u <= 0) return 0;
        double x = Math.min(u, 1), p = loft(ph);
        return DustModel.loftMass(x * p) / DustModel.loftMass(p) / top(x, ph);
    }

    /** Head share behind the wedge: from {@link #HANDOFF_FROM} to {@link #HANDOFF_TO} of its length, hand over to pall. Copy {@code pallWallFade}. */
    static double handoff(double u) {
        return 1 - smooth(HANDOFF_FROM, HANDOFF_TO, u);
    }

    /**
     * Head density share without noise (billow mean), relative to ground density at wedge end: {@code s} — behind the dust leading
     * edge (blocks; negative ahead), {@code z} — above terrain (blocks), {@code len} — wedge length, {@code top} — its end top, {@code ph} —
     * phase fraction at the wedge end, {@code lift} — the heated layer's rise ({@link #heated}). Vertically, pall profile
     * ({@link PallField#profile}) up to the local top; behind the wedge the heated layer spreads the same dust higher; below map terrain, down
     * to half the local top (map is coarser than the world; with the full top, a dense curb with a flat top stood on the leading edge wherever
     * real ground lies below the map). Copy {@code pallWallMean}.
     */
    static double mean(double s, double z, double len, double top, double ph, double lift) {
        double u = s / Math.max(len, 0.001);
        if (u <= 0) return 0;
        double heat = heated(u * ph, lift), h = Math.max(top * top(u, ph) * heat, 0.001);
        double k = shape(u, ph) * handoff(u) / heat;
        if (z < 0) return k * smooth(-0.5 * h, -0.25 * h, z);
        return k * PallField.profile(z / h);
    }

    /** Local head top (blocks) {@code s} blocks behind the dust edge over water share {@code wet} (spray: lower, behind), as {@link #mean}. */
    static double local(float[] slot, double s, double wet) {
        double len = slot[1], full = slot[2] * (1 + (slot[6] - 1) * wet), u = (s - slot[5] * wet) / Math.max(len, 0.001);
        return full * top(u, slot[8]) * heated(u * slot[8], slot[11]);
    }

    /** Highest head top (blocks): the heated layer at the end of the hand-over to pall. Copy {@code pallWallCeiling}. */
    static double ceiling(float[] slot) {
        return slot[2] * heated(HANDOFF_TO * slot[8], slot[11]);
    }

    /**
     * Saltation development at wedge fraction u behind the leading edge, 0..1: share {@code prompt} rises at once (suspension, debris),
     * the rest as 1 − e^(−path/{@link DustModel#SATURATION_M}) (the grains' path at the wind behind the front), {@code rate} — e-folds over
     * the wedge length. Total lofted mass stays the steady-state law ({@link DustModel#column}); only the foot of the head is younger and
     * thinner over its first metres instead of standing at full density on an exact circle. Copy {@code pallWallDevelop}.
     */
    static double develop(double u, double rate, double prompt) {
        return 1 - (1 - prompt) * Math.exp(-Math.max(u, 0) * rate);
    }

    /**
     * Mean-head extinction (without billows) at a point, 1/block, as in {@code pallWallAt} and {@code pallWallMean}: {@code slot}
     * contains {@link #slot} parameters; {@code relX}, {@code relZ} — point relative to ground zero (blocks); {@code up} — above its terrain;
     * {@code wet} — water fraction below it (over water the head is spray: lower and behind the dust edge).
     */
    static double sigma(float[] slot, double relX, double relZ, double up, double wet) {
        return sigma(slot, relX, relZ, up, wet, 0);
    }

    /** The same over ground with hard share {@code hard} ({@link PallGround#hard}). */
    static double sigma(float[] slot, double relX, double relZ, double up, double wet, double hard) {
        if (slot[3] <= 0) return 0;
        double s = slot[0] - Math.hypot(relX, relZ), len = slot[1], top = slot[2];
        if (s < -1 || s > HANDOFF_TO * len + slot[5] || up > ceiling(slot) || up < -0.5 * top) return 0;
        double back = slot[5] * wet, at = s - back;
        double k = density(slot, at, wet, hard);
        return k * mean(at, up, len, top * (1 + (slot[6] - 1) * wet), slot[8], slot[11]);
    }

    /**
     * Head extinction multiplier {@code s} blocks behind the edge (including spray lag): dust from edge to wedge end, developing
     * ({@link #develop}) and thinner over hard ground ({@code hard} share, {@link #pavedShare}); spray has its own. Copy
     * {@code pallWallDensity}.
     */
    static double density(float[] slot, double s, double wet, double hard) {
        double v = s / Math.max(slot[1], 0.001), u = Mth.clamp(v, 0, 1);
        double soil = 1 + hard * (paved(slot, s) - 1);
        double dust = slot[3] * (slot[14] + (slot[15] - slot[14]) * u) * develop(v, slot[9], slot[10]) * soil;
        return dust + (slot[7] - dust) * wet;
    }

    /**
     * Debris dust over hard ground relative to soil at {@code s} blocks behind the dust edge: from the leading edge's ratio to the wedge end's
     * by wedge share ({@link #pavedShare} at both, slot 12 and 13) — as {@code pallWallDensity} and the head material in {@code fx_pall}.
     */
    static double paved(float[] slot, double s) {
        double u = Mth.clamp(s / Math.max(slot[1], 0.001), 0, 1);
        return slot[12] + (slot[13] - slot[12]) * u;
    }

    /**
     * Mean-head vertical optical depth above a point from {@code from} to {@code to} (blocks above local terrain; {@code to} may be
     * infinite): height integral of {@link #sigma}, using Simpson separately below and above terrain (a kink there), up to the
     * local wedge top. Head dust above and below the eye determines pall-ray reach ({@link NukePall}).
     */
    static double tau(float[] slot, double relX, double relZ, double from, double to, double wet) {
        return tau(slot, relX, relZ, from, to, wet, 0);
    }

    /** The same over ground with hard share {@code hard}. */
    static double tau(float[] slot, double relX, double relZ, double from, double to, double wet, double hard) {
        if (slot[3] <= 0) return 0;
        double s = slot[0] - Math.hypot(relX, relZ);
        double full = slot[2] * (1 + (slot[6] - 1) * wet), here = local(slot, s, wet);
        return simpson(slot, relX, relZ, Math.max(from, -0.5 * full), Math.min(to, 0), wet, hard)
                + simpson(slot, relX, relZ, Math.max(from, 0), Math.min(to, here), wet, hard);
    }

    private static double simpson(float[] slot, double relX, double relZ, double a, double b, double wet, double hard) {
        if (!(b > a)) return 0;
        int n = 16;
        double h = (b - a) / n, sum = sigma(slot, relX, relZ, a, wet, hard) + sigma(slot, relX, relZ, b, wet, hard);
        for (int k = 1; k < n; k++) sum += (k % 2 == 1 ? 4 : 2) * sigma(slot, relX, relZ, a + k * h, wet, hard);
        return sum * h / 3;
    }

    static double smooth(double e0, double e1, double x) {
        double f = Mth.clamp((x - e0) / (e1 - e0), 0, 1);
        return f * f * (3 - 2 * f);
    }
}
