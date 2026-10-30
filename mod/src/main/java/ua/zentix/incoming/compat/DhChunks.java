package ua.zentix.incoming.compat;

import com.seibel.distanthorizons.api.DhApi;
import com.seibel.distanthorizons.api.interfaces.config.IDhApiConfig;
import com.seibel.distanthorizons.api.interfaces.data.IDhApiTerrainDataRepo;
import com.seibel.distanthorizons.api.interfaces.world.IDhApiLevelWrapper;
import com.seibel.distanthorizons.api.interfaces.world.IDhApiWorldProxy;
import com.seibel.distanthorizons.api.methods.events.DhApiEventRegister;
import com.seibel.distanthorizons.api.methods.events.abstractEvents.DhApiChunkModifiedEvent;
import com.seibel.distanthorizons.api.methods.events.sharedParameterObjects.DhApiEventParam;
import com.seibel.distanthorizons.api.objects.DhApiResult;
import com.seibel.distanthorizons.coreapi.DependencyInjection.WorldGeneratorInjector;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.event.server.ServerStartingEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import org.jetbrains.annotations.Nullable;
import ua.zentix.incoming.Incoming;

/**
  * API call of Distant Horizons: the chunk goes into the LOD at once, not at the next chunk save (DH itself sees block
  * changes only on a chunk save: on a free server after ~10 s, on a busy one by autosave every 5 minutes). {@code
  * DhApi.Delayed.terrainRepo.overwriteChunkDataAsync} puts the chunk into DH's update queue (the same one as its own
  * updates; parsing runs in DH's threads no earlier than 250 ms later, by a live reference to the chunk, without a
  * copy), DH writes the LOD to its database and sends it to players with DH (single player, a host with DH, a server
  * with DH: “real-time updates”). Accepts any {@link ChunkAccess} with a world: both a loaded chunk and a chunk built
  * by the mod that is not from the world ({@link DhUpdates#offer}). Pace and repeats are {@link DhUpdates}: a position
  * already in DH's queue is silently skipped. Without DH, or with another major version of the API, it does nothing;
  * only the nested {@link Api} touches the API classes, and only if DH is installed.
 */
public final class DhChunks {
    /** Major version of the API the mod is built against (7.2.0, DH 3.3.x). */
    private static final int API_MAJOR = 7;
    /** 0 — not yet checked, 1 — DH with our API, −1 — no (or its classes are not the ones the mod knows). */
    private static volatile int state;
    /**
     * API error or refusal already in the log (since server start): silent from then on. DH classes are not needed
     * here.
     */
    private static boolean logged, refused;
    /**
      * Subscription to LOD records ({@link #listen}) exists: the DH API events live as long as the game does, one per
      * game launch.
     */
    private static volatile boolean listening;

    private DhChunks() {
    }

    /** Server start (in single player, each world): check DH again, errors of the previous world do not count. */
    public static void onServerStarting(ServerStartingEvent e) {
        DhUpdates.resetWaiting();
        state = 0;
        logged = false;
        refused = false;
    }

    /**
      * Server stopped (in single player, leaving the world): forget DH's generators per world. DH 3.3.3 creates a
      * generator for each server world in the static map {@code WorldGeneratorInjector} (the key is the wrapper of the
      * world with the {@code ServerLevel} itself) and never removes it, so without clearing, all worlds opened during
      * the game launch stay in memory entirely with their chunks (scenario leak: 1.3 GB in the menu before a world, 2.4
      * GB after two; the holder is this map, without e4all too). After the worlds stop, there are no live ones in it:
      * DH creates the generator anew when a world loads, and another generator is registered to the wrapper of the
      * loaded world — the next world's wrappers are new. The clearing method is public and in DH's API jar. The
      * previous world is still held by DH in {@code ThreadWorldGenParams} (a static field and the fields of the
      * generation threads) until generation in the next world — the mod does not touch it there.
     */
    public static void onServerStopped(ServerStoppedEvent e) {
        DhUpdates.resetWaiting();
        if (!available()) return;
        try {
            Api.forgetGenerators();
        } catch (LinkageError ex) {
            state = -1;
            Incoming.LOG.warn("Distant Horizons: could not drop generators of previous worlds — they stay in memory until the game exits", ex);
        }
    }

