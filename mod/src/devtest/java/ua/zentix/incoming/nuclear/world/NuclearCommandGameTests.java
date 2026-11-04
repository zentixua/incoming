package ua.zentix.incoming.nuclear.world;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import ua.zentix.incoming.Incoming;
import ua.zentix.incoming.IncomingConfig;
import ua.zentix.incoming.gametest.StrikeGameTests;
import ua.zentix.incoming.nuclear.NuclearEvents;
import ua.zentix.incoming.nuclear.NuclearWarhead;
import ua.zentix.incoming.strike.Loadout;
import ua.zentix.incoming.strike.NuclearCommands;
import ua.zentix.incoming.strike.ServerActions;
import ua.zentix.incoming.strike.StrikeWorld;
import ua.zentix.incoming.strike.Waypoints;
import ua.zentix.incoming.strike.WeaponType;
import ua.zentix.incoming.target.Target;

/** Counted command authority checks; backend preparation performance is exercised separately. */
@GameTestHolder(Incoming.MOD_ID)
@PrefixGameTestTemplate(false)
public final class NuclearCommandGameTests {
    private NuclearCommandGameTests() {}

    private static Loadout loadout() {
        return Loadout.DEFAULT.withWeapon(WeaponType.NUKE).withNuke(new Loadout.Nuke(1, false));
    }

    private static Vec3 setup(GameTestHelper h) {
        var c = IncomingConfig.SERVER;
        boolean enabled = c.nukeEnabled.get();
        c.nukeEnabled.set(true);
        StrikeGameTests.afterTest(h, () -> {
            NuclearCommands.cancelAll(h.getLevel().getServer());
            c.nukeEnabled.set(enabled);
        });
        return Vec3.atCenterOf(h.absolutePos(new BlockPos(32, 4, 32)));
    }

    @GameTest(template = "range", batch = "nuke_command_ready", skyAccess = true)
    public static void commandWaitsForReadyThenSchedulesExactlyOnce(GameTestHelper h) throws ReflectiveOperationException {
        Vec3 target = setup(h);
        ServerLevel level = h.getLevel();
        var events = NuclearEvents.get(level);
        int before = events.scheduled().size();
        h.assertTrue(NuclearCommands.request(level, "command-ready-test", 0, loadout(),
                new ServerActions.Aim(new Target.Point(target), target, null), Waypoints.NONE, null), "command refused");
        tick(level);
        h.assertValueEqual(events.scheduled().size(), before, "unready command launched");
        UUID handle = handle(level);
        ready(level, handle, target);
        tick(level);
        h.assertValueEqual(NuclearCommands.pending(level), 0, "ready authority not consumed");
        h.assertValueEqual(events.scheduled().size(), before + 1, "ready explicit command did not schedule");
        var scheduled = events.scheduled().getLast();
        StrikeGameTests.afterTest(h, () -> {
            events.unschedule(scheduled);
            RuinPreparation.cancel(level, handle);
        });
        h.assertTrue(scheduled.target().equals(target), "command target changed");
        h.assertTrue(RuinPreparation.status(level, handle).stage() == RuinPreparation.Stage.READY, "accepted order lost preparation");
        tick(level);
        h.assertValueEqual(events.scheduled().size(), before + 1, "command scheduled twice");
        h.succeed();
    }

    @GameTest(template = "range", batch = "nuke_command_abort", skyAccess = true)
    public static void globalAbortRevokesAnAlreadyReadyCommand(GameTestHelper h) throws ReflectiveOperationException {
        Vec3 target = setup(h);
        ServerLevel level = h.getLevel();
        int before = NuclearEvents.get(level).scheduled().size();
        h.assertTrue(NuclearCommands.request(level, "command-abort-test", 0, loadout(),
                new ServerActions.Aim(new Target.Point(target), target, null), Waypoints.NONE, null), "command refused");
        UUID handle = handle(level);
        ready(level, handle, target);
        h.assertValueEqual(NuclearCommands.cancelAll(level.getServer()), 1, "abort missed unpaid command");
        tick(level);
        h.assertValueEqual(NuclearEvents.get(level).scheduled().size(), before, "aborted ready command launched");
        h.assertTrue(RuinPreparation.status(level, handle).stage() == RuinPreparation.Stage.ABSENT, "abort retained preparation");
        h.succeed();
    }

    @GameTest(template = "range", batch = "nuke_command_policy", skyAccess = true)
    public static void disabledNuclearPolicyCancelsReadyCommand(GameTestHelper h) throws ReflectiveOperationException {
        Vec3 target = setup(h);
        ServerLevel level = h.getLevel();
        int before = NuclearEvents.get(level).scheduled().size();
        h.assertTrue(NuclearCommands.request(level, "command-policy-test", 0, loadout(),
                new ServerActions.Aim(new Target.Point(target), target, null), Waypoints.NONE, null), "command refused");
        UUID handle = handle(level);
        ready(level, handle, target);
        IncomingConfig.SERVER.nukeEnabled.set(false);
        tick(level);
        h.assertValueEqual(NuclearCommands.pending(level), 0, "disabled policy kept command");
        h.assertValueEqual(NuclearEvents.get(level).scheduled().size(), before, "disabled nuclear policy launched");
        h.assertTrue(RuinPreparation.status(level, handle).stage() == RuinPreparation.Stage.ABSENT, "disabled policy retained preparation");
        h.succeed();
    }

