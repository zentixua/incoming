package ua.zentix.incoming.gametest;

import com.mojang.authlib.GameProfile;
import io.netty.channel.embedded.EmbeddedChannel;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.Connection;
import net.minecraft.network.PacketSendListener;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.game.ClientboundLightUpdatePacket;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.DataLayer;
import net.neoforged.fml.util.ObfuscationReflectionHelper;
import net.neoforged.neoforge.common.util.FakePlayer;
import org.jetbrains.annotations.Nullable;
import ua.zentix.incoming.Incoming;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Only recipient routing is replaced; real ChunkHolder light notifications/broadcasts build the packets. */
public final class LightPacketProbe extends ServerGamePacketListenerImpl {
    private final BlockLightReference received;
    private final int minLightSection;
    private int packets;

    private LightPacketProbe(ServerPlayer player, Connection connection, BlockLightReference received, int minLightSection) {
        super(player.server, connection, player, CommonListenerCookie.createInitial(player.getGameProfile(), false));
        this.received = received;
        this.minLightSection = minLightSection;
    }

    public static LightPacketProbe attach(GameTestHelper h, ChunkPos center) {
        var level = h.getLevel();
        FakePlayer player = new FakePlayer(level, new GameProfile(UUID.randomUUID(), "light-watcher"));
        Connection connection = new Connection(PacketFlow.SERVERBOUND);
        EmbeddedChannel channel = new EmbeddedChannel(connection);
        var probe = new LightPacketProbe(player, connection, BlockLightReference.capture(level, center, 2),
                level.getChunkSource().getLightEngine().getMinLightSection());
        Map<ChunkHolder, ChunkHolder.PlayerProvider> old = new LinkedHashMap<>();
        for (int z = -1; z <= 1; z++) for (int x = -1; x <= 1; x++) {
            ChunkHolder holder = level.getChunkSource().chunkMap.getVisibleChunkIfPresent(ChunkPos.asLong(center.x + x, center.z + z));
            h.assertTrue(holder.getTickingChunk() != null, "packet fixture chunk is not ticking");
            old.put(holder, ObfuscationReflectionHelper.getPrivateValue(ChunkHolder.class, holder, "playerProvider"));
            ObfuscationReflectionHelper.setPrivateValue(ChunkHolder.class, holder, (ChunkHolder.PlayerProvider) (pos, border) -> List.of(player), "playerProvider");
        }
        StrikeGameTests.afterTest(h, () -> {
            old.forEach((holder, provider) -> ObfuscationReflectionHelper.setPrivateValue(ChunkHolder.class, holder, provider, "playerProvider"));
            channel.finishAndReleaseAll();
        });
        return probe;
    }

    @Override public void send(Packet<?> packet, @Nullable PacketSendListener listener) {
        if (!(packet instanceof ClientboundLightUpdatePacket light)) return;
        packets++;
        var tile = received.tiles.get(new ChunkPos(light.getX(), light.getZ()));
        var data = light.getLightData();
        int next = 0;
        for (int bit = 0; bit < received.getSectionsCount() + 2; bit++) {
            DataLayer layer = null;
            if (data.getBlockYMask().get(bit)) layer = new DataLayer(data.getBlockUpdates().get(next++));
            else if (data.getEmptyBlockYMask().get(bit)) layer = new DataLayer();
            int section = minLightSection + bit - received.getMinSection();
            if (layer == null || section < 0 || section >= received.getSectionsCount()) continue;
            for (int i = 0; i < 4096; i++) tile.stored[section * 4096 + i] = (byte) layer.get(i & 15, i >> 8, i >> 4 & 15);
        }
    }

    public void verify(GameTestHelper h, ChunkPos center) {
        var current = BlockLightReference.capture(h.getLevel(), center, 2);
        current.tiles.forEach((pos, tile) -> System.arraycopy(tile.blocks, 0, received.tiles.get(pos).blocks, 0, tile.blocks.length));
        var diff = received.compare(center, 1);
        Incoming.LOG.info("LIGHT_PACKETS packets={} cells={} over={} under={}", packets, diff.cells(), diff.over(), diff.under());
        h.assertTrue(packets > 0 && diff.over() + diff.under() == 0, "ChunkHolder packets do not reproduce correct client block light: " + diff);
    }
}
