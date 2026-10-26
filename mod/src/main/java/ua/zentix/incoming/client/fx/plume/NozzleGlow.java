package ua.zentix.incoming.client.fx.plume;

import ua.zentix.incoming.entity.FlightPhase;
import ua.zentix.incoming.nuclear.model.BlackBody;
import ua.zentix.incoming.strike.ThrustCurve;
import ua.zentix.incoming.util.StandardAtmosphere;

/**
 * Hot solid-fuel launch-booster nozzle: wall glow from temperature — attached in flight (visible during thrust decay,
 * when the plume fades) and after separation ({@code SpentBoosterEntity}). Casing does not glow: the steel
 * motor casing has internal thermal protection; over the burn its exterior heats by tens of degrees (Sutton, Biblarz,
 * “Rocket Propulsion Elements”, ch. 15); glow begins at ~800 K (Draper point).
 * <p>
 * Walls — typical tactical solid-motor nozzle (Sutton, Biblarz, ch. 15; (est.) no drawings for Tomahawk Mk 106 or Shahed
 * boosters): throat — graphite insert ({@link Material#GRAPHITE}), divergent section — carbon-phenolic composite
 * ({@link Material#CARBON_PHENOLIC}, char after firing). Each is a flat wall (est.) (curvature ignored) with an
 * insulated rear face (behind it — insulation and steel: almost no heat reaches them in tens of seconds); through-thickness conduction
 * uses a grid ({@link #surface}). During firing, gas convection by Bartz heats it
 * ({@link #bartz}: h ∝ p_c^0.8 — falls with pressure during thrust decay, {@link ThrustCurve}) toward wall recovery
 * temperature ({@link #recovery}); (est.) no ablation, alumina deposition or hot-wall correction (σ = 1). After
 * firing, wall heat is lost by outward radiation through the exit (fraction {@link #VIEW_OUT}) and air convection
 * ({@link #AIR_H}); internal conduction supplies surface heat. Visible surface light: ε × black body at its
 * temperature ({@link BlackBody}).
 * <p>
 * Real time: in-game acceleration is shorter than real motor burn (not physical, {@code WeaponSpec.LaunchProfile}), so
 * the in-game acceleration plateau maps to the real plateau ({@link #seconds}), decay to real duration (as in
 * {@link ThrustCurve}). At separation, walls are heated as after real 12 s for Tomahawk and 2.5 s for Shahed.
 * Visible result: toward decay end, incandescent divergent section near exit (~2 500 K, yellow-white), fading over 10–30 s through orange
 * to dark red; the more massive graphite throat keeps red light inside the nozzle for minutes (est.) (no post-firing nozzle cooling
 * measurements — order of magnitude from the law). These are cooling-model times; the game cleans up the spent booster earlier:
 * mesh and glow fade together before removal (not physical, {@code BoosterLifetime}), so players never see the throat
 * cool over minutes.
 */
public final class NozzleGlow {
    /** Stefan–Boltzmann constant, W/(m²·K⁴). */
    static final double SIGMA = 5.670374e-8;
    /**
     * Wall radiation fraction escaping through the exit: the rest exchanges with other nozzle walls and the empty
     * casing at roughly the same temperature (est.) (cavity view factor).
     */
    static final double VIEW_OUT = 0.5;
    /** Air convection in a falling booster nozzle, W/(m²·K) (est.) (base region, 10–100). */
    static final double AIR_H = 25;
    /** Conical divergent section half-angle: standard 15° (Sutton, Biblarz, ch. 3). */
    static final double CONE_HALF_ANGLE = Math.toRadians(15);
    /** Graphite insert thickness in throat radii (est.) (typical inserts: outer diameter ~2 throat diameters). */
    static final double INSERT = 1.0;
    /** Carbon-phenolic divergent section thickness in throat radii (est.) (1–2 cm for nozzles of this size). */
    static final double LINER = 0.4;
    /** Temperature table step, s. */
    static final double SAMPLE = 0.05;
    /** Table cooling seconds after burn end: booster lives less time in the world ({@code SpentBoosterEntity}). */
    static final double AFTER = 60;
    /**
     * Through-thickness grid nodes: carbon-phenolic — {@value #CELLS_LINER} (heat penetration during thrust decay √(α·t) — 0.25–0.55 mm,
     * node no thicker than half that; with 20 nodes divergent section was 90–125 K colder at separation than the law), graphite —
     * {@value #CELLS_INSERT} (conducts 40 times better; error at most ~15 K, computation tens of ms).
     */
    static final int CELLS_LINER = 80, CELLS_INSERT = 40;

