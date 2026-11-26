package ua.zentix.incoming.gametest;

import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import ua.zentix.incoming.Incoming;
import ua.zentix.incoming.grid.Blackouts;
import ua.zentix.incoming.grid.Districts;
import ua.zentix.incoming.grid.Outage;
import ua.zentix.incoming.grid.PowerGrid;
import ua.zentix.incoming.util.StrikeClock;
import ua.zentix.incoming.util.WorldClock;

import java.util.Locale;

/**
 * A district's outage during its strike's show ({@code docs/design/time.md} §8.3 rule 8, §10 mismatch 5): its repairs run
 * at real rate while the show runs and join the world's rate through the blend, so power does not come back 72× fast in
 * front of the show. The grid steps the hold once a second on its own ({@code Blackouts.hold}).
 */
@GameTestHolder(Incoming.MOD_ID)
@PrefixGameTestTemplate(false)
public final class GridShowGameTests {
    /** The conventional show of the test outage, game ticks. */
    private static final int SHOW = 200;

    @GameTest(template = "range", timeoutTicks = SHOW + (int) StrikeClock.RAMP_TICKS + 200, batch = "grid_show_hold", skyAccess = true)
    public static void repairsHoldThroughTheShow(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        PowerGrid grid = PowerGrid.get(level);
        long now = Blackouts.now(level), game = level.getGameTime();
        // far from every platform: no chunk of it is loaded, the grid only keeps its times
        long district = Districts.of(12_500, 12_500);
        Outage o = grid.addOutage(new Outage(0, Districts.seedX(district), Districts.seedZ(district), 64, now, 50, now + 100_000, 0, -1, -1, -1,
                0, -1, game + SHOW));
        StrikeGameTests.afterTest(h, () -> grid.replace(current(grid, o).released().restoring(Blackouts.now(level), 0)));
        double rate0 = WorldClock.rateClock(level);
        h.runAtTickTime(SHOW + 1, () -> {
            Outage held = current(grid, o);
            double world = WorldClock.rateClock(level) - rate0, real = (level.getGameTime() - game) * WorldClock.REAL_RATE;
            // stepped once a second: within one second's worth of the world's excess
            double expect = world - real, second = 40 * Math.max(1, world / Math.max(1, level.getGameTime() - game));
            h.assertTrue(held.held() && Math.abs(held.lag() - expect) <= second,
                    String.format(Locale.ROOT, "in the show the outage fell %d behind, the world ran %.0f ahead of real", held.lag(), expect));
            h.assertTrue(held.repairsAt() == now + 100_000 + held.lag(), "the repairs did not move by the lag: " + held.repairsAt());
        });
        h.runAtTickTime(SHOW + (int) StrikeClock.RAMP_TICKS + 100, () -> {
            Outage done = current(grid, o);
            h.assertFalse(done.held(), "the outage is still held after the show and the blend");
            double perTick = (WorldClock.rateClock(level) - rate0) / (level.getGameTime() - game);
            // the show's whole excess, then half of it over the blend's 60 s
            double expect = (perTick - WorldClock.REAL_RATE) * (SHOW + StrikeClock.RAMP_TICKS / 2.0);
            h.assertTrue(Math.abs(done.lag() - expect) <= 40 * Math.max(1, perTick),
                    String.format(Locale.ROOT, "after the blend the outage is %d behind, expected %.0f", done.lag(), expect));
            h.succeed();
        });
    }

    private static Outage current(PowerGrid grid, Outage o) {
        return grid.outages().stream().filter(x -> x.id() == o.id()).findFirst().orElseThrow();
    }
}
