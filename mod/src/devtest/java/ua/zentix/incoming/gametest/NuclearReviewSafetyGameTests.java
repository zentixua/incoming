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
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.server.players.PlayerList;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.Vec3;
import net.neoforged.fml.util.ObfuscationReflectionHelper;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import org.jetbrains.annotations.Nullable;
import ua.zentix.incoming.Incoming;
import ua.zentix.incoming.IncomingConfig;
import ua.zentix.incoming.net.C2S;
import ua.zentix.incoming.net.S2C;
import ua.zentix.incoming.nuclear.NuclearEvents;
import ua.zentix.incoming.registry.ModAttachments;
import ua.zentix.incoming.registry.ModItems;
import ua.zentix.incoming.strike.Loadout;
import ua.zentix.incoming.strike.NuclearReview;
import ua.zentix.incoming.strike.ServerActions;
import ua.zentix.incoming.strike.TargetMode;
import ua.zentix.incoming.strike.Waypoints;
import ua.zentix.incoming.strike.WeaponType;
import ua.zentix.incoming.target.Sides;
import ua.zentix.incoming.target.Sightings;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;


/** Real ServerPlayers and captured outgoing payloads: FakePlayer.die is a no-op and cannot test death invalidation. */
@GameTestHolder(Incoming.MOD_ID)
@PrefixGameTestTemplate(false)
public final class NuclearReviewSafetyGameTests {
    private NuclearReviewSafetyGameTests() {}

    private static void give(ServerPlayer p, net.minecraft.world.item.Item item, int count) {
        p.getInventory().add(new net.minecraft.world.item.ItemStack(item, count));
    }

    private static int held(ServerPlayer p, net.minecraft.world.item.Item item) {
        return p.getInventory().countItem(item);
    }

    private static void setup(GameTestHelper h) {
        var c = IncomingConfig.SERVER;
        boolean ops = c.nukeOpsOnly.get(), enabled = c.nukeEnabled.get(), sight = c.sightRules.get();
        int cap = c.nukeMaxYield.get();
        StrikeGameTests.afterTest(h, () -> {
            c.nukeOpsOnly.set(ops); c.nukeEnabled.set(enabled); c.sightRules.set(sight); c.nukeMaxYield.set(cap);
        });
        c.nukeOpsOnly.set(false); c.nukeEnabled.set(true); c.nukeMaxYield.set(15);
    }

    private static C2S.Fire point(GameTestHelper h) {
        Vec3 q = Vec3.atCenterOf(h.absolutePos(new BlockPos(20, 4, 20)));
        return new C2S.Fire(loadout(), Optional.of(new C2S.AimHint(C2S.AimHint.POINT, q, 0, Vec3.ZERO)),
                Optional.empty(), Waypoints.NONE, Optional.empty());
    }

    private static Loadout loadout() {
        return Loadout.DEFAULT.withWeapon(WeaponType.NUKE);
    }

    private static void reviewPlayer(Watcher by, Watcher target) {
        NuclearReview.request(by.player, new C2S.Fire(loadout().withMode(TargetMode.PLAYER).withPlayer(target.player.getGameProfile().getName()),
                Optional.empty(), Optional.empty(), Waypoints.NONE, Optional.empty()));
    }

    private static UUID id(ServerPlayer p) {
        return p.getData(ModAttachments.NUCLEAR_REVIEW).snapshot().orElseThrow().id();
    }

    private static long scheduled(ServerPlayer p) {
        return NuclearEvents.get(p.serverLevel()).scheduled().stream().filter(s -> s.owner().filter(p.getUUID()::equals).isPresent()).count();
    }

