package ua.zentix.incoming.nuclear.world;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.storage.RegionStorageInfo;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import ua.zentix.incoming.Incoming;
import ua.zentix.incoming.gametest.StrikeGameTests;
import ua.zentix.incoming.nuclear.NuclearStrikes;

/** A completed source needing growth must not keep the trimmer that funds it off the shared lane. */
@GameTestHolder(Incoming.MOD_ID)
@PrefixGameTestTemplate(false)
public final class RuinReclaimProgressGameTests {
    private RuinReclaimProgressGameTests() {}

    @GameTest(template = "range", batch = "nuke_reclaim_publication", timeoutTicks = 240, skyAccess = true)
    @SuppressWarnings("unchecked")
    public static void refusedReadySourceLetsItsSavedCacheFundPublication(GameTestHelper h) throws Exception {
        publication(h, false);
    }

    @GameTest(template = "range", batch = "nuke_reclaim_other_owner", timeoutTicks = 240, skyAccess = true)
    public static void refusedReadySourceLetsAnotherContextFundPublication(GameTestHelper h) throws Exception {
        publication(h, true);
    }

    @SuppressWarnings("unchecked")
    private static void publication(GameTestHelper h, boolean separateOwner) throws Exception {
        StrikeGameTests.gameSpeed(h);
        var level = h.getLevel();
        NuclearStrikes.clear(level);
        RuinMatterGameTests.clearFixture(level);
        var reference = RuinStorageGameTests.fixture(h, false);
        var world = NuclearWorld.get(level);
        var ctx = world.ruins(level, reference.ctx().d, level.getGameTime());
        var original = reference.ctx().d;
        var cacheCtx = separateOwner ? world.ruins(level, new ua.zentix.incoming.nuclear.Detonation(original.id() + 1,
                original.burst(), original.groundY(), original.yieldKt(), original.surface(), original.gameTime(),
                0, 0, original.visibility(), original.seed(), original.scale(), false, original.wave()), level.getGameTime()) : ctx;
        var other = NuclearWorld.get(level.getServer().getLevel(Level.NETHER));
        long prior = other.materialReservation;
        var oldClock = NuclearWorld.clock(level.getServer());
        NuclearWorld.useClock(level.getServer(), WorkClock.counting(1_000_000));
        StrikeGameTests.afterTest(h, () -> {
            other.materialReservation = prior;
            NuclearWorld.useClock(level.getServer(), oldClock);
            NuclearStrikes.clear(level);
            RuinMatterGameTests.clearFixture(level);
            RuinWorkers.shutdown();
        });
        var cold = new ChunkPos(reference.centre().x + 1024, reference.centre().z + 1024);
        var ordinary = RuinSnapshotAdmissionGameTests.largeRead(level, cold);
        var sections = java.util.Arrays.copyOf(ordinary.states(), 128);
        for (int i = ordinary.states().length; i < sections.length; i++) sections[i] = ordinary.states()[i % ordinary.states().length];
        var source = new DiskShots.Read(cold, ordinary.minY(), sections, ordinary.heights(), 0, null, 0);
        long publication = ctx.publicationBytes(source, null);
        h.assertTrue(publication > (4L << 20), "Fixture must require growth beyond its completed read slot");
        var snapshots = new ArrayList<ChunkShot>();
        for (int i = 0; i < 8; i++) {
            var pos = new ChunkPos(cold.x + 8 + i, cold.z);
            var read = new DiskShots.Read(pos, ordinary.minY(), ordinary.states(), ordinary.heights(), 0, null, 0);
            h.assertTrue(cacheCtx.putDisk(level, read), "Unpressured cache fixture refused");
            snapshots.add(cacheCtx.shot(level, pos.x, pos.z));
        }
        var root = level.getServer().getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT)
                .resolve("data/reclaim-publication-" + UUID.randomUUID());
        var archive = new RuinSnapshots(new RuinPages(new RegionStorageInfo("gametest", level.dimension(), "incoming_preparation"), root),
                world.prep().readAccounting(), () -> Math.max(0, NuclearPrep.PLANS_CAP - NuclearPrep.residentMemory(level)));
        cacheCtx.archive(archive);
        for (var shot : snapshots) archive.evictable(shot.pos().toLong(), shot);
        var inbox = (RuinInbox<Object>) field(ctx, "reads").get(ctx);
        var ctor = Class.forName(RuinContext.class.getName() + "$WindowRead").getDeclaredConstructors()[0];
        ctor.setAccessible(true);
        var reply = ctor.newInstance(cold.toLong(), source, null);
        int[] stage = {0};
        long[] fixed = {0}, started = {0};
        h.succeedWhen(() -> {
            if (stage[0] == 0) {
                h.assertTrue(archive.pendingWrites() == 0, "Real original snapshots are still being written");
                for (var shot : snapshots) h.assertTrue(archive.evictable(shot.pos().toLong(), shot), "Saved original did not become evictable");
                var pieces = new ArrayList<RuinMatter.Piece>();
                BlockPos at = h.absolutePos(new BlockPos(32, 180, 32));
                for (int i = 0; i < 8192; i++) pieces.add(new RuinMatter.Piece(at,
                        Blocks.STONE.defaultBlockState(), null, Vec3.ZERO, level.getGameTime()));
                RuinMatter.get(level).release(pieces);
                inbox.expect().accept(reply);
                // Keep the existing FIFO's next page rotation funded; it must still be far
                // too small to publish this larger source without reclaiming original snapshots.
                long rotation = RuinMatter.get(level).queueGrowthBytes(1);
                h.assertTrue(publication - (4L << 20) > rotation, "Publication unexpectedly fits rotation room");
                other.materialReservation += NuclearPrep.PLANS_CAP - NuclearPrep.residentMemory(level) - rotation;
                fixed[0] = other.materialReservation;
                started[0] = level.getGameTime();
                stage[0] = 1;
                Incoming.LOG.info("RECLAIM publication {} read slot {} fixed {} cached {}", publication, 4L << 20,
                        fixed[0], cacheCtx.shotBytes());
            }
            h.assertTrue(other.materialReservation == fixed[0], "External owner was refunded");
            h.assertTrue(NuclearPrep.residentMemory(level) <= NuclearPrep.PLANS_CAP, "Publication exceeded the shared cap: " + NuclearPrep.residentMemory(level));
            h.assertTrue(inbox.pending() == 0, "Refused ready source kept its own reclamation lane blocked");
            var published = ctx.shot(level, cold.x, cold.z);
            h.assertTrue(published != null && published.saved().encode().equals(ChunkShot.fromDisk(source, null, null, false).saved().encode()),
                    "Published source changed its actual palettes/heightmaps");
            h.assertTrue(RuinMatter.get(level).pending() > 0, "Material backlog ended before completion");
            Incoming.LOG.info("RECLAIM completed after {} ticks, resident {}", level.getGameTime() - started[0], NuclearPrep.residentMemory(level));
        });
    }

    private static Field field(Object owner, String name) throws Exception {
        Field result = owner.getClass().getDeclaredField(name);
        result.setAccessible(true);
        return result;
    }
}
