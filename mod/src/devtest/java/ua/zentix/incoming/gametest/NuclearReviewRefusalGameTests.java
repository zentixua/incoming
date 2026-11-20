package ua.zentix.incoming.gametest;

import com.mojang.authlib.GameProfile;
import io.netty.channel.embedded.EmbeddedChannel;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.Connection;
import net.minecraft.network.PacketSendListener;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import ua.zentix.incoming.Incoming;
import ua.zentix.incoming.IncomingConfig;
import ua.zentix.incoming.net.C2S;
import ua.zentix.incoming.registry.ModAttachments;
import ua.zentix.incoming.strike.Loadout;
import ua.zentix.incoming.strike.NuclearReview;
import ua.zentix.incoming.strike.ServerActions;
import ua.zentix.incoming.strike.TargetMode;
import ua.zentix.incoming.strike.Waypoints;
import ua.zentix.incoming.strike.WeaponType;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * A nuclear review refuses what the launch after the hold would refuse, and each refusal reaches the action bar
 * alone: a launch origin out of map range is refused before the review opens; an aim that went out of range during
 * the hold keeps its own reason, not the generic "review changed" line over it.
 */
@GameTestHolder(Incoming.MOD_ID)
@PrefixGameTestTemplate(false)
public final class NuclearReviewRefusalGameTests {
    private static final int RANGE = 256;
    private static final Loadout ICBM = Loadout.DEFAULT.withWeapon(WeaponType.NUKE).withMode(TargetMode.MAP);

    @GameTest(template = "range", batch = "nuclear_review_origin_site", timeoutTicks = 100, skyAccess = true)
    public static void outOfRangeOriginIsRefusedBeforeReview(GameTestHelper h) {
        configure(h);
        Watcher watcher = Watcher.at(h, true);
        ServerPlayer p = watcher.player;
        Vec3 origin = p.position().add(RANGE + 1, 0, 0).multiply(1, 0, 1);
        Vec3 target = p.position().add(16, 0, 0);
        h.assertTrue(ServerActions.launchSiteProblem(p, origin) != null, "fixture origin is legal");
        NuclearReview.request(p, order(new C2S.AimHint(C2S.AimHint.POINT, target, 0, Vec3.ZERO), Optional.of(origin)));
        boolean opened = p.getData(ModAttachments.NUCLEAR_REVIEW).snapshot().isPresent();
        h.assertTrue(!opened && watcher.actionBars.equals(List.of("incoming.map.launch.out_of_range")),
                "expected origin refusal with no review, got opened=" + opened + ", action bars=" + watcher.actionBars);
        h.succeed();
    }

    @GameTest(template = "range", batch = "nuclear_review_commit_refusal", timeoutTicks = 100, skyAccess = true)
    public static void commitKeepsTheSpecificRangeRefusal(GameTestHelper h) {
        configure(h);
        Watcher watcher = Watcher.at(h, false);
        ServerPlayer p = watcher.player;
        Vec3 target = p.position().add(16, 0, 0);
        NuclearReview.request(p, order(new C2S.AimHint(C2S.AimHint.POINT, target, 0, Vec3.ZERO), Optional.empty()));
        UUID review = p.getData(ModAttachments.NUCLEAR_REVIEW).snapshot().orElseThrow().id();
        h.assertTrue(NuclearReview.act(p, review, C2S.NuclearAction.BEGIN), "fixture could not start its hold");
        p.setPos(target.add(RANGE + 1, 0, 0));
        h.runAfterDelay(NuclearReview.HOLD_TICKS + 1, () -> {
            h.assertFalse(NuclearReview.act(p, review, C2S.NuclearAction.COMMIT), "out-of-range aim committed");
            h.assertTrue(watcher.actionBars.equals(List.of("incoming.map.out_of_range")),
                    "expected a single actionable refusal, got " + watcher.actionBars);
            h.succeed();
        });
    }

    private static C2S.Fire order(C2S.AimHint target, Optional<Vec3> from) {
        return new C2S.Fire(ICBM, Optional.of(target), Optional.empty(), Waypoints.NONE, from);
    }

    private static void configure(GameTestHelper h) {
        var c = IncomingConfig.SERVER;
        boolean enabled = c.nukeEnabled.get(), only = c.nukeOpsOnly.get(), designator = c.designatorForEveryone.get();
        int cap = c.nukeMaxYield.get(), range = c.mapRange.get();
        StrikeGameTests.afterTest(h, () -> {
            c.nukeEnabled.set(enabled);
            c.nukeOpsOnly.set(only);
            c.designatorForEveryone.set(designator);
            c.nukeMaxYield.set(cap);
            c.mapRange.set(range);
        });
        c.nukeEnabled.set(true);
        c.nukeOpsOnly.set(false);
        c.designatorForEveryone.set(true);
        c.nukeMaxYield.set(15);
        c.mapRange.set(RANGE);
    }

    private static final class Watcher extends ServerGamePacketListenerImpl {
        private final List<String> actionBars = new ArrayList<>();

        private Watcher(ServerPlayer player, Connection connection) {
            super(player.server, connection, player, CommonListenerCookie.createInitial(player.getGameProfile(), false));
        }

        static Watcher at(GameTestHelper h, boolean operator) {
            ServerPlayer p = new ServerPlayer(h.getLevel().getServer(), h.getLevel(),
                    new GameProfile(UUID.randomUUID(), "review_probe"), ClientInformation.createDefault()) {
                @Override public boolean hasPermissions(int permission) {
                    return operator && permission <= 2;
                }
            };
            p.setPos(Vec3.atCenterOf(h.absolutePos(new BlockPos(16, 4, 16))));
            Connection connection = new Connection(PacketFlow.SERVERBOUND);
            EmbeddedChannel channel = new EmbeddedChannel(connection);
            StrikeGameTests.afterTest(h, channel::finish);
            return new Watcher(p, connection);
        }

        @Override public void send(Packet<?> packet, PacketSendListener listener) {
            if (packet instanceof ClientboundSystemChatPacket actionBar && actionBar.overlay()
                    && actionBar.content().getContents() instanceof TranslatableContents text) {
                actionBars.add(text.getKey());
            }
        }
    }
}
