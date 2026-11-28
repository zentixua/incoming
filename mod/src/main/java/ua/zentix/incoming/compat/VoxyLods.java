package ua.zentix.incoming.compat;

import io.netty.channel.Channel;
import io.netty.util.AttributeKey;
import net.minecraft.Util;
import net.minecraft.network.Connection;
import net.minecraft.network.PacketSendListener;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunk;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import org.jetbrains.annotations.Nullable;
import ua.zentix.incoming.Incoming;
import ua.zentix.incoming.net.C2S;
import ua.zentix.incoming.net.LodBatchFlow;
import ua.zentix.incoming.net.LodDiag;
import ua.zentix.incoming.net.S2C;
import ua.zentix.incoming.nuclear.world.FarLods;
import ua.zentix.incoming.registry.ModAttachments;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiConsumer;
import java.util.function.LongSupplier;

/**
 * World changes made by the mod, for players' Voxy LODs ({@code client/VoxyIngest}): alongside Distant Horizons through
 * the same path ({@link DhUpdates}: loaded chunk marks, copies of chunks outside the world, heavy-zone copies at the front).
 * Voxy is a client mod with no server component: it builds its LODs from chunks received by the client and cannot see
 * changes in chunks the client does not have (distant ruins, detonation scorch and district darkness —
 * {@code nuclear/world/FarLods} copies, present neither in the world nor on disk; a crater in a server-loaded chunk far
 * from the player). The server therefore sends such chunks to players with Voxy ({@link C2S.LodMods}) via
 * {@link S2C.VoxyChunk} (sections with light — {@link LodSections}).
 * <ul>
 * <li>Recipients: players in the same world with Voxy within {@link FarLods#FAR_CHUNKS} chunks who do not have this chunk
 * (Voxy rebuilds chunks the player sees itself from vanilla packets).</li>
 * <li>Rate: as fast as the client acknowledges, within a window of unacknowledged bytes that follows the queue it builds
 * ({@link LodBatchFlow}, {@link #WINDOW_MIN}–{@link #WINDOW_MAX}), sent by
 * its network event loop with one chunk write outstanding and headroom left for game packets. The player queue is
 * limited to {@link #QUEUE_BYTES}, a fresh copy of a chunk replaces its waiting copy. Queue space is {@link #room}:
 * {@link DhUpdates} uses it to deliver chunks even without DH.</li>
 * <li>A copy is built in the background (light from its blocks, {@link CopyLight}); a loaded chunk is snapshotted on the
 * server thread.</li>
 * </ul>
 * With LOD Server Support on the server: it broadcasts world-saved chunks itself (live chunk or disk — already with ruins),
 * but a chunk outside the world is sent only on the client's first comparison, from disk, so intact: the client reports
 * such a chunk ({@link C2S.VoxyStale}) and the server resends the copy ({@link #stale}). Non-persistent attachment of the
 * main world: server players, their queues and copies being built.
 */
