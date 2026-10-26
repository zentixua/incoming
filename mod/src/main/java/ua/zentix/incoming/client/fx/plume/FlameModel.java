package ua.zentix.incoming.client.fx.plume;

import org.jetbrains.annotations.Nullable;
import ua.zentix.incoming.client.far.Sight;
import ua.zentix.incoming.nuclear.model.BlackBody;
import ua.zentix.incoming.util.StandardAtmosphere;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Engine plume from spec data ({@link Motor}) — one law for all engines.
 * <ol>
 * <li><b>Jet</b>: chamber gas (stagnation temperature T₀, pressure p₀ — isentropic from exit) expands to ambient
 * pressure at altitude (standard atmosphere): fully expanded jet Mach M_j, temperature T_j and radius r_j
 * (isentropic, no losses). Underexpanded jet beyond exit is wider than the nozzle; overexpanded — narrower.</li>
 * <li><b>Core</b> — supersonic centre where gas has not mixed with air: L_c = 3.45·d_j·(1 + 0.38·M_j)²
 * (Eldred, NASA SP-8072, 1971, fully expanded jet; formula and analysis — James et al., Trans. JSASS 14,
 * 2016, Po_2_17: at M = 3 it is 15.8 d; Varnier, AIAA J. 39(10), 2001, measures half as long — (est.) factor-of-2 accuracy).</li>
 * <li><b>Afterburning</b>: solid propellant products are fuel-rich (CO, H₂ — {@link Motor#monoxide}, {@link Motor#hydrogen}) and
 * burn in entrained air. Beyond the core the axial product fraction decays like a round jet, ∝ 1/x (est.) (velocity and
 * admixture decay this way in the far jet); flame ends where the axial mixture is stoichiometric: x_f = L_c/f_st
 * (Burke–Schumann: jet flame is the stoichiometric mixture surface), f_st = 1/(1 + air per kg of products: CO —
 * 2.46 kg, H₂ — 34.5 kg). Flame temperature is adiabatic at f_st: products/air mixture plus heat of
 * combustion (CO 10.1 MJ/kg, H₂ 120 MJ/kg) divided by heat capacity {@link #CP_FLAME} (est.) (no dissociation or radiation — upper
 * estimate, real temperature 10–20 % lower).</li>
 * <li><b>Tail</b>: beyond the flame, gas cools by the same ∝ 1/x law and remains visible while above the Draper point (798 K —
 * incandescent matter becomes visible in darkness).</li>
 * <li><b>Light</b>: gas emits almost no visible light — condensed particles (Al₂O₃, soot) emit it: emissivity
 * ε = 1 − e^(−τ), τ = σ_m·(hot-particle absorption)·c·2r_j, c — their jet density. Luminance — ε × black body
 * ({@link BlackBody}) at gas temperature; colour — black-body chromaticity. Beyond core, particles dilute: τ ∝ 1/x (same cross-section
 * mass, path grows as x, density falls as x²). Sheath luminance (flame and tail) sampled along the axis
 * ({@link Flame#sheath}: drawn by nearby jet); part luminance is side-area mean (distant and world light).</li>
 * <li><b>Diamonds</b> — shocks when exit pressure differs from ambient (both underexpansion and
 * overexpansion); visible beyond a ratio of {@link #DIAMONDS} (est.) (weak shocks do not heat gas enough to
 * glow).</li>
 * </ol>
 * No plume ({@code null}) if exit gas is below the Draper point or its light is invisible to the eye at night (piston,
 * turbofan: almost no particles, no exhaust fuel). Width — mixing layer grows inward from the nozzle lip to the axis by the core
 * end and equally outward (est.) (real layer grows outward more).
 */
public final class FlameModel {
    /** Draper point, K. */
    public static final double DRAPER = 798;
    /** Exit/ambient pressure ratio (or inverse) where diamonds become visible. */
    public static final double DIAMONDS = 1.3;
    /** Air per kilogram of CO and H₂ for complete combustion, kg (oxygen 0.571 and 8 kg/kg, air is 23.2 % oxygen by mass). */
    static final double AIR_CO = 2.46, AIR_H2 = 34.5;
    /** CO and H₂ lower heating values, J/kg. */
    static final double HEAT_CO = 10.1e6, HEAT_H2 = 120e6;
    /** Hot products/air mixture heat capacity, J/(kg·K) (est.) (1.2–1.4 kJ/(kg·K) at 1 500–2 500 K). */
    static final double CP_FLAME = 1_300;
    /** Eldred core length coefficient. */
    static final double ELDRED = 3.45, ELDRED_MACH = 0.38;
    /**
     * Light the eye sees on the darkest screen: Koschmieder threshold with nighttime thunderstorm adaptation (skylight 0.12,
     * {@code Sight.adapted}).
     */
    static final double DIMMEST = Sight.THRESHOLD * Math.pow(0.12, Sight.ADAPT);
    /** Altitude cache steps, m: pressure changes by 0.6 % over 50 m. */
    static final double STEP = 50;

    private static final Map<Long, Flame> CACHE = new ConcurrentHashMap<>();
    private static final Map<Motor, Integer> IDS = new ConcurrentHashMap<>();
    private static final AtomicInteger NEXT = new AtomicInteger();
    private static final Flame NONE = new Flame(0, 0, 0, 0, false, 0, 0, 0, 0, 0, 0, 0, 0, 0, Flame.Sheath.NONE, 0, 0, 0);

    private FlameModel() {}

    /** Engine plume at altitude metres above sea level ({@link #STEP} steps); null — invisible. */
    @Nullable
    public static Flame at(Motor motor, double altitude) {
        long step = Math.round(altitude / STEP);
        long key = (long) IDS.computeIfAbsent(motor, m -> NEXT.getAndIncrement()) << 32 | (step & 0xFFFFFFFFL);
        Flame f = CACHE.computeIfAbsent(key, k -> {
            Flame made = of(motor, step * STEP);
            return made == null ? NONE : made;
        });
        return f == NONE ? null : f;
    }

    /** Cached plateau-flow fraction step ({@link #at(Motor, double, double)}), logarithmic: neighbouring steps differ by 2 %. */
    static final double SHARE_STEP = -Math.log(0.98);

    /**
     * Engine plume at fraction share of plateau flow — solid rocket motor thrust rise and decay ({@code strike.ThrustCurve}),
     * at altitude. While nozzle is choked (chamber pressure exceeds ambient by ((γ + 1)/2)^(γ/(γ − 1))), same
     * law for a motor with this flow ({@link Motor#throttled}). Below that nozzle is unchoked: the law's fully expanded jet
     * is subsonic, velocity tends to zero at pressure ratio 1 and radius to infinity (light would rise toward the end of decay).
     * Therefore use the plume at the critical ratio, with light decreasing with flow: jet particles follow flow; the decaying jet
     * is optically thin (ε ≈ τ ∝ flow; Tomahawk at choking has ε ≈ 0.14). Fraction quantized by {@link #SHARE_STEP}
     * (cache); null — invisible.
     */
    @Nullable
    public static Flame at(Motor motor, double altitude, double share) {
        if (share >= 1) return at(motor, altitude);
        if (share <= 0) return null;
        double g = motor.gamma(), pa = StandardAtmosphere.pressure(Math.round(altitude / STEP) * STEP);
        double choke = Math.min(1, pa * Math.pow((g + 1) / 2, g / (g - 1)) / motor.chamberPressure());
        double s = Math.max(share, choke), step = Math.min(1, Math.exp(-Math.round(-Math.log(s) / SHARE_STEP) * SHARE_STEP));
        Flame f = at(step >= 1 ? motor : motor.throttled(step), altitude);
        return f == null || share >= choke ? f : f.dimmed(share / choke);
    }

    /** Uncached plume (unit tests). */
    @Nullable
    public static Flame of(Motor m, double altitude) {
        double ta = StandardAtmosphere.temperature(altitude), pa = StandardAtmosphere.pressure(altitude);
        double g = m.gamma(), p0 = m.chamberPressure(), t0 = m.temperature();
        double ratio = Math.pow(p0 / pa, (g - 1) / g);
        if (ratio <= 1) return null;
        double mach = Math.sqrt(2 / (g - 1) * (ratio - 1)), tj = t0 / (1 + (g - 1) / 2 * mach * mach);
        if (tj <= DRAPER || tj <= ta) return null;
        double vj = mach * Math.sqrt(g * Motor.R * tj / m.molarMass()), rhoj = pa * m.molarMass() / (Motor.R * tj);
        double perNozzle = m.exhaust() / m.nozzles(), rj = Math.sqrt(perNozzle / (rhoj * vj * Math.PI));
        double core = ELDRED * 2 * rj * Math.pow(1 + ELDRED_MACH * mach, 2);
        // afterburning: flame extends to axial stoichiometric mixture, at its adiabatic temperature
        double air = m.monoxide() * AIR_CO + m.hydrogen() * AIR_H2, flame = core, tf = tj;
        if (air > 0) {
            double fst = 1 / (1 + air);
            flame = core / fst;
            tf = Math.max(tj, fst * tj + (1 - fst) * ta + fst * (m.monoxide() * HEAT_CO + m.hydrogen() * HEAT_H2) / CP_FLAME);
        }
        double length = flame * (tf - ta) / (DRAPER - ta);
        double tau = 0;
        Smoke s = m.smoke();
        if (s != null) tau = s.extinction * s.hotAbsorption * (m.smokeFlow() / m.nozzles()) / (vj * Math.PI * rj * rj) * 2 * rj;
        double eps = 1 - Math.exp(-tau);
        double coreL = eps * BlackBody.luminance(tj);
        if (coreL < DIMMEST) return null;
        double width = rj * Math.sqrt(m.nozzles());
        double pe = m.exitPressure();
        boolean diamonds = pe > pa * DIAMONDS || pe * DIAMONDS < pa;
        Jet jet = new Jet(core, flame, tf, ta, tau);
        // mean part luminance by side area under the same axial law (black-body tail luminance at the part midpoint
        // is tens of times below the mean: B(T) rises steeply with T, and the tail near flame end glows almost as brightly as flame)
        double[] flameRgb = new double[3], tailRgb = new double[3];
        double flameL = jet.mean(0, flame, width, flameRgb), tailL = jet.mean(flame, length, width, tailRgb);
        // light centre is flux centroid: core (cone, side-area centroid one third along) and sheath; the midpoint
        // of length to the Draper point was three times farther from the nozzle than luminous flame, placing smoke light in the tail
        double coreFlux = coreL * core * width;
        double middle = (coreFlux * core / 3 + jet.moment(0, length, width)) / (coreFlux + jet.flux(0, length, width));
        return new Flame(core, flame, length, width, diamonds, coreL, flameL, tailL, color(tj), color(tf), tailL > 0 ? pack(tailRgb) : color(DRAPER),
                tj, tf, eps, jet.sheath(length), middle, tailL > 0 ? jet.moment(flame, length, width) / jet.flux(flame, length, width) : flame, vj);
    }

    /**
     * Axial jet sheath law (afterburning flame around core, tail behind): at x metres from exit, emissivity
     * ε(x) = 1 − e^(−τ(x)), τ(x) = τ₀ within core and τ₀·L_c/x beyond (particle dilution); temperature — flame temperature until flame end,
     * then T_a + (T_f − T_a)·x_f/x; luminance — ε × black body.
     */
    private record Jet(double core, double flame, double tf, double ta, double tau) {
        /** Gas temperature at x metres from exit, K. */
        double temperature(double x) {
            return x <= flame ? tf : ta + (tf - ta) * flame / x;
        }

        /** Cross-jet emissivity at x. */
        double emissivity(double x) {
            return 1 - Math.exp(-(x <= core ? tau : tau * core / x));
        }

        /** Side-view sheath luminance at x (white wall at noon — 1). */
        double luminance(double x) {
            return emissivity(x) * BlackBody.luminance(temperature(x));
        }

        /**
         * Sheath side area per axial metre at x with exit half-width w: whole jet 2w(1 + x/L_c), excluding the core
         * cone near exit 2w(1 − x/L_c) (flame surrounds core, {@link Flame#partAreas}).
         */
        double side(double x, double w) {
            return x < core ? 4 * w * x / core : 2 * w * (1 + x / core);
        }

        /** Mean sheath luminance over area from a to b; rgb receives light flux (linear) for colour. */
        double mean(double a, double b, double w, double[] rgb) {
            if (b <= a) return 0;
            int n = 64;
            double dx = (b - a) / n, flux = 0, area = 0;
            double[] l = new double[4];
            for (int i = 0; i < n; i++) {
                double x = a + (i + 0.5) * dx, s = side(x, w) * dx, e = emissivity(x);
                BlackBody.light(temperature(x), l);
                flux += e * l[3] * s;
                area += s;
                for (int k = 0; k < 3; k++) rgb[k] += e * l[k] * s;
            }
            return area > 0 ? flux / area : 0;
        }

        /** Sheath light flux from a to b (luminance × side area). */
        double flux(double a, double b, double w) {
            return integral(a, b, w, false);
        }

        /** Sheath flux moment from a to b relative to exit (x × luminance × area). */
        double moment(double a, double b, double w) {
            return integral(a, b, w, true);
        }

        private double integral(double a, double b, double w, boolean moment) {
            if (b <= a) return 0;
            int n = 128;
            double dx = (b - a) / n, sum = 0;
            for (int i = 0; i < n; i++) {
                double x = a + (i + 0.5) * dx;
                sum += luminance(x) * side(x, w) * dx * (moment ? x : 1);
            }
            return sum;
        }

        /** Axial positions: exit, core, flame, tail to Draper point — denser near flame end where light drops more steeply. */
        Flame.Sheath sheath(double length) {
            double[] xs = new double[13];
            int n = 0;
            for (double x : new double[]{0, core, (core + flame) / 2, flame}) if (n == 0 || x > xs[n - 1] + 1e-6) xs[n++] = x;
            double from = Math.max(flame, 1e-3);
            for (int k = 1; k <= 8 && length > from; k++) xs[n++] = from * Math.pow(length / from, k / 8.0);
            double[] x = java.util.Arrays.copyOf(xs, n), lum = new double[n];
            int[] colors = new int[n];
            for (int i = 0; i < n; i++) {
                lum[i] = luminance(x[i]);
                colors[i] = color(temperature(x[i]));
            }
            return new Flame.Sheath(x, lum, colors);
        }
    }

    /** Black-body chromaticity at kelvin: sRGB, maximum channel 255. */
    public static int color(double kelvin) {
        double[] l = new double[4];
        BlackBody.light(kelvin, l);
        return pack(l);
    }

    /** Linear rgb (first three numbers) → sRGB, maximum channel 255. */
    static int pack(double[] rgb) {
        double max = Math.max(rgb[0], Math.max(rgb[1], rgb[2]));
        if (max <= 0) return 0;
        int out = 0;
        for (int i = 0; i < 3; i++) out = out << 8 | (int) Math.round(255 * srgb(rgb[i] / max));
        return out;
    }

    /** sRGB encoding (IEC 61966-2-1). */
    public static double srgb(double c) {
        c = Math.max(0, Math.min(1, c));
        return c <= 0.0031308 ? 12.92 * c : 1.055 * Math.pow(c, 1 / 2.4) - 0.055;
    }

    /** sRGB byte → linear fraction. */
    public static double linear(int srgb) {
        double c = srgb / 255.0;
        return c <= 0.04045 ? c / 12.92 : Math.pow((c + 0.055) / 1.055, 2.4);
    }
}
