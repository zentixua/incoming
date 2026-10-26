package ua.zentix.incoming.nuclear.model;

import java.util.Arrays;

/**
 * A standing person in blast wind — without Minecraft (unit-tested). The body is an inverted pendulum at the ankles,
 * maintaining balance: intrinsic muscle stiffness and damping plus delayed tilt and tilt-rate feedback, delivered as
 * torque through muscle activation — a second-order element (independent-channel model: Peterka 2002, J Neurophysiol 88;
 * with activation — van der Kooij &amp; Peterka 2011; all values — Pasma et al. 2017, Front. Comput. Neurosci. 11:99,
 * table 1 and (A4); activation damping is relative, term 2βω₀s: (A4) prints sβω₀², dimensionally wrong for dimensionless β).
 * The model's positive torque feedback (K_F, τ_F 17.4 s) is omitted: over seconds of sway it changes tilt by less than
 * one percent. Post-front wind pushes: drag q·Cd·A at center-of-mass height (standing Cd — 1.0–1.3, Hoerner 1965;
 * frontal area about 0.6 m²); dynamic pressure is wind squared, and weak-blast wind follows pressure (u = p/ρc):
 * Friedlander phase, with weak reversal during rarefaction.
 * <p>
 * The front itself hits first (diffraction loading, Glasstone &amp; Dolan 1977, ch. IV): post-front pressure rises in less
 * than a millisecond, reflected on the chest (G&amp;D, eq. 3.56.1), falling to stagnation pressure over 3S/U (S — half body
 * width, U — front speed); it reaches the back after L/U (L — body depth), rising there over another 4S/U. While the wave
 * flows around the body, the pressure difference pushes it for ~2 ms versus a second of wind: an impact giving immediate
 * tilt velocity ({@link #jolt}). At 1 psi — 11 N·s (8°/s), at 3.5 psi (15 kt at 2 km) — 38 N·s (27°/s), versus 20–40 N·s
 * of wind over the full phase. Body dimensions — ANSUR II (Gordon et al. 2014): chest depth ~0.25 m, shoulder width ~0.4 m;
 * the same loading applies to buildings (G&amp;D rectangular idealization); for the body it is an estimate.
 * <p>
 * <b>Head on neck</b> ({@link #motion}) — what the camera sees. The neck supports the head on the shoulders, an
 * underdamped second-order element: in a seated person under fore-and-aft oscillation, head pitch peaks around 2 Hz
 * (Paddan &amp; Griffin 1988, J Biomech 21(3):199–206; head fundamental ~2 Hz — Gresty &amp; Halmagyi 1979,
 * J Neurol Neurosurg Psychiatry 42); transfer element is passive and underdamped (Tangorra, Jones &amp; Hunter 2003,
 * Ann Biomed Eng 31: in the horizontal plane); ⚠ relative damping {@link #NECK_DAMPING} is an estimate (no pitch value
 * found in those papers). The element is modeled as measured: shoulder acceleration to head rotation, including all
 * neck reflexes (head gravity during body tilt is included: vestibular and cervical reflexes hold the head over the
 * shoulders, so there is no separate overturning term). The head is pushed by (a) shoulders: the body gets tilt velocity
 * at once, shoulders at neck height move downwind, and the head stays by inertia — tilts back on the neck, then catches
 * up and oscillates with damping (as in whiplash, but much weaker); (b) its own loading — the same diffraction impact and
 * wind on head area ({@link #HEAD_AREA}): pushes forward against the lag. At 1 psi shoulders move at ~0.2 m/s, head loading
 * adds ~0.1 m/s: the head lags and sways a few degrees. ⚠ The vestibulocollic reflex (VCR: holds a living person's head in
 * space below ~2–8 Hz, Happee et al. 2017, J Biomech 58) is not modeled separately: the on-screen head sways more than a
 * person's — the sway the eye recognizes as impact (presentation: body and timing remain the same).
 * <p>
 * Ground underfoot does not tilt the body. Airslap arrives with the blast, driving soil sharply down, then partly back
 * (Glasstone &amp; Dolan 1977, §6.12–6.16); ground velocity is p/ρc (plane wave in soil, ρc — estimate): at 1 psi,
 * 0.2–0.9 cm/s and 1–4 mm displacement; at ~6 psi, 1.5–5 cm/s and up to 1.6 cm. The body feels the jolt but drops with the
 * ground and everything around it; surface slope under the front is ground velocity divided by its ground-trace speed,
 * thousandths of a degree. Ground motion ahead of the wave cannot be computed (§6.84: no simple method); it is absent.
 * <p>
 * A person cannot hold tilt past the stability boundary (center of mass over foot edge: forward 8°, backward 4.5°,
 * sideways 8° — McCollum & Leen 1989, J Motor Behav 21): steps or falls. Facing the wave, front impact plus wind reaches
 * this at ~0.77 psi for 15 kt (1.3 s phase) and ~0.88 psi for 2 kt (0.6 s), back-on at ~1.06 and ~1.35 psi: a larger burst
 * has longer wind. The balanced pendulum does not describe what follows (linear feedback would pull the “fallen” body
 * back across vertical): a step keeps the center of mass above the new support edge — tilt stays at the boundary while
 * wind overcomes balance, and the body straightens when balance wins again (deviation: stepping and falling are not
 * modeled). Step threshold is by tilt, using static boundaries; under wind, center of pressure reaches the foot edge
 * earlier: by foot torque (mgh·sin boundary), wind alone would trigger a step from ~0.6 psi facing the wave for 15 kt and
 * ~0.8 for 2 kt, back-on from ~0.8 and ~1.1 psi; this needs a two-segment stepping model — a deviation. After the phase,
 * reverse rarefaction wind tilts the body back toward the burst (Friedlander — up to 13.5 % of front pressure, dynamic
 * pressure 1.8 %): at 2–3 psi by 1–2°; reaches the boundary back-on to the wave (backward 4.5°) from ~4.6 psi, facing it
 * (forward 8°) from 6–8 psi.
 */
