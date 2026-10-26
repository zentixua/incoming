package ua.zentix.incoming.client.map;

import me.cortex.voxy.common.world.WorldEngine;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.material.MapColor;
import net.neoforged.fml.loading.LoadingModList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Reading the Voxy database by reflection - on fakes of its classes of the same names ({@code src/test/java/me/cortex/voxy}). */
class VoxyTerrainTest {
    private WorldEngine engine;

    @BeforeAll
    static void boot() {
        SharedConstants.tryDetectVersion();
        if (LoadingModList.get() == null) LoadingModList.of(List.of(), List.of(), List.of(), List.of(), Map.of());
        Bootstrap.bootStrap();
    }

    @BeforeEach
    void voxy() {
        engine = new WorldEngine();
        WorldEngine.current = engine;
        assertTrue(VoxyTerrain.init(VoxyTerrainTest.class.getClassLoader()), "classes Voxy 0.2.16 found");
    }

    @AfterEach
    void gone() {
        WorldEngine.current = null;
    }

    /** Block in section of level {@code lvl} of the fake base: voxel in world coordinates. */
    private void put(int lvl, int x, int y, int z, int id) {
        int vx = x >> lvl, vy = y >> lvl, vz = z >> lvl;
        long[] s = engine.sections.computeIfAbsent(WorldEngine.key(lvl, vx >> 5, vy >> 5, vz >> 5), k -> new long[VoxyColumns.VOLUME]);
        s[(vy & 31) << 10 | (vz & 31) << 5 | vx & 31] = me.cortex.voxy.common.world.other.Mapper.voxel(id);
    }

    @Test
    void tileReadsItsLevelAndPeekAnswersFromMemory() {
        int grass = engine.mapper.id(Blocks.GRASS_BLOCK.defaultBlockState());
        put(0, 5, 64, 7, grass);
        put(0, -40, 120, -3, grass);
        VoxyTerrain t = new VoxyTerrain(null, -64, 320);
        assertTrue(t.active(), "world Voxy is");
        assertNull(t.peek(5, 7), "nothing in memory before reading");
        TerrainSource.Reader r = t.open(null, 0);
        assertNotNull(r);
        TerrainSource.Column c = r.column(5, 7);
        assertNotNull(c);
        assertEquals(65, c.height());
        assertEquals(MapColor.GRASS, c.color());
        assertEquals(121, r.column(-40, -3).height(), "negative coordinates - your section column");
        assertNull(r.column(6, 7), "Voxy didn't see this");
        assertEquals(65, t.peek(5, 7).height(), "read - immediately from memory");
        assertEquals(0, engine.refs, "acquireRef and releaseRef in pairs");
        assertEquals(engine.acquired, engine.released, "each section released");
    }

    @Test
    void coarseTileReadsCoarseSections() {
        int stone = engine.mapper.id(Blocks.STONE.defaultBlockState());
        put(4, 100, 64, 100, stone);
        put(0, 100, 70, 100, stone);
        VoxyTerrain t = new VoxyTerrain(null, -64, 320);
        assertTrue(t.active());
        // tile level 4 takes a voxel in 16 blocks, not a block one
        assertEquals(VoxyColumns.height(4, 64), t.open(null, 4).column(100, 100).height());
        assertEquals(VoxyColumns.height(4, 64), t.peek(100, 100).height(), "only rough is known");
        t.open(null, 0).column(100, 100);
        assertEquals(71, t.peek(100, 100).height(), "detailed - first");
    }

    @Test
    void closedVoxyWorldIsNotAFailure() {
        int grass = engine.mapper.id(Blocks.GRASS_BLOCK.defaultBlockState());
        put(0, 0, 64, 0, grass);
        VoxyTerrain t = new VoxyTerrain(null, -64, 320);
        assertTrue(t.active());
        TerrainSource.Reader r = t.open(null, 0);
        engine.live = false;
        assertNull(r.column(0, 0), "world Voxy is closed under reading - we don’t know");
        assertFalse(t.describe().contains("off"), "the source is not turned off: will find the world again");
    }

