package ua.zentix.incoming.client.fx;

import net.minecraft.client.Minecraft;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.ViewportEvent;
import ua.zentix.incoming.IncomingConfig;
import ua.zentix.incoming.nuclear.model.BodySway;

/**
 * Тряска камеры без сдвига прицела: крутится только камера (курс, тангаж, крен), взгляд игрока остаётся на месте —
 * тряска самого взгляда сбивала бы прицел.
 * <p>
 * Глаз видит скорость поворота: размах A° на частоте f — до 2π·f·A °/с (на каждом кадре — её доля). Поэтому толчки
 * грунта у обычных взрывов медленные, как у тела: голову качает само тело (основной тон ниже ~6 Гц), а частую дрожь
 * земли оно до глаз почти не доносит. Размахи меняются без скачков: скачок на кадр — тот же рывок.
 * <p>
 * Ядерная волна камеру не трясёт, а наклоняет: ветер за фронтом толкает стоящего, и тело отклоняется по ветру и
 * выпрямляется ({@link BodySway} — маятник с равновесием); голова — с телом. Дальше границы устойчивости человек шагает
 * или падает — камера стоит на ней.
 */
public final class CameraShake {
    /** Удар взрывной волны: сколько тиков ещё трясёт. */
    private static int shake;
    /** Толчки грунта (подземный взрыв, бурение): медленнее и мягче. */
    private static int quake;
    private static long ticks;
    private static final double[] STILL = new double[0];
    /** Ядерная волна: наклон тела по ветру, рад ({@link BodySway#RATE} отсчётов в секунду), с тика {@link #swayFrom}. */
    private static double[] sway = STILL;
    private static long swayFrom;
    /** Куда дует ветер волны: от взрыва вдоль земли (x, z, до 1 — под самым взрывом в сторону не дует). */
    private static double pushX, pushZ;

    private CameraShake() {}

    public static void blast(int ticks) {
        shake = Math.max(shake, ticks);
    }

    public static void quake(int ticks) {
        quake = Math.max(quake, ticks);
    }

    /**
     * Ветер ядерной волны качнул тело ({@link BodySway#tilt}); {@code away} — от взрыва к игроку. Новый наклон заменяет
     * прежний, если сильнее того, что от прежнего осталось.
     */
    public static void sway(double[] tilt, Vec3 away) {
        if (BodySway.peak(tilt, 0) < BodySway.peak(sway, swayIndex(0))) return;
        sway = tilt;
        swayFrom = ticks;
        // напор у земли дует вдоль неё от эпицентра; под самим воздушным взрывом в сторону не дует — доля по горизонтали
        // (косинус угла на взрыв)
        Vec3 n = away.lengthSqr() > 1e-9 ? away.normalize() : Vec3.ZERO;
        pushX = n.x;
        pushZ = n.z;
    }

    /** Отсчёт наклона тела сейчас (с долей тика {@code partial}). */
    private static int swayIndex(double partial) {
        return (int) ((ticks - swayFrom + partial) / 20 * BodySway.RATE);
    }

    public static void tick() {
        ticks++;
        if (shake > 0) shake--;
        if (quake > 0) quake--;
        if (sway.length > 0 && swayIndex(0) >= sway.length) sway = STILL;
    }

    public static void reset() {
        shake = quake = 0;
        sway = STILL;
        ticks = 0;
    }

    public static void apply(ViewportEvent.ComputeCameraAngles e) {
        double k = IncomingConfig.CLIENT.cameraShake.get();
        if (k <= 0 || shake <= 0 && quake <= 0 && sway.length == 0 || Minecraft.getInstance().isPaused()) return;
        double[] o = offsets(e.getPartialTick(), e.getYaw());
        e.setYaw(e.getYaw() + (float) (o[0] * k));
        e.setPitch(e.getPitch() + (float) (o[1] * k));
        e.setRoll(e.getRoll() + (float) (o[2] * k));
    }

    /**
     * Поворот камеры тряской в кадре, ° (курс, тангаж — вниз больше нуля, крен — верх вправо больше нуля), при курсе
     * камеры {@code cameraYaw}.
     */
    static double[] offsets(double partial, double cameraYaw) {
        double t = ticks + partial;
        // амплитуды: курс/тангаж, °
        double yaw = 0, pitch = 0;
        if (shake > 0) {
            double[] a = shake >= 26 ? new double[]{5, 3.5} : shake >= 18 ? new double[]{3, 2} : shake >= 8 ? new double[]{1.5, 1} : new double[]{0.6, 0.4};
            double fade = Math.min(1, shake / 4.0);
            yaw += a[0] * fade * wobble(t, 9.7, 0.0);
            pitch += a[1] * fade * wobble(t, 12.3, 1.7);
        }
        if (quake > 0) {
            double[] a = quake >= 50 ? new double[]{1.6, 1.1} : quake >= 24 ? new double[]{0.9, 0.6} : new double[]{0.35, 0.25};
            double fade = Math.min(1, quake / 6.0);
            yaw += a[0] * fade * wobble(t, 5.1, 3.1);
            pitch += a[1] * fade * wobble(t, 6.7, 4.4);
        }
        double roll = yaw * 0.6;
        double f = (ticks - swayFrom + partial) / 20 * BodySway.RATE;
        int i = (int) f;
        if (i + 1 < sway.length) {
            // тело отклоняется по ветру от взрыва — в лицо запрокидывает голову, в спину клонит вниз, сбоку наклоняет от
            // взрыва; дальше границы устойчивости человек шагает или падает — камера стоит на ней
            double tilt = Math.toDegrees(sway[i] + (sway[i + 1] - sway[i]) * (f - i));
            double r = Math.toRadians(cameraYaw), sin = Math.sin(r), cos = Math.cos(r);
            pitch += Mth.clamp(tilt * (-sin * pushX + cos * pushZ), -Math.toDegrees(BodySway.BACK), Math.toDegrees(BodySway.FORWARD));
            roll += Mth.clamp(tilt * (-cos * pushX - sin * pushZ), -Math.toDegrees(BodySway.SIDE), Math.toDegrees(BodySway.SIDE));
        }
        return new double[]{yaw, pitch, roll};
    }

    /** Гладкое «дрожание» из двух синусов разной частоты (Гц при 20 тиках/с), амплитуда ~1. */
    private static double wobble(double t, double hz, double phase) {
        double s = t / 20.0;
        return 0.7 * Mth.sin((float) (s * hz * Mth.TWO_PI + phase)) + 0.3 * Mth.sin((float) (s * hz * 1.73 * Mth.TWO_PI + phase * 2.1));
    }
}
