package ua.zentix.incoming.guidance;

import net.minecraft.world.phys.Vec3;
import ua.zentix.incoming.entity.FlightPhase;
import ua.zentix.incoming.strike.WeaponSpec;

/**
 * Закон полёта шахеда после старта: набор высоты на винте, крейсер над рельефом и не ниже цели+30, пикирование по
 * дуге, когда на последнем участке цель уходит на 18° под горизонт и прямая до неё свободна от блоков, с разгоном
 * до скорости пике. Закрытая прямая — то же правило захода, что у ракеты ({@link Autopilot.Sightline}), но без набора:
 * шахед идёт дальше на высоте крейсера и пикирует круче, когда прямая откроется или нос перестанет успевать к цели.
 * Набор тут вредит: прямая над препятствием открывается под тем же углом к цели (через его верх), только нос уже задран,
 * и пике на 4°/тик к этому углу не успевает — с набором шахед пролетал цель за домом в 45 блоках от неё и ближе
 * ({@code DroneDiveTest}). Старт с пусковой, цель и шаг полёта — у сущности; здесь — только команды органам
 * управления ({@link Craft}).
 */
public final class DroneAutopilot {
    /** Над целью крейсер не ниже стольких блоков. */
    private static final double ABOVE_TARGET = 30;
    /** Над рельефом впереди — не ниже стольких блоков. */
    private static final double ABOVE_RELIEF = 18;
    /** Пикирование — когда цель на столько градусов под горизонтом. */
    private static final double DIVE_PITCH = 18;
    /** Пике: предельная угловая скорость тангажа, °/тик, и её предельное ускорение, °/тик². */
    /**
     * Пока прямая до цели закрыта, свой путь вперёд на высоте полёта проверяется на столько блоков: шахед набирает
     * высоту над самой высокой частью города, где мачты и башни надо видеть заранее: набор — не круче 15°. Полоса
     * рельефа ({@link Autopilot#reliefAhead}) их видит; эта проверка — вдобавок по клеткам блоков на самой прямой
     * вперёд, раз в {@link Autopilot#SIGHT_RECHECK} тиков. На 90 блоках набор поднимает ~20 блоков.
     */
    private static final double PATH_AHEAD = 90;

    private final WeaponSpec.Airframe air;
    private final Autopilot.Turn climbTurn;
    private final Autopilot.Turn cruiseTurn;
    /** Рельеф впереди. */
    private final Autopilot.ReliefSensor relief = new Autopilot.ReliefSensor();
    /** Высота крейсера: не спускаемся ниже, даже если рельеф понижается. */
    private double cruiseAlt;
    /** Прямая до цели на заходе. */
    private final Autopilot.Sightline sight = new Autopilot.Sightline();
    /** Сколько тиков ещё не проверять свой путь вперёд, пока прямая до цели закрыта (не сохраняется). */
    private int pathWait;
    /**
     * Верх препятствия на своём пути впереди, найденного, пока прямая до цели закрыта: крейсер держится над ним
     * на {@link #ABOVE_RELIEF}; {@code -Infinity} — препятствия нет. Сбрасывается вне окна пике (не сохраняется:
     * после загрузки — проверить сразу).
     */
    private double obstacleTop = Double.NEGATIVE_INFINITY;

    public DroneAutopilot(WeaponSpec.Airframe air) {
        this.air = air;
        // на старте разворот мягче: скорость ещё мала
        this.climbTurn = Autopilot.Turn.of(0.08, air.handling().climb());
        this.cruiseTurn = Autopilot.Turn.of(0.15, air.handling().cruise());
    }

    public double cruiseAlt() {
        return cruiseAlt;
    }

    public void setCruiseAlt(double cruiseAlt) {
        this.cruiseAlt = cruiseAlt;
    }

    /**
     * Уже в воздухе (заход издалека, тесты): высота полёта = max(старт, цель+30, рельеф+20), она же — крейсер.
     *
     * @param surface высота рельефа под стартом
     */
    public double airborneAltitude(Vec3 pos, Vec3 target, double surface) {
        double y = Math.max(pos.y, target.y + ABOVE_TARGET);
        y = Math.max(y, surface + 20);
        cruiseAlt = y;
        return y;
    }

    /** С пусковой: крейсер на высоте из паспорта над стартом или целью, что выше. */
    public void fromLauncher(Vec3 rail, Vec3 target) {
        cruiseAlt = Math.max(rail.y, target.y) + air.cruiseHeight();
    }

