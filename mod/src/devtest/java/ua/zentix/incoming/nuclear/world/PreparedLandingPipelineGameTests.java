package ua.zentix.incoming.nuclear.world;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ImposterProtoChunk;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.storage.ChunkSerializer;
import net.minecraft.world.level.chunk.storage.RegionStorageInfo;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import ua.zentix.incoming.Incoming;
import ua.zentix.incoming.gametest.StrikeGameTests;
import ua.zentix.incoming.nuclear.Detonation;
import ua.zentix.incoming.nuclear.NuclearEvents;
import ua.zentix.incoming.nuclear.world.landing.PreparedLandingModel;
import ua.zentix.incoming.registry.ModAttachments;

import java.lang.management.ManagementFactory;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.IntStream;

/** A bounded native composition test; no strike scheduler or whole-server restart/performance claim. */
@GameTestHolder(Incoming.MOD_ID)
@PrefixGameTestTemplate(false)
public final class PreparedLandingPipelineGameTests {
    @GameTest(template = "range", batch = "nuke_prepared_landing_pipeline", timeoutTicks = 600, skyAccess = true)
    public static void computedLandingsSurvivePublicationPartialSourcesAndColdCargo(GameTestHelper h) {
        StrikeGameTests.gameSpeed(h);
        var executor = Executors.newSingleThreadExecutor(r -> new Thread(r, "prepared-pipeline-control"));
        StrikeGameTests.afterTest(h, executor::shutdownNow);
        BlockPos anchor = h.absolutePos(new BlockPos(20, 64, 20));
        var cases = List.of(new Chain(h, new ChunkPos(anchor), anchor.getY() & ~15, false),
                new Chain(h, new ChunkPos((anchor.getX() >> 4) + 1, anchor.getZ() >> 4), anchor.getY() & ~15, true));
        for (var chain : cases) chain.prepare(executor);
        h.succeedWhen(() -> {
            for (var chain : cases) h.assertTrue(chain.advance(), "Waiting for bounded landing pipeline " + chain.reverse);
        });
    }

    private record Computed(PreparedLandingSnapshot.Input input, PreparedLandingModel.Result result,
                            long wallNanos, long cpuNanos, long allocatedBytes) {}

    /** Two source stacks share one source section and land on two different destination section pages. */
    private static final class Chain {
        final GameTestHelper h;
        final ServerLevel level;
        final LevelChunk chunk;
        final boolean reverse;
        final UUID preparation = UUID.randomUUID();
        final List<BlockPos> sources;
        final BlockPos barrel;
        final Detonation draft;
        final Path root;
        final RegionStorageInfo info;
        final ColumnScar.Budget budget = new ColumnScar.Budget(false);
        final int sectionY;
        ChunkShot[] shots;
        RuinWindow window;
        CompletableFuture<Computed> worker;
        Computed computed;
        List<PreparedMaterialBatch> batches;
        PreparedSourcePlan plan;
        PreparedMaterialStore store;
        CompletableFuture<Void> io;
        CompletableFuture<CompoundTag> saved;
        NuclearEvents.PreparedBinding binding;
        PreparedSourceProgress.Identity identity;
        Detonation event;
        Map<Integer, BitSet> footprint;
        long due;
        int stage, page;