public final class VoxyLods {
    /** Headroom below Netty's high watermark for normal game traffic (transport policy, not a physical quantity). */
    static final int WRITE_HEADROOM = 16 * 1024;
    /** Even with a raised Netty watermark, do not add LODs behind more than this many pending bytes. */
    static final int MAX_PENDING_BYTES = 32 * 1024;
    /**
     * Most section bytes sent and not yet acknowledged by the client, whatever buffers lie between (Netty, kernel,
     * proxy, line): what can wait ahead of game packets. Counted before the connection's compression, which shrinks ruin
     * chunks 5.7× (rig, 26.11.2026): 1.5 MiB is about 280 KB on the wire, just above the 240 KB that Artem's LTE link
     * (4.5 MB/s, 54 ms round trip) holds in flight, so such a line stays full. One chunk larger than the window goes alone.
     */
    static final int WINDOW_MAX = 3 << 19;
    /** The window never closes below this: two batches, so acknowledgements keep coming on any line. */
    static final int WINDOW_MIN = 128 * 1024;
    /**
     * Our own queue the window allows beyond the line's bandwidth-delay product ({@link LodBatchFlow}): LEDBAT's largest
     * TARGET (RFC 6817). It is also how much of another sender's queue (LSS on the same connection) the window holds its
     * share against: after a strike with LSS re-sending columns, mark-to-ack times on the LTE rig were 65 ms above base
     * at p90 (26.11.2026). A 25 ms target there gave ruins 6 % of the line beside a 100 ms foreign queue (simulation,
     * `VoxyLodsSharedLineTest`), this one 38 %. On slow lines it is the queue game packets wait behind while a backlog
     * streams, 88–222 ms from 4.5 down to 0.6 MB/s (`slowerLinesKeepTheLineFullWithAShortQueue`). A socket that caps
     * unsent data (`tcp_notsent_lowat`; rig: at most 47 KB unsent, against 2.5 MB without) leaves the window's bytes
     * beyond TCP's own window in the stock, behind game packets (by mechanism, not measured on a slow line).
     */
    static final long TARGET_NANOS = 100_000_000;
    /** A batch mark, so an acknowledgement, at least every this many section bytes: the window refills as chunks land. */
    static final int BATCH_BYTES = 64 * 1024;
    /**
     * Chunks the server thread keeps ready on the network event loop, topped up every tick: the window plus what a fast
     * line sends within a tick, so acknowledgements between ticks find chunks to send. It is also the one rate ceiling:
     * 2 MiB a tick, 40 MiB/s of sections at 20 TPS (about 7 MB/s on the wire).
     */
    static final int STOCK_BYTES = 2 << 20;
    /** Section bytes one pump task writes before it yields the event loop to other connections and game packets. */
    static final int PUMP_BYTES = 256 * 1024;
    /**
     * Chunks per player whose last bytes are remembered ({@link Viewer#offer}): no more than the client remembers to
     * report LSS overwrites of ({@link C2S.VoxyStale#REMEMBERED}), 4 MiB of map at most.
     */
    static final int REMEMBERED = C2S.VoxyStale.REMEMBERED;
    /** Maximum waiting player queue size; this does not bound bytes already handed to the connection. */
    static final long QUEUE_BYTES = 48L << 20;
    /** Estimated chunk size (for {@link #room}): 24 sections with palettes and surface light — ~40 KB before compression. */
    static final int CHUNK_BYTES = 40 * 1024;
    /**
     * Maximum LSS-overwritten chunks a player can report per second: each costs a disk header read or a read and copy;
     * the client itself reports at most {@link C2S.VoxyStale#PER_TICK} per tick; excess reports are dropped.
     */
    static final int STALE_PER_SECOND = 256;

    /**
     * Player with Voxy. The server thread keeps their chunks in order (chunk → packet; a fresh copy replaces a waiting one
     * in place) and hands at most {@link #STOCK_BYTES} at a time to the connection's network event loop, which sends them
     * within the acknowledged window ({@link LodBatchFlow}) as Netty allows and continues on each acknowledgement
     * without waiting for a server tick.
     */
    static final class Viewer {
        final LinkedHashMap<Long, S2C.VoxyChunk> queue = new LinkedHashMap<>();
        long bytes, dropped;
        /** Chunks written to the connection (event loop), read and reset by the server thread's minute report. */
        final AtomicLong sent = new AtomicLong();
        @Nullable
        private final Channel channel;
        private final BiConsumer<CustomPacketPayload, PacketSendListener> transport;
        /** Server thread → event loop: chunks taken from the queue, not yet written. */
        private final ConcurrentLinkedQueue<S2C.VoxyChunk> stock = new ConcurrentLinkedQueue<>();
        private final AtomicLong stockBytes = new AtomicLong();
        private final AtomicBoolean pumpQueued = new AtomicBoolean();
        /** Event loop only. */
        final LodBatchFlow flow = new LodBatchFlow(WINDOW_MIN, WINDOW_MAX, BATCH_BYTES, TARGET_NANOS);
        private final LongSupplier clock;
        /** One chunk write outstanding, from hand-over to the write completion callback. Event loop only. */
        private boolean writing, pumping;
        /** {@link LodDiag}: mark times by batch ID (event loop); chunks and bytes written this second, bytes in flight. */
        private final ArrayDeque<long[]> diagMarks = new ArrayDeque<>();
        private volatile long diagInFlight, diagWindow;
        private volatile int diagChunks;
        private volatile long diagBytes;