public final class BodySway {
    /** Body mass, kg. */
    static final double MASS = 75;
    /** Center-of-mass height above ankles, m: wind force acts there too (frontal body center). */
    static final double HEIGHT = 0.83;
    /** Body moment of inertia about ankles, kg·m². */
    static final double INERTIA = 66;
    /** Intrinsic muscle stiffness and damping: N·m/rad and N·m·s/rad. */
    static final double MUSCLE_K = 40.5, MUSCLE_B = 68.8;
    /** Balance feedback: tilt, N·m/rad; tilt rate, N·m·s/rad; and delay, s. */
    static final double GAIN_P = 943.9, GAIN_D = 313.5, DELAY = 0.097;
    /** Muscle activation: natural frequency, rad/s, and damping ratio. */
    static final double ACTIVATION_W = 16.8, ACTIVATION_DAMPING = 0.99;
    /** Frontal body area, m². */
    static final double AREA = 0.6;
    /** Standing body drag area, m²: area × Cd 1.1. */
    static final double DRAG_AREA = AREA * 1.1;
    /** Body depth along the wave and half-width, m: front path to the back and chest unloading path (ANSUR II). */
    static final double DEPTH = 0.25, HALF_WIDTH = 0.2;
    static final double G = 9.81;
    /** Tilt samples per second. */
    public static final int RATE = 100;
    /** Integration step, s. */
    static final double STEP = 0.001;
    /** Stability boundaries, rad: forward, backward, sideways. */
    public static final double FORWARD = Math.toRadians(8), BACK = Math.toRadians(4.5), SIDE = Math.toRadians(8);
    /** Below this tilt, rad (0.01°), the body is still. */
    static final double STILL = Math.toRadians(0.01);
    /** Do not integrate beyond this many seconds. */
    static final double LONGEST = 20;
    /** Head mass, kg. */
    static final double HEAD_MASS = 4.5;
    /**
     * Neck height (cervical landmark C7) above ankles, m: ANSUR II — cervical height ~1.5 m, ankles ~0.07 m.
     * Shoulders move at body tilt rate × this height.
     */
    static final double NECK_HEIGHT = 1.43;
    /** Head center of mass above the neck, m (⚠ estimate: C7 to head center near the ear canal). */
    static final double HEAD_LEVER = 0.2;
    /** Head pitch moment of inertia about its center, kg·m² (⚠ order of magnitude: ~0.02, Yoganandan et al. 2009, J Biomech 42). */
    static final double HEAD_INERTIA = 0.022;
    /** Head moment of inertia about the neck, kg·m². */
    static final double NECK_INERTIA = HEAD_INERTIA + HEAD_MASS * HEAD_LEVER * HEAD_LEVER;
    /** Natural head-on-neck frequency, Hz. */
    static final double NECK_HZ = 2.0;
    /** Head-on-neck damping ratio (⚠ estimate). */
    static final double NECK_DAMPING = 0.3;
    /**
     * Neck pitch range, rad: forward ~50°, backward ~60–70° in young adults (Youdas et al. 1992, Phys Ther 72) — use the smaller
     * value. The linear element is invalid there: head-on-neck tilt approaches it smoothly (R·th(φ/R), ⚠ shape is an estimate);
     * this occurs only where the blast already knocks people down.
     */
    static final double NECK_RANGE = Math.toRadians(50);
    /** Frontal head area, m²; head length along the wave and half-width, m (ANSUR II: ~0.2 and ~0.155 m). */
    static final double HEAD_AREA = 0.03, HEAD_DEPTH = 0.2, HEAD_HALF_WIDTH = 0.077;
    /** Head drag area, m²: area × Cd 0.5 (sphere, Hoerner 1965 — estimate for a head). */
    static final double HEAD_DRAG_AREA = HEAD_AREA * 0.5;
    /**
     * How far the head lags behind shoulders: body tilt rate ω gives head-on-neck velocity −(1 + m·l·h/I)·ω
     * (shoulders move at ω·h, head on lever l stays; its own inertia also prevents rotating with the body).
     */
    static final double NECK_LAG = 1 + HEAD_MASS * HEAD_LEVER * NECK_HEIGHT / NECK_INERTIA;

