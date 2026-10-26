package ua.zentix.incoming.client.map;

import com.seibel.distanthorizons.api.DhApi;
import com.seibel.distanthorizons.api.interfaces.block.IDhApiBlockStateWrapper;
import com.seibel.distanthorizons.api.interfaces.config.IDhApiConfig;
import com.seibel.distanthorizons.api.interfaces.data.IDhApiTerrainDataCache;
import com.seibel.distanthorizons.api.interfaces.data.IDhApiTerrainDataRepo;
import com.seibel.distanthorizons.api.interfaces.world.IDhApiLevelWrapper;
import com.seibel.distanthorizons.api.methods.events.DhApiEventRegister;
import com.seibel.distanthorizons.api.methods.events.abstractEvents.DhApiAfterDhInitEvent;
import com.seibel.distanthorizons.api.methods.events.abstractEvents.DhApiChunkModifiedEvent;
import com.seibel.distanthorizons.api.methods.events.abstractEvents.DhApiLevelLoadEvent;
import com.seibel.distanthorizons.api.methods.events.abstractEvents.DhApiLevelUnloadEvent;
import com.seibel.distanthorizons.api.methods.events.sharedParameterObjects.DhApiEventParam;
import com.seibel.distanthorizons.api.objects.DhApiResult;
import com.seibel.distanthorizons.api.objects.data.DhApiTerrainDataPoint;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.MapColor;
import org.jetbrains.annotations.Nullable;
import ua.zentix.incoming.Incoming;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Terrain from Distant Horizons data through its public API ({@code DhApi.Delayed.terrainRepo}): everything DH has
 * computed for distant rendering, kilometres around, without loading chunks. Reads come from the DH database and
 * can take milliseconds, so never on the game thread; DH data cache ({@code createSoftCache}) is per tile, otherwise
 * every column would reread and unpack its 64×64 region.
 * <p>
 * API readiness and DH's loaded worlds come from API events ({@code DhApiAfterDhInitEvent},
 * {@code DhApiLevelLoadEvent}/{@code DhApiLevelUnloadEvent}), as required by {@code DhApi.Delayed} Javadoc;
 * changed chunks come from {@code DhApiChunkModifiedEvent} (Javadoc: once the change is in data read by
 * {@code IDhApiTerrainDataRepo}): ready tiles are reread on the event, rather than on a timer.
 * <p>
 * Class loads only with DH installed (see {@link TerrainTiles}): its API references cannot resolve without DH.
 */
final class DistantHorizonsTerrain implements TerrainSource {
    /**
     * API major version the mod was built against (7.2.0, DH 3.3.x). {@code getApiMajorVersion} Javadoc says it changes
     * only for incompatible changes, so another version is not ours: terrain then comes only from chunks.
     */
    private static final int API_MAJOR = 7;
    private static final int MAX_WATER_DEPTH = 16;
    /**
     * Complete tile reread every 5 minutes without a change event: DH sends events only while its chunk hash checks
     * are enabled ({@code disableUnchangedChunkCheck} off, as by default; {@code AbstractDhLevel}).
     */
    private static final long REFRESH_NS = 300_000_000_000L;

    /** For logs: columns with surface, no data, API failures and first failure; why the world did not open. */
    private final AtomicLong found = new AtomicLong(), empty = new AtomicLong(), failed = new AtomicLong();
    /** API exception already logged with stack trace (subsequent failures counted only). */
    private final AtomicBoolean threw = new AtomicBoolean();
    private volatile String firstFailure = "", notOpened = "";

    /** {@code DhApi.Delayed} populated (after first DH initialization). */
    private static volatile boolean initialized;
    /** Worlds DH currently keeps loaded. */
    private static final Set<IDhApiLevelWrapper> loaded = ConcurrentHashMap.newKeySet();
    /**
     * Chunks ({@link ChunkPos#asLong}) updated by DH, per world: event comes from DH threads, game thread processes
     * it each tick ({@link #changes}); DH saves a chunk repeatedly, but the set holds it once.
     */
    private static final Map<IDhApiLevelWrapper, Set<Long>> changed = new HashMap<>();

