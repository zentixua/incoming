package ua.zentix.incoming.gametest;

import com.mojang.authlib.GameProfile;
import net.minecraft.commands.CommandSource;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.PlayerList;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import net.neoforged.fml.util.ObfuscationReflectionHelper;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.server.permission.PermissionAPI;
import net.neoforged.neoforge.server.permission.handler.DefaultPermissionHandler;
import net.neoforged.neoforge.server.permission.handler.IPermissionHandler;
import net.neoforged.neoforge.server.permission.nodes.PermissionDynamicContext;
import net.neoforged.neoforge.server.permission.nodes.PermissionNode;
import org.jetbrains.annotations.Nullable;
import ua.zentix.incoming.Incoming;
import ua.zentix.incoming.IncomingConfig;
import ua.zentix.incoming.api.StrikeOrderEvent;
import ua.zentix.incoming.launcher.FixedLauncherBlockEntity;
import ua.zentix.incoming.launcher.LauncherLinks;
import ua.zentix.incoming.launcher.LauncherOrders;
import ua.zentix.incoming.launcher.Mission;
import ua.zentix.incoming.net.C2S;
import ua.zentix.incoming.registry.ModDataComponents;
import ua.zentix.incoming.registry.ModItems;
import ua.zentix.incoming.strike.Authority;
import ua.zentix.incoming.strike.Loadout;
import ua.zentix.incoming.strike.NuclearKeys;
import ua.zentix.incoming.strike.NuclearReview;
import ua.zentix.incoming.strike.ServerActions;
import ua.zentix.incoming.strike.TargetMode;
import ua.zentix.incoming.strike.Waypoints;
import ua.zentix.incoming.strike.WeaponType;
import ua.zentix.incoming.target.Target;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * One authorization policy behind every strike entry ({@link Authority}). An order that passes every Incoming check
 * reaches admission, where {@link Gate} sees its {@code StrikeOrderEvent.Request} and cancels it: nothing launches and
 * nothing is paid. With NeoForge's default handler each entry answers exactly as the switches ({@code
 * designator_for_everyone}, {@code nuclear.ops_only}, operator rights); a permission handler ({@link Handler}, swapped in
 * for one batch) refuses one weapon at every entry and leaves the others, refuses nuclear orders and second keys while
 * the nuclear flag and trust stay hard gates, refuses linking, and an offline owner's launcher answers from the handler's
 * last answer or the node default without the handler's offline lookup. Each probe has its own batch: the handler and
 * the switches are server-wide.
 */
@GameTestHolder(Incoming.MOD_ID)
@PrefixGameTestTemplate(false)
public final class PermissionGameTests {
    private static final BlockPos PAD = new BlockPos(16, 4, 8);
    private static final BlockPos SPOT = new BlockPos(24, 4, 16);

    private PermissionGameTests() {}

    // ---------------------------------------------------------------- default handler

