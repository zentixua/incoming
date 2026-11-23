package ua.zentix.incoming.gametest;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.neoforge.common.util.FakePlayer;
import com.mojang.authlib.GameProfile;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import net.neoforged.fml.util.ObfuscationReflectionHelper;
import net.neoforged.neoforge.common.NeoForge;
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
import ua.zentix.incoming.strike.NuclearKeys;
import ua.zentix.incoming.strike.ServerActions;
import ua.zentix.incoming.strike.TargetMode;
import ua.zentix.incoming.strike.Waypoints;
import ua.zentix.incoming.strike.WeaponType;
import ua.zentix.incoming.target.Target;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.BiPredicate;
import java.util.function.Consumer;

@GameTestHolder(Incoming.MOD_ID)
@PrefixGameTestTemplate(false)
/**
 * Regressions from the B1 review (round 1), each its own batch (the handler and the switches are server-wide): the
 * player who typed a command is asked also through {@code /execute as} and after a deferral that changed their level;
 * the console's {@code nuke now} as a player stays automation while the flying nuke asks that player; the second key
 * keeps its no-order line, keeps a paid carrier when carrier settings change, and nothing leaves after nuclear weapons
 * are switched off during the warning; a handler without Incoming's nodes breaks neither the command tree nor logout; no
 * handler at all answers the switches; an answer kept at logout never grants an offline owner more than the default.
 */
public final class PermissionRegressionGameTests {
    private PermissionRegressionGameTests() {}

    @GameTest(template = "runway", batch = "permission_r_op_execute_as", skyAccess = true)
    public static void playerSourceSurvivesExecuteAs(GameTestHelper h) throws Exception {
        switches(h);
        var op = player(h, "ReviewOp", 2);
        var guest = player(h, "ReviewGuest", 0);
        handler(h, (player, node) -> node.equals("incoming.launch.rocket"), false);
        var seen = requests(h);
        MinecraftServer server = h.getLevel().getServer();

        run(server, op.createCommandSourceStack(), "incoming salvo grad 1 0 at " + at(h) + " tag review_direct");
        // ExecuteCommand's "as" redirect uses this transformation; the original CommandSource remains the player.
        run(server, op.createCommandSourceStack().withEntity(guest), "incoming salvo grad 1 0 at " + at(h) + " tag review_player_as");
        run(server, server.createCommandSourceStack().withEntity(guest), "incoming salvo grad 1 0 at " + at(h) + " tag review_console_as");

        h.assertValueEqual(seen, List.of("review_console_as"), "admissions under the Grad deny");
        h.succeed();
    }

    @GameTest(template = "runway", batch = "permission_r_deferred_deop", skyAccess = true)
    public static void deferredPlayerDoesNotBecomeAutomation(GameTestHelper h) throws Exception {
        switches(h);
        class Operator extends FakePlayer {
            int permission = 2;

            Operator(ServerLevel level) {
                super(level, new GameProfile(java.util.UUID.randomUUID(), "ReviewQueuedOp"));
            }

            @Override
            protected int getPermissionLevel() {
                return permission;
            }
        }
        var op = new Operator(h.getLevel());
        op.moveTo(aim(h).point());
        handler(h, (player, node) -> node.equals("incoming.launch.rocket"), false);
        MinecraftServer server = h.getLevel().getServer();
        var seen = requests(h);
        Consumer<StrikeOrderEvent.Request> nested = event -> {
            if (!"review_enqueue".equals(event.getTag())) return;
            try {
                run(server, op.createCommandSourceStack(), "incoming salvo grad 1 0 at " + at(h) + " tag review_deferred");
            } catch (Exception error) {
                throw new IllegalStateException(error);
            }
            op.permission = 0;
        };
        NeoForge.EVENT_BUS.addListener(net.neoforged.bus.api.EventPriority.HIGH, nested);
        StrikeGameTests.afterTest(h, () -> NeoForge.EVENT_BUS.unregister(nested));
        run(server, server.createCommandSourceStack(), "incoming salvo drone 1 0 at " + at(h) + " tag review_enqueue");
        h.assertValueEqual(seen, List.of("review_enqueue"), "nested order was deferred");
        h.runAfterDelay(3, () -> {
            h.assertValueEqual(seen, List.of("review_enqueue"), "admissions after the player's deop");
            h.succeed();
        });
    }

