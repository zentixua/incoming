package ua.zentix.incoming.guidance;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/**
 * Ориентация и поворот снаряда. Углы в градусах в соглашении Minecraft: yaw 0 = +Z (юг), растёт к -X (запад);
 * pitch > 0 — нос вниз. Угловая скорость (°/тик) и угловое ускорение (°/тик²) ограничены — поэтому полёт
 * без рывков: снаряд не «ломается» в точке, а выходит на курс плавной дугой.
 * <p>
 * Сверх пределов автопилота — закон: путь гнёт подъёмная сила, а она не больше перегрузки изделия n
 * ({@link #limit}: {@code Article.loadFactor}). Нужное ускорение поперёк скорости — вертикальное v·ω<sub>т</sub> +
 * g·cos γ (держать вес и гнуть путь вверх) и боковое v·ω<sub>к</sub>·cos γ; их сумма по модулю не больше n·g. Поэтому
 * разворот на скорости v не круче g·√(n² − 1)/v (вираж без потери высоты), горка — (n − cos γ)·g/v, а на скорости
 * вдвое больше радиус вчетверо больше. Тангаж — первым: высота над рельефом важнее курса (автопилоты держат высоту до
 * поворота), курс берёт остаток перегрузки.
 */
public final class FlightController {
    /** Ускорение свободного падения, блоков/тик²: 9,80665 м/с² при 20 тиках в секунду. */
    public static final double G = 9.80665 / 400;

    private float yaw;
    private float pitch;
    private float yawRate;
    private float pitchRate;
    /** Скорость, блоков/тик, и наибольшая перегрузка ({@link #limit}); n ≤ 0 — без предела (рули ракетного двигателя, проверки). */
    private double speed;
    private double loadFactor;

    public FlightController(float yaw, float pitch) {
        this.yaw = yaw;
        this.pitch = pitch;
    }

    /** Копия с теми же углами, угловыми скоростями и пределом перегрузки: проиграть поворот вперёд, не трогая снаряд. */
    public FlightController copy() {
        FlightController c = new FlightController(yaw, pitch);
        c.yawRate = yawRate;
        c.pitchRate = pitchRate;
        c.speed = speed;
        c.loadFactor = loadFactor;
        return c;
    }

    /**
     * Скорость {@code speed} блоков/тик и наибольшая перегрузка {@code n} изделия: с ними повороты не круче, чем даёт
     * подъёмная сила. Сущность ставит их каждый тик перед автопилотом; n ≤ 0 — без предела.
     */
    public void limit(double speed, double n) {
        this.speed = speed;
        this.loadFactor = n;
    }

    /** Наибольшая перегрузка, с которой летит ({@link #limit}). */
    public double loadFactor() {
        return loadFactor;
    }

    /** Предел нет: без перегрузки или стоит на месте. */
    private boolean unlimited() {
        return loadFactor <= 0 || speed <= 1e-6;
    }

    /** Вертикальная часть нужного ускорения поперёк скорости, блоков/тик²: g·cos γ − v·ω (ω — темп тангажа, нос вниз). */
    private double verticalLoad(double pitchRateDeg) {
        return G * Math.cos(Math.toRadians(pitch)) - speed * Math.toRadians(pitchRateDeg);
    }

    /** Предел курсовой угловой скорости, °/тик: боковое ускорение не больше √((n·g)² − вертикальное²). */
    private double yawLimit() {
        if (unlimited()) return Double.MAX_VALUE;
        double n = loadFactor * G, v = verticalLoad(pitchRate);
        double side = Math.sqrt(Math.max(0, n * n - v * v)), cos = Math.max(0.05, Math.cos(Math.toRadians(pitch)));
        return Math.toDegrees(side / (speed * cos));
    }

    /**
     * Пределы темпа тангажа, °/тик: [вверх, вниз] — вертикальное ускорение g·cos γ − v·ω не больше по модулю n·g
     * (тангаж первым: аппарат кренится и тянет; курсу остаётся то, что не занял он, {@link #yawLimit}).
     */
    private double[] pitchLimits() {
        if (unlimited()) return new double[]{-Double.MAX_VALUE, Double.MAX_VALUE};
        double room = loadFactor * G, g = G * Math.cos(Math.toRadians(pitch));
        return new double[]{Math.toDegrees((g - room) / speed), Math.toDegrees((g + room) / speed)};
    }

    public float yaw() {
        return yaw;
    }

    public float pitch() {
        return pitch;
    }

    public void set(float yaw, float pitch) {
        this.yaw = Mth.wrapDegrees(yaw);
        this.pitch = Mth.clamp(pitch, -89f, 89f);
    }

    /** Направление носа (единичный вектор). */
    public Vec3 forward() {
        return Vec3.directionFromRotation(pitch, yaw);
    }