    /**
     * Default handler, every entry as the switches decide: the designator packet, the {@code /incoming} root, a player's
     * command and an operator's own, {@code /execute as} a player from a higher source, the console and a command block,
     * an owned fixed launcher whose owner is offline (the GameTest server keeps no profile cache, so an offline owner is
     * never an operator here; {@code Authority.level} reads that cache as the launcher always did), an ownerless one, linking, and
     * nuclear orders with {@code ops_only} on and off.
     */
    @GameTest(template = "runway", timeoutTicks = 40, batch = "permission_defaults", skyAccess = true)
    public static void defaultsAreTheSwitches(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        MinecraftServer server = level.getServer();
        h.assertValueEqual(PermissionAPI.getActivePermissionHandler(), DefaultPermissionHandler.IDENTIFIER, "active handler");
        h.assertTrue(PermissionAPI.getRegisteredNodes().containsAll(Authority.nodes()), "nodes not registered: " + PermissionAPI.getRegisteredNodes());
        switches(h);
        Gate gate = Gate.listen(h);
        IncomingConfig.SERVER.nukeOpsOnly.set(true);
        for (boolean everyone : new boolean[]{false, true}) {
            IncomingConfig.SERVER.designatorForEveryone.set(everyone);
            for (int level0 : new int[]{0, 2}) {
                boolean expected = everyone || level0 >= 2;
                String what = "designator for everyone " + everyone + ", level " + level0;
                MunitionGameTests.Listener p = player(h, "perm_d" + (everyone ? 1 : 0) + level0, level0);
                gate.watch(p.getUUID());
                h.assertValueEqual(Authority.allows(Authority.Action.USE, p), expected, "use, " + what);
                h.assertValueEqual(Authority.allows(Authority.Action.LINK, p), expected, "link, " + what);
                h.assertValueEqual(rootAllows(server, p.createCommandSourceStack()), expected, "/incoming, " + what);
                // designator packet
                ServerActions.fire(fire(p, WeaponType.DRONE), context(p));
                h.assertValueEqual(gate.passed(p.getUUID(), WeaponType.DRONE), expected ? 1 : 0, "designator, " + what);
                if (!expected) h.assertTrue(p.last("incoming.no_permission") != null, "no refusal line, " + what);
                // player's own command: under rules for a player, without for an operator
                if (expected) {
                    run(server, p.createCommandSourceStack(), "incoming drone at " + at(h));
                    h.assertValueEqual(gate.passed(p.getUUID(), WeaponType.DRONE), 2, "command, " + what);
                }
                // nuclear: ops_only on refuses a player, not an operator
                ServerActions.strike(p, WeaponType.NUKE, 1, 0, aim(h), nuke(), Waypoints.NONE, null, level0 < 2);
                boolean nuke = expected && level0 >= 2;
                h.assertValueEqual(gate.passed(p.getUUID(), WeaponType.NUKE), nuke ? 1 : 0, "nuclear, ops_only, " + what);
                if (expected && !nuke) h.assertTrue(p.last("incoming.nuke.ops_only") != null, "no ops_only line, " + what);
            }
            // /execute as a player from the console: automation, no designator rule, as today
            MunitionGameTests.Listener plain = player(h, "perm_ex" + (everyone ? 1 : 0), 0);
            gate.watch(plain.getUUID());
            run(server, server.createCommandSourceStack().withSuppressedOutput().withEntity(plain), "incoming drone at " + at(h));
            h.assertValueEqual(gate.passed(plain.getUUID(), WeaponType.DRONE), 1, "/execute as a player, everyone " + everyone);
            // the console and a command block (level 2, no entity)
            String tag = "perm_console_" + everyone;
            gate.watchTag(tag);
            run(server, server.createCommandSourceStack().withSuppressedOutput(), "incoming salvo drone 1 0 at " + at(h) + " tag " + tag);
            run(server, server.createCommandSourceStack().withSuppressedOutput().withSource(CommandSource.NULL).withPermission(2),
                    "incoming salvo drone 1 0 at " + at(h) + " tag " + tag);
            h.assertValueEqual(gate.tagged(tag), 2, "console and command block, everyone " + everyone);
        }
        // ops_only off: a player's nuclear order passes (the second key comes after admission)
        IncomingConfig.SERVER.nukeOpsOnly.set(false);
        MunitionGameTests.Listener open = player(h, "perm_nuke_open", 0);
        gate.watch(open.getUUID());
        ServerActions.strike(open, WeaponType.NUKE, 1, 0, aim(h), nuke(), Waypoints.NONE, null, true);
        h.assertValueEqual(gate.passed(open.getUUID(), WeaponType.NUKE), 1, "nuclear, ops_only off");

        // fixed launcher: owner offline (by profile), operator by the op list, ownerless
        IncomingConfig.SERVER.designatorForEveryone.set(false);
        UUID offline = UUID.randomUUID();
        gate.watch(offline);
        FixedLauncherBlockEntity be = FixedLauncherGameTests.launcher(h, PAD, offline);
        Vec3 far = Vec3.atBottomCenterOf(h.absolutePos(PAD)).add(0, -1, 1000);
        be.setMission(new Mission(WeaponType.DRONE, 1, 0, new Target.Point(far), far, Waypoints.NONE));
        FixedLauncherGameTests.load(h, be, new ItemStack(ModItems.SHAHED.get(), 2));
        h.assertValueEqual(FixedLauncherGameTests.key(LauncherOrders.fire(level, be)), "incoming.fixed_launcher.no_rights", "offline owner, designator for operators");
        IncomingConfig.SERVER.designatorForEveryone.set(true);
        be.setOwner(offline);
        LauncherOrders.fire(level, be);
        h.assertValueEqual(gate.passed(offline, WeaponType.DRONE), 1, "offline owner, designator for everyone");
        be.setOwner(null);
        gate.watchLauncher(be.getBlockPos());
        IncomingConfig.SERVER.designatorForEveryone.set(false);
        LauncherOrders.fire(level, be);
        h.assertValueEqual(gate.fromLauncher(be.getBlockPos()), 1, "ownerless launcher");

        // linking: not your launcher without the designator; an operator links an ownerless one
        MunitionGameTests.Listener plain = player(h, "perm_link_plain", 0);
        ItemStack designator = new ItemStack(ModItems.DESIGNATOR.get());
        h.assertValueEqual(FixedLauncherGameTests.key(LauncherLinks.toggle(plain, designator, be)), "incoming.fixed_launcher.not_yours", "link without designator");
        MunitionGameTests.Listener operator = player(h, "perm_link_op", 2);
        h.assertValueEqual(FixedLauncherGameTests.key(LauncherLinks.toggle(operator, designator, be)), "incoming.fixed_launcher.linked", "operator links ownerless");
        h.succeed();
    }

