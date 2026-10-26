package ua.zentix.incoming.nuclear.world;

import it.unimi.dsi.fastutil.HashCommon;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import io.netty.buffer.Unpooled;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ThreadedLevelLightEngine;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.lighting.BlockLightEngine;
import net.minecraft.world.level.lighting.ChunkSkyLightSources;
import net.minecraft.world.level.lighting.LightEngine;
import net.minecraft.world.level.lighting.LevelLightEngine;
import net.minecraft.world.level.lighting.SkyLightEngine;
import net.minecraft.world.phys.shapes.Shapes;
import org.jetbrains.annotations.Nullable;

import ua.zentix.incoming.Incoming;
import ua.zentix.incoming.IncomingConfig;
import ua.zentix.incoming.compat.DhUpdates;
import ua.zentix.incoming.compat.SableTerrain;
import ua.zentix.incoming.grid.GridLights;
import ua.zentix.incoming.net.S2C;
import ua.zentix.incoming.nuclear.Detonation;
import ua.zentix.incoming.util.Terrain;

import java.util.List;

/**
 * One chunk's prebuilt ruins ({@link RuinPlanner}), applied to world at once when wave reaches
 * chunk: not block-by-block ({@code setBlock} with heightmaps, light, neighbors, Sable costs ≈10 µs/block, thousands
 * per city chunk), but plan positions directly into sections and {@code setBlock} work once per chunk.
 * <p>
 * Plan stores delta only: changed positions (4 bytes each: section position, section, new plan-palette state,
 * “check light”, “through world”), fires, changed-column heightmaps/lower sky sources (before/after)
 * and light-check positions. No section copies retained. All other section blocks remain as at wave arrival,
 * including blackout-darkened lamps (changed directly in palette) and blocks placed after plan.
 * <p>
 * Plan stale if its positions changed afterward (player, explosion, other mod): each changed section stores hash
 * of old states at plan positions (lamp/darkened twin identical). Mismatch rebuilds plan
 * from current chunk. Other blocks checked at replacement against snapshot section fingerprints
 * ({@link #sameBlocks}); mod does not listen for chunk changes.
 * <p>
 * Replacement repeats {@code LevelChunk.setBlockState} work once per chunk instead of per block: positions into
 * sections in place (like {@code setBlock}; never replace by reading into section, see pitfalls); column heightmaps and
 * sky sources from plan (different live “before” value means column changed since plan: rescan entire chunk),
 * section emptiness to light engine; light checks (highest changed column point, new/removed blocks beneath
 * surviving top, new/removed light sources) in one light-engine task. Chunk viewers
 * receive same-tick section packets ({@code ChunkHolder.blockChanged}, like {@code setBlock}); Distant Horizons
 * LODs queued same tick ({@link DhUpdates}). Block entities/POIs not changed this way: world
 * changes them afterward ({@link ColumnScar#replace}: {@code onRemove} and consequences, no inventory drops, deferred
 * data removed). Queue places trunks in neighboring chunks after their ruins. Fires applied with
 * ruins (without {@code setBlock}: wall/tree fire does not wake neighbors), only fire tick scheduled.
 */
public final class RuinPlan {
    /** Server chunk heightmaps; index is map type in {@link #heights}. */
    public static final Heightmap.Types[] HEIGHTMAP_TYPES = {Heightmap.Types.MOTION_BLOCKING, Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
            Heightmap.Types.OCEAN_FLOOR, Heightmap.Types.WORLD_SURFACE};
    /** Type in {@link #heights}: column lower sky source. */
    static final int SKY = HEIGHTMAP_TYPES.length;

    /** Position packing: 12 bits section position (y, z, x), 8 section index, 10 palette state, flags. */
    static final int SECTION_SHIFT = 12, STATE_SHIFT = 20, STATE_MASK = 0x3FF;
    /** Position flag: check light after replacement. */
    static final int LIGHT = 1 << 30;
    /** Position flag: block entity/POI, changed through world. */
    static final int SLOW = 1 << 31;
    /** Distinct states fitting plan palette. */
    static final int MAX_STATES = STATE_MASK + 1;

    /** Fluid ticks per second per chunk (rest in later seconds). */
    private static final int FLUID_TICKS = 64;
    private static final int[] NONE = new int[0];
    private static final long[] NO_LONGS = new long[0];
    private static final BlockState[] NO_STATES = new BlockState[0];

    /** Unchanged plan: wave has nothing to change (field, water, zone-edge chunk). */
    static final RuinPlan EMPTY = new RuinPlan(NONE, NO_LONGS, NO_STATES, NO_STATES, NONE, NONE, NO_LONGS, null, null, null, null, null, NO_LONGS, null);

    /**
     * No blocks change, but chunk ruins “applied”: neighbors read original chunk (already original), counters at
     * replacement; {@code biomes} means light still singed biome.
     */
    static RuinPlan nothing(@Nullable RuinContext ctx, @Nullable ScorchedBiomes.Cells biomes) {
        return ctx == null && biomes == null ? EMPTY : new RuinPlan(NONE, NO_LONGS, NO_STATES, NO_STATES, NONE, NONE, NO_LONGS, null, null, null, null, ctx, NO_LONGS, biomes);
    }

    /**
     * Log: replacement-stage times, ns, plan check, positions/fires in sections (plus section emptiness to light
     * engine), heightmaps/sky sources, light/player packets, world-path block entities.
     */
    static final long[] PHASES = new long[5];
    /** Log: longest world-path part, time (ns), chunk, world-path position count (since previous line). */
    static long slowestWorldNanos, slowestWorldChunk;
    static int slowestWorldCells;
    /** This chunk: longest world-path block replacement and Distant Horizons LOD call, ns. */
    @Nullable
    static BlockState slowestWorldBlock;
    static long slowestWorldBlockNanos, slowestWorldDhNanos;

    /**
     * Planned heightmaps/sky sources of changed columns: triples (column {@code << 3} | type,
     * index in {@link #HEIGHTMAP_TYPES} or {@link #SKY}; pre-ruin value; post-ruin value). Changed values only.
     */
    private final int[] heights;
    /** Light-check positions (except world-path, whose light world checks), as {@link BlockPos#asLong}. */
    private final long[] lightAt;
    /**
     * Plan snapshot section fingerprints: raw ({@link ChunkShot#rawPrint}) and normalized for
     * darkened lamps ({@link ChunkShot#normalPrint}); null without comparison data (no-position plan).
     */
    @Nullable
    private final long[] prints, normals;
    /** Plan positions by section (ascending section index). */
    private final int[] cells;
    /** Per section: hash of old states at its positions (no positions means 0, unchecked). */
    private final long[] oldHashes;
    private final BlockState[] states;
    /** Old plan-position states ({@link #cells} order): neighbors read original chunk while these ruins stand. */
    private final BlockState[] olds;
    /** All-chunk detonation ruins (records replacement: old blocks are neighbors' original world); null outside detonation. */
    @Nullable
    private final RuinContext ctx;
    /** Fluid beside changes: position ({@link BlockPos#asLong}) to tick, flowing into breach or draining. */
    private final long[] fluidTicks;
    /** Fires: position (as {@link #cells}) and fire state from palette. */
    private final int[] fires;
    /** Toppled-tree logs in neighboring chunks: position (height by surface at replacement), state. */
    private final LongArrayList outside;
    private final List<BlockState> outsideState;
    /** Scorched-biome tiers per chunk cell ({@link ScorchedBiomes}); null unchanged. */
    @Nullable
    private final ScorchedBiomes.Cells biomes;
    /**
     * Flash-charred faces of surviving chunk blocks ({@link ua.zentix.incoming.nuclear.flash.FlashChar#mark}): applied
     * to chunk marks with ruins ({@link ua.zentix.incoming.nuclear.flash.FlashSync#chars}); null uncomputed.
     */
    @Nullable
    private int[] flash;
    /**
     * Flash layer ({@link RuinPlanner#flash}): fires wave will extinguish (positions as {@link #fires}, no state,
     * ascending) excluded from detonation fire count: extinguished with chunk ruins before first tick ({@link #write});
     * null if none.
     */
    @Nullable
    private int[] blown;
    /** Sorted fire positions (no state), for lookup; computed on first request. */
    @Nullable
    private int[] fireKeys;
    /** Sections with plan positions/fires (bit per section); computed on first request (server thread). */
    @Nullable
    private long[] sectionBits;

