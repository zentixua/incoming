package ua.zentix.incoming.gametest;

import com.mojang.authlib.GameProfile;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import ua.zentix.incoming.Incoming;
import ua.zentix.incoming.net.S2C;
import ua.zentix.incoming.nuclear.Detonation;
import ua.zentix.incoming.nuclear.NuclearEvents;
import ua.zentix.incoming.nuclear.NuclearStrikes;
import ua.zentix.incoming.util.WorldClock;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * The world clock's jumps and real time against a live server ({@code docs/design/time.md} §8.3 rules 7, 9, 10): who is
 * awake decides whether a night moves the strikes, a front holds a counted jump, a frozen server stops the clock, the
 * clocks reach every player once a second. Each test has its own batch: a jump is server-wide, and each test puts the
 * counters back.
 */
@GameTestHolder(Incoming.MOD_ID)
@PrefixGameTestTemplate(false)
public final class WorldClockGameTests {
    private static final double NIGHT = 13_000;

    /** A detonation far from every platform, {@code age} ticks old, in a detached event list (no world work). */
    private static Detonation detonation(ServerLevel level, NuclearEvents events, long age) {
        Detonation d = new Detonation(NuclearEvents.get(level).nextId(), new Vec3(90_000, 64, 90_000), 64, 7, true,
                level.getGameTime() - age, 0, 0, 20000, 3, 0.03f, false, 1);
        events.add(d, level);
        return d;
    }

    /** Every dimension's jumps now, to put back. */
    private static Map<String, double[]> jumps(MinecraftServer server) {
        Map<String, double[]> out = new HashMap<>();
        for (ServerLevel l : server.getAllLevels()) {
            String dim = l.dimension().location().toString();
            out.put(dim, WorldClock.get(server).jumpsForTest(dim));
        }
        return out;
    }

    private static void restore(MinecraftServer server, Map<String, double[]> saved) {
        saved.forEach((dim, j) -> WorldClock.get(server).restoreJumpsForTest(dim, j));
    }

    private static String dim(Level level) {
        return level.dimension().location().toString();
    }

    /**
     * Rule 7: an idle player (awake, not in bed) in the dimension blocks a counted jump. The night moves only what nobody sees
     * change ({@link WorldClock#unseen}); the show runs on, neither age moves.
     */
    @GameTest(template = "range", timeoutTicks = 20, batch = "clock_jump_awake", skyAccess = true)
    public static void anIdlePlayerKeepsTheShow(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        MinecraftServer server = level.getServer();
        WorldClock clock = WorldClock.get(server);
        Map<String, double[]> saved = jumps(server);
        FakePlayer afk = new FakePlayer(level, new GameProfile(UUID.randomUUID(), "afk"));
        afk.setPos(Vec3.atCenterOf(h.absolutePos(new net.minecraft.core.BlockPos(4, 2, 4))));
        level.players().add(afk);
        try {
            clock.recordAwakeForTest(server);
        } finally {
            level.players().remove(afk);
        }
        try {
            NuclearEvents events = new NuclearEvents();
            Detonation d = detonation(level, events, 12_000);
            h.assertTrue(events.endShows(level).isEmpty(), "the show ended before the jump");
            double s0 = events.seconds(d, level), m0 = events.motion(d, level);
            clock.jumpForTest(server, NIGHT);
            double[] before = saved.get(dim(level)), after = clock.jumpsForTest(dim(level));
            h.assertTrue(after[0] == before[0] && after[2] == before[2], "an idle player's night was counted: " + java.util.Arrays.toString(after));
            h.assertTrue(after[1] == before[1] + NIGHT, "the night did not move what nobody sees: " + java.util.Arrays.toString(after));
            h.assertTrue(events.endShows(level).isEmpty() && !events.showEnded(d, level), "the night ended the show in front of a player");
            h.assertTrue(events.seconds(d, level) == s0 && events.motion(d, level) == m0,
                    String.format(Locale.ROOT, "the ages moved: S %.1f → %.1f, M %.1f → %.1f", s0, events.seconds(d, level), m0, events.motion(d, level)));
            double[] nether = clock.jumpsForTest(dim(server.getLevel(Level.NETHER)));
            h.assertTrue(java.util.Arrays.equals(nether, saved.get(dim(server.getLevel(Level.NETHER)))), "a fixed-time dimension took the jump");
        } finally {
            restore(server, saved);
        }
        h.succeed();
    }

