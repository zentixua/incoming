package ua.zentix.incoming.gametest;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.entity.EntityTypeTest;
import net.minecraft.world.item.Item;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import org.jetbrains.annotations.Nullable;
import ua.zentix.incoming.Incoming;
import ua.zentix.incoming.IncomingConfig;
import ua.zentix.incoming.entity.IcbmEntity;
import ua.zentix.incoming.entity.LauncherEntity;
import ua.zentix.incoming.gametest.MunitionGameTests.Listener;
import ua.zentix.incoming.nuclear.NuclearEvents;
import ua.zentix.incoming.nuclear.NuclearStrikes;
import ua.zentix.incoming.registry.ModItems;
import ua.zentix.incoming.strike.Loadout;
import ua.zentix.incoming.strike.Munitions;
import ua.zentix.incoming.strike.NuclearKeys;
import ua.zentix.incoming.strike.ServerActions;
import ua.zentix.incoming.strike.StrikeWorld;
import ua.zentix.incoming.strike.TargetMode;
import ua.zentix.incoming.strike.VirtualFlights;
import ua.zentix.incoming.strike.Waypoints;
import ua.zentix.incoming.strike.WeaponType;
import ua.zentix.incoming.target.Target;

import java.util.List;
import java.util.UUID;

import static ua.zentix.incoming.gametest.MunitionGameTests.args;
import static ua.zentix.incoming.gametest.MunitionGameTests.give;
import static ua.zentix.incoming.gametest.MunitionGameTests.held;

/**
 * Non-operator nuclear strike ({@link NuclearKeys}, {@code nuclear.ops_only = false}): when others are
 * online, the second key must come from a nearby player, not from afar or oneself. An expired key prevents
 * launch and refunds the cost; when alone online, no key is needed. ICBM flight takes at least 90 s
 * (operator commands use the setting); a carrier launches 90 s after the alarm at the target and only
 * nuclear recall can cancel it. Everyone sees the launch. Players are NeoForge {@code FakePlayer}s:
 * they are absent from the player list, so online status is supplied explicitly. Each test has its own
 * batch: nuclear recall and {@link NuclearStrikes#clear} are world-wide.
 */
@GameTestHolder(Incoming.MOD_ID)
@PrefixGameTestTemplate(false)
public final class NuclearKeyGameTests {
    /** The launcher's place is on the strip (behind him, in 30 blocks, is the ICBM site) and the target is far ahead. */
    private static final BlockPos LAUNCHER = new BlockPos(16, 4, 60);
    private static final BlockPos TARGET = new BlockPos(16, 3, 200);
    /** The flight time of ICBM in the setting for the time of checks is shorter than the alarm in 90 s. */
    private static final int SHORT_FLIGHT = 200;

    private NuclearKeyGameTests() {}

