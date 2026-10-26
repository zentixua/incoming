package ua.zentix.incoming.nuclear.world;

import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongHeapPriorityQueue;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.DoublePlantBlock;
import net.minecraft.world.level.block.FaceAttachedHorizontalDirectionalBlock;
import net.minecraft.world.level.block.LadderBlock;
import net.minecraft.world.level.block.WallBannerBlock;
import net.minecraft.world.level.block.WallSignBlock;
import net.minecraft.world.level.block.WallTorchBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.AttachFace;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import ua.zentix.incoming.nuclear.Detonation;

/**
 * Chunk ruins after fracture ({@link Blast}): second physics process, <b>what lost support falls</b>, and its
 * consequences: leaves without trunks, water without bottom, attachments without their support.
 * <ul>
 * <li>Support distance to ground: 0-1 traversal from window bottom (up 0, sideways/down +1, up to {@link #C}); block holds while
 * distance within span ({@link Blast.Props#span}); structural ground (unreinforced masonry) only if support
 * path did not lengthen, and hanging masonry formerly held by a row between supports on both sides only while still held by
 * such row ({@link #unspanned}). Only wave-deprived support falls: {@code after > L}
 * and ({@code before ≤ L}, or {@code after ≥ before + 3}, avoiding slits around floor holes, or unsupported before strike
 * and touching broken block); already “hanging” natural arches/overhangs stand.</li>
 * <li>Computed only if wave broke load-bearing block with survivors above or beside it; uses chunk
 * window (±16 blocks), falls within chunk (exact within 8 blocks around it: support paths shorter than {@link #C}).</li>
 * <li>Leaves attach to trunks (like game: no farther than 6 leaves); fall without trunk.</li>
 * <li>Small water bodies (pool, fountain: up to {@link #MAX_DRAIN} positions and 16 blocks wide) with breached bottom or
 * wall drain entirely; large ones (river, sea) flow into breach through fluid ticks.</li>
 * </ul>
 */
final class Collapse {
    static final int C = 8, INF = C + 1;
    private static final int SIDE = RuinWindow.SIDE, LO = 16, HI = 32;
    private static final int MAX_DRAIN = 4096, DRAIN_WIDTH = 16, LEAF_REACH = 6;
    private static final BlockState AIR = Blocks.AIR.defaultBlockState();

    /** Traversal buffers per thread (no reentry): grow and reuse. */
    private static final class Buffers {
        byte[] before = new byte[0], after = new byte[0];
        int[] ring = new int[0];
    }

    private static final ThreadLocal<Buffers> BUFFERS = ThreadLocal.withInitial(Buffers::new);
    private byte[] before, after;
    private int[] ring;

    final RuinWindow w;
    final Blast[] blasts = new Blast[9];
    final int yb, yt, h;
    /** Lowest original window surface (column support top): all load-bearing blocks at or below it are ground/support. */
    final int surface;
    final long[] gone;
    /** Drained water. */
    final long[] drained;

    /** Chunk result: new state at position (key {@link BlockPos#asLong}). */
    final it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<BlockState> changes = new it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<>();
    /** Per chunk column: removed rubble-producing blocks and wooden subset; post-ruin ground (MIN absent). */
    final int[] removed = new int[256], wood = new int[256], ground = new int[256];
    /** Wave-broken leaves (position), for light-ignited fires. */
    final LongArrayList leaves = new LongArrayList();
    /** Fluid beside changed positions: tick it (including neighboring chunks beside this chunk's positions). */
    final LongArrayList fluidTicks = new LongArrayList();

    private final Detonation d;
    private final boolean blockDamage;

    private Collapse(RuinContext.Grid g) {
        this.w = g.windowOf(0, 0);
        this.d = g.ctx().d;
        this.blockDamage = g.blockDamage();
        for (int k = 0; k < 9; k++) {
            if (!w.present(k)) continue;
            blasts[k] = g.blast(k % 3 - 1, k / 3 - 1);
        }
        int low = Integer.MAX_VALUE, high = w.minY;
        for (int wz = 0; wz < SIDE; wz++) {
            for (int wx = 0; wx < SIDE; wx++) {
                int s = w.solid(wx, wz), t = w.top(wx, wz);
                if (s != Integer.MIN_VALUE) low = Math.min(low, s);
                if (t != Integer.MIN_VALUE) high = Math.max(high, t);
            }
        }
        // fracture may extend below lowest support (underwater breach, leaves above ravine)
        for (int k = 0; k < 9; k++) {
            if (blasts[k] == null || blasts[k].removed().length == 0) continue;
            int c = blasts[k].removed()[0];
            low = Math.min(low, w.minY + ((c >>> RuinPlan.SECTION_SHIFT) << 4) + ((c >> 8) & 15));
        }
        if (low == Integer.MAX_VALUE) low = high;
        int ground = Integer.MAX_VALUE;
        for (int wz = 0; wz < SIDE; wz++) {
            for (int wx = 0; wx < SIDE; wx++) {
                int s = w.solid(wx, wz);
                if (s != Integer.MIN_VALUE && w.has(wx, wz)) ground = Math.min(ground, s);
            }
        }
        this.surface = ground == Integer.MAX_VALUE ? w.minY - 1 : ground;
        this.yb = Math.max(w.minY, low - 16);
        this.yt = Math.min(w.maxY - 1, Math.max(high, yb) + 1);
        this.h = yt - yb + 1;
        this.gone = new long[(SIDE * SIDE * h + 63) >> 6];
        this.drained = new long[gone.length];
        for (int k = 0; k < 9; k++) {
            if (blasts[k] == null) continue;
            int bx = (k % 3) << 4, bz = (k / 3) << 4;
            for (int c : blasts[k].removed()) {
                int y = w.minY + ((c >>> RuinPlan.SECTION_SHIFT) << 4) + ((c >> 8) & 15);
                if (y < yb || y > yt) continue;
                set(gone, idx(bx + (c & 15), y, bz + ((c >> 4) & 15)));
            }
        }
    }