        Chain(GameTestHelper h, ChunkPos at, int floorY, boolean reverse) {
            this.h = h; level = h.getLevel(); this.reverse = reverse;
            chunk = level.getChunk(at.x, at.z);
            preserve(h, chunk);
            chunk.setData(ModAttachments.CHUNK_SCAR, 0);
            chunk.removeData(ModAttachments.PREPARED_SOURCE_PROGRESS);
            // Actual native blocks: flat lower floor, upper right floor, and an intact separating wall.
            for (int y = 0; y < 48; y++) for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
                boolean support = y == 0 || y == 16 && x >= 8 || x == 7 && y < 35;
                level.setBlock(new BlockPos(at.getMinBlockX() + x, floorY + y, at.getMinBlockZ() + z),
                        support ? Blocks.OBSIDIAN.defaultBlockState() : Blocks.AIR.defaultBlockState(), 2);
            }
            barrel = new BlockPos(at.getMinBlockX() + 4, floorY + 36, at.getMinBlockZ() + 8);
            BlockPos stone = new BlockPos(at.getMinBlockX() + 12, floorY + 36, at.getMinBlockZ() + 8);
            sources = List.of(barrel.below(), barrel, stone.below(), stone);
            level.setBlock(barrel.below(), Blocks.WHITE_CONCRETE.defaultBlockState(), 2);
            level.setBlock(barrel, Blocks.BARREL.defaultBlockState(), 2);
            level.setBlock(stone.below(), Blocks.OAK_PLANKS.defaultBlockState(), 2);
            level.setBlock(stone, Blocks.STONE.defaultBlockState(), 2);
            ((Container) level.getBlockEntity(barrel)).setItem(0, new ItemStack(Items.DIAMOND, 3));
            sectionY = barrel.getY() >> 4;
            var local = StructuralRuinGameTests.event(2);
            draft = copy(local, 0, 0, local.burst().add(at.getMinBlockX(), floorY - 65, at.getMinBlockZ()), floorY);
            root = level.getServer().getWorldPath(LevelResource.ROOT).resolve("data/prepared-pipeline-" + preparation);
            info = new RegionStorageInfo("gametest", level.dimension(), "incoming_prepared_pipeline");
        }

        void prepare(ExecutorService executor) {
            shots = new ChunkShot[9];
            var palette = new PreparedLandingPalette.Builder(level);
            for (int dz = -1; dz <= 1; dz++) for (int dx = -1; dx <= 1; dx++) {
                var shot = ChunkShot.take(level.getChunk(chunk.getPos().x + dx, chunk.getPos().z + dz), null, null, false);
                shots[(dz + 1) * 3 + dx + 1] = shot;
                for (var section : shot.saved().states()) if (section != null)
                    section.getAll(state -> { Blast.ensure(state); palette.add(state); });
            }
            window = new RuinWindow(chunk.getPos(), chunk.getMinBuildHeight(), chunk.getMaxBuildHeight(), shots, Blast.publish());
            var facts = palette.freeze();
            // Declared structural losses isolate the integration chain. Destinations and times are solved.
            var removals = new ArrayList<PreparedLandingSnapshot.Removal>();
            for (BlockPos at : sources) removals.add(new PreparedLandingSnapshot.Removal(at,
                    draft.arrivalTicks(Vec3.atCenterOf(at).distanceTo(draft.burst())) / 20, true));
            worker = CompletableFuture.supplyAsync(() -> {
                var bean = ManagementFactory.getThreadMXBean();
                var allocation = bean instanceof com.sun.management.ThreadMXBean extended ? extended : null;
                long cpu = bean.getCurrentThreadCpuTime(), allocated = allocation == null ? -1 : allocation.getThreadAllocatedBytes(Thread.currentThread().threadId());
                long started = System.nanoTime();
                var input = PreparedLandingSnapshot.from(window, removals, draft, facts);
                var result = PreparedLandingModel.solve(input.collision(), input.sources(), PreparedLandingModel.Limits.experiment());
                long elapsed = System.nanoTime() - started;
                cpu = bean.getCurrentThreadCpuTime() - cpu;
                allocated = allocation == null ? -1 : allocation.getThreadAllocatedBytes(Thread.currentThread().threadId()) - allocated;
                var opposite = new ArrayList<>(input.sources()); Collections.reverse(opposite);
                if (!result.equals(PreparedLandingModel.solve(input.collision(), opposite, PreparedLandingModel.Limits.experiment())))
                    throw new IllegalStateException("Source iteration changed computed landings");
                return new Computed(input, result, elapsed, cpu, allocated);
            }, executor);
        }

