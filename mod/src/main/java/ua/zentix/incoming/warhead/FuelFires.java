package ua.zentix.incoming.warhead;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.BaseFireBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.FireBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.event.level.ChunkWatchEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import ua.zentix.incoming.IncomingConfig;
import ua.zentix.incoming.net.S2C;
import ua.zentix.incoming.nuclear.model.FuelPool;
import ua.zentix.incoming.registry.ModAttachments;
import ua.zentix.incoming.strike.WeaponType;
import ua.zentix.incoming.util.Terrain;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.PriorityQueue;

/**
 * Fuel left in crater after fireball ({@link FuelPool}: tank fraction, crater area, thin-layer burnout) burns as
 * Minecraft fire on actual blocks while available. Law determines amount/duration; blocks determine location:
 * liquid fuel falls to support and flows down ({@link #cells}).
 * <ul>
 * <li><b>Where.</b> Down from impact to first shaped block (crater floor, floor under pierced roof, ground under
 * bridge); no fire on water or void (water film cannot sustain burn, as client). Spread horizontally from
 * existing fuel cells, lower first (off edges down to support), then closer to landing point; one-block upward
 * surface step allowed (splashes across whole crater bowl, not only bottom), not two-block walls or through them.
 * Count by law area ({@link FuelPool#cells}: Shahed 5, missile 9). Only with ready neighboring chunks too: fire
 * survival reads surroundings ({@link #holds}).</li>
 * <li><b>Fire.</b> Vanilla {@link BaseFireBlock#getState}: on nonflammable ground, top-face flame like
 * netherrack; on fuel, burns by game rules; spread follows game/fire-mod rules.</li>
 * <li><b>While burning.</b> Vanilla fire on stone/ground dies in seconds (age above 3 without nearby fuel), so
 * keep cells with vanilla age mechanism: {@code setBlock} age 0, {@link Block#UPDATE_INVISIBLE}, no mixin.
 * Extinguished externally (rain, hand, fire mod) relights: rain cannot extinguish burning hydrocarbon pool
 * ({@link #RELIGHT}). If cell dies almost immediately (fire mod replaces/extinguishes every tick, Burnt on ground
 * in rain), do not fight world: double retry interval up to {@link #RELIGHT_MAX}; fuel still burns by law, client
 * smoke/light/rumble continue. Other mod's {@code FireBlock} counts as fire too; reset age only if present.
 * Burial/flooding ends cell fuel. Broken/burned support drips fuel down same column to next support: no
 * unsupported fire.</li>
 * <li><b>How long.</b> Cells expire by law: burning area fraction {@link FuelPool#share}, highest film edge
 * first, lowest holding most fuel last ({@link FuelPool#cellSeconds}). Expired cell removes fire if only fuel
 * sustained it (no nearby combustible); by combustible fire continues naturally. Unready cell chunk (player left)
 * waits: unloaded fire does not tick; remove when ready again.</li>
 * </ul>
 * Clients receive {@link S2C.FuelFire}: crater state (cells, early extinguished, weapon, impact tick) at
 * creation, each change (flow, burial, flooding), and chunk delivery; client smoke/light/rumble follow same law
 * ({@code WeaponSpec.pool(lit)}). Saved with world ({@link ModAttachments#FUEL_FIRES}): after restart fuel burns
 * out on schedule.
 */
public final class FuelFires {
    /** Maximum downward support search for fuel falling off an edge/through roof, blocks. */
    static final int FALL = 64;
    /** Maximum spread-search cells per fuel cell (around walls/holes). */
    static final int SEARCH = 24;
    /**
     * Ticks until externally extinguished cell relights. Rain cannot extinguish gasoline/kerosene pool: denser
     * water sinks below fuel; vaporizing 10 mm/h downpour consumes ~7 kW/m² versus 20–60 kW/m² flame flux back to
     * surface (⚠ estimate from Babrauskas, SFPE Handbook, “Heat Release Rates”; such fires need foam, NFPA 11);
     * vanilla rain extinguishes fire on its tick.
     */
    static final int RELIGHT = 5;
    /**
     * Fire dying sooner after ignition means world cannot sustain it (fire mod extinguishes every rainy tick):
     * double retry delay, ticks. Vanilla rain/dry-weather fire mod extinguish on fire tick, no earlier than ~30
     * ticks, unaffected.
     */
    static final int QUICK = 5;
    /** Maximum retry interval for cell fire world will not sustain, ticks (10 s). */
    static final int RELIGHT_MAX = 200;

