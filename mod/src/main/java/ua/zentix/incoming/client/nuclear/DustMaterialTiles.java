package ua.zentix.incoming.client.nuclear;

import ua.zentix.incoming.nuclear.Detonation;
import ua.zentix.incoming.nuclear.material.DustGrid;
import ua.zentix.incoming.nuclear.material.DustMaterial;

/**
 * The ground material the dust wave draws for one detonation ({@code dustwall.glsl}: {@code dwMaterial}): two square tiles of
 * world-aligned cells around ground zero, a fine one at the server's dense cell ({@link DustGrid#FINE}) and a coarse one at its
 * outer cell ({@link DustGrid#COARSE}, doubled while the reach is wider than the tile), RGBA8 per cell: water, sand, hard ground,
 * known. Filled from {@link SourceMaterial} (the server's committed cells, or each client's frozen first answer for worlds and
 * recordings without them), a bounded number of cells a frame; a known cell never changes, so no point flips on screen.
 * Cells the server has not committed yet read as soil: it commits each cell at least 10 s before the front reaches it.
 */
public final class DustMaterialTiles {
    /** Cells across a tile ({@code DW_MATERIAL_SIDE}). */
    public static final int SIDE = 256;
    /** Cells asked per frame (both tiles together): a full fill takes about a second at 60 fps. */
    static final int PER_FRAME = 1024;

    private final SourceMaterial source;
    private final DustGrid grid;
    private final int gx, gz;
    /** Per tile: first cell's world x, z (blocks), cell size (blocks). */
    private final int[] originX = new int[2], originZ = new int[2], cell = new int[2];
    /** RGBA8 of both tiles, tile-major, x fastest; known flags in the alpha byte. */
    public final byte[] rgba = new byte[2 * SIDE * SIDE * 4];
    private int cursor, version, uploaded = -1, left = 2 * SIDE * SIDE;

    public DustMaterialTiles(Detonation d, SourceMaterial source) {
        this.source = source;
        this.grid = new DustGrid(DustGrid.Bounds.of(d));
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
        boolean changed = false;
        int n = 2 * SIDE * SIDE;
        for (int asked = 0, scanned = 0; asked < PER_FRAME && scanned < n; scanned++) {
            int i = cursor;
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
            rgba[o] = (byte) (m == DustMaterial.WATER ? 255 : 0);
            rgba[o + 1] = (byte) (m == DustMaterial.SAND ? 255 : 0);
            rgba[o + 2] = (byte) (m == DustMaterial.HARD ? 255 : 0);
            rgba[o + 3] = (byte) 255;
            left--;
            changed = true;
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

    /** Tile t's origin from ground zero (x, z, blocks; the shader's frame of reference), cell size and cells across. */
    public void tile(int t, float[] out, int offset, double burstX, double burstZ) {
        out[offset] = (float) (originX[t] - burstX);
        out[offset + 1] = (float) (originZ[t] - burstZ);
        out[offset + 2] = cell[t];
        out[offset + 3] = SIDE;
    }
}
