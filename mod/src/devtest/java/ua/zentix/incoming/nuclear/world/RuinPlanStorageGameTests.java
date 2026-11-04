package ua.zentix.incoming.nuclear.world;

import it.unimi.dsi.fastutil.longs.LongArrayList;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BarrelBlockEntity;
import net.minecraft.world.level.chunk.storage.RegionStorageInfo;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import ua.zentix.incoming.Incoming;
import ua.zentix.incoming.gametest.StrikeGameTests;
import ua.zentix.incoming.nuclear.Detonation;
import ua.zentix.incoming.nuclear.model.HobBlast;
import ua.zentix.incoming.nuclear.model.Yield;

import java.lang.reflect.Modifier;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

/** Durable field inventory plus real solver -> spill -> replacement -> material SavedData ownership. */
@GameTestHolder(Incoming.MOD_ID)
@PrefixGameTestTemplate(false)
public final class RuinPlanStorageGameTests {
    private RuinPlanStorageGameTests() {}

    // Every instance field is classified. Adding authoritative state requires extending the payload
    // assertions rather than silently making store(store(plan)) an incomplete equality oracle.
    private static final Set<String> PAYLOAD = Set.of("cells", "states", "olds", "fires", "heights", "lightAt",
            "fluidTicks", "outside", "outsideState", "biomes", "flash", "blown", "material");
    private static final Set<String> REBUILT = Set.of("oldHashes", "prints", "normals", "ctx", "fireKeys",
            "sectionBits", "oldTops", "memoryWaiting");

    private static Object field(RuinPlan plan, String name) {
        try {
            var field = RuinPlan.class.getDeclaredField(name);
            field.setAccessible(true);
            return field.get(plan);
        } catch (ReflectiveOperationException error) { throw new RuntimeException(error); }
    }

    private static void samePayload(GameTestHelper h, RuinPlan expected, RuinPlan actual, String route) {
        for (String name : PAYLOAD) {
            Object a = field(expected, name), b = field(actual, name);
            boolean same = a instanceof ScorchedBiomes.Cells cells ? cells.same((ScorchedBiomes.Cells) b)
                    : Objects.deepEquals(a, b);
            h.assertTrue(same, route + " lost authoritative field " + name);
        }
    }

    @GameTest(template = "pad", batch = "nuke_plan_fields", timeoutTicks = 20, skyAccess = true)
    public static void everyAuthoritativeFieldSurvivesAndOldPagesAreRejected(GameTestHelper h) {
        for (var field : RuinPlan.class.getDeclaredFields()) if (!Modifier.isStatic(field.getModifiers()))
            h.assertTrue(PAYLOAD.contains(field.getName()) || REBUILT.contains(field.getName()),
                    "Unclassified plan field: " + field.getName());
        var ctx = new RuinContext(h.getLevel(), StructuralRuinGameTests.event(2));
        int cell = 0x321 | RuinPlan.LIGHT | RuinPlan.SLOW;
        long[] hashes = {RuinPlan.mix(0, Blocks.BARREL.defaultBlockState())};
        byte[] tiers = new byte[16]; Arrays.fill(tiers, (byte) 1);
        int[] from = new int[16]; Arrays.fill(from, 3);
        var original = new RuinPlan(new int[]{cell}, hashes,
                new net.minecraft.world.level.block.state.BlockState[]{Blocks.AIR.defaultBlockState()},
                new net.minecraft.world.level.block.state.BlockState[]{Blocks.BARREL.defaultBlockState()},
                new int[]{0x654}, new int[]{1, 10, 9}, new long[]{101}, new long[]{11}, new long[]{12},
                new LongArrayList(new long[]{202}), List.of(Blocks.OAK_LOG.defaultBlockState()), ctx,
                new long[]{303}, new ScorchedBiomes.Cells(tiers, from));
        original.flash(new int[]{401, 402});
        original.blown(new int[]{0x654});
        original.material(new long[]{501, 502});
        // Populate derived caches too: their omission is deliberate and must remain harmless.
        original.oldTops(0); original.touches(0); original.hasFire(0x654);
        var detached = original.detached();
        samePayload(h, original, detached, "detach");
        h.assertTrue(field(detached, "ctx") == null, "Detached callback retained the live context");
        var restored = RuinPlan.restore(original.store(), ctx);
        samePayload(h, original, restored, "store/restore");
        h.assertTrue(field(restored, "ctx") == ctx, "Restored plan was not rebound to its owner");
        h.assertTrue(Arrays.equals(hashes, (long[]) field(restored, "oldHashes")), "Old-state hashes were not rebuilt");
        h.assertTrue(restored.oldTops(0)[0x21] == original.oldTops(0)[0x21]
                && restored.touches(0) && restored.hasFire(0x654), "Derived caches changed behavior");
        detached.material(new long[0]);
        h.assertTrue(original.differs(detached) != null, "Comparator ignored structural material ownership");
        h.assertTrue(original.bytes() - detached.bytes() == 16, "Material bytes disappeared from admission accounting");
        var legacy = original.store(); legacy.putInt("version", 1); rejects(h, legacy, ctx);
        var missing = original.store(); missing.remove("material"); rejects(h, missing, ctx);
        var wrongType = original.store(); wrongType.putIntArray("material", new int[]{1}); rejects(h, wrongType, ctx);
        h.assertTrue(((long[]) field(RuinPlan.restore(RuinPlan.EMPTY.store(), ctx), "material")).length == 0,
                "A genuine empty-material plan cannot be restored");
        h.succeed();
    }