    final List<Pool> pools;
    private int nextId;
    /** Relight count and accounting time since world load (tests/probe). */
    private long relights, nanos, ticks;

    /** For {@link ModAttachments#FUEL_FIRES}: per-world. */
    public FuelFires() {
        this(0, new ArrayList<>());
    }

    private FuelFires(int nextId, List<Pool> pools) {
        this.nextId = nextId;
        this.pools = new ArrayList<>(pools);
    }

    public static final Codec<FuelFires> CODEC = RecordCodecBuilder.create(i -> i.group(
            Codec.INT.fieldOf("next").forGetter(f -> f.nextId),
            Pool.CODEC.listOf().fieldOf("pools").forGetter(f -> f.pools)).apply(i, FuelFires::new));

    /** Whether to save: burning fuel exists. */
    public boolean isEmpty() {
        return pools.isEmpty();
    }

    public static FuelFires get(ServerLevel level) {
        return level.getData(ModAttachments.FUEL_FIRES);
    }

    /**
     * One strike's burning fuel: cells in burnout order (0 longest), early-ended cells (buried, flooded, fell
     * into water), completed cells; law expiry game times from weapon/impact tick. Relight retries memory-only.
     */
    static final class Pool {
        static final Codec<Pool> CODEC = RecordCodecBuilder.create(i -> i.group(
                Codec.INT.fieldOf("id").forGetter(p -> p.id),
                Codec.INT.fieldOf("weapon").forGetter(p -> p.weapon),
                Codec.LONG.fieldOf("blast").forGetter(p -> p.blastTick),
                Codec.DOUBLE.fieldOf("reach").forGetter(p -> p.reach),
                Codec.LONG.listOf().fieldOf("cells").forGetter(p -> java.util.Arrays.stream(p.cells).map(BlockPos::asLong).toList()),
                Codec.LONG.fieldOf("smothered").forGetter(p -> p.smothered),
                Codec.LONG.fieldOf("done").forGetter(p -> p.done)).apply(i, Pool::new));

        final int id, weapon;
        final long blastTick;
        final double reach;
        final BlockPos[] cells;
        final long[] out, relight, relitAt, seen;
        final int[] backoff;
        /** Cells (indexed bits) whose fuel ended early; completed cells. */
        long smothered, done;

        Pool(int id, int weapon, long blastTick, double reach, List<Long> cells, long smothered, long done) {
            this.id = id;
            this.weapon = weapon;
            this.blastTick = blastTick;
            this.reach = reach;
            int n = cells.size();
            this.cells = new BlockPos[n];
            for (int k = 0; k < n; k++) this.cells[k] = BlockPos.of(cells.get(k));
            this.smothered = smothered;
            this.done = done;
            FuelPool law = law(weapon, n);
            this.out = new long[n];
            this.relight = new long[n];
            this.relitAt = new long[n];
            this.seen = new long[n];
            this.backoff = new int[n];
            for (int k = 0; k < n; k++) {
                out[k] = blastTick + Math.round(20 * law.cellSeconds(k, n));
                relitAt[k] = blastTick;
                backoff[k] = RELIGHT;
            }
        }

        boolean done(int k) {
            return (done >>> k & 1) != 0;
        }

        void finish(int k, boolean early) {
            done |= 1L << k;
            if (early) smothered |= 1L << k;
        }

        boolean finished() {
            return Long.bitCount(done) >= cells.length;
        }

