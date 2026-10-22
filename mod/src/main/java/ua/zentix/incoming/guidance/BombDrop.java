package ua.zentix.incoming.guidance;

import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import ua.zentix.incoming.strike.WeaponSpec;
import ua.zentix.incoming.util.Air;
import ua.zentix.incoming.util.StandardAtmosphere;

/**
 * Падение бетонобойной бомбы после сброса: рули ведут нос по дуге на точку, скорость — от тяжести вдоль пути и
 * сопротивления воздуха ({@link WeaponSpec.Ballistic} бомбы в паспорте), ветер сносит. Одни и те же шаги
 * у самой бомбы и у B-2, который перед сбросом проигрывает её падение ({@link #miss}): попадёт ли бомба, сброшенная
 * сейчас, с этой высоты и дальности.
 */
public final class BombDrop {
    /** Бомба ниже B-2 на столько (отделяется от брюха). */
    public static final double DROP_BELOW = 4;
    /** Запас дальности подрыва сверх шага: бомба ближе шага и этого запаса к точке — попала в неё. */
    public static final double REACH_PAD = 5.3;
    /** Длина носа: за тик бомба ищет преграду от середины носа до его конца после шага (как все снаряды). */
    public static final double NOSE = 3.1;
    /** Край окна сброса по курсу — не дальше высоты над точкой, дуги, на которой бомба опускает нос ({@link #pullOver}), и этого. */
    public static final double EDGE_REACH = 30;
    /** Рули бомбы: тангаж не быстрее стольких °/тик и прирост его не больше стольких °/тик² (игровые, не по физике). */
    private static final double FIN_RATE = 7, FIN_ACCEL = 0.8;
    /** Земля выше точки на столько: B-2 целит в середину верхнего блока, бомба бьётся о его верх. */
    public static final double GROUND_ABOVE_AIM = 0.5;
    /**
     * Нос вниз не меньше: бомба сходит со скоростью и тангажем носителя ({@link #miss}, почти горизонтально), рули
     * опускают нос, и вверх он уже не идёт; опустив его до стольких градусов, бомба не выравнивается положе.
     */
    public static final float MIN_DIVE = 10;
    /** Падение, когда цели под носом нет. */
    public static final float FALL_PITCH = 60;
    /** Столько тиков бомба падает в {@link #miss} самое большее: с высоты до ~1000 блоков она на земле раньше. */
    private static final int MAX_TICKS = 200;

    private BombDrop() {}

    /** Точка позади по курсу {@code yaw} (дальше 8 блоков по горизонтали): бомба к ней уже не повернёт. */
    public static boolean passed(Vec3 pos, Vec3 aim, float yaw) {
        double dx = aim.x - pos.x, dz = aim.z - pos.z;
        if (dx * dx + dz * dz <= 64) return false;
        return Math.abs(Mth.wrapDegrees(FlightController.anglesTo(pos, aim)[0] - yaw)) >= 90;
    }

    /**
     * Шаг наведения падающей бомбы: повернуть нос; возвращает скорость на этом тике — тяжесть вдоль пути разгоняет,
     * сопротивление (квадратичное, плотность на высоте) тормозит: сброшенная со скоростью B-2 бомба тормозит
     * к пределу √(g/drag), медленная — разгоняется к нему.
     */
    public static double steer(FlightController flight, Vec3 pos, double speed, Vec3 aim) {
        float[] a = FlightController.anglesTo(pos, aim);
        double dx = aim.x - pos.x, dz = aim.z - pos.z;
        double horizontal = Math.sqrt(dx * dx + dz * dz);
        // свободно падающая бомба не выравнивается и не набирает высоту: точка не ниже MIN_DIVE под носом
        // (цель на высоте бомбы или выше, уже пролетели) — просто падать круто вниз, на рули не надеясь
        boolean below = a[1] >= MIN_DIVE && !passed(pos, aim, flight.yaw());
        float floor = Math.min(MIN_DIVE, flight.pitch());
        flight.arcPitch(below ? a[1] : FALL_PITCH, speed, pos.distanceTo(aim), FIN_RATE, FIN_ACCEL);
        if (flight.pitch() < floor) flight.set(flight.yaw(), floor);
        if (below && horizontal > 8) flight.steerYaw(a[0], 0.15, 3.0, 0.3);
        double drag = fall().drag() * StandardAtmosphere.density(pos.y - Air.SEA) / StandardAtmosphere.density(0);
        return Math.max(0, speed - Ballistics.GRAVITY * flight.forward().y - drag * speed * speed);
    }

    /**
     * Радиус дуги, на которой бомба со скоростью {@code speed} опускает нос рулями: v / ω. Сошедшая горизонтально со
     * скоростью B-2 (12 блоков/тик) бомба проходит по курсу ~100 блоков, прежде чем пойдёт круто вниз: ближе этого
     * к точке она её перелетает, с какой бы высоты ни падала.
     */
    public static double pullOver(double speed) {
        return speed / Math.toRadians(FIN_RATE);
    }

