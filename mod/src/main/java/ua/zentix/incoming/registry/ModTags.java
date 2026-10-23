package ua.zentix.incoming.registry;

import net.minecraft.core.registries.Registries;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Block;
import ua.zentix.incoming.Incoming;

/** Теги (data/incoming/tags). Среда блока для бомбы — карта данных {@link ModDataMaps#PENETRATION}, не теги. */
public final class ModTags {
    /** Сквозь это летим: воздух, трава, цветы, снег-слой, огонь. */
    public static final TagKey<Block> PASSABLE = block("passable");
    /** Бьются ударной волной. */
    public static final TagKey<Block> SHATTERS = block("shatters");
    /** Ядерный удар: природный грунт (волна не трогает, только свет — верхний слой — и воронка). */
    public static final TagKey<Block> NUKE_GROUND = block("nuke_ground");
    /** Ядерный удар: ломается при 0.5–1 psi. */
    public static final TagKey<Block> NUKE_FRAGILE = block("nuke_fragile");
    /** Ядерный удар: растения — их срывает напор, что сдирает и дёрн (~8 psi); слабая волна их только гнёт. */
    public static final TagKey<Block> NUKE_PLANTS = block("nuke_plants");
    /** Ядерный удар: лёгкие постройки, 3–5 psi. */
    public static final TagKey<Block> NUKE_LIGHT = block("nuke_light");
    /** Ядерный удар: кладка, 10–15 psi. */
    public static final TagKey<Block> NUKE_MASONRY = block("nuke_masonry");
    /** Ядерный удар: вспышка обугливает грани до черноты — дерево, шерсть, сено ({@code nuclear/flash/FlashChar}). */
    public static final TagKey<Block> NUKE_CHARS = block("nuke_chars");
    /** Ядерный удар: вспышка только закапчивает грани — кладка, бетон, терракота. */
    public static final TagKey<Block> NUKE_SOOTS = block("nuke_soots");

    /**
     * Шар топлива поджигает импульсом ({@code nuclear/model/PulseIgnition}): листва, бумага, сухая трава и кусты, ткань —
     * у каждого свой порог; остальное горючее (дерево) — только горящее топливо на нём ({@code warhead/Craters}).
     */
    public static final TagKey<Block> PULSE_LEAVES = block("pulse_leaves");
    public static final TagKey<Block> PULSE_PAPER = block("pulse_paper");
    public static final TagKey<Block> PULSE_GRASS = block("pulse_grass");
    public static final TagKey<Block> PULSE_FABRIC = block("pulse_fabric");

    /** Ядерный удар: биомы, которые свет не перекрашивает (моря, реки, пещеры), — {@code nuclear/world/ScorchedBiomes}. */
    public static final TagKey<Biome> NUKE_KEEPS_BIOME = TagKey.create(Registries.BIOME, Incoming.id("nuke_keeps_biome"));

    /** Прозрачны для прицела: «клей», сиденья, служебные сущности Create/Sable. */
    public static final TagKey<EntityType<?>> AIM_IGNORE = TagKey.create(Registries.ENTITY_TYPE, Incoming.id("aim_ignore"));

    private ModTags() {}

    private static TagKey<Block> block(String name) {
        return TagKey.create(Registries.BLOCK, Incoming.id(name));
    }
}
