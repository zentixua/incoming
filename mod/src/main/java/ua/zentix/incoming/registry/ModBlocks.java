package ua.zentix.incoming.registry;

import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredRegister;
import ua.zentix.incoming.Incoming;
import ua.zentix.incoming.defense.SamBlock;
import ua.zentix.incoming.grid.SubstationBlock;
import ua.zentix.incoming.grid.block.UnlitBlock;
import ua.zentix.incoming.grid.block.UnlitBulbBlock;
import ua.zentix.incoming.grid.block.UnlitEndRodBlock;
import ua.zentix.incoming.grid.block.UnlitLampBlock;
import ua.zentix.incoming.grid.block.UnlitLanternBlock;
import ua.zentix.incoming.grid.block.UnlitLightBlock;
import ua.zentix.incoming.grid.block.UnlitPillarBlock;
import ua.zentix.incoming.launcher.FixedLauncherBlock;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.BiFunction;
import java.util.function.Supplier;

public final class ModBlocks {
    public static final DeferredRegister.Blocks REGISTER = DeferredRegister.createBlocks(Incoming.MOD_ID);

    /** Trinitite: sand fused by a surface nuclear fireball into greenish glass. */
    public static final DeferredBlock<Block> TRINITITE = REGISTER.registerSimpleBlock("trinitite", BlockBehaviour.Properties.of()
            .mapColor(MapColor.COLOR_GREEN)
            .strength(1.5f, 6.0f)
            .requiresCorrectToolForDrops()
            .sound(SoundType.GLASS));

    /** Transformer substation: grid node; a strike cuts power to its surrounding area ({@link ua.zentix.incoming.grid.Blackouts}). */
    public static final DeferredBlock<SubstationBlock> SUBSTATION = REGISTER.register("substation", () -> new SubstationBlock(
            BlockBehaviour.Properties.of()
                    .mapColor(MapColor.COLOR_GRAY)
                    .strength(5.0f, 6.0f)
                    .requiresCorrectToolForDrops()
                    .sound(SoundType.METAL)
                    .noOcclusion()));

    /**
     * SAM: radar cabin beneath a canister rack ({@link SamBlock}). Strength as for a substation: a nearby Shahed explosion
     * destroys it.
     */
    public static final DeferredBlock<SamBlock> SAM = REGISTER.register("sam", () -> new SamBlock(
            BlockBehaviour.Properties.of()
                    .mapColor(MapColor.COLOR_GREEN)
                    .strength(5.0f, 6.0f)
                    .requiresCorrectToolForDrops()
                    .sound(SoundType.METAL)));

    /**
     * Fixed launcher ({@link FixedLauncherBlock}): redstone signal fires the mission. Strength as for the SAM.
     */
    public static final DeferredBlock<FixedLauncherBlock> FIXED_LAUNCHER = REGISTER.register("fixed_launcher", () -> new FixedLauncherBlock(
            BlockBehaviour.Properties.of()
                    .mapColor(MapColor.COLOR_GREEN)
                    .strength(5.0f, 6.0f)
                    .requiresCorrectToolForDrops()
                    .sound(SoundType.METAL)));

    /** Grid lamp and its unpowered counterpart. */
    public record UnlitPair(Supplier<Block> lit, DeferredBlock<? extends Block> unlit) {}

    private static final List<UnlitPair> UNLIT_PAIRS = new ArrayList<>();
    /** All lamp/counterpart pairs ({@link ua.zentix.incoming.grid.GridLights}); torches, candles, campfires and furnaces are off-grid. */
    public static final List<UnlitPair> UNLIT = Collections.unmodifiableList(UNLIT_PAIRS);

    static {
        unlit("glowstone", () -> Blocks.GLOWSTONE, UnlitBlock::new);
        unlit("sea_lantern", () -> Blocks.SEA_LANTERN, UnlitBlock::new);
        unlit("shroomlight", () -> Blocks.SHROOMLIGHT, UnlitBlock::new);
        unlit("ochre_froglight", () -> Blocks.OCHRE_FROGLIGHT, UnlitPillarBlock::new);
        unlit("verdant_froglight", () -> Blocks.VERDANT_FROGLIGHT, UnlitPillarBlock::new);
        unlit("pearlescent_froglight", () -> Blocks.PEARLESCENT_FROGLIGHT, UnlitPillarBlock::new);
        unlit("lantern", () -> Blocks.LANTERN, UnlitLanternBlock::new);
        unlit("soul_lantern", () -> Blocks.SOUL_LANTERN, UnlitLanternBlock::new);
        unlit("end_rod", () -> Blocks.END_ROD, UnlitEndRodBlock::new);
        unlit("redstone_lamp", () -> Blocks.REDSTONE_LAMP, UnlitLampBlock::new);
        unlit("copper_bulb", () -> Blocks.COPPER_BULB, UnlitBulbBlock::new);
        unlit("exposed_copper_bulb", () -> Blocks.EXPOSED_COPPER_BULB, UnlitBulbBlock::new);
        unlit("weathered_copper_bulb", () -> Blocks.WEATHERED_COPPER_BULB, UnlitBulbBlock::new);
        unlit("oxidized_copper_bulb", () -> Blocks.OXIDIZED_COPPER_BULB, UnlitBulbBlock::new);
        unlit("waxed_copper_bulb", () -> Blocks.WAXED_COPPER_BULB, UnlitBulbBlock::new);
        unlit("waxed_exposed_copper_bulb", () -> Blocks.WAXED_EXPOSED_COPPER_BULB, UnlitBulbBlock::new);
        unlit("waxed_weathered_copper_bulb", () -> Blocks.WAXED_WEATHERED_COPPER_BULB, UnlitBulbBlock::new);
        unlit("waxed_oxidized_copper_bulb", () -> Blocks.WAXED_OXIDIZED_COPPER_BULB, UnlitBulbBlock::new);
        unlit("light", () -> Blocks.LIGHT, UnlitLightBlock::new);
    }

    private ModBlocks() {}

    /**
     * Counterpart fully copies lamp properties (strength, sound, map color), except light; drops use the lamp's loot table
     * ({@code ofFullCopy} does not copy the table by block name: without {@code lootFrom} the counterpart would have its own,
     * empty one). Properties read at registration: vanilla blocks already exist then.
     */
    private static void unlit(String name, Supplier<Block> lit, BiFunction<BlockBehaviour.Properties, Supplier<Block>, Block> factory) {
        DeferredBlock<Block> unlit = REGISTER.register("unlit_" + name,
                () -> factory.apply(BlockBehaviour.Properties.ofFullCopy(lit.get()).lightLevel(s -> 0).lootFrom(lit), lit));
        UNLIT_PAIRS.add(new UnlitPair(lit, unlit));
    }
}
