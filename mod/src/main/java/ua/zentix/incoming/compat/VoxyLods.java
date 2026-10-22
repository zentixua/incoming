package ua.zentix.incoming.compat;

import net.minecraft.Util;
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
import net.neoforged.neoforge.network.PacketDistributor;
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

/**
 * Что мод изменил в мире — в LOD Voxy у игроков ({@code client/VoxyIngest}): рядом с Distant Horizons, тем же путём
 * ({@link DhUpdates}: отметки загруженных чанков, копии чанков не в мире, копии тяжёлой зоны к фронту). Voxy — мод
 * клиента без сервера: свои LOD он строит из чанков, которые клиент получил, а изменения в чанках, которых у клиента
 * нет (руины, след подрыва и тьма квартала вдали — копии {@code nuclear/world/FarLods}, ни в мире, ни на диске их нет;
 * воронка в загруженном сервером, но далёком от игрока чанке), не видит. Поэтому сервер шлёт такие чанки тем, у кого
 * Voxy ({@link C2S.LodMods}), пакетом {@link S2C.VoxyChunk} (секции со светом — {@link LodSections}).
 * <ul>
 * <li>Кому: игрокам того же мира с Voxy не дальше {@link FarLods#FAR_CHUNKS} чанков, у кого этого чанка нет (чанк,
 * который игрок видит, Voxy перестраивает сам по пакетам ванили).</li>
 * <li>Темп: каждому не больше {@link #BYTES_PER_TICK} в тик (~2,5 МиБ/с до сжатия, ~60 чанков); очередь игрока — не
 * больше {@link #QUEUE_BYTES}, свежая копия того же чанка заменяет ждущую. Место в очередях — {@link #room}: по нему
 * {@link DhUpdates} отдаёт чанки и без DH.</li>
 * <li>Копию собирают в фоне (свет по её блокам, {@link CopyLight}), загруженный чанк — снимком в потоке сервера.</li>
 * </ul>
 * С LOD Server Support на сервере: сохранённые миром чанки он рассылает сам (живой чанк или диск — уже с руинами), а
 * чанк не в мире — только при первой сверке клиента, с диска, то есть целым: такой чанк клиент называет ({@link
 * C2S.VoxyStale}), и сервер шлёт копию заново ({@link #stale}). Несохраняемый attachment главного мира: игроки
 * сервера, их очереди и копии в сборке.
 */
public final class VoxyLods {
    /**
     * Байтов чанков в тик одному игроку, не больше: 2,5 МиБ/с до сжатия сети, после сжатия — около 1 МиБ/с (у LSS по
     * умолчанию 25 МиБ/с до сжатия, у DH 0,5 МБ/с): игрок, открывший мир друзьям, отдаёт их со своего канала.
     */
    static final int BYTES_PER_TICK = 128 * 1024;
    /** Очередь игрока, байтов, не больше: дальше новые чанки ему не идут (и {@link #room} — 0). */
    static final long QUEUE_BYTES = 48L << 20;
    /** Чанк по оценке (для {@link #room}): 24 секции с палитрами и свет у поверхности — ~40 КБ до сжатия. */
    static final int CHUNK_BYTES = 40 * 1024;
    /**
     * Чанков, переписанных LSS, которые игрок может назвать за секунду, не больше: каждый — заголовок с диска или чтение
     * и копия; клиент сам называет не больше {@link C2S.VoxyStale#PER_TICK} за тик, лишнее отбрасывается.
     */
    static final int STALE_PER_SECOND = 256;

    /** Игрок с Voxy: его чанки по порядку (чанк → пакет; свежий заменяет ждущий на его месте). */
    private static final class Viewer {
        final LinkedHashMap<Long, S2C.VoxyChunk> queue = new LinkedHashMap<>();
        long bytes, sent, dropped;
        /** Сколько ещё чанков, переписанных LSS, игрок может назвать ({@link #stale}): {@link #STALE_PER_SECOND} в секунду. */
        int staleBudget = STALE_PER_SECOND;
        @Nullable
        ResourceKey<Level> dimension;
    }

