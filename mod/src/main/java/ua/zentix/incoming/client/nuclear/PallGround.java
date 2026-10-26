package ua.zentix.incoming.client.nuclear;

import net.minecraft.util.Mth;
import org.jetbrains.annotations.Nullable;
import ua.zentix.incoming.client.far.FarTerrain;
import ua.zentix.incoming.client.far.Sightline;
import ua.zentix.incoming.client.fx.layer.PallPass;
import ua.zentix.incoming.nuclear.Detonation;

import java.util.Arrays;
import java.util.Comparator;
import java.util.stream.IntStream;

/**
 * Terrain beneath a detonation pall — a coarse heightmap for pall rays with no world geometry behind them ({@code pallFloor} in
 * {@code pall.glsl}): beyond render distance without Distant Horizons, beyond its LOD edge and between them, the ray would otherwise not know
 * where to end. A ground-zero plane is unsuitable: a camera in a valley below it looks downward through empty space,
 * ending the ray right at the eye — a straight cutoff below the horizon without pall. The second channel is cell water fraction: what
 * the pall consists of ({@link PallField}: dust over land, spray over water; the shader samples where the material was lofted).
 * <p>
 * A square of {@link #SIDE}² cells around ground zero covers pall at its source ({@link PallField#sourceExtent}: table,
 * haze plume, base surge), dust-wall head and camera with {@link #AROUND_EYE_M} margin (as it grows, the map
 * is remapped more coarsely, retaining known data), but no wider than the full pall bounds ({@link PallField#extent}). An hour of drift
 * carries dust tens of kilometers: a map of the entire drift would coarsen to half-kilometer cells even at the camera; beyond its
 * edge, terrain is clamped to the edge (layer floor, ray end), while material follows the location where dust was lofted (at the source, on
 * the map). Each client tick samples {@link #PER_TICK} cells radially outward from ground zero, four columns per cell,
 * using client knowledge ({@code heights}, {@code water}: local chunks, terrain-map tiles — with Distant Horizons and
 * at distance); a cell once seen retains what was seen (player leaves, chunks unload). Ground zero is always known (ground from server, water from {@code NukeWater.under}: chunk, map tile
 * or sea level). Unknown areas are filled from known data, each channel according to its nature:
 * <ul>
 * <li>height is a continuous field: average surrounding known data (pull-push, {@link #pullPush}: averages in smaller copies,
 * smoothly top-down); ground-zero elevation has weight one cell; far away — the mean observed elevation;</li>
 * <li>water is a cell property (land or water), extended outward: shore continues, open sea remains sea. Averaging
 * known data would produce nonexistent "half-water" (camera by a pond — pale dust over all unknown land; near a shore —
 * half-dust over sea around the detonation), so use the nearest known value ({@link #nearest}), treating ground zero as a known
 * point equal to observed cells.</li>
 * </ul>
 * If nothing is known, the whole map follows ground zero: its elevation, and a sea burst is over sea (as for the firestorm,
 * {@link FireStorm#survey}). Refill the map at most once per second and only after learning something (changed water resolution beneath
 * ground zero — immediately); upload by {@link #version} ({@code PallPass}, RG16F: height and water). Per-frame cost:
 * map samples along rays into empty space and a water sample per pall ray step where water exists below; per-tick cost — up to 1024
 * columns.
 */
public final class PallGround {
    /** Cells per map side. */
    static final int SIDE = 128;
    /** Minimum cell width in blocks (a small detonation uses a smaller map, not a finer one). */
    static final double MIN_CELL = 16;
    /** Cells per tick: the full map in 6.4 s. */
    static final int PER_TICK = 128;
    /** Refill the map at most once per this many ticks. */
    static final int FILL_TICKS = 20;
    /** Pall bounds outgrew the map — new map with this margin. */
    static final double GROW = 1.25;
    /** Mapped terrain around the camera extends this many meters (× strike scale): layer floor and ray end are visible up close there. */
    static final double AROUND_EYE_M = 2000;
    /** Water or land below this fraction of the whole map is considered absent beneath the pall ({@link #hasWater}, {@link #hasLand}). */
    static final float TRACE = 1e-3f;
    private static final int LEVELS = Integer.numberOfTrailingZeros(SIDE) + 1;
    /** Cells by distance from map center: sampling moves outward from ground zero. */
    private static final int[] ORDER = IntStream.range(0, SIDE * SIDE).boxed()
            .sorted(Comparator.comparingDouble(i -> Mth.square(i % SIDE + 0.5 - SIDE / 2.0) + Mth.square(i / SIDE + 0.5 - SIDE / 2.0)))
            .mapToInt(Integer::intValue).toArray();

