package ua.zentix.incoming.nuclear.world;

import net.minecraft.util.BitStorage;
import net.minecraft.util.ZeroBitStorage;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.BaseFireBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.chunk.GlobalPalette;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.Palette;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.levelgen.Heightmap;
import org.jetbrains.annotations.Nullable;

import java.util.concurrent.atomic.AtomicReferenceArray;

/**
 * Original chunk blocks for ruins — server-thread snapshot: section palette copies ({@code PalettedContainer.copy},
 * live sections only read), heightmaps and skylight sources; for an already ruined chunk, old blocks at its plan
 * positions. Snapshots also come from disk ({@link DiskShots}): a chunk absent from memory read from a region file in
 * the background. A chunk window ({@link RuinWindow}) and plan ({@link RuinPlanner#finish}) can be built from snapshots
 * on any thread: the snapshot no longer reads the world and is immutable.
 * <p>
 * Block properties ({@link Blast.Props}) at section positions use its palette: a copied palette (up to 256 states) has a
 * property array by palette index; a position reads its index from section storage, no extra memory per position.
 * The server thread publishes all palette states into the table ({@link Blast#ensure}) when taking the snapshot.
 * <p>
 * Snapshot origin: chunk {@code identityHashCode}, blocks ({@link #prints}: section fingerprints), and whether ruins
 * were applied. Valid while chunk, blocks (a blackout-darkened lamp counts as the same lamp), ruins and property table
 * generation remain the same ({@link #current}). The mod does not listen for chunk changes: the snapshot checks itself
 * against the chunk. A disk snapshot has no chunk (number 0): its plan checks against the chunk at application using
 * the same section fingerprints.
 * <p>
 * Snapshot number ({@link #serial}) tells the fracture and plan which snapshots built them ({@link RuinWindow.Stamp}):
 * a new snapshot is taken only when the old one is invalid.
 */
final class ChunkShot {
    private static final BlockState AIR = Blocks.AIR.defaultBlockState();

    private static final java.util.concurrent.atomic.AtomicLong SERIALS = new java.util.concurrent.atomic.AtomicLong();
    /** Fingerprint of an all-air section (absent from the snapshot). */
    private static final long AIR_PRINT = 0x5DEECE66DL;

    final int x, z;
    final int id;
    /** Snapshot number (from 1): fracture and plan built from this snapshot ({@link RuinWindow.Stamp}). */
    final long serial = SERIALS.incrementAndGet();
    /**
     * Section fingerprints by index ({@link #rawPrint}): compare snapshot to live chunk. A fingerprint with identical blocks
     * up to unlit lamp counterparts ({@link #normalPrint}) is replaced by the live one (server thread).
     */
    final long[] prints;
    /** Property table reset after which this snapshot was taken ({@link Blast#generation}). */
    private final int generation = Blast.generation;
    /** Chunk already ruined: original blocks are old plan-position blocks ({@link #old}); fire and flowing water count as air. */
    final boolean ruined;
    @Nullable
    private final RuinPlan old;
    /** Column tops from old plan blocks ({@link RuinPlan#oldTops}): computed here on the server thread. */
    @Nullable
    final int[] oldTops;
    final int minY, sections;
    /** Section copies; null — all-air section. */
    private final PalettedContainer<BlockState>[] states;
    /** Top block ({@code getHeight}) of {@code WORLD_SURFACE} and {@code MOTION_BLOCKING_NO_LEAVES} maps (index {@code lz << 4 | lx}). */
    final int[] surface = new int[256], solid = new int[256];
    /** First free position from {@link RuinPlan#HEIGHTMAP_TYPES}: {@code type * 256 + column}. */
    final int[] heights;
    /** Lowest column skylight source ({@code ChunkSkyLightSources}); null — compute from blocks (disk snapshot). */
    @Nullable
    final int[] sky;
    /** Section position properties, populated as read. */
    private final AtomicReferenceArray<Sec> props;
    /** Bytes occupied by palette copies and maps (detonation memory). */
    final long bytes;

