package ua.zentix.incoming.client.fx.layer;

import org.junit.jupiter.api.Test;
import ua.zentix.incoming.client.ClientWeaponSpec;
import ua.zentix.incoming.client.fx.plume.Flame;
import ua.zentix.incoming.entity.FlightPhase;
import ua.zentix.incoming.strike.WeaponType;
import ua.zentix.incoming.util.Air;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Свет факела на мир отрезками и тени прохода {@link HeatPass}. */
class HeatPassTest {
    /** ∫ cos θ / d² по части отрезка над гранью — численно, мелкими шагами. */
    private static double numeric(double[] a, double[] u, double t0, double t1, double[] n, double w2) {
        int steps = 200_000;
        double sum = 0, dt = (t1 - t0) / steps;
        for (int i = 0; i < steps; i++) {
            double t = t0 + (i + 0.5) * dt;
            double x = a[0] + u[0] * t, y = a[1] + u[1] * t, z = a[2] + u[2] * t;
            double cos = n[0] * x + n[1] * y + n[2] * z;
            if (cos <= 0) continue;
            // та же поправка у оси, что у формулы: d² → d² + w²
            double d2 = x * x + y * y + z * z + w2;
            sum += cos * Math.pow(d2, -1.5) * dt;
        }
        return sum;
    }

    private static double[] unit(Random r) {
        double x = r.nextGaussian(), y = r.nextGaussian(), z = r.nextGaussian(), l = Math.sqrt(x * x + y * y + z * z);
        return new double[]{x / l, y / l, z / l};
    }

    /**
     * Первообразная прямого света отрезка ({@link HeatPass#segment}) — то же, что интеграл численно, при любом положении
     * грани: отрезок целиком над ней, частью под ней (струя в землю) и у самой оси.
     */
    @Test
    void segmentLightIsTheIntegralOverTheVisiblePart() {
        Random r = new Random(7);
        for (int i = 0; i < 200; i++) {
            double[] a = {r.nextGaussian() * 6, r.nextGaussian() * 6, r.nextGaussian() * 6}, u = unit(r), n = unit(r);
            double t0 = r.nextDouble() * 3, t1 = t0 + 0.2 + r.nextDouble() * 20, w2 = Math.pow(0.05 + r.nextDouble() * 0.5, 2);
            double exact = HeatPass.segment(a[0], a[1], a[2], u[0], u[1], u[2], t0, t1, n[0], n[1], n[2], w2);
            double num = numeric(a, u, t0, t1, n, w2);
            assertEquals(num, exact, 1e-4 * Math.max(num, 1e-3), "случай " + i);
        }
    }

    /**
     * Вдали струя — точка: свет на метр × длина × cos/d² — прежний шар той же площади сбоку ({@link Flame#lightRadius},
     * яркость {@link Flame#brightness}); вблизи — по частям, и пятно на земле под струёй не одно на её середине.
     */
    @Test
    void jetFarAwayIsTheBallOfTheSameArea() {
        Flame f = ClientWeaponSpec.of(WeaponType.MISSILE).airframe(false).far().stage(FlightPhase.BOOST).flame(Air.SEA);
        assertNotNull(f);
        double[] parts = HeatPass.parts(f);
        double core = f.core(), fl = Math.max(f.flame(), core), end = f.length();
        double[] lum = {f.coreLuminance(), f.flameLuminance(), f.tailLuminance()};
        double[][] span = {{0, core}, {0, fl}, {fl, end}};
        // струя вдоль x, грань лицом к ней в d по y
        double d = 2000, sum = 0;
        for (int k = 0; k < 3; k++) {
            double q = lum[k] * parts[k] / ((span[k][1] - span[k][0]) * Math.PI);
            sum += q * HeatPass.segment(-f.middle(), d, 0, 1, 0, 0, span[k][0], span[k][1], 0, 1, 0, 0);
        }
        double r = f.lightRadius(), ball = f.brightness() * r * r / (d * d);
        assertEquals(ball, sum, 1e-3 * ball, "вдали — шар той же площади");
        // у среза сопла на земле в 2 м под струёй светит ядро: свет там — не меньше, чем под серединой струи у шара
        double atNozzle = 0, h = 2;
        for (int k = 0; k < 3; k++) {
            double q = lum[k] * parts[k] / ((span[k][1] - span[k][0]) * Math.PI);
            atNozzle += q * HeatPass.segment(0, h, 0, 1, 0, 0, span[k][0], span[k][1], 0, 1, 0, 0);
        }
        double ballThere = f.brightness() * r * r / Math.max(f.middle() * f.middle() + h * h, r * r) * h / Math.hypot(f.middle(), h);
        assertTrue(atNozzle > ballThere, "у сопла по отрезку " + atNozzle + " против шара в середине " + ballThere);
    }

