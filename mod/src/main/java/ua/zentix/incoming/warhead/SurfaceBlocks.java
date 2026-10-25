package ua.zentix.incoming.warhead;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import ua.zentix.incoming.util.Terrain;

/** Geometry shared by the air wave and excavation; no road/building classification. */
public final class SurfaceBlocks {
    private SurfaceBlocks() {}

    /** A solid backing cannot be replaced by the free rear face assumed by the panel damage curve. */
    static boolean backed(ServerLevel level, BlockPos p, Direction loadedFace) {
        BlockPos rear = p.relative(loadedFace.getOpposite());
        if (!Terrain.ready(level, rear)) return true;
        return solid(level, rear, level.getBlockState(rear));
    }

    public static boolean solid(ServerLevel level, BlockPos p, BlockState s) {
        return solid((net.minecraft.world.level.BlockGetter) level, p, s);
    }

    /** То же у любого мира (клиент ищет плиту над пустотой тем же правилом: картинка взрыва по цели, {@code FarBlasts}). */
    public static boolean solid(net.minecraft.world.level.BlockGetter level, BlockPos p, BlockState s) {
        return s.getFluidState().isEmpty() && s.isFaceSturdy(level, p, Direction.UP)
                && s.isFaceSturdy(level, p, Direction.DOWN);
    }

    /** Группа материала блока для пробоя зарядом (FM 5-250 табл. B-3) — та же, что у пробоя сервера ({@code SlabBreach}). */
    public static BreachModel.Material breaching(BlockState s) {
        return SlabBreach.material(s);
    }

    /** First air above a continuous solid backing rooted in natural ground; a gap stops the search. */
    public static int supportedTop(ServerLevel level, int x, int z, int ground, int top) {
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos(x, ground, z);
        int y = Math.min(ground, top);
        while (y < top && solid(level, p.setY(y), level.getBlockState(p))) y++;
        return y;
    }

    /** An impacted slab has its own surface even if the natural ground is a room or a river below it. */
    public static int excavationTop(ServerLevel level, int x, int z, int ground, int top, double sourceY) {
        // The contact plane caps the supported layer. A wall/tree above it is not a raised road surface.
        int plane = (int) Math.floor(sourceY + 1e-5);
        int backed = supportedTop(level, x, z, ground, Math.min(top, plane));
        BlockPos p = BlockPos.containing(x, sourceY - 1e-5, z);
        if (p.getY() >= backed && p.getY() < top && solid(level, p, level.getBlockState(p))) return p.getY() + 1;
        return backed;
    }

    /** Loose earth is sampled as grains; cohesive rock/concrete must retain a sub-block remainder. */
    public static boolean cohesive(BlockState s) {
        return SlabBreach.material(s) != BreachModel.Material.EARTH;
    }
}
