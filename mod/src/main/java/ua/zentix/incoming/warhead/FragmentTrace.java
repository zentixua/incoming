package ua.zentix.incoming.warhead;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import ua.zentix.incoming.registry.ModTags;

/**
 * Fragment block path ({@link FragmentModel}), shared server (glass/chips) and client (dust/sparks), stopping at
 * same block (except wave-broken walls: server traces after main explosion, client on explosion tick). Straight
 * grid traversal (Amanatides–Woo), ready chunks only: unready stops tracing (server read would load it).
 * <ul>
 * <li>glass ({@link ModTags#SHATTERS}) penetrated, continues (little loss, estimated lossless);</li>
 * <li>no collision shape (grass, flowers, torches), passes;</li>
 * <li>fluid, leaves, all solids stop it;</li>
 * <li>skip explosion cell: contact blast already removed block before fragments (still present on client).</li>
 * </ul>
 */
public final class FragmentTrace {
    private FragmentTrace() {}

    /** Fragment encounters. */
    public interface Visitor {
        /** Penetrated glass at {@code pos} after {@code s} m. */
        default void glass(BlockPos pos, BlockState state, double s) {}

        /** Stopped in {@code pos} after {@code s} m, entering face from {@code entry} (outward normal). */
        void stop(BlockPos pos, BlockState state, double s, Vec3 point, net.minecraft.core.Direction entry);
    }

    /**
     * Trace from {@code from} along unit {@code dir} up to {@code maxS} m.
     *
     * @return distance to stop, m; {@code maxS} if no solid hit; −1 if unready chunk (unknown beyond)
     */
    public static double trace(Level level, Vec3 from, Vec3 dir, double maxS, Visitor visitor) {
        int x = floor(from.x), y = floor(from.y), z = floor(from.z);
        int sx = (int) Math.signum(dir.x), sy = (int) Math.signum(dir.y), sz = (int) Math.signum(dir.z);
        double tdx = sx == 0 ? Double.MAX_VALUE : Math.abs(1 / dir.x);
        double tdy = sy == 0 ? Double.MAX_VALUE : Math.abs(1 / dir.y);
        double tdz = sz == 0 ? Double.MAX_VALUE : Math.abs(1 / dir.z);
        double tx = sx == 0 ? Double.MAX_VALUE : ((sx > 0 ? x + 1 - from.x : from.x - x) * tdx);
        double ty = sy == 0 ? Double.MAX_VALUE : ((sy > 0 ? y + 1 - from.y : from.y - y) * tdy);
        double tz = sz == 0 ? Double.MAX_VALUE : ((sz > 0 ? z + 1 - from.z : from.z - z) * tdz);
        int minY = level.getMinBuildHeight(), maxY = level.getMaxBuildHeight();
        int cx = Integer.MIN_VALUE, cz = Integer.MIN_VALUE;
        LevelChunk chunk = null;
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        net.minecraft.core.Direction entry = net.minecraft.core.Direction.UP;
        double s = 0;
        while (true) {
            // Step into next cell.
            if (tx <= ty && tx <= tz) {
                s = tx;
                x += sx;
                tx += tdx;
                entry = sx > 0 ? net.minecraft.core.Direction.WEST : net.minecraft.core.Direction.EAST;
            } else if (ty <= tz) {
                s = ty;
                y += sy;
                ty += tdy;
                entry = sy > 0 ? net.minecraft.core.Direction.DOWN : net.minecraft.core.Direction.UP;
            } else {
                s = tz;
                z += sz;
                tz += tdz;
                entry = sz > 0 ? net.minecraft.core.Direction.NORTH : net.minecraft.core.Direction.SOUTH;
            }
            if (s >= maxS || y < minY || y >= maxY) return maxS;
            if (x >> 4 != cx || z >> 4 != cz) {
                cx = x >> 4;
                cz = z >> 4;
                chunk = chunkNow(level, cx, cz);
            }
            if (chunk == null) return -1;
            m.set(x, y, z);
            BlockState st = chunk.getBlockState(m);
            if (st.isAir()) continue;
            if (st.is(ModTags.SHATTERS)) {
                visitor.glass(m.immutable(), st, s);
                continue;
            }
            if (st.getFluidState().isEmpty() && st.getCollisionShape(chunk, m).isEmpty()) continue;
            visitor.stop(m.immutable(), st, s, from.add(dir.scale(s)), entry);
            return s;
        }
    }

    /** Ready chunk or null (server {@code getChunkNow}, no load/wait). */
    static @Nullable LevelChunk chunkNow(Level level, int cx, int cz) {
        if (level instanceof ServerLevel server) return server.getChunkSource().getChunkNow(cx, cz);
        return level.getChunkSource().getChunk(cx, cz, false);
    }

    private static int floor(double v) {
        return (int) Math.floor(v);
    }
}