    private static void rejects(GameTestHelper h, CompoundTag tag, RuinContext ctx) {
        boolean rejected = false;
        try { RuinPlan.restore(tag, ctx); } catch (IllegalArgumentException expected) { rejected = true; }
        h.assertTrue(rejected, "Incomplete cache page was accepted as zero structural material");
    }

    private record Fixture(ServerLevel level, ChunkPos centre, int ground, BlockPos barrel, Detonation d) {
        void reset() {
            var pos = new BlockPos.MutableBlockPos();
            for (int x = 0; x < 16; x++) for (int z = 0; z < 16; z++) for (int y = -2; y <= 12; y++)
                level.setBlock(pos.set(centre.getMinBlockX() + x, ground + y, centre.getMinBlockZ() + z),
                        (y <= 0 ? Blocks.BEDROCK : Blocks.AIR).defaultBlockState(), 18);
            for (int x = 4; x <= 11; x++) for (int y = 1; y <= 8; y++)
                level.setBlock(new BlockPos(centre.getMinBlockX() + x, ground + y, centre.getMinBlockZ() + 7),
                        Blocks.STONE_BRICKS.defaultBlockState(), 18);
            level.setBlock(barrel, Blocks.BARREL.defaultBlockState(), 18);
            var container = (BarrelBlockEntity) level.getBlockEntity(barrel);
            container.setItem(0, new ItemStack(Items.DIAMOND, 7));
            container.setItem(8, new ItemStack(Items.IRON_INGOT, 11));
            container.setChanged();
        }
    }

    private static Fixture fixture(GameTestHelper h) {
        var level = h.getLevel();
        ChunkPos centre = new ChunkPos(h.absolutePos(new BlockPos(32, 1, 32)));
        int ground = h.absolutePos(new BlockPos(0, 0, 0)).getY();
        for (int dz = -2; dz <= 2; dz++) for (int dx = -2; dx <= 2; dx++)
            level.getChunk(centre.x + dx, centre.z + dz);
        double height = Yield.optimalBurstHeight(15), distance = HobBlast.rangeFor(50, height, 15);
        var d = new Detonation(711, new Vec3(centre.getMiddleBlockX() - distance, ground + height,
                centre.getMiddleBlockZ()), ground, 15, false, level.getGameTime(), 0, 3, 20000, 42, 1, false, .25f, 0, false);
        var fixture = new Fixture(level, centre, ground,
                new BlockPos(centre.getMinBlockX() + 7, ground + 4, centre.getMinBlockZ() + 7), d);
        fixture.reset();
        return fixture;
    }

    private record Outcome(RuinMatter ledger, CompoundTag saved, Path file) {}

    private static final Block[] TYPES = {Blocks.STONE_BRICKS, Blocks.BARREL};

