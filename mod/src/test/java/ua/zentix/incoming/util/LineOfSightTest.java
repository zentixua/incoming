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

    /**
     * Guide promise (defense, SAM redstone): a door beside the SAM hides targets below about 45° straight out from that
     * side. SAM block at y 64 (column's first air 65), antenna half a block above it ({@code SamBlockEntity}: block
     * centre + 1, y 65.5), a two-high door in the next column east (first air 66): the line enters that column half a
     * block out and must rise half a block, tan = 1.
     */
    @Test
    void blockBesideSamHidesBelowFortyFive() {
        LineOfSight.Columns door = (x, z) -> x == 0 && z == 0 ? 65 : x == 1 && z == 0 ? 66 : 64;
        Vec3 antenna = new Vec3(0.5, 65.5, 0.5);
        double far = 200;
        assertFalse(LineOfSight.clear(antenna, new Vec3(0.5 + far, 65.5 + far * Math.tan(Math.toRadians(43)), 0.5), door));
        assertTrue(LineOfSight.clear(antenna, new Vec3(0.5 + far, 65.5 + far * Math.tan(Math.toRadians(47)), 0.5), door));
        // a block no taller than the SAM leaves the antenna's horizon open
        LineOfSight.Columns low = (x, z) -> x <= 1 && z == 0 ? 65 : 64;
        assertTrue(LineOfSight.clear(antenna, new Vec3(0.5 + far, 66, 0.5), low));
    }

    /** A plumb straight line is one column. */
    @Test
    void verticalLine() {
        assertTrue(LineOfSight.clear(new Vec3(0.5, 65, 0.5), new Vec3(0.5, 200, 0.5), HILL));
        assertFalse(LineOfSight.clear(new Vec3(102.5, 65, 0.5), new Vec3(102.5, 200, 0.5), HILL));
    }
}