    /**
     * Тик полёта после старта.
     *
     * @param aim       точка цели
     * @param nav       куда держать курс: точка маршрута или цель
     * @param finalLeg  маршрут пройден: последний участок — на цель
     */
    public void fly(Craft c, Vec3 aim, Vec3 nav, boolean finalLeg) {
        Bearing b = Bearing.of(c.position(), aim);
        Bearing n = Bearing.of(c.position(), nav);
        FlightPhase ph = c.phase();
        Autopilot.Turn turn = ph == FlightPhase.CLIMB ? climbTurn : cruiseTurn;

        if (ph == FlightPhase.CLIMB) {
            // винт на полных оборотах, скорость после ускорителя спадает к крейсерской
            c.throttle(air, air.cruiseSpeed());
            double terrain = relief.reliefAhead(c, nav, turn, air);
            c.holdAltitude(Math.max(cruiseAlt, terrain + ABOVE_RELIEF), 0.10, air.handling(), air.handling().climb());
            if (c.phaseAge() > 60 && Math.abs(cruiseAlt - c.position().y) < 6) c.setPhase(FlightPhase.CRUISE);
        }
        // цель внутри круга разворота: пике отменяется, шахед уходит прямо, набирая высоту, и заходит снова
        boolean outOfTurn = Autopilot.outOfTurn(c, nav, n, air.attack().reattackMin(), turn.rate());
        if (outOfTurn && c.phase() == FlightPhase.TERMINAL) c.setPhase(FlightPhase.CRUISE);
        // пикирование — как только цель под нужным углом, даже если высота ещё набирается (цель рядом, перенацеливание);
        // прямая до цели — последней: луч по блокам дороже остальных условий. В пике прямую не проверяем: дуга пике лежит
        // над открытой прямой, а выход из пике у шахеда медленный (тангаж крейсера — 1,2°/тик)
        boolean window = (c.phase() == FlightPhase.CRUISE || c.phase() == FlightPhase.CLIMB) && finalLeg && b.pitch() >= DIVE_PITCH && !outOfTurn;
        boolean masked = window && sight.masked(c, aim, b, air.reachPad(), air.handling().dive().pitchRate(), air.handling().dive().pitchAccel());
        if (!window) {
            sight.forget();
            pathWait = 0;
            obstacleTop = Double.NEGATIVE_INFINITY;
        } else if (masked) {
            watchPath(c);
        } else {
            c.setPhase(FlightPhase.TERMINAL);
        }

        if (c.phase() == FlightPhase.CRUISE) {
            c.throttle(air, air.cruiseSpeed());
            double terrain = relief.reliefAhead(c, nav, turn, air);
            double desired = Math.max(Math.max(Math.max(terrain, obstacleTop) + ABOVE_RELIEF, cruiseAlt), aim.y + ABOVE_TARGET);
            c.holdAltitude(desired, 0.12, air.handling(), air.handling().cruise());
        } else if (c.phase() == FlightPhase.TERMINAL) {
            WeaponSpec.Agility dive = air.handling().dive();
            c.flight().arcPitch(b.pitch(), c.speed(), b.distance(), dive.pitchRate(), dive.pitchAccel());
            c.throttle(air, air.diveSpeed());
        }
        Autopilot.steer(c, outOfTurn, n, turn);
    }

    /**
     * Пока прямая до цели закрыта, раз в {@link Autopilot#SIGHT_RECHECK} тиков проверяется свой путь вперёд на высоте
     * полёта ({@link #PATH_AHEAD}): верх первого препятствия на нём — в {@link #obstacleTop} (наибольший за ожидание).
     */
    private void watchPath(Craft c) {
        if (pathWait > 0) {
            pathWait--;
            return;
        }
        pathWait = Autopilot.SIGHT_RECHECK - 1;
        Vec3 f = c.flight().forward();
        Vec3 level = new Vec3(f.x, 0, f.z).normalize();
        double free = c.clearAlong(c.position().add(level.scale(PATH_AHEAD)), 0);
        // наибольший верх за ожидание: над препятствием путь снова свободен, и крейсер опускался бы на него
        if (free != Double.POSITIVE_INFINITY) {
            Vec3 p = c.position(), at = p.add(level.scale(free + 1));
            obstacleTop = Math.max(obstacleTop, Autopilot.reliefAlong(new double[]{p.x, p.z, at.x, at.z}, c::relief));
        }
    }
}
