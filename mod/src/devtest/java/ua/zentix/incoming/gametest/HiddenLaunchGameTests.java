package ua.zentix.incoming.gametest;

import io.netty.channel.embedded.EmbeddedChannel;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.Connection;
import net.minecraft.network.PacketSendListener;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.server.players.PlayerList;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import net.neoforged.fml.util.ObfuscationReflectionHelper;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import org.jetbrains.annotations.Nullable;
import ua.zentix.incoming.Incoming;
import ua.zentix.incoming.IncomingConfig;
import ua.zentix.incoming.entity.IcbmEntity;
import ua.zentix.incoming.entity.StrikeProjectile;
import ua.zentix.incoming.net.S2C;
import ua.zentix.incoming.nuclear.NuclearEvents;
import ua.zentix.incoming.nuclear.NuclearStrikes;
import ua.zentix.incoming.registry.ModEntities;
import ua.zentix.incoming.strike.*;
import ua.zentix.incoming.target.Target;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Launch origins and actual packet recipients; each config-changing flow has its own batch. */
@GameTestHolder(Incoming.MOD_ID)
@PrefixGameTestTemplate(false)
public final class HiddenLaunchGameTests {
    private HiddenLaunchGameTests() {}

    private static void cleanup(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        StrikeGameTests.afterTest(h, () -> {
            NuclearKeys.cancelAll(level.getServer(), List.of(), null, "test completed");
            NuclearStrikes.clear(level);
            VirtualFlights.get(level).clear(level, p -> true);
            new ArrayList<>(StrikeWorld.projectiles(level)).forEach(Entity::discard);
            SalvoData.get(level).cancel(level, null, List.of());
        });
    }

    @GameTest(template = "runway", batch = "hidden_icbm_site", skyAccess = true)
    public static void icbmBoostStartsAtOrderedPad(GameTestHelper h) {
        cleanup(h);
        ServerLevel level = h.getLevel();
        Vec3 site = Vec3.atBottomCenterOf(h.absolutePos(new BlockPos(24, 3, 40)));
        UUID owner = UUID.randomUUID();
        LaunchOrigin from = new LaunchOrigin.Place(site);
        h.assertTrue(StrikeService.launchIcbm(level, new Target.Ground(site.add(0, 0, 160)), site.add(0, 0, 160),
                Loadout.Nuke.DEFAULT, null, owner, from, NuclearKeys.MIN_WARNING).ok(), "ordered launch failed");
        NuclearEvents.ScheduledStrike s = NuclearEvents.get(level).scheduled().stream().filter(x -> x.owner().filter(owner::equals).isPresent()).findFirst().orElseThrow();
        h.assertTrue(s.launchPos().subtract(site).horizontalDistance() < 1, "ICBM used player/console origin");
        IcbmEntity icbm = (IcbmEntity) StrikeWorld.projectiles(level).stream().filter(p -> owner.equals(p.ownerId())).findFirst().orElseThrow();
        h.assertTrue(icbm.position().subtract(s.launchPos().add(0, 9, 0)).length() < 0.001, "boost did not begin above pad");
        h.assertTrue(LaunchPlaces.holds(level, from, icbm.getUUID()), "launch area is not held");
        UUID nearbyOwner = UUID.randomUUID();
        h.assertTrue(NuclearStrikes.launchFrom(level, site.add(0, 0, 160), 1, true, site, 0, nearbyOwner), "default origin launch failed");
        NuclearEvents.ScheduledStrike nearby = NuclearEvents.get(level).scheduled().stream().filter(x -> x.owner().filter(nearbyOwner::equals).isPresent()).findFirst().orElseThrow();
        h.assertTrue(nearby.launchPos().subtract(site.add(0, 0, -30)).horizontalDistance() < 1, "no-site launch changed its 30-block offset");
        double y = icbm.getY();
        h.runAfterDelay(10, () -> {
            StrikeProjectile current = StrikeWorld.projectiles(level).stream().filter(p -> p.getUUID().equals(icbm.getUUID())).findFirst().orElseThrow();
            h.assertTrue(current.getY() > y, "no visible climb from pad");
            h.succeed();
        });
    }

