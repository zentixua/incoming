package ua.zentix.incoming.client;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.level.ChunkPos;
import ua.zentix.incoming.client.lod.TimedLodBuffer;
import ua.zentix.incoming.net.S2C;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.Executors;

/** Client-owned deadline queue. The only external calls run on a serial worker, outside every tick-facing lock. */
final class ClientHorizon {
    // The measured 1 Mt panorama needs ~3,638 columns/s at the front, above the transport's 1,280/s ceiling.
    // 24 MiB prefilling left it 282 ticks late. Hold the encoded work earlier, without moving its deadlines.
    // Shared by all strikes in this client; this is separate from the server's global 512 MiB working budget.
    static final long QUEUED_BYTES = 96L << 20;
    private static final int QUEUED_COLUMNS = 65536;
    private static final ScheduledExecutorService WORKER = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "Incoming horizon");
        thread.setDaemon(true);
        return thread;
    });
    private static final TimedLodBuffer<S2C.VoxyChunk> QUEUE = new TimedLodBuffer<>(QUEUED_COLUMNS, QUEUED_BYTES);
    private static ClientLevel world;
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
            VoxyIngest.beginHints(level);
        }
        var ticket = QUEUE.reserve(ChunkPos.asLong(packet.x(), packet.z()), packet.notBefore(),
                packet.sections().length + 128L, 1, packet.detonation());
        boolean accepted = ticket != null && QUEUE.complete(ticket, packet);
        if (accepted) kick(level);
        return accepted;
    }

    static synchronized long bytes() { return QUEUE.bytes(); }

    static synchronized boolean full() { return QUEUE.bytes() >= QUEUED_BYTES - (1 << 20) - 128 || QUEUE.size() >= QUEUED_COLUMNS - 64; }

    static synchronized void tick(ClientLevel level, long now) {
        // Retire the old dimension even when a full old batch prevents any new terrain packet.
        if (world != level) {
            reset();
            world = level;
            VoxyIngest.beginHints(level);
        }
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
            java.util.function.IntToDoubleFunction clock = event -> clocks.getOrDefault(event, (double) now);
            for (int i = 0; i < 256 && changedSections < 1024 && QUEUE.epoch() == epoch; i++) {
                var ready = QUEUE.take(clock);
                if (ready == null) break;
                try {
                    // A concurrent reset may occur here. The explicit level belongs to this session; reset's
                    // returned barrier completes AFTER this external call, never merely after a current() check.
                    S2C.VoxyChunk packet = ready.value();
                    long started = System.nanoTime();
                    int sections = VoxyIngest.ingest(level, packet);
                    if (sections >= 0) {
                        changedSections += sections;
                        remember(level, epoch, packet);
                        var measured = observer;
                        if (measured != null) measured.accept(new Submission(ready.value(), now, System.nanoTime() - started, sections, bytes()));
                    } else {
                        requestFresh(level, epoch, packet);
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

    private static synchronized void remember(ClientLevel level, long epoch, S2C.VoxyChunk packet) {
        if (world == level && QUEUE.epoch() == epoch) VoxyIngest.remember(level, packet);
    }

    private static synchronized void requestFresh(ClientLevel level, long epoch, S2C.VoxyChunk packet) {
        if (world == level && QUEUE.epoch() == epoch) VoxyIngest.requestFresh(level, packet);
    }

    static CompletableFuture<Void> barrier() { return CompletableFuture.runAsync(() -> {}, WORKER); }

    /** Nonblocking request. The returned future is the Incoming submission fence, not a Voxy renderer fence. */
    static synchronized CompletableFuture<Void> reset() {
        QUEUE.stop();
        world = null;
        latestTick = Long.MIN_VALUE;
        latestClocks = java.util.Map.of();
        VoxyIngest.clearHints();
        draining = false;
        return barrier();
    }
}
