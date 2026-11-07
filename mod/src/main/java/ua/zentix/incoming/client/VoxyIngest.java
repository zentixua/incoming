package ua.zentix.incoming.client;

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
import ua.zentix.incoming.client.lod.LodRefreshHints;
import ua.zentix.incoming.compat.LodSections;
import ua.zentix.incoming.compat.HandleFailures;
import ua.zentix.incoming.net.C2S;
import ua.zentix.incoming.net.S2C;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;

/**
 * Chunks absent on the client go into Voxy LODs ({@link S2C.VoxyChunk} from {@code compat/VoxyLods}: distant ruins,
 * craters, detonation marks, district lamps). Voxy has no API: sections use its static
 * {@code VoxelIngestService.rawIngest(WorldIdentifier, LevelChunkSection, x, y, z, blockLight, skyLight)}, the same
 * method LOD Server Support ({@code compat/VoxyCompat}, MIT) uses for server chunks and Voxy for its own; via
 * {@link MethodHandle}, without mixins. World is {@code WorldIdentifier.of(Level)}. If unavailable, log one line,
 * tell the server Voxy is absent, and receive no packets. Voxy 0.2.16 (via Roxy) accepts sections from any thread:
 * decoding and ingestion run on the network thread.
 * <p>
 * Remember which chunks the client received from the mod in this world (bounded recovery hints): if LOD Server Support later
 * writes the same chunk from the server's disk to Voxy (its first check; no distant ruins on disk), the client names
 * it to the server ({@link C2S.VoxyStale}, {@link #tick}), which sends the copy again.
 */
public final class VoxyIngest {
    @Nullable
    private static MethodHandle worldOf, rawIngest, instanceOf, ingestService, taskCount;
    // Coordinate-only, best-effort recovery: history and a burst of callbacks must both stay bounded.
    private static final LodRefreshHints HINTS = new LodRefreshHints(65536, 4096);
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
            Class<?> common = Class.forName("me.cortex.voxy.commonImpl.VoxyCommon", false, loader);
            Class<?> instance = Class.forName("me.cortex.voxy.commonImpl.VoxyInstance", false, loader);
            instanceOf = lookup.findStatic(common, "getInstance", MethodType.methodType(instance)).asType(MethodType.methodType(Object.class));
            ingestService = lookup.findVirtual(instance, "getIngestService", MethodType.methodType(ingest))
                    .asType(MethodType.methodType(Object.class, Object.class));
            taskCount = lookup.findVirtual(ingest, "getTaskCount", MethodType.methodType(int.class))
                    .asType(MethodType.methodType(int.class, Object.class));
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

    /**
     * Queued (not yet started) Voxy ingest sections at which LOD Server Support 0.20 halts its own column requests
     * ({@code IngestBackpressureGovernor}, 6144 sections). LSS calls {@code rawIngest} for every section of every column
     * it receives and paces itself to keep this queue in the thousands while it fills its radius, so the queue is never
     * empty then. Waiting for an empty queue held every acknowledgement for seconds: on Zearth 07.11.2026, with LSS
     * refilling a wiped Voxy database, ruin LODs went out at 73–122 chunks a minute and 48 % were dropped from the full
     * player queue. Above this mark Voxy is behind even by LSS's measure, and the batch waits.
     */
    static final int INGEST_HALT_SECTIONS = 6144;

    /** Voxy's queue (sections waiting, {@code getTaskCount}) is deeper than LSS lets its own requests go. */
    static boolean backlogged(int queued) {
        return queued > INGEST_HALT_SECTIONS;
    }

    /** Public Voxy service counter: never acknowledge another batch while its background queue is backed up. */
    static boolean backlogged() {
        if (!available()) return false;
        try {
            Object instance = instanceOf.invoke();
            if (instance == null) return false;
            Object service = ingestService.invoke(instance);
            return service != null && backlogged((int) taskCount.invoke(service));
        } catch (LinkageError | Exception error) {
            broken = true;
            Incoming.LOG.warn("Voxy: cannot measure ingest backlog; stopping Incoming LOD delivery ({})", error.toString());
            return false;
        } catch (Throwable error) {
            throw HandleFailures.propagate(error);
        }
    }

    /** World login: tell the server whether to send chunks. */
    static void login() {
        reset();
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
            HINTS.begin(level);
        }
        try {
            Object world = of.invoke((Level) level);
            if (world == null) return;
            for (LodSections.Section s : LodSections.read(p.sections(), level.registryAccess().registryOrThrow(Registries.BIOME))) {
                boolean ok = (boolean) raw.invoke(world, s.blocks(), p.x(), s.y(), p.z(), s.blockLight(), s.skyLight());
                if (!ok) return;
            }
            HINTS.remember(level, ChunkPos.asLong(p.x(), p.z()));
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
        HINTS.overwritten(level, ChunkPos.asLong(x, z));
    }

    /** Release the previous world immediately on logout/reset, even if the next session has no LOD packets. */
    static void reset() {
        ingested = null;
        HINTS.reset();
    }

    /** Client tick: report overwritten chunks at the server's bounded request rate. */
    static void tick() {
        var minecraft = Minecraft.getInstance();
        ClientLevel level = minecraft.level;
        if (level == null || minecraft.getConnection() == null) return;
        long[] batch = HINTS.take(level, C2S.VoxyStale.PER_TICK);
        if (batch.length > 0) PacketDistributor.sendToServer(new C2S.VoxyStale(batch));
    }
}
