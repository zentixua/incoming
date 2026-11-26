package ua.zentix.incoming.client.nuclear;

import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;
import ua.zentix.incoming.net.S2C;
import ua.zentix.incoming.nuclear.Detonation;
import ua.zentix.incoming.nuclear.material.DustGrid;
import ua.zentix.incoming.nuclear.material.DustMaterial;
import ua.zentix.incoming.nuclear.material.MaterialInbox;
import ua.zentix.incoming.nuclear.material.MaterialMap;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A live detonation carries the ground-zero seed (S2C.NukeDetonation.ground); {@link SourceMaterial} answers every cell the server
 * has not committed yet with that seed, provisionally. {@link DustMaterialTiles} draws it but asks again until the cell is
 * committed: a lake committed after the first fill is drawn as water, never frozen as the seed's soil.
 */
class DustMaterialTilesTest {
    @Test
    void aCellCommittedAfterTheFirstFillIsDrawnAsCommitted() {
        Detonation d = new Detonation(1, Vec3.ZERO, 0, 15, true, 0, 0, 0, 20000, 7, 1f, false, 0.35f);
        MaterialInbox inbox = new MaterialInbox();
        // a live viewer: the packet's ground seed is soil (land under ground zero), no map yet
        SourceMaterial source = new SourceMaterial(d, inbox, DustMaterial.SOIL.ordinal(), (x, z) -> { throw new AssertionError(); });
        DustMaterialTiles tiles = new DustMaterialTiles(d, source);
        for (int i = 0; i < 1000; i++) tiles.fill();
        // the server's map arrives: every sampled chunk votes water (a lake under the whole footprint)
        DustGrid.Bounds bounds = DustGrid.Bounds.of(d);
        Long2IntOpenHashMap chunks = new Long2IntOpenHashMap();
        int reach = bounds.reach();
        for (int cx = -reach / 16 - 1; cx <= reach / 16 + 1; cx++)
            for (int cz = -reach / 16 - 1; cz <= reach / 16 + 1; cz++)
                chunks.put(ChunkPos.asLong(cx, cz), 15 << (4 * DustMaterial.WATER.ordinal()));
        MaterialMap map = MaterialMap.build(bounds, chunks, DustMaterial.WATER.ordinal(), 0);
        inbox.receive(new S2C.NukeMaterial(UUID.randomUUID(), 0, 0, map.encode()));
        // the source now answers committed water at ground zero's fine cell
        int side = DustMaterialTiles.SIDE, center = (side / 2) * side + side / 2;
        assertEquals(DustMaterial.WATER, source.material(32, 32).value().orElseThrow(), "map delivered");
        for (int i = 0; i < 1000; i++) tiles.fill();
        // tile 0 (fine), the cell at ground zero: water byte (R) must be 255
        int water = tiles.rgba[4 * center] & 255;
        assertEquals(255, water, "fine tile cell at ground zero is still the frozen seed (soil) after the server committed water");
    }

    /** The fill reaches every cell of both tiles, ground zero's first (the front's order). */
    @Test
    void fillGoesOutwardFromGroundZero() {
        Detonation d = new Detonation(1, Vec3.ZERO, 0, 15, true, 0, 0, 0, 20000, 7, 1f, false, 0.35f);
        java.util.List<int[]> asked = new java.util.ArrayList<>();
        SourceMaterial source = new SourceMaterial(d, null, (x, z) -> {
            asked.add(new int[]{x, z});
            return DustMaterial.SOIL;
        });
        DustMaterialTiles tiles = new DustMaterialTiles(d, source);
        tiles.fill();
        int[] first = asked.getFirst();
        assertEquals(0, Math.floorDiv(first[0], DustGrid.FINE), "first cell asked is ground zero's");
        assertEquals(0, Math.floorDiv(first[1], DustGrid.FINE), "first cell asked is ground zero's");
        double before = 0;
        for (int[] a : asked.subList(0, Math.min(200, asked.size()))) {
            double r = Math.max(Math.abs(a[0]), Math.abs(a[1]));
            assertEquals(true, r + 3 * DustGrid.COARSE >= before, "rings go outward");
            before = Math.max(before, r - 3 * DustGrid.COARSE);
        }
    }
}