    /** Ruins of central snapshot chunk (any thread): window fractures cached per detonation or computed anew. */
    static Collapse solve(RuinContext.Grid g) {
        Collapse c = new Collapse(g);
        c.run();
        return c;
    }

    /** Snapshot IDs used to build chunk window. */
    RuinWindow.Stamp stamp() {
        return w.stamp();
    }

    Blast blast() {
        return blasts[4];
    }

    // ---------------------------------------------------------------- positions

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

    Blast.Props props(int wx, int y, int wz) {
        return w.props(wx, y, wz);
    }

    // ---------------------------------------------------------------- solution

    private void run() {
        // third wave round: survivors near neighbors' breaches (exact within 9 blocks around chunk, support read reach)
        IntArrayList breaks = new IntArrayList();
        for (int k = 0; k < 9; k++) {
            Blast b = blasts[k];
            if (b == null) continue;
            int bx = (k % 3) << 4, bz = (k / 3) << 4;
            for (int c : b.removed()) {
                int wx = bx + (c & 15), wz = bz + ((c >> 4) & 15), y = w.minY + ((c >>> RuinPlan.SECTION_SHIFT) << 4) + ((c >> 8) & 15);
                if (inside(wx, y, wz)) breaks.add(idx(wx, y, wz));
            }
        }
        if (!breaks.isEmpty()) {
            for (int e : Blast.remnants(w, d, blasts, C + 1, blockDamage)) {
                int wx = e & 63, wz = (e >> 6) & 63, y = w.minY + (e >>> 12);
                if (!inside(wx, y, wz) || isGone(wx, y, wz)) continue;
                set(gone, idx(wx, y, wz));
                breaks.add(idx(wx, y, wz));
            }
        }
        boolean logs = false, support = false;
        for (int q = 0; q < breaks.size(); q++) {
            int i = breaks.getInt(q), wx = i % SIDE, wz = (i / SIDE) % SIDE, y = i / (SIDE * SIDE) + yb;
            Blast.Props p = props(wx, y, wz);
            if (p.log()) logs = true;
            if (support || !p.bearing() || p.log()) continue;
            // survivors above/beside broken blocks may have lost support
            if (standing(wx, y + 1, wz) || standing(wx + 1, y, wz) || standing(wx - 1, y, wz) || standing(wx, y, wz + 1) || standing(wx, y, wz - 1)) support = true;
        }
        if (!logs) for (int k = 0; k < 9 && !logs; k++) logs = blasts[k] != null && !blasts[k].trees().isEmpty();
        if (support) support();
        if (logs) leaves();
        if (!breaks.isEmpty()) drain();
        attachments();
        collect();
    }

    private boolean standing(int wx, int y, int wz) {
        return inside(wx, y, wz) && !isGone(wx, y, wz) && props(wx, y, wz).bearing();
    }

    // ---------------------------------------------------------------- support

