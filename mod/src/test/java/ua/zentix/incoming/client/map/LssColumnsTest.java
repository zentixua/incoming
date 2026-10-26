package ua.zentix.incoming.client.map;

import net.minecraft.server.Bootstrap;
import net.neoforged.fml.loading.LoadingModList;
import net.minecraft.SharedConstants;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.MapColor;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The top of the columns from LSS chunks is like the layer of client chunks ({@link LoadedChunksTerrain}), and their storage. */
class LssColumnsTest {
    @BeforeAll
    static void boot() {
        // like S2CGoldenTest: FML is not in the unit test - loading flags NeoForge empty list of mods
        SharedConstants.tryDetectVersion();
        if (LoadingModList.get() == null) LoadingModList.of(List.of(), List.of(), List.of(), List.of(), Map.of());
        Bootstrap.bootStrap();
    }

    /** Chunk sections with one column (x, z) defined from bottom to top with {@code fromY}; the remaining columns are air. */
    private static LssColumns.Section[] column(int minSection, int sections, int x, int z, int fromY, BlockState... blocks) {
        BlockState[][] states = new BlockState[sections][4096];
        for (int i = 0; i < blocks.length; i++) {
            int y = fromY + i - minSection * 16;
            states[y >> 4][(y & 15) << 8 | z << 4 | x] = blocks[i];
        }
        LssColumns.Section[] out = new LssColumns.Section[sections];
        for (int s = 0; s < sections; s++) {
            BlockState[] st = states[s];
            boolean any = false;
            for (BlockState b : st) any |= b != null;
            if (!any) continue;
            out[s] = new LssColumns.Section() {
                @Override
                public boolean empty() {
                    return false;
                }

                @Override
                public BlockState block(int bx, int by, int bz) {
                    BlockState b = st[by << 8 | bz << 4 | bx];
                    return b == null ? Blocks.AIR.defaultBlockState() : b;
                }
            };
        }
        return out;
    }

    @Nullable
    private static TerrainSource.Column top(LssColumns.Section[] sections, int minSection, int x, int z) {
        return LssColumns.column(LssColumns.summarize(minSection, sections)[z * 16 + x]);
    }

    /** Earth: height - the first air above the top block (like a height map), color - its color on the map. */
    @Test
    void groundTopAndColour() {
        var s = column(-4, 12, 3, 5, 60, Blocks.STONE.defaultBlockState(), Blocks.DIRT.defaultBlockState(), Blocks.GRASS_BLOCK.defaultBlockState());
        TerrainSource.Column c = top(s, -4, 3, 5);
        assertEquals(63, c.height());
        assertEquals(MapColor.GRASS, c.color());
        assertEquals(0, c.waterDepth());
        assertNull(top(s, -4, 4, 5), "column without blocks - unknown");
    }

    /** Grass and flowers do not hold the MOTION_BLOCKING height map (the layer of chunks takes it): the top is the block below them; holds foliage. */
    @Test
    void plantsDoNotCountLeavesDo() {
        var grass = column(0, 8, 0, 0, 64, Blocks.DIRT.defaultBlockState(), Blocks.SHORT_GRASS.defaultBlockState());
        assertEquals(65, top(grass, 0, 0, 0).height());
        assertEquals(MapColor.DIRT, top(grass, 0, 0, 0).color());
        var tree = column(0, 8, 0, 0, 64, Blocks.DIRT.defaultBlockState(), Blocks.OAK_LOG.defaultBlockState(), Blocks.OAK_LEAVES.defaultBlockState());
        assertEquals(67, top(tree, 0, 0, 0).height(), "crown - a barrier to rays");
    }

    /** The glass holds a height map, but it has no color on the map: the color and height of the first block with the color below it. */
    @Test
    void colourlessTopLooksBelow() {
        var s = column(0, 8, 0, 0, 70, Blocks.STONE.defaultBlockState(), Blocks.GLASS.defaultBlockState());
        TerrainSource.Column c = top(s, 0, 0, 0);
        assertEquals(71, c.height());
        assertEquals(MapColor.STONE, c.color());
    }

    /** Water: depth to the bottom, no more than 16; through the empty section (LSS does not send it) the top is located. */
    @Test
    void waterDepthAndGaps() {
        var pond = column(0, 8, 2, 2, 60, Blocks.SAND.defaultBlockState(), Blocks.WATER.defaultBlockState(), Blocks.WATER.defaultBlockState(),
                Blocks.WATER.defaultBlockState());
        TerrainSource.Column c = top(pond, 0, 2, 2);
        assertEquals(64, c.height());
        assertEquals(MapColor.WATER, c.color());
        assertEquals(3, c.waterDepth());
        BlockState[] sea = new BlockState[30];
        java.util.Arrays.fill(sea, Blocks.WATER.defaultBlockState());
        sea[0] = Blocks.SAND.defaultBlockState();
        assertEquals(LssColumns.MAX_WATER_DEPTH, top(column(0, 8, 0, 0, 30, sea), 0, 0, 0).waterDepth());
        // stone in section 0, section 1 empty (null), foliage in section 2
        var gap = column(0, 4, 1, 1, 5, Blocks.STONE.defaultBlockState());
        var leaves = column(0, 4, 1, 1, 40, Blocks.OAK_LEAVES.defaultBlockState());
        gap[2] = leaves[2];
        assertEquals(41, top(gap, 0, 1, 1).height());
    }

