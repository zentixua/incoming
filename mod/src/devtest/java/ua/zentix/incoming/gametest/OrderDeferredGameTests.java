package ua.zentix.incoming.gametest;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.Event;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import ua.zentix.incoming.Incoming;
import ua.zentix.incoming.IncomingConfig;
import ua.zentix.incoming.api.StrikeOrderEvent;
import ua.zentix.incoming.launcher.FixedLauncherBlockEntity;
import ua.zentix.incoming.launcher.Mission;
import ua.zentix.incoming.registry.ModBlocks;
import ua.zentix.incoming.registry.ModItems;
import ua.zentix.incoming.strike.Loadout;
import ua.zentix.incoming.strike.SalvoData;
import ua.zentix.incoming.strike.ServerActions;
import ua.zentix.incoming.strike.Waypoints;
import ua.zentix.incoming.strike.WeaponType;
import ua.zentix.incoming.target.Target;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

/** Order events, independent review probes: An entry started inside a Request listener runs again later from its outermost call: a launcher signal rechecks busy and stock; a player who left by then orders nothing and pays nothing. */
@GameTestHolder(Incoming.MOD_ID)
@PrefixGameTestTemplate(false)
public final class OrderDeferredGameTests {
    private OrderDeferredGameTests() {}

    @GameTest(template = "range", batch = "order_probe_deferred_launcher", skyAccess = true)
    public static void deferredLauncherSignalRechecksBusyAndStock(GameTestHelper h) {
        var level = h.getLevel();
        BlockPos launcherPos = new BlockPos(32, 12, 24);
        h.setBlock(launcherPos, ModBlocks.FIXED_LAUNCHER.get());
        FixedLauncherBlockEntity launcher = h.getBlockEntity(launcherPos);
        UUID owner = UUID.randomUUID();
        launcher.setOwner(owner);
        Vec3 at = point(h).add(0, 0, 200);
        launcher.setMission(new Mission(WeaponType.DRONE, 2, 0, new Target.Point(at), at, Waypoints.NONE));
        h.assertTrue(launcher.load(new ItemStack(ModItems.SHAHED.get(), 2)).isEmpty(), "Fixture stock did not fit");
        boolean designators = IncomingConfig.SERVER.designatorForEveryone.get();
        int limit = IncomingConfig.SERVER.maxActivePerPlayer.get();
        IncomingConfig.SERVER.designatorForEveryone.set(true);
        IncomingConfig.SERVER.maxActivePerPlayer.set(1);
        StrikeGameTests.afterTest(h, () -> {
            IncomingConfig.SERVER.designatorForEveryone.set(designators);
            IncomingConfig.SERVER.maxActivePerPlayer.set(limit);
            SalvoData.get(level).cancel(level, owner, List.of());
            MunitionGameTests.removeFlights(level, owner);
            level.removeBlockEntity(launcher.getBlockPos());
            h.setBlock(launcherPos, Blocks.AIR);
        });

        String outer = "probe_deferred_launcher_outer";
        List<UUID> admitted = new ArrayList<>();
        listen(h, StrikeOrderEvent.Accepted.class, e -> {
            if (launcher.getBlockPos().equals(e.getLauncherPos())) admitted.add(e.getOrderId());
        });
        listen(h, StrikeOrderEvent.Request.class, e -> {
            if (!outer.equals(e.getTag())) return;
            launcher.fire(level);
            launcher.fire(level);
            e.setCanceled(true);
        });
        h.assertFalse(dispatch(level, outer, point(h)), "Outer fixture order should be vetoed");
        h.runAfterDelay(3, () -> {
            h.assertValueEqual(admitted.size(), 1, "admissions from one busy launcher with stock for one salvo");
            h.succeed();
        });
    }

    @GameTest(template = "range", batch = "order_probe_deferred_logout", skyAccess = true)
    public static void deferredPlayerOrderCannotPayFromAnAlreadySavedPlayer(GameTestHelper h) {
        var level = h.getLevel();
        var payer = MunitionGameTests.player(h, "probe_deferred_logout");
        var ammo = ModItems.SHAHED.get();
        MunitionGameTests.give(payer, ammo, 2);
        StrikeGameTests.afterTest(h, () -> {
            SalvoData.get(level).cancel(level, payer.getUUID(), List.of(payer));
            MunitionGameTests.removeFlights(level, payer.getUUID());
        });
        String outer = "probe_deferred_logout_outer";
        Vec3 point = point(h);
        var aim = new ServerActions.Aim(new Target.Point(point), point, null);
        listen(h, StrikeOrderEvent.Request.class, e -> {
            if (!outer.equals(e.getTag())) return;
            ServerActions.strike(payer, WeaponType.DRONE, 2, 0, aim, Loadout.Nuke.DEFAULT, Waypoints.NONE, null, true);
            e.setCanceled(true);
        });
        h.assertFalse(dispatch(level, outer, point), "Outer fixture order should be vetoed");
        // PlayerList saves inventory before removing the disconnected ServerPlayer with this reason.
        payer.setRemoved(Entity.RemovalReason.UNLOADED_WITH_PLAYER);
        h.runAfterDelay(3, () -> {
            h.assertValueEqual(MunitionGameTests.held(payer, ammo), 2, "ammunition in the disconnected player object");
            h.succeed();
        });
    }

    private static Vec3 point(GameTestHelper h) {
        return Vec3.atCenterOf(h.absolutePos(new BlockPos(32, 12, 32)));
    }

    private static boolean dispatch(ServerLevel level, String tag, Vec3 point) {
        var aim = new ServerActions.Aim(new Target.Point(point), point, null);
        return ServerActions.dispatch(level, tag, 0, WeaponType.DRONE, 1, 0, aim, Loadout.Nuke.DEFAULT, Waypoints.NONE, null, tag, null);
    }

    private static <T extends Event> void listen(GameTestHelper h, Class<T> type, Consumer<T> listener) {
        NeoForge.EVENT_BUS.addListener(type, listener);
        StrikeGameTests.afterTest(h, () -> NeoForge.EVENT_BUS.unregister(listener));
    }
}