    RuinPlan(int[] cells, long[] oldHashes, BlockState[] states, BlockState[] olds, int[] fires, int[] heights, long[] lightAt, @Nullable long[] prints,
            @Nullable long[] normals, LongArrayList outside, List<BlockState> outsideState, @Nullable RuinContext ctx, long[] fluidTicks,
            @Nullable ScorchedBiomes.Cells biomes) {
        this.biomes = biomes;
        this.olds = olds;
        this.ctx = ctx;
        this.fluidTicks = fluidTicks;
        this.prints = prints;
        this.normals = normals;
        this.heights = heights;
        this.lightAt = lightAt;
        this.cells = cells;
        this.oldHashes = oldHashes;
        this.states = states;
        this.fires = fires;
        this.outside = outside;
        this.outsideState = outsideState;
    }

    /** Plan flash scorch (plan-building thread, before handoff). */
    void flash(int[] marks) {
        this.flash = marks;
    }

    /** Plan flash scorch: face marks; empty if uncomputed or no charring. */
    public int[] flash() {
        return flash == null ? NONE : flash;
    }

    /** Wave-blown flash-layer fires (plan-building thread). */
    void blown(int[] cells) {
        java.util.Arrays.sort(cells);
        this.blown = cells;
    }

    /** Wave extinguishes flash-layer fire at {@code key}: excluded from detonation fire count. */
    boolean isBlown(int key) {
        return blown != null && java.util.Arrays.binarySearch(blown, key) >= 0;
    }

    /** Position (without state) among plan fires. */
    boolean hasFire(int key) {
        int[] keys = fireKeys;
        if (keys == null) {
            keys = new int[fires.length];
            for (int k = 0; k < keys.length; k++) keys[k] = fires[k] & KEY_MASK;
            java.util.Arrays.sort(keys);
            fireKeys = keys;
        }
        return java.util.Arrays.binarySearch(keys, key) >= 0;
    }

    /** Plan position (without state/flags): index in {@link #cells}, otherwise negative. */
    private int find(int key) {
        int lo = 0, hi = cells.length - 1;
        while (lo <= hi) {
            int m = (lo + hi) >>> 1, k = cells[m] & KEY_MASK;
            if (k < key) lo = m + 1;
            else if (k > key) hi = m - 1;
            else return m;
        }
        return -1;
    }

    /** Section position/index without state/flags. */
    static final int KEY_MASK = (1 << STATE_SHIFT) - 1;

    /**
     * Pre-flash state from {@code live} in chunk bearing this layer: layer fire is air, layer
     * new state restores old; otherwise unchanged (post-flash edits stale ruin plan there).
     */
    BlockState beforeFlash(int key, BlockState live) {
        if (live.getBlock() instanceof net.minecraft.world.level.block.BaseFireBlock && hasFire(key)) live = Blocks.AIR.defaultBlockState();
        int k = find(key);
        return k >= 0 && live == states[(cells[k] >>> STATE_SHIFT) & STATE_MASK] ? olds[k] : live;
    }

    /** Whether layer has positions/fires in section {@code i} (server thread). */
    boolean touches(int i) {
        long[] bits = sectionBits;
        if (bits == null) {
            int top = 0;
            for (int c : cells) top = Math.max(top, section(c));
            for (int c : fires) top = Math.max(top, section(c));
            bits = new long[(top >> 6) + 1];
            for (int c : cells) set(bits, section(c));
            for (int c : fires) set(bits, section(c));
            sectionBits = bits;
        }
        return i >= 0 && (i >> 6) < bits.length && bit(bits, i);
    }

    /**
     * Pre-flash world in chunk copy (ruin snapshot, {@link ChunkShot}): layer fire becomes air, positions matching layer's new
     * state restore old; layer-column heightmaps/sky sources rescanned from copy ({@link #rescanCopy}): layer plan's
     * “after” values omit its fires (fire raises world surface a block), invalid if chunk edited
     * since layer snapshot. Touched sections ({@link #touches}) get own copies ({@link #copyStates}); null
     * means air section. Server thread.
     */
    void unflash(PalettedContainer<BlockState>[] sections, int minY, int[] shotHeights, @Nullable int[] sky) {
        BlockState air = Blocks.AIR.defaultBlockState();
        int[] top = new int[256];
        java.util.Arrays.fill(top, Integer.MIN_VALUE);
        for (int c : fires) {
            int column = c & 0xFF;
            top[column] = Math.max(top[column], minY + (section(c) << 4) + ((c >> 8) & 15));
            PalettedContainer<BlockState> s = sections[section(c)];
            if (s != null && s.get(c & 15, (c >> 8) & 15, (c >> 4) & 15).getBlock() instanceof net.minecraft.world.level.block.BaseFireBlock) {
                s.getAndSetUnchecked(c & 15, (c >> 8) & 15, (c >> 4) & 15, air);
            }
        }
        for (int k = 0; k < cells.length; k++) {
            int c = cells[k], i = section(c), column = c & 0xFF;
            top[column] = Math.max(top[column], minY + (i << 4) + ((c >> 8) & 15));
            PalettedContainer<BlockState> s = sections[i];
            BlockState now = s == null ? air : s.get(c & 15, (c >> 8) & 15, (c >> 4) & 15);
            if (now != states[(c >>> STATE_SHIFT) & STATE_MASK]) continue;
            if (s == null) {
                if (olds[k].isAir()) continue;
                sections[i] = s = new PalettedContainer<>(Block.BLOCK_STATE_REGISTRY, air, PalettedContainer.Strategy.SECTION_STATES);
            }
            s.getAndSetUnchecked(c & 15, (c >> 8) & 15, (c >> 4) & 15, olds[k]);
        }
        rescanCopy(sections, minY, shotHeights, sky, top);
    }

    /**
     * Detonation flash layer present ({@link FlashScorch#marked}) but plan absent from memory (ruins forgotten,
     * server restart): fire in owned section copies ({@code own}) becomes air, fire-column heightmaps rescanned from copy.
     * Server thread.
     *
     * @return whether copies contained fire
     */
    static boolean unfire(PalettedContainer<BlockState>[] sections, boolean[] own, int minY, int[] heights, @Nullable int[] sky) {
        BlockState air = Blocks.AIR.defaultBlockState();
        int[] top = new int[256];
        java.util.Arrays.fill(top, Integer.MIN_VALUE);
        boolean any = false;
        for (int i = 0; i < sections.length; i++) {
            PalettedContainer<BlockState> s = sections[i];
            if (s == null || !own[i]) continue;
            for (int k = 0; k < 4096; k++) {
                int x = k & 15, y = k >> 8, z = (k >> 4) & 15;
                if (!(s.get(x, y, z).getBlock() instanceof net.minecraft.world.level.block.BaseFireBlock)) continue;
                s.getAndSetUnchecked(x, y, z, air);
                top[z << 4 | x] = Math.max(top[z << 4 | x], minY + (i << 4) + y);
                any = true;
            }
        }
        if (any) rescanCopy(sections, minY, heights, sky, top);
        return any;
    }

