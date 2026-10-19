package ua.zentix.incoming.client.fx;

import net.minecraft.world.phys.Vec3;
import org.joml.Quaterniond;
import org.joml.Vector3d;
import org.junit.jupiter.api.Test;
import ua.zentix.incoming.nuclear.model.BlastModel;
import ua.zentix.incoming.nuclear.model.BodySway;
import ua.zentix.incoming.nuclear.model.HobBlast;
import ua.zentix.incoming.nuclear.model.Yield;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CameraShakeTest {
    private static final int STEP = BodySway.RATE / 20;

    /**
     * Волна на земле, где давление {@code psi}, от воздушного подрыва {@code kt} на лучшей высоте: {скоростной напор вдоль
     * земли, Па; положительная фаза, с; давление фронта, Па}.
     */
    private static double[] wave(double psi, double kt) {
        double h = Yield.optimalBurstHeight(kt), g = HobBlast.rangeFor(psi, h, kt);
        return new double[]{1000 * BlastModel.dynamicPressureKpa(BlastModel.kpa(psi)) * HobBlast.alongGround(g, h, kt), HobBlast.positivePhaseSeconds(g, h, kt),
                1000 * BlastModel.kpa(psi)};
    }

    /** Волна {@code psi} от {@code kt} качнула игрока, что смотрит по курсу 0 (на +z); {@code away} — от взрыва к нему. */
    private static double[] sway(double psi, double kt, Vec3 away) {
        double[] w = wave(psi, kt);
        return CameraShake.sway(w[2], w[0], w[1], 0, away, 0);
    }

    /**
     * Ядерная волна наклоняет камеру по ветру от взрыва: взрыв впереди — запрокидывает, позади — клонит вниз, справа —
     * наклоняет влево, на наклон тела. Ветер дует вдоль земли от эпицентра: высота взрыва над игроком направление не
     * меняет (долю напора вдоль земли даёт сама волна, {@code Detonation.dynamicPressureKpa}).
     */
    @Test
    void nukeWindLeansAwayFromTheBurst() {
        CameraShake.reset();
        double peak = Math.toDegrees(BodySway.peak(sway(0.6, 2, new Vec3(0, 0, -1)), 0));
        assertTrue(peak > 0.5 && peak < Math.toDegrees(BodySway.BACK), "0,6 psi: " + peak);
        assertEquals(-peak, extreme(0.6, 2, new Vec3(0, 0, -1), 1)[0], 1e-6, "взрыв впереди — назад");
        assertEquals(peak, extreme(0.6, 2, new Vec3(0, 0, 1), 1)[1], 1e-6, "взрыв позади — вниз");
        assertEquals(-peak, extreme(0.6, 2, new Vec3(1, 0, 0), 1)[2], 1e-6, "взрыв справа — влево");
        assertEquals(-peak, extreme(0.6, 2, new Vec3(0, -1, -1), 1)[0], 1e-6, "над головой впереди — тоже назад");
    }

    /**
     * Наклон тела поворачивает камеру вокруг горизонтали мира поперёк наклона, как есть, при любом взгляде: курс, тангаж и
     * крен, которые отдаёт тряска, у {@code Camera.setRotation} дают ровно этот поворот. Глядя прямо вниз, наклон вбок
     * уводит взгляд вбок (голова — в одну сторону, взгляд вдоль тела — в другую), а не вертит картинку.
     */
    @Test
    void viewTurnsWithTheBody() {
        for (Vec3 away : new Vec3[]{new Vec3(1, 0, 0), new Vec3(-1, 0, 1), new Vec3(0.3, -0.5, -1)}) {
            for (double yaw : new double[]{0, 30, -135}) {
                for (double pitch : new double[]{-90, -45, 0, 30, 60, 85, 89.9, 90}) {
                    for (double roll : new double[]{0, 10}) {
                        CameraShake.reset();
                        double[] w = wave(1, 2), t = CameraShake.sway(w[2], w[0], w[1], 0, away, yaw);
                        int top = 0;
                        for (int i = 0; i < t.length; i++) if (t[i] > t[top]) top = i;
                        for (int tick = 0; tick < top / STEP; tick++) CameraShake.tick(true);
                        double[] o = CameraShake.offsets((top % STEP) / (double) STEP, yaw, pitch, roll, yaw, 1);
                        Vector3d[] got = camera(yaw + o[0], pitch + o[1], roll + o[2]), was = camera(yaw, pitch, roll);
                        double flat = Math.hypot(away.x, away.z);
                        Quaterniond lean = new Quaterniond().rotationAxis(t[top], away.z / flat, 0, -away.x / flat);
                        String what = "от взрыва " + away + ", курс " + yaw + ", тангаж " + pitch + ", крен " + roll;
                        assertEquals(0, angle(got[0], was[0].rotate(lean)), 1e-6, what + ": взгляд");
                        assertEquals(0, angle(got[1], was[1].rotate(lean)), 1e-6, what + ": верх");
                    }
                }
            }
        }
        // прямо вниз, взрыв справа: голову уносит влево, и взгляд вдоль тела уходит вправо (−x при курсе 0) на наклон тела
        CameraShake.reset();
        double[] t = sway(1, 2, new Vec3(1, 0, 0));
        int top = 0;
        for (int i = 0; i < t.length; i++) if (t[i] > t[top]) top = i;
        for (int tick = 0; tick < top / STEP; tick++) CameraShake.tick(true);
        double[] o = CameraShake.offsets((top % STEP) / (double) STEP, 0, 90, 0, 0, 1);
        Vector3d look = camera(o[0], 90 + o[1], o[2])[0];
        assertEquals(Math.toDegrees(t[top]), angle(look, new Vector3d(0, -1, 0)), 1e-6);
        assertTrue(look.x < 0, "взгляд вниз уходит вправо: " + look);
        CameraShake.reset();
    }

    /**
     * Дальше границы устойчивости камера не уходит: лицом к сильной волне — не больше 4,5° назад, спиной — не больше 8°
     * вперёд, боком — 8°, наискось — по эллипсу между ними; и до неё доходит. Две волны вместе — тоже не дальше. С края
     * тело выпрямляется, а не валится на другой край: у 3 psi назад к взрыву — меньше 2° (обратный ветер разрежения).
     * Тряска слабее в настройках — слабее наклон.
     */
    @Test
    void nukeWindStopsAtTheBalanceLimit() {
        assertEquals(-Math.toDegrees(BodySway.BACK), extreme(5, 15, new Vec3(0, 0, -1), 1)[0], 1e-6);
        assertEquals(Math.toDegrees(BodySway.FORWARD), extreme(5, 15, new Vec3(0, 0, 1), 1)[1], 1e-6);
        assertEquals(-Math.toDegrees(BodySway.SIDE), extreme(5, 15, new Vec3(1, 0, 0), 1)[2], 1e-6);
        double[] behind = extreme(3, 15, new Vec3(0, 0, 1), 1);
        assertEquals(Math.toDegrees(BodySway.FORWARD), behind[1], 1e-6);
        assertTrue(behind[0] > -2, "3 psi: назад " + behind[0]);
        // взрыв позади справа: наклон вперёд и влево — на эллипсе; две волны — тоже на нём
        for (int waves = 1; waves <= 2; waves++) {
            CameraShake.reset();
            Vec3 away = new Vec3(1, 0, 1);
            for (int i = 0; i < waves; i++) sway(5, 15, away);
            double edge = Math.toDegrees(CameraShake.edge(0, away.x / Math.sqrt(2), away.z / Math.sqrt(2))), most = 0;
            for (int tick = 0; tick < 20 * 20; tick++) {
                double[] o = CameraShake.offsets(0, 0, 0, 0, 0, 1);
                most = Math.max(most, angle(camera(o[0], o[1], o[2])[1], new Vector3d(0, 1, 0)) / edge);
                CameraShake.tick(true);
            }
            assertEquals(1, most, 1e-9, waves + " волн: наискось — до эллипса и не дальше");
        }
        // половина силы в настройках — половина наклона
        assertEquals(-0.5 * Math.toDegrees(BodySway.BACK), extreme(5, 15, new Vec3(0, 0, -1), 0.5)[0], 1e-6);
        assertEquals(0.5 * extreme(1, 2, new Vec3(0, 0, -1), 1)[0], extreme(1, 2, new Vec3(0, 0, -1), 0.5)[0], 1e-6);
    }

    /**
     * Без рывков: до 2 psi (у 15 кт — уже граница) при любом курсе, и глядя почти отвесно, за кадр в 1/10 тика камера
     * поворачивается меньше чем на полградуса, а у 1 psi — не быстрее 30°/с (тело качается за секунды); после того как
     * тело выпрямилось, поворота нет. Сильнее напор швыряет тело к границе за сотые доли секунды — так и камеру.
     */
    @Test
    void nukeWindDoesNotJerkAndEnds() {
        for (double kt : new double[]{2, 15}) {
            for (double psi : new double[]{0.5, 1, 2}) {
                for (double pitch : new double[]{30, 89.9}) {
                    for (double yaw = 0; yaw < 360; yaw += 45) {
                        CameraShake.reset();
                        double[] w = wave(psi, kt), t = CameraShake.sway(w[2], w[0], w[1], 0, new Vec3(0.3, -0.2, 1), yaw);
                        double most = 0;
                        Quaterniond prev = null;
                        for (int tick = 0; tick * STEP < t.length + 20; tick++) {
                            for (int f = 0; f < 10; f++) {
                                double[] o = CameraShake.offsets(f / 10.0, yaw, pitch, 0, yaw, 1);
                                Quaterniond q = rotation(yaw + o[0], pitch + o[1], o[2]);
                                if (prev != null) most = Math.max(most, Math.toDegrees(2 * Math.acos(Math.min(1, Math.abs(q.dot(prev))))));
                                prev = q;
                            }
                            CameraShake.tick(true);
                        }
                        String what = kt + " кт, " + psi + " psi, курс " + yaw + ", тангаж " + pitch;
                        assertTrue(most < 0.5, what + ": " + most + "° за кадр");
                        if (psi == 1) assertTrue(most * 200 < 30, what + ": " + most * 200 + " °/с");
                        double[] o = CameraShake.offsets(0.5, yaw, pitch, 0, yaw, 1);
                        assertEquals(0, Math.abs(o[0]) + Math.abs(o[1]) + Math.abs(o[2]), 1e-12, what + ": тело выпрямилось, а камера нет");
                    }
                }
            }
        }
        CameraShake.reset();
    }

    /**
     * Наклон — тела игрока: камера не на нём (камера снаряда) наклона не показывает; граница устойчивости — по тому, куда
     * смотрит тело, а не камера: вид спереди от третьего лица смотрит назад, а спиной к взрыву тело клонится вперёд до 8°.
     */
    @Test
    void leanIsTheBodys() {
        CameraShake.reset();
        sway(5, 15, new Vec3(0, 0, 1));
        double front = 0;
        for (int tick = 0; tick < 20 * 20; tick++) {
            double[] o = CameraShake.offsets(0.5, 0, 0, 0, Double.NaN, 1);
            assertEquals(0, Math.abs(o[0]) + Math.abs(o[1]) + Math.abs(o[2]), 1e-12, "камера не на игроке");
            o = CameraShake.offsets(0.5, 180, 0, 0, 0, 1);
            front = Math.max(front, angle(camera(180 + o[0], o[1], o[2])[1], new Vector3d(0, 1, 0)));
            CameraShake.tick(true);
        }
        assertEquals(Math.toDegrees(BodySway.FORWARD), front, 1e-6, "вид спереди: граница — тела");
        CameraShake.reset();
    }

    /** Вторая волна ложится поверх того, что осталось от первой: без скачка в миг прихода, дальше наклоны складываются. */
    @Test
    void wavesAdd() {
        CameraShake.reset();
        double[] first = sway(1, 2, new Vec3(0, 0, 1));
        for (int i = 0; i < 10; i++) CameraShake.tick(true);
        double before = CameraShake.offsets(0, 0, 0, 0, 0, 1)[1];
        double[] second = sway(0.5, 2, new Vec3(0, 0, 1));
        assertEquals(before, CameraShake.offsets(0, 0, 0, 0, 0, 1)[1], 1e-9, "в миг прихода");
        for (int i = 0; i < 10; i++) CameraShake.tick(true);
        assertEquals(Math.toDegrees(first[20 * STEP] + second[10 * STEP]), CameraShake.offsets(0, 0, 0, 0, 0, 1)[1], 1e-9);
        CameraShake.reset();
    }

    /**
     * Удар — с прихода фронта к телу, а не с тика, в котором его заметили: фронт прошёл тело полтика назад — в первом
     * кадре этого тика тело уже клонится на полтика наклона; с прихода — с самого начала (не с 0, не позже).
     */
    @Test
    void swayStartsWhenTheFrontPassed() {
        CameraShake.reset();
        double[] w = wave(1, 2), t = CameraShake.sway(w[2], w[0], w[1], 0.5, new Vec3(0, 0, 1), 0);
        double half = Math.toDegrees(t[STEP / 2] + (t[STEP / 2 + 1] - t[STEP / 2]) * (STEP / 2.0 - STEP / 2));
        assertEquals(half, CameraShake.offsets(0, 0, 0, 0, 0, 1)[1], 1e-9);
        assertTrue(half > 0, "полтика удара — уже наклон");
        CameraShake.reset();
    }

    /** В воздухе (прыжок) тело на ноги не опирается: наклон сходит за четверть секунды и возвращается, когда встал. */
    @Test
    void noLeanInTheAir() {
        CameraShake.reset();
        double[] t = sway(1, 2, new Vec3(0, 0, 1));
        for (int i = 0; i < 10; i++) CameraShake.tick(true);
        assertTrue(CameraShake.offsets(0, 0, 0, 0, 0, 1)[1] > 0.1);
        for (int i = 0; i < 6; i++) CameraShake.tick(false);
        assertEquals(0, CameraShake.offsets(0.5, 0, 0, 0, 0, 1)[1], 1e-12);
        for (int i = 0; i < 6; i++) CameraShake.tick(true);
        assertEquals(Math.toDegrees(t[22 * STEP]), CameraShake.offsets(0, 0, 0, 0, 0, 1)[1], 1e-9);
        CameraShake.reset();
    }

    /**
     * Волна {@code psi} от {@code kt} с силой тряски {@code k}: наименьший и наибольший тангаж и наименьший крен, пока
     * тело качается, при курсе 0 (на +z): {мин. тангаж, макс. тангаж, мин. крен}.
     */
    private static double[] extreme(double psi, double kt, Vec3 away, double k) {
        CameraShake.reset();
        double[] t = sway(psi, kt, away), e = {0, 0, 0};
        for (int tick = 0; tick * STEP < t.length; tick++) {
            for (int f = 0; f < 10; f++) {
                double[] o = CameraShake.offsets(f / 10.0, 0, 0, 0, 0, k);
                e[0] = Math.min(e[0], o[1]);
                e[1] = Math.max(e[1], o[1]);
                e[2] = Math.min(e[2], o[2]);
            }
            CameraShake.tick(true);
        }
        CameraShake.reset();
        return e;
    }

    /**
     * Поворот камеры, как его строит {@code Camera.setRotation}, — в double: во float у JOML курс около 0 (половина угла
     * около 90°, косинус — из синуса) сам по себе ошибается до трёх сотых градуса.
     */
    private static Quaterniond rotation(double yaw, double pitch, double roll) {
        return new Quaterniond().rotationYXZ(Math.PI - Math.toRadians(yaw), -Math.toRadians(pitch), -Math.toRadians(roll));
    }

    /** Камера: {вперёд, вверх}. */
    private static Vector3d[] camera(double yaw, double pitch, double roll) {
        Quaterniond q = rotation(yaw, pitch, roll);
        return new Vector3d[]{new Vector3d(0, 0, -1).rotate(q), new Vector3d(0, 1, 0).rotate(q)};
    }

    /** Угол между направлениями, °. */
    private static double angle(Vector3d a, Vector3d b) {
        return Math.toDegrees(Math.atan2(new Vector3d(a).cross(b).length(), a.dot(b)));
    }
}
