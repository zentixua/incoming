package ua.zentix.incoming.guidance;

import net.minecraft.world.phys.Vec3;
import ua.zentix.incoming.strike.WeaponSpec;
import java.util.Arrays;

/**
 * Общие поведения управляемого полёта — один раз для всех законов ({@link DroneAutopilot}, {@link MissileAutopilot},
 * B-2): точка внутри круга разворота и уход на повторный заход, курс на точку маршрута. Чистый код без мира Minecraft.
 */
public final class Autopilot {
    private Autopilot() {}

    /**
     * Разворот по курсу на участке полёта.
     *
     * @param gain   усиление поворота к курсу на точку
     * @param rate   предельная угловая скорость, °/тик (паспорт)
     * @param accel  предельное угловое ускорение, °/тик²
     * @param settle угловое ускорение, с которым гасится поворот при уходе на повторный заход
     */
    public record Turn(double gain, double rate, double accel, double settle) {
        /** Разворот с усилением {@code gain} и пределами участка полёта из паспорта: гасится так же быстро, как набирается. */
        public static Turn of(double gain, WeaponSpec.Agility a) {
            return new Turn(gain, a.turnRate(), a.turnAccel(), a.turnAccel());
        }
    }

    /** Ближе этого по горизонтали курс на точку не трогаем: над самой точкой он скачет. */
    private static final double HOLD_COURSE = 8;

    /**
     * Точка внутри круга разворота: с предельной угловой скоростью {@code maxRateDeg} °/тик снаряд до неё не довернёт
     * и кружил бы вокруг неё, пока не кончится запас хода (крылатая ракета на 4 блоках/тик и 3°/тик разворачивается
     * по кругу радиусом ~80 блоков: цель, сместившаяся вбок на атаке, остаётся внутри). Такой снаряд сначала уходит
     * прямо, пока точка не выйдет из круга, и заходит снова. С запасом на разгон угловой скорости — {@link WeaponSpec#TURN_MARGIN}.
     */
    public static boolean insideTurn(Vec3 pos, Vec3 forward, double speed, Vec3 point, double maxRateDeg) {
        double fl = Math.sqrt(forward.x * forward.x + forward.z * forward.z);
        if (fl < 1e-6 || speed <= 0) return false;
        double fx = forward.x / fl, fz = forward.z / fl;
        double dx = point.x - pos.x, dz = point.z - pos.z;
        double r = WeaponSpec.turnRadius(speed * fl, maxRateDeg) * WeaponSpec.TURN_MARGIN;
        // центр разворота — сбоку, в сторону точки
        double nx = -fz, nz = fx;
        if (nx * dx + nz * dz < 0) {
            nx = -nx;
            nz = -nz;
        }
        double cx = dx - nx * r, cz = dz - nz * r;
        return cx * cx + cz * cz < r * r;
    }

    /**
     * Точка маршрута {@code nav} внутри круга разворота (промах в пике, цель ушла вбок, точка в воздухе, где цель
     * пропала): до неё не довернуть — атака отменяется, снаряд уходит прямо и заходит снова. Ближе {@code reattackMin}
     * по горизонтали — нет: небольшой промах добирает неконтактный взрыватель, а пролетев, снаряд зайдёт снова.
     */
    public static boolean outOfTurn(Craft c, Vec3 nav, Bearing toNav, double reattackMin, double rate) {
        return outOfTurn(c.position(), c.flight().forward(), c.speed(), nav, toNav, reattackMin, rate);
    }

    /** То же для места {@code pos}, носа {@code forward} и скорости {@code speed} (проигрыш пути вперёд, {@link #track}). */
    static boolean outOfTurn(Vec3 pos, Vec3 forward, double speed, Vec3 nav, Bearing toNav, double reattackMin, double rate) {
        return toNav.horizontal() > reattackMin && insideTurn(pos, forward, speed, nav, rate);
    }

    /** Курс: на повторном заходе — прямо, гася поворот; иначе — на точку маршрута (над самой точкой курс не трогаем). */
    public static void steer(Craft c, boolean outOfTurn, Bearing toNav, Turn turn) {
        steer(c.flight(), outOfTurn, toNav, turn);
    }