    /** Client knowledge about a column: a number, or {@link Double#NaN} if unknown. */
    @FunctionalInterface
    private interface Column {
        double at(int x, int z);
    }

    private final PallField pall;
    private final Detonation d;
    private final double cx, cz, groundY;
    /** Map half-side and cell width, blocks. */
    private double half, cell;
    /**
     * Cell heights above ground zero, blocks, and water fractions 0..1; {@link Float#NaN} — unknown (the client knows height and water
     * separately: a map tile can lack water depth).
     */
    private float[] known = new float[SIDE * SIDE], knownWet = new float[SIDE * SIDE];
    /** Filled map (for the shader): per cell, x fastest, height above ground zero (blocks) and water fraction. */
    private final float[] map = new float[2 * SIDE * SIDE];
    /** Filled-map bottom and top, blocks above ground zero. */
    private float lo, hi;
    /** Water beneath ground zero used to fill the map ({@link PallField#under}). */
    private float filledUnder;
    /** Whether the filled map contains water and land. */
    private boolean water, land;
    private int next, version, sinceFill;
    private boolean dirty;
    /** Smaller copies for {@link #fill} (shared: one thread fills maps — the client tick). */
    private static final float[][] V = new float[LEVELS][], W = new float[LEVELS][];
    /**
     * For {@link #nearest}: nearest known source per cell (cell index or {@link #EPICENTRE}) and squared
     * distance to it, cells (shared like {@link #V}).
     */
    private static final int[] SOURCE = new int[SIDE * SIDE];
    private static final float[] DIST = new float[SIDE * SIDE];
    /** "Ground zero" source — map center ({@link #nearest}). */
    private static final int EPICENTRE = SIDE * SIDE;

    PallGround(Detonation d, PallField pall) {
        this(d, pall, 0);
    }

    /** Map with half-side {@code half} blocks (0 — from pall bounds): fill checks use a map of their own cells. */
    PallGround(Detonation d, PallField pall, double half) {
        this.pall = pall;
        this.d = d;
        this.cx = d.burst().x;
        this.cz = d.burst().z;
        this.groundY = d.groundY();
        Arrays.fill(known, Float.NaN);
        Arrays.fill(knownWet, Float.NaN);
        resize(half > 0 ? half : pall.extent(0) * GROW);
        fill();
    }

    /** Map half-side, blocks. */
    double half() {
        return half;
    }

    /** Map fill version: changes when refilled or remapped. */
    int version() {
        return version;
    }

    /** Whether water exists beneath the pall (anywhere in the filled map): otherwise the shader does not sample water. */
    boolean hasWater() {
        return water;
    }

    /** Whether land exists beneath the pall: otherwise no dust or haze (detonation at open sea). */
    boolean hasLand() {
        return land;
    }

