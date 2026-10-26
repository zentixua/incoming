package ua.zentix.incoming.sound;

import java.util.Arrays;

/**
 * Unweighted, normalized source octave spectrum (63 Hz–8 kHz, {@link Propagation#OCTAVES}): total power 0 dB.
 * A source of power L<sub>W</sub> emits level L<sub>W</sub> + {@link #z}(b) in octave b.
 * Mod recording spectra are {@link Spectra}: {@code tools/build_sounds.py} measures them from the loops played in game.
 */
public record Spectrum(double[] bands) {
    public Spectrum {
        if (bands.length != Propagation.BANDS) throw new IllegalArgumentException("octaves " + bands.length);
        double sum = 0;
        for (double b : bands) sum += Math.pow(10, b / 10);
        double norm = 10 * Math.log10(sum);
        bands = Arrays.stream(bands).map(b -> b - norm).toArray();
    }

    /** Spectrum from octave levels (any common offset): normalizes itself. */
    public static Spectrum of(double... relative) {
        return new Spectrum(relative.clone());
    }

    /** Octave b level relative to total, dB. */
    public double z(int b) {
        return bands[b];
    }

    /** A-weighted power level relative to unweighted level, dB (negative for low-frequency spectra). */
    public double aWeighting() {
        double sum = 0;
        for (int b = 0; b < bands.length; b++) sum += Math.pow(10, (bands[b] + Propagation.A_WEIGHT[b]) / 10);
        return 10 * Math.log10(sum);
    }
}