    /**
     * Heightmaps ({@code type * 256 + column}: first free) and lower sky sources of edited section-copy columns
     * computed from copy blocks, same rule as plan ({@code RuinPlanner.Sections.heights}): {@code top} is highest
     * edited column position ({@code Integer.MIN_VALUE} unedited). Copy unchanged above, so values
     * above remain, others searched downward from it. Server-thread property table ({@link Blast#SERVER}).
     */
    private static void rescanCopy(PalettedContainer<BlockState>[] sections, int minY, int[] heights, @Nullable int[] sky, int[] top) {
        for (int column = 0; column < 256; column++) {
            int t = top[column];
            if (t == Integer.MIN_VALUE) continue;
            int lx = column & 15, lz = column >> 4;
            for (int type = 0; type < HEIGHTMAP_TYPES.length; type++) {
                int i = type * 256 + column;
                if (heights[i] - 1 > t) continue;
                int y = t;
                while (y >= minY && !Blast.SERVER.get(copyState(sections, minY, lx, y, lz)).opaque(type)) y--;
                heights[i] = y + 1;
            }
            if (sky == null || sky[column] > t + 1) continue;
            int source = minY - 1;
            BlockState upper = copyState(sections, minY, lx, t + 1, lz);
            for (int y = t; y >= minY - 1; y--) {
                BlockState lower = copyState(sections, minY, lx, y, lz);
                Blast.Props below = Blast.SERVER.get(lower);
                if (below.lightBlock() != 0 || Shapes.faceShapeOccludes(Blast.SERVER.get(upper).occludeDown(), below.occludeUp())) {
                    source = y + 1;
                    break;
                }
                upper = lower;
            }
            sky[column] = source;
        }
    }

    /** Section-copy position state (world y); air outside world height. */
    private static BlockState copyState(PalettedContainer<BlockState>[] sections, int minY, int lx, int y, int lz) {
        int i = (y - minY) >> 4;
        if (y < minY || i >= sections.length || sections[i] == null) return Blocks.AIR.defaultBlockState();
        return sections[i].get(lx, y & 15, lz);
    }

    /** State normalized for old-state comparison: blackout-darkened lamp is same lamp. */
    static BlockState normal(BlockState s) {
        BlockState lit = GridLights.lit(s);
        return lit != null ? lit : s;
    }

    /** Add old position state to section hash (plan position order). */
    static long mix(long h, BlockState old) {
        return mixNormal(h, normal(old));
    }

    /** Same with normalized state ({@link Blast.Props#normal}: background plan never calls {@code GridLights}). */
    static long mixNormal(long h, BlockState normal) {
        return (h ^ System.identityHashCode(normal)) * 0x9E3779B97F4A7C15L + 1;
    }

    /** For tests: differences from another plan (same positions, new/old states, fires); null if none. */
    @Nullable
    public String differs(RuinPlan o) {
        int mask = (1 << STATE_SHIFT) - 1;
        java.util.TreeMap<Integer, String> a = new java.util.TreeMap<>(), b = new java.util.TreeMap<>();
        for (int k = 0; k < cells.length; k++) a.put(cells[k] & mask, olds[k] + "→" + states[(cells[k] >>> STATE_SHIFT) & STATE_MASK]);
        for (int k = 0; k < o.cells.length; k++) b.put(o.cells[k] & mask, o.olds[k] + "→" + o.states[(o.cells[k] >>> STATE_SHIFT) & STATE_MASK]);
        int n = 0;
        StringBuilder out = new StringBuilder();
        java.util.TreeSet<Integer> keys = new java.util.TreeSet<>(a.keySet());
        keys.addAll(b.keySet());
        for (int c : keys) {
            if (java.util.Objects.equals(a.get(c), b.get(c))) continue;
            if (n++ < 4) out.append(" [x ").append(c & 15).append(" y ").append((section(c) << 4) + ((c >> 8) & 15)).append(" z ").append((c >> 4) & 15)
                    .append(": ").append(a.get(c)).append(" | ").append(b.get(c)).append(']');
        }
        // fires by position: light-pulse shadow from ruined neighbor same as before ruins
        java.util.TreeSet<Integer> fa = new java.util.TreeSet<>(), fb = new java.util.TreeSet<>();
        for (int c : fires) fa.add(c & mask);
        for (int c : o.fires) fb.add(c & mask);
        java.util.TreeSet<Integer> onlyA = new java.util.TreeSet<>(fa), onlyB = new java.util.TreeSet<>(fb);
        onlyA.removeAll(fb);
        onlyB.removeAll(fa);
        if (!onlyA.isEmpty() || !onlyB.isEmpty()) {
            out.append(" fires ").append(fa.size()).append(" | ").append(fb.size()).append(", only left ").append(onlyA.size())
                    .append(", only right ").append(onlyB.size());
        }
        // heightmaps/lower sky sources for changed columns (disk-snapshot sky sources computed from blocks)
        boolean heightsDiffer = !java.util.Arrays.equals(heights, o.heights);
        if (heightsDiffer) out.append(" heightmaps differ (").append(heights.length).append(" | ").append(o.heights.length).append(')');
        boolean biomesDiffer = biomes == null ? o.biomes != null : !biomes.same(o.biomes);
        if (biomesDiffer) out.append(" biomes ").append(biomes).append(" | ").append(o.biomes);
        return n == 0 && onlyA.isEmpty() && onlyB.isEmpty() && !heightsDiffer && !biomesDiffer ? null : n + " cells" + out;
    }

    /**
     * Neighbors replacement requires loaded (0 chunk alone). Radius 1 when changing block entity/POI
     * through world ({@code SLOW}) near chunk edge: chest/comparator {@code onRemove} reads neighbors
     * ({@link #EDGE} from edge; Sable block change {@link NuclearTickets#SABLE_REACH}), or chunk has
     * Sable craft ({@code SableTerrain}: neighbors read per position). Radius {@link RuinPlanner#REACH}
     * for any non-vanilla block entity removed through world: {@code onRemove} follows chains
     * (Create belt removes up to 20 blocks via {@code getBlockState}, shaft network traversed via
     * {@code getBlockEntity}), which would synchronously load neighbor.
     * Other positions written in own sections; no fluid ticks scheduled in unloaded neighbors ({@link #write}).
     */
    public int neighbourRadius(ServerLevel level, LevelChunk chunk) {
        int r = 0;
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        int x0 = chunk.getPos().getMinBlockX(), z0 = chunk.getPos().getMinBlockZ(), minY = chunk.getMinBuildHeight();
        for (int c : cells) {
            if ((c & SLOW) == 0) continue;
            BlockState old = chunk.getBlockState(at(m, c, x0, z0, minY));
            if (old.hasBlockEntity() && !"minecraft".equals(net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(old.getBlock()).getNamespace())) {
                return RuinPlanner.REACH;
            }
            int x = c & 15, z = (c >> 4) & 15;
            if (x < EDGE || x > 15 - EDGE || z < EDGE || z > 15 - EDGE) r = 1;
        }
        return r > 0 || SableTerrain.watched(level, chunk) ? 1 : 0;
    }

    /** Replacement needs neighbors ({@link #neighbourRadius}). */
    public boolean needsNeighbours(ServerLevel level, LevelChunk chunk) {
        return neighbourRadius(level, chunk) > 0;
    }

    /** Replacement needs unloaded neighbors ({@link #neighbourRadius}): awaited radius, otherwise 0. */
    public int waitsNeighbours(ServerLevel level, LevelChunk chunk) {
        int r = neighbourRadius(level, chunk);
        return r > 0 && !NuclearTickets.neighbourhoodLoaded(level, chunk.getPos(), r) ? r : 0;
    }

    /** Block-entity {@code onRemove} neighbor-read reach from chunk edge (comparator reads through block). */
    static final int EDGE = 2;

    /** Fluid ticks scheduled by plan. */
    public int fluidTickCount() {
        return fluidTicks.length;
    }