    @GameTest(template = "range", batch = "nuclear_review_abort", timeoutTicks = 100, skyAccess = true)
    public static void globalNuclearAbortInvalidatesAnAlreadyHeldReview(GameTestHelper h) {
        setup(h);
        Watcher watcher = Watcher.at(h);
        ServerPlayer p = watcher.player;
        give(p, ModItems.ICBM.get(), 1);
        NuclearReview.request(p, point(h));
        UUID id = id(p);
        h.assertTrue(NuclearReview.act(p, id, C2S.NuclearAction.BEGIN), "hold refused");
        ServerActions.clearAll(p.server, true, "nuclear review abort regression");
        h.assertTrue(watcher.cleared.stream().anyMatch(S2C.Cleared::nuclear), "client did not receive nuclear abort");
        h.assertTrue(p.getData(ModAttachments.NUCLEAR_REVIEW).snapshot().isEmpty(), "global abort retained review");
        h.runAfterDelay(65, () -> {
            h.assertFalse(NuclearReview.act(p, id, C2S.NuclearAction.COMMIT), "old COMMIT survived global nuclear abort");
            h.assertValueEqual(held(p, ModItems.ICBM.get()), 1, "aborted review spent ammunition");
            h.assertValueEqual(scheduled(p), 0L, "aborted review scheduled a strike");
            h.succeed();
        });
    }

    @GameTest(template = "range", batch = "nuclear_review_death", timeoutTicks = 100, skyAccess = true)
    public static void actualDeathWithRetainedInventoryInvalidatesBeforeRespawn(GameTestHelper h) {
        setup(h);
        var level = h.getLevel();
        var keep = level.getGameRules().getRule(GameRules.RULE_KEEPINVENTORY);
        boolean before = keep.get();
        keep.set(true, level.getServer());
        StrikeGameTests.afterTest(h, () -> keep.set(before, level.getServer()));
        Watcher watcher = Watcher.at(h);
        ServerPlayer p = watcher.player;
        give(p, ModItems.ICBM.get(), 1);
        NuclearReview.request(p, point(h));
        UUID id = id(p);
        h.assertTrue(NuclearReview.act(p, id, C2S.NuclearAction.BEGIN), "hold refused");
        p.hurt(level.damageSources().genericKill(), Float.MAX_VALUE);
        h.assertFalse(p.isAlive(), "actual death did not happen");
        h.assertTrue(p.getData(ModAttachments.NUCLEAR_REVIEW).snapshot().isEmpty(), "death handler retained review before respawn");
        h.assertValueEqual(held(p, ModItems.ICBM.get()), 1, "keepInventory did not preserve ammunition");
        h.runAfterDelay(65, () -> {
            h.assertFalse(NuclearReview.act(p, id, C2S.NuclearAction.COMMIT), "dead player committed before respawn");
            watcher.reviews.clear();
            NuclearReview.request(p, point(h));
            h.assertTrue(p.getData(ModAttachments.NUCLEAR_REVIEW).snapshot().isEmpty(), "dead player opened a new review");
            h.assertTrue(watcher.openReviews().isEmpty(), "new review payload sent to dead player");
            h.assertValueEqual(held(p, ModItems.ICBM.get()), 1, "dead player spent retained ammunition");
            h.assertValueEqual(scheduled(p), 0L, "dead player scheduled a strike");
            h.succeed();
        });
    }

    private record Excluded(Watcher viewer, Watcher target, boolean rules, boolean spectator) {}

