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
        double[] parts = f.partAreas();
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

    /** Окно заслонов: ящики блоков (включительно-исключительно по блокам), земля — всё ниже y = groundY. */
    private record Box(int x0, int y0, int z0, int x1, int y1, int z1) {
        boolean has(int x, int y, int z) {
            return x >= x0 && x < x1 && y >= y0 && y < y1 && z >= z0 && z < z1;
        }
    }

    private static final int[] SIZE = {192, 96, 192};

    private static HeatPass.Cells world(int groundY, Box... boxes) {
        return (x, y, z) -> {
            if (y < groundY) return true;
            for (Box b : boxes) if (b.has(x, y, z)) return true;
            return false;
        };
    }

    private static boolean shadowed(HeatPass.Cells w, double[] p, double[] q) {
        return HeatPass.blocked(p[0], p[1], p[2], q[0], q[1], q[2], SIZE, w);
    }

    /**
     * Жалоба на свет за домом: дом между огнём и землёй оставляет землю в тени — с какой стороны ни смотри (по блокам, а
     * не по глубине кадра: ревью #399 — луч поперёк взгляда сквозь цельный дом проходил в 84 % случаев), и у цельного, и у
     * полого дома со стенами в блок, и у холма. Земля сбоку открыта; столб рядом с лучом тени не даёт; клетка самой
     * точки (грань, на которой она лежит) — не заслон; часть луча за окном — свободна.
     */
    @Test
    void blocksBetweenGroundAndFireCastAShadow() {
        int g = 40;
        // земля — верх блока y = 39 (y = 40 — воздух); точка на земле — чуть выше грани
        double[] ground = {100.5, 40.02, 40.5};
        double[] fire = {100.5, 43, 70.5};
        Box house = new Box(96, 40, 50, 105, 45, 59);
        assertTrue(shadowed(world(g, house), ground, fire), "цельный дом — тень");
        Box[] hollow = {new Box(96, 40, 50, 105, 45, 51), new Box(96, 40, 58, 105, 45, 59), new Box(96, 40, 50, 97, 45, 59),
                new Box(104, 40, 50, 105, 45, 59), new Box(96, 44, 50, 105, 45, 59)};
        assertTrue(shadowed(world(g, hollow), ground, fire), "полый дом со стенами в блок — тень");
        assertFalse(shadowed(world(g), ground, fire), "без дома — свет");
        assertFalse(shadowed(world(g, house), new double[]{120.5, 40.02, 40.5}, new double[]{120.5, 43, 70.5}), "сбоку от дома — свет");
        Box pole = new Box(102, 40, 55, 103, 50, 56);
        assertFalse(shadowed(world(g, pole), ground, fire), "столб в полутора блоках от луча — мимо");
        // холм: луч к огню за гребнем уходит в землю
        Box hill = new Box(80, 40, 52, 120, 50, 60);
        assertTrue(shadowed(world(g, hill), ground, fire), "за холмом — тень");
        // точка на грани стены дома (клетка её — сам блок стены): своя клетка не заслон, а свет перед стеной — открыт
        assertFalse(shadowed(world(g, house), new double[]{100.5, 42, 49.98}, new double[]{100.5, 43, 30}), "своя грань — не заслон");
        // за окном — свободно: заслон вне окна не известен
        assertFalse(shadowed((x, y, z) -> x >= SIZE[0] || x < 0, new double[]{100, 50, 100}, new double[]{300, 50, 100}), "за окном — свободно");
    }

    /**
     * Струя, ушедшая в землю, сквозь неё не светит (ревью #399): у старта ракеты из 2 м под 30° ось уходит в землю на 4 м,
     * дальше факела для прохода нет — концы частей кончаются там, свет на метр у них прежний.
     */
    @Test
    void jetEndsWhereItsAxisMeetsTheGround() {
        Flame f = ClientWeaponSpec.of(WeaponType.MISSILE).airframe(false).far().stage(FlightPhase.BOOST).flame(Air.SEA);
        assertNotNull(f);
        HeatPass.Frame frame = new HeatPass.Frame();
        double sin = Math.sin(Math.toRadians(30)), cos = Math.cos(Math.toRadians(30)), cut = 2 / sin;
        frame.jet(f.middle() * cos, 10 - f.middle() * sin, 0, cos, -sin, 0, f, 0, 2, cut);
        org.joml.Vector4f c = new org.joml.Vector4f();
        assertEquals(cut, frame.jetSize.getColumn(0, c).x, 1e-5, "конец струи — у земли");
        assertEquals(Math.min(f.flame(), cut), frame.jetAxis.getColumn(0, c).w, 1e-5, "пламя — до земли");
        assertEquals(Math.min(f.core(), cut), frame.jets.getColumn(0, c).w, 1e-5);
        HeatPass.Frame whole = new HeatPass.Frame();
        whole.jet(f.middle() * cos, 10 - f.middle() * sin, 0, cos, -sin, 0, f, 0, 2, Double.POSITIVE_INFINITY);
        assertEquals(frame.jetFlame.getColumn(0, c).x, whole.jetFlame.getColumn(0, new org.joml.Vector4f()).x, 1e-9, "свет на метр — прежний");
        HeatPass.Frame buried = new HeatPass.Frame();
        buried.jet(0, 0, 0, 0, -1, 0, f, 0, 2, 0);
        assertFalse(buried.lit(), "сопло в земле — света нет");
    }
}
