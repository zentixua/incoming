package ua.zentix.incoming.nuclear.world;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import ua.zentix.incoming.Incoming;
import ua.zentix.incoming.gametest.StrikeGameTests;
import ua.zentix.incoming.nuclear.Detonation;
import ua.zentix.incoming.nuclear.model.StructuralResponse;
import ua.zentix.incoming.nuclear.world.landing.PreparedLandingModel;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.function.Supplier;

/** Bounded snapshot experiments only. Timing is reported, never a CI assertion. No world payload is committed. */
@GameTestHolder(Incoming.MOD_ID)
@PrefixGameTestTemplate(false)
public final class PreparedLandingGameTests {
    private static final PreparedLandingModel.Limits LIMITS = PreparedLandingModel.Limits.experiment();

    @GameTest(template = "range", batch = "nuke_prepared_landing_physics", timeoutTicks = 200, skyAccess = true)
    public static void detachedFallMatchesCurrentNativeMaterial(GameTestHelper h) { compareCurrent(h, false); }

    @GameTest(template = "range", batch = "nuke_prepared_landing_wall", timeoutTicks = 200, skyAccess = true)
    public static void detachedWallImpactMatchesCurrentNativeMaterial(GameTestHelper h) { compareCurrent(h, true); }

    private static void compareCurrent(GameTestHelper h, boolean wall) {
        h.assertTrue(PreparedLandingModel.GRAVITY == StructuralResponse.GRAVITY, "Prototype gravity drifted from production");
        var level = h.getLevel();
        BlockPos base = h.absolutePos(new BlockPos(8, 4, 8));
        BlockPos source = base.offset(16, 16, 16);
        byte[] cells = new byte[48 * 48 * 32];
        for (int x = 0; x < 48; x++) for (int z = 0; z < 48; z++) {
            level.setBlock(base.offset(x, 0, z), Blocks.OBSIDIAN.defaultBlockState(), 2);
            cells[z * 48 + x] = PreparedLandingModel.SOLID;
        }
        if (wall) for (int y = 1; y < 25; y++) for (int z = 0; z < 48; z++) {
            level.setBlock(base.offset(22, y, z), Blocks.OBSIDIAN.defaultBlockState(), 2);
            cells[(y * 48 + z) * 48 + 22] = PreparedLandingModel.SOLID;
        }
        var state = Blocks.WHITE_CONCRETE.defaultBlockState();
        level.setBlock(source, state, 2);
        cells[(16 * 48 + 16) * 48 + 16] = PreparedLandingModel.SOLID;
        var props = Blast.props(state);
        double wind = wall ? 12 : 0, drag = wall ? 80 : 0;
        var input = new PreparedLandingModel.Source(source.asLong(), 1, source.getX(), source.getY(), source.getZ(),
                0, props.volume(), props.volume() * props.density(), 0, wind, 0, drag, 3, PreparedLandingModel.Kind.ORDINARY);
        var snapshot = new PreparedLandingModel.Snapshot(base.getX(), base.getY(), base.getZ(), 48, 32, 48, cells);
        var future = worker(h, () -> PreparedLandingModel.solve(snapshot, List.of(input), LIMITS));
        RuinMatter ledger = new RuinMatter();
        StrikeGameTests.afterTest(h, () -> ledger.unload(level));
        h.startSequence().thenWaitUntil(() -> h.assertTrue(future.isDone(), "Waiting for detached physics worker")).thenExecute(() -> {
            var result = future.join();
            h.assertTrue(result.count(PreparedLandingModel.Status.LANDED) == 1, "Detached native comparison did not land");
            var expected = result.outcomes().getFirst();
            // Native control gets enough elapsed game time to drain; no server clock changes.
            var piece = new RuinMatter.Piece(source, state, null, Vec3.ZERO, level.getGameTime() - 1000);
            piece.windX = wind; piece.drag = drag; piece.pulse = 3;
            level.setBlock(source, Blocks.AIR.defaultBlockState(), 2);
            ledger.release(List.of(piece));
            var clock = WorkClock.counting(1);
            for (int i = 0; i < 1000 && ledger.pending() > 0; i++) { clock.start(16); ledger.work(level, clock); }
            h.assertTrue(ledger.deposited() == 1 && ledger.pending() == 0, "Current native material did not finish");
            h.assertTrue(level.getBlockState(new BlockPos(expected.x(), expected.y(), expected.z())) == state,
                    "Detached endpoint differs from current native material");
            h.assertTrue(ledger.depositedVolume() == input.volume(), "Native control changed material volume");
            Incoming.LOG.info("LANDING_CONTROL wall={} destination={},{},{} time_s={} segments={}", wall,
                    expected.x(), expected.y(), expected.z(), expected.time(), result.segments());
        }).thenSucceed();
    }

