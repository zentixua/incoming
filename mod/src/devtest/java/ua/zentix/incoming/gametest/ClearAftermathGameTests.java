package ua.zentix.incoming.gametest;

import com.mojang.authlib.GameProfile;
import io.netty.channel.embedded.EmbeddedChannel;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.Connection;
import net.minecraft.network.PacketSendListener;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import org.jetbrains.annotations.Nullable;
import ua.zentix.incoming.Incoming;
import ua.zentix.incoming.net.S2C;
import ua.zentix.incoming.nuclear.Detonation;
import ua.zentix.incoming.nuclear.NuclearEvents;
import ua.zentix.incoming.nuclear.NuclearStrikes;
import ua.zentix.incoming.strike.ServerActions;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * The guide promises (commands.md, nuke.md "Ending the aftermath", faq.md) that the host's or an operator's
 * {@code /incoming clear} ends a past nuclear detonation — its cloud, smoke, fallout and black rain — for every player,
 * while an ordinary player's clear leaves it. Clients draw all of these from the detonations in {@link S2C.NukeSync}
 * ({@code ClientNuclear.sync}), and the server derives radiation and further ruins from {@link NuclearEvents}; so the
 * check is that a nuclear clear forgets the detonation on the server and sends a sync without it to the dimension's
 * players. Own batch: the clear is world-wide.
 */
@GameTestHolder(Incoming.MOD_ID)
@PrefixGameTestTemplate(false)
public final class ClearAftermathGameTests {
    private static final BlockPos GROUND = new BlockPos(32, 11, 32);

    private ClearAftermathGameTests() {}

    @GameTest(template = "range", timeoutTicks = 20, batch = "clear_aftermath", skyAccess = true)
    public static void hostClearEndsPastDetonationForEveryone(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        StrikeGameTests.afterTest(h, () -> NuclearStrikes.clear(level));
        BlockPos g = h.absolutePos(GROUND);
        // a 1 kt ground burst with fallout; added and cleared within this call, before any world tick works on it
        Detonation d = new Detonation(NuclearGameTests.nextId(level), Vec3.atBottomCenterOf(g), g.getY(), 1, true,
                level.getGameTime(), 0, 3, 20_000, 7, 1, true, Detonation.OLD_WAVE);
        NuclearEvents events = NuclearEvents.get(level);
        events.add(d, level);
        Watcher watcher = Watcher.in(level);
        try {
            ServerActions.clearAll(level.getServer(), false, "check: ordinary clear");
            h.assertTrue(events.detonations().contains(d), "an ordinary clear forgot the nuclear detonation");
            h.assertTrue(watcher.syncs.isEmpty(), "an ordinary clear resent the nuclear state");

            ServerActions.clearAll(level.getServer(), true, "check: host clear");
            h.assertTrue(events.detonations().isEmpty() && events.past().isEmpty(),
                    "host clear left detonations: " + events.detonations().size() + " active, " + events.past().size() + " past");
            h.assertValueEqual(watcher.syncs.size(), 1, "nuclear syncs sent to a player in the dimension");
            h.assertTrue(watcher.syncs.getFirst().detonations().isEmpty(), "the player's client still gets the detonation: "
                    + watcher.syncs.getFirst().detonations());
        } finally {
            watcher.leave();
        }
        h.succeed();
    }

    /**
     * A {@link FakePlayer} in this dimension's player list (what {@code NuclearStrikes.sync(level)} sends to) and the
     * server's, recording {@link S2C.NukeSync} and swallowing other packets; no login, no world presence, removed before
     * the next tick.
     */
    private static final class Watcher extends ServerGamePacketListenerImpl {
        final List<S2C.NukeSync> syncs = new ArrayList<>();
        private final ServerLevel level;
        private final ServerPlayer player;

        private Watcher(ServerLevel level, ServerPlayer player, Connection connection) {
            super(level.getServer(), connection, player, CommonListenerCookie.createInitial(player.getGameProfile(), false));
            this.level = level;
            this.player = player;
        }

        static Watcher in(ServerLevel level) {
            FakePlayer player = new FakePlayer(level, new GameProfile(UUID.randomUUID(), "clear-watcher"));
            Connection connection = new Connection(PacketFlow.SERVERBOUND);
            new EmbeddedChannel(connection);
            Watcher watcher = new Watcher(level, player, connection);
            level.players().add(player);
            return watcher;
        }

        void leave() {
            level.players().remove(player);
        }

        @Override
        public void send(Packet<?> packet, @Nullable PacketSendListener listener) {
            if (packet instanceof ClientboundCustomPayloadPacket custom && custom.payload() instanceof S2C.NukeSync sync) syncs.add(sync);
        }
    }
}
