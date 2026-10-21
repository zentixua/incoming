package ua.zentix.incoming.guidance;

import net.minecraft.world.phys.Vec3;
import ua.zentix.incoming.strike.WeaponSpec;

/**
 * Пике барражирующего на цель: курс и тангаж — на точку встречи с движущейся целью (упреждение по её скорости),
 * а не на то место, где она сейчас. Без упреждения снаряд, догоняя идущую цель, отстаёт на повороте, у самой цели
 * тангажа уже не хватает, и он проходит мимо, делает петлю и пикирует снова.
 */
public final class Dive {
    /** Усиление доворота курса в пике. */
    private static final double YAW_GAIN = 0.4;
    /** Дальше упреждать не на что: скорость цели за это время уже изменится. */
    private static final double MAX_LEAD_TICKS = 60;

    private Dive() {}

    /**
     * Повернуть в пике на цель в {@code target}, идущую со скоростью {@code targetVelocity} (блоков за тик), с пределами
     * поворотов пике из паспорта {@code dive}.
     */
    public static void steer(FlightController flight, Vec3 pos, double speed, Vec3 target, Vec3 targetVelocity, WeaponSpec.Agility dive) {
        Vec3 aim = intercept(pos, speed, target, targetVelocity);
        float[] a = FlightController.anglesTo(pos, aim);
        flight.arcPitch(a[1], speed, pos.distanceTo(aim), dive.pitchRate(), dive.pitchAccel());
        // с круга цель сбоку: резкий доворот с креном и пике (радиус разворота ~15 блоков); над целью — не крутиться
        double dx = aim.x - pos.x, dz = aim.z - pos.z;
        if (dx * dx + dz * dz > 9) flight.steerYaw(a[0], YAW_GAIN, dive.turnRate(), dive.turnAccel());
        else flight.settleYaw(dive.turnAccel());
    }

    /**
     * Точка встречи: где цель будет через t тиков, если снаряд со скоростью {@code speed} долетит туда за те же t —
     * меньший положительный корень |d + v·t| = s·t. Встречи нет (цель быстрее) — сама цель.
     */
    public static Vec3 intercept(Vec3 pos, double speed, Vec3 target, Vec3 targetVelocity) {
        Vec3 d = target.subtract(pos);
        double a = targetVelocity.lengthSqr() - speed * speed;
        double b = 2 * d.dot(targetVelocity);
        double c = d.lengthSqr();
        double t;
        if (Math.abs(a) < 1e-9) {
            t = b < 0 ? -c / b : -1;
        } else {
            double disc = b * b - 4 * a * c;
            if (disc < 0) return target;
            double q = Math.sqrt(disc);
            double t1 = (-b - q) / (2 * a), t2 = (-b + q) / (2 * a);
            double lo = Math.min(t1, t2), hi = Math.max(t1, t2);
            t = lo > 0 ? lo : hi;
        }
        if (!(t > 0)) return target;
        return target.add(targetVelocity.scale(Math.min(t, MAX_LEAD_TICKS)));
    }
}
