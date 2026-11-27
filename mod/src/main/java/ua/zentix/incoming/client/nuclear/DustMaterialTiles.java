package ua.zentix.incoming.client.nuclear;

import ua.zentix.incoming.nuclear.Detonation;
import ua.zentix.incoming.nuclear.material.DustGrid;
import ua.zentix.incoming.nuclear.material.DustMaterial;

/**
 * The ground material the dust wave draws for one detonation ({@code dustwall.glsl}: {@code dwMaterial}): two square tiles of
 * world-aligned cells around ground zero, a fine one at the server's dense cell ({@link DustGrid#FINE}) and a coarse one at its
 * outer cell ({@link DustGrid#COARSE}, doubled while the reach is wider than the tile), RGBA8 per cell: water, sand, hard ground,
 * known. Filled from {@link SourceMaterial} (the server's committed cells, or each client's frozen first answer for worlds and
 * recordings without them), a bounded number of cells a frame, in rings outward from ground zero (the front's order); a known
 * cell never changes. A cell the server has not committed yet holds the detonation's provisional ground-zero material and is
 * asked again until it is committed: the server commits each cell at least 10 s before the front reaches it, before any dust
 * is born there, so no point flips on screen.
 */
public final class DustMaterialTiles {
    /** Cells across a tile ({@code DW_MATERIAL_SIDE}). */
    public static final int SIDE = 256;
    /** Cells asked per frame (both tiles together): a full fill takes about a second at 60 fps. */
    static final int PER_FRAME = 1024;
    /**
     * Once every open cell was asked since the server's last map, frames between passes: such cells change only with a new
     * map or a late local answer, and a full pass each frame walked all 2·256² cells (0.31 ms a source per view, release
     * audit of 3.14.0) for as long as a cell stayed provisional.
     */
    static final int IDLE_FRAMES = 20;

    private final SourceMaterial source;
    private final DustGrid grid;
    private final int gx, gz;
    private final double burstX, burstZ;
    /** Per tile: first cell's world x, z (blocks), cell size (blocks). */
    private final int[] originX = new int[2], originZ = new int[2], cell = new int[2];
    /** RGBA8 of both tiles, tile-major, x fastest; known flags in the alpha byte. */
    public final byte[] rgba = new byte[2 * SIDE * SIDE * 4];
    private int cursor, version, uploaded = -1, left = 2 * SIDE * SIDE;
    /** Cells visited since the server's last map; frames to skip while every open cell has been asked. */
    private int swept, idle;
    /** Cells visited by every fill (tests). */
    long visited;
    /** The server's map the cursor last restarted for ({@link SourceMaterial#committedMap}). */
    private Object lastMap;
    /** Both tiles' cells in fill order: rings outward from ground zero's cell, the fine and coarse tile's ring r together. */
    private static final int[] ORDER = order();

    private static int[] order() {
        int[] o = new int[2 * SIDE * SIDE];
        int k = 0, c = SIDE / 2;
        for (int r = 0; r <= c; r++) {
            for (int t = 0; t < 2; t++) {
                for (int y = c - r; y <= c + r; y++) {
                    if (y < 0 || y >= SIDE) continue;
                    for (int x = c - r; x <= c + r; x++) {
                        if (x < 0 || x >= SIDE || Math.max(Math.abs(x - c), Math.abs(y - c)) != r) continue;
                        o[k++] = t * SIDE * SIDE + y * SIDE + x;
                    }
                }
            }
        }
        if (k != o.length) throw new IllegalStateException("fill order covers " + k + " of " + o.length + " cells");
        return o;
    }

    public DustMaterialTiles(Detonation d, SourceMaterial source) {
        this.source = source;
        this.grid = new DustGrid(DustGrid.Bounds.of(d));
        burstX = d.burst().x;
        burstZ = d.burst().z;
        gx = (int) Math.floor(d.burst().x);
        gz = (int) Math.floor(d.burst().z);
        int reach = grid.bounds.reach();
        cell[0] = DustGrid.FINE;
        cell[1] = DustGrid.COARSE;
        while (cell[1] * SIDE / 2 < reach && cell[1] < 1 << 14) cell[1] *= 2;
        for (int t = 0; t < 2; t++) {
            originX[t] = (Math.floorDiv(gx, cell[t]) - SIDE / 2) * cell[t];
            originZ[t] = (Math.floorDiv(gz, cell[t]) - SIDE / 2) * cell[t];
        }
    }