        Viewer(@Nullable Channel channel, BiConsumer<CustomPacketPayload, PacketSendListener> transport) {
            this(channel, transport, System::nanoTime);
        }

        Viewer(@Nullable Channel channel, BiConsumer<CustomPacketPayload, PacketSendListener> transport, LongSupplier clock) {
            this.channel = channel;
            this.transport = transport;
            this.clock = clock;
        }

        static Viewer of(ServerPlayer player) {
            Connection connection = player.connection.getConnection();
            // Straight to the Connection, which writes at once on its own event loop; the player's send wrapper only
            // adds NeoForge's channel check, already passed: the client announced Voxy with this mod's own packet.
            return new Viewer(connection.channel(), (packet, listener) -> connection.send(new ClientboundCustomPayloadPacket(packet), listener));
        }

        /**
         * Server thread. A chunk with the same bytes as the last one queued for this player in this world is not sent
         * again: its Voxy already has it (2 719 of 17 390 chunks after a 300 kt strike, 28.11.2026), and a Flashback
         * recording would keep each copy. Whatever else writes the chunk into the client's Voxy {@link #forget}s it:
         * the server sending the chunk itself (Voxy rebuilds what the player sees) and LOD Server Support (its overwrite
         * reported by the client).
         */
        void offer(long chunk, S2C.VoxyChunk packet) {
            offer(chunk, packet, print(packet.sections()));
        }

        void offer(long chunk, S2C.VoxyChunk packet, long print) {
            if (last.containsKey(chunk) && last.get(chunk) == print) {
                repeats++;
                return;
            }
            S2C.VoxyChunk was = queue.remove(chunk);
            if (was != null) bytes -= was.sections().length;
            if (bytes + packet.sections().length > QUEUE_BYTES) {
                dropped++;
                last.remove(chunk);
                return;
            }
            queue.put(chunk, packet);
            bytes += packet.sections().length;
            // a bound, not a cache policy: past it, chunks may go again once
            if (last.size() >= REMEMBERED) last.clear();
            last.put(chunk, print);
        }

        /** The client's Voxy no longer holds what was sent for {@code chunk}: the player received it or LSS wrote it. */
        void forget(long chunk) {
            last.remove(chunk);
        }

        /** Last queued bytes by chunk, as {@link #print}: server thread. */
        private final it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap last = new it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap();
        /** Chunks not sent again since the minute report: same bytes as the last queued ({@link #offer}). */
        long repeats;

        /** Server thread, every tick: top up the event loop's stock and let it send. Never waits for the network. */
        void drain() {
            stockUp();
            if (!stock.isEmpty()) schedulePump();
        }

        /** Any thread: one pump task at a time on the connection's event loop. */
        private void schedulePump() {
            if (channel == null || !channel.isActive() || !pumpQueued.compareAndSet(false, true)) return;
            channel.eventLoop().execute(() -> {
                pumpQueued.set(false);
                pump();
            });
        }

        /** Server thread. */
        void stockUp() {
            for (Iterator<S2C.VoxyChunk> q = queue.values().iterator(); q.hasNext() && stockBytes.get() < STOCK_BYTES; ) {
                S2C.VoxyChunk packet = q.next();
                q.remove();
                bytes -= packet.sections().length;
                stock.add(packet);
                stockBytes.addAndGet(packet.sections().length);
            }
        }

        long stockBytes() {
            return stockBytes.get();
        }

        /** Server thread: a dimension change drops what the client no longer needs, including the stock. */
        void clear() {
            queue.clear();
            last.clear();
            bytes = 0;
            for (S2C.VoxyChunk packet; (packet = stock.poll()) != null; ) stockBytes.addAndGet(-packet.sections().length);
        }

