package ua.zentix.incoming.guidance;

import net.minecraft.world.phys.Vec3;
import ua.zentix.incoming.entity.FlightPhase;
import ua.zentix.incoming.strike.WeaponSpec;

/**
 * Закон полёта крылатой ракеты после старта: турбина разгоняет до маршевой, снижение на бреющий полёт над рельефом
 * и не ниже цели на высоте из паспорта, на последнем участке — горка (при длинном заходе) и пикирование по дуге
 * с разгоном до скорости пике. Старт с пусковой, цель, ядерный воздушный подрыв и шаг полёта — у сущности; здесь —
 * только команды органам управления ({@link Craft}).
 */
public final class MissileAutopilot {
    /** Горка кончается, когда цель на столько градусов под горизонтом… */
    private static final double DIVE_PITCH = 24;
    /** …или ракета на столько блоков выше цели. */
    private static final double POP_UP_HEIGHT = 32;
    /** На наборе после ускорителя — не ниже стольких блоков над рельефом впереди. */
    private static final double CLIMB_ABOVE_RELIEF = 25;
    /**
     * Атака (горка или пике) — только когда цель впереди, не дальше этого угла от курса, °: иначе ракета снижалась бы
     * к цели, ещё разворачиваясь к ней по кругу радиусом в сотни блоков, и задевала бы рельеф на развороте.
     */
    static final double ATTACK_CONE = 30;

    private final WeaponSpec.Airframe air;
    private final Autopilot.Turn climbTurn;
    private final Autopilot.Turn cruiseTurn;
    /** Рельеф впереди. */
    private final Autopilot.ReliefSensor relief = new Autopilot.ReliefSensor();
    /** Горка перед пикированием — только при длинном заходе. */
    private boolean popUp = true;

    public MissileAutopilot(WeaponSpec.Airframe air) {
        this.air = air;
        this.climbTurn = new Autopilot.Turn(0.10, air.lateral() * air.climbTurnShare(), 0.15, 0.15);
        this.cruiseTurn = new Autopilot.Turn(0.15, air.lateral(), 0.3, 0.3);
    }

    public boolean popUp() {
        return popUp;
    }

    public void setPopUp(boolean popUp) {
        this.popUp = popUp;
    }

    /**
     * Уже в воздухе (заход издалека, тесты): бреющий полёт на высоте из паспорта над рельефом и не ниже цели на столько же.
     *
     * @param surface высота рельефа под стартом
     */
    public double airborneAltitude(Vec3 target, double surface) {
        return Math.max(target.y + air.cruiseHeight(), surface + air.cruiseHeight());
    }

    /** Заход на {@code target} с {@code horizontal} блоков по горизонтали: горка — только если места на неё хватает. */
    public void approach(double horizontal) {
        popUp = horizontal >= air.popUpMinRange();
    }

    /** Перенацелили на {@code aim}: атака заново, горка — по новому заходу. */
    public void retarget(Craft c, Vec3 aim) {
        approach(Bearing.of(c.position(), aim).horizontal());
        if (c.phase() == FlightPhase.TERMINAL || c.phase() == FlightPhase.POP_UP) c.setPhase(FlightPhase.CRUISE);
    }

    /**
     * Атака без горки (заход ближе {@link WeaponSpec.Airframe#popUpMinRange}) — с бреющего полёта прямо в пике, когда цель
     * под углом θ, на который ракета успевает опустить нос, потратив не больше половины высоты над целью h: дуга
     * выхода в пике радиуса R = v²/((n + 1)·g) (подъёмная сила вниз и вес) опускает на R·(1 − cos θ). Раньше пике шло
     * бы полого над рельефом, который ракета на атаке не видит; позже — нос не успевал бы к цели.
     *
     * @return наименьший угол цели под горизонтом, °
     */
    private double directDive(Craft c, Vec3 aim) {
        double v = c.speed(), n = air.article().loadFactor(), h = Math.max(1, c.position().y - aim.y);
        double r = v * v / ((n + 1) * FlightController.G);
        return Math.toDegrees(Math.acos(Math.max(-1, Math.min(1, 1 - 0.5 * h / r))));
    }

    /**
     * Тик полёта после старта.
     *
     * @param aim      точка цели
     * @param nav      куда держать курс: точка маршрута или цель
     * @param finalLeg маршрут пройден: последний участок — на цель
     */
    public void fly(Craft c, Vec3 aim, Vec3 nav, boolean finalLeg) {
        Bearing b = Bearing.of(c.position(), aim);
        Bearing n = Bearing.of(c.position(), nav);
        FlightPhase ph = c.phase();
        Autopilot.Turn turn = ph == FlightPhase.CLIMB ? climbTurn : cruiseTurn;
        if (ph == FlightPhase.CLIMB) {
            // турбина набирает тягу; ракета переходит с подъёма на снижение к бреющему полёту
            Autopilot.accelerate(c, air, air.cruiseSpeed(), 1, 0.09);
            double terrain = relief.reliefAhead(c, nav, turn, air);
            Autopilot.holdAltitude(c, air, Math.max(terrain + CLIMB_ABOVE_RELIEF, aim.y + air.cruiseHeight()), 0.25, 4, 0.6);
            // маршевая — предел, к которому тяга подходит асимптотически (на ней тяга = сопротивление): набор кончается
            // раньше, на доле, ниже которой автопилот не тратит скорость на набор
            if (c.speed() >= Autopilot.KEEP_SPEED * air.cruiseSpeed() && c.phaseAge() > 40) c.setPhase(FlightPhase.CRUISE);
        }
        // цель внутри круга разворота (сместилась вбок на атаке, перенацеливание, игрок телепортировался): атака
        // отменяется, ракета уходит прямо, пока цель не выйдет из круга, и заходит снова
        boolean outOfTurn = Autopilot.outOfTurn(c, nav, n, air.reattackMin(), turn);
        if (outOfTurn && (c.phase() == FlightPhase.TERMINAL || c.phase() == FlightPhase.POP_UP)) c.setPhase(FlightPhase.CRUISE);
        boolean ahead = Math.abs(net.minecraft.util.Mth.wrapDegrees(b.yaw() - c.flight().yaw())) <= ATTACK_CONE;
        if (c.phase() == FlightPhase.CRUISE && finalLeg && !outOfTurn && ahead
                && (popUp ? b.horizontal() <= air.terminalRange() : b.pitch() >= directDive(c, aim))) {
            c.setPhase(popUp ? FlightPhase.POP_UP : FlightPhase.TERMINAL);
        }
        if (c.phase() == FlightPhase.POP_UP && (b.pitch() >= DIVE_PITCH || c.position().y >= aim.y + POP_UP_HEIGHT)) {
            c.setPhase(FlightPhase.TERMINAL);
        }

        switch (c.phase()) {
            case CRUISE -> {
                Autopilot.accelerate(c, air, air.cruiseSpeed(), 1, 0.09);
                double terrain = relief.reliefAhead(c, nav, turn, air);
                Autopilot.holdAltitude(c, air, Math.max(terrain + air.cruiseHeight(), aim.y + air.cruiseHeight()), 0.30, 8, 1.8);
            }
            case POP_UP -> c.flight().holdPitch(-20, 0.30, 8, 1.8);
            case TERMINAL -> {
                c.flight().arcPitch(b.pitch(), c.speed(), b.distance(), 16, 3.5);
                Autopilot.accelerate(c, air, air.diveSpeed(), 1, 0.1);
            }
            default -> {}
        }
        Autopilot.steer(c, outOfTurn, n, turn);
    }
}
