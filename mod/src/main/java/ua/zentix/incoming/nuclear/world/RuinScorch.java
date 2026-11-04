package ua.zentix.incoming.nuclear.world;

import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.chunk.LevelChunk;
import ua.zentix.incoming.nuclear.flash.FlashChar;
import ua.zentix.incoming.nuclear.flash.FlashSync;

/** One source-chunk index per removal batch; face identity travels with the material. */
final class RuinScorch {
    private final Int2ObjectOpenHashMap<IntArrayList> faces = new Int2ObjectOpenHashMap<>();

    RuinScorch(LevelChunk chunk) {
        for (var layer : FlashSync.of(chunk).layers()) for (int mark : layer.chars())
            faces.computeIfAbsent(mark & FlashChar.PLACE_MASK, ignored -> new IntArrayList()).add(mark);
    }

    int capture(BlockPos at, BlockState source, BlockState placed, net.minecraft.world.phys.Vec3 away) {
        IntArrayList marks = faces.get(FlashChar.key(at.getX() & 15, at.getY(), at.getZ() & 15, Direction.DOWN) & FlashChar.PLACE_MASK);
        if (marks == null) return 0;
        int packed = 0;
        for (int mark : marks) {
            if (!FlashChar.holds(source, mark)) continue;
            Direction face = FlashChar.face(mark);
            if (source.hasProperty(BlockStateProperties.AXIS) && placed.hasProperty(BlockStateProperties.AXIS))
                face = rotate(face, source.getValue(BlockStateProperties.AXIS), placed.getValue(BlockStateProperties.AXIS),
                        placed.getValue(BlockStateProperties.AXIS) == Direction.Axis.X ? (away.x < 0 ? -1 : 1) : (away.z < 0 ? -1 : 1));
            int shift = face.get3DDataValue() * 3;
            int tier = Math.max(packed >>> shift & 7, FlashChar.level(mark));
            packed = packed & ~(7 << shift) | tier << shift;
        }
        return packed;
    }

    /** Rigid quarter turns through vertical, matching the log axis change (no new illumination). */
    static Direction rotate(Direction face, Direction.Axis from, Direction.Axis to, int sign) {
        if (from == to) return face;
        int x = face.getStepX(), y = face.getStepY(), z = face.getStepZ();
        if (from == Direction.Axis.X) { int oldX = x; x = -y; y = oldX; }
        else if (from == Direction.Axis.Z) { int oldZ = z; z = -y; y = oldZ; }
        if (to == Direction.Axis.X) { int oldY = y; y = -sign * x; x = sign * oldY; }
        else if (to == Direction.Axis.Z) { int oldY = y; y = -sign * z; z = sign * oldY; }
        return Direction.fromDelta(x, y, z);
    }
}