        S2C.FuelFire packet() {
            return new S2C.FuelFire(id, List.of(cells), smothered, weapon, blastTick);
        }
    }

    /** Fuel law for {@code weapon} spread on {@code lit} cells ({@code WeaponSpec.pool(lit)}). */
    static FuelPool law(int weapon, int lit) {
        return WeaponType.byId(weapon).spec().pool(lit);
    }

    /**
     * {@code weapon} fuel left in crater at {@code pos} (impact {@code blastTick}) settles on blocks/burns:
     * cells, fire, accounting, client packet within {@code reach}. Returns ignited count.
     */
    static int pour(ServerLevel level, Vec3 pos, WeaponType weapon, long blastTick, double reach) {
        if (!IncomingConfig.SERVER.fire.get()) return 0;
        int n = weapon.spec().pool().cells();
        if (n <= 0 || n > S2C.FuelFire.MAX_CELLS) return 0;
        List<Long> lit = new ArrayList<>(n);
        for (BlockPos p : cells(level, pos, n)) {
            if (level.getBlockState(p).getBlock() instanceof BaseFireBlock || Warheads.igniteIn(level, p)) lit.add(p.asLong());
        }
        if (lit.isEmpty()) return 0;
        FuelFires all = get(level);
        Pool pool = new Pool(all.nextId++, weapon.id(), blastTick, reach, lit, 0, 0);
        all.pools.add(pool);
        send(level, pool);
        return lit.size();
    }

    /** Tests: {@code weapon} fuel after impact at {@code pos} this tick, without explosion/packet. */
    public static int testPour(ServerLevel level, Vec3 pos, WeaponType weapon) {
        return testPour(level, pos, weapon, 0);
    }

    /** Tests: same, impact {@code burned} ticks ago, already burned that long. */
    public static int testPour(ServerLevel level, Vec3 pos, WeaponType weapon, long burned) {
        return testPour(level, pos, weapon, burned, 0);
    }

    /** Delivery tests: same with packet to players within {@code reach}, like impact. */
    public static int testPour(ServerLevel level, Vec3 pos, WeaponType weapon, long burned, double reach) {
        return pour(level, pos, weapon, level.getGameTime() - burned, reach);
    }

    /** Crater state to players within blast-packet reach (position first cell). */
    private static void send(ServerLevel level, Pool pool) {
        if (pool.reach <= 0) return;
        BlockPos at = pool.cells[0];
        PacketDistributor.sendToPlayersNear(level, null, at.getX() + 0.5, at.getY(), at.getZ() + 0.5, pool.reach, pool.packet());
    }

    /** Chunk sent to player (joined, approached, returned): send current crater states whose first cell is there. */
    public static void onChunkSent(ChunkWatchEvent.Sent e) {
        FuelFires all = e.getLevel().getExistingDataOrNull(ModAttachments.FUEL_FIRES.get());
        if (all == null || all.pools.isEmpty()) return;
        long chunk = e.getPos().toLong();
        for (Pool pool : all.pools) {
            if (pool.reach > 0 && ChunkPos.asLong(pool.cells[0]) == chunk) PacketDistributor.sendToPlayer(e.getPlayer(), pool.packet());
        }
    }

