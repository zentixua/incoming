package ua.zentix.incoming.gametest;

import com.mojang.authlib.GameProfile;
import io.netty.channel.embedded.EmbeddedChannel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.Connection;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.PacketSendListener;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.network.protocol.game.ClientboundLightUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundLightUpdatePacketData;
import net.minecraft.network.protocol.game.ClientboundSectionBlocksUpdatePacket;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.neoforged.fml.util.ObfuscationReflectionHelper;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import org.jetbrains.annotations.Nullable;
import ua.zentix.incoming.Incoming;
import ua.zentix.incoming.nuclear.Detonation;
import ua.zentix.incoming.nuclear.world.ColumnScar;
import ua.zentix.incoming.work.ChunkDelivery;
import ua.zentix.incoming.nuclear.world.RuinPlan;
import ua.zentix.incoming.nuclear.world.RuinPlanner;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static ua.zentix.incoming.gametest.NuclearGameTests.atPsi;

/**
 * How in-place changes (ruins, blackout lamps) reach players holding the chunk ({@link ChunkDelivery}): a heavily changed
 * chunk as one whole-chunk packet built after its light, a lightly changed one as section deltas. A recorder in place of the chunk's viewers (its
 * {@code ChunkHolder} player provider, the one vanilla's own broadcast uses) rebuilds what a client would hold from the
 * blocks before the wave and the packets in order; it must equal the server's chunk in every position, either way.
 */
@GameTestHolder(Incoming.MOD_ID)
@PrefixGameTestTemplate(false)
public final class ChunkDeliveryGameTests {
    private ChunkDeliveryGameTests() {}

    /** Heavy chunk (house, glass, trees) goes whole after its light; a lone pane in a distant chunk goes as deltas. */
    @GameTest(template = "range", timeoutTicks = 400, batch = "nuke_delivery", skyAccess = true)
    public static void heavyRuinsGoWholeAfterTheirLight(GameTestHelper h) {
        run(h, true);
    }

    /** The same heavy ruins forced through section deltas give the client the same chunk. */
    @GameTest(template = "range", timeoutTicks = 400, batch = "nuke_delivery_deltas", skyAccess = true)
    public static void heavyRuinsAsDeltasGiveTheSameChunk(GameTestHelper h) {
        int was = ChunkDelivery.wholeFrom(Integer.MAX_VALUE);
        StrikeGameTests.afterTest(h, () -> ChunkDelivery.wholeFrom(was));
        run(h, false);
    }

