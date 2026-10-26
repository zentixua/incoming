package ua.zentix.replayscan;

import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jetbrains.annotations.Nullable;

/**
 * Ray along the blocks of the replay world (bypassing the cells of Amantides - Wu) and measuring the ground at the point. The world of replay is what the recording client received
 * : a chunk that he did not receive or that the replay server does not currently hold - the unknown, and not
 * air ({@code known} - chunks that came in the recording; {@code getChunkNow} - only ready-made ones, does not load anything).
 */
final class Rays {
    /**
     * What's on the ray: up to the first opaque one ({@code hard}, −1 - no), how much is transparent with the shape
     *  (foliage, glass, water - {@code soft}), where the unknown chunk began ({@code unknown}, −1 - no).
     */
    record Hit(double hard, int soft, double unknown) {}

    private final ServerLevel level;
    private final LongSet known;
    private final BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
    private long cachedKey = Long.MIN_VALUE;
    @Nullable
    private LevelChunk cached;

    Rays(ServerLevel level, LongSet known) {
        this.level = level;
        this.known = known;
    }

    @Nullable
    private LevelChunk chunk(int cx, int cz) {
        long key = (cx & 0xFFFFFFFFL) | (long) cz << 32;
        if (key != cachedKey) {
            cachedKey = key;
            cached = known.contains(key) ? level.getChunkSource().getChunkNow(cx, cz) : null;
        }
        return cached;
    }

    /** Block state or {@code null} if the chunk is unknown. Beyond the heights of the world is air. */
    @Nullable
    BlockState state(int x, int y, int z) {
        LevelChunk c = chunk(x >> 4, z >> 4);
        if (c == null) return null;
        if (y < level.getMinBuildHeight() || y >= level.getMaxBuildHeight()) return net.minecraft.world.level.block.Blocks.AIR.defaultBlockState();
        return c.getBlockState(pos.set(x, y, z));
    }

    /** The beam from {@code from} to {@code to}, the last {@code stopBefore} blocks are not visible (the target itself and its support). */
    Hit cast(Vec3 from, Vec3 to, double stopBefore) {
        Vec3 d = to.subtract(from);
        double length = d.length();
        double limit = length - stopBefore;
        if (limit <= 0) return new Hit(-1, 0, -1);
        Vec3 dir = d.scale(1 / length);
        int x = floor(from.x), y = floor(from.y), z = floor(from.z);
        int sx = (int) Math.signum(dir.x), sy = (int) Math.signum(dir.y), sz = (int) Math.signum(dir.z);
        double tdx = sx == 0 ? Double.POSITIVE_INFINITY : Math.abs(1 / dir.x);
        double tdy = sy == 0 ? Double.POSITIVE_INFINITY : Math.abs(1 / dir.y);
        double tdz = sz == 0 ? Double.POSITIVE_INFINITY : Math.abs(1 / dir.z);
        double tx = sx == 0 ? Double.POSITIVE_INFINITY : ((sx > 0 ? x + 1 - from.x : from.x - x) * tdx);
        double ty = sy == 0 ? Double.POSITIVE_INFINITY : ((sy > 0 ? y + 1 - from.y : from.y - y) * tdy);
        double tz = sz == 0 ? Double.POSITIVE_INFINITY : ((sz > 0 ? z + 1 - from.z : from.z - z) * tdz);
        double t = 0;
        int soft = 0;
        // the eye cage doesn’t look good: the viewer stands in the air, but the camera is sometimes right next to the wall
        boolean first = true;
        while (t <= limit) {
            if (!first) {
                BlockState s = state(x, y, z);
                if (s == null) return new Hit(-1, soft, t);
                if (!s.isAir()) {
                    if (!s.getFluidState().isEmpty()) soft++;
                    if (!(s.getBlock() instanceof LiquidBlock)) {
                        pos.set(x, y, z);
                        if (s.isSolidRender(level, pos)) return new Hit(t, soft, -1);
                        VoxelShape shape = s.getShape(level, pos);
                        if (!shape.isEmpty()) {
                            BlockHitResult hit = shape.clip(from, to, pos.immutable());
                            if (hit != null) {
                                double at = hit.getLocation().distanceTo(from);
                                if (at <= limit) {
                                    if (s.canOcclude()) return new Hit(at, soft, -1);
                                    soft++;
                                }
                            }
                        }
                    }
                }
            }
            first = false;
            if (tx < ty && tx < tz) {
                t = tx;
                tx += tdx;
                x += sx;
            } else if (ty < tz) {
                t = ty;
                ty += tdy;
                y += sy;
            } else {
                t = tz;
                tz += tdz;
                z += sz;
            }
        }
        return new Hit(-1, soft, -1);
    }

    /**
     * How many blocks from a point down to the first block with a collision shape: 0 - a point in a block, −1 - an unknown chunk,
     * {@code max + 1} - nothing within {@code max}.
     */
    int clearance(Vec3 p, int max) {
        int x = floor(p.x), z = floor(p.z), y0 = floor(p.y);
        for (int dy = 0; dy <= max; dy++) {
            BlockState s = state(x, y0 - dy, z);
            if (s == null) return -1;
            if (!s.getCollisionShape(level, pos.set(x, y0 - dy, z)).isEmpty()) return dy;
        }
        return max + 1;
    }

    /**
     * Distance from a point to the nearest block with a collision shape in a cube with side {@code 2r + 1} (at the centers of
     *  cells), −1 - there is an unknown chunk in the cube, but no solid was found, {@code r + 1} - nothing.
     */
    double nearestSolid(Vec3 p, int r) {
        int x0 = floor(p.x), y0 = floor(p.y), z0 = floor(p.z);
        double best = Double.POSITIVE_INFINITY;
        boolean unknown = false;
        for (int dx = -r; dx <= r; dx++) {
            for (int dy = -r; dy <= r; dy++) {
                for (int dz = -r; dz <= r; dz++) {
                    double dist = Math.sqrt(sq(x0 + dx + 0.5 - p.x) + sq(y0 + dy + 0.5 - p.y) + sq(z0 + dz + 0.5 - p.z));
                    if (dist >= best) continue;
                    BlockState s = state(x0 + dx, y0 + dy, z0 + dz);
                    if (s == null) {
                        unknown = true;
                        continue;
                    }
                    if (!s.isAir() && !s.getCollisionShape(level, pos.set(x0 + dx, y0 + dy, z0 + dz)).isEmpty()) best = dist;
                }
            }
        }
        if (best != Double.POSITIVE_INFINITY) return best;
        return unknown ? -1 : r + 1;
    }

    private static double sq(double v) {
        return v * v;
    }

    private static int floor(double v) {
        return (int) Math.floor(v);
    }
}
