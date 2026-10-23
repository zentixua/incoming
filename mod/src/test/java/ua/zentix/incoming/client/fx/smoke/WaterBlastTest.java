package ua.zentix.incoming.client.fx.smoke;

import org.junit.jupiter.api.Test;
import ua.zentix.incoming.strike.WeaponType;

import java.util.ArrayList;
import java.util.List;

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
     * Класс жалобы «удар в воду без столба»: у каждого оружия удар о воду даёт султан высотой закона — таблица Swisdak
     * (H/W^⅓ = 29,7 у глади) без запаса безопасности 1,4, — и его клубы брызг стоят на этой высоте в вершине и видны
     * (толща клуба поперёк больше порога).
     */
    @Test
    void everyWeaponThrowsAColumnOfTheLawHeight() {
        for (WeaponType wt : WeaponType.values()) {
            double kg = wt.spec().charge();
            if (kg <= 0) continue;
            WaterBlast w = new WaterBlast(kg, 0, DEEP, CloudModel.Air.STANDARD);
            double law = 29.7 * Math.cbrt(kg) / 1.4;
            assertEquals(law, w.height, 1e-9 * law, wt.name());
            // вершина столба — в √(2H/g), там же клубы самой быстрой струи
            assertEquals(Math.sqrt(2 * law / 9.80665), w.rise, 1e-6, wt.name());
            double top = topBlob(w, w.rise);
            assertEquals(law, top, 0.02 * law, wt.name() + ": клубы столба на высоте закона");
            List<Double> taus = new ArrayList<>();
            w.blobs(w.rise, (x, y, z, ax, ay, az, sh, sv, soot, dust, water, r, g, b, k, rise) -> {
                if (y > 0.9 * law) taus.add(CloudModel.DROP_K * water / (2 * Math.PI * sh * sv));
            }, 0, 0, 0);
            assertFalse(taus.isEmpty(), wt.name());
            for (double tau : taus) assertTrue(tau > 10 * CloudModel.SEEN, wt.name() + ": верх столба видно, толща " + tau);
        }
        // порядок величины у шахеда: десятки метров (у 500 кг на 30 м пример отчёта — 56 м по таблице)
        WaterBlast shahed = new WaterBlast(50, 0, DEEP, CloudModel.Air.STANDARD);
        assertTrue(shahed.height > 60 && shahed.height < 100, "столб шахеда " + shahed.height);
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
     * Струи — баллистика: в вершине столб шире всего на радиус закона (крайняя струя — на H·sin2θ = R), падают
     * завесой — к 2·подъёма вода в воде, брызг в воздухе нет; медленные струи падают раньше.
     */
    @Test
    void jetsFlyAndFall() {
        WaterBlast w = new WaterBlast(50, 0, DEEP, CloudModel.Air.STANDARD);
        double[] wide = {0};
        // струи — выше кольца водяной пыли (оно у воды, на высоте своей σ)
        w.blobs(w.rise * Math.cos(w.cone), (x, y, z, ax, ay, az, sh, sv, soot, dust, water, r, g, b, k, rise) -> {
            if (y > 0.5 * w.height) wide[0] = Math.max(wide[0], Math.hypot(x, z));
        }, 0, 0, 0);
        assertEquals(w.radius, wide[0], 0.02 * w.radius, "крайняя струя в вершине — на радиусе столба");
        assertEquals(1, w.aloft(0.5 * w.rise), 0);
        assertTrue(w.aloft(1.2 * w.rise) < 1 && w.aloft(1.2 * w.rise) > 0, "завеса падает по частям");
        assertEquals(0, w.aloft(2 * w.rise + 1e-6), 0);
        assertTrue(topBlob(w, 2 * w.rise + 1e-6) < 0.1 * w.height, "столба нет — вода упала, у воды только водяная пыль");
        // упала вода — до двух радиусов от середины
        assertEquals(2 * w.radius, w.fallen(2 * w.rise), 0.02 * w.radius);
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

    /** Пена: у полости — сразу, дальше — когда туда упала вода; тает. Волна на мелкой воде медленнее, чем на глубокой. */
    @Test
    void foamAndWaveOnTheWater() {
        WaterBlast w = new WaterBlast(50, 0, DEEP, CloudModel.Air.STANDARD);
        assertEquals(1, w.foam(0, 0.01), 0.01);
        assertEquals(0, w.foam(1.5 * w.radius, w.rise), 0, "завеса ещё не упала");
        assertTrue(w.foam(1.5 * w.radius, 2 * w.rise) > 0.5, "упала — пена");
        assertTrue(w.foam(1.5 * w.radius, 2 * w.rise + 60) < 0.05, "через минуту — почти нет");
        WaterBlast pond = new WaterBlast(50, 0, 1, CloudModel.Air.STANDARD);
        assertEquals(Math.sqrt(9.80665), pond.waveSpeed(), 1e-9);
        assertTrue(pond.waveSpeed() < w.waveSpeed());
        assertTrue(w.waveRadius(10) > w.waveRadius(1));
    }
}
