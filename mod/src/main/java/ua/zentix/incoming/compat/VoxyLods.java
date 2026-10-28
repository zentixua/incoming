package ua.zentix.incoming.compat;

import io.netty.channel.Channel;
import net.minecraft.Util;
import net.minecraft.network.PacketSendListener;
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
import ua.zentix.incoming.net.S2C;
import ua.zentix.incoming.nuclear.world.FarLods;
import ua.zentix.incoming.registry.ModAttachments;

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
import java.util.function.BiConsumer;

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
 * <li>Rate: at most {@link #BYTES_PER_TICK} per tick per player, with one final chunk allowed to cross the budget.
 * Delivery pauses while the connection is busy; only one LOD write may be outstanding. The player queue
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
    /**
     * Maximum chunk bytes per tick per player: 2.5 MiB/s before network compression, around 1 MiB/s after compression
     * (LSS defaults to 25 MiB/s before compression, DH to 0.5 MB/s): a player hosting friends uses their own uplink.
     */
    static final int BYTES_PER_TICK = 128 * 1024;
    /** Headroom below Netty's high watermark for normal game traffic (transport policy, not a physical quantity). */
    static final int WRITE_HEADROOM = 16 * 1024;
    /** Even with a raised Netty watermark, do not add LODs behind more than this many pending bytes. */
    static final int MAX_PENDING_BYTES = 32 * 1024;
    /** Maximum waiting player queue size; this does not bound bytes already handed to the connection. */
    static final long QUEUE_BYTES = 48L << 20;
    /** Estimated chunk size (for {@link #room}): 24 sections with palettes and surface light — ~40 KB before compression. */
    static final int CHUNK_BYTES = 40 * 1024;
    /**
     * Maximum LSS-overwritten chunks a player can report per second: each costs a disk header read or a read and copy;
     * the client itself reports at most {@link C2S.VoxyStale#PER_TICK} per tick; excess reports are dropped.
     */
    static final int STALE_PER_SECOND = 256;

    /** Player with Voxy: their chunks in order (chunk → packet; a fresh copy replaces a waiting one in place). */
    static final class Viewer {
        final LinkedHashMap<Long, S2C.VoxyChunk> queue = new LinkedHashMap<>();
        long bytes, sent, dropped;
        /** Includes dispatch waiting in the Netty event loop; cleared only by the write completion callback. */
        private volatile boolean writing;

        void offer(long chunk, S2C.VoxyChunk packet) {
            S2C.VoxyChunk was = queue.remove(chunk);
            if (was != null) bytes -= was.sections().length;
            if (bytes + packet.sections().length > QUEUE_BYTES) {
                dropped++;
                return;
            }
            queue.put(chunk, packet);
            bytes += packet.sections().length;
        }

        void drain(ServerPlayer player) {
            drain(player.connection.getConnection().channel(),
                    (packet, listener) -> player.connection.send(new ClientboundCustomPayloadPacket(packet), listener));
        }

        /** Server-thread queue drain. Never wait for the network or scan a blocked queue. */
        void drain(Channel channel, BiConsumer<S2C.VoxyChunk, PacketSendListener> send) {
            int budget = BYTES_PER_TICK;
            for (Iterator<S2C.VoxyChunk> q = queue.values().iterator(); q.hasNext() && budget > 0; ) {
                if (writing || channel == null || !channel.isActive() || !channel.isWritable()) break;
                long room = channel.bytesBeforeUnwritable();
                long pending = channel.config().getWriteBufferHighWaterMark() - room;
                if (room < WRITE_HEADROOM || pending > MAX_PENDING_BYTES) break;
                // Connection.send schedules on the event loop: writability alone cannot see those pending tasks.
                // A single chunk may exceed the watermark. Let it through once, then await its completed write;
                // requiring the entire chunk to fit would permanently block large, globally paletted chunks.
                S2C.VoxyChunk packet = q.next();
                writing = true;
                try {
                    send.accept(packet, PacketSendListener.thenRun(() -> writing = false));
                } catch (RuntimeException | Error error) {
                    writing = false;
                    throw error;
                }
                q.remove();
                bytes -= packet.sections().length;
                budget -= packet.sections().length;
                sent++;
            }
        }

        /** How many more LSS-overwritten chunks the player can report ({@link #stale}): {@link #STALE_PER_SECOND} per second. */
        int staleBudget = STALE_PER_SECOND;
        @Nullable
        ResourceKey<Level> dimension;
    }

    /** Built copy: recipient and earliest world tick for delivery ({@code notBefore}). */
    private record Built(ResourceKey<Level> dimension, long chunk, @Nullable byte[] data, List<UUID> to, long notBefore) {}

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
        for (UUID id : b.to) {
            Viewer viewer = viewers.get(id);
            if (viewer == null || viewer.dimension != b.dimension) continue;
            viewer.offer(b.chunk, packet);
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
                viewer.queue.clear();
                viewer.bytes = 0;
            }
            levels.add(dim);
            if (server.getTickCount() % 20 == 0) viewer.staleBudget = STALE_PER_SECOND;
            viewer.drain(p);
        }
        long now = server.getTickCount();
        if (now >= nextReport) {
            nextReport = now + 1200;
            for (Map.Entry<UUID, Viewer> entry : viewers.entrySet()) {
                Viewer v = entry.getValue();
                if (v.sent + v.dropped == 0) continue;
                ServerPlayer p = server.getPlayerList().getPlayer(entry.getKey());
                Incoming.LOG.info("Voxy LODs of changed chunks to {}: sent {}, queued {} ({} KiB), dropped {} (queue full)",
                        p == null ? entry.getKey() : p.getGameProfile().getName(), v.sent, v.queue.size(), v.bytes >> 10, v.dropped);
                v.sent = 0;
                v.dropped = 0;
            }
        }
    }

    /** The client reported whether it renders Voxy, into which the mod ingests chunks. Server thread. */
    public static void lodMods(C2S.LodMods packet, IPayloadContext context) {
        if (!(context.player() instanceof ServerPlayer p)) return;
        VoxyLods v = get(p.getServer());
        if (!packet.voxy()) {
            v.viewers.remove(p.getUUID());
            return;
        }
        Viewer viewer = v.viewers.computeIfAbsent(p.getUUID(), id -> new Viewer());
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

    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent e) {
        if (e.getEntity() instanceof ServerPlayer p && p.getServer() != null && p.getServer().overworld().hasData(ModAttachments.VOXY_LODS)) {
            get(p.getServer()).viewers.remove(p.getUUID());
        }
    }
}