    /**
     * Wall material: thermal conductivity and heat capacity by temperature (tables, linear interpolation), density,
     * emissivity.
     */
    public enum Material {
        /**
         * Fine-grain graphite throat inserts (ATJ grade): ρ 1 760 kg/m³; heat capacity — JANAF (graphite, C(ref)); conductivity
         * across grain ~125 W/(m·K) at 300 K, falling to ~33 at 2 500 K (est.) (ATJ reference curves, ±20 %);
         * ε 0.85 (0.8–0.9).
         */
        GRAPHITE(1760, 0.85, new double[]{250, 298.15, 300, 400, 500, 600, 700, 800, 900, 1000, 1500, 2000, 2500, 3000, 3500},
                massHeat(6.816, 8.517, 8.581, 11.817, 14.623, 16.844, 18.537, 19.827, 20.824, 21.610, 23.904, 25.094, 25.926, 26.611, 27.225),
                new double[]{300, 1000, 1500, 2000, 2500, 3000}, new double[]{125, 60, 45, 38, 33, 30}),
        /**
         * Carbon-phenolic char (solid-motor divergent sections, MX-4926 grade): ρ 1 300 kg/m³, heat capacity ~1 500 J/(kg·K),
         * conductivity ~1 W/(m·K) (est.) (char 0.6–1.7 by temperature), ε 0.85.
         */
        CARBON_PHENOLIC(1300, 0.85, new double[]{298, 3000}, new double[]{1500, 1500}, new double[]{298, 3000}, new double[]{1.0, 1.0});

        final double density, emissivity;
        private final double[] heatT, heat, conductT, conduct;

        Material(double density, double emissivity, double[] heatT, double[] heat, double[] conductT, double[] conduct) {
            this.density = density;
            this.emissivity = emissivity;
            this.heatT = heatT;
            this.heat = heat;
            this.conductT = conductT;
            this.conduct = conduct;
        }

        /** Specific heat at temperature t, J/(kg·K). */
        double heat(double t) {
            return table(heatT, heat, t);
        }

        /**
         * JANAF C(ref), https://janaf.nist.gov/tables/C-002.html: molar heat capacity, J/(mol·K),
         * converted to mass-specific J/(kg·K); graphite molar mass — 12.011 g/mol. Retain the original table numbers
         * so unit conversion is not replaced by an approximately transcribed series.
         */
        private static double[] massHeat(double... molar) {
            for (int i = 0; i < molar.length; i++) molar[i] /= 0.012011;
            return molar;
        }

        /** Thermal conductivity at temperature t, W/(m·K). */
        double conductivity(double t) {
            return table(conductT, conduct, t);
        }

        /** Maximum thermal diffusivity k/(ρc) over all table ranges, m²/s: explicit grid step. */
        double maxDiffusivity() {
            double a = 0;
            for (double t = 250; t <= 3500; t += 25) a = Math.max(a, conductivity(t) / (density * heat(t)));
            return a;
        }
    }

    /**
     * Wall: material, thickness, m, plateau gas heat-transfer coefficient, W/(m²·K), recovery temperature, K,
     * plateau gas emissivity inside nozzle (wall radiates outward through it).
     */
    record Wall(Material material, double thickness, double gas, double recovery, double gasEmissivity) {
        /** Through-thickness grid nodes. */
        int cells() {
            return material == Material.GRAPHITE ? CELLS_INSERT : CELLS_LINER;
        }
    }