    /**
     * Body tilt and head-on-neck tilt, rad, {@link #RATE} samples per second from front arrival, equal lengths.
     * Camera is the head: body + neck. Empty — neither body nor head moved by even {@link #STILL}.
     */
    public record Motion(double[] body, double[] neck) {
        static final Motion NONE = new Motion(new double[0], new double[0]);

        public boolean still() {
            return body.length < 2;
        }
    }

    private BodySway() {}

    /**
     * Downwind body tilt, rad (positive downwind), {@link #RATE} samples per second from front arrival until wind subsides
     * and body straightens. Empty — wind did not tilt the body by even {@link #STILL}. Tilt does not exceed the stability
     * boundary: holds there until balance overcomes wind.
     *
     * @param pPa   front pressure at the body (incident), Pa
     * @param qPa   post-front dynamic pressure along the ground, Pa
     * @param open  share open toward the burst (wall, house, hill between body and burst — wind shadow): impact and wind
     *              scale by it (estimate: the front bends around the obstacle but no longer pushes along the ground)
     * @param tPlus positive phase, s
     * @param edge  downwind stability boundary, rad
     * @param back  upwind boundary (body returns there and reverse rarefaction wind pushes it there), rad
     */
    public static double[] tilt(double pPa, double qPa, double open, double tPlus, double edge, double back) {
        return tilt(pPa, qPa, open, tPlus, tPlus, edge, back);
    }

    /**
     * As {@link #tilt(double, double, double, double, double, double)}, but front impact decays over its own phase
     * {@code hitPhase}, s ({@link #jolt(double, double, double)}): a nuclear blast's in-game wind phase is stretched by
     * {@code effects_scale}, while flow around the body takes real milliseconds.
     */
    public static double[] tilt(double pPa, double qPa, double open, double tPlus, double hitPhase, double edge, double back) {
        double[] body = motion(pPa, qPa, open, tPlus, hitPhase, edge, back).body;
        return peak(body, 0) < STILL ? new double[0] : body;
    }

