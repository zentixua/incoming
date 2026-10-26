package ua.zentix.incoming.sound;

import org.jetbrains.annotations.Nullable;
import ua.zentix.incoming.entity.FlightPhase;
import ua.zentix.incoming.strike.ThrustCurve;

/**
 * What makes a projectile noisy — its airframe sound spec ({@code WeaponSpec.Airframe#noise}): cruise engine, launch
 * booster (or rocket motor), body airflow, drilling and launch transients. Each part's level is sound power
 * L<sub>W</sub> (dB re 1 pW), from its class law using article data and actual entity speed
 * ({@code StrikeProjectile#velocity}); spectrum is the recording's octaves ({@link Spectra}). Server audibility and
 * client volume use this same spec ({@link Propagation}).
 *
 * @param engine     cruise engine; {@code null} — none
 * @param motor      solid booster or rocket motor, burns while {@link FlightPhase#boosterLit}; {@code null} — none
 * @param body       body: airflow noise and supersonic N-wave; {@code null} — ignored
 * @param drill      bomb drilling into soil, A-weighted sound power, dBA; {@code NaN} — not drilling
 * @param catapult   catapult impulse, A-weighted sound power, dBA; {@code NaN} — none
 * @param separation booster separation (pyrotechnic cartridges, lock clank), A-weighted sound power, dBA; {@code NaN} — none
 */
public record Noise(@Nullable Engine engine, @Nullable Rocket motor, @Nullable Body body, double drill, double catapult, double separation) {
    /** Sound speed, m/s. */
    public static final double SOUND = ua.zentix.incoming.nuclear.model.BlastModel.SOUND_SPEED;
    /** Zero-dB power, W. */
    static final double PW = 1e-12;
    /** Silence — absent level. */
    public static final double SILENT = Double.NEGATIVE_INFINITY;

    /** Noise sources: each part has its own law and recording. */
    public enum Part { ENGINE, MOTOR, BODY, DRILL }

    /** Cruise engine. */
    public sealed interface Engine permits Propeller, Jet {
        /** Current sound power, dB (unweighted). */
        double power(FlightPhase ph, double age, double speed);

        /** RPM relative to nominal (recorded pitch at nominal). */
        double revs(FlightPhase ph, double age, double speed);

        Spectrum spectrum();
    }

    /**
     * Piston or electric motor with fixed-pitch propeller. RPM follows airspeed at constant advance ratio (J = V/nD):
     * n = n<sub>nom</sub>·V/V<sub>nom</sub>, no lower than stationary RPM or higher than limit; shaft power ∝ n³
     * (C<sub>P</sub>·ρ·n³·D⁵ at equal advance ratio); sound is a constant power share:
     * L<sub>WA</sub> = L<sub>WA,ref</sub> + 10·lg(P/P<sub>ref</sub>), calibrated from a same-class craft recording at known
     * distance. Tones: blade passage B·n/60 and cylinder firing, in the nominal-RPM loop ({@code tools/build_sounds.py}).
     *
     * @param power  nominal power, W
     * @param rpm    nominal RPM, rev/min
     * @param blades propeller blade count
     * @param speed  nominal airspeed, m/s
     * @param idle   stationary RPM (on launcher, full throttle) relative to nominal
     * @param limit  RPM limit (dive overspeed) relative to nominal
     * @param refA   same-class craft A-weighted sound power, dBA, at {@code refPower}, W
     */
    public record Propeller(double power, double rpm, int blades, double speed, double idle, double limit, double refA, double refPower,
                            Spectrum spectrum) implements Engine {
        @Override
        public double revs(FlightPhase ph, double age, double v) {
            if (ph.onLauncher()) return idle;
            return Math.max(idle, Math.min(limit, v * 20 / speed));
        }

        @Override
        public double power(FlightPhase ph, double age, double v) {
            double n = revs(ph, age, v);
            return refA + 10 * Math.log10(power * n * n * n / refPower) - spectrum.aWeighting();
        }

        /** Nominal blade passage frequency, Hz. */
        public double bladePass() {
            return blades * rpm / 60;
        }
    }

