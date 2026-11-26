package ua.zentix.incoming.gametest;

import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import ua.zentix.incoming.Incoming;
import ua.zentix.incoming.nuclear.Detonation;
import ua.zentix.incoming.nuclear.NuclearEvents;
import ua.zentix.incoming.nuclear.NuclearStrikes;
import ua.zentix.incoming.util.WorldClock;

import java.util.Locale;

/**
 * A night counted while an earlier burst's front crossed is held (pending), then settles (review of PR #778, round 1). A detonation that burst after that night, while the jump was still pending, must not take it: its show must
 * keep running and its motion age must not move. Head fa9a78e70 took {@code counted} without the held jumps, so when the hold
 * released the newborn strike's show ended at once and M and S gained the whole night (13 000 ticks = 13 h).
 */
@GameTestHolder(Incoming.MOD_ID)
@PrefixGameTestTemplate(false)
public final class ClockReviewProbeGameTests {
    @GameTest(template = "range", timeoutTicks = 20, batch = "clock_probe_pending", skyAccess = true)
    public static void aNightBeforeTheBurstIsNotItsOwn(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        MinecraftServer server = level.getServer();
        WorldClock clock = WorldClock.get(server);
        String dim = level.dimension().location().toString();
        double[] saved = clock.jumpsForTest(dim);
        boolean[] crossing = {true};
        try {
            WorldClock.holdJumpsWhile(l -> l == level && crossing[0]);
            clock.recordAwakeForTest(server);
            clock.jumpForTest(server, 13_000);
            NuclearEvents events = new NuclearEvents();
            Detonation d = new Detonation(NuclearEvents.get(level).nextId(), new Vec3(90_000, 64, 90_000), 64, 7, true,
                    level.getGameTime(), 0, 0, 20000, 3, 0.03f, false, 1);
            events.add(d, level);
            events.endShows(level);
            double m0 = events.motion(d, level);
            crossing[0] = false;
            clock.settleForTest(server);
            h.assertTrue(events.endShows(level).isEmpty() && !events.showEnded(d, level), "a night before the burst ended its show");
            h.assertTrue(Math.abs(events.motion(d, level) - m0) < 1e-9,
                    String.format(Locale.ROOT, "a night before the burst moved its motion age: %.0f s → %.0f s", m0, events.motion(d, level)));
        } finally {
            WorldClock.holdJumpsWhile(NuclearStrikes::frontCrossing);
            clock.restoreJumpsForTest(dim, saved);
        }
        h.succeed();
    }
}