    /** Properties of one section's positions: by palette index or (ruins, global palette) by position. */
    static final class Sec {
        @Nullable
        private final BitStorage storage;
        @Nullable
        private final Blast.Props[] local;
        @Nullable
        private final Blast.Props[] full;

        Sec(@Nullable BitStorage storage, @Nullable Blast.Props[] local, @Nullable Blast.Props[] full) {
            this.storage = storage;
            this.local = local;
            this.full = full;
        }

        /** Section position properties ({@code y << 8 | z << 4 | x}). */
        Blast.Props get(int i) {
            return full != null ? full[i] : local[storage.get(i)];
        }
    }

    /**
     * Pre-flash world snapshot for a chunk with the detonation's flash layer ({@link RuinPlan#unflash},
     * {@link RuinPlan#unfire}): live section fingerprints normalized for unlit lamps in sections changed by snapshot
     * adjustment ({@link #liveKnown}) — compare against those, not snapshot blocks; elsewhere snapshot and chunk are
     * identical. null — unmodified snapshot.
     */
    @Nullable
    private long[] liveNormals;
    @Nullable
    private boolean[] liveKnown;

    private ChunkShot(int x, int z, int id, @Nullable RuinPlan old, boolean ruined, int minY,
                      PalettedContainer<BlockState>[] states, int[] heights, @Nullable int[] sky, @Nullable long[] livePrints,
                      @Nullable long[] liveNormals, @Nullable boolean[] liveKnown) {
        this.x = x;
        this.z = z;
        this.id = id;
        this.ruined = ruined;
        this.old = old;
        this.minY = minY;
        this.states = states;
        this.sections = states.length;
        this.heights = heights;
        this.sky = sky;
        this.prints = new long[states.length];
        for (int i = 0; i < states.length; i++) prints[i] = livePrints != null ? livePrints[i] : rawPrint(states[i]);
        this.liveNormals = liveNormals;
        this.liveKnown = liveKnown;
        long size = 0;
        for (PalettedContainer<BlockState> c : states) {
            if (c == null) continue;
            size += c.getSerializedSize();
            register(c);
        }
        int surfaceType = type(Heightmap.Types.WORLD_SURFACE), solidType = type(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES);
        for (int c = 0; c < 256; c++) {
            surface[c] = heights[surfaceType * 256 + c] - 1;
            solid[c] = heights[solidType * 256 + c] - 1;
        }
        if (old != null) {
            this.oldTops = old.oldTops(minY);
            old.forEachOld(ChunkShot::ensure);
        } else {
            this.oldTops = null;
        }
        this.props = new AtomicReferenceArray<>(sections);
        this.bytes = size + 4L * (heights.length + 512 + (sky == null ? 0 : 256)) + 64L * sections + 256;
    }

    static int type(Heightmap.Types t) {
        for (int i = 0; i < RuinPlan.HEIGHTMAP_TYPES.length; i++) if (RuinPlan.HEIGHTMAP_TYPES[i] == t) return i;
        throw new IllegalStateException(t.name());
    }

    /**
     * Publish all palette states while on the server thread (global palette, over 256 states per section, cannot be
     * enumerated — scan all positions); for logs, all axes too (ruins lay trunks horizontally).
     */
    private static void register(PalettedContainer<BlockState> c) {
        boolean[] listed = {false};
        if (c.maybeHas(st -> {
            listed[0] = true;
            ensure(st);
            return false;
        }) && !listed[0]) {
            c.getAll(ChunkShot::ensure);
        }
    }

    private static void ensure(BlockState st) {
        Blast.ensure(st);
        if (st.hasProperty(BlockStateProperties.AXIS)) {
            for (var axis : BlockStateProperties.AXIS.getPossibleValues()) Blast.ensure(st.setValue(BlockStateProperties.AXIS, axis));
        }
    }

