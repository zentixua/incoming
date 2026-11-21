package ua.zentix.incoming.nuclear.world;

import com.mojang.authlib.GameProfile;
import io.netty.channel.embedded.EmbeddedChannel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.Connection;
import net.minecraft.network.PacketSendListener;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ThreadedLevelLightEngine;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.phys.Vec3;
import net.neoforged.fml.util.ObfuscationReflectionHelper;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.event.tick.LevelTickEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import org.jetbrains.annotations.Nullable;
import ua.zentix.incoming.Incoming;
import ua.zentix.incoming.gametest.StrikeGameTests;
import ua.zentix.incoming.nuclear.NuclearWarhead;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

@GameTestHolder(Incoming.MOD_ID)
@PrefixGameTestTemplate(false)
public final class RuinDeliveryRevisionGameTests {
    private RuinDeliveryRevisionGameTests() {}

    @GameTest(template = "range", batch = "nuke_delivery_revision", timeoutTicks = 250, skyAccess = true)
    public static void firstFenceCannotSendSecondWritesWithUnprocessedLight(GameTestHelper h) {
        overlappingWrites(h, false);
    }

    @GameTest(template = "range", batch = "nuke_delivery_revision_expiry", timeoutTicks = 350, skyAccess = true)
    public static void smallWriteJoinsWholeWithoutRenewingLightHold(GameTestHelper h) {
        overlappingWrites(h, true);
    }

    private static void overlappingWrites(GameTestHelper h, boolean smallWrite) {
        StrikeGameTests.gameSpeed(h);
        ServerLevel level = h.getLevel();
        var cache = level.getChunkSource();
        var engine = cache.getLightEngine();
        LevelChunk chunk = level.getChunkAt(h.absolutePos(new BlockPos(32, 30, 32)));
        var pos = chunk.getPos();
        // Separate the two sources by more than block light's maximum range.
        int y = h.absolutePos(new BlockPos(0, 30, 0)).getY();
        BlockPos first = pos.getBlockAt(0, y, 0);
        BlockPos second = pos.getBlockAt(15, y, 15);
        ChunkHolder holder = cache.chunkMap.getVisibleChunkIfPresent(pos.toLong());
        h.assertTrue(holder != null && holder.getTickingChunk() == chunk, "fixture chunk must be ticking");
        List<ClientboundLevelChunkWithLightPacket> packets = watch(h, holder);

        CountDownLatch startLight = new CountDownLatch(1);
        CountDownLatch releaseLight = new CountDownLatch(1);
        StrikeGameTests.afterTest(h, startLight::countDown);
        StrikeGameTests.afterTest(h, releaseLight::countDown);
        long[] firstWrite = {0};
        boolean[] firstFenceDispatched = {false};
        h.runAfterDelay(20, () -> {
            h.assertTrue(level.getBrightness(LightLayer.BLOCK, second) == 0, "second source must start dark");
            // Delay the second write by several game ticks so the two possible hold deadlines differ.
            engine.addTask(pos.x, pos.z, ThreadedLevelLightEngine.TaskType.PRE_UPDATE, () -> await(startLight));
            firstWrite[0] = level.getGameTime();
            level.setBlock(first, Blocks.GLOWSTONE.defaultBlockState(), Block.UPDATE_CLIENTS);
            // Queue the second server write immediately before the first send in the main-thread FIFO.
            engine.waitForPendingTasks(pos.x, pos.z).thenRunAsync(() -> {
                if (smallWrite) applySingleSource(h, chunk, second);
                else {
                    level.setBlock(second, Blocks.GLOWSTONE.defaultBlockState(), Block.UPDATE_CLIENTS);
                    RuinDelivery.wholeAfterLight(level, chunk, RuinDelivery.WHOLE_FROM);
                }
            }, cache.chunkMap.mainThreadExecutor::tell);
            RuinDelivery.wholeAfterLight(level, chunk, RuinDelivery.WHOLE_FROM);
            engine.waitForPendingTasks(pos.x, pos.z)
                    .thenRunAsync(() -> firstFenceDispatched[0] = true, cache.chunkMap.mainThreadExecutor::tell);
            // The newer write's light work cannot run until the test has checked the first send.
            engine.addTask(pos.x, pos.z, ThreadedLevelLightEngine.TaskType.POST_UPDATE, () -> await(releaseLight));
        });
        h.runAfterDelay(30, startLight::countDown);
        h.startSequence()
                .thenWaitUntil(() -> h.assertTrue(firstFenceDispatched[0], "waiting for the first send callback"))
                .thenExecute(() -> {
                    h.assertTrue(level.getBlockState(second).is(Blocks.GLOWSTONE), "second write must be in the pipeline");
                    h.assertTrue(level.getBrightness(LightLayer.BLOCK, first) == 15, "first source must be lit");
                    h.assertTrue(level.getBrightness(LightLayer.BLOCK, second) == 0, "second source must still be dark");
                    if (!packets.isEmpty()) assertSource(h, second, packets.getFirst());
                    h.assertTrue(packets.isEmpty() && RuinDelivery.waiting(level, pos),
                            "only the second fence may send the whole chunk");
                    if (!smallWrite) releaseLight.countDown();
                })
                .thenWaitUntil(() -> {
                    if (smallWrite) h.assertTrue(level.getGameTime() - firstWrite[0] >= 199,
                            "waiting for the original light hold deadline");
                })
                .thenExecute(() -> {
                    if (smallWrite) assertHold(h, holder, y, true);
                })
                .thenExecuteAfter(1, () -> {
                    if (smallWrite) {
                        assertHold(h, holder, y, false);
                        h.assertTrue(packets.isEmpty() && RuinDelivery.waiting(level, pos), "delivery must still wait for light");
                        releaseLight.countDown();
                    }
                })
                .thenWaitUntil(() -> h.assertTrue(!RuinDelivery.waiting(level, pos) && !packets.isEmpty(),
                        "waiting for the latest revision to be delivered"))
                .thenExecute(() -> {
                    h.assertTrue(packets.size() == 1, "expected one coalesced whole chunk, got " + packets.size());
                    assertSource(h, first, packets.getFirst());
                    assertSource(h, second, packets.getFirst());
                    Incoming.LOG.info("RUIN_DELIVERY coalesced {} whole packet with both sources at 15/15{}",
                            packets.size(), smallWrite ? " after the 200-tick hold expired" : "");
                })
                .thenSucceed();
    }