    @GameTest(template = "runway", batch = "permission_r_nuke_now_as", skyAccess = true)
    public static void nuclearCommandsAskWhoGaveThem(GameTestHelper h) throws Exception {
        switches(h);
        var guest = player(h, "ReviewGuest", 0);
        var op = player(h, "ReviewNowOp", 2);
        handler(h, (player, node) -> node.equals("incoming.nuclear"), false);
        var seen = requests(h);
        MinecraftServer server = h.getLevel().getServer();
        CommandSourceStack asGuest = server.createCommandSourceStack().withEntity(guest);
        // the flying nuke needs the rights of the player it is made for, from any source
        run(server, asGuest, "incoming nuke at " + at(h) + " 1 tag review_flight");
        // nuke now never asked that player: the console's, as any player, is automation (a map's
        // "execute as @p at @s run incoming nuke now" keeps working under ops_only)
        run(server, asGuest, "incoming nuke now at " + at(h) + " 1 tag review_now_as");
        run(server, server.createCommandSourceStack(), "incoming nuke now at " + at(h) + " 1 tag review_now_console");
        // an operator's own nuke now asks the operator
        run(server, op.createCommandSourceStack(), "incoming nuke now at " + at(h) + " 1 tag review_now_op");
        h.assertValueEqual(seen, List.of("review_now_as", "review_now_console"), "admissions under the nuclear deny");
        h.succeed();
    }

    @GameTest(template = "runway", batch = "permission_r_key_line", skyAccess = true)
    public static void noEligibleOrderKeepsItsLine(GameTestHelper h) {
        switches(h);
        IncomingConfig.SERVER.nukeOpsOnly.set(true);
        var guest = player(h, "ReviewGuest", 0);
        NuclearKeys.confirm(guest, List.of(guest));
        h.assertTrue(guest.last("incoming.nuke.key.none") != null, "actual second-key feedback " + guest.said);
        h.succeed();
    }

    @GameTest(template = "runway", batch = "permission_r_key_wait", skyAccess = true)
    public static void paidDefaultKeyWaitKeepsItsCarrier(GameTestHelper h) {
        switches(h);
        var owner = player(h, "ReviewOwner", 0);
        var guest = player(h, "ReviewGuest", 0);
        var online = List.of(owner, guest);
        var loadout = carrier();
        var bill = ua.zentix.incoming.strike.Munitions.Bill.of(loadout);
        MunitionGameTests.give(owner, WeaponType.MISSILE.spec().munition().item().get(), 1);
        MunitionGameTests.give(owner, ua.zentix.incoming.strike.Munitions.warhead(), 1);
        h.assertTrue(ua.zentix.incoming.strike.Munitions.pay(owner, bill), "carrier payment fixture");
        h.assertTrue(NuclearKeys.order(owner, online, loadout, aim(h), Waypoints.NONE, bill), "carrier did not enter key wait");
        IncomingConfig.SERVER.carrierNukes.set(false);

        boolean confirmed = NuclearKeys.confirm(guest, online);
        int pending = NuclearKeys.get(h.getLevel()).pending();
        int missiles = owner.getInventory().countItem(WeaponType.MISSILE.spec().munition().item().get());
        h.assertTrue(confirmed && pending == 1 && missiles == 0,
                "after carrier switch flipped: confirmed=" + confirmed + ", pending=" + pending
                        + ", refunded missiles=" + missiles + ", lines=" + owner.said);
        h.succeed();
    }