        /**
         * Event loop: write stocked chunks while the window has room and Netty has headroom for game packets, one
         * outstanding write at a time (its completion continues here), then mark the batch for acknowledgement.
         */
        void pump() {
            if (pumping) return; // a write completed synchronously inside this loop
            pumping = true;
            long written = 0;
            try {
                while (!writing && transportWritable(channel) && flow.hasRoom()) {
                    if (written >= PUMP_BYTES) {
                        schedulePump(); // yield: the event loop serves other connections and this player's game packets
                        break;
                    }
                    S2C.VoxyChunk packet = stock.poll();
                    if (packet == null) break;
                    int size = packet.sections().length;
                    stockBytes.addAndGet(-size);
                    writing = true;
                    try {
                        transport.accept(packet, PacketSendListener.thenRun(this::written));
                    } catch (RuntimeException | Error error) {
                        writing = false;
                        throw error;
                    }
                    flow.chunkSent(size);
                    written += size;
                    sent.incrementAndGet();
                    if (LodDiag.ON) {
                        diagChunks++;
                        diagBytes += size;
                    }
                    if (flow.markDue(!stock.isEmpty())) mark();
                }
                // A small mark needs no Netty headroom: without it the client could never acknowledge the last chunks.
                if (flow.markDue(!stock.isEmpty() && (writing || transportWritable(channel)))) mark();
            } finally {
                pumping = false;
            }
            if (LodDiag.ON) {
                diagInFlight = flow.inFlight();
                diagWindow = flow.window();
            }
        }

        /**
         * Write completion. Inside {@link #pump} its loop continues; otherwise pump as a new task: Netty completes the
         * write before it subtracts the bytes from the channel's pending count, so a pump from here would see a full
         * channel and stop until the next tick.
         */
        private void written() {
            writing = false;
            if (!pumping) schedulePump();
        }

        private void mark() {
            int id = flow.mark(clock.getAsLong());
            if (LodDiag.ON) {
                diagMarks.add(new long[]{id, LodDiag.now()});
                LodDiag.log("srv mark batch=%d chunks=%d inFlight=%d stock=%d", id, flow.markedChunks(), flow.inFlight(), stockBytes.get());
            }
            transport.accept(new S2C.LodBatchFinished(id, flow.markedChunks()), null);
        }

        /** Event loop: the client acknowledged marks up to {@code id}; send what the freed window allows. */
        void acknowledged(int id) {
            boolean accepted = flow.acknowledge(id, clock.getAsLong());
            if (LodDiag.ON) {
                long markedAt = 0;
                while (!diagMarks.isEmpty() && diagMarks.peek()[0] <= id) markedAt = diagMarks.poll()[1];
                LodDiag.log("srv ack batch=%d ok=%s markToAckMs=%d inFlight=%d window=%d", id, accepted, markedAt == 0 ? -1 : LodDiag.now() - markedAt,
                        flow.inFlight(), flow.window());
            }
            if (accepted) pump();
        }

        private static boolean transportWritable(@Nullable Channel channel) {
            if (channel == null || !channel.isActive() || !channel.isWritable()) return false;
            long room = channel.bytesBeforeUnwritable();
            long pending = channel.config().getWriteBufferHighWaterMark() - room;
            return room >= WRITE_HEADROOM && pending <= MAX_PENDING_BYTES;
        }

        void diagSecond(String name, int latencyMs) {
            int chunks = diagChunks;
            long sentBytes = diagBytes;
            diagChunks = 0;
            diagBytes = 0;
            if (chunks == 0 && queue.isEmpty() && stock.isEmpty()) return;
            LodDiag.log("srv sec player=%s latencyMs=%d queue=%d/%dKiB stock=%dKiB inFlight=%dKiB window=%dKiB sentChunks=%d sentKiB=%d dropped=%d",
                    name, latencyMs, queue.size(), bytes >> 10, stockBytes.get() >> 10, diagInFlight >> 10, diagWindow >> 10, chunks, sentBytes >> 10, dropped);
        }

        /** How many more LSS-overwritten chunks the player can report ({@link #stale}): {@link #STALE_PER_SECOND} per second. */
        int staleBudget = STALE_PER_SECOND;
        @Nullable
        ResourceKey<Level> dimension;
    }

