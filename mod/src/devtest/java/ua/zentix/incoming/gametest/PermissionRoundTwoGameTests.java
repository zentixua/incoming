package ua.zentix.incoming.gametest;

import com.mojang.authlib.GameProfile;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.PlayerList;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.fml.util.ObfuscationReflectionHelper;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import net.neoforged.neoforge.server.permission.PermissionAPI;
import net.neoforged.neoforge.server.permission.handler.IPermissionHandler;
import net.neoforged.neoforge.server.permission.nodes.PermissionDynamicContext;
import net.neoforged.neoforge.server.permission.nodes.PermissionNode;
import ua.zentix.incoming.Incoming;
import ua.zentix.incoming.IncomingConfig;
import ua.zentix.incoming.api.StrikeOrderEvent;
import ua.zentix.incoming.api.StrikeRoundEvent;
import ua.zentix.incoming.launcher.LauncherOrders;
import ua.zentix.incoming.launcher.Mission;
import ua.zentix.incoming.registry.ModItems;
import ua.zentix.incoming.strike.Loadout;
import ua.zentix.incoming.strike.Munitions;
import ua.zentix.incoming.strike.NuclearKeys;
import ua.zentix.incoming.strike.Order;
import ua.zentix.incoming.strike.Orders;
import ua.zentix.incoming.strike.ServerActions;
import ua.zentix.incoming.strike.TargetMode;
import ua.zentix.incoming.strike.Waypoints;
import ua.zentix.incoming.strike.WeaponType;
import ua.zentix.incoming.target.Target;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiPredicate;
import java.util.function.Consumer;

@GameTestHolder(Incoming.MOD_ID)
@PrefixGameTestTemplate(false)
/**
 * Regressions from the B1 review (round 2), each its own batch: a player's command run by a non-player executor asks
 * that player; a deferred {@code nuke now} reads its player again by UUID (still an operator, their nodes now); the
 * console's {@code nuke now} as a player stays automation under the default handler; an offline launcher owner follows
 * the node default, never an answer from another context; nuclear weapons switched off during the warning keep an
 * offline payer's order until they come back for the refund.
 */
public final class PermissionRoundTwoGameTests {
    private PermissionRoundTwoGameTests() {}

    @GameTest(template = "runway", batch = "permission_r2_nonplayer_executor", skyAccess = true)
    public static void playerRemainsAskerWithNonplayerExecutor(GameTestHelper h) throws Exception {
        switches(h);
        var player = new MunitionGameTests.Listener(h.getLevel(), "TesterSource", 2);
        Vec3 pos = aim(h).point();
        var executor = new ArmorStand(h.getLevel(), pos.x, pos.y, pos.z);
        setHandler(h, (p, node) -> node.equals("incoming.launch.rocket"));
        var seen = requests(h);
        String order = "incoming salvo grad 1 0 at " + at(h) + " tag ";
        run(h, player.createCommandSourceStack(), order + "r2_direct");
        run(h, player.createCommandSourceStack().withEntity(executor), order + "r2_player_as_stand");
        run(h, h.getLevel().getServer().createCommandSourceStack().withEntity(executor), order + "r2_console_as_stand");
        h.assertValueEqual(seen, List.of("r2_console_as_stand"), "admission tags under the Grad deny");
        h.succeed();
    }