    @GameTest(template = "runway", batch = "permission_r_warning", timeoutTicks = 1900, skyAccess = true)
    public static void nuclearFlagStillAppliesBeforeCarrierRelease(GameTestHelper h) {
        switches(h);
        var owner = player(h, "ReviewOwner", 0);
        var guest = player(h, "ReviewGuest", 0);
        var online = List.of(owner, guest);
        var loadout = carrier();
        var bill = ua.zentix.incoming.strike.Munitions.Bill.of(loadout);
        var missile = WeaponType.MISSILE.spec().munition().item().get();
        var warhead = ua.zentix.incoming.strike.Munitions.warhead();
        MunitionGameTests.give(owner, missile, 1);
        MunitionGameTests.give(owner, warhead, 1);
        h.assertTrue(ua.zentix.incoming.strike.Munitions.pay(owner, bill), "carrier payment fixture");
        var order = ua.zentix.incoming.strike.Order.player(owner, loadout, aim(h), StrikeOrderEvent.Payment.UPFRONT);
        h.assertTrue(ua.zentix.incoming.strike.Orders.admit(order, o -> {
            ua.zentix.incoming.strike.Orders.carrierPending(o);
            o.accept(1);
            return NuclearKeys.order(owner, online, loadout, aim(h), Waypoints.NONE, null, bill, o.round(0));
        }), "carrier did not enter key wait");
        h.assertTrue(NuclearKeys.confirm(guest, online), "carrier did not arm");
        List<StrikeRoundEvent.Released> releases = new ArrayList<>();
        Consumer<StrikeRoundEvent.Released> listener = event -> {
            if (owner.getUUID().equals(event.getOwnerId())) releases.add(event);
        };
        NeoForge.EVENT_BUS.addListener(listener);
        StrikeGameTests.afterTest(h, () -> NeoForge.EVENT_BUS.unregister(listener));
        IncomingConfig.SERVER.nukeEnabled.set(false);
        h.runAfterDelay(NuclearKeys.MIN_WARNING + 3, () -> {
            int pending = NuclearKeys.get(h.getLevel()).pending();
            int missiles = owner.getInventory().countItem(missile);
            int warheads = owner.getInventory().countItem(warhead);
            h.assertTrue(pending == 0 && releases.isEmpty() && missiles == 1 && warheads == 1,
                    "after warning with nuclear disabled: pending=" + pending + ", released=" + releases.size()
                            + ", refund=" + missiles + " missile and " + warheads + " warheads");
            h.succeed();
        });
    }

    @GameTest(template = "runway", batch = "permission_r_unregistered", skyAccess = true)
    public static void incompleteHandlerCannotBreakCommandTreeOrLogout(GameTestHelper h) {
        switches(h);
        var guest = player(h, "ReviewGuest", 0);
        handler(h, (player, node) -> false, true);
        var root = h.getLevel().getServer().getCommands().getDispatcher().getRoot().getChild("incoming");
        List<String> errors = new ArrayList<>();
        try {
            root.canUse(guest.createCommandSourceStack());
        } catch (RuntimeException error) {
            errors.add("command tree: " + error);
        }
        try {
            NeoForge.EVENT_BUS.post(new PlayerEvent.PlayerLoggedOutEvent(guest));
        } catch (RuntimeException error) {
            errors.add("logout: " + error);
        }
        h.assertTrue(errors.isEmpty(), errors.toString());
        h.succeed();
    }

    @GameTest(template = "runway", batch = "permission_r_offline_grant", skyAccess = true)
    public static void keptGrantNeverOpensAnOfflineLauncher(GameTestHelper h) {
        switches(h);
        var owner = player(h, "ReviewOwner", 0);
        // the handler grants the designator to this owner while designator_for_everyone is off
        handler(h, (player, node) -> false, false, (player, node) -> node.equals("incoming.launch") && player.getUUID().equals(owner.getUUID()));
        IncomingConfig.SERVER.designatorForEveryone.set(false);
        var pad = FixedLauncherGameTests.launcher(h, new BlockPos(16, 4, 8), owner.getUUID());
        var target = aim(h);
        pad.setMission(new Mission(WeaponType.ROCKET, 1, 0, target.target(), target.point(), Waypoints.NONE));
        FixedLauncherGameTests.load(h, pad, new ItemStack(ModItems.GRAD_ROCKETS.get()));
        NeoForge.EVENT_BUS.post(new PlayerEvent.PlayerLoggedOutEvent(owner));
        List<StrikeOrderEvent.Request> admitted = new ArrayList<>();
        Consumer<StrikeOrderEvent.Request> listener = event -> {
            if (owner.getUUID().equals(event.getOwnerId())) {
                admitted.add(event);
                event.setCanceled(true);
            }
        };
        NeoForge.EVENT_BUS.addListener(listener);
        StrikeGameTests.afterTest(h, () -> NeoForge.EVENT_BUS.unregister(listener));
        // offline, the grant kept at logout may have been revoked since: only the default answers, and it refuses
        var refused = LauncherOrders.fire(h.getLevel(), pad);
        h.assertTrue(admitted.isEmpty() && "incoming.fixed_launcher.no_rights".equals(FixedLauncherGameTests.key(refused)),
                "kept grant opened the launcher: admissions=" + admitted.size() + ", line=" + refused);
        h.succeed();
    }

