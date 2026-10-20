package ua.zentix.incoming.client.fx.smoke;

import org.junit.jupiter.api.Test;
import ua.zentix.incoming.client.fx.plume.Stability;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Шлейф над пожаром по Бриггсу: подъём на пути, конечный подъём в двух ветрах, устойчивый воздух и штиль. */
class FirePlumeTest {
    /**
     * 20 блоков огня: 3,5 МВт, конвекцией 2,45 МВт — F = g·Q/(π·c_p·ρ·T) ≈ 21,6 м⁴/с³. В нейтральном воздухе (D) конечный
     * подъём 21,425·F^¾/u: при 2 м/с — ~107 м, при 8 м/с — вчетверо ниже (~27 м); путь подъёма — 1,6·F^⅓·x^⅔/u.
     */
    @Test
    void briggsRiseInTwoWinds() {
        double f = 9.80665 * 0.7 * 20 * FirePlume.BLOCK_WATTS / (Math.PI * 1005 * 1.225 * 288.15);
        for (double u : new double[]{2, 8}) {
            FirePlume p = new FirePlume(20, u, Stability.D);
            assertEquals(f, p.flux, 1e-9 * f);
            assertEquals(21.425 * Math.pow(f, 0.75) / u, p.finalRise, 1e-9);
            double x = 0.25 * p.finalX;
            assertEquals(1.6 * Math.cbrt(f) * Math.pow(x, 2.0 / 3) / u, p.rise(x), 1e-9);
            assertEquals(p.finalRise, p.rise(10 * p.finalX), 1e-9);
            assertEquals(p.finalRise, p.rise(p.finalX), 1e-6 * p.finalRise, "подъём непрерывен на конечном");
        }
        assertEquals(4, new FirePlume(20, 2, Stability.D).finalRise / new FirePlume(20, 8, Stability.D).finalRise, 1e-9);
        assertEquals(26.8, new FirePlume(20, 8, Stability.D).finalRise, 0.5);
    }

    /** Ночью (F, N = 0,03) — 2,6·(F/(u·s))^⅓, но не выше штиля 5·F^¼·s^(−⅜); шлейф ниже дневного. */
    @Test
    void stableAirHoldsThePlumeLow() {
        FirePlume night = new FirePlume(20, 2, Stability.F), day = new FirePlume(20, 2, Stability.D);
        double s = Stability.brunt(Stability.F) * Stability.brunt(Stability.F);
        double expected = Math.min(2.6 * Math.cbrt(night.flux / (2 * s)), 5.0 * Math.pow(night.flux, 0.25) * Math.pow(s, -0.375));
        assertEquals(expected, night.finalRise, 1e-9);
        assertTrue(night.finalRise < day.finalRise, night.finalRise + " < " + day.finalRise);
        // штиль: ветер не меньше 1 м/с
        assertEquals(1, new FirePlume(20, 0, Stability.F).wind, 0);
    }

    /** Дым густой: шлейф 20 блоков огня виден сотни метров; сажа на метр — тепло × выход / теплота / ветер. */
    @Test
    void plumeIsSeenFar() {
        FirePlume p = new FirePlume(20, 3, Stability.D);
        assertEquals(20 * FirePlume.BLOCK_WATTS * 0.015 / 12.4e6 / 3, p.sootPerMetre(), 1e-12);
        double seen = p.visible(2000);
        assertTrue(seen > 300, "виден на " + seen + " м");
        assertTrue(p.tau(seen) >= 0.02 - 1e-6);
        // у очага горячо, выше — воздух
        assertTrue(p.excess(1) > 100 && p.excess(50) < 10, p.excess(1) + " " + p.excess(50));
    }
}