    /**
     * Body and head on neck under blast — as {@link #tilt(double, double, double, double, double, double, double)}, plus head
     * ({@link Motion#neck}): pushed by shoulders (body tilt acceleration at neck height; not the boundary step stop: no step
     * model, and an instantaneous stop would jerk the head tens of degrees) and its own loading (diffraction impact and
     * wind on the head). Samples continue until both body and head settle.
     */
    public static Motion motion(double pPa, double qPa, double open, double tPlus, double hitPhase, double edge, double back) {
        if (!(qPa > 0) || !(open > 0) || !(tPlus > 0)) return Motion.NONE;
        int steps = (int) Math.ceil(LONGEST / STEP), lag = (int) Math.round(DELAY / STEP), every = (int) Math.round(1.0 / (RATE * STEP));
        // tilt and tilt rate one delay ago (ring buffer; body still before blast — zeros)
        double[] thBack = new double[lag], omBack = new double[lag];
        double[] out = new double[steps / every + 2], neck = new double[steps / every + 2];
        int n = 1;
        // front impact is shorter than integration step and balance delay: immediate tilt velocity
        double mgh = MASS * G * HEIGHT, peak = 0, th = 0, om = open * jolt(pPa, qPa, hitPhase) * HEIGHT / INERTIA, torque = 0, torqueRate = 0;
        double w2 = ACTIVATION_W * ACTIVATION_W, damp = 2 * ACTIVATION_DAMPING * ACTIVATION_W;
        // head on neck: shoulders move at once — head lags; its own impact pushes it downwind
        double nw = 2 * Math.PI * NECK_HZ, nw2 = nw * nw, ndamp = 2 * NECK_DAMPING * nw, phi = 0, neckPeak = 0;
        double phiRate = -NECK_LAG * om + open * jolt(pPa, qPa, hitPhase, HEAD_AREA, HEAD_DEPTH, HEAD_HALF_WIDTH) * HEAD_LEVER / NECK_INERTIA;
        for (int i = 0; i < steps; i++) {
            double t = i * STEP;
            // wind follows pressure; dynamic pressure is its signed square; step average: a conventional blast phase spans a few steps
            double share = windShare(t / tPlus, (t + STEP) / tPlus), push = open * qPa * share * DRAG_AREA * HEIGHT;
            double omBefore = om;
            int k = i % lag;
            double command = GAIN_P * thBack[k] + GAIN_D * omBack[k];
            thBack[k] = th;
            omBack[k] = om;
            // balance command becomes torque through muscle activation
            torqueRate += (w2 * (command - torque) - damp * torqueRate) * STEP;
            torque += torqueRate * STEP;
            double a = (mgh * Math.sin(th) - torque - MUSCLE_K * th - MUSCLE_B * om + push) / INERTIA;
            om += a * STEP;
            th += om * STEP;
            // shoulders before stepping: do not pass the boundary step stop to the head — stepping is not instantaneous and no step model exists (⚠)
            double omFree = om;
            if (th > edge || th < -back) {
                // beyond the boundary a person steps: center of mass stays over the new support edge, and balance starts from it —
                // body stays at the edge (delay remembers the edge with no velocity; muscles hold it)
                th = th > 0 ? edge : -back;
                om = 0;
                Arrays.fill(thBack, th);
                Arrays.fill(omBack, 0);
                torque = GAIN_P * th;
                torqueRate = 0;
            }
            // neck: shoulder acceleration over the step and wind on the head
            double headPush = open * qPa * share * HEAD_DRAG_AREA * HEAD_LEVER / NECK_INERTIA;
            phiRate += (-nw2 * phi - ndamp * phiRate + headPush) * STEP - NECK_LAG * (omFree - omBefore);
            phi += phiRate * STEP;
            peak = Math.max(peak, Math.abs(th));
            neckPeak = Math.max(neckPeak, Math.abs(phi));
            boolean sample = (i + 1) % every == 0;
            if (sample) {
                out[n] = th;
                neck[n++] = NECK_RANGE * Math.tanh(phi / NECK_RANGE);
            }
            // wind subsided (rarefaction after three phases — under 1 % dynamic pressure), body still and head on neck settled
            if (t > 3 * tPlus && Math.abs(th) < STILL && Math.abs(om) < STILL && Math.abs(phi) < STILL && Math.abs(phiRate) < nw * STILL) {
                if (!sample) {
                    out[n] = th;
                    neck[n++] = NECK_RANGE * Math.tanh(phi / NECK_RANGE);
                }
                break;
            }
        }
        if (peak < STILL && neckPeak < STILL) return Motion.NONE;
        return new Motion(Arrays.copyOf(out, n), Arrays.copyOf(neck, n));
    }