    @GameTest(template = "range", batch = "nuke_command_player", skyAccess = true)
    public static void playerPreparationIsUnpaidAndReplacementLogoutAndDeathRevokeIt(GameTestHelper h) throws ReflectiveOperationException {
        Vec3 target = setup(h);
        ServerLevel level = h.getLevel();
        var c = IncomingConfig.SERVER;
        boolean ops = c.nukeOpsOnly.get(), everyone = c.designatorForEveryone.get();
        c.nukeOpsOnly.set(false);
        c.designatorForEveryone.set(true);
        StrikeGameTests.afterTest(h, () -> { c.nukeOpsOnly.set(ops); c.designatorForEveryone.set(everyone); });
        var player = net.neoforged.neoforge.common.util.FakePlayerFactory.get(level,
                new com.mojang.authlib.GameProfile(UUID.randomUUID(), "prep_command"));
        player.setPos(target.add(0, 0, 2));
        var ammo = ua.zentix.incoming.registry.ModItems.ICBM.get();
        player.getInventory().add(new net.minecraft.world.item.ItemStack(ammo, 1));
        var aim = new ServerActions.Aim(new Target.Point(target), target, null);
        h.assertTrue(NuclearCommands.request(player, loadout(), aim, Waypoints.NONE, null, true), "player command refused");
        UUID first = handle(level);
        h.assertValueEqual(player.getInventory().countItem(ammo), 1, "preparation paid ammunition");
        h.assertTrue(NuclearCommands.request(player, loadout(), aim, Waypoints.NONE, null, true), "replacement refused");
        UUID second = handle(level);
        h.assertTrue(!first.equals(second), "replacement reused consumed authority");
        h.assertValueEqual(NuclearCommands.pending(level), 1, "replacement retained two commands");
        h.assertTrue(RuinPreparation.status(level, first).stage() == RuinPreparation.Stage.ABSENT, "replacement kept old preparation");
        NuclearCommands.onLogout(new net.neoforged.neoforge.event.entity.player.PlayerEvent.PlayerLoggedOutEvent(player));
        h.assertValueEqual(NuclearCommands.pending(level), 0, "logout retained command");
        h.assertTrue(RuinPreparation.status(level, second).stage() == RuinPreparation.Stage.ABSENT, "logout kept preparation");
        h.assertTrue(NuclearCommands.request(player, loadout(), aim, Waypoints.NONE, null, true), "next command refused");
        UUID third = handle(level);
        NuclearCommands.onDeath(new net.neoforged.neoforge.event.entity.living.LivingDeathEvent(player, level.damageSources().genericKill()));
        h.assertValueEqual(NuclearCommands.pending(level), 0, "death retained command");
        h.assertTrue(RuinPreparation.status(level, third).stage() == RuinPreparation.Stage.ABSENT, "death kept preparation");
        h.assertValueEqual(player.getInventory().countItem(ammo), 1, "unaccepted commands spent ammunition");
        h.succeed();
    }

    private static NuclearCommands state(ServerLevel level) throws ReflectiveOperationException {
        return (NuclearCommands) field(StrikeWorld.get(level), "commands").get(StrikeWorld.get(level));
    }

    private static void tick(ServerLevel level) throws ReflectiveOperationException {
        state(level).tick(level);
    }

    private static UUID handle(ServerLevel level) throws ReflectiveOperationException {
        return (UUID) ((Map<?, ?>) field(state(level), "entries").get(state(level))).keySet().iterator().next();
    }

    private static void ready(ServerLevel level, UUID handle, Vec3 target) throws ReflectiveOperationException {
        var prep = NuclearWorld.get(level).prep();
        for (Object p : (List<?>) field(prep, "preps").get(prep)) {
            if (!handle.equals(field(p, "handle").get(p))) continue;
            field(p, "geometry").set(p, NuclearWarhead.geometry(level, target, 1, false, (float) IncomingConfig.SERVER.effectsScale()));
            field(p, "resolvedTarget").set(p, target);
            field(p, "order").set(p, new long[0]);
            field(p, "nextFar").setInt(p, 0);
            hReady(level, handle);
            return;
        }
        throw new AssertionError("preparation absent");
    }

    private static void hReady(ServerLevel level, UUID handle) {
        if (RuinPreparation.status(level, handle).stage() != RuinPreparation.Stage.READY)
            throw new AssertionError("receipt fixture not ready: " + RuinPreparation.status(level, handle));
    }

    private static Field field(Object value, String name) throws ReflectiveOperationException {
        Field result = value.getClass().getDeclaredField(name);
        result.setAccessible(true);
        return result;
    }
}