    /**
     * While a heavily ruined chunk waits for its light, vanilla must not broadcast that chunk's light first: computed over
     * the new blocks, it would light the old ones on the client. The light thread is held so the chunk keeps waiting,
     * and a light change is marked on its holder meanwhile (as the chunk's own checks or a ruined neighbour's light
     * would); nothing of it may reach the client before the whole chunk.
     * <p>
     * At game speed: the light fence completes in wall time on the light thread, while the hold lasts {@code
     * ChunkDelivery.HOLD_LIMIT} ticks. Unthrottled, a CI shard where {@code light_late_busy} left the light thread a
     * backlog sent the chunk 234 and 381 ticks after the write: the hold expired and a light update went first, or the
     * test timed out (PR #683, 20.11.2026).
     */
    @GameTest(template = "range", timeoutTicks = 400, batch = "nuke_delivery_hold", skyAccess = true)
    public static void lightWaitsForTheWholeChunk(GameTestHelper h) {
        StrikeGameTests.gameSpeed(h);
        ServerLevel level = h.getLevel();
        LevelChunk heavy = level.getChunkAt(h.absolutePos(new BlockPos(16, 12, 16)));
        int y = h.absolutePos(new BlockPos(0, 12, 0)).getY();
        house(level, heavy.getPos(), y);
        Detonation d = atPsi(h, new BlockPos(-120, 12, 32), true, h.relativePos(heavy.getPos().getBlockAt(8, y + 2, 8)), 10);
        Recorder rec = Recorder.attach(h, List.of(heavy.getPos()));
        java.util.concurrent.CountDownLatch light = new java.util.concurrent.CountDownLatch(1);
        StrikeGameTests.afterTest(h, light::countDown);
        long[] applied = {0};
        Light[] before = {null};
        h.runAfterDelay(20, () -> {
            before[0] = Light.of(level, heavy.getPos());
            var engine = level.getChunkSource().getLightEngine();
            engine.addTask(heavy.getPos().x, heavy.getPos().z, net.minecraft.server.level.ThreadedLevelLightEngine.TaskType.PRE_UPDATE, () -> {
                try {
                    light.await(20, java.util.concurrent.TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
            rec.started = true;
            RuinPlan plan = RuinPlanner.plan(level, d, heavy);
            h.assertTrue(plan.apply(level, heavy, d, new ColumnScar.Budget(false)), "the new plan is outdated");
            h.assertTrue(ChunkDelivery.waiting(level, heavy.getPos()), "the heavy chunk does not wait to go whole");
            ChunkHolder holder = level.getChunkSource().chunkMap.getVisibleChunkIfPresent(heavy.getPos().toLong());
            holder.sectionLightChanged(LightLayer.SKY, SectionPos.blockToSectionCoord(y));
            applied[0] = level.getGameTime();
        });
        h.runAfterDelay(30, () -> {
            h.assertTrue(ChunkDelivery.waiting(level, heavy.getPos()), "the light fence passed while the light thread was held");
            List<Object> got = rec.got(heavy.getPos());
            h.assertTrue(count(got, ClientboundLightUpdatePacket.class) == 0 && count(got, ClientboundLevelChunkWithLightPacket.class) == 0,
                    "while waiting the client got " + got.stream().map(o -> o.getClass().getSimpleName()).toList());
            light.countDown();
        });
        h.succeedWhen(() -> {
            List<Object> got = rec.got(heavy.getPos());
            int first = indexOf(got, ClientboundLevelChunkWithLightPacket.class);
            h.assertTrue(first >= 0 && level.getGameTime() > applied[0] + 30, "waiting for the whole chunk");
            for (int i = 0; i < first; i++) {
                h.assertFalse(got.get(i) instanceof ClientboundLightUpdatePacket, "a light update reached the old blocks before the ruined chunk");
            }
            clientLight(h, level, heavy.getPos(), before[0], rec);
        });
    }

    /**
     * A blackout darkening many lamps of a watched chunk sends the chunk whole after its light, never lamp by lamp: each
     * lamp switched by a delta makes the client spread its block-light removal itself, on its render thread (a 1 Mt
     * blackout over the Silo map sent 91.6 thousand lamp deltas to a viewer at 3.9 km during the wave's 10 s passage,
     * 42 % of the client's render thread in light updates, frames up to 1.3 s; passage-stutter run 24.11.2026). The
     * client's blocks and light must equal the server's; a chunk with a few lamps keeps deltas. The 64 lamps go in two
     * budgeted passes of 30 and 34, each under the threshold alone: the threshold is about the chunk.
     */
    @GameTest(template = "range", timeoutTicks = 300, batch = "grid_delivery", skyAccess = true)
    public static void blackoutLampsGoWholeAfterTheirLight(GameTestHelper h) {
        StrikeGameTests.gameSpeed(h);
        ServerLevel level = h.getLevel();
        LevelChunk many = level.getChunkAt(h.absolutePos(new BlockPos(16, 12, 16)));
        LevelChunk few = level.getChunkAt(h.absolutePos(new BlockPos(48, 12, 48)));
        int y = h.absolutePos(new BlockPos(0, 12, 0)).getY() + 1;
        BlockState lamp = Blocks.GLOWSTONE.defaultBlockState();
        // 64 lamps two blocks apart, their light overlapping like a lit street; the other chunk holds three
        for (int x = 0; x < 16; x += 2) for (int z = 0; z < 16; z += 2) level.setBlock(many.getPos().getBlockAt(x, y, z), lamp, 2);
        for (int i = 0; i < 3; i++) level.setBlock(few.getPos().getBlockAt(4 + 4 * i, y, 8), lamp, 2);
        Recorder rec = Recorder.attach(h, List.of(many.getPos(), few.getPos()));
        Client manyClient = new Client(level, many), fewClient = new Client(level, few);
        h.runAfterDelay(20, () -> {
            manyClient.lightBefore = Light.of(level, many.getPos());
            manyClient.blocksFrom(many);
            fewClient.blocksFrom(few);
            rec.started = true;
            // the 64 lamps in two budgeted passes, as the blackout queue splits a chunk: 30 then the rest
            var first = ua.zentix.incoming.grid.ChunkLights.applyInPlace(level, many, true, 30, 0, true,
                    new it.unimi.dsi.fastutil.longs.LongArrayList(), null);
            h.assertTrue(!first.done() && first.changed() == 30, "the first pass did not stop at its limit: " + first);
            var rest = ua.zentix.incoming.grid.ChunkLights.applyInPlace(level, many, true, Integer.MAX_VALUE, first.next(), true,
                    new it.unimi.dsi.fastutil.longs.LongArrayList(), null);
            h.assertTrue(rest.done() && rest.changed() == 34, "the second pass did not finish the chunk: " + rest);
            var three = ua.zentix.incoming.grid.ChunkLights.applyInPlace(level, few, true, Integer.MAX_VALUE, 0, true,
                    new it.unimi.dsi.fastutil.longs.LongArrayList(), null);
            h.assertTrue(three.done() && three.changed() == 3, "three lamps not darkened: " + three);
            rec.applied = level.getGameTime();
        });
        h.succeedWhen(() -> {
            h.assertTrue(rec.applied > 0 && level.getGameTime() > rec.applied + 40, "waiting for delivery and light");
            List<Object> manyGot = rec.got(many.getPos()), fewGot = rec.got(few.getPos());
            int chunks = count(manyGot, ClientboundLevelChunkWithLightPacket.class), deltas = deltaPositions(manyGot);
            h.assertTrue(chunks == 1 && deltas == 0,
                    "64 lamps: expected one whole chunk and no deltas, got " + chunks + " chunks and " + deltas + " delta positions");
            int first = indexOf(manyGot, ClientboundLevelChunkWithLightPacket.class);
            for (int i = 0; i < first; i++) {
                h.assertFalse(manyGot.get(i) instanceof ClientboundLightUpdatePacket, "a light update reached the old lamps before the dark chunk");
            }
            clientLight(h, level, many.getPos(), manyClient.lightBefore, rec);
            int fewChunks = count(fewGot, ClientboundLevelChunkWithLightPacket.class), fewDeltas = deltaPositions(fewGot);
            h.assertTrue(fewChunks == 0 && fewDeltas == 3,
                    "3 lamps: expected 3 delta positions only, got " + fewChunks + " chunks and " + fewDeltas + " delta positions");
            manyClient.apply(manyGot);
            fewClient.apply(fewGot);
            manyClient.matches(h, many);
            fewClient.matches(h, few);
        });
    }

    /**
     * Chunks whose light completes together reach each of their two viewers at most {@link ChunkDelivery#PER_TICK} a
     * tick, both in the same tick, the rest on the next ticks: the client handles every packet that arrived before its frame, and a wave crossing the Silo
     * city in view (1 Mt, viewer at 3.9 km) readied up to 105 whole chunks in one tick, about 600 within 3 s; the client's
     * frames went to 0.5–0.7 s for 2 s (passage-burst run 24.11.2026).
     */
    @GameTest(template = "range", timeoutTicks = 200, batch = "nuke_delivery_pace", skyAccess = true)
    public static void readyChunksGoPacedToEachViewer(GameTestHelper h) {
        paced(h, false);
    }

    /** With no budget at all a ready chunk still goes, {@link ChunkDelivery#READY_LIMIT} ticks after its write. */
    @GameTest(template = "range", timeoutTicks = 200, batch = "nuke_delivery_pace_limit", skyAccess = true)
    public static void readyChunkGoesAtItsWaitLimit(GameTestHelper h) {
        int was = ChunkDelivery.perTick(0);
        StrikeGameTests.afterTest(h, () -> ChunkDelivery.perTick(was));
        paced(h, true);
    }

    private static void paced(GameTestHelper h, boolean limitOnly) {
        StrikeGameTests.gameSpeed(h);
        ServerLevel level = h.getLevel();
        List<LevelChunk> chunks = new ArrayList<>();
        for (int x = 0; x < 64; x += 16) for (int z = 0; z < 64; z += 16) {
            LevelChunk c = level.getChunkAt(h.absolutePos(new BlockPos(x, 12, z)));
            if (!chunks.contains(c)) chunks.add(c);
        }
        h.assertTrue(chunks.size() > ChunkDelivery.PER_TICK, "need more chunks than one tick's budget: " + chunks.size());
        List<Recorder> recs = Recorder.attach(h, chunks.stream().map(LevelChunk::getPos).toList(), 2);
        Recorder rec = recs.get(0), other = recs.get(1);
        int y = h.absolutePos(new BlockPos(0, 12, 0)).getY() + 1;
        // the light thread is held while the chunks are written, so all their fences complete at once, as a wave's do
        java.util.concurrent.CountDownLatch light = new java.util.concurrent.CountDownLatch(1);
        StrikeGameTests.afterTest(h, light::countDown);
        h.runAfterDelay(20, () -> {
            rec.started = true;
            other.started = true;
            ChunkPos first = chunks.get(0).getPos();
            level.getChunkSource().getLightEngine().addTask(first.x, first.z,
                    net.minecraft.server.level.ThreadedLevelLightEngine.TaskType.PRE_UPDATE, () -> {
                        try {
                            light.await(20, java.util.concurrent.TimeUnit.SECONDS);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                    });
            for (LevelChunk c : chunks) {
                level.setBlock(c.getPos().getBlockAt(8, y, 8), Blocks.GLOWSTONE.defaultBlockState(), 2);
                ChunkDelivery.wholeAfterLight(level, c, ChunkDelivery.WHOLE_FROM);
            }
            rec.applied = level.getGameTime();
        });
        h.runAfterDelay(25, light::countDown);
        h.succeedWhen(() -> {
            h.assertTrue(rec.applied > 0, "not written yet");
            Map<Long, Integer> perTick = new java.util.TreeMap<>();
            for (LevelChunk c : chunks) {
                h.assertTrue(other.wholeAt.getOrDefault(c.getPos(), List.of()).equals(rec.wholeAt.getOrDefault(c.getPos(), List.of())),
                        "a chunk held by both viewers reached them at different ticks: " + c.getPos());
                List<Long> at = rec.wholeAt.getOrDefault(c.getPos(), List.of());
                h.assertTrue(at.size() <= 1, c.getPos() + " went whole " + at.size() + " times");
                if (at.isEmpty()) {
                    h.assertTrue(level.getGameTime() < rec.applied + ChunkDelivery.READY_LIMIT + 40, c.getPos() + " never went");
                    h.fail("waiting for " + c.getPos());
                }
                perTick.merge(at.get(0), 1, Integer::sum);
            }
            long first = perTick.keySet().iterator().next(), last = ((java.util.TreeMap<Long, Integer>) perTick).lastKey();
            String sent = "sent at ticks after the write " + perTick;
            // the fences complete on the light thread: they may reach the server thread over more than one tick
            h.assertTrue(last - rec.applied <= ChunkDelivery.READY_LIMIT + 10, "too late: " + sent);
            if (limitOnly) {
                h.assertTrue(first - rec.applied >= ChunkDelivery.READY_LIMIT,
                        "with no budget a chunk goes " + ChunkDelivery.READY_LIMIT + " ticks after its write, not before: " + sent);
            } else {
                for (var en : perTick.entrySet()) {
                    h.assertTrue(en.getValue() <= ChunkDelivery.PER_TICK, en.getValue() + " whole chunks in one tick, budget "
                            + ChunkDelivery.PER_TICK + ": " + sent);
                }
                h.assertTrue(perTick.size() >= 2 && first - rec.applied < ChunkDelivery.READY_LIMIT, "not paced: " + sent);
            }
        });
    }

    private static void run(GameTestHelper h, boolean wholeExpected) {
        StrikeGameTests.gameSpeed(h);
        ServerLevel level = h.getLevel();
        // two chunks two apart on the diagonal: no shared light, trunks from one cannot reach the other
        LevelChunk heavy = level.getChunkAt(h.absolutePos(new BlockPos(16, 12, 16)));
        LevelChunk light = level.getChunkAt(h.absolutePos(new BlockPos(48, 12, 48)));
        int y = h.absolutePos(new BlockPos(0, 12, 0)).getY();
        house(level, heavy.getPos(), y);
        // the pane's chunk under stone: the flash burns turf (a few dozen positions), the pane alone stays a few
        for (int x = 0; x < 16; x++) for (int z = 0; z < 16; z++) {
            BlockPos top = light.getPos().getBlockAt(x, light.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.WORLD_SURFACE, x, z), z);
            for (BlockPos p = top; p.getY() > top.getY() - 3; p = p.below()) {
                if (!level.getBlockState(p).isAir()) level.setBlock(p, Blocks.STONE.defaultBlockState(), 2);
            }
        }
        level.setBlock(light.getPos().getBlockAt(8, y, 8), Blocks.GLASS.defaultBlockState(), 2);
        Detonation d = atPsi(h, new BlockPos(-120, 12, 32), true, h.relativePos(heavy.getPos().getBlockAt(8, y + 2, 8)), 10);

        Recorder rec = Recorder.attach(h, List.of(heavy.getPos(), light.getPos()));
        Client heavyClient = new Client(level, heavy), lightClient = new Client(level, light);
        // light settles in the fixture first: the client starts from the server's light before the wave
        h.runAfterDelay(20, () -> {
            heavyClient.lightBefore = Light.of(level, heavy.getPos());
            heavyClient.blocksFrom(heavy);
            lightClient.blocksFrom(light);
            rec.started = true;
            for (LevelChunk c : List.of(heavy, light)) {
                RuinPlan plan = RuinPlanner.plan(level, d, c);
                h.assertTrue(plan.apply(level, c, d, new ColumnScar.Budget(false)), "the new plan is outdated");
            }
            rec.applied = level.getGameTime();
        });
        h.succeedWhen(() -> {
            h.assertTrue(rec.applied > 0 && level.getGameTime() > rec.applied + 40, "waiting for delivery and light");
            List<Object> heavyGot = rec.got(heavy.getPos()), lightGot = rec.got(light.getPos());
            int heavyChunks = count(heavyGot, ClientboundLevelChunkWithLightPacket.class), heavyDeltas = deltaPositions(heavyGot);
            if (wholeExpected) {
                h.assertTrue(heavyChunks == 1 && heavyDeltas == 0,
                        "heavy ruins: expected one whole chunk and no deltas, got " + heavyChunks + " chunks and " + heavyDeltas + " delta positions");
                // no new light on the old blocks: the chunk itself comes first
                int first = indexOf(heavyGot, ClientboundLevelChunkWithLightPacket.class);
                for (int i = 0; i < first; i++) {
                    h.assertFalse(heavyGot.get(i) instanceof ClientboundLightUpdatePacket, "a light update reached the old blocks before the ruined chunk");
                }
                // and its light is the ruins' own, applied over the client's light before the wave
                clientLight(h, level, heavy.getPos(), heavyClient.lightBefore, rec);
            } else {
                h.assertTrue(heavyChunks == 0 && heavyDeltas >= ChunkDelivery.WHOLE_FROM,
                        "forced deltas: got " + heavyChunks + " chunks and " + heavyDeltas + " delta positions");
            }
            int lightChunks = count(lightGot, ClientboundLevelChunkWithLightPacket.class), lightDeltas = deltaPositions(lightGot);
            h.assertTrue(lightChunks == 0 && lightDeltas > 0 && !ChunkDelivery.whole(lightDeltas),
                    "lone pane: expected deltas only, got " + lightChunks + " chunks and " + lightDeltas + " delta positions");
            heavyClient.apply(heavyGot);
            lightClient.apply(lightGot);
            heavyClient.matches(h, heavy);
            lightClient.matches(h, light);
            Incoming.LOG.info("RUIN_DELIVERY whole={} heavy: chunks {}, delta positions {}, changed blocks {}; pane: delta positions {}", wholeExpected,
                    heavyChunks, heavyDeltas, heavyClient.changed, lightDeltas);
        });
    }

    /** Plank house with glass windows (9×9, 5 high) and two oaks with crowns, inside one chunk. */
    private static void house(ServerLevel level, ChunkPos c, int y) {
        for (int x = 1; x <= 9; x++) for (int z = 1; z <= 9; z++) {
            boolean wall = x == 1 || x == 9 || z == 1 || z == 9;
            for (int dy = 0; dy < 5; dy++) {
                if (!wall) continue;
                boolean window = dy >= 1 && dy <= 2 && (x == 5 || z == 5);
                level.setBlock(c.getBlockAt(x, y + dy, z), (window ? Blocks.GLASS : Blocks.OAK_PLANKS).defaultBlockState(), 2);
            }
            level.setBlock(c.getBlockAt(x, y + 5, z), Blocks.OAK_PLANKS.defaultBlockState(), 2);
        }
        for (int[] t : new int[][]{{12, 4}, {12, 12}}) {
            for (int dy = 0; dy < 5; dy++) level.setBlock(c.getBlockAt(t[0], y + dy, t[1]), Blocks.OAK_LOG.defaultBlockState(), 2);
            for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) for (int dy = 4; dy <= 6; dy++) {
                if (dx == 0 && dz == 0 && dy == 4) continue;
                level.setBlock(c.getBlockAt(t[0] + dx, y + dy, t[1] + dz),
                        Blocks.OAK_LEAVES.defaultBlockState().setValue(net.minecraft.world.level.block.LeavesBlock.PERSISTENT, true), 2);
            }
        }
    }

    private static int count(List<Object> got, Class<?> type) {
        int n = 0;
        for (Object o : got) if (type.isInstance(o)) n++;
        return n;
    }

    private static int indexOf(List<Object> got, Class<?> type) {
        for (int i = 0; i < got.size(); i++) if (type.isInstance(got.get(i))) return i;
        return -1;
    }

    private static int deltaPositions(List<Object> got) {
        int n = 0;
        for (Object o : got) {
            if (o instanceof ClientboundBlockUpdatePacket) n++;
            else if (o instanceof ClientboundSectionBlocksUpdatePacket s) {
                int[] k = {0};
                s.runUpdates((p, st) -> k[0]++);
                n += k[0];
            }
        }
        return n;
    }

    /** What a client holding the chunk would have: blocks before the wave, then every packet in order. */
    private static final class Client {
        final ChunkPos pos;
        final int minY, height;
        final BlockState[] blocks;
        Light lightBefore;
        int changed;
        private final ServerLevel level;

        Client(ServerLevel level, LevelChunk c) {
            this.level = level;
            pos = c.getPos();
            minY = c.getMinBuildHeight();
            height = c.getHeight();
            blocks = new BlockState[256 * height];
        }

        private int index(int x, int y, int z) {
            return ((y - minY) * 16 + (z & 15)) * 16 + (x & 15);
        }

        void blocksFrom(LevelChunk c) {
            BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
            for (int y = minY; y < minY + height; y++) for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
                blocks[index(x, y, z)] = c.getBlockState(p.set(pos.getMinBlockX() + x, y, pos.getMinBlockZ() + z));
            }
        }

        void apply(List<Object> got) {
            for (Object o : got) {
                if (o instanceof ClientboundBlockUpdatePacket b) blocks[index(b.getPos().getX(), b.getPos().getY(), b.getPos().getZ())] = b.getBlockState();
                else if (o instanceof ClientboundSectionBlocksUpdatePacket s) s.runUpdates((p, st) -> blocks[index(p.getX(), p.getY(), p.getZ())] = st);
                else if (o instanceof ClientboundLevelChunkWithLightPacket c) {
                    FriendlyByteBuf buf = c.getChunkData().getReadBuffer();
                    for (int i = 0; i < height / 16; i++) {
                        LevelChunkSection s = new LevelChunkSection(level.registryAccess().registryOrThrow(Registries.BIOME));
                        s.read(buf);
                        for (int k = 0; k < 4096; k++) blocks[index(k & 15, minY + i * 16 + (k >> 8), k >> 4 & 15)] = s.getBlockState(k & 15, k >> 8, k >> 4 & 15);
                    }
                }
            }
        }

        void matches(GameTestHelper h, LevelChunk c) {
            BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
            int wrong = 0;
            BlockPos first = null;
            for (int y = minY; y < minY + height; y++) for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
                BlockState server = c.getBlockState(p.set(pos.getMinBlockX() + x, y, pos.getMinBlockZ() + z));
                if (blocks[index(x, y, z)] != server) {
                    wrong++;
                    if (first == null) first = p.immutable();
                }
            }
            h.assertTrue(wrong == 0, "client chunk " + pos + " differs from the server in " + wrong + " positions, first " + first);
        }
    }

    /**
     * Light of a chunk as a client holds it: per section and layer, absent (null), empty or its bytes. A light payload
     * (whole chunk or light update) replaces the sections in its data mask, zeroes those in its empty mask and leaves
     * the rest as the client had them, as vanilla's client does ({@code ClientPacketListener.readSectionList}).
     */
    private static final class Light {
        final Map<String, byte[]> layers;

        private Light(Map<String, byte[]> layers) {
            this.layers = layers;
        }

        static Light of(ServerLevel level, ChunkPos pos) {
            var engine = level.getLightEngine();
            Map<String, byte[]> m = new HashMap<>();
            for (LightLayer layer : LightLayer.values()) {
                for (int s = engine.getMinLightSection(); s < engine.getMaxLightSection(); s++) {
                    DataLayer data = engine.getLayerListener(layer).getDataLayerData(SectionPos.of(pos, s));
                    m.put(layer + "@" + s, data == null ? null : data.isEmpty() ? new byte[0] : data.getData().clone());
                }
            }
            return new Light(m);
        }

        Light copy() {
            return new Light(new HashMap<>(layers));
        }

        /** Apply a payload the client received for this chunk. */
        void apply(ClientboundLightUpdatePacketData d, ServerLevel level) {
            var engine = level.getLightEngine();
            apply(LightLayer.SKY, engine.getMinLightSection(), engine.getMaxLightSection(), d.getSkyYMask(), d.getEmptySkyYMask(), d.getSkyUpdates());
            apply(LightLayer.BLOCK, engine.getMinLightSection(), engine.getMaxLightSection(), d.getBlockYMask(), d.getEmptyBlockYMask(), d.getBlockUpdates());
        }

        private void apply(LightLayer layer, int min, int max, java.util.BitSet mask, java.util.BitSet empty, List<byte[]> data) {
            int next = 0;
            for (int s = min; s < max; s++) {
                int bit = s - min;
                if (mask.get(bit)) layers.put(layer + "@" + s, data.get(next++));
                else if (empty.get(bit)) layers.put(layer + "@" + s, new byte[0]);
            }
        }

        private static boolean zero(byte[] a) {
            for (byte b : a) if (b != 0) return false;
            return true;
        }

        /** Sections that differ: absent against present counts, an empty section equals all zeros. */
        String diff(Light o) {
            List<String> out = new ArrayList<>();
            java.util.Set<String> keys = new java.util.TreeSet<>(layers.keySet());
            keys.addAll(o.layers.keySet());
            for (String k : keys) {
                byte[] a = layers.get(k), b = o.layers.get(k);
                if (a == null && b == null) continue;
                if (a == null || b == null) {
                    out.add(k + (a == null ? " absent here" : " absent there"));
                    continue;
                }
                if (zero(a) && zero(b)) continue;
                if (!Arrays.equals(a, b)) out.add(k);
            }
            return String.join(", ", out);
        }

        boolean same(Light o) {
            return diff(o).isEmpty();
        }
    }

    /**
     * The client's light of the chunk through the packets in order, from its light before the wave: right after the
     * whole chunk it must already be the ruins' light, the server's light once the chunk's light has settled (the
     * fixture chunk is alone: nothing changes its light after its own checks), not something a later light update
     * repairs; and it stays so to the end. A packet without light, or one built before the chunk's light, fails.
     */
    private static void clientLight(GameTestHelper h, ServerLevel level, ChunkPos pos, Light before, Recorder rec) {
        Light client = before.copy(), now = Light.of(level, pos);
        h.assertTrue(!now.same(before), "the ruins changed no light: the test checks nothing");
        int whole = 0;
        for (Object o : rec.got(pos)) {
            if (o instanceof ClientboundLevelChunkWithLightPacket c) {
                client.apply(c.getLightData(), level);
                whole++;
                String diff = client.diff(now);
                h.assertTrue(diff.isEmpty(), "after the whole chunk the client's light is not the ruins' light: " + diff);
            } else if (o instanceof ClientboundLightUpdatePacket l) {
                client.apply(l.getLightData(), level);
            }
        }
        h.assertTrue(whole > 0, "no whole chunk");
        String diff = client.diff(now);
        h.assertTrue(diff.isEmpty(), "the client's light ends unlike the server's: " + diff);
    }

    /** Stands in for every viewer of the chosen chunks: records what vanilla and Incoming send them, in order. */
    private static final class Recorder extends ServerGamePacketListenerImpl {
        final Map<ChunkPos, List<Object>> got = new HashMap<>();
        /** Game time of each whole chunk sent, by chunk. */
        final Map<ChunkPos, List<Long>> wholeAt = new HashMap<>();
        volatile boolean started;
        long applied;

        private Recorder(FakePlayer player, Connection connection) {
            super(player.server, connection, player, CommonListenerCookie.createInitial(player.getGameProfile(), false));
        }

        static Recorder attach(GameTestHelper h, List<ChunkPos> chunks) {
            return attach(h, chunks, 1).get(0);
        }

        /** {@code viewers} recorders that all hold every chosen chunk. */
        static List<Recorder> attach(GameTestHelper h, List<ChunkPos> chunks, int viewers) {
            ServerLevel level = h.getLevel();
            List<Recorder> recs = new ArrayList<>();
            List<net.minecraft.server.level.ServerPlayer> players = new ArrayList<>();
            List<EmbeddedChannel> channels = new ArrayList<>();
            for (int i = 0; i < viewers; i++) {
                FakePlayer player = new FakePlayer(level, new GameProfile(UUID.randomUUID(), "ruin-delivery-" + i));
                Connection connection = new Connection(PacketFlow.SERVERBOUND);
                channels.add(new EmbeddedChannel(connection));
                Recorder rec = new Recorder(player, connection);
                player.connection = rec;
                recs.add(rec);
                players.add(player);
            }
            List<net.minecraft.server.level.ServerPlayer> all = List.copyOf(players);
            Map<ChunkHolder, ChunkHolder.PlayerProvider> old = new HashMap<>();
            for (ChunkPos c : chunks) {
                ChunkHolder holder = level.getChunkSource().chunkMap.getVisibleChunkIfPresent(c.toLong());
                h.assertTrue(holder != null && holder.getTickingChunk() != null, "fixture chunk is not ticking");
                old.put(holder, holder.playerProvider);
                ObfuscationReflectionHelper.setPrivateValue(ChunkHolder.class, holder, (ChunkHolder.PlayerProvider) (pos, border) -> all,
                        "playerProvider");
                for (Recorder rec : recs) rec.got.put(c, new ArrayList<>());
            }
            StrikeGameTests.afterTest(h, () -> {
                old.forEach((holder, provider) -> ObfuscationReflectionHelper.setPrivateValue(ChunkHolder.class, holder, provider, "playerProvider"));
                channels.forEach(EmbeddedChannel::finishAndReleaseAll);
            });
            return recs;
        }

        List<Object> got(ChunkPos c) {
            return got.get(c);
        }

        @Override
        public void send(Packet<?> packet, @Nullable PacketSendListener listener) {
            if (!started) return;
            ChunkPos at = switch (packet) {
                case ClientboundLevelChunkWithLightPacket c -> new ChunkPos(c.getX(), c.getZ());
                case ClientboundLightUpdatePacket l -> new ChunkPos(l.getX(), l.getZ());
                case ClientboundBlockUpdatePacket b -> new ChunkPos(b.getPos());
                case ClientboundSectionBlocksUpdatePacket s -> {
                    BlockPos[] first = {null};
                    s.runUpdates((p, st) -> {
                        if (first[0] == null) first[0] = p.immutable();
                    });
                    yield first[0] == null ? null : new ChunkPos(first[0]);
                }
                default -> null;
            };
            if (at == null) return;
            List<Object> list = got.get(at);
            if (list != null) list.add(packet);
            if (list != null && packet instanceof ClientboundLevelChunkWithLightPacket)
                wholeAt.computeIfAbsent(at, k -> new ArrayList<>()).add(player.serverLevel().getGameTime());
        }
    }
}