    /**
     * Mean f·|f| (signed dynamic pressure for Friedlander phase f = (1 − x)·e^(−x)) over phase fraction {@code x0} to {@code x1}:
     * ∫(1 − x)²e^(−2x)dx = −e^(−2x)·((1 − x)²/2 − (1 − x)/2 + 1/4), reversed sign beyond x = 1.
     */
    static double windShare(double x0, double x1) {
        return (windImpulse(x1) - windImpulse(x0)) / (x1 - x0);
    }

    /** ∫₀ˣ f·|f| dx. */
    private static double windImpulse(double x) {
        double one = antiderivative(1) - antiderivative(0);
        return x <= 1 ? antiderivative(x) - antiderivative(0) : 2 * one - (antiderivative(x) - antiderivative(0));
    }

    private static double antiderivative(double x) {
        double u = 1 - x;
        return -Math.exp(-2 * x) * (u * u / 2 - u / 2 + 0.25);
    }

    /**
     * Front impact for a long blast (phase much longer than body diffraction time, nuclear burst):
     * {@link #jolt(double, double, double)} as t₊ → ∞.
     */
    public static double jolt(double pPa, double qPa) {
        return jolt(pPa, qPa, Double.POSITIVE_INFINITY);
    }

    /**
     * Front impact on the body along the ground, N·s: impulse of the chest–back pressure difference while the wave flows
     * around it, beyond wind ({@link #tilt} counts dynamic pressure from front arrival). Chest: reflected p_r falls to p + q
     * over 3S/U — above wind, (p_r − p − q)·3S/2U; back: no pressure for L/U, rising to p over 4S/U — p·(L + 2S)/U.
     * The seconds-long pressure phase does not decay over these milliseconds. Under an airburst the front hits the ground
     * obliquely: ground-aligned share cos α; {@code qPa} already contains cos²α (ground-aligned dynamic pressure versus
     * Rankine–Hugoniot dynamic pressure at this pressure). A conventional blast's phase lasts milliseconds, comparable to
     * flow around the body: pressure decays by Friedlander during them, and both differences use its share at each instant
     * (less than 0.5 % for a seconds-long nuclear phase).
     *
     * @param pPa   front pressure at the body (incident), Pa
     * @param qPa   post-front dynamic pressure along the ground, Pa
     * @param tPlus positive phase, s
     */
    public static double jolt(double pPa, double qPa, double tPlus) {
        return jolt(pPa, qPa, tPlus, AREA, DEPTH, HALF_WIDTH);
    }

    /**
     * Front impact on a body (or part) with frontal {@code area}, m², along-wave {@code depth} and {@code halfWidth}, m —
     * the {@link #jolt(double, double, double)} law.
     */
    static double jolt(double pPa, double qPa, double tPlus, double area, double depth, double halfWidth) {
        if (!(pPa > 0) || !(qPa > 0) || !(tPlus > 0)) return 0;
        double kpa = pPa / 1000, full = 1000 * BlastModel.dynamicPressureKpa(kpa), u = BlastModel.frontSpeed(kpa);
        double along = Math.sqrt(Math.min(1, qPa / full)), reflected = 1000 * BlastInjury.reflectedKpa(kpa);
        double clear = 3 * halfWidth / u, behind = depth / u, rise = 4 * halfWidth / u;
        // chest: excess reflected pressure falls to zero over 3S/U; back: no pressure for L/U, then rises to p over 4S/U
        double front = Math.max(0, reflected - pPa - full) * weighted(clear, 0, tPlus, 1), rear = pPa * (weighted(behind, 0, tPlus, 0) + weighted(rise, behind, tPlus, 1));
        return area * along * (front + rear);
    }

    /**
     * ∫ Friedlander pressure share over a segment of length {@code span} starting at {@code from}, weighted to fall toward
     * the segment end ({@code fall} = 1: 1 − s/span) or stay uniform (0), s.
     */
    private static double weighted(double span, double from, double tPlus, double fall) {
        int n = 64;
        double sum = 0;
        for (int i = 0; i < n; i++) {
            double s = (i + 0.5) / n * span, x = (from + s) / tPlus;
            sum += (1 - fall * s / span) * (1 - x) * Math.exp(-x);
        }
        return sum * span / n;
    }

    /** Maximum tilt, rad, from sample {@code from} onward. */
    public static double peak(double[] tilt, int from) {
        double m = 0;
        for (int i = Math.max(0, from); i < tilt.length; i++) m = Math.max(m, Math.abs(tilt[i]));
        return m;
    }
}
