package ua.zentix.incoming.guidance;

import net.minecraft.world.phys.Vec3;

/**
 * Climb over terrain on the course that the craft cannot out-climb (Lancet catapult at the foot of a ridge between it
 * and the target, a ridge above its cruise altitude). A craft climbing at its maximum angle ({@code WeaponSpec.Handling#climbAngle}) flies a
 * straight climb line; terrain rising faster than that line within the climb meets the craft on its slope (a 1:1.5
 * hillside rises at 34°, the Lancet climbs at 15°). Then the craft circles up at its turn limit over the launch area,
 * as small unmanned aircraft climb in an orbit to a safe altitude before departure, and leaves as soon as a straight
 * climb from where it is, along its predicted turn back onto the course ({@link Autopilot#track}), clears every
 * corridor column ({@link Corridor}). Pure code without a Minecraft world.
 * <p>
 * One sensor per craft, measuring every {@link Autopilot#REFRESH_TICKS} ticks like {@link Autopilot.ReliefSensor}: the
 * corridor spans hundreds of columns. Between measurements the needed altitude grows by the climb gradient times the
 * distance flown since the measurement (columns ahead are closer: upper bound). Unsaved: measures right after load.
 */
public final class ClimbOut {
    /**
     * Margin of the flight line over a column top, blocks: the body below the centre line (Lancet: 0.3 m fuselage) and
     * the heightmap rounding to the block top.
     */
    static final double HULL = 1;

    private double ridge, need, gradient;
    private Vec3 at;
    private int age;

    /**
     * Course reach, blocks: the terrain survey reach of the mod's terrain-following craft (Shahed, missile
     * {@code reliefLookahead}). A 15° climb gains 69 blocks over it, so a higher ridge entering it is circled up to
     * with the whole reach to spare; one walk over about 1300 columns per measurement.
     */
    public static final double REACH = 256;

    /**
     * Measure the course ahead: path toward {@code nav} with the craft's turns ({@code turn}) for {@code lookahead}
     * blocks ({@link #REACH}, shorter when the course ends at the target).
     *
     * @param climbAngle maximum climb angle, degrees: the straight climb line the craft holds while far below its
     *                   altitude
     */
    public void measure(Craft c, Vec3 nav, Autopilot.Turn turn, double climbAngle, double lookahead) {
        Vec3 pos = c.position();
        if (at != null && age < Autopilot.REFRESH_TICKS) {
            age++;
            return;
        }
        gradient = Math.tan(Math.toRadians(climbAngle));
        double[] track = Autopilot.track(c, nav, turn, 0, lookahead);
        double[] highest = Corridor.both(track, Autopilot.CORRIDOR_HALF_WIDTH, new Corridor.Climb(0, gradient), c.reliefSurvey());
        ridge = highest[0];
        need = highest[1] + HULL;
        at = pos;
        age = 1;
    }

    /** Highest terrain under the measured course corridor, blocks (world Y). */
    public double ridge() {
        return ridge;
    }

    /**
     * A straight climb from {@code pos} cannot clear the course: circle up first. The last measurement's needed
     * altitude grows by the climb gradient over the horizontal distance flown since.
     */
    public boolean circle(Vec3 pos) {
        if (at == null) return false;
        return need + gradient * Math.hypot(pos.x - at.x, pos.z - at.z) > pos.y;
    }
}