    private void support() {
        int n = SIDE * SIDE * h;
        buffers(n);
        long[] fixed = fixedCells();
        distances(before, false, n, fixed);
        distances(after, true, n, fixed);
        long[] down = new long[gone.length];
        IntArrayList fall = new IntArrayList(), masonry = new IntArrayList();
        for (int y = yb; y <= yt; y++) {
            for (int wz = LO - C; wz < HI + C; wz++) {
                for (int wx = LO - C; wx < HI + C; wx++) {
                    int i = idx(wx, y, wz);
                    if (bit(gone, i)) continue;
                    Blast.Props p = props(wx, y, wz);
                    if (!p.bearing()) continue;
                    if (falls(i, after[i], p)) {
                        fall.add(i);
                        set(down, i);
                    } else if (after[i] > 0 && p.span() > 0 && !p.fixed() && p.response().kind() == BlockResponse.Kind.GROUND
                            && spanned(wx, y, wz, false, down)) {
                        masonry.add(i);
                    }
                }
            }
        }
        // masonry made cantilever ({@link #unspanned}) falls, lengthening others' support paths; repair distances
        // where support passed through it ({@link #reroute}), let new unsupported blocks fall; repeat to fixed point
        while (true) {
            int was = fall.size();
            if (!unspanned(masonry, down, fall)) break;
            IntArrayList lost = reroute(fall, was, down);
            for (int q = 0; q < lost.size(); q++) {
                int i = lost.getInt(q), wx = i % SIDE, wz = (i / SIDE) % SIDE, y = i / (SIDE * SIDE) + yb;
                if (wx < LO - C || wx >= HI + C || wz < LO - C || wz >= HI + C || bit(down, i)) continue;
                if (falls(i, after[i], props(wx, y, wz))) {
                    fall.add(i);
                    set(down, i);
                }
            }
        }
        // structure unsupported even before strike within {@link #C} (hall wider than 2C, blocks on floating
        // platform): everything touching broken/falling blocks falls, cascading through connections to fixed point.
        // Ground does not fall this way: preexisting natural “hanging” arches/overhangs stand
        IntArrayList queue = new IntArrayList();
        for (int y = yb; y <= yt; y++) {
            for (int wz = LO - C; wz < HI + C; wz++) {
                for (int wx = LO - C; wx < HI + C; wx++) {
                    int i = idx(wx, y, wz);
                    if (!unsupported(i, wx, y, wz, down)) continue;
                    if (touches(wx, y, wz, gone) || touches(wx, y, wz, down)) {
                        set(down, i);
                        queue.add(i);
                    }
                }
            }
        }
        for (int q = 0; q < queue.size(); q++) {
            int i = queue.getInt(q), wx = i % SIDE, wz = (i / SIDE) % SIDE, y = i / (SIDE * SIDE) + yb;
            fall.add(i);
            for (Direction dir : Direction.values()) {
                int nx = wx + dir.getStepX(), ny = y + dir.getStepY(), nz = wz + dir.getStepZ();
                if (nx < LO - C || nx >= HI + C || nz < LO - C || nz >= HI + C || !inside(nx, ny, nz)) continue;
                int m = idx(nx, ny, nz);
                if (!unsupported(m, nx, ny, nz, down)) continue;
                set(down, m);
                queue.add(m);
            }
        }
        for (int i = 0; i < fall.size(); i++) set(gone, fall.getInt(i));
    }

    /** Non-ground load-bearing block unsupported within {@link #C} before/after strike, not yet falling. */
    private boolean unsupported(int i, int wx, int y, int wz, long[] down) {
        if (bit(gone, i) || bit(down, i) || before[i] < INF || after[i] < INF) return false;
        Blast.Props p = props(wx, y, wz);
        return p.bearing() && !p.fixed() && p.response().kind() != BlockResponse.Kind.GROUND;
    }

    /** Touches broken/falling load-bearing block (nearby flower, torch, leaves do not start cascade). */
    private boolean touches(int wx, int y, int wz, long[] bits) {
        for (Direction dir : Direction.values()) {
            int nx = wx + dir.getStepX(), ny = y + dir.getStepY(), nz = wz + dir.getStepZ();
            if (inside(nx, ny, nz) && bit(bits, idx(nx, ny, nz)) && props(nx, ny, nz).bearing()) return true;
        }
        return false;
    }

    /**
     * Whether block with post-strike support {@code a} falls: beyond its span and wave removed support (closer before,
     * or lengthened by 3 or more; no farther than {@link #INF}). Unsupported before strike ({@code INF}) handled separately
     * ({@link #support}). Indestructible blocks never fall.
     */
    private boolean falls(int i, int a, Blast.Props p) {
        if (p.fixed()) return false;
        int b = before[i];
        // structural ground (terracotta, calcite, stone) is unreinforced masonry: once cracked it holds only as
        // before strike; cantilever whose support path lengthened falls
        if (a <= p.span()) return a > b && p.response().kind() == BlockResponse.Kind.GROUND;
        return b <= p.span() || b < INF && a >= Math.min(b + 3, INF);
    }

    /**
     * Structural ground, unreinforced masonry, holds as slab, lintel or arch: row between supports on both
     * sides. Hanging masonry (not on load-bearing block, support sideways), formerly held by such row on at least one axis
     * ({@code masonry}: {@link #spanned} before strike) but now on none, became cantilever and falls even if nearest support
     * unchanged (slab beside surviving core after walls fall: nearest positions keep same core distance). Cascade:
     * fall breaks neighbors' rows and support beneath blocks on it. Natural overhang/ledge was supported on one side
     * already and stands; holed floor can hold along other axis. New falls into {@code down}
     * and {@code fall}; order-independent: rows and supports below only disappear.
     *
     * @return whether anything fell
     */
    private boolean unspanned(IntArrayList masonry, long[] down, IntArrayList fall) {
        boolean any = false, changed = true;
        while (changed) {
            changed = false;
            for (int q = 0; q < masonry.size(); q++) {
                int i = masonry.getInt(q);
                if (bit(down, i)) continue;
                int wx = i % SIDE, wz = (i / SIDE) % SIDE, y = i / (SIDE * SIDE) + yb;
                if (standingAfter(wx, y - 1, wz, down) || spanned(wx, y, wz, true, down)) continue;
                set(down, i);
                fall.add(i);
                changed = any = true;
            }
        }
        return any;
    }