    /**
     * There are others online: the order has been paid and is waiting; call only those who are nearby. A key from afar and your own do not count; the key
     *  of the player nearby launches an ICBM with a flight of at least 90 s (in the setting - 10 s), and to everyone in the network - “Launch ICBM from
     *  area X, Z detected” on cell 256 blocks.
     */
    @GameTest(template = "runway", batch = "nuclear_keys", skyAccess = true)
    public static void secondKeyFromNearbyPlayerLaunches(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        setUp(h);
        Listener a = at(h, "keys_launcher", LAUNCHER);
        Listener near = at(h, "keys_near", LAUNCHER.east(3));
        Listener far = at(h, "keys_far", LAUNCHER.south(40));
        List<Listener> online = List.of(a, near, far);
        Item icbm = ModItems.ICBM.get();
        give(a, icbm, 1);
        Loadout l = nuke(WeaponType.NUKE, false);
        int scheduled = NuclearEvents.get(level).scheduled().size();

        h.assertTrue(order(a, online, l, h), "the order was not accepted");
        h.assertValueEqual(held(a, icbm), 0, "ICBM not paid upon return");
        h.assertValueEqual(NuclearKeys.get(level).pending(), 1, "orders waiting for the key");
        h.assertValueEqual(StrikeWorld.active(level.getServer(), a.getUUID()), 1, "the order waiting for the key is not working (max_active_per_player)");
        h.assertValueEqual(NuclearEvents.get(level).scheduled().size(), scheduled, "launch without second key");
        h.assertTrue(a.last("incoming.nuke.key.wait") != null, "the launcher is not told to wait for the key");
        h.assertTrue(near.last("incoming.nuke.key.prompt") != null, "the player was not invited nearby");
        h.assertTrue(far.last("incoming.nuke.key.prompt") == null, "called a player in 40 blocks");

        h.assertFalse(NuclearKeys.confirm(far, online), "key of 40 blocks accepted");
        h.assertTrue(far.last("incoming.nuke.key.none") != null, "the distant one is not told that there is nothing to confirm");
        h.assertFalse(NuclearKeys.confirm(a, online), "the starter turned his second key");
        h.assertValueEqual(NuclearKeys.get(level).pending(), 1, "the order left without a key nearby");

        h.assertTrue(NuclearKeys.confirm(near, online), "nearby player key not accepted");
        h.assertValueEqual(NuclearKeys.get(level).pending(), 0, "the order is waiting after the key");
        NuclearEvents.ScheduledStrike s = mine(level, a.getUUID());
        h.assertTrue(s != null, "ICBM is not started after the key");
        h.assertValueEqual(s.detonateTime() - s.launchTime(), (long) NuclearKeys.MIN_WARNING, "flight ICBM not operator, tick");
        h.assertTrue(a.last("incoming.nuke.key.confirmed") != null && near.last("incoming.nuke.key.confirmed") != null, "no line about the key");
        List<Object> area = List.of(NuclearKeys.cell(a.getX()), NuclearKeys.cell(a.getZ()));
        for (Listener p : online) h.assertValueEqual(args(p.last("incoming.nuke.launch_detected")), area, "the line “launch detected” " + p.getGameProfile().getName());
        h.succeed();
    }

    /**
     * Alone on the network (server GameTest without players): the order from the remote control goes immediately, without a key, but ICBM flies 90 s, and not 10 s
     *  settings. Operator command ({@code rules = false}) - without a key and by configuration.
     */
    @GameTest(template = "runway", batch = "nuclear_keys_alone", skyAccess = true)
    public static void aloneNoKeyButFullWarning(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        setUp(h);
        h.assertTrue(level.getServer().getPlayerList().getPlayers().isEmpty(), "there are players on the server GameTest");
        Listener a = at(h, "keys_alone", LAUNCHER);
        give(a, ModItems.ICBM.get(), 1);
        ServerActions.Aim aim = aim(h);
        h.assertTrue(ServerActions.strike(a, WeaponType.NUKE, 1, 0, aim, new Loadout.Nuke(15, true), Waypoints.NONE, null, true), "launch of one on the network is not accepted");
        h.assertValueEqual(NuclearKeys.get(level).pending(), 0, "there's one on the network waiting for the key");
        NuclearEvents.ScheduledStrike s = mine(level, a.getUUID());
        h.assertTrue(s != null, "ICBM alone on the network is not running");
        h.assertValueEqual(s.detonateTime() - s.launchTime(), (long) NuclearKeys.MIN_WARNING, "flight ICBM not operator, tick");
        h.assertTrue(a.last("incoming.nuke.launch_detected") != null, "no line \"detected by launch\"");

        Listener op = at(h, "keys_op_command", LAUNCHER.east(8));
        h.assertTrue(ServerActions.strike(op, WeaponType.NUKE, 1, 0, aim, new Loadout.Nuke(15, true), Waypoints.NONE, null, false), "operator command not accepted");
        NuclearEvents.ScheduledStrike o = mine(level, op.getUUID());
        h.assertTrue(o != null, "Operator command ICBM is not running");
        h.assertValueEqual(o.detonateTime() - o.launchTime(), (long) SHORT_FLIGHT, "flight ICBM operator commands, tick");
        h.assertTrue(op.last("incoming.nuke.key.wait") == null, "the operator team is waiting for the key");
        h.succeed();
    }