    /** Built copy: recipient and earliest world tick for delivery ({@code notBefore}). */
    private record Built(ResourceKey<Level> dimension, long chunk, @Nullable byte[] data, List<UUID> to, long notBefore) {}

    /** The connection's viewer, for acknowledgements handled on its network thread. */
    private static final AttributeKey<Viewer> VIEWER = AttributeKey.valueOf("incoming:voxy_lods");
    private final Map<UUID, Viewer> viewers = new HashMap<>();
    /** Worlds with Voxy players (per tick): {@link #wanted} is called for every chunk mark. */
    private final Set<ResourceKey<Level>> levels = new HashSet<>();
    private final ConcurrentLinkedQueue<Built> built = new ConcurrentLinkedQueue<>();
    /** Built copies whose time has not yet come (heavy-zone copies wait for the wave front). */
    private final List<Built> held = new ArrayList<>();
    /** Copies being built (their queue space is already reserved for {@link #room}). */
    private int building;
    private long nextReport;

    /** For {@link ModAttachments#VOXY_LODS}: on the main world, non-persistent. */
    public VoxyLods() {}

    private static VoxyLods get(MinecraftServer server) {
        return server.overworld().getData(ModAttachments.VOXY_LODS);
    }

    /** This world has a player with Voxy. Server thread. */
    public static boolean wanted(ServerLevel level) {
        return get(level.getServer()).levels.contains(level.dimension());
    }

    /**
     * How many more chunks can be delivered now: space in the fullest queue of this world's Voxy players, estimated using
     * {@link #CHUNK_BYTES}; no players — {@link Integer#MAX_VALUE}.
     */
    public static int room(ServerLevel level) {
        VoxyLods v = get(level.getServer());
        long free = Long.MAX_VALUE;
        for (Map.Entry<UUID, Viewer> e : v.viewers.entrySet()) {
            if (e.getValue().dimension == level.dimension()) free = Math.min(free, QUEUE_BYTES - e.getValue().bytes);
        }
        if (free == Long.MAX_VALUE) return Integer.MAX_VALUE;
        return (int) Math.max(0, Math.min(Integer.MAX_VALUE, free / CHUNK_BYTES - v.building - v.held.size()));
    }

    /**
     * Chunk of world {@code level}, for Voxy LODs of players who do not have it: a loaded chunk ({@link LevelChunk}) as it is
     * now, a copy outside the world as built (do not modify it afterward). Server thread.
     */
    public static void send(ServerLevel level, ChunkAccess chunk) {
        send(level, chunk, Long.MIN_VALUE);
    }

    /**
     * The same, but delivered to the player no earlier than world tick {@code notBefore}: a heavy-zone copy
     * ({@link DhUpdates#sendHeld}) goes to DH its delay before front arrival, whereas Voxy ingests sections immediately —
     * LODs must not change before the front.
     */
    public static void send(ServerLevel level, ChunkAccess chunk, long notBefore) {
        send(level, chunk, notBefore, null);
    }

    /**
     * Chunk for one player with Voxy (LOD Server Support overwrote the mod's copy with the disk chunk,
     * {@code FarLods.refresh}): DH and other players already have correct LODs. Server thread.
     */
    public static void sendTo(ServerLevel level, ChunkAccess chunk, UUID player) {
        send(level, chunk, Long.MIN_VALUE, player);
    }