    @GameTest(template = "runway", batch = "permission_r2_deferred_now_deop", skyAccess = true)
    public static void deferredInstantCommandChecksLiveOperatorRequirement(GameTestHelper h) throws Exception {
        switches(h);
        class Operator extends FakePlayer {
            int level = 2;

            Operator() {
                super(h.getLevel(), new GameProfile(UUID.randomUUID(), "TesterInstant"));
            }

            @Override
            protected int getPermissionLevel() {
                return level;
            }
        }
        var op = new Operator();
        var seen = requests(h);
        String instant = "incoming nuke now at " + at(h) + " 1 tag ";
        run(h, op.createCommandSourceStack(), instant + "r2_direct_now");
        Consumer<StrikeOrderEvent.Request> nested = event -> {
            if (!"r2_enqueue".equals(event.getTag())) return;
            try {
                run(h, op.createCommandSourceStack(), instant + "r2_deferred_now");
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
            op.level = 0;
        };
        NeoForge.EVENT_BUS.addListener(EventPriority.HIGH, nested);
        StrikeGameTests.afterTest(h, () -> NeoForge.EVENT_BUS.unregister(nested));

        CommandSourceStack console = h.getLevel().getServer().createCommandSourceStack();
        run(h, console, "incoming salvo drone 1 0 at " + at(h) + " tag r2_enqueue");
        h.assertValueEqual(seen, List.of("r2_direct_now", "r2_enqueue"), "admission tags before replay");
        h.assertFalse(op.hasPermissions(2), "operator was not deopped");
        h.assertTrue(h.getLevel().getServer().getCommands().getDispatcher()
                .parse(instant + "r2_deopped_now", op.createCommandSourceStack()).getReader().canRead(),
                "fresh level-0 instant command was parseable");
        h.runAfterDelay(3, () -> {
            h.assertValueEqual(seen, List.of("r2_direct_now", "r2_enqueue"), "admission tags after deop and replay");
            h.succeed();
        });
    }

    @GameTest(template = "runway", batch = "permission_r2_deferred_now_replacement", skyAccess = true)
    public static void deferredInstantCommandQueriesReplacementPlayer(GameTestHelper h) throws Exception {
        switches(h);
        GameProfile profile = new GameProfile(UUID.randomUUID(), "TesterReplaced");
        class Operator extends FakePlayer {
            Operator() {
                super(h.getLevel(), profile);
            }

            @Override
            protected int getPermissionLevel() {
                return 2;
            }
        }
        var original = new Operator();
        var replacement = new Operator();
        setHandler(h, (player, node) -> player == replacement && node.equals("incoming.nuclear"));
        Map<UUID, ServerPlayer> players = ObfuscationReflectionHelper.getPrivateValue(
                PlayerList.class, h.getLevel().getServer().getPlayerList(), "playersByUUID");
        players.put(profile.getId(), original);
        StrikeGameTests.afterTest(h, () -> players.remove(profile.getId()));

        var seen = requests(h);
        String instant = "incoming nuke now at " + at(h) + " 1 tag ";
        Consumer<StrikeOrderEvent.Request> nested = event -> {
            if (!"r2_replace_enqueue".equals(event.getTag())) return;
            try {
                run(h, original.createCommandSourceStack(), instant + "r2_replaced_now");
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
            original.discard();
            players.put(profile.getId(), replacement);
        };
        NeoForge.EVENT_BUS.addListener(EventPriority.HIGH, nested);
        StrikeGameTests.afterTest(h, () -> NeoForge.EVENT_BUS.unregister(nested));

        CommandSourceStack console = h.getLevel().getServer().createCommandSourceStack();
        run(h, console, "incoming salvo drone 1 0 at " + at(h) + " tag r2_replace_enqueue");
        h.assertTrue(original.isRemoved() && players.get(profile.getId()) == replacement, "player replacement fixture");
        run(h, replacement.createCommandSourceStack(), instant + "r2_replacement_direct");
        h.assertValueEqual(seen, List.of("r2_replace_enqueue"), "tags before deferred admission");
        h.runAfterDelay(3, () -> {
            h.assertValueEqual(seen, List.of("r2_replace_enqueue"), "tags after live replacement denied the node");
            h.succeed();
        });
    }

    @GameTest(template = "runway", batch = "permission_r2_now_default", skyAccess = true)
    public static void defaultKeepsConsoleInstantCommandAsPlayer(GameTestHelper h) throws Exception {
        switches(h);
        IncomingConfig.SERVER.nukeOpsOnly.set(true);
        var guest = new MunitionGameTests.Listener(h.getLevel(), "TesterGuest", 0);
        var seen = requests(h);
        var asGuest = h.getLevel().getServer().createCommandSourceStack().withEntity(guest);
        run(h, asGuest, "incoming nuke at " + at(h) + " 1 tag r2_console_flying");
        run(h, asGuest, "incoming nuke now at " + at(h) + " 1 tag r2_console_instant");
        h.assertValueEqual(seen, List.of("r2_console_instant"), "default approvals with ops_only");
        h.succeed();
    }

    @GameTest(template = "runway", batch = "permission_r2_offline_denial", skyAccess = true)
    public static void offlineLauncherFallsBackFromContextualLogoutDenial(GameTestHelper h) {
        switches(h);
        var world = h.getLevel();
        var nether = world.getServer().getLevel(Level.NETHER);
        h.assertTrue(nether != null && world.dimension().equals(Level.OVERWORLD), "Overworld and Nether required");
        var owner = new MunitionGameTests.Listener(nether, "TesterNether", 0);
        setHandler(h, (p, node) -> p.getUUID().equals(owner.getUUID()) && p.level() == nether
                && node.equals("incoming.launch.rocket"));

        var pad = FixedLauncherGameTests.launcher(h, new BlockPos(16, 4, 8), owner.getUUID());
        var target = aim(h);
        pad.setMission(new Mission(WeaponType.ROCKET, 1, 0, target.target(), target.point(), Waypoints.NONE));
        FixedLauncherGameTests.load(h, pad, new ItemStack(ModItems.GRAD_ROCKETS.get()));
        List<StrikeOrderEvent.Request> seen = new ArrayList<>();
        Consumer<StrikeOrderEvent.Request> listener = event -> {
            if (!owner.getUUID().equals(event.getOwnerId())) return;
            seen.add(event);
            event.setCanceled(true);
        };
        NeoForge.EVENT_BUS.addListener(listener);
        StrikeGameTests.afterTest(h, () -> NeoForge.EVENT_BUS.unregister(listener));

        h.assertTrue(world.getServer().getPlayerList().getPlayer(owner.getUUID()) == null, "owner must be offline");
        var before = LauncherOrders.fire(world, pad);
        h.assertValueEqual(seen.size(), 1, "admissions without any cached offline answer: " + before);
        NeoForge.EVENT_BUS.post(new PlayerEvent.PlayerLoggedOutEvent(owner));
        var after = LauncherOrders.fire(world, pad);
        h.assertValueEqual(seen.size() - 1, 1, "additional admissions after Nether-only logout refusal: " + after);
        h.succeed();
    }

    @GameTest(template = "runway", batch = "permission_r2_offline_warning", timeoutTicks = 1900, skyAccess = true)
    public static void disablingAfterWarningReturnsPaidMunitionsToOfflineOwner(GameTestHelper h) {
        switches(h);
        var owner = new MunitionGameTests.Listener(h.getLevel(), "TesterPaid", 0);
        Vec3 beside = Vec3.atBottomCenterOf(h.absolutePos(new BlockPos(24, 6, 16)));
        owner.moveTo(beside);
        var peer = new MunitionGameTests.Listener(h.getLevel(), "TesterKey", 0);
        peer.moveTo(beside);
        var online = List.of(owner, peer);
        var loadout = new Loadout(WeaponType.MISSILE, 1, 0, TargetMode.LOOK, "", new Loadout.Nuke(1, false, true));
        var bill = Munitions.Bill.of(loadout);
        var missile = WeaponType.MISSILE.spec().munition().item().get();
        var warhead = Munitions.warhead();
        MunitionGameTests.give(owner, missile, 1);
        MunitionGameTests.give(owner, warhead, 1);
        List<StrikeRoundEvent.Ended> endings = new ArrayList<>();
        List<StrikeRoundEvent.Released> releases = new ArrayList<>();
        Consumer<StrikeRoundEvent.Ended> ended = event -> {
            if (owner.getUUID().equals(event.getOwnerId())) endings.add(event);
        };
        Consumer<StrikeRoundEvent.Released> released = event -> {
            if (owner.getUUID().equals(event.getOwnerId())) releases.add(event);
        };
        NeoForge.EVENT_BUS.addListener(ended);
        NeoForge.EVENT_BUS.addListener(released);
        StrikeGameTests.afterTest(h, () -> {
            NeoForge.EVENT_BUS.unregister(ended);
            NeoForge.EVENT_BUS.unregister(released);
        });
        var strike = Order.player(owner, loadout, aim(h), StrikeOrderEvent.Payment.UPFRONT);
        h.assertTrue(Orders.admit(strike, order -> {
            h.assertTrue(Munitions.pay(owner, bill), "fixture payment failed");
            Orders.carrierPending(order);
            order.accept(1);
            return NuclearKeys.order(owner, online, loadout, aim(h), Waypoints.NONE, null, bill, order.round(0));
        }), "carrier must enter the key wait");
        h.assertTrue(NuclearKeys.confirm(peer, online), "carrier must arm");
        NeoForge.EVENT_BUS.post(new PlayerEvent.PlayerLoggedOutEvent(owner));
        owner.discard();
        h.assertTrue(owner.isRemoved(), "offline owner's old object remained live");
        h.assertValueEqual(NuclearKeys.get(h.getLevel()).pending(), 1, "armed warning stays queued on logout");
        IncomingConfig.SERVER.nukeEnabled.set(false);

        // an offline inventory takes nothing back: the order waits for its owner, then refunds once
        h.runAfterDelay(NuclearKeys.MIN_WARNING + 3, () -> {
            h.assertValueEqual(releases.size(), 0, "releases with nuclear disabled");
            h.assertValueEqual(endings.size(), 0, "ended while its payer is offline");
            h.assertValueEqual(NuclearKeys.get(h.getLevel()).pending(), 1, "the paid order no longer waits for its payer");
            FakePlayer back = new FakePlayer(h.getLevel(), owner.getGameProfile());
            back.moveTo(beside);
            var list = h.getLevel().getServer().getPlayerList();
            List<ServerPlayer> players = net.neoforged.fml.util.ObfuscationReflectionHelper.getPrivateValue(
                    net.minecraft.server.players.PlayerList.class, list, "players");
            java.util.Map<java.util.UUID, ServerPlayer> byId = net.neoforged.fml.util.ObfuscationReflectionHelper.getPrivateValue(
                    net.minecraft.server.players.PlayerList.class, list, "playersByUUID");
            players.add(back);
            byId.put(back.getUUID(), back);
            StrikeGameTests.afterTest(h, () -> {
                players.remove(back);
                byId.remove(back.getUUID());
            });
            h.runAfterDelay(3, () -> {
                h.assertValueEqual(releases.size(), 0, "released after the payer came back");
                h.assertValueEqual(endings.size(), 1, "terminal outcomes");
                h.assertValueEqual(endings.getFirst().getOutcome(), StrikeRoundEvent.Outcome.RECALLED, "terminal outcome");
                h.assertTrue(endings.getFirst().isRefunded(), "not refunded");
                h.assertValueEqual(NuclearKeys.get(h.getLevel()).pending(), 0, "order still waits");
                h.assertTrue(back.getInventory().countItem(missile) == 1 && back.getInventory().countItem(warhead) == 1,
                        "munitions not returned: " + back.getInventory().countItem(missile) + "/" + back.getInventory().countItem(warhead));
                h.succeed();
            });
        });
    }

    private static void run(GameTestHelper h, CommandSourceStack source, String command) throws Exception {
        h.getLevel().getServer().getCommands().getDispatcher().execute(command, source.withSuppressedOutput());
    }

    private static List<String> requests(GameTestHelper h) {
        var seen = new ArrayList<String>();
        Consumer<StrikeOrderEvent.Request> listener = event -> {
            if (event.getTag() != null && event.getTag().startsWith("r2_")) {
                seen.add(event.getTag());
                event.setCanceled(true);
            }
        };
        NeoForge.EVENT_BUS.addListener(listener);
        StrikeGameTests.afterTest(h, () -> NeoForge.EVENT_BUS.unregister(listener));
        return seen;
    }

    private static ServerActions.Aim aim(GameTestHelper h) {
        Vec3 pos = Vec3.atBottomCenterOf(h.absolutePos(new BlockPos(24, 12, 45)));
        return new ServerActions.Aim(new Target.Point(pos), pos, null);
    }

    private static String at(GameTestHelper h) {
        Vec3 pos = aim(h).point();
        return String.format(Locale.ROOT, "%.1f %.1f %.1f", pos.x, pos.y, pos.z);
    }

    private static void switches(GameTestHelper h) {
        var config = IncomingConfig.SERVER;
        boolean everyone = config.designatorForEveryone.get();
        boolean nuclear = config.nukeEnabled.get();
        boolean opsOnly = config.nukeOpsOnly.get();
        boolean carrier = config.carrierNukes.get();
        config.designatorForEveryone.set(true);
        config.nukeEnabled.set(true);
        config.nukeOpsOnly.set(false);
        config.carrierNukes.set(true);
        StrikeGameTests.afterTest(h, () -> {
            config.designatorForEveryone.set(everyone);
            config.nukeEnabled.set(nuclear);
            config.nukeOpsOnly.set(opsOnly);
            config.carrierNukes.set(carrier);
            NuclearKeys.cancelAll(h.getLevel().getServer(), List.of(), null, "review fixture cleanup");
        });
    }

    private static void setHandler(GameTestHelper h, BiPredicate<ServerPlayer, String> deny) {
        IPermissionHandler base = ObfuscationReflectionHelper.getPrivateValue(PermissionAPI.class, null, "activeHandler");
        IPermissionHandler replacement = new IPermissionHandler() {
            @Override
            public ResourceLocation getIdentifier() {
                return Incoming.id("r2_memory_free_handler");
            }

            @Override
            public Set<PermissionNode<?>> getRegisteredNodes() {
                return base.getRegisteredNodes();
            }

            @Override
            @SuppressWarnings("unchecked")
            public <T> T getPermission(ServerPlayer player, PermissionNode<T> node, PermissionDynamicContext<?>... contexts) {
                return deny.test(player, node.getNodeName()) ? (T) Boolean.FALSE : base.getPermission(player, node, contexts);
            }

            @Override
            public <T> T getOfflinePermission(UUID player, PermissionNode<T> node, PermissionDynamicContext<?>... contexts) {
                throw new AssertionError("Unexpected call into handler offline API for " + node.getNodeName());
            }
        };
        ObfuscationReflectionHelper.setPrivateValue(PermissionAPI.class, null, replacement, "activeHandler");
        StrikeGameTests.afterTest(h, () -> ObfuscationReflectionHelper.setPrivateValue(PermissionAPI.class, null, base, "activeHandler"));
    }
}