    /** DH API version matches the build; otherwise no source (and a log entry). */
    static boolean supported() {
        int major = DhApi.getApiMajorVersion();
        if (major == API_MAJOR) return true;
        Incoming.LOG.warn("Distant Horizons {}: API {}.{}, the mod is built for {}.x — far terrain on the map is off",
                DhApi.getModVersion(), major, DhApi.getApiMinorVersion(), API_MAJOR);
        return false;
    }

    /** Once at client startup: subscribe to DH API events. */
    static void subscribe() {
        DhApiEventRegister.on(DhApiAfterDhInitEvent.class, new DhApiAfterDhInitEvent() {
            @Override
            public void afterDistantHorizonsInit(DhApiEventParam<Void> input) {
                initialized = true;
            }
        });
        DhApiEventRegister.on(DhApiLevelLoadEvent.class, new DhApiLevelLoadEvent() {
            @Override
            public void onLevelLoad(DhApiEventParam<EventParam> input) {
                loaded.add(input.value.levelWrapper);
            }
        });
        DhApiEventRegister.on(DhApiLevelUnloadEvent.class, new DhApiLevelUnloadEvent() {
            @Override
            public void onLevelUnload(DhApiEventParam<EventParam> input) {
                loaded.remove(input.value.levelWrapper);
                synchronized (changed) {
                    changed.remove(input.value.levelWrapper);
                }
            }
        });
        DhApiEventRegister.on(DhApiChunkModifiedEvent.class, new DhApiChunkModifiedEvent() {
            @Override
            public void onChunkModified(DhApiEventParam<EventParam> input) {
                long chunk = ChunkPos.asLong(input.value.chunkX, input.value.chunkZ);
                synchronized (changed) {
                    changed.computeIfAbsent(input.value.levelWrapper, l -> new HashSet<>()).add(chunk);
                }
            }
        });
        // DH may have initialized before subscription: event already passed, fields already populated.
        if (DhApi.Delayed.terrainRepo != null) initialized = true;
    }

    @Override
    public boolean offThread() {
        return true;
    }

    @Override
    public long refreshNanos() {
        return REFRESH_NS;
    }

    /**
     * DH threads from its settings ({@code threadCount}): all its pools use this many ({@code PriorityTaskPicker} 3.3.3),
     * and map region reads ({@code getAsync} in the IO pool) wait for a free thread in the same queue as LOD loading.
     * Public API provides only full-detail regions, so a map view needs hundreds or thousands of these reads.
     */
    @Override
    public int parallelism() {
        IDhApiConfig configs = DhApi.Delayed.configs;
        return configs == null ? 1 : configs.multiThreading().threadCount().getValue();
    }

    /** Process all accumulated changes; discard other worlds (another dimension, previous world). */
    @Override
    public void changes(ClientLevel level, ChunkSink sink) {
        IDhApiLevelWrapper mine = initialized ? dhLevel(level) : null;
        Set<Long> chunks;
        synchronized (changed) {
            if (changed.isEmpty()) return;
            chunks = mine == null ? null : changed.get(mine);
            changed.clear();
        }
        if (chunks != null) {
            for (long c : chunks) sink.changed(ChunkPos.getX(c), ChunkPos.getZ(c));
        }
    }

    /** World exit: accumulated changes no longer belong anywhere; do not retain world wrappers. */
    static void clearChanges() {
        synchronized (changed) {
            changed.clear();
        }
    }