    @GameTest(template = "runway", batch = "permission_r_null_handler", skyAccess = true)
    public static void absentHandlerUsesVanillaDefaults(GameTestHelper h) {
        switches(h);
        var guest = player(h, "ReviewGuest", 0);
        var root = h.getLevel().getServer().getCommands().getDispatcher().getRoot().getChild("incoming");
        IPermissionHandler base = ObfuscationReflectionHelper.<IPermissionHandler, PermissionAPI>getPrivateValue(PermissionAPI.class, null, "activeHandler");
        ObfuscationReflectionHelper.<PermissionAPI, IPermissionHandler>setPrivateValue(PermissionAPI.class, null, null, "activeHandler");
        StrikeGameTests.afterTest(h, () -> ObfuscationReflectionHelper.<PermissionAPI, IPermissionHandler>setPrivateValue(PermissionAPI.class, null, base, "activeHandler"));
        h.assertTrue(root.canUse(guest.createCommandSourceStack()), "null handler lost the allow default");
        IncomingConfig.SERVER.designatorForEveryone.set(false);
        h.assertFalse(root.canUse(guest.createCommandSourceStack()), "null handler lost the deny default");
        h.succeed();
    }

    private static void run(MinecraftServer server, CommandSourceStack source, String command) throws Exception {
        server.getCommands().getDispatcher().execute(command, source.withSuppressedOutput());
    }

    private static List<String> requests(GameTestHelper h) {
        List<String> seen = new ArrayList<>();
        Consumer<StrikeOrderEvent.Request> listener = event -> {
            if (event.getTag() != null && event.getTag().startsWith("review_")) {
                seen.add(event.getTag());
                event.setCanceled(true);
            }
        };
        NeoForge.EVENT_BUS.addListener(listener);
        StrikeGameTests.afterTest(h, () -> NeoForge.EVENT_BUS.unregister(listener));
        return seen;
    }

    private static MunitionGameTests.Listener player(GameTestHelper h, String name, int permission) {
        var player = new MunitionGameTests.Listener(h.getLevel(), name, permission);
        player.moveTo(Vec3.atBottomCenterOf(h.absolutePos(new BlockPos(24, 6, 16))));
        return player;
    }

    private static ServerActions.Aim aim(GameTestHelper h) {
        Vec3 pos = Vec3.atBottomCenterOf(h.absolutePos(new BlockPos(24, 12, 45)));
        return new ServerActions.Aim(new Target.Point(pos), pos, null);
    }

    private static String at(GameTestHelper h) {
        Vec3 pos = aim(h).point();
        return String.format(java.util.Locale.ROOT, "%.1f %.1f %.1f", pos.x, pos.y, pos.z);
    }

    private static Loadout carrier() {
        return new Loadout(WeaponType.MISSILE, 1, 0, TargetMode.LOOK, "", new Loadout.Nuke(1, false, true));
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

    private static void handler(GameTestHelper h, BiPredicate<ServerPlayer, String> deny, boolean omitNodes) {
        handler(h, deny, omitNodes, (player, node) -> false);
    }

    private static void handler(GameTestHelper h, BiPredicate<ServerPlayer, String> deny, boolean omitNodes, BiPredicate<ServerPlayer, String> grant) {
        IPermissionHandler base = ObfuscationReflectionHelper.<IPermissionHandler, PermissionAPI>getPrivateValue(PermissionAPI.class, null, "activeHandler");
        IPermissionHandler handler = new IPermissionHandler() {
            @Override
            public ResourceLocation getIdentifier() {
                return Incoming.id("review_handler");
            }

            @Override
            public Set<PermissionNode<?>> getRegisteredNodes() {
                return omitNodes ? Set.of() : base.getRegisteredNodes();
            }

            @Override
            @SuppressWarnings("unchecked")
            public <T> T getPermission(ServerPlayer player, PermissionNode<T> node, PermissionDynamicContext<?>... contexts) {
                if (deny.test(player, node.getNodeName())) return (T) Boolean.FALSE;
                if (grant.test(player, node.getNodeName())) return (T) Boolean.TRUE;
                return base.getPermission(player, node, contexts);
            }

            @Override
            public <T> T getOfflinePermission(java.util.UUID player, PermissionNode<T> node, PermissionDynamicContext<?>... contexts) {
                throw new AssertionError("unexpected offline lookup for " + node.getNodeName());
            }
        };
        ObfuscationReflectionHelper.<PermissionAPI, IPermissionHandler>setPrivateValue(PermissionAPI.class, null, handler, "activeHandler");
        StrikeGameTests.afterTest(h, () -> ObfuscationReflectionHelper.<PermissionAPI, IPermissionHandler>setPrivateValue(PermissionAPI.class, null, base, "activeHandler"));
    }
}