    static void steer(FlightController flight, boolean outOfTurn, Bearing toNav, Turn turn) {
        if (outOfTurn) {
            flight.settleYaw(turn.settle());
        } else if (toNav.horizontal() > HOLD_COURSE) {
            flight.steerYaw(toNav.yaw(), turn.gain(), turn.rate(), turn.accel());
        }
    }

    // ---------------------------------------------------------------- прямая до цели на заходе

    /**
     * Запас пути на доворот носа к цели (игровое число, не по физике): с закрытой прямой (цель под крышей, высотка или
     * гребень вплотную к цели) снаряд всё равно входит в атаку, когда до цели осталось столько путей доворота
     * ({@link #turnDistance}), — позже нос не успел бы к цели, и снаряд пролетал бы её. Пока путь длиннее, он в атаку
     * не идёт ({@link Sightline}).
     */
    static final double DIVE_MARGIN = 1.5;
    /**
     * Закрытую прямую до цели проверять снова через столько тиков: луч по клеткам блоков — до сотен блоков, у залпа —
     * у каждого снаряда. За паузу шахед пролетает ~8 блоков, ракета ~16: это покрывает {@link #DIVE_MARGIN}.
     */
    static final int SIGHT_RECHECK = 4;

    /**
     * Путь, за который нос доворачивает от нынешнего тангажа до линии на цель {@code aim} с предельной угловой
     * скоростью тангажа {@code rate} °/тик и её ускорением {@code accel} °/тик²: разгон угловой скорости и торможение,
     * при большом угле — с полкой на пределе. Цель позади (за плоскостью поперёк курса) — линия за отвесом:
     * 180° − угол под горизонтом. Иначе набирающая высоту ракета проходила бы над целью, а доворот считался бы не больше
     * чем до отвесного пике, и атака не начиналась бы вовсе.
     */
    public static double turnDistance(Craft c, Vec3 aim, Bearing toAim, double rate, double accel) {
        Vec3 pos = c.position(), f = c.flight().forward();
        boolean behind = (aim.x - pos.x) * f.x + (aim.z - pos.z) * f.z < 0;
        double los = behind ? 180 - toAim.pitch() : toAim.pitch();
        double turn = Math.max(0, los - c.flight().pitch());
        double ticks = turn <= rate * rate / accel
                ? 2 * Math.sqrt(turn / accel)
                : turn / rate + rate / accel;
        return c.speed() * ticks;
    }

    /**
     * Игровое правило захода (одно у шахеда и ракеты, без упреждения по идущей цели): если прямая от снаряда к цели
     * закрыта блоками ({@link Craft#clearAlong} — только готовые чанки), снаряд в атаку не идёт, пока прямая не
     * откроется. Ракета при этом набирает высоту горкой, а закрылась прямая в пике (цель зашла за склон) — выходит
     * в горку снова ({@link MissileAutopilot}); шахед идёт дальше на высоте крейсера ({@link DroneAutopilot}: набор
     * ему вредит). Не дольше, чем нос успевает довернуть к цели ({@link #DIVE_MARGIN}): ближе снаряд атакует как есть —
     * высотку вплотную к цели и цель под крышей правило не обходит.
     */
    public static final class Sightline {
        private boolean blocked;
        /**
         * Атака уже вынужденная ({@link #DIVE_MARGIN}): до конца захода прямую не мерить — иначе снаряд, довернув нос,
         * вернулся бы в набор. Не сохраняется: после загрузки посреди такого пике ракета несколько тиков тянет в горку,
         * пока путь доворота снова не станет вынужденным.
         */
        private boolean committed;
        /** Сколько тиков не мерить прямую заново (не сохраняется: после загрузки — замер сразу). */
        private int wait;

