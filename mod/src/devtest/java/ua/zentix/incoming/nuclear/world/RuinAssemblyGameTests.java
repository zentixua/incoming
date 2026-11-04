package ua.zentix.incoming.nuclear.world;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import ua.zentix.incoming.Incoming;
import ua.zentix.incoming.gametest.StrikeGameTests;

/** An unadmitted demand cannot multiply protected input ownership through another dimension. */
@GameTestHolder(Incoming.MOD_ID)
@PrefixGameTestTemplate(false)
public final class RuinAssemblyGameTests {
    private RuinAssemblyGameTests() {}

    @GameTest(template = "range", batch = "nuke_assembly", timeoutTicks = 240, skyAccess = true)
    public static void deferredOtherDimensionStartsNoReadsUntilAssemblyTransfers(GameTestHelper h) {
        StrikeGameTests.gameSpeed(h);
        var level = h.getLevel();
        var reference = RuinStorageGameTests.fixture(h, false);
        var first = new RuinContext(level, reference.ctx().d);
        var otherLevel = level.getServer().getLevel(Level.NETHER);
        var second = new RuinContext(otherLevel, reference.ctx().d);
        var pos = new ChunkPos(h.absolutePos(new BlockPos(32, 1, 32)));
        var cold = new ChunkPos(pos.x + 1024, pos.z + 1024);
        StrikeGameTests.afterTest(h, () -> { first.cancelTasks(); second.cancelTasks(); });
        first.requestWindow(level, pos, RuinWorkers.Priority.SEEN);
        second.requestWindow(otherLevel, cold, RuinWorkers.Priority.NEAR);
        h.assertTrue(second.pendingWindowReads() == 0, "Another dimension admitted a deferred source window");
        first.forget(pos.toLong());
        second.requestWindow(otherLevel, cold, RuinWorkers.Priority.NEAR);
        h.assertTrue(second.pendingWindowReads() > 0, "Retiring the first assembly did not admit real cold reads");
        second.retireWindowReads();
        h.succeedWhen(() -> {
            h.assertTrue(second.pendingWindowReads() == 0, "Retired callbacks still own their source reservations");
        });
    }
}
