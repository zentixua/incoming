package ua.zentix.incoming.client.fx;

import net.minecraft.client.Minecraft;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.ViewportEvent;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix3d;
import ua.zentix.incoming.IncomingConfig;
import ua.zentix.incoming.nuclear.model.BodySway;

import java.util.ArrayList;
import java.util.List;

/**
 * Тряска камеры без сдвига прицела: крутится только камера (курс, тангаж, крен), взгляд игрока остаётся на месте —
 * тряска самого взгляда сбивала бы прицел.
 * <p>
 * Глаз видит скорость поворота: размах A° на частоте f — до 2π·f·A °/с (на каждом кадре — её доля). Поэтому толчки
 * грунта (подземный взрыв, бурение — {@link #quake}, по пакету сервера) медленные, как у тела: голову качает само тело
 * (основной тон ниже ~6 Гц), а частую дрожь земли оно до глаз почти не доносит. Размахи меняются без скачков: скачок на
 * кадр — тот же рывок.
 * <p>
 * Ударная волна — ядерная и обычного взрыва — камеру не трясёт, а наклоняет: фронт бьёт стоящего разом (за миллисекунды обтекания тело получает
 * скорость наклона), ветер за ним толкает дальше, и тело отклоняется по ветру и выпрямляется ({@link BodySway} — маятник
 * с равновесием); голова — с телом. Наклон идёт с самого прихода фронта к телу, а не с начала тика, в котором его
 * заметили: удар — на кадре, где он был. Наклоны от нескольких волн складываются
 * (маятник при малых углах линеен). Дальше границы устойчивости человек шагает — наклон стоит на ней. В воздухе
 * (прыжок) тело на ноги не опирается — наклона нет, пока снова не встанет. Наклон тела — поворот камеры вокруг
 * горизонтали мира поперёк наклона, как есть: курс камеры вертится вокруг вертикали мира, поэтому сложить наклон с
 * курсом, тангажем и креном по отдельности нельзя — у взгляда вниз поворот вбок ушёл бы во вращение картинки.
 */
public final class CameraShake {
    /** Толчки грунта (подземный взрыв, бурение): медленнее и мягче. */
    private static int quake;
    private static long ticks;
    /** Волны, что качают тело сейчас. */
    private static final List<Sway> SWAYS = new ArrayList<>();
    /** Больше стольких волн сразу — самая старая уходит. */
    private static final int SWAYS_MAX = 8;
    /** Насколько игрок стоит на ногах (1 — стоит, 0 — в воздухе), в прошлом тике и сейчас. */
    private static double standing = 1, standingBefore = 1;
    /** Насколько за тик меняется опора: тело встаёт на ноги и отрывается от них за четверть секунды. */
    private static final double STANDING_STEP = 0.2;

    /**
     * Наклон тела ударом и ветром одной волны, рад ({@link BodySway#RATE} отсчётов в секунду), с мига {@code from} (тики
     * клиента, с долей); куда дул её ветер — от взрыва вдоль земли (x, z, единичный).
     */
    private record Sway(double[] tilt, double from, double pushX, double pushZ) {}

    private CameraShake() {}

    public static void quake(int ticks) {
        quake = Math.max(quake, ticks);
    }

    /**
     * Фронт волны (ядерной или обычного взрыва) ударил тело и ветер за ним качнул его ({@link BodySway#tilt}): давление фронта {@code pPa} и
     * скоростной напор за фронтом вдоль земли {@code qPa}, Па, доля открытого к взрыву {@code open} (ветровая тень), с
     * положительной фазой {@code tPlus}, с; {@code late} —
     * сколько тиков назад (0..1) фронт прошёл тело; {@code away} — от взрыва к игроку (ветер дует от эпицентра),
     * {@code yaw} — куда игрок смотрит, °. Наклон ложится поверх того, что осталось от прежних волн. Возвращает наклон
     * тела по ветру, рад.
     */
    public static double[] sway(double pPa, double qPa, double open, double tPlus, double late, Vec3 away, double yaw) {
        return sway(pPa, qPa, open, tPlus, tPlus, late, away, yaw);
    }

    /**
     * Как {@link #sway(double, double, double, double, double, Vec3, double)}, но удар обтекания спадает по своей фазе
     * {@code hitPhase}, с: у ядерной волны фаза ветра в игре — время модели × {@code effects_scale}, а обтекание тела —
     * настоящие миллисекунды, и фаза удара — фаза модели.
     */
    public static double[] sway(double pPa, double qPa, double open, double tPlus, double hitPhase, double late, Vec3 away, double yaw) {
        double flat = Math.hypot(away.x, away.z);
        if (!(flat > 1e-6)) return new double[0];
        double x = away.x / flat, z = away.z / flat;
        double[] tilt = BodySway.tilt(pPa, qPa, open, tPlus, hitPhase, edge(yaw, x, z), edge(yaw, -x, -z));
        if (tilt.length < 2) return tilt;
        if (SWAYS.size() >= SWAYS_MAX) SWAYS.remove(0);
        SWAYS.add(new Sway(tilt, ticks - Mth.clamp(late, 0, 1), x, z));
        return tilt;
    }

