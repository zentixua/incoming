package ua.zentix.incoming.nuclear.world;

import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.event.TagsUpdatedEvent;
import ua.zentix.incoming.nuclear.model.BlastDamage;
import ua.zentix.incoming.nuclear.model.BlastModel;
import ua.zentix.incoming.registry.ModTags;

import java.util.IdentityHashMap;
import java.util.Map;

/**
 * Block response to shock wave: overpressure threshold (psi) and consequence.
 * Thresholds from DESIGN-nuke §3.3: {@code incoming:nuke_*} tags (datapack-overridable), otherwise
 * block strength. Response cached per block state.
 */
public record BlockResponse(float thresholdPsi, Kind kind) {
    public static final float NEVER = Float.MAX_VALUE;
    /** Fragile threshold ({@code incoming:nuke_fragile}: glass, panes, lanterns, torches), psi, lowest of all. */
    public static final float FRAGILE_PSI = 0.8f;
    /**
     * Plant threshold ({@code incoming:nuke_plants}: grass, flowers, bushes, crops), psi: stripped by same pressure that
     * strips turf ({@code RuinPlanner.STRIP_PSI}); weaker wave bends rather than breaks. Light burns them farther away.
     */
    public static final float PLANT_PSI = 8f;
    private static final float JITTER_SPAN = 0.3f;
    /** Threshold variation in {@link #breaksAt}: block fails between threshold × {@code JITTER_MIN} and threshold × {@code JITTER_MAX}. */
    public static final float JITTER_MIN = 0.85f, JITTER_MAX = JITTER_MIN + JITTER_SPAN;

    public enum Kind {
        /** Unaffected by wave (air, liquid, indestructible). */
        NONE,
        /** Ground (soil, stone, terracotta): breaks like masonry, but thick mountains and banks withstand wave ({@link Blast}). */
        GROUND,
        /** Breaks into air. */
        BREAK,
        /** Leaves: break, ignite under strong light pulse. */
        LEAVES,
        /** Log: ground-rooted tree trunk topples away from epicenter. */
        LOG
    }

    private static final BlockResponse NONE = new BlockResponse(NEVER, Kind.NONE);
    private static final BlockResponse GROUND = new BlockResponse(15f, Kind.GROUND);
    private static final Map<BlockState, BlockResponse> CACHE = new IdentityHashMap<>();

    public static synchronized BlockResponse of(BlockState s) {
        return CACHE.computeIfAbsent(s, BlockResponse::classify);
    }

    /** Tags may change: /reload or different single-player world (own datapacks). */
    public static synchronized void clearCache() {
        CACHE.clear();
        Blast.clearProps();
    }

    public static void onTagsUpdated(TagsUpdatedEvent e) {
        if (e.getUpdateCause() == TagsUpdatedEvent.UpdateCause.SERVER_DATA_LOAD) clearCache();
    }

    public boolean breaksAt(double psi, int jitterSeed) {
        if (thresholdPsi == NEVER) return false;
        // ±15%: neighboring identical blocks do not break along a ruler-straight line
        return psi >= thresholdPsi * jitter(jitterSeed);
    }

    /**
     * Whether conventional-charge wave breaks block: pressure {@code psi} and impulse {@code impulsePas} (Pa·s) against
     * threshold's iso-damage curve ({@link BlastDamage}). Same threshold variation as {@link #breaksAt(double, int)},
     * on both axes. Nuclear-wave impulse is far above curve, giving same result as pressure alone.
     */
    public boolean breaksAt(double psi, double impulsePas, int jitterSeed) {
        if (thresholdPsi == NEVER) return false;
        float j = jitter(jitterSeed);
        return BlastDamage.breaks(BlastModel.kpa(psi) / j, impulsePas / j, BlastModel.kpa(thresholdPsi));
    }

    /** Wave fraction passing through after block breaks ({@link BlastDamage#passes}); 0 if unbroken. */
    public double passes(double psi, double impulsePas, int jitterSeed) {
        if (thresholdPsi == NEVER) return 0;
        float j = jitter(jitterSeed);
        return BlastDamage.passes(BlastModel.kpa(psi) / j, impulsePas / j, BlastModel.kpa(thresholdPsi));
    }

    /**
     * Block-threshold multiplier at {@code pos} ({@code JITTER_MIN}…{@code JITTER_MAX}): mod explosions
     * derive variation from position hash, so same block at same place breaks identically.
     */
    public static float jitterAt(BlockPos pos) {
        return jitter(Mth.murmurHash3Mixer(Long.hashCode(pos.asLong())));
    }

    private static float jitter(int seed) {
        return JITTER_MIN + JITTER_SPAN * ((seed * 0x9E3779B9 >>> 8) & 0xFFFF) / 65535f;
    }

    private static BlockResponse classify(BlockState s) {
        if (s.isAir() || s.getBlock() instanceof net.minecraft.world.level.block.LiquidBlock) return NONE;
        // wave never breaks indestructible or ultra-strong blocks (bedrock, barrier, obsidian), regardless of tags
        float destroy = s.getBlock().defaultDestroyTime();
        if (destroy < 0 || destroy >= 50) return NONE;
        if (s.is(ModTags.NUKE_GROUND)) return GROUND;
        if (s.is(ModTags.NUKE_FRAGILE)) return new BlockResponse(FRAGILE_PSI, Kind.BREAK);
        if (s.is(ModTags.NUKE_PLANTS)) return new BlockResponse(PLANT_PSI, Kind.BREAK);
        if (s.is(BlockTags.LEAVES)) return new BlockResponse(2f, Kind.LEAVES);
        if (s.is(BlockTags.LOGS)) return new BlockResponse(5f, Kind.LOG);
        if (s.is(ModTags.NUKE_LIGHT)) return new BlockResponse(4f, Kind.BREAK);
        if (s.is(ModTags.NUKE_MASONRY)) return new BlockResponse(12f, Kind.BREAK);
        Block b = s.getBlock();
        float hardness = b.defaultDestroyTime();
        if (hardness < 0) return NONE;
        if (hardness <= 0.5f) return new BlockResponse(1f, Kind.BREAK);
        if (hardness <= 3f) return new BlockResponse(5f, Kind.BREAK);
        if (hardness <= 6f) return new BlockResponse(12f, Kind.BREAK);
        if (hardness <= 9f) return new BlockResponse(25f, Kind.BREAK);
        return NONE;
    }
}
