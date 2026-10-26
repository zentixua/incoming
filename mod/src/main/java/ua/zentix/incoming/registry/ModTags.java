package ua.zentix.incoming.registry;

import net.minecraft.core.registries.Registries;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Block;
import ua.zentix.incoming.Incoming;

/** Tags (data/incoming/tags). Bomb block medium is the {@link ModDataMaps#PENETRATION} data map, not tags. */
public final class ModTags {
    /** Fly through these: air, grass, flowers, snow layers, fire. */
    public static final TagKey<Block> PASSABLE = block("passable");
    /** Broken by the shock wave. */
    public static final TagKey<Block> SHATTERS = block("shatters");
    /** Nuclear strike: natural ground (untouched by wave, only light on the top layer and the crater). */
    public static final TagKey<Block> NUKE_GROUND = block("nuke_ground");
    /** Nuclear strike: breaks at 0.5–1 psi. */
    public static final TagKey<Block> NUKE_FRAGILE = block("nuke_fragile");
    /** Nuclear strike: plants torn away by the dynamic pressure that also strips turf (~8 psi); a weak wave only bends them. */
    public static final TagKey<Block> NUKE_PLANTS = block("nuke_plants");
    /** Nuclear strike: light structures, 3–5 psi. */
    public static final TagKey<Block> NUKE_LIGHT = block("nuke_light");
    /** Nuclear strike: masonry, 10–15 psi. */
    public static final TagKey<Block> NUKE_MASONRY = block("nuke_masonry");
    /** Nuclear strike: flash chars faces black — wood, wool, hay ({@code nuclear/flash/FlashChar}). */
    public static final TagKey<Block> NUKE_CHARS = block("nuke_chars");
    /** Nuclear strike: flash only deposits soot on faces — masonry, concrete, terracotta. */
    public static final TagKey<Block> NUKE_SOOTS = block("nuke_soots");

    /**
     * Fuel fireball ignites with a pulse ({@code nuclear/model/PulseIgnition}): leaves, paper, dry grass and shrubs, fabric —
     * each has its own threshold; other combustibles (wood) need burning fuel on them ({@code warhead/Craters}).
     */
    public static final TagKey<Block> PULSE_LEAVES = block("pulse_leaves");
    public static final TagKey<Block> PULSE_PAPER = block("pulse_paper");
    public static final TagKey<Block> PULSE_GRASS = block("pulse_grass");
    public static final TagKey<Block> PULSE_FABRIC = block("pulse_fabric");

    /** Nuclear strike: biomes light does not recolor (seas, rivers, caves) — {@code nuclear/world/ScorchedBiomes}. */
    public static final TagKey<Biome> NUKE_KEEPS_BIOME = TagKey.create(Registries.BIOME, Incoming.id("nuke_keeps_biome"));

    /** Transparent to aiming: glue, seats, Create/Sable helper entities. */
    public static final TagKey<EntityType<?>> AIM_IGNORE = TagKey.create(Registries.ENTITY_TYPE, Incoming.id("aim_ignore"));

    private ModTags() {}

    private static TagKey<Block> block(String name) {
        return TagKey.create(Registries.BLOCK, Incoming.id(name));
    }
}
