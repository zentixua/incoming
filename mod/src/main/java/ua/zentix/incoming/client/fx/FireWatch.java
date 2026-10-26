package ua.zentix.incoming.client.fx;

import it.unimi.dsi.fastutil.longs.Long2LongMap;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.BaseFireBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainer;
import org.jetbrains.annotations.Nullable;
import ua.zentix.incoming.client.nuclear.ClientNuclear;

import java.util.List;
import java.util.Locale;

/**
 * Burning nuclear city fire visible to the client: fire blocks in local chunks near detonations (ruin circle plus
 * {@link #NUKE_MARGIN} blocks beyond it, where {@code NukeAfter} counts fire for roar and inward wind), chunk by
 * chunk within {@link #BUDGET_NANOS} per tick (the whole render circle takes seconds; fires change over minutes).
 * The mod does not inspect fire outside detonations: world fire, including conventional-strike ignitions, burns/smokes like all
 * game fire (vanilla or the combustion mod). No detonation means no scan.
 * <p>
 * Sections without fire in their palette are skipped ({@code maybeHas}); others sampled: every {@link #SAMPLE}th block,
 * count ×{@link #SAMPLE}. Full counting ({@code PalettedContainer.count}: all 4096 positions into a new map) would cost milliseconds
 * per tick: detailed cities use a global section palette ({@code maybeHas} always true), while small palettes retain spent fire.
 * This fire lets burning-city sound place nearby fire at its actual position, and scale the distant model ({@code FireField})
 * to actual burning (client does not know server fire settings: fires may be disabled/extinguished).
 * <p>
 * Fire on burning crater-fuel cells is not wood fire ({@link FuelCells}): crater sources carry its heat and soot;
 * excluded from the count.
 */
public final class FireWatch {
    /** Fire time per tick, ns (at least one chunk). */
    static final long BUDGET_NANOS = 100_000;
    /** Maximum chunks per tick. */
    static final int MAX_PER_TICK = 8;
    /** Section sampling: every this many blocks (two x positions out of 16, all y/z). */
    static final int SAMPLE = 8;
    /** Blocks beyond the ruin circle: how far {@code NukeAfter} counts detonation fire. */
    public static final double NUKE_MARGIN = 64;
    /** Chunk → (fire count << 16) | fire height above world bottom. */
    private static final Long2LongOpenHashMap SEEN = new Long2LongOpenHashMap();
    @Nullable
    private static ClientLevel level;
    private static int cursor, total, phase;
    /** Cost since last scenario line: time in fire ticks, tick count, longest tick (ns), chunks/sections. */
    private static long spent, ticks, slowest, chunks, scanned;

    private FireWatch() {}

    /** Another portion of chunks near nuclear detonations within {@code radius} chunks of camera chunk. */
    public static void tick(ClientLevel lvl, int cx, int cz, int radius) {
        long t0 = System.nanoTime();
        if (lvl != level) reset();
        level = lvl;
        List<ClientNuclear.Active> nukes = ClientNuclear.detonations();
        if (nukes.isEmpty()) {
            if (!SEEN.isEmpty()) {
                SEEN.clear();
                total = 0;
            }
            return;
        }
        // scan only bounds of detonation circles within render square: a distant detonation does not cost a whole-circle scan
        int n = nukes.size();
        double[] bx = new double[n], bz = new double[n], reach = new double[n];
        for (int j = 0; j < n; j++) {
            ClientNuclear.Active a = nukes.get(j);
            bx[j] = a.d.burst().x;
            bz[j] = a.d.burst().z;
            reach[j] = a.d.ruinRadius() + NUKE_MARGIN;
        }
        Box box = box(bx, bz, reach, cx, cz, radius);
        if (box == null) {
            if (!SEEN.isEmpty()) {
                SEEN.clear();
                total = 0;
            }
            return;
        }
        int w = box.x1 - box.x0 + 1, all = w * (box.z1 - box.z0 + 1);
        // every step counts toward time, even chunks outside circles: bounds of several detonations can cover almost the whole square
        for (int k = 0, step = 0; k < MAX_PER_TICK && step < all; step++) {
            if (step > 0 && System.nanoTime() - t0 > BUDGET_NANOS) break;
            int i = cursor++ % all;
            if (cursor >= all) {
                cursor = 0;
                phase = (phase + 1) % SAMPLE;
                prune(lvl, box);
            }
            int x = box.x0 + i % w, z = box.z0 + i / w;
            long key = ChunkPos.asLong(x, z);
            long was = SEEN.remove(key);
            total -= (int) (was >>> 16);
            if (!nuclear(bx, bz, reach, x, z)) continue;
            k++;
            LevelChunk chunk = lvl.getChunkSource().getChunk(x, z, false);
            if (chunk == null) continue;
            chunks++;
            long now = scan(chunk, phase);
            if (now != 0) {
                SEEN.put(key, now);
                total += (int) (now >>> 16);
            }
        }
        long dt = System.nanoTime() - t0;
        spent += dt;
        ticks++;
        slowest = Math.max(slowest, dt);
    }

    /** Inclusive chunk bounds. */
    record Box(int x0, int z0, int x1, int z1) {
        boolean has(int x, int z) {
            return x >= x0 && x <= x1 && z >= z0 && z <= z1;
        }
    }

