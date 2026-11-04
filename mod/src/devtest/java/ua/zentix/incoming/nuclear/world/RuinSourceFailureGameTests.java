package ua.zentix.incoming.nuclear.world;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import ua.zentix.incoming.Incoming;
import ua.zentix.incoming.IncomingConfig;
import ua.zentix.incoming.nuclear.NuclearWarhead;

/** A successful final-block read whose snapshot callback fails is required work, never an absent chunk. */
@GameTestHolder(Incoming.MOD_ID)
@PrefixGameTestTemplate(false)
public final class RuinSourceFailureGameTests {
    private RuinSourceFailureGameTests() {}

    private record Request(NuclearPrep prep, Object owner, UUID handle, UUID player, Vec3 target, long key, DiskShots.Read source) {}

    @SuppressWarnings("unchecked")
    private static Request request(GameTestHelper h) throws ReflectiveOperationException {
        ServerLevel level = h.getLevel();
        BlockPos at = h.absolutePos(new BlockPos(32, 1, 32));
        var chunk = level.getChunkAt(at);
        var root = new ChunkPos(chunk.getPos().x + 64, chunk.getPos().z);
        h.assertFalse(ua.zentix.incoming.util.Terrain.ready(level, root.x, root.z), "Required source fixture must be cold");
        var source = DiskShots.parse(level, root, net.minecraft.world.level.chunk.storage.ChunkSerializer.write(level, chunk));
        h.assertTrue(source.skip() == null, "Source fixture is not a successful final-block read");
        UUID handle = UUID.randomUUID(), player = UUID.randomUUID();
        Vec3 target = Vec3.atCenterOf(at);
        RuinPreparation.prepare(level, handle, player, target, 15, false, false);
        var prep = NuclearWorld.get(level).prep();
        Object p = null;
        for (Object candidate : (List<Object>) field(prep, "preps").get(prep))
            if (handle.equals(field(candidate, "handle").get(candidate))) { p = candidate; break; }
        if (p == null) throw new AssertionError("Prepared request missing");
        var geometry = NuclearWarhead.geometry(level, target, 15, false, (float) IncomingConfig.SERVER.effectsScale());
        field(p, "geometry").set(p, geometry); field(p, "resolvedTarget").set(p, target);
        field(p, "ruins").set(p, new RuinContext(level, geometry));
        field(p, "order").set(p, new long[] {root.toLong()}); field(p, "nextRead").setInt(p, 1);
        return new Request(prep, p, handle, player, target, root.toLong(), source);
    }

    private static DiskShots.Read failingPublication(DiskShots.Read source) {
        var states = source.states().clone();
        int section = 0;
        while (section < states.length && states[section] == null) section++;
        if (section == states.length) throw new AssertionError("Source fixture has no solid section");
        var original = states[section];
        // Valid states/heightmaps are retained. Only the actual snapshot-size callback throws.
        var broken = new PalettedContainer<BlockState>(Block.BLOCK_STATE_REGISTRY, Blocks.AIR.defaultBlockState(), PalettedContainer.Strategy.SECTION_STATES) {
            @Override public int getSerializedSize() { throw new IllegalStateException("Injected source snapshot publication failure"); }
        };
        for (int y = 0; y < 16; y++) for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++)
            broken.set(x, y, z, original.get(x, y, z));
        states[section] = broken;
        return new DiskShots.Read(source.pos(), source.minY(), states, source.heights(), source.unprimed(), null, source.flashLayer(), source.scar());
    }

    private static void far(GameTestHelper h, Request request) throws ReflectiveOperationException {
        var method = NuclearPrep.class.getDeclaredMethod("far", ServerLevel.class, request.owner().getClass(), WorkClock.class);
        method.setAccessible(true);
        ((WorkClock) field(request.prep(), "clock").get(request.prep())).start(1_000_000_000);
        var shared = WorkClock.counting(1_000_000); shared.start(1_000_000_000);
        method.invoke(request.prep(), h.getLevel(), request.owner(), shared);
    }

    private static void requireFailed(GameTestHelper h, Request request) throws ReflectiveOperationException {
        var status = RuinPreparation.status(h.getLevel(), request.handle());
        h.assertTrue(status.stage() == RuinPreparation.Stage.FAILED && status.reason() == RuinPreparation.Reason.IO,
                "Failed source publication certified preparation instead of FAILED/IO");
        h.assertTrue(field(request.owner(), "farSkipped").getInt(request.owner()) == 0, "Failed required root received absent-terrain completion credit");
        h.assertTrue(RuinPreparation.bindCarrier(h.getLevel(), request.handle(), request.player(), request.target(), 15, false, false) == -1,
                "Failed source publication allowed carrier binding");
    }

    @GameTest(template = "range", batch = "nuke_source_failure", skyAccess = true)
    @SuppressWarnings("unchecked")
    public static void ordinarySourcePublicationFailureCannotBecomeReady(GameTestHelper h) throws ReflectiveOperationException {
        var request = request(h);
        try {
            var reads = (RuinInbox<DiskShots.Read>) field(request.owner(), "reads").get(request.owner());
            reads.expect().accept(failingPublication(request.source()));
            far(h, request); requireFailed(h, request);
        } finally { RuinPreparation.cancel(h.getLevel(), request.handle()); }
        h.succeed();
    }

    @GameTest(template = "range", batch = "nuke_source_cached_failure", skyAccess = true)
    @SuppressWarnings("unchecked")
    public static void cachedFallbackPublicationFailureCannotBecomeReady(GameTestHelper h) throws ReflectiveOperationException {
        var request = request(h);
        try {
            var type = Class.forName(NuclearPrep.class.getName() + "$ArchivedShot");
            var ctor = type.getDeclaredConstructor(long.class, Optional.class, DiskShots.Read.class); ctor.setAccessible(true);
            var archived = (RuinInbox<Object>) field(request.owner(), "archived").get(request.owner());
            archived.expect().accept(ctor.newInstance(request.key(), Optional.empty(), failingPublication(request.source())));
            far(h, request); requireFailed(h, request);
        } finally { RuinPreparation.cancel(h.getLevel(), request.handle()); }
        h.succeed();
    }

    @GameTest(template = "range", batch = "nuke_source_absent", skyAccess = true)
    @SuppressWarnings("unchecked")
    public static void genuinelyAbsentOrPartialTerrainRemainsNonRequired(GameTestHelper h) throws ReflectiveOperationException {
        for (String reason : new String[] {"not on disk", "not fully generated on disk"}) {
            var request = request(h);
            try {
                var reads = (RuinInbox<DiskShots.Read>) field(request.owner(), "reads").get(request.owner());
                reads.expect().accept(DiskShots.Read.skipped(new ChunkPos(request.key()), reason));
                far(h, request);
                var status = RuinPreparation.status(h.getLevel(), request.handle());
                h.assertTrue(status.stage() == RuinPreparation.Stage.READY && status.ready() == 1 && status.total() == 1,
                        "Legitimate non-required terrain became a source error: " + reason);
                h.assertTrue(field(request.owner(), "farSkipped").getInt(request.owner()) == 1, "Absent terrain lost completion accounting");
            } finally { RuinPreparation.cancel(h.getLevel(), request.handle()); }
        }
        h.succeed();
    }

    private static Field field(Object owner, String name) throws ReflectiveOperationException {
        var field = owner.getClass().getDeclaredField(name); field.setAccessible(true); return field;
    }
}