    @Nullable
    @Override
    public Reader open(ClientLevel level) {
        IDhApiLevelWrapper dhLevel = initialized ? dhLevel(level) : null;
        if (dhLevel == null) {
            notOpened = !initialized ? "DH not initialized yet" : "DH has not loaded world " + level.dimension().location();
            return null;
        }
        IDhApiTerrainDataRepo repo = DhApi.Delayed.terrainRepo;
        notOpened = "";
        IDhApiTerrainDataCache cache = repo.createSoftCache();
        return new Reader() {
            @Nullable
            @Override
            public Column column(int x, int z) {
                DhApiResult<DhApiTerrainDataPoint[]> r;
                try {
                    r = repo.getColumnDataAtBlockPos(dhLevel, x, z, cache);
                } catch (RuntimeException e) {
                    // API failed by exception rather than DhApiResult: column has no data, as with any failure;
                    // one column does not fail the entire tile (which would be rebuilt every 5 s with a logged stack trace).
                    if (failed.getAndIncrement() == 0) firstFailure = e.toString();
                    if (!threw.getAndSet(true)) Incoming.LOG.warn("Distant Horizons: reading column ({}, {}) threw an exception", x, z, e);
                    return null;
                }
                if (!r.success || r.payload == null) {
                    if (failed.getAndIncrement() == 0) firstFailure = r.message;
                    return null;
                }
                Column c = top(r.payload);
                (c == null ? empty : found).incrementAndGet();
                return c;
            }

            @Override
            public void close() {
                cache.close();
            }
        };
    }

    @Override
    public String describe() {
        return String.format(Locale.ROOT, "DH %s (API %d.%d): columns with top %d, empty %d, failures %d%s%s", DhApi.getModVersion(),
                DhApi.getApiMajorVersion(), DhApi.getApiMinorVersion(), found.get(), empty.get(), failed.get(),
                firstFailure.isEmpty() ? "" : " (first: " + firstFailure + ")", notOpened.isEmpty() ? "" : ", not opened: " + notOpened);
    }

    /**
     * DH level for the client world. Singleplayer DH data belong to the server world (as with
     * {@code getSinglePlayerLevel}); multiplayer uses the client level: its wrapper holds the same {@code ClientLevel}.
     */
    @Nullable
    private static IDhApiLevelWrapper dhLevel(ClientLevel level) {
        boolean singlePlayer = Minecraft.getInstance().hasSingleplayerServer();
        for (IDhApiLevelWrapper w : loaded) {
            Object mc = w.getWrappedMcObject();
            if (singlePlayer ? mc instanceof ServerLevel server && server.dimension() == level.dimension() : mc == level) return w;
        }
        return null;
    }

    /** DH column top (data top-down): first block with a map colour; underwater, depth to the bottom. */
    @Nullable
    private static Column top(DhApiTerrainDataPoint[] column) {
        for (int i = 0; i < column.length; i++) {
            DhApiTerrainDataPoint p = column[i];
            MapColor color = color(p);
            if (color == MapColor.NONE) continue;
            int depth = p.blockStateWrapper.isLiquid() ? waterDepth(column, i) : 0;
            return new Column(p.topYBlockPos, color, depth);
        }
        return null;
    }

    private static int waterDepth(DhApiTerrainDataPoint[] column, int water) {
        int surface = column[water].topYBlockPos;
        for (int i = water + 1; i < column.length; i++) {
            DhApiTerrainDataPoint p = column[i];
            if (p != null && !p.blockStateWrapper.isAir() && !p.blockStateWrapper.isLiquid()) {
                return Math.min(MAX_WATER_DEPTH, surface - p.topYBlockPos);
            }
        }
        return MAX_WATER_DEPTH;
    }

    private static MapColor color(@Nullable DhApiTerrainDataPoint p) {
        if (p == null) return MapColor.NONE;
        IDhApiBlockStateWrapper block = p.blockStateWrapper;
        if (block == null || block.isAir() || !(block.getWrappedMcObject() instanceof BlockState state)) return MapColor.NONE;
        // Block colour without surrounding world: vanilla and almost all modded blocks do not depend on it.
        return state.getMapColor(EmptyBlockGetter.INSTANCE, BlockPos.ZERO);
    }
}