    /** Instantaneous nozzle appearance: exit/throat radii, throat depth behind exit, m; divergent section/throat temperatures, K. */
    public record Shine(double exitRadius, double throatRadius, double depth, double coneKelvin, double throatKelvin) {
        /** Divergent section luminance in frame units (white wall at noon — 1). */
        public double coneLuminance() {
            return Material.CARBON_PHENOLIC.emissivity * NozzleGlow.luminance(coneKelvin);
        }

        /** Throat luminance. */
        public double throatLuminance() {
            return Material.GRAPHITE.emissivity * NozzleGlow.luminance(throatKelvin);
        }

        /**
         * Throat fraction visible through exit for view-axis cosine cos (1 — directly aft): throat circle at
         * depth {@link #depth} behind exit shifts across it by depth·tan θ; visible part lies within the exit circle (two-circle
         * lens); forward of exit — 0.
         */
        public double throatSeen(double cos) {
            if (cos <= 0) return 0;
            double tan = Math.sqrt(Math.max(0, 1 - cos * cos)) / cos;
            return lens(throatRadius, exitRadius, depth * tan) / (Math.PI * throatRadius * throatRadius);
        }
    }

    /** Intersection area of circles with radii r1, r2 and centre distance d. */
    static double lens(double r1, double r2, double d) {
        if (d >= r1 + r2) return 0;
        double small = Math.min(r1, r2);
        if (d <= Math.abs(r2 - r1)) return Math.PI * small * small;
        double a1 = Math.acos(Math.max(-1, Math.min(1, (d * d + r1 * r1 - r2 * r2) / (2 * d * r1))));
        double a2 = Math.acos(Math.max(-1, Math.min(1, (d * d + r2 * r2 - r1 * r1) / (2 * d * r2))));
        double k = (-d + r1 + r2) * (d + r1 - r2) * (d - r1 + r2) * (d + r1 + r2);
        return r1 * r1 * a1 + r2 * r2 * a2 - 0.5 * Math.sqrt(Math.max(0, k));
    }

    private final Motor motor;
    private final ThrustCurve curve;
    /** Real burn time, s. */
    private final double burn;
    private final double exitRadius, throatRadius, depth;
    /** Divergent section and throat surface temperatures every {@link #SAMPLE} s after ignition, K. */
    private final double[] cone, throat;
    /** Throat and divergent section walls; thrust decay, real seconds. */
    final Wall throatWall, coneWall;
    final double tail;

    /**
     * Booster motor nozzle (engine data — {@code ClientWeaponSpec}) with in-game thrust curve curve
     * ({@code Airframe.thrust}) and real burn time burn, s ({@code Article.SolidMotor.burn}).
     */
    public NozzleGlow(Motor motor, ThrustCurve curve, double burn) {
        this.motor = motor;
        this.curve = curve;
        this.burn = burn;
        exitRadius = motor.exitRadius();
        throatRadius = throatRadius(motor);
        depth = Math.max(0, exitRadius - throatRadius) / Math.tan(CONE_HALF_ANGLE);
        double ratio = Math.max(1, exitRadius * exitRadius / (throatRadius * throatRadius));
        // divergent section as one wall at side-area midpoint (est.): heat transfer and recovery there
        double mid = (1 + ratio) / 2;
        // nozzle gas emissivity from jet core across the exit (est.) (gas denser inside nozzle: lower estimate)
        Flame plateau = FlameModel.at(motor, 0);
        double gas = plateau == null ? 0 : plateau.emissivity();
        throatWall = new Wall(Material.GRAPHITE, INSERT * throatRadius, bartz(motor, 1), recovery(motor, 1), gas);
        coneWall = new Wall(Material.CARBON_PHENOLIC, LINER * throatRadius, bartz(motor, mid), recovery(motor, mach(motor.gamma(), mid)), gas);
        tail = curve.tail() / 20;
        throat = surface(throatWall, burn, tail, AFTER, 1, 1);
        cone = surface(coneWall, burn, tail, AFTER, 1, 1);
    }

