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
import ua.zentix.incoming.compat.HandleFailures;
import ua.zentix.incoming.net.C2S;
import ua.zentix.incoming.net.S2C;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Chunks absent on the client go into Voxy LODs ({@link S2C.VoxyChunk} from {@code compat/VoxyLods}: distant ruins,
 * craters, detonation marks, district lamps). Voxy has no API: sections use its static
 * {@code VoxelIngestService.rawIngest(WorldIdentifier, LevelChunkSection, x, y, z, blockLight, skyLight)}, the same
 * method LOD Server Support ({@code compat/VoxyCompat}, MIT) uses for server chunks and Voxy for its own; via
 * {@link MethodHandle}, without mixins. World is {@code WorldIdentifier.of(Level)}. If unavailable, log one line,
 * tell the server Voxy is absent, and receive no packets. Voxy 0.2.16 (via Roxy) accepts sections from any thread:
 * decoding and ingestion run on the network thread.
 * <p>
 * Remember which chunks the client received from the mod in this world ({@link #ours}): if LOD Server Support later
 * writes the same chunk from the server's disk to Voxy (its first check; no distant ruins on disk), the client names
 * it to the server ({@link C2S.VoxyStale}, {@link #tick}), which sends the copy again.
 */
public final class VoxyIngest {
    @Nullable
    private static MethodHandle worldOf, rawIngest;
    /** Chunks ({@code ChunkPos.toLong}) the mod wrote to Voxy in this world. */
    private static final Set<Long> OURS = ConcurrentHashMap.newKeySet();
    /** Those overwritten by LSS: report to the server ({@link #tick}). */
    private static final Set<Long> STALE = ConcurrentHashMap.newKeySet();
    private static volatile boolean broken;
    @Nullable
    private static volatile ClientLevel ingested;

    private VoxyIngest() {}

    /** At client startup: whether Voxy is installed and its methods were found. */
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

    /** The mod writes chunks to Voxy. */
    public static boolean available() {
        return rawIngest != null && !broken;
    }

    /** World login: tell the server whether to send chunks. */
    static void login() {
        OURS.clear();
        STALE.clear();
        if (available()) PacketDistributor.sendToServer(new C2S.LodMods(true));
    }

    /** Server chunk into Voxy; network thread. */
    static void accept(S2C.VoxyChunk p) {
        MethodHandle of = worldOf, raw = rawIngest;
        ClientLevel level = Minecraft.getInstance().level;
        // Flashback replay: Voxy shows its live database there; past chunks from the recording do not belong in it.
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
        } catch (Exception e) {
            Incoming.LOG.warn("Voxy: changed chunk {} {} not put into its LODs", p.x(), p.z(), e);
        } catch (Throwable e) {
            throw HandleFailures.propagate(e);
        }
    }

    /**
     * LOD Server Support wrote a chunk of world {@code level} to Voxy (LSS thread): if sent by the mod, notify the server
     * to resend it (LSS sends an unloaded chunk from disk, without ruins).
     */
    static void overwritten(ClientLevel level, int x, int z) {
        if (level != ingested) return;
        long chunk = ChunkPos.asLong(x, z);
        if (OURS.remove(chunk)) STALE.add(chunk);
    }

    /** Client tick: report LSS-overwritten chunks to the server, at most {@link C2S.VoxyStale#PER_TICK} per tick (server limit). */
    static void tick() {
        if (STALE.isEmpty() || Minecraft.getInstance().getConnection() == null) return;
        LongArrayList batch = new LongArrayList();
        for (Long c : STALE) {
            if (batch.size() >= C2S.VoxyStale.PER_TICK) break;
            batch.add((long) c);
        }
        for (int i = 0; i < batch.size(); i++) STALE.remove(batch.getLong(i));
        PacketDistributor.sendToServer(new C2S.VoxyStale(batch.toLongArray()));
    }
}
