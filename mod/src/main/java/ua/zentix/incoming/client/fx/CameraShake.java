package ua.zentix.incoming.client.fx;

import net.minecraft.client.Minecraft;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.ViewportEvent;
import org.jetbrains.annotations.Nullable;
import ua.zentix.incoming.IncomingConfig;
import ua.zentix.incoming.nuclear.model.BodySway;

import java.util.ArrayList;
import java.util.List;

/**
 * Тряска камеры без сдвига прицела: крутится только камера (курс, тангаж, крен), взгляд игрока остаётся на месте —
 * тряска самого взгляда сбивала бы прицел.
 * <p>
 * Глаз видит скорость поворота: размах A° на частоте f — до 2π·f·A °/с (на каждом кадре — её доля). Поэтому толчки
 * грунта у обычных взрывов медленные, как у тела: голову качает само тело (основной тон ниже ~6 Гц), а частую дрожь
 * земли оно до глаз почти не доносит. Размахи меняются без скачков: скачок на кадр — тот же рывок.
 * <p>
 * Ядерная волна камеру не трясёт, а наклоняет: ветер за фронтом толкает стоящего, и тело отклоняется по ветру и
 * выпрямляется ({@link BodySway} — маятник с равновесием); голова — с телом. Наклоны от нескольких волн складываются
 * (маятник при малых углах линеен). Дальше границы устойчивости человек шагает или падает — камера стоит на ней. В
 * воздухе (прыжок) тело на ноги не опирается — наклона нет, пока снова не встанет.
 */
public final class CameraShake {
    /** Удар взрывной волны: сколько тиков ещё трясёт. */
    private static int shake;
    /** Толчки грунта (подземный взрыв, бурение): медленнее и мягче. */
    private static int quake;
    private static long ticks;
    /** Ядерные волны, что качают тело сейчас. */
    private static final List<Sway> SWAYS = new ArrayList<>();
    /** Больше стольких волн сразу — самая старая уходит. */
    private static final int SWAYS_MAX = 8;
    /** Насколько игрок стоит на ногах (1 — стоит, 0 — в воздухе), в прошлом тике и сейчас. */
    private static double standing = 1, standingBefore = 1;
    /** Насколько за тик меняется опора: тело встаёт на ноги и отрывается от них за четверть секунды. */
    private static final double STANDING_STEP = 0.2;

    /**
     * Наклон тела ветром одной волны, рад ({@link BodySway#RATE} отсчётов в секунду), с тика {@code from}; куда дул её
     * ветер — от взрыва вдоль земли (x, z, до 1 — под самым взрывом в сторону не дует).
     */
    private record Sway(double[] tilt, long from, double pushX, double pushZ) {}

    private CameraShake() {}

    public static void blast(int ticks) {
        shake = Math.max(shake, ticks);
    }

    public static void quake(int ticks) {
        quake = Math.max(quake, ticks);
    }

    /**
     * Ветер ядерной волны качнул тело ({@link BodySway#tilt}); {@code away} — от взрыва к игроку. Наклон ложится поверх
     * того, что осталось от прежних волн.
     */
    public static void sway(double[] tilt, Vec3 away) {
        if (tilt.length < 2) return;
        // напор у земли дует вдоль неё от эпицентра; под самим воздушным взрывом в сторону не дует — доля по горизонтали
        // (косинус угла на взрыв)
        Vec3 n = away.lengthSqr() > 1e-9 ? away.normalize() : Vec3.ZERO;
        if (SWAYS.size() >= SWAYS_MAX) SWAYS.remove(0);
        SWAYS.add(new Sway(tilt, ticks, n.x, n.z));
    }

    /** Стоит ли игрок на ногах: на земле и его не несёт ({@link #carried}). */
    public static boolean standing(@Nullable Player p) {
        return p != null && p.onGround() && !carried(p);
    }

    /** Ветер несёт игрока целиком или тот не стоит вовсе: верхом, вплавь, в полёте, во сне. */
    public static boolean carried(Player p) {
        return p.isPassenger() || p.isSwimming() || p.isFallFlying() || p.isSleeping() || p.getAbilities().flying;
    }

