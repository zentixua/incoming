package ua.zentix.incoming.nuclear.radiation;

import net.minecraft.server.level.ServerLevel;
import org.jetbrains.annotations.Nullable;
import ua.zentix.incoming.nuclear.Detonation;
import ua.zentix.incoming.nuclear.NuclearEvents;
import ua.zentix.incoming.nuclear.model.FalloutModel;
import ua.zentix.incoming.nuclear.model.FalloutModel.Landing;

/**
 * Fallout and black-rain dust on the ground ({@code docs/design/time.md} §8.3 rules 4 and 7). Each deposit lands at a point
 * when its carrier brings it there, on the motion age (the plume's arrival, the rain's onset there) and from then ages on the
 * detonation's ground clock ({@link NuclearEvents#ground}): its ground age is the age it landed at plus the ground clock since
 * ({@link Landing}), so it lands with the law's dose rate on any day and decays as the world's day runs. Live detonations
 * and ground fields of forgotten ones ({@link NuclearEvents#groundFields}) read the same way; a source is read only within
 * its reach ({@link Detonation#groundReach}), so a distant or old one costs one distance check.
 */
public final class GroundFallout {
    /**
     * A source counts at a point only where its bound reaches this share of the floor ({@link FalloutModel#FLOOR}): a total
     * below the floor adds no dose, and it takes ten sources under this share overlapping to reach it.
     */
    static final double NEGLIGIBLE = 0.1;

    private GroundFallout() {}

    /**
     * One source's clocks for a step: ground clock now and at the step's start, seconds, and its reach, blocks (where its
     * dose rate can still reach {@link #NEGLIGIBLE} of the floor at any moment of the step: at its youngest ground age at
     * the step's start, so a long step, such as a mob's account over months, reads every point that was above it then).
     */
    public record Source(Detonation d, double ground, double before, double reach) {
        /** Whether the point is beyond this source's reach. */
        public boolean far(double x, double z) {
            double dx = x - d.burst().x, dz = z - d.burst().z;
            return dx * dx + dz * dz > reach * reach;
        }
    }

    /** Source {@code d} with ground clock now {@code ground} and at the step's start {@code before}. */
    public static Source source(NuclearEvents events, ServerLevel level, Detonation d, double ground, double before) {
        return new Source(d, ground, before, reach(d, events.youngestGround(d, level), ground - before));
    }

    /**
     * Reach, blocks, of a step of {@code span} ground-clock seconds that ends when {@code d}'s youngest ground age is
     * {@code youngest} seconds: the youngest age at the step's start is at least {@code youngest − span} (it grows no faster
     * than the ground clock), so the dose rate beyond stays under {@link #NEGLIGIBLE} of the floor throughout the step.
     */
    public static double reach(Detonation d, double youngest, double span) {
        return d.groundReach(Math.max(0, youngest - Math.max(0, span)) / 3600, FalloutModel.FLOOR * NEGLIGIBLE);
    }

    /**
     * One source at one point: the plume's and the rain deposit's H+1 dose rates, R/h, and their landings (null — not
     * landed yet).
     */
    public record Point(double falloutH1, @Nullable Landing fallout, double rainH1, @Nullable Landing rain) {
        public static final Point NONE = new Point(0, null, 0, null);

        /** Dose rate, R/h, at ground clock {@code ground}. */
        public double rate(double ground) {
            return (fallout == null ? 0 : fallout.rate(falloutH1, ground)) + (rain == null ? 0 : rain.rate(rainH1, ground));
        }

        /** Unshielded dose, R, over ground clock {@code g0} to {@code g1}: nothing before a deposit landed. */
        public double dose(double g0, double g1) {
            return (fallout == null ? 0 : fallout.dose(falloutH1, g0, g1)) + (rain == null ? 0 : rain.dose(rainH1, g0, g1));
        }

        /** Youngest ground age of a deposit landed here by ground clock {@code ground}, hours; NaN — none. */
        public double youngestHours(double ground) {
            double age = Double.NaN;
            if (fallout != null && fallout.rate(falloutH1, ground) > 0) age = fallout.groundHours(ground);
            if (rain != null && rain.rate(rainH1, ground) > 0) {
                double r = rain.groundHours(ground);
                age = Double.isNaN(age) ? r : Math.min(age, r);
            }
            return age;
        }
    }

    /** Source {@code s} at point ({@code x}, {@code z}): {@link Point#NONE} beyond its reach. */
    public static Point at(NuclearEvents events, ServerLevel level, Source s, double x, double z) {
        if (s.far(x, z)) return Point.NONE;
        Detonation d = s.d();
        double f = d.falloutH1(x, z), r = d.rainH1(x, z);
        Landing lf = f > 0 ? events.landingByMotion(d, level, d.falloutArrivalSeconds(x, z)) : null;
        Landing lr = r > 0 ? events.landingByMotion(d, level, d.rainStartSeconds(x, z)) : null;
        return lf == null && lr == null ? Point.NONE : new Point(f, lf, r, lr);
    }
}
