package ua.zentix.incoming.client.sound;

import ua.zentix.incoming.sound.Propagation;
import ua.zentix.incoming.sound.Spectrum;

import java.util.Arrays;

/**
 * Гул пожаров у уха — по закону источника, без Minecraft (проверяется юнит-тестом): каждый очаг — тепловыделение, Вт,
 * звуком уходит его доля {@link Sources#FLAME_EFFICIENCY} (шум турбулентного пламени, {@link Sources#flame}); очаги разных
 * мест некогерентны — мощности складываются. До уха — путь ISO 9613 ({@link Propagation}), громкость — {@link Loudness}.
 * <p>
 * Очагов у горящего города — десятки тысяч (клетки шторма и огонь в своих чанках), поэтому путь считается не по каждому:
 * очаги — в {@link #QUADRANTS} четверти вокруг уха (cos²/sin² между соседними, как у петель гула) и в кольца по
 * логарифму расстояния ({@link #RINGS_PER_OCTAVE} на удвоение). В кольце точно складывается разлёт — Σ P/(4πd²), а
 * воздух, земля и тень берутся на расстоянии, среднем по силе пришедшего: в кольце они меняются меньше чем на десятую
 * долю децибела на сотню метров у верхней октавы.
 */
public final class FireRoar {
    public static final int QUADRANTS = 4;
    /** Колец на удвоение расстояния. */
    static final int RINGS_PER_OCTAVE = 4;
    /** Кольца — от метра до 2¹⁶ м (65 км, дальше {@code Hearing.RANGE}). */
    static final int RINGS = 16 * RINGS_PER_OCTAVE + 1;
    /** Ближе — всё равно что в стольких метрах: очаг — не точка, а горящая грань у уха. */
    static final double NEAR = 1;

    /** Сумма в кольце: P/d² (Вт/м²·4π), то же × d и × высоту очага над ухом. */
    private final double[] power = new double[QUADRANTS * RINGS], dist = new double[QUADRANTS * RINGS], height = new double[QUADRANTS * RINGS];
    private final double[] bands = new double[Propagation.BANDS], ring = new double[Propagation.BANDS];
    private final double[] level = new double[QUADRANTS], margin = new double[QUADRANTS], distance = new double[QUADRANTS];

    public void clear() {
        Arrays.fill(power, 0);
        Arrays.fill(dist, 0);
        Arrays.fill(height, 0);
    }

    /** Очаг тепловыделением {@code watts}, Вт, на (dx, dy, dz) от уха, м. */
    public void add(double dx, double dy, double dz, double watts) {
        if (!(watts > 0)) return;
        double d = Math.max(NEAR, Math.sqrt(dx * dx + dy * dy + dz * dz));
        double p = Sources.FLAME_EFFICIENCY * watts / (d * d);
        int r = Math.min(RINGS - 1, (int) Math.floor(RINGS_PER_OCTAVE * Math.log(d) / Math.log(2)));
        double az = Math.atan2(dz, dx), sector = 2 * Math.PI / QUADRANTS;
        double u = (az < 0 ? az + 2 * Math.PI : az) / sector;
        int q0 = (int) Math.floor(u) % QUADRANTS, q1 = (q0 + 1) % QUADRANTS;
        double frac = u - Math.floor(u), c = Math.cos(frac * Math.PI / 2), s = Math.sin(frac * Math.PI / 2);
        put(q0 * RINGS + r, p * c * c, d, dy);
        put(q1 * RINGS + r, p * s * s, d, dy);
    }

    private void put(int k, double p, double d, double dy) {
        power[k] += p;
        dist[k] += p * d;
        height[k] += p * dy;
    }

    /**
     * Уровни у уха по четвертям для записи со спектром {@code shape} в погоду {@code weather}: A-уровень ({@link #level}),
     * запас над фоном в лучшей октаве ({@link #margin}) и расстояние, среднее по силе ({@link #distance}).
     */
    public void finish(Spectrum shape, Propagation.Weather weather) {
        for (int q = 0; q < QUADRANTS; q++) {
            Arrays.fill(bands, 0);
            double all = 0, far = 0;
            for (int r = 0; r < RINGS; r++) {
                int k = q * RINGS + r;
                if (power[k] <= 0) continue;
                double d = dist[k] / power[k], hs = Math.max(0, height[k] / power[k] + Propagation.EAR);
                // мощность, у которой на расстоянии d разлёт тот же, что сумма очагов кольца
                double lw = Sources.lw(power[k] * d * d);
                Propagation.level(lw, shape, Propagation.Path.over(d, hs, weather), ring);
                for (int b = 0; b < bands.length; b++) bands[b] += Math.pow(10, ring[b] / 10);
                all += power[k];
                far += dist[k];
            }
            if (all <= 0) {
                level[q] = Double.NEGATIVE_INFINITY;
                margin[q] = Double.NEGATIVE_INFINITY;
                distance[q] = 0;
                continue;
            }
            double sum = 0;
            for (int b = 0; b < bands.length; b++) {
                sum += bands[b];
                ring[b] = 10 * Math.log10(Math.max(bands[b], 1e-30));
            }
            level[q] = 10 * Math.log10(Math.max(sum, 1e-30));
            margin[q] = Propagation.margin(ring, weather);
            distance[q] = far / all;
        }
    }

    /** A-уровень у уха в четверти {@code q} после {@link #finish}, дБА. */
    public double level(int q) {
        return level[q];
    }

    public double margin(int q) {
        return margin[q];
    }

    /** Расстояние до пожаров четверти, среднее по силе, м. */
    public double distance(int q) {
        return distance[q];
    }

    /** Громкость в игре четверти {@code q} у файла громкостью {@code fileLufs}, потолок {@code ceilingLufs}. */
    public float volume(int q, double fileLufs, double ceilingLufs) {
        return Loudness.volume(Loudness.gain(level[q], margin[q], ceilingLufs), fileLufs);
    }
}
