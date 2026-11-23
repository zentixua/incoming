package ua.zentix.incoming.nuclear.material;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.Block;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/** Stable two-bit surface classes. A tied shore vote goes to water, then sand, soil and hard ground. */
public enum DustMaterial {
    WATER, SAND, SOIL, HARD;

    private static final DustMaterial[] CLASSES = values();
    public static DustMaterial of(int bits) { return CLASSES[bits]; }

    public static boolean sod(BlockState s) {
        Block block = s.getBlock();
        return BuiltInRegistries.BLOCK.wrapAsHolder(block).is(BlockTags.DIRT) || block == Blocks.MOSS_CARPET
                || block == Blocks.MUD || block == Blocks.CLAY;
    }
    public static boolean loose(BlockState s) {
        Block block = s.getBlock();
        return block == Blocks.DIRT || block == Blocks.COARSE_DIRT || block == Blocks.ROOTED_DIRT || block == Blocks.FARMLAND
                || block == Blocks.DIRT_PATH || BuiltInRegistries.BLOCK.wrapAsHolder(block).is(BlockTags.SAND)
                || block == Blocks.GRAVEL || snow(block) || block == Blocks.SUSPICIOUS_SAND || block == Blocks.SUSPICIOUS_GRAVEL;
    }
    private static boolean snow(Block block) {
        return block == Blocks.SNOW || block == Blocks.SNOW_BLOCK || block == Blocks.POWDER_SNOW;
    }
    public static DustMaterial from(BlockState s) {
        if (s.getFluidState().is(FluidTags.WATER)) return WATER;
        if (!s.getFluidState().isEmpty()) return SOIL;
        Block block = s.getBlock();
        if (BuiltInRegistries.BLOCK.wrapAsHolder(block).is(BlockTags.SAND)) return SAND;
        if (snow(block)) return HARD;
        return loose(s) || sod(s) ? SOIL : HARD;
    }
}
