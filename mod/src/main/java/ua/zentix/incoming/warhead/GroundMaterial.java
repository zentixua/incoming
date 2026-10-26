package ua.zentix.incoming.warhead;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.SlabType;
import org.jetbrains.annotations.Nullable;
import ua.zentix.incoming.util.Terrain;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * Crater material: selects debris, dust color, ground fountain. Check below impact first, then struck block
 * itself, which takes priority.
 */
public enum GroundMaterial {
    DIRT(() -> Blocks.DIRT, 0.36f, 0.27f, 0.18f, 1,
            "coarse_dirt", "dirt", "coarse_dirt", "rooted_dirt", "dirt", "grass_block", "coarse_dirt", "gravel", "dirt"),
    STONE(() -> Blocks.STONE, 0.5f, 0.5f, 0.5f, 0,
            "cobblestone", "cobblestone", "gravel", "stone", "cobblestone_slab", "andesite", "gravel", "cobblestone", "tuff"),
    DEEPSLATE(() -> Blocks.DEEPSLATE, 0.3f, 0.3f, 0.32f, 0,
            "cobbled_deepslate", "cobbled_deepslate", "cobbled_deepslate", "deepslate", "cobbled_deepslate_slab", "gravel", "tuff", "cobbled_deepslate", "gravel"),
    SAND(() -> Blocks.SAND, 0.85f, 0.78f, 0.58f, 1,
            "sand", "sand", "sand", "sandstone", "sandstone", "sandstone_slab", "sand", "smooth_sandstone", "gravel"),
    SNOW(() -> Blocks.SNOW_BLOCK, 0.95f, 0.95f, 0.97f, 1,
            "snow_block", "snow_block", "snow_block", "packed_ice", "snow", "dirt", "coarse_dirt", "snow_block", "ice"),
    WOOD(() -> Blocks.OAK_PLANKS, 0.45f, 0.33f, 0.2f, 0,
            "oak_planks", "spruce_planks", "stripped_dark_oak_log", "dark_oak_slab", "oak_slab", "spruce_fence", "oak_planks", "stripped_spruce_log", "dark_oak_planks"),
    BRICK(() -> Blocks.STONE_BRICKS, 0.55f, 0.52f, 0.5f, 0,
            "cracked_stone_bricks", "cracked_stone_bricks", "stone_brick_slab", "cobblestone", "cobblestone", "stone_brick_wall", "gravel", "bricks", "cobblestone_slab"),
    TERRACOTTA(() -> Blocks.TERRACOTTA, 0.6f, 0.38f, 0.28f, 0,
            "terracotta", "terracotta", "terracotta", "clay", "brown_terracotta", "coarse_dirt", "gravel", "terracotta", "dirt"),
    GRAVEL(() -> Blocks.GRAVEL, 0.45f, 0.42f, 0.38f, 0.5f,
            "cobblestone", "gravel", "gravel", "stone", "cobblestone_slab", "andesite", "coarse_dirt", "dirt", "gravel"),
    WATER(() -> Blocks.GRAVEL, 0.8f, 0.85f, 0.9f, 0,
            "gravel", "gravel", "gravel", "sand", "sand", "clay", "clay", "dirt", "gravel");

    private final Supplier<Block> particle;
    public final float r, g, b;
    /**
     * Acoustic porosity G, ISO 9613-2 (7.3.1): 1 loose (grassy soil, sand, snow), attenuates highs near ground; 0
     * hard (stone, masonry, wood, water), reflects; gravel between.
     */
    public final float porosity;
    private final String[] debris;
    private List<BlockState> debrisStates;

    /**
     * Crater-law ground ({@link ua.zentix.incoming.nuclear.model.CraterModel.Soil}): rock means stone/masonry;
     * saturated means underwater bottom; dry means soil/sand/gravel (rail ballast is loose rubble, not rock:
     * complaint 23.10, ballast crater was 30% narrower than field). ⚠ Snow as saturated: no snow-crater data,
     * looser than dry ground (estimate).
     */
    public ua.zentix.incoming.nuclear.model.CraterModel.Soil soil() {
        return switch (this) {
            case STONE, DEEPSLATE, BRICK -> ua.zentix.incoming.nuclear.model.CraterModel.Soil.ROCK;
            case WATER, SNOW -> ua.zentix.incoming.nuclear.model.CraterModel.Soil.WET;
            default -> ua.zentix.incoming.nuclear.model.CraterModel.Soil.DRY;
        };
    }