    /** Maximum fluid ticks in any scheduled second (tests): at most {@link #FLUID_TICKS}. */
    public int maxFluidTicksPerSecond() {
        it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap at = new it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap();
        int most = 0;
        for (int k = 0; k < fluidTicks.length; k++) {
            long p = fluidTicks[k];
            int second = (fluidDelay(k, BlockPos.getX(p), BlockPos.getY(p), BlockPos.getZ(p)) - FLUID_DELAY) / 20;
            most = Math.max(most, at.addTo(second, 1) + 1);
        }
        return most;
    }

    /** First fluid-tick delay after replacement, ticks. */
    private static final int FLUID_DELAY = 5;

    /**
     * Delay to plan fluid tick {@code k}: {@link #FLUID_TICKS} per second in order, within second
     * staggered by position hash.
     */
    private static int fluidDelay(int k, int x, int y, int z) {
        return FLUID_DELAY + 20 * (k / FLUID_TICKS) + (int) (RuinPlanner.hash(x, y, z, 37) * 20);
    }

    /** Plan fire count (tests). */
    public int fireCount() {
        return fires.length;
    }

    /** Plan changed-block count (excluding fires). */
    public int changedBlocks() {
        return cells.length;
    }

    /** Whether plan singes chunk biome (tests). */
    public boolean recolours() {
        return biomes != null;
    }

    /**
     * Up to {@code max} positions fully removed by wave (modeled block became air), with original block:
     * their pieces scatter for chunk viewers ({@code S2C.NukeDebris}). Chosen by smallest position hashes,
     * not plan order: spread throughout chunk, same positions for same plan. Positions already changed by flash
     * layer (flash-burned plants) do not scatter. Server thread, scans all plan positions, per position only
     * comparisons and integer mixing.
     */
    public List<S2C.NukeDebris.Piece> thrown(LevelChunk chunk, int max) {
        return debris(chunk, max).pieces();
    }

    /** Removed positions ({@link #thrown}) and total chunk count sampled from. */
    public record Debris(List<S2C.NukeDebris.Piece> pieces, int removed) {
        static final Debris NONE = new Debris(List.of(), 0);
    }

    /** {@link #thrown} with total fully wave-removed positions; returned positions sample these. */
    public Debris debris(LevelChunk chunk, int max) {
        if (cells.length == 0 || max <= 0) return Debris.NONE;
        ChunkPos pos = chunk.getPos();
        int x0 = pos.getMinBlockX(), z0 = pos.getMinBlockZ(), minY = chunk.getMinBuildHeight();
        long salt = pos.toLong() * 0x9E3779B97F4A7C15L;
        int local = (1 << STATE_SHIFT) - 1;
        RuinPlan flash = flashOf(chunk);
        // best ascending hashes: plan position index and hash
        int[] best = new int[max];
        long[] keys = new long[max];
        int n = 0, removed = 0;
        for (int k = 0; k < cells.length; k++) {
            int c = cells[k];
            if (!states[(c >>> STATE_SHIFT) & STATE_MASK].isAir() || olds[k].isAir() || olds[k].getRenderShape() != RenderShape.MODEL) continue;
            if (flash != null && flash.find(c & KEY_MASK) >= 0) continue;
            removed++;
            long key = HashCommon.mix(salt + (c & local)) >>> 1;
            if (n == max && key >= keys[n - 1]) continue;
            int i = n < max ? n++ : n - 1;
            while (i > 0 && keys[i - 1] > key) {
                keys[i] = keys[i - 1];
                best[i] = best[i - 1];
                i--;
            }
            keys[i] = key;
            best[i] = k;
        }
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        List<S2C.NukeDebris.Piece> out = new java.util.ArrayList<>(n);
        for (int i = 0; i < n; i++) out.add(new S2C.NukeDebris.Piece(at(m, cells[best[i]], x0, z0, minY).immutable(), olds[best[i]]));
        return new Debris(out, removed);
    }

    /** Approximate plan memory, bytes (for log). */
    public long bytes() {
        if (this == EMPTY) return 0;
        return 64 + 12L * cells.length + 8L * fluidTicks.length + 8L * oldHashes.length + 8L * states.length + 4L * fires.length + 4L * heights.length
                + 8L * lightAt.length + (prints == null ? 0 : 16L * prints.length) + (outside == null ? 0 : 16L * outside.size()) + (biomes == null ? 0 : 96)
                + (flash == null ? 0 : 4L * flash.length) + (blown == null ? 0 : 4L * blown.length);
    }

    private static int section(int cell) {
        return (cell >>> SECTION_SHIFT) & 0xFF;
    }

    /**
     * Detonation {@code d} plan positions still same as at planning. Plan uses pre-flash world:
     * where detonation flash layer present ({@link RuinContext#flashed}), compare pre-layer world
     * ({@link #beforeFlash}); layer marked without memory plan ({@link FlashScorch#marked}) means fire read
     * as air, like snapshot of such chunk ({@link #unfire}).
     */
    boolean current(LevelChunk chunk, Detonation d) {
        RuinPlan flash = flashOf(chunk);
        return current(chunk, flash, flash == null && FlashScorch.marked(chunk, d.id()));
    }

    /** Chunk flash layer for checking this ruin plan (null for layer itself). */
    @Nullable
    private RuinPlan flashOf(LevelChunk chunk) {
        RuinPlan f = ctx == null ? null : ctx.flashed(chunk.getPos().toLong());
        return f == this ? null : f;
    }

    private boolean current(LevelChunk chunk, @Nullable RuinPlan flash, boolean marked) {
        LevelChunkSection[] now = chunk.getSections();
        int k = 0;
        while (k < cells.length) {
            int i = section(cells[k]);
            if (i >= now.length) return false;
            LevelChunkSection s = now[i];
            long h = 0;
            for (; k < cells.length && section(cells[k]) == i; k++) {
                int c = cells[k];
                BlockState live = s.getBlockState(c & 15, (c >> 8) & 15, (c >> 4) & 15);
                if (flash != null) live = flash.beforeFlash(c & KEY_MASK, live);
                else if (marked && fire(live)) live = Blocks.AIR.defaultBlockState();
                h = mix(h, live);
            }
            if (h != oldHashes[i]) return false;
        }
        return true;
    }

    private static boolean fire(BlockState s) {
        return s.getBlock() instanceof net.minecraft.world.level.block.BaseFireBlock;
    }

    /**
     * Ruins in outside-world disk chunk copy for distant Distant Horizons LODs ({@link FarLods}): all plan positions
     * (including world-path, copy has nothing to wake), optional {@code fires}, without detonation fire counter; copy
     * does not consume it. Any thread, calling
     * only copy sections. No darkened lamp twins on disk ({@code ChunkSaves}), so old-state hash uses
     * raw states.
     *
     * @return biome cells changed ({@link ScorchedBiomes#write}); −1 if plan positions differ (chunk
     *          edited since plan), copy invalid
     */
    int applyToCopy(LevelChunkSection[] sections, boolean fires, net.minecraft.core.Registry<net.minecraft.world.level.biome.Biome> biomeRegistry,
                        int minSection) {
        int k = 0;
        while (k < cells.length) {
            int i = section(cells[k]);
            if (i >= sections.length) return -1;
            LevelChunkSection s = sections[i];
            long h = 0;
            for (; k < cells.length && section(cells[k]) == i; k++) {
                int c = cells[k];
                h = mixNormal(h, s.getBlockState(c & 15, (c >> 8) & 15, (c >> 4) & 15));
            }
            if (h != oldHashes[i]) return -1;
        }
        for (int c : cells) sections[section(c)].setBlockState(c & 15, (c >> 8) & 15, (c >> 4) & 15, states[(c >>> STATE_SHIFT) & STATE_MASK], false);
        for (int c : fires ? this.fires : NONE) {
            LevelChunkSection s = sections[section(c)];
            if (s.getBlockState(c & 15, (c >> 8) & 15, (c >> 4) & 15).isAir()) {
                s.setBlockState(c & 15, (c >> 8) & 15, (c >> 4) & 15, states[(c >>> STATE_SHIFT) & STATE_MASK], false);
            }
        }
        return biomes == null ? 0 : ScorchedBiomes.write(biomeRegistry, sections, minSection, biomes);
    }

