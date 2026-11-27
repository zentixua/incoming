package ua.zentix.incoming.client.nuclear;

import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;
import ua.zentix.incoming.nuclear.Detonation;
import ua.zentix.incoming.nuclear.material.DustGrid;
import ua.zentix.incoming.nuclear.material.DustMaterial;
import ua.zentix.incoming.nuclear.material.MaterialInbox;
import ua.zentix.incoming.nuclear.material.MaterialMap;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A server map that has committed all but the footprint's outer cells (4964 of 131072 open): those stay provisional until its
 * next map, and a scan each frame walks most of the 2·256² cells to find them; the fill rests between passes
 * ({@link DustMaterialTiles#IDLE_FRAMES}). Fails with the rest removed.
 */
class DustMaterialTilesRestProbeTest {
    @Test
    void fewProvisionalCellsAreNotScannedEveryFrame() throws Exception {
        Detonation d = new Detonation(1, Vec3.ZERO, 0, 15, true, 0, 0, 0, 20000, 7, 1f, false, 0.35f);
        DustGrid grid = new DustGrid(DustGrid.Bounds.of(d));
        int reach = grid.bounds.reach();
        // a partial checkpoint: committed out to just inside the footprint's edge, the outer ring of cells still open
        double through = Math.pow(reach - 2.0 * DustGrid.COARSE, 2);
        Constructor<MaterialMap> c = MaterialMap.class.getDeclaredConstructor(DustGrid.class, byte[].class, boolean.class, long.class, double.class);
        c.setAccessible(true);
        MaterialMap map = c.newInstance(grid, new byte[(grid.count() + 3) / 4], false, 0L, through);
        MaterialInbox inbox = new MaterialInbox();
        Field f = MaterialInbox.class.getDeclaredField("map");
        f.setAccessible(true);
        f.set(inbox, map);
        SourceMaterial source = new SourceMaterial(d, inbox, DustMaterial.SOIL.ordinal(), (x, z) -> { throw new AssertionError(); });
        DustMaterialTiles tiles = new DustMaterialTiles(d, source);
        for (int i = 0; i < 400; i++) tiles.fill();
        Field left = DustMaterialTiles.class.getDeclaredField("left");
        left.setAccessible(true);
        int n = 2 * DustMaterialTiles.SIDE * DustMaterialTiles.SIDE, open = (int) left.get(tiles);
        long before = tiles.visited;
        int frames = 10 * DustMaterialTiles.IDLE_FRAMES;
        for (int i = 0; i < frames; i++) tiles.fill();
        long per = (tiles.visited - before) / frames;
        assertTrue(open > 0 && open < n / 20, "the case: a few open cells (" + open + ")");
        assertTrue(per <= 2L * n / DustMaterialTiles.IDLE_FRAMES, "cells visited a frame with " + open + " open: " + per + " of " + n);
    }
}
