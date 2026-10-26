package ua.zentix.incoming.gametest;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import ua.zentix.incoming.Incoming;
import ua.zentix.incoming.compat.DhUpdates;
import ua.zentix.incoming.registry.ModAttachments;

import java.util.concurrent.CompletableFuture;

@GameTestHolder(Incoming.MOD_ID)
@PrefixGameTestTemplate(false)
public final class DhAttachmentGameTests {
    private DhAttachmentGameTests() {}

    /** A DH callback from another thread acknowledges only its world's attachment. */
    @GameTest(template = "range", timeoutTicks = 100, batch = "dh_attachments", skyAccess = true)
    public static void acknowledgementsStayWithTheirWorld(GameTestHelper h) {
        var level = h.getLevel();
        var other = level.getServer().getLevel(Level.NETHER);
        h.assertTrue(other != null, "no Nether");
        var chunk = level.getChunkAt(h.absolutePos(new BlockPos(32, 12, 32)));
        DhUpdates.testSink(level, (l, c) -> {});
        DhUpdates.testSink(other, (l, c) -> {});
        StrikeGameTests.afterTest(h, () -> {
            DhUpdates.testSink(level, null);
            DhUpdates.testSink(other, null);
        });
        h.assertTrue(DhUpdates.sendHeld(level, chunk) && DhUpdates.sendHeld(other, chunk), "copies not sent");
        var saved = CompletableFuture.runAsync(() -> DhUpdates.saved(level, chunk.getPos().x, chunk.getPos().z));
        h.succeedWhen(() -> {
            h.assertTrue(saved.isDone(), "DH callback not delivered");
            long[] first = DhUpdates.takeHeldStats(level);
            h.assertTrue(first[5] == 0, "first world still waiting");
            h.assertTrue(DhUpdates.takeHeldStats(other)[5] == 1, "acknowledgement reached another world");
            // The listener is discarded with the attachment; old callbacks must not create a replacement.
            level.removeData(ModAttachments.DH_UPDATES);
            DhUpdates.saved(level, chunk.getPos().x, chunk.getPos().z);
            h.assertFalse(level.hasData(ModAttachments.DH_UPDATES), "late callback recreated world state");
        });
    }
}
