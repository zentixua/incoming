package ua.zentix.incoming.client.fx;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;
import ua.zentix.incoming.client.fx.smoke.SmokeGrid;
import ua.zentix.incoming.nuclear.model.FuelPool;
import ua.zentix.incoming.strike.WeaponType;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A crater's smoke is never born whole: the server pours the fuel in a work unit after the crater, seconds after the blast
 * in a salvo, and a client that watched the blast grows the plume from the source from the moment it learns of the fuel
 * (rc-video raid clip, 31.7 s: a Grad crater's plume appeared at full length in one frame as a straight rod). A client
 * that learns of a fire after the fact (late join, returning to the chunk) gets the plume that is already out there.
 */
class FuelPlumeBirthTest {
    private static final FuelPool GRAD = WeaponType.ROCKET.spec().pool(WeaponType.ROCKET.spec().pool().cells());
    private static final List<BlockPos> CRATER = List.of(new BlockPos(0, 63, 0));
    /** A Grad salvo's impact units ran 94 ticks (rc-video take): the fuel packet came up to 4.7 s after its blast. */
    private static final double LATE = 4.7;

    @Test
    void aWitnessedCraterGrowsItsPlumeFromTheSource() {
        FireSmoke.reset();
        try {
            FireSmoke.fuel(null, 1, GRAD, CRATER, 0, LATE, true);
            FireSmoke f = FireSmoke.crater(1);
            double now = SmokeGrid.seconds(0);
            assertEquals(0, f.reach(now), 1e-9, "no plume in the frame the fuel arrives");
            double u = f.plume.wind;
            assertTrue(u > 0);
            assertEquals(Math.min(f.visible, u), f.reach(now + 1), 1e-9, "one second later: one second of wind travel");
        } finally {
            FireSmoke.reset();
        }
    }

    @Test
    void aFireLearnedAfterTheFactKeepsItsPlume() {
        FireSmoke.reset();
        try {
            FireSmoke.fuel(null, 2, GRAD, CRATER, 0, LATE, false);
            FireSmoke f = FireSmoke.crater(2);
            double now = SmokeGrid.seconds(0);
            assertEquals(Math.min(f.visible, f.plume.wind * LATE), f.reach(now), 1e-9, "the plume already out there");
        } finally {
            FireSmoke.reset();
        }
    }
}
