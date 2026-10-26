package ua.zentix.incoming.util;

import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Line of sight over terrain ({@link LineOfSight}): the hill obscures the low target, the unknown column does not. */
class LineOfSightTest {
    /** Flat land (first air - y 64) and hill up to y 90 in columns x 100–104. */
    private static final LineOfSight.Columns HILL = (x, z) -> x >= 100 && x <= 104 ? 90 : 64;

    @Test
    void hillHidesLowTargetNotHighOne() {
        Vec3 radar = new Vec3(0.5, 65.5, 0.5);
        assertFalse(LineOfSight.clear(radar, new Vec3(300, 76, 0.5), HILL), "rocket at low level over the hill");
        assertTrue(LineOfSight.clear(radar, new Vec3(300, 160, 0.5), HILL), "target high above the hill");
        assertTrue(LineOfSight.clear(radar, new Vec3(95, 66, 0.5), HILL), "target in front of the hill");
        // The target rises from behind the hill: visible as soon as the sightline clears the top at the near edge
        // (x 100: the line rises, and this is its lowest clearance over the hill).
        double rise = 65.5 + (90 - 65.5) * (300 - 0.5) / (100 - 0.5);
        assertFalse(LineOfSight.clear(radar, new Vec3(300, rise - 1, 0.5), HILL));
        assertTrue(LineOfSight.clear(radar, new Vec3(300, rise + 1, 0.5), HILL));
    }

    /** The straight line does not slide obliquely between the columns at the cut corner. */
    @Test
    void diagonalDoesNotSlipThroughCorner() {
        LineOfSight.Columns post = (x, z) -> x == 10 && z == 11 ? 200 : 64;
        // straight line (0,0) → (20,20) passes exactly through the corner of the columns (10,10)/(11,11) - the shift by 0,3 touches (10, 11)
        assertFalse(LineOfSight.clear(new Vec3(0.2, 70, 0.5), new Vec3(20.2, 70, 20.5), post));
    }

    /** An unfinished chunk (column unknown) does not close the line: we don’t load chunks, there are no guesses about the relief. */
    @Test
    void unknownColumnsDoNotBlock() {
        LineOfSight.Columns unknown = (x, z) -> x >= 100 ? LineOfSight.UNKNOWN : 64;
        assertTrue(LineOfSight.clear(new Vec3(0.5, 65.5, 0.5), new Vec3(300, 66, 0.5), unknown));
    }

    /** A plumb straight line is one column. */
    @Test
    void verticalLine() {
        assertTrue(LineOfSight.clear(new Vec3(0.5, 65, 0.5), new Vec3(0.5, 200, 0.5), HILL));
        assertFalse(LineOfSight.clear(new Vec3(102.5, 65, 0.5), new Vec3(102.5, 200, 0.5), HILL));
    }
}
