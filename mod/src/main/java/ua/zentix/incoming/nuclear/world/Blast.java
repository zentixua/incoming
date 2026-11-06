package ua.zentix.incoming.nuclear.world;

import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import it.unimi.dsi.fastutil.objects.Reference2ObjectOpenHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.Mth;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.lighting.LightEngine;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
import ua.zentix.incoming.nuclear.Detonation;
import ua.zentix.incoming.nuclear.flash.FlashChar;
import ua.zentix.incoming.registry.ModTags;

import java.util.ArrayList;
import java.util.List;

/**
 * What shock wave breaks in one chunk, first of two ruin physics processes (second {@link Collapse}):
 * <b>pressure differences break thin elements</b>. Uses only original chunk-window blocks ({@link RuinWindow}), nothing else,
 * so chunk fracture is identical whoever computes it and whenever (cache {@link RuinContext}).
 * <ul>
 * <li>Pressure reaches only faces beside sky-connected air: above column tops and no deeper than
 * {@link #SKY_DEPTH} blocks through openings; beyond breaches (rounds 2 and 3), all window air connected to them:
 * wave flows through floors to core. Bunkers, sealed caves and basements untouched.</li>
 * <li>Blast-facing faces get reflected pressure {@code P = max(p, Pr·cos θ)}, {@code Pr = 2p(7p₀+4p)/(7p₀+p)}; wall
 * angle is horizontal (near-ground wave travels as Mach stem), roof angle vertical.</li>
 * <li>Block in element of thickness {@code t} ≤ 3 (row of full cubes with open ends along shortest axis) breaks at
 * {@code P ≥ T·k(t)·jitter}, {@code k = 1, 2, 3.5}; face-breached element fails through full thickness. Ground only
 * with sky on both sides (ridge, wall, overhang).</li>
 * <li>Toppling: tall narrow elements (slenderness {@code H/t ≥ 4}, {@code t} ≤ {@link #MAX_TOPPLE} away from
 * blast, sky behind) fail through full cross-section at {@code Pr·cos θ·H/t ≥ T·k(3)·jitter}. {@code H} is height above position
 * while air remains behind: floor behind wall pier ends lever arm at that story. Two-brick walls and tower cores fall,
 * while thicker mountains, cliffs and mesas stand.</li>
 * <li>Two rounds: second sees first-round breaches (and air behind; only columns at most
 * {@link #MAX_TOPPLE} wide across topple there). Third uses all neighbors' breaches ({@link #remnants}). Trees topple
 * as whole trunks before per-block wave processing.</li>
 * <li>Indestructible blocks (strength &lt; 0 or ≥ 50: bedrock, obsidian, barrier, light) neither break nor fall,
 * and support like ground ({@link Props#fixed}).</li>
 * </ul>
 * Reach: each block decision reads at most 16 blocks away, neighboring-chunk width in window.
 */
record Blast(RuinWindow.Stamp stamp, int minY, int[] removed, List<long[]> trees, List<BlockState> treeLogs) {
    /** Pressure penetration depth through opening, blocks. */
    static final int SKY_DEPTH = 4;
    /**
     * Columns around chunk solved in first round: chunk's second round reads breaches 5 blocks around and air
     * opened by them another {@link #SKY_DEPTH}, making result identical to any neighbor's calculation.
     */
    private static final int ROUND_ONE = 9;
    /** Atmospheric pressure, psi. */
    private static final double P0 = 14.7;
    /** Strength factors for thickness 1, 2, 3 relative to thin element. */
    static final double[] K = {0, 1, 2, 3.5};
    /** Toppling slenderness threshold and maximum thickness. */
    private static final int SLENDER = 4, MAX_TOPPLE = 6;
    /**
     * Maximum ground toppling thickness: natural pillars/mesas thicker than 4 stand (4×4 calcite or
     * terracotta tower core still falls).
     */
    private static final int GROUND_TOPPLE = 4;
    private static final int SIDE = RuinWindow.SIDE, LO = 16, HI = 32;

    // ---------------------------------------------------------------- state properties