        boolean advance() {
            switch (stage) {
                case 0 -> {
                    if (!worker.isDone()) return false;
                    computed = worker.join(); publish(); store = open(); stage = 1;
                }
                case 1 -> {
                    if (io != null) { if (!io.isDone()) return false; io.join(); io = null; page++; }
                    if (page < batches.size()) {
                        var batch = batches.get(page); var key = PreparedMaterialStore.Key.of(batch);
                        if (store.request(key).state() != PreparedMaterialStore.State.ABSENT) return false;
                        h.assertTrue(store.admit(batch), "Computed page was not admitted"); io = store.save(key); return false;
                    }
                    var tag = new CompoundTag(); tag.put("source_plan", plan.store());
                    var originals = new ListTag(); for (var shot : shots) originals.add(shot.saved().encode());
                    tag.put("originals", originals);
                    saved = roundtrip("publication", tag); io = store.close(); stage = 2;
                }
                case 2 -> {
                    if (!io.isDone() || !saved.isDone()) return false;
                    io.join(); var publication = saved.join();
                    h.assertTrue(publication.getList("originals", 10).size() == 9, "Original collision inputs were not durable");
                    plan = restore(publication.getCompound("source_plan"));
                    var events = new NuclearEvents();
                    event = copy(draft, events.nextId(), level.getGameTime() + 4_000_000, draft.burst(), draft.groundY());
                    events.add(event); binding = events.bindPrepared(event, preparation);
                    identity = new PreparedSourceProgress.Identity(event.id(), preparation, plan.generation());
                    footprint = plan.delta().sourceFootprint(plan.minSection()); due = plan.dueTick(sectionY);
                    h.assertTrue(attempt(null, due - 1, true).state() == PreparedSourceCommit.State.PENDING
                                    && PreparedSourceProgress.get(chunk).isEmpty(), "Source began before its computed due offset");
                    h.assertTrue(attempt(null, due, false).state() == PreparedSourceCommit.State.PENDING
                                    && PreparedSourceProgress.get(chunk).isEmpty(), "Source began without durable original inputs");
                    h.assertTrue(attempt(null, due, true).state() == PreparedSourceCommit.State.PENDING, "Unowned material source completed");
                    pending(4);
                    ((Container) level.getBlockEntity(barrel)).setItem(0, new ItemStack(Items.DIAMOND, 7));
                    store = open(); page = 0; stage = 3;
                }
                case 3 -> {
                    var key = PreparedMaterialStore.Key.of(batches.get(page));
                    if (store.request(key).state() != PreparedMaterialStore.State.READY) return false;
                    try (var lease = store.pin(key)) {
                        var result = attempt(lease, due, true);
                        h.assertTrue(result.state() == (page == 0 ? PreparedSourceCommit.State.PENDING : PreparedSourceCommit.State.COMPLETE),
                                "Source completion ignored another destination owner");
                        for (int i = 0; i < lease.batch().size(); i++) h.assertTrue(lease.batch().ownsMaterial(i), "Confirmed source lost its material owner");
                        if (page == 0) {
                            pending(2);
                            h.assertTrue(PreparedMaterialCommit.land(level, lease.batch(), indices(lease.batch()), Long.MAX_VALUE,
                                    chunk.getData(ModAttachments.CHUNK_SCAR) >= event.id()) == 0, "Pending source chunk admitted a landing");
                        }
                    }
                    if (page == 0) {
                        var tag = new CompoundTag(); tag.put("source_plan", plan.store()); tag.put("chunk", ChunkSerializer.write(level, chunk));
                        saved = roundtrip("partial-source", tag); io = store.close(); stage = 4;
                    } else {
                        h.assertTrue(chunk.getData(ModAttachments.CHUNK_SCAR) == event.id() && PreparedSourceProgress.get(chunk).isEmpty(),
                                "Completed source did not publish and retire its scar receipt");
                        io = store.close(); stage = 5;
                    }
                }
                case 4 -> {
                    if (!io.isDone() || !saved.isDone()) return false;
                    io.join(); var tag = saved.join(); plan = restore(tag.getCompound("source_plan"));
                    var copy = readChunk(tag.getCompound("chunk"));
                    var progress = PreparedSourceProgress.get(copy);
                    h.assertTrue(identity.equals(progress.identity()) && progress.pending(sectionY).cardinality() == 2
                                    && copy.getData(ModAttachments.CHUNK_SCAR) < event.id(), "Partial source reload lost its pending receipt");
                    for (BlockPos source : sources) h.assertTrue(copy.getBlockState(source) == chunk.getBlockState(source), "Saved source blocks diverged from their receipt");
                    // Restore the persisted attachment only after checking the same live test-owned blocks.
                    // This is a bounded native serialization/resume control, not a server restart.
                    chunk.removeData(ModAttachments.PREPARED_SOURCE_PROGRESS);
                    chunk.setData(ModAttachments.PREPARED_SOURCE_PROGRESS, progress);
                    store = open(); page = 1; stage = 3;
                }
                case 5 -> {
                    if (!io.isDone()) return false; io.join(); io = null; store = open(); page = 0; stage = 6;
                }
                case 6 -> {
                    if (io != null) { if (!io.isDone()) return false; io.join(); io = null; page++; }
                    if (page == batches.size()) { io = store.close(); stage = 7; return false; }
                    var key = PreparedMaterialStore.Key.of(batches.get(page));
                    if (store.request(key).state() != PreparedMaterialStore.State.READY) return false;
                    try (var lease = store.pin(key)) {
                        var batch = lease.batch(); int[] rows = indices(batch);
                        long first = Arrays.stream(rows).mapToLong(i -> batch.move(i).landingTick()).min().orElseThrow();
                        h.assertTrue(PreparedMaterialCommit.land(level, batch, rows, age(first - 1), true) == 0, "Material landed before its solved time");
                        h.assertTrue(PreparedMaterialCommit.land(level, batch, rows, Long.MAX_VALUE, false) == 0, "Landing bypassed destination scar guard");
                        for (long tick : Arrays.stream(rows).mapToLong(i -> batch.move(i).landingTick()).distinct().sorted().toArray()) {
                            long expected = Arrays.stream(rows).filter(i -> batch.phase(i) == PreparedMaterialBatch.Phase.REMOVED && batch.move(i).landingTick() <= tick).count();
                            h.assertTrue(PreparedMaterialCommit.land(level, batch, rows, age(tick), true) == expected, "Computed due landings were not applied exactly once");
                        }
                        h.assertTrue(batch.finished(), "Solved material remained pending after all due operations");
                    }
                    io = store.save(key);
                }
                case 7 -> {
                    if (!io.isDone()) return false; io.join(); store = open(); page = 0; stage = 8;
                }
                case 8 -> {
                    if (page == batches.size()) {
                        saved = roundtrip("completed-chunk", ChunkSerializer.write(level, chunk)); io = store.close(); stage = 9; return false;
                    }
                    var key = PreparedMaterialStore.Key.of(batches.get(page));
                    if (store.request(key).state() != PreparedMaterialStore.State.READY) return false;
                    try (var lease = store.pin(key)) {
                        var batch = lease.batch();
                        h.assertTrue(batch.finished() && PreparedMaterialCommit.land(level, batch, indices(batch), Long.MAX_VALUE, true) == 0,
                                "Cold terminal material replayed its placement");
                        for (int i = 0; i < batch.size(); i++) h.assertTrue(batch.cargo(i) == null, "Placed cargo retained a second escrow owner");
                    }
                    page++;
                }
                case 9 -> {
                    if (!io.isDone() || !saved.isDone()) return false; io.join();
                    var copy = readChunk(saved.join());
                    h.assertTrue(copy.getData(ModAttachments.CHUNK_SCAR) == event.id() && PreparedSourceProgress.get(copy).isEmpty(), "Completed chunk reload lost its scar receipt");
                    for (var outcome : computed.result().outcomes()) {
                        BlockPos target = new BlockPos(outcome.x(), outcome.y(), outcome.z());
                        h.assertTrue(copy.getBlockState(target) == computed.input().states().get(outcome.source().state()), "Solved destination changed after native chunk reload");
                        if (outcome.source().id() == barrel.asLong()) {
                            var inventory = (Container) copy.getBlockEntity(target);
                            h.assertTrue(inventory != null && inventory.getItem(0).is(Items.DIAMOND) && inventory.getItem(0).getCount() == 7,
                                    "Computed cargo destination lost the seven live departure diamonds");
                        }
                    }
                    Incoming.LOG.info("PREPARED_PIPELINE reverse_pages={} sources={} destination_pages={} worker_wall_ns={} worker_cpu_ns={} worker_allocated_bytes={} source_plan_nbt_bytes={} final_chunk_nbt_bytes={} scope=bounded-native-chain-not-whole-strike",
                            reverse, sources.size(), batches.size(), computed.wallNanos(), computed.cpuNanos(), computed.allocatedBytes(), plan.store().sizeInBytes(), saved.join().sizeInBytes());
                    stage = 10;
                }
                default -> { return true; }
            }
            return stage == 10;
        }

