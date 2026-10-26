package ua.zentix.incoming.client.map;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.material.MapColor;
import net.neoforged.fml.loading.LoadingModList;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.IntUnaryOperator;
import java.util.function.LongToIntFunction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The top of the columns from sections Voxy is like the layer of client chunks and LSS, at rough levels it is the middle of the voxel. */
class VoxyColumnsTest {
    private static final int GRASS = 1, STONE = 2, WATER = 3, GLASS = 4, TALL_GRASS = 5, SAND = 6;
    /** Block number - bits 27–46, like Voxy ({@code Mapper.getBlockId}). */
    private static final LongToIntFunction ID = v -> (int) (v >>> 27 & 0xFFFFF);
    private static final IntUnaryOperator INFO = id -> switch (id) {
        case GRASS -> VoxyColumns.KNOWN | VoxyColumns.MOTION | MapColor.GRASS.id;
        case STONE -> VoxyColumns.KNOWN | VoxyColumns.MOTION | MapColor.STONE.id;
        case SAND -> VoxyColumns.KNOWN | VoxyColumns.MOTION | MapColor.SAND.id;
        case WATER -> VoxyColumns.KNOWN | VoxyColumns.MOTION | VoxyColumns.WATER | MapColor.WATER.id;
        case GLASS -> VoxyColumns.KNOWN | VoxyColumns.MOTION;
        case TALL_GRASS -> VoxyColumns.KNOWN | MapColor.PLANT.id;
        default -> 0;
    };

    @BeforeAll
    static void boot() {
        SharedConstants.tryDetectVersion();
        if (LoadingModList.get() == null) LoadingModList.of(List.of(), List.of(), List.of(), List.of(), Map.of());
        Bootstrap.bootStrap();
    }

    /** Column of sections of level {@code level}: column voxels (x, z) according to the height of the world in blocks (bottom of the voxel). */
    private static final class Stack {
        final int level;
        final TreeMap<Integer, long[]> sections = new TreeMap<>();

        Stack(int level) {
            this.level = level;
        }

        Stack put(int x, int z, int y, int id) {
            int vy = y >> level;
            long[] s = sections.computeIfAbsent(vy >> 5, k -> new long[VoxyColumns.VOLUME]);
            s[(vy & 31) << 10 | z << 5 | x] = (long) id << 27;
            return this;
        }

        /** Summary: sections from top to bottom from {@code topSection} to {@code bottomSection}, which are not - null. */
        int[] summarize(int topSection, int bottomSection) {
            VoxyColumns.Summary s = new VoxyColumns.Summary(level);
            for (int sy = topSection; sy >= bottomSection && !s.done(); sy--) s.section(sy, sections.get(sy), ID, INFO);
            return s.finish();
        }
    }

    private static int at(int[] columns, int x, int z) {
        return columns[z << 5 | x];
    }

    @Test
    void groundAtBlockLevelIsExact() {
        int[] c = new Stack(0).put(3, 4, 63, STONE).put(3, 4, 64, GRASS).summarize(9, -2);
        int v = at(c, 3, 4);
        assertTrue(LssColumns.present(v));
        assertEquals(65, LssColumns.height(v), "first air over the grass");
        assertEquals(MapColor.GRASS.id, LssColumns.color(v));
        assertEquals(0, LssColumns.waterDepth(v));
        assertFalse(LssColumns.present(at(c, 0, 0)), "column where Voxy saw nothing - unknown");
    }

    @Test
    void plantsDoNotStopTheHeightmapButGlassKeepsTheColourBelow() {
        int[] c = new Stack(0).put(1, 1, 64, GRASS).put(1, 1, 65, TALL_GRASS)
                .put(2, 2, 70, STONE).put(2, 2, 71, GLASS).summarize(9, -2);
        assertEquals(65, LssColumns.height(at(c, 1, 1)), "grass does not hold MOTION_BLOCKING");
        assertEquals(MapColor.GRASS.id, LssColumns.color(at(c, 1, 1)));
        assertEquals(71, LssColumns.height(at(c, 2, 2)), "under the glass there is a stone, like a layer of chunks");
        assertEquals(MapColor.STONE.id, LssColumns.color(at(c, 2, 2)));
    }

    @Test
    void colourlessDeeperThanEightBlocksIsUnknown() {
        Stack s = new Stack(0);
        for (int y = 60; y < 70; y++) s.put(0, 0, y, GLASS);
        s.put(0, 0, 59, STONE);
        assertFalse(LssColumns.present(at(s.summarize(9, -2), 0, 0)));
    }

    @Test
    void waterDepthAcrossSectionsAndCapped() {
        Stack s = new Stack(0).put(5, 5, 90, SAND);
        for (int y = 91; y <= 98; y++) s.put(5, 5, y, WATER); // 8 water blocks across the boundary of sections 95|96
        s.put(6, 6, 10, SAND);
        for (int y = 11; y <= 40; y++) s.put(6, 6, y, WATER);
        int[] c = s.summarize(9, -2);
        assertEquals(99, LssColumns.height(at(c, 5, 5)));
        assertEquals(MapColor.WATER.id, LssColumns.color(at(c, 5, 5)));
        assertEquals(8, LssColumns.waterDepth(at(c, 5, 5)));
        assertEquals(LssColumns.MAX_WATER_DEPTH, LssColumns.waterDepth(at(c, 6, 6)), "deeper 16 - the same");
    }

    @Test
    void coarseLevelTakesTheMiddleOfTheVoxel() {
        // level 2: voxel 4 block; top of the grass - somewhere from 65 to 68 block, middle - 66
        int[] c = new Stack(2).put(0, 0, 64, GRASS).put(1, 0, 60, SAND).put(1, 0, 64, WATER).put(1, 0, 68, WATER).summarize(2, -1);
        assertEquals(66, LssColumns.height(at(c, 0, 0)));
        assertEquals(VoxyColumns.height(2, 68), LssColumns.height(at(c, 1, 0)));
        assertEquals(8, LssColumns.waterDepth(at(c, 1, 0)), "two voxels of water per 4 block");
        // level 4: voxel 16 blocks - middle, error up to 8 blocks
        assertEquals(64 + 8, VoxyColumns.height(4, 64));
        assertEquals(65, VoxyColumns.height(0, 64));
    }

    @Test
    void sectionsGoTopDown() {
        VoxyColumns.Summary s = new VoxyColumns.Summary(0);
        s.section(3, null, ID, INFO);
        assertThrows(IllegalArgumentException.class, () -> s.section(3, null, ID, INFO));
    }

    @Test
    void blockInfoAsTheHeightmap() {
        int grass = VoxyColumns.info(Blocks.GRASS_BLOCK.defaultBlockState());
        assertTrue((grass & VoxyColumns.MOTION) != 0);
        assertEquals(MapColor.GRASS.id, grass & 0x3F);
        int water = VoxyColumns.info(Blocks.WATER.defaultBlockState());
        assertTrue((water & VoxyColumns.WATER) != 0 && (water & VoxyColumns.MOTION) != 0, "water holds MOTION_BLOCKING");
        assertEquals(0, VoxyColumns.info(Blocks.SHORT_GRASS.defaultBlockState()) & VoxyColumns.MOTION);
        assertEquals(MapColor.NONE.id, VoxyColumns.info(Blocks.GLASS.defaultBlockState()) & 0x3F);
    }
}