    /**
     * Block behavior in ruin physics. {@code rubble} creates debris on collapse (strength from 1: not grass or
     * glass), {@code wood} creates wooden rubble (axe). {@code flash} is face flash scorch: material
     * ({@link ua.zentix.incoming.nuclear.flash.FlashChar#material}) and block hash above two bits ({@link #flashMaterial},
     * {@link #flashHash}).
     */
    record Props(BlockResponse response, boolean air, boolean full, boolean bearing, int span, boolean fluid, boolean waterlogged, boolean leaves,
                 boolean log, boolean fixed, boolean rubble, boolean wood, int flags, int lightBlock, int emission, VoxelShape occludeUp,
                 VoxelShape occludeDown, BlockState normal, int flash) {
        /** Opaque to heightmap {@code RuinPlan.HEIGHTMAP_TYPES[i]}: bit {@code 1 << i}. */
        static final int OPAQUE = 1;
        static final int STURDY_UP = 1 << 4, FLAMMABLE = 1 << 5, REPLACEABLE = 1 << 6, SLOW = 1 << 7, SOURCE = 1 << 8,
                SHAPE_LIGHT = 1 << 9, GRASS = 1 << 10, MOSS = 1 << 11, SAND = 1 << 12, DIRT = 1 << 13, SNOW = 1 << 14,
                SOUL_BASE = 1 << 15, FIRE = 1 << 16, NO_FLUID = 1 << 17, PLANT = 1 << 18, SNOW_LAYER = 1 << 19,
                /** Snow for turf beneath ({@code SnowyDirtBlock}: {@code minecraft:snow} tag, layer, block, powder snow). */
                SNOWY_TOP = 1 << 20;

        boolean is(int flag) {
            return (flags & flag) != 0;
        }

        int flashMaterial() {
            return flash & 3;
        }

        int flashHash() {
            return flash >>> 2;
        }

        /** Opaque to heightmap {@code RuinPlan.HEIGHTMAP_TYPES[type]}. */
        boolean opaque(int type) {
            return (flags & OPAQUE << type) != 0;
        }

        float threshold() {
            return response.thresholdPsi();
        }

        /** Wave-breakable (log only as tree trunk, not per block). */
        boolean breakable() {
            return response.kind() != BlockResponse.Kind.NONE && !log;
        }
    }

    /** State properties for ruin solvers: shared table on server thread, published table in background. */
    interface PropsView {
        Props get(BlockState st);
    }

    /** All known properties (server thread only). */
    private static final Reference2ObjectOpenHashMap<BlockState, Props> PROPS = new Reference2ObjectOpenHashMap<>();
    /** Copy of {@link #PROPS} for background threads: immutable, replaced by {@link #publish}. */
    private static volatile Reference2ObjectOpenHashMap<BlockState, Props> published = new Reference2ObjectOpenHashMap<>();
    private static boolean dirty;
    /** Table reset count: snapshot taken before reset republishes its states ({@link ChunkShot#current}). */
    static int generation;

    /** Tags reloaded: thresholds changed (server thread, like all ruin work). */
    static void clearProps() {
        generation++;
        PROPS.clear();
        published = new Reference2ObjectOpenHashMap<>();
        dirty = false;
    }

    /** State properties (server thread). */
    static Props props(BlockState st) {
        Props p = PROPS.get(st);
        if (p == null) {
            PROPS.put(st, p = computeProps(st));
            dirty = true;
        }
        return p;
    }

    /** Snapshot state ({@link ChunkShot}) into table (server thread); background threads receive after {@link #publish}. */
    static void ensure(BlockState st) {
        props(st);
    }

    /** Table for background tasks created after call (server thread): all currently known properties. */
    static PropsView publish() {
        props(AIR);
        props(RuinWindow.MASS);
        props(WATER);
        if (dirty) {
            published = new Reference2ObjectOpenHashMap<>(PROPS);
            dirty = false;
        }
        Reference2ObjectOpenHashMap<BlockState, Props> table = published;
        return st -> {
            Props p = table.get(st);
            if (p == null) throw new IllegalStateException("Ruins: properties of state " + st + " not in the snapshot table");
            return p;
        };
    }

    /** Properties on server thread. */
    static final PropsView SERVER = Blast::props;

    private static final BlockState AIR = net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(),
            WATER = net.minecraft.world.level.block.Blocks.WATER.defaultBlockState();