    @GameTest(template = "pad", batch = "nuke_prepared_landing_snapshot", timeoutTicks = 200, skyAccess = true)
    public static void absentSnapshotIsUnknownAndCargoIsNotOrdinary(GameTestHelper h) {
        ChunkShot[] shots = syntheticShots(48, false);
        shots[0] = null;
        var window = new RuinWindow(new ChunkPos(0, 0), 0, 48, shots, Blast.publish());
        // x=4,z=-12 is in the known northern central chunk; westward motion enters missing NW.
        var at = new BlockPos(4, 32, -12);
        var removals = List.of(new PreparedLandingSnapshot.Removal(at, 0, true),
                new PreparedLandingSnapshot.Removal(new BlockPos(8, 32, -12), 0, true));
        var input = PreparedLandingSnapshot.from(window, removals, syntheticDetonation());
        h.assertTrue(input.sources().size() == 2 && input.sources().get(1).kind() == PreparedLandingModel.Kind.UNSUPPORTED_SOURCE,
                "A container entered the ordinary no-cargo fast path");
        var original = input.sources().getFirst();
        var driven = new PreparedLandingModel.Source(original.id(), original.event(), original.x(), original.y(), original.z(),
                original.state(), original.volume(), original.mass(), 0, -50, 0, 100, 5, PreparedLandingModel.Kind.ORDINARY);
        var future = worker(h, () -> PreparedLandingModel.solve(input.collision(), List.of(driven), LIMITS));
        h.succeedWhen(() -> {
            h.assertTrue(future.isDone(), "Waiting for missing-neighbor worker");
            var result = future.join();
            h.assertTrue(result.count(PreparedLandingModel.Status.UNKNOWN_GEOMETRY) == 1,
                    "Missing chunk became solid support or a successful landing");
        });
    }