    // ---------------------------------------------------------------- a permission handler

    /**
     * {@code incoming.launch.rocket} denied: Grad is refused at every entry (designator packet, linked launchers, player
     * command, a deferred order, an online and a cached offline launcher owner) and every other weapon passes.
     */
    @GameTest(template = "runway", timeoutTicks = 60, batch = "permission_weapon", skyAccess = true)
    public static void deniedWeaponIsRefusedEverywhere(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        MinecraftServer server = level.getServer();
        switches(h);
        IncomingConfig.SERVER.designatorForEveryone.set(true);
        Handler handler = Handler.install(h);
        handler.everyone(Authority.weapon(WeaponType.ROCKET), false);
        Gate gate = Gate.listen(h);
        // designator packet: Grad refused with its line before anything else, Shahed passes in the same tick
        MunitionGameTests.Listener p = player(h, "perm_w_packet", 0);
        gate.watch(p.getUUID());
        ServerActions.fire(fire(p, WeaponType.ROCKET), context(p));
        h.assertTrue(p.last("incoming.no_permission.weapon") != null, "no weapon line on the designator");
        ServerActions.fire(fire(p, WeaponType.DRONE), context(p));
        h.assertValueEqual(gate.passed(p.getUUID(), WeaponType.DRONE), 1, "Shahed by designator");
        // every weapon through the order path
        MunitionGameTests.Listener q = player(h, "perm_w_all", 0);
        gate.watch(q.getUUID());
        for (WeaponType w : WeaponType.values()) {
            if (w == WeaponType.NUKE) continue;
            ServerActions.strike(q, w, 1, 0, aim(h), Loadout.Nuke.DEFAULT, Waypoints.NONE, null, true);
            h.assertValueEqual(gate.passed(q.getUUID(), w), w == WeaponType.ROCKET ? 0 : 1, w + " order");
        }
        // player commands, both names of Grad
        MunitionGameTests.Listener c = player(h, "perm_w_cmd", 0);
        gate.watch(c.getUUID());
        h.assertValueEqual(run(server, c.createCommandSourceStack(), "incoming grad at " + at(h)), 0, "/incoming grad result");
        h.assertValueEqual(run(server, c.createCommandSourceStack(), "incoming salvo rocket 4 10 at " + at(h)), 0, "/incoming salvo rocket result");
        h.assertTrue(c.last("incoming.no_permission.weapon") != null, "no weapon line on the command");
        run(server, c.createCommandSourceStack(), "incoming shahed at " + at(h));
        h.assertValueEqual(gate.passed(c.getUUID(), WeaponType.ROCKET), 0, "Grad by command");
        h.assertValueEqual(gate.passed(c.getUUID(), WeaponType.DRONE), 1, "Shahed by command");
        // an operator's own command is asked too; /execute as from the console is automation
        MunitionGameTests.Listener operator = player(h, "perm_w_op", 2);
        gate.watch(operator.getUUID());
        run(server, operator.createCommandSourceStack(), "incoming grad at " + at(h));
        h.assertValueEqual(gate.passed(operator.getUUID(), WeaponType.ROCKET), 0, "Grad by an operator's command");
        run(server, server.createCommandSourceStack().withSuppressedOutput().withEntity(c), "incoming grad at " + at(h));
        h.assertValueEqual(gate.passed(c.getUUID(), WeaponType.ROCKET), 1, "Grad by /execute as from the console");

        // linked launchers: the designator refuses Grad before any mission, gives Shahed
        MunitionGameTests.Listener linker = player(h, "perm_w_link", 0);
        FixedLauncherBlockEntity mine = FixedLauncherGameTests.launcher(h, PAD, linker.getUUID());
        ItemStack designator = new ItemStack(ModItems.DESIGNATOR.get());
        LauncherLinks.toggle(linker, designator, mine);
        h.assertTrue(!LauncherLinks.of(designator).isEmpty(), "launcher not linked");
        linker.setItemInHand(InteractionHand.MAIN_HAND, designator);
        ServerActions.fire(fire(linker, WeaponType.ROCKET), context(linker));
        h.assertTrue(mine.mission() == null && linker.last("incoming.no_permission.weapon") != null, "Grad mission given: " + mine.mission());

        // fixed launcher owners: online (the handler answers) and offline with the handler's last answer
        Vec3 far = Vec3.atBottomCenterOf(h.absolutePos(PAD)).add(0, -1, 1000);
        FakePlayer online = new FakePlayer(level, new GameProfile(UUID.randomUUID(), "perm_w_online"));
        online(h, online);
        gate.watch(online.getUUID());
        mine.setOwner(online.getUUID());
        mine.setMission(new Mission(WeaponType.ROCKET, 1, 0, new Target.Point(far), far, Waypoints.NONE));
        FixedLauncherGameTests.load(h, mine, new ItemStack(ModItems.GRAD_ROCKETS.get()));
        h.assertValueEqual(FixedLauncherGameTests.key(LauncherOrders.fire(level, mine)), "incoming.fixed_launcher.no_rights.weapon", "online owner, Grad");
        mine.setMission(new Mission(WeaponType.DRONE, 1, 0, new Target.Point(far), far, Waypoints.NONE));
        FixedLauncherGameTests.load(h, mine, new ItemStack(ModItems.SHAHED.get(), 1));
        LauncherOrders.fire(level, mine);
        h.assertValueEqual(gate.passed(online.getUUID(), WeaponType.DRONE), 1, "online owner, Shahed");

        // a deferred order (started inside another order's request) asks again on its own run
        MunitionGameTests.Listener later = player(h, "perm_w_later", 0);
        gate.watch(later.getUUID());
        String seed = "perm_seed";
        gate.watchTag(seed);
        gate.onRequest(e -> {
            if (!seed.equals(e.getTag())) return;
            ServerActions.strike(later, WeaponType.ROCKET, 1, 0, aim(h), Loadout.Nuke.DEFAULT, Waypoints.NONE, null, true);
            ServerActions.strike(later, WeaponType.DRONE, 1, 0, aim(h), Loadout.Nuke.DEFAULT, Waypoints.NONE, null, true);
        });
        run(server, server.createCommandSourceStack().withSuppressedOutput(), "incoming salvo drone 1 0 at " + at(h) + " tag " + seed);
        h.assertValueEqual(gate.passed(later.getUUID(), WeaponType.DRONE), 0, "the deferred order ran inside the request");
        h.succeedWhen(() -> {
            h.assertValueEqual(gate.passed(later.getUUID(), WeaponType.DRONE), 1, "deferred Shahed");
            h.assertValueEqual(gate.passed(later.getUUID(), WeaponType.ROCKET), 0, "deferred Grad");
            h.assertTrue(later.last("incoming.no_permission.weapon") != null, "no weapon line on the deferred order");
            h.assertValueEqual(handler.offline, 0, "offline lookups");
        });
    }