    /**
     * Client tick: remap if source pall at {@code ticks} (ticks since detonation), dust-wall head ({@code wall} — reach,
     * blocks, {@link BlastWall#reach}: its fading edge reaches 0.3 psi, twice the pall dust reach), or camera plus margin
     * ({@code eye} — ground distance from ground zero, blocks; capped at full pall bounds) has left the map;
     * sample the next cells
     * using {@code heights} and {@code water} (null — unknown: ceiling dimension; map stays as is); after new data,
     * refill at most once per {@link #FILL_TICKS} ticks; changed water resolution beneath ground zero — immediately.
     */
    void survey(double ticks, double wall, double eye, @Nullable Sightline.Heights heights, @Nullable FarTerrain.Water water) {
        double extent = Math.max(wall, Math.min(pall.extent(ticks), Math.max(pall.sourceExtent(ticks), eye + d.blocks(AROUND_EYE_M))));
        // map and half-side enter the frame together: a remapped map is immediately filled
        boolean grew = extent > half;
        if (grew) resize(extent * GROW);
        if (heights != null || water != null) {
            for (int n = 0; n < PER_TICK; n++) {
                int i = ORDER[next];
                next = (next + 1) % ORDER.length;
                if (heights != null) {
                    double h = measure(i, heights::at);
                    if (!Double.isNaN(h)) dirty |= learn(known, i, (float) (h - groundY), 0.5f);
                }
                if (water != null) {
                    double w = measure(i, water::at);
                    if (!Double.isNaN(w)) dirty |= learn(knownWet, i, (float) w, 0.01f);
                }
            }
        }
        if (grew || (float) pall.under() != filledUnder || ++sinceFill >= FILL_TICKS && dirty) fill();
    }

    /** Cell i is now {@code v}: true for new data (previously unknown or difference exceeds {@code tolerance}). */
    private static boolean learn(float[] cells, int i, float v, float tolerance) {
        if (!Float.isNaN(cells[i]) && Math.abs(cells[i] - v) <= tolerance) return false;
        cells[i] = v;
        return true;
    }

    /** Mean known value in cell i from four columns (one per quarter); NaN — none known. */
    private double measure(int i, Column column) {
        double x0 = cx - half + (i % SIDE + 0.25) * cell, z0 = cz - half + (i / SIDE + 0.25) * cell;
        double sum = 0;
        int n = 0;
        for (int k = 0; k < 4; k++) {
            double h = column.at(Mth.floor(x0 + (k & 1) * 0.5 * cell), Mth.floor(z0 + (k >> 1) * 0.5 * cell));
            if (Double.isNaN(h)) continue;
            sum += h;
            n++;
        }
        return n == 0 ? Double.NaN : sum / n;
    }

    /** Map with half-side {@code to} (at least {@link #MIN_CELL} per cell): map known data to new cells by their centers. */
    private void resize(double to) {
        double h = Math.max(to, MIN_CELL * SIDE / 2), c = 2 * h / SIDE;
        if (half > 0) {
            known = moved(known, h, c);
            knownWet = moved(knownWet, h, c);
            next = 0;
        }
        half = h;
        cell = c;
    }

    /** Known {@code was} onto a map with half-side {@code h} and cell width {@code c}. */
    private float[] moved(float[] was, double h, double c) {
        float[] now = new float[SIDE * SIDE];
        for (int j = 0; j < SIDE; j++) {
            for (int i = 0; i < SIDE; i++) {
                int oi = Mth.floor((-h + (i + 0.5) * c + half) / cell), oj = Mth.floor((-h + (j + 0.5) * c + half) / cell);
                now[j * SIDE + i] = oi < 0 || oj < 0 || oi >= SIDE || oj >= SIDE ? Float.NaN : was[oj * SIDE + oi];
            }
        }
        return now;
    }

    /**
     * Fill the map: heights use the average of surrounding known data ({@link #pullPush}; ground zero — its ground, 0); water uses
     * nearest known data ({@link #nearest}; ground zero — {@link PallField#under}).
     */
    void fill() {
        float under = (float) pall.under();
        pullPush(known, 0);
        float a = Float.MAX_VALUE, b = -Float.MAX_VALUE;
        for (int i = 0; i < SIDE * SIDE; i++) {
            float h = V[0][i];
            map[2 * i] = h;
            a = Math.min(a, h);
            b = Math.max(b, h);
        }
        lo = a;
        hi = b;
        nearest(knownWet, under);
        float most = 0, least = 1;
        for (int i = 0; i < SIDE * SIDE; i++) {
            float w = Mth.clamp(map[2 * i + 1], 0, 1);
            map[2 * i + 1] = w;
            most = Math.max(most, w);
            least = Math.min(least, w);
        }
        water = most > TRACE;
        land = least < 1 - TRACE;
        filledUnder = under;
        version++;
        sinceFill = 0;
        dirty = false;
    }