    @GameTest(template = "pad", batch = "nuke_prepared_landing_measure", timeoutTicks = 600, skyAccess = true)
    public static void denseSyntheticSnapshotReportsFiniteWorkerCost(GameTestHelper h) {
        var shots = syntheticShots(96, true);
        var window = new RuinWindow(new ChunkPos(0, 0), 0, 96, shots, Blast.publish());
        var removals = new ArrayList<PreparedLandingSnapshot.Removal>();
        for (int y = 1; y < 96; y++) for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++)
            if (!window.get(x + 16, y, z + 16).isAir()) removals.add(new PreparedLandingSnapshot.Removal(new BlockPos(x, y, z), 0, true));
        var future = worker(h, () -> {
            long before = System.nanoTime();
            var input = PreparedLandingSnapshot.from(window, removals, syntheticDetonation());
            long adapter = System.nanoTime() - before;
            return measure("synthetic-dense-central", input, 0, adapter);
        });
        h.succeedWhen(() -> {
            h.assertTrue(future.isDone(), "Waiting for dense snapshot worker");
            verify(h, future.join());
        });
    }

    /** Explicit external fixture: raw NBT remains ignored and is never copied into this repository. */
    @GameTest(template = "pad", batch = "nuke_prepared_landing_city", timeoutTicks = 1200, skyAccess = true)
    public static void externalCityWindowsReportEligibilityAndWorkerCost(GameTestHelper h) {
        String directory = System.getProperty("incoming.preparedLanding.fixture", "");
        if (directory.isBlank()) {
            Incoming.LOG.info("LANDING_CITY skipped: no incoming.preparedLanding.fixture; synthetic checks are not city evidence");
            h.succeed(); return;
        }
        String eventsPath = System.getProperty("incoming.preparedLanding.eventInputs", "");
        h.assertTrue(!eventsPath.isBlank(), "City experiment requires explicit detonation inputs");
        try {
            Path fixture = Path.of(directory);
            JsonObject manifest = JsonParser.parseString(Files.readString(fixture.resolve("manifest.json"))).getAsJsonObject();
            JsonObject events = JsonParser.parseString(Files.readString(Path.of(eventsPath))).getAsJsonObject();
            var shots = new HashMap<Long, ChunkShot>();
            long began = System.nanoTime();
            var format = DiskShots.Format.of(h.getLevel());
            for (var element : manifest.getAsJsonArray("chunks")) {
                var row = element.getAsJsonObject();
                Path file = fixture.resolve(row.get("file").getAsString());
                byte[] compressed = Files.readAllBytes(file);
                String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(compressed));
                h.assertTrue(hash.equals(row.get("gzip_sha256").getAsString()), "City fixture hash changed: " + file.getFileName());
                ChunkPos pos = new ChunkPos(row.get("x").getAsInt(), row.get("z").getAsInt());
                var raw = NbtIo.readCompressed(file, NbtAccounter.create(16L << 20));
                var read = DiskShots.parse(format, pos, raw);
                h.assertTrue(read.skip() == null, "City chunk was not parsed: " + read.skip());
                // Register states before priming and publish the immutable table before any worker.
                for (var section : read.states()) if (section != null) section.getAll(Blast::ensure);
                shots.put(pos.toLong(), ChunkShot.fromDisk(read.primed(Blast.SERVER), null, null, false));
            }
            Blast.PropsView view = Blast.publish();
            Incoming.LOG.info("LANDING_CITY_INPUT chunks={} parse_publish_ns={} level_sha256={} scope=pristine-central-cohort-not-live-453-chunks",
                    shots.size(), System.nanoTime() - began, manifest.get("level_sha256").getAsString());
            var grids = new ArrayList<RuinContext.Grid>();
            var labels = new ArrayList<String>();
            for (String variant : List.of("512", "1536")) {
                Detonation d = detonation(events.getAsJsonArray(variant).get(0).getAsJsonObject());
                var context = new RuinContext(h.getLevel(), d);
                StrikeGameTests.afterTest(h, context::cancelTasks);
                for (var center : manifest.getAsJsonArray("centres")) {
                    var xy = center.getAsJsonArray();
                    var pos = new ChunkPos(xy.get(0).getAsInt(), xy.get(1).getAsInt());
                    ChunkShot[] grid = new ChunkShot[25];
                    for (int dz = -2; dz <= 2; dz++) for (int dx = -2; dx <= 2; dx++) {
                        grid[(dz + 2) * 5 + dx + 2] = shots.get(ChunkPos.asLong(pos.x + dx, pos.z + dz));
                        h.assertTrue(grid[(dz + 2) * 5 + dx + 2] != null, "Missing 5x5 city fracture source");
                    }
                    ChunkShot own = grid[12];
                    grids.add(new RuinContext.Grid(context, pos, own.minY, own.minY + own.sections * 16, grid, view, true, true, true));
                    labels.add("city-" + variant + "-" + pos.x + "-" + pos.z);
                }
            }
            var future = worker(h, () -> {
                var results = new ArrayList<Measurement>();
                for (int i = 0; i < grids.size(); i++) {
                    var grid = grids.get(i);
                    long start = System.nanoTime();
                    Collapse collapse = Collapse.solve(grid);
                    long fracture = System.nanoTime() - start;
                    var removals = new ArrayList<PreparedLandingSnapshot.Removal>();
                    for (long position : collapse.changes.keySet()) {
                        BlockPos source = BlockPos.of(position);
                        double departure = grid.ctx().d.arrivalTicks(Vec3.atCenterOf(source).distanceTo(grid.ctx().d.burst())) / 20;
                        removals.add(new PreparedLandingSnapshot.Removal(source, departure, true));
                    }
                    start = System.nanoTime();
                    var input = PreparedLandingSnapshot.from(grid.windowOf(0, 0), removals, grid.ctx().d);
                    long adapter = System.nanoTime() - start;
                    results.add(measure(labels.get(i), input, fracture, adapter));
                }
                return results;
            });
            h.succeedWhen(() -> {
                h.assertTrue(future.isDone(), "Waiting for city worker measurements");
                for (Measurement result : future.join()) verify(h, result);
            });
        } catch (IOException | NoSuchAlgorithmException error) {
            throw new IllegalStateException("Cannot read external city fixture", error);
        }
    }

    private record Measurement(PreparedLandingSnapshot.Input input, PreparedLandingModel.Result result) {}

    private static Measurement measure(String label, PreparedLandingSnapshot.Input input, long fracture, long adapter) {
        var bean = ManagementFactory.getThreadMXBean();
        long cpu = bean.isCurrentThreadCpuTimeSupported() ? bean.getCurrentThreadCpuTime() : -1;
        var allocation = bean instanceof com.sun.management.ThreadMXBean extended ? extended : null;
        long allocated = allocation != null && allocation.isThreadAllocatedMemorySupported()
                ? allocation.getThreadAllocatedBytes(Thread.currentThread().threadId()) : -1;
        long began = System.nanoTime();
        var result = PreparedLandingModel.solve(input.collision(), input.sources(), LIMITS);
        long elapsed = System.nanoTime() - began;
        cpu = cpu < 0 ? -1 : bean.getCurrentThreadCpuTime() - cpu;
        allocated = allocated < 0 ? -1 : allocation.getThreadAllocatedBytes(Thread.currentThread().threadId()) - allocated;
        var counts = new java.util.EnumMap<PreparedLandingModel.Status, Long>(PreparedLandingModel.Status.class);
        for (var status : PreparedLandingModel.Status.values()) counts.put(status, result.count(status));
        long eligible = input.sources().stream().filter(s -> s.kind() == PreparedLandingModel.Kind.ORDINARY).count();
        Incoming.LOG.info("LANDING_MEASURE label={} removals={} non_material={} sources={} eligible={} states={} fracture_ns={} adapter_ns={} solver_wall_ns={} solver_cpu_ns={} allocated_bytes={} segments={} queue_ops={} geometry_reads={} peak_queue={} collision_bytes={} motion_bytes_estimate={} output_bytes_estimate={} outcomes={}",
                label, input.removalCells(), input.nonMaterialCells(), input.sources().size(), eligible, input.states().size(),
                fracture, adapter, elapsed, cpu, allocated, result.segments(), result.queueOperations(), result.collisionReads(), result.peakQueue(),
                result.inputBytes(), result.motionBytesEstimate(), result.outputBytesEstimate(), counts);
        return new Measurement(input, result);
    }

    private static void verify(GameTestHelper h, Measurement measurement) {
        var inputs = measurement.input.sources();
        var result = measurement.result;
        h.assertTrue(inputs.size() == result.outcomes().size(), "Material identity lost or duplicated");
        Map<Long, PreparedLandingModel.Source> expected = new HashMap<>();
        for (var source : inputs) expected.put(source.id(), source);
        var landings = new java.util.HashSet<BlockPos>();
        for (var output : result.outcomes()) {
            h.assertTrue(output.source().equals(expected.remove(output.source().id())), "State/volume/mass/identity changed");
            h.assertTrue(Double.isFinite(output.time()) && output.time() >= output.source().departure(), "Noncausal or infinite result");
            if (output.status() == PreparedLandingModel.Status.LANDED) {
                h.assertTrue(output.dueTick() / 20.0 >= output.time(), "Landing tick rounded before arrival");
                h.assertTrue(landings.add(new BlockPos(output.x(), output.y(), output.z())), "Two materials occupy one final cell");
            }
        }
        h.assertTrue(expected.isEmpty(), "Unreported material source");
        h.assertTrue(result.segments() <= LIMITS.segments(), "Worker escaped operation bound");
    }

    private static <T> CompletableFuture<T> worker(GameTestHelper h, Supplier<T> task) {
        StrikeGameTests.gameSpeed(h);
        var executor = Executors.newSingleThreadExecutor(r -> new Thread(r, "prepared-landing-experiment"));
        StrikeGameTests.afterTest(h, executor::shutdownNow);
        return CompletableFuture.supplyAsync(task, executor);
    }

    @SuppressWarnings("unchecked")
    private static ChunkShot[] syntheticShots(int height, boolean dense) {
        ChunkShot[] result = new ChunkShot[9];
        for (int k = 0; k < 9; k++) {
            var sections = (PalettedContainer<BlockState>[]) new PalettedContainer<?>[height / 16];
            for (int section = 0; section < sections.length; section++) {
                sections[section] = new PalettedContainer<>(Block.BLOCK_STATE_REGISTRY, Blocks.AIR.defaultBlockState(), PalettedContainer.Strategy.SECTION_STATES);
                for (int y = 0; y < 16; y++) for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
                    int worldY = section * 16 + y;
                    boolean material = dense && worldY > 3 && worldY < height - 8
                            && (worldY % 4 == 0 || x == 1 || x == 14 || z == 1 || z == 14);
                    if (worldY == 0 || material || !dense && x == 4 && z == 4 && worldY == 32)
                        sections[section].set(x, y, z, worldY == 0 ? Blocks.OBSIDIAN.defaultBlockState() : Blocks.WHITE_CONCRETE.defaultBlockState());
                    if (!dense && x == 8 && z == 4 && worldY == 32) sections[section].set(x, y, z, Blocks.BARREL.defaultBlockState());
                }
                sections[section].getAll(Blast::ensure);
            }
            var read = new DiskShots.Read(new ChunkPos(k % 3 - 1, k / 3 - 1), 0, sections, new int[4 * 256], 15, null, 0);
            result[k] = ChunkShot.fromDisk(read.primed(Blast.SERVER), null, null, false);
        }
        return result;
    }

    private static Detonation syntheticDetonation() {
        return new Detonation(1, new Vec3(-3104.5, 63, -560.5), 63, 1000, true, 0, 0, 3, 20000, 42, 1, false, .25f, 6000, false);
    }

    private static Detonation detonation(JsonObject row) {
        var xyz = row.getAsJsonArray("burst");
        return new Detonation(row.get("id").getAsInt(), new Vec3(xyz.get(0).getAsDouble(), xyz.get(1).getAsDouble(), xyz.get(2).getAsDouble()),
                row.get("ground_y").getAsDouble(), row.get("yield").getAsDouble(), row.get("surface").getAsInt() != 0,
                row.get("game_time").getAsLong(), row.get("wind_dir").getAsFloat(), row.get("wind_speed").getAsFloat(),
                row.get("visibility").getAsFloat(), row.get("seed").getAsLong(), row.get("scale").getAsFloat(), row.get("fallout").getAsInt() != 0,
                row.get("wave").getAsFloat(), row.get("day_time").getAsLong(), true);
    }

    private PreparedLandingGameTests() {}
}
