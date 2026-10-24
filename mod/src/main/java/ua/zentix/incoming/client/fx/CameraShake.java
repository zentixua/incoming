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
 * Наклон от ударной волны — ядерной и обычного взрыва: фронт бьёт стоящего разом (за миллисекунды обтекания тело получает
 * скорость наклона), ветер за ним толкает дальше, и тело отклоняется по ветру и выпрямляется ({@link BodySway} — маятник
 * с равновесием); голова — с телом. Наклон идёт с самого прихода фронта к телу, а не с начала тика, в котором его
 * заметили: удар — на кадре, где он был. Наклоны от нескольких волн складываются
 * (маятник при малых углах линеен). Дальше границы устойчивости человек шагает — наклон стоит на ней. В воздухе
 * (прыжок) тело на ноги не опирается — наклона нет, пока снова не встанет. Наклон тела — поворот камеры вокруг
 * горизонтали мира поперёк наклона, как есть: курс камеры вертится вокруг вертикали мира, поэтому сложить наклон с
 * курсом, тангажем и креном по отдельности нельзя — у взгляда вниз поворот вбок ушёл бы во вращение картинки.
 * <p>
 * <b>Дрожь удара</b> ({@link #tremor}) — <b>подача</b> (принцип вида: камера — подача, факты не меняются): в миг прихода
 * фронта обычного взрыва картинка коротко дрожит вращением, как в хорошей съёмке удара и как было до 3.5.0. Её сила,
 * частота и длина — из самой волны у тела: размах — по давлению фронта, длина — по его импульсу, частота ниже у сильного
 * удара; без полос дальности. Вращение, без сдвига; начинается с нуля и гаснет к нулю — без рывка. Потом остаётся
 * наклон тела и качание головы ({@link BodySway}).
 */
public final class CameraShake {
    /** Толчки грунта (подземный взрыв, бурение): медленнее и мягче. */
    private static int quake;
    private static long ticks;
    /** Волны, что качают тело сейчас. */
    private static final List<Sway> SWAYS = new ArrayList<>();
    /** Больше стольких волн сразу — самая старая уходит. */
    private static final int SWAYS_MAX = 8;
    /** Дрожь от ударов, что идёт сейчас ({@link #tremor}). */
    private static final List<Tremor> TREMORS = new ArrayList<>();
    /**
     * Дрожь удара: с давления {@link #TREMOR_LOW_PA} (140 дБ — громкий хлопок) её нет, к {@link #TREMOR_HIGH_PA} (5 psi —
     * сбивает с ног) — полная; сила — доля по логарифму давления в степени {@link #TREMOR_POWER} (у Эйзерлоха, GDC 2016,
     * тряска — trauma² или trauma³: слабый удар почти не трясёт; у нас мягче, чтобы шахед в 100 м дрожал не слабее 3.4.1).
     * Подача.
     */
    static final double TREMOR_LOW_PA = 200, TREMOR_HIGH_PA = 35_000, TREMOR_POWER = 1.5;
    /** Полный размах дрожи курса и тангажа, ° (как у ближнего удара в 3.4.1); крен — доля курса. Подача. */
    static final double TREMOR_YAW = 5, TREMOR_PITCH = 3.5, TREMOR_ROLL = 0.6;
    /** Длина дрожи, с: от {@link #TREMOR_SHORT} у слабого импульса фазы до {@link #TREMOR_LONG} у сильного. Подача. */
    static final double TREMOR_SHORT = 0.3, TREMOR_LONG = 1.7;
    /** Импульс фазы сжатия, Па·с, от которого дрожь длиннее кратчайшей и с которого — длиннейшая. Подача. */
    static final double TREMOR_IMPULSE_LOW = 5, TREMOR_IMPULSE_HIGH = 300;
    /** Частота дрожи, Гц: у слабого удара — {@link #TREMOR_HZ_WEAK}, у полного — {@link #TREMOR_HZ_STRONG}. Подача. */
    static final double TREMOR_HZ_WEAK = 12.5, TREMOR_HZ_STRONG = 8.5;

    /** Дрожь одного удара: с мига {@code from} (тики, с долей), сила 0..1, длина, с, частота, Гц, сдвиг узора. */
    private record Tremor(double from, double strength, double seconds, double hz, double phase) {}
    /** Насколько игрок стоит на ногах (1 — стоит, 0 — в воздухе), в прошлом тике и сейчас. */
    private static double standing = 1, standingBefore = 1;
    /** Насколько за тик меняется опора: тело встаёт на ноги и отрывается от них за четверть секунды. */
    private static final double STANDING_STEP = 0.2;

    /**
     * Наклон тела ударом и ветром одной волны, рад ({@link BodySway#RATE} отсчётов в секунду), с мига {@code from} (тики
     * клиента, с долей); куда дул её ветер — от взрыва вдоль земли (x, z, единичный).
     */
    private record Sway(double[] tilt, double[] neck, double from, double pushX, double pushZ) {}

    private CameraShake() {}

    public static void quake(int ticks) {
        quake = Math.max(quake, ticks);
    }

    /** Сила дрожи 0..1 у давления фронта {@code pPa}, Па: (доля по логарифму давления)^{@link #TREMOR_POWER}. */
    static double tremorStrength(double pPa) {
        double x = Mth.clamp(Math.log(Math.max(pPa, 1e-9) / TREMOR_LOW_PA) / Math.log(TREMOR_HIGH_PA / TREMOR_LOW_PA), 0, 1);
        return Math.pow(x, TREMOR_POWER);
    }

    /** Длина дрожи, с, у импульса фазы сжатия {@code impulse}, Па·с. */
    static double tremorSeconds(double impulse) {
        double x = Mth.clamp(Math.log(Math.max(impulse, 1e-9) / TREMOR_IMPULSE_LOW) / Math.log(TREMOR_IMPULSE_HIGH / TREMOR_IMPULSE_LOW), 0, 1);
        return TREMOR_SHORT + (TREMOR_LONG - TREMOR_SHORT) * x;
    }

    /**
     * Фронт обычного взрыва дошёл до тела: дрожь камеры по его давлению {@code pPa}, Па, и импульсу фазы сжатия
     * {@code impulse}, Па·с; {@code late} — сколько тиков назад (0..1); {@code seed} — узор. Подача. Возвращает силу 0..1.
     */
    public static double tremor(double pPa, double impulse, double late, long seed) {
        double strength = tremorStrength(pPa);
        if (!(strength > 1e-4)) return 0;
        double hz = TREMOR_HZ_WEAK + (TREMOR_HZ_STRONG - TREMOR_HZ_WEAK) * Math.sqrt(strength);
        TREMORS.add(new Tremor(ticks - Mth.clamp(late, 0, 1), strength, tremorSeconds(impulse), hz, (seed & 0xFFFF) / 65536.0));
        return strength;
    }

    /**
     * Дрожь в миг {@code t} тиков, ° (курс, тангаж; крен — доля курса): узор — две синусоиды в отношении 1,73, с нуля,
     * огибающая (1 − s)² гаснет к нулю за длину дрожи. Сумма дрожей — не больше полного размаха (предела числа дрожей
     * нет: вытеснить идущую — рывок; каждая кончается за {@link #TREMOR_LONG} с).
     */
    static double[] tremorAt(double t) {
        double yaw = 0, pitch = 0;
        for (Tremor r : TREMORS) {
            double s = (t - r.from) / 20;
            if (s < 0 || s >= r.seconds) continue;
            double fade = (1 - s / r.seconds) * (1 - s / r.seconds), a = r.strength * fade;
            yaw += TREMOR_YAW * a * shiver(s, r.hz, r.phase);
            pitch += TREMOR_PITCH * a * shiver(s, 1.27 * r.hz, r.phase + 0.37);
        }
        yaw = Mth.clamp(yaw, -TREMOR_YAW, TREMOR_YAW);
        pitch = Mth.clamp(pitch, -TREMOR_PITCH, TREMOR_PITCH);
        return new double[]{yaw, pitch, TREMOR_ROLL * yaw};
    }

    /** Узор дрожи, размах ~1, с нуля в s = 0: две синусоиды частот f и 1,73·f, у каждого удара свой сдвиг частоты. */
    private static double shiver(double s, double hz, double phase) {
        double f = hz * (0.9 + 0.2 * phase);
        return 0.7 * Math.sin(Mth.TWO_PI * f * s) + 0.3 * Math.sin(Mth.TWO_PI * 1.73 * f * s);
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
        return sway(pPa, qPa, open, tPlus, tPlus, late, away, yaw, false).body();
    }

    /**
     * Как {@link #sway(double, double, double, double, double, Vec3, double)}, но удар обтекания спадает по своей фазе
     * {@code hitPhase}, с: у ядерной волны фаза ветра в игре — время модели × {@code effects_scale}, а обтекание тела —
     * настоящие миллисекунды, и фаза удара — фаза модели. {@code neck} — камера качается и головой на шее
     * ({@link BodySway#motion}); без неё — голова идёт с телом (<b>не по физике</b>: так пока у ядерной волны — её
     * картинку этот шаг не менял).
     */
    public static BodySway.Motion sway(double pPa, double qPa, double open, double tPlus, double hitPhase, double late, Vec3 away, double yaw,
                                       boolean neck) {
        double flat = Math.hypot(away.x, away.z);
        if (!(flat > 1e-6)) return new BodySway.Motion(new double[0], new double[0]);
        double x = away.x / flat, z = away.z / flat;
        double edge = edge(yaw, x, z), back = edge(yaw, -x, -z);
        BodySway.Motion m = neck ? BodySway.motion(pPa, qPa, open, tPlus, hitPhase, edge, back)
                : new BodySway.Motion(BodySway.tilt(pPa, qPa, open, tPlus, hitPhase, edge, back), null);
        if (m.still()) return m;
        if (SWAYS.size() >= SWAYS_MAX) SWAYS.remove(0);
        SWAYS.add(new Sway(m.body(), m.neck(), ticks - Mth.clamp(late, 0, 1), x, z));
        return m;
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
        TREMORS.removeIf(r -> (ticks - r.from) / 20.0 >= r.seconds);
    }

    public static void reset() {
        quake = 0;
        SWAYS.clear();
        TREMORS.clear();
        standing = standingBefore = 1;
        ticks = 0;
    }

    public static void apply(ViewportEvent.ComputeCameraAngles e) {
        double k = IncomingConfig.CLIENT.cameraShake.get();
        Minecraft mc = Minecraft.getInstance();
        if (k <= 0 || quake <= 0 && SWAYS.isEmpty() && TREMORS.isEmpty() || mc.isPaused()) return;
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
        if (!TREMORS.isEmpty() && !Double.isNaN(bodyYaw)) {
            // дрожь удара — у головы игрока, как наклон: камере снаряда её не видно
            double[] tr = tremorAt(t);
            yaw += tr[0];
            pitch += tr[1];
            roll += tr[2];
        }
        yaw *= k;
        pitch *= k;
        roll *= k;
        // наклон тела и головы на шее по ветру всех волн, ° по осям мира
        double leanX = 0, leanZ = 0, neckX = 0, neckZ = 0;
        for (Sway s : SWAYS) {
            double f = (ticks - s.from + partial) / 20 * BodySway.RATE;
            int i = (int) f;
            if (f < 0 || i + 1 >= s.tilt.length) continue;
            double tilt = Math.toDegrees(s.tilt[i] + (s.tilt[i + 1] - s.tilt[i]) * (f - i));
            leanX += tilt * s.pushX;
            leanZ += tilt * s.pushZ;
            if (s.neck == null) continue;
            double neck = Math.toDegrees(s.neck[i] + (s.neck[i + 1] - s.neck[i]) * (f - i));
            neckX += neck * s.pushX;
            neckZ += neck * s.pushZ;
        }
        double w = k * (standingBefore + (standing - standingBefore) * partial), body = w * Math.hypot(leanX, leanZ);
        if (Double.isNaN(bodyYaw) || !(body > 0) && !(neckX != 0 || neckZ != 0)) return new double[]{yaw, pitch, roll};
        // тело отклоняется по ветру от взрыва — в лицо запрокидывает голову, в спину клонит вниз, сбоку наклоняет от
        // взрыва; дальше границы устойчивости человек шагает — наклон тела стоит на ней; голова качается на шее поверх
        if (body > 0) {
            double bx = leanX / Math.hypot(leanX, leanZ), bz = leanZ / Math.hypot(leanX, leanZ);
            body = Math.min(body, Math.toDegrees(edge(bodyYaw, bx, bz)));
            leanX = body * bx;
            leanZ = body * bz;
        }
        leanX += w * neckX;
        leanZ += w * neckZ;
        double lean = Math.hypot(leanX, leanZ);
        if (!(lean > 0)) return new double[]{yaw, pitch, roll};
        double x = leanX / lean, z = leanZ / lean;
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
