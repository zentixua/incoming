package ua.zentix.incoming.client.sound;

import ua.zentix.incoming.sound.Propagation;

/**
 * How a distant blast reaches an outdoor ear — ISO 9613-2 in two bands, without Minecraft (covered by unit tests).
 * Lows (63 Hz) carry the rumble and determine audibility; highs (2 kHz) determine how muffled it is.
 * <ul>
 * <li>Barrier (hill, houses): single-edge diffraction, Dz = 10·lg(3 + 20·z/λ) (eq. 14), at most 20 dB; z is edge
 * path difference ({@code Sightline}). Edge replaces ground effect: Abar = Dz − Agr, at least zero (eq. 12).</li>
 * <li>Ground (table 3): at source and receiver, 63 Hz As = Ar = −1.5 dB, 2 kHz −1.5·(1 − G); middle
 * Am = −3q and −3q·(1 − Gm). Hard ground reflects — louder; porous ground attenuates highs.</li>
 * <li>Daytime ground heats air, bending sound up: rays from source height hs to ear height hr touch ground at
 * r = √(2R)·(√hs + √hr) (R is ray curvature radius); beyond lies shadow, at 4r up to 20 dB highs and 10 dB lows.
 * R gives, for a blast at 2 m and ear at 1.7 m, r = 1 km (near-ground air cools nearly at the dry adiabatic lapse
 * rate, ~0.9 °C per 100 m); detonation at 600 m moves shadow beyond 9 km. Night inversion: no shadow.</li>
 * <li>Rain noise hides distant sound: +3 dB, thunder another +3.</li>
 * </ul>
 * Audibility margin M = 20·lg(R0/d) − low-band losses; R0 is audible blast range in normal conditions, already
 * including low-band ground reflection. Game volume starts from the near model's edge ({@link #NEAR}) and falls
 * by a quarter of the margin decibels (10^(ΔM/40): hearing compresses loudness), smoothly to zero over the final
 * {@link #FADE} dB. At the edge, open path on a dry night gives the same volume as the near model: no step.
 */
public final class Outdoor {
    /** Near blast sound/visual model edge, blocks (= metres): beyond is computed here. */
    public static final double NEAR = 640;
    /** Band wavelengths, m: 63 Hz lows and 2 kHz highs at 343 m/s. */
    public static final double LOW = 343.0 / 63, HIGH = 343.0 / 2000;
    /** Maximum single-edge diffraction, dB (ISO 9613-2, sec. 7.4). */
    static final double BARRIER_CAP = 20;
    /** Rain and thunder hide distant sound, dB per 0..1 level. */
    static final double RAIN = 3, THUNDER = 3;
    /** Final margin decibels: volume smoothly to zero. */
    static final double FADE = 6;

    private Outdoor() {}

    /**
     * Blast-to-ear sound path.
     *
     * @param d       distance, blocks (= metres)
     * @param z       edge path difference, m; 0 — line of sight
     * @param hs      source height above ground, m
     * @param hr      ear height above ground, m
     * @param gs      source ground porosity, 0..1 ({@code GroundMaterial.porosity})
     * @param gr      listener ground porosity
     * @param shadow  0..1 — how much air bends sound up into shadow ({@link Propagation#refraction})
     * @param rain    rain 0..1
     * @param thunder thunder 0..1
     */
    public record Path(double d, double z, double hs, double hr, double gs, double gr, double shadow, double rain, double thunder) {}

    /**
     * What arrived: game volume and highs (fraction, without air), and log terms, dB: barrier and shadow at lows;
     * highs are how much more muffled than lows, with {@code ground} the ground contribution.
     */
    public record Heard(float volume, float highs, double margin, double barrier, double shadow, double masking, double ground) {}

    /** Single-edge diffraction for a band of wavelength lambda, dB; open path (z ≤ 0) — 0. */
    static double barrier(double z, double lambda) {
        return z <= 0 ? 0 : Math.min(BARRIER_CAP, 10 * Math.log10(3 + 20 * z / lambda));
    }

    /** Middle-path fraction where ground acts (table 3, note): no middle closer than 30·(hs + hr). */
    static double q(double dp, double hs, double hr) {
        double near = 30 * (hs + hr);
        return dp <= near ? 0 : 1 - near / dp;
    }

