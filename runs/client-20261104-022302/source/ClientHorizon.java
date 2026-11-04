package ua.zentix.incoming.client;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.level.ChunkPos;
import ua.zentix.incoming.client.lod.TimedLodBuffer;
import ua.zentix.incoming.net.S2C;

import java.util.LinkedHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Client-owned deadline queue. The only external calls run on a serial worker, outside every tick-facing lock. */
final class ClientHorizon {
    static final long QUEUED_BYTES = 24L << 20, REPAIR_BYTES = 24L << 20;
    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "Incoming horizon");
        thread.setDaemon(true);
        return thread;
    });
    private static final TimedLodBuffer<S2C.VoxyChunk> QUEUE = new TimedLodBuffer<>(8192, QUEUED_BYTES);
    private static final LinkedHashMap<Long, S2C.VoxyChunk> REPAIR = new LinkedHashMap<>();
    private static ClientLevel world;
    private static long repairBytes;
    private static boolean draining;
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
        return ticket != null && QUEUE.complete(ticket, packet);
    }

    static synchronized boolean repair(ClientLevel level, long key) {
        if (world != level) return false;
        S2C.VoxyChunk packet = REPAIR.get(key);
        return packet != null && offer(level, packet);
    }

    static synchronized long bytes() { return QUEUE.bytes() + repairBytes; }

    static synchronized boolean full() { return QUEUE.bytes() >= QUEUED_BYTES - (1 << 20); }

    static synchronized void tick(ClientLevel level, long now) {
        if (world != level || draining) return;
        draining = true;
        long epoch = QUEUE.epoch();
        java.util.Map<Integer, Double> clocks = new java.util.HashMap<>();
        for (var active : ua.zentix.incoming.client.nuclear.ClientNuclear.detonations()) {
            clocks.put(active.d.id(), ua.zentix.incoming.client.nuclear.ClientNuclear.horizonTime(active.d.id(), now));
        }
        WORKER.execute(() -> drain(level, now, epoch, clocks));
    }

    private static void drain(ClientLevel level, long now, long epoch, java.util.Map<Integer, Double> clocks) {
        try {
            if (VoxyIngest.backlogged()) return;
            for (int i = 0; i < 256 && QUEUE.epoch() == epoch; i++) {
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
            synchronized (ClientHorizon.class) { if (QUEUE.epoch() == epoch) draining = false; }
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
        REPAIR.clear();
        repairBytes = 0;
        draining = false;
        return CompletableFuture.runAsync(() -> {}, WORKER);
    }
}
