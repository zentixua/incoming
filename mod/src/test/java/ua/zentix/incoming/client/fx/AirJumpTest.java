package ua.zentix.incoming.client.fx;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;
import ua.zentix.incoming.client.fx.smoke.CloudModel;
import ua.zentix.incoming.client.fx.smoke.SmokeGrid;
import ua.zentix.incoming.nuclear.model.FuelPool;
import ua.zentix.incoming.strike.WeaponType;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Rule 2 for conventional strikes ({@code docs/design/time.md} §8.3): a night nobody saw ({@link AirJump}) ages what is in
 * the air by its hours at once. An 8-hour night clears a Shahed's cloud; the crater fuel fire stays in place on its own clock,
 * and its smoke near the source is its present plume, never a gap or an old plume popping back.
 */
class AirJumpTest {
    private static final long NIGHT = 8 * 3600 * 20;
    private static final FuelPool DRONE = WeaponType.DRONE.spec().pool(5);
    private static final List<BlockPos> CRATER = List.of(new BlockPos(0, 63, 0), new BlockPos(1, 63, 0), new BlockPos(-1, 63, 0),
            new BlockPos(0, 63, 1), new BlockPos(0, 63, -1));

    /** A Shahed's cloud two minutes old lives on; after an 8-hour night it is past its life (and past {@link CloudModel#LIFE_MAX}). */
    @Test
    void aCountedNightClearsAShahedCloud() {
        CloudModel shahed = new CloudModel(BlastSource.of(WeaponType.DRONE, false), CloudModel.Air.STANDARD);
        long born = BlastTime.ticks();
        for (int i = 0; i < 2400; i++) BlastTime.advance();
        double age = BlastTime.seconds(BlastTime.ticks(), born, 0);
        assertEquals(120, age, 1e-9);
        assertTrue(age < shahed.life, "the cloud at 2 min: life " + shahed.life + " s");
        BlastTime.jump(NIGHT);
        age = BlastTime.seconds(BlastTime.ticks(), born, 0);
        assertEquals(120 + 8 * 3600, age, 1e-9, "the cloud's age is real plus the counted night");
        assertTrue(age > shahed.life && age > CloudModel.LIFE_MAX, "the cloud outlived the night");
    }

    /**
     * The crater fuel burns on the server's cells, which the night does not move: the source keeps its strength by its real age;
     * the grid clock moved, so the smoke around the source is the one it gave off meanwhile, at that strength.
     */
    @Test
    void craterSmokeKeepsItsFireAcrossTheNight() {
        FireSmoke.reset();
        try {
            FireSmoke f = FireSmoke.pool(null, new FuelBurn(7, DRONE, 5, CRATER, 0), 60);
            // the sources are reassessed once a second, at a phase of the shared counter: the age read is within that second
            for (int i = 0; i < 40; i++) tick();
            assertEquals(DRONE.share(61.5), f.blocks, spread(61, 62));
            double start = f.start;
            SmokeGrid.jump(NIGHT);
            FireSmoke.jump(NIGHT);
            for (int i = 0; i < 20; i++) tick();
            assertTrue(DRONE.seconds() < 8 * 3600 && f.blocks > 0, "the fire burns on by its real age, not out with the night");
            assertEquals(DRONE.share(62.5), f.blocks, spread(62, 63), "the fire burns on by its real age");
            assertTrue(Double.isNaN(f.end), "the fire went out with the night");
            assertEquals(start, f.start, "the smoke's start stays: the plume is whole, not newly lit");
            double now = SmokeGrid.seconds(0);
            for (double back : new double[]{1, 100, 600})
                assertEquals(f.blocks, f.blocksAt(now - back), 0.02 * f.blocks, "smoke " + back + " s downwind of the source");
        } finally {
            FireSmoke.reset();
        }
    }

    /** How much the law's share changes between ages a and b, s: the tolerance of an age read once a second. */
    private static double spread(double a, double b) {
        return Math.abs(DRONE.share(a) - DRONE.share(b)) + 1e-9;
    }

    private static void tick() {
        SmokeGrid.tick();
        FireSmoke.tick(null, true);
    }
}
