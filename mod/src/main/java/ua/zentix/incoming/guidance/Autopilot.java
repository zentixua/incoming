package ua.zentix.incoming.guidance;

import net.minecraft.world.phys.Vec3;
import ua.zentix.incoming.strike.WeaponSpec;
import ua.zentix.incoming.util.Relief;
import java.util.Arrays;

/**
 * Shared guided-flight behaviors, defined once for all laws ({@link DroneAutopilot}, {@link MissileAutopilot},
 * B-2): point inside the turn circle, reapproach, waypoint heading. Pure code without a Minecraft world.
 */
public final class Autopilot {
    private Autopilot() {}

    /**
     * Heading turn for a flight phase.
     *
     * @param gain   gain for turning toward point bearing
     * @param rate   angular-speed limit, °/tick (spec)
     * @param accel  angular-acceleration limit, °/tick²
     * @param settle angular acceleration damping the turn during reapproach
     */
    public record Turn(double gain, double rate, double accel, double settle) {
        /** Turn with {@code gain} and spec phase limits: damping as fast as acceleration. */
        public static Turn of(double gain, WeaponSpec.Agility a) {
            return new Turn(gain, a.turnRate(), a.turnAccel(), a.turnAccel());
        }
    }

    /** Keep heading inside this horizontal point distance: directly overhead the bearing jumps. */
    private static final double HOLD_COURSE = 8;

    /**
     * Point inside the turn circle: at maximum angular speed {@code maxRateDeg} °/tick the projectile cannot turn to it
     * and would circle until range ran out (cruise missile at 4 blocks/tick and 3°/tick has a turn
     * radius of ~80 blocks: a target moving sideways during attack remains inside). Such a projectile first flies
     * straight until the point leaves the circle, then reapproaches. Angular-speed acceleration margin: {@link WeaponSpec#TURN_MARGIN}.
     */
    public static boolean insideTurn(Vec3 pos, Vec3 forward, double speed, Vec3 point, double maxRateDeg) {
        double fl = Math.sqrt(forward.x * forward.x + forward.z * forward.z);
        if (fl < 1e-6 || speed <= 0) return false;
        double fx = forward.x / fl, fz = forward.z / fl;
        double dx = point.x - pos.x, dz = point.z - pos.z;
        double r = WeaponSpec.turnRadius(speed * fl, maxRateDeg) * WeaponSpec.TURN_MARGIN;
        // Turn center lies to the side, toward the point.
        double nx = -fz, nz = fx;
        if (nx * dx + nz * dz < 0) {
            nx = -nx;
            nz = -nz;
        }
        double cx = dx - nx * r, cz = dz - nz * r;
        return cx * cx + cz * cz < r * r;
    }

    /**
     * Waypoint {@code nav} is inside the turn circle (dive miss, target moved sideways, airborne point where target
     * disappeared): cannot turn to it — cancel attack, fly straight and reapproach. Not within {@code reattackMin}
     * horizontally: proximity fuze covers small misses; after passing, projectile reapproaches.
     */
    public static boolean outOfTurn(Craft c, Vec3 nav, Bearing toNav, double reattackMin, double rate) {
        return outOfTurn(c.position(), c.flight().forward(), c.speed(), nav, toNav, reattackMin, rate);
    }

    /** Same for {@code pos}, nose {@code forward}, speed {@code speed} (forward path prediction, {@link #track}). */
    static boolean outOfTurn(Vec3 pos, Vec3 forward, double speed, Vec3 nav, Bearing toNav, double reattackMin, double rate) {
        return toNav.horizontal() > reattackMin && insideTurn(pos, forward, speed, nav, rate);
    }

    /** Heading: straight while damping the turn on reapproach; otherwise toward waypoint (unchanged directly overhead). */
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

    // ---------------------------------------------------------------- straight target approach

    /**
     * Path margin to turn nose toward target (game number, not physical): with an obstructed straight line (roofed target,
     * high-rise or ridge right beside it), projectile still attacks once this many nose-turn paths remain
     * ({@link #turnDistance}); later its nose could not reach the target and it would overshoot. While farther away,
     * it does not attack ({@link Sightline}).
     */
    static final double DIVE_MARGIN = 1.5;
    /**
     * Recheck obstructed target line after this many ticks: block-cell ray spans hundreds of blocks, for every projectile
     * in a salvo. During the pause Shahed travels ~8 blocks, missile ~16: covered by {@link #DIVE_MARGIN}.
     */
    static final int SIGHT_RECHECK = 4;

    /**
     * Path needed for nose to turn from current pitch to target line {@code aim}, with pitch angular-speed limit
     * {@code rate} °/tick and acceleration {@code accel} °/tick²: angular acceleration and braking,
     * with a plateau at the limit for large angles. A target behind (beyond the plane across heading) has a line past vertical:
     * 180° − angle below horizon. Otherwise a climbing missile would pass overhead while computed turn stayed no greater
     * than to a vertical dive, and attack would never start.
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
     * Game approach rule (shared by Shahed and missile, without moving-target lead): when blocks obstruct the straight line
     * to target ({@link Craft#clearAlong} — ready chunks only), do not attack until it
     * opens. Missile climbs in a pop-up; if line closes during dive (target behind a slope), return
     * to pop-up ({@link MissileAutopilot}); Shahed continues at cruise altitude ({@link DroneAutopilot}: climbing
     * hurts its approach). Only while nose can still turn toward target ({@link #DIVE_MARGIN}): closer in, attack as is —
     * this rule does not bypass a high-rise right beside target or a roofed target.
     */
    public static final class Sightline {
        private boolean blocked;
        /**
         * Attack already forced ({@link #DIVE_MARGIN}): do not measure the line until approach ends, otherwise after turning
         * its nose the projectile would resume climbing. Not saved: loading mid-dive makes missile pull into pop-up for a few
         * ticks until the nose-turn path forces attack again.
         */
        private boolean committed;
        /** Ticks before remeasuring the line (unsaved: measure immediately after load). */
        private int wait;

