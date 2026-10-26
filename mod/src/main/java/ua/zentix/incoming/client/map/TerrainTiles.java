package ua.zentix.incoming.client.map;

import com.google.common.util.concurrent.ThreadFactoryBuilder;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.level.material.MapColor;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModList;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.common.NeoForge;
import org.jetbrains.annotations.Nullable;
import ua.zentix.incoming.Incoming;
import ua.zentix.incoming.IncomingConfig;
import ua.zentix.incoming.registry.ModItems;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.OptionalInt;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Map base: terrain in 64×64-pixel tiles, as in web maps. A level {@code L} tile covers {@code 64·2^L} blocks,
 * a pixel is the column at the centre of a {@code 2^L}-block cell; scale selects the level so each screen pixel
 * contains no more than one tile pixel. Vanilla map colours ({@link MapColor}), with north-facing slope shading
 * as on a held map.
 * <p>
 * Two layers: distant terrain below (kilometres around, built on background threads), client chunks above (built
 * gradually within frames). Distant source is the first installed and operating one: LOD Server Support (server
 * distributes terrain, {@link LssTerrain}), otherwise Distant Horizons ({@link DistantHorizonsTerrain}); neither
 * means chunks only. Cells without data are transparent: nearby chunks are accurate and current (craters), distant
 * areas use distant terrain. DH reads may wait indefinitely in its queues (no data yet, generation stalled, DH
 * loading LODs): only our background threads wait, independently of the chunk layer. At most {@link #PREFETCH_JOBS}
 * threads while no map is shown; while shown (remote or projectile camera without video), as many as the source's
 * threads ({@link TerrainSource#parallelism}): the world behind the map is hidden, and the view receives the full
 * read throughput starting at its centre. Ready tiles upload to textures on the game thread.
 * <p>
 * Each DH read is a 64×64-block database region (unpacking in its file-thread queue), so read DH terrain once where
 * possible: larger tiles compose four ready tiles one level below ({@link #compose}); detailed tiles around the
 * player are prefetched while the map is closed ({@link #tick}): DH in the background, client chunks in partial ticks
 * within {@link #PREFETCH_TICK_NS}, so the map opens on the player immediately. Ready DH tiles rebuild on chunk change
 * events rather than a timer, and a rebuilt finer tile immediately copies into its quarter of larger tiles.
 * Tiles live while the world is open; least recently displayed are evicted.
 */
public final class TerrainTiles {
    static final int SIZE = 64;
    /** Largest tile: 1024 blocks (16 blocks per pixel). */
    public static final int MAX_LEVEL = 4;
    /** At least this many tiles per layer, however small the map ({@link #capacity}). */
    private static final int MIN_TILES = 768;
    /** Concurrent background tile builds while no map is shown: DH reads its database; do not take more from the game. */
    static final int PREFETCH_JOBS = 2;
    /** Map drawn this recently counts as on screen (at low FPS, several ticks pass between frames). */
    private static final long SHOWN_NS = 500_000_000L;
    /** Query tiles with incomplete terrain this often (DH still computing distance, chunk not received). */
    private static final long PARTIAL_REFRESH_NS = 30_000_000_000L;
    /** Tiles without data are queried more often. */
    private static final long EMPTY_REFRESH_NS = 5_000_000_000L;
    /** Log a DH read longer than this (once per world): distant terrain cannot appear until DH replies. */
    private static final long STALL_NS = 30_000_000_000L;
    /** Frame time spent building tiles itself: client chunks and tiles one level below. */
    private static final long FRAME_BUDGET_NS = 3_000_000L;
    /** Prefetch detailed background-layer tiles in a ±7-tile square around the player (≈ 900 blocks). */
    static final int PREFETCH_RADIUS = 7;
    /** Reconsider prefetch tiles every this many ticks (player moved). */
    private static final int PREFETCH_PERIOD = 10;
    /**
     * Client tick time for prefetching a game-thread-only layer (client chunks), measured by clock; read tiles in
     * parts across ticks ({@link Partial}): check deadline before every column.
     */
    static final long PREFETCH_TICK_NS = 2_000_000L;
    /** No height data (northern neighbour outside the tile and no estimate). */
    private static final int NO_HEIGHT = Integer.MIN_VALUE;

    record Key(int level, int tx, int tz) {
        int span() {
            return SIZE << level;
        }

        Key parent() {
            return new Key(level + 1, Math.floorDiv(tx, 2), Math.floorDiv(tz, 2));
        }
    }

    /**
     * Tile column tops: height, vanilla map colour ({@link MapColor#id}) and water depth; inputs to pixel painting
     * ({@link #paint}) and larger tile composition. 16 KB per tile.
     */
    static final class Columns {
        static final short NONE = Short.MIN_VALUE;
        final short[] height = new short[SIZE * SIZE];
        final byte[] color = new byte[SIZE * SIZE];
        final byte[] depth = new byte[SIZE * SIZE];
        /** Columns with data. */
        int filled;

        Columns() {
            Arrays.fill(height, NONE);
        }

        boolean has(int i) {
            return height[i] != NONE;
        }

        void set(int i, TerrainSource.Column c) {
            height[i] = (short) Mth.clamp(c.height(), Short.MIN_VALUE + 1, Short.MAX_VALUE);
            color[i] = (byte) c.color().id;
            depth[i] = (byte) Math.min(c.waterDepth(), Byte.MAX_VALUE);
            filled++;
        }

        /** Cell {@code i} copies cell {@code j} of tile {@code from}; no data there means no data here. */
        void copy(int i, Columns from, int j) {
            if (has(i)) filled--;
            height[i] = from.height[j];
            color[i] = from.color[j];
            depth[i] = from.depth[j];
            if (has(i)) filled++;
        }

        /**
         * Tile quarter ({@code q}: 0 northwest, 1 northeast, 2 southwest, 3 southeast) from the tile one level below
         * at its position: pixel uses the child cell near its own cell centre.
         */
        void putQuarter(int q, Columns kid) {
            int half = SIZE / 2, x0 = (q & 1) * half, z0 = (q >> 1) * half;
            for (int z = 0; z < half; z++) {
                for (int x = 0; x < half; x++) copy((z0 + z) * SIZE + x0 + x, kid, (2 * z + 1) * SIZE + 2 * x + 1);
            }
        }

        boolean empty() {
            return filled == 0;
        }

        boolean complete() {
            return filled == SIZE * SIZE;
        }
    }

    static final class Tile {
        final Key key;
        @Nullable
        DynamicTexture texture;
        @Nullable
        ResourceLocation id;
        /** Latest build data; null if never built (or build failed). */
        @Nullable
        Columns columns;
        long builtAt;
        /** Last frame displaying the tile: currently visible tiles are not evicted. */
        long drawnFrame;
        /** Background build start time; 0 if not building. */
        long buildingSince;
        /**
         * Source reported terrain below this tile changed: rebuild earlier than usual. Clear when build starts;
         * changes during the build set it again. Rebuilt child tiles do not set it: they immediately copy into their
         * quarter ({@link #propagate}).
         */
        boolean stale;

        Tile(Key key) {
            this.key = key;
        }
    }

    /**
     * Ready tile: columns (null if not built, queried again as empty), build duration. Game thread paints pixels:
     * first row needs the tile to the north.
     */
    record Built(Key key, int generation, @Nullable Columns columns, long nanos) {
        static Built failed(Key key, int generation) {
            return new Built(key, generation, null, 0);
        }
    }

    /** Larger tile's children: all fresh, compose; any building or due for refresh, wait; any absent, read the source. */
    enum Kids { READY, REFRESHING, ABSENT }

    /** Tiles of one terrain source. */
    static final class Layer {
        final String name;
        final TerrainSource source;
        /** Access order: least recently displayed first. */
        final Map<Key, Tile> tiles = new LinkedHashMap<>(256, 0.75f, true);
        final Queue<Built> done = new ConcurrentLinkedQueue<>();
        /** Tiles prefetched around the player: not evicted. */
        Set<Key> prefetch = Set.of();
        /** The same tiles, nearest first. */
        List<Key> prefetchOrder = List.of();
        /** Tile the game-thread layer is prefetching in parts; null if none. */
        @Nullable
        Partial partial;
        int jobs;
        boolean stallLogged, firstLogged;
        /** Source broken (different DH API version): stop building layer until world change. Written by background thread. */
        volatile boolean broken;
        /** Distant layer (one distant source operates, see {@code pickFar}), rather than client chunks. */
        final boolean far;
        /** For logs: source builds, builds with terrain, build time; compositions from tiles one level below. */
        int built, withData, composed;
        long buildNanos;

        Layer(String name, TerrainSource source) {
            this(name, source, false);
        }

        Layer(String name, TerrainSource source, boolean far) {
            this.name = name;
            this.source = source;
            this.far = far;
        }
    }

    /**
     * Visible tiles at the required scale and number ready, for map opening time logs;
     * {@code layers} gives the same per layer ("dh 32/32, chunks 0/32"), showing which layer lags.
     */
    public record Progress(int visible, int ready, String layers) {
        public boolean done() {
            return ready >= visible;
        }
    }

    @Nullable
    private static ExecutorService executor;
    @Nullable
    private static ClientLevel level;
    /** Bottom to top: distant layers by increasing preference (DH, LSS, if installed), then client chunks. */
    private static List<Layer> layers = List.of();
    /** Currently active distant layer ({@link #pickFar}); null means terrain only from chunks. */
    @Nullable
    private static Layer far;
    /**
     * Changes with the world: discard tiles started for the old one; background builds stop between columns
     * ({@code volatile}, read by their thread).
     */
    private static volatile int generation;
    /** DH installed with compatible API ({@link #init}). */
    private static boolean distantHorizons;
    /** LOD Server Support installed with compatible API ({@link #init}). */
    private static boolean lodServerSupport;
    /** Voxy installed with the classes this mod reads ({@link #init}). */
    private static boolean voxy;
    /** Map frame counter (evict invisible tiles only). */
    private static long frame;
    private static int ticks;
    private static Progress progress = new Progress(0, 0, "");
    /** Prefetch terrain around the player ({@link #prefetchWanted}); checked every {@link #PREFETCH_PERIOD}. */
    private static boolean prefetching;
    /** Map opened in this world before: prefetch around the player even without holding the remote. */
    private static boolean opened;
    /** Last map draw time ({@link #render}); 0 if never drawn in this world. */
    private static long shownAt;
    /** Layer tile limit from the map size at the last draw ({@link #capacity}). */
    private static int capacity = MIN_TILES;

    private TerrainTiles() {}

    /** Tile level for scale {@code k} pixels per block. */
    static int levelFor(double k) {
        return Mth.clamp(Mth.floor(Math.log(1 / k) / Math.log(2)), 0, MAX_LEVEL);
    }

    /** Draw terrain in the screen rectangle and request missing tiles, nearest to centre first. */
    public static void render(GuiGraphics g, MapProjection map, int left, int top, int right, int bottom) {
        ClientLevel current = Minecraft.getInstance().level;
        if (current == null) return;
        if (current != level) switchTo(current);
        frame++;
        shownAt = System.nanoTime();
        int want = levelFor(map.k());
        // Do not build (or create) below the largest tiles' scale: such a map would cost thousands of DH regions.
        boolean build = map.k() * (2 << MAX_LEVEL) >= 1;
        double cx = map.worldX((left + right) / 2.0), cz = map.worldZ((top + bottom) / 2.0);
        capacity = capacity(right - left, bottom - top);
        int visible = 0, ready = 0;
        StringBuilder byLayer = new StringBuilder();
        g.enableScissor(left, top, right, bottom);
        pickFar();
        for (Layer layer : layers) {
            collect(layer);
            if (!live(layer)) continue;
            if (!build) {
                // Hundreds of thousands of frame cells on such a map; create nothing new: draw ready tiles
                // at the largest level, iterating layer tiles (bounded by the layer limit).
                draw(g, map, layer, ready(layer, map, left, top, right, bottom), false);
                continue;
            }
            // Existing larger tiles first, as a base while required tiles build (zooming in does not empty the map).
            for (int l = MAX_LEVEL; l > want; l--) draw(g, map, layer, visible(map, l, left, top, right, bottom), false);
            List<Key> keys = visible(map, want, left, top, right, bottom);
            if (!layer.source.offThread()) keys.removeIf(k -> !nearPlayer(k));
            draw(g, map, layer, keys, true);
            evict(layer);
            if (layer.broken) continue;
            keys.sort(Comparator.comparingDouble(k -> Mth.square((k.tx + 0.5) * k.span() - cx) + Mth.square((k.tz + 0.5) * k.span() - cz)));
            request(current, layer, keys, false);
            int built = 0;
            for (Key k : keys) {
                Tile t = layer.tiles.get(k);
                if (t != null && t.builtAt != 0) built++;
            }
            visible += keys.size();
            ready += built;
            if (!byLayer.isEmpty()) byLayer.append(", ");
            byLayer.append(layer.name).append(' ').append(built).append('/').append(keys.size());
        }
        g.disableScissor();
        progress = new Progress(visible, ready, byLayer.toString());
    }

    /** Once per client tick: background tiles to textures, source terrain changes, prefetch tiles around the player. */
    public static void tick() {
        Minecraft mc = Minecraft.getInstance();
        ClientLevel current = mc.level;
        LocalPlayer player = mc.player;
        if (current == null || player == null) return;
        if (current != level) switchTo(current);
        pickFar();
        if (lodServerSupport) LssTerrain.tick(player.getX(), player.getZ());
        for (Layer layer : layers) {
            collect(layer);
            if (live(layer)) layer.source.tickNear(player.getX(), player.getZ());
            if (layer.source.invalidated(current)) {
                for (Tile t : layer.tiles.values()) t.stale = true;
            }
            if (layer.source.arrivals()) layer.source.changes(current, (chunkX, chunkZ) -> arrived(layer, chunkX, chunkZ));
            else layer.source.changes(current, (chunkX, chunkZ) -> changed(layer, chunkX, chunkZ));
        }
        if (++ticks % PREFETCH_PERIOD == 0) {
            boolean shown = shownAt != 0 && System.nanoTime() - shownAt < SHOWN_NS;
            prefetching = prefetchWanted(IncomingConfig.CLIENT.mapPrefetch.get(), opened,
                    () -> player.getInventory().contains(s -> s.is(ModItems.DESIGNATOR.get())));
            for (Layer layer : layers) {
                if (layer.broken) continue;
                if (!prefetching || !live(layer)) {
                    layer.prefetch = Set.of();
                    layer.prefetchOrder = List.of();
                    dropPartial(layer);
                    continue;
                }
                List<Key> keys = around(player.getX(), player.getZ());
                // Chunk layer only where the client has chunks.
                if (!layer.source.offThread()) keys.removeIf(k -> !nearPlayer(k));
                layer.prefetch = new HashSet<>(keys);
                layer.prefetchOrder = keys;
                for (Key k : keys) layer.tiles.computeIfAbsent(k, Tile::new);
                evict(layer);
                // While map is shown, background reads serve its view from the centre; it reads around the player itself if visible.
                if (layer.source.offThread() && !shown) request(current, layer, keys, true);
            }
        }
        // Chunk layer is game-thread-only: partial reads each tick under a clock deadline; no remote or map means no work.
        if (!prefetching) return;
        long deadline = System.nanoTime() + PREFETCH_TICK_NS;
        for (Layer layer : layers) {
            if (layer.source.offThread() || layer.broken) continue;
            prefetchInGameThread(layer, true, () -> layer.source.open(current), System::nanoTime, deadline, b -> apply(layer, b, true));
        }
    }

    /**
     * Prefetch only for those needing the map: enabled in client settings, and remote in inventory or map opened
     * before in this world. Otherwise DH would read its database for every pack player throughout the game, and
     * client tick would build chunk tiles. Inspect inventory only if the first two conditions did not decide.
     */
    static boolean prefetchWanted(boolean enabled, boolean opened, BooleanSupplier hasDesignator) {
        return enabled && (opened || hasDesignator.getAsBoolean());
    }

    /**
     * Incomplete prefetch tile of a game-thread-only layer: columns before {@code next} (row order) are read.
     * Continue in later ticks; while started, tile has {@code buildingSince} set, so the map does not build it again
     * within the frame.
     */
    static final class Partial {
        final Key key;
        final long started;
        final Columns columns = new Columns();
        int next;
        long nanos;

        Partial(Key key, long started) {
            this.key = key;
            this.started = started;
        }
    }

    /**
     * Prefetch on the game thread from {@link Layer#prefetchOrder} (nearest first): absent, stale or timed-incomplete
     * tiles, one column at a time until {@code clock} reaches {@code deadline}. Continue started tiles next tick;
     * ready ones go to {@code sink}; abandon incomplete tiles no longer needed for prefetch (player moved, teleport).
     * If not needed ({@code wanted} false), abandon the started tile without touching source or clock.
     */
    static void prefetchInGameThread(Layer layer, boolean wanted, Supplier<TerrainSource.Reader> open, LongSupplier clock, long deadline,
                                     Consumer<Built> sink) {
        if (!wanted) {
            dropPartial(layer);
            return;
        }
        if (layer.partial != null && !layer.prefetch.contains(layer.partial.key)) dropPartial(layer);
        if (layer.partial == null && (layer.partial = nextPartial(layer, clock.getAsLong())) == null) return;
        TerrainSource.Reader reader = open.get();
        if (reader == null) return;
        try (reader) {
            while (layer.partial != null) {
                Partial p = layer.partial;
                long start = clock.getAsLong();
                // Detailed tile: one column per block.
                int x0 = p.key.tx * SIZE, z0 = p.key.tz * SIZE;
                boolean late = false;
                while (p.next < SIZE * SIZE) {
                    if (clock.getAsLong() >= deadline) {
                        late = true;
                        break;
                    }
                    int x = p.next % SIZE, z = p.next / SIZE;
                    TerrainSource.Column col = reader.column(x0 + x, z0 + z);
                    if (col != null) p.columns.set(p.next, col);
                    p.next++;
                }
                long now = clock.getAsLong();
                p.nanos += now - start;
                if (late) return;
                Tile t = layer.tiles.get(p.key);
                dropPartial(layer);
                // Tile built another way (or evicted) in the meantime: this one is no longer newer.
                if (t != null && t.builtAt <= p.started) sink.accept(new Built(p.key, generation, p.columns, p.nanos));
                if (now >= deadline || (layer.partial = nextPartial(layer, now)) == null) return;
            }
        }
    }

    /** Next layer prefetch tile: like {@link #request} with {@code prefetch}; marked as building. */
    @Nullable
    private static Partial nextPartial(Layer layer, long now) {
        for (Key key : layer.prefetchOrder) {
            Tile t = layer.tiles.get(key);
            if (t == null || t.buildingSince != 0) continue;
            int u = urgency(false, t.builtAt, t.stale, t.columns, layer.source.refreshNanos(), now);
            if (u == 0 || u == 1 && (t.columns == null || !t.columns.complete())) {
                t.stale = false;
                t.buildingSince = now;
                return new Partial(key, now);
            }
        }
        return null;
    }

    /** Started prefetch tile no longer building: clear its building flag. */
    private static void dropPartial(Layer layer) {
        Partial p = layer.partial;
        if (p == null) return;
        layer.partial = null;
        Tile t = layer.tiles.get(p.key);
        if (t != null && t.buildingSince == p.started) t.buildingSince = 0;
    }

    /** Targeting map opened: prefetch terrain around the player until world exit. */
    public static void opened() {
        opened = true;
    }

    /** Ready tiles among those displayed in the latest map frame. */
    public static Progress progress() {
        return progress;
    }

    /** Distant terrain available (LOD Server Support or Distant Horizons), beyond loaded chunks. */
    public static boolean farTerrain() {
        return far != null;
    }

    /**
     * Map label while distant source has supplied nothing: waiting for its data or read replies (DH busy loading);
     * null if not waiting.
     */
    @Nullable
    public static String farPendingNote() {
        Layer f = far;
        return f != null && (f.source.waiting() || f.withData == 0 && f.jobs > 0) ? f.source.pendingNote() : null;
    }

    /**
     * Active distant layer: first by preference (last in the layer list) whose source operates in this world
     * ({@link TerrainSource#active}: LSS when the server distributes terrain).
     */
    private static void pickFar() {
        far = pickFar(layers);
    }

    @Nullable
    static Layer pickFar(List<Layer> layers) {
        Layer picked = null;
        for (Layer layer : layers) {
            if (layer.far && layer.source.active()) picked = layer;
        }
        return picked;
    }

    /** Layer draws, builds and answers height queries: client chunks always, only the active distant layer. */
    private static boolean live(Layer layer) {
        return !layer.far || layer == far;
    }

    /** Ground height in a column: most detailed ready tile, client chunks first. */
    public static OptionalInt height(int x, int z) {
        return column(x, z, false);
    }

    /** Water depth above bottom in a column (0 land, capped at 16), as {@link #height}. */
    public static OptionalInt waterDepth(int x, int z) {
        return column(x, z, true);
    }

    private static OptionalInt column(int x, int z, boolean water) {
        for (int i = layers.size() - 1; i >= 0; i--) {
            Layer layer = layers.get(i);
            if (!live(layer)) continue;
            TerrainSource.Column now = layer.source.peek(x, z);
            if (now != null) return OptionalInt.of(water ? now.waterDepth() : now.height());
            Map<Key, Tile> tiles = layer.tiles;
            for (int l = 0; l <= MAX_LEVEL; l++) {
                int span = SIZE << l;
                Tile t = tiles.get(new Key(l, Math.floorDiv(x, span), Math.floorDiv(z, span)));
                if (t == null || t.columns == null) continue;
                int px = Math.floorMod(x, span) >> l, pz = Math.floorMod(z, span) >> l;
                int j = pz * SIZE + px;
                if (t.columns.has(j)) return OptionalInt.of(water ? t.columns.depth[j] : t.columns.height[j]);
            }
        }
        return OptionalInt.empty();
    }

    /**
     * Whether column surface cannot burn (nuclear firestorm): most detailed ready tile, client chunks first;
     * −1 unknown, 1 water, ice or snow, 0 other.
     */
    public static int wet(int x, int z) {
        for (int i = layers.size() - 1; i >= 0; i--) {
            Layer layer = layers.get(i);
            if (!live(layer)) continue;
            TerrainSource.Column now = layer.source.peek(x, z);
            if (now != null) return wetness(now.color().id, now.waterDepth());
            Map<Key, Tile> tiles = layer.tiles;
            for (int l = 0; l <= MAX_LEVEL; l++) {
                int span = SIZE << l;
                Tile t = tiles.get(new Key(l, Math.floorDiv(x, span), Math.floorDiv(z, span)));
                if (t == null || t.columns == null) continue;
                int px = Math.floorMod(x, span) >> l, pz = Math.floorMod(z, span) >> l;
                int j = pz * SIZE + px;
                if (!t.columns.has(j)) continue;
                return wetness(t.columns.color[j] & 0x3F, t.columns.depth[j]);
            }
        }
        return -1;
    }

    private static int wetness(int color, int waterDepth) {
        return waterDepth > 0 || color == MapColor.WATER.id || color == MapColor.ICE.id || color == MapColor.SNOW.id ? 1 : 0;
    }

    /** For logs and scenarios: per layer, ready tiles, tiles with terrain, builds in progress, source output. */
    public static String stats() {
        StringBuilder b = new StringBuilder();
        long now = System.nanoTime();
        for (Layer layer : layers) {
            long oldest = 0;
            for (Tile t : layer.tiles.values()) {
                if (t.buildingSince != 0) oldest = Math.max(oldest, now - t.buildingSince);
            }
            if (!b.isEmpty()) b.append("; ");
            b.append(String.format(Locale.ROOT, "%s: tiles %d, with terrain %d, avg %.0f ms, composed from smaller %d, in memory %d, building %d (longest %.1f s)",
                    layer.name, layer.built, layer.withData, layer.built == 0 ? 0.0 : layer.buildNanos / 1e6 / layer.built,
                    layer.composed, layer.tiles.size(), layer.jobs, oldest / 1e9));
            String src = layer.source.describe();
            if (!src.isEmpty()) b.append(", ").append(src);
        }
        return b.toString();
    }

    /**
     * World exit or dimension change: textures released, background tiles no longer needed. Create new threads:
     * old-world DH reads may wait indefinitely, which would queue the new world behind them. Do not interrupt old
     * threads: DH logs interruption during a read as an error ({@code InterruptedException} in
     * {@code getTerrainDataColumnArray}). Their builds see the new generation and stop before the next column;
     * queued tasks finish immediately; threads are daemons.
     */
    public static void reset() {
        for (Layer layer : layers) {
            for (Tile t : layer.tiles.values()) release(t);
            layer.source.close();
        }
        layers = List.of();
        far = null;
        level = null;
        generation++;
        progress = new Progress(0, 0, "");
        opened = false;
        shownAt = 0;
        prefetching = false;
        // DH event queue would retain wrappers of the world we left.
        if (distantHorizons) DistantHorizonsTerrain.clearChanges();
        // LSS data to disk: next login receives only changes.
        if (lodServerSupport) LssTerrain.flush();
        LoadedChunksTerrain.clearArrivals();
        if (executor != null) {
            executor.shutdown();
            executor = null;
        }
    }

    /**
     * At client startup: subscribe to client chunk arrivals (chunk layer), check LSS and DH with our API version,
     * subscribe to their columns and events. Touch source classes only when their mod is installed: otherwise
     * API references cannot resolve.
     */
    public static void init(IEventBus modBus) {
        NeoForge.EVENT_BUS.addListener(LoadedChunksTerrain::onChunkLoad);
        // Subscribe to LSS after all mod constructors: its API touches its own network classes.
        if (ModList.get().isLoaded("lss")) modBus.addListener((FMLClientSetupEvent e) -> subscribeLss());
        // Voxy classes (via Roxy) also after all mods load.
        if (ModList.get().isLoaded("voxy")) modBus.addListener((FMLClientSetupEvent e) -> voxy = VoxyTerrain.init());
        if (!ModList.get().isLoaded("distanthorizons")) return;
        try {
            distantHorizons = DistantHorizonsTerrain.supported();
            if (distantHorizons) DistantHorizonsTerrain.subscribe();
        } catch (LinkageError e) {
            // DH API lacks required classes or methods (newer DH than known): game loads, terrain comes from chunks.
            Incoming.LOG.warn("Distant Horizons: API differs from the one the mod was built for — far terrain on the map is off", e);
            distantHorizons = false;
        }
    }

    private static void subscribeLss() {
        try {
            LssTerrain.subscribe();
            lodServerSupport = true;
        } catch (LinkageError e) {
            // LSS API lacks required classes or methods (newer LSS than known): game loads, terrain comes from elsewhere.
            Incoming.LOG.warn("LOD Server Support: API differs from the one the mod was built for — far terrain from it is off ({})", e.toString());
        }
    }

    private static void switchTo(ClientLevel current) {
        reset();
        level = current;
        layers = stack(distantHorizons ? new DistantHorizonsTerrain() : null, voxy ? new VoxyTerrain(current) : null,
                lodServerSupport ? new LssTerrain(current) : null, new LoadedChunksTerrain());
        pickFar();
    }

    /**
     * Layers bottom to top; distant by increasing preference ({@link #pickFar} takes the last active): DH, Voxy database,
     * LSS. LSS supplies server terrain for the entire map while distributed; Voxy database holds everything seen or
     * imported, read at tile levels and working in singleplayer too; DH only via API in block-detail regions.
     * Touch source classes only with their mod installed: null if absent.
     */
    static List<Layer> stack(@Nullable TerrainSource dh, @Nullable TerrainSource voxy, @Nullable TerrainSource lss, TerrainSource chunks) {
        List<Layer> list = new ArrayList<>(4);
        if (dh != null) list.add(new Layer("dh", dh, true));
        if (voxy != null) list.add(new Layer("voxy", voxy, true));
        if (lss != null) list.add(new Layer("lss", lss, true));
        list.add(new Layer("chunks", chunks));
        return List.copyOf(list);
    }

    /** Tile intersects client render distance: no client chunks beyond it. */
    private static boolean nearPlayer(Key key) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return false;
        int r = (mc.options.getEffectiveRenderDistance() + 1) * 16, span = key.span();
        double px = mc.player.getX(), pz = mc.player.getZ();
        double x0 = key.tx * (double) span, z0 = key.tz * (double) span;
        return x0 < px + r && x0 + span > px - r && z0 < pz + r && z0 + span > pz - r;
    }

    /** Detailed tiles in a {@link #PREFETCH_RADIUS} square around the point, nearest first. */
    static List<Key> around(double x, double z) {
        int cx = Math.floorDiv(Mth.floor(x), SIZE), cz = Math.floorDiv(Mth.floor(z), SIZE), r = PREFETCH_RADIUS;
        List<Key> keys = new ArrayList<>((2 * r + 1) * (2 * r + 1));
        for (int tz = cz - r; tz <= cz + r; tz++) {
            for (int tx = cx - r; tx <= cx + r; tx++) keys.add(new Key(0, tx, tz));
        }
        keys.sort(Comparator.comparingInt(k -> Mth.square(k.tx - cx) + Mth.square(k.tz - cz)));
        return keys;
    }

    /**
     * Layer tile limit for a {@code width × height}-pixel map: twice the widest possible view (current screen with
     * larger tile base, plus previous view to return to), and prefetch square around the player, at least
     * {@link #MIN_TILES}. A tile on screen is at least 32 pixels (level chosen so tile pixels are no smaller than
     * screen pixels, and map builds no tiles below the largest tiles' scale); a segment {@code s} tiles long
     * intersects at most {@code ceil(s) + 1} tiles.
     */
    static int capacity(int width, int height) {
        int widest = (Mth.ceil(width / 32.0) + 1) * (Mth.ceil(height / 32.0) + 1);
        return Math.max(MIN_TILES, 2 * widest + Mth.square(2 * PREFETCH_RADIUS + 1));
    }

    /**
     * Concurrent background builds in a layer: prefetch ({@code prefetch}, no map shown) uses {@link #PREFETCH_JOBS};
     * visible map uses as many as the source's read threads ({@code parallelism}): each of our reads holds one,
     * and extra reads would wait in its own queue.
     */
    static int jobLimit(boolean prefetch, int parallelism) {
        return prefetch ? PREFETCH_JOBS : Math.max(PREFETCH_JOBS, parallelism);
    }

    static List<Key> visible(MapProjection map, int l, int left, int top, int right, int bottom) {
        int span = SIZE << l;
        int x0 = Mth.floor(map.worldX(left) / span), x1 = Mth.floor(map.worldX(right) / span);
        int z0 = Mth.floor(map.worldZ(top) / span), z1 = Mth.floor(map.worldZ(bottom) / span);
        List<Key> keys = new ArrayList<>((x1 - x0 + 1) * (z1 - z0 + 1));
        for (int tz = z0; tz <= z1; tz++) {
            for (int tx = x0; tx <= x1; tx++) keys.add(new Key(l, tx, tz));
        }
        return keys;
    }

    /** Ready largest-level tiles intersecting the screen rectangle. */
    private static List<Key> ready(Layer layer, MapProjection map, int left, int top, int right, int bottom) {
        double x0 = map.worldX(left), x1 = map.worldX(right), z0 = map.worldZ(top), z1 = map.worldZ(bottom);
        List<Key> keys = new ArrayList<>();
        for (Key key : layer.tiles.keySet()) {
            double span = key.span();
            if (key.level == MAX_LEVEL && key.tx * span < x1 && (key.tx + 1) * span > x0 && key.tz * span < z1 && (key.tz + 1) * span > z0) {
                keys.add(key);
            }
        }
        return keys;
    }

    /** @param create create a tile if absent (otherwise draw ready tiles only) */
    private static void draw(GuiGraphics g, MapProjection map, Layer layer, List<Key> keys, boolean create) {
        for (Key key : keys) {
            Tile t = create ? layer.tiles.computeIfAbsent(key, Tile::new) : layer.tiles.get(key);
            if (t == null) continue;
            t.drawnFrame = frame;
            if (t.id == null) continue;
            int span = key.span();
            float x = (float) (map.ox() + (key.tx * (double) span - map.cx()) * map.k());
            float y = (float) (map.oy() + (key.tz * (double) span - map.cz()) * map.k());
            float s = (float) (span * map.k() / SIZE);
            g.pose().pushPose();
            g.pose().translate(x, y, 0);
            g.pose().scale(s, s, 1);
            g.blit(t.id, 0, 0, 0, 0, SIZE, SIZE, SIZE, SIZE);
            g.pose().popPose();
        }
    }

    /**
     * Whether tile needs building: 0 now (never built, underlying terrain changed), 1 due for refresh (empty after
     * {@link #EMPTY_REFRESH_NS}, incomplete after {@link #PARTIAL_REFRESH_NS}, complete as source specifies),
     * −1 unnecessary or already building.
     */
    static int urgency(boolean building, long builtAt, boolean stale, @Nullable Columns columns, long refreshNanos, long now) {
        if (building) return -1;
        if (builtAt == 0 || stale) return 0;
        long period = columns == null || columns.empty() ? EMPTY_REFRESH_NS : columns.complete() ? refreshNanos : PARTIAL_REFRESH_NS;
        return now - builtAt >= period ? 1 : -1;
    }

    /**
     * Build needed tiles from {@code keys} (nearest first): absent before stale. Larger tile from four fresh tiles
     * one level below ({@link #kids}); any building or stale children go first, parent waits; any absent means read
     * source, in background (concurrent limit {@link #jobLimit}) or in frame under {@link #FRAME_BUDGET_NS}.
     * {@code prefetch} runs while map is not viewed: missing, stale and timed-incomplete tiles (complete tiles
     * reread on source event or when viewed).
     */
    private static void request(ClientLevel current, Layer layer, List<Key> keys, boolean prefetch) {
        TerrainSource src = layer.source;
        long now = System.nanoTime();
        long deadline = now + FRAME_BUDGET_NS;
        int limit = src.offThread() ? jobLimit(prefetch, src.parallelism()) : 0;
        List<Tile> todo = new ArrayList<>();
        List<Tile> later = new ArrayList<>();
        for (Key key : keys) {
            Tile t = layer.tiles.get(key);
            if (t == null) continue;
            if (t.buildingSince != 0) {
                stalled(layer, t, now);
                continue;
            }
            int u = urgency(false, t.builtAt, t.stale, t.columns, src.refreshNanos(), now);
            if (u == 0) todo.add(t);
            else if (u == 1 && (!prefetch || t.columns == null || !t.columns.complete())) later.add(t);
        }
        todo.addAll(later);
        Set<Key> queued = new HashSet<>();
        for (Tile t : todo) queued.add(t.key);
        TerrainSource.Reader inFrame = null;
        try {
            // List grows: append children of larger tiles that are due for refresh.
            for (int n = 0; n < todo.size(); n++) {
                Tile t = todo.get(n);
                if (System.nanoTime() > deadline) return;
                if (t.buildingSince != 0) continue;
                Tile[] kids = kidsOf(layer, t.key);
                switch (kids(kids, src.refreshNanos(), now)) {
                    case READY -> {
                        Columns c = new Columns();
                        for (int q = 0; q < 4; q++) c.putQuarter(q, kids[q].columns);
                        t.stale = false;
                        layer.composed++;
                        apply(layer, new Built(t.key, generation, c, 0), false);
                        continue;
                    }
                    case REFRESHING -> {
                        for (Tile kid : kids) {
                            if (kid.buildingSince == 0 && urgency(false, kid.builtAt, kid.stale, kid.columns, src.refreshNanos(), now) >= 0
                                    && queued.add(kid.key)) todo.add(kid);
                        }
                        continue;
                    }
                    case ABSENT -> {}
                }
                if (src.offThread()) {
                    if (layer.jobs >= limit) continue;
                    TerrainSource.Reader reader = src.open(current, t.key.level);
                    if (reader == null) return;
                    t.buildingSince = now;
                    t.stale = false;
                    layer.jobs++;
                    int gen = generation;
                    Key key = t.key;
                    executor().execute(() -> buildOffThread(layer, key, gen, reader, () -> gen != generation));
                } else {
                    if (inFrame == null) inFrame = src.open(current);
                    if (inFrame == null) return;
                    t.stale = false;
                    apply(layer, build(t.key, generation, inFrame, () -> false), true);
                }
            }
        } finally {
            if (inFrame != null) inFrame.close();
        }
    }

    /** Four tiles one level below (northwest, northeast, southwest, southeast); none for a detailed tile. */
    @Nullable
    private static Tile[] kidsOf(Layer layer, Key key) {
        if (key.level == 0) return null;
        Tile[] kids = new Tile[4];
        for (int q = 0; q < 4; q++) kids[q] = layer.tiles.get(new Key(key.level - 1, key.tx * 2 + (q & 1), key.tz * 2 + (q >> 1)));
        return kids;
    }

    /**
     * Whether a larger tile can compose its children. Terrain read once serves all scales, and reading a tile from
     * the source costs the same DH regions as all four children; compose when all are fresh: neither event-stale nor
     * timed for refresh (incomplete {@link #PARTIAL_REFRESH_NS}, empty {@link #EMPTY_REFRESH_NS}: a just-requeried
     * incomplete child is as fresh as the source, and a reread parent would have the same holes). Wait for any building
     * or stale child (reading the parent would read the same regions twice). Any absent child (or failed build)
     * means {@link Kids#ABSENT}: read source.
     */
    static Kids kids(@Nullable Tile[] kids, long refreshNanos, long now) {
        if (kids == null) return Kids.ABSENT;
        boolean waiting = false;
        for (Tile kid : kids) {
            if (kid == null) return Kids.ABSENT;
            if (kid.buildingSince != 0) {
                waiting = true;
                continue;
            }
            if (kid.columns == null) return Kids.ABSENT;
            if (urgency(false, kid.builtAt, kid.stale, kid.columns, refreshNanos, now) >= 0) waiting = true;
        }
        return waiting ? Kids.REFRESHING : Kids.READY;
    }

    /** Compose children in order: northwest, northeast, southwest, southeast. */
    static Columns compose(Columns[] kids) {
        Columns c = new Columns();
        for (int q = 0; q < 4; q++) c.putQuarter(q, kids[q]);
        return c;
    }

    /**
     * Background tile build. Return tile to the layer for every outcome, including {@link Error} (rethrow it): otherwise
     * layer task and tile's building flag would remain forever, retaining the layer too.
     *
     * @param cancelled tile no longer needed (world changed): stop build before next column
     */
    static void buildOffThread(Layer layer, Key key, int gen, TerrainSource.Reader reader, BooleanSupplier cancelled) {
        Built result = Built.failed(key, gen);
        try (reader) {
            result = build(key, gen, reader, cancelled);
        } catch (RuntimeException e) {
            Incoming.LOG.warn("Map: tile {} ({}) not built", key, layer.name, e);
        } catch (LinkageError e) {
            // DH API version differs from build: disable source until world change.
            Incoming.LOG.error("Map: source {} is incompatible, terrain from it is off", layer.name, e);
            layer.broken = true;
        } finally {
            layer.done.add(result);
        }
    }

    private static void stalled(Layer layer, Tile t, long now) {
        if (layer.stallLogged || now - t.buildingSince < STALL_NS) return;
        layer.stallLogged = true;
        Incoming.LOG.warn("Map: source {} has not delivered tile {} for over {} s — far terrain will appear when it answers; {}",
                layer.name, t.key, STALL_NS / 1_000_000_000L, stats());
    }

    /** Collect background-built tiles and upload to textures. */
    private static void collect(Layer layer) {
        for (Built b; (b = layer.done.poll()) != null; ) {
            if (b.generation != generation) continue;
            layer.jobs--;
            apply(layer, b, true);
        }
    }

    /** Source reported a changed chunk: rebuild overlying tiles at every level when needed. */
    static void changed(Layer layer, int chunkX, int chunkZ) {
        for (int l = 0; l <= MAX_LEVEL; l++) {
            int span = SIZE << l;
            Tile t = layer.tiles.get(new Key(l, Math.floorDiv(chunkX * 16, span), Math.floorDiv(chunkZ * 16, span)));
            if (t != null) t.stale = true;
        }
    }

    /**
     * Source supplied previously absent chunk data (chunk arrived on client): rebuild only overlying tiles with
     * holes (complete ones already had it) and those currently building (earlier columns already read as empty;
     * completing the build does not clear the flag).
     */
    static void arrived(Layer layer, int chunkX, int chunkZ) {
        for (int l = 0; l <= MAX_LEVEL; l++) {
            int span = SIZE << l;
            Tile t = layer.tiles.get(new Key(l, Math.floorDiv(chunkX * 16, span), Math.floorDiv(chunkZ * 16, span)));
            if (t == null) continue;
            if (t.buildingSince != 0 || t.builtAt != 0 && (t.columns == null || !t.columns.complete())) t.stale = true;
        }
    }

    /** @param fromSource tile read from source (for logs; compositions from finer tiles counted separately) */
    private static void apply(Layer layer, Built b, boolean fromSource) {
        Tile t = layer.tiles.get(b.key);
        if (t == null) return;
        t.buildingSince = 0;
        t.builtAt = System.nanoTime();
        Columns c = b.columns;
        if (fromSource) {
            layer.built++;
            layer.buildNanos += b.nanos;
            if (c != null && !c.empty()) layer.withData++;
            if (c != null && !c.empty() && !layer.firstLogged) {
                layer.firstLogged = true;
                Incoming.LOG.info("Map: first tile with terrain from {} — {} in {} ms; {}", layer.name, b.key, b.nanos / 1_000_000, stats());
            }
        }
        if (c == null) return;
        t.columns = c;
        repaint(layer, t);
        propagate(layer, t);
    }

    /**
     * Rebuilt tile into its quarter of existing larger tiles, up to the largest: no source reread for an already
     * read change. A larger tile building from source will receive its own data.
     */
    private static void propagate(Layer layer, Tile t) {
        for (Tile kid = t; kid.key.level < MAX_LEVEL; ) {
            Tile parent = layer.tiles.get(kid.key.parent());
            if (parent == null || parent.columns == null || parent.buildingSince != 0 || kid.columns == null) return;
            parent.columns.putQuarter((Math.floorMod(kid.key.tz, 2) << 1) | Math.floorMod(kid.key.tx, 2), kid.columns);
            repaint(layer, parent);
            kid = parent;
        }
    }

    /** Pixels of tile and its southern neighbour: neighbour's first row shaded using this tile's last row. */
    private static void repaint(Layer layer, Tile t) {
        upload(layer, t);
        Tile south = layer.tiles.get(new Key(t.key.level, t.key.tx, t.key.tz + 1));
        if (south != null && south.columns != null) upload(layer, south);
    }

    private static void upload(Layer layer, Tile t) {
        if (t.columns == null) return;
        Tile north = layer.tiles.get(new Key(t.key.level, t.key.tx, t.key.tz - 1));
        int[] pixels = paint(t.columns, t.key.level, north == null ? null : north.columns);
        if (t.texture == null) {
            t.texture = new DynamicTexture(SIZE, SIZE, false);
            t.id = Incoming.id("map/" + layer.name + "/" + t.key.level + "/" + t.key.tx + "/" + t.key.tz);
            Minecraft.getInstance().getTextureManager().register(t.id, t.texture);
        }
        NativeImage image = t.texture.getPixels();
        if (image == null) return;
        for (int z = 0; z < SIZE; z++) {
            for (int x = 0; x < SIZE; x++) image.setPixelRGBA(x, z, pixels[z * SIZE + x]);
        }
        t.texture.upload();
    }

    /** Above limit, evict least recently displayed, except visible this frame, needed for prefetch, or building. */
    private static void evict(Layer layer) {
        Iterator<Tile> it = layer.tiles.values().iterator();
        while (layer.tiles.size() > capacity && it.hasNext()) {
            Tile t = it.next();
            if (t.buildingSince != 0 || t.drawnFrame == frame || layer.prefetch.contains(t.key)) continue;
            release(t);
            it.remove();
        }
    }

    private static void release(Tile t) {
        if (t.id != null) Minecraft.getInstance().getTextureManager().release(t.id);
        t.texture = null;
        t.id = null;
    }

    /**
     * Background build threads. At most {@link #jobLimit} tasks (enforced by layer task count), and source threads
     * no more than cores (DH {@code threadCount}), so pool sized by cores; idle threads retire.
     */
    private static ExecutorService executor() {
        if (executor == null) {
            int threads = Runtime.getRuntime().availableProcessors();
            ThreadPoolExecutor pool = new ThreadPoolExecutor(threads, threads, 30, TimeUnit.SECONDS, new LinkedBlockingQueue<>(),
                    new ThreadFactoryBuilder().setNameFormat("Incoming map %d").setDaemon(true).setPriority(Thread.MIN_PRIORITY).build());
            pool.allowCoreThreadTimeOut(true);
            executor = pool;
        }
        return executor;
    }

    /** Tile from source: column at each cell centre; cancelled tile has no columns. */
    private static Built build(Key key, int gen, TerrainSource.Reader reader, BooleanSupplier cancelled) {
        long start = System.nanoTime();
        int step = 1 << key.level, span = key.span(), off = step / 2;
        int x0 = key.tx * span + off, z0 = key.tz * span + off;
        Columns c = new Columns();
        for (int z = 0; z < SIZE; z++) {
            for (int x = 0; x < SIZE; x++) {
                if (cancelled.getAsBoolean()) return Built.failed(key, gen);
                TerrainSource.Column col = reader.column(x0 + x * step, z0 + z * step);
                if (col != null) c.set(z * SIZE + x, col);
            }
        }
        return new Built(key, gen, c, System.nanoTime() - start);
    }

    /**
     * Tile pixels (ABGR, as in {@link NativeImage}); transparent without data. Vanilla map shading
     * ({@code MapItem.update}): height difference from northern neighbour; first row uses last row of tile
     * {@code north} if in memory (redraw this one when it arrives later), otherwise estimate height from slope
     * toward the southern neighbour (reading a neighbouring tile for one row would cost another source region per tile).
     */
    static int[] paint(Columns c, int level, @Nullable Columns north) {
        int step = 1 << level;
        int[] pixels = new int[SIZE * SIZE];
        for (int z = 0; z < SIZE; z++) {
            for (int x = 0; x < SIZE; x++) {
                int i = z * SIZE + x;
                if (!c.has(i)) continue;
                int h = c.height[i], northH;
                if (z > 0) northH = c.has(i - SIZE) ? c.height[i - SIZE] : NO_HEIGHT;
                else if (north != null && north.has(i + (SIZE - 1) * SIZE)) northH = north.height[i + (SIZE - 1) * SIZE];
                else northH = c.has(i + SIZE) ? 2 * h - c.height[i + SIZE] : NO_HEIGHT;
                pixels[i] = MapColor.byId(c.color[i] & 0x3F).calculateRGBColor(shade(h, c.depth[i], northH, step, x + z));
            }
        }
        return pixels;
    }

    /**
     * Cell brightness: water by depth, land by height difference from northern neighbour (divide by cell size on a
     * coarse tile). {@code parity} is vanilla map's checker pattern to keep flat areas distinct.
     */
    static MapColor.Brightness shade(int height, int waterDepth, int northHeight, int step, int parity) {
        double dither = (parity & 1);
        if (waterDepth > 0) {
            double f = waterDepth * 0.1 + dither * 0.2;
            return f < 0.5 ? MapColor.Brightness.HIGH : f > 0.9 ? MapColor.Brightness.LOW : MapColor.Brightness.NORMAL;
        }
        if (northHeight == NO_HEIGHT) return MapColor.Brightness.NORMAL;
        double d = (height - northHeight) * 4.0 / (step + 4) + (dither - 0.5) * 0.4;
        return d > 0.6 ? MapColor.Brightness.HIGH : d < -0.6 ? MapColor.Brightness.LOW : MapColor.Brightness.NORMAL;
    }
}