    /**
     * Rule 7: with nobody awake in the dimension (single-player sleep), a night adds its hours once to M and S of every
     * strike there and ends their shows: one in its show at minute 10, one after it.
     */
    @GameTest(template = "range", timeoutTicks = 20, batch = "clock_jump_asleep", skyAccess = true)
    public static void aNightNobodyWatchesMovesEveryStrike(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        MinecraftServer server = level.getServer();
        WorldClock clock = WorldClock.get(server);
        h.assertTrue(level.players().stream().allMatch(p -> p.isSleeping()), "someone is awake in the test world: " + level.players());
        Map<String, double[]> saved = jumps(server);
        try {
            WorldClock.holdJumpsWhile(l -> false);
            clock.recordAwakeForTest(server);
            NuclearEvents events = new NuclearEvents();
            Detonation showing = detonation(level, events, 12_000), after = detonation(level, events, 30_000);
            events.endShows(level);
            h.assertTrue(!events.showEnded(showing, level) && events.showEnded(after, level), "setup: one in its show, one after");
            double m0 = events.motion(showing, level), s0 = events.seconds(showing, level), b0 = events.seconds(after, level),
                    bm0 = events.motion(after, level);
            int samples = events.history(showing).size();
            clock.jumpForTest(server, NIGHT);
            double night = NIGHT * WorldClock.SECONDS_PER_TICK;
            h.assertTrue(clock.jumpsForTest(dim(level))[0] == saved.get(dim(level))[0] + NIGHT, "the night was not counted");
            h.assertTrue(events.endShows(level).contains(showing), "the night did not end the show");
            h.assertTrue(Math.abs(events.motion(showing, level) - (m0 + night)) < 1e-6 && Math.abs(events.seconds(showing, level) - (s0 + night)) < 1e-6,
                    String.format(Locale.ROOT, "in its show: M %.1f → %.1f, S %.1f → %.1f (night %.0f s)", m0, events.motion(showing, level),
                            s0, events.seconds(showing, level), night));
            h.assertTrue(Math.abs(events.seconds(after, level) - (b0 + night)) < 1e-6 && Math.abs(events.motion(after, level) - (bm0 + night)) < 1e-6,
                    String.format(Locale.ROOT, "after its show: S %.1f → %.1f", b0, events.seconds(after, level)));
            h.assertTrue(events.history(showing).size() >= samples + 2, "the history missed the jump: " + events.history(showing).size());
            h.assertTrue(Math.abs(events.secondsAt(showing, level, showing.gameTime() + 6000) - 300) < 1e-6,
                    "a past moment's strike clock: " + events.secondsAt(showing, level, showing.gameTime() + 6000));
        } finally {
            WorldClock.holdJumpsWhile(NuclearStrikes::frontCrossing);
            restore(server, saved);
        }
        h.succeed();
    }

    /** Rule 7: a counted jump that falls while a blast front crosses the dimension is counted when the front ends. */
    @GameTest(template = "range", timeoutTicks = 20, batch = "clock_jump_front", skyAccess = true)
    public static void aJumpDuringAFrontWaitsForIt(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        MinecraftServer server = level.getServer();
        WorldClock clock = WorldClock.get(server);
        Map<String, double[]> saved = jumps(server);
        boolean[] crossing = {true};
        try {
            WorldClock.holdJumpsWhile(l -> l == level && crossing[0]);
            clock.recordAwakeForTest(server);
            clock.jumpForTest(server, 5000);
            double[] held = clock.jumpsForTest(dim(level)), was = saved.get(dim(level));
            h.assertTrue(held[0] == was[0] && held[2] == was[2] + 5000, "not held by the front: " + java.util.Arrays.toString(held));
            crossing[0] = false;
            clock.settleForTest(server);
            double[] done = clock.jumpsForTest(dim(level));
            h.assertTrue(done[0] == was[0] + 5000 && done[2] == 0, "not counted after the front: " + java.util.Arrays.toString(done));
        } finally {
            WorldClock.holdJumpsWhile(NuclearStrikes::frontCrossing);
            restore(server, saved);
        }
        h.succeed();
    }