    /** There is no second key within the period (5 s): no launch, ICBM returned, the launcher gets a line. */
    @GameTest(template = "range", timeoutTicks = 300, batch = "nuclear_keys_expiry", skyAccess = true)
    public static void keyWindowExpiresAndRefunds(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        setUp(h);
        int window = IncomingConfig.SERVER.nukeSecondKeyWindow.get();
        StrikeGameTests.afterTest(h, () -> IncomingConfig.SERVER.nukeSecondKeyWindow.set(window));
        IncomingConfig.SERVER.nukeSecondKeyWindow.set(5);
        Listener a = at(h, "keys_expiry", new BlockPos(32, 12, 32));
        Listener other = at(h, "keys_expiry_other", new BlockPos(34, 12, 32));
        Item icbm = ModItems.ICBM.get();
        give(a, icbm, 1);
        h.assertTrue(order(a, List.of(a, other), nuke(WeaponType.NUKE, false), h), "the order was not accepted");
        h.assertValueEqual(held(a, icbm), 0, "ICBM not paid upon return");
        long started = level.getGameTime();
        h.onEachTick(() -> {
            if (NuclearKeys.get(level).pending() > 0) return;
            long waited = level.getGameTime() - started;
            if (waited < 5 * 20) throw new GameTestAssertException("the order was canceled through " + waited + " tick, before the key deadline");
            if (held(a, icbm) != 1) throw new GameTestAssertException("ICBM after key expiration: " + held(a, icbm));
            if (a.last("incoming.nuke.key.expired") == null) throw new GameTestAssertException("no line about key expiration");
            if (mine(level, a.getUUID()) != null) throw new GameTestAssertException("ICBM launched without a key");
            h.succeed();
        });
    }

    /**
     * The carrier (a cruise missile with a nuclear warhead) does not start immediately after the key: at the target alarm - a line for everyone in its radius, -
     * launch through 90 s. A normal shutdown (not by an operator) does not remove it and does not remove anything nuclear; nuclear release
     * (operator, host) removes and returns the missile and warhead. Not an operator - not “one of us” for a nuclear shutdown.
     */
    @GameTest(template = "range", batch = "nuclear_keys_carrier", skyAccess = true)
    public static void carrierWaitsOutAlarmAndOnlyNuclearClearStopsIt(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        setUp(h);
        Listener a = at(h, "keys_carrier", new BlockPos(32, 12, 32));
        Listener near = at(h, "keys_carrier_near", new BlockPos(35, 12, 32));
        List<Listener> online = List.of(a, near);
        Item missile = ModItems.CRUISE_MISSILE.get(), warhead = Munitions.warhead();
        give(a, missile, 1);
        give(a, warhead, 1);
        Loadout l = nuke(WeaponType.MISSILE, true);
        h.assertTrue(l.nuclear(), "missile without nuclear warhead (carrier_nukes?)");
        h.assertTrue(order(a, online, l, h), "the order was not accepted");
        h.assertTrue(held(a, missile) == 0 && held(a, warhead) == 0, "missile and warhead not paid for");
        h.assertTrue(NuclearKeys.confirm(near, online), "key not accepted");
        h.assertValueEqual(NuclearKeys.get(level).pending(), 1, "the carrier does not wait for the end of the alarm");
        h.assertValueEqual(StrikeWorld.active(level.getServer(), a.getUUID()), 1, "the carrier is not in operation until the end of the alarm (max_active_per_player)");
        h.assertTrue(near.last("incoming.nuke.alarm.carrier") != null, "no alarm at the target");
        h.assertTrue(a.last("incoming.nuke.alarm.armed") != null, "the triggerer is not told about the alarm");
        h.assertTrue(StrikeWorld.projectiles(level).stream().noneMatch(p -> a.getUUID().equals(p.ownerId())), "the carrier started until the end of the alarm");

        h.assertFalse(NuclearKeys.trusted(a), "not an operator - “one of our own” for nuclear shutdown");
        ServerActions.clearAll(level.getServer(), false, "check: non-operator hang up");
        h.assertValueEqual(NuclearKeys.get(level).pending(), 1, "normal all-clear lifted nuclear launch");
        ServerActions.clearAll(level.getServer(), true, "check: nuclear shutdown");
        h.assertValueEqual(NuclearKeys.get(level).pending(), 0, "nuclear shutdown did not lift launch");
        h.assertTrue(held(a, missile) == 1 && held(a, warhead) == 1, "the missile and warhead did not return: " + held(a, missile) + ", " + held(a, warhead));
        h.assertTrue(a.last("incoming.nuke.key.cancelled") != null, "the launcher is not told about the hang up");
        h.succeed();
    }

