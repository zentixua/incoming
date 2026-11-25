package ua.zentix.incoming.gametest;

import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.storage.IOWorker;
import ua.zentix.incoming.gametest.scenario.InstantChunks;
import ua.zentix.incoming.nuclear.world.NuclearWorld;
import ua.zentix.incoming.nuclear.world.RuinWorkers;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * The mod's background ruin work finished at each test tick, so a check of it depends on game ticks only. Zone layouts
 * ({@code NuclearPrep}), disk header scans and chunk reads (the chunk I/O worker), their parsing and every ruin plan
 * ({@link RuinWorkers}) run in wall time on other threads: a GameTest server ticks without pauses (~2000 ticks a second
 * on CI), so how many ticks they took followed the runner, and a check paced at 50 ms a tick still raced them. Here each
 * tick waits until all of it submitted so far is done (a read's parsing, a parse's next read: until nothing is left),
 * then loads what tickets ask for in full ({@link InstantChunks#settle}): work a tick submits is ready by the next tick.
 * The wall-time limit only guards against a hang; reaching it fails the check.
 */
final class BackgroundSettle {
    /** A hang, not a slow machine: the largest wait seen locally is under a second. */
    private static final long LIMIT_MS = 60_000;

    private BackgroundSettle() {}

    static void settle(ServerLevel level) {
        long until = System.nanoTime() + LIMIT_MS * 1_000_000L;
        IOWorker io = (IOWorker) level.getChunkSource().chunkMap.chunkScanner();
        boolean idle = false;
        while (!idle) {
            if (!NuclearWorld.get(level).awaitPreparationLayoutsForTest(left(until))) fail("zone layout");
            sync(io, until);
            if (!RuinWorkers.awaitForTest(RuinWorkers.Priority.FAR, left(until))) fail("ruin workers");
            // a finished task may have queued a read whose answer queues another task
            sync(io, until);
            idle = RuinWorkers.awaitForTest(RuinWorkers.Priority.FAR, 0);
        }
        try {
            InstantChunks.settle(level);
        } catch (RuntimeException e) {
            // any other exception in a test's tick stops the whole GameTest server
            throw new GameTestAssertException("tickets' chunks did not load: " + e);
        }
    }

    /** Reads and header scans queued at the chunk I/O worker answered (their callbacks ran on its thread). */
    private static void sync(IOWorker io, long until) {
        try {
            io.synchronize(false).get(Math.max(0, until - System.nanoTime()), TimeUnit.NANOSECONDS);
        } catch (TimeoutException e) {
            fail("chunk I/O");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            fail("chunk I/O (interrupted)");
        } catch (ExecutionException e) {
            throw new GameTestAssertException("chunk I/O failed: " + e.getCause());
        }
    }

    private static long left(long until) {
        return Math.max(0, (until - System.nanoTime()) / 1_000_000L);
    }

    private static void fail(String what) {
        throw new GameTestAssertException("background work did not finish within " + LIMIT_MS + " ms: " + what);
    }
}
