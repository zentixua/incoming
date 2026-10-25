package ua.zentix.incoming.client.fx;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.fml.loading.LoadingModList;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import ua.zentix.incoming.nuclear.model.DustModel;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Сор у камеры ({@link NearLitter}): какая поверхность у места — та же, что у пыли волны ({@code FarBlasts.ground}). */
class NearLitterTest {
    private static BlockState AIR;

    @BeforeAll
    static void boot() {
        SharedConstants.tryDetectVersion();
        if (LoadingModList.get() == null) LoadingModList.of(List.of(), List.of(), List.of(), List.of(), Map.of());
        Bootstrap.bootStrap();
        AIR = Blocks.AIR.defaultBlockState();
    }

    /** Трава на дёрне — клок травы, держит до 3 м/с (дёрн); голая земля, песок и гравий — крошка, рыхлое. */
    @Test
    void groundCoverPicksTheSurfaceOfThePlace() {
        NearLitter.Cover grass = NearLitter.cover(Blocks.GRASS_BLOCK.defaultBlockState(), Blocks.SHORT_GRASS.defaultBlockState());
        assertSame(DustModel.Surface.COVERED, grass.surface());
        assertEquals(Fragments.Kind.LEAF, grass.kind());
        assertTrue(grass.torn().is(Blocks.SHORT_GRASS), "травинка, а не кубик земли");
        NearLitter.Cover turf = NearLitter.cover(Blocks.GRASS_BLOCK.defaultBlockState(), AIR);
        assertSame(DustModel.Surface.COVERED, turf.surface());
        assertTrue(turf.torn().is(Blocks.SHORT_GRASS), "дёрн рвётся травой, не землёй");
        // (песок и листва — по тегам, а теги в юнит-тесте не загружены: только блоки, что узнаются по себе)
        for (BlockState loose : new BlockState[]{Blocks.GRAVEL.defaultBlockState(), Blocks.DIRT.defaultBlockState(), Blocks.DIRT_PATH.defaultBlockState()}) {
            NearLitter.Cover c = NearLitter.cover(loose, AIR);
            assertSame(DustModel.Surface.FIELD, c.surface(), loose.toString());
            assertEquals(Fragments.Kind.CHUNK, c.kind());
            assertSame(loose, c.torn(), "крошка своего блока");
        }
    }

    /** Камень и кирпич — только осевшая пыль (кусков нет); вода и воздух — ничего. */
    @Test
    void hardAndWater() {
        for (BlockState hard : new BlockState[]{Blocks.STONE.defaultBlockState(), Blocks.BRICKS.defaultBlockState()}) {
            NearLitter.Cover c = NearLitter.cover(hard, AIR);
            assertSame(DustModel.Surface.HARD, c.surface(), hard.toString());
            assertFalse(c.pieces(), "с твёрдого — только пыль");
        }
        assertNull(NearLitter.cover(Blocks.WATER.defaultBlockState(), AIR));
        assertNull(NearLitter.cover(AIR, AIR));
    }
}
