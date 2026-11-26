package ua.zentix.incoming.client.nuclear;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import ua.zentix.incoming.client.far.FarTerrain;
import ua.zentix.incoming.client.far.Sightline;

import java.util.Arrays;
import java.util.Comparator;
import java.util.stream.IntStream;

/**
 * The ground the nuclear dust wave lies on around the eye: two square height tiles centred on the eye (a clipmap, as
 * terrain-following fog and ground haze are drawn in engines), the fine one over the drawn chunks, the coarse one over the
 * near kilometres. Each detonation's own terrain map ({@link PallGround}) spans the pall's whole extent (tens of kilometres
 * for 1 Mt, cells of half a kilometre, of which its fixed columns know a few near the eye): dust laid on it alone floats over
 * valleys and sinks into hills (dust-wave.md). These tiles hold what
 * the client knows ({@link FarTerrain#dust}: the surface dust lies on — below permeable canopies; client chunks, then the
 * LOD map tiles of LSS, Voxy or Distant Horizons); past what it knows the fine tile gives way to the coarse one
 * ({@code dwFloor}), which holds the ground where it was last seen (its nearest known cell) out to its edge. The fine tile takes the lowest of each cell's four columns
 * (dust lies in streets between houses, under bridges' spans it stays outdoors by the open-air window), the coarse their
 * mean. Client tick: up to {@link #PER_TICK} cells a tile within {@link #SURVEY_NANOS}, in order of distance from the eye;
 * whole sweeps keep up with ruins.
 */
public final class DustGround {
    /** Cells per tile side. */
    public static final int SIDE = 128;
    /** Coarse tile cell, blocks. */
    static final double COARSE_CELL = 32;
    /** Cells per tile surveyed per client tick: a full sweep of a tile in 32 ticks. */
    static final int PER_TICK = 512;
    /**
     * Render-thread time a tile's survey may take per client tick, ns: a column costs 1.4–8 µs (a forest's columns scan down
     * through the canopy), so 512 cells a tile would take 1.4–8 ms a tick; bounded, the fine tile knows the drawn chunks within
     * 2.5–8 s, the cells nearest the eye first.
     */
    static final long SURVEY_NANOS = 250_000;

    /** Cells over which the fine tile hands over to the coarse one inside what it knows. */
    static final float FINE_RAMP = 8;
    /** Refill (pull-push) and upload at most this often, ticks. */
    static final int FILL_TICKS = 10;
    private static final int[] ORDER = IntStream.range(0, SIDE * SIDE).boxed()
            .sorted(Comparator.comparingDouble(i -> Mth.square(i % SIDE + 0.5 - SIDE / 2.0) + Mth.square(i / SIDE + 0.5 - SIDE / 2.0)))
            .mapToInt(Integer::intValue).toArray();

    /** One eye-centred tile: origin (world, its corner), cell width, heights known (NaN unknown), heights filled. */
    static final class Tile {
        final boolean fine;
        double cell, x0, z0;
        final float[] known = new float[SIDE * SIDE];
        /** Filled heights (absolute world y of the surface's top), x fastest. */
        final float[] filled = new float[SIDE * SIDE];
        /** Its share over the next level per cell, 0..1 ({@link #fill}). */
        final float[] knownFlag = new float[SIDE * SIDE];
        /**
         * Coarse tile: the height an unknown cell took the first time it was filled (its nearest known cell's then), kept until
         * the cell itself is known or leaves the tile; NaN never filled.
         */
        final float[] held = new float[SIDE * SIDE];
        /** Whether any cell is known (the coarse tile's share is 1 everywhere then, else 0). */
        boolean any;
        int next;
        /**
         * Whether the survey has gone once round the whole tile since it turned on or re-centred far: until then the coarse
         * tile takes each unknown cell's nearest known height afresh (a cell held at the first, time-bounded fill kept the
         * ground next to the eye 1–2 km out: 15–31 m under the drawn chunks' edge in a valley, review step 8).
         */
        boolean swept;
        boolean on, dirty;

