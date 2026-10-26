package ua.zentix.incoming.gametest;

import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import ua.zentix.incoming.Incoming;
import ua.zentix.incoming.nuclear.world.WorkClock;
import ua.zentix.incoming.registry.ModAttachments;
import ua.zentix.incoming.strike.StrikeWorld;
import ua.zentix.incoming.work.ImpactTiming;
import ua.zentix.incoming.work.TickReserve;
import ua.zentix.incoming.work.UnitQueue;
import ua.zentix.incoming.work.WorkScheduler;

import java.util.function.Consumer;
import java.util.ArrayList;
import java.util.List;

@GameTestHolder(Incoming.MOD_ID)
@PrefixGameTestTemplate(false)
public final class ImpactScheduleGameTests {
    private static final long MS = 1_000_000L;
    private ImpactScheduleGameTests() {}

    /** A controlled-clock scenario with an actual profiler observer and hasTime, not counting clocks. */
    @GameTest(template = "pad", batch = "work_impact_schedule")
    public static void backgroundCannotTakeReadyImpactWindow(GameTestHelper h) {
        for (int mandatory : new int[]{40, 43, 47}) {
            long[] now = {0};
            WorkClock clock = WorkClock.decaying(() -> now[0], 0.5);
            ImpactTiming timing = new ImpactTiming(() -> now[0]);
            TickReserve reserve = new TickReserve();
            reserve.observe(mandatory * MS);
            int busy = 0;
            long used = 0, maximum = 0;
            for (int tick = 0; tick < 600; tick++) {
                long start = now[0], deadline = start + 50 * MS;
                timing.begin(() -> true);
                long early = reserve.earlyNanos(20 * MS, now[0], deadline);
                clock.start(early, () -> now[0] < deadline, 0);
                work(clock, now);
                long earlyUsed = clock.usedThisTickNanos();
                now[0] += mandatory * MS;
                timing.push("chunk_unload");
                now[0] = Math.max(now[0], deadline);
                timing.pop();
                if (now[0] >= deadline) busy++;
                clock.resume(0, () -> now[0] < deadline, 0);
                if (earlyUsed <= early) clock.allowLateStart(); // a newly queued hit in Post
                work(clock, now);
                reserve.observe(timing.mandatoryNanos(clock.usedThisTickNanos()));
                h.assertTrue(now[0] - start <= 50 * MS + MS / 4, "tick deadline plus final unit");
                h.assertTrue(clock.usedThisTickNanos() <= 30 * MS + MS / 4, "one common work cap");
                used += clock.usedThisTickNanos();
                maximum = Math.max(maximum, now[0] - start);
            }
            h.assertTrue(busy == 600 && used > 600 * 2 * MS, "IMPACT pinned to BUSY");
            Incoming.LOG.info("PL10 CLOCK mandatory={} post_busy={}/600 impact_ms={} max_tick_ms={}",
                    mandatory, busy, used / 600.0 / MS, maximum / (double) MS);
        }
        h.succeed();
    }

    private static void work(WorkClock clock, long[] now) {
        while (clock.canStart(0)) {
            long began = clock.begin(); now[0] += MS / 4; clock.end(began, 0);
        }
    }

