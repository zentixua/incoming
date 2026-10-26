package ua.zentix.incoming.nuclear.world;

import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import org.jetbrains.annotations.Nullable;
import ua.zentix.incoming.Incoming;
import ua.zentix.incoming.nuclear.model.Fragility;
import ua.zentix.incoming.registry.ModTags;

import java.util.List;

/**
 * Scorched biomes: light pulse dries and burns grass and leaves kilometers beyond wave destruction; their color
 * (and water color) changes in tiers: singed ground (brown-yellow), scorched (brown), charred (dark brown). Own
 * data biomes ({@code data/incoming/worldgen/biome}), differing from plains only in grass, foliage and water colors.
 * Client blends neighboring-cell colors (biome blend), giving smooth edge on ground and maps.
 * <p>
 * Tier on biome grid (4×4 horizontal blocks), by cell ground light and world noise ({@link Fragility}): chunk ruin
 * plan computes it in background ({@code RuinPlanner}); written on replacement like {@code /fillbiome}
 * ({@code fillBiomesFromNoise} per section: old biome except tiered cells), with biome packet to players viewing
 * chunk. Cells change from {@link #BELOW} blocks underground upward: deeper caves/biomes untouched. Preserve
 * biomes tagged {@link ModTags#NUKE_KEEPS_BIOME} (oceans, rivers, caves), without precipitation (desert, badlands, savanna,
 * already dry; plains rain would arrive permanently), and snowy biomes (warm biome would prevent future snow and ice permanently); own
 * biomes only advance in tier. Permanent change: singed-biome grass stays brown.
 */
public final class ScorchedBiomes {
    /** Tiers in order: 1 singed ground, 3 charred. */
    static final List<ResourceKey<Biome>> LEVELS = List.of(key("singed"), key("scorched"), key("charred"));
    /** Ground light (cal/cm²) where half the cells reach tier: foliage browns from ~3, grass burns from ~5–6. */
    static final double[] MEDIAN = {3, 6, 12};
    /** Tier spread (lognormal, {@link Fragility}). */
    static final double BETA = 0.35;
    /** Blocks beneath surface still changed (client biome blending also samples vertical neighbors). */
    static final int BELOW = 8;
    /** Below this temperature biome is snowy ({@code Biome.coldEnoughToSnow} at sea level). */
    private static final float SNOWY = 0.15f;

    private ScorchedBiomes() {}

    private static ResourceKey<Biome> key(String name) {
        return ResourceKey.create(Registries.BIOME, Incoming.id(name));
    }

    /** Cell tier by ground light and local randomness {@code z} (~N(0, 1)): 0 unchanged. */
    static int level(double q, double z) {
        for (int i = LEVELS.size(); i >= 1; i--) if (Fragility.hit(q, MEDIAN[i - 1], BETA, z)) return i;
        return 0;
    }

    /** Biome tier if ours, otherwise 0. */
    static int levelOf(Holder<Biome> biome) {
        for (int i = 0; i < LEVELS.size(); i++) if (biome.is(LEVELS.get(i))) return i + 1;
        return 0;
    }

    /** Whether biome changes to {@code level}. */
    static boolean replaces(Holder<Biome> biome, int level) {
        int now = levelOf(biome);
        if (now > 0) return level > now;
        return !biome.is(ModTags.NUKE_KEEPS_BIOME) && biome.value().hasPrecipitation() && biome.value().getBaseTemperature() >= SNOWY;
    }

    /**
     * Chunk tiers from plan: {@code levels} by cell columns ({@code qz << 2 | qx}, 0 unchanged); {@code from} is
     * lowest block (world y) where column cells change.
     */
    record Cells(byte[] levels, int[] from) {
        boolean same(@Nullable Cells o) {
            return o != null && java.util.Arrays.equals(levels, o.levels) && java.util.Arrays.equals(from, o.from);
        }

        @Override
        public String toString() {
            return java.util.Arrays.toString(levels);
        }
    }

    /**
     * Write tiers into chunk sections: server thread in world, disk-chunk copy thread for copies ({@link FarLods}).
     * {@code minSection} is vertical section index of {@code sections[0]}.
     *
     * @return changed cell count
     */
    static int write(Registry<Biome> registry, LevelChunkSection[] sections, int minSection, Cells cells) {
        @SuppressWarnings("unchecked")
        Holder<Biome>[] ours = (Holder<Biome>[]) new Holder<?>[LEVELS.size()];
        for (int i = 0; i < ours.length; i++) {
            ours[i] = registry.getHolder(LEVELS.get(i)).orElse(null);
            // mod data absent (datapack removed biomes): colors unchanged
            if (ours[i] == null) return 0;
        }
        int changed = 0;
        @SuppressWarnings("unchecked")
        Holder<Biome>[] next = (Holder<Biome>[]) new Holder<?>[64];
        for (int i = 0; i < sections.length; i++) {
            LevelChunkSection s = sections[i];
            int y0 = (minSection + i) << 4;
            boolean any = false;
            for (int qy = 0; qy < 4; qy++) {
                for (int qz = 0; qz < 4; qz++) {
                    for (int qx = 0; qx < 4; qx++) {
                        Holder<Biome> was = s.getNoiseBiome(qx, qy, qz);
                        int column = qz << 2 | qx, level = cells.levels[column];
                        Holder<Biome> now = was;
                        // cell entirely below column start: underground cave, untouched
                        if (level > 0 && y0 + (qy << 2) + 3 >= cells.from[column] && replaces(was, level)) {
                            now = ours[level - 1];
                            any = true;
                            changed++;
                        }
                        next[qy << 4 | qz << 2 | qx] = now;
                    }
                }
            }
            if (any) s.fillBiomesFromNoise((x, y, z, sampler) -> next[y << 4 | z << 2 | x], null, 0, 0, 0);
        }
        return changed;
    }

    /** Apply tiers to world chunk (server thread), send biome packet to viewers: true if anything changed. */
    static boolean write(ServerLevel level, LevelChunk chunk, Cells cells) {
        if (write(level.registryAccess().registryOrThrow(Registries.BIOME), chunk.getSections(), chunk.getMinSection(), cells) == 0) return false;
        chunk.setUnsaved(true);
        level.getChunkSource().chunkMap.resendBiomesForChunks(List.of(chunk));
        return true;
    }
}
