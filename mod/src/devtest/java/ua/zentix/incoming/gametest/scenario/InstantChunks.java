package ua.zentix.incoming.gametest.scenario;

import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ChunkLevel;
import net.minecraft.server.level.DistanceManager;
import net.minecraft.server.level.FullChunkStatus;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.storage.IOWorker;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import ua.zentix.incoming.Incoming;
import ua.zentix.incoming.util.Terrain;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.locks.LockSupport;

/**
 * Instant generation for the duration of scenarios: at the end of each server tick, everything that tickets require to be downloaded in full
 *  (level ≤ 33) is downloaded immediately ({@code level.getChunk}).
 * <p>
 * Why. Mod areas ({@code AreaLoader}: your chunk and the chunk in front of the projectile, the target area) begin to tick when the
 *  square around is ready, and the background generation prepares it for a different number of ticks - from run to run and from car to car.
 *  A projectile at the edge of ticking chunks leaves the world or remains in it depending on whether the neighbor has time, and the trajectory diverges
 *  from the reference. With instant generation, readiness is a function of ticket, and ticket is a function of flight: the flight is repeated exactly.
 * <p>
 *  There is one place in tick ({@link ServerTickEvent.Post} - after the levels and tests GameTest): the order of tests in batch does not affect it. Reading unready chunks of the mod is still visible ({@link SyncLoadWatch}): the chunk that tickets
 *  did not ask for will not appear on its own.
 * <p>
 * Blind spots: loading here is perfect. {@link SyncLoadWatch} only catches reading chunks that were not requested by any
 *  ticket; The chunk that the ticket requested, but the background generation has not yet given, is already ready for reading - synchronous waiting
 * such a chunk is not visible here. Waiting for the mod area (the projectile is waiting for the target to load, {@code AREA_WAIT_LIMIT}), leaving the world
 *  on the edge of ticking chunks due to slow generation, and returning to the world also do not work. This checks the run of
 *  with a real load ({@code -PscenarioRealChunks}, {@link ScenarioMode}): the properties are the same, without a reference.
 * <p>
 * Write chunks to disk. The unloaded chunk goes into the background queue {@link IOWorker} ready NBT, and in it the reads
 *  (the generation first looks for the chunk on disk) go ahead of the writes: the server GameTest ticks without pauses, and the queue cannot keep up with the batch scripts
 *  - from Lithium to 27,5 thousand chunks waiting to be written, 3 million {@code CompoundTag} and 2,5 GB live
 *  heaps with 11 thousand chunks in memory, CI (heap 4 GB) is falling {@code OutOfMemoryError}. Therefore, once every {@link #WRITE_PERIOD}
 *  tick the server waits until the queue finishes: the game server manages this between ticks.
 */
final class InstantChunks {
    private static final int FULL = ChunkLevel.byStatus(FullChunkStatus.FULL);
    /** How many times tick to wait for the uploaded chunks to be recorded. */
    private static final int WRITE_PERIOD = 100;

    private static int users;
    private static boolean registered;

    private InstantChunks() {}

    /** The scenario has begun: instantaneous generation as long as there is at least one. */
    static synchronized void acquire() {
        if (!registered) {
            registered = true;
            NeoForge.EVENT_BUS.addListener(InstantChunks::onServerTick);
        }
        users++;
    }

    static synchronized void release() {
        users--;
    }

    private static void onServerTick(ServerTickEvent.Post e) {
        if (users <= 0) return;
        boolean write = e.getServer().getTickCount() % WRITE_PERIOD == 0;
        for (ServerLevel level : e.getServer().getAllLevels()) {
            settle(level);
            // relief shooting without loading (disk, generator in the background) - also at the end of the order tick, and not when the disk has time
            ua.zentix.incoming.util.TerrainSurvey.settle(level);
            if (write) awaitWrites(level);
        }
    }

    /** Wait until the background queue completes the uploaded chunks (without flushing files to disk). */
    private static void awaitWrites(ServerLevel level) {
        long t0 = System.nanoTime();
        ((IOWorker) level.getChunkSource().chunkMap.chunkScanner()).synchronize(false).join();
        long ms = (System.nanoTime() - t0) / 1_000_000;
        if (ms >= 1000) Incoming.LOG.info("SCENARIO recording chunks ({}) waited for {} ms", level.dimension().location(), ms);
    }

    /**
     * Load everything that the ticket requires in full, and bring everything to the tick of entities that they give it to. tick - also the
     *  ticket function: vanilla transfers the ready chunk to the “tick entities” task of the server thread when the square 5×5
     *  around ({@code ChunkMap.prepareEntityTickingChunk}) is ready, and will the queue reach this task by the next tick, solve
     *  streams and other people's level recalculations. The projectile leaves the world when a step leads to a chunk without ticking entities, and returns
     *  when the path ahead is ticking - without this “Lancet” script, {@code loiter_rail.player} left the world or not
     *  according to who recalculated the levels in tick ({@link ChunkChurn}).
     */
    static void settle(ServerLevel level) {
        ServerChunkCache cache = level.getChunkSource();
        // tickets taken in this tick - to chunk levels now (opened AT)
        cache.runDistanceManagerUpdates();
        List<ChunkPos> pending = new ArrayList<>();
        for (ChunkHolder holder : cache.chunkMap.getChunks()) {
            if (holder.getTicketLevel() <= FULL && !Terrain.ready(level, holder.getPos().x, holder.getPos().z)) pending.add(holder.getPos());
        }
        for (ChunkPos c : pending) level.getChunk(c.x, c.z);
        awaitEntityTicking(level, cache);
    }

    /** Waiting limit for tick entities, ms: longer is not a wait, but an error (a chunk that cannot wait for its neighbors). */
    private static final long TICKING_WAIT_MS = 10_000;

    /**
     * Execute server thread tasks until each chunk that is ticketed with tick entities is ticking them. We don’t expect tick blocks
     * : it also needs the loaded entities of the chunk, and vanilla loads them only in the tick world - and the flight does not depend on the tick of
     *  blocks.
     */
    private static void awaitEntityTicking(ServerLevel level, ServerChunkCache cache) {
        DistanceManager tickets = cache.chunkMap.getDistanceManager();
        List<ChunkPos> late = new ArrayList<>();
        for (ChunkHolder holder : cache.chunkMap.getChunks()) {
            ChunkPos c = holder.getPos();
            if (tickets.inEntityTickingRange(c.toLong()) && !level.isPositionEntityTicking(c.getWorldPosition())) late.add(c);
        }
        long deadline = System.nanoTime() + TICKING_WAIT_MS * 1_000_000;
        while (!late.isEmpty()) {
            if (System.nanoTime() > deadline) {
                throw new IllegalStateException("SCENARIO: chunks did not tick with entities behind " + TICKING_WAIT_MS + " ms: " + late.subList(0, Math.min(8, late.size())));
            }
            if (!cache.pollTask()) LockSupport.parkNanos(100_000);
            late.removeIf(c -> level.isPositionEntityTicking(c.getWorldPosition()));
        }
    }
}