    /**
     * Граница устойчивости, рад, для наклона в сторону (x, z) мира, когда игрок смотрит по курсу {@code yaw}°: вперёд
     * {@link BodySway#FORWARD}, назад {@link BodySway#BACK}, вбок {@link BodySway#SIDE}, наискось — эллипсом между ними.
     */
    static double edge(double yaw, double x, double z) {
        double r = Math.toRadians(yaw), sin = Math.sin(r), cos = Math.cos(r);
        double ahead = -sin * x + cos * z, side = -cos * x - sin * z, len = Math.hypot(ahead, side);
        if (!(len > 0)) return BodySway.FORWARD;
        return len / Math.hypot(ahead / (ahead > 0 ? BodySway.FORWARD : BodySway.BACK), side / BodySway.SIDE);
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
        if (quake > 0) quake--;
        standingBefore = standing;
        standing = Mth.clamp(standing + (onFeet ? STANDING_STEP : -STANDING_STEP), 0, 1);
        SWAYS.removeIf(s -> (ticks - s.from) / 20.0 * BodySway.RATE >= s.tilt.length - 1);
    }

    public static void reset() {
        quake = 0;
        SWAYS.clear();
        standing = standingBefore = 1;
        ticks = 0;
    }

    public static void apply(ViewportEvent.ComputeCameraAngles e) {
        double k = IncomingConfig.CLIENT.cameraShake.get();
        Minecraft mc = Minecraft.getInstance();
        if (k <= 0 || quake <= 0 && SWAYS.isEmpty() || mc.isPaused()) return;
        // наклон — тела игрока: камере снаряда и глазам другой сущности его не видно
        Player p = mc.player;
        double body = p != null && mc.getCameraEntity() == p ? p.getViewYRot((float) e.getPartialTick()) : Double.NaN;
        double[] o = offsets(e.getPartialTick(), e.getYaw(), e.getPitch(), e.getRoll(), body, k);
        e.setYaw(e.getYaw() + (float) o[0]);
        e.setPitch(e.getPitch() + (float) o[1]);
        e.setRoll(e.getRoll() + (float) o[2]);
    }

    /**
     * Поворот камеры тряской в кадре, ° (курс, тангаж — вниз больше нуля, крен — верх вправо больше нуля), при курсе
     * {@code cameraYaw}, тангаже {@code cameraPitch} и крене {@code cameraRoll} камеры; {@code bodyYaw} — куда смотрит
     * игрок, ° (по нему граница устойчивости: вид спереди от третьего лица смотрит назад), NaN — камера не на игроке,
     * наклона тела нет; {@code k} — сила тряски из настроек.
     */
    static double[] offsets(double partial, double cameraYaw, double cameraPitch, double cameraRoll, double bodyYaw, double k) {
        double t = ticks + partial;
        // амплитуды: курс/тангаж, °
        double yaw = 0, pitch = 0;
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
            if (f < 0 || i + 1 >= s.tilt.length) continue;
            double tilt = Math.toDegrees(s.tilt[i] + (s.tilt[i + 1] - s.tilt[i]) * (f - i));
            leanX += tilt * s.pushX;
            leanZ += tilt * s.pushZ;
        }
        double w = k * (standingBefore + (standing - standingBefore) * partial), lean = w * Math.hypot(leanX, leanZ);
        if (!(lean > 0) || Double.isNaN(bodyYaw)) return new double[]{yaw, pitch, roll};
        // тело отклоняется по ветру от взрыва — в лицо запрокидывает голову, в спину клонит вниз, сбоку наклоняет от
        // взрыва; дальше границы устойчивости человек шагает — наклон стоит на ней
        double x = leanX / Math.hypot(leanX, leanZ), z = leanZ / Math.hypot(leanX, leanZ);
        lean = Math.min(lean, Math.toDegrees(edge(bodyYaw, x, z)));
        double[] to = leaned(cameraYaw + yaw, cameraPitch + pitch, cameraRoll + roll, lean, x, z);
        return new double[]{Mth.wrapDegrees(to[0] - cameraYaw), to[1] - cameraPitch, Mth.wrapDegrees(to[2] - cameraRoll)};
    }

    /**
     * Камера с курсом {@code yaw}, тангажем {@code pitch} и креном {@code roll} (°) после наклона тела на {@code lean}° в
     * сторону (x, z) мира (единичную): поворот вокруг горизонтали поперёк наклона — верх камеры уходит туда, куда клонится
     * тело. Возвращает курс, тангаж и крен, которые дают этот поворот у {@code Camera.setRotation}:
     * {@code rotationYXZ(π − курс, −тангаж, −крен)}.
     */
    static double[] leaned(double yaw, double pitch, double roll, double lean, double x, double z) {
        Matrix3d m = new Matrix3d().rotation(Math.toRadians(lean), z, 0, -x)
                .mul(new Matrix3d().rotationYXZ(Math.PI - Math.toRadians(yaw), -Math.toRadians(pitch), -Math.toRadians(roll)));
        // разложение R = Ry(a)·Rx(b)·Rz(c); у JOML mCR — столбец C, строка R
        double b = Math.asin(Mth.clamp(-m.m21(), -1, 1)), a, c;
        if (Math.cos(b) > 1e-7) {
            a = Math.atan2(m.m20(), m.m22());
            c = Math.atan2(m.m01(), m.m11());
        } else {
            // взгляд ровно в зенит или надир: курс и крен вертят картинку вокруг одной оси — крен оставляем прежним
            c = -Math.toRadians(roll);
            double turn = Math.atan2(-m.m02(), m.m00());
            a = b < 0 ? turn - c : turn + c;
        }
        return new double[]{180 - Math.toDegrees(a), -Math.toDegrees(b), -Math.toDegrees(c)};
    }

    /** Гладкое «дрожание» из двух синусов разной частоты (Гц при 20 тиках/с), амплитуда ~1. */
    private static double wobble(double t, double hz, double phase) {
        double s = t / 20.0;
        return 0.7 * Mth.sin((float) (s * hz * Mth.TWO_PI + phase)) + 0.3 * Mth.sin((float) (s * hz * 1.73 * Mth.TWO_PI + phase * 2.1));
    }
}
