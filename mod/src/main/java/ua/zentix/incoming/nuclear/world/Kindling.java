package ua.zentix.incoming.nuclear.world;

import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;
import ua.zentix.incoming.nuclear.model.PulseIgnition;
import ua.zentix.incoming.registry.ModTags;

/**
 * What the nuclear flash can set burning: dry fuel only ({@link PulseIgnition}: live vegetation chars and smokes, never
 * sustains). Blocks by tag — paper ({@code incoming:pulse_paper}), fabric ({@code pulse_fabric}), dry plant fuel
 * ({@code pulse_dry}: hay, dead bush) — and turf where the game's climate cures grass ({@link #cured}). Server flash layer
 * and ruins ({@code RuinPlanner}) and the client fire model use the same classes.
 */
public final class Kindling {
    /**
     * Biomes whose downfall is at most this have cured grass, a continuous dry fuel bed (BUSTER's Nevada grass, 4–12 %
     * moisture). Vanilla 1.21 gives downfall 0 exactly to its arid and savanna biomes (desert, savanna, badlands and their
     * variants, all without precipitation: Köppen B and tropical savanna with a dry season, whose grass is drawn by the
     * colormap's yellow dry corner) and at least 0.3 to every other overworld biome (windswept hills, stony shore 0.3,
     * plains 0.4, forests 0.6–0.8, jungles and swamps 0.9; {@code data/minecraft/worldgen/biome}). The threshold sits
     * between the two groups, so modded biomes follow the downfall their authors gave them.
     */
    public static final float DRY_DOWNFALL = 0.15f;

    private Kindling() {}

    /** Fuel class of a block the flash can ignite, null if none (wood, live plants, leaves, soil). */
    @Nullable
    public static PulseIgnition.Fuel of(BlockState st) {
        if (st.is(ModTags.PULSE_PAPER)) return PulseIgnition.Fuel.PAPER;
        if (st.is(ModTags.PULSE_FABRIC)) return PulseIgnition.Fuel.FABRIC;
        if (st.is(ModTags.PULSE_DRY)) return PulseIgnition.Fuel.GRASS;
        return null;
    }

    /** Whether the biome's grass is cured: downfall at most {@link #DRY_DOWNFALL}. */
    public static boolean cured(Holder<Biome> biome) {
        return biome.value().getModifiedClimateSettings().downfall() <= DRY_DOWNFALL;
    }

    /** Biome at chunk-local block {@code lx}, {@code y}, {@code lz}; null unknown. */
    interface BiomeAt {
        @Nullable
        Holder<Biome> at(int lx, int y, int lz);
    }

    /**
     * Biome cells (4 × 4 columns, bit {@code qz << 2 | qx}) whose surface biome cures grass: the biome under each cell's middle
     * column at its top block ({@code surface}: first air above it by column index {@code lz << 4 | lx}).
     */
    static int curedCells(int minY, java.util.function.IntUnaryOperator surface, BiomeAt biome) {
        int mask = 0;
        for (int q = 0; q < 16; q++) {
            int lx = (q & 3) << 2 | 2, lz = (q >> 2) << 2 | 2;
            int y = Math.max(minY, surface.applyAsInt(lz << 4 | lx) - 1);
            Holder<Biome> b = biome.at(lx, y, lz);
            if (b != null && cured(b)) mask |= 1 << q;
        }
        return mask;
    }

    /**
     * Whether {@code st} is loose ground the ruins also leave as rubble of broken building parts ({@code RuinPlanner.STONE_RUBBLE},
     * {@code WOOD_RUBBLE}): gravel, rooted dirt, andesite, tuff. Before the wave it is ground; after it, a reader cannot tell it from
     * a fallen building. Coarse dirt is left out: the wave strips green turf to it, so it reads as ground (no fuel) — a fallen wooden
     * house still leaves its full blocks and the rest of its rubble. Cobblestone is not ground: it reads as built anyway.
     */
    public static boolean rubble(BlockState st) {
        if (st.is(Blocks.COARSE_DIRT) || st.is(Blocks.COBBLESTONE)) return false;
        for (BlockState r : RuinPlanner.STONE_RUBBLE) if (st.is(r.getBlock())) return true;
        for (BlockState r : RuinPlanner.WOOD_RUBBLE) if (st.is(r.getBlock())) return true;
        return false;
    }

    /** Whether {@code st} is turf that carries a grass bed. */
    public static boolean turf(BlockState st) {
        return st.is(Blocks.GRASS_BLOCK);
    }
}
