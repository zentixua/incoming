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
 * for 1 Mt, cells of half a kilometre): the dust laid on it floated over valleys and sank into hills; on the Zearth Silo map
 * its floor stood 10 m above the eye in a valley, the drawn ground 13 m under the dust (none visible on it), and every ray
 * past the drawn chunks ended at their edge (step 7 measurement, mod/run/agents/unified-wave/step7). These tiles hold what
 * the client knows ({@link FarTerrain#dust}: the surface dust lies on — below permeable canopies; client chunks, then the
 * LOD map tiles of LSS, Voxy or Distant Horizons); unknown cells continue the known ones (pull-push) and are marked, so the
 * shader takes the next coarser level there ({@code dwFloor}). The fine tile takes the lowest of each cell's four columns
 * (dust lies in streets between houses, under bridges' spans it stays outdoors by the open-air window), the coarse their
 * mean. Client tick: {@link #PER_TICK} cells per tile in order of distance from the eye; whole sweeps keep up with ruins.
 */
public final class DustGround {
    /** Cells per tile side. */
    public static final int SIDE = 128;
    /** Coarse tile cell, blocks. */
    static final double COARSE_CELL = 32;
    /** Cells per tile surveyed per client tick: a full sweep of a tile in 32 ticks. */
    static final int PER_TICK = 512;
    /** Cells over which the fine tile hands over to the coarse one inside what it knows, and the coarse one to the map outside it. */
    static final float FINE_RAMP = 8, RAMP = 4;
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
        /** Filled heights (absolute world y of the surface's top) and known flags, x fastest. */
        final float[] filled = new float[SIDE * SIDE];
        /** Its share over the next level per cell, 0..1 ({@link #fill}). */
        final float[] knownFlag = new float[SIDE * SIDE];
        int next;
        boolean on, dirty;

        Tile(boolean fine) {
            this.fine = fine;
            Arrays.fill(known, Float.NaN);
        }

        /** Re-centre on the eye when it has left the middle half: snapped to 16 cells, known cells carried over. */
        void centre(double ex, double ez, double cellNow) {
            double span = SIDE * cellNow, step = 16 * cellNow;
            double nx = Math.floor((ex - span / 2) / step + 0.5) * step, nz = Math.floor((ez - span / 2) / step + 0.5) * step;
            boolean far = Math.abs(ex - (x0 + span / 2)) > span / 4 || Math.abs(ez - (z0 + span / 2)) > span / 4;
            if (on && cellNow == cell && !far) return;
            float[] was = known.clone();
            int di = (int) Math.round((nx - x0) / cellNow), dj = (int) Math.round((nz - z0) / cellNow);
            boolean keep = on && cellNow == cell;
            Arrays.fill(known, Float.NaN);
            if (keep) {
                for (int j = 0; j < SIDE; j++) {
                    int oj = j + dj;
                    if (oj < 0 || oj >= SIDE) continue;
                    for (int i = 0; i < SIDE; i++) {
                        int oi = i + di;
                        if (oi >= 0 && oi < SIDE) known[j * SIDE + i] = was[oj * SIDE + oi];
                    }
                }
            }
            x0 = nx;
            z0 = nz;
            cell = cellNow;
            on = true;
            next = 0;
            dirty = true;
        }

        void survey(Sightline.Heights heights, int count) {
            for (int n = 0; n < count; n++) {
                int i = ORDER[next];
                next = (next + 1) % ORDER.length;
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

        /** Pull-push the unknown cells from the known ones (the eye's ground at the middle when nothing is known). */
        void fill(float eyeGround) {
            PallGround.pullPush(known, eyeGround);
            System.arraycopy(PallGround.pulled(), 0, filled, 0, SIDE * SIDE);
            // the share each cell gives over the next level (chamfer distance, two passes). The fine tile hands over to the
            // coarse one inside what it knows: 0 at an unknown cell, 1 FINE_RAMP cells into the known area. The coarse tile continues
            // the known ground past it (its pull-push fill) and hands over to the detonation's map RAMP cells outside: there the
            // floor goes from the real ground to the map's estimate over 128 m, not over one cell at the drawn chunks' edge
            // (DustGroundTest: 4.6 m over 2 m with a known flag)
            float[] d = knownFlag;
            for (int i = 0; i < SIDE * SIDE; i++) d[i] = Float.isNaN(known[i]) == fine ? 0 : fine ? FINE_RAMP : RAMP;
            chamfer(d);
            for (int i = 0; i < SIDE * SIDE; i++) d[i] = fine ? Math.min(d[i] / FINE_RAMP, 1f) : 1f - Math.min(d[i] / RAMP, 1f);
            dirty = false;
        }

        /** Distance in cells to the nearest zero cell, capped at the cells' starting value (two-pass chamfer, 1 and √2). */
        private static void chamfer(float[] d) {
            for (int j = 0; j < SIDE; j++) {
                for (int i = 0; i < SIDE; i++) {
                    int c = j * SIDE + i;
                    if (i > 0) d[c] = Math.min(d[c], d[c - 1] + 1);
                    if (j > 0) d[c] = Math.min(d[c], d[c - SIDE] + 1);
                    if (i > 0 && j > 0) d[c] = Math.min(d[c], d[c - SIDE - 1] + 1.4142f);
                    if (i < SIDE - 1 && j > 0) d[c] = Math.min(d[c], d[c - SIDE + 1] + 1.4142f);
                }
            }
            for (int j = SIDE - 1; j >= 0; j--) {
                for (int i = SIDE - 1; i >= 0; i--) {
                    int c = j * SIDE + i;
                    if (i < SIDE - 1) d[c] = Math.min(d[c], d[c + 1] + 1);
                    if (j < SIDE - 1) d[c] = Math.min(d[c], d[c + SIDE] + 1);
                    if (i < SIDE - 1 && j < SIDE - 1) d[c] = Math.min(d[c], d[c + SIDE + 1] + 1.4142f);
                    if (i > 0 && j < SIDE - 1) d[c] = Math.min(d[c], d[c + SIDE - 1] + 1.4142f);
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
    private static int sinceFill;
    private static long version;
    private static ClientLevel world;

    private DustGround() {}

    /** Fine cell for the render distance: the fine tile covers the drawn chunks (4 m at up to 14 chunks, 8 m to 28, then 16). */
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
        }, eye.x, eye.y, eye.z, renderChunks, false);
    }

    /** One tick's re-centring, survey ({@code heights}: NaN unknown) and, every {@link #FILL_TICKS} or when {@code fill}, refill. */
    static void update(Sightline.Heights heights, double ex, double ey, double ez, int renderChunks, boolean fill) {
        FINE.centre(ex, ez, fineCell(renderChunks));
        COARSE.centre(ex, ez, COARSE_CELL);
        FINE.survey(heights, PER_TICK);
        COARSE.survey(heights, PER_TICK);
        if ((++sinceFill >= FILL_TICKS || fill) && (FINE.dirty || COARSE.dirty)) {
            float ground = (float) (ey - 1.62);
            COARSE.fill(ground);
            FINE.fill(ground);
            version++;
            sinceFill = 0;
        }
    }

    public static void reset() {
        world = null;
        for (Tile t : new Tile[] {FINE, COARSE}) {
            t.on = false;
            Arrays.fill(t.known, Float.NaN);
            Arrays.fill(t.knownFlag, 0);
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

    /** Tile 0 fine, 1 coarse: its filled heights relative to {@code refY} and known flags, interleaved RG into out (2 floats a cell). */
    public static boolean pack(int level, double refY, float[] out) {
        Tile t = level == 0 ? FINE : COARSE;
        if (!t.on) return false;
        for (int i = 0; i < SIDE * SIDE; i++) {
            out[2 * i] = (float) (t.filled[i] - refY);
            out[2 * i + 1] = t.knownFlag[i];
        }
        return true;
    }

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
        return String.format(java.util.Locale.ROOT, "dust ground: fine %.0f m %d/%d known, coarse %.0f m %d/%d known",
                FINE.cell, a, SIDE * SIDE, COARSE.cell, b, SIDE * SIDE);
    }
}