    private static void sameLedger(GameTestHelper h, RuinMatter a, RuinMatter b, CompoundTag expected, String route) {
        h.assertTrue(a.released() == b.released() && a.pending() == b.pending()
                && a.deposited() == b.deposited() && a.releasedVolume() == b.releasedVolume()
                && a.pendingSupport() == b.pendingSupport(), route + " changed aggregate/support ownership");
        for (var type : TYPES) h.assertTrue(Arrays.equals(a.balance(type), b.balance(type)), route + " changed mass/volume for " + type);
        var saved = b.save(new CompoundTag(), h.getLevel().registryAccess());
        h.assertTrue(expected.getAllKeys().equals(saved.getAllKeys()), route + " changed ledger fields");
        for (String key : expected.getAllKeys()) {
            // These maps have no ordering contract; rehashing on load may change their row order.
            String rowKey = switch (key) {
                case "materials" -> "block";
                case "support_originals", "cold_edges" -> "position";
                default -> null;
            };
            Object before = rowKey == null ? expected.get(key) : rows(expected, key, rowKey);
            Object after = rowKey == null ? saved.get(key) : rows(saved, key, rowKey);
            h.assertTrue(Objects.equals(before, after), route + " changed ledger field " + key);
        }
    }

    private static List<CompoundTag> rows(CompoundTag tag, String name, String key) {
        var list = tag.getList(name, Tag.TAG_COMPOUND);
        var sorted = new ArrayList<CompoundTag>();
        for (int i = 0; i < list.size(); i++) sorted.add(list.getCompound(i));
        sorted.sort(java.util.Comparator.comparing(row -> row.get(key).toString()));
        return sorted;
    }

    private static void cargo(GameTestHelper h, CompoundTag saved, BlockPos barrel, CompoundTag contents) {
        var moving = saved.getList("moving", Tag.TAG_COMPOUND);
        int owners = 0;
        for (int i = 0; i < moving.size(); i++) {
            var row = moving.getCompound(i);
            if (row.getLong("source") == barrel.asLong()) {
                owners++;
                h.assertTrue(contents.equals(row.getCompound("contents")), "Captured live barrel inventory changed");
            }
        }
        h.assertTrue(owners == 1, "Live inventory must have exactly one material owner, got " + owners);
    }