        private void publish() {
            h.assertTrue(computed.result().count(PreparedLandingModel.Status.LANDED) == 4, "Native fixture did not solve all four ordinary sources");
            var grouped = new LinkedHashMap<PreparedMaterialStore.Key, List<PreparedMaterialBatch.Move>>();
            for (var outcome : computed.result().outcomes()) {
                var source = BlockPos.of(outcome.source().id()); var target = new BlockPos(outcome.x(), outcome.y(), outcome.z());
                var before = shots[4].get(source.getX() & 15, source.getY(), source.getZ() & 15);
                var placed = computed.input().states().get(outcome.source().state());
                var destinationBefore = window.get(target.getX() - window.x0, target.getY(), target.getZ() - window.z0);
                var move = new PreparedMaterialBatch.Move(source, target, before, placed, destinationBefore,
                        (long) Math.ceil(outcome.source().departure() * 20), outcome.dueTick(), outcome.source().volume(), outcome.source().mass());
                var key = new PreparedMaterialStore.Key(preparation, net.minecraft.core.SectionPos.of(target).asLong());
                grouped.computeIfAbsent(key, ignored -> new ArrayList<>()).add(move);
                Incoming.LOG.info("PREPARED_PIPELINE_MOVE reverse_pages={} source={} destination={} departure={} landing={} volume={} mass={}", reverse,
                        source, target, move.departureTick(), move.landingTick(), move.volume(), move.mass());
            }
            batches = new ArrayList<>();
            for (var rows : grouped.values()) batches.add(new PreparedMaterialBatch(preparation, new ChunkPos(rows.getFirst().destination()), rows));
            batches.sort(Comparator.comparingInt(PreparedMaterialBatch::sectionY));
            h.assertTrue(batches.size() == 2 && batches.stream().allMatch(b -> b.size() == 2), "Fixture did not produce two separate destination owners");
            if (reverse) Collections.reverse(batches);
            long[] dueTicks = new long[chunk.getSections().length];
            var references = new ArrayList<PreparedSourcePlan.Reference>();
            for (var batch : batches) for (int i = 0; i < batch.size(); i++) {
                var move = batch.move(i); int section = chunk.getSectionIndex(move.source().getY());
                dueTicks[section] = Math.max(dueTicks[section], move.departureTick());
                references.add(new PreparedSourcePlan.Reference(section << 12 | PreparedSourceProgress.local(move.source()),
                        PreparedMaterialStore.Key.of(batch).section(), i));
            }
            plan = new PreparedSourcePlan(preparation, 1, chunk.getPos(), chunk.getMinSection(), delta(), dueTicks, references);
        }

