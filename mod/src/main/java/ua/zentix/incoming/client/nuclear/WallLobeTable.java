package ua.zentix.incoming.client.nuclear;

import org.joml.Matrix4f;
import org.joml.Vector4f;

import java.util.Arrays;

/**
 * The frame's dust-wall head lobes ({@link WallLobes}) as the shaders read them: rows of the frame's object table (the mushroom table,
 * RGBA32F, {@link MushroomField#WIDTH} texels wide — every texture unit is taken, so the lobes ride in it), built once per frame here.
 * <ul>
 *     <li>Records, {@link #RECORD} texels each from row {@link #ROW}: (centre from the eye, peak extinction), (kernel radii forward, back,
 *     across, up), (slot, wedge share, optical depth up through the other lobes, toward the sun through them), (water, hard, sand
 *     under it, 0). Cells of a lattice row are contiguous, {@link WallLobes#PER_CELL} records each (parent, children; empty ones zero).</li>
 *     <li>Directory, row {@link #DIR_ROW}: per slot and lattice ({@link #ENTRY} texels from texel 5 × (2 × slot + lattice)): (spacing,
 *     share, first row j, rows), then the first record of each row (four per texel); texel 20: the tile grid (tile width and height in
 *     frame pixels, tiles across, down); texel 21: (records, tiles cut short).</li>
 *     <li>Screen tiles from row {@link #TILE_ROW}: {@link #TILES_X} × {@link #TILES_Y} tiles over the frame, each {@link #TILE_CAP} record
 *     indices (−1: none), the lobes whose bounds cover it, nearest centres first.</li>
 * </ul>
 * The march reads its pixel's tile (each lobe once, closed-form chord); probes, layer quads and the composite walk the directory by lattice
 * cell over the same records. Copies of the layout: {@code pall.glsl} ({@code PALL_LOBE_*}).
 */
public final class WallLobeTable {
    public static final int WIDTH = MushroomField.WIDTH, RECORD = 4, PER_ROW = WIDTH / RECORD;
    public static final int ROW = MushroomField.ROWS, ROWS = 192, CAPACITY = ROWS * PER_ROW;
    public static final int DIR_ROW = ROW + ROWS, ENTRY = 5, MAX_ROWS = 16, GRID_TEXEL = 20;
    public static final int TILES_X = 48, TILES_Y = 32, TILE_CAP = 64, TILE_TEXELS = TILE_CAP / 4;
    public static final int TILE_ROW = DIR_ROW + 1, TILE_ROWS = TILES_X * TILES_Y * TILE_TEXELS / WIDTH;
    /** Rows of the whole object table: mushrooms, lobes, directory, tiles. */
    public static final int TABLE_ROWS = TILE_ROW + TILE_ROWS;
    /** Light paths through the other lobes reach this many spacings (the 3×3 cells around a lobe). */
    static final double LIGHT_REACH = 1.5;

    public final float[] records = new float[ROWS * WIDTH * 4];
    public final float[] directory = new float[WIDTH * 4];
    public final float[] tiles = new float[TILE_ROWS * WIDTH * 4];
    /** Records used; lobes dropped from full tile lists this frame (logged by the pass; 0 in the judged views). */
    public int count, truncated;
    private final int[] owner = new int[CAPACITY];
    private final float[] tileDist = new float[TILES_X * TILES_Y * TILE_CAP];
    private final int[] tileCount = new int[TILES_X * TILES_Y];
    private final boolean[] shown = new boolean[CAPACITY];
    private float projX, projY;
    private final WallLobes.Lobe[] cell = new WallLobes.Lobe[WallLobes.PER_CELL];
    private final float[][] slots = new float[2][BlastWall.SLOT];

    /** New frame: no lobes. */
    public void clear() {
        count = 0;
        truncated = 0;
        Arrays.fill(directory, 0);
    }