    /**
     * Post-strike support without fallen masonry ({@code fall} from {@code from}) recalculated only where paths passed through it,
     * not over whole window (same result: distances only increase). Position loses distance if no
     * supporting neighbor remains one step closer to support (same distance below): process ascending
     * distance, bottom-up, so supporting neighbors already resolved. Lost positions receive distance
     * from surviving neighbors by distance-bucket traversal ({@code after}).
     *
     * @return positions whose distance increased (may now fall, {@link #falls})
     */
    private IntArrayList reroute(IntArrayList fall, int from, long[] down) {
        long[] seen = new long[gone.length], lost = new long[gone.length];
        LongHeapPriorityQueue queue = new LongHeapPriorityQueue();
        IntArrayList out = new IntArrayList();
        for (int q = from; q < fall.size(); q++) successors(fall.getInt(q), queue, down, lost);
        while (!queue.isEmpty()) {
            int i = (int) queue.dequeueLong();
            if (bit(seen, i)) continue;
            set(seen, i);
            int wx = i % SIDE, wz = (i / SIDE) % SIDE, y = i / (SIDE * SIDE) + yb;
            boolean held = false;
            for (Direction dir : Direction.values()) {
                int nx = wx + dir.getStepX(), ny = y + dir.getStepY(), nz = wz + dir.getStepZ();
                if (!standingAfter(nx, ny, nz, down)) continue;
                int m = idx(nx, ny, nz);
                // neighbor below supports at same distance (up 0), others one step closer
                if (!bit(lost, m) && after[m] + (dir == Direction.DOWN ? 0 : 1) == after[i] && leads(m, nx, ny, nz)) {
                    held = true;
                    break;
                }
            }
            if (held) continue;
            set(lost, i);
            out.add(i);
            if (leads(i, wx, y, wz)) successors(i, queue, down, lost);
        }
        IntArrayList[] buckets = new IntArrayList[C + 1];
        for (int q = 0; q < out.size(); q++) after[out.getInt(q)] = INF;
        for (int q = 0; q < out.size(); q++) {
            int i = out.getInt(q), wx = i % SIDE, wz = (i / SIDE) % SIDE, y = i / (SIDE * SIDE) + yb, best = INF;
            for (Direction dir : Direction.values()) {
                int nx = wx + dir.getStepX(), ny = y + dir.getStepY(), nz = wz + dir.getStepZ();
                if (!standingAfter(nx, ny, nz, down)) continue;
                int m = idx(nx, ny, nz);
                if (bit(lost, m) || !leads(m, nx, ny, nz)) continue;
                best = Math.min(best, after[m] + (dir == Direction.DOWN ? 0 : 1));
            }
            if (best > C) continue;
            after[i] = (byte) best;
            (buckets[best] == null ? buckets[best] = new IntArrayList() : buckets[best]).add(i);
        }
        for (int d = 1; d <= C; d++) {
            IntArrayList b = buckets[d];
            for (int q = 0; b != null && q < b.size(); q++) {
                int i = b.getInt(q), wx = i % SIDE, wz = (i / SIDE) % SIDE, y = i / (SIDE * SIDE) + yb;
                if (after[i] != d || !leads(i, wx, y, wz)) continue;
                for (Direction dir : Direction.values()) {
                    int nx = wx + dir.getStepX(), ny = y + dir.getStepY(), nz = wz + dir.getStepZ();
                    if (!standingAfter(nx, ny, nz, down)) continue;
                    int m = idx(nx, ny, nz), nd = d + (dir == Direction.UP ? 0 : 1);
                    if (!bit(lost, m) || nd > C || after[m] <= nd) continue;
                    after[m] = (byte) nd;
                    (buckets[nd] == null ? buckets[nd] = new IntArrayList() : buckets[nd]).add(m);
                }
            }
        }
        return out;
    }

    /** Queue standing neighbors for {@link #reroute} whose support may pass through {@code i} (one step farther, above same distance). */
    private void successors(int i, LongHeapPriorityQueue queue, long[] down, long[] lost) {
        int wx = i % SIDE, wz = (i / SIDE) % SIDE, y = i / (SIDE * SIDE) + yb, di = after[i];
        for (Direction dir : Direction.values()) {
            int nx = wx + dir.getStepX(), ny = y + dir.getStepY(), nz = wz + dir.getStepZ();
            if (!standingAfter(nx, ny, nz, down)) continue;
            int m = idx(nx, ny, nz), nd = di + (dir == Direction.UP ? 0 : 1);
            if (bit(lost, m) || nd > C || after[m] != nd) continue;
            // distance then bottom-up: supporting positions resolved first
            queue.enqueue((long) (nd * h + ny - yb) << 32 | m);
        }
    }

    /** Support passes onward through position, as in {@link #distances}: own support or supported and not falling. */
    private boolean leads(int i, int wx, int y, int wz) {
        return after[i] == 0 || after[i] < INF && !falls(i, after[i], props(wx, y, wz));
    }

    /** Load-bearing position stands after strike: not broken or falling. */
    private boolean standingAfter(int wx, int y, int wz, long[] down) {
        if (!inside(wx, y, wz)) return false;
        int i = idx(wx, y, wz);
        return !bit(gone, i) && !bit(down, i) && props(wx, y, wz).bearing();
    }