    /**
     * In-memory chunk snapshot (server thread): {@code applied} — chunk ruins already applied (their plan, or null if
     * released); {@code flash} — detonation flash layer present ({@link RuinPlan#applyFlash}): snapshot is pre-flash world
     * ({@link RuinPlan#unflash}), chunk comparison uses live sections; {@code unfire} — layer present without its plan
     * ({@link FlashScorch#marked}): snapshot treats chunk fire as air ({@link RuinPlan#unfire}).
     */
    @SuppressWarnings("unchecked")
    static ChunkShot take(LevelChunk chunk, @Nullable RuinContext.Applied applied, @Nullable RuinPlan flash, boolean unfire) {
        LevelChunkSection[] live = chunk.getSections();
        PalettedContainer<BlockState>[] states = (PalettedContainer<BlockState>[]) new PalettedContainer<?>[live.length];
        boolean[] own = flash != null || unfire ? new boolean[live.length] : null;
        for (int i = 0; i < live.length; i++) {
            if (live[i].hasOnlyAir()) continue;
            // own copy for flash layer edits (copy() retains the live section's palette resize handler)
            boolean edit = flash != null ? flash.touches(i) : unfire && ua.zentix.incoming.util.Palettes.contains(live[i].getStates(), FIRE);
            states[i] = edit ? RuinPlan.copyStates(live[i].getStates()) : live[i].getStates().copy();
            if (own != null) own[i] = edit;
        }
        int[] heights = new int[RuinPlan.HEIGHTMAP_TYPES.length * 256];
        for (int t = 0; t < RuinPlan.HEIGHTMAP_TYPES.length; t++) {
            Heightmap map = chunk.getOrCreateHeightmapUnprimed(RuinPlan.HEIGHTMAP_TYPES[t]);
            for (int c = 0; c < 256; c++) heights[t * 256 + c] = map.getFirstAvailable(c & 15, c >> 4);
        }
        int[] sky = new int[256];
        var sources = chunk.getSkyLightSources();
        for (int c = 0; c < 256; c++) sky[c] = sources.get(c);
        long[] livePrints = null, liveNormals = null;
        if (own != null) {
            // fingerprints from live sections; normalize lamps only in modified sections (elsewhere snapshot is the chunk)
            livePrints = new long[live.length];
            liveNormals = new long[live.length];
            for (int i = 0; i < live.length; i++) {
                livePrints[i] = rawPrint(live[i]);
                if (own[i]) liveNormals[i] = normalPrint(live[i]);
            }
            if (flash != null) flash.unflash(states, chunk.getMinBuildHeight(), heights, sky);
            else RuinPlan.unfire(states, own, chunk.getMinBuildHeight(), heights, sky);
        }
        ChunkPos pos = chunk.getPos();
        return new ChunkShot(pos.x, pos.z, System.identityHashCode(chunk), applied == null ? null : applied.plan(),
                applied != null, chunk.getMinBuildHeight(), states, heights, sky, livePrints, liveNormals, own);
    }

    private static final java.util.function.Predicate<BlockState> FIRE = s -> s.getBlock() instanceof BaseFireBlock;

    /**
     * Disk chunk snapshot (server thread: publish its states), see {@link DiskShots}; {@code applied} — ruins already stood
     * (chunk was in memory after detonation and dropped below full loading): original world at plan positions comes from
     * the plan, as in a memory snapshot; {@code flash} — flash layer stood while chunk was in memory: pre-flash world;
     * {@code unfire} — layer present (mark in chunk data), no plan: fire is air, as in a memory snapshot ({@link #take}).
     */
    static ChunkShot fromDisk(DiskShots.Read read, @Nullable RuinContext.Applied applied, @Nullable RuinPlan flash, boolean unfire) {
        PalettedContainer<BlockState>[] states = read.states();
        int[] heights = read.heights();
        if (flash != null || unfire) {
            states = states.clone();
            boolean[] own = new boolean[states.length];
            for (int i = 0; i < states.length; i++) {
                own[i] = states[i] != null && (flash != null ? flash.touches(i) : ua.zentix.incoming.util.Palettes.contains(states[i], FIRE));
                if (own[i]) states[i] = RuinPlan.copyStates(states[i]);
            }
            heights = heights.clone();
            if (flash != null) flash.unflash(states, read.minY(), heights, null);
            else RuinPlan.unfire(states, own, read.minY(), heights, null);
        }
        return new ChunkShot(read.pos().x, read.pos().z, 0, applied == null ? null : applied.plan(), applied != null,
                read.minY(), states, heights, null, null, null, null);
    }

