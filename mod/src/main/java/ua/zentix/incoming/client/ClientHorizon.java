package ua.zentix.incoming.client;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.level.ChunkPos;
import ua.zentix.incoming.client.lod.TimedLodBuffer;
import ua.zentix.incoming.net.S2C;

import java.util.LinkedHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.Executors;

/** Client-owned deadline queue. The only external calls run on a serial worker, outside every tick-facing lock. */
final class ClientHorizon {
    static final long QUEUED_BYTES = 24L << 20, REPAIR_BYTES = 24L << 20;
    private static final int QUEUED_COLUMNS = 16384;
    private static final ScheduledExecutorService WORKER = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "Incoming horizon");
        thread.setDaemon(true);
        return thread;
    });
    private static final TimedLodBuffer<S2C.VoxyChunk> QUEUE = new TimedLodBuffer<>(QUEUED_COLUMNS, QUEUED_BYTES);
    private static final LinkedHashMap<Long, S2C.VoxyChunk> REPAIR = new LinkedHashMap<>();
    private static ClientLevel world;
    private static long repairBytes;
    private static boolean draining;
    private static long latestTick;
    private static java.util.Map<Integer, Double> latestClocks = java.util.Map.of();
    record Submission(S2C.VoxyChunk packet, long tick, long elapsedNanos, int sections, long retainedBytes) {}
    /** Optional development measurement, no per-column allocation when absent. */
    static volatile java.util.function.Consumer<Submission> observer;

    private ClientHorizon() {}

    static synchronized boolean offer(ClientLevel level, S2C.VoxyChunk packet) {
        if (world != level) {
            reset();
            world = level;
        }
        var ticket = QUEUE.reserve(ChunkPos.asLong(packet.x(), packet.z()), packet.notBefore(),
                packet.sections().length + 128L, 1);
        boolean accepted = ticket != null && QUEUE.complete(ticket, packet);
        if (accepted) kick(level);
        return accepted;
    }

    static synchronized boolean repair(ClientLevel level, long key) {
        if (world != level) return false;
        S2C.VoxyChunk packet = REPAIR.get(key);
        return packet != null && offer(level, packet);
    }

    static synchronized long bytes() { return QUEUE.bytes() + repairBytes; }

    static synchronized boolean full() { return QUEUE.bytes() >= QUEUED_BYTES - (1 << 20) - 128 || QUEUE.size() >= QUEUED_COLUMNS - 64; }

    static synchronized void tick(ClientLevel level, long now) {
        if (world != level) return;
        java.util.Map<Integer, Double> clocks = new java.util.HashMap<>();
        for (var active : ua.zentix.incoming.client.nuclear.ClientNuclear.detonations()) {
            clocks.put(active.d.id(), ua.zentix.incoming.client.nuclear.ClientNuclear.horizonTime(active.d.id(), now));
        }
        latestTick = now;
        latestClocks = clocks;
        kick(level);
    }

    private static void kick(ClientLevel level) {
        if (draining || QUEUE.size() == 0) return;
        draining = true;
        long epoch = QUEUE.epoch();
        WORKER.execute(() -> drain(level, epoch));
    }

    private static void drain(ClientLevel level, long epoch) {
        long now;
        java.util.Map<Integer, Double> clocks;
        synchronized (ClientHorizon.class) {
            if (world != level || QUEUE.epoch() != epoch) return;
            now = latestTick;
            clocks = latestClocks;
        }
        try {
            if (VoxyIngest.backlogged()) return;
            int changedSections = 0;
            for (int i = 0; i < 256 && changedSections < 1024 && QUEUE.epoch() == epoch; i++) {
                var ready = QUEUE.take(Long.MAX_VALUE);
                if (ready == null) break;
                if (ready.ticket().due() > clocks.getOrDefault(ready.value().detonation(), (double) now)) {
                    QUEUE.defer(ready);
                    break;
                }
                try {
                    // A concurrent reset may occur here. The explicit level belongs to this session; reset's
                    // returned barrier completes AFTER this external call, never merely after a current() check.
                    S2C.VoxyChunk packet = ready.value();
                    long started = System.nanoTime();
                    int sections = VoxyIngest.ingest(level, packet);
                    if (sections >= 0) {
                        changedSections += sections;
                        // Only the incoming changed sections enter Voxy. The union is retained solely for stale replay repair.
                        S2C.VoxyChunk previous = previous(level, epoch, ready.ticket().key());
                        if (previous != null) packet = new S2C.VoxyChunk(packet.dimension(), packet.x(), packet.z(),
                                ua.zentix.incoming.compat.LodSections.merge(previous.sections(), packet.sections(),
                                        level.registryAccess().registryOrThrow(net.minecraft.core.registries.Registries.BIOME)), packet.notBefore(), packet.detonation());
                        remember(level, epoch, packet);
                        var measured = observer;
                        if (measured != null) measured.accept(new Submission(ready.value(), now, System.nanoTime() - started, sections, bytes()));
                    }
                } finally {
                    QUEUE.finish(ready);
                }
            }
        } finally {
            synchronized (ClientHorizon.class) {
                if (QUEUE.epoch() == epoch) {
                    draining = QUEUE.size() > 0;
                    // Service completion is asynchronous; waiting for the next game tick wastes its spare capacity.
                    // Polling never advances the event clock and never holds the client/server tick thread.
                    if (draining) WORKER.schedule(() -> drain(level, epoch), 2, TimeUnit.MILLISECONDS);
                }
            }
        }
    }

    private static synchronized S2C.VoxyChunk previous(ClientLevel level, long epoch, long key) {
        return world == level && QUEUE.epoch() == epoch ? REPAIR.get(key) : null;
    }

    private static synchronized void remember(ClientLevel level, long epoch, S2C.VoxyChunk packet) {
        if (world != level || QUEUE.epoch() != epoch) return;
        VoxyIngest.remember(level, packet);
        long key = ChunkPos.asLong(packet.x(), packet.z());
        S2C.VoxyChunk previous = REPAIR.remove(key);
        if (previous != null) repairBytes -= previous.sections().length + 128L;
        REPAIR.put(key, packet);
        repairBytes += packet.sections().length + 128L;
        var it = REPAIR.values().iterator();
        while (repairBytes > REPAIR_BYTES && it.hasNext()) {
            repairBytes -= it.next().sections().length + 128L;
            it.remove();
        }
    }

    /** Nonblocking request. The returned future is the Incoming submission fence, not a Voxy renderer fence. */
    static synchronized CompletableFuture<Void> reset() {
        QUEUE.stop();
        world = null;
        latestTick = Long.MIN_VALUE;
        latestClocks = java.util.Map.of();
        REPAIR.clear();
        repairBytes = 0;
        draining = false;
        return CompletableFuture.runAsync(() -> {}, WORKER);
    }
}