    /** Тик клиента; {@code onFeet} — игрок стоит на ногах ({@link #standing}). */
    public static void tick(boolean onFeet) {
        ticks++;
        if (shake > 0) shake--;
        if (quake > 0) quake--;
        standingBefore = standing;
        standing = Mth.clamp(standing + (onFeet ? STANDING_STEP : -STANDING_STEP), 0, 1);
        SWAYS.removeIf(s -> (ticks - s.from) / 20.0 * BodySway.RATE >= s.tilt.length - 1);
    }

    public static void reset() {
        shake = quake = 0;
        SWAYS.clear();
        standing = standingBefore = 1;
        ticks = 0;
    }

    public static void apply(ViewportEvent.ComputeCameraAngles e) {
        double k = IncomingConfig.CLIENT.cameraShake.get();
        if (k <= 0 || shake <= 0 && quake <= 0 && SWAYS.isEmpty() || Minecraft.getInstance().isPaused()) return;
        double[] o = offsets(e.getPartialTick(), e.getYaw(), e.getPitch(), k);
        e.setYaw(e.getYaw() + (float) o[0]);
        e.setPitch(e.getPitch() + (float) o[1]);
        e.setRoll(e.getRoll() + (float) o[2]);
    }

    /**
     * Поворот камеры тряской в кадре, ° (курс, тангаж — вниз больше нуля, крен — верх вправо больше нуля), при курсе
     * {@code cameraYaw} и тангаже {@code cameraPitch} камеры; {@code k} — сила тряски из настроек.
     */
    static double[] offsets(double partial, double cameraYaw, double cameraPitch, double k) {
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
        yaw *= k;
        pitch *= k;
        roll *= k;
        // наклон тела по ветру всех волн, ° по осям мира
        double leanX = 0, leanZ = 0;
        for (Sway s : SWAYS) {
            double f = (ticks - s.from + partial) / 20 * BodySway.RATE;
            int i = (int) f;
            if (i + 1 >= s.tilt.length) continue;
            double tilt = Math.toDegrees(s.tilt[i] + (s.tilt[i + 1] - s.tilt[i]) * (f - i));
            leanX += tilt * s.pushX;
            leanZ += tilt * s.pushZ;
        }
        double w = k * (standingBefore + (standing - standingBefore) * partial);
        if (w > 0 && (leanX != 0 || leanZ != 0)) {
            // тело отклоняется по ветру от взрыва — в лицо запрокидывает голову, в спину клонит вниз, сбоку наклоняет от
            // взрыва
            double r = Math.toRadians(cameraYaw), sin = Math.sin(r), cos = Math.cos(r);
            double ahead = w * (-sin * leanX + cos * leanZ), side = w * (-cos * leanX - sin * leanZ);
            // дальше границы устойчивости (вперёд, назад, вбок — эллипсом между ними) человек шагает или падает — камера
            // стоит на ней
            double edge = Math.hypot(ahead / Math.toDegrees(ahead > 0 ? BodySway.FORWARD : BodySway.BACK), side / Math.toDegrees(BodySway.SIDE));
            if (edge > 1) {
                ahead /= edge;
                side /= edge;
            }
            pitch += ahead;
            // наклон вбок — поворот вокруг горизонтали вперёд: у взгляда вниз это курс, а не крен
            double b = Math.toRadians(cameraPitch);
            roll += side * Math.cos(b);
            yaw -= side * Math.sin(b);
        }
        return new double[]{yaw, pitch, roll};
    }

    /** Гладкое «дрожание» из двух синусов разной частоты (Гц при 20 тиках/с), амплитуда ~1. */
    private static double wobble(double t, double hz, double phase) {
        double s = t / 20.0;
        return 0.7 * Mth.sin((float) (s * hz * Mth.TWO_PI + phase)) + 0.3 * Mth.sin((float) (s * hz * 1.73 * Mth.TWO_PI + phase * 2.1));
    }
}