    /**
     * {@code incoming.nuclear} denied: the order, its review, the command and the second key refuse; an operator too. The
     * flag stays a hard gate above a granted node. A non-operator granted the node still waits for a second key, gets no
     * operator trust (map launch site), only players with the right are invited, and rights revoked during the wait
     * cancel the order with a refund when the key turns.
     */
    @GameTest(template = "runway", timeoutTicks = 40, batch = "permission_nuclear", skyAccess = true)
    public static void nuclearNodeUnderHardGates(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        MinecraftServer server = level.getServer();
        switches(h);
        IncomingConfig.SERVER.designatorForEveryone.set(true);
        IncomingConfig.SERVER.nukeOpsOnly.set(false);
        StrikeGameTests.afterTest(h, () -> NuclearKeys.cancelAll(server, List.of(), null, "check completed"));
        Handler handler = Handler.install(h);
        Gate gate = Gate.listen(h);
        Loadout.Nuke yield = new Loadout.Nuke(Math.min(15, IncomingConfig.SERVER.nukeMaxYield.get()), false, false);

        MunitionGameTests.Listener denied = player(h, "perm_n_denied", 0);
        handler.set(denied.getUUID(), Authority.NUCLEAR, false);
        gate.watch(denied.getUUID());
        ServerActions.strike(denied, WeaponType.NUKE, 1, 0, aim(h), yield, Waypoints.NONE, null, true);
        h.assertTrue(denied.last("incoming.no_permission.nuclear") != null, "no nuclear line on the order");
        h.assertValueEqual(gate.passed(denied.getUUID(), WeaponType.NUKE), 0, "denied ICBM order");
        NuclearReview.request(denied, fire(denied, WeaponType.NUKE));
        h.assertValueEqual(denied.said.stream().filter(m -> key(m).equals("incoming.no_permission.nuclear")).count(), 2L, "review refusal");
        h.assertFalse(nukeAllows(server, denied.createCommandSourceStack()), "/incoming nuke shown to a denied player");
        // an operator is asked as well
        MunitionGameTests.Listener operator = player(h, "perm_n_op", 2);
        handler.set(operator.getUUID(), Authority.NUCLEAR, false);
        gate.watch(operator.getUUID());
        ServerActions.strike(operator, WeaponType.NUKE, 1, 0, aim(h), yield, Waypoints.NONE, null, false);
        h.assertValueEqual(gate.passed(operator.getUUID(), WeaponType.NUKE), 0, "denied operator's ICBM");
        // the flag is above a granted node
        IncomingConfig.SERVER.nukeEnabled.set(false);
        MunitionGameTests.Listener off = player(h, "perm_n_off", 0);
        handler.set(off.getUUID(), Authority.NUCLEAR, true);
        ServerActions.strike(off, WeaponType.NUKE, 1, 0, aim(h), yield, Waypoints.NONE, null, true);
        h.assertTrue(off.last("incoming.nuke.disabled") != null, "nuclear weapons off, granted node");
        IncomingConfig.SERVER.nukeEnabled.set(true);

        // granted to a non-operator with ops_only on: the second key and trust still apply
        IncomingConfig.SERVER.nukeOpsOnly.set(true);
        Vec3 spot = Vec3.atBottomCenterOf(h.absolutePos(SPOT));
        MunitionGameTests.Listener granted = player(h, "perm_n_granted", 0), keyless = player(h, "perm_n_keyless", 0), holder = player(h, "perm_n_holder", 0);
        for (MunitionGameTests.Listener l : List.of(granted, keyless, holder)) {
            l.moveTo(spot);
            online(h, l);
        }
        handler.set(granted.getUUID(), Authority.NUCLEAR, true);
        handler.set(holder.getUUID(), Authority.NUCLEAR, true);
        ItemStack icbm = new ItemStack(WeaponType.NUKE.spec().munition().item().get());
        granted.getInventory().add(icbm.copy());
        h.assertTrue(ServerActions.strike(granted, WeaponType.NUKE, 1, 0, aim(h), yield, Waypoints.NONE, null, true), "granted order refused");
        h.assertValueEqual(NuclearKeys.get(level).pending(), 1, "the granted order skipped the second key");
        h.assertValueEqual(granted.getInventory().countItem(icbm.getItem()), 0, "ICBM not paid");
        h.assertValueEqual(FixedLauncherGameTests.key(ServerActions.launchSiteProblem(granted, spot)), "incoming.map.launch.ops_only", "node granted trust");
        h.assertTrue(holder.last("incoming.nuke.key.prompt") != null && keyless.last("incoming.nuke.key.prompt") == null,
                "second-key invitations: holder " + holder.said + ", keyless " + keyless.said);
        List<ServerPlayer> all = List.of(granted, keyless, holder);
        h.assertFalse(NuclearKeys.confirm(keyless, all), "a key turned without the right");
        h.assertTrue(keyless.last("incoming.no_permission.nuclear") != null, "no line for the keyless player");
        h.assertValueEqual(NuclearKeys.get(level).pending(), 1, "the keyless player's key cancelled the order");
        // revoked during the wait: the turning key cancels and refunds
        handler.set(granted.getUUID(), Authority.NUCLEAR, false);
        h.assertFalse(NuclearKeys.confirm(holder, all), "launched after the right was revoked");
        h.assertValueEqual(NuclearKeys.get(level).pending(), 0, "the revoked order still waits");
        h.assertTrue(granted.last("incoming.no_permission.nuclear") != null, "no line for the revoked owner");
        h.assertValueEqual(granted.getInventory().countItem(icbm.getItem()), 1, "ICBM not refunded");
        h.succeed();
    }

