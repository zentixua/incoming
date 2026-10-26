package ua.zentix.incoming.client.sound;

import net.minecraft.util.RandomSource;

import java.util.Arrays;

/**
 * Sum of incoherent sources of one sound into {@link #N} loops around the ear (without Minecraft, covered by a unit
 * test): powers add, direction is cos²/sin² between neighbouring loops (weights sum to 1, like {@link Rumble} and
 * {@link FireRoar}), highs are power-weighted means. This plays crater fires ({@link CraterFire}): raids have hundreds,
 * engine has 247 channels for everything; one loop per fire exhausted them (300-Shahed raid: ~150 fire channels and
 * engine refusals every half-second as loops tried restarting).
 * <ul>
 * <li>Loop powers sum at the ear only if their sounds are incoherent for every start difference: each quarter has
 * its own {@code blast.fire} variant — a separate piece of a long fire recording ({@code fire()} in
 * {@code build_sounds.py}), not a shifted copy. One recording in two loops started in the same tick summed by
 * amplitude: a fire between them was 2 dB louder (review R8).</li>
 * <li>Audibility is decided by the sum ({@link #heard}), not each loop: an independently audible fire
 * ({@link CraterFire}) splits in half between two loops, so a threshold per loop would remove it entirely
 * depending on azimuth (review R9). All loops with a share of the audible sum play ({@link #plays}). The fire's
 * own audibility is at the ear, in the calibrated measure, not file volume (review R11).</li>
 * </ul>
 */
final class Quarters {
    /** Quarters around the ear (budget: direction precision vs channels; power preserved). */
    static final int N = 4;

    private final double[] power = new double[N], highs = new double[N];

    void clear() {
        Arrays.fill(power, 0);
        Arrays.fill(highs, 0);
    }

    /**
     * Source at {@code azimuth} from ear (atan2(dz, dx)), {@code volume} (like Minecraft sound, before filtering),
     * passing barrier with gain fraction {@code gain} and high fraction {@code highs}.
     */
    void add(double azimuth, double volume, double gain, double highs) {
        double p = volume * volume * gain * gain;
        if (!(p > 0)) return;
        double u = (azimuth < 0 ? azimuth + 2 * Math.PI : azimuth) / (2 * Math.PI / N);
        int q0 = (int) Math.floor(u) % N, q1 = (q0 + 1) % N;
        double frac = u - Math.floor(u), c = Math.cos(frac * Math.PI / 2), s = Math.sin(frac * Math.PI / 2);
        put(q0, p * c * c, highs);
        put(q1, p * s * s, highs);
    }

    private void put(int q, double p, double h) {
        power[q] += p;
        highs[q] += p * h;
    }

    /**
     * Loop {@code q} volume (0..1): square root of summed powers. Fire loops are built at game ceiling
     * ({@code FIRE_LUFS} in {@code build_sounds.py}; Minecraft cannot play louder than file): power 1 is ceiling.
     * Ceiling applies to all quarters' sum, like blast mix: above it, all quarters attenuate equally, preserving
     * directions; a per-loop ceiling would make a fire field between two loops 3 dB louder than on one loop's axis.
     */
    float volume(int q) {
        double sum = 0;
        for (double p : power) sum += p;
        return (float) Math.sqrt(power[q] / Math.max(1, sum));
    }

    /** High fraction of loop {@code q}: power-weighted mean. */
    float highs(int q) {
        return power[q] > 0 ? (float) (highs[q] / power[q]) : 1;
    }

    /**
     * Whether sum is audible: contains an audible source. Each source's audibility was decided at ear, in the
     * calibrated measure, before sum ({@link CraterFire#volume}); no file-volume threshold here: remixing changes
     * it and would change audible range (review R11).
     */
    boolean heard() {
        double sum = 0;
        for (double p : power) sum += p;
        return sum > 0;
    }

    /** Play loop {@code q}: sum audible and quarter has a share, even below threshold. */
    boolean plays(int q) {
        return power[q] > 0 && heard();
    }

    /**
     * Sound seed with which quarter {@code q}'s loop selects recording variant {@code q} among {@code variants}
     * equally weighted variants (Minecraft selects by sound {@code RandomSource}'s first weighted {@code nextInt}):
     * quarters use different variants while enough exist. Seeds scattered across all {@code long}:
     * {@code LegacyRandomSource}'s first value is nearly identical for small seeds, and among seeds 0–4095,
     * {@code nextInt(4)} gives neither 0 nor 1 — three of four loops played one file.
     */
    static long seed(int variants, int q) {
        int n = Math.max(1, variants);
        for (long i = 0; i < 4096; i++) {
            long seed = i * 0x9E3779B97F4A7C15L;
            if (RandomSource.create(seed).nextInt(n) == q % n) return seed;
        }
        throw new IllegalStateException("no seed picks variant " + q % n + " of " + n);
    }

    /** Loop {@code q} direction from ear: azimuth atan2(dz, dx). */
    static double azimuth(int q) {
        return 2 * Math.PI * q / N;
    }
}
