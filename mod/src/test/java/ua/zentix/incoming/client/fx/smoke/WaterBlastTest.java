package ua.zentix.incoming.client.fx.smoke;

import org.junit.jupiter.api.Test;
import ua.zentix.incoming.strike.WeaponType;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Взрыв на воде: столб по таблице Swisdak (заряд и глубина), баллистика струй, падение завесой, водяная пыль, пена и
 * волна на воде.
 */
class WaterBlastTest {
    private static final double DEEP = Double.POSITIVE_INFINITY;

    /** Верх брызг (центр самого высокого клуба) в возрасте t, м. */
    private static double topBlob(WaterBlast w, double t) {
        double[] top = {0};
        w.blobs(t, (x, y, z, ax, ay, az, sh, sv, soot, dust, water, r, g, b, k, rise) -> top[0] = Math.max(top[0], y), 0, 0, 0);
        return top[0];
    }

    /**
     * Клубы струй в возрасте t (без плоского кольца водяной пыли) — точками: отрезок струи — 64 гаусса его сечения вдоль
     * оси; место, σh, σv, ослабление (м²).
     */
    private static List<double[]> spray(WaterBlast w, double t) {
        List<double[]> p = new ArrayList<>();
        w.blobs(t, (x, y, z, ax, ay, az, sh, sv, soot, dust, water, r, g, b, k, rise) -> {
            // струи — круглые отрезки; плоское кольцо водяной пыли — свой закон (радиус базисной волны)
            if (sh != sv) return;
            int n = 64;
            for (int i = 0; i < n; i++) {
                double f = (i + 0.5) / n * 2 - 1;
                p.add(new double[]{x + ax * f, y + ay * f, z + az * f, sh, sv, CloudModel.DROP_K * water / n});
            }
        }, 0, 0, 0);
        return p;
    }

    /** Толща брызг по горизонтальному лучу вдоль z на высоте y в стороне x от оси (интеграл гауссов вдоль луча). */
    private static double sight(List<double[]> p, double x, double y) {
        double tau = 0;
        for (double[] q : p) {
            double dx = x - q[0], dy = y - q[1];
            tau += q[5] / (2 * Math.PI * q[3] * q[4]) * Math.exp(-dx * dx / (2 * q[3] * q[3]) - dy * dy / (2 * q[4] * q[4]));
        }
        return tau;
    }

    /** The original spatial grid, including its floating-point step accumulation. */
    private static double[] grid(double end, double step) {
        List<Double> points = new ArrayList<>();
        for (double p = 0; p < end; p += step) points.add(p);
        return points.stream().mapToDouble(Double::doubleValue).toArray();
    }

    /**
     * The same 64-point quadrature as sight(), with exp(-dx²/2σh²) cached for the whole x grid. Points at the same
     * height and vertical width share their y factor, including jets at different azimuths. All terms are positive:
     * the sum of each group's maximum to the right bounds every ray there, so it can safely skip invisible rows.
     */
    private static final class SightGrid {
        private record Vertical(double y, double sigma) {}

        private final double[] x;
        private final Vertical[] groups;
        private final double[][] horizontal, maximum;

        SightGrid(List<double[]> points, double[] x) {
            this.x = x;
            Map<Vertical, double[]> sums = new LinkedHashMap<>();
            for (double[] q : points) {
                double[] row = sums.computeIfAbsent(new Vertical(q[1], q[4]), key -> new double[x.length]);
                double weight = q[5] / (2 * Math.PI * q[3] * q[4]);
                for (int i = 0; i < x.length; i++) {
                    double dx = x[i] - q[0];
                    row[i] += weight * Math.exp(-dx * dx / (2 * q[3] * q[3]));
                }
            }
            groups = sums.keySet().toArray(Vertical[]::new);
            horizontal = sums.values().toArray(double[][]::new);
            maximum = new double[groups.length][x.length];
            for (int g = 0; g < groups.length; g++) {
                double high = 0;
                for (int i = x.length - 1; i >= 0; i--) maximum[g][i] = high = Math.max(high, horizontal[g][i]);
            }
        }

        double[] vertical(double y) {
            double[] weights = new double[groups.length];
            for (int g = 0; g < groups.length; g++) {
                double dy = y - groups[g].y();
                weights[g] = Math.exp(-dy * dy / (2 * groups[g].sigma() * groups[g].sigma()));
            }
            return weights;
        }

