package ua.zentix.incoming.nuclear.world;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import ua.zentix.incoming.Incoming;
import ua.zentix.incoming.nuclear.Detonation;

/**
 * Where a lit fuel's flame stands ({@code RuinPlanner.expose}): on the fuel — above it, else on its face toward the ball — and
 * never through a cell the ray only touches at a tie. Built high above the platform in one chunk, so only these blocks matter.
 */
@GameTestHolder(Incoming.MOD_ID)
@PrefixGameTestTemplate(false)
public final class ExposeGameTests {
    private static final long NO_FIRE = Long.MIN_VALUE;

    private ExposeGameTests() {}

    @GameTest(template = "pad", batch = "nuke_flash_kindling")
    public static void flameStandsOnTheFuel(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        LevelChunk chunk = level.getChunkAt(h.absolutePos(new BlockPos(1, 1, 1)));
        int x = chunk.getPos().getMinBlockX() + 8, z = chunk.getPos().getMinBlockZ() + 8, y = level.getMaxBuildHeight() - 40;
        BlockPos wool = new BlockPos(x, y, z), east = wool.east(), column = new BlockPos(x + 1, y, z + 1);
        try {
            set(level, wool.below(), Blocks.STONE);
            set(level, wool, Blocks.WHITE_WOOL);
            // open wool: the flame on top
            long top = expose(level, chunk, wool, 400, 240, 50);
            h.assertTrue(top == wool.above().asLong(), "open wool: fire at " + at(top) + ", want above it");
            // a torch on top: the flame on its side toward the ball
            set(level, wool.above(), Blocks.TORCH);
            long side = expose(level, chunk, wool, 400, 240, 50);
            h.assertTrue(side == east.asLong(), "wool under a torch, ball to the east: fire at " + at(side) + ", want " + east);
            // ball exactly diagonal: the ray only touches the east cell's corner and crosses the stone column behind it
            for (int dy = 0; dy < 5; dy++) set(level, column.above(dy), Blocks.STONE);
            long behind = expose(level, chunk, wool, 400, 240, 400);
            h.assertTrue(behind == NO_FIRE, "wool behind a stone column on the diagonal: fire at " + at(behind) + ", want none");
            // without the column: the first cell crossed is diagonal, not a face of the fuel, so no side flame
            for (int dy = 0; dy < 5; dy++) set(level, column.above(dy), Blocks.AIR);
            long diagonal = expose(level, chunk, wool, 400, 240, 400);
            h.assertTrue(diagonal == NO_FIRE, "wool under a torch, ball on the diagonal: fire at " + at(diagonal) + ", want none");
        } finally {
            for (int dy = 0; dy < 5; dy++) set(level, column.above(dy), Blocks.AIR);
            set(level, wool.above(), Blocks.AIR);
            set(level, wool, Blocks.AIR);
            set(level, wool.below(), Blocks.AIR);
        }
        h.succeed();
    }

    /** Fire position for the fuel at {@code fuel} with the burst {@code dx, dy, dz} blocks from its centre. */
    private static long expose(ServerLevel level, LevelChunk chunk, BlockPos fuel, double dx, double dy, double dz) {
        Vec3 burst = new Vec3(fuel.getX() + 0.5 + dx, fuel.getY() + 0.5 + dy, fuel.getZ() + 0.5 + dz);
        Detonation d = new Detonation(0, burst, fuel.getY() - 1, 15, false, level.getGameTime(), 0, 0, 20000, 7, 1f, false, Detonation.OLD_WAVE);
        RuinContext ctx = new RuinContext(level, d);
        ChunkShot shot = ChunkShot.take(chunk, null, null, false);
        RuinContext.Grid g = ctx.scorchGrid(level, shot, Blast.SERVER);
        // the ball's middle sits at the burst height here (its radius reaches below the fuel): aim at the burst itself
        Vec3 ball = new Vec3(burst.x, burst.y, burst.z);
        return RuinPlanner.expose(g, shot, Blast.SERVER, fuel.getX(), fuel.getY(), fuel.getZ(), ball, new double[1]);
    }

    private static void set(ServerLevel level, BlockPos p, Block b) {
        level.setBlock(p, b.defaultBlockState(), Block.UPDATE_CLIENTS);
    }

    private static String at(long p) {
        return p == NO_FIRE ? "none" : BlockPos.of(p).toShortString();
    }
}