    /**
     * Load-bearing row through position (x or z, same height) reaches ground-supported blocks on both sides (support
     * distance 0 with span: wall, column, core; not glass) within position's span, before or after strike.
     */
    private boolean spanned(int wx, int y, int wz, boolean afterBlast, long[] down) {
        int reach = props(wx, y, wz).span();
        return reaches(wx, y, wz, 1, 0, reach, afterBlast, down) && reaches(wx, y, wz, -1, 0, reach, afterBlast, down)
                || reaches(wx, y, wz, 0, 1, reach, afterBlast, down) && reaches(wx, y, wz, 0, -1, reach, afterBlast, down);
    }

    private boolean reaches(int wx, int y, int wz, int sx, int sz, int reach, boolean afterBlast, long[] down) {
        byte[] dist = afterBlast ? after : before;
        for (int k = 1; k <= reach; k++) {
            int x = wx + sx * k, z = wz + sz * k;
            if (!inside(x, y, z)) return false;
            int m = idx(x, y, z);
            Blast.Props p = props(x, y, z);
            // after strike must also not be falling from support loss: beyond fall-solving band ({@code down} absent there), falling
            // blocks would appear standing, making neighboring windows solve chunk-edge slab differently
            if (afterBlast ? !standingAfter(x, y, z, down) || !leads(m, x, y, z) : !p.bearing()) return false;
            // no span (glass, sand) means row end provides no support: windows break at 0.8 psi, so floor between core and
            // glass wall was already cantilevered from core, not a slab
            if (dist[m] == 0 && p.span() > 0) return true;
        }
        return false;
    }

    private boolean bearing(int i, int wx, int y, int wz, boolean afterBlast) {
        if (afterBlast && bit(gone, i)) return false;
        return props(wx, y, wz).bearing();
    }

    /** Indestructible load-bearing blocks in window (only sections whose palettes know them), support like ground. */
    private long[] fixedCells() {
        long[] out = new long[gone.length];
        for (int k = 0; k < 9; k++) {
            if (!w.present(k)) continue;
            int bx = (k % 3) << 4, bz = (k / 3) << 4;
            for (int sec = (yb - w.minY) >> 4; sec <= (yt - w.minY) >> 4; sec++) {
                if (!w.mayHave(k, sec, st -> w.view.get(st).fixed() && w.view.get(st).bearing())) continue;
                for (int y = Math.max(yb, w.minY + (sec << 4)); y <= Math.min(yt, w.minY + (sec << 4) + 15); y++) {
                    for (int lz = 0; lz < 16; lz++) {
                        for (int lx = 0; lx < 16; lx++) {
                            Blast.Props p = props(bx + lx, y, bz + lz);
                            if (p.fixed() && p.bearing()) set(out, idx(bx + lx, y, bz + lz));
                        }
                    }
                }
            }
        }
        return out;
    }

    /**
     * 0-1 traversal from supports (up 0, sideways/down +1, up to {@link #C}; beyond {@link #INF}). Ground supports: all
     * load-bearing blocks at window bottom no higher than lowest original window surface ({@link #surface}), plus indestructible blocks. After
     * strike falling block ({@link #falls}) supports nobody: traversal never passes through it; collapse cascades in one
     * traversal (positions leave queue ascending by distance, first distance final).
     */
    private void distances(byte[] dist, boolean afterBlast, int n, long[] fixed) {
        java.util.Arrays.fill(dist, 0, n, (byte) INF);
        int cap = n + 1, head = 0, tail = 0;
        for (int y = yb; y <= yt; y++) {
            boolean ground = y == yb || y <= surface;
            for (int wz = 0; wz < SIDE; wz++) {
                for (int wx = 0; wx < SIDE; wx++) {
                    int i = idx(wx, y, wz);
                    if (!(ground || bit(fixed, i)) || !bearing(i, wx, y, wz, afterBlast)) continue;
                    dist[i] = 0;
                    ring[tail] = i;
                    tail = (tail + 1) % cap;
                }
            }
            if (!ground && y > surface) {
                // above ground only indestructible supports: already collected, skip other layers
                for (int wd = (y - yb) * SIDE * SIDE >> 6, e = ((yt - yb + 1) * SIDE * SIDE + 63) >> 6; wd < e; wd++) {
                    long bits = fixed[wd];
                    while (bits != 0) {
                        int i = wd << 6 | Long.numberOfTrailingZeros(bits);
                        bits &= bits - 1;
                        if (i >= n || dist[i] == 0) continue;
                        int wx = i % SIDE, wz = (i / SIDE) % SIDE, yy = i / (SIDE * SIDE) + yb;
                        if (!bearing(i, wx, yy, wz, afterBlast)) continue;
                        dist[i] = 0;
                        ring[tail] = i;
                        tail = (tail + 1) % cap;
                    }
                }
                break;
            }
        }
        while (head != tail) {
            int i = ring[head];
            head = (head + 1) % cap;
            int di = dist[i];
            int wx = i % SIDE, wz = (i / SIDE) % SIDE, y = i / (SIDE * SIDE) + yb;
            if (afterBlast && di > 0 && falls(i, di, props(wx, y, wz))) continue;
            for (Direction dir : Direction.values()) {
                int nx = wx + dir.getStepX(), ny = y + dir.getStepY(), nz = wz + dir.getStepZ();
                if (!inside(nx, ny, nz)) continue;
                int cost = dir == Direction.UP ? 0 : 1, nd = di + cost;
                if (nd > C) continue;
                int m = idx(nx, ny, nz);
                if (dist[m] <= nd || !bearing(m, nx, ny, nz, afterBlast)) continue;
                dist[m] = (byte) nd;
                if (cost == 0) {
                    head = (head - 1 + cap) % cap;
                    ring[head] = m;
                } else {
                    ring[tail] = m;
                    tail = (tail + 1) % cap;
                }
            }
        }
    }