    /**
     * For tests: chunk section copies (with biomes) as this plan alone would produce, all positions/fires where
     * air, without fire counter ({@link #applyToCopy}).
     */
    public LevelChunkSection[] testAppliedCopy(ServerLevel level, LevelChunk chunk) {
        LevelChunkSection[] live = chunk.getSections(), out = new LevelChunkSection[live.length];
        for (int i = 0; i < live.length; i++) out[i] = copyOf(live[i]);
        if (this != EMPTY && applyToCopy(out, true, level.registryAccess().registryOrThrow(net.minecraft.core.registries.Registries.BIOME), chunk.getMinSection()) < 0) {
            throw new IllegalStateException("plan does not fit chunk " + chunk.getPos());
        }
        return out;
    }

    /**
     * All chunk blocks match plan snapshot (server thread): per section compare raw fingerprint; where
     * different (rebuilt palette, blackout-darkened lamp), compare positions allowing darkened lamps. If true,
     * plan heightmaps/sky sources valid; otherwise rescan plan columns from chunk ({@link #rescanColumns}).
     */
    public boolean sameBlocks(LevelChunk chunk) {
        if (prints == null || normals == null) return false;
        LevelChunkSection[] live = chunk.getSections();
        if (live.length != prints.length) return false;
        for (int i = 0; i < live.length; i++) {
            if (ChunkShot.rawPrint(live[i]) != prints[i] && ChunkShot.normalPrint(live[i]) != normals[i]) return false;
        }
        return true;
    }

    /**
     * Old chunk state before these ruins (world y), null outside plan. For neighbors planning
     * from original world while this chunk's ruins stand.
     */
    @Nullable
    BlockState oldState(int lx, int y, int lz, int minY) {
        int i = (y - minY) >> 4;
        if (i < 0 || i >= oldHashes.length || oldHashes[i] == 0) return null;
        int key = i << SECTION_SHIFT | (y & 15) << 8 | lz << 4 | lx;
        int lo = 0, hi = cells.length - 1;
        while (lo <= hi) {
            int m = (lo + hi) >>> 1, k = cells[m] & (1 << STATE_SHIFT) - 1;
            if (k < key) lo = m + 1;
            else if (k > key) hi = m - 1;
            else return olds[m];
        }
        return null;
    }

    /** Each old plan-position state (with duplicates). */
    void forEachOld(java.util.function.Consumer<BlockState> each) {
        for (BlockState o : olds) each.accept(o);
    }

    /** Whether old states of {@code section} plan positions include one satisfying {@code test}. */
    boolean anyOld(int section, java.util.function.Predicate<BlockState> test) {
        if (section < 0 || section >= oldHashes.length || oldHashes[section] == 0) return false;
        for (int k = 0; k < cells.length; k++) if (section(cells[k]) == section && test.test(olds[k])) return true;
        return false;
    }

    /** Column tops from old plan blocks: [0..255] non-air, [256..511] support without leaves (or liquid); MIN absent. */
    int[] oldTops(int minY) {
        int[] t = oldTops;
        if (t != null) return t;
        t = new int[512];
        java.util.Arrays.fill(t, Integer.MIN_VALUE);
        for (int k = 0; k < cells.length; k++) {
            int c = cells[k], column = c & 0xFF, y = minY + (section(c) << 4) + ((c >> 8) & 15);
            BlockState o = olds[k];
            if (!o.isAir()) t[column] = Math.max(t[column], y);
            if (Heightmap.Types.MOTION_BLOCKING_NO_LEAVES.isOpaque().test(o)) t[256 + column] = Math.max(t[256 + column], y);
        }
        return oldTops = t;
    }

    @Nullable
    private int[] oldTops;

    /**
     * Apply ruins (chunk/neighbors fully loaded, caller checks). Stale plan not applied. Where detonation
     * flash layer present ({@link FlashScorch}), overwrite with ruins ({@link #write}): layer fire absent
     * from ruin fires (wave-blown, buried, support removed) extinguished, remainder continues and
     * receives first tick only now; chunk layer mark cleared.
     *
     * @param d detonation owning ruins: flash scorch uses its layer. Not {@code ctx.d}: prebuilt ruins
     *           use geometry without ID/time ({@code NuclearWarhead.geometry}), while scorch layer must match
     *           detonation silhouettes and flash time
     * @return false if stale, nothing changed
     */
    public boolean apply(ServerLevel level, LevelChunk chunk, Detonation d, ColumnScar.Budget budget) {
        if (this == EMPTY) {
            // ruins change no blocks, layer ignited fire: wave extinguishes all (otherwise remains unticked)
            if (FlashScorch.marked(chunk, d.id())) {
                RuinContext kept = NuclearWorld.get(level).ruinsIfKept(d.id());
                RuinPlan flash = kept == null ? null : kept.flashed(chunk.getPos().toLong());
                write(level, chunk, budget, System.nanoTime(), false, flash, flash == null, false);
                FlashScorch.unmark(chunk, d.id());
            }
            return true;
        }
        long t = System.nanoTime();
        RuinPlan flash = flashOf(chunk);
        boolean marked = flash == null && FlashScorch.marked(chunk, d.id());
        if (!current(chunk, flash, marked)) return false;
        short[] motion = new short[256];
        for (int column = 0; column < 256; column++) motion[column] = (short) (chunk.getHeight(Heightmap.Types.MOTION_BLOCKING, column & 15, column >> 4) + 1);
        // biome before blocks: Distant Horizons LOD reads chunk with biome after position writes
        boolean recoloured = biomes != null && ScorchedBiomes.write(level, chunk, biomes);
        if (cells.length > 0 || fires.length > 0 || outside != null || fluidTicks.length > 0 || flash != null || marked) {
            // blocks outside plan positions checked before writes: afterward chunk different. Flash layer makes chunk differ
            // from pre-flash plan snapshot, so rescan plan columns from chunk
            write(level, chunk, budget, t, flash == null && !marked && cells.length > 0 && sameBlocks(chunk), flash, marked, false);
        } else if (recoloured) {
            DhUpdates.mark(level, chunk.getPos(), level.getGameTime());
        }
        if (ctx != null) ctx.applied(chunk, this, motion);
        FlashScorch.unmark(chunk, d.id());
        // flash scorch with ruins (layer already applied: replace with surviving scorch); old flash traces
        // on changed blocks removed
        if (ctx != null) ua.zentix.incoming.nuclear.flash.FlashSync.chars(level, chunk, d, flash());
        return true;
    }

