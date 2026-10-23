package ua.zentix.incoming.client.fx.layer;

import org.junit.jupiter.api.Test;
import ua.zentix.incoming.client.ClientWeaponSpec;
import ua.zentix.incoming.client.fx.BlastSource;
import ua.zentix.incoming.client.fx.plume.Flame;
import ua.zentix.incoming.client.fx.smoke.CloudModel;
import ua.zentix.incoming.client.fx.smoke.FirePlume;
import ua.zentix.incoming.entity.FlightPhase;
import ua.zentix.incoming.strike.WeaponType;
import ua.zentix.incoming.util.Air;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Дрожание воздуха над огнём, огненным шаром и в струе сопла ({@link HeatPass}): на экране видно там, где его видит глаз
 * (жалоба FX 23.10: «дрожания нет нигде» — было 0,01–0,1 пикселя), и не сильнее того, что видит глаз.
 */
class HazeTest {
    /** Пиксель при поле зрения 70° по вертикали и 1080 строках, рад. */
    static final double PIXEL = Math.toRadians(70) / 1080;
    private static final CloudModel DRONE = new CloudModel(BlastSource.of(WeaponType.DRONE, false), CloudModel.Air.STANDARD);

    static HeatPass.Frame frame(double pixel) {
        HeatPass.Frame f = new HeatPass.Frame();
        f.begin();
        f.pixel = pixel;
        return f;
    }

    /** Сдвиг самого заметного столба кадра, пиксели (СКО по оси). */
    static double shift(HeatPass.Frame f) {
        double most = 0;
        for (double s : f.hotShift) most = Math.max(most, s);
        return most;
    }

    static Flame booster() {
        Flame f = ClientWeaponSpec.of(WeaponType.MISSILE).airframe(false).far().stage(FlightPhase.BOOST).flame(Air.SEA);
        assertNotNull(f);
        return f;
    }

    /**
     * При обычном поле зрения огонь Minecraft и шар шахеда в 20–100 блоках сбоку дрожат на пиксель (СКО ≥ 0,5; размах —
     * вдвое-втрое), струя ускорителя ракеты, ушедшей на 100 блоков вверх за 2 с, — на доли пикселя (газ быстрый, глаз
     * сливает его вихри), и ничто — не на «космические» пиксели.
     */
    @Test
    void fireBallAndJetShimmerOnScreen() {
        FirePlume fire = new FirePlume(6, FirePlume.CALM, 3);
        Flame boost = booster();
        for (double d : new double[]{20, 50, 100}) {
            HeatPass.Frame f = frame(PIXEL);
            f.plume(d, -1.6, 0, fire, 1);
            HeatPass.Frame b = frame(PIXEL);
            double t = 1, cr = DRONE.radius(t);
            b.hot(d, Math.max(0, DRONE.height(t) - cr) - 1.6, 0, 2 * cr, cr, DRONE.kelvin(t), DRONE.rise(t));
            HeatPass.Frame j = frame(PIXEL);
            j.exhaust(d, 100, 0, 0, -1, 0, boost, 100, 2, 100);
            // огонь и шар — заметный пиксель; газ струи быстрый, глаз сливает его вихри — доли пикселя
            double[] least = {0.5, 0.5, 0.15};
            HeatPass.Frame[] all = {f, b, j};
            for (int k = 0; k < 3; k++) {
                double s = shift(all[k]);
                assertTrue(s >= least[k] && s <= 4, "в " + d + " блоках, " + k + ": " + s + " px");
            }
        }
    }