    private static void send(ServerLevel level, ChunkAccess chunk, long notBefore, @Nullable UUID only) {
        VoxyLods v = get(level.getServer());
        if (!v.levels.contains(level.dimension())) return;
        ChunkPos pos = chunk.getPos();
        boolean loaded = chunk instanceof LevelChunk;
        List<UUID> to = new ArrayList<>();
        Set<ServerPlayer> seeing = loaded ? new HashSet<>(level.getChunkSource().chunkMap.getPlayers(pos, false)) : Set.of();
        for (ServerPlayer p : level.players()) {
            Viewer viewer = v.viewers.get(p.getUUID());
            if (viewer != null && seeing.contains(p)) viewer.forget(pos.toLong()); // its Voxy rebuilds it from the world
            if (viewer == null || only != null && !only.equals(p.getUUID()) || seeing.contains(p) || !near(p, pos)) continue;
            to.add(p.getUUID());
        }
        if (to.isEmpty()) return;
        ResourceKey<Level> dim = level.dimension();
        if (loaded) {
            v.enqueue(level.getServer(), new Built(dim, pos.toLong(), LodSections.ofLoaded(level, chunk), to, notBefore));
            return;
        }
        boolean sky = level.dimensionType().hasSkyLight();
        v.building++;
        CompletableFuture.supplyAsync(() -> LodSections.ofCopy(chunk, sky), Util.backgroundExecutor()).whenComplete((data, error) -> {
            if (error != null) Incoming.LOG.warn("Voxy LOD of changed chunk {} not built", pos, error);
            v.built.add(new Built(dim, pos.toLong(), error == null ? data : null, to, notBefore));
        });
    }

    /** 64-bit fingerprint of a chunk's bytes: CRC32C in the high half, {@code Arrays.hashCode} in the low, the length mixed in. */
    static long print(byte[] b) {
        java.util.zip.CRC32C crc = new java.util.zip.CRC32C();
        crc.update(b);
        return crc.getValue() << 32 ^ (java.util.Arrays.hashCode(b) & 0xffffffffL) ^ (long) b.length << 40;
    }

    private static boolean near(ServerPlayer p, ChunkPos pos) {
        ChunkPos at = p.chunkPosition();
        return Math.max(Math.abs(at.x - pos.x), Math.abs(at.z - pos.z)) <= FarLods.FAR_CHUNKS;
    }

    private void enqueue(MinecraftServer server, Built b) {
        ServerLevel level = server.getLevel(b.dimension);
        if (level != null && level.getGameTime() < b.notBefore) {
            held.add(b);
            return;
        }
        S2C.VoxyChunk packet = new S2C.VoxyChunk(b.dimension.location(), ChunkPos.getX(b.chunk), ChunkPos.getZ(b.chunk), b.data);
        long print = print(b.data);
        for (UUID id : b.to) {
            Viewer viewer = viewers.get(id);
            if (viewer == null || viewer.dimension != b.dimension) continue;
            viewer.offer(b.chunk, packet, print);
        }
    }

    public static void onServerTick(ServerTickEvent.Post e) {
        MinecraftServer server = e.getServer();
        if (!server.overworld().hasData(ModAttachments.VOXY_LODS)) return;
        get(server).tick(server);
    }

    private void tick(MinecraftServer server) {
        if (!held.isEmpty()) {
            List<Built> due = new ArrayList<>(held);
            held.clear();
            for (Built b : due) enqueue(server, b);
        }
        for (Built b; (b = built.poll()) != null; ) {
            building--;
            if (b.data != null) enqueue(server, b);
        }
        levels.clear();
        for (Iterator<Map.Entry<UUID, Viewer>> it = viewers.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<UUID, Viewer> entry = it.next();
            ServerPlayer p = server.getPlayerList().getPlayer(entry.getKey());
            if (p == null) {
                it.remove();
                continue;
            }
            Viewer viewer = entry.getValue();
            ResourceKey<Level> dim = p.level().dimension();
            if (dim != viewer.dimension) {
                // different world: the client no longer needs the previous world's chunks
                viewer.dimension = dim;
                viewer.clear();
            }
            levels.add(dim);
            if (server.getTickCount() % 20 == 0) {
                viewer.staleBudget = STALE_PER_SECOND;
                if (LodDiag.ON) viewer.diagSecond(p.getGameProfile().getName(), p.connection.latency());
            }
            viewer.drain();
        }
        long now = server.getTickCount();
        if (now >= nextReport) {
            nextReport = now + 1200;
            for (Map.Entry<UUID, Viewer> entry : viewers.entrySet()) {
                Viewer v = entry.getValue();
                long sent = v.sent.getAndSet(0);
                if (sent + v.dropped + v.repeats == 0) continue;
                ServerPlayer p = server.getPlayerList().getPlayer(entry.getKey());
                Incoming.LOG.info("Voxy LODs of changed chunks to {}: sent {}, queued {} ({} KiB), dropped {} (queue full), not repeated {}",
                        p == null ? entry.getKey() : p.getGameProfile().getName(), sent, v.queue.size(), v.bytes >> 10, v.dropped, v.repeats);
                v.dropped = 0;
                v.repeats = 0;
            }
        }
    }

