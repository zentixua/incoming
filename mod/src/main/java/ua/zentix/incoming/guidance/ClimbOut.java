package ua.zentix.incoming.guidance;

import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import ua.zentix.incoming.util.Relief;

import java.util.Arrays;

/**
 * Climb over terrain on the course that the craft cannot out-climb (Lancet catapult at the foot of a ridge between it
 * and the target, a ridge above its cruise altitude). A craft climbing at its maximum angle
 * ({@code WeaponSpec.Handling#climbAngle}) flies a straight climb line; terrain rising faster than that line meets
 * the craft on its slope (a 1:1.5 hillside rises at 34°, the Lancet climbs at 15°). Then the craft circles up at its
 * turn limit, as small unmanned aircraft climb in an orbit to a safe altitude before departure, and leaves as soon as
 * a straight climb from where it is, along its predicted turn back onto the course ({@link Autopilot#track}), clears
 * every corridor column ({@link Corridor}). The circle turns to the side whose own climbing circle clears the terrain
 * (a valley wall beside the launcher), the preferred side first. Pure code without a Minecraft world.
 * <p>
 * Under a roof (terrain top above the craft at its own column: overhang, Nether ceiling) the surface heights say
 * nothing about the air the craft flies in: no circling, no raised altitude.
 * <p>
 * One sensor per craft, measuring every {@link Autopilot#REFRESH_TICKS} ticks like {@link Autopilot.ReliefSensor}: the
 * corridor spans hundreds of columns. Between measurements the needed altitude grows by the climb gradient times the
 * distance flown since the measurement (columns ahead are closer: upper bound). A new course point or a jump of the
 * craft (return into the world) measures at once. Unsaved: measures right after load.
 */
public final class ClimbOut {
    /**
     * Margin of the flight line over a column top, blocks: the body below the centre line (Lancet: 0.3 m fuselage) and
     * the heightmap rounding to the block top.
     */
    static final double HULL = 1;

    /**
     * Course reach, blocks: the terrain survey reach of the mod's terrain-following craft (Shahed, missile
     * {@code reliefLookahead}). A 15° climb gains 69 blocks over it, so a higher ridge entering it is circled up to
     * with the whole reach to spare; one walk over about 1300 columns per measurement.
     */
    public static final double REACH = 256;

    /** Climbing circle checked for a side: one full turn, in steps of {@link #ARC_STEP} ticks. */
    private static final int ARC_TICKS_MAX = 360, ARC_STEP = 4;

    private double ridge, need, gradient;
    private boolean roofed;
    /** Circle side clearing its own circle, or the preferred one if neither does (0: not measured). */
    private int side;
    private Vec3 at, nav;
    private double speed;
    private int age;

    /**
     * Measure the course ahead: path toward {@code nav} with the craft's turns ({@code turn}) for {@code lookahead}
     * blocks ({@link #REACH}, at most to {@code nav}: the course turns there); while the course cannot be cleared,
     * also the climbing circle on each side.
     *
     * @param climbAngle maximum climb angle, degrees: the straight climb line the craft holds while far below its
     *                   altitude
     * @param preferred  circle side to take when both clear (+1 or −1, the yaw sign of the turn)
     */
    public void measure(Craft c, Vec3 nav, Autopilot.Turn turn, double climbAngle, double lookahead, int preferred) {
        Vec3 pos = c.position();
        if (at != null && age < Autopilot.REFRESH_TICKS && sameCourse(pos, nav)) {
            age++;
            return;
        }
        gradient = Math.tan(Math.toRadians(climbAngle));
        Relief relief = c.reliefSurvey();
        roofed = relief.at((int) Math.floor(pos.x), (int) Math.floor(pos.z)) > pos.y;
        double[] highest = Corridor.both(Autopilot.track(c, nav, turn, 0, lookahead), Autopilot.CORRIDOR_HALF_WIDTH,
                new Corridor.Climb(0, gradient), relief);
        ridge = highest[0];
        need = highest[1] + HULL;
        side = 0;
        if (!roofed && need > pos.y) {
            double first = arcNeed(c, turn, preferred, relief);
            side = first <= pos.y || arcNeed(c, turn, -preferred, relief) > first ? preferred : -preferred;
        }
        at = pos;
        this.nav = nav;
        speed = c.speed();
        age = 1;
    }

    /** Same course point, and the craft is where its flight since the last measurement can take it. */
    private boolean sameCourse(Vec3 pos, Vec3 nav) {
        double flown = (age + 1) * Math.max(speed, 0.5) + 1;
        return nav.distanceToSqr(this.nav) < 1 && pos.distanceToSqr(at) <= flown * flown;
    }

    /** Altitude needed now to clear a full climbing circle turned to {@code side} at the turn limit. */
    private double arcNeed(Craft c, Autopilot.Turn turn, int side, Relief relief) {
        FlightController f = c.flight().copy();
        Vec3 pos = c.position();
        double step = Math.max(0.5, c.speed()), x = pos.x, z = pos.z, turned = 0;
        double[] arc = new double[2 * (ARC_TICKS_MAX / ARC_STEP + 2)];
        int n = 0;
        arc[n++] = x;
        arc[n++] = z;
        for (int t = 1; t <= ARC_TICKS_MAX && turned < 360; t++) {
            float before = f.yaw();
            f.steerYaw(before + side * 90.0, turn.gain(), turn.rate(), turn.accel());
            turned += Math.abs(Mth.wrapDegrees(f.yaw() - before));
            double yaw = Math.toRadians(f.yaw());
            x -= Math.sin(yaw) * step;
            z += Math.cos(yaw) * step;
            if (t % ARC_STEP == 0) {
                arc[n++] = x;
                arc[n++] = z;
            }
        }
        if (n < arc.length) {
            arc[n++] = x;
            arc[n++] = z;
        }
        return Corridor.both(Arrays.copyOf(arc, n), Autopilot.CORRIDOR_HALF_WIDTH, new Corridor.Climb(0, gradient), relief)[1] + HULL;
    }

    /** Highest terrain under the measured course corridor, blocks (world Y); none under a roof. */
    public double ridge() {
        return roofed ? Double.NEGATIVE_INFINITY : ridge;
    }

    /**
     * Circle side if a straight climb from {@code pos} cannot clear the course (+1 or −1, the yaw sign of the turn),
     * otherwise 0. The last measurement's needed altitude grows by the climb gradient over the horizontal distance
     * flown since.
     */
    public int circle(Vec3 pos) {
        if (at == null || roofed || side == 0) return 0;
        return need + gradient * Math.hypot(pos.x - at.x, pos.z - at.z) > pos.y ? side : 0;
    }
}
