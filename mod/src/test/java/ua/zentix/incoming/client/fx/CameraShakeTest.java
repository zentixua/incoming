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

    /** Наклон вбок — поворот вокруг горизонтали вперёд: глядя прямо вниз, взгляд уходит вбок курсом, а не креном. */
    @Test
    void sideLeanTurnsTheViewWhenLookingDown() {
        double[] t = tilt(1);
        double peak = Math.toDegrees(BodySway.peak(t, 0));
        for (double look : new double[]{0, 45, 90}) {
            CameraShake.reset();
            // взрыв справа: тело клонит влево
            CameraShake.sway(t, new Vec3(1, 0, 0));
            int top = 0;
            for (int i = 0; i < t.length; i++) if (t[i] > t[top]) top = i;
            for (int tick = 0; tick < top / (BodySway.RATE / 20); tick++) CameraShake.tick(true);
            double[] o = CameraShake.offsets((top % (BodySway.RATE / 20)) / (BodySway.RATE / 20.0), 0, look, 1);
            double b = Math.toRadians(look);
            assertEquals(-peak * Math.cos(b), o[2], 1e-9, "крен, взгляд вниз на " + look + "°");
            assertEquals(peak * Math.sin(b), o[0], 1e-9, "курс, взгляд вниз на " + look + "°");
            assertEquals(0, o[1], 1e-9);
        }
        CameraShake.reset();
    }

    /**
     * Дальше границы устойчивости камера не уходит: лицом к сильной волне — не больше 4,5° назад, спиной — не больше 8°
     * вперёд, боком — 8°, наискось — по эллипсу между ними; и до неё доходит. Тряска слабее в настройках — слабее
     * наклон, но граница та же.
     */
    @Test
    void nukeWindStopsAtTheBalanceLimit() {
        double[] t = tilt(5);
        assertEquals(-Math.toDegrees(BodySway.BACK), extreme(t, new Vec3(0, 0, -1))[0], 1e-9);
        assertEquals(Math.toDegrees(BodySway.FORWARD), extreme(t, new Vec3(0, 0, 1))[1], 1e-9);
        assertEquals(-Math.toDegrees(BodySway.SIDE), extreme(t, new Vec3(1, 0, 0))[2], 1e-9);
        // взрыв позади слева: вперёд и вправо поровну — на эллипсе
        CameraShake.reset();
        CameraShake.sway(t, new Vec3(-1, 0, 1));
        double most = 0;
        for (int tick = 0; tick * (BodySway.RATE / 20) < t.length; tick++) {
            double[] o = CameraShake.offsets(0, 0, 0, 1);
            most = Math.max(most, Math.hypot(o[1] / Math.toDegrees(BodySway.FORWARD), o[2] / Math.toDegrees(BodySway.SIDE)));
            CameraShake.tick(true);
        }
        assertEquals(1, most, 1e-9, "наискось — до эллипса и не дальше");
        // половина силы в настройках — до границы всё равно доходит, а слабая волна наклоняет вдвое меньше
        assertEquals(-Math.toDegrees(BodySway.BACK), extreme(t, new Vec3(0, 0, -1), 0.5)[0], 1e-9);
        double[] weak = tilt(1);
        assertEquals(0.5 * extreme(weak, new Vec3(0, 0, -1), 1)[0], extreme(weak, new Vec3(0, 0, -1), 0.5)[0], 1e-9);
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
                        double[] o = CameraShake.offsets(f / 10.0, yaw, 30, 1);
                        if (prev != null) most = Math.max(most, Math.sqrt(sq(o[0] - prev[0]) + sq(o[1] - prev[1]) + sq(o[2] - prev[2])));
                        prev = o;
                    }
                    CameraShake.tick(true);
                }
                assertTrue(most < 1, psi + " psi, курс " + yaw + ": " + most + "° за кадр");
                if (psi == 1) assertTrue(most * 200 < 30, "1 psi: " + most * 200 + " °/с");
                double[] o = CameraShake.offsets(0.5, yaw, 30, 1);
                assertEquals(0, Math.abs(o[0]) + Math.abs(o[1]) + Math.abs(o[2]), 1e-12, psi + " psi: тело выпрямилось, а камера нет");
            }
        }
        CameraShake.reset();
    }

    /** Вторая волна ложится поверх того, что осталось от первой: без скачка в миг прихода, дальше наклоны складываются. */
    @Test
    void wavesAdd() {
        double[] first = tilt(1), second = tilt(0.5);
        CameraShake.reset();
        CameraShake.sway(first, new Vec3(0, 0, 1));
        for (int i = 0; i < 10; i++) CameraShake.tick(true);
        double before = CameraShake.offsets(0, 0, 0, 1)[1];
        CameraShake.sway(second, new Vec3(0, 0, 1));
        assertEquals(before, CameraShake.offsets(0, 0, 0, 1)[1], 1e-12, "в миг прихода");
        for (int i = 0; i < 10; i++) CameraShake.tick(true);
        int step = BodySway.RATE / 20;
        assertEquals(Math.toDegrees(first[20 * step] + second[10 * step]), CameraShake.offsets(0, 0, 0, 1)[1], 1e-9);
        CameraShake.reset();
    }

    /** В воздухе (прыжок) тело на ноги не опирается: наклон сходит за четверть секунды и возвращается, когда встал. */
    @Test
    void noLeanInTheAir() {
        double[] t = tilt(1);
        CameraShake.reset();
        CameraShake.sway(t, new Vec3(0, 0, 1));
        for (int i = 0; i < 10; i++) CameraShake.tick(true);
        assertTrue(CameraShake.offsets(0, 0, 0, 1)[1] > 0.1);
        for (int i = 0; i < 6; i++) CameraShake.tick(false);
        assertEquals(0, CameraShake.offsets(0.5, 0, 0, 1)[1], 1e-12);
        for (int i = 0; i < 6; i++) CameraShake.tick(true);
        assertEquals(Math.toDegrees(t[22 * (BodySway.RATE / 20)]), CameraShake.offsets(0, 0, 0, 1)[1], 1e-9);
        CameraShake.reset();
    }

    /**
     * Наименьший и наибольший тангаж и наименьший крен, пока тело качается, при курсе 0 (на +z):
     * {мин. тангаж, макс. тангаж, мин. крен}.
     */
    private static double[] extreme(double[] tilt, Vec3 away) {
        return extreme(tilt, away, 1);
    }

    /** То же при силе тряски {@code k} из настроек. */
    private static double[] extreme(double[] tilt, Vec3 away, double k) {
        CameraShake.reset();
        CameraShake.sway(tilt, away);
        double[] e = {0, 0, 0};
        for (int tick = 0; tick * (BodySway.RATE / 20) < tilt.length; tick++) {
            for (int f = 0; f < 10; f++) {
                double[] o = CameraShake.offsets(f / 10.0, 0, 0, k);
                e[0] = Math.min(e[0], o[1]);
                e[1] = Math.max(e[1], o[1]);
                e[2] = Math.min(e[2], o[2]);
            }
            CameraShake.tick(true);
        }
        CameraShake.reset();
        return e;
    }

    private static double sq(double x) {
        return x * x;
    }
}