    /** Real server events and ChunkMap profiler sections: no manually pushed section names or fake clock. */
    @GameTest(template = "pad", timeoutTicks = 40, batch = "work_impact_profiler")
    public static void observerSeesVanillaChunkUnloadOnConsecutiveTicks(GameTestHelper h) {
        var server = h.getLevel().getServer();
        WorkClock previous = WorkScheduler.impactClock(server);
        WorkScheduler.useImpactClock(server, WorkScheduler.newImpactClock());
        int[] posts = {0}, seen = {0}, sections = {0};
        List<String> errors = new ArrayList<>();
        Consumer<ServerTickEvent.Post> observe = e -> {
            // The test starts after the worlds of its first tick; the new clock takes effect next Pre.
            if (++posts[0] == 1 || seen[0] >= 4) return;
            ImpactTiming timing = server.overworld().getData(ModAttachments.IMPACT_TIMING);
            int count = timing.chunkUnloadSections();
            if (count == 0) errors.add("no vanilla chunk_unload in server tick " + server.getTickCount());
            sections[0] += count;
            seen[0]++;
        };
        NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, observe);
        StrikeGameTests.afterTest(h, () -> {
            NeoForge.EVENT_BUS.unregister(observe);
            WorkScheduler.useImpactClock(server, previous);
        });
        h.succeedWhen(() -> {
            h.assertTrue(seen[0] == 4, "waiting for four real server ticks");
            h.assertTrue(errors.isEmpty(), errors.toString());
            Incoming.LOG.info("PL10 PROFILER real_ticks={} chunk_unload_sections={}", seen[0], sections[0]);
        });
    }

    /** Server events continue while the GameTest/world is frozen; they thaw it without wall-clock sleeps. */
    @GameTest(template = "pad", timeoutTicks = 100, batch = "work_impact_modes")
    public static void freezeSprintAndMultipleWorldsShareBudget(GameTestHelper h) {
        var server = h.getLevel().getServer();
        var rates = server.tickRateManager();
        WorkClock previous = WorkScheduler.impactClock(server);
        long[] now = {0};
        WorkClock clock = WorkClock.decaying(() -> now[0], 0.5);
        WorkScheduler.useImpactClock(server, clock);
        int[] done = {0, 0};
        ServerLevel[] levels = {server.overworld(), server.getLevel(Level.NETHER)};
        UnitQueue.Job[] jobs = new UnitQueue.Job[2];
        for (int i = 0; i < 2; i++) {
            int world = i;
            jobs[i] = new UnitQueue.Job() {
                @Override public boolean step(ServerLevel level) { now[0] += MS / 4; done[world]++; return true; }
                @Override public String describe() { return "PL10 mode probe"; }
            };
            StrikeWorld.get(levels[i]).impacts().add(levels[i], jobs[i]);
        }
        int[] posts = {0}, frozenUnits = {0};
        boolean[] complete = {false};
        List<String> errors = new ArrayList<>();
        boolean wasFrozen = rates.isFrozen();
        rates.setFrozen(true);
        Consumer<ServerTickEvent.Post> observe = e -> {
            if (complete[0]) return;
            int tick = ++posts[0];
            if (tick == 1) frozenUnits[0] = clock.units(); // freeze was requested during this tick
            if (tick >= 2 && tick <= 4 && clock.units() != frozenUnits[0]) errors.add("frozen impacts advanced");
            if (tick == 4) rates.requestGameToSprint(3);
            if (tick >= 5) {
                if (clock.usedThisTickNanos() > WorkScheduler.totalNanos() + MS / 4) errors.add("two worlds/windows exceeded cap");
                if (tick == 7) {
                    if (done[0] == 0 || done[1] == 0) errors.add("one world starved");
                    if (clock.usedThisTickNanos() <= 2 * MS) errors.add("sprint limited to BUSY");
                    Incoming.LOG.info("PL10 MODES frozen_posts=3 sprint_posts=3 overworld_units={} nether_units={} max_tick_work_ms={}",
                            done[0], done[1], clock.maxTickNanos() / (double) MS);
                    // Sprint restores its pre-sprint frozen state. Thaw, then complete inside GameTestTicker:
                    // succeed() from a Post listener bypasses GameTestInfo's testPassed notification entirely.
                    rates.stopSprinting();
                    rates.setFrozen(wasFrozen);
                    complete[0] = true;
                }
            }
        };
        NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, observe);
        StrikeGameTests.afterTest(h, () -> {
            NeoForge.EVENT_BUS.unregister(observe);
            for (ServerLevel level : levels) StrikeWorld.get(level).impacts().clear(level);
            rates.stopSprinting();
            rates.setFrozen(wasFrozen);
            WorkScheduler.useImpactClock(server, previous);
        });
        h.succeedWhen(() -> {
            h.assertTrue(complete[0], "mode probe pending");
            h.assertTrue(errors.isEmpty(), errors.toString());
        });
    }
}