    /** {@code incoming.launcher.link} denied: linking and missions for linked launchers refuse; others link as before. */
    @GameTest(template = "runway", timeoutTicks = 20, batch = "permission_link", skyAccess = true)
    public static void linkNodeRefusesLinking(GameTestHelper h) {
        switches(h);
        IncomingConfig.SERVER.designatorForEveryone.set(true);
        Handler handler = Handler.install(h);
        MunitionGameTests.Listener owner = player(h, "perm_l_owner", 0), other = player(h, "perm_l_other", 0);
        FixedLauncherBlockEntity be = FixedLauncherGameTests.launcher(h, PAD, owner.getUUID());
        ItemStack linked = new ItemStack(ModItems.DESIGNATOR.get());
        h.assertValueEqual(FixedLauncherGameTests.key(LauncherLinks.toggle(owner, linked, be)), "incoming.fixed_launcher.linked", "owner links");
        handler.set(owner.getUUID(), Authority.LINK, false);
        ItemStack fresh = new ItemStack(ModItems.DESIGNATOR.get());
        h.assertValueEqual(FixedLauncherGameTests.key(LauncherLinks.toggle(owner, fresh, be)), "incoming.no_permission.link", "link denied");
        h.assertTrue(LauncherLinks.of(fresh).isEmpty(), "linked despite the denial");
        // a designator linked before: Fire gives no mission
        owner.setItemInHand(InteractionHand.MAIN_HAND, linked);
        ServerActions.fire(fire(owner, WeaponType.DRONE), context(owner));
        h.assertTrue(be.mission() == null, "mission given without the right: " + be.mission());
        // unlinking stays free
        h.assertValueEqual(FixedLauncherGameTests.key(LauncherLinks.toggle(owner, linked, be)), "incoming.fixed_launcher.unlinked", "unlink");
        // another player is not affected (their own launcher)
        FixedLauncherBlockEntity theirs = FixedLauncherGameTests.launcher(h, PAD.east(4), other.getUUID());
        h.assertValueEqual(FixedLauncherGameTests.key(LauncherLinks.toggle(other, fresh, theirs)), "incoming.fixed_launcher.linked", "other player links");
        h.assertTrue(linked.get(ModDataComponents.LAUNCHERS.get()) == null, "unlinked designator keeps links");
        h.succeed();
    }