    // ---------------------------------------------------------------- assistants

    /**
     * the nuke not only to operators, ICBM in the setting flies 10 s (in short, alarm). After the test - the same settings, no
     *  waiting for orders (the carrier would launch into the world of GameTest after the alarm), no nuclear strikes, no ICBM and launcher.
     */
    private static void setUp(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        boolean opsOnly = IncomingConfig.SERVER.nukeOpsOnly.get();
        int flight = IncomingConfig.SERVER.nukeFlightTime.get();
        StrikeGameTests.afterTest(h, () -> {
            NuclearKeys.cancelAll(level.getServer(), List.of(), null, "check completed");
            NuclearStrikes.clear(level);
            VirtualFlights.get(level).clear(level, p -> p instanceof IcbmEntity);
            level.getEntities(EntityTypeTest.forClass(IcbmEntity.class), e -> true).forEach(Entity::discard);
            level.getEntitiesOfClass(LauncherEntity.class, h.getBounds().inflate(64)).forEach(Entity::discard);
            IncomingConfig.SERVER.nukeOpsOnly.set(opsOnly);
            IncomingConfig.SERVER.nukeFlightTime.set(flight);
        });
        IncomingConfig.SERVER.nukeOpsOnly.set(false);
        IncomingConfig.SERVER.nukeFlightTime.set(SHORT_FLIGHT);
    }

    private static Listener at(GameTestHelper h, String name, BlockPos pos) {
        Listener p = MunitionGameTests.player(h, name);
        p.moveTo(Vec3.atBottomCenterOf(h.absolutePos(pos)), 0, 0);
        return p;
    }

    private static ServerActions.Aim aim(GameTestHelper h) {
        Vec3 point = Vec3.atCenterOf(h.absolutePos(TARGET)).add(0, 0.5, 0);
        return new ServerActions.Aim(new Target.Point(point), point, null);
    }

    /** Nuclear order within the settings: ICBM or carrier with a nuclear warhead, 15 kt. */
    private static Loadout nuke(WeaponType weapon, boolean onCarrier) {
        return ServerActions.clamp(new Loadout(weapon, 1, 0, TargetMode.LOOK, "", new Loadout.Nuke(15, true, onCarrier)));
    }

    /** Like {@code ServerActions.strike} for a non-operator: pay and give an order, “online” - {@code online}. */
    private static boolean order(Listener owner, List<Listener> online, Loadout l, GameTestHelper h) {
        Munitions.Bill bill = Munitions.Bill.of(l);
        if (!Munitions.pay(owner, bill)) throw new GameTestAssertException("not paid: " + l);
        return NuclearKeys.order(owner, online, l, aim(h), Waypoints.NONE, bill);
    }

    @Nullable
    private static NuclearEvents.ScheduledStrike mine(ServerLevel level, UUID owner) {
        for (NuclearEvents.ScheduledStrike s : NuclearEvents.get(level).scheduled()) {
            if (s.owner().map(owner::equals).orElse(false)) return s;
        }
        return null;
    }
}
