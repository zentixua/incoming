package ua.zentix.incoming.gametest;

import com.mojang.authlib.GameProfile;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import ua.zentix.incoming.Incoming;
import ua.zentix.incoming.nuclear.NuclearStrikes;
import ua.zentix.incoming.util.WorldClock;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Rule 7 (review probe, air-m round 1): a night everyone slept through while a nuclear front was still crossing is held
 * ({@code WorldClock.settle}) until the front ends, up to about a minute. By then the sleepers are awake (vanilla wakes them
 * in the tick that skips the night) and watch the strike, so the held night is one someone sees: it moves only what nobody
 * sees change ({@code unseen}), never the count that ages the strike's air, fires and show in front of them.
 */
@GameTestHolder(Incoming.MOD_ID)
@PrefixGameTestTemplate(false)
public final class AirAwakeJumpProbeGameTests {
    @GameTest(template = "range", timeoutTicks = 20, batch = "clock_air_awake", skyAccess = true)
    public static void aNightHeldByTheFrontNeverJumpsTheAirInFrontOfAWokenPlayer(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        MinecraftServer server = level.getServer();
        WorldClock clock = WorldClock.get(server);
        String dim = level.dimension().location().toString();
        Map<String, double[]> saved = new HashMap<>();
        for (ServerLevel l : server.getAllLevels()) saved.put(l.dimension().location().toString(), clock.jumpsForTest(l.dimension().location().toString()));
        h.assertTrue(level.players().stream().allMatch(p -> p.isSleeping()), "someone is awake in the test world: " + level.players());
        boolean[] crossing = {true};
        FakePlayer woke = new FakePlayer(level, new GameProfile(UUID.randomUUID(), "woke"));
        woke.setPos(Vec3.atCenterOf(h.absolutePos(new BlockPos(4, 2, 4))));
        try {
            WorldClock.holdJumpsWhile(l -> l == level && crossing[0]);
            // the night is skipped with nobody awake, a front still crossing: held
            clock.recordAwakeForTest(server);
            clock.jumpForTest(server, 13_000);
            h.assertTrue(clock.jumpsForTest(dim)[2] == saved.get(dim)[2] + 13_000, "not held: " + Arrays.toString(clock.jumpsForTest(dim)));
            // the sleeper woke with the skip and watches; the front ends
            level.players().add(woke);
            crossing[0] = false;
            clock.settleForTest(server);
            boolean awake = level.players().stream().anyMatch(p -> !p.isSleeping());
            h.assertTrue(awake, "probe setup: nobody awake");
            h.assertTrue(clock.jumpsForTest(dim)[1] == saved.get(dim)[1] + 13_000 && clock.jumpsForTest(dim)[2] == 0,
                    "the held night did not become one someone saw: " + Arrays.toString(clock.jumpsForTest(dim)));
            h.assertFalse(WorldClock.countedRose(level),
                    "a held night was counted in front of an awake player: the air jumps 13 000 clock ticks (936 000 real ticks) in one frame; jumps "
                            + Arrays.toString(clock.jumpsForTest(dim)));
        } finally {
            level.players().remove(woke);
            WorldClock.holdJumpsWhile(NuclearStrikes::frontCrossing);
            saved.forEach(clock::restoreJumpsForTest);
        }
        h.succeed();
    }

    /**
     * A strike born while a night is held takes it as its past (it burst after the skip). If the held night then turns out
     * seen, that strike drops it too: its show runs on, its motion age stays, and a later night nobody saw moves it by
     * exactly that later night, never short by the seen one.
     */
    @GameTest(template = "range", timeoutTicks = 20, batch = "clock_air_born_held", skyAccess = true)
    public static void aStrikeBornDuringAHeldNightKeepsItsLaterNights(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        MinecraftServer server = level.getServer();
        WorldClock clock = WorldClock.get(server);
        String dim = level.dimension().location().toString();
        Map<String, double[]> saved = new HashMap<>();
        for (ServerLevel l : server.getAllLevels()) saved.put(l.dimension().location().toString(), clock.jumpsForTest(l.dimension().location().toString()));
        boolean[] crossing = {true};
        FakePlayer woke = new FakePlayer(level, new GameProfile(UUID.randomUUID(), "woke"));
        woke.setPos(Vec3.atCenterOf(h.absolutePos(new BlockPos(4, 2, 4))));
        ua.zentix.incoming.nuclear.NuclearEvents events = new ua.zentix.incoming.nuclear.NuclearEvents();
        try {
            WorldClock.holdJumpsWhile(l -> l == level && crossing[0]);
            WorldClock.onHeldSeen((l, held) -> { if (l == level) events.heldSeen(l, held); });
            clock.recordAwakeForTest(server);
            clock.jumpForTest(server, 13_000);
            ua.zentix.incoming.nuclear.Detonation d = new ua.zentix.incoming.nuclear.Detonation(
                    ua.zentix.incoming.nuclear.NuclearEvents.get(level).nextId(), new Vec3(90_000, 64, 90_000), 64, 7, true,
                    level.getGameTime(), 0, 0, 20000, 3, 0.03f, false, 1);
            events.add(d, level);
            double m0 = events.motion(d, level), g0 = events.ground(d, level);
            level.players().add(woke);
            crossing[0] = false;
            clock.settleForTest(server);
            h.assertTrue(events.endShows(level).isEmpty() && !events.showEnded(d, level), "a seen night ended the newborn's show");
            h.assertTrue(Math.abs(events.motion(d, level) - m0) < 1e-9, "a seen night moved the newborn: " + m0 + " → " + events.motion(d, level));
            // the night was before its burst: not one of its unseen nights either (its fallout's ground clock stays)
            h.assertTrue(Math.abs(events.ground(d, level) - g0) < 1e-6, "a seen night moved the newborn's ground clock: " + g0 + " → " + events.ground(d, level));
            level.players().remove(woke);
            clock.recordAwakeForTest(server);
            clock.jumpForTest(server, 5_000);
            double night = 5_000 * WorldClock.SECONDS_PER_TICK;
            h.assertTrue(Math.abs(events.motion(d, level) - (m0 + night)) < 1e-6, String.format(java.util.Locale.ROOT,
                    "a later counted night moved the newborn by %.0f s, not %.0f s", events.motion(d, level) - m0, night));
        } finally {
            level.players().remove(woke);
            WorldClock.holdJumpsWhile(NuclearStrikes::frontCrossing);
            WorldClock.onHeldSeen((l, held) -> ua.zentix.incoming.nuclear.NuclearEvents.get(l).heldSeen(l, held));
            saved.forEach(clock::restoreJumpsForTest);
        }
        h.succeed();
    }
}