        double sight(int i, double[] vertical) {
            double tau = 0;
            for (int g = 0; g < groups.length; g++) tau += horizontal[g][i] * vertical[g];
            return tau;
        }

        int rightmost(double y, int first) {
            if (first == x.length) return -1;
            double[] weights = vertical(y);
            double bound = 0;
            for (int g = 0; g < groups.length; g++) bound += maximum[g][first] * weights[g];
            if (bound < CloudModel.SEEN) return -1;
            // A mixture can have separate lobes: scan from the outside, never assume monotonicity for a bisection.
            for (int i = x.length - 1; i >= first; i--) {
                if (sight(i, weights) >= CloudModel.SEEN) return i;
            }
            return -1;
        }
    }

    /** Check the cached, factored sum against the old independent ray integral, including faint edges and landing. */
    @Test
    void cachedSightMatchesTheDirectQuadrature() {
        for (WeaponType wt : WeaponType.values()) {
            if (wt.spec().charge() <= 0) continue;
            WaterBlast w = new WaterBlast(wt.spec().charge(), 0, DEEP, CloudModel.Air.STANDARD);
            double[] x = {0, 0.5 * w.radius, 0.95 * w.radius, w.radius, 1.5 * w.radius};
            for (double age : new double[]{0.025, 0.5, 1, 1.5, 1.975, 2}) {
                List<double[]> points = spray(w, age * w.rise);
                SightGrid cached = new SightGrid(points, x);
                for (double y : new double[]{0, 0.1 * w.height, 0.5 * w.height, 0.98 * w.height, w.height, 1.5 * w.height}) {
                    double[] vertical = cached.vertical(y);
                    int rightmost = -1;
                    for (int i = 0; i < x.length; i++) {
                        double direct = sight(points, x[i], y);
                        assertEquals(direct, cached.sight(i, vertical), Math.max(1e-14, 1e-12 * direct), wt.name());
                        if (direct >= CloudModel.SEEN) rightmost = i;
                    }
                    assertEquals(rightmost, cached.rightmost(y, 0), wt.name());
                    assertEquals(-1, cached.rightmost(y, rightmost + 1), wt.name());
                }
            }
        }
    }

    /**
     * Класс жалобы «удар в воду без столба» и его обратной стороны — «столб пеленой во весь кадр» (финальный проход 3.7.0:
     * залп «Града» по воде у берега закрывал кадр ровной серой заливкой): H и R таблицы Swisdak — видимый султан, и на них
     * лежит его видимый край — где толща по лучу взгляда падает до порога видимости ({@link CloudModel#SEEN}), а не середины
     * струй. Прежде середины стояли на H и R, а густая струя видна ещё на 3–4 σ дальше: столб «Града» выходил втрое шире
     * закона, и камера в 30 м от залпа оказывалась внутри. R — наибольший радиус за всё событие, а дальше всего брызги
     * уходят, падая (ревью #445: с радиусом по вершине завеса на спаде уходила до 1,41 R). У каждого оружия: видимый
     * верх — H (−3…0 %), видимый радиус струй за весь полёт до 2·подъёма на любой высоте — R (−8…0 %).
     */
    @Test
    void everyWeaponThrowsAColumnOfTheLawSize() {
        for (WeaponType wt : WeaponType.values()) {
            double kg = wt.spec().charge();
            if (kg <= 0) continue;
            WaterBlast w = new WaterBlast(kg, 0, DEEP, CloudModel.Air.STANDARD);
            double law = 29.7 * Math.cbrt(kg) / 1.4, wide = 8.3 * Math.cbrt(kg) / 1.3;
            assertEquals(law, w.height, 1e-9 * law, wt.name());
            assertEquals(wide, w.radius, 1e-9 * wide, wt.name());
            double[] xs = grid(2 * wide, wide / 150), ys = grid(1.5 * law, law / 300);
            List<Double> times = new ArrayList<>();
            for (double t = w.rise / 40; t <= 2 * w.rise; t += w.rise / 40) {
                times.add(t);
            }
            // Visit the apex and outer-jet landing first, then every remaining original time sample. Finding the
            // extrema early lets subsequent rows skip rays that cannot increase either measured dimension.
            times.sort(Comparator.comparingDouble(t -> Math.min(Math.abs(t / w.rise - 1), Math.abs(t / w.rise - 2 * Math.cos(w.cone)))));
            double top = 0;
            int sideIndex = -1;
            for (double t : times) {
                SightGrid rays = new SightGrid(spray(w, t), xs);
                for (int iy = ys.length - 1; iy >= 0; iy--) {
                    double y = ys[iy];
                    int edge = rays.rightmost(y, y > top ? 0 : sideIndex + 1);
                    if (edge < 0) continue;
                    top = Math.max(top, y);
                    sideIndex = Math.max(sideIndex, edge);
                }
            }
            double side = sideIndex < 0 ? 0 : xs[sideIndex];
            assertTrue(top <= law && top >= 0.97 * law, wt.name() + ": видимый верх " + top + " против " + law);
            assertTrue(side <= wide && side >= 0.92 * wide, wt.name() + ": видимый радиус за полёт " + side + " против " + wide);
        }
        // порядок величины у шахеда: десятки метров (у 500 кг на 30 м пример отчёта — 56 м по таблице)
        WaterBlast shahed = new WaterBlast(50, 0, DEEP, CloudModel.Air.STANDARD);
        assertTrue(shahed.height > 60 && shahed.height < 100, "столб шахеда " + shahed.height);
    }

