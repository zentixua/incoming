package ua.zentix.incoming.gametest;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import ua.zentix.incoming.Incoming;
import ua.zentix.incoming.util.OpenBox;

import java.util.List;
import java.util.Random;

/**
 * {@link OpenBox}, the shortcut particles and debris pieces take in open air, never says “clear” where vanilla's block sweep
 * ({@code Entity.collideBoundingBox} with no entity, as {@code Particle.move}) would change the step: random particle and
 * piece boxes and steps around a fence (1.5 blocks tall), a slab, a carpet, a pane, leaves, a wall top, grass (no
 * collision) and stone. It must also say “clear” in open air, or it saves nothing.
 */
@GameTestHolder(Incoming.MOD_ID)
@PrefixGameTestTemplate(false)
public final class OpenBoxGameTests {
    @GameTest(template = "range", timeoutTicks = 20, batch = "open_box", skyAccess = true)
    public static void openBoxAgreesWithTheSweep(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        BlockState[] blocks = {Blocks.OAK_FENCE.defaultBlockState(), Blocks.STONE_SLAB.defaultBlockState(), Blocks.WHITE_CARPET.defaultBlockState(),
                Blocks.GLASS_PANE.defaultBlockState(), Blocks.OAK_LEAVES.defaultBlockState(), Blocks.COBBLESTONE_WALL.defaultBlockState(),
                Blocks.SHORT_GRASS.defaultBlockState(), Blocks.STONE.defaultBlockState()};
        BlockPos base = h.absolutePos(new BlockPos(2, 4, 2));
        for (int i = 0; i < blocks.length; i++) level.setBlock(base.offset(2 * i, 0, 0), blocks[i], 2);
        Random r = new Random(5);
        int clear = 0, open = 0;
        for (int k = 0; k < 40_000; k++) {
            double half = r.nextBoolean() ? 0.1 : 0.05 + 0.5 * r.nextDouble(), height = r.nextBoolean() ? 0.2 : 2 * half;
            Vec3 c = Vec3.atLowerCornerOf(base).add(-4 + 24 * r.nextDouble(), -1 + 7 * r.nextDouble(), -5 + 10 * r.nextDouble());
            AABB box = new AABB(c.x - half, c.y, c.z - half, c.x + half, c.y + height, c.z + half);
            Vec3 step = new Vec3(r.nextGaussian() * 0.4, r.nextGaussian() * 0.4, r.nextGaussian() * 0.4);
            boolean said = OpenBox.clear(level, box.minX, box.minY, box.minZ, box.maxX, box.maxY, box.maxZ, step.x, step.y, step.z);
            Vec3 swept = Entity.collideBoundingBox(null, step, box, level, List.of());
            if (said) {
                clear++;
                h.assertTrue(swept.equals(step), "clear, but the sweep changes " + step + " to " + swept + " for " + box);
            }
            if (swept.equals(step)) open++;
        }
        // boxes whose reach misses the row (blocks every 2 m along x: a box needs one block of air around its whole step)
        h.assertTrue(clear > open / 10, "shortcut answered " + clear + " of " + open + " unchanged steps");
        Vec3 sky = Vec3.atLowerCornerOf(base).add(0, 40, 0);
        h.assertTrue(OpenBox.clear(level, sky.x, sky.y, sky.z, sky.x + 0.2, sky.y + 0.2, sky.z + 0.2, 0.3, -0.5, 0.1), "open sky not clear");
        h.succeed();
    }
}
