package ua.zentix.incoming.nuclear.world;

import dev.vox.lss.common.LSSConstants;
import dev.vox.lss.common.PositionUtil;
import dev.vox.lss.networking.server.LSSServerNetworking;
import dev.vox.lss.networking.server.SectionSerializer;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestGenerator;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.TestFunction;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.storage.ChunkSerializer;
import net.minecraft.world.phys.Vec3;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import ua.zentix.incoming.Incoming;
import ua.zentix.incoming.nuclear.Detonation;
import ua.zentix.incoming.nuclear.NuclearEvents;

import java.util.Arrays;
import java.util.Collection;
import java.util.List;

/** Real optional LSS save-hook + SQLite integration, never included in the mod jar. */
@GameTestHolder(Incoming.MOD_ID)
@PrefixGameTestTemplate(false)
public final class LssRuinGameTests {
    private LssRuinGameTests() {}

    @GameTestGenerator
    public static Collection<TestFunction> tests() {
        // Do not register tests (or resolve LSS implementation classes) without the optional runtime.
        if (!ModList.get().isLoaded("lss")) return List.of();
        var tests = new java.util.ArrayList<TestFunction>(java.util.stream.IntStream.range(0, 4).mapToObj(kind ->
                new TestFunction("lss_ruin_" + kind, "lssruin." + List.of("section", "ruins", "flash", "biomes").get(kind),
                        "incoming:range", Rotation.NONE, 4000, 0, true, false, 1, 1, true, h -> Checks.check(h, kind))).toList());
        tests.add(new TestFunction("lss_settle", "lssruin.savedzone", "incoming:range", Rotation.NONE,
                3000, 0, true, false, 1, 1, true, h -> RuinSettleGameTests.wholeSavedAreaSettles(h, true)));
        return tests;
    }

    /** Keep lambda capture signatures containing optional LSS types off the scanned holder class. */
    private static final class Checks {
        private Checks() {}

        private static void check(GameTestHelper h, int kind) {
            ua.zentix.incoming.gametest.StrikeGameTests.gameSpeed(h);
            var level = h.getLevel();
            var service = LSSServerNetworking.getRequestService();
            h.assertTrue(service != null && service.getLodStore() != null, "LSS store not active; use -PwithLss");
            var store = service.getLodStore();
            ChunkPos pad = new ChunkPos(h.absolutePos(new BlockPos(32, 1, 32)));
            ChunkPos pos = new ChunkPos(pad.x + 200, pad.z + 200);
            LevelChunk chunk = RuinSettleGameTests.fixture(level, pos, 64);
            String dimension = level.dimension().location().toString();
            long packed = PositionUtil.packPosition(pos.x, pos.z);
            // Detached fixture, no world generation/light/autosave can race the store baseline.
            byte[] intact = SectionSerializer.serializeColumn(level, chunk, pos.x, pos.z).serializedSections();
            service.getDirtyContentFilter().seed(dimension, pos.x, pos.z, intact);
            h.assertTrue(store.deposit(dimension, packed, intact, LSSConstants.epochSeconds(), 0), "deposit refused");
            int[] stage = {0};
            byte[][] ruined = {null};
            java.util.concurrent.atomic.AtomicReference<java.util.concurrent.CompletableFuture<Void>> deleted =
                    new java.util.concurrent.atomic.AtomicReference<>();
            h.onEachTick(() -> {
                if (stage[0] == 0) {
                    var hit = store.get(dimension, packed);
                    if (hit == null) return;
                    h.assertTrue(Arrays.equals(intact, hit.sectionBytes()), "baseline differs from intact fixture");
                    chunk.setUnsaved(false);
                    mutate(h, chunk, kind);
                    h.assertTrue(chunk.isUnsaved(), "mutation did not mark chunk for vanilla saving");
                    ruined[0] = SectionSerializer.serializeColumn(level, chunk, pos.x, pos.z).serializedSections();
                    h.assertTrue(!Arrays.equals(intact, ruined[0]), "fixture changed no LSS-visible blocks/biomes");
                    // The actual vanilla choke point; deliberately do not call LSS's hook body ourselves.
                    ChunkSerializer.write(level, chunk);
                    h.assertTrue(store.get(dimension, packed) == null, "save left a stale LSS store row");
                    h.assertTrue(service.getDirtyTracker().isPending(dimension, packed), "save did not enter normal dirty broadcast");
                    Incoming.LOG.info("LSSRUIN kind={} old row invalidated; awaiting dirty fan-out", kind);
                    stage[0] = 1;
                } else if (stage[0] == 1) {
                    // Wait for LSS's own timestamp/store fan-out before simulating a delivery-path rewarm.
                    if (service.getDirtyTracker().isPending(dimension, packed)) return;
                    // A no-op policy receipt is a control-queue barrier: the store's unconditional
                    // queued delete must commit before this test deposits replacement bytes.
                    if (deleted.get() == null) {
                        var sqlite = (dev.vox.lss.common.store.SqliteLodStore) store;
                        var policy = sqlite.adoptedPolicy();
                        deleted.set(sqlite.updatePolicy(policy.maxDbBytes(), policy.resweepSeconds(), policy.revision()));
                        return;
                    }
                    if (!deleted.get().isDone()) return;
                    deleted.get().join();
                    h.assertTrue(store.deposit(dimension, packed, ruined[0], LSSConstants.epochSeconds(), 0), "rewarm refused");
                    Incoming.LOG.info("LSSRUIN kind={} dirty fan-out drained; awaiting fresh row", kind);
                    stage[0] = 2;
                } else {
                    var hit = store.get(dimension, packed);
                    if (hit == null) return;
                    h.assertTrue(Arrays.equals(ruined[0], hit.sectionBytes()), "later store reader received intact bytes");
                    Incoming.LOG.info("LSSRUIN kind={} stale row invalidated, dirty fan-out drained, fresh store bytes verified", kind);
                    h.succeed();
                }
            });
        }