    /**
     * Струя — отрезок круглых клубов, а не вытянутый гаусс: вода одного направления, выброшенная в один миг с разной
     * скоростью, лежит на прямой. Прежде струя была гауссом с σ по вертикали до пяти σ поперёк (чтобы закрыть щель между
     * пятью скоростями), и слой рисовал его растянутым клубом — плоская полоса с прямыми боками (кадры 6 и 20 с залпа). Ни
     * одного вытянутого клуба брызг ни в каком возрасте; вода в струях — та, что ещё в воздухе.
     */
    @Test
    void jetsAreRoundSegments() {
        for (WeaponType wt : WeaponType.values()) {
            double kg = wt.spec().charge();
            if (kg <= 0) continue;
            WaterBlast w = new WaterBlast(kg, 0, DEEP, CloudModel.Air.STANDARD);
            for (double t = 0.05; t < w.mistLife(); t *= 1.3) {
                double time = t;
                double[] water = {0};
                w.blobs(t, (x, y, z, ax, ay, az, sh, sv, soot, dust, wet, r, g, b, k, rise) -> {
                    if (sh == sv) water[0] += wet;
                    else assertTrue(sh > 3 * sv, wt.name() + " в " + time + " с: вытянутый клуб брызг σh " + sh + ", σv " + sv);
                }, 0, 0, 0);
                assertEquals(WaterBlast.asGridDrops(w.water) * w.aloft(t), water[0], 1e-6 * WaterBlast.asGridDrops(w.water), wt.name());
            }
        }
    }

    /** Таблица — как в отчёте: пример гл. 14 (500 кг на 30 м: радиус 61,5 м, высота 56 м — до запаса). */
    @Test
    void tableMatchesTheReportExample() {
        double scaled = 30 / Math.pow(500, 0.25);
        assertEquals(7.75, WaterBlast.table(WaterBlast.SCALED_R, scaled), 0.03);
        assertEquals(7.05, WaterBlast.table(WaterBlast.SCALED_H, scaled), 0.05);
        WaterBlast w = new WaterBlast(500, 30, DEEP, CloudModel.Air.STANDARD);
        assertEquals(56.0 / 1.4, w.height, 0.5);
    }

    /** Заряд ×8 — столб ×2 (подобие по кубическому корню при той же приведённой глубине — у глади). */
    @Test
    void sizeScalesWithTheCubeRoot() {
        WaterBlast a = new WaterBlast(50, 0, DEEP, CloudModel.Air.STANDARD), b = new WaterBlast(400, 0, DEEP, CloudModel.Air.STANDARD);
        assertEquals(2, b.height / a.height, 1e-9);
        assertEquals(2, b.radius / a.radius, 1e-9);
    }

    /** Глубже лучшей глубины столб ниже: на 9 м/кг^¼ — 5,9 против 29,7 у глади. */
    @Test
    void deepChargeThrowsLess() {
        double kg = 50, deep = 9 * Math.pow(kg, 0.25);
        assertEquals(5.9 / 29.7, new WaterBlast(kg, deep, DEEP, CloudModel.Air.STANDARD).height / new WaterBlast(kg, 0, DEEP, CloudModel.Air.STANDARD).height,
                1e-9);
    }

