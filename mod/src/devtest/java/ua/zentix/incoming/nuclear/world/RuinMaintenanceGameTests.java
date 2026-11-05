package ua.zentix.incoming.nuclear.world;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongConsumer;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.storage.ChunkSerializer;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import ua.zentix.incoming.Incoming;
import ua.zentix.incoming.gametest.StrikeGameTests;
import ua.zentix.incoming.nuclear.Detonation;
import ua.zentix.incoming.nuclear.NuclearEvents;
import ua.zentix.incoming.nuclear.NuclearStrikes;

/** Completed source ownership must drain while authoritative material still needs the lane. */
@GameTestHolder(Incoming.MOD_ID)
@PrefixGameTestTemplate(false)
public final class RuinMaintenanceGameTests {
    private RuinMaintenanceGameTests() {}

    @GameTest(template = "range", batch = "nuke_maintenance", timeoutTicks = 80, skyAccess = true)
    @SuppressWarnings("unchecked")
    public static void completedSourceDrainsBeforeMaterialBacklogEnds(GameTestHelper h) throws Exception {
        var level = h.getLevel();
        NuclearStrikes.clear(level);
        RuinMatterGameTests.clearFixture(level);
        var world = NuclearWorld.get(level);
        var previousClock = NuclearWorld.clock(level.getServer());
        var clock = WorkClock.counting(1_000_000);
        NuclearWorld.useClock(level.getServer(), clock);
        clock.start(4_000_000);
        world.tick(level, clock);
        StrikeGameTests.afterTest(h, () -> {
            NuclearWorld.useClock(level.getServer(), previousClock);
            NuclearStrikes.clear(level);
            RuinMatterGameTests.clearFixture(level);
            RuinWorkers.shutdown();
        });
        BlockPos at = h.absolutePos(new BlockPos(32, 80, 32));
        level.setBlock(at, Blocks.GLASS.defaultBlockState(), 2);
        var centre = new ChunkPos(at);
        var chunk = level.getChunkSource().getChunkNow(centre.x, centre.z);
        var events = NuclearEvents.get(level);
        var d = new Detonation(events.nextId(), Vec3.atCenterOf(at), at.getY(), 15, true,
                level.getGameTime(), 0, 0, 20000, 42, 1, false, .25f);
        events.add(d);
        var ctx = world.ruins(level, d, level.getGameTime());
        var source = DiskShots.parse(level, centre, ChunkSerializer.write(level, chunk));
        h.assertTrue(source.skip() == null, "Actual source was not readable");

        // Install the already-handed-off boundary, without a synthetic empty source or I/O failure.
        var completed = completedSource(level, ctx, source);
        var inbox = completed.inbox();
        var readBytes = completed.charged();
        long reserved = completed.reserved();
        h.assertTrue(ctx.publicationBytes(source, Optional.empty()) < reserved,
                "Fixture must shrink an already-owned source, not require an external refund");

        var pieces = new ArrayList<RuinMatter.Piece>();
        for (int i = 0; i < 8192; i++) pieces.add(new RuinMatter.Piece(at.above(80),
                Blocks.STONE.defaultBlockState(), null, Vec3.ZERO, level.getGameTime()));
        var matter = RuinMatter.get(level);
        matter.release(pieces);
        long baseline = NuclearPrep.residentMemory(level);
        h.runAtTickTime(8, () -> {
            h.assertTrue(matter.pending() > 0, "Material backlog ended before the completion check");
            h.assertTrue(inbox.pending() == 0,
                    "Completed source starved behind material: ready=" + (inbox.peek() != null)
                            + ", pending=" + inbox.pending() + ", material=" + matter.pending());
            h.assertTrue(readBytes.get() < reserved, "Consumed source reservation was not released");
            h.assertTrue(ctx.shotCount() > 0, "Completed source did not publish its actual snapshot");
            h.assertTrue(ctx.shot(level, centre.x, centre.z).get(at.getX() & 15, at.getY(), at.getZ() & 15).is(Blocks.GLASS),
                    "Completed source changed the actual glass snapshot");
            h.assertTrue(NuclearPrep.residentMemory(level) <= NuclearPrep.PLANS_CAP,
                    "Maintenance exceeded the unchanged shared ownership cap");
            Incoming.LOG.info("MAINTENANCE completed source with material remaining {}, reserved {}, baseline {}, resident {}",
                    matter.pending(), reserved, baseline, NuclearPrep.residentMemory(level));
            h.succeed();
        });
    }

    record Completion(RuinInbox<Object> inbox, AtomicLong charged, long reserved) {}

    @SuppressWarnings("unchecked")
    static Completion completedSource(net.minecraft.server.level.ServerLevel level, RuinContext ctx, DiskShots.Read source)
            throws ReflectiveOperationException {
        var world = NuclearWorld.get(level);
        var d = ctx.d;
        var centre = source.pos();
        var prep = world.prep();
        var type = Class.forName(NuclearPrep.class.getName() + "$Prep");
        var constructor = type.getDeclaredConstructor(int.class, long.class, LongConsumer.class);
        constructor.setAccessible(true);
        AtomicLong readBytes = (AtomicLong) field(prep, "readBytes").get(prep);
        Object request = constructor.newInstance(190001, level.getGameTime(), (LongConsumer) readBytes::addAndGet);
        field(request, "geometry").set(request, d);
        field(request, "detonation").setInt(request, d.id());
        field(request, "handedOff").setLong(request, level.getGameTime());
        field(request, "ruins").set(request, ctx);
        field(request, "archiveWritten").setBoolean(request, true);
        field(request, "nextZoneReport").setLong(request, Long.MAX_VALUE);
        var plans = (RetainedPlans) field(request, "plans").get(request);
        plans.put(centre.toLong(), RuinPlan.EMPTY); // Keeps the valid preparation owner alive.
        ((Map<Integer, RetainedPlans>) field(world.scars(), "prepared").get(world.scars())).putIfAbsent(d.id(), plans);
        ((List<Object>) field(prep, "preps").get(prep)).add(request);
        var replyType = Class.forName(NuclearPrep.class.getName() + "$ArchivedShot");
        var replyConstructor = replyType.getDeclaredConstructor(long.class, Optional.class, DiskShots.Read.class);
        replyConstructor.setAccessible(true);
        var inbox = (RuinInbox<Object>) field(request, "archived").get(request);
        inbox.expect().accept(replyConstructor.newInstance(centre.toLong(), Optional.empty(), source));
        long reserved = readBytes.get();
        return new Completion(inbox, readBytes, reserved);
    }

    private static Field field(Object owner, String name) throws NoSuchFieldException {
        Field field = owner.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }
}