    /**
     * Тангаж: командная угловая скорость → ограничения |ω| ≤ maxRate, |Δω| ≤ maxAccel.
     *
     * @param commandRate желаемая угловая скорость, °/тик (> 0 — нос вниз)
     */
    public void steerPitch(double commandRate, double maxRate, double maxAccel) {
        double[] lim = pitchLimits();
        double w = Mth.clamp(Mth.clamp(commandRate, -maxRate, maxRate), lim[0], lim[1]);
        double dw = Mth.clamp(w - pitchRate, -maxAccel, maxAccel);
        // подъёмная сила — предел и для уже набранного темпа (скорость упала, занята другая ось)
        pitchRate = (float) Mth.clamp(pitchRate + dw, lim[0], lim[1]);
        pitch = Mth.clamp(pitch + pitchRate, -89f, 89f);
    }

    /** Держать тангаж: угловая скорость пропорциональна ошибке. */
    public void holdPitch(double targetPitch, double gain, double maxRate, double maxAccel) {
        steerPitch((targetPitch - pitch) * gain, maxRate, maxAccel);
    }

    /**
     * Наведение по дуге (пропорциональное): ω = 2·G·v·(угол на цель − тангаж)/дальность, G = 1.6.
     * На большой дальности поворот мягкий, у цели — энергичный; снаряд входит в цель по дуге.
     */
    public void arcPitch(double losPitch, double speed, double distance, double maxRate, double maxAccel) {
        double w = 3.2 * speed * (losPitch - pitch) / Math.max(distance, 1.0);
        steerPitch(w, maxRate, maxAccel);
    }

    /**
     * Курс на цель: ω = gain·ошибка, |ω| ≤ maxRate °/тик, |Δω| ≤ maxAccel °/тик².
     *
     * @param targetYaw курс на цель
     */
    public void steerYaw(double targetYaw, double gain, double maxRate, double maxAccel) {
        trackYaw(targetYaw, 0, gain, maxRate, maxAccel);
    }

    /**
     * Курс по кривой: к упреждению {@code feedForward} (°/тик — с какой скоростью поворачивает сама кривая,
     * например v/R на круге) добавляется поправка gain·ошибка. Без упреждения на установившемся развороте курс
     * отстаёт на ω/gain, и снаряд сползает с кривой наружу.
     */
    public void trackYaw(double targetYaw, double feedForward, double gain, double maxRate, double maxAccel) {
        double err = Mth.wrapDegrees(targetYaw - yaw), lift = yawLimit(), limit = Math.min(maxRate, lift);
        double w = Mth.clamp(feedForward + err * gain, -limit, limit);
        double dw = Mth.clamp(w - yawRate, -maxAccel, maxAccel);
        // подъёмная сила — предел и для уже набранного темпа (скорость выросла, занят тангаж)
        yawRate = (float) Mth.clamp(yawRate + dw, -lift, lift);
        yaw = Mth.wrapDegrees(yaw + yawRate);
    }

    /** Гасим курсовую скорость (над самой целью курс не трогаем). */
    public void settleYaw(double maxAccel) {
        yawRate -= (float) Mth.clamp(yawRate, -maxAccel, maxAccel);
        yaw = Mth.wrapDegrees(yaw + yawRate);
    }

    /**
     * Крен координированного виража: tan φ = v·ω/g (v — блоков/тик, ω — рад/тик, g — {@link #G}); при перегрузке n крен
     * не больше arccos(1/n).
     */
    public float bankAngle(double speed) {
        double w = Math.toRadians(yawRate);
        return (float) Mth.clamp(Math.toDegrees(Math.atan(speed * w / G)), -80, 80);
    }

    public void save(CompoundTag tag) {
        tag.putFloat("yaw", yaw);
        tag.putFloat("pitch", pitch);
        tag.putFloat("yaw_rate", yawRate);
        tag.putFloat("pitch_rate", pitchRate);
    }

    public void load(CompoundTag tag) {
        yaw = tag.getFloat("yaw");
        pitch = tag.getFloat("pitch");
        yawRate = tag.getFloat("yaw_rate");
        pitchRate = tag.getFloat("pitch_rate");
    }

    /** Угол на точку: [yaw, pitch] в градусах. */
    public static float[] anglesTo(Vec3 from, Vec3 to) {
        double dx = to.x - from.x, dy = to.y - from.y, dz = to.z - from.z;
        double h = Math.sqrt(dx * dx + dz * dz);
        float yaw = (float) (Mth.atan2(dz, dx) * Mth.RAD_TO_DEG) - 90f;
        float pitch = (float) -(Mth.atan2(dy, h) * Mth.RAD_TO_DEG);
        return new float[]{Mth.wrapDegrees(yaw), pitch};
    }
}