    /**
     * Подача — только в долях того, что различает глаз: при обычном пикселе сдвиг на экране — угол / острота зрения (усиление
     * пиксель/острота, не больше), в бинокль (пиксель мельче остроты) — угол / пиксель, как есть.
     */
    @Test
    void presentationIsTheEyesResolutionOnly() {
        double dn = HeatPass.dn(600, HeatPass.TURBULENCE), len = 4, eddy = 0.4, d = 30, speed = 3;
        double tele = HeatPass.ACUITY / 8;
        double angle = HeatPass.hazeAngle(dn, len, eddy, HeatPass.aperture(d, tele)) * HeatPass.hazeSeen(eddy, speed);
        assertEquals(angle / tele, HeatPass.hazeShift(dn, len, eddy, d, tele, speed), 1e-9);
        double eye = HeatPass.hazeAngle(dn, len, eddy, HeatPass.aperture(d, HeatPass.ACUITY)) * HeatPass.hazeSeen(eddy, speed);
        double onScreen = HeatPass.hazeShift(dn, len, eddy, d, PIXEL, speed);
        assertEquals(eye / HeatPass.ACUITY, onScreen, 1e-9);
        assertTrue(onScreen * PIXEL / eye <= PIXEL / HeatPass.ACUITY + 1e-9);
    }

    /**
     * Закон: сильнее всего дрожит газ при T = 2T₀ (δn ∝ (T₀/T)(1 − T₀/T)); длиннее путь — сильнее (√L); дальше газ (пятно,
     * что различает глаз, крупнее) — не сильнее; быстрые вихри глаз сливает.
     */
    @Test
    void lawIsMonotonic() {
        double t0 = CloudModel.T_AIR_K, peak = HeatPass.dn(2 * t0, 1);
        for (double k = 300; k < 3000; k += 50) assertTrue(HeatPass.dn(k, 1) <= peak + 1e-15, k + " K");
        double dn = 2e-5, prev = 0;
        for (double len = 0.5; len < 50; len *= 1.5) {
            double a = HeatPass.hazeAngle(dn, len, 0.4, 0.004);
            assertTrue(a > prev);
            prev = a;
        }
        prev = Double.POSITIVE_INFINITY;
        for (double d = 1; d < 5000; d *= 1.3) {
            double s = HeatPass.hazeShift(dn, 4, 0.4, d, PIXEL, 3);
            assertTrue(s <= prev + 1e-12, d + " блоков");
            prev = s;
        }
        assertEquals(1, HeatPass.hazeSeen(0.4, 3), 1e-12);
        assertTrue(HeatPass.hazeSeen(0.02, 2000) < 0.1);
    }

    /** Без горячего газа столба нет: газ холоднее воздуха + COOL, факел без скорости газа. */
    @Test
    void nothingWithoutHotGas() {
        HeatPass.Frame f = frame(PIXEL);
        f.hot(20, 0, 0, 10, 3, CloudModel.T_AIR_K + HeatPass.COOL - 1, 2);
        Flame b = booster();
        f.exhaust(20, 1.5, 0, 0, -1, 0, new Flame(b.core(), b.flame(), b.length(), b.width(), b.diamonds(), b.coreLuminance(),
                b.flameLuminance(), b.tailLuminance(), b.coreColor(), b.flameColor(), b.tailColor(), b.temperature(), b.flameTemperature(),
                b.emissivity(), b.sheath(), b.middle(), b.tailMiddle(), 0), Double.POSITIVE_INFINITY, 10, 100);
        assertEquals(0, shift(f));
        // струя, что ушла в землю у среза, — не дрожит; на поджиге газа ещё нет
        f.exhaust(20, 1.5, 0, 0, -1, 0, b, 0, 10, 100);
        f.exhaust(20, 1.5, 0, 0, -1, 0, b, Double.POSITIVE_INFINITY, 0, 0);
        assertEquals(0, shift(f));
    }

    /**
     * Горячий газ струи — не дальше, чем дошёл с поджига: за t секунд в ядре — со скоростью среза, дальше x² растёт
     * линейно (скорость на оси ∝ 1/x); столб кадра — не длиннее этого и пути снаряда с места поджига.
     */
    @Test
    void jetGasGoesNoFurtherThanItGot() {
        Flame b = booster();
        double inCore = b.core() / b.speed();
        assertEquals(b.speed() * inCore / 2, b.gasReach(inCore / 2), 1e-9);
        double prev = b.core();
        for (double t = inCore * 1.01; t < 30; t *= 1.5) {
            double x = b.gasReach(t), dt = 1e-6, u = (b.gasReach(t + dt) - x) / dt;
            assertEquals(b.speed() * b.core() / x, u, 1e-3 * u, t + " с");
            assertTrue(x > prev);
            prev = x;
        }
        for (double t : new double[]{0.1, 1, 3}) {
            HeatPass.Frame f = frame(PIXEL);
            f.exhaust(50, 100, 0, 0, -1, 0, b, Double.POSITIVE_INFINITY, t, 20);
            assertTrue(f.shape.m00() <= 20 + b.gasReach(t) + 1e-3, t + " с: " + f.shape.m00());
        }
    }