    /** Below zero and high - loss-free packaging; nothing - 0. */
    @Test
    void packing() {
        int p = LssColumns.pack(-63, MapColor.DEEPSLATE.id, 0);
        assertTrue(LssColumns.present(p));
        assertEquals(-63, LssColumns.height(p));
        assertEquals(MapColor.DEEPSLATE.id, LssColumns.color(p));
        p = LssColumns.pack(320, MapColor.WATER.id, 16);
        assertEquals(320, LssColumns.height(p));
        assertEquals(16, LssColumns.waterDepth(p));
        assertFalse(LssColumns.present(0));
        assertNull(LssColumns.column(0));
    }

    private static int[] chunk(int base) {
        int[] c = new int[LssColumns.FULL];
        for (int i = 0; i < c.length; i++) c[i] = LssColumns.pack(base + i, MapColor.STONE.id, 0);
        return c;
    }

    /** Rough chunk - the middle of the cells 4×4 (columns of the tile level 2): any column of the cell corresponds to its middle. */
    @Test
    void coarseKeepsCellCentres() {
        int[] full = chunk(0), small = LssColumns.coarse(full);
        assertEquals(LssColumns.COARSE, small.length);
        for (int z = 0; z < 16; z++) {
            for (int x = 0; x < 16; x++) {
                assertEquals(full[((z & ~3) + 2) * 16 + (x & ~3) + 2], LssColumns.at(small, x, z));
                assertEquals(full[z * 16 + x], LssColumns.at(full, x, z));
            }
        }
    }

    /** Storage: the new one replaces the old one, from the disk - does not overwrite the new one; negative coordinates in place. */
    @Test
    void storeReplacesAndFindsChunks() {
        LssColumns.Store s = new LssColumns.Store();
        s.put(-1, -33, chunk(10));
        assertEquals(LssColumns.at(chunk(10), -1, -513), s.get(-1, -513));
        assertEquals(0, s.get(16, 0), "no chunk - unknown");
        s.put(-1, -33, chunk(20));
        assertFalse(s.putIfAbsent(-1, -33, chunk(30)));
        assertEquals(LssColumns.at(chunk(20), -16, -528), s.get(-16, -528));
        assertEquals(1, s.chunks());
        assertEquals(1, s.fullChunks());
    }

    /** Detailed beyond the limit - coarse are those farthest from the player; the near ones remain detailed. */
    @Test
    void coarsensFarthestFirst() {
        LssColumns.Store s = new LssColumns.Store();
        for (int cx = 0; cx < 10; cx++) s.put(cx, 0, chunk(cx));
        assertEquals(0, s.coarsen(0, 0, 10, 5), "in the limit - nothing");
        assertEquals(5, s.coarsen(0, 0, 8, 5));
        assertEquals(5, s.fullChunks());
        assertEquals(10, s.chunks());
        for (int cx = 0; cx < 5; cx++) assertEquals(LssColumns.FULL, s.chunk(cx, 0).length, "near detailed");
        for (int cx = 5; cx < 10; cx++) assertEquals(LssColumns.COARSE, s.chunk(cx, 0).length, "distant rough");
        s.put(9, 0, chunk(9));
        assertEquals(6, s.fullChunks(), "came again - detailed");
    }

    /** Chunks of everything are beyond the limit - the ones that are farthest from the player go, detailed and rough alike. */
    @Test
    void trimsFarthestChunks() {
        LssColumns.Store s = new LssColumns.Store();
        for (int cx = -5; cx < 5; cx++) s.put(cx, 0, chunk(cx));
        s.coarsen(0, 0, 4, 4);
        assertEquals(0, s.trim(0, 0, 10, 6), "in the limit - nothing");
        assertEquals(4, s.trim(0, 0, 8, 6));
        assertEquals(6, s.chunks());
        for (int cx = -3; cx < 3; cx++) assertTrue(s.chunk(cx, 0) != null, "the neighbor remained");
        assertNull(s.chunk(-5, 0));
        assertNull(s.chunk(4, 0));
        assertEquals(4, s.fullChunks(), "detailed (close) are not touched");
        s.put(4, 0, chunk(4));
        assertEquals(7, s.chunks(), "came again - right there");
    }

    /** File: everything that was (and rough) reads the same; someone else's file is an error, not garbage in the card. */
    @Test
    void fileRoundTrip() throws IOException {
        LssColumns.Store s = new LssColumns.Store();
        s.put(0, 0, chunk(1));
        s.put(-40, 70, chunk(2));
        s.coarsen(0, 0, 1, 1);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        s.write(new DataOutputStream(bytes));
        LssColumns.Store back = new LssColumns.Store();
        assertEquals(2, back.read(new DataInputStream(new ByteArrayInputStream(bytes.toByteArray()))));
        assertArrayEquals(s.chunk(0, 0), back.chunk(0, 0));
        assertArrayEquals(s.chunk(-40, 70), back.chunk(-40, 70));
        assertEquals(1, back.fullChunks());
        byte[] junk = new byte[16];
        org.junit.jupiter.api.Assertions.assertThrows(IOException.class, () -> new LssColumns.Store().read(new DataInputStream(new ByteArrayInputStream(junk))));
    }
}
