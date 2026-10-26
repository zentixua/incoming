package ua.zentix.incoming.nuclear.model;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Hearing after blast: temporary threshold shift (TTS), recovery, eardrum rupture.
 * <ul>
 * <li>Ear damage depends on energy in the audible band, not peak pressure. A distant blast's energy is almost entirely
 * infrasound; audible energy comes from the front: a pressure step p (rise in fractions of a millisecond,
 * {@code client.sound.BlastPulse}). A-weighted step energy (IEC 61672) is peak minus {@link #STEP_A} dB, independent of
 * phase duration (above 50 ms): LAE = L_peak − 38.7 dB. Aircraft sonic booms up to 144 lb/ft² (~1 psi) caused no human
 * hearing damage (DTIC AD0680800, NASA reviews); the threshold is there too: daily noise dose 85 dBA for 8 h
 * (ISO 1999, NIOSH), LAE 129.6 dB — a front of ~0.75 psi. The 140 dB peak limit (CHABA, NIOSH) applies to gunshots,
 * whose energy is entirely audible, not to a long blast.</li>
 * <li>One law for any blast: audible energy is the A-energy of the wave at the ear, a Friedlander pulse with peak p and
 * positive phase t₊ ({@link #exposure(double, double)}). For a distant nuclear wave (seconds-long phase), the spectrum
 * above 100 Hz is the step spectrum, exactly “peak − 38.7 dB” ({@link #exposure(double)} — limit as t₊ → ∞; at 0.2 s phase,
 * difference below 0.01 dB). A conventional blast's phase is milliseconds: the whole wave is audible, with less energy
 * than a step of equal peak (50 kg at 100 m — by 0.2 dB; 3 kg at 1 km — by 7 dB). Same threshold: equal energy,
 * 85 dBA for 8 h. The 140 dB peak limit (CHABA, NIOSH) allows a hundred shots daily, rather than defining threshold shift
 * from one blast: Shahed shift onset (LAE 129.6 dB) is at ~75 m, peak ~168 dB.</li>
 * <li>High-frequency shift (4 kHz) after 2 minutes: zero at threshold to {@link #TRAUMA_SHIFT} dB at eardrum rupture
 * threshold (5 psi — acoustic trauma), linear in energy dB between (~3 dB shift per dB). Approximation: little TTS data
 * for one long blast; anchors are threshold and trauma. At low frequencies (up to 1 kHz), impulse causes less shift —
 * {@link #LOW_SHARE} of the high-frequency shift (approximation from the 4 kHz “notch” shape).</li>
 * <li>Recovery is linear in log time from 2 minutes (Ward 1960): shifts up to 40 dB recover in 16 hours; larger ones take
 * longer, doubling per extra 10 dB (approximation: such shifts often leave permanent loss in humans, absent in the game).
 * Before 2 minutes, shift is greater — the same line, from 12 s.</li>
 * <li>Eardrum rupture follows a lognormal law: 1 % at 5 psi, 50 % at 15 psi (DTIC; Glasstone &amp; Dolan, table 12.38),
 * 99 % at 45 psi. Rupture causes conductive loss {@link #RUPTURE_LOW}/{@link #RUPTURE_HIGH} dB at low/high frequencies
 * (medium perforation, Mehta et al. 2006); perforation heals in 4–8 weeks.</li>
 * <li>Less muffling than threshold shift: cochlear threshold shift barely affects loud sounds (recruitment — loudness
 * catches normal by {@link #DISCOMFORT} dB), so game sound (~{@link #LEVEL} dB) is quieter by {@link #loudnessLoss}, not
 * the full shift; conductive loss attenuates everything by its full amount.</li>
 * </ul>
 */
public final class HearingModel {
    /** Reference sound pressure, Pa. */
    public static final double REFERENCE_PA = 2e-5;
    /** A-weighted step energy relative to peak, dB: 10·lg(2∫A²(f)/(2πf)² df), s. */
    public static final double STEP_A = -38.74;
    /** Audible front energy at threshold-shift onset, dB·s (85 dBA for 8 hours). */
    public static final double ONSET_EXPOSURE = 85 + 10 * Math.log10(8 * 3600);
    /** High-frequency shift at 5 psi, dB. */
    static final double TRAUMA_SHIFT = 50;
    /** Eardrum rupture threshold pressure, kPa (5 psi). */
    static final double RUPTURE_ONSET_KPA = BlastModel.kpa(5);
    /** Low-frequency fraction of high-frequency shift. */
    static final double LOW_SHARE = 1 / 3.0;
    /** Hour when the recovery line starts (2 minutes); blasts within this many hours form one dose ({@link Ear}). */
    public static final double FIRST_HOURS = 2 / 60.0;
    /** Recovery: hours for a shift up to {@code SLOW_FROM} dB to resolve. */
    static final double FULL_HOURS = 16, SLOW_FROM = 40;
    /** Eardrums: median, psi; 1 % at 5 psi (z = −2.326). */
    static final double RUPTURE_MEDIAN_PSI = 15, RUPTURE_SIGMA = Math.log(3) / 2.326;
    /** Conductive rupture loss, dB: lows and highs. */
    static final double RUPTURE_LOW = 25, RUPTURE_HIGH = 15;
    /** Perforation healing: day when loss starts fading and day when it is gone. */
    static final double HEAL_FROM_DAYS = 28, HEAL_DAYS = 56;
    /** Game sound loudness and discomfort threshold, dB: recruitment between them. */
    static final double LEVEL = 70, DISCOMFORT = 100;

    /** Quiet rural night background, dBA (estimate: rural nights — 25–35 dBA). */
    static final double QUIET_NIGHT_A = 30;
    /** Time over which hearing sums short-sound energy, s (temporal integration, ~200 ms). */
    static final double INTEGRATION_S = 0.2;
    /**
     * ISO 9613-1 air absorption at 20 °C and 70 % humidity, dB/km, at 63 Hz … 8 kHz — same table as
     * {@code tools/build_sounds.py} ({@code AIR_DB_KM}); logarithmic interpolation; below 63 Hz and above 8 kHz, f².
     */
    private static final double[] AIR_HZ = {63, 125, 250, 500, 1000, 2000, 4000, 8000}, AIR_DB_KM = {0.1, 0.3, 1.1, 2.8, 5.0, 9.0, 22.9, 76.6};
    /** Energy integral frequencies, Hz: infrasound to upper hearing limit, logarithmic. */
    private static final double[] F = new double[480];
    private static final double[] A2 = new double[F.length], DF = new double[F.length];
    /** Audible range by charge: expensive law, only a few charges in the game. */
    private static final Map<Double, Double> AUDIBLE = new ConcurrentHashMap<>();

    static {
        double lo = Math.log(1), hi = Math.log(40_000);
        for (int i = 0; i < F.length; i++) F[i] = Math.exp(lo + (hi - lo) * i / (F.length - 1));
        for (int i = 0; i < F.length; i++) {
            double a = aWeight(F[i]);
            A2[i] = a * a;
            // trapezoids on the logarithmic grid
            DF[i] = 0.5 * ((i + 1 < F.length ? F[i + 1] : F[i]) - (i > 0 ? F[i - 1] : F[i]));
        }
    }

    private HearingModel() {
    }

    /**
     * A-weighting (IEC 61672-1), amplitude ratio: R_A(f) = 12194²·f⁴ / ((f² + 20.6²)·√((f² + 107.7²)(f² + 737.9²))·(f² +
     * 12194²)), +2.00 dB — 1 at 1 kHz.
     */
    public static double aWeight(double f) {
        double f2 = f * f;
        double ra = 12194.0 * 12194 * f2 * f2 / ((f2 + 20.6 * 20.6) * Math.sqrt((f2 + 107.7 * 107.7) * (f2 + 737.9 * 737.9)) * (f2 + 12194.0 * 12194));
        return ra * Math.pow(10, 2.0 / 20);
    }

    /**
     * A-energy of the wave at the ear, dB·s (LAE): Friedlander pulse (1 − t/t₊)·e^(−t/t₊) with peak {@code pa} Pa
     * (front is a step: fractional-millisecond rise at harmful pressures does not affect energy), spectrum
     * |P(ω)| = p·t₊·ωt₊ / (1 + (ωt₊)²), E = 2∫A²(f)|P(f)|² df.
     *
     * @param tPlus positive phase at the ear, s
     */
    public static double exposure(double pa, double tPlus) {
        return 20 * Math.log10(Math.max(pa, 1e-9) / REFERENCE_PA) + 10 * Math.log10(shape(tPlus, 0));
    }

    /**
     * Energy share of a unit-peak wave with phase {@code tPlus} received by hearing (A-weighted), s·Pa²/Pa²;
     * path {@code metres} absorbs high frequencies by ISO 9613-1 (0 — at source).
     */
    private static double shape(double tPlus, double metres) {
        double sum = 0, t = Math.max(1e-6, tPlus);
        for (int i = 0; i < F.length; i++) {
            double wt = 2 * Math.PI * F[i] * t, spectrum = t * wt / (1 + wt * wt);
            double air = metres > 0 ? Math.pow(10, -airDbPerKm(F[i]) * metres / 1000 / 10) : 1;
            sum += A2[i] * spectrum * spectrum * air * DF[i];
        }
        return 2 * sum;
    }

    /** Air absorption at frequency f, dB/km (ISO 9613-1, {@link #AIR_DB_KM}). */
    static double airDbPerKm(double f) {
        if (f <= AIR_HZ[0]) return AIR_DB_KM[0] * (f / AIR_HZ[0]) * (f / AIR_HZ[0]);
        int n = AIR_HZ.length - 1;
        if (f >= AIR_HZ[n]) return AIR_DB_KM[n] * (f / AIR_HZ[n]) * (f / AIR_HZ[n]);
        int i = 0;
        while (f > AIR_HZ[i + 1]) i++;
        double u = Math.log(f / AIR_HZ[i]) / Math.log(AIR_HZ[i + 1] / AIR_HZ[i]);
        return Math.exp(Math.log(AIR_DB_KM[i]) + u * Math.log(AIR_DB_KM[i + 1] / AIR_DB_KM[i]));
    }

    /**
     * A-energy of a surface blast from charge {@code tntKg} kg TNT at {@code metres}, dB·s: pressure and phase —
     * Kinney–Graham ({@link BlastModel}); air absorbs high frequencies along the path (ISO 9613-1). No ground, terrain or
     * wind — handled by the listener path ({@code client.sound.Outdoor}).
     */
    public static double blastExposureAt(double tntKg, double metres) {
        double pa = 1000 * BlastModel.surfaceOverpressureKpa(metres, tntKg), tPlus = BlastModel.chemicalPhaseSeconds(metres, tntKg, true);
        return 20 * Math.log10(Math.max(pa, 1e-12) / REFERENCE_PA) + 10 * Math.log10(shape(tPlus, metres));
    }

    /**
     * Audible surface blast range for charge {@code tntKg} kg TNT on a quiet night, m: its A-energy falls to background
     * energy ({@link #QUIET_NIGHT_A}) over auditory integration time ({@link #INTEGRATION_S}) — 23 dB·s. This is R0 of the
     * distant sound model (audibility margin 20·lg(R0/d) minus path losses) and server blast packet range. Upper estimate:
     * flat world, no turbulence or waveguide; daytime shadow, hills and rain handled at the listener. 0 — no charge.
     */
    public static double audibleRangeM(double tntKg) {
        if (!(tntKg > 0)) return 0;
        return AUDIBLE.computeIfAbsent(tntKg, HearingModel::solveAudible);
    }

    private static double solveAudible(double tntKg) {
        double threshold = QUIET_NIGHT_A + 10 * Math.log10(INTEGRATION_S), lo = 1, hi = 1e7;
        if (blastExposureAt(tntKg, hi) >= threshold) return hi;
        for (int i = 0; i < 60; i++) {
            double mid = Math.sqrt(lo * hi);
            if (blastExposureAt(tntKg, mid) > threshold) lo = mid;
            else hi = mid;
        }
        return lo;
    }

    /**
     * Audible front energy of a long wave with peak {@code pa} Pa, dB·s (LAE): {@link #exposure(double, double)} limit
     * when positive phase is much longer than audible timescales (t₊ → ∞) — a step.
     */
    public static double exposure(double pa) {
        return 20 * Math.log10(Math.max(pa, 1e-9) / REFERENCE_PA) + STEP_A;
    }

    /** High-frequency threshold shift after 2 minutes, dB, by audible front energy {@code exposure}, dB·s. */
    public static double shift(double exposure) {
        double trauma = exposure(1000 * RUPTURE_ONSET_KPA);
        return Math.max(0, TRAUMA_SHIFT * (exposure - ONSET_EXPOSURE) / (trauma - ONSET_EXPOSURE));
    }

    /** Low-frequency shift for high-frequency shift {@code high}, dB. */
    public static double low(double high) {
        return LOW_SHARE * high;
    }

    /** Hours for shift {@code first} (at 2 minutes), dB, to resolve. */
    public static double recoveryHours(double first) {
        return FULL_HOURS * Math.pow(2, Math.max(0, first - SLOW_FROM) / 10);
    }

    /** Shift {@code hours} hours after the blast, dB; {@code first} is the shift at 2 minutes. */
    public static double shiftAfter(double first, double hours) {
        if (first <= 0) return 0;
        double end = recoveryHours(first), t = Math.max(hours, 0.1 * FIRST_HOURS);
        if (t >= end) return 0;
        return first * (1 - Math.log10(t / FIRST_HOURS) / Math.log10(end / FIRST_HOURS));
    }

    /** Eardrum rupture probability at pressure {@code kpa} kPa. */
    public static double ruptureProbability(double kpa) {
        double psi = BlastModel.psi(kpa);
        if (psi <= 0) return 0;
        return normal(Math.log(psi / RUPTURE_MEDIAN_PSI) / RUPTURE_SIGMA);
    }

    /** Conductive loss from ruptured eardrums after {@code days} days, dB: highs or lows. */
    public static double rupturedLoss(boolean high, double days) {
        double t = Math.max(0, Math.min(1, (days - HEAL_FROM_DAYS) / (HEAL_DAYS - HEAL_FROM_DAYS)));
        return (high ? RUPTURE_HIGH : RUPTURE_LOW) * (1 - t * t * (3 - 2 * t));
    }

    /**
     * How much quieter game sound is with cochlear threshold shift {@code shift}, dB: loudness rises from the shifted
     * threshold, catching normal at {@link #DISCOMFORT} dB (recruitment, linear in dB — approximation).
     */
    public static double loudnessLoss(double shift) {
        double s = Math.max(0, Math.min(shift, LEVEL));
        return (DISCOMFORT - LEVEL) * s / (DISCOMFORT - s);
    }

    /** Standard normal cumulative distribution (Abramowitz–Stegun 26.2.17, error up to 7.5·10⁻⁸). */
    static double normal(double z) {
        double t = 1 / (1 + 0.2316419 * Math.abs(z));
        double poly = t * (0.319381530 + t * (-0.356563782 + t * (1.781477937 + t * (-1.821255978 + t * 1.330274429))));
        double tail = Math.exp(-z * z / 2) / Math.sqrt(2 * Math.PI) * poly;
        return z >= 0 ? 1 - tail : tail;
    }

    /**
     * Blasts at one ear. Blasts within 2 minutes ({@link #FIRST_HOURS} hours) form one dose: energies add (equal energy,
     * ISO 1999), shift from the sum and latest blast. Afterward each dose has its own recovery line, and shift is their
     * maximum: a second dose over an unresolved shift neither shortens nor adds to it (approximation: no data on summing
     * shifts from separate doses).
     */
    public static final class Ear {
        /** Maximum retained doses: discard the one with the smallest current shift. */
        private static final int MOST = 16;
        /** Dose: energy, dB·s, and hour of its last blast. */
        private final List<double[]> doses = new ArrayList<>();

        /** Blast with audible front energy {@code exposure}, dB·s, at hearing-clock {@code hour}. */
        public void blast(double exposure, double hour) {
            double e = exposure;
            for (Iterator<double[]> it = doses.iterator(); it.hasNext(); ) {
                double[] d = it.next();
                if (hour - d[1] > FIRST_HOURS) continue;
                e = 10 * Math.log10(Math.pow(10, e / 10) + Math.pow(10, d[0] / 10));
                it.remove();
            }
            doses.add(new double[]{e, hour});
            if (doses.size() > MOST) {
                double[] least = doses.get(0);
                for (double[] d : doses) if (shiftAfter(HearingModel.shift(d[0]), hour - d[1]) < shiftAfter(HearingModel.shift(least[0]), hour - least[1])) least = d;
                doses.remove(least);
            }
        }

        /** High-frequency shift at {@code hour}, dB; resolved doses are removed (except those still able to combine with a new blast). */
        public double shift(double hour) {
            double most = 0;
            for (Iterator<double[]> it = doses.iterator(); it.hasNext(); ) {
                double[] d = it.next();
                double s = shiftAfter(HearingModel.shift(d[0]), hour - d[1]);
                if (s <= 0 && hour - d[1] > FIRST_HOURS) it.remove();
                else most = Math.max(most, s);
            }
            return most;
        }

        /** Whether any doses remain. */
        public boolean any() {
            return !doses.isEmpty();
        }

        public void clear() {
            doses.clear();
        }
    }
}