    /**
     * Flash layer {@code flash} applied to this snapshot's chunk (server thread; snapshot valid before it): snapshot remains
     * pre-flash world, but layer sections compare against their post-layer state (other sections untouched). Same snapshot
     * number: fractures and plans built from it remain valid.
     */
    void relive(LevelChunk chunk, RuinPlan flash) {
        LevelChunkSection[] live = chunk.getSections();
        if (liveNormals == null) {
            liveNormals = new long[sections];
            liveKnown = new boolean[sections];
        }
        for (int i = 0; i < sections; i++) {
            if (!flash.touches(i)) continue;
            prints[i] = rawPrint(live[i]);
            liveNormals[i] = normalPrint(live[i]);
            liveKnown[i] = true;
        }
    }

    /**
     * Snapshot from the same chunk and ruins as now (server thread). Without ruins, same blocks too; with ruins, ruins
     * themselves keep changing blocks (fire, flowing water, logs), but original world at plan positions comes from the plan.
     */
    boolean current(LevelChunk chunk, @Nullable RuinContext.Applied applied) {
        if (System.identityHashCode(chunk) != id || !fresh() || ruined != (applied != null)) return false;
        return applied != null ? applied.plan() == old : sameBlocks(chunk);
    }

    /**
     * Live chunk blocks match the snapshot: section fingerprints, then where they differ, blocks normalized for unlit
     * lamps (blackout changes them directly in sections); replace such a fingerprint with the live one, making the next
     * comparison cheap again.
     */
    private boolean sameBlocks(LevelChunk chunk) {
        LevelChunkSection[] live = chunk.getSections();
        if (live.length != sections) return false;
        long[] normals = liveNormals;
        boolean[] known = liveKnown;
        for (int i = 0; i < sections; i++) {
            long p = rawPrint(live[i]);
            if (p == prints[i]) continue;
            if (normalPrint(live[i]) != (normals != null && known[i] ? normals[i] : normalPrint(states[i], RuinPlan::normal))) return false;
            prints[i] = p;
        }
        return true;
    }

    /** Snapshot section fingerprints normalized for unlit lamps (any thread: states through the property table). */
    long[] normalPrints(Blast.PropsView view) {
        long[] out = new long[sections];
        for (int i = 0; i < sections; i++) out[i] = normalPrint(states[i], st -> view.get(st).normal());
        return out;
    }

    /** World section fingerprint as is ({@link #rawPrint(PalettedContainer)}). */
    static long rawPrint(LevelChunkSection s) {
        return rawPrint(s.hasOnlyAir() ? null : s.getStates());
    }

    /**
     * Section fingerprint as is: indexed palette states and index storage (hundreds of {@code long}, cheap). Identical
     * blocks give a different fingerprint after palette rebuild or lamp darkening; {@link #normalPrint} then decides.
     */
    static long rawPrint(@Nullable PalettedContainer<BlockState> c) {
        if (c == null) return AIR_PRINT;
        var data = c.data;
        long h = data.storage().getBits();
        Palette<BlockState> palette = data.palette();
        if (!(palette instanceof GlobalPalette)) {
            for (int k = 0; k < palette.getSize(); k++) h = mix(h, System.identityHashCode(palette.valueFor(k)));
        }
        for (long v : data.storage().getRaw()) h = mix(h, v);
        return h;
    }

    /** World section fingerprint by position, normalized for unlit lamps (server thread). */
    static long normalPrint(LevelChunkSection s) {
        return normalPrint(s.hasOnlyAir() ? null : s.getStates(), RuinPlan::normal);
    }