    /** Distant Horizons is installed, and its API is the one the mod was built for. */
    public static boolean available() {
        if (state == 0) state = ModList.get().isLoaded("distanthorizons") && supported() ? 1 : -1;
        return state > 0;
    }

    /**
     * Chunk of the world {@code level} (loaded or built by the mod): DH will rebuild the LOD from it. Server thread.
     */
    static void overwrite(ServerLevel level, ChunkAccess chunk) {
        if (!available()) return;
        try {
            Api.overwrite(level, chunk);
        } catch (LinkageError e) {
            // the API is not the one the mod was built for: we do not touch it until the end of the game
            state = -1;
            Incoming.LOG.warn("Distant Horizons: API differs from the one the mod was built for — LODs of changed chunks will update when they are saved", e);
        } catch (RuntimeException e) {
            // one-off refusal (the DH world is not ready yet, etc.): this chunk on save, the next ones again via the
            // API
            if (!logged) {
                logged = true;
                Incoming.LOG.warn("Distant Horizons: LOD update of changed chunk {} failed — it will update when saved", chunk.getPos(), e);
            }
        }
    }

    /**
     * DH update parsing threads ({@code numberOfThreads} in its settings): DH computes its update queue limit by them
     * ({@link DhQueue}). Without DH — 1.
     */
    static int threads() {
        if (!available()) return 1;
        try {
            return Math.max(1, Api.threads());
        } catch (LinkageError | RuntimeException e) {
            return 1;
        }
    }

    /**
      * Subscription to chunk LOD records into DH's database ({@code DhApiChunkModifiedEvent} — after a section is
      * written, per chunk) — confirmations of the held copies ({@link DhUpdates#saved}). Once per game launch. Server
      * thread.
     */
    static void listen() {
        if (listening || !available()) return;
        listening = true;
        try {
            String refused = Api.listen();
            if (refused != null) Incoming.LOG.warn("Distant Horizons: no notice of saved LODs ({}) — LODs sent ahead of the blast wave are paced by a timeout instead", refused);
        } catch (LinkageError | RuntimeException e) {
            Incoming.LOG.warn("Distant Horizons: no notice of saved LODs — LODs sent ahead of the blast wave are paced by a timeout instead", e);
        }
    }

    private static boolean supported() {
        try {
            return Api.major() == API_MAJOR;
        } catch (LinkageError e) {
            return false;
        }
    }

    private static final class Api {
        static int major() {
            return DhApi.getApiMajorVersion();
        }

        static int threads() {
            IDhApiConfig configs = DhApi.Delayed.configs;
            return configs == null ? 1 : configs.multiThreading().threadCount().getValue();
        }

        /** Subscription; null — it took, otherwise the reason DH refused it. */
        @Nullable
        static String listen() {
            DhApiResult<Void> r = DhApiEventRegister.on(DhApiChunkModifiedEvent.class, new DhApiChunkModifiedEvent() {
                @Override
                public void onChunkModified(DhApiEventParam<EventParam> input) {
                    DhUpdates.saved(input.value.levelWrapper.getWrappedMcObject(), input.value.chunkX, input.value.chunkZ);
                }
            });
            return r == null || r.success ? null : String.valueOf(r.message);
        }

        static void forgetGenerators() {
            WorldGeneratorInjector.INSTANCE.clear();
        }

        static void overwrite(ServerLevel level, ChunkAccess chunk) {
            IDhApiWorldProxy world = DhApi.Delayed.worldProxy;
            IDhApiTerrainDataRepo repo = DhApi.Delayed.terrainRepo;
            if (world == null || repo == null || !world.worldLoaded()) return;
            for (IDhApiLevelWrapper w : world.getAllLoadedLevelWrappers()) {
                if (w.getWrappedMcObject() == level) {
                    DhApiResult<Void> r = repo.overwriteChunkDataAsync(w, new Object[] {chunk, level});
                    // API refusal (the DH world is not the right one, DH only reads) — once to the log: in LODs the
                    // ruins will come on chunk save
                    if (!r.success && !refused) {
                        refused = true;
                        Incoming.LOG.warn("Distant Horizons rejected changed chunk {}: {}", chunk.getPos(), r.message);
                    }
                    return;
                }
            }
        }
    }
}