        Tile(boolean fine) {
            this.fine = fine;
            Arrays.fill(known, Float.NaN);
            Arrays.fill(held, Float.NaN);
        }

        /**
         * Re-centre on the eye once it is more than 16 cells off the middle: the origin snapped to 16 cells, known cells
         * carried over; true when it moved (the caller refills at once: the origin and the filled heights change together).
         * With the eye at most 16 cells off the middle, the fine tile's full share (8 cells inside its edge) reaches at least
         * 40 cells past the eye on every side: 160 m at 4 m cells, the drawn chunks' radius at render distance 10, 0.8 of it at
         * 12 ({@link #fineCell}); the coarse tile's 32 m cells take the rest of the drawn chunks.
         */
        boolean centre(double ex, double ez, double cellNow) {
            double span = SIDE * cellNow, step = 16 * cellNow;
            double nx = Math.floor((ex - span / 2) / step + 0.5) * step, nz = Math.floor((ez - span / 2) / step + 0.5) * step;
            boolean far = Math.abs(ex - (x0 + span / 2)) > step || Math.abs(ez - (z0 + span / 2)) > step;
            if (on && cellNow == cell && !far) return false;
            float[] was = known.clone(), wasHeld = held.clone();
            int di = (int) Math.round((nx - x0) / cellNow), dj = (int) Math.round((nz - z0) / cellNow);
            boolean keep = on && cellNow == cell;
            Arrays.fill(known, Float.NaN);
            Arrays.fill(held, Float.NaN);
            if (keep) {
                for (int j = 0; j < SIDE; j++) {
                    int oj = j + dj;
                    if (oj < 0 || oj >= SIDE) continue;
                    for (int i = 0; i < SIDE; i++) {
                        int oi = i + di;
                        if (oi < 0 || oi >= SIDE) continue;
                        known[j * SIDE + i] = was[oj * SIDE + oi];
                        held[j * SIDE + i] = wasHeld[oj * SIDE + oi];
                    }
                }
            }
            x0 = nx;
            z0 = nz;
            cell = cellNow;
            on = true;
            // the sweep goes on where it was on a small shift (restarting it at each re-centre left a gliding eye's leading side
            // unknown); after a jump that kept nothing it starts again at the eye (resumed mid-sweep, the ground under a
            // teleported eye stayed unknown for 15–100 s, review step 8)
            if (!keep || Math.abs(di) > 16 || Math.abs(dj) > 16) {
                next = 0;
                swept = false;
                // the old place's held edge would come back past the new drawn chunks once the new sweep wraps (a 22.5 m pop
                // 640 m out after a 1 km jump, review step 8 round 2: DustGroundJumpHeldProbeTest)
                Arrays.fill(held, Float.NaN);
            }
            dirty = true;
            return true;
        }

        void survey(Sightline.Heights heights, int count, long until) {
            for (int n = 0; n < count; n++) {
                // (every cell: a coarse cell reads four columns of up to 8 µs, every 8 cells overshot the bound to 0.47 ms)
                if (n > 0 && until != Long.MAX_VALUE && System.nanoTime() - until > 0) return;
                int i = ORDER[next];
                next = (next + 1) % ORDER.length;
                if (next == 0) swept = true;
                double h = measure(i, heights);
                if (Double.isNaN(h)) continue;
                float v = (float) h;
                if (Float.isNaN(known[i]) || Math.abs(known[i] - v) > 0.25f) {
                    known[i] = v;
                    dirty = true;
                }
            }
        }

        /** The cell's four quarter-point columns: their lowest (fine) or mean (coarse); NaN when none is known. */
        private double measure(int i, Sightline.Heights heights) {
            double x = x0 + (i % SIDE + 0.25) * cell, z = z0 + (i / SIDE + 0.25) * cell;
            double low = Double.POSITIVE_INFINITY, sum = 0;
            int n = 0;
            for (int k = 0; k < 4; k++) {
                double h = heights.at(Mth.floor(x + (k & 1) * 0.5 * cell), Mth.floor(z + (k >> 1) * 0.5 * cell));
                if (Double.isNaN(h)) continue;
                low = Math.min(low, h);
                sum += h;
                n++;
            }
            if (n == 0) return Double.NaN;
            return fine ? low : sum / n;
        }