    /**
     * Slot k's head lobes (both lattices) for the frame: {@code wall} — its slot ({@link BlastWall#slot}), {@code ground} — its map,
     * (ex, ey, ez) — the eye from ground zero (y above ground zero's ground), {@code seconds} — model seconds since the detonation (churn).
     * Rows cover every lobe centre of the head: from the handoff's end less the carry to the dust edge.
     */
    public void build(int k, float[] wall, WallLobes.Ground ground, double ex, double ey, double ez, double seconds) {
        System.arraycopy(wall, 0, slots[k], 0, BlastWall.SLOT);
        if (wall[3] <= 0) return;
        double[] tile = WallLobes.tile(wall);
        double back = Math.max(0, wall[0] - BlastWall.HANDOFF_TO * wall[1] - wall[5] - wall[4]);
        for (int lv = 0; lv < 2; lv++) {
            double weight = lv == 0 ? 1 - tile[1] : tile[1], D = lv == 0 ? tile[0] : 2 * tile[0];
            int e = (2 * k + lv) * ENTRY * 4;
            if (weight <= 0) continue;
            int j0 = Math.max(0, (int) Math.floor(back / D) - 1), j1 = (int) Math.floor(wall[0] / D) + 1;
            int rows = Math.min(j1 - j0 + 1, MAX_ROWS);
            directory[e] = (float) D;
            directory[e + 1] = (float) weight;
            directory[e + 2] = j0;
            for (int r = 0; r < rows; r++) {
                int j = j0 + r, n = (int) WallLobes.row(j);
                if (count + n * WallLobes.PER_CELL > CAPACITY) {
                    rows = r;
                    break;
                }
                directory[e + 4 + r] = count;
                for (int i = 0; i < n; i++) {
                    int made = WallLobes.cell(wall, ground, i, j, D, weight, seconds, cell);
                    for (int c = 0; c < WallLobes.PER_CELL; c++) {
                        int at = count + i * WallLobes.PER_CELL + c;
                        owner[at] = (2 * k + lv) << 24 | r << 16 | i;
                        write(at, c < made ? cell[c] : null, k, ex, ey, ez);
                    }
                }
                count += n * WallLobes.PER_CELL;
            }
            directory[e + 3] = rows;
        }
        directory[21 * 4] = count;
    }

    private void write(int at, WallLobes.Lobe l, int k, double ex, double ey, double ez) {
        int o = texel(at);
        if (l == null || l.a() <= 0) {
            Arrays.fill(records, o, o + RECORD * 4, 0f);
            return;
        }
        records[o] = (float) (l.x() - ex);
        records[o + 1] = (float) (l.y() - ey);
        records[o + 2] = (float) (l.z() - ez);
        records[o + 3] = (float) l.a();
        records[o + 4] = (float) l.front();
        records[o + 5] = (float) l.back();
        records[o + 6] = (float) l.across();
        records[o + 7] = (float) l.up();
        records[o + 8] = k;
        records[o + 9] = (float) l.u();
        records[o + 10] = 0;
        records[o + 11] = 0;
        records[o + 12] = (float) l.wet();
        records[o + 13] = (float) l.hard();
        records[o + 14] = (float) l.sand();
        records[o + 15] = 0;
    }

    /** First float of record {@code at} in {@link #records} (rows from {@link #ROW}). */
    static int texel(int at) {
        return ((at / PER_ROW) * WIDTH + (at % PER_ROW) * RECORD) * 4;
    }

