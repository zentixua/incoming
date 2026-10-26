package ua.zentix.incoming.gametest.scenario;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import ua.zentix.incoming.grid.Blackouts;
import ua.zentix.incoming.nuclear.world.WorkClock;
import ua.zentix.incoming.registry.ModAttachments;
import ua.zentix.incoming.strike.StrikeWorld;
import ua.zentix.incoming.work.WorkScheduler;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Hard work budget per tick ({@link WorkScheduler}, root 1) during scenarios. Band clock - counting
 * ({@link WorkClock#counting}, unit of work = 1 ms): Queue decisions are independent of machine speed, and the
 *  bound is checked exactly. At the end of each tick (after the scheduler): the streak of hits is no more than the total duration and one
 *  unit, hits with blackout are no more than the total duration and one unit per streak; and at which tick the hit queue
 *  was empty. There is only one clock per server, so it is changed while at least one scenario is running (batch of scenarios - only
 *  of the scenarios), and the game clock is returned after the last one.
 */
final class WorkBudgetWatch {
    /** Price per unit of work for a counting clock. */
    static final long UNIT = 1_000_000L;

    /** tick of the server in which the boundary was violated, and by what. */
    record Violation(int tick, String what) {}

    private static int users;
    private static boolean registered;
    private static final List<Violation> violations = new ArrayList<>();
    /** The last tick of the server, at the end of which the hit queues of all worlds were empty. */
    private static int lastEmpty = -1;

    private WorkBudgetWatch() {}

    static synchronized void acquire(MinecraftServer server) {
        if (!registered) {
            registered = true;
            NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, WorkBudgetWatch::onServerTick);
        }
        if (users++ == 0) {
            WorkScheduler.useImpactClock(server, WorkClock.counting(UNIT));
            Blackouts.useClock(server, WorkClock.counting(UNIT));
        }
    }

    static synchronized void release(MinecraftServer server) {
        if (--users == 0) {
            WorkScheduler.useImpactClock(server, WorkScheduler.newImpactClock());
            Blackouts.useClock(server, Blackouts.newClock());
        }
    }

    private static void onServerTick(ServerTickEvent.Post e) {
        if (users <= 0) return;
        MinecraftServer server = e.getServer();
        WorkClock impact = WorkScheduler.impactClock(server), grid = Blackouts.clock(server);
        long total = WorkScheduler.totalNanos();
        int tick = server.getTickCount();
        if (impact.usedThisTickNanos() > total + UNIT) {
            violations.add(new Violation(tick, String.format(Locale.ROOT, "hits %d ms with deadline %d", impact.usedThisTickNanos() / UNIT, total / UNIT)));
        }
        long both = impact.usedThisTickNanos() + grid.usedThisTickNanos();
        if (both > total + 2 * UNIT) {
            violations.add(new Violation(tick, String.format(Locale.ROOT, "hits and blackout %d ms at time limit %d", both / UNIT, total / UNIT)));
        }
        boolean empty = true;
        for (ServerLevel level : server.getAllLevels()) {
            if (level.hasData(ModAttachments.STRIKE_WORLD) && !StrikeWorld.get(level).impacts().isEmpty()) empty = false;
        }
        if (empty) lastEmpty = tick;
    }

    /** Boundary violations in the server tick from {@code fromTick} (inclusive). */
    static synchronized List<Violation> since(int fromTick) {
        return violations.stream().filter(v -> v.tick() >= fromTick).toList();
    }

    /** Was the hit queue empty at the end of some server tick with {@code fromTick}. */
    static boolean emptiedSince(int fromTick) {
        return lastEmpty >= fromTick;
    }
}
