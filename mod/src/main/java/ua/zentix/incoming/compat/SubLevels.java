package ua.zentix.incoming.compat;

import dev.ryanhcode.sable.companion.SableCompanion;
import dev.ryanhcode.sable.companion.SubLevelAccess;
import dev.ryanhcode.sable.companion.math.BoundingBox3d;
import net.minecraft.core.Position;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.neoforged.fml.ModList;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3d;
import ua.zentix.incoming.Incoming;
import ua.zentix.incoming.util.Terrain;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * The sole integration point for Sable flying craft (“sub-levels”), through sable-companion (MIT, bundled in the jar).
 * Without Sable, companion returns a stub: no craft, coordinates unchanged.
 * <p>
 * Layout: craft blocks live in a distant plot in the same world; their projection is rendered in the world.
 * With Sable, vanilla {@code Level.clip} already accounts for craft and returns hits in plot coordinates —
 * so it is enough to check whether the point lies in a plot and project it into the world.
 */
public final class SubLevels {
    private static volatile boolean broken;
    private static volatile long lastFailureLog;

    private SubLevels() {}

    private static SableCompanion companion() {
        return SableCompanion.INSTANCE;
    }

    /** Craft whose plot contains the point, or null. */
    @Nullable
    public static SubLevelAccess containing(Level level, Position plotPos) {
        if (broken) return null;
        try {
            return companion().getContaining(level, plotPos);
        } catch (RuntimeException | LinkageError e) {
            fail(e);
            return null;
        }
    }

    public static boolean isInPlot(Level level, Position pos) {
        return containing(level, pos) != null;
    }

    /** World coordinates of a plot point (unchanged for a point outside a plot). */
    public static Vec3 toWorld(Level level, Vec3 pos) {
        if (broken) return pos;
        try {
            return companion().projectOutOfSubLevel(level, (Position) pos);
        } catch (RuntimeException | LinkageError e) {
            fail(e);
            return pos;
        }
    }

    /** Plot point currently at world position world (for craft sub). */
    public static Vec3 toPlot(SubLevelAccess sub, Vec3 world) {
        try {
            return sub.logicalPose().transformPositionInverse(world);
        } catch (RuntimeException | LinkageError e) {
            fail(e);
            return world;
        }
    }

    /** Craft center in the world. */
    public static Vec3 center(SubLevelAccess sub) {
        try {
            Vector3d c = sub.boundingBox().center();
            return new Vec3(c.x, c.y, c.z);
        } catch (RuntimeException | LinkageError e) {
            fail(e);
            return Vec3.ZERO;
        }
    }

    /** How much smaller the craft is in the world than in its plot (smallest scale axis; 1 if unavailable). */
    public static double minScale(SubLevelAccess sub) {
        try {
            var s = sub.logicalPose().scale();
            double m = Math.min(s.x(), Math.min(s.y(), s.z()));
            return m > 1e-3 ? m : 1;
        } catch (RuntimeException | LinkageError e) {
            fail(e);
            return 1;
        }
    }

    /** Plot cells enclosing a craft's world bounds. Reading them still requires Terrain.ready. */
    public static Iterable<net.minecraft.core.BlockPos> plotCells(SubLevelAccess sub) {
        var b = sub.boundingBox().toMojang();
        double x0 = Double.POSITIVE_INFINITY, y0 = x0, z0 = x0;
        double x1 = Double.NEGATIVE_INFINITY, y1 = x1, z1 = x1;
        for (double x : new double[]{b.minX, b.maxX}) for (double y : new double[]{b.minY, b.maxY})
            for (double z : new double[]{b.minZ, b.maxZ}) {
                Vec3 p = toPlot(sub, new Vec3(x, y, z));
                x0 = Math.min(x0, p.x); y0 = Math.min(y0, p.y); z0 = Math.min(z0, p.z);
                x1 = Math.max(x1, p.x); y1 = Math.max(y1, p.y); z1 = Math.max(z1, p.z);
            }
        return net.minecraft.core.BlockPos.betweenClosed(net.minecraft.core.BlockPos.containing(x0, y0, z0),
                net.minecraft.core.BlockPos.containing(x1, y1, z1));
    }

    /** Exact distance to a posed voxel (rotation and scale included), rather than to a hollow craft's bounds. */
    public static double blockRangeSqr(SubLevelAccess sub, net.minecraft.core.BlockPos cell, Vec3 source) {
        Vec3 local = toPlot(sub, source);
        Vec3 nearest = new Vec3(net.minecraft.util.Mth.clamp(local.x, cell.getX(), cell.getX() + 1.0),
                net.minecraft.util.Mth.clamp(local.y, cell.getY(), cell.getY() + 1.0),
                net.minecraft.util.Mth.clamp(local.z, cell.getZ(), cell.getZ() + 1.0));
        return sub.logicalPose().transformPosition(nearest).distanceToSqr(source);
    }