    private static void applySingleSource(GameTestHelper h, LevelChunk chunk, BlockPos pos) {
        ServerLevel level = h.getLevel();
        int section = chunk.getSectionIndex(pos.getY());
        int cell = section << RuinPlan.SECTION_SHIFT | (pos.getY() & 15) << 8 | (pos.getZ() & 15) << 4 | (pos.getX() & 15);
        BlockState old = chunk.getBlockState(pos);
        long[] hashes = new long[chunk.getSectionsCount()];
        hashes[section] = RuinPlan.mix(0, old);
        RuinPlan plan = new RuinPlan(new int[]{cell}, hashes, new BlockState[]{Blocks.GLOWSTONE.defaultBlockState()},
                new BlockState[]{old}, new int[0], new int[0], new long[]{pos.asLong()}, null, null, null, null, null, new long[0], null);
        var d = NuclearWarhead.geometry(level, Vec3.atCenterOf(pos), 1, true, 1);
        h.assertTrue(plan.apply(level, chunk, d, new ColumnScar.Budget(false)), "single-position plan is stale");
    }

    private static void assertHold(GameTestHelper h, ChunkHolder holder, int y, boolean expected) {
        ServerLevel level = h.getLevel();
        holder.sectionLightChanged(LightLayer.BLOCK, SectionPos.blockToSectionCoord(y));
        RuinDelivery.onLevelTickPre(new LevelTickEvent.Pre(() -> true, level));
        h.assertTrue(holder.blockChangedLightSectionFilter.isEmpty() == expected,
                "vanilla light hold must end 200 ticks after the first write");
    }

    private static List<ClientboundLevelChunkWithLightPacket> watch(GameTestHelper h, ChunkHolder holder) {
        ServerLevel level = h.getLevel();
        var pos = holder.getPos();
        List<ClientboundLevelChunkWithLightPacket> packets = new ArrayList<>();
        var player = new FakePlayer(level, new GameProfile(UUID.randomUUID(), "ruin-revision"));
        var connection = new Connection(PacketFlow.SERVERBOUND);
        var channel = new EmbeddedChannel(connection);
        player.connection = new ServerGamePacketListenerImpl(level.getServer(), connection, player,
                CommonListenerCookie.createInitial(player.getGameProfile(), false)) {
            @Override
            public void send(Packet<?> packet, @Nullable PacketSendListener listener) {
                if (packet instanceof ClientboundLevelChunkWithLightPacket whole
                        && whole.getX() == pos.x && whole.getZ() == pos.z) packets.add(whole);
            }
        };
        var provider = holder.playerProvider;
        ObfuscationReflectionHelper.setPrivateValue(ChunkHolder.class, holder,
                (ChunkHolder.PlayerProvider) (p, border) -> List.of(player), "playerProvider");
        StrikeGameTests.afterTest(h, () -> {
            ObfuscationReflectionHelper.setPrivateValue(ChunkHolder.class, holder, provider, "playerProvider");
            channel.finishAndReleaseAll();
        });
        return packets;
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(20, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void assertSource(GameTestHelper h, BlockPos where, ClientboundLevelChunkWithLightPacket packet) {
        ServerLevel level = h.getLevel();
        var buf = packet.getChunkData().getReadBuffer();
        var biomes = level.registryAccess().registryOrThrow(Registries.BIOME);
        LevelChunkSection section = new LevelChunkSection(biomes);
        for (int i = 0; i <= level.getSectionIndex(where.getY()); i++) {
            section.read(buf);
        }
        int x = where.getX() & 15, y = where.getY() & 15, z = where.getZ() & 15;
        h.assertTrue(section.getBlockState(x, y, z).is(Blocks.GLOWSTONE), "whole chunk omitted a glowstone write at " + where);

        var data = packet.getLightData();
        int bit = SectionPos.blockToSectionCoord(where.getY()) - level.getLightEngine().getMinLightSection();
        var mask = data.getBlockYMask();
        int value = mask.get(bit) ? new DataLayer(data.getBlockUpdates().get(mask.get(0, bit).cardinality())).get(x, y, z) : 0;
        h.assertTrue(value == 15, "whole chunk carries glowstone at " + where + " with light " + value + "/15");
    }
}