    @GameTest(template = "runway", batch = "hidden_icbm_keys", skyAccess = true, timeoutTicks = 800)
    public static void keyedIcbmRetainsSiteAndMinimumFlight(GameTestHelper h) {
        cleanup(h);
        ServerLevel level = h.getLevel();
        boolean ops = IncomingConfig.SERVER.nukeOpsOnly.get();
        IncomingConfig.SERVER.nukeOpsOnly.set(false);
        StrikeGameTests.afterTest(h, () -> IncomingConfig.SERVER.nukeOpsOnly.set(ops));
        var owner = new MunitionGameTests.Listener(level, "hidden_keys");
        owner.moveTo(Vec3.atBottomCenterOf(h.absolutePos(new BlockPos(16, 4, 60))));
        var key = new MunitionGameTests.Listener(level, "hidden_key");
        key.setPos(owner.position().add(2, 0, 0));
        Vec3 site = owner.position().add(1400, 0, 0), target = owner.position().add(0, 0, 100);
        LaunchOrigin from = new LaunchOrigin.Place(site);
        Loadout l = new Loadout(WeaponType.NUKE, 1, 0, TargetMode.LOOK, "", Loadout.Nuke.DEFAULT);
        List<ServerPlayer> online = List.of(owner, key);
        h.assertTrue(NuclearKeys.order(owner, online, l, new ServerActions.Aim(new Target.Ground(target), target, null), Waypoints.NONE, from, null), "order failed");
        h.assertTrue(NuclearEvents.get(level).scheduled().isEmpty(), "launched before second key");
        h.assertTrue(NuclearKeys.confirm(key, online), "second key failed");
        StrikeGameTests.gameSpeed(h);
        h.succeedWhen(() -> {
            NuclearEvents.ScheduledStrike s = NuclearEvents.get(level).scheduled().stream().filter(x -> x.owner().filter(owner.getUUID()::equals).isPresent()).findFirst().orElse(null);
            h.assertTrue(s != null, "site not ready/strike not launched");
            h.assertTrue(s.launchPos().subtract(site).horizontalDistance() < 1, "keyed order lost origin");
            h.assertTrue(s.detonateTime() - s.launchTime() >= NuclearKeys.MIN_WARNING, "keyed flight shorter than 90 s");
        });
    }

    @GameTest(template = "runway", batch = "hidden_warning_off", skyAccess = true)
    public static void disabledNetworkHidesWarningsButKeepsOwnerData(GameTestHelper h) { warnings(h, false); }

    @GameTest(template = "runway", batch = "hidden_warning_on", skyAccess = true)
    public static void defaultNetworkStillWarns(GameTestHelper h) { warnings(h, true); }

    private static void warnings(GameTestHelper h, boolean enabled) {
        cleanup(h);
        boolean old = IncomingConfig.SERVER.earlyWarning.get();
        IncomingConfig.SERVER.earlyWarning.set(enabled);
        StrikeGameTests.afterTest(h, () -> IncomingConfig.SERVER.earlyWarning.set(old));
        ServerLevel level = h.getLevel();
        Vec3 at = Vec3.atBottomCenterOf(h.absolutePos(new BlockPos(16, 4, 100)));
        Watcher owner = Watcher.at(h, "hidden_owner", at.add(0, 0, -40));
        Watcher other = Watcher.at(h, "hidden_other", at);
        Watcher far = Watcher.at(h, "hidden_far", at.add(2000, 0, 0));
        StrikeService.siren(level, WeaponType.MISSILE, at);
        h.assertTrue(other.has(S2C.Siren.class) == enabled, "conventional siren policy");
        h.assertFalse(far.has(S2C.Siren.class), "conventional siren escaped radius");
        h.assertTrue(NuclearStrikes.launch(level, at, 1, true, owner.player), "ICBM failed");
        h.assertTrue(other.has(S2C.NukeWarning.class) == enabled, "non-owner learned of ICBM launch");
        h.assertTrue(far.has(S2C.NukeWarning.class) == enabled, "distant non-owner learned of launch");
        S2C.NukeWarning mine = owner.got.stream().filter(S2C.NukeWarning.class::isInstance).map(S2C.NukeWarning.class::cast).findFirst().orElseThrow();
        h.assertTrue(mine.mine() && mine.alarm() == enabled, "owner countdown/alarm policy");
        for (Watcher w : List.of(owner, other, far)) {
            w.got.clear();
            NuclearStrikes.onLogin(new PlayerEvent.PlayerLoggedInEvent(w.player));
            S2C.NukeSync sync = w.got.stream().filter(S2C.NukeSync.class::isInstance).map(S2C.NukeSync.class::cast).findFirst().orElseThrow();
            h.assertTrue(sync.warnings().isEmpty() == (!enabled && w != owner), "resync disclosed scheduled strike");
            h.assertTrue(w.has(S2C.NukeApproach.class), "terminal visuals were gated with warnings");
        }
        // Ordinary flight telemetry goes to its owner, independently of warning settings.
        StrikeProjectile drone = ModEntities.DRONE.get().create(level);
        drone.launch(owner.player.position().add(0, 80, 0), new Target.Point(at), at, owner.player.getUUID());
        VirtualFlights.launch(level, drone);
        h.onEachTick(() -> {
            if (level.getServer().getTickCount() % 5 != 0) return;
            FlightStatus.onServerTick(new ServerTickEvent.Post(() -> true, level.getServer()));
            h.assertTrue(owner.has(S2C.Flights.class), "owner lost flight HUD data");
            h.assertFalse(other.has(S2C.Flights.class) || far.has(S2C.Flights.class), "foreign flight list leaked");
            h.succeed();
        });
    }

    @GameTest(template = "runway", batch = "hidden_key_warning_off", skyAccess = true, timeoutTicks = 2100)
    public static void hiddenKeyedLaunchesSendNoChatOrAlarm(GameTestHelper h) { keyWarnings(h, false); }