    /**
     * Screen tiles for the frame and each listed lobe's light factors: {@code viewProj} — the frame's projection × view (eye-relative world),
     * {@code width}, {@code height} — the frame in pixels, (sx, sy, sz) — toward the sun, {@code centres} — ground zero from the eye (x, z)
     * of slot 0, then of slot 1. A lobe goes into every tile its bounding box covers; around the eye or across the near plane, into all.
     */
    public void bin(Matrix4f viewProj, int width, int height, double sx, double sy, double sz, float[] centres) {
        // the projection's scale on screen x and y: the length of the clip x, y rows' spatial part (view rotation keeps lengths)
        projX = (float) Math.sqrt(viewProj.m00() * viewProj.m00() + viewProj.m10() * viewProj.m10() + viewProj.m20() * viewProj.m20());
        projY = (float) Math.sqrt(viewProj.m01() * viewProj.m01() + viewProj.m11() * viewProj.m11() + viewProj.m21() * viewProj.m21());
        Arrays.fill(tileCount, 0);
        Arrays.fill(shown, 0, count, false);
        Vector4f v = new Vector4f();
        for (int at = 0; at < count; at++) {
            int o = texel(at);
            if (records[o + 3] <= 0) continue;
            float cx = records[o], cy = records[o + 1], cz = records[o + 2];
            float r = (float) WallLobes.CUT * Math.max(Math.max(records[o + 4], records[o + 5]), Math.max(records[o + 6], records[o + 7]));
            float dist = (float) Math.sqrt(cx * cx + cy * cy + cz * cz);
            int a0 = 0, a1 = TILES_X - 1, b0 = 0, b1 = TILES_Y - 1;
            if (dist > r) {
                // sphere of the lobe's largest radius: its screen extent r·P/(w − r) around the projected centre (w — depth along the view)
                viewProj.transform(v.set(cx, cy, cz, 1));
                if (v.w > r) {
                    float ex = projX * r / (v.w - r), ey = Math.abs(projY) * r / (v.w - r);
                    float nx = v.x / v.w, ny = v.y / v.w;
                    if (nx + ex < -1 || nx - ex > 1 || ny + ey < -1 || ny - ey > 1) continue;
                    a0 = Math.max(0, (int) ((nx - ex + 1) * 0.5f * TILES_X));
                    a1 = Math.min(TILES_X - 1, (int) ((nx + ex + 1) * 0.5f * TILES_X));
                    b0 = Math.max(0, (int) ((ny - ey + 1) * 0.5f * TILES_Y));
                    b1 = Math.min(TILES_Y - 1, (int) ((ny + ey + 1) * 0.5f * TILES_Y));
                } else if (v.w < -r) {
                    continue;
                }
            }
            shown[at] = true;
            for (int b = b0; b <= b1; b++) {
                for (int a = a0; a <= a1; a++) insert(b * TILES_X + a, at, dist);
            }
        }
        for (int t = 0; t < TILES_X * TILES_Y; t++) {
            int base = t * TILE_TEXELS * 4;
            for (int c = 0; c < TILE_CAP; c++) tiles[base + c] = c < tileCount[t] ? tileList[t * TILE_CAP + c] : -1f;
        }
        int g = GRID_TEXEL * 4;
        directory[g] = (float) width / TILES_X;
        directory[g + 1] = (float) height / TILES_Y;
        directory[g + 2] = TILES_X;
        directory[g + 3] = TILES_Y;
        directory[g + 5] = truncated;
        for (int at = 0; at < count; at++) if (shown[at]) light(at, sx, sy, sz, centres);
    }

    private final int[] tileList = new int[TILES_X * TILES_Y * TILE_CAP];

    /** The longest tile list this frame (before the cap). */
    int fullest() {
        int m = 0;
        for (int c : tileCount) m = Math.max(m, c);
        return m;
    }

    /** Into tile t's list, nearest centres first; a full list drops its farthest for a nearer one. */
    private void insert(int t, int at, float dist) {
        int n = tileCount[t], base = t * TILE_CAP;
        if (n == TILE_CAP) {
            if (tileDist[base + n - 1] <= dist) {
                truncated++;
                return;
            }
            truncated++;
            n--;
        } else {
            tileCount[t] = n + 1;
        }
        int i = n;
        while (i > 0 && tileDist[base + i - 1] > dist) {
            tileDist[base + i] = tileDist[base + i - 1];
            tileList[base + i] = tileList[base + i - 1];
            i--;
        }
        tileDist[base + i] = dist;
        tileList[base + i] = at;
    }

