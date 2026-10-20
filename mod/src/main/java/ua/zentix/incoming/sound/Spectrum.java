package ua.zentix.incoming.sound;

import java.util.Arrays;

/**
 * Октавный спектр источника (63 Гц – 8 кГц, {@link Propagation#OCTAVES}) без взвешивания, нормированный: сумма по
 * мощности — 0 дБ. Источник мощностью L<sub>W</sub> излучает в октаве b уровень L<sub>W</sub> + {@link #z}(b).
 * Спектры записей мода — {@link Spectra}: их меряет {@code tools/build_sounds.py} по петлям, которые звучат в игре.
 */
public record Spectrum(double[] bands) {
    public Spectrum {
        if (bands.length != Propagation.BANDS) throw new IllegalArgumentException("октав " + bands.length);
        double sum = 0;
        for (double b : bands) sum += Math.pow(10, b / 10);
        double norm = 10 * Math.log10(sum);
        bands = Arrays.stream(bands).map(b -> b - norm).toArray();
    }

    /** Спектр по уровням октав (любой общий сдвиг): нормируется сам. */
    public static Spectrum of(double... relative) {
        return new Spectrum(relative.clone());
    }

    /** Уровень октавы b против всего, дБ. */
    public double z(int b) {
        return bands[b];
    }

    /** На сколько A-уровень мощности ниже уровня без взвешивания, дБ (≤ 0). */
    public double aWeighting() {
        double sum = 0;
        for (int b = 0; b < bands.length; b++) sum += Math.pow(10, (bands[b] + Propagation.A_WEIGHT[b]) / 10);
        return 10 * Math.log10(sum);
    }
}
