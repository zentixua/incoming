package ua.zentix.incoming.client.sound;

import org.junit.jupiter.api.Test;
import ua.zentix.incoming.sound.Propagation;
import ua.zentix.incoming.sound.Spectra;
import ua.zentix.incoming.sound.Spectrum;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Гул пожаров по закону источника ({@link FireRoar}): тепло → мощность звука → путь ISO 9613. */
class FireRoarTest {
    private static final Spectrum FIRE = Spectra.NUKE_FIRE;
    private static final Propagation.Weather NIGHT = Propagation.Weather.NIGHT;

    /** Вдвое больше горит — на 3 дБ громче (мощности очагов складываются). */
    @Test
    void doubleHeatThreeDecibels() {
        assertEquals(10 * Math.log10(2), one(200, 2e8) - one(200, 1e8), 1e-9);
    }

    /**
     * Уровень у уха — тот же, что у одного очага по {@link Propagation} напрямую: кольцо с одним очагом — сам очаг
     * (cos² у оси четверти — 1).
     */
    @Test
    void singleFireIsTheLaw() {
        double watts = 5e7, d = 300;
        double[] bands = new double[Propagation.BANDS];
        double want = Propagation.level(Sources.lw(Sources.FLAME_EFFICIENCY * watts), FIRE,
                Propagation.Path.over(d, Propagation.EAR, NIGHT), bands);
        // на оси четверти 0 (восток) — вся мощность в ней
        assertEquals(want, one(d, watts), 0.01);
    }

    /**
     * Кольца не врут: тысяча очагов от 20 м до 6 км вокруг уха — по четвертям в пределах 0,5 дБ от точной суммы по каждому
     * очагу (путь каждого — свой).
     */
    @Test
    void ringsMatchExactSum() {
        Random r = new Random(7);
        FireRoar roar = new FireRoar();
        double[][] exact = new double[FireRoar.QUADRANTS][Propagation.BANDS];
        double[] bands = new double[Propagation.BANDS];
        for (int i = 0; i < 1000; i++) {
            double d = 20 * Math.pow(300, r.nextDouble()), az = 2 * Math.PI * r.nextDouble(), w = 1e6 + 5e7 * r.nextDouble(), dy = 4 * r.nextDouble();
            double dx = d * Math.cos(az), dz = d * Math.sin(az);
            roar.add(dx, dy, dz, w);
            double d3 = Math.sqrt(d * d + dy * dy);
            Propagation.level(Sources.lw(Sources.FLAME_EFFICIENCY * w), FIRE, Propagation.Path.over(d3, dy + Propagation.EAR, NIGHT), bands);
            double u = (az / (Math.PI / 2)), frac = u - Math.floor(u);
            int q0 = (int) Math.floor(u) % 4, q1 = (q0 + 1) % 4;
            double c = Math.cos(frac * Math.PI / 2), s = Math.sin(frac * Math.PI / 2);
            for (int b = 0; b < bands.length; b++) {
                exact[q0][b] += c * c * Math.pow(10, bands[b] / 10);
                exact[q1][b] += s * s * Math.pow(10, bands[b] / 10);
            }
        }
        roar.finish(FIRE, NIGHT);
        for (int q = 0; q < FireRoar.QUADRANTS; q++) {
            double sum = 0;
            for (double e : exact[q]) sum += e;
            assertEquals(10 * Math.log10(sum), roar.level(q), 0.5, "четверть " + q);
        }
    }

    /** Дальше — тише; огонь со всех сторон — в каждой четверти; без огня — тишина. */
    @Test
    void distanceAndSides() {
        assertTrue(one(100, 1e8) - one(200, 1e8) > 6 - 0.2, "разлёт: −6 дБ на удвоение и воздух");
        FireRoar roar = new FireRoar();
        roar.finish(FIRE, NIGHT);
        for (int q = 0; q < FireRoar.QUADRANTS; q++) assertEquals(0, roar.volume(q, Spectra.NUKE_FIRE_LUFS, Loudness.ENGINE_LUFS));
        roar.clear();
        for (int q = 0; q < 4; q++) roar.add(500 * Math.cos(q * Math.PI / 2), 0, 500 * Math.sin(q * Math.PI / 2), 1e8);
        roar.finish(FIRE, NIGHT);
        for (int q = 0; q < FireRoar.QUADRANTS; q++) assertTrue(roar.volume(q, Spectra.NUKE_FIRE_LUFS, Loudness.ENGINE_LUFS) > 0, "четверть " + q);
    }

    /** A-уровень одного очага {@code watts} на востоке в {@code d} м. */
    private static double one(double d, double watts) {
        FireRoar roar = new FireRoar();
        roar.add(d, 0, 0, watts);
        roar.finish(FIRE, NIGHT);
        return roar.level(0);
    }
}