    /** Network thread: the client has Voxy room for every batch up to the acknowledged mark. */
    public static void batchReceived(C2S.LodBatchReceived packet, IPayloadContext context) {
        Channel channel = context.connection().channel();
        Viewer viewer = channel.attr(VIEWER).get();
        if (viewer == null) return;
        if (channel.eventLoop().inEventLoop()) viewer.acknowledged(packet.id());
        else channel.eventLoop().execute(() -> viewer.acknowledged(packet.id()));
    }

    /** The client reported whether it renders Voxy, into which the mod ingests chunks. Server thread. */
    public static void lodMods(C2S.LodMods packet, IPayloadContext context) {
        if (!(context.player() instanceof ServerPlayer p)) return;
        VoxyLods v = get(p.getServer());
        Channel channel = p.connection.getConnection().channel();
        if (!packet.voxy()) {
            Viewer gone = v.viewers.remove(p.getUUID());
            if (gone != null) gone.clear();
            channel.attr(VIEWER).set(null);
            return;
        }
        Viewer viewer = channel.attr(VIEWER).get();
        if (viewer == null) {
            viewer = Viewer.of(p);
            channel.attr(VIEWER).set(viewer);
        }
        v.viewers.put(p.getUUID(), viewer);
        viewer.dimension = p.level().dimension();
        v.levels.add(viewer.dimension);
    }

    /**
     * LOD Server Support overwrote chunks sent by the mod on the client: resend them if they still differ from disk
     * ({@link FarLods#refresh}); LSS sends loaded chunks live — a mark will fix those. Server thread.
     */
    public static void stale(C2S.VoxyStale packet, IPayloadContext context) {
        if (!(context.player() instanceof ServerPlayer p)) return;
        Viewer viewer = get(p.getServer()).viewers.get(p.getUUID());
        if (viewer == null) return;
        ServerLevel level = p.serverLevel();
        // forgetting is free, the budget is for refreshes
        for (long chunk : packet.chunks()) viewer.forget(chunk);
        int n = 0;
        for (long chunk : packet.chunks()) {
            if (viewer.staleBudget <= 0) break;
            if (!near(p, new ChunkPos(chunk))) continue;
            viewer.staleBudget--;
            FarLods.refresh(level, chunk, p.getUUID());
            n++;
        }
        if (n > 0) Incoming.LOG.debug("Voxy LODs: {} chunks overwritten by LOD Server Support at {}, sent again if still changed", n, p.getGameProfile().getName());
    }

    /** Checks: {@code viewer} stands for player {@code id} (null removes it). */
    public static void testViewer(MinecraftServer server, UUID id, @Nullable Viewer viewer) {
        if (viewer == null) get(server).viewers.remove(id);
        else get(server).viewers.put(id, viewer);
    }

    /**
     * The server sent a world chunk to a player: their Voxy rebuilds it from that, so a later copy with the bytes last
     * queued to them is no repeat ({@link Viewer#offer}). Server thread.
     */
    public static void onChunkSent(net.neoforged.neoforge.event.level.ChunkWatchEvent.Sent e) {
        ServerPlayer p = e.getPlayer();
        if (p.getServer() == null || !p.getServer().overworld().hasData(ModAttachments.VOXY_LODS)) return;
        Viewer viewer = get(p.getServer()).viewers.get(p.getUUID());
        if (viewer != null) viewer.forget(e.getPos().toLong());
    }

    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent e) {
        if (e.getEntity() instanceof ServerPlayer p && p.getServer() != null && p.getServer().overworld().hasData(ModAttachments.VOXY_LODS)) {
            get(p.getServer()).viewers.remove(p.getUUID());
        }
    }
}