    /**
     * Струи — баллистика: быстрый конец крайней струи в вершине — в H'·sin2θ от оси, падает в 2H'·sin2θ — внутри R (с
     * ореолом — R); к 2·подъёма вода в воде, брызг в воздухе нет; медленные струи падают раньше.
     */
    @Test
    void jetsFlyAndFall() {
        WaterBlast w = new WaterBlast(50, 0, DEEP, CloudModel.Air.STANDARD);
        double[] wide = {0};
        // середина отрезка крайней струи в её вершине — в стороне H'·sin2θ·(1 + доля медленной)/2: место линейно по скорости
        w.blobs(w.rise * Math.cos(w.cone), (x, y, z, ax, ay, az, sh, sv, soot, dust, water, r, g, b, k, rise) -> {
            if (sh == sv) wide[0] = Math.max(wide[0], Math.hypot(x + ax, z + az));
        }, 0, 0, 0);
        double apex = w.speed * w.speed * Math.sin(2 * w.cone) / (2 * 9.80665);
        assertEquals(apex, wide[0], 0.02 * w.radius, "быстрый конец крайней струи в вершине — в H'·sin2θ");
        assertEquals(1, w.aloft(0.5 * w.rise), 0);
        assertTrue(w.aloft(1.2 * w.rise) < 1 && w.aloft(1.2 * w.rise) > 0, "завеса падает по частям");
        assertEquals(0, w.aloft(2 * w.rise + 1e-6), 0);
        assertTrue(topBlob(w, 2 * w.rise + 1e-6) < 0.1 * w.height, "столба нет — вода упала, у воды только водяная пыль");
        // упала вода — до 2H'·sin2θ от середины: внутри R, с ореолом падающей струи — на R
        assertEquals(2 * apex, w.fallen(2 * w.rise), 0.02 * w.radius);
        assertTrue(w.fallen(2 * w.rise) < w.radius && w.fallen(2 * w.rise) > 0.4 * w.radius, "середины падают в " + w.fallen(2 * w.rise));
    }

    /** Водяная пыль: кольцо базисной волны после падения, видно секунды–десятки секунд, потом брызг нет. */
    @Test
    void mistAfterTheColumn() {
        WaterBlast w = new WaterBlast(50, 0, DEEP, CloudModel.Air.STANDARD);
        double t = 2 * w.rise;
        List<Double> taus = new ArrayList<>();
        w.blobs(t, (x, y, z, ax, ay, az, sh, sv, soot, dust, water, r, g, b, k, rise) -> taus.add(CloudModel.DROP_K * water / (2 * Math.PI * sh * sv)), 0, 0,
                0);
        assertEquals(WaterBlast.RING, taus.size(), "после падения — только кольцо водяной пыли");
        assertTrue(taus.getFirst() > 3 * CloudModel.SEEN, "кольцо водяной пыли видно: толща " + taus.getFirst());
        double life = w.mistLife() - t;
        assertTrue(life > 3 && life < 120, "водяная пыль после падения столба " + life + " с");
        assertFalse(w.blobs(w.mistLife() + 1, (x, y, z, ax, ay, az, sh, sv, soot, dust, water, r, g, b, k, rise) -> {}, 0, 0, 0));
    }