    /** Луч в усечённом конусе ({@link HeatPass#coneSpan}) — то же, что выборка точек луча по самому конусу. */
    @Test
    void coneSpanIsWhereTheRayIsInside() {
        java.util.Random rnd = new java.util.Random(11);
        for (int i = 0; i < 2000; i++) {
            double[] a = unit(rnd), d = unit(rnd);
            if (i % 3 == 0) d = unit(a, rnd, 0.02);
            double[] base = {rnd.nextGaussian() * 20, rnd.nextGaussian() * 20, rnd.nextGaussian() * 20};
            double len = 1 + rnd.nextDouble() * 80, r0 = 0.1 + rnd.nextDouble() * 3, g = rnd.nextDouble() * 0.3;
            double c = dot(d, a), ba = dot(base, a);
            double t0 = 0, t1 = 300;
            if (Math.abs(c) > 1e-5) {
                t0 = Math.max(t0, Math.min(ba / c, (ba + len) / c));
                t1 = Math.min(t1, Math.max(ba / c, (ba + len) / c));
            } else if (ba > 0 || ba + len < 0) {
                continue;
            }
            if (t1 <= t0) continue;
            double[] dp = new double[3], bp = new double[3];
            for (int k = 0; k < 3; k++) {
                dp[k] = d[k] - a[k] * c;
                bp[k] = base[k] - a[k] * ba;
            }
            double R0 = r0 - g * ba, G = g * c;
            double[] span = HeatPass.coneSpan(dot(dp, dp) - G * G, dot(dp, bp) + R0 * G, dot(bp, bp) - R0 * R0, t0, t1);
            // выборка: точки луча в конусе
            double lo = Double.NaN, hi = Double.NaN;
            int n = 20000;
            for (int k = 0; k <= n; k++) {
                double t = t0 + (t1 - t0) * k / n, u = t * c - ba, r = r0 + g * u;
                double px = t * d[0] - base[0] - a[0] * u, py = t * d[1] - base[1] - a[1] * u, pz = t * d[2] - base[2] - a[2] * u;
                if (u >= 0 && u <= len && px * px + py * py + pz * pz < r * r) {
                    if (Double.isNaN(lo)) lo = t;
                    hi = t;
                }
            }
            double step = 2 * (t1 - t0) / n;
            if (Double.isNaN(lo)) {
                assertTrue(span[1] - span[0] <= step, "случай " + i + ": " + span[0] + ".." + span[1]);
            } else {
                assertEquals(lo, span[0], step, "случай " + i);
                assertEquals(hi, span[1], step, "случай " + i);
            }
        }
    }

    private static double dot(double[] p, double[] q) {
        return p[0] * q[0] + p[1] * q[1] + p[2] * q[2];
    }

    private static double[] unit(java.util.Random r) {
        double x = r.nextGaussian(), y = r.nextGaussian(), z = r.nextGaussian(), l = Math.sqrt(x * x + y * y + z * z);
        return new double[]{x / l, y / l, z / l};
    }

    /** Почти вдоль a (взгляд вдоль струи), разброс spread. */
    private static double[] unit(double[] a, java.util.Random r, double spread) {
        double x = a[0] + r.nextGaussian() * spread, y = a[1] + r.nextGaussian() * spread, z = a[2] + r.nextGaussian() * spread;
        double l = Math.sqrt(x * x + y * y + z * z), s = r.nextBoolean() ? 1 : -1;
        return new double[]{s * x / l, s * y / l, s * z / l};
    }
}