    /**
     * Jet engine: jet noise by Lighthill's law — mechanical jet power share η = K·M⁵ (M — jet Mach relative to air),
     * K = 10⁻⁴ (M. J. Lighthill, “On sound generated aerodynamically”, 1952–54; ⚠ check primary source). Jet speed relative to
     * air subtracts flight speed (simplest flight correction, (V<sub>j</sub> − V)⁷: measured flight reduces noise less —
     * underestimated here, B-2 by ~5 dB), thrust is static (lower at altitude — overestimated here). Mass flow is static
     * thrust / jet speed. Starts after booster separation; rotor RPM rises from idle to full over {@code spool} ticks;
     * jet speed proportional to RPM.
     *
     * @param thrust one engine's static thrust, N
     * @param jet    static jet speed, m/s
     * @param count  engine count
     * @param idle   idle RPM relative to full
     * @param spool  spool-up ticks
     */
    public record Jet(double thrust, double jet, int count, double idle, double spool, Spectrum spectrum) implements Engine {
        /** Lighthill coefficient. */
        static final double LIGHTHILL = 1e-4;

        @Override
        public double revs(FlightPhase ph, double age, double v) {
            if (ph.launching()) return 0;
            return ph == FlightPhase.CLIMB ? idle + (1 - idle) * Propagation.smoothstep(0, spool, age) : 1;
        }

        @Override
        public double power(FlightPhase ph, double age, double v) {
            double n = revs(ph, age, v);
            double rel = jet * n - v * 20;
            if (n <= 0 || rel <= 0) return SILENT;
            double mass = thrust / jet;
            double w = count * LIGHTHILL * Math.pow(rel / SOUND, 5) * 0.5 * mass * rel * rel;
            return 10 * Math.log10(w / PW);
        }
    }

    /**
     * Solid motor: sound is mechanical jet power share η·½·F·v<sub>e</sub>, η = 0.005 ± 0.0022
     * (NASA SP-8072, “Acoustic loads generated by the propulsion system”, 1971; value via Oliynik, Batutina, 2024).
     * Time-varying thrust uses the booster curve ({@link ThrustCurve}: ignition ramp, plateau, tail-off to separation);
     * exhaust velocity remains constant during tail-off, so jet power ∝ thrust share; without a curve, only the ignition
     * ramp ({@link ThrustCurve#ignition}), then plateau.
     *
     * @param thrust  thrust, N
     * @param exhaust exhaust velocity, m/s
     * @param curve   launch booster thrust over time; null — motor at full thrust throughout boost phase
     */
    public record Rocket(double thrust, double exhaust, Spectrum spectrum, @Nullable ThrustCurve curve) {
        static final double EFFICIENCY = 0.005;

        public Rocket(double thrust, double exhaust, Spectrum spectrum) {
            this(thrust, exhaust, spectrum, null);
        }

        public double power(FlightPhase ph, double age) {
            if (!ph.boosterLit()) return SILENT;
            double share = curve != null ? curve.share(ph, age) : ph == FlightPhase.IGNITION ? ThrustCurve.ignition(age) : 1;
            return share <= 0 ? SILENT : 10 * Math.log10(EFFICIENCY * 0.5 * thrust * share * exhaust / PW);
        }
    }

    /**
     * Body in airflow: Curle dipole noise (N. Curle, 1955), W = K·ρ·U⁶·A/c³ (A — frontal area), with
     * K = C′²·St²·π²/48 from vortex-shedding force on the body at frequency St·U/D: C′ = 0.1, St = 0.2 — estimate,
     * shared by all bodies (≈ 8·10⁻⁵). Pitch follows Strouhal number: ∝ U, recording at {@code reference}.
     *
     * @param diameter  caliber (body diameter), m
     * @param length    length, m
     * @param reference recording speed, m/s (estimated from recording)
     */
    public record Body(double diameter, double length, double reference, Spectrum spectrum) {
        static final double CURLE = 0.1 * 0.1 * 0.2 * 0.2 * Math.PI * Math.PI / 48;

        public double area() {
            return Math.PI * diameter * diameter / 4;
        }