    /**
     * Apply flash layer ({@link RuinPlanner#flash}) at flash, before wave ({@link FlashScorch}; server
     * thread, chunk fully loaded, neighbors too near Sable craft): positions/fires directly into sections like ruins; players
     * get same-tick section packets, Distant Horizons LODs updated, face scorch in {@code d} layer. No fire ticks
     * scheduled: before ruins fire neither grows, spreads nor dies, so pre-flash ruin-plan positions
     * remain valid whenever ruins applied; {@link #apply} schedules continuing fire. Only wave-surviving fires enter
     * detonation count ({@link #blown}); wave-blown ones extinguished before first tick. Layer recorded in detonation
     * ruins ({@link RuinContext#flashed}) for chunk validation/pre-flash snapshots; if
     * fire ignited, also chunk mark ({@link FlashScorch#mark}): ruins built without layer plan (after
     * restart) know chunk fire belongs to layer.
     *
     * @return fires ignited (including wave-blown); −1 stale layer (positions changed since snapshot): nothing
     *          changed
     */
    int applyFlash(ServerLevel level, LevelChunk chunk, Detonation d, ColumnScar.Budget budget) {
        if (this == EMPTY || ctx == null) return 0;
        long t = System.nanoTime();
        if (!current(chunk, null, false)) return -1;
        boolean shotCurrent = ctx.beforeFlash(level, chunk);
        boolean recoloured = biomes != null && ScorchedBiomes.write(level, chunk, biomes);
        int lit = 0;
        if (cells.length > 0 || fires.length > 0) {
            lit = write(level, chunk, budget, t, cells.length > 0 && sameBlocks(chunk), null, false, true);
        } else if (recoloured) {
            DhUpdates.mark(level, chunk.getPos(), level.getGameTime());
        }
        ctx.flashed(chunk, this, shotCurrent);
        if (lit > 0) FlashScorch.mark(chunk, d.id());
        ua.zentix.incoming.nuclear.flash.FlashSync.chars(level, chunk, d, flash());
        return lit;
    }

    /**
     * @param untouched chunk blocks same as plan snapshot ({@link #sameBlocks})
     * @param flash     applied chunk flash layer (ruins over it), otherwise null
     * @param marked    layer marked without memory plan ({@link FlashScorch#marked}): chunk fire belongs to it
     * @param layer     writing flash layer itself: no fire ticks, only wave-surviving fires counted
     * @return fires ignited
     */
    private int write(ServerLevel level, LevelChunk chunk, ColumnScar.Budget budget, long t, boolean untouched, @Nullable RuinPlan flash,
                      boolean marked, boolean layer) {
        PHASES[0] += System.nanoTime() - t;
        t = System.nanoTime();
        ChunkPos pos = chunk.getPos();
        int x0 = pos.getMinBlockX(), z0 = pos.getMinBlockZ(), minY = chunk.getMinBuildHeight();
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        BlockState air = Blocks.AIR.defaultBlockState();
        // plan positions directly in sections like setBlock (also changes section in place while light thread reads
        // chunk), without per-block overhead; block entities/POIs through world afterward (onRemove:
        // contents, chest halves, Create belts)
        LevelChunkSection[] now = chunk.getSections();
        // changed sections and previous emptiness (bit per section, can exceed 64 sections)
        long[] touched = new long[(now.length + 63) >> 6], wasEmpty = new long[touched.length];
        // ruins over flash layer: layer fire present in plan positions/ruin fires or extinguished
        boolean over = flash != null || marked;
        boolean ignites = budget.ignites && IncomingConfig.SERVER.nukeFires.get();
        int maxFires = IncomingConfig.SERVER.nukeMaxFires.get();
        // Sable sees state immediately before write (over layer, not plan's pre-flash world)
        boolean sable = SableTerrain.present() && SableTerrain.watched(level, chunk);
        BlockState[] was = sable ? new BlockState[cells.length] : null;
        // extinguished layer fire (rubble, breach, wave-blown) needs light check, since fire emits light
        LongArrayList quenched = null;
        // ruins over layer without plan cannot identify counted chunk fires: return all layer counter took,
        // then count continuing fire (fresh post-restart counter took none). With layer plan, per position
        int layerCounted = budget.layer.remove(pos.toLong());
        if (marked) budget.fires = Math.max(0, budget.fires - layerCounted);
        for (int k = 0; k < cells.length; k++) {
            int c = cells[k];
            if ((c & SLOW) != 0) continue;
            int i = section(c);
            LevelChunkSection s = now[i];
            BlockState state = states[(c >>> STATE_SHIFT) & STATE_MASK];
            BlockState live = s.getBlockState(c & 15, (c >> 8) & 15, (c >> 4) & 15);
            if (over && fire(live)) {
                // layer fire where plan says air with ruin fire (burned grass) continues, handled by fires below
                if (state.isAir() && ignites && hasFire(c & KEY_MASK)) continue;
                if (quenched == null) quenched = new LongArrayList();
                quenched.add(at(m, c, x0, z0, minY).asLong());
                if (flash != null && flash.counted(c & KEY_MASK)) budget.fires = Math.max(0, budget.fires - 1);
            }
            touch(touched, wasEmpty, now, i);
            s.setBlockState(c & 15, (c >> 8) & 15, (c >> 4) & 15, state, false);
            if (was != null) was[k] = live;
        }
        // fires where plan says air, until detonation fire counter exhausted; layer fire on ruin-fire position
        // continues (ruin type: faces toward post-wave flammable neighbors)
        int lit = 0, n = 0;
        int[] burning = fires.length == 0 || !ignites ? NONE : new int[fires.length];
        BlockState[] fireWas = burning.length == 0 ? NO_STATES : new BlockState[burning.length];
        for (int k = 0; k < burning.length; k++) {
            int c = fires[k];
            int i = section(c);
            LevelChunkSection s = now[i];
            BlockState fire = states[(c >>> STATE_SHIFT) & STATE_MASK];
            BlockState live = s.getBlockState(c & 15, (c >> 8) & 15, (c >> 4) & 15);
            if (!live.isAir()) {
                if (!over || !fire(live)) continue;
                // layer fire excluded from detonation count (layer predicts wave-blown, or no plan) but retained by ruins
                // now enters count
                if (flash == null || !flash.counted(c & KEY_MASK)) budget.fires++;
                if (live != fire) {
                    touch(touched, wasEmpty, now, i);
                    s.setBlockState(c & 15, (c >> 8) & 15, (c >> 4) & 15, fire, false);
                }
            } else {
                if (budget.fires >= maxFires) continue;
                touch(touched, wasEmpty, now, i);
                s.setBlockState(c & 15, (c >> 8) & 15, (c >> 4) & 15, fire, false);
                lit++;
                if (!layer || !isBlown(c & KEY_MASK)) {
                    budget.fires++;
                    if (layer) layerCounted++;
                }
            }
            fireWas[n] = live;
            burning[n++] = c;
        }
        if (layer && layerCounted > 0) budget.layer.put(pos.toLong(), layerCounted);
        // layer fire absent from both plan positions and ruin fires (wave-blown, support removed) extinguished
        LongArrayList out = null;
        List<BlockState> outWas = null;
        if (over) {
            Heightmap surface = chunk.getOrCreateHeightmapUnprimed(Heightmap.Types.WORLD_SURFACE);
            for (int f : flash != null ? flash.fires : fireCells(now)) {
                int key = f & KEY_MASK, i = section(f);
                if (i >= now.length) continue;
                LevelChunkSection s = now[i];
                BlockState live = s.getBlockState(f & 15, (f >> 8) & 15, (f >> 4) & 15);
                if (!fire(live) || find(key) >= 0 || ignites && hasFire(key)) continue;
                touch(touched, wasEmpty, now, i);
                s.setBlockState(f & 15, (f >> 8) & 15, (f >> 4) & 15, air, false);
                at(m, f, x0, z0, minY);
                // fire blocks neither motion nor sky: only world-surface heightmap changes
                surface.update(f & 15, m.getY(), (f >> 4) & 15, air);
                if (quenched == null) quenched = new LongArrayList();
                quenched.add(m.asLong());
                if (out == null) {
                    out = new LongArrayList();
                    outWas = new java.util.ArrayList<>();
                }
                out.add(m.asLong());
                outWas.add(live);
                if (flash != null && flash.counted(key)) budget.fires = Math.max(0, budget.fires - 1);
            }
        }
        // Sable keeps world-block copies near craft, observes only LevelChunk.setBlockState: notify near craft
        if (sable) {
            for (int k = 0; k < cells.length; k++) {
                if (was[k] == null) continue;
                int c = cells[k];
                at(m, c, x0, z0, minY);
                SableTerrain.changed(level, chunk, m.getX(), m.getY(), m.getZ(), was[k], states[(c >>> STATE_SHIFT) & STATE_MASK]);
            }
            for (int k = 0; k < n; k++) {
                at(m, burning[k], x0, z0, minY);
                SableTerrain.changed(level, chunk, m.getX(), m.getY(), m.getZ(), fireWas[k], states[(burning[k] >>> STATE_SHIFT) & STATE_MASK]);
            }
            for (int k = 0; out != null && k < out.size(); k++) {
                m.set(out.getLong(k));
                SableTerrain.changed(level, chunk, m.getX(), m.getY(), m.getZ(), outWas.get(k), air);
            }
        }
        LevelLightEngine light = level.getChunkSource().getLightEngine();
        for (int i = 0; i < now.length; i++) {
            if (!bit(touched, i)) continue;
            boolean empty = now[i].hasOnlyAir();
            if (bit(wasEmpty, i) != empty) light.updateSectionStatus(SectionPos.of(pos, chunk.getSectionYFromSectionIndex(i)), empty);
        }
        PHASES[1] += System.nanoTime() - t;
        t = System.nanoTime();
        // heightmaps/sky sources from plan; chunk edited since plan outside plan positions (block inside house), or
        // column “before” value differs, so rescan all plan columns from chunk
        if (!untouched || !setHeights(chunk)) rescanColumns(chunk);
        if (lit > 0) {
            // fire is not air but blocks neither motion nor sky: only world-surface heightmap changes
            Heightmap surface = chunk.getOrCreateHeightmapUnprimed(Heightmap.Types.WORLD_SURFACE);
            for (int k = 0; k < n; k++) {
                if (!fireWas[k].isAir()) continue;
                int c = burning[k];
                surface.update(c & 15, minY + (section(c) << 4) + ((c >> 8) & 15), (c >> 4) & 15, states[(c >>> STATE_SHIFT) & STATE_MASK]);
            }
        }
        PHASES[2] += System.nanoTime() - t;
        t = System.nanoTime();
        // chunk viewers get changes same tick via section packets (like setBlock), not chunk queue
        ChunkHolder holder = level.getChunkSource().chunkMap.getVisibleChunkIfPresent(pos.toLong());
        List<ServerPlayer> players = holder == null ? List.of() : level.getChunkSource().chunkMap.getPlayers(pos, false);
        boolean watched = !players.isEmpty();
        boolean sections = watched && holder.getTickingChunk() != null;
        if (sections) {
            for (int c : cells) if ((c & SLOW) == 0) holder.blockChanged(at(m, c, x0, z0, minY));
            for (int k = 0; out != null && k < out.size(); k++) holder.blockChanged(m.set(out.getLong(k)));
        }
        int extra = quenched == null ? 0 : quenched.size();
        long[] checks = java.util.Arrays.copyOf(lightAt, lightAt.length + lit + extra);
        int checked = lightAt.length;
        for (int k = 0; k < extra; k++) checks[checked++] = quenched.getLong(k);
        for (int k = 0; k < n; k++) {
            int c = burning[k];
            BlockState fire = states[(c >>> STATE_SHIFT) & STATE_MASK];
            at(m, c, x0, z0, minY);
            if (fireWas[k].isAir()) checks[checked++] = m.asLong();
            if (sections && fireWas[k] != fire) holder.blockChanged(m);
            // fire tick like newly placed fire (FireBlock.onPlace): 30–39 ticks, flash-layer fire starts with ruins
            if (!layer) level.scheduleTick(m.immutable(), fire.getBlock(), 30 + (int) (RuinPlanner.hash(m.getX(), m.getY(), m.getZ(), 31) * 10));
        }
        checkLight(light, pos, checked == checks.length ? checks : java.util.Arrays.copyOf(checks, checked));
        // fluid beside breach (neighbors too) gets tick like neighbor setBlock, staggered by position hash, at most
        // FLUID_TICKS per second: replacement wakes no neighbors, unticked water would stand as wall
        for (int k = 0; k < fluidTicks.length; k++) {
            m.set(fluidTicks[k]);
            // neighbor unloaded (ready plan needs no neighbors): no tick; chunk load does not schedule one, so water
            // stands until nearby block update
            if (!Terrain.ready(level, m)) continue;
            BlockState st = level.getBlockState(m);
            if (st.getFluidState().isEmpty()) continue;
            level.scheduleTick(m.immutable(), st.getFluidState().getType(), fluidDelay(k, m.getX(), m.getY(), m.getZ()));
        }
        PHASES[3] += System.nanoTime() - t;
        t = System.nanoTime();
        chunk.setUnsaved(true);
        // log chunk's longest block: onRemove may be costly (entire Create shaft network traversed,
        // belt removes whole chain)
        BlockState slowBlock = null;
        long slowBlockNanos = 0;
        for (int c : cells) {
            if ((c & SLOW) == 0) continue;
            at(m, c, x0, z0, minY);
            BlockState old = chunk.getBlockState(m);
            long b = System.nanoTime();
            ColumnScar.replace(level, m, old, states[(c >>> STATE_SHIFT) & STATE_MASK]);
            b = System.nanoTime() - b;
            if (b > slowBlockNanos) {
                slowBlockNanos = b;
                slowBlock = old;
            }
        }
        // viewed but non-ticking chunk (view edge): send full chunk via vanilla
        if (watched && !sections) {
            for (ServerPlayer p : players) p.connection.chunkSender.markChunkPendingToSend(chunk);
        }
        // Distant Horizons LOD: ruins with wave, not next chunk save
        long dh = System.nanoTime();
        DhUpdates.mark(level, pos, level.getGameTime());
        long took = System.nanoTime() - t;
        dh = System.nanoTime() - dh;
        PHASES[4] += took;
        if (took > slowestWorldNanos) {
            slowestWorldNanos = took;
            slowestWorldChunk = pos.toLong();
            int slow = 0;
            for (int c : cells) if ((c & SLOW) != 0) slow++;
            slowestWorldCells = slow;
            slowestWorldBlock = slowBlock;
            slowestWorldBlockNanos = slowBlockNanos;
            slowestWorldDhNanos = dh;
        }
        return lit;
    }

