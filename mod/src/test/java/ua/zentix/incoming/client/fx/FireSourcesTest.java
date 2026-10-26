package ua.zentix.incoming.client.fx;

import net.minecraft.SharedConstants;
import net.minecraft.core.MappedRegistry;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.neoforged.fml.loading.LoadingModList;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Burnt горит собственными блоками; остывшие состояния остаются в тех же тегах. */
class FireSourcesTest {
    private static final BooleanProperty HOT = BooleanProperty.create("hot");
    private static final IntegerProperty STAGE = IntegerProperty.create("stage", 0, 3);

    @BeforeAll
    static void boot() {
        SharedConstants.tryDetectVersion();
        if (LoadingModList.get() == null) LoadingModList.of(List.of(), List.of(), List.of(), List.of(), Map.of());
        Bootstrap.bootStrap();
    }

    private static final class Smoldering extends Block {
        Smoldering() {
            super(BlockBehaviour.Properties.of());
        }

        @Override
        protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
            builder.add(STAGE, HOT);
        }
    }

    @Test
    void vanillaFireStillWorksWithoutBurnt() {
        assertTrue(FireSources.burning(Blocks.FIRE.defaultBlockState()));
        assertTrue(FireSources.burning(Blocks.SOUL_FIRE.defaultBlockState()));
        assertFalse(FireSources.burning(Blocks.OAK_LOG.defaultBlockState()));
        assertFalse(FireSources.burning(Blocks.CAMPFIRE.defaultBlockState()));
    }

    @Test
    @SuppressWarnings("deprecation") // В юнит-тесте нет события RegisterEvent; реестр закрываем сразу после двух фикстур.
    void taggedFlamesAndHotMaterialSmokeButColdMaterialDoesNot() {
        MappedRegistry<Block> registry = (MappedRegistry<Block>) BuiltInRegistries.BLOCK;
        registry.unfreeze();
        Block flames = new Block(BlockBehaviour.Properties.of());
        Block material = new Smoldering();
        BlockState cold = material.defaultBlockState().setValue(STAGE, 0).setValue(HOT, false);
        assertFalse(FireSources.burning(cold.setValue(HOT, true)), "без тега просто горячий блок не дымит");
        var flameHolder = Registry.registerForHolder(BuiltInRegistries.BLOCK,
                ResourceLocation.fromNamespaceAndPath("incoming_test", "flames"), flames);
        var materialHolder = Registry.registerForHolder(BuiltInRegistries.BLOCK,
                ResourceLocation.fromNamespaceAndPath("incoming_test", "smoldering"), material);
        registry.freeze();
        var oldTags = BuiltInRegistries.BLOCK.getTags().collect(java.util.stream.Collectors.toMap(
                com.mojang.datafixers.util.Pair::getFirst, p -> p.getSecond().stream().toList()));
        try {
            BuiltInRegistries.BLOCK.bindTags(Map.of(FireSources.SMOKE,
                    List.of(flameHolder, materialHolder, BuiltInRegistries.BLOCK.wrapAsHolder(Blocks.CAMPFIRE))));
            assertTrue(FireSources.burning(flames.defaultBlockState()));
            assertFalse(FireSources.burning(Blocks.CAMPFIRE.defaultBlockState()), "костёр в теге Burnt сохраняет свой дым");
            assertFalse(FireSources.burning(cold));
            assertTrue(FireSources.burning(cold.setValue(HOT, true)));
            assertTrue(FireSources.burning(cold.setValue(STAGE, 1)), "stage >= 1 горит и при hot=false");
            // Одна секция: полный осмотр видит все три источника, а не только BaseFireBlock.
            BlockState[] states = {flames.defaultBlockState(), cold, cold.setValue(HOT, true), cold.setValue(STAGE, 2)};
            int[] window = new int[4 * FireWatch.SAMPLE + 1];
            assertEquals(3, FireWatch.phases(j -> j < states.length && FireSources.burning(states[j]), 0, window));
        } finally {
            BuiltInRegistries.BLOCK.bindTags(oldTags);
        }
    }
}