        /**
         * Refill: the fine tile pulls and pushes its unknown cells from the known ones and hands over inside what it knows; the
         * coarse one holds the ground where it was last seen (the eye's ground when nothing is known).
         */
        void fill(float eyeGround) {
            float[] d = knownFlag;
            if (fine) {
                // the fine tile hands over to the coarse one inside what it knows: its share 0 at an unknown cell, 1 FINE_RAMP
                // cells into the known area (chamfer distance; a known flag dropped from the real ground to the next level within
                // one cell at the drawn chunks' edge, 4.6 m over 2 m in DustGroundTest)
                PallGround.pullPush(known, eyeGround);
                System.arraycopy(PallGround.pulled(), 0, filled, 0, SIDE * SIDE);
                for (int i = 0; i < SIDE * SIDE; i++) d[i] = Float.isNaN(known[i]) ? 0 : FINE_RAMP;
                chamfer(d, null);
                for (int i = 0; i < SIDE * SIDE; i++) d[i] = Math.min(d[i] / FINE_RAMP, 1f);
            } else {
                // past what the client knows the coarse tile holds the ground where it was last seen: an unknown cell takes its
                // nearest known cell's height the first time it is filled and keeps it until it is known itself or leaves the
                // tile (taken afresh at every fill, the moving edge of the drawn chunks reset the floor 1–2 km out by 7–19 m at
                // each refill as the player moved; DustGroundTest.farFloorHoldsWhileMoving). The detonation's map takes over at
                // the tile's edge. A mean of the known area, or a blend into the map from the known edge, stands over a
                // valley's floor and ends rays down it short of the ground (DustGroundRayEndTest)
                any = false;
                float[] carry = CARRY;
                for (int i = 0; i < SIDE * SIDE; i++) {
                    boolean has = !Float.isNaN(known[i]);
                    any |= has;
                    d[i] = has ? 0 : Float.MAX_VALUE;
                    carry[i] = has ? known[i] : eyeGround;
                }
                if (any) chamfer(d, carry);
                for (int i = 0; i < SIDE * SIDE; i++) {
                    if (!Float.isNaN(known[i])) {
                        filled[i] = known[i];
                        held[i] = Float.NaN;
                    } else if (any && swept) {
                        if (Float.isNaN(held[i])) held[i] = carry[i];
                        filled[i] = held[i];
                    } else if (any) {
                        filled[i] = carry[i];
                    } else {
                        filled[i] = eyeGround;
                    }
                }
                Arrays.fill(d, any ? 1f : 0f);
            }
            dirty = false;
        }

        /**
         * Distance in cells to the nearest zero cell, capped at the cells' starting value (two-pass chamfer, 1 and √2); with
         * {@code carry}, each cell also takes that nearest cell's value.
         */
        private static void chamfer(float[] d, float[] carry) {
            int[][] di = {{-1, 0, -1, 1}, {1, 0, 1, -1}}, dj = {{0, -1, -1, -1}, {0, 1, 1, 1}};
            float[] cost = {1, 1, 1.4142f, 1.4142f};
            for (int pass = 0; pass < 2; pass++) {
                for (int n = 0; n < SIDE * SIDE; n++) {
                    int c = pass == 0 ? n : SIDE * SIDE - 1 - n, i = c % SIDE, j = c / SIDE;
                    for (int k = 0; k < 4; k++) {
                        int ni = i + di[pass][k], nj = j + dj[pass][k];
                        if (ni < 0 || nj < 0 || ni >= SIDE || nj >= SIDE) continue;
                        int nb = nj * SIDE + ni;
                        if (d[nb] + cost[k] < d[c]) {
                            d[c] = d[nb] + cost[k];
                            if (carry != null) carry[c] = carry[nb];
                        }
                    }
                }
            }
        }