    /**
     * Снос ветром за тик: поперечная воздуху скорость бомбы тянется к ветру {@code wind} (блоков/тик) с долей
     * drag·|v| за тик — тяжёлую быструю бомбу ветер почти не сносит.
     */
    public static Vec3 drift(Vec3 drift, Vec3 wind, double speed) {
        double k = Math.min(1, fall().drag() * speed);
        return drift.add(wind.subtract(drift).scale(k));
    }

    /** Полёт бомбы без мотора из паспорта (читается при вызове: паспорт сам ссылается на этот класс). */
    private static WeaponSpec.Ballistic fall() {
        return WeaponSpec.BUNKER.payload().ballistic();
    }

    /**
     * Сбросить бомбу сейчас: B-2 в {@code pos}, за тик он проходит {@code step} (это и скорость бомбы при сбросе). Бомба, сброшенная
     * отсюда, придёт в точку ({@link #miss}), и дальше ждать нечего: B-2 дошёл до дальности сброса для своей высоты над
     * точкой ({@code lineRatio} × высота — угол на точку тот же, что на эшелоне) или со следующего тика бомба уже
     * промахнётся — край окна (ниже ~160 блоков бомбе со скоростью B-2 не хватает места до черты, чтобы опустить нос, и
     * край дальше черты). Край ищется по курсу не дальше высоты, дуги опускания носа ({@link #pullOver}) и
     * {@link #EDGE_REACH}: дальше попадания рваные (окно в пару тиков между промахами — не край). Падение проигрывается
     * теми же шагами, что у самой бомбы, — на ровной земле она придёт туда же. С любой высоты; промах отовсюду (точка
     * слишком близко впереди, сзади, выше B-2) — заход снова.
     */
    public static boolean releaseNow(Vec3 pos, Vec3 step, Vec3 aim, double lineRatio) {
        double dx = aim.x - pos.x, dz = aim.z - pos.z;
        double horizontal = Math.sqrt(dx * dx + dz * dz), height = pos.y - aim.y, line = height * lineRatio;
        // край — по курсу: точка сбоку отодвигает его на свой отступ (иначе ниже 100 блоков сбоку край не нашёлся бы)
        double course = Math.sqrt(step.x * step.x + step.z * step.z), along = (step.x * dx + step.z * dz) / course;
        if (horizontal > line && along > height + pullOver(step.length()) + EDGE_REACH || miss(pos, step, aim) > 0) return false;
        return horizontal <= line || miss(pos.add(step), step, aim) > 0;
    }

    /**
     * Промах бомбы, сброшенной сейчас из {@code release} (там, где B-2) по точке {@code aim} на
     * ровной земле ({@link #GROUND_ABOVE_AIM} над точкой): 0 — придёт в точку (ближе шага и {@link #REACH_PAD}), иначе —
     * сколько блоков по горизонтали от точки она войдёт в землю. Земля — как у бомбы в мире
     * ({@code StrikeProjectile.advance}): сначала попадание в точку, потом преграда на отрезке носа от его середины до
     * конца после шага — нос касается земли на тик раньше, чем бомба подходит к точке на шаг и запас, и на краю окна
     * сброса это промах на 13–18 блоков. Бомба сходит со скоростью носителя {@code velocity} (блоков/тик): величина,
     * курс и тангаж — его.
     */
    public static double miss(Vec3 release, Vec3 velocity, Vec3 aim) {
        double ground = aim.y + GROUND_ABOVE_AIM;
        float[] angles = FlightController.anglesTo(Vec3.ZERO, velocity);
        FlightController flight = new FlightController(angles[0], angles[1]);
        Vec3 pos = release.add(0, -DROP_BELOW, 0);
        double speed = velocity.length();
        for (int t = 0; t < MAX_TICKS; t++) {
            speed = steer(flight, pos, speed, aim);
            if (pos.distanceTo(aim) <= speed + REACH_PAD) return 0;
            Vec3 dir = flight.forward();
            Vec3 noseFrom = pos.add(dir.scale(NOSE * 0.5)), noseTo = pos.add(dir.scale(speed + NOSE));
            if (noseTo.y <= ground) {
                Vec3 at = noseFrom.y <= ground ? noseFrom : noseFrom.lerp(noseTo, (noseFrom.y - ground) / (noseFrom.y - noseTo.y));
                return Math.sqrt((at.x - aim.x) * (at.x - aim.x) + (at.z - aim.z) * (at.z - aim.z));
            }
            pos = pos.add(dir.scale(speed));
        }
        return Double.POSITIVE_INFINITY;
    }
}