    @Test
    void failingSectionOfALiveWorldIsCountedNotTakenForClosed() {
        int grass = engine.mapper.id(Blocks.GRASS_BLOCK.defaultBlockState());
        put(0, 0, 64, 0, grass);
        VoxyTerrain t = new VoxyTerrain(null, -64, 320);
        assertTrue(t.active());
        engine.broken = true;
        assertNull(t.open(null, 0).column(0, 0));
        assertTrue(t.describe().startsWith("Voxy (world open)"), "the living world is not forgotten: " + t.describe());
        assertFalse(t.describe().contains("failures 0;"), "failure counted: " + t.describe());
    }

    /** The re-read rough summary became different (crater in the distance) - the detailed ones disappear below it: the rays do not take the relief before impact. */
    @Test
    void finerSummariesUnderAChangedCoarseOneAreDropped() {
        Map<Long, VoxyTerrain.Section> cache = new HashMap<>();
        VoxyTerrain.Section s = new VoxyTerrain.Section(new int[1024], 0, true);
        // level column 4 (-1, 2): blocks -512..-1 by x, 1024..1535 by z
        cache.put(VoxyTerrain.key(4, -1, 2), s);
        cache.put(VoxyTerrain.key(0, -16, 32), s);
        cache.put(VoxyTerrain.key(0, -1, 47), s);
        cache.put(VoxyTerrain.key(2, -4, 8), s);
        cache.put(VoxyTerrain.key(3, -2, 5), s);
        cache.put(VoxyTerrain.key(0, 0, 32), s);
        cache.put(VoxyTerrain.key(0, -17, 32), s);
        VoxyTerrain.dropFiner(cache, 4, -1, 2);
        assertTrue(cache.containsKey(VoxyTerrain.key(4, -1, 2)), "stays on her own");
        assertFalse(cache.containsKey(VoxyTerrain.key(0, -16, 32)));
        assertFalse(cache.containsKey(VoxyTerrain.key(0, -1, 47)));
        assertFalse(cache.containsKey(VoxyTerrain.key(2, -4, 8)));
        assertFalse(cache.containsKey(VoxyTerrain.key(3, -2, 5)));
        assertTrue(cache.containsKey(VoxyTerrain.key(0, 0, 32)) && cache.containsKey(VoxyTerrain.key(0, -17, 32)), "neighboring ones - no");
    }

    @Test
    void otherVoxyClassesTurnTheSourceOff() {
        assertFalse(VoxyTerrain.init(new ClassLoader(null) {}), "no classes - no source");
        assertFalse(new VoxyTerrain(null, -64, 320).active());
    }

    @Test
    void scanAroundIsNearestFirstAndInsideTheRadius() {
        List<long[]> cols = VoxyTerrain.around(0, 0, 4, 2048);
        assertTrue(cols.get(0)[2] < 512L * 512, "first is the player's column");
        for (int i = 1; i < cols.size(); i++) assertTrue(cols.get(i)[2] >= cols.get(i - 1)[2]);
        for (long[] c : cols) assertTrue(c[2] <= 2048L * 2048);
        // circle 2048 blocks in columns along 512: about π·4² ≈ 50
        assertTrue(cols.size() > 40 && cols.size() < 70, "columns " + cols.size());
    }

    @Test
    void trimDropsTheFarthest() {
        Map<Long, VoxyTerrain.Section> cache = new HashMap<>();
        VoxyTerrain.Section s = new VoxyTerrain.Section(new int[1024], 0, false);
        for (int i = -10; i < 10; i++) cache.put(VoxyTerrain.key(4, i, -3), s);
        assertEquals(0, VoxyTerrain.trim(cache, 0, 0, 20));
        assertEquals(20 - 9, VoxyTerrain.trim(cache, 0, 0, 10));
        assertTrue(cache.containsKey(VoxyTerrain.key(4, 0, -3)) && cache.containsKey(VoxyTerrain.key(4, -1, -3)), "neighbors remained");
        assertFalse(cache.containsKey(VoxyTerrain.key(4, -10, -3)), "the distant one left");
    }
}