    /** Section {@code i} changes: mark it and pre-write emptiness. */
    private static void touch(long[] touched, long[] wasEmpty, LevelChunkSection[] now, int i) {
        if (bit(touched, i)) return;
        set(touched, i);
        if (now[i].hasOnlyAir()) set(wasEmpty, i);
    }

    /** Layer fire at {@code key} counted toward detonation fires: layer ignited it, wave does not extinguish it. */
    private boolean counted(int key) {
        return hasFire(key) && !isBlown(key);
    }

    /** Chunk-section fire positions (like {@link #fires}, no state): layer fire with forgotten plan. Server thread. */
    private static int[] fireCells(LevelChunkSection[] sections) {
        it.unimi.dsi.fastutil.ints.IntArrayList out = new it.unimi.dsi.fastutil.ints.IntArrayList();
        for (int i = 0; i < sections.length; i++) {
            LevelChunkSection s = sections[i];
            if (s.hasOnlyAir() || !ua.zentix.incoming.util.Palettes.contains(s.getStates(), RuinPlan::fire)) continue;
            for (int k = 0; k < 4096; k++) if (fire(s.getBlockState(k & 15, k >> 8, (k >> 4) & 15))) out.add(i << SECTION_SHIFT | k);
        }
        return out.toIntArray();
    }