    /** Rule 9, mismatch 15: a frozen tick manager stops the world clock; running, it counts. */
    @GameTest(template = "range", timeoutTicks = 20, batch = "clock_freeze", skyAccess = true)
    public static void aFrozenServerStopsTheClock(GameTestHelper h) {
        MinecraftServer server = h.getLevel().getServer();
        double[] before = WorldClock.get(server).readingsForTest();
        try {
            server.tickRateManager().setFrozen(true);
            // the manager decides at its tick whether game elements run (as at a server tick's start)
            server.tickRateManager().tick();
            WorldClock.onServerTick(new ServerTickEvent.Post(() -> true, server));
            double[] frozen = WorldClock.get(server).readingsForTest();
            h.assertTrue(frozen[0] == before[0] && frozen[1] == before[1], "the clock counted a frozen tick: "
                    + java.util.Arrays.toString(before) + " → " + java.util.Arrays.toString(frozen));
        } finally {
            server.tickRateManager().setFrozen(false);
            server.tickRateManager().tick();
        }
        WorldClock.onServerTick(new ServerTickEvent.Post(() -> true, server));
        h.assertTrue(WorldClock.get(server).readingsForTest()[0] > before[0], "the running clock did not count");
        h.succeed();
    }

    /** Mismatch 13: the clocks reach every player once a second, also in a dimension with no detonations. */
    @GameTest(template = "range", timeoutTicks = 100, batch = "clock_packet", skyAccess = true)
    public static void theClocksReachEveryPlayer(GameTestHelper h) {
        ServerLevel nether = h.getLevel().getServer().getLevel(Level.NETHER);
        h.assertTrue(NuclearEvents.get(nether).detonations().isEmpty(), "the Nether holds detonations");
        var watcher = FuelFireGameTests.Watcher.in(h, nether, new Vec3(0, 100, 0), S2C.NukeClock.class);
        long start = nether.getGameTime();
        h.succeedWhen(() -> {
            h.assertTrue(watcher.got.size() >= 2, "clock packets: " + watcher.got.size() + " in " + (nether.getGameTime() - start) + " ticks");
            S2C.NukeClock p = watcher.got.get(watcher.got.size() - 1);
            h.assertTrue(p.clocks().known() && p.clocks().pace() >= 1, "the packet lacks the clocks: " + p);
            h.assertTrue(nether.getGameTime() - start <= 45, "more than a second apart");
        });
    }

    /** The clocks and the jumps survive a restart; a world saved before them loads with none. */
    @GameTest(template = "range", timeoutTicks = 20, batch = "clock_save", skyAccess = true)
    public static void theClocksSurviveARestart(GameTestHelper h) {
        MinecraftServer server = h.getLevel().getServer();
        Map<String, double[]> saved = jumps(server);
        try {
            WorldClock.get(server).restoreJumpsForTest(dim(h.getLevel()), new double[]{1234, 567, 89});
            WorldClock read = WorldClock.readBackForTest(server);
            h.assertTrue(java.util.Arrays.equals(read.readingsForTest(), WorldClock.get(server).readingsForTest()), "the clocks changed on a restart");
            h.assertTrue(java.util.Arrays.equals(read.jumpsForTest(dim(h.getLevel())), new double[]{1234, 567, 89}), "the jumps changed on a restart");
        } finally {
            restore(server, saved);
        }
        h.succeed();
    }
}