    /** Струя, ушедшая под грань, светит только частью над ней: грань под самым соплом струи в землю — не ярче, чем от одной надземной части. */
    @Test
    void partBelowTheFaceDoesNotLight() {
        // струя отвесно вниз из 3 м над землёй, длина 10 м: под землёй 7 м
        double whole = HeatPass.segment(1, 3, 0, 0, -1, 0, 0, 10, 0, 1, 0, 0.01);
        double aboveOnly = HeatPass.segment(1, 3, 0, 0, -1, 0, 0, 3, 0, 1, 0, 0.01);
        assertEquals(aboveOnly, whole, 1e-12);
        double[] t = HeatPass.above(1, 3, 0, 0, -1, 0, 0, 10, 0, 1, 0);
        assertEquals(3, t[1], 1e-12);
        assertEquals(0, HeatPass.segment(1, -3, 0, 0, -1, 0, 0, 10, 0, 1, 0, 0.01), "вся под гранью");
    }

    /**
     * Синтетический кадр: глаз в 2 м над землёй смотрит вдоль +x, пиксель — {@link #PIXEL} рад, мир — земля и ящики
     * блоков. Глубина — расстояние вдоль взгляда до мира у середины пикселя, куда попадает точка.
     */
    private static final double PIXEL = 1e-3;

    private record Box(double x0, double y0, double z0, double x1, double y1, double z1) {}

    private static HeatPass.Depth scene(Box... boxes) {
        return (x, y, z) -> {
            if (x <= 0) return Double.NaN;
            // пиксель точки и луч глаза через его середину
            double px = Math.floor(Math.atan2(z, x) / PIXEL) + 0.5, py = Math.floor(Math.atan2(y, x) / PIXEL) + 0.5;
            if (Math.abs(px * PIXEL) > 0.8 || Math.abs(py * PIXEL) > 0.6) return Double.NaN;
            double dx = 1, dy = Math.tan(py * PIXEL), dz = Math.tan(px * PIXEL);
            double best = dy < 0 ? -2 / dy : 1e9;
            for (Box b : boxes) {
                double tn = 0, tf = 1e9;
                double[] o = {0, 0, 0}, d = {dx, dy, dz}, lo = {b.x0, b.y0, b.z0}, hi = {b.x1, b.y1, b.z1};
                boolean miss = false;
                for (int k = 0; k < 3; k++) {
                    if (Math.abs(d[k]) < 1e-12) {
                        if (o[k] < lo[k] || o[k] > hi[k]) miss = true;
                        continue;
                    }
                    double a = (lo[k] - o[k]) / d[k], c = (hi[k] - o[k]) / d[k];
                    tn = Math.max(tn, Math.min(a, c));
                    tf = Math.min(tf, Math.max(a, c));
                }
                if (!miss && tn <= tf && tn > 0) best = Math.min(best, tn);
            }
            // вдоль взгляда (+x): t·dx
            return best;
        };
    }

    private static boolean shadowed(HeatPass.Depth depth, double[] p, double[] n, double[] q) {
        return HeatPass.shadowed(p[0], p[1], p[2], n[0], n[1], n[2], q[0], q[1], q[2], 1, 0, 0, PIXEL, 0.5, depth);
    }

    /**
     * Жалоба на свет за домом: огонь за домом не освещает землю перед ним с той стороны, откуда смотрит глаз — луч к огню
     * уходит в стену. Земля сбоку от дома, открытая огню, освещена; столб у глаза, мимо которого луч проходит позади, тени
     * не даёт; стена толщиной в блок (полый дом) — тоже заслон.
     */
    @Test
    void houseBetweenGroundAndFireCastsAShadow() {
        double[] up = {0, 1, 0};
        // огонь в 3 м над землёй за домом (x 60…68), земля перед домом в 50 м
        double[] fire = {80, 1, 0}, ground = {50, -2, 0};
        Box house = new Box(60, -2, -4, 68, 4, 4);
        assertTrue(shadowed(scene(house), ground, up, fire), "земля перед домом за ним от огня — в тени");
        assertFalse(shadowed(scene(), ground, up, fire), "без дома — свет");
        assertFalse(shadowed(scene(house), new double[]{50, -2, 12}, up, new double[]{80, 1, 12}), "сбоку от дома — свет");
        // полый дом: две стены в блок
        Box front = new Box(60, -2, -4, 61, 4, 4), back = new Box(67, -2, -4, 68, 4, 4);
        assertTrue(shadowed(scene(front, back), ground, up, fire), "полый дом — тоже заслон");
        // столб у глаза: луч от земли к огню проходит в метрах позади него
        Box pole = new Box(5, -2, -0.2, 5.4, 6, 0.2);
        assertFalse(shadowed(scene(pole), ground, up, fire), "столб перед глазом — мимо луча");
        // земля, видимая под скользящим углом, сама себя не затеняет
        assertFalse(shadowed(scene(), new double[]{120, -2, 3}, up, new double[]{100, 1, 0}), "своей тени нет");
    }
}
