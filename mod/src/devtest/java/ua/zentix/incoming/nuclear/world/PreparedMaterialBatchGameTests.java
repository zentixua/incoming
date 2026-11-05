package ua.zentix.incoming.nuclear.world;

import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.chunk.storage.RegionStorageInfo;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import ua.zentix.incoming.Incoming;
import ua.zentix.incoming.gametest.StrikeGameTests;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Persistence/ownership controls; these do not claim a complete strike or crash-atomic world commit. */
@GameTestHolder(Incoming.MOD_ID)
@PrefixGameTestTemplate(false)
public final class PreparedMaterialBatchGameTests {
    @GameTest(template = "pad", batch = "nuke_prepared_batch_io", timeoutTicks = 200, skyAccess = true)
    public static void delayedOldSaveDoesNotFreezeOrOverwriteNewRevision(GameTestHelper h) {
        StrikeGameTests.gameSpeed(h);
        var source = h.absolutePos(new BlockPos(8, 24, 8));
        var target = source.offset(16, -8, 0);
        var owner = new ChunkPos(target);
        var batch = new PreparedMaterialBatch(UUID.randomUUID(), owner, List.of(new PreparedMaterialBatch.Move(source, target,
                Blocks.BARREL.defaultBlockState(), Blocks.BARREL.defaultBlockState(), 100, 140, 1, 600)));
        var cargo = new CompoundTag();
        cargo.putString("proof", "exact private contents");
        cargo.putInt("count", 7);
        batch.capture(0, cargo);
        cargo.putInt("count", 99); // An external mutable tag must not change escrow.
        batch.removed(0);
        var old = batch.snapshot();
        var latch = new CountDownLatch(1);
        var level = h.getLevel();
        var info = new RegionStorageInfo("gametest", level.dimension(), "incoming_prepared_material");
        var root = level.getServer().getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT)
                .resolve("data/prepared-material-test-" + UUID.randomUUID());
        var pages = new RuinPages(info, root);
        StrikeGameTests.afterTest(h, () -> { latch.countDown(); pages.close(); });
        var first = pages.write(owner.toLong(), () -> {
            try {
                if (!latch.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("Fixture did not release encoder");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
            return old.encode();
        });
        h.assertTrue(!first.isDone(), "Old encoder unexpectedly passed its closed latch");
        batch.placed(0);
        h.assertTrue(batch.finished(), "Saving a snapshot froze the live material revision");
        var detached = PreparedMaterialBatch.decode(old.encode());
        h.assertTrue(detached.ownsMaterial(0) && detached.cargo(0).getInt("count") == 7,
                "Later placement or external cargo edit mutated the saved ownership snapshot");
        var newer = batch.snapshot();
        var second = pages.write(owner.toLong(), newer::encode);
        latch.countDown();
        var reopened = second.thenCompose(unused -> pages.close()).thenApply(unused -> new RuinPages(info, root));
        var read = reopened.thenCompose(store -> store.read(owner.toLong())
                .thenCompose(tag -> store.close().thenApply(unused -> tag)));
        h.succeedWhen(() -> {
            h.assertTrue(read.isDone(), "Waiting for ordered region write and reopen");
            var restored = PreparedMaterialBatch.decode(read.join().orElseThrow());
            h.assertTrue(restored.finished() && !restored.ownsMaterial(0) && restored.cargo(0) == null,
                    "An older async write resurrected material after the final revision");
            h.assertTrue(restored.revision() == batch.revision(), "Reopen lost the final revision");
        });
    }

    @GameTest(template = "pad", batch = "nuke_prepared_batch_repair", skyAccess = true)
    public static void localRepairsRetainOnlyRemovedCargoAndExactState(GameTestHelper h) {
        var source = h.absolutePos(new BlockPos(8, 24, 8));
        var target = source.offset(16, -8, 0);
        var before = Blocks.OAK_STAIRS.defaultBlockState().setValue(BlockStateProperties.WATERLOGGED, true);
        var placed = before.setValue(BlockStateProperties.WATERLOGGED, false);
        var moves = List.of(new PreparedMaterialBatch.Move(source, target, before, placed, 100, 140, .75, 450),
                new PreparedMaterialBatch.Move(source.above(), target.above(), Blocks.BARREL.defaultBlockState(),
                        Blocks.BARREL.defaultBlockState(), 101, 141, 1, 600));
        var batch = new PreparedMaterialBatch(UUID.randomUUID(), new ChunkPos(target), moves);
        var cargo = new CompoundTag();
        cargo.putString("inventory", "preserved");
        batch.capture(0, cargo);
        batch.sourceChanged(0);
        batch.capture(1, cargo);
        batch.removed(1);
        batch.destinationChanged(1);
        var encoded = batch.snapshot().encode();
        var restored = PreparedMaterialBatch.decode(encoded);
        h.assertTrue(!restored.ownsMaterial(0) && restored.cargo(0) == null,
                "A source still in the world became a second material owner");
        h.assertTrue(restored.ownsMaterial(1) && "preserved".equals(restored.cargo(1).getString("inventory")),
                "Destination repair lost removed material or contents");
        h.assertTrue(restored.move(0).equals(moves.getFirst()) && restored.move(1).equals(moves.getLast()),
                "Local repair changed exact state, amount, position or causal ticks");
        restored.cargo(1).putString("inventory", "external edit");
        h.assertTrue("preserved".equals(restored.cargo(1).getString("inventory")), "Cargo accessor leaked a mutable save owner");
        var corrupt = encoded.copy();
        corrupt.putLong("plan", 3); // Numeric coercion must not accept an incompatible header.
        boolean rejected = false;
        try { PreparedMaterialBatch.decode(corrupt); }
        catch (IllegalArgumentException expected) { rejected = true; }
        h.assertTrue(rejected, "Invalid page header was accepted with implicit NBT coercion");
        h.succeed();
    }

    @GameTest(template = "pad", batch = "nuke_prepared_batch_identity", skyAccess = true)
    public static void preparationIdentityAndOffsetsRejectLegacyOrAliasedInputs(GameTestHelper h) {
        var plan = UUID.randomUUID();
        var source = new BlockPos(-25, 72, 40);
        var target = source.east(64);
        var move = new PreparedMaterialBatch.Move(source, target, Blocks.STONE.defaultBlockState(),
                Blocks.STONE.defaultBlockState(), 100, 140, 1, 2500);
        var batch = new PreparedMaterialBatch(plan, new ChunkPos(target), List.of(move));
        var encoded = batch.snapshot().encode();
        var restored = PreparedMaterialBatch.decode(encoded);
        h.assertTrue(restored.plan().equals(plan) && restored.move(0).equals(move)
                        && !encoded.contains("event") && encoded.getInt("version") == 5,
                "Page rewrote preparation identity or relative times");
        var corrupt = encoded.copy();
        corrupt.putIntArray("plan", new int[]{1, 2, 3});
        rejected(h, () -> PreparedMaterialBatch.decode(corrupt), "Truncated UUID was accepted");
        var wrongVersion = encoded.copy(); wrongVersion.putInt("version", 4);
        rejected(h, () -> PreparedMaterialBatch.decode(wrongVersion), "Absolute-time schema version was accepted as v5");
        var old = encoded.copy();
        old.putInt("version", 4); old.remove("plan"); old.putInt("event", 7);
        rejected(h, () -> PreparedMaterialBatch.decode(old), "Unshipped absolute-time v4 page was accepted as v5");
        var ambiguous = encoded.copy(); ambiguous.putInt("event", 7);
        rejected(h, () -> PreparedMaterialBatch.decode(ambiguous), "Two material identity domains were accepted");
        rejected(h, () -> new PreparedMaterialBatch(null, new ChunkPos(target), List.of(move)), "Missing plan was accepted");
        rejected(h, () -> new PreparedMaterialStore.Key(null, SectionPos.asLong(target)), "Missing key plan was accepted");
        rejected(h, () -> new PreparedMaterialStore.Key(plan, SectionPos.asLong(0, 128, 0)),
                "Section outside packed block identities was accepted");
        rejected(h, () -> new PreparedMaterialBatch.Move(source.offset(1 << 26, 0, 0), target,
                move.before(), move.placed(), 100, 140, 1, 2500), "Truncated source identity was accepted");
        h.succeed();
    }

    private static void rejected(GameTestHelper h, Runnable action, String message) {
        boolean failed = false;
        try { action.run(); } catch (IllegalArgumentException expected) { failed = true; }
        h.assertTrue(failed, message);
    }

    private PreparedMaterialBatchGameTests() {}
}