    /**
     * An offline owner's launcher under a permission mod: without a kept answer the node default (fires), with the answer
     * the handler gave while the owner was online (refuses Grad, fires Shahed); the handler's offline lookup is never used.
     * An ownerless launcher asks nothing.
     */
    @GameTest(template = "runway", timeoutTicks = 20, batch = "permission_offline", skyAccess = true)
    public static void offlineOwnerUsesKeptAnswer(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        switches(h);
        IncomingConfig.SERVER.designatorForEveryone.set(true);
        Handler handler = Handler.install(h);
        Gate gate = Gate.listen(h);
        GameProfile owner = new GameProfile(UUID.randomUUID(), "perm_o_owner");
        handler.set(owner.getId(), Authority.weapon(WeaponType.ROCKET), false);
        gate.watch(owner.getId());
        FixedLauncherBlockEntity be = FixedLauncherGameTests.launcher(h, PAD, owner.getId());
        Vec3 far = Vec3.atBottomCenterOf(h.absolutePos(PAD)).add(0, -1, 1000);
        be.setMission(new Mission(WeaponType.ROCKET, 1, 0, new Target.Point(far), far, Waypoints.NONE));
        FixedLauncherGameTests.load(h, be, new ItemStack(ModItems.GRAD_ROCKETS.get()));
        LauncherOrders.fire(level, be);
        h.assertValueEqual(gate.passed(owner.getId(), WeaponType.ROCKET), 1, "offline owner without a kept answer: node default");
        Authority.remember(new FakePlayer(level, owner));
        h.assertValueEqual(FixedLauncherGameTests.key(LauncherOrders.fire(level, be)), "incoming.fixed_launcher.no_rights.weapon", "kept answer");
        be.setMission(new Mission(WeaponType.DRONE, 1, 0, new Target.Point(far), far, Waypoints.NONE));
        FixedLauncherGameTests.load(h, be, new ItemStack(ModItems.SHAHED.get(), 1));
        LauncherOrders.fire(level, be);
        h.assertValueEqual(gate.passed(owner.getId(), WeaponType.DRONE), 1, "kept answer allows Shahed");
        // ownerless: no rules even when the handler denies every launch
        handler.everyone(Authority.LAUNCH, false);
        be.setOwner(null);
        gate.watchLauncher(be.getBlockPos());
        LauncherOrders.fire(level, be);
        h.assertValueEqual(gate.fromLauncher(be.getBlockPos()), 1, "ownerless launcher");
        h.assertValueEqual(handler.offline, 0, "offline lookups");
        h.succeed();
    }

    // ---------------------------------------------------------------- helpers