    private void buffers(int n) {
        Buffers b = BUFFERS.get();
        if (b.before.length < n) {
            b.before = new byte[n];
            b.after = new byte[n];
            b.ring = new int[n + 1];
        }
        before = b.before;
        after = b.after;
        ring = b.ring;
    }

    /** Ruin queues empty: this thread's traversal buffers no longer needed (tall city window tens of MB). */
    static void releaseBuffers() {
        Buffers b = BUFFERS.get();
        b.before = b.after = new byte[0];
        b.ring = new int[0];
    }

    // ---------------------------------------------------------------- leaves

    /**
     * Leaves farther than {@link #LEAF_REACH} from surviving trunk (through leaves), formerly closer, fall.
     * Only leaves within {@link #LEAF_REACH} of broken log/leaf could change, so traversal
     * stays around those (trunks within double reach), not entire window.
     */
    private void leaves() {
        // broken wood positions are seeds
        IntArrayList seeds = new IntArrayList();
        for (int wd = 0; wd < gone.length; wd++) {
            long bits = gone[wd];
            while (bits != 0) {
                int i = wd << 6 | Long.numberOfTrailingZeros(bits);
                bits &= bits - 1;
                int wx = i % SIDE, wz = (i / SIDE) % SIDE, y = i / (SIDE * SIDE) + yb;
                Blast.Props p = props(wx, y, wz);
                if (p.leaves() || p.log()) seeds.add(i);
            }
        }
        if (seeds.isEmpty()) return;
        // area: leaves within 2·LEAF_REACH + 1 steps of seeds (and adjacent logs); nearest LEAF_REACH are candidates
        it.unimi.dsi.fastutil.ints.Int2ByteOpenHashMap reach = new it.unimi.dsi.fastutil.ints.Int2ByteOpenHashMap();
        IntArrayList logs = new IntArrayList(), queue = new IntArrayList();
        for (int i : seeds) {
            reach.put(i, (byte) 0);
            queue.add(i);
        }
        for (int q = 0; q < queue.size(); q++) {
            int i = queue.getInt(q), di = reach.get(i);
            if (di > 2 * LEAF_REACH) continue;
            int wx = i % SIDE, wz = (i / SIDE) % SIDE, y = i / (SIDE * SIDE) + yb;
            for (Direction dir : Direction.values()) {
                int nx = wx + dir.getStepX(), ny = y + dir.getStepY(), nz = wz + dir.getStepZ();
                if (!inside(nx, ny, nz)) continue;
                int m = idx(nx, ny, nz);
                if (reach.containsKey(m)) continue;
                Blast.Props p = props(nx, ny, nz);
                if (p.log()) {
                    reach.put(m, (byte) (di + 1));
                    logs.add(m);
                } else if (p.leaves()) {
                    reach.put(m, (byte) (di + 1));
                    queue.add(m);
                }
            }
        }
        for (int i : seeds) if (props(i % SIDE, i / (SIDE * SIDE) + yb, (i / SIDE) % SIDE).log()) logs.add(i);
        it.unimi.dsi.fastutil.ints.Int2ByteOpenHashMap was = leafDistances(reach, logs, false), now = leafDistances(reach, logs, true);
        for (var e : reach.int2ByteEntrySet()) {
            int i = e.getIntKey();
            if (e.getByteValue() > LEAF_REACH || bit(gone, i)) continue;
            int wx = i % SIDE, wz = (i / SIDE) % SIDE, y = i / (SIDE * SIDE) + yb;
            if (wx < LO - C || wx >= HI + C || wz < LO - C || wz >= HI + C || !props(wx, y, wz).leaves()) continue;
            if (was.getOrDefault(i, (byte) (LEAF_REACH + 1)) <= LEAF_REACH && now.getOrDefault(i, (byte) (LEAF_REACH + 1)) > LEAF_REACH) set(gone, i);
        }
    }

