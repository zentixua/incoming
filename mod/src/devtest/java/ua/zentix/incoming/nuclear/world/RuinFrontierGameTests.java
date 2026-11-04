package ua.zentix.incoming.nuclear.world;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.LongConsumer;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.storage.ChunkSerializer;
import net.minecraft.world.level.chunk.storage.RegionStorageInfo;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import ua.zentix.incoming.Incoming;
import ua.zentix.incoming.gametest.StrikeGameTests;
import ua.zentix.incoming.nuclear.Detonation;

/** Real region/source reads and archived originals with a deliberately tiny re-readable cache. */
@GameTestHolder(Incoming.MOD_ID)
@PrefixGameTestTemplate(false)
public final class RuinFrontierGameTests {
    private RuinFrontierGameTests() {}

    @GameTest(template = "range", batch = "nuke_disk_frontier", timeoutTicks = 1200, skyAccess = true)
    @SuppressWarnings("unchecked")
    public static void evictedDemandRefillsBeforeReaderSweepsTheZone(GameTestHelper h) throws Exception {
        StrikeGameTests.gameSpeed(h);
        ServerLevel level = h.getLevel();
        var original = RuinStorageGameTests.fixture(h, true);
        var centre = new ChunkPos(original.centre().x + 128, original.centre().z + 128);
        h.assertFalse(ua.zentix.incoming.util.Terrain.ready(level, centre.x, centre.z), "Disk frontier must be cold");
        double dx = (centre.x - original.centre().x) * 16.0, dz = (centre.z - original.centre().z) * 16.0;
        var g = original.ctx().d;
        var geometry = new Detonation(-1, g.burst().add(dx, 0, dz), g.groundY(), g.yieldKt(), g.surface(),
                0, 0, 0, g.visibility(), 0, g.scale(), false, g.wave());
        UUID handle = UUID.randomUUID();
        var target = new Vec3(centre.getMiddleBlockX(), geometry.groundY(), centre.getMiddleBlockZ());
        RuinPreparation.prepare(level, handle, UUID.randomUUID(), target, 15, false, true);
        NuclearPrep prep = NuclearWorld.get(level).prep(); Object p = null;
        for (Object candidate : (List<Object>) field(prep, "preps").get(prep))
            if (handle.equals(field(candidate, "handle").get(candidate))) p = candidate;
        if (p == null) throw new AssertionError("Request absent");
        final Object owner = p;
        RuinContext context = new RuinContext(level, geometry), reference = new RuinContext(level, geometry);
        field(p, "geometry").set(p, geometry); field(p, "resolvedTarget").set(p, target); field(p, "ruins").set(p, context);
        field(p, "layout").set(p, new CompletableFuture<NuclearPrep.Layout>()); // Keep registered work behind actual storage setup.
        field(p, "nextRefresh").setLong(p, Long.MAX_VALUE);
        long[] order = new long[100]; order[0] = centre.toLong();
        for (int i = 1; i < order.length; i++) order[i] = ChunkPos.asLong(centre.x + 16 * i, centre.z + 16 * i);
        field(p, "order").set(p, order); field(p, "nextRead").setInt(p, order.length);
        long previousLimit = field(prep, "shotCacheLimit").getLong(prep);
        field(prep, "shotCacheLimit").setLong(prep, 0); // Pressure is an eviction policy, never fake resident bytes.
        var other = NuclearWorld.get(level.getServer().getLevel(Level.NETHER));
        long previousReservation = other.materialReservation;
        StrikeGameTests.afterTest(h, () -> {
            other.materialReservation = previousReservation;
            try { field(prep, "shotCacheLimit").setLong(prep, previousLimit); }
            catch (ReflectiveOperationException e) { throw new IllegalStateException(e); }
            RuinPreparation.cancel(level, handle);
        });
        var info = new RegionStorageInfo("gametest", level.dimension(), "incoming_frontier");
        var root = level.getServer().getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT)
                .resolve("data/frontier-test-" + UUID.randomUUID());
        var archive = new RuinSnapshots(new RuinPages(info, root), (LongConsumer) field(p, "accounting").get(p), () -> 32L << 20);
        context.archive(archive);
        CompoundTag master = ChunkSerializer.write(level, level.getChunkSource().getChunkNow(original.centre().x, original.centre().z));
        List<ChunkShot> shots = new ArrayList<>();
        for (int x = -2; x <= 2; x++) for (int z = -2; z <= 2; z++) {
            var pos = new ChunkPos(centre.x + x, centre.z + z);
            CompoundTag data = master.copy(); data.putInt("xPos", pos.x); data.putInt("zPos", pos.z);
            level.getChunkSource().chunkMap.write(pos, data); // Fixture source writes, never a live chunk load/generation.
            var read = DiskShots.parse(level, pos, data);
            h.assertTrue(read.skip() == null, "Source fixture was not final terrain");
            context.putDisk(level, read); reference.putDisk(level, read);
            ChunkShot shot = context.shot(level, pos.x, pos.z);
            shots.add(shot); archive.evictable(pos.toLong(), shot);
        }
        var referenceGrid = reference.grid(level, centre, false);
        CompoundTag expected = RuinPlanner.finish(referenceGrid, Collapse.solve(referenceGrid)).store();
        h.assertTrue(expected.getLongArray("material").length > 0, "Physical reference has no removed material");
        boolean[] opened = {false};
        h.succeedWhen(() -> {
            try {
                if (!opened[0]) {
                    for (ChunkShot shot : shots) h.assertTrue(archive.evictable(shot.pos().toLong(), shot), "Actual snapshot write not acknowledged yet");
                    h.assertTrue(NuclearPrep.memory(level) < NuclearPrep.PLANS_CAP - (48L << 20), "Cold ledgers/setup still own admission");
                    context.dropShotsForTest();
                    invokeFar(prep, owner, level);
                    h.assertTrue(field(owner, "nextRead").getInt(owner) == 0,
                            "Missing current demand did not rewind stale read cursor: " + field(owner, "nextRead").getInt(owner));
                    other.materialReservation += Math.max(0, NuclearPrep.PLANS_CAP - (32L << 20) - NuclearPrep.memory(level) - (16L << 20));
                    field(owner, "layout").set(owner, null);
                    opened[0] = true;
                }
                int cursor = field(owner, "nextFar").getInt(owner), readCursor = field(owner, "nextRead").getInt(owner);
                h.assertTrue(readCursor <= Math.min(order.length, cursor + RuinWorkers.capacity()), "Reader outran bounded solver frontier");
                h.assertTrue(NuclearPrep.memory(level) <= NuclearPrep.PLANS_CAP, "Controlled pipeline exceeded its admission budget");
                var plans = (RetainedPlans) field(owner, "plans").get(owner);
                var plan = plans.get(centre.toLong());
                h.assertTrue(plan != null, "Current physical plan did not complete through actual disk/archive reads; read=" + readCursor
                        + ", far=" + cursor + ", reading=" + ((it.unimi.dsi.fastutil.longs.LongOpenHashSet) field(owner, "reading").get(owner)).size());
                h.assertTrue(plan.store().equals(expected), "Frontier/cache changes altered complete physical/material output");
                h.assertFalse(ua.zentix.incoming.util.Terrain.ready(level, centre.x, centre.z), "Preparation loaded/generated the disk-only source");
            } catch (ReflectiveOperationException e) { throw new IllegalStateException(e); }
        });
    }

    private static void invokeFar(NuclearPrep prep, Object p, ServerLevel level) throws ReflectiveOperationException {
        var method = NuclearPrep.class.getDeclaredMethod("far", ServerLevel.class, p.getClass(), WorkClock.class); method.setAccessible(true);
        ((WorkClock) field(prep, "clock").get(prep)).start(1_000_000_000);
        var clock = WorkClock.counting(1_000_000); clock.start(1_000_000_000); method.invoke(prep, level, p, clock);
    }
    private static Field field(Object owner, String name) throws ReflectiveOperationException {
        Field field = owner.getClass().getDeclaredField(name); field.setAccessible(true); return field;
    }
}