    @GameTest(template = "range", batch = "nuclear_review_disclosure", timeoutTicks = 100, skyAccess = true)
    public static void unpaidReviewsSendNoCoordinatesForExcludedPlayers(GameTestHelper h) {
        setup(h);
        List<Excluded> cases = new ArrayList<>();
        for (boolean rules : new boolean[] {false, true}) {
            for (boolean spectator : new boolean[] {false, true}) {
                Watcher viewer = Watcher.at(h), target = Watcher.at(h);
                target.player.setPos(target.player.position().add(5, 0, 0));
                IncomingConfig.SERVER.sightRules.set(rules);
                if (rules) {
                    var board = h.getLevel().getScoreboard();
                    var team = board.addPlayerTeam("review_" + UUID.randomUUID().toString().substring(0, 8));
                    board.addPlayerToTeam(viewer.player.getScoreboardName(), team);
                    board.addPlayerToTeam(target.player.getScoreboardName(), team);
                    StrikeGameTests.afterTest(h, () -> board.removePlayerTeam(team));
                }
                h.assertValueEqual(held(viewer.player, ModItems.ICBM.get()), 0, "disclosure test unexpectedly has ammunition");
                reviewPlayer(viewer, target);
                h.assertValueEqual(viewer.openReviews().size(), 1, "visible control target did not produce a review payload");
                cases.add(new Excluded(viewer, target, rules, spectator));
            }
        }
        h.runAfterDelay(5, () -> {
            for (Excluded c : cases) {
                IncomingConfig.SERVER.sightRules.set(c.rules);
                if (c.spectator) c.target.player.setGameMode(GameType.SPECTATOR);
                else c.target.player.setInvisible(true);
                c.viewer.reviews.clear();
                reviewPlayer(c.viewer, c.target);
                h.assertTrue(c.viewer.openReviews().isEmpty(), "live position disclosed: rules=" + c.rules + ", spectator=" + c.spectator);
                h.assertTrue(c.viewer.player.getData(ModAttachments.NUCLEAR_REVIEW).snapshot().isEmpty(), "excluded target retained review");
            }
            h.succeed();
        });
    }

    @GameTest(template = "range", batch = "nuclear_review_last_seen", skyAccess = true)
    public static void unseenHostileReviewUsesOnlyTheRememberedPositionEvenForAFreshContact(GameTestHelper h) {
        setup(h);
        IncomingConfig.SERVER.sightRules.set(true);
        Watcher viewer = Watcher.at(h), target = Watcher.at(h);
        Vec3 seen = target.player.position();
        Sightings.spot(h.getLevel(), Sides.side(viewer.player), target.player, Sightings.Source.EYES);
        target.player.setPos(seen.add(60, 0, 20));
        reviewPlayer(viewer, target);
        h.assertValueEqual(viewer.openReviews().size(), 1, "remembered hostile is not reviewable");
        h.assertTrue(viewer.openReviews().getFirst().target().distanceToSqr(seen.add(0, 1, 0)) < 1e-8, "fresh contact exposed unobserved live movement");
        h.succeed();
    }

    /** Observe real PacketDistributor sends without login/world presence or a real network connection. */
    private static final class Watcher extends ServerGamePacketListenerImpl {
        private final List<S2C.NuclearReview> reviews = new ArrayList<>();
        private final List<S2C.Cleared> cleared = new ArrayList<>();

        private Watcher(ServerPlayer player, Connection connection) {
            super(player.server, connection, player, CommonListenerCookie.createInitial(player.getGameProfile(), false));
        }

        static Watcher at(GameTestHelper h) {
            ServerPlayer player = new ServerPlayer(h.getLevel().getServer(), h.getLevel(),
                    new GameProfile(UUID.randomUUID(), "review_" + UUID.randomUUID().toString().substring(0, 8)), ClientInformation.createDefault());
            player.setPos(Vec3.atCenterOf(h.absolutePos(new BlockPos(16, 4, 16))));
            Connection connection = new Connection(PacketFlow.SERVERBOUND);
            EmbeddedChannel channel = new EmbeddedChannel(connection);
            Watcher watcher = new Watcher(player, connection);
            List<ServerPlayer> players = ObfuscationReflectionHelper.getPrivateValue(PlayerList.class, player.server.getPlayerList(), "players");
            players.add(player);
            StrikeGameTests.afterTest(h, () -> { players.remove(player); channel.finish(); });
            return watcher;
        }

        List<S2C.NuclearReview> openReviews() {
            return reviews.stream().filter(p -> !p.finished()).toList();
        }

        @Override public void send(Packet<?> packet, @Nullable PacketSendListener listener) {
            if (packet instanceof ClientboundCustomPayloadPacket custom) {
                if (custom.payload() instanceof S2C.NuclearReview review) reviews.add(review);
                if (custom.payload() instanceof S2C.Cleared clear) cleared.add(clear);
            }
        }
    }
}
