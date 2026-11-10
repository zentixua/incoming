package ua.zentix.incoming.client.far;

import org.junit.jupiter.api.Test;

import net.minecraft.world.level.material.MapColor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FarTerrainTest {
    @Test
    void leavesDoNotReplaceTheForestFloorAndSolidsStillSupportDust() {
        // Leaves at 10–12 m, open air under them, floor at 0: the cold flow belongs on the floor.
        assertEquals(0, FarTerrain.belowCanopy(12, -64, true, y -> y > 0));
        // An actual solid at 5 m is a surface, whether stone, wood, a roof or a ledge.
        assertEquals(5, FarTerrain.belowCanopy(12, -64, true, y -> y > 5));
        assertEquals(12, FarTerrain.belowCanopy(12, -64, false, y -> y > 0));
        assertEquals(-64, FarTerrain.belowCanopy(12, -64, true, y -> true));
    }

    /**
     * Hard ground for nuclear dust by terrain-tile map color ({@link FarTerrain#hard} beyond client chunks): grass, foliage (a forest
     * lofts its floor), dirt, sand are soil; rock, snow, ice, wood and metal (roofs, roads) are hard; water and unknown are not hard.
     */
    @Test
    void mapColorsTellSoilFromHardGround() {
        for (MapColor soil : new MapColor[]{MapColor.GRASS, MapColor.PLANT, MapColor.DIRT, MapColor.SAND, MapColor.PODZOL, MapColor.CLAY}) {
            assertFalse(FarTerrain.hardColor(soil.id), "soil " + soil.id);
        }
        for (MapColor hard : new MapColor[]{MapColor.STONE, MapColor.DEEPSLATE, MapColor.SNOW, MapColor.ICE, MapColor.WOOD, MapColor.METAL,
                MapColor.COLOR_GRAY, MapColor.TERRACOTTA_WHITE}) {
            assertTrue(FarTerrain.hardColor(hard.id), "hard " + hard.id);
        }
        assertFalse(FarTerrain.hardColor(MapColor.WATER.id));
        assertFalse(FarTerrain.hardColor(MapColor.NONE.id));
    }
}