        /**
         * Straight line to {@code aim} is blocked except for last {@code margin} blocks at target (fuze reaches them), and
         * target is farther than {@link #DIVE_MARGIN} nose-turn paths with attack limits {@code rate},
         * {@code accel}: do not attack. Measure once per {@link #SIGHT_RECHECK} ticks.
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

        /** Attack forced and line obstructed at last measurement: attack immediately. */
        public boolean forced() {
            return committed && blocked;
        }

        /** Outside approach or new target: next approach measures the line immediately. */
        public void forget() {
            wait = 0;
            blocked = false;
            committed = false;
        }
    }

    // ---------------------------------------------------------------- terrain ahead

    /**
     * Terrain corridor half-width over path, blocks: body (Shahed span ~2.5 blocks) and turn drift; thin
     * columns (fence, mast) within the corridor are not missed ({@link Corridor}).
     */
    static final double CORRIDOR_HALF_WIDTH = 1.5;
    /** Predict turns along path for at most this many ticks, then continue straight on last heading. */
    static final int TRACK_TICKS = 64;
    /** Path point every this many predicted ticks: turn-arc chord (3°/tick) deviates by less than half a block. */
    private static final int TRACK_STEP = 4;
    /** Share of maximum climb angle ({@link WeaponSpec.Handling#climbAngle}) used in {@link #gradient}. */
    static final double CLIMB_SHARE = 0.5;

    /**
     * Climb gradient before an obstacle ahead: tangent of {@link #CLIMB_SHARE} of maximum climb angle {@code climbAngle};
     * the remainder allows for filtered altitude and proportional pitch control rather than a straight line.
     */
    static double gradient(double climbAngle) {
        return Math.tan(Math.toRadians(climbAngle * CLIMB_SHARE));
    }
    /**
     * Ticks of travel just establishing climb: altitude setpoint filter ({@link AltitudeHold}, 1/5 per tick)
     * and pitch with bounded angular speed/acceleration. No climb assumed along this path — terrain counts in full
     * (missile: 120 blocks, Shahed: 63). With less margin, a missile retargeted inside its turn circle over
     * hills would attack too low and hit the slope (flight properties, {@code AutopilotPropertiesTest}).
     */
    static final int REACTION_TICKS = 30;

    /**
     * Terrain height to account for now: maximum across the corridor ({@link #CORRIDOR_HALF_WIDTH}) over the path for
     * {@link WeaponSpec.Airframe#reliefLookahead} blocks ahead ({@link #track}), minus height the projectile
     * can gain before the column ({@link #climb}). Without subtraction a long corridor would hold the missile above the
     * highest terrain hundreds of blocks ahead, preventing low flight. Three heading points instead of a corridor would miss a fence
     * mast or narrow house between them; altitude filtering would smooth the short peak: low-flying missile and salvo Shaheds would hit them.
     */
    public static double reliefAhead(Craft c, Vec3 nav, Turn turn, WeaponSpec.Airframe air) {
        return climbOver(track(c, nav, turn, air.attack().reattackMin(), air.reliefLookahead()), c.speed(), air.handling().climbAngle(), c.reliefSurvey());
    }

    /**
     * Altitude from which projectile at {@code speed}, climbing by {@link #climb}, clears corridor terrain
     * ({@link #CORRIDOR_HALF_WIDTH}) over {@code track}: same as {@link #reliefAhead} for a supplied path. Launcher location
     * ({@code LaunchSite}) uses it to check the autopilot can climb after fuze arming.
     */
    public static double climbOver(double[] track, double speed, double climbAngle, Relief relief) {
        return Corridor.highest(track, CORRIDOR_HALF_WIDTH, climb(speed, climbAngle), relief);
    }

    /**
     * Projectile climb at {@code speed} with maximum climb angle {@code climbAngle}: {@link #REACTION_TICKS}
     * ticks of travel without climb, then {@link #gradient}.
     */
    static Corridor.Climb climb(double speed, double climbAngle) {
        return new Corridor.Climb(REACTION_TICKS * speed, gradient(climbAngle));
    }

    /** Recompute terrain corridor ahead every this many ticks ({@link ReliefSensor}). */
    static final int REFRESH_TICKS = 4;

    /**
     * One projectile's forward-terrain sensor ({@link #reliefAhead}), measuring every {@link #REFRESH_TICKS} ticks: corridor spans
     * hundreds of columns, changing little during the pause. Between measurements, answer grows by {@link #gradient} × distance since measurement:
     * columns ahead are closer, leaving less time to climb (upper bound — for nearby columns without climb,
     * extra margin is at most gradient × pause travel). Unsaved: measure immediately after load.
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

    /** Maximum terrain height beneath corridor over {@code track}, without gradient (world reentry, Lancet). */
    public static double reliefAlong(double[] track, Relief relief) {
        return Corridor.highest(track, CORRIDOR_HALF_WIDTH, Corridor.Climb.NONE, relief);
    }

    /**
     * Ground path for {@code lookahead} blocks ahead (x0, z0, x1, z1, …): turns toward {@code nav} are simulated using copied
     * controls ({@link FlightController#copy}) with the same heading law ({@link #steer}, reapproach via
     * {@link #outOfTurn}) for at most {@link #TRACK_TICKS} ticks, then straight. Current horizontal speed:
     * conservative during climb/descent.
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