    /**
     * Where {@code n} fuel cells at {@code pos} settle: down to support ({@link #land}), spread lower then closer
     * to landing, one-block upward surface step ({@link #step}), burning where fire survives ({@link #holds});
     * fuel crosses nonholding cells (slab, dirt path) without burning. Lowest first (longest burn). Empty means
     * nowhere: water, void, unready chunks.
     */
    static List<BlockPos> cells(ServerLevel level, Vec3 pos, int n) {
        BlockPos start = BlockPos.containing(pos);
        if (!Terrain.ready(level, start)) return List.of();
        // Impact point inside block (half-block into ground): fuel above it.
        if (!open(level, start)) start = start.above();
        if (!open(level, start)) return List.of();
        BlockPos land = land(level, start);
        if (land == null) return List.of();
        Comparator<BlockPos> lower = Comparator.<BlockPos>comparingInt(BlockPos::getY)
                .thenComparingInt(p -> horizontal(p, land)).thenComparingLong(BlockPos::asLong);
        PriorityQueue<BlockPos> front = new PriorityQueue<>(lower);
        LongOpenHashSet seen = new LongOpenHashSet();
        front.add(land);
        seen.add(land.asLong());
        List<BlockPos> out = new ArrayList<>(n);
        for (int looked = 0; !front.isEmpty() && out.size() < n && looked < SEARCH * n; looked++) {
            BlockPos p = front.poll();
            if (holds(level, p)) out.add(p);
            for (Direction d : Direction.Plane.HORIZONTAL) {
                BlockPos q = p.relative(d);
                if (!Terrain.ready(level, q)) continue;
                // Off edge down to support; one-block surface step (splashes whole crater bowl, not just
                // floor); no higher or through wall.
                BlockPos to = open(level, q) ? land(level, q) : step(level, q);
                if (to != null && seen.add(to.asLong())) front.add(to);
            }
        }
        out.sort(Comparator.comparingInt(BlockPos::getY));
        return out;
    }

    /** Free cell above shaped block {@code q} (one-block surface step), otherwise null. */
    private static BlockPos step(ServerLevel level, BlockPos q) {
        BlockPos up = q.above();
        if (level.getBlockState(q).getCollisionShape(level, q).isEmpty() || !level.isInWorldBounds(up) || !open(level, up)) return null;
        return up;
    }

    /** Squared horizontal distance. */
    private static int horizontal(BlockPos a, BlockPos b) {
        int dx = a.getX() - b.getX(), dz = a.getZ() - b.getZ();
        return dx * dx + dz * dz;
    }

    /**
     * Free {@code p} passes fuel (air, fire, shapeless plant, not fluid): descend to first cell over collision
     * shape, no deeper than {@link #FALL}. null if water/lava below, void, or unready chunk.
     */
    static BlockPos land(ServerLevel level, BlockPos p) {
        BlockPos.MutableBlockPos at = p.mutable();
        for (int i = 0; i <= FALL; i++) {
            BlockPos below = at.below();
            if (!level.isInWorldBounds(below)) return null;
            BlockState s = level.getBlockState(below);
            if (!s.getFluidState().isEmpty()) return null;
            if (!s.getCollisionShape(level, below).isEmpty()) return at.immutable();
            at.move(Direction.DOWN);
        }
        return null;
    }

    /** Fuel passes air, fire, shapeless plants, not fluid or shaped blocks. */
    static boolean open(ServerLevel level, BlockPos p) {
        BlockState s = level.getBlockState(p);
        return s.isAir() || s.getFluidState().isEmpty() && s.getCollisionShape(level, p).isEmpty();
    }

    /**
     * Minecraft fire survives in cell (support top or nearby fuel). Vanilla reads all six neighbors
     * (flammability, support, soul fire), including adjacent chunk at edge; require cell chunk and all neighbors
     * ready, or it loads synchronously in tick.
     */
    static boolean holds(ServerLevel level, BlockPos p) {
        return Terrain.neighbourhoodReady(level, p.getX() >> 4, p.getZ() >> 4) && BaseFireBlock.getState(level, p).canSurvive(level, p);
    }

    /** Each world tick: sustain cell fire while fuel remains, remove expired fire, send changes to clients. */
    public static void onLevelTick(LevelTickEvent.Post event) {
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        FuelFires all = level.getExistingDataOrNull(ModAttachments.FUEL_FIRES.get());
        if (all == null || all.pools.isEmpty()) return;
        long t0 = System.nanoTime(), now = level.getGameTime();
        all.pools.removeIf(pool -> {
            boolean changed = false;
            for (int k = 0; k < pool.cells.length; k++) if (!pool.done(k)) changed |= all.hold(level, pool, k, now);
            if (changed) send(level, pool);
            return pool.finished();
        });
        all.nanos += System.nanoTime() - t0;
        all.ticks++;
    }

