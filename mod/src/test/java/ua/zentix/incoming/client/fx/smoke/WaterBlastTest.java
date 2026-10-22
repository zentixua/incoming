package ua.zentix.incoming.client.fx.smoke;

import org.junit.jupiter.api.Test;
import ua.zentix.incoming.nuclear.model.WaterModel;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Взрыв на воде: тот же закон размера, что у столба ядерки, баллистика по времени, водяная пыль после падения. */
class WaterBlastTest {
    /** Размер — кубический корень заряда от «Бейкера», как у {@link WaterModel}: заряд ×8 — столб ×2. */
    @Test
    void sizeFollowsTheNukeColumnLaw() {
        WaterBlast shahed = new WaterBlast(50, CloudModel.Air.STANDARD), big = new WaterBlast(400, CloudModel.Air.STANDARD);
        assertEquals(WaterModel.columnHeight(50e-6), shahed.height, 1e-9);
        assertEquals(WaterModel.columnRadius(50e-6), shahed.radius, 1e-9);
        assertEquals(2, big.height / shahed.height, 1e-9);
        // порядок величины: столб шахеда — метры–десятки метров, ниже столба 9 кг на лучшей глубине у Swisdak (23 м) ×√∛5,5
        assertTrue(shahed.height > 8 && shahed.height < 25, "столб " + shahed.height);
    }

    /** Время — баллистика: верх √(2H/g) после взрыва на высоте H, к 2√(2H/g) вода упала. */
    @Test
    void columnIsBallistic() {
        WaterBlast w = new WaterBlast(50, CloudModel.Air.STANDARD);
        assertEquals(Math.sqrt(2 * w.height / 9.80665), w.rise, 1e-9);
        assertEquals(w.height, w.top(w.rise), 1e-6 * w.height);
        assertEquals(0, w.top(2 * w.rise), 1e-9);
        assertEquals(1, w.aloft(w.rise), 0);
        assertEquals(0, w.aloft(2 * w.rise), 1e-9);
        assertTrue(w.top(0.5 * w.rise) > 0.7 * w.height, "быстрый взлёт, медленная вершина");
    }

    /** Клубы: столб и корона, пока вода в воздухе; потом — водяная пыль, пока её видно; брызги — капли, без сажи и пыли. */
    @Test
    void columnThenMist() {
        WaterBlast w = new WaterBlast(50, CloudModel.Air.STANDARD);
        List<double[]> got = new ArrayList<>();
        SmokeGrid.Sink sink = (x, y, z, ax, ay, az, sh, sv, soot, dust, water, r, g, b, k, rise) -> got.add(new double[]{y, soot, dust, water});
        assertTrue(w.blobs(0.5 * w.rise, sink, 0, 63, 0));
        assertEquals(2, got.size(), "столб и корона");
        for (double[] b : got) assertTrue(b[1] == 0 && b[2] == 0 && b[3] > 0);
        got.clear();
        assertTrue(w.blobs(3 * w.rise, sink, 0, 63, 0));
        assertEquals(WaterBlast.RING, got.size(), "водяная пыль — кольцо базисной волны");
        // кольцо растёт от стенки столба к пределу закона ядерки
        assertTrue(w.surgeRadius(0.1) < w.surgeRadius(3 * w.rise) && w.surgeRadius(1e3) <= w.radius + WaterModel.surgeReach(50e-6) + 1e-9);
        assertTrue(w.mistLife() > 2 * w.rise);
        got.clear();
        assertFalse(w.blobs(w.mistLife() + 1, sink, 0, 63, 0));
    }
}
