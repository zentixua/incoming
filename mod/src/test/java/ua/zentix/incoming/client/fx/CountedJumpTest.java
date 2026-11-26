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
 * Rule 2 for conventional strikes ({@code docs/design/time.md} §8.3): a night nobody saw ({@link CountedJump}) ages what is in
 * the air by its hours at once, and the crater fuel, burning in place within its show, alike. An 8-hour night clears a
 * Shahed's cloud and burns its crater out, with no old plume popping back.
 */
class CountedJumpTest {
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
     * The crater fuel burns in place on its strike's clock, within its show, so a counted night moves it as it moves the
     * smoke: the source ends where its law ran out, at once, and the smoke near it is none, never the plume it had before
     * the night; the source leaves the grid on the next tick. A source left alone burns on by its law.
     */
    @Test
    void aCountedNightBurnsTheCraterSmokeOut() {
        FireSmoke.reset();
        try {
            FireSmoke f = FireSmoke.pool(null, new FuelBurn(7, DRONE, 5, CRATER, 0), 60);
            for (int i = 0; i < 40; i++) tick();
            assertEquals(DRONE.share(61.5), f.blocks, spread(61, 62));
            assertTrue(f.blocks > 0 && Double.isNaN(f.end), "the fire burns two minutes before its law's end");
            double start = f.start;
            SmokeGrid.jump(NIGHT);
            FireSmoke.jump(NIGHT);
            double now = SmokeGrid.seconds(0);
            assertEquals(start + DRONE.seconds(), f.end, 1e-9, "the fuel ends where its law ran out, during the night");
            for (double back : new double[]{0, 1, 100, 600}) assertEquals(0, f.blocksAt(now - back), "smoke " + back + " s downwind after the night");
            tick();
            assertTrue(f.ended(), "the spent source is still drawn");
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