    /** Собранная копия: кому её отдать и не раньше какого тика мира ({@code notBefore}). */
    private record Built(ResourceKey<Level> dimension, long chunk, @Nullable byte[] data, List<UUID> to, long notBefore) {}

    private final Map<UUID, Viewer> viewers = new HashMap<>();
    /** Миры, где есть игроки с Voxy (на тик): {@link #wanted} зовут на каждую отметку чанка. */
    private final Set<ResourceKey<Level>> levels = new HashSet<>();
    private final ConcurrentLinkedQueue<Built> built = new ConcurrentLinkedQueue<>();
    /** Собранные копии, чей срок ещё не пришёл (копии тяжёлой зоны — к фронту волны). */
    private final List<Built> held = new ArrayList<>();
    /** Копий в сборке (их место в очередях уже занято для {@link #room}). */
    private int building;
    private long nextReport;

    /** Для {@link ModAttachments#VOXY_LODS}: у главного мира, не сохраняется. */
    public VoxyLods() {}

    private static VoxyLods get(MinecraftServer server) {
        return server.overworld().getData(ModAttachments.VOXY_LODS);
    }

    /** В этом мире есть игрок с Voxy. Поток сервера. */
    public static boolean wanted(ServerLevel level) {
        return get(level.getServer()).levels.contains(level.dimension());
    }

    /**
     * Сколько чанков ещё можно отдать сейчас: место в самой полной очереди игроков этого мира с Voxy, по оценке
     * {@link #CHUNK_BYTES}; игроков нет — {@link Integer#MAX_VALUE}.
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
     * Чанк мира {@code level} — в LOD Voxy игроков, у кого его нет: загруженный ({@link LevelChunk}) — как он есть
     * сейчас, копия не из мира — как собрана (после этого её не менять). Поток сервера.
     */
    public static void send(ServerLevel level, ChunkAccess chunk) {
        send(level, chunk, Long.MIN_VALUE);
    }

    /**
     * То же, но игроку не раньше тика мира {@code notBefore}: копия тяжёлой зоны ({@link DhUpdates#sendHeld}) уходит в
     * DH за его задержку до прихода фронта, а Voxy кладёт секции сразу — LOD не должен меняться раньше фронта.
     */
    public static void send(ServerLevel level, ChunkAccess chunk, long notBefore) {
        send(level, chunk, notBefore, null);
    }

    /**
     * Чанк — одному игроку с Voxy (у него LOD Server Support переписал копию мода чанком с диска, {@code
     * FarLods.refresh}): у DH и у остальных LOD уже верный. Поток сервера.
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
            S2C.VoxyChunk was = viewer.queue.remove(b.chunk);
            if (was != null) viewer.bytes -= was.sections().length;
            if (viewer.bytes + b.data.length > QUEUE_BYTES) {
                viewer.dropped++;
                continue;
            }
            viewer.queue.put(b.chunk, packet);
            viewer.bytes += b.data.length;
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
                // другой мир: чанки прежнего клиенту больше не нужны
                viewer.dimension = dim;
                viewer.queue.clear();
                viewer.bytes = 0;
            }
            levels.add(dim);
            if (server.getTickCount() % 20 == 0) viewer.staleBudget = STALE_PER_SECOND;
            int budget = BYTES_PER_TICK;
            for (Iterator<S2C.VoxyChunk> q = viewer.queue.values().iterator(); q.hasNext() && budget > 0; ) {
                S2C.VoxyChunk packet = q.next();
                q.remove();
                viewer.bytes -= packet.sections().length;
                budget -= packet.sections().length;
                viewer.sent++;
                PacketDistributor.sendToPlayer(p, packet);
            }
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

    /** Клиент сказал, рисует ли он Voxy, в который мод кладёт чанки. Поток сервера. */
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
     * LOD Server Support переписал у клиента чанки, которые прислал мод: они уходят ему снова, если ещё отличаются
     * от диска ({@link FarLods#refresh}); загруженный чанк LSS шлёт живым — его поправит отметка. Поток сервера.
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
