package ua.zentix.incoming.client.fx;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.block.BaseFireBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.CampfireBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.IntegerProperty;

/** Источники дыма, в том числе горящие блоки модов, которые не наследуют {@link BaseFireBlock}. */
final class FireSources {
    static final TagKey<Block> SMOKE = TagKey.create(Registries.BLOCK,
            ResourceLocation.fromNamespaceAndPath("incoming", "fire_smoke_sources"));

    private FireSources() {}

    static boolean burning(BlockState state) {
        if (state.getBlock() instanceof BaseFireBlock) return true;
        // Burnt включает ванильный костёр в on_fire; у него уже свой дым, даже без Burnt.
        if (state.getBlock() instanceof CampfireBlock || !state.is(SMOKE)) return false;
        var definition = state.getBlock().getStateDefinition();
        // Burnt 1.10.5.2: stage >= 1 горит; stage 0 дымит лишь пока hot. Старые листья с radius ещё горят.
        var stage = definition.getProperty("stage");
        if (stage instanceof IntegerProperty p && state.getValue(p) >= 1) return true;
        if (definition.getProperty("radius") instanceof IntegerProperty) return true;
        if (definition.getProperty("hot") instanceof BooleanProperty p) return state.getValue(p);
        return !(stage instanceof IntegerProperty);
    }
}