    public Motor motor() {
        return motor;
    }

    /** Real burn time, s: in-game booster separation is this instant in the table. */
    public double burn() {
        return burn;
    }

    /**
     * Real seconds since ignition at age ticks of booster flight phase ph: ignition unchanged (rise,
     * {@link ThrustCurve#RISE}); in-game acceleration plateau maps to real plateau; decay 1:1 (real duration).
     */
    public double seconds(FlightPhase ph, double age) {
        double rise = ThrustCurve.RISE / 20;
        if (ph == FlightPhase.IGNITION) return Math.min(Math.max(age, 0), ThrustCurve.RISE) / 20;
        if (ph != FlightPhase.BOOST) return 0;
        double plateauGame = curve.burn() - curve.tail(), plateauReal = Math.max(0, burn - curve.tail() / 20 - rise);
        double a = Math.max(0, age);
        if (a < plateauGame) return rise + a / plateauGame * plateauReal;
        return rise + plateauReal + Math.min(a - plateauGame, curve.tail()) / 20;
    }

    /** Nozzle seconds real seconds after ignition (after burn — {@link #burn} + cooling seconds). */
    public Shine at(double seconds) {
        return new Shine(exitRadius, throatRadius, depth, sample(cone, seconds), sample(throat, seconds));
    }

    /** Detached booster at age lifetime ticks: walls cool from burn end. */
    public Shine spent(double age) {
        return at(burn + Math.max(0, age) / 20);
    }

    /** Table xs → ys (increasing xs): linear between points, clamped outside bounds. */
    static double table(double[] xs, double[] ys, double x) {
        if (x <= xs[0]) return ys[0];
        for (int i = 1; i < xs.length; i++) {
            if (x <= xs[i]) return ys[i - 1] + (ys[i] - ys[i - 1]) * (x - xs[i - 1]) / (xs[i] - xs[i - 1]);
        }
        return ys[ys.length - 1];
    }

    private static double sample(double[] table, double seconds) {
        double i = Math.max(0, seconds) / SAMPLE;
        int k = (int) Math.floor(i);
        if (k >= table.length - 1) return table[table.length - 1];
        double f = i - k;
        return table[k] * (1 - f) + table[k + 1] * f;
    }

    // ------------------------------------------------------------------ laws

    /**
     * Throat radius, m: flow ṁ = p_c·A_t/c*, c* = √(RT/M)/(√γ·(2/(γ+1))^((γ+1)/(2(γ−1)))) (Sutton, Biblarz, ch. 3);
     * chamber pressure from motor exit ({@link Motor#chamberPressure}).
     */
    static double throatRadius(Motor m) {
        double area = m.exhaust() * cStar(m) / m.chamberPressure();
        return Math.sqrt(area / (m.nozzles() * Math.PI));
    }

    /** Characteristic velocity c*, m/s. */
    static double cStar(Motor m) {
        double g = m.gamma();
        double gammaFn = Math.sqrt(g) * Math.pow(2 / (g + 1), (g + 1) / (2 * (g - 1)));
        return Math.sqrt(Motor.R * m.temperature() / m.molarMass()) / gammaFn;
    }

    /** Product viscosity, Pa·s: μ = 46.6·10⁻¹⁰·M^0.5·T^0.6 (lb/(in·s), T in °R; Sutton, Biblarz, eq. 8-21) converted to SI. */
    static double viscosity(Motor m) {
        return 46.6e-10 * Math.sqrt(m.molarMass() * 1000) * Math.pow(m.temperature() * 1.8, 0.6) * 0.45359237 / 0.0254;
    }

