package ua.zentix.incoming.guidance;

import net.minecraft.world.phys.Vec3;
import ua.zentix.incoming.entity.FlightPhase;
import ua.zentix.incoming.strike.WeaponSpec;

/**
 * Cruise missile post-launch flight law: turbine accelerates to cruise, descends to terrain-hugging flight above
 * terrain and at least spec height above target; final leg has popup (long approach) and arcing dive accelerating
 * to dive speed. If terrain blocks the target line (behind a hill, quarry floor), the missile does not dive but
 * climbs in popup until clear ({@link Autopilot.Sightline}). Launcher start, target, nuclear airburst, and flight
 * step belong to the entity; here only control commands ({@link Craft}).
 */
public final class MissileAutopilot {
    /** Popup ends when target is this many degrees below horizon… */
    private static final double DIVE_PITCH = 24;
    /** …or missile is this many blocks above target. */
    private static final double POP_UP_HEIGHT = 32;
    /** During climb after booster, at least this many blocks above terrain ahead. */
    private static final double CLIMB_ABOVE_RELIEF = 25;

    private final WeaponSpec.Airframe air;
    private final Autopilot.Turn climbTurn;
    private final Autopilot.Turn cruiseTurn;
    /** Terrain ahead. */
    private final Autopilot.ReliefSensor relief = new Autopilot.ReliefSensor();
    /** Line to target on approach. */
    private final Autopilot.Sightline sight = new Autopilot.Sightline();
    /** Pre-dive popup only on a long approach. */
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
     * Already airborne (approach from afar, tests): terrain-hugging at spec height above terrain and at least as
     * high above target.
     *
     * @param surface terrain height under launch
     */
    public double airborneAltitude(Vec3 target, double surface) {
        return Math.max(target.y + air.cruiseHeight(), surface + air.cruiseHeight());
    }

    /** Approach {@code target} from {@code horizontal} horizontal blocks: popup only if there is room. */
    public void approach(double horizontal) {
        popUp = horizontal >= air.attack().popUpMinRange();
    }

    /** Retargeted to {@code aim}: restart attack; popup depends on new approach. */
    public void retarget(Craft c, Vec3 aim) {
        approach(Bearing.of(c.position(), aim).horizontal());
        sight.forget();
        if (c.phase() == FlightPhase.TERMINAL || c.phase() == FlightPhase.POP_UP) c.setPhase(FlightPhase.CRUISE);
    }

    /**
     * Post-launch flight tick.
     *
     * @param aim target point
     * @param nav heading destination: waypoint or target
     * @param finalLeg route complete: final leg toward target
     */
    public void fly(Craft c, Vec3 aim, Vec3 nav, boolean finalLeg) {
        Bearing b = Bearing.of(c.position(), aim);
        Bearing n = Bearing.of(c.position(), nav);
        FlightPhase ph = c.phase();
        Autopilot.Turn turn = ph == FlightPhase.CLIMB ? climbTurn : cruiseTurn;
        if (ph == FlightPhase.CLIMB) {
            // Turbine gains thrust; missile transitions from ascent to terrain-hugging descent.
            c.throttle(air, air.cruiseSpeed());
            double terrain = relief.reliefAhead(c, nav, turn, air);
            c.holdAltitude(Math.max(terrain + CLIMB_ABOVE_RELIEF, aim.y + air.cruiseHeight()), 0.25, air.handling(), air.handling().climb());
            // Turbine at cruise: climb speed is slightly lower (altitude/speed exchange); allow 5% for it.
            if (c.speed() >= air.cruiseSpeed() * 0.95 && c.phaseAge() > 40) c.setPhase(FlightPhase.CRUISE);
        }
        // Target inside turning circle (moved sideways during attack, retargeted, player teleported): cancel
        // attack, continue straight until target leaves the circle, then approach again.
        boolean outOfTurn = Autopilot.outOfTurn(c, nav, n, air.attack().reattackMin(), turn.rate());
        if (outOfTurn && (c.phase() == FlightPhase.TERMINAL || c.phase() == FlightPhase.POP_UP)) c.setPhase(FlightPhase.CRUISE);
        if (c.phase() == FlightPhase.CRUISE && finalLeg && b.horizontal() <= air.attack().terminalRange() && !outOfTurn) {
            c.setPhase(popUp ? FlightPhase.POP_UP : FlightPhase.TERMINAL);
        }
        // Target line blocked on approach: popup until clear (including exiting a dive into popup).
        boolean attack = c.phase() == FlightPhase.POP_UP || c.phase() == FlightPhase.TERMINAL;
        boolean masked = attack && sight.masked(c, aim, b, air.reachPad(), air.handling().dive().pitchRate(), air.handling().dive().pitchAccel());
        if (!attack) sight.forget();
        if (masked) {
            c.setPhase(FlightPhase.POP_UP);
        } else if (c.phase() == FlightPhase.POP_UP
                && (sight.forced() || !popUp || b.pitch() >= DIVE_PITCH || c.position().y >= aim.y + POP_UP_HEIGHT)) {
            // Popup ends: normal by angle or height; forced attack or short approach (popup only due to
            // blocked line), immediately.
            c.setPhase(FlightPhase.TERMINAL);
        }

        switch (c.phase()) {
            case CRUISE -> {
                c.throttle(air, air.cruiseSpeed());
                double terrain = relief.reliefAhead(c, nav, turn, air);
                c.holdAltitude(Math.max(terrain + air.cruiseHeight(), aim.y + air.cruiseHeight()), 0.30, air.handling(), air.handling().cruise());
            }
            case POP_UP -> {
                // Popup: spec maximum climb angle (negative nose-up pitch), turbine at cruise; climbing
                // reduces speed.
                c.throttle(air, air.cruiseSpeed());
                c.flight().holdPitch(-air.handling().climbAngle(), 0.30, air.handling().cruise().pitchRate(), air.handling().cruise().pitchAccel());
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
