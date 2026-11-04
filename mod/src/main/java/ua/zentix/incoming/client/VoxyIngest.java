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
 * decoding and ingestion run on Incoming's serial horizon worker.
 * <p>
 * Remember which chunks the client received from the mod in this world (the bounded hint ledger): if LOD Server Support later
 * writes the same chunk from the server's disk to Voxy (its first check; no distant ruins on disk), the client names
 * it to the server ({@link C2S.VoxyStale}, {@link #tick}), which sends the copy again.
 */
public final class VoxyIngest {
    @Nullable
    private static MethodHandle worldOf, rawIngest, instanceOf, ingestService, taskCount;
    // Recovery is best effort and coordinate-only. Replaying retained sections could erase subsequent edits,
    // support/fall updates or another strike. The server must build the replacement from its current authority.
    private static final LodRefreshHints HINTS = new LodRefreshHints(65536, 4096);
    private static volatile boolean broken;

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

    /** Public Voxy service counter: never acknowledge another batch while its background queue is backed up. */
    static boolean backlogged() {
        if (!available()) return false;
        try {
            Object instance = instanceOf.invoke();
            if (instance == null) return false;
            Object service = ingestService.invoke(instance);
            return service != null && (int) taskCount.invoke(service) > 0;
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
        ClientHorizon.reset();
        if (available()) PacketDistributor.sendToServer(new C2S.LodMods(true));
    }

    /** Network receipt holds the encoded sections until their authoritative front time. */
    static void accept(S2C.VoxyChunk p, net.neoforged.neoforge.network.handling.IPayloadContext context) {
        var minecraft = Minecraft.getInstance();
        var connection = minecraft.getConnection();
        // The dimension name alone is not a session identity: a late packet from a closed connection must
        // never enter the new world's queue, even when both servers call the dimension "overworld".
        if (connection == null || connection.getConnection() != context.connection()) return;
        ClientLevel level = minecraft.level;
        if (!available() || level == null || Replay.active() || !level.dimension().location().equals(p.dimension())) return;
        if (!ClientHorizon.offer(level, p)) requestFresh(level, p);
    }

    /** Worker only. The level is captured before submission; never retarget taken work to a new world. */
    static int ingest(ClientLevel level, S2C.VoxyChunk p) {
        MethodHandle of = worldOf, raw = rawIngest;
        if (of == null || raw == null || broken) return -1;
        try {
            Object world = of.invoke((Level) level);
            if (world == null) return -1;
            var sections = LodSections.read(p.sections(), level.registryAccess().registryOrThrow(Registries.BIOME));
            for (LodSections.Section s : sections) {
                boolean ok = (boolean) raw.invoke(world, s.blocks(), p.x(), s.y(), p.z(), s.blockLight(), s.skyLight());
                if (!ok) return -1;
            }
            return sections.size();
        } catch (LinkageError e) {
            broken = true;
            Incoming.LOG.warn("Voxy: rawIngest no longer matches ({}) — strike damage far away shows in Voxy only when you get there", e.toString());
        } catch (Exception e) {
            Incoming.LOG.warn("Voxy: changed chunk {} {} not put into its LODs", p.x(), p.z(), e);
        } catch (Throwable e) {
            throw HandleFailures.propagate(e);
        }
        return -1;
    }

    static void beginHints(ClientLevel level) { HINTS.begin(level); }

    static void clearHints() { HINTS.reset(); }

    static void requestFresh(ClientLevel level, S2C.VoxyChunk p) {
        HINTS.request(level, ChunkPos.asLong(p.x(), p.z()));
    }

    /** Publish only after ClientHorizon checks the session, under its short ownership lock. */
    static void remember(ClientLevel level, S2C.VoxyChunk p) {
        HINTS.remember(level, ChunkPos.asLong(p.x(), p.z()));
    }

    /** LSS is observational; request current server state instead of replaying an old local section image. */
    static void overwritten(ClientLevel level, int x, int z) {
        HINTS.overwritten(level, ChunkPos.asLong(x, z));
    }

    /** Client tick: report overwritten chunks at the server's bounded request rate. */
    static void tick() {
        var minecraft = Minecraft.getInstance();
        ClientLevel level = minecraft.level;
        if (level != null) ClientHorizon.tick(level, level.getGameTime());
        if (level == null || minecraft.getConnection() == null) return;
        long[] batch = HINTS.take(level, C2S.VoxyStale.PER_TICK);
        if (batch.length > 0) PacketDistributor.sendToServer(new C2S.VoxyStale(batch));
    }
}