    /** Product Prandtl number: 4γ/(9γ − 5) (Eucken formula, same source). */
    static double prandtl(Motor m) {
        return 4 * m.gamma() / (9 * m.gamma() - 5);
    }

    /**
     * Gas-to-wall heat transfer at area ratio ratio = A/A_t (1 — throat), W/(m²·K): Bartz (1957),
     * h = 0.026/D_t^0.2 · μ^0.2·c_p/Pr^0.6 · (p_c/c*)^0.8 · (A_t/A)^0.9 (throat curvature radius ~ D_t, σ = 1 — (est.)).
     */
    static double bartz(Motor m, double ratio) {
        double d = 2 * throatRadius(m);
        return 0.026 / Math.pow(d, 0.2) * Math.pow(viscosity(m), 0.2) * m.cp() / Math.pow(prandtl(m), 0.6)
                * Math.pow(m.chamberPressure() / cStar(m), 0.8) * Math.pow(1 / ratio, 0.9);
    }

    /** Wall recovery temperature at Mach mach, K: T_c·(1 + r·(γ−1)/2·M²)/(1 + (γ−1)/2·M²), r = Pr^⅓. */
    static double recovery(Motor m, double mach) {
        double k = (m.gamma() - 1) / 2 * mach * mach;
        return m.temperature() * (1 + Math.cbrt(prandtl(m)) * k) / (1 + k);
    }

    /** Supersonic Mach number at area ratio ratio (area equation, bisection). */
    static double mach(double gamma, double ratio) {
        double lo = 1, hi = 30;
        for (int i = 0; i < 80; i++) {
            double mid = (lo + hi) / 2;
            if (areaRatio(gamma, mid) < ratio) lo = mid;
            else hi = mid;
        }
        return (lo + hi) / 2;
    }

    static double areaRatio(double gamma, double mach) {
        return Math.pow(2 / (gamma + 1) * (1 + (gamma - 1) / 2 * mach * mach), (gamma + 1) / (2 * (gamma - 1))) / mach;
    }

    /**
     * Wall surface temperature every {@link #SAMPLE} s after ignition through burn + after: through-thickness conduction —
     * explicit grid of {@link Wall#cells} × cellScale nodes from surface to rear (boundary half-cells; insulated rear), node on
     * the surface itself receives {@link #flux} at the real curve's thrust fraction ({@link #share}: rise, plateau,
     * decay tail s) — second-order scheme: a subsurface half-cell node with balance through it made graphite heating
     * under the plume converge slowly (~25 K at 40 nodes). Stability-limited step, capped at quarter table step and one tenth
     * of decay (carbon-phenolic stability allows 0.1–0.3 s — decay would take one or two steps); fine — time-step
     * refinement factor; cellScale — node count multiplier (reference in tests).
     */
    static double[] surface(Wall w, double burn, double tail, double after, double fine, int cellScale) {
        Material mat = w.material();
        int n = w.cells() * cellScale + 1;
        double dx = w.thickness() / (n - 1), air = StandardAtmosphere.T0;
        double[] t = new double[n], next = new double[n];
        java.util.Arrays.fill(t, air);
        // conduction limit — dx²/(2α) at all nodes (including half-cells); 0.2 leaves margin for gas, radiation and surface air
        double dt = Math.min(0.2 * dx * dx / mat.maxDiffusivity(), Math.min(SAMPLE / 4, tail / 10)) / fine;
        ThrustCurve real = new ThrustCurve(ThrustCurve.RISE, burn * 20 - ThrustCurve.RISE, tail * 20);
        int samples = (int) Math.ceil((burn + after) / SAMPLE) + 1;
        double[] out = new double[samples];
        double time = 0;
        int k = 0;
        out[k++] = air;
        while (k < samples) {
            double q = flux(w, t[0], share(real, burn, time), air);
            for (int i = 0; i < n; i++) {
                double in = i == 0 ? q : mat.conductivity((t[i - 1] + t[i]) / 2) * (t[i - 1] - t[i]) / dx;
                double outFlow = i == n - 1 ? 0 : mat.conductivity((t[i] + t[i + 1]) / 2) * (t[i] - t[i + 1]) / dx;
                double cell = i == 0 || i == n - 1 ? dx / 2 : dx;
                next[i] = t[i] + dt * (in - outFlow) / (mat.density * mat.heat(t[i]) * cell);
            }
            double nowTime = time + dt;
            // linear records between steps
            while (k < samples && k * SAMPLE <= nowTime) {
                double f = (k * SAMPLE - time) / dt;
                out[k++] = t[0] + (next[0] - t[0]) * f;
            }
            double[] swap = t;
            t = next;
            next = swap;
            time = nowTime;
        }
        return out;
    }