    /** Restores the switches this class changes after the test. */
    private static void switches(GameTestHelper h) {
        IncomingConfig.Server c = IncomingConfig.SERVER;
        boolean everyone = c.designatorForEveryone.get(), opsOnly = c.nukeOpsOnly.get(), enabled = c.nukeEnabled.get();
        StrikeGameTests.afterTest(h, () -> {
            c.designatorForEveryone.set(everyone);
            c.nukeOpsOnly.set(opsOnly);
            c.nukeEnabled.set(enabled);
        });
        c.nukeEnabled.set(true);
    }

    /** An ICBM's warhead within this world's yield limit. */
    private static Loadout.Nuke nuke() {
        return new Loadout.Nuke(Math.min(15, IncomingConfig.SERVER.nukeMaxYield.get()), false, false);
    }

    private static MunitionGameTests.Listener player(GameTestHelper h, String name, int level) {
        MunitionGameTests.Listener p = new MunitionGameTests.Listener(h.getLevel(), name, level);
        p.moveTo(Vec3.atBottomCenterOf(h.absolutePos(SPOT)));
        return p;
    }

    /** {@code p} in the server's player list for this test (a launcher owner online, a second-key player). */
    private static void online(GameTestHelper h, ServerPlayer p) {
        PlayerList list = h.getLevel().getServer().getPlayerList();
        List<ServerPlayer> players = ObfuscationReflectionHelper.getPrivateValue(PlayerList.class, list, "players");
        Map<UUID, ServerPlayer> byId = ObfuscationReflectionHelper.getPrivateValue(PlayerList.class, list, "playersByUUID");
        players.add(p);
        byId.put(p.getUUID(), p);
        StrikeGameTests.afterTest(h, () -> {
            players.remove(p);
            byId.remove(p.getUUID());
        });
    }

    /** A target 40 blocks along the runway, within every range. */
    private static Vec3 target(GameTestHelper h) {
        return Vec3.atBottomCenterOf(h.absolutePos(SPOT)).add(0, 0, 40);
    }

    private static ServerActions.Aim aim(GameTestHelper h) {
        Vec3 t = target(h);
        return new ServerActions.Aim(new Target.Point(t), t, null);
    }

    private static String at(GameTestHelper h) {
        Vec3 t = target(h);
        return String.format(Locale.ROOT, "%.1f %.1f %.1f", t.x, t.y, t.z);
    }

    /** The designator's “Fire” at {@link #target}, one {@code w}. */
    private static C2S.Fire fire(ServerPlayer p, WeaponType w) {
        Loadout l = ServerActions.clamp(new Loadout(w, 1, 0, TargetMode.LOOK, "", w == WeaponType.NUKE ? nuke() : Loadout.Nuke.DEFAULT));
        Vec3 t = p.getEyePosition().add(0, 0, 40);
        return new C2S.Fire(l, Optional.of(new C2S.AimHint(C2S.AimHint.POINT, t, -1, Vec3.ZERO)), Optional.empty(), Waypoints.NONE, Optional.empty());
    }

    /** The packet context of {@code player}'s packet: only the sender is read. */
    private static IPayloadContext context(ServerPlayer player) {
        return (IPayloadContext) Proxy.newProxyInstance(PermissionGameTests.class.getClassLoader(), new Class<?>[]{IPayloadContext.class}, (proxy, m, args) -> {
            if (m.getName().equals("player")) return player;
            throw new UnsupportedOperationException(m.getName());
        });
    }

    private static boolean rootAllows(MinecraftServer server, CommandSourceStack s) {
        return server.getCommands().getDispatcher().getRoot().getChild("incoming").canUse(s);
    }

    private static boolean nukeAllows(MinecraftServer server, CommandSourceStack s) {
        return server.getCommands().getDispatcher().getRoot().getChild("incoming").getChild("nuke").canUse(s);
    }

    private static int run(MinecraftServer server, CommandSourceStack s, String command) {
        try {
            return server.getCommands().getDispatcher().execute(command, s);
        } catch (com.mojang.brigadier.exceptions.CommandSyntaxException e) {
            throw new GameTestAssertException("/" + command + ": " + e.getMessage());
        }
    }

    private static String key(net.minecraft.network.chat.Component c) {
        String k = FixedLauncherGameTests.key(c);
        return k == null ? "" : k;
    }

    /**
     * Requests of watched owners, tags and launchers: the order passed every Incoming check. Cancelled at once, so nothing
     * is paid or launched.
     */
    private static final class Gate {
        private final Set<UUID> owners = new HashSet<>();
        private final Set<String> tags = new HashSet<>();
        private final Set<BlockPos> launchers = new HashSet<>();
        private final List<StrikeOrderEvent.Request> seen = new ArrayList<>();
        private final List<Consumer<StrikeOrderEvent.Request>> also = new ArrayList<>();