    /**
     * Fill {@code cells} (NaN — unknown) into {@code V[0]} (pull-push): smaller copies average known data by cell
     * count (copy weight is the known data beneath it), then top-down unknown and partly known areas (weight below 1)
     * take a coarser copy (bilinear, no steps). Ground zero is always known — {@code centre} in its four surrounding cells
     * with weight one cell; if nothing is known, {@code centre} everywhere.
     */
    private static void pullPush(float[] cells, float centre) {
        int levels = LEVELS;
        float[][] v = V, w = W;
        if (v[0] == null) {
            for (int l = 0; l < levels; l++) {
                int n = SIDE >> l;
                v[l] = new float[n * n];
                w[l] = new float[n * n];
            }
        }
        for (int i = 0; i < SIDE * SIDE; i++) {
            boolean has = !Float.isNaN(cells[i]);
            v[0][i] = has ? cells[i] : 0;
            w[0][i] = has ? 1 : 0;
        }
        int mid = SIDE / 2;
        for (int k = 0; k < 4; k++) {
            int i = (mid - 1 + (k >> 1)) * SIDE + mid - 1 + (k & 1);
            if (w[0][i] == 0) {
                v[0][i] = centre;
                w[0][i] = 0.25f;
            }
        }
        for (int l = 1; l < levels; l++) {
            int n = SIDE >> l, m = n * 2;
            for (int j = 0; j < n; j++) {
                for (int i = 0; i < n; i++) {
                    float sv = 0, sw = 0;
                    for (int k = 0; k < 4; k++) {
                        int c = (2 * j + (k >> 1)) * m + 2 * i + (k & 1);
                        sv += v[l - 1][c] * w[l - 1][c];
                        sw += w[l - 1][c];
                    }
                    v[l][j * n + i] = sw > 0 ? sv / sw : 0;
                    w[l][j * n + i] = sw;
                }
            }
        }
        // top-down: v[l] becomes a filled copy
        for (int l = levels - 2; l >= 0; l--) {
            int n = SIDE >> l;
            for (int j = 0; j < n; j++) {
                for (int i = 0; i < n; i++) {
                    int c = j * n + i;
                    float a = Math.min(w[l][c], 1);
                    if (a >= 1) continue;
                    float up = bilinear(v[l + 1], n / 2, 1, 0, (i + 0.5f) / 2 - 0.5f, (j + 0.5f) / 2 - 0.5f);
                    v[l][c] = a * v[l][c] + (1 - a) * up;
                }
            }
        }
    }

    /**
     * Fill {@code cells} (NaN — unknown) into the map water channel from nearest known data: known cells stay unchanged,
     * unknown cells take the nearest known cell or ground zero (a point at map center, {@code centre}), whichever is closer, by
     * cell-center distance. Two passes propagating sources (8SSEDT, Danielsson 1980; Leymarie &amp; Levine
     * 1992): a cell takes a neighbor's source if closer than its own; distances are nearly Euclidean, 16 thousand cells
     * cost fractions of a millisecond once per second. Boundary between sources with different water is midway between them (the map
     * transitions over one cell there: bilinear in the shader).
     */
    private void nearest(float[] cells, float centre) {
        int n = SIDE * SIDE;
        int[] src = SOURCE;
        float[] dist = DIST;
        for (int i = 0; i < n; i++) {
            boolean has = !Float.isNaN(cells[i]);
            src[i] = has ? i : EPICENTRE;
            dist[i] = has ? 0 : distance(i, EPICENTRE);
        }
        // forward: row left to right (left and upper neighbors), then right to left (right neighbor); backward — mirrored
        for (int j = 0; j < SIDE; j++) {
            for (int i = 0; i < SIDE; i++) {
                int c = j * SIDE + i;
                if (i > 0) take(c, c - 1);
                if (j > 0) {
                    take(c, c - SIDE);
                    if (i > 0) take(c, c - SIDE - 1);
                    if (i < SIDE - 1) take(c, c - SIDE + 1);
                }
            }
            for (int i = SIDE - 2; i >= 0; i--) take(j * SIDE + i, j * SIDE + i + 1);
        }
        for (int j = SIDE - 1; j >= 0; j--) {
            for (int i = SIDE - 1; i >= 0; i--) {
                int c = j * SIDE + i;
                if (i < SIDE - 1) take(c, c + 1);
                if (j < SIDE - 1) {
                    take(c, c + SIDE);
                    if (i > 0) take(c, c + SIDE - 1);
                    if (i < SIDE - 1) take(c, c + SIDE + 1);
                }
            }
            for (int i = 1; i < SIDE; i++) take(j * SIDE + i, j * SIDE + i - 1);
        }
        for (int i = 0; i < n; i++) map[2 * i + 1] = src[i] == EPICENTRE ? centre : cells[src[i]];
    }

