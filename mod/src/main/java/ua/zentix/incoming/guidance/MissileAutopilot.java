package ua.zentix.incoming.guidance;

import net.minecraft.world.phys.Vec3;
import ua.zentix.incoming.entity.FlightPhase;
import ua.zentix.incoming.strike.WeaponSpec;

/**
 * Закон полёта крылатой ракеты после старта: турбина разгоняет до маршевой, снижение на бреющий полёт над рельефом
 * и не ниже цели на высоте из паспорта, на последнем участке — горка (при длинном заходе) и пикирование по дуге
 * с разгоном до скорости пике. Прямая до цели закрыта рельефом (цель за холмом, на дне карьера) — ракета в пике не
 * идёт, а набирает высоту горкой, пока прямая не откроется ({@link Autopilot.Sightline}). Старт с пусковой, цель, ядерный воздушный подрыв и шаг полёта — у сущности; здесь —
 * только команды органам управления ({@link Craft}).
 */
public final class MissileAutopilot {
    /** Горка кончается, когда цель на столько градусов под горизонтом… */
    private static final double DIVE_PITCH = 24;
    /** …или ракета на столько блоков выше цели. */
    private static final double POP_UP_HEIGHT = 32;
    /** На наборе после ускорителя — не ниже стольких блоков над рельефом впереди. */
    private static final double CLIMB_ABOVE_RELIEF = 25;
    /** Тангаж горки, ° (вверх — отрицательный): набор над рельефом и перед пике (игровое число, не по физике). */
    private static final double POP_UP_PITCH = -20;

    private final WeaponSpec.Airframe air;
    private final Autopilot.Turn climbTurn;
    private final Autopilot.Turn cruiseTurn;
    /** Рельеф впереди. */
    private final Autopilot.ReliefSensor relief = new Autopilot.ReliefSensor();
    /** Прямая до цели на заходе. */
    private final Autopilot.Sightline sight = new Autopilot.Sightline();
    /** Горка перед пикированием — только при длинном заходе. */
    private boolean popUp = true;

    public MissileAutopilot(WeaponSpec.Airframe air) {
        this.air = air;
        this.climbTurn = Autopilot.Turn.of(0.10, air.handling().climb());
        this.cruiseTurn = Autopilot.Turn.of(0.15, air.handling().cruise());
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
        popUp = horizontal >= air.attack().popUpMinRange();
    }

    /** Перенацелили на {@code aim}: атака заново, горка — по новому заходу. */
    public void retarget(Craft c, Vec3 aim) {
        approach(Bearing.of(c.position(), aim).horizontal());
        sight.forget();
        if (c.phase() == FlightPhase.TERMINAL || c.phase() == FlightPhase.POP_UP) c.setPhase(FlightPhase.CRUISE);
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
            c.throttle(air, air.cruiseSpeed());
            double terrain = relief.reliefAhead(c, nav, turn, air);
            c.holdAltitude(Math.max(terrain + CLIMB_ABOVE_RELIEF, aim.y + air.cruiseHeight()), 0.25, air.handling(), air.handling().climb());
            // турбина вышла на маршевую: в наборе скорость чуть ниже (обмен высоты на скорость) — 5 % на это
            if (c.speed() >= air.cruiseSpeed() * 0.95 && c.phaseAge() > 40) c.setPhase(FlightPhase.CRUISE);
        }
        // цель внутри круга разворота (сместилась вбок на атаке, перенацеливание, игрок телепортировался): атака
        // отменяется, ракета уходит прямо, пока цель не выйдет из круга, и заходит снова
        boolean outOfTurn = Autopilot.outOfTurn(c, nav, n, air.attack().reattackMin(), turn.rate());
        if (outOfTurn && (c.phase() == FlightPhase.TERMINAL || c.phase() == FlightPhase.POP_UP)) c.setPhase(FlightPhase.CRUISE);
        if (c.phase() == FlightPhase.CRUISE && finalLeg && b.horizontal() <= air.attack().terminalRange() && !outOfTurn) {
            c.setPhase(popUp ? FlightPhase.POP_UP : FlightPhase.TERMINAL);
        }
        // на заходе прямая до цели закрыта: горка, пока не откроется (и из пике — тоже в горку)
        boolean attack = c.phase() == FlightPhase.POP_UP || c.phase() == FlightPhase.TERMINAL;
        boolean masked = attack && sight.masked(c, aim, b, air.reachPad(), air.handling().dive().pitchRate(), air.handling().dive().pitchAccel());
        if (!attack) sight.forget();
        if (masked) {
            c.setPhase(FlightPhase.POP_UP);
        } else if (c.phase() == FlightPhase.POP_UP
                && (sight.forced() || !popUp || b.pitch() >= DIVE_PITCH || c.position().y >= aim.y + POP_UP_HEIGHT)) {
            // горка кончается: обычная — по углу или высоте; вынужденная атака или короткий заход (горка была только
            // из-за закрытой прямой) — сразу
            c.setPhase(FlightPhase.TERMINAL);
        }

        switch (c.phase()) {
            case CRUISE -> {
                c.throttle(air, air.cruiseSpeed());
                double terrain = relief.reliefAhead(c, nav, turn, air);
                c.holdAltitude(Math.max(terrain + air.cruiseHeight(), aim.y + air.cruiseHeight()), 0.30, air.handling(), air.handling().cruise());
            }
            case POP_UP -> {
                // горка: турбина на маршевой, набор отнимает скорость
                c.throttle(air, air.cruiseSpeed());
                c.flight().holdPitch(POP_UP_PITCH, 0.30, air.handling().cruise().pitchRate(), air.handling().cruise().pitchAccel());
            }
            case TERMINAL -> {
                WeaponSpec.Agility dive = air.handling().dive();
                c.flight().arcPitch(b.pitch(), c.speed(), b.distance(), dive.pitchRate(), dive.pitchAccel());
                c.throttle(air, air.diveSpeed());
            }
            default -> {}
        }
        Autopilot.steer(c, outOfTurn, n, turn);
    }
}