    /**
     * Bounds of chunks intersecting detonation circles (center {@code bx, bz}, radius {@code reach}, blocks), within the square
     * {@code radius} chunks from camera chunk; {@code null} if none.
     */
    @Nullable
    static Box box(double[] bx, double[] bz, double[] reach, int cx, int cz, int radius) {
        int x0 = Integer.MAX_VALUE, z0 = Integer.MAX_VALUE, x1 = Integer.MIN_VALUE, z1 = Integer.MIN_VALUE;
        for (int j = 0; j < bx.length; j++) {
            x0 = Math.min(x0, Math.floorDiv((int) Math.floor(bx[j] - reach[j]), 16));
            z0 = Math.min(z0, Math.floorDiv((int) Math.floor(bz[j] - reach[j]), 16));
            x1 = Math.max(x1, Math.floorDiv((int) Math.floor(bx[j] + reach[j]), 16));
            z1 = Math.max(z1, Math.floorDiv((int) Math.floor(bz[j] + reach[j]), 16));
        }
        x0 = Math.max(x0, cx - radius);
        z0 = Math.max(z0, cz - radius);
        x1 = Math.min(x1, cx + radius);
        z1 = Math.min(z1, cz + radius);
        return x0 > x1 || z0 > z1 ? null : new Box(x0, z0, x1, z1);
    }

    /** Chunk (x, z) intersects a detonation circle: center within radius plus chunk half-diagonal. */
    static boolean nuclear(double[] bx, double[] bz, double[] reach, int x, int z) {
        double mx = x * 16 + 8, mz = z * 16 + 8;
        for (int j = 0; j < bx.length; j++) {
            double r = reach[j] + 12, dx = mx - bx[j], dz = mz - bz[j];
            if (dx * dx + dz * dz <= r * r) return true;
        }
        return false;
    }

    /**
     * Sampled chunk fire: (count << 16) | mean height; {@code offset} shifts sampling per scan. Burning crater-fuel
     * cells are excluded: their smoke belongs to the crater source ({@link FuelCells}).
     */
    private static long scan(LevelChunk chunk, int offset) {
        LevelChunkSection[] sections = chunk.getSections();
        long count = 0, ySum = 0;
        boolean fuel = FireSmoke.FUEL.inChunk(chunk.getPos().toLong());
        int bx = chunk.getPos().getMinBlockX(), bz = chunk.getPos().getMinBlockZ(), minY = chunk.getMinBuildHeight();
        for (int i = 0; i < sections.length; i++) {
            LevelChunkSection s = sections[i];
            if (s.hasOnlyAir() || !s.maybeHas(FireWatch::fire)) continue;
            scanned++;
            PalettedContainer<BlockState> states = s.getStates();
            int n = 0;
            // section index is (y << 8) | (z << 4) | x
            for (int j = offset; j < 4096; j += SAMPLE) {
                if (fire(states.get(j & 15, j >> 8, (j >> 4) & 15))
                        && !(fuel && FireSmoke.FUEL.covers(bx + (j & 15), minY + i * 16 + (j >> 8), bz + ((j >> 4) & 15)))) n++;
            }
            n *= SAMPLE;
            count += n;
            ySum += (long) n * (i * 16 + 8);
        }
        if (count == 0) return 0;
        return Math.min(count, 0xFFFFFFL) << 16 | (ySum / count) & 0xFFFF;
    }

    private static boolean fire(BlockState state) {
        return state.getBlock() instanceof BaseFireBlock;
    }

    /** Chunks no longer on client or outside scan bounds: their fire is no longer reassessed and would never go out. */
    private static void prune(ClientLevel lvl, Box box) {
        for (var it = SEEN.long2LongEntrySet().iterator(); it.hasNext(); ) {
            Long2LongMap.Entry e = it.next();
            int x = ChunkPos.getX(e.getLongKey()), z = ChunkPos.getZ(e.getLongKey());
            if (!box.has(x, z) || lvl.getChunkSource().getChunk(x, z, false) == null) {
                total -= (int) (e.getLongValue() >>> 16);
                it.remove();
            }
        }
    }

    /** Fire by chunk. */
    public interface Visitor {
        void fire(double x, double y, double z, int count);
    }

    public static void forEach(Visitor v) {
        if (level == null) return;
        int minY = level.getMinBuildHeight();
        for (Long2LongMap.Entry e : SEEN.long2LongEntrySet()) {
            long key = e.getLongKey(), val = e.getLongValue();
            v.fire(ChunkPos.getX(key) * 16 + 8, minY + (val & 0xFFFF), ChunkPos.getZ(key) * 16 + 8, (int) (val >>> 16));
        }
    }

    public static int total() {
        return total;
    }

    public static void reset() {
        SEEN.clear();
        cursor = total = 0;
        level = null;
    }

    /**
     * For the {@code SCENARIO} line: per-tick cost since last line (mean/longest, ms), chunks and sections with fire
     * in palette per tick, total fire; resets counters.
     */
    public static String describe() {
        String s = ticks == 0 ? "watch -" : String.format(Locale.ROOT, "watch %.3f/%.3f ms/tick chunks %.1f sections %.1f fire %d",
                spent / 1e6 / ticks, slowest / 1e6, chunks / (double) ticks, scanned / (double) ticks, total);
        spent = ticks = slowest = chunks = scanned = 0;
        return s;
    }
}