    /** Ground effect Agr = As + Ar + Am, dB: negative means ground reflection adds sound. */
    static double ground(boolean high, Path p) {
        double q = q(p.d, p.hs, p.hr);
        if (!high) return -1.5 - 1.5 - 3 * q;
        double gm = (p.gs + p.gr) / 2;
        return -1.5 * (1 - p.gs) - 1.5 * (1 - p.gr) - 3 * q * (1 - gm);
    }

    /** Barrier beyond ground effect (Abar = Dz − Agr ≥ 0), dB. */
    static double obstacle(boolean high, Path p) {
        return p.z > 0 ? Math.max(0, barrier(p.z, high ? HIGH : LOW) - ground(high, p)) : 0;
    }

    /** Daytime shadow start, blocks: where source-to-ear ray touches ground ({@link Propagation#shadowFrom}). */
    static double shadowFrom(double hs, double hr) {
        return Propagation.shadowFrom(hs, hr);
    }

    /** Daytime shadow, dB: upward refraction reduces near-ground sound beyond ray-ground tangency (63 Hz and 2 kHz). */
    static double shadow(boolean high, Path p) {
        return Propagation.shadow(high ? 2000 : 63, p.d, p.hs, p.hr, p.shadow);
    }

    /** Rain and thunder, dB. */
    static double masking(Path p) {
        return RAIN * p.rain + THUNDER * p.thunder;
    }

    /** Low-band path loss beyond spreading, dB: barrier, shadow, rain (low-band ground already in R0 and pressure). */
    public static double losses(Path p) {
        return pressureLoss(p) + masking(p);
    }

    /** Actual low-band pressure path loss, dB: barrier and shadow (rain hides sound, not pressure). */
    public static double pressureLoss(Path p) {
        return obstacle(false, p) + shadow(false, p);
    }

    /** Audibility margin, dB: low-band ground already in R0. */
    public static double margin(double r0, Path p) {
        return 20 * Math.log10(r0 / p.d) - losses(p);
    }

    /**
     * Game volume.
     *
     * @param r0   audible range in normal conditions, blocks
     * @param v640 near model volume at its edge ({@link #NEAR})
     */
    public static float volume(double r0, double v640, Path p) {
        double m = margin(r0, p);
        double v = v640 * Math.pow(10, (m - 20 * Math.log10(r0 / NEAR)) / 40) * smoothstep(0, FADE, m);
        return (float) Math.min(1, v);
    }

    /**
     * Game volume from peak ear pressure {@code pa} (open path, with ground reflection as in the wave model):
     * pressure {@code full} gives volume 1, quieter by half the pressure decibels, like the near conventional-blast
     * model ({@link BlastMix}: −3 dB per doubling) and {@link #volume}, with path losses applied likewise. No fade
     * at audibility threshold: used for blasts audible far beyond the world extent (nuclear detonation).
     */
    public static float pressureVolume(double pa, double full, Path p) {
        return (float) Math.min(1, Math.sqrt(Math.max(0, pa) / full) * Math.pow(10, -losses(p) / 40));
    }

    /** High fraction relative to lows (rain attenuates both equally — omitted); air separately. */
    public static float highs(Path p) {
        double extra = ground(true, p) + obstacle(true, p) + shadow(true, p) - ground(false, p) - obstacle(false, p) - shadow(false, p);
        return (float) Math.min(1, Math.pow(10, -extra / 20));
    }

    /**
     * High fraction relative to lows lost from the shock-wave pressure itself along the path: edge (diffraction
     * without ground) and shadow. No ground in either band: wave pressure already includes ground reflection
     * (at ground, one wave with its reflection, Mach stem), rather than destructive sound interference over soft
     * ground as in {@link #highs}; also beyond an edge ({@link #obstacle} subtracts ground, so grass behind a hill
     * would give wave highs 4–5 dB more than stone).
     */
    public static float pressureHighs(Path p) {
        double edge = p.z > 0 ? barrier(p.z, HIGH) - barrier(p.z, LOW) : 0;
        double extra = edge + shadow(true, p) - shadow(false, p);
        return (float) Math.min(1, Math.pow(10, -extra / 20));
    }

    public static Heard hear(double r0, double v640, Path p) {
        return new Heard(volume(r0, v640, p), highs(p), margin(r0, p), obstacle(false, p), shadow(false, p), masking(p),
                ground(true, p) - ground(false, p));
    }

    private static double smoothstep(double a, double b, double x) {
        double t = Math.max(0, Math.min(1, (x - a) / (b - a)));
        return t * t * (3 - 2 * t);
    }
}