        private static void mutate(GameTestHelper h, LevelChunk chunk, int kind) {
            var level = h.getLevel();
            ChunkPos pos = chunk.getPos();
            if (kind == 0) {
                chunk.getSection(chunk.getSectionIndex(100)).setBlockState(8, 100 & 15, 8,
                        Blocks.DIAMOND_BLOCK.defaultBlockState(), false);
                chunk.setUnsaved(true);
                return;
            }
            Detonation d = new Detonation(NuclearEvents.get(level).nextId(),
                    new Vec3(pos.getMiddleBlockX() - 48, 65, pos.getMiddleBlockZ()),
                    65, 7, true, level.getGameTime() - 10000, 0, 0, 20000, 42, 0.03f, false, 1, 0, false);
            RuinContext ctx = new RuinContext(level, d);
            if (kind == 3) {
                byte[] tiers = new byte[16];
                Arrays.fill(tiers, (byte) 3);
                int[] from = new int[16];
                Arrays.fill(from, 56);
                h.assertTrue(RuinPlan.nothing(ctx, new ScorchedBiomes.Cells(tiers, from))
                        .apply(level, chunk, d, new ColumnScar.Budget(false)), "biome-only plan stale");
            } else {
                ctx.putDisk(level, DiskShots.parse(level, pos, ChunkSerializer.write(level, chunk)));
                RuinContext.Grid grid = ctx.cachedGridForTest(level, pos);
                RuinPlan plan = kind == 1 ? RuinPlanner.finish(grid, Collapse.solve(grid)) : RuinPlanner.flash(grid);
                h.assertTrue(plan.changedBlocks() > 0, "plan changes no blocks");
                if (kind == 1) h.assertTrue(plan.apply(level, chunk, d, new ColumnScar.Budget(false)), "ruin plan stale");
                else h.assertTrue(plan.applyFlash(level, chunk, d, new ColumnScar.Budget(false)) >= 0, "flash plan stale");
            }
        }
    }
}
