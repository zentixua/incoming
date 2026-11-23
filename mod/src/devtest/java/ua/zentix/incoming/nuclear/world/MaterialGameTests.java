package ua.zentix.incoming.nuclear.world;

import io.netty.buffer.Unpooled;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.storage.ChunkSerializer;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import ua.zentix.incoming.Incoming;
import ua.zentix.incoming.net.S2C;
import ua.zentix.incoming.nuclear.Detonation;
import ua.zentix.incoming.nuclear.NuclearEvents;
import ua.zentix.incoming.nuclear.material.*;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

@GameTestHolder(Incoming.MOD_ID)
@PrefixGameTestTemplate(false)
public final class MaterialGameTests {
    private MaterialGameTests() {}

    /** Editor fixture with no stored heightmaps. The exact same four top columns are sampled in a FULL chunk and on disk. */
    private static DiskShots.Read fixture(GameTestHelper h, ChunkPos pos, DustMaterial left, DustMaterial right) {
        ServerLevel level = h.getLevel();
        LevelChunk chunk = new LevelChunk(level, pos);
        for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
            var surface = switch (x < 8 ? left : right) {
                case WATER -> Blocks.WATER;
                case HARD -> Blocks.BRICKS;
                case SOIL -> Blocks.GRASS_BLOCK;
                case SAND -> Blocks.RED_SAND;
            };
            chunk.getSection(chunk.getSectionIndex(64)).setBlockState(x, 0, z, surface.defaultBlockState(), false);
        }
        net.minecraft.world.level.levelgen.Heightmap.primeHeightmaps(chunk, java.util.Set.of(net.minecraft.world.level.levelgen.Heightmap.Types.WORLD_SURFACE));
        CompoundTag data = ChunkSerializer.write(level, chunk);
        data.remove("Heightmaps");
        var read = DiskShots.parse(level, pos, data);
        int expected = (2 << (4 * left.ordinal())) + (2 << (4 * right.ordinal()));
        h.assertTrue(read.skip() == null && read.surfaceVotes() == expected, "editor palette votes " + read.surfaceVotes());
        h.assertTrue(MaterialSurvey.liveVotes(chunk) == expected, "loaded surface differs from palette");
        return read;
    }

    private static Detonation source(int id, int x, int z, long time, double kt) {
        return new Detonation(id, new Vec3(x + 0.5, 65, z + 0.5), 65, kt, true, time, 0, 0, 20000, id, 1f, false, 0.35f);
    }

    @GameTest(template = "range", batch = "nuke_material", timeoutTicks = 500, skyAccess = true)
    public static void editorLakeMixedShoreAndDelayedPalette(GameTestHelper h) {
        // Detached chunks outside the test platform: a late read of a remote cell cannot replace its fallback.
        int x = 10_000_000, z = -10_000_000;
        long now = h.getLevel().getGameTime();
        var d = source(7, x, z, now + 200, 15);
        var survey = new MaterialSurvey(h.getLevel(), d, new AtomicLong());
        var lake = fixture(h, new ChunkPos(x >> 4, z >> 4), DustMaterial.WATER, DustMaterial.WATER);
        var shore = fixture(h, new ChunkPos((x + 64) >> 4, z >> 4), DustMaterial.WATER, DustMaterial.SAND);
        var sand = fixture(h, new ChunkPos((x + 128) >> 4, z >> 4), DustMaterial.SAND, DustMaterial.SAND);
        var paving = fixture(h, new ChunkPos((x + 192) >> 4, z >> 4), DustMaterial.HARD, DustMaterial.HARD);
        for (var read : List.of(lake, shore, sand, paving)) survey.disk(read.pos().toLong(), read.surfaceVotes());
        var live = new LoadedSurfaces();
        live.put(paving.pos().toLong(), 4 << (DustMaterial.SOIL.ordinal() * 4));
        h.assertTrue(h.getLevel().getChunkSource().getChunkNow(x >> 4, z >> 4) == null, "fixture loaded an actual world chunk");
        var birth = survey.seal(h.getLevel(), now, live);
        live.remove(paving.pos().toLong());
        survey.disk(ChunkPos.asLong((x + 256) >> 4, z >> 4), 4);
        var events = new NuclearEvents();
        events.add(d, 0.0);
        events.material(d.id(), birth);
        var tag = events.save(new CompoundTag(), h.getLevel().registryAccess());
        var reload = NuclearEvents.load(tag, h.getLevel().registryAccess());
        tag.remove("materials");
        var old = NuclearEvents.load(tag, h.getLevel().registryAccess());
        h.assertTrue(old.detonations().size() == 1 && old.material(7) == null, "legacy detonation lost");
        h.succeedWhen(() -> {
            var bytes = birth.encoded();
            var restored = reload.material(7).encoded();
            h.assertTrue(bytes != null && restored != null, "birth encoding");
            h.assertTrue(java.util.Arrays.equals(bytes.bytes(), restored.bytes()) && reload.material(7).id.equals(birth.id), "reload changed revision");
            var replay = new MaterialInbox();
            bytes.packets().forEach(replay::receive);
            MaterialMap m = replay.map();
            h.assertTrue(m != null && m.wet() && m.material(x, z) == DustMaterial.WATER, "lake " + (m == null ? "pending" : m.material(x, z) + ", wet=" + m.wet() + ", bounds=" + m.bounds()));
            h.assertTrue(m.material(x + 64, z) == DustMaterial.WATER, "equal shore vote");
            h.assertTrue(m.material(x + 128, z) == DustMaterial.SAND, "sand patch");
            h.assertTrue(m.material(x + 192, z) == DustMaterial.SOIL && m.material(x + 256, z) == DustMaterial.SOIL, "live priority or overdue fallback");
            h.assertTrue(m.tick() + 200 <= d.gameTime() + d.arrivalTicks(d.slantOf(0)), "late ground-zero birth");
        });
    }

    @GameTest(template = "range", batch = "nuke_material", timeoutTicks = 500, skyAccess = true)
    public static void scheduledBirthIsCommittedBeforeArrival(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        Vec3 target = Vec3.atCenterOf(h.absolutePos(new BlockPos(16, 4, 16)));
        var events = new NuclearEvents();
        long burst = level.getGameTime() + 210;
        events.schedule(new NuclearEvents.ScheduledStrike(1, target, 15, false, burst - 210, burst, target, Optional.empty(), true));
        var surveys = new MaterialSurveys();
        h.succeedWhen(() -> {
            long now = level.getGameTime();
            surveys.tick(level, events, System.nanoTime() + 450_000);
            var b = events.material(1);
            h.assertTrue(b != null && b.encoded() != null, "waiting for scheduled revision");
            var m = MaterialMap.decode(b.encoded().bytes());
            h.assertTrue(m.tick() <= burst - 200, "birth tick " + m.tick() + " for burst " + burst);
        });
    }

    @GameTest(template = "range", batch = "nuke_material_disk", timeoutTicks = 700, skyAccess = true)
    public static void sparseEditorReadIsReservedAndNeverLoadsTheChunk(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        BlockPos at = h.absolutePos(new BlockPos(32, 8, 32));
        var d = source(1, at.getX(), at.getZ(), level.getGameTime() + 900, 1000);
        var order = MaterialSurvey.sparse(d);
        h.assertTrue(order.length > 0, "no sparse cells outside ruin preparation");
        ChunkPos far = new ChunkPos(order[0]);
        h.assertTrue(level.getChunkSource().getChunkNow(far.x, far.z) == null, "sparse target already loaded");
        var chunk = new LevelChunk(level, far);
        for (int x = 0; x < 16; x++) for (int z = 0; z < 16; z++)
            chunk.getSection(chunk.getSectionIndex(64)).setBlockState(x, 0, z, Blocks.RED_SAND.defaultBlockState(), false);
        var data = ChunkSerializer.write(level, chunk);
        data.remove("Heightmaps");
        var saved = ua.zentix.incoming.util.RegionFiles.write(level, far, data);
        var reserved = new AtomicLong();
        var survey = new MaterialSurvey(level, d, reserved);
        var live = new LoadedSurfaces();
        long[] peak = {0};
        MaterialBirth[] birth = {null};
        h.succeedWhen(() -> {
            h.assertTrue(saved.isDone(), "waiting for fixture write");
            saved.join();
            if (birth[0] == null) {
                survey.work(level, System.nanoTime() + 400_000, true);
                peak[0] = Math.max(peak[0], reserved.get());
                h.assertTrue(survey.storedAnswers > 0, "waiting for stored chunk, submitted " + survey.sparseReads);
                birth[0] = survey.seal(level, level.getGameTime(), live);
            }
            var bytes = birth[0].encoded();
            h.assertTrue(bytes != null && reserved.get() == 0, "waiting for sealed map or released I/O");
            h.assertTrue(MaterialMap.decode(bytes.bytes()).material(far.getMiddleBlockX(), far.getMiddleBlockZ()) == DustMaterial.SAND,
                    "sparse sample missing");
            h.assertTrue(level.getChunkSource().getChunkNow(far.x, far.z) == null, "sparse read loaded chunk");
            h.assertTrue(peak[0] >= NuclearPrep.READ_BYTES && peak[0] <= 4 * NuclearPrep.READ_BYTES, "queued bytes " + peak[0]);
            Incoming.LOG.info("MATERIALBENCH 1 Mt disk fixture: {} sparse cells, {} submitted reads, {} stored answers, peak {} reserved bytes",
                    order.length, survey.sparseReads, survey.storedAnswers, peak[0]);
        });
    }

    @GameTest(template = "range", batch = "nuke_material", timeoutTicks = 1200, skyAccess = true)
    public static void coldJoinAndSeekUseIdenticalCommittedSnapshots(GameTestHelper h) {
        var events = new NuclearEvents();
        var births = new java.util.ArrayList<MaterialBirth>();
        // Spatially varying 1 Mt map with four real block classes. Measure the SavedData increment and framed wire bytes.
        for (int id = 1; id <= 17; id++) {
            int x = id * 4096;
            var d = source(id, x, 0, 1000, id == 1 ? 1000 : 1);
            var samples = new Long2IntOpenHashMap();
            for (int iz = -16; iz < 16; iz++) for (int ix = -16; ix < 16; ix++) {
                int kind = Math.floorMod(ix / 3 + iz / 5, 4);
                samples.put(ChunkPos.asLong((x >> 4) + ix * 8, iz * 8), 4 << (kind * 4));
            }
            births.add(MaterialBirth.seal(d, 800, 0, samples, null));
            events.add(d, 1000.0);
        }
        h.succeedWhen(() -> {
            for (var b : births) h.assertTrue(b.encoded() != null, "encoding sources");
            int oneDisk;
            try {
                var tag = events.save(new CompoundTag(), h.getLevel().registryAccess());
                int empty = diskBytes(tag);
                events.material(1, births.getFirst());
                oneDisk = diskBytes(events.save(new CompoundTag(), h.getLevel().registryAccess())) - empty;
            } catch (IOException e) { throw new IllegalStateException(e); }
            MaterialDelivery a = new MaterialDelivery(), b = new MaterialDelivery();
            a.snapshot(births, 4096, 0);
            b.snapshot(births, 4096 * 17, 0);
            var mapsA = new java.util.HashMap<java.util.UUID, MaterialInbox>();
            var mapsB = new java.util.HashMap<java.util.UUID, MaterialInbox>();
            int total = 0, oneWire = 0;
            long peak = 0;
            for (int tick = 0; tick < 300; tick++) {
                long start = System.nanoTime();
                var pa = a.next(tick);
                peak = Math.max(peak, System.nanoTime() - start);
                var pb = b.next(tick);
                if (pa == null || pb == null) break;
                if (tick == 0) h.assertTrue(pa.id().equals(births.getFirst().id) && pb.id().equals(births.getLast().id), "nearest source order");
                h.assertTrue(a.next(tick) == null, "two parts in one tick");
                total += wire(h, pa, mapsA.computeIfAbsent(pa.id(), unused -> new MaterialInbox()));
                if (pa.id().equals(births.getFirst().id)) oneWire = total;
                wire(h, pb, mapsB.computeIfAbsent(pb.id(), unused -> new MaterialInbox()));
            }
            h.assertTrue(mapsA.size() == 17 && mapsB.size() == 17 && total < 1_500_000 && oneWire < 64_000, "snapshot wire " + total);
            for (var birth : births) {
                var replay = new MaterialInbox();
                for (var p : birth.encoded().packets()) wire(h, p, replay);
                h.assertTrue(java.util.Arrays.equals(mapsA.get(birth.id).map().encode(), mapsB.get(birth.id).map().encode())
                        && java.util.Arrays.equals(replay.map().encode(), birth.encoded().bytes()), "cold client or seek disagreement");
            }
            Incoming.LOG.info("MATERIALBENCH 1 Mt fixture: framed wire {} B, uncompressed SavedData {} B, 17 sources {} B, peak dispatch {} ns",
                    oneWire, oneDisk, total, peak);
        });
    }

    private static int wire(GameTestHelper h, S2C.NukeMaterial p, MaterialInbox target) {
        var buf = Unpooled.buffer();
        try {
            var codec = net.minecraft.network.protocol.game.GameProtocols.CLIENTBOUND_TEMPLATE.bind(
                    net.minecraft.network.RegistryFriendlyByteBuf.decorator(h.getLevel().registryAccess(),
                            net.neoforged.neoforge.network.connection.ConnectionType.NEOFORGE)).codec();
            codec.encode(buf, new net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket(p));
            int bytes = buf.readableBytes() + net.minecraft.network.VarInt.getByteSize(buf.readableBytes());
            var packet = (net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket) codec.decode(buf);
            target.receive((S2C.NukeMaterial) packet.payload());
            return bytes;
        } finally { buf.release(); }
    }
    private static int diskBytes(CompoundTag tag) throws IOException {
        var bytes = new ByteArrayOutputStream();
        NbtIo.write(tag, new DataOutputStream(bytes));
        return bytes.size();
    }
}