    @GameTest(template = "range", batch = "nuke_plan_material", timeoutTicks = 1200, skyAccess = true)
    public static void structuralInventorySurvivesDetachStoreSpillApplyAndRealLedgerSave(GameTestHelper h) throws Exception {
        StrikeGameTests.gameSpeed(h);
        var f = fixture(h); var level = f.level(); var lookup = level.registryAccess();
        var storage = level.getDataStorage();
        var previousMatter = storage.cache.get("incoming_ruin_matter");
        var previousCrater = storage.cache.get("incoming_crater_matter");
        storage.set("incoming_ruin_matter", new RuinMatter());
        storage.set("incoming_crater_matter", new CraterMatter());
        var outcomes = new ArrayList<Outcome>();
        StrikeGameTests.afterTest(h, () -> {
            RuinMatter current = (RuinMatter) storage.cache.get("incoming_ruin_matter");
            if (current != null) current.unload(level);
            outcomes.forEach(outcome -> outcome.ledger.unload(level));
            if (previousMatter == null) storage.cache.remove("incoming_ruin_matter");
            else storage.cache.put("incoming_ruin_matter", previousMatter);
            if (previousCrater == null) storage.cache.remove("incoming_crater_matter");
            else storage.cache.put("incoming_crater_matter", previousCrater);
        });
        var ctx = new RuinContext(level, f.d());
        var grid = ctx.grid(level, f.centre(), false);
        var original = RuinPlanner.finish(grid, Collapse.solve(grid));
        var container = (BarrelBlockEntity) level.getBlockEntity(f.barrel());
        var contents = container.saveWithoutMetadata(lookup);
        var candidates = (long[]) field(original, "material");
        h.assertTrue(candidates.length > 1 && Arrays.stream(candidates).anyMatch(p -> p == f.barrel().asLong()),
                "Real structural solver did not remove the populated barrel and masonry");
        var plans = new RetainedPlans(0);
        var charged = new AtomicLong();
        var root = java.nio.file.Files.createTempDirectory(storage.dataFolder.toPath(), "ruin-plan-material-");
        var pages = new RuinPages(new RegionStorageInfo("gametest", level.dimension(), "incoming_preparation"), root.resolve("plans-v2"));
        plans.spill(pages, ctx, charged::addAndGet, () -> 64L << 20);
        long key = f.centre().toLong();
        plans.put(key, original);
        StrikeGameTests.afterTest(h, plans::clear);
        boolean[] evicted = {false}, applied = {false};
        var closed = new java.util.concurrent.atomic.AtomicReference<java.util.concurrent.CompletableFuture<Void>>();
        h.onEachTick(() -> {
            if (!applied[0]) {
                plans.pump(64L << 20);
                if (plans.pendingWrites() != 0) return;
                if (!evicted[0]) {
                    h.assertTrue(plans.containsKey(key) && plans.values().iterator().next() == null,
                            "Plan never crossed the real resident eviction boundary");
                    evicted[0] = true;
                }
                var spilled = plans.get(key);
                if (spilled == null) return;
                h.assertTrue(spilled != original && charged.get() == 0, "Read did not transfer/release spill ownership");
                samePayload(h, original, spilled, "real spill/read");
                RuinPlan[] routes = {original, original.detached(), RuinPlan.restore(original.store(), new RuinContext(level, f.d())), spilled};
                for (int i = 0; i < routes.length; i++) {
                    f.reset();
                    RuinMatter ledger = new RuinMatter(); storage.set("incoming_ruin_matter", ledger);
                    var chunk = level.getChunkSource().getChunkNow(f.centre().x, f.centre().z);
                    h.assertTrue(routes[i].current(chunk, f.d()), "Route " + i + " is stale before replacement");
                    h.assertTrue(routes[i].apply(level, chunk, f.d(), new ColumnScar.Budget(false)), "Route " + i + " refused replacement");
                    h.assertTrue(!level.getBlockState(f.barrel()).is(Blocks.BARREL) && level.getBlockEntity(f.barrel()) == null,
                            "Route " + i + " never removed the live inventory");
                    var saved = ledger.save(new CompoundTag(), lookup);
                    h.assertTrue(ledger.released() > 1 && ledger.pendingSupport() > 0 && ledger.balance(Blocks.STONE_BRICKS)[1] > 0
                            && ledger.balance(Blocks.BARREL)[1] > 0, "Route " + i + " discarded structural material/support");
                    cargo(h, saved, f.barrel(), contents);
                    Incoming.LOG.info("Ruin plan storage route {}: released={}, volume={}, mass={}, support={}, masonry mass={}, barrel mass={}",
                            i, ledger.released(), ledger.releasedVolume(), saved.getDouble("released_mass"), ledger.pendingSupport(),
                            ledger.balance(Blocks.STONE_BRICKS)[1], ledger.balance(Blocks.BARREL)[1]);
                    if (i > 0) sameLedger(h, outcomes.getFirst().ledger, ledger, outcomes.getFirst().saved, "Route " + i);
                    var restored = RuinMatter.load(saved, lookup);
                    sameLedger(h, ledger, restored, saved, "In-memory ledger reload " + i);
                    restored.unload(level);
                    Path file = root.resolve("material-" + i + ".dat");
                    outcomes.add(new Outcome(ledger, saved, file));
                    ledger.save(file.toFile(), lookup); // The real SavedData file wrapper/streaming writer.
                }
                // No pending transport may mutate a compared ledger while its file save finishes.
                storage.set("incoming_ruin_matter", new RuinMatter());
                plans.remove(key);
                h.assertTrue(plans.bytes() == 0, "Consumed spilled plan retained historical memory");
                closed.set(plans.closeStorage());
                applied[0] = true;
            }
            if (!closed.get().isDone() || outcomes.stream().anyMatch(outcome -> outcome.ledger.writing())) return;
            h.assertTrue(charged.get() == 0 && plans.pendingReads() == 0, "Plan storage retained I/O reservations");
            closed.get().join();
            for (int i = 0; i < outcomes.size(); i++) {
                var outcome = outcomes.get(i);
                try {
                    var saved = NbtIo.readCompressed(outcome.file, NbtAccounter.create(16L << 20)).getCompound("data");
                    var restored = RuinMatter.load(saved, lookup);
                    sameLedger(h, outcome.ledger, restored, outcome.saved, "Real file ledger reload " + i);
                    cargo(h, restored.save(new CompoundTag(), lookup), f.barrel(), contents);
                    restored.unload(level);
                } catch (java.io.IOException error) { throw new RuntimeException(error); }
            }
            h.succeed();
        });
    }
}