    /**
     * Водяная пыль оседает из тонкого слоя: капли 0,1 мм падают со скоростью 0,3 м/с, а вихри у глади их не держат, пока
     * число Рауза выше порога взвеси (над водой при ветре 6 м/с P ≈ 3,6). Прежде слой рос вверх по Бриггсу, как газ, — до
     * 28 м за 20 с (над камерой залпа), а капли терял как слой высотой 2,5 м: туман вставал стеной и держался. Теперь при
     * таком ветре видимый верх слоя — не выше базисной волны всё время, а в воздухе остаётся e^(−v·t/h), h — толща слоя у
     * глади (√(2πe)·σv для клуба с серединой на σv); в сильный ветер (P < 2,5) вихри поднимают слой.
     */
    @Test
    void mistSettlesFromAThinLayer() {
        CloudModel.Air breeze = new CloudModel.Air(0.012, 0, -6, 0.14, 2, 1), gale = new CloudModel.Air(0.012, 0, -20, 0.14, 2, 1);
        double rouse = WaterBlast.mistSettling() / (WaterBlast.KARMAN * Math.sqrt(WaterBlast.DRAG_WATER) * 6);
        assertTrue(rouse > 3 && rouse < 4.5, "число Рауза при 6 м/с " + rouse);
        WaterBlast w = new WaterBlast(6.4, 0.5, 4, breeze);
        assertEquals(0, w.suspended(), 0);
        for (double since = 0.25; since < w.mistLife() - w.landing(); since *= 1.5) {
            double t = w.landing() + since;
            double[] top = {0};
            w.blobs(t, (x, y, z, ax, ay, az, sh, sv, soot, dust, water, r, g, b, k, rise) -> {
                if (sh > 3 * sv) top[0] = Math.max(top[0], y + WaterBlast.visible(CloudModel.DROP_K * water / (2 * Math.PI * sh * sv)) * sv);
            }, 0, 0, 0);
            assertTrue(top[0] <= 1.001 * w.surgeTop(), "через " + since + " с видимый верх водяной пыли " + top[0] + " выше волны " + w.surgeTop());
        }
        double h = Math.sqrt(2 * Math.PI * Math.E) * w.mistSigmaV(0);
        assertEquals(Math.exp(-WaterBlast.mistSettling() * 10 / h), w.mistLeft(10), 1e-9);
        assertTrue(h > 0.7 * w.surgeTop() && h < 1.1 * w.surgeTop(), "толща слоя " + h + " против высоты волны " + w.surgeTop());
        WaterBlast windy = new WaterBlast(6.4, 0.5, 4, gale);
        assertTrue(windy.suspended() > 0, "ветер 20 м/с держит капли");
        assertTrue(windy.mistSigmaV(20) > 2 * windy.mistSigmaV(0), "и слой растёт вверх");
    }

    /** Пена: у полости — сразу, дальше — когда туда упала вода; тает. Волна: предел мелкой воды и dω/dk при конечной глубине. */
    @Test
    void foamAndWaveOnTheWater() {
        WaterBlast w = new WaterBlast(50, 0, DEEP, CloudModel.Air.STANDARD);
        assertEquals(1, w.foam(0, 0.01), 0.01);
        // завеса ложится до 2H'·sin2θ — дальности середин крайних струй, за ней — по сечению струи
        double reach = w.fallen(2 * w.rise), fall = reach + 0.5 * w.jet;
        assertTrue(fall > w.radius / 2, "завеса ложится за полостью");
        assertEquals(0, w.foam(fall, w.rise), 0, "завеса ещё не упала");
        assertTrue(w.foam(fall, 2 * w.rise) > 0.5, "упала — пена");
        assertTrue(w.foam(fall, 2 * w.rise + 60) < 0.05, "через минуту — почти нет");
        assertTrue(w.foam(reach + w.jet, 2 * w.rise) > 0.3 && w.foam(reach + w.jet, 2 * w.rise) < 0.7, "за дальностью середин — по сечению струи");
        assertEquals(0, w.foam(reach + 1.01 * w.halo * w.jet, 2 * w.rise), 0, "за видимым краем завесы пены нет");
        WaterBlast pond = new WaterBlast(50, 0, 1, CloudModel.Air.STANDARD);
        assertEquals(Math.sqrt(9.80665), pond.waveSpeed(), 0.01 * Math.sqrt(9.80665));
        assertTrue(pond.waveSpeed() < w.waveSpeed());
        // Независимая сверка: численная производная дисперсии, не ещё одна запись формулы групповой скорости.
        double k = 2 * Math.PI / w.waveLength(), dk = k * 1e-5;
        for (double depth : new double[]{0.01, 1, 4, 20, 1000}) {
            WaterBlast finite = new WaterBlast(50, 0, depth, CloudModel.Air.STANDARD);
            double hi = Math.sqrt(9.80665 * (k + dk) * Math.tanh((k + dk) * depth));
            double lo = Math.sqrt(9.80665 * (k - dk) * Math.tanh((k - dk) * depth));
            assertEquals((hi - lo) / (2 * dk), finite.waveSpeed(), 1e-7, "глубина " + depth);
        }
        assertTrue(w.waveRadius(10) > w.waveRadius(1));
    }
}