        /**
         * Прямая до {@code aim} закрыта, кроме последних {@code margin} блоков у цели (их достаёт взрыватель), и до
         * цели дальше {@link #DIVE_MARGIN} путей доворота носа с пределами атаки {@code rate},
         * {@code accel}: в атаку не идти. Замер — раз в {@link #SIGHT_RECHECK} тиков.
         */
        public boolean masked(Craft c, Vec3 aim, Bearing toAim, double margin, double rate, double accel) {
            if (committed) return false;
            if (toAim.distance() <= turnDistance(c, aim, toAim, rate, accel) * DIVE_MARGIN) {
                committed = true;
                return false;
            }
            if (wait > 0) {
                wait--;
                return blocked;
            }
            blocked = c.clearAlong(aim, margin) != Double.POSITIVE_INFINITY;
            wait = SIGHT_RECHECK - 1;
            return blocked;
        }

        /** Атака вынужденная, а прямая при последнем замере была закрыта: в атаку идти сейчас же. */
        public boolean forced() {
            return committed && blocked;
        }

        /** Вне захода или новая цель: следующий заход меряет прямую сразу. */
        public void forget() {
            wait = 0;
            blocked = false;
            committed = false;
        }
    }

    // ---------------------------------------------------------------- рельеф впереди

    /**
     * Полуширина полосы рельефа над путём, блоков: корпус (размах шахеда ~2,5 блока) и снос на развороте; тонкая
     * колонка (забор, мачта) в полосе не пропускается ({@link Corridor}).
     */
    static final double CORRIDOR_HALF_WIDTH = 1.5;
    /** Путь вперёд проигрывается поворотами не дальше стольких тиков, дальше — прямо по последнему курсу. */
    static final int TRACK_TICKS = 64;
    /** Точка пути — раз в столько тиков проигрыша: хорда дуги разворота (3°/тик) отходит от неё меньше чем на полблока. */
    private static final int TRACK_STEP = 4;
    /** Доля наибольшего угла набора ({@link WeaponSpec.Handling#climbAngle}) в {@link #gradient}. */
    static final double CLIMB_SHARE = 0.5;

    /**
     * Наклон набора перед препятствием впереди: тангенс доли {@link #CLIMB_SHARE} наибольшего угла набора {@code climbAngle};
     * остальное — запас на то, что снаряд держит высоту фильтром и П-регулятором тангажа, а не по прямой.
     */
    static double gradient(double climbAngle) {
        return Math.tan(Math.toRadians(climbAngle * CLIMB_SHARE));
    }
    /**
     * Сколько тиков пути снаряд только выходит на набор: фильтр заданной высоты ({@link AltitudeHold}, 1/5 за тик)
     * и тангаж с ограничением угловой скорости и ускорения. На этом пути набора нет — рельеф на нём учитывается целиком
     * (у ракеты — 120 блоков, у шахеда — 63). С меньшим запасом ракета, перенацеленная внутри круга разворота над
     * холмами, шла бы на атаку слишком низко и задевала бы склон (свойства полёта, {@code AutopilotPropertiesTest}).
     */
    static final int REACTION_TICKS = 30;

    /**
     * Какую высоту рельефа надо иметь в виду сейчас: наибольшее по полосе ({@link #CORRIDOR_HALF_WIDTH}) над путём на
     * {@link WeaponSpec.Airframe#reliefLookahead} блоков вперёд ({@link #track}) за вычетом высоты, которую снаряд
     * успеет набрать до колонки ({@link #climb}). Без вычета длинная полоса держала бы ракету над самым высоким на
     * сотни блоков вперёд, и бреющего полёта не было бы. Три точки по курсу вместо полосы пропускали бы мачту из забора
     * и узкий дом между ними, а фильтр высоты сглаживал бы короткий пик: ракета на бреющем и шахеды залпа бились бы в них.
     */
    public static double reliefAhead(Craft c, Vec3 nav, Turn turn, WeaponSpec.Airframe air) {
        return climbOver(track(c, nav, turn, air.attack().reattackMin(), air.reliefLookahead()), c.speed(), air.handling().climbAngle(), c::relief);
    }

    /**
     * Высота, с которой снаряд на скорости {@code speed}, набирая высоту по {@link #climb}, пройдёт над рельефом полосы
     * ({@link #CORRIDOR_HALF_WIDTH}) над путём {@code track} — то же, что {@link #reliefAhead}, по заданному пути: место
     * пусковой ({@code LaunchSite}) проверяет им, что подъём после взведения автопилоту по силам.
     */
    public static double climbOver(double[] track, double speed, double climbAngle, Corridor.Relief relief) {
        return Corridor.highest(track, CORRIDOR_HALF_WIDTH, climb(speed, climbAngle), relief);
    }