    /** Leaf-path distance within area to trunk (before/after strike), no farther than {@link #LEAF_REACH}. */
    private it.unimi.dsi.fastutil.ints.Int2ByteOpenHashMap leafDistances(it.unimi.dsi.fastutil.ints.Int2ByteOpenHashMap reach, IntArrayList logs, boolean afterBlast) {
        it.unimi.dsi.fastutil.ints.Int2ByteOpenHashMap dist = new it.unimi.dsi.fastutil.ints.Int2ByteOpenHashMap();
        IntArrayList queue = new IntArrayList();
        for (int i : logs) {
            if (afterBlast && bit(gone, i) || dist.containsKey(i)) continue;
            dist.put(i, (byte) 0);
            queue.add(i);
        }
        for (int q = 0; q < queue.size(); q++) {
            int i = queue.getInt(q), di = dist.get(i);
            if (di >= LEAF_REACH) continue;
            int wx = i % SIDE, wz = (i / SIDE) % SIDE, y = i / (SIDE * SIDE) + yb;
            for (Direction dir : Direction.values()) {
                int nx = wx + dir.getStepX(), ny = y + dir.getStepY(), nz = wz + dir.getStepZ();
                if (!inside(nx, ny, nz)) continue;
                int m = idx(nx, ny, nz);
                if (!reach.containsKey(m) || dist.containsKey(m) || afterBlast && bit(gone, m) || !props(nx, ny, nz).leaves()) continue;
                dist.put(m, (byte) (di + 1));
                queue.add(m);
            }
        }
        return dist;
    }

    // ---------------------------------------------------------------- water

    /**
     * Small water beside broken blocks (breached bottom/wall), with positions in chunk, drains entirely. Water-body traversal
     * stops once large ({@link #MAX_DRAIN} positions, {@link #DRAIN_WIDTH} wide) or at window edge:
     * never traverse entire sea.
     */
    private void drain() {
        long[] seen = new long[gone.length], big = new long[gone.length];
        IntArrayList body = new IntArrayList();
        for (int y = yb; y <= yt; y++) {
            for (int wz = LO; wz < HI; wz++) {
                for (int wx = LO; wx < HI; wx++) {
                    int start = idx(wx, y, wz);
                    if (bit(seen, start) || bit(gone, start) || !props(wx, y, wz).fluid()) continue;
                    body.clear();
                    body.add(start);
                    set(seen, start);
                    boolean stop = false, touches = false;
                    int x0 = wx, x1 = wx, z0 = wz, z1 = wz;
                    for (int q = 0; q < body.size() && !stop; q++) {
                        int i = body.getInt(q), bx = i % SIDE, bz = (i / SIDE) % SIDE, by = i / (SIDE * SIDE) + yb;
                        for (Direction dir : Direction.values()) {
                            int nx = bx + dir.getStepX(), ny = by + dir.getStepY(), nz = bz + dir.getStepZ();
                            if (!inside(nx, ny, nz)) {
                                if (ny <= yt && props(nx, ny, nz).fluid()) stop = true;
                                continue;
                            }
                            int m = idx(nx, ny, nz);
                            if (bit(gone, m)) {
                                touches = true;
                                continue;
                            }
                            if (!props(nx, ny, nz).fluid()) continue;
                            // part of large water body whose traversal already abandoned
                            if (bit(big, m)) stop = true;
                            if (bit(seen, m)) continue;
                            set(seen, m);
                            body.add(m);
                            x0 = Math.min(x0, nx);
                            x1 = Math.max(x1, nx);
                            z0 = Math.min(z0, nz);
                            z1 = Math.max(z1, nz);
                        }
                        if (body.size() > MAX_DRAIN || x1 - x0 >= DRAIN_WIDTH || z1 - z0 >= DRAIN_WIDTH) stop = true;
                    }
                    if (stop) {
                        for (int q = 0; q < body.size(); q++) set(big, body.getInt(q));
                        continue;
                    }
                    if (!touches) continue;
                    for (int q = 0; q < body.size(); q++) {
                        set(drained, body.getInt(q));
                        set(gone, body.getInt(q));
                    }
                }
            }
        }
    }

    // ---------------------------------------------------------------- attachments

    /** Blocks attached to neighbors rather than self-supporting removed with support (two passes: door on torch). */
    private void attachments() {
        for (int pass = 0; pass < 2; pass++) {
            for (int y = Math.max(yb, w.minY + 1); y <= yt; y++) {
                for (int wz = LO; wz < HI; wz++) {
                    for (int wx = LO; wx < HI; wx++) {
                        int i = idx(wx, y, wz);
                        if (bit(gone, i)) continue;
                        Blast.Props p = props(wx, y, wz);
                        if (p.air() || p.fluid() || p.fixed()) continue;
                        // load-bearing blocks stand independently (support traversal drops them); leaves attach to trunk
                        if ((p.bearing() || p.leaves()) && !attached(w.get(wx, y, wz))) continue;
                        if (detached(wx, y, wz, w.get(wx, y, wz), p)) set(gone, i);
                    }
                }
            }
        }
    }

    /** Attached to neighbor by type (door, bed, button, wall-mounted), not self-supporting. */
    private static boolean attached(BlockState st) {
        Block b = st.getBlock();
        return b instanceof DoorBlock || b instanceof DoublePlantBlock || b instanceof BedBlock || b instanceof FaceAttachedHorizontalDirectionalBlock
                || b instanceof WallTorchBlock || b instanceof WallSignBlock || b instanceof WallBannerBlock || b instanceof LadderBlock;
    }

