package ua.zentix.incoming.client.fx;

import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;
import ua.zentix.incoming.nuclear.model.BlastModel;
import ua.zentix.incoming.nuclear.model.BodySway;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CameraShakeTest {
    /** Наклон тела ветром волны давлением {@code psi} с фазой 0,8 с. */
    private static double[] tilt(double psi) {
        return BodySway.tilt(1000 * BlastModel.dynamicPressureKpa(BlastModel.kpa(psi)), 0.8);
    }

    /**
     * Ядерная волна наклоняет камеру по ветру от взрыва: взрыв впереди — запрокидывает, позади — клонит вниз, справа —
     * наклоняет влево, на наклон тела; воздушный взрыв впереди под 45° — тоже назад, слабее (напор у земли дует вдоль неё).
     */
    @Test
    void nukeWindLeansAwayFromTheBurst() {
        double[] t = tilt(1);
        double peak = Math.toDegrees(BodySway.peak(t, 0));
        assertTrue(peak > 0.5 && peak < Math.toDegrees(BodySway.BACK), "1 psi: " + peak);
        assertEquals(-peak, extreme(t, new Vec3(0, 0, -1))[0], 0.05 * peak, "взрыв впереди — назад");
        assertEquals(peak, extreme(t, new Vec3(0, 0, 1))[1], 0.05 * peak, "взрыв позади — вниз");
        assertEquals(-peak, extreme(t, new Vec3(1, 0, 0))[2], 0.05 * peak, "взрыв справа — влево");
        double above = extreme(t, new Vec3(0, -1, -1))[0];
        assertTrue(above < -0.5 * peak && above > -0.8 * peak, "над головой впереди — назад слабее: " + above);
    }

    /**
     * Дальше границы устойчивости камера не уходит: лицом к сильной волне — не больше 4,5° назад, спиной — не больше 8°
     * вперёд, боком — 8°; и до неё доходит.
     */
    @Test
    void nukeWindStopsAtTheBalanceLimit() {
        double[] t = tilt(5);
        assertEquals(-Math.toDegrees(BodySway.BACK), extreme(t, new Vec3(0, 0, -1))[0], 1e-9);
        assertEquals(Math.toDegrees(BodySway.FORWARD), extreme(t, new Vec3(0, 0, 1))[1], 1e-9);
        assertEquals(-Math.toDegrees(BodySway.SIDE), extreme(t, new Vec3(1, 0, 0))[2], 1e-9);
    }

    /**
     * Без рывков: до 5 psi при любом курсе за кадр в 1/10 тика камера поворачивается меньше чем на градус, а у 1 psi —
     * не быстрее 30°/с (тело качается за секунды); после того как тело выпрямилось, поворота нет. Сильнее (20 psi —
     * смерть почти для всех) напор швыряет тело к границе устойчивости за сотые доли секунды — так и камеру.
     */
    @Test
    void nukeWindDoesNotJerkAndEnds() {
        for (double psi : new double[]{0.5, 1, 2, 5}) {
            double[] t = tilt(psi);
            for (double yaw = 0; yaw < 360; yaw += 45) {
                CameraShake.reset();
                CameraShake.sway(t, new Vec3(0.3, -0.2, 1));
                double most = 0;
                double[] prev = null;
                for (int tick = 0; tick * (BodySway.RATE / 20) < t.length + 20; tick++) {
                    for (int f = 0; f < 10; f++) {
                        double[] o = CameraShake.offsets(f / 10.0, yaw);
                        if (prev != null) most = Math.max(most, Math.sqrt(sq(o[0] - prev[0]) + sq(o[1] - prev[1]) + sq(o[2] - prev[2])));
                        prev = o;
                    }
                    CameraShake.tick();
                }
                assertTrue(most < 1, psi + " psi, курс " + yaw + ": " + most + "° за кадр");
                if (psi == 1) assertTrue(most * 200 < 30, "1 psi: " + most * 200 + " °/с");
                double[] o = CameraShake.offsets(0.5, yaw);
                assertEquals(0, Math.abs(o[0]) + Math.abs(o[1]) + Math.abs(o[2]), 1e-12, psi + " psi: тело выпрямилось, а камера нет");
            }
        }
        CameraShake.reset();
    }

    /** Новая волна слабее того, что осталось от прежней, наклон не сбивает; сильнее — заменяет. */
    @Test
    void strongerWaveTakesOver() {
        double[] strong = tilt(2), weak = tilt(0.5);
        CameraShake.reset();
        CameraShake.sway(strong, new Vec3(0, 0, 1));
        for (int i = 0; i < 10; i++) CameraShake.tick();
        double before = CameraShake.offsets(0, 0)[1];
        CameraShake.sway(weak, new Vec3(0, 0, 1));
        assertEquals(before, CameraShake.offsets(0, 0)[1], 1e-12);
        CameraShake.reset();
        CameraShake.sway(weak, new Vec3(0, 0, 1));
        for (int i = 0; i < 10; i++) CameraShake.tick();
        CameraShake.sway(strong, new Vec3(0, 0, 1));
        assertEquals(0, CameraShake.offsets(0, 0)[1], 1e-12, "сильная волна — с начала");
        CameraShake.reset();
    }

    /**
     * Наименьший и наибольший тангаж и наименьший крен, пока тело качается, при курсе 0 (на +z):
     * {мин. тангаж, макс. тангаж, мин. крен}.
     */
    private static double[] extreme(double[] tilt, Vec3 away) {
        CameraShake.reset();
        CameraShake.sway(tilt, away);
        double[] e = {0, 0, 0};
        for (int tick = 0; tick * (BodySway.RATE / 20) < tilt.length; tick++) {
            for (int f = 0; f < 10; f++) {
                double[] o = CameraShake.offsets(f / 10.0, 0);
                e[0] = Math.min(e[0], o[1]);
                e[1] = Math.max(e[1], o[1]);
                e[2] = Math.min(e[2], o[2]);
            }
            CameraShake.tick();
        }
        CameraShake.reset();
        return e;
    }

    private static double sq(double x) {
        return x * x;
    }
}