        /** Filled height at world (x, z) and the known share there (bilinear, as the shader reads it); NaN outside. */
        double at(double x, double z, double[] share) {
            float u = (float) ((x - x0) / cell - 0.5), v = (float) ((z - z0) / cell - 0.5);
            if (!on || u < 0 || v < 0 || u > SIDE - 1 || v > SIDE - 1) {
                share[0] = 0;
                return Double.NaN;
            }
            share[0] = PallGround.bilinear(knownFlag, SIDE, 1, 0, u, v) * edge(u, v);
            return PallGround.bilinear(filled, SIDE, 1, 0, u, v);
        }
    }

    /** The shader's fade to the next level over the tile's outer 8 cells ({@code dwTerrainEdge}). */
    static float edge(float u, float v) {
        float d = Math.min(Math.min(u, v), Math.min(SIDE - 1 - u, SIDE - 1 - v));
        return Mth.clamp(d / 8f, 0f, 1f);
    }

    private static final Tile FINE = new Tile(true), COARSE = new Tile(false);
    /** The coarse fill's nearest-known carry (client tick thread). */
    private static final float[] CARRY = new float[SIDE * SIDE];
    private static int sinceFill;
    private static long version;
    private static ClientLevel world;

    private DustGround() {}

    /** Fine cell for the render distance: 4 m up to 14 chunks, 8 m up to 29, then 16 (the tile 1.1 × the drawn chunks' width). */
    static double fineCell(int renderChunks) {
        double need = renderChunks * 16 * 2 * 1.1 / SIDE;
        return need <= 4 ? 4 : need <= 8 ? 8 : 16;
    }

    /** Client tick while a detonation's dust can be drawn: re-centre, survey and refill the tiles. */
    public static void tick(ClientLevel level, Vec3 eye, int renderChunks) {
        if (level != world) {
            reset();
            world = level;
        }
        if (level.dimensionType().hasCeiling()) {
            FINE.on = COARSE.on = false;
            return;
        }
        Sightline.Heights heights = FarTerrain.dust(level);
        int floor = level.getMinBuildHeight() + 1;
        update((x, z) -> {
            double h = heights.at(x, z);
            return h <= floor ? Double.NaN : h;
        }, eye.x, eye.y, eye.z, renderChunks, false, SURVEY_NANOS);
    }

    /**
     * One tick's re-centring, survey ({@code heights}: NaN unknown; each tile within {@code bound} ns, {@code Long.MAX_VALUE}
     * unbounded) and, every {@link #FILL_TICKS} or when {@code fill}, refill.
     */
    static void update(Sightline.Heights heights, double ex, double ey, double ez, int renderChunks, boolean fill, long bound) {
        long start = System.nanoTime();
        boolean moved = FINE.centre(ex, ez, fineCell(renderChunks));
        moved |= COARSE.centre(ex, ez, COARSE_CELL);
        FINE.survey(heights, PER_TICK, bound == Long.MAX_VALUE ? Long.MAX_VALUE : System.nanoTime() + bound);
        COARSE.survey(heights, PER_TICK, bound == Long.MAX_VALUE ? Long.MAX_VALUE : System.nanoTime() + bound);
        // a re-centred tile refills in the same tick: its origin reaches the shader and the Java readers with its heights
        if (moved || (++sinceFill >= FILL_TICKS || fill) && (FINE.dirty || COARSE.dirty)) {
            float ground = (float) (ey - 1.62);
            COARSE.fill(ground);
            FINE.fill(ground);
            refY = 64 * Math.round(ey / 64);
            version++;
            sinceFill = 0;
        }
        long spent = System.nanoTime() - start;
        tickNanos += spent;
        tickMax = Math.max(tickMax, spent);
        ticks++;
    }