    /**
     * Набор высоты снаряда на скорости {@code speed} с наибольшим углом набора {@code climbAngle}: {@link #REACTION_TICKS}
     * тиков пути без набора, потом {@link #gradient}.
     */
    static Corridor.Climb climb(double speed, double climbAngle) {
        return new Corridor.Climb(REACTION_TICKS * speed, gradient(climbAngle));
    }

    /** Полоса рельефа впереди считается заново раз в столько тиков ({@link ReliefSensor}). */
    static final int REFRESH_TICKS = 4;

    /**
     * Датчик рельефа впереди одного снаряда ({@link #reliefAhead}) с замером раз в {@link #REFRESH_TICKS} тиков: полоса —
     * сотни колонок, а за паузу меняется мало. Между замерами ответ растёт на {@link #gradient} × путь с замера:
     * колонки впереди стали ближе, и набрать до них снаряд успеет меньше (оценка сверху — у ближних колонок, где набора
     * нет, это лишний запас не больше наклона × пути за паузу). Не сохраняется: после загрузки — замер сразу.
     */
    public static final class ReliefSensor {
        private double value;
        private Vec3 at;
        private int age;

        public double reliefAhead(Craft c, Vec3 nav, Turn turn, WeaponSpec.Airframe air) {
            Vec3 pos = c.position();
            if (at == null || age >= REFRESH_TICKS) {
                value = Autopilot.reliefAhead(c, nav, turn, air);
                at = pos;
                age = 0;
            }
            age++;
            return value + gradient(air.handling().climbAngle()) * Math.hypot(pos.x - at.x, pos.z - at.z);
        }
    }

    /** Наибольшая высота рельефа под полосой над путём {@code track} — без наклона (возврат в мир, «Ланцет»). */
    public static double reliefAlong(double[] track, Corridor.Relief relief) {
        return Corridor.highest(track, CORRIDOR_HALF_WIDTH, Corridor.Climb.NONE, relief);
    }

    /**
     * Путь по земле на {@code lookahead} блоков вперёд (x0, z0, x1, z1, …): повороты к {@code nav} проигрываются копией
     * органов управления ({@link FlightController#copy}) с тем же законом курса ({@link #steer}, повторный заход —
     * {@link #outOfTurn}) не дальше {@link #TRACK_TICKS} тиков, остаток — прямо. Скорость — нынешняя, по горизонтали:
     * с запасом на наборе и снижении.
     */
    static double[] track(Craft c, Vec3 nav, Turn turn, double reattackMin, double lookahead) {
        Vec3 pos = c.position();
        double[] out = new double[2 * (TRACK_TICKS / TRACK_STEP + 3)];
        int n = 0;
        out[n++] = pos.x;
        out[n++] = pos.z;
        if (lookahead <= 0) return Arrays.copyOf(out, n);
        FlightController f = c.flight().copy();
        double step = Math.max(0.5, c.speed()), x = pos.x, z = pos.z, travelled = 0;
        for (int t = 1; t <= TRACK_TICKS && travelled < lookahead; t++) {
            Vec3 here = new Vec3(x, pos.y, z);
            Bearing toNav = Bearing.of(here, nav);
            steer(f, outOfTurn(here, f.forward(), step, nav, toNav, reattackMin, turn.rate()), toNav, turn);
            double yaw = Math.toRadians(f.yaw()), d = Math.min(step, lookahead - travelled);
            x -= Math.sin(yaw) * d;
            z += Math.cos(yaw) * d;
            travelled += d;
            if (t % TRACK_STEP == 0 || travelled >= lookahead) {
                out[n++] = x;
                out[n++] = z;
            }
        }
        if (travelled < lookahead) {
            double yaw = Math.toRadians(f.yaw()), d = lookahead - travelled;
            out[n++] = x - Math.sin(yaw) * d;
            out[n++] = z + Math.cos(yaw) * d;
        }
        return Arrays.copyOf(out, n);
    }
}