    /**
     * Light factors of record {@code at}: optical depth toward the sun from its sunward surface point, and up from its top, through the other
     * lobes around it (its own side is its orientation, lit in the shader): the parents of the 3×3 cells around its cell, and a child's own
     * parent; paths {@link #LIGHT_REACH} spacings long. Children of the neighbours are left out (a tenth of a cell's mass each, on top).
     */
    private void light(int at, double sx, double sy, double sz, float[] centres) {
        int o = texel(at), own = owner[at], e = own >>> 24, r = own >>> 16 & 0xFF, cellAt = own & 0xFFFF;
        int de = e * ENTRY * 4;
        double D = directory[de], reach = LIGHT_REACH * D;
        double cx = records[o], cy = records[o + 1], cz = records[o + 2];
        int k = (int) records[o + 8];
        double gx = cx - centres[2 * k], gz = cz - centres[2 * k + 1], gr = Math.max(Math.hypot(gx, gz), 1e-3);
        double erx = gx / gr, erz = gz / gr;
        // sunward point on the seen surface (SEEN kernel radii): R²l/√(lᵀR²l)
        double lr = sx * erx + sz * erz, lt = -sx * erz + sz * erx, rr = lr >= 0 ? records[o + 4] : records[o + 5];
        double nr = rr * rr * lr, nt = records[o + 6] * records[o + 6] * lt, ny = records[o + 7] * records[o + 7] * sy;
        double norm = Math.sqrt(Math.max(rr * rr * lr * lr + records[o + 6] * records[o + 6] * lt * lt + records[o + 7] * records[o + 7] * sy * sy, 1e-9));
        double seen = WallLobes.SEEN;
        double px = cx + seen * (nr * erx - nt * erz) / norm, py = cy + seen * ny / norm, pz = cz + seen * (nr * erz + nt * erx) / norm;
        double ty = cy + seen * records[o + 7];
        double sun = 0, up = 0;
        int j0 = (int) directory[de + 2], rows = (int) directory[de + 3];
        double angle = Math.atan2(gz, gx);
        if (angle < 0) angle += 2 * Math.PI;
        int parent = at - (at - (int) directory[de + 4 + r]) % WallLobes.PER_CELL;
        for (int dr = -1; dr <= 1; dr++) {
            int rr2 = r + dr;
            if (rr2 < 0 || rr2 >= rows) continue;
            int n = (int) WallLobes.row(j0 + rr2), base = (int) directory[de + 4 + rr2], i0 = (int) Math.floor(angle / (2 * Math.PI) * n);
            for (int di = -1; di <= 1; di++) {
                int i = ((i0 + di) % n + n) % n, other = base + i * WallLobes.PER_CELL;
                if (other == at || (dr == 0 && i == cellAt && other != parent)) continue;
                if (dr == 0 && i == cellAt && at == parent) continue;
                sun += chord(other, px, py, pz, sx, sy, sz, reach, centres);
                up += chord(other, cx, ty, cz, 0, 1, 0, reach, centres);
            }
        }
        records[o + 10] = (float) up;
        records[o + 11] = (float) sun;
    }

    /** Optical depth of record {@code at} along p + t·d (from the eye), t in [0, len] (as {@link WallLobes#tau}, from the record's numbers). */
    double tau(int at, double px, double py, double pz, double dx, double dy, double dz, double len, float[] centres) {
        return chord(at, px, py, pz, dx, dy, dz, len, centres);
    }

    private double chord(int at, double px, double py, double pz, double dx, double dy, double dz, double len, float[] centres) {
        int o = texel(at);
        float a = records[o + 3];
        if (a <= 0) return 0;
        float cx = records[o], cy = records[o + 1], cz = records[o + 2];
        float far = (float) WallLobes.CUT * Math.max(Math.max(records[o + 4], records[o + 5]), Math.max(records[o + 6], records[o + 7]));
        // quick miss: the path's nearest approach to the centre beyond the largest radius
        double qx = px - cx, qy = py - cy, qz = pz - cz, along = Math.max(0, Math.min(len, -(qx * dx + qy * dy + qz * dz)));
        double mx = qx + dx * along, my = qy + dy * along, mz = qz + dz * along;
        if (mx * mx + my * my + mz * mz > far * far) return 0;
        int k = (int) records[o + 8];
        double gx = cx - centres[2 * k], gz = cz - centres[2 * k + 1], gr = Math.max(Math.hypot(gx, gz), 1e-3);
        return WallLobes.tau(gx / gr, gz / gr, cx, cy, cz, records[o + 4], records[o + 5], records[o + 6], records[o + 7], a, px, py, pz, dx, dy, dz, 0, len);
    }
}