    /** Crater cell {@code k} at {@code now}; whether client-known state changed (position/early fuel end). */
    private boolean hold(ServerLevel level, Pool pool, int k, long now) {
        BlockPos cell = pool.cells[k];
        // Unready chunk (player left): fire neither ticks nor dies; if burned out, remove once ready.
        if (!Terrain.neighbourhoodReady(level, cell.getX() >> 4, cell.getZ() >> 4)) return false;
        if (now >= pool.out[k]) {
            burnOut(level, cell);
            pool.finish(k, false);
            return false;
        }
        BlockPos below = cell.below();
        boolean moved = false;
        if (level.getBlockState(below).getCollisionShape(level, below).isEmpty()) {
            // Support burned/broken: fuel drips down same column; fire at old site no longer belongs to it.
            BlockPos to = land(level, cell);
            if (to == null) {
                pool.finish(k, true);
                return true;
            }
            pool.cells[k] = cell = to;
            pool.relight[k] = now;
            pool.backoff[k] = RELIGHT;
            moved = true;
        }
        BlockState s = level.getBlockState(cell);
        if (s.getBlock() instanceof BaseFireBlock) {
            pool.seen[k] = now;
            // Vanilla fire-age update: no neighbors/client packet (age not rendered).
            if (s.hasProperty(FireBlock.AGE) && s.getValue(FireBlock.AGE) > 0) level.setBlock(cell, s.setValue(FireBlock.AGE, 0), Block.UPDATE_INVISIBLE);
            return moved;
        }
        // Cell buried/flooded: fuel ended.
        if (!open(level, cell)) {
            pool.finish(k, true);
            return true;
        }
        if (now >= pool.relight[k]) {
            // Fire failed within QUICK ticks of last ignition: world cannot sustain it (rainy fire mod), retry less often.
            long alive = Math.max(0, pool.seen[k] - pool.relitAt[k]);
            pool.backoff[k] = !moved && alive < QUICK ? Math.min(RELIGHT_MAX, 2 * pool.backoff[k]) : RELIGHT;
            pool.relight[k] = now + pool.backoff[k];
            pool.relitAt[k] = now;
            if (Warheads.igniteIn(level, cell)) relights++;
        }
        return moved;
    }

    /**
     * Cell fuel exhausted: extinguish if fuel alone sustained fire, no nearby combustible or eternal-fire support
     * (netherrack); by combustible, game fire continues.
     */
    private static void burnOut(ServerLevel level, BlockPos cell) {
        BlockState s = level.getBlockState(cell);
        if (!(s.getBlock() instanceof BaseFireBlock)) return;
        BlockPos below = cell.below();
        if (level.getBlockState(below).isFireSource(level, below, Direction.UP)) return;
        for (Direction d : Direction.values()) {
            BlockPos n = cell.relative(d);
            if (level.getBlockState(n).isFlammable(level, n, d.getOpposite())) return;
        }
        level.removeBlock(cell, false);
    }

    /** Unfinished fuel cells' current positions (tests). */
    public List<BlockPos> cells() {
        List<BlockPos> out = new ArrayList<>();
        for (Pool p : pools) for (int k = 0; k < p.cells.length; k++) if (!p.done(k)) out.add(p.cells[k]);
        return out;
    }

    /** Tests/probe: relights, accounting ticks, mean accounting time per tick, ms. */
    public String stats() {
        return String.format(java.util.Locale.ROOT, "pools %d cells %d relights %d ticks %d mean %.4f ms", pools.size(), cells().size(), relights, ticks,
                ticks == 0 ? 0 : nanos / 1e6 / ticks);
    }

    /** Tests: crater state as received by client. */
    public List<S2C.FuelFire> state() {
        return pools.stream().map(Pool::packet).toList();
    }

    /** Tests: cell relights since world load. */
    public long relights() {
        return relights;
    }
}