    /** Thrust fraction at time real seconds since ignition: real curve ({@link ThrustCurve}), 0 after burn. */
    static double share(ThrustCurve real, double burn, double time) {
        if (time >= burn) return 0;
        return time < ThrustCurve.RISE / 20 ? ThrustCurve.ignition(time * 20) : real.share(FlightPhase.BOOST, time * 20 - ThrustCurve.RISE);
    }

    /**
     * Heat flux into wall surface at temperature ts, thrust fraction share, W/m² (positive inward) — one balance for
     * all time: gas input h·s^0.8·(T_recovery − T); outward radiation ε·σ·{@link #VIEW_OUT}·(T⁴ − T_air⁴) through nozzle gas,
     * fraction (1 − ε_g(s)), ε_g(s) = 1 − (1 − ε_g)^s (gas depth ∝ flow, like {@code Flame.dimmed}); air {@link #AIR_H}
     * at fraction (1 − s): while gas flows, no air at the wall. Every term continuous in s — no step at burn end (a
     * gas/radiation switch made the divergent surface jump by ~280 K instantaneously).
     */
    static double flux(Wall w, double ts, double share, double air) {
        Material mat = w.material();
        double h = share > 0 ? w.gas() * Math.pow(share, 0.8) : 0;
        double rad = mat.emissivity * SIGMA * VIEW_OUT * Math.pow(1 - w.gasEmissivity(), share), conv = AIR_H * (1 - share);
        return h * (w.recovery() - ts) - rad * (Math.pow(ts, 4) - Math.pow(air, 4)) - conv * (ts - air);
    }

    // ------------------------------------------------------------------ light

    /** Black-body luminance table from {@link #FROM_K} in {@link #STEP_K} steps: {@link BlackBody} — 200 steps per value. */
    private static final double FROM_K = 500, STEP_K = 5;
    private static final int TO = 600;
    private static final double[] LUM = new double[TO + 1];
    private static final int[] COLOR = new int[TO + 1];

    static {
        double[] l = new double[4];
        for (int i = 0; i <= TO; i++) {
            BlackBody.light(FROM_K + i * STEP_K, l);
            LUM[i] = l[3];
            COLOR[i] = FlameModel.pack(l);
        }
    }

    /** Black-body luminance at kelvin in frame units (white wall at noon — 1); 0 below 500 K. */
    public static double luminance(double kelvin) {
        if (kelvin <= FROM_K) return 0;
        double i = Math.min((kelvin - FROM_K) / STEP_K, TO);
        int k = Math.min((int) i, TO - 1);
        double f = i - k;
        // logarithmic between nodes: red-heat luminance grows by orders of magnitude per hundred degrees
        return Math.exp(Math.log(LUM[k] + 1e-300) * (1 - f) + Math.log(LUM[k + 1] + 1e-300) * f);
    }

    /** Black-body chromaticity at kelvin (nearest table node): sRGB, maximum channel 255. */
    public static int color(double kelvin) {
        int k = (int) Math.round(Math.max(0, Math.min(TO, (kelvin - FROM_K) / STEP_K)));
        return COLOR[k];
    }
}