    /** Cell c takes neighbor {@code from}'s source if closer than its own ({@link #nearest}). */
    private static void take(int c, int from) {
        int s = SOURCE[from];
        if (s == SOURCE[c]) return;
        float d = distance(c, s);
        if (d < DIST[c]) {
            DIST[c] = d;
            SOURCE[c] = s;
        }
    }

    /** Squared distance from cell c center to source s (cell or ground zero — map center), in cells. */
    private static float distance(int c, int s) {
        float x = c % SIDE + 0.5f, y = c / SIDE + 0.5f;
        float sx = s == EPICENTRE ? SIDE / 2f : s % SIDE + 0.5f, sy = s == EPICENTRE ? SIDE / 2f : s / SIDE + 0.5f;
        return (x - sx) * (x - sx) + (y - sy) * (y - sy);
    }

    /**
     * An n × n copy at (x, y) in its cell coordinates (cell 0 center = 0), edges clamped; each cell has {@code stride} numbers,
     * taking the {@code offset}-th.
     */
    private static float bilinear(float[] t, int n, int stride, int offset, float x, float y) {
        if (n == 1) return t[offset];
        x = Mth.clamp(x, 0, n - 1);
        y = Mth.clamp(y, 0, n - 1);
        int i = Math.min((int) x, n - 2), j = Math.min((int) y, n - 2);
        float fx = x - i, fy = y - j;
        float t00 = t[(j * n + i) * stride + offset], t10 = t[(j * n + i + 1) * stride + offset];
        float t01 = t[((j + 1) * n + i) * stride + offset], t11 = t[((j + 1) * n + i + 1) * stride + offset];
        float a = t00 + (t10 - t00) * fx;
        float b = t01 + (t11 - t01) * fx;
        return a + (b - a) * fy;
    }

    /** Filled-map height above ground zero at world (x, z), blocks, as in the shader (bilinear, edges clamped). */
    double at(double x, double z) {
        return bilinear(map, SIDE, 2, 0, (float) ((x - cx + half) / cell - 0.5), (float) ((z - cz + half) / cell - 0.5));
    }

    /** Filled-map water fraction at world (x, z) 0..1, as in the shader ({@code pallWater}). */
    double water(double x, double z) {
        return bilinear(map, SIDE, 2, 1, (float) ((x - cx + half) / cell - 0.5), (float) ((z - cz + half) / cell - 0.5));
    }

    /** Mean water fraction in the filled map (for the scenario line). */
    double waterShare() {
        double sum = 0;
        for (int i = 0; i < SIDE * SIDE; i++) sum += map[2 * i + 1];
        return sum / (SIDE * SIDE);
    }

    /** Map bottom and top above ground zero, blocks (as in the shader). */
    float low() {
        return lo;
    }

    float high() {
        return hi;
    }

    /** For the effects layer: map and its parameters ({@code pall.glsl}: half-side, bottom, top, cell width). */
    void frame(PallPass.Ground out) {
        out.params.set((float) half, lo, hi, (float) cell);
        out.map = map;
        out.side = SIDE;
        out.source = this;
        out.version = version;
    }
}