    @GameTest(template = "runway", batch = "hidden_key_warning_on", skyAccess = true, timeoutTicks = 2100)
    public static void keyedLaunchesStillWarnByDefault(GameTestHelper h) { keyWarnings(h, true); }

    private static void keyWarnings(GameTestHelper h, boolean enabled) {
        cleanup(h);
        ServerLevel level = h.getLevel();
        boolean old = IncomingConfig.SERVER.earlyWarning.get(), ops = IncomingConfig.SERVER.nukeOpsOnly.get();
        int flight = IncomingConfig.SERVER.nukeFlightTime.get();
        IncomingConfig.SERVER.earlyWarning.set(enabled);
        IncomingConfig.SERVER.nukeOpsOnly.set(false);
        IncomingConfig.SERVER.nukeFlightTime.set(6000); // keep the ICBM from detonating during the carrier delay
        StrikeGameTests.afterTest(h, () -> {
            IncomingConfig.SERVER.earlyWarning.set(old);
            IncomingConfig.SERVER.nukeOpsOnly.set(ops);
            IncomingConfig.SERVER.nukeFlightTime.set(flight);
        });
        Vec3 at = Vec3.atBottomCenterOf(h.absolutePos(new BlockPos(16, 4, 60)));
        Watcher owner = Watcher.at(h, "hidden_key_owner", at);
        Watcher other = Watcher.at(h, "hidden_key_other", at.add(3, 0, 0));
        Watcher far = Watcher.at(h, "hidden_key_far", at.add(30_000, 0, 0));
        List<ServerPlayer> online = List.of(owner.player, other.player, far.player);
        ServerActions.Aim aim = new ServerActions.Aim(new Target.Ground(at.add(0, 0, 120)), at.add(0, 0, 120), null);
        Loadout icbm = new Loadout(WeaponType.NUKE, 1, 0, TargetMode.LOOK, "", Loadout.Nuke.DEFAULT);
        h.assertTrue(NuclearKeys.order(owner.player, online, icbm, aim, Waypoints.NONE, null), "ICBM key order failed");
        h.assertTrue(other.player.last("incoming.nuke.key.prompt") != null, "second-key consent was hidden with warnings");
        h.assertTrue(NuclearKeys.confirm(other.player, online), "ICBM key failed");
        h.assertTrue((other.player.last("incoming.nuke.launch_detected") != null) == enabled, "ICBM detected line policy");
        h.assertTrue((far.sounds > 0) == enabled, "ICBM detected sound policy");
        int sounds = far.sounds;
        Loadout carrier = new Loadout(WeaponType.BUNKER, 1, 0, TargetMode.LOOK, "", new Loadout.Nuke(1, true, true));
        h.assertTrue(NuclearKeys.order(owner.player, online, carrier, aim, Waypoints.NONE, null), "carrier key order failed");
        h.assertTrue(NuclearKeys.confirm(other.player, online), "carrier key failed");
        h.assertTrue((other.player.last("incoming.nuke.alarm.carrier") != null) == enabled, "carrier target alarm line policy");
        h.assertTrue(other.has(S2C.Siren.class) == enabled, "carrier target siren policy");
        h.assertTrue(owner.player.last("incoming.nuke.alarm.armed") != null, "owner lost pre-launch confirmation");
        h.runAfterDelay(NuclearKeys.MIN_WARNING + 2, () -> {
            h.assertTrue((other.player.last("incoming.nuke.launch_detected.carrier") != null) == enabled, "carrier detected line policy");
            h.assertTrue((far.sounds > sounds) == enabled, "carrier detected sound policy");
            h.succeed();
        });
    }

    private static final class Watcher extends ServerGamePacketListenerImpl {
        final List<CustomPacketPayload> got = new ArrayList<>();
        int sounds;
        final MunitionGameTests.Listener player;
        Watcher(MunitionGameTests.Listener player, Connection connection) {
            super(player.server, connection, player, CommonListenerCookie.createInitial(player.getGameProfile(), false));
            this.player = player;
        }
        static Watcher at(GameTestHelper h, String name, Vec3 at) {
            ServerLevel level = h.getLevel();
            var player = new MunitionGameTests.Listener(level, name);
            player.setPos(at);
            Connection connection = new Connection(PacketFlow.SERVERBOUND);
            new EmbeddedChannel(connection);
            Watcher w = new Watcher(player, connection);
            List<ServerPlayer> players = ObfuscationReflectionHelper.getPrivateValue(PlayerList.class, level.getServer().getPlayerList(), "players");
            players.add(player);
            level.players().add(player);
            StrikeGameTests.afterTest(h, () -> { players.remove(player); level.players().remove(player); });
            return w;
        }
        boolean has(Class<?> type) { return got.stream().anyMatch(type::isInstance); }
        @Override
        public void send(Packet<?> packet, @Nullable PacketSendListener listener) {
            if (packet instanceof ClientboundCustomPayloadPacket custom) got.add(custom.payload());
            if (packet instanceof net.minecraft.network.protocol.game.ClientboundSoundPacket sound
                    && sound.getSound().value() == ua.zentix.incoming.registry.ModSounds.SIREN.get()) sounds++;
        }
    }
}
