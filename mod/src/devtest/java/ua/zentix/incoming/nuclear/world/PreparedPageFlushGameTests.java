package ua.zentix.incoming.nuclear.world;

import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.storage.RegionStorageInfo;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import ua.zentix.incoming.Incoming;
import ua.zentix.incoming.gametest.StrikeGameTests;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Real region-file fences; no claim of a cross-file crash-atomic transaction. */
@GameTestHolder(Incoming.MOD_ID)
@PrefixGameTestTemplate(false)
public final class PreparedPageFlushGameTests {
    @GameTest(template = "pad", batch = "nuke_prepared_page_flush", timeoutTicks = 200, skyAccess = true)
    public static void flushWaitsForDelayedEncodingAndSurvivesReopen(GameTestHelper h) {
        StrikeGameTests.gameSpeed(h);
        var level = h.getLevel();
        var info = new RegionStorageInfo("gametest", level.dimension(), "incoming_prepared_flush");
        var path = level.getServer().getWorldPath(LevelResource.ROOT).resolve("data/prepared-flush-" + UUID.randomUUID());
        var pages = new RuinPages(info, path);
        var gate = new CountDownLatch(1);
        StrikeGameTests.afterTest(h, () -> { gate.countDown(); pages.close(); });
        long key = ChunkPos.asLong(7, -3);
        var written = pages.write(key, () -> {
            try {
                if (!gate.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("Fixture did not release page encoder");
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(error);
            }
            return value(17);
        });
        var flushed = pages.flush();
        h.assertTrue(!written.isDone() && !flushed.isDone(), "Flush passed an admitted but unencoded page");
        gate.countDown();
        var read = flushed.thenCompose(unused -> pages.close()).thenCompose(unused -> {
            var reopened = new RuinPages(info, path);
            return reopened.read(key).thenCompose(tag -> reopened.close().thenApply(done -> tag));
        });
        h.succeedWhen(() -> {
            h.assertTrue(read.isDone(), "Waiting for page flush and reopen");
            h.assertTrue(read.join().orElseThrow().getInt("value") == 17, "Flushed page did not survive reopen");
        });
    }

    @GameTest(template = "pad", batch = "nuke_prepared_page_failure", timeoutTicks = 200, skyAccess = true)
    public static void readAndLaterSuccessCannotHideAnEarlierFailedWrite(GameTestHelper h) {
        StrikeGameTests.gameSpeed(h);
        var level = h.getLevel();
        var info = new RegionStorageInfo("gametest", level.dimension(), "incoming_prepared_failure");
        var path = level.getServer().getWorldPath(LevelResource.ROOT).resolve("data/prepared-failure-" + UUID.randomUUID());
        var pages = new RuinPages(info, path);
        StrikeGameTests.afterTest(h, pages::close);
        var marker = new IllegalStateException("Intentional page encoder failure");
        long key = ChunkPos.asLong(4, 9), goodKey = ChunkPos.asLong(5, 9);
        var failed = pages.write(key, () -> { throw marker; });
        var interveningRead = pages.read(key);
        var good = pages.write(goodKey, () -> value(23));
        var flushed = pages.flush();
        var closed = flushed.handle((unused, failure) -> null).thenCompose(unused -> pages.close());
        var closedAgain = closed.handle((unused, failure) -> null).thenCompose(unused -> pages.close());
        var reopened = closedAgain.handle((unused, failure) -> new RuinPages(info, path)).thenCompose(store ->
                store.read(goodKey).thenCompose(tag -> store.close().thenApply(unused -> tag)));
        h.succeedWhen(() -> {
            h.assertTrue(reopened.isDone(), "Waiting for failed flush, close and recovery handle");
            h.assertTrue(interveningRead.join().isEmpty() && good.isDone() && !good.isCompletedExceptionally(),
                    "Failure changed ordinary read/write recovery ordering");
            h.assertTrue(causedBy(failed, marker) && causedBy(flushed, marker) && causedBy(closed, marker)
                    && causedBy(closedAgain, marker), "A later operation hid the admitted write failure");
            h.assertTrue(reopened.join().orElseThrow().getInt("value") == 23, "Failed close did not drain the healthy later page");
        });
    }

    private static boolean causedBy(CompletableFuture<Void> future, Throwable marker) {
        try { future.join(); return false; }
        catch (RuntimeException error) {
            for (Throwable cause = error; cause != null; cause = cause.getCause()) if (cause == marker) return true;
            return false;
        }
    }

    private static CompoundTag value(int value) {
        var tag = new CompoundTag();
        tag.putInt("value", value);
        return tag;
    }

    private PreparedPageFlushGameTests() {}
}