    /** Ask up to {@link #PER_FRAME} cells not yet known; true if any changed. */
    public boolean fill() {
        if (left <= 0) return false;
        // a new map from the server commits cells at once: ask again from ground zero outward, where the front lofts dust first
        Object map = source.committedMap();
        if (map != lastMap) {
            lastMap = map;
            cursor = 0;
            swept = 0;
            idle = 0;
        }
        if (idle > 0) {
            idle--;
            return false;
        }
        boolean changed = false;
        int n = 2 * SIDE * SIDE, scanned = 0;
        for (int asked = 0; asked < PER_FRAME && scanned < n; scanned++) {
            int i = ORDER[cursor];
            cursor = (cursor + 1) % n;
            int o = 4 * i;
            if (rgba[o + 3] != 0) continue;
            int t = i / (SIDE * SIDE), j = i % (SIDE * SIDE);
            int x = originX[t] + (j % SIDE) * cell[t] + cell[t] / 2, z = originZ[t] + (j / SIDE) * cell[t] + cell[t] / 2;
            if (grid.index(x, z) < 0) {
                // beyond the dust's reach: no dust is born there, soil
                rgba[o + 3] = (byte) 255;
                left--;
                continue;
            }
            asked++;
            SourceMaterial.Answer a = source.material(x, z);
            if (a.value().isEmpty()) continue;
            DustMaterial m = a.value().get();
            byte w = (byte) (m == DustMaterial.WATER ? 255 : 0), sand = (byte) (m == DustMaterial.SAND ? 255 : 0),
                    hard = (byte) (m == DustMaterial.HARD ? 255 : 0);
            if (rgba[o] != w || rgba[o + 1] != sand || rgba[o + 2] != hard) changed = true;
            rgba[o] = w;
            rgba[o + 1] = sand;
            rgba[o + 2] = hard;
            // provisional (the ground-zero seed until the server commits the cell): drawn, asked again
            if (!a.settled()) continue;
            rgba[o + 3] = (byte) 255;
            left--;
            changed = true;
        }
        swept += scanned;
        visited += scanned;
        if (swept >= n) {
            swept = 0;
            idle = IDLE_FRAMES;
        }
        if (changed) version++;
        return changed;
    }

    /** Whether the tiles changed since {@link #uploaded()} was last called. */
    public boolean stale() {
        return version != uploaded;
    }

    public void uploaded() {
        uploaded = version;
    }

    /**
     * Hard ground's share at ground point (rx, rz) from ground zero, as the dust wave reads it ({@code dustwall.glsl}:
     * {@code dwMaterial}: the fine tile inside its border, else the coarse one, bilinear between cell centres).
     */
    public double hard(double rx, double rz) {
        return share(rx, rz, 2);
    }

    /** Channel c's share (0 water, 1 sand, 2 hard ground) at ground point (rx, rz), as {@link #hard}. */
    public double share(double rx, double rz, int c) {
        double x = burstX + rx, z = burstZ + rz;
        int t = 1;
        double cx = (x - originX[0]) / cell[0], cz = (z - originZ[0]) / cell[0];
        if (cx >= 1 && cz >= 1 && cx < SIDE - 1 && cz < SIDE - 1) t = 0;
        else {
            cx = (x - originX[1]) / cell[1];
            cz = (z - originZ[1]) / cell[1];
        }
        cx = Math.clamp(cx, 0.5, SIDE - 0.5) - 0.5;
        cz = Math.clamp(cz, 0.5, SIDE - 0.5) - 0.5;
        int i = Math.min((int) cx, SIDE - 2), j = Math.min((int) cz, SIDE - 2);
        double fx = cx - i, fz = cz - j;
        return (1 - fz) * ((1 - fx) * at(t, i, j, c) + fx * at(t, i + 1, j, c)) + fz * ((1 - fx) * at(t, i, j + 1, c) + fx * at(t, i + 1, j + 1, c));
    }

    private double at(int t, int i, int j, int c) {
        return (rgba[4 * (t * SIDE * SIDE + j * SIDE + i) + c] & 255) / 255.0;
    }

    /**
     * Diagnostics ({@code dw:ground=}): both tiles' RGBA8 cells and their frames, {@code <prefix>_material.rgba} and {@code .json}
     * (counts of cells with a non-zero water, sand and hard share, and known cells).
     */
    public void writeDiag(String prefix, String how) throws java.io.IOException {
        java.nio.file.Files.write(java.nio.file.Path.of(prefix + "_material.rgba"), rgba);
        int[] count = new int[4];
        for (int i = 0; i < rgba.length; i += 4) for (int c = 0; c < 4; c++) if (rgba[i + c] != 0) count[c]++;
        java.nio.file.Files.writeString(java.nio.file.Path.of(prefix + "_material.json"), String.format(java.util.Locale.ROOT,
                "{\"side\": %d, \"burst\": [%.3f, %.3f], \"origin\": [[%d, %d], [%d, %d]], \"cell\": [%d, %d], \"left\": %d, "
                        + "\"version\": %d, \"nonzero_water\": %d, \"nonzero_sand\": %d, \"nonzero_hard\": %d, \"known\": %d, \"source\": \"%s\"}",
                SIDE, burstX, burstZ, originX[0], originZ[0], originX[1], originZ[1], cell[0], cell[1], left, version, count[0], count[1],
                count[2], count[3], how));
    }

    /** Tile t's origin from ground zero (x, z, blocks; the shader's frame of reference), cell size and cells across. */
    public void tile(int t, float[] out, int offset, double burstX, double burstZ) {
        out[offset] = (float) (originX[t] - burstX);
        out[offset + 1] = (float) (originZ[t] - burstZ);
        out[offset + 2] = cell[t];
        out[offset + 3] = SIDE;
    }
}