    /**
     * Homogeneous propagation medium at the source, not a ray trace through the whole ground path.
     * Estimates for Minecraft categories: snow/gravel are loose, timber is on soil, masonry acts as fractured rock;
     * WATER represents the saturated bed, not a solid seismic wave propagating through water. Terracotta is fired
     * clay, not evidence of water saturation. These approximations do not change the separate crater material law.
     */
    public ua.zentix.incoming.nuclear.model.GroundShock.Medium shockMedium() {
        return switch (this) {
            case STONE, DEEPSLATE, BRICK, TERRACOTTA -> ua.zentix.incoming.nuclear.model.GroundShock.Medium.ROCK;
            case WATER -> ua.zentix.incoming.nuclear.model.GroundShock.Medium.SATURATED;
            case SAND, GRAVEL, SNOW -> ua.zentix.incoming.nuclear.model.GroundShock.Medium.LOOSE;
            case DIRT, WOOD -> ua.zentix.incoming.nuclear.model.GroundShock.Medium.SOIL;
        };
    }

    GroundMaterial(Supplier<Block> particle, float r, float g, float b, float porosity, String... debris) {
        this.particle = particle;
        this.r = r;
        this.g = g;
        this.b = b;
        this.porosity = porosity;
        this.debris = debris;
    }

    /** Block for ground-fountain particles. */
    public BlockState particleBlock() {
        return particle.get().defaultBlockState();
    }

    /** Nine ground debris pieces (like debris/gN). */
    public List<BlockState> debris() {
        if (debrisStates == null) debrisStates = states(debris);
        return debrisStates;
    }

    public boolean isWater() {
        return this == WATER;
    }

    /** Impact ground; unready chunk defaults gravel: server read would load synchronously in tick. */
    public static GroundMaterial sample(Level level, BlockPos at) {
        if (!Terrain.ready(level, at)) return GRAVEL;
        GroundMaterial below = of(level.getBlockState(at.below()));
        GroundMaterial here = of(level.getBlockState(at));
        return here != null ? here : below != null ? below : GRAVEL;
    }

    /** Block material; {@code null} neither ground nor structure (air, grass, untagged mod blocks). */
    public static @Nullable GroundMaterial of(BlockState s) {
        if (s.is(Blocks.WATER)) return WATER;
        if (s.is(BlockTags.TERRACOTTA) || s.is(Blocks.CLAY)) return TERRACOTTA;
        if (s.is(BlockTags.STONE_BRICKS) || s.is(Blocks.COBBLESTONE) || s.is(Blocks.BRICKS) || s.is(Blocks.MOSSY_COBBLESTONE)) return BRICK;
        if (s.is(BlockTags.PLANKS) || s.is(BlockTags.LOGS) || s.is(BlockTags.LEAVES) || s.is(BlockTags.WOODEN_SLABS)) return WOOD;
        if (s.is(BlockTags.SNOW) || s.is(BlockTags.ICE)) return SNOW;
        if (s.is(BlockTags.SAND) || s.is(Blocks.SANDSTONE)) return SAND;
        if (s.is(Blocks.DEEPSLATE) || s.is(Blocks.COBBLED_DEEPSLATE) || s.is(Blocks.TUFF)) return DEEPSLATE;
        // Gravel is loose rubble (rail ballast), not rock: own crater/dust/sound ({@link #GRAVEL}).
        if (s.is(Blocks.GRAVEL)) return GRAVEL;
        if (s.is(BlockTags.BASE_STONE_OVERWORLD)) return STONE;
        if (s.is(BlockTags.DIRT) || s.is(Blocks.GRASS_BLOCK)) return DIRT;
        // Concrete and tagged masonry are cohesive rock, rather than the earth below the paving.
        if (s.is(ua.zentix.incoming.registry.ModTags.NUKE_MASONRY)) return STONE;
        return null;
    }

    /** Blocks by name; missing (mod absent) skipped. “_slab” uses lower half. */
    public static List<BlockState> states(String... ids) {
        List<BlockState> out = new ArrayList<>();
        for (String id : ids) {
            ResourceLocation rl = id.contains(":") ? ResourceLocation.parse(id) : ResourceLocation.withDefaultNamespace(id);
            BuiltInRegistries.BLOCK.getOptional(rl).ifPresent(block -> {
                BlockState s = block.defaultBlockState();
                if (s.hasProperty(BlockStateProperties.SLAB_TYPE)) s = s.setValue(BlockStateProperties.SLAB_TYPE, SlabType.BOTTOM);
                out.add(s);
            });
        }
        return List.copyOf(out);
    }

    private static final GroundMaterial[] VALUES = values();

    public static GroundMaterial byId(int id) {
        return id >= 0 && id < VALUES.length ? VALUES[id] : GRAVEL;
    }
}