    private static Props computeProps(BlockState st) {
        BlockResponse r = BlockResponse.of(st);
        boolean liquid = st.getBlock() instanceof LiquidBlock;
        var shape = st.getCollisionShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO);
        boolean full = !liquid && Block.isShapeFullBlock(shape);
        boolean leaves = st.is(BlockTags.LEAVES), log = st.is(BlockTags.LOGS);
        // support: anything with collision (glass, chains, fences too); leaves attach to trunk rather than rest on support ({@link Collapse})
        boolean bearing = !liquid && !leaves && !shape.isEmpty();
        // span: blocks sideways (or down) a block holds without support beneath
        int span;
        if (st.getBlock() instanceof FallingBlock || r.thresholdPsi() <= 1) span = 0;
        else if (log || r.kind() == BlockResponse.Kind.NONE || r.kind() == BlockResponse.Kind.GROUND || r.thresholdPsi() >= 12) span = 6;
        else span = 4;
        boolean waterlogged = !liquid && st.hasProperty(BlockStateProperties.WATERLOGGED) && st.getValue(BlockStateProperties.WATERLOGGED);
        // indestructible (bedrock, barrier, light, portal frame, obsidian): never falls or detaches, provides support
        float destroy = st.getBlock().defaultDestroyTime();
        boolean fixed = !st.isAir() && !liquid && (destroy < 0 || destroy >= 50);
        // for background plan completion ({@code RuinPlanner.finish}): light, fire, heightmaps, ground obtained here on server thread
        int flags = 0;
        for (int t = 0; t < RuinPlan.HEIGHTMAP_TYPES.length; t++) if (RuinPlan.HEIGHTMAP_TYPES[t].isOpaque().test(st)) flags |= Props.OPAQUE << t;
        EmptyBlockGetter at = EmptyBlockGetter.INSTANCE;
        if (st.isFaceSturdy(at, BlockPos.ZERO, Direction.UP)) flags |= Props.STURDY_UP;
        if (st.isFlammable(at, BlockPos.ZERO, Direction.UP)) flags |= Props.FLAMMABLE;
        if (st.canBeReplaced()) flags |= Props.REPLACEABLE;
        if (st.hasBlockEntity() || net.minecraft.world.entity.ai.village.poi.PoiTypes.forState(st).isPresent()) flags |= Props.SLOW;
        if (st.getFluidState().isSource()) flags |= Props.SOURCE;
        if (st.getFluidState().isEmpty()) flags |= Props.NO_FLUID;
        if (st.useShapeForLightOcclusion()) flags |= Props.SHAPE_LIGHT;
        if (st.is(Blocks.GRASS_BLOCK) || st.is(Blocks.PODZOL) || st.is(Blocks.MYCELIUM)) flags |= Props.GRASS;
        if (st.is(Blocks.MOSS_BLOCK)) flags |= Props.MOSS;
        if (st.is(BlockTags.SAND)) flags |= Props.SAND;
        if (st.is(BlockTags.DIRT)) flags |= Props.DIRT;
        if (st.is(Blocks.SNOW_BLOCK)) flags |= Props.SNOW;
        if (st.is(Blocks.SNOW)) flags |= Props.SNOW_LAYER;
        if (st.is(BlockTags.SNOW)) flags |= Props.SNOWY_TOP;
        // flash burns above-ground plants tagged nuke_plants (same tag defines pressure threshold); dead
        // bush is their remnant
        if (!liquid && st.getFluidState().isEmpty() && !st.is(Blocks.DEAD_BUSH) && st.is(ModTags.NUKE_PLANTS)) flags |= Props.PLANT;
        if (st.is(BlockTags.SOUL_FIRE_BASE_BLOCKS)) flags |= Props.SOUL_BASE;
        if (st.is(Blocks.FIRE)) flags |= Props.FIRE;
        return new Props(r, st.isAir(), full, bearing, span, liquid, waterlogged, leaves, log, fixed, destroy >= 1, st.is(BlockTags.MINEABLE_WITH_AXE),
                flags, st.getLightBlock(at, BlockPos.ZERO), st.getLightEmission(at, BlockPos.ZERO),
                LightEngine.getOcclusionShape(at, BlockPos.ZERO, st, Direction.UP), LightEngine.getOcclusionShape(at, BlockPos.ZERO, st, Direction.DOWN),
                RuinPlan.normal(st), FlashChar.material(st) | FlashChar.hash(st.getBlock()) << 2);
    }

    /** Normally reflected pressure (air, γ = 1.4), psi. */
    static double reflected(double p) {
        return 2 * p * (7 * P0 + 4 * p) / (7 * P0 + p);
    }

    // ---------------------------------------------------------------- result

    /** Chunk position (like {@link RuinPlan} keys: section, y, z, x) broken by wave or toppled with trunk. */
    boolean gone(int lx, int y, int lz) {
        int i = (y - minY) >> 4;
        if (i < 0) return false;
        return java.util.Arrays.binarySearch(removed, i << RuinPlan.SECTION_SHIFT | (y & 15) << 8 | lz << 4 | lx) >= 0;
    }

    /** Fracture of window {@code w} chunk (any thread: snapshot window). */
    static Blast solve(RuinWindow w, Detonation d, boolean blockDamage, boolean treeFall) {
        Solver s = new Solver(w, d);
        try {
            return s.run(blockDamage, treeFall);
        } finally {
            s.close();
        }
    }

    /** Solver buffers per thread: one solver takes them at a time; nested solver uses its own. */
    private static final class Buffers {
        byte[] sky = new byte[0];
        long[] gone = new long[0];
        boolean busy;
    }

    private static final ThreadLocal<Buffers> BUFFERS = ThreadLocal.withInitial(Buffers::new);

    /** Ruin queues empty: this thread's solver buffers no longer needed. */
    static void releaseBuffers() {
        Buffers b = BUFFERS.get();
        if (b.busy) return;
        b.sky = new byte[0];
        b.gone = new long[0];
    }

    /**
     * Third round uses fractures of all window chunks (built by {@link Collapse}): remnants near neighboring breaches
     * (house corner, wall beside floor, core behind broken stories) now thinner and exposed from another side, solved
     * again by same rules in chunk columns ± {@code margin}. Window positions: {@code wx | wz << 6 | (y − minY) << 12}.
     */
    static int[] remnants(RuinWindow w, Detonation d, Blast[] blasts, int margin, boolean blockDamage) {
        if (!blockDamage) return new int[0];
        Solver s = new Solver(w, d);
        try {
            return s.remnants(blasts, margin);
        } finally {
            s.close();
        }
    }

    // ---------------------------------------------------------------- solution

    private static final class Solver {
        /** Third round ({@link Blast#remnants}): every window chunk's breaches count as breaches. */
        int[] remnants(Blast[] blasts, int margin) {
            skyAir();
            IntArrayList breaks = new IntArrayList();
            for (int k = 0; k < 9; k++) {
                if (blasts[k] == null) continue;
                int bx = (k % 3) << 4, bz = (k / 3) << 4;
                for (int c : blasts[k].removed()) {
                    int wx = bx + (c & 15), wz = bz + ((c >> 4) & 15), y = w.minY + ((c >>> RuinPlan.SECTION_SHIFT) << 4) + ((c >> 8) & 15);
                    if (!inside(wx, y, wz)) continue;
                    int i = idx(wx, y, wz);
                    set(gone, i);
                    breaks.add(i);
                }
            }
            IntArrayList out = new IntArrayList();
            if (!breaks.isEmpty()) aroundBreaks(breaks, margin, out);
            int[] res = new int[out.size()];
            for (int q = 0; q < res.length; q++) {
                int i = out.getInt(q), wx = i % SIDE, wz = (i / SIDE) % SIDE, y = i / (SIDE * SIDE) + yb;
                res[q] = wx | wz << 6 | (y - w.minY) << 12;
            }
            return res;
        }

        final RuinWindow w;
        final Detonation d;
        final Vec3 burst;
        final int yb, yt, h;
        /** Lowest top within {@link #SKY_DEPTH} + 1: no sky openings below. */
        final int[] lowTop = new int[SIDE * SIDE];
        /** Sky-connected air depth (1…{@link #SKY_DEPTH}); above column top, sky without stored entry. */
        final byte[] sky;
        final long[] gone;
        /** Thread buffers {@link #sky} and {@link #gone} ({@link #BUFFERS}). */
        final Buffers pooled;
        final List<long[]> trees = new ArrayList<>();
        final List<BlockState> treeLogs = new ArrayList<>();

        Solver(RuinWindow w, Detonation d) {
            this.w = w;
            this.d = d;
            this.burst = d.burst();
            int minTop = Integer.MAX_VALUE, maxTop = w.minY;
            for (int wz = 0; wz < SIDE; wz++) {
                for (int wx = 0; wx < SIDE; wx++) {
                    int t = w.top(wx, wz);
                    if (t == Integer.MIN_VALUE) continue;
                    maxTop = Math.max(maxTop, t);
                }
            }
            int r = SKY_DEPTH + 1;
            int[] rows = new int[SIDE * SIDE];
            for (int wz = 0; wz < SIDE; wz++) {
                for (int wx = 0; wx < SIDE; wx++) {
                    int m = Integer.MAX_VALUE;
                    for (int x = Math.max(0, wx - r); x <= Math.min(SIDE - 1, wx + r); x++) m = Math.min(m, topOr(x, wz));
                    rows[wz * SIDE + wx] = m;
                }
            }
            for (int wz = 0; wz < SIDE; wz++) {
                for (int wx = 0; wx < SIDE; wx++) {
                    int m = Integer.MAX_VALUE;
                    for (int z = Math.max(0, wz - r); z <= Math.min(SIDE - 1, wz + r); z++) m = Math.min(m, rows[z * SIDE + wx]);
                    lowTop[wz * SIDE + wx] = m;
                    minTop = Math.min(minTop, m);
                }
            }
            // wave sees nothing below lowest opening minus sky depth
            this.yb = Math.max(w.minY, Math.min(minTop, maxTop) - SKY_DEPTH - 2);
            this.yt = Math.min(w.maxY - 1, maxTop + 1);
            this.h = Math.max(1, yt - yb + 1);
            int n = SIDE * SIDE * h, words = (n + 63) >> 6;
            Buffers b = BUFFERS.get();
            if (b.busy) {
                this.sky = new byte[n];
                this.gone = new long[words];
                this.pooled = null;
            } else {
                if (b.sky.length < n) b.sky = new byte[n];
                if (b.gone.length < words) b.gone = new long[words];
                java.util.Arrays.fill(b.sky, 0, n, (byte) 0);
                java.util.Arrays.fill(b.gone, 0, words, 0L);
                this.sky = b.sky;
                this.gone = b.gone;
                b.busy = true;
                this.pooled = b;
            }
        }

        /** Return buffers to shared storage. */
        void close() {
            if (pooled != null) pooled.busy = false;
        }

        int topOr(int wx, int wz) {
            int t = w.top(wx, wz);
            return t == Integer.MIN_VALUE ? w.minY - 1 : t;
        }

        int idx(int wx, int y, int wz) {
            return ((y - yb) * SIDE + wz) * SIDE + wx;
        }

        boolean inside(int wx, int y, int wz) {
            return wx >= 0 && wx < SIDE && wz >= 0 && wz < SIDE && y >= yb && y <= yt;
        }

        static boolean bit(long[] b, int i) {
            return (b[i >> 6] & 1L << (i & 63)) != 0;
        }

        static void set(long[] b, int i) {
            b[i >> 6] |= 1L << (i & 63);
        }

        boolean isGone(int wx, int y, int wz) {
            return inside(wx, y, wz) && bit(gone, idx(wx, y, wz));
        }

        /** Sky-connected air: above column top, within {@link #SKY_DEPTH} inside, or breach. */
        boolean open(int wx, int y, int wz) {
            if (wx < 0 || wx >= SIDE || wz < 0 || wz >= SIDE) return false;
            if (y >= w.maxY) return true;
            if (y > w.top(wx, wz)) return w.has(wx, wz);
            if (y < yb || y > yt) return false;
            int i = idx(wx, y, wz);
            return sky[i] > 0 || bit(gone, i);
        }

        Blast run(boolean blockDamage, boolean treeFall) {
            if (blockDamage) {
                skyAir();
                if (treeFall) fellTrees();
                IntArrayList round = new IntArrayList();
                for (int wz = LO - ROUND_ONE; wz < HI + ROUND_ONE; wz++) {
                    for (int wx = LO - ROUND_ONE; wx < HI + ROUND_ONE; wx++) {
                        int t = w.top(wx, wz);
                        if (t == Integer.MIN_VALUE || !w.has(wx, wz)) continue;
                        int low = Math.max(yb, Math.min(t, lowTop[wz * SIDE + wx]) - SKY_DEPTH - 1);
                        double pmax = reflected(d.psi(new Vec3(w.x0 + wx + 0.5, Mth.clamp(burst.y, low, t + 1), w.z0 + wz + 0.5)));
                        for (int y = Math.min(t, yt); y >= low; y--) {
                            Props p = w.props(wx, y, wz);
                            if (p.air() || isGone(wx, y, wz)) continue;
                            if (!p.breakable()) continue;
                            // rejection: block cannot fail even by toppling (slenderness bounded by height above, 64 blocks)
                            if (p.threshold() * 0.85 > pmax * 64 / K[3]) continue;
                            decide(wx, y, wz, p, pmax, round, true);
                        }
                    }
                }
                for (int i = 0; i < round.size(); i++) set(gone, round.getInt(i));
                // first-round breaches admit wave into all connected air (halls, floors, core behind facade);
                // second round inside chunk: blocks beside breaches and this air
                IntArrayList second = new IntArrayList();
                aroundBreaks(round, 0, second);
                for (int i = 0; i < second.size(); i++) set(gone, second.getInt(i));
            }
            // broken positions inside chunk use plan keys
            IntArrayList out = new IntArrayList();
            for (int y = yb; y <= yt; y++) {
                for (int wz = LO; wz < HI; wz++) {
                    for (int wx = LO; wx < HI; wx++) {
                        if (!bit(gone, idx(wx, y, wz))) continue;
                        out.add(((y - w.minY) >> 4) << RuinPlan.SECTION_SHIFT | (y & 15) << 8 | (wz - LO) << 4 | (wx - LO));
                    }
                }
            }
            int[] removed = out.toIntArray();
            java.util.Arrays.sort(removed);
            return new Blast(w.stamp(), w.minY, removed, trees, treeLogs);
        }

        /**
         * Wave enters {@code breaks}: all connected air in chunk columns ± {@link #ROUND_ONE}
         * receives incident-wave pressure (becomes sky air); blocks beside breaches and this air in chunk columns
         * ± {@code margin} solved again into {@code out}. Fill stops at {@link #ROUND_ONE}: decisions beyond
         * are needed by neither chunk nor collapse (shorter support paths); 48×48 tower window costs tens of ms per unit.
         */
        void aroundBreaks(IntArrayList breaks, int margin, IntArrayList out) {
            IntArrayList reach = new IntArrayList(breaks);
            for (int q = 0; q < reach.size(); q++) {
                int c = reach.getInt(q), wx = c % SIDE, wz = (c / SIDE) % SIDE, y = c / (SIDE * SIDE) + yb;
                int dc = bit(gone, c) ? 0 : sky[c];
                for (Direction dir : Direction.values()) {
                    int nx = wx + dir.getStepX(), ny = y + dir.getStepY(), nz = wz + dir.getStepZ();
                    if (nx < LO - ROUND_ONE || nx >= HI + ROUND_ONE || nz < LO - ROUND_ONE || nz >= HI + ROUND_ONE) continue;
                    if (!inside(nx, ny, nz) || ny > w.top(nx, nz)) continue;
                    int n = idx(nx, ny, nz);
                    // sky position already visited (air depth marked on first visit)
                    if (bit(gone, n) || sky[n] != 0 || !w.air(nx, ny, nz)) continue;
                    sky[n] = (byte) Math.min(Byte.MAX_VALUE, dc + 1);
                    reach.add(n);
                }
            }
            IntOpenHashSet seen = new IntOpenHashSet();
            for (int q = 0; q < reach.size(); q++) {
                int c = reach.getInt(q), wx = c % SIDE, wz = (c / SIDE) % SIDE, y = c / (SIDE * SIDE) + yb;
                for (Direction dir : Direction.values()) {
                    int nx = wx + dir.getStepX(), ny = y + dir.getStepY(), nz = wz + dir.getStepZ();
                    if (nx < LO - margin || nx >= HI + margin || nz < LO - margin || nz >= HI + margin || ny < yb || ny > yt) continue;
                    int n = idx(nx, ny, nz);
                    if (bit(gone, n) || !seen.add(n)) continue;
                    Props p = w.props(nx, ny, nz);
                    if (p.air() || !p.breakable()) continue;
                    decide(nx, ny, nz, p, Double.MAX_VALUE, out, false);
                }
            }
        }

        /** Whether block breaks (thickness, reflection) or element topples; broken positions into {@code out}. */
        void decide(int wx, int y, int wz, Props p, double pmax, IntArrayList out, boolean first) {
            int x = w.x0 + wx, z = w.z0 + wz;
            double cx = x + 0.5, cy = y + 0.5, cz = z + 0.5;
            double ux = burst.x - cx, uy = burst.y - cy, uz = burst.z - cz, len = Math.sqrt(ux * ux + uy * uy + uz * uz);
            if (len < 1e-6) len = 1e-6;
            double vy = uy / len, hl = Math.hypot(ux, uz);
            double ex = hl < 1e-6 ? 0 : ux / hl, ez = hl < 1e-6 ? 0 : uz / hl;
            // sky-facing faces: most directly blast-facing (horizontal wall angle, Mach stem; vertical roof angle)
            double facing = -2;
            Direction.Axis faceAxis = null;
            for (Direction dir : Direction.values()) {
                if (!open(wx + dir.getStepX(), y + dir.getStepY(), wz + dir.getStepZ())) continue;
                double cos = dir.getAxis() == Direction.Axis.Y ? dir.getStepY() * vy : dir.getStepX() * ex + dir.getStepZ() * ez;
                if (cos > facing) {
                    facing = cos;
                    faceAxis = dir.getAxis();
                }
            }
            if (facing < -1) return; // not beside sky
            long th = thickness(wx, y, wz, p);
            int t = (int) (th & 7);
            // mass: fails only by toppling, requiring height above position
            int height = 0;
            if (t > 3) {
                if (p.leaves() || hl < 1e-6) return;
                while (height < 64 && y + height + 1 <= w.maxY - 1 && !w.air(wx, y + height + 1, wz)) height++;
                if (height < SLENDER) return;
            }
            double psi = d.psi(new Vec3(cx, cy, cz)), pr = reflected(psi);
            int seed = Mth.murmurHash3Mixer(Long.hashCode(BlockPos.asLong(x, y, z)));
            // ground (soil, stone, terracotta) fails as thin element only with sky-connected air on both sides:
            // ridge, wall, overhang; no closed cave/bunker/basement ceiling under thick ground (no pressure difference)
            // (also partial ground: path, farmland, mud, otherwise trenches along paths and fields)
            boolean thin = t <= 3 && (p.response().kind() != BlockResponse.Kind.GROUND || ends(wx, y, wz, th));
            if (thin) {
                double pressure = facing > 0 ? Math.max(psi, pr * facing) : psi;
                if (p.response().breaksAt(pressure / K[t], seed)) {
                    int axis = (int) (th >> 3 & 3);
                    if (facing > 0 && faceAxis.ordinal() == axis && t > 1) {
                        // face breached: element fails through full thickness
                        int a = (int) (th >> 5 & 3), b = (int) (th >> 7 & 3);
                        int dx = axis == 0 ? 1 : 0, dy = axis == 1 ? 1 : 0, dz = axis == 2 ? 1 : 0;
                        for (int i = -b; i <= a; i++) take(wx + dx * i, y + dy * i, wz + dz * i, out);
                    } else {
                        out.add(idx(wx, y, wz));
                    }
                    return;
                }
            }
            if (t <= 3) {
                if (p.leaves() || hl < 1e-6) return;
                while (height < 64 && y + height + 1 <= w.maxY - 1 && !w.air(wx, y + height + 1, wz)) height++;
                if (height < SLENDER) return;
            }
            topple(wx, y, wz, p, pr, ex, ez, height, seed, out, first);
        }

        /** Both ends of thickness row are sky-connected air (or breach). */
        boolean ends(int wx, int y, int wz, long th) {
            int axis = (int) (th >> 3 & 3), a = (int) (th >> 5 & 3), b = (int) (th >> 7 & 3);
            int dx = axis == 0 ? 1 : 0, dy = axis == 1 ? 1 : 0, dz = axis == 2 ? 1 : 0;
            return open(wx + dx * (a + 1), y + dy * (a + 1), wz + dz * (a + 1)) && open(wx - dx * (b + 1), y - dy * (b + 1), wz - dz * (b + 1));
        }

        /** Add position to fracture if breakable (liquid and unbreakable blocks inside element remain). */
        void take(int wx, int y, int wz, IntArrayList out) {
            if (!inside(wx, y, wz)) return;
            Props p = w.props(wx, y, wz);
            if (p.breakable() && p.full()) out.add(idx(wx, y, wz));
        }

        /**
         * Toppling: full-cube row from blast-facing face along axis away from epicenter (no thicker than {@link #MAX_TOPPLE}, sky
         * both sides) and height {@code H} above position: entire row breaks when {@code H/t ≥} {@link #SLENDER} and pressure sufficient.
         */
        void topple(int wx, int y, int wz, Props p, double pr, double ex, double ez, int height, int seed, IntArrayList out, boolean first) {
            boolean alongX = Math.abs(ex) >= Math.abs(ez);
            int step = alongX ? (ex > 0 ? -1 : 1) : (ez > 0 ? -1 : 1);
            double cos = alongX ? Math.abs(ex) : Math.abs(ez);
            int fx = alongX ? wx - step : wx, fz = alongX ? wz : wz - step;
            if (!open(fx, y, fz)) return; // face not beside sky
            // ground (cliffs, pillars, mesas) topples up to GROUND_TOPPLE; structures to MAX_TOPPLE
            int most = p.response().kind() == BlockResponse.Kind.GROUND ? GROUND_TOPPLE : MAX_TOPPLE;
            int run = 0;
            while (run <= most && full(alongX ? wx + step * run : wx, y, alongX ? wz : wz + step * run)) run++;
            if (run == 0 || run > most) return;
            int bx = alongX ? wx + step * run : wx, bz = alongX ? wz : wz + step * run;
            if (!open(bx, y, bz)) return; // no sky behind: mass, slope
            // lever arm only height where element stands alone with air behind: wall pier supports floor above story
            height = freeHeight(wx, y, wz, bx, bz, height);
            if (height < SLENDER * run) return;
            // beyond breaches (second/third rounds), only column topples: core, corner, pier; crosswalls and floors
            // support wide walls
            if (!first) {
                int a = 0, b = 0;
                while (a < most && full(alongX ? wx : wx + a + 1, y, alongX ? wz + a + 1 : wz)) a++;
                while (b < most && full(alongX ? wx : wx - b - 1, y, alongX ? wz - b - 1 : wz)) b++;
                if (a + b + 1 > most) return;
            }
            if (!p.response().breaksAt(pr * cos * height / run / K[3], seed)) return;
            for (int i = 0; i < run; i++) {
                int ax = alongX ? wx + step * i : wx, az = alongX ? wz : wz + step * i;
                if (w.has(ax, az)) take(ax, y, az, out);
            }
        }

        /**
         * Free height above position (at most {@code limit}): column blocks standing (not removed), with air or breach
         * behind in column {@code bx, bz}. Floor or lintel behind ends lever arm.
         */
        int freeHeight(int wx, int y, int wz, int bx, int bz, int limit) {
            int k = 0;
            while (k < limit) {
                int yy = y + k + 1;
                if (isGone(wx, yy, wz) || w.air(wx, yy, wz)) break;
                if (!isGone(bx, yy, bz) && !w.air(bx, yy, bz)) break;
                k++;
            }
            return k;
        }

        /** Full cube in toppling row: includes liquid (no sky beyond water), excludes round-1 removals. */
        boolean full(int wx, int y, int wz) {
            if (isGone(wx, y, wz)) return false;
            Props p = w.props(wx, y, wz);
            return p.full() || p.fluid();
        }

        /** Solid full cube for thickness: excludes liquid and round-1 removals. */
        boolean solid(int wx, int y, int wz) {
            return !isGone(wx, y, wz) && w.props(wx, y, wz).full();
        }

        /** Liquid (not removed): no pressure difference beyond it. */
        boolean wet(int wx, int y, int wz) {
            return inside(wx, y, wz) && !isGone(wx, y, wz) && w.props(wx, y, wz).fluid();
        }

        /**
         * Element thickness through position: shortest axis-aligned solid-full-cube row (4 means mass). Axis with
         * water at both ends excluded: wave cannot push through water on both sides (pond glass intact); aquarium
         * glass with water only behind is thin: air-facing face receives pressure, fragile glass breaks.
         * Packed: bits 0–2 thickness, 3–4 axis, 5–6 and 7–8 cube count in positive/negative axis directions.
         */
        long thickness(int wx, int y, int wz, Props self) {
            if (!self.full()) return 1 | 1L << 3;
            int best = 4, bestAxis = 0, bestA = 0, bestB = 0;
            for (int axis = 0; axis < 3 && best > 1; axis++) {
                int dx = axis == 0 ? 1 : 0, dy = axis == 1 ? 1 : 0, dz = axis == 2 ? 1 : 0;
                int a = 0, b = 0;
                while (a < 3 && solid(wx + dx * (a + 1), y + dy * (a + 1), wz + dz * (a + 1))) a++;
                if (a >= 3) continue;
                while (b < 3 && solid(wx - dx * (b + 1), y - dy * (b + 1), wz - dz * (b + 1))) b++;
                if (b >= 3 || a + b + 1 >= best) continue;
                if (wet(wx + dx * (a + 1), y + dy * (a + 1), wz + dz * (a + 1)) && wet(wx - dx * (b + 1), y - dy * (b + 1), wz - dz * (b + 1))) continue;
                best = a + b + 1;
                bestAxis = axis;
                bestA = a;
                bestB = b;
            }
            return best | (long) bestAxis << 3 | (long) bestA << 5 | (long) bestB << 7;
        }

        /** Air below column tops connected to sky through openings, at most {@link #SKY_DEPTH} deep. */
        void skyAir() {
            IntArrayList queue = new IntArrayList();
            for (int wz = 0; wz < SIDE; wz++) {
                for (int wx = 0; wx < SIDE; wx++) {
                    int t = w.top(wx, wz);
                    if (t == Integer.MIN_VALUE || !w.has(wx, wz)) continue;
                    int low = Math.max(yb, Math.min(t, lowTop[wz * SIDE + wx]) - 1);
                    for (Direction dir : Direction.Plane.HORIZONTAL) {
                        int nx = wx + dir.getStepX(), nz = wz + dir.getStepZ();
                        if (nx < 0 || nx >= SIDE || nz < 0 || nz >= SIDE || !w.has(nx, nz)) continue;
                        // column positions above neighbor's top see sky sideways
                        for (int y = Math.max(low, topOr(nx, nz) + 1); y <= Math.min(t, yt); y++) {
                            int i = idx(wx, y, wz);
                            if (sky[i] != 0 || !w.air(wx, y, wz)) continue;
                            sky[i] = 1;
                            queue.add(i);
                        }
                    }
                    // also below open sky (overhang, arch): air immediately beneath position above neighboring column top is higher
                }
            }
            for (int q = 0; q < queue.size(); q++) {
                int i = queue.getInt(q), depth = sky[i];
                if (depth >= SKY_DEPTH) continue;
                int wx = i % SIDE, wz = (i / SIDE) % SIDE, y = i / (SIDE * SIDE) + yb;
                for (Direction dir : Direction.values()) {
                    int nx = wx + dir.getStepX(), ny = y + dir.getStepY(), nz = wz + dir.getStepZ();
                    if (!inside(nx, ny, nz) || ny > w.top(nx, nz)) continue;
                    int n = idx(nx, ny, nz);
                    if (sky[n] != 0 || !w.air(nx, ny, nz)) continue;
                    sky[n] = (byte) (depth + 1);
                    queue.add(n);
                }
            }
        }

        /** Supported trunks topple whole away from epicenter (before per-block wave; unsupported leaves follow, {@link Collapse}). */
        void fellTrees() {
            for (int wz = LO - ROUND_ONE; wz < HI + ROUND_ONE; wz++) {
                for (int wx = LO - ROUND_ONE; wx < HI + ROUND_ONE; wx++) {
                    int t = w.top(wx, wz);
                    if (t == Integer.MIN_VALUE || !w.has(wx, wz)) continue;
                    for (int y = Math.min(t, yt); y > yb; y--) {
                        Props p = w.props(wx, y, wz);
                        if (!p.log() || isGone(wx, y, wz)) continue;
                        Props pb = w.props(wx, y - 1, wz);
                        if (pb.log() || pb.leaves() || !pb.bearing()) continue;
                        int x = w.x0 + wx, z = w.z0 + wz;
                        if (d.psi(new Vec3(x + 0.5, y + 0.5, z + 0.5)) < p.threshold()) continue;
                        // trunk only if tree: leaves above (otherwise log wall or pillar)
                        int height = 0;
                        while (height < 24 && y + height <= yt && w.props(wx, y + height, wz).log()) height++;
                        if (!w.props(wx, y + height, wz).leaves() && !nearLeaves(wx, y + height - 1, wz)) continue;
                        BlockState st = w.get(wx, y, wz);
                        for (int i = 0; i < height; i++) set(gone, idx(wx, y + i, wz));
                        if (wx >= LO && wx < HI && wz >= LO && wz < HI) {
                            double dx = x - burst.x, dz = z - burst.z;
                            Direction dir = Math.abs(dx) > Math.abs(dz) ? (dx > 0 ? Direction.EAST : Direction.WEST) : (dz > 0 ? Direction.SOUTH : Direction.NORTH);
                            trees.add(new long[]{BlockPos.asLong(x, y, z), height, dir.get3DDataValue()});
                            treeLogs.add(st);
                        }
                        break; // support below trunk base
                    }
                }
            }
        }

        boolean nearLeaves(int wx, int y, int wz) {
            for (Direction dir : Direction.values()) if (w.props(wx + dir.getStepX(), y + dir.getStepY(), wz + dir.getStepZ()).leaves()) return true;
            return false;
        }
    }
}
