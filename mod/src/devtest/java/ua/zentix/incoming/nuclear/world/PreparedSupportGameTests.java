package ua.zentix.incoming.nuclear.world;

import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import ua.zentix.incoming.Incoming;

/** Detached-snapshot experiments for finite prepared structural decisions. */
@GameTestHolder(Incoming.MOD_ID)
@PrefixGameTestTemplate(false)
public final class PreparedSupportGameTests {
    @GameTest(template = "pad", timeoutTicks = 100, batch = "nuke_prepared_support_baseline", skyAccess = true)
    public static void finiteHaloCannotProveTheFarEndOfADetachedRoof(GameTestHelper h) {
        var context = StructuralRuinGameTests.fixture(h.getLevel(), (x, y, z) -> {
            if (z == 8 && y == 70 && x >= 0 && x < 56) return Blocks.STONE.defaultBlockState();
            if (z == 8 && x == 0 && y < 70) return Blocks.GLASS.defaultBlockState();
            return Blocks.AIR.defaultBlockState();
        }, 2);
        var near = Collapse.solve(context.cachedGridForTest(h.getLevel(), new ChunkPos(0, 0)));
        var far = Collapse.solve(context.cachedGridForTest(h.getLevel(), new ChunkPos(2, 0)));
        h.assertTrue(near.isGone(16, 69, 24), "Fixture did not fracture the only supporting column");
        h.assertTrue(near.isGone(16, 70, 24), "Local collapse did not release the cap");
        h.assertFalse(far.isGone(24, 70, 24), "Baseline no longer demonstrates the finite-window gap");
        Incoming.LOG.info("PREPARED_SUPPORT_BASELINE anchorBroken=true nearReleased=true farX40Retained=true halo=16");
        h.succeed();
    }

    private PreparedSupportGameTests() {}
}