    /**
     * Section fingerprint by position: each position's state transformed by {@code normal} (lamp and counterpart identical).
     * State uses {@code identityHashCode} (states are singletons), not {@code Block.getId}: background ruin threads also
     * compute fingerprints and cannot call block code ({@code backgroundSolversCallNoWorld}).
     */
    static long normalPrint(@Nullable PalettedContainer<BlockState> c, java.util.function.Function<BlockState, BlockState> normal) {
        if (c == null) return AIR_PRINT;
        var data = c.data;
        Palette<BlockState> palette = data.palette();
        BitStorage storage = data.storage();
        long h = 0;
        if (palette instanceof GlobalPalette) {
            it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap seen = new it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap();
            for (int i = 0; i < storage.getSize(); i++) {
                h = mix(h, seen.computeIfAbsent(storage.get(i), id -> System.identityHashCode(normal.apply(palette.valueFor(id)))));
            }
        } else {
            int[] norm = new int[palette.getSize()];
            for (int k = 0; k < norm.length; k++) norm[k] = System.identityHashCode(normal.apply(palette.valueFor(k)));
            for (int i = 0; i < storage.getSize(); i++) h = mix(h, norm[storage.get(i)]);
        }
        return h;
    }

    private static long mix(long h, long v) {
        return (h ^ v) * 0x9E3779B97F4A7C15L + 1;
    }

    /** Snapshot from disk, not memory. */
    boolean fromDisk() {
        return id == 0;
    }

    /** Property table has not reset since the snapshot. */
    boolean fresh() {
        return generation == Blast.generation;
    }

    ChunkPos pos() {
        return new ChunkPos(x, z);
    }

    int maxY() {
        return minY + (sections << 4);
    }

    /** Original state at a position (y in world coordinates, within world height). */
    BlockState get(int lx, int y, int lz) {
        if (ruined) {
            if (old != null) {
                BlockState o = old.oldState(lx, y, lz, minY);
                if (o != null) return o;
            }
            BlockState now = raw(lx, y, lz);
            // ruin aftermath: fire and spread water occupy former air
            if (now.getBlock() instanceof BaseFireBlock || !now.getFluidState().isEmpty() && !now.getFluidState().isSource()) return AIR;
            return now;
        }
        return raw(lx, y, lz);
    }

    /** State at a chunk position when snapshotted (including ruins if present). */
    BlockState raw(int lx, int y, int lz) {
        int i = (y - minY) >> 4;
        if (i < 0 || i >= sections) return AIR;
        return states[i] == null ? AIR : states[i].get(lx, y & 15, lz);
    }

    /** Position properties of section {@code i} (index from world bottom). */
    Sec props(int i, Blast.PropsView view) {
        Sec p = props.get(i);
        if (p != null) return p;
        PalettedContainer<BlockState> c = states[i];
        if (ruined || c != null && c.data.palette() instanceof GlobalPalette) {
            // by position: old plan blocks overlaid on section, or global palette (index in the whole registry)
            Blast.Props[] full = new Blast.Props[4096];
            int y0 = minY + (i << 4);
            for (int y = 0; y < 16; y++) {
                for (int z = 0; z < 16; z++) {
                    for (int x = 0; x < 16; x++) full[y << 8 | z << 4 | x] = view.get(get(x, y0 + y, z));
                }
            }
            p = new Sec(null, null, full);
        } else if (c == null) {
            p = new Sec(new ZeroBitStorage(4096), new Blast.Props[]{view.get(AIR)}, null);
        } else {
            Palette<BlockState> palette = c.data.palette();
            Blast.Props[] local = new Blast.Props[palette.getSize()];
            for (int k = 0; k < local.length; k++) local[k] = view.get(palette.valueFor(k));
            p = new Sec(c.data.storage(), local, null);
        }
        return props.compareAndSet(i, null, p) ? p : props.get(i);
    }

    /** Whether the section may contain a state matching {@code test} (from copied palette). */
    boolean mayHave(int i, java.util.function.Predicate<BlockState> test) {
        if (i < 0 || i >= sections) return false;
        if (ruined && old != null && old.anyOld(i, test)) return true;
        return states[i] == null ? test.test(AIR) : states[i].maybeHas(test);
    }
}
