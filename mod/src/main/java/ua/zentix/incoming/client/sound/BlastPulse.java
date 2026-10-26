package ua.zentix.incoming.client.sound;

/**
 * Shock-wave impact at the ear — a track from the model, without Minecraft (covered by a unit test), for any blast:
 * far from a nuclear detonation (below 1 psi) and for a conventional charge ({@link BlastMix}: Kinney–Graham pressure
 * and phase), the wave is a shock: one pressure jump and a smooth positive-phase decay to zero over {@code t+},
 * then rarefaction (balanced Friedlander pulse: Garcés 2019, ch. 8). There is no trailing jump as in an N-wave:
 * the rear front lags and becomes a shock only beyond a hundred kilometres. At an ear 1.6 m high, ground reflection
 * arrives milliseconds later and merges with the direct wave (beyond the Mach triple point, one wave): no second bang.
 * <p>
 * Air does not blur the jump like ordinary sound (ISO 9613-1 would absorb its highs over a kilometre): the wave
 * itself sustains the steepness, and rise time increases as the jump weakens, on average inversely with the jump
 * (Pierce 1989). The value comes from sonic-boom measurements: 30–200 Pa rise over 1–5 ms (with turbulence;
 * relaxation theory gives 2–3 times less, Gionfriddo 1991), or ~0.17 Pa·s: 0.01 psi — 2.5 ms (dull thud),
 * 0.1 psi — 0.25 ms (sharp crack). What the hill and shadow zone absorbed — the 2 kHz high fraction
 * ({@link Outdoor#highs}) — is a one-pole low-pass with the same fraction at 2 kHz.
 * Hearing cannot hear below 20 Hz: a second-long positive phase is infrasound, felt as a push rather than heard;
 * a second-order Butterworth filter removes it so the track does not spend headroom on the inaudible. For a nuclear
 * wave, only one front is heard — a click lasting tens of milliseconds (50 ms after the peak, less than 2 % remains
 * for any phase); for a conventional blast, the phase is milliseconds (3 kg at 50 m — 7 ms, 450 kg at 1 km — 39 ms),
 * and the entire compression and rarefaction wave is audible. Both fit in the first {@link #TAIL} s after the front:
 * beyond that the track contains only filtered infrasound.
 */
public final class BlastPulse {
    /** Track rate, Hz: resolves the front to hundredths of a millisecond. */
    public static final int RATE = 44_100;
    /** Rise time (10–90 %) × pressure jump, s·Pa. */
    static final double RISE_PA_S = 0.17;
    /** Hearing cannot hear below this, Hz. */
    static final double HEARING_HZ = 20;
    /** Band at which {@link Outdoor} computes highs, Hz. */
    static final double HIGHS_HZ = 2000;
    /** Track duration after the front, s. */
    static final double TAIL = 0.25;
    /** Track peak: source gain restores loudness. */
    static final double PEAK = 0.98;

    private BlastPulse() {}

    /** Wave pressure (fraction of peak) {@code t} s after the front: (1 − t/t+)·e^(−t/t+), zero total impulse. */
    public static double friedlander(double t, double tPlus) {
        double x = Math.max(0, t) / tPlus;
        return (1 - x) * Math.exp(-x);
    }

    /** Front rise time (10–90 %) for a {@code pa} pascal jump, s. */
    public static double riseSeconds(double pa) {
        return RISE_PA_S / Math.max(pa, 1e-9);
    }

    /**
     * Impact track: mono, {@link #RATE}, peak {@link #PEAK}, front in the first milliseconds.
     *
     * @param pa    pressure jump at the ear, Pa
     * @param tPlus positive phase, s
     * @param highs fraction of 2 kHz highs that arrived ({@link Outdoor#highs}; 1 — path is open)
     */
    public static short[] render(double pa, double tPlus, double highs) {
        double rise = riseSeconds(pa);
        // Taylor front ½(1 + th(t/θ)): 10 to 90 % is 2·arth(0.8)·θ
        double theta = Math.max(0.5 / RATE, rise / (2 * 1.0986));
        double t0 = 4 * theta + 0.002;
        int n = (int) Math.ceil(RATE * (t0 + TAIL));
        double[] x = new double[n];
        for (int i = 0; i < n; i++) {
            double t = (double) i / RATE - t0;
            x[i] = 0.5 * (1 + Math.tanh(t / theta)) * friedlander(t, tPlus);
        }
        if (highs < 1) {
            double h = Math.max(1e-3, highs);
            lowpass(x, HIGHS_HZ * h / Math.sqrt(1 - h * h));
        }
        highpass(x, HEARING_HZ);
        // last quarter fades to silence: track ends without a click
        int fade = n / 4;
        for (int i = n - fade; i < n; i++) x[i] *= 0.5 * (1 + Math.cos(Math.PI * (i - (n - fade)) / fade));
        double peak = 0;
        for (double v : x) peak = Math.max(peak, Math.abs(v));
        short[] pcm = new short[n];
        if (peak <= 0) return pcm;
        for (int i = 0; i < n; i++) pcm[i] = (short) Math.round(x[i] / peak * PEAK * Short.MAX_VALUE);
        return pcm;
    }

    /** One-pole low-pass: −3 dB at frequency {@code fc}. */
    static void lowpass(double[] x, double fc) {
        double a = 1 - Math.exp(-2 * Math.PI * fc / RATE), y = 0;
        for (int i = 0; i < x.length; i++) {
            y += a * (x[i] - y);
            x[i] = y;
        }
    }

    /** Second-order high-pass Butterworth (bilinear transform). */
    static void highpass(double[] x, double fc) {
        double k = Math.tan(Math.PI * fc / RATE), q = Math.sqrt(0.5), norm = 1 / (1 + k / q + k * k);
        double b0 = norm, b1 = -2 * norm, b2 = norm, a1 = 2 * (k * k - 1) * norm, a2 = (1 - k / q + k * k) * norm;
        double x1 = 0, x2 = 0, y1 = 0, y2 = 0;
        for (int i = 0; i < x.length; i++) {
            double in = x[i], out = b0 * in + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2;
            x2 = x1;
            x1 = in;
            y2 = y1;
            y1 = out;
            x[i] = out;
        }
    }
}