        static Gate listen(GameTestHelper h) {
            Gate g = new Gate();
            Consumer<StrikeOrderEvent.Request> listener = g::request;
            NeoForge.EVENT_BUS.addListener(StrikeOrderEvent.Request.class, listener);
            StrikeGameTests.afterTest(h, () -> NeoForge.EVENT_BUS.unregister(listener));
            return g;
        }

        void watch(UUID owner) {
            owners.add(owner);
        }

        void watchTag(String tag) {
            tags.add(tag);
        }

        void watchLauncher(BlockPos pos) {
            launchers.add(pos.immutable());
        }

        void onRequest(Consumer<StrikeOrderEvent.Request> run) {
            also.add(run);
        }

        private void request(StrikeOrderEvent.Request e) {
            boolean mine = e.getOwnerId() != null && owners.contains(e.getOwnerId()) || e.getTag() != null && tags.contains(e.getTag())
                    || e.getOwnerId() == null && e.getLauncherPos() != null && launchers.contains(e.getLauncherPos());
            if (!mine) return;
            seen.add(e);
            e.setCanceled(true);
            for (Consumer<StrikeOrderEvent.Request> run : List.copyOf(also)) run.accept(e);
        }

        int passed(UUID owner, WeaponType w) {
            return (int) seen.stream().filter(e -> owner.equals(e.getOwnerId()) && e.getWeapon().equals(w.getSerializedName())).count();
        }

        int tagged(String tag) {
            return (int) seen.stream().filter(e -> tag.equals(e.getTag())).count();
        }

        int fromLauncher(BlockPos pos) {
            return (int) seen.stream().filter(e -> e.getOwnerId() == null && pos.equals(e.getLauncherPos())).count();
        }
    }

    /**
     * A permission mod for the test: answers set per player or for everyone, otherwise the handler it replaced; counts
     * offline lookups (Incoming must never make one). Installed as the active handler for one test.
     */
    private static final class Handler implements IPermissionHandler {
        private static final ResourceLocation ID = Incoming.id("test_handler");
        private final IPermissionHandler base;
        private final Map<UUID, Map<String, Boolean>> answers = new HashMap<>();
        private final Map<String, Boolean> forAll = new HashMap<>();
        int offline;

        private Handler(IPermissionHandler base) {
            this.base = base;
        }

        static Handler install(GameTestHelper h) {
            IPermissionHandler before = ObfuscationReflectionHelper.<IPermissionHandler, PermissionAPI>getPrivateValue(PermissionAPI.class, null, "activeHandler");
            Handler handler = new Handler(before);
            ObfuscationReflectionHelper.<PermissionAPI, IPermissionHandler>setPrivateValue(PermissionAPI.class, null, handler, "activeHandler");
            StrikeGameTests.afterTest(h, () -> ObfuscationReflectionHelper.<PermissionAPI, IPermissionHandler>setPrivateValue(PermissionAPI.class, null, before, "activeHandler"));
            return handler;
        }

        void set(UUID who, PermissionNode<Boolean> node, boolean value) {
            answers.computeIfAbsent(who, k -> new HashMap<>()).put(node.getNodeName(), value);
        }

        void everyone(PermissionNode<Boolean> node, boolean value) {
            forAll.put(node.getNodeName(), value);
        }

        @Override
        public ResourceLocation getIdentifier() {
            return ID;
        }

        @Override
        public Set<PermissionNode<?>> getRegisteredNodes() {
            return base.getRegisteredNodes();
        }

        @Override
        public <T> T getPermission(ServerPlayer player, PermissionNode<T> node, PermissionDynamicContext<?>... context) {
            T set = answer(player.getUUID(), node);
            return set != null ? set : base.getPermission(player, node, context);
        }

        @Override
        public <T> T getOfflinePermission(UUID player, PermissionNode<T> node, PermissionDynamicContext<?>... context) {
            offline++;
            T set = answer(player, node);
            return set != null ? set : base.getOfflinePermission(player, node, context);
        }

        @SuppressWarnings("unchecked")
        @Nullable
        private <T> T answer(UUID who, PermissionNode<T> node) {
            Boolean v = answers.getOrDefault(who, Map.of()).get(node.getNodeName());
            if (v == null) v = forAll.get(node.getNodeName());
            return (T) v;
        }
    }
}
