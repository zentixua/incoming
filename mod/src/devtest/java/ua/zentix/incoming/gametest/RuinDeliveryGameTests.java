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
import ua.zentix.incoming.nuclear.world.RuinDelivery;
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
 * How ruins reach players holding the chunk ({@link RuinDelivery}): a heavily ruined chunk as one whole-chunk packet
 * built after its light, a lightly ruined one as section deltas. A recorder in place of the chunk's viewers (its
 * {@code ChunkHolder} player provider, the one vanilla's own broadcast uses) rebuilds what a client would hold from the
 * blocks before the wave and the packets in order; it must equal the server's chunk in every position, either way.
 */
@GameTestHolder(Incoming.MOD_ID)
@PrefixGameTestTemplate(false)
public final class RuinDeliveryGameTests {
    private RuinDeliveryGameTests() {}

    /** Heavy chunk (house, glass, trees) goes whole after its light; a lone pane in a distant chunk goes as deltas. */
    @GameTest(template = "range", timeoutTicks = 400, batch = "nuke_delivery", skyAccess = true)
    public static void heavyRuinsGoWholeAfterTheirLight(GameTestHelper h) {
        run(h, true);
    }

    /** The same heavy ruins forced through section deltas give the client the same chunk. */
    @GameTest(template = "range", timeoutTicks = 400, batch = "nuke_delivery_deltas", skyAccess = true)
    public static void heavyRuinsAsDeltasGiveTheSameChunk(GameTestHelper h) {
        int was = RuinDelivery.wholeFrom(Integer.MAX_VALUE);
        StrikeGameTests.afterTest(h, () -> RuinDelivery.wholeFrom(was));
        run(h, false);
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
                // and its light is the ruins' own (the server's light after the wave), not the light before
                Light sent = Light.of((ClientboundLevelChunkWithLightPacket) heavyGot.get(first), level), now = Light.of(level, heavy.getPos());
                h.assertTrue(!now.equals(heavyClient.lightBefore), "the ruins changed no light: the test checks nothing");
                h.assertTrue(sent.equals(now), "the whole chunk carries stale light: " + sent.diff(now));
            } else {
                h.assertTrue(heavyChunks == 0 && heavyDeltas >= RuinDelivery.WHOLE_FROM,
                        "forced deltas: got " + heavyChunks + " chunks and " + heavyDeltas + " delta positions");
            }
            int lightChunks = count(lightGot, ClientboundLevelChunkWithLightPacket.class), lightDeltas = deltaPositions(lightGot);
            h.assertTrue(lightChunks == 0 && lightDeltas > 0 && !RuinDelivery.whole(lightDeltas),
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

    /** Light of a chunk as a client holds it: per section and layer, absent, empty or its bytes. */
    private record Light(Map<String, byte[]> layers) {
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

        static Light of(ClientboundLevelChunkWithLightPacket p, ServerLevel level) {
            var engine = level.getLightEngine();
            ClientboundLightUpdatePacketData d = p.getLightData();
            Map<String, byte[]> m = new HashMap<>();
            read(m, LightLayer.SKY, engine.getMinLightSection(), engine.getMaxLightSection(), d.getSkyYMask(), d.getEmptySkyYMask(), d.getSkyUpdates());
            read(m, LightLayer.BLOCK, engine.getMinLightSection(), engine.getMaxLightSection(), d.getBlockYMask(), d.getEmptyBlockYMask(), d.getBlockUpdates());
            return new Light(m);
        }

        private static void read(Map<String, byte[]> m, LightLayer layer, int min, int max, java.util.BitSet mask, java.util.BitSet empty, List<byte[]> data) {
            int next = 0;
            for (int s = min; s < max; s++) {
                int bit = s - min;
                m.put(layer + "@" + s, mask.get(bit) ? data.get(next++) : empty.get(bit) ? new byte[0] : null);
            }
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof Light l && diff(l).isEmpty();
        }

        @Override
        public int hashCode() {
            return layers.keySet().hashCode();
        }

        String diff(Light o) {
            List<String> out = new ArrayList<>();
            for (var e : layers.entrySet()) {
                byte[] a = e.getValue(), b = o.layers.get(e.getKey());
                // a section the packet leaves out keeps the client's copy: only compare sections both have
                if (a == null || b == null) continue;
                if (!Arrays.equals(a, b)) out.add(e.getKey());
            }
            return String.join(", ", out);
        }
    }

    /** Stands in for every viewer of the chosen chunks: records what vanilla and Incoming send them, in order. */
    private static final class Recorder extends ServerGamePacketListenerImpl {
        final Map<ChunkPos, List<Object>> got = new HashMap<>();
        volatile boolean started;
        long applied;

        private Recorder(FakePlayer player, Connection connection) {
            super(player.server, connection, player, CommonListenerCookie.createInitial(player.getGameProfile(), false));
        }

        static Recorder attach(GameTestHelper h, List<ChunkPos> chunks) {
            ServerLevel level = h.getLevel();
            FakePlayer player = new FakePlayer(level, new GameProfile(UUID.randomUUID(), "ruin-delivery"));
            Connection connection = new Connection(PacketFlow.SERVERBOUND);
            EmbeddedChannel channel = new EmbeddedChannel(connection);
            Recorder rec = new Recorder(player, connection);
            player.connection = rec;
            Map<ChunkHolder, ChunkHolder.PlayerProvider> old = new HashMap<>();
            for (ChunkPos c : chunks) {
                ChunkHolder holder = level.getChunkSource().chunkMap.getVisibleChunkIfPresent(c.toLong());
                h.assertTrue(holder != null && holder.getTickingChunk() != null, "fixture chunk is not ticking");
                old.put(holder, holder.playerProvider);
                ObfuscationReflectionHelper.setPrivateValue(ChunkHolder.class, holder, (ChunkHolder.PlayerProvider) (pos, border) -> List.of(player),
                        "playerProvider");
                rec.got.put(c, new ArrayList<>());
            }
            StrikeGameTests.afterTest(h, () -> {
                old.forEach((holder, provider) -> ObfuscationReflectionHelper.setPrivateValue(ChunkHolder.class, holder, provider, "playerProvider"));
                channel.finishAndReleaseAll();
            });
            return rec;
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
        }
    }
}