    /** Craft within a radius (by bounds). */
    public static List<SubLevelAccess> near(Level level, Vec3 at, double radius) {
        List<SubLevelAccess> out = new ArrayList<>();
        if (broken) return out;
        try {
            BoundingBox3d box = new BoundingBox3d(at.x - radius, at.y - radius, at.z - radius, at.x + radius, at.y + radius, at.z + radius);
            for (SubLevelAccess s : companion().getAllIntersecting(level, box)) out.add(s);
        } catch (RuntimeException | LinkageError e) {
            fail(e);
        }
        return out;
    }

    /**
     * Whether a craft could be in the cube ±{@code radius} around {@code at}: at least one craft's bounds overlap — or Sable
     * is installed but its integration is broken (then “yes”: better to take the vanilla path with all Sable mixins).
     */
    public static boolean mayHaveCraftNear(Level level, Vec3 at, double radius) {
        if (!ModList.get().isLoaded("sable")) return false;
        if (broken) return true;
        List<SubLevelAccess> near = near(level, at, radius);
        return broken || !near.isEmpty();
    }

    /** All craft in the world. */
    public static List<SubLevelAccess> all(Level level) {
        return near(level, Vec3.ZERO, 3.0e7);
    }

    /** Chunk in Sable's plot grid, even when the craft there is not loaded (unlike {@link #containing}). */
    public static boolean inPlotGrid(Level level, net.minecraft.world.level.ChunkPos chunk) {
        if (broken) return false;
        try {
            return companion().isInPlotGrid(level, chunk);
        } catch (RuntimeException | LinkageError e) {
            fail(e);
            return false;
        }
    }

    /** Craft whose plot contains the chunk, or null. */
    @Nullable
    public static SubLevelAccess containing(Level level, net.minecraft.world.level.ChunkPos chunk) {
        if (broken) return null;
        try {
            return companion().getContaining(level, chunk);
        } catch (RuntimeException | LinkageError e) {
            fail(e);
            return null;
        }
    }

    /** ID of the craft whose plot contains the chunk, or null. */
    @Nullable
    public static UUID containingId(Level level, net.minecraft.world.level.ChunkPos chunk) {
        SubLevelAccess sub = containing(level, chunk);
        return sub == null ? null : uniqueId(sub);
    }

    @Nullable
    private static UUID uniqueId(SubLevelAccess sub) {
        try {
            return sub.getUniqueId();
        } catch (RuntimeException | LinkageError e) {
            fail(e);
            return null;
        }
    }

    @Nullable
    public static SubLevelAccess byId(Level level, Vec3 near, UUID id) {
        for (SubLevelAccess s : near(level, near, 2048)) {
            if (id.equals(s.getUniqueId())) return s;
        }
        return null;
    }

    /**
     * A craft at plot point {@code plotPos} is visible from {@code from}: the ray to its world position hits neither world
     * blocks nor another craft — hitting the craft itself means seeing it (with Sable, {@code Level.clip} returns craft hits
     * in its plot coordinates). Ready chunks only ({@link Terrain#readyAlong}).
     */
    public static boolean visibleFrom(ServerLevel level, Vec3 from, Vec3 plotPos) {
        SubLevelAccess craft = containing(level, plotPos);
        if (craft == null) return false;
        Vec3 to = toWorld(level, plotPos);
        if (!Terrain.readyAlong(level, from, to)) return false;
        BlockHitResult hit = level.clip(new ClipContext(from, to, ClipContext.Block.VISUAL, ClipContext.Fluid.NONE, CollisionContext.empty()));
        if (hit.getType() == HitResult.Type.MISS) return true;
        SubLevelAccess struck = containing(level, hit.getLocation());
        UUID id = uniqueId(craft);
        return struck != null && id != null && id.equals(uniqueId(struck));
    }

    /** Craft name for the interface. */
    public static Component describe(@Nullable SubLevelAccess subLevel) {
        String name = subLevel == null ? null : name(subLevel);
        return name == null || name.isBlank()
                ? Component.translatable("incoming.target.aircraft")
                : Component.translatable("incoming.target.aircraft.named", name);
    }

    /** Craft name assigned by a player; null — unnamed. */
    @Nullable
    public static String name(SubLevelAccess subLevel) {
        try {
            return subLevel.getName();
        } catch (RuntimeException | LinkageError e) {
            fail(e);
            return null;
        }
    }

    /**
     * Sable's API does not promise stability (Sable README). If a modpack update breaks it (missing class or method —
     * {@link LinkageError}), the mod continues without craft targeting until restart, and the reason stays in the log.
     * An exception in an individual call (the craft is splitting or already removed) affects only that call: otherwise one
     * Sable failure would disable targeting of all craft until the game restarts.
     */
    private static void fail(Throwable e) {
        if (e instanceof LinkageError) {
            if (!broken) {
                broken = true;
                Incoming.LOG.error("Sable companion broke — targeting aircraft is disabled until restart", e);
            }
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastFailureLog > 60_000) {
            lastFailureLog = now;
            Incoming.LOG.warn("Sable did not answer an aircraft query (query skipped)", e);
        }
    }
}
