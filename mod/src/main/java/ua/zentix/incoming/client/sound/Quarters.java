package ua.zentix.incoming.client.sound;

import java.util.Arrays;

/**
 * Сумма некогерентных источников одной записи по {@link #N} петлям вокруг уха (без Minecraft, проверяется юнит-тестом):
 * мощности складываются, направление — cos²/sin² между двумя соседними петлями (сумма весов 1, как у {@link Rumble} и
 * {@link FireRoar}), верха — средние по мощности. Так звучат пожары в воронках ({@link CraterFire}): их в налёте сотни, а
 * каналов у движка — 247 на всё; по петле на пожар их не хватало (налёт из 300 шахедов: ~150 каналов пожаров и отказы
 * движка каждые полсекунды, пока те петли пытались встать заново).
 */
final class Quarters {
    static final int N = 4;

    private final double[] power = new double[N], highs = new double[N];

    void clear() {
        Arrays.fill(power, 0);
        Arrays.fill(highs, 0);
    }

    /**
     * Источник с азимута {@code azimuth} от уха (atan2(dz, dx)) громкостью {@code volume} (как у звука Minecraft, до
     * фильтра), что проходит преграду долей громкости {@code gain} и верхов {@code highs}.
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

    /** Громкость петли {@code q} (0..1): корень суммы мощностей. */
    float volume(int q) {
        return (float) Math.min(1, Math.sqrt(power[q]));
    }

    /** Доля верхов петли {@code q}: средняя по мощности. */
    float highs(int q) {
        return power[q] > 0 ? (float) (highs[q] / power[q]) : 1;
    }

    /** Направление петли {@code q} от уха: азимут atan2(dz, dx). */
    static double azimuth(int q) {
        return 2 * Math.PI * q / N;
    }
}