    private boolean detached(int wx, int y, int wz, BlockState st, Blast.Props p) {
        if (p.fixed()) return false;
        Block b = st.getBlock();
        if ((b instanceof DoorBlock || b instanceof DoublePlantBlock) && st.hasProperty(BlockStateProperties.DOUBLE_BLOCK_HALF)) {
            boolean lower = st.getValue(BlockStateProperties.DOUBLE_BLOCK_HALF) == DoubleBlockHalf.LOWER;
            return isGone(wx, lower ? y + 1 : y - 1, wz) || lower && isGone(wx, y - 1, wz);
        }
        if (b instanceof BedBlock) {
            Direction f = st.getValue(BedBlock.FACING);
            Direction other = st.getValue(BedBlock.PART) == BedPart.FOOT ? f : f.getOpposite();
            return isGone(wx + other.getStepX(), y, wz + other.getStepZ()) || isGone(wx, y - 1, wz);
        }
        if (b instanceof FaceAttachedHorizontalDirectionalBlock) {
            AttachFace face = st.getValue(BlockStateProperties.ATTACH_FACE);
            if (face == AttachFace.FLOOR) return isGone(wx, y - 1, wz);
            if (face == AttachFace.CEILING) return isGone(wx, y + 1, wz);
            Direction back = st.getValue(BlockStateProperties.HORIZONTAL_FACING).getOpposite();
            return isGone(wx + back.getStepX(), y, wz + back.getStepZ());
        }
        if (b instanceof WallTorchBlock || b instanceof WallSignBlock || b instanceof WallBannerBlock || b instanceof LadderBlock) {
            Direction back = st.getValue(BlockStateProperties.HORIZONTAL_FACING).getOpposite();
            return isGone(wx + back.getStepX(), y, wz + back.getStepZ());
        }
        // load-bearing blocks stand independently (support traversal drops them); other non-colliding blocks (carpet, torch, rail, dust, plant) rest on
        // block beneath
        if (p.bearing() || p.leaves()) return false;
        return isGone(wx, y - 1, wz);
    }

    // ---------------------------------------------------------------- output

    private void collect() {
        java.util.Arrays.fill(ground, Integer.MIN_VALUE);
        Blast own = blasts[4];
        for (int wz = LO; wz < HI; wz++) {
            for (int wx = LO; wx < HI; wx++) {
                int column = (wz - LO) << 4 | (wx - LO);
                for (int y = yt; y >= yb; y--) {
                    int i = idx(wx, y, wz);
                    Blast.Props p = props(wx, y, wz);
                    if (bit(gone, i)) {
                        if (p.air()) continue;
                        long at = BlockPos.asLong(w.x0 + wx, y, w.z0 + wz);
                        changes.put(at, p.waterlogged() && wet(wx, y, wz) ? Blocks.WATER.defaultBlockState() : AIR);
                        if (p.leaves() && own != null && own.gone(wx - LO, y, wz - LO)) leaves.add(at);
                        if (!p.fluid() && !p.leaves() && !p.log() && p.rubble()) {
                            removed[column]++;
                            if (p.wood()) wood[column]++;
                        }
                        continue;
                    }
                    if (ground[column] == Integer.MIN_VALUE && p.bearing() && !p.log()) ground[column] = y;
                }
            }
        }
        // fluid ticks: beside this chunk's broken blocks, including neighboring chunks; this chunk's fluid beside neighbors' broken blocks
        for (int y = yb; y <= yt; y++) {
            for (int wz = LO - 1; wz <= HI; wz++) {
                for (int wx = LO - 1; wx <= HI; wx++) {
                    int i = idx(wx, y, wz);
                    if (bit(gone, i) || !props(wx, y, wz).fluid()) continue;
                    boolean mine = wx >= LO && wx < HI && wz >= LO && wz < HI;
                    boolean near = false;
                    for (Direction dir : Direction.values()) {
                        int nx = wx + dir.getStepX(), ny = y + dir.getStepY(), nz = wz + dir.getStepZ();
                        if (!isGone(nx, ny, nz)) continue;
                        if (mine || nx >= LO && nx < HI && nz >= LO && nz < HI) near = true;
                    }
                    if (near) fluidTicks.add(BlockPos.asLong(w.x0 + wx, y, w.z0 + wz));
                }
            }
        }
    }

    /** Waterlogged block broken beside remaining water (river pier) becomes water; isolated high block becomes air. */
    private boolean wet(int wx, int y, int wz) {
        for (Direction dir : Direction.values()) {
            if (dir == Direction.DOWN) continue;
            int nx = wx + dir.getStepX(), ny = y + dir.getStepY(), nz = wz + dir.getStepZ();
            if (!inside(nx, ny, nz) || bit(drained, idx(nx, ny, nz))) continue;
            Blast.Props n = props(nx, ny, nz);
            if ((n.fluid() || n.waterlogged()) && !isGone(nx, ny, nz)) return true;
        }
        return false;
    }
}
