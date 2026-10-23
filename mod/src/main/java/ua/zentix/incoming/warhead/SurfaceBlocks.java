package ua.zentix.incoming.warhead;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import ua.zentix.incoming.util.Terrain;

/** Geometry shared by the air wave and excavation; no road/building classification. */
public final class SurfaceBlocks {
    private SurfaceBlocks() {}

    /** A solid backing cannot be replaced by the free rear face assumed by the panel damage curve. */
    static boolean backed(ServerLevel level, BlockPos p, Vec3 from) {
        // Normal of the face facing the source, not the grazing ray's direction along that face.
        double x = from.x - (p.getX() + 0.5), y = from.y - (p.getY() + 0.5), z = from.z - (p.getZ() + 0.5);
        Direction toward = Direction.getNearest(x, y, z);
        BlockPos rear = p.relative(toward.getOpposite());
        if (!Terrain.ready(level, rear)) return true;
        return solid(level, rear, level.getBlockState(rear));
    }

    public static boolean solid(ServerLevel level, BlockPos p, BlockState s) {
        return s.getFluidState().isEmpty() && s.isFaceSturdy(level, p, Direction.UP)
                && s.isFaceSturdy(level, p, Direction.DOWN);
    }

    /** First air above a continuous solid backing rooted in natural ground; a gap stops the search. */
    public static int supportedTop(ServerLevel level, int x, int z, int ground, int top) {
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos(x, ground, z);
        int y = ground;
        while (y < top && solid(level, p.setY(y), level.getBlockState(p))) y++;
        return y;
    }

    /** An impacted slab has its own surface even if the natural ground is a room or a river below it. */
    public static int excavationTop(ServerLevel level, int x, int z, int ground, int top, double sourceY) {
        int backed = supportedTop(level, x, z, ground, top);
        BlockPos p = BlockPos.containing(x, sourceY - 1e-5, z);
        if (p.getY() >= backed && p.getY() < top && solid(level, p, level.getBlockState(p))) return p.getY() + 1;
        return backed;
    }

    /** Loose earth is sampled as grains; cohesive rock/concrete must retain a sub-block remainder. */
    public static boolean cohesive(BlockState s) {
        return SlabBreach.material(s) != BreachModel.Material.EARTH;
    }
}