    /**
     * Chunk light checks in one light-engine task (like {@code LevelLightEngine.checkBlock} on its thread), not
     * one mailbox task per position: hundreds per chunk, thousands of chunks within seconds. Non-vanilla light engine
     * (another mod) uses individual {@code checkBlock} calls.
     */
    private static void checkLight(LevelLightEngine light, ChunkPos pos, long[] at) {
        if (at.length == 0) return;
        // block engine always present; absent means another mod computes light (Starlight, ScalableLux): task would be empty
        if (light instanceof ThreadedLevelLightEngine threaded && light.blockEngine != null && vanilla(light.blockEngine, BlockLightEngine.class)
                && vanilla(light.skyEngine, SkyLightEngine.class)) {
            var block = light.blockEngine;
            var sky = light.skyEngine;
            threaded.addTask(pos.x, pos.z, ThreadedLevelLightEngine.TaskType.PRE_UPDATE, () -> {
                BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
                for (long a : at) {
                    p.set(a);
                    block.checkBlock(p);
                    if (sky != null) sky.checkBlock(p);
                }
            });
            return;
        }
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        for (long a : at) light.checkBlock(p.set(a));
    }

    /**
     * Column heightmaps/sky sources from plan. Live pre-ruin value differs (column edited since plan):
     * false, nothing changed; caller rescans plan columns from chunk.
     */
    private boolean setHeights(LevelChunk chunk) {
        ChunkSkyLightSources sky = chunk.getSkyLightSources();
        for (int k = 0; k < heights.length; k += 3) {
            int key = heights[k], column = key >> 3, type = key & 7;
            int was = type == SKY ? sky.get(column) : chunk.getOrCreateHeightmapUnprimed(HEIGHTMAP_TYPES[type]).getFirstAvailable(column & 15, column >> 4);
            if (was != heights[k + 1]) return false;
        }
        for (int k = 0; k < heights.length; k += 3) {
            int key = heights[k], column = key >> 3, type = key & 7;
            if (type == SKY) sky.set(column, heights[k + 2]);
            else chunk.getOrCreateHeightmapUnprimed(HEIGHTMAP_TYPES[type]).setHeight(column & 15, column >> 4, heights[k + 2]);
        }
        return true;
    }

    /**
     * Heightmaps/lower sky sources of all plan-position columns from chunk after writes, like
     * {@code Heightmap.primeHeightmaps} and {@code ChunkSkyLightSources.fillFrom}, one pass per column for all
     * maps/sky, only downward from highest plan position: replacement changed nothing above, live world value
     * there valid. Chunk edited since plan (roof above gutted house broken, lowering height to a floor
     * removed by plan), so plan values may be stale. Columns without plan positions untouched, world
     * maintained them; world-path block entities/POIs changed afterward.
     */
    private void rescanColumns(LevelChunk chunk) {
        int minY = chunk.getMinBuildHeight();
        int[] top = new int[256];
        java.util.Arrays.fill(top, Integer.MIN_VALUE);
        for (int c : cells) {
            if ((c & SLOW) != 0) continue;
            int y = minY + (section(c) << 4) + ((c >> 8) & 15);
            if (y > top[c & 0xFF]) top[c & 0xFF] = y;
        }
        ChunkSkyLightSources sky = chunk.getSkyLightSources();
        Heightmap[] maps = new Heightmap[HEIGHTMAP_TYPES.length];
        for (int i = 0; i < maps.length; i++) maps[i] = chunk.getOrCreateHeightmapUnprimed(HEIGHTMAP_TYPES[i]);
        int x0 = chunk.getPos().getMinBlockX(), z0 = chunk.getPos().getMinBlockZ();
        BlockPos.MutableBlockPos upper = new BlockPos.MutableBlockPos(), lower = new BlockPos.MutableBlockPos();
        for (int column = 0; column < 256; column++) {
            int t = top[column];
            if (t == Integer.MIN_VALUE) continue;
            int lx = column & 15, lz = column >> 4, x = x0 + lx, z = z0 + lz;
            // search values at/below highest plan position (above belongs to unchanged block over plan)
            int open = 0;
            for (int i = 0; i < maps.length; i++) if (maps[i].getFirstAvailable(lx, lz) <= t + 1) open |= 1 << i;
            boolean skyOpen = sky.get(column) <= t + 1;
            BlockState above = chunk.getBlockState(upper.set(x, t + 1, z));
            for (int y = t; y >= minY - 1 && (open != 0 || skyOpen); y--) {
                BlockState below = chunk.getBlockState(lower.set(x, y, z));
                for (int i = 0; open != 0 && i < maps.length; i++) {
                    if ((open & 1 << i) != 0 && y >= minY && HEIGHTMAP_TYPES[i].isOpaque().test(below)) {
                        maps[i].setHeight(lx, lz, y + 1);
                        open &= ~(1 << i);
                    }
                }
                if (skyOpen) {
                    upper.set(x, y + 1, z);
                    if (below.getLightBlock(chunk, lower) != 0 || Shapes.faceShapeOccludes(LightEngine.getOcclusionShape(chunk, upper, above, Direction.DOWN),
                            LightEngine.getOcclusionShape(chunk, lower, below, Direction.UP))) {
                        sky.set(column, y + 1);
                        skyOpen = false;
                    }
                }
                above = below;
            }
            for (int i = 0; i < maps.length; i++) if ((open & 1 << i) != 0) maps[i].setHeight(lx, lz, minY);
            if (skyOpen) sky.set(column, minY - 1);
        }
    }

    private static boolean vanilla(@Nullable Object engine, Class<?> type) {
        return engine == null || engine.getClass() == type;
    }

    /** Trunks toppled into neighboring chunks: count. */
    public int outsideCount() {
        return outside == null ? 0 : outside.size();
    }

    public long outsidePos(int k) {
        return outside.getLong(k);
    }

    public BlockState outsideState(int k) {
        return outsideState.get(k);
    }

    /** Toppled-trunk log on neighboring chunk surface (after its ruins), unless structure there. */
    public static void placeLog(ServerLevel level, long at, BlockState log) {
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos().set(at);
        if (!Terrain.ready(level, m) || !NuclearTickets.aroundLoaded(level, m)) return;
        m.setY(level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, m.getX(), m.getZ()));
        BlockState was = level.getBlockState(m);
        if (was.canBeReplaced()) ColumnScar.replace(level, m, was, log);
    }

    private static final ThreadLocal<FriendlyByteBuf> COPY_BUFFER = ThreadLocal.withInitial(() -> new FriendlyByteBuf(Unpooled.buffer(16384)));

    /**
     * Independent section copy. Not {@code PalettedContainer.copy()}: palette retains old container's resize
     * handler (single-color palette even same object), so a new copy state rebuilds old world-section
     * data while writing foreign ID into copy (“The value 1 is not in the specified inclusive range of 0 to 0”).
     * Buffer write/read rebuilds palette with own handler.
     */
    static LevelChunkSection copyOf(LevelChunkSection section) {
        return new LevelChunkSection(copyStates(section.getStates()), section.getBiomes());
    }

    /** Independent editable section-block copy ({@link #copyOf}). */
    static PalettedContainer<BlockState> copyStates(PalettedContainer<BlockState> source) {
        FriendlyByteBuf buf = COPY_BUFFER.get();
        buf.clear();
        source.write(buf);
        PalettedContainer<BlockState> states = new PalettedContainer<>(Block.BLOCK_STATE_REGISTRY, Blocks.AIR.defaultBlockState(),
                PalettedContainer.Strategy.SECTION_STATES);
        states.read(buf);
        return states;
    }

    private static boolean bit(long[] bits, int i) {
        return (bits[i >> 6] & 1L << (i & 63)) != 0;
    }

    private static void set(long[] bits, int i) {
        bits[i >> 6] |= 1L << (i & 63);
    }

    private static BlockPos.MutableBlockPos at(BlockPos.MutableBlockPos m, int c, int x0, int z0, int minY) {
        return m.set(x0 + (c & 15), minY + (section(c) << 4) + ((c >> 8) & 15), z0 + ((c >> 4) & 15));
    }
}