    /** The tiles' height reference (world y in 64-block steps from the player's eye at the last fill): one for every view. */
    private static double refY;
    /** Survey cost since the last {@link #describe}: total and longest tick, ticks. */
    private static long tickNanos, tickMax, ticks;

    public static double refY() {
        return refY;
    }

    public static void reset() {
        world = null;
        for (Tile t : new Tile[] {FINE, COARSE}) {
            t.on = false;
            Arrays.fill(t.known, Float.NaN);
            Arrays.fill(t.knownFlag, 0);
            Arrays.fill(t.held, Float.NaN);
            t.any = false;
            t.next = 0;
            t.swept = false;
        }
        version++;
    }

    static Tile coarse() {
        return COARSE;
    }

    /** Upload generation: the tiles changed. */
    public static long version() {
        return version;
    }

    /** Tile 0 fine, 1 coarse: its filled heights relative to {@link #refY} and shares, interleaved RG into out (2 floats a cell). */
    public static boolean pack(int level, float[] out) {
        Tile t = level == 0 ? FINE : COARSE;
        if (!t.on) return false;
        for (int i = 0; i < SIDE * SIDE; i++) {
            out[2 * i] = (float) (t.filled[i] - refY);
            out[2 * i + 1] = t.knownFlag[i];
        }
        return true;
    }

    /**
     * The lowest and highest ground the tiles hold (world y; NaN when off): the dust's vertical slab and ray search must reach
     * them (a source's map spans only its own cells' fill: in a valley 9 m under its lowest cell no dust was marched at all).
     */
    public static double[] range() {
        if (rangeVersion == version) return RANGE;
        rangeVersion = version;
        double lo = Double.NaN, hi = Double.NaN;
        for (Tile t : new Tile[] {FINE, COARSE}) {
            if (!t.on) continue;
            for (int i = 0; i < SIDE * SIDE; i++) {
                if (t.knownFlag[i] <= 0) continue;
                float h = t.filled[i];
                if (!(h >= lo)) lo = Double.isNaN(lo) ? h : Math.min(lo, h);
                if (!(h <= hi)) hi = Double.isNaN(hi) ? h : Math.max(hi, h);
            }
        }
        RANGE[0] = lo;
        RANGE[1] = hi;
        return RANGE;
    }

    private static final double[] RANGE = {Double.NaN, Double.NaN};
    private static long rangeVersion = -1;

    /** Tile origin x, z (world), cell width; null when off. */
    @Nullable
    public static double[] place(int level) {
        Tile t = level == 0 ? FINE : COARSE;
        return t.on ? new double[] {t.x0, t.z0, t.cell} : null;
    }

    private static final double[] SHARE = new double[1];

    /**
     * The dust's ground at world (x, z), absolute y, as the shader blends it: the fine tile where known, else the coarse one,
     * else {@code fallback} (a detonation's own map); each level fades into the next by its known share and edge.
     */
    public static double floor(double x, double z, double fallback) {
        double h = fallback;
        double c = COARSE.at(x, z, SHARE);
        if (!Double.isNaN(c)) h += (c - h) * SHARE[0];
        double f = FINE.at(x, z, SHARE);
        if (!Double.isNaN(f)) h += (f - h) * SHARE[0];
        return h;
    }

    /** One line for diagnostics. */
    public static String describe() {
        int a = 0, b = 0;
        for (int i = 0; i < SIDE * SIDE; i++) {
            if (!Float.isNaN(FINE.known[i])) a++;
            if (!Float.isNaN(COARSE.known[i])) b++;
        }
        String line = String.format(java.util.Locale.ROOT, "dust ground: fine %.0f m %d/%d known, coarse %.0f m %d/%d known; "
                + "client tick %.3f ms mean, %.3f ms longest over %d ticks", FINE.cell, a, SIDE * SIDE, COARSE.cell, b, SIDE * SIDE,
                ticks == 0 ? 0 : tickNanos / 1e6 / ticks, tickMax / 1e6, ticks);
        tickNanos = tickMax = ticks = 0;
        return line;
    }
}