        public double power(double v) {
            double u = v * 20;
            if (u <= 0) return SILENT;
            double w = CURLE * Air.DENSITY * Math.pow(u, 6) * area() / (SOUND * SOUND * SOUND);
            return 10 * Math.log10(w / PW);
        }

        /** Airflow pitch relative to recording. */
        public double pitch(double v) {
            return v * 20 / reference;
        }
    }

    /**
     * A-weighted sound power of a source with spectrum {@code shape}, dBA, whose {@code path} produces measured A-level
     * {@code measured} under the mod's propagation law ({@link Propagation#level}). Inverse of the same law, not spreading
     * with its own ground correction: otherwise a measurement-derived source would not reproduce its measurement there
     * (Shahed 4.9 dB quieter than its reference ultralight over a meadow, F4).
     */
    public static double fromMeasured(double measured, Spectrum shape, Propagation.Path path) {
        return measured - Propagation.level(0, shape, path, null) + shape.aWeighting();
    }

    /** Current part sound power, dB (unweighted); not emitting — {@link #SILENT}. */
    public double power(Part part, FlightPhase ph, double age, double speed, boolean drilling) {
        return switch (part) {
            case ENGINE -> engine == null || drilling ? SILENT : engine.power(ph, age, speed);
            case MOTOR -> motor == null || drilling ? SILENT : motor.power(ph, age);
            case BODY -> body == null || drilling ? SILENT : body.power(speed);
            case DRILL -> drilling && !Double.isNaN(drill) ? drill - Spectra.BOMB_DRILL.aWeighting() : SILENT;
        };
    }

    /** Part spectrum. */
    public Spectrum spectrum(Part part) {
        return switch (part) {
            case ENGINE -> engine == null ? Spectra.DRONE_ENGINE : engine.spectrum();
            case MOTOR -> motor == null ? Spectra.BOOSTER_ENGINE : motor.spectrum();
            case BODY -> body == null ? Spectra.BOMB_FALL : body.spectrum();
            case DRILL -> Spectra.BOMB_DRILL;
        };
    }

    /**
     * What reaches the ear from all currently emitting parts over {@code path} (without obstacles): A-weighted octaves
     * in {@code bands}.
     *
     * @param speed speed, blocks/tick
     * @return total A-weighted level, dBA ({@link #SILENT} — no sound)
     */
    public double hear(FlightPhase ph, double age, double speed, boolean drilling, Propagation.Path path, double[] bands) {
        double[] sum = new double[Propagation.BANDS], part = new double[Propagation.BANDS];
        boolean any = false;
        for (Part p : Part.values()) {
            double lw = power(p, ph, age, speed, drilling);
            if (lw == SILENT) continue;
            any = true;
            Propagation.level(lw, spectrum(p), path, part);
            for (int b = 0; b < sum.length; b++) sum[b] += Math.pow(10, part[b] / 10);
        }
        double total = 0;
        for (int b = 0; b < sum.length; b++) {
            bands[b] = sum[b] > 0 ? 10 * Math.log10(sum[b]) : SILENT;
            total += sum[b];
        }
        return any ? 10 * Math.log10(total) : SILENT;
    }

    /** How far current combined noise exceeds the ear's background in the most favorable octave, dB (negative — inaudible). */
    public double margin(FlightPhase ph, double age, double speed, boolean drilling, Propagation.Path path) {
        // upper bound: all power in one octave, no air, maximum ground and A boost — still inaudible
        double sum = 0;
        for (Part p : Part.values()) {
            double lw = power(p, ph, age, speed, drilling);
            if (lw != SILENT) sum += Math.pow(10, lw / 10);
        }
        if (sum <= 0) return Double.NEGATIVE_INFINITY;
        double bound = 10 * Math.log10(sum) - Propagation.divergence(path.d()) + Propagation.MAX_GAIN - Propagation.quietest(path.weather());
        if (bound <= 0) return bound;
        double[] bands = new double[Propagation.BANDS];
        return hear(ph, age, speed, drilling, path, bands) == SILENT ? Double.NEGATIVE_INFINITY : Propagation.margin(bands, path.weather());
    }
}
