package ua.zentix.incoming.gametest;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.PlayerList;
import net.minecraft.world.phys.Vec3;
import net.neoforged.fml.util.ObfuscationReflectionHelper;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import ua.zentix.incoming.Incoming;
import ua.zentix.incoming.IncomingConfig;
import ua.zentix.incoming.api.GridOutageEvent;
import ua.zentix.incoming.api.StrikeOrderEvent;
import ua.zentix.incoming.grid.Blackouts;
import ua.zentix.incoming.grid.Outage;
import ua.zentix.incoming.grid.PowerGrid;
import ua.zentix.incoming.registry.ModItems;
import ua.zentix.incoming.strike.Loadout;
import ua.zentix.incoming.strike.NuclearKeys;
import ua.zentix.incoming.strike.Orders;
import ua.zentix.incoming.strike.SalvoData;
import ua.zentix.incoming.strike.ServerActions;
import ua.zentix.incoming.strike.StrikeWorld;
import ua.zentix.incoming.strike.Waypoints;
import ua.zentix.incoming.strike.WeaponType;
import ua.zentix.incoming.target.Target;
import ua.zentix.incoming.work.UnitQueue;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

/** Invokes Incoming's stop callback in isolated batches, without stopping the GameTest server. */
/** Order events, independent review probes: Server stop: what finishing impacts cause (an outage) is still delivered, and no paid order is left in an unsaved wait. */
@GameTestHolder(Incoming.MOD_ID)
@PrefixGameTestTemplate(false)
public final class OrderShutdownGameTests {
    private OrderShutdownGameTests() {}

    @GameTest(template = "range", batch = "order_probe_shutdown_outage", skyAccess = true)
    public static void shutdownDeliversOutageFromFinishingImpactWork(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        Vec3 point = point(h);
        List<GridOutageEvent> seen = new ArrayList<>();
        Consumer<GridOutageEvent> listener = e -> {
            if (level.dimension().equals(e.getDimension()) && e.getCentreX() == point.x && e.getCentreZ() == point.z) seen.add(e);
        };
        NeoForge.EVENT_BUS.addListener(listener);
        StrikeGameTests.afterTest(h, () -> {
            NeoForge.EVENT_BUS.unregister(listener);
            Blackouts.restore(level, point, 0, 0);
            Orders.deliver(level);
        });

        List<Outage> created = new ArrayList<>();
        StrikeWorld.get(level).impacts().add(level, new UnitQueue.Job() {
            @Override
            public boolean step(ServerLevel world) {
                created.add(Blackouts.blackout(world, point, 16, 16, -1));
                return false;
            }

            @Override
            public String describe() {
                return "Outage during shutdown impact drain";
            }
        });
        StrikeWorld.onServerStopping(new ServerStoppingEvent(level.getServer()));
        h.assertValueEqual(created.size(), 1, "impact work finished");
        h.assertTrue(PowerGrid.get(level).outages().contains(created.getFirst()), "Outage was not recorded");
        h.assertValueEqual(seen.size(), 1, "outages published during shutdown");
        h.succeed();
    }

    @GameTest(template = "range", batch = "order_probe_shutdown_debit", skyAccess = true)
    public static void shutdownDoesNotStrandPaidWorkStartedByAnAcceptedListener(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        var server = level.getServer();
        var config = IncomingConfig.SERVER;
        boolean enabled = config.nukeEnabled.get();
        boolean opsOnly = config.nukeOpsOnly.get();
        boolean carriers = config.carrierNukes.get();
        boolean warning = config.earlyWarning.get();
        boolean designators = config.designatorForEveryone.get();
        int cap = config.maxActivePerPlayer.get();
        config.nukeEnabled.set(true);
        config.nukeOpsOnly.set(false);
        config.carrierNukes.set(true);
        config.earlyWarning.set(false);
        config.designatorForEveryone.set(true);
        config.maxActivePerPlayer.set(0);
        StrikeGameTests.afterTest(h, () -> {
            config.nukeEnabled.set(enabled);
            config.nukeOpsOnly.set(opsOnly);
            config.carrierNukes.set(carriers);
            config.earlyWarning.set(warning);
            config.designatorForEveryone.set(designators);
            config.maxActivePerPlayer.set(cap);
        });
        var payer = MunitionGameTests.player(h, "probe_stop_payer");
        List<ServerPlayer> players = ObfuscationReflectionHelper.getPrivateValue(PlayerList.class, server.getPlayerList(), "players");
        players.add(payer);
        var missile = ModItems.CRUISE_MISSILE.get();
        var warhead = ModItems.NUCLEAR_WARHEAD.get();
        MunitionGameTests.give(payer, missile, 1);
        MunitionGameTests.give(payer, warhead, 1);
        Vec3 point = point(h);
        var aim = new ServerActions.Aim(new Target.Point(point), point, null);
        String tag = "probe_stop_outer";
        var consoleActor = new MunitionGameTests.Listener(level, "probe_stop_outer", 2);
        UUID consoleId = consoleActor.getUUID();
        Consumer<StrikeOrderEvent.Accepted> listener = e -> {
            if (!tag.equals(e.getTag())) return;
            ServerActions.strike(payer, WeaponType.MISSILE, 1, 0, aim, new Loadout.Nuke(1, true, true),
                    Waypoints.NONE, null, true);
        };
        NeoForge.EVENT_BUS.addListener(listener);
        StrikeGameTests.afterTest(h, () -> {
            NeoForge.EVENT_BUS.unregister(listener);
            NuclearKeys.cancelAll(server, List.of(payer), null, "shutdown review fixture cleanup");
            players.remove(payer);
            SalvoData.get(level).cancel(level, consoleId, List.of());
            Orders.deliver(level);
        });

        h.assertTrue(ServerActions.strike(consoleActor, WeaponType.DRONE, 2, 0, aim, Loadout.Nuke.DEFAULT, Waypoints.NONE, null, false, tag),
                "Outer fixture order was refused");
        StrikeWorld.onServerStopping(new ServerStoppingEvent(server));
        h.assertValueEqual(StrikeWorld.active(server, payer.getUUID()), 0, "unsaved rounds still waiting after shutdown");
        h.assertValueEqual(MunitionGameTests.held(payer, missile), 1, "carrier retained or refunded");
        h.assertValueEqual(MunitionGameTests.held(payer, warhead), 1, "warhead retained or refunded");
        h.succeed();
    }

    private static Vec3 point(GameTestHelper h) {
        return Vec3.atCenterOf(h.absolutePos(new BlockPos(32, 12, 32)));
    }
}