        private RuinPlan delta() {
            var sorted = new ArrayList<>(sources); sorted.sort(Comparator.comparingInt(PreparedSourceProgress::local));
            int[] cells = new int[sorted.size()]; var olds = new BlockState[cells.length]; long[] hashes = new long[chunk.getSections().length];
            for (int i = 0; i < cells.length; i++) {
                var at = sorted.get(i); int section = chunk.getSectionIndex(at.getY());
                olds[i] = shots[4].get(at.getX() & 15, at.getY(), at.getZ() & 15);
                cells[i] = section << RuinPlan.SECTION_SHIFT | PreparedSourceProgress.local(at) | (Blast.props(olds[i]).is(Blast.Props.SLOW) ? RuinPlan.SLOW : 0);
                hashes[section] = RuinPlan.mix(hashes[section], olds[i]);
            }
            var delta = new RuinPlan(cells, hashes, new BlockState[]{Blocks.AIR.defaultBlockState()}, olds, new int[0], new int[0], new long[0],
                    null, null, null, null, null, new long[0], null);
            delta.material(sources.stream().mapToLong(BlockPos::asLong).toArray()); return delta;
        }

        private PreparedSourceCommit.Result attempt(PreparedMaterialStore.Lease lease, long tick, boolean originalsDurable) {
            int[] rows = lease == null ? new int[0] : plan.references(sectionY).stream()
                    .filter(ref -> ref.destinationSection() == PreparedMaterialStore.Key.of(lease.batch()).section())
                    .mapToInt(ref -> plan.verify(ref, lease.batch())).toArray();
            return PreparedSourceCommit.attempt(level, chunk, event, identity, plan.delta(), footprint, sectionY, due,
                    age(tick), budget, originalsDurable, lease, rows, () -> true);
        }

