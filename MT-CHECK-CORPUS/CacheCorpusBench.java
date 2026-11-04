package ua.zentix.incoming.nuclear.world;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.lang.management.ManagementFactory;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import ua.zentix.incoming.Incoming;
import ua.zentix.incoming.nuclear.Detonation;
import ua.zentix.incoming.nuclear.model.HobBlast;
import ua.zentix.incoming.nuclear.model.WorldNoise;
import ua.zentix.incoming.nuclear.model.Yield;

/** Private paired measurement, loaded only by the ignored Gradle init script. */
@GameTestHolder(Incoming.MOD_ID)
@PrefixGameTestTemplate(false)
public final class CacheCorpusBench {
    private static final Path ROOT = Path.of("/mnt/data/projects/incoming/mod/run/wt-MT-OPT/mod/run/mt-s2/corpus");
    private static final double[] PRESSURES = {1, 6, 20, 50};
    private static final com.sun.management.ThreadMXBean ALLOC =
            (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
    private static final Field NOISE;
    private static final Field BLASTS;
    static {
        try {
            NOISE = RuinContext.class.getDeclaredField("noise"); NOISE.setAccessible(true);
            BLASTS = RuinContext.class.getDeclaredField("blasts"); BLASTS.setAccessible(true);
        } catch (ReflectiveOperationException failure) { throw new ExceptionInInitializerError(failure); }
    }
    private CacheCorpusBench() {}
    private record Result(Collapse collapse, RuinPlan plan, long solveNs, long finishNs,
                          long solveBytes, long finishBytes) {}
    private static long allocated() { return ALLOC.getThreadAllocatedBytes(Thread.currentThread().threadId()); }
    private static long gcMillis() {
        long sum = 0;
        for (var bean : ManagementFactory.getGarbageCollectorMXBeans()) sum += Math.max(0, bean.getCollectionTime());
        return sum;
    }
    private static Result solve(RuinContext.Grid grid) {
        long bytes = allocated(), begin = System.nanoTime();
        Collapse collapse = Collapse.solve(grid);
        long solveNs = System.nanoTime() - begin, solveBytes = allocated() - bytes;
        bytes = allocated(); begin = System.nanoTime();
        RuinPlan plan = RuinPlanner.finish(grid, collapse);
        return new Result(collapse, plan, solveNs, System.nanoTime() - begin, solveBytes, allocated() - bytes);
    }
    private static void same(GameTestHelper h, Collapse a, Collapse b, String label) {
        h.assertTrue(a.yb == b.yb && a.yt == b.yt && a.h == b.h && a.surface == b.surface, label + " geometry");
        h.assertTrue(a.directSupport == b.directSupport && a.supportChecked == b.supportChecked, label + " support path");
        h.assertTrue(Arrays.equals(a.gone, b.gone), label + " gone");
        h.assertTrue(Arrays.equals(a.drained, b.drained), label + " drained");
        h.assertTrue(a.changes.equals(b.changes), label + " changed states");
        h.assertTrue(Arrays.equals(a.changes.keySet().toLongArray(), b.changes.keySet().toLongArray()), label + " material candidate order");
        h.assertTrue(Arrays.equals(a.removed, b.removed) && Arrays.equals(a.wood, b.wood), label + " material counts");
        h.assertTrue(Arrays.equals(a.ground, b.ground), label + " ground");
        h.assertTrue(a.leaves.equals(b.leaves), label + " leaves");
        h.assertTrue(a.fluidTicks.equals(b.fluidTicks), label + " fluid ticks");
        for (int i = 0; i < 9; i++) {
            h.assertTrue(a.blasts[i] != null && b.blasts[i] != null, label + " complete fracture window");
            h.assertTrue(Arrays.equals(a.blasts[i].removed(), b.blasts[i].removed()), label + " fracture " + i);
        }
    }
    private static RuinContext.Grid grid(net.minecraft.server.level.ServerLevel level, Detonation d,
                                          ChunkPos centre, ChunkShot[] shots, long noiseSeed) throws IllegalAccessException {
        RuinContext ctx = new RuinContext(level, d);
        NOISE.set(ctx, new WorldNoise(noiseSeed, 1));
        for (ChunkShot shot : shots) ctx.restoreShot(shot.pos().toLong(), shot);
        return new RuinContext.Grid(ctx, centre, level.getMinBuildHeight(), level.getMaxBuildHeight(),
                shots, Blast.SERVER, true, true, true);
    }
    private static Map<?, ?> blasts(RuinContext.Grid grid) throws IllegalAccessException {
        return (Map<?, ?>) BLASTS.get(grid.ctx());
    }
    @GameTest(template = "range", timeoutTicks = 10000, batch = "mt_check_cache_corpus", skyAccess = true)
    public static void paired(GameTestHelper h) throws Exception {
        h.assertTrue(ALLOC.isThreadAllocatedMemorySupported(), "Thread allocation counters unavailable");
        if (!ALLOC.isThreadAllocatedMemoryEnabled()) ALLOC.setThreadAllocatedMemoryEnabled(true);
        var manifest = JsonParser.parseString(Files.readString(ROOT.resolve("manifest.json"))).getAsJsonObject();
        var unique = new LinkedHashMap<Long, JsonObject>();
        for (var item : manifest.getAsJsonArray("roots")) {
            var row = item.getAsJsonObject(); var at = row.getAsJsonArray("chunk");
            unique.putIfAbsent(ChunkPos.asLong(at.get(0).getAsInt(), at.get(1).getAsInt()), row);
        }
        h.assertTrue(unique.size() == 190 && manifest.getAsJsonArray("chunks").size() == 2177, "Unexpected corpus shape");
        var cases = new ArrayList<>(unique.entrySet());
        var level = h.getLevel();
        h.assertTrue(level.getHeight() == manifest.get("height").getAsInt()
                && level.getMinBuildHeight() == manifest.get("minY").getAsInt(), "Corpus dimension geometry mismatch");
        Incoming.LOG.info("CACHE_CORPUS_START roots={} cases={} pairs={} timedPairs={} cpu={} maxHeap={} cacheMode=clear-only-blasts view=Blast.SERVER",
                cases.size(), cases.size() * 4, cases.size() * 16, cases.size() * 8,
                Runtime.getRuntime().availableProcessors(), Runtime.getRuntime().maxMemory());
        for (int index = 0; index < cases.size() * 4; index++) {
            final int number = index;
            h.runAtTickTime(index + 1, () -> {
                try {
                    var entry = cases.get(number / 4);
                    double psi = PRESSURES[number % 4]; var centre = new ChunkPos(entry.getKey());
                    var reads = new DiskShots.Read[25]; long started = System.nanoTime();
                    for (int z = -2; z <= 2; z++) for (int x = -2; x <= 2; x++) {
                        var pos = new ChunkPos(centre.x + x, centre.z + z);
                        var tag = NbtIo.readCompressed(ROOT.resolve("chunks/" + pos.x + "_" + pos.z + ".nbt.gz"), NbtAccounter.create(16L << 20));
                        var read = DiskShots.parse(level, pos, tag);
                        h.assertTrue(read.skip() == null, "Invalid strict corpus source " + pos + ": " + read.skip());
                        reads[(z + 2) * 5 + x + 2] = read;
                    }
                    long parseNs = System.nanoTime() - started; started = System.nanoTime();
                    for (int i = 0; i < 25; i++) reads[i] = reads[i].primed(Blast.SERVER);
                    long primeNs = System.nanoTime() - started;
                    int ground = reads[12].motion()[8 * 16 + 8] - 1;
                    double hob = Yield.optimalBurstHeight(1000), distance = HobBlast.rangeFor(psi, hob, 1000);
                    var d = new Detonation(100, new Vec3(centre.getMiddleBlockX() - distance, ground + hob, centre.getMiddleBlockZ()),
                            ground, 1000, false, 0, 0, 3, 20000, 42, 1, false, .25f, 0, true);
                    var shots = new ChunkShot[25]; long shotBytes = 0; started = System.nanoTime();
                    for (int i = 0; i < 25; i++) { shots[i] = ChunkShot.fromDisk(reads[i], null, null, false); shotBytes += shots[i].bytes; }
                    var cached = grid(level, d, centre, shots, manifest.get("seed").getAsLong());
                    var uncached = grid(level, d, centre, shots, manifest.get("seed").getAsLong());
                    long snapshotNs = System.nanoTime() - started;
                    long bytes = allocated(); started = System.nanoTime();
                    for (int z = -1; z <= 1; z++) for (int x = -1; x <= 1; x++) cached.blast(x, z);
                    long coldBlastSetupNs = System.nanoTime() - started, coldBlastSetupBytes = allocated() - bytes;
                    Map<?, ?> cachedIdentity = new LinkedHashMap<>(blasts(cached));
                    h.assertTrue(cachedIdentity.size() == 9, "Cached prewarm did not create nine fractures");
                    Map<?, ?> previousFresh = Map.of();
                    for (int sample = 0; sample < 4; sample++) {
                        // The only per-solve reset: discard cross-task fractures, retain every input/setting/noise law.
                        blasts(uncached).clear();
                        h.assertTrue(blasts(uncached).isEmpty(), "Uncached solve retained fractures");
                        long gcBefore = gcMillis(); Result a, b;
                        if ((sample & 1) == 0) { a = solve(cached); b = solve(uncached); }
                        else { b = solve(uncached); a = solve(cached); }
                        long gcAfter = gcMillis();
                        String label = "case " + number + "/" + sample;
                        h.assertTrue(blasts(cached).size() == 9 && blasts(uncached).size() == 9, label + " fracture count");
                        for (var fracture : cachedIdentity.entrySet())
                            h.assertTrue(blasts(cached).get(fracture.getKey()) == fracture.getValue(), label + " cached fracture replaced");
                        for (var fracture : blasts(uncached).entrySet())
                            h.assertTrue(previousFresh.get(fracture.getKey()) != fracture.getValue(), label + " uncached fracture reused");
                        previousFresh = new LinkedHashMap<>(blasts(uncached));
                        same(h, a.collapse, b.collapse, label);
                        h.assertTrue(a.plan.differs(b.plan) == null, label + " plan differs: " + a.plan.differs(b.plan));
                        long encodeStart = System.nanoTime();
                        var storedA = a.plan.detached().store(); var storedB = b.plan.detached().store();
                        h.assertTrue(storedA.equals(storedB), label + " full durable/material payload differs");
                        var restoredA = RuinPlan.restore(storedA, cached.ctx()); var restoredB = RuinPlan.restore(storedB, uncached.ctx());
                        h.assertTrue(a.plan.differs(restoredA) == null && b.plan.differs(restoredB) == null, label + " restored plan differs");
                        h.assertTrue(storedA.equals(restoredA.detached().store()) && storedB.equals(restoredB.detached().store()), label + " full durable/material roundtrip differs");
                        long encodeRestoreNs = System.nanoTime() - encodeStart;
                        Incoming.LOG.info("CACHE_PAIR case={} group={} x={} z={} psi={} sample={} warm={} cachedFirst={} cachedSolveNs={} uncachedSolveNs={} cachedFinishNs={} uncachedFinishNs={} cachedSolveBytes={} uncachedSolveBytes={} cachedFinishBytes={} uncachedFinishBytes={} coldBlastSetupNs={} coldBlastSetupBytes={} parseNs={} primeNs={} snapshotNs={} encodeRestoreNs={} gcMs={} shotBytes={} planBytes={} changes={} support={} direct={} fullEqual=true",
                                number, entry.getValue().get("group").getAsString(), centre.x, centre.z, psi, sample, sample < 2, (sample & 1) == 0,
                                a.solveNs, b.solveNs, a.finishNs, b.finishNs, a.solveBytes, b.solveBytes, a.finishBytes, b.finishBytes,
                                coldBlastSetupNs, coldBlastSetupBytes, parseNs, primeNs, snapshotNs, encodeRestoreNs, gcAfter - gcBefore,
                                shotBytes, a.plan.bytes(), a.plan.changedBlocks(), a.collapse.supportChecked, a.collapse.directSupport);
                    }
                    if (number % 20 == 19) Incoming.LOG.info("CACHE_PROGRESS cases={} of760", number + 1);
                } catch (Exception failure) { throw new AssertionError("Corpus case " + number, failure); }
            });
        }
        h.runAtTickTime(cases.size() * 4 + 1, () -> {
            Incoming.LOG.info("CACHE_CORPUS_DONE cases=760 pairs=3040 timedPairs=1520 fullEqual=true"); h.succeed();
        });
    }
}
