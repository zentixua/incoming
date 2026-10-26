package ua.zentix.incoming.compat;

import com.mojang.logging.LogUtils;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.neoforged.fml.ModList;
import org.slf4j.Logger;

/**
 * Ruins written directly into sections ({@code RuinPlan}), for Sable craft physics. Sable 2.0.5–2.0.6 keeps a copy of world
 * blocks near craft (sections in the Rapier scene, {@code PhysicsChunkTicketManager}: sections near craft, up to 20 ticks
 * after a craft leaves) and updates it only from {@code LevelChunk.setBlockState} (its own {@code plot.LevelChunkMixin} →
 * {@code SableCommonEvents.handleBlockChange}); a section write bypassing it is invisible to the craft — it would collide
 * with the house that used to be there. Plan positions near craft are therefore reported through the same call.
 * The call reads a position's neighbors, and solid neighbors' neighbors (up to 2 blocks), so the plan is applied only
 * when neighboring chunks are loaded.
 * <p>
 * Sable itself is only referenced in {@link Hook}: it loads on the first call, and is called only when Sable is installed.
 */
public final class SableTerrain {
    private static final Logger LOG = LogUtils.getLogger();
    /** How far from a craft's bounds Sable can keep world sections, in blocks: with a margin for 20 ticks of flight. */
    private static final double NEAR = 64;
    private static Boolean present;
    private static boolean broken;

    private SableTerrain() {}

    public static boolean present() {
        if (present == null) present = ModList.get() != null && ModList.get().isLoaded("sable");
        return present && !broken;
    }

    /** Whether the chunk has nearby craft whose physics may hold its sections. */
    public static boolean watched(ServerLevel level, LevelChunk chunk) {
        if (!present()) return false;
        var pos = chunk.getPos();
        Vec3 centre = new Vec3(pos.getMiddleBlockX(), (level.getMinBuildHeight() + level.getMaxBuildHeight()) / 2.0, pos.getMiddleBlockZ());
        return !SubLevels.near(level, centre, 8 + NEAR + (level.getMaxBuildHeight() - level.getMinBuildHeight()) / 2.0).isEmpty();
    }

    /** A chunk position changed outside {@code LevelChunk.setBlockState}: Sable updates its copy. */
    public static void changed(ServerLevel level, LevelChunk chunk, int x, int y, int z, BlockState old, BlockState now) {
        if (broken || old == now) return;
        try {
            Hook.changed(level, chunk, x, y, z, old, now);
        } catch (RuntimeException | LinkageError e) {
            broken = true;
            LOG.error("Sable: ruin changes are no longer reported to aircraft physics", e);
        }
    }

    private static final class Hook {
        static void changed(ServerLevel level, LevelChunk chunk, int x, int y, int z, BlockState old, BlockState now) {
            dev.ryanhcode.sable.SableCommonEvents.handleBlockChange(level, chunk, x, y, z, old, now);
        }
    }
}
