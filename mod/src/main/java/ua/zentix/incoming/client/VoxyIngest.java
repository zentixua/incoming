package ua.zentix.incoming.client;

import it.unimi.dsi.fastutil.longs.LongArrayList;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.network.PacketDistributor;
import org.jetbrains.annotations.Nullable;
import ua.zentix.incoming.Incoming;
import ua.zentix.incoming.client.replay.Replay;
import ua.zentix.incoming.compat.LodSections;
import ua.zentix.incoming.net.C2S;
import ua.zentix.incoming.net.S2C;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Чанки, которых у клиента нет, — в LOD Voxy ({@link S2C.VoxyChunk} от {@code compat/VoxyLods}: руины, воронки, след
 * подрыва, лампы квартала вдали). API у Voxy нет: секции кладутся его статическим методом
 * {@code VoxelIngestService.rawIngest(WorldIdentifier, LevelChunkSection, x, y, z, blockLight, skyLight)} — тем же, через
 * который чанки с сервера кладёт в Voxy LOD Server Support ({@code compat/VoxyCompat}, MIT) и сам Voxy свои; по
 * {@link MethodHandle}, без миксинов. Мир — {@code WorldIdentifier.of(Level)}. Не нашлись — одна строка в лог, серверу
 * говорится «Voxy нет», и пакеты не приходят. Voxy 0.2.16 (через Roxy) принимает секции из любого потока: разбор и
 * отдача — в потоке сети.
 * <p>
 * Какие чанки клиент получил от мода в этом мире, помнится ({@link #ours}): если потом LOD Server Support положит в
 * Voxy тот же чанк с диска сервера (его первая сверка; руин вдали на диске нет), клиент называет его серверу
 * ({@link C2S.VoxyStale}, {@link #tick}), и тот шлёт копию заново.
 */
public final class VoxyIngest {
    @Nullable
    private static MethodHandle worldOf, rawIngest;
    /** Чанки ({@code ChunkPos.toLong}), которые мод положил в Voxy в этом мире. */
    private static final Set<Long> OURS = ConcurrentHashMap.newKeySet();
    /** Из них переписанные LSS — назвать серверу ({@link #tick}). */
    private static final Set<Long> STALE = ConcurrentHashMap.newKeySet();
    private static volatile boolean broken;
    @Nullable
    private static volatile ClientLevel ingested;

    private VoxyIngest() {}

    /** При запуске клиента: стоит ли Voxy и нашлись ли его методы. */
    static void init() {
        if (!ModList.get().isLoaded("voxy")) return;
        try {
            MethodHandles.Lookup lookup = MethodHandles.lookup();
            ClassLoader loader = VoxyIngest.class.getClassLoader();
            Class<?> id = Class.forName("me.cortex.voxy.commonImpl.WorldIdentifier", false, loader);
            Class<?> ingest = Class.forName("me.cortex.voxy.common.world.service.VoxelIngestService", false, loader);
            worldOf = lookup.findStatic(id, "of", MethodType.methodType(id, Level.class)).asType(MethodType.methodType(Object.class, Level.class));
            rawIngest = lookup.findStatic(ingest, "rawIngest", MethodType.methodType(boolean.class, id, LevelChunkSection.class,
                    int.class, int.class, int.class, DataLayer.class, DataLayer.class)).asType(MethodType.methodType(boolean.class, Object.class,
                    LevelChunkSection.class, int.class, int.class, int.class, DataLayer.class, DataLayer.class));
            Incoming.LOG.info("Voxy: changed chunks the client has not loaded go into its LODs (VoxelIngestService.rawIngest)");
        } catch (ReflectiveOperationException | LinkageError | RuntimeException e) {
            worldOf = null;
            rawIngest = null;
            Incoming.LOG.warn("Voxy: its classes differ from Voxy 0.2.16 the mod calls ({}) — strike damage far away shows in Voxy only when you get there", e.toString());
        }
    }

    /** Мод кладёт чанки в Voxy. */
    public static boolean available() {
        return rawIngest != null && !broken;
    }

    /** Вход в мир: сказать серверу, слать ли чанки. */
    static void login() {
        OURS.clear();
        STALE.clear();
        if (available()) PacketDistributor.sendToServer(new C2S.LodMods(true));
    }

    /** Чанк от сервера — в Voxy; поток сети. */
    static void accept(S2C.VoxyChunk p) {
        MethodHandle of = worldOf, raw = rawIngest;
        ClientLevel level = Minecraft.getInstance().level;
        // повтор Flashback: Voxy в нём показывает свою живую базу — прошлым чанкам записи там не место
        if (of == null || raw == null || broken || level == null || Replay.active() || !level.dimension().location().equals(p.dimension())) return;
        if (ingested != level) {
            ingested = level;
            OURS.clear();
            STALE.clear();
        }
        try {
            Object world = of.invoke((Level) level);
            if (world == null) return;
            for (LodSections.Section s : LodSections.read(p.sections(), level.registryAccess().registryOrThrow(Registries.BIOME))) {
                boolean ok = (boolean) raw.invoke(world, s.blocks(), p.x(), s.y(), p.z(), s.blockLight(), s.skyLight());
                if (!ok) return;
            }
            long chunk = ChunkPos.asLong(p.x(), p.z());
            OURS.add(chunk);
            STALE.remove(chunk);
        } catch (LinkageError e) {
            broken = true;
            Incoming.LOG.warn("Voxy: rawIngest no longer matches ({}) — strike damage far away shows in Voxy only when you get there", e.toString());
        } catch (Throwable e) {
            if (e instanceof Error err) throw err;
            Incoming.LOG.warn("Voxy: changed chunk {} {} not put into its LODs", p.x(), p.z(), e);
        }
    }

    /**
     * LOD Server Support положил в Voxy чанк мира {@code level} (поток LSS): если его прислал мод — серверу, чтобы
     * прислал заново (LSS шлёт чанк не из мира с диска, без руин).
     */
    static void overwritten(ClientLevel level, int x, int z) {
        if (level != ingested) return;
        long chunk = ChunkPos.asLong(x, z);
        if (OURS.remove(chunk)) STALE.add(chunk);
    }

    /** Тик клиента: переписанные LSS чанки — серверу пачками. */
    static void tick() {
        if (STALE.isEmpty() || Minecraft.getInstance().getConnection() == null) return;
        LongArrayList batch = new LongArrayList();
        for (Long c : STALE) {
            if (batch.size() >= C2S.VoxyStale.MAX) break;
            batch.add((long) c);
        }
        for (int i = 0; i < batch.size(); i++) STALE.remove(batch.getLong(i));
        PacketDistributor.sendToServer(new C2S.VoxyStale(batch.toLongArray()));
    }
}
