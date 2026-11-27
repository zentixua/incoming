package ua.zentix.incoming.nuclear.material;

import io.netty.buffer.Unpooled;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import net.minecraft.world.level.ChunkPos;
import org.junit.jupiter.api.Test;
import ua.zentix.incoming.net.S2C;
import ua.zentix.incoming.nuclear.Detonation;
import net.minecraft.world.phys.Vec3;

import java.util.Arrays;
import java.util.Random;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class MaterialMapTest {
    @Test void runsAndLiteralCellsRoundTripAtNegativeWorldCoordinates() {
        DustGrid.Bounds b = new DustGrid.Bounds(-128, -1, 512, 4096);
        DustGrid grid = new DustGrid(b);
        var votes = new Long2IntOpenHashMap();
        var random = new Random(951);
        grid.visit((i, x, z, size) -> votes.put(ChunkPos.asLong(x >> 4, z >> 4), 1 << (4 * random.nextInt(4))));
        for (var samples : java.util.List.of(votes, new Long2IntOpenHashMap())) {
            MaterialMap map = MaterialMap.build(b, samples, 0, 123);
            byte[] bytes = map.encode();
            assertEquals(samples.isEmpty() ? 1 : 0, bytes[26]);
            var decoded = MaterialMap.decode(bytes);
            grid.visit((i, x, z, size) -> assertEquals(map.material(x, z), decoded.material(x, z)));
            assertArrayEquals(bytes, decoded.encode());
            assertThrows(IllegalArgumentException.class, () -> MaterialMap.decode(Arrays.copyOf(bytes, bytes.length - 1)));
            assertThrows(IllegalArgumentException.class, () -> MaterialMap.decode(Arrays.copyOf(bytes, bytes.length + 1)));
        }
    }

    @Test void snapshotFreezesLoadedVotesAndOverridesOlderDisk() {
        var loaded = new LoadedSurfaces();
        long lake = ChunkPos.asLong(-1, -1);
        loaded.put(lake, 4);
        var snapshot = loaded.snapshot();
        loaded.remove(lake);
        loaded.put(lake, 4 << 12);
        var disk = new Long2IntOpenHashMap();
        disk.put(lake, 4 << 4);
        snapshot.overlay(disk);
        assertEquals(4, disk.get(lake));
    }

    @Test void unknownCellBetweenTwoCentresTakesTheNearestAndTiesUseWorldOrder() {
        var source = new Long2IntOpenHashMap();
        source.put(ChunkPos.asLong(-4, 0), 4);
        source.put(ChunkPos.asLong(4, 0), 4 << 4);
        var map = MaterialMap.build(new DustGrid.Bounds(0, 0, 512, 1500), source, -1, 12);
        assertEquals(DustMaterial.WATER, map.material(32, 32));
        assertEquals(DustMaterial.SAND, map.material(300, 32));
        assertTrue(map.wet());
    }

    /**
     * One water cell at the edge of the measured ground is not drawn out along a ray: with one nearest centre, every unread
     * cell east of it in its row took its class, a straight 64-block strip of water kilometres long (the dust over it a thin
     * spray trench). The unread cells take the weighted vote of their nearest measured centres; the measured map and the
     * frontier agree.
     */
    @Test void anEdgeCellIsNotDrawnOutAlongARay() {
        var source = new Long2IntOpenHashMap();
        int soil = 15 << (4 * DustMaterial.SOIL.ordinal()), water = 15 << (4 * DustMaterial.WATER.ordinal());
        for (int cx = -8; cx < 4; cx++)
            for (int cz = -8; cz < 8; cz++) source.put(ChunkPos.asLong(cx, cz), cx >= 0 && cz >= 4 ? water : soil);
        var bounds = new DustGrid.Bounds(0, 0, 2048, 4096);
        var map = MaterialMap.build(bounds, source, -1, 12);
        assertEquals(DustMaterial.WATER, map.material(32, 96), "the measured water cell stays water");
        for (int x = 160; x < 2000; x += 64) assertEquals(DustMaterial.SOIL, map.material(x, 96), "no strip of water at x " + x);
        // the frontier (live sources) reduces by the same rule
        var d = new Detonation(-1, new Vec3(0.5, 64, 0.5), 64, 1000, true, 0, 0, 0, 20_000, 7, 1, false, 1);
        var frontier = new MaterialFrontier(d, DustMaterial.SOIL.ordinal());
        frontier.observe(source);
        frontier.closeThrough(Long.MAX_VALUE);
        var live = frontier.snapshot(12);
        var grid = new DustGrid(DustGrid.Bounds.of(d));
        for (int x = 160; x < 1000; x += 64)
            if (grid.index(x, 96) >= 0) assertEquals(DustMaterial.SOIL, live.material(x, 96), "frontier: no strip at x " + x);
    }

    /** A sea along the measured ground's edge (a shore burst's measured half) still reaches out as water past it. */
    @Test void aShoreAtTheEdgeKeepsItsWaterOutward() {
        var source = new Long2IntOpenHashMap();
        int soil = 15 << (4 * DustMaterial.SOIL.ordinal()), water = 15 << (4 * DustMaterial.WATER.ordinal());
        for (int cx = -8; cx < 4; cx++)
            for (int cz = -8; cz < 8; cz++) source.put(ChunkPos.asLong(cx, cz), cx >= 0 ? water : soil);
        var map = MaterialMap.build(new DustGrid.Bounds(0, 0, 2048, 4096), source, -1, 12);
        for (int x = 96; x < 2000; x += 64) for (int z = -96; z <= 96; z += 64)
            assertEquals(DustMaterial.WATER, map.material(x, z), "the sea past the shore at x " + x + ", z " + z);
    }

    @Test void largestSourceAndSeventeenColdSourcesFitOnTheWire() {
        var d = new Detonation(1, new Vec3(-0.5, 64 + ua.zentix.incoming.nuclear.model.Yield.optimalBurstHeight(1000), 0.5),
                64, 1000, false, 20, 0, 0, 20000, 1L, 1f, false, 0.35f);
        var grid = new DustGrid(DustGrid.Bounds.of(d));
        // The literal codec bounds even an incompressible edited map. Include every packet header and source UUID.
        byte[] worst = new byte[27 + (grid.count() + 3) / 4];
        int wire = 0;
        var frames = MaterialBirth.Encoded.of(UUID.randomUUID(), worst).packets();
        for (var frame : frames) {
            var buf = Unpooled.buffer();
            try {
                S2C.NukeMaterial.CODEC.encode(buf, frame);
                int framed = buf.readableBytes() + 40; // length, compression framing, play packet id and payload resource ID
                assertTrue(framed < 64000);
                assertTrue(20 * framed <= 256000);
                wire += framed;
            } finally { buf.release(); }
        }
        assertTrue(worst.length > 64_000, "a literal 1 Mt airburst cannot fit in one 64 KB frame");
        assertTrue(wire + 256 < 84_000, "framed 1 Mt literal including source metadata: " + wire);
        assertTrue(17L * (wire + 256) < 1_500_000, "17 framed maps and source metadata, bytes per source " + wire);
    }
}