        private long age(long offset) { return binding.age(Math.addExact(binding.origin(), offset)); }

        private void pending(int expected) {
            h.assertTrue(chunk.getData(ModAttachments.CHUNK_SCAR) < event.id() && PreparedSourceProgress.get(chunk).pending(sectionY).cardinality() == expected,
                    "Source chunk was scarred while material sources remained pending");
        }

        private PreparedSourcePlan restore(CompoundTag tag) {
            return PreparedSourcePlan.restore(tag, null, PreparedSourcePlan.restorePublicationBytes(tag));
        }

        private PreparedMaterialStore open() {
            var value = new PreparedMaterialStore(info, root.resolve("material"), new PreparedMaterialStore.Limits(1, 1, 1, 1, 1L << 20));
            StrikeGameTests.afterTest(h, () -> value.close()); return value;
        }

        private CompletableFuture<CompoundTag> roundtrip(String name, CompoundTag tag) {
            var writer = new RuinPages(info, root.resolve(name)); var reader = new RuinPages(info, root.resolve(name));
            StrikeGameTests.afterTest(h, () -> writer.close()); StrikeGameTests.afterTest(h, () -> reader.close());
            return writer.write(chunk.getPos().toLong(), () -> tag).thenCompose(unused -> writer.flush()).thenCompose(unused -> writer.close())
                    .thenCompose(unused -> reader.read(chunk.getPos().toLong()))
                    .thenCompose(saved -> reader.close().thenApply(unused -> saved.orElseThrow()));
        }

        private LevelChunk readChunk(CompoundTag tag) {
            return ((ImposterProtoChunk) ChunkSerializer.read(level, level.getPoiManager(), info, chunk.getPos(), tag)).getWrapped();
        }
    }

    private static int[] indices(PreparedMaterialBatch batch) { return IntStream.range(0, batch.size()).toArray(); }

    private static Detonation copy(Detonation d, int id, long origin, Vec3 burst, double groundY) {
        return new Detonation(id, burst, groundY, d.yieldKt(), d.surface(), origin, d.windDir(), d.windSpeed(), d.visibility(),
                d.seed(), d.scale(), d.fallout(), d.wave(), d.dayTime(), d.fires());
    }

    private static void preserve(GameTestHelper h, LevelChunk chunk) {
        int scar = chunk.getData(ModAttachments.CHUNK_SCAR); var progress = PreparedSourceProgress.get(chunk);
        StrikeGameTests.afterTest(h, () -> {
            chunk.setData(ModAttachments.CHUNK_SCAR, scar);
            if (progress.isEmpty()) chunk.removeData(ModAttachments.PREPARED_SOURCE_PROGRESS);
            else chunk.setData(ModAttachments.PREPARED_SOURCE_PROGRESS, progress);
        });
    }

    private PreparedLandingPipelineGameTests() {}
}
