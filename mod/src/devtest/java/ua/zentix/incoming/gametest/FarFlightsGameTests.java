package ua.zentix.incoming.gametest;

import com.mojang.authlib.GameProfile;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import io.netty.channel.embedded.EmbeddedChannel;
import net.minecraft.network.Connection;
import net.minecraft.network.PacketSendListener;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.jetbrains.annotations.Nullable;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import ua.zentix.incoming.Incoming;
import ua.zentix.incoming.entity.CruiseMissileEntity;
import ua.zentix.incoming.entity.FlightPhase;
import ua.zentix.incoming.entity.StrikeProjectile;
import ua.zentix.incoming.net.S2C;
import ua.zentix.incoming.registry.ModEntities;
import ua.zentix.incoming.sound.Propagation;
import ua.zentix.incoming.strike.FarFlights;
import ua.zentix.incoming.strike.Hearing;
import ua.zentix.incoming.strike.WeaponSpec;
import ua.zentix.incoming.strike.VirtualFlights;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/** Far-flight selection without ticking flights: real Hearing, seenBy and packet construction. */
@GameTestHolder(Incoming.MOD_ID)
@PrefixGameTestTemplate(false)
public final class FarFlightsGameTests {
    private FarFlightsGameTests() {}

    private static final class Missile extends CruiseMissileEntity {
        int hearing;
        boolean virtual;

        Missile(ServerLevel level, Vec3 pos) {
            super(ModEntities.CRUISE_MISSILE.get(), level);
            setPos(pos);
            speed = 4;
            setDeltaMovement(0, 0, 4);
            setPhase(FlightPhase.CRUISE);
        }

        @Override
        public boolean isVirtual() { return virtual; }

        // Only Hearing reads the airframe in FarFlights.sample here: flights do not tick and boosters are off.
        @Override
        public WeaponSpec.Airframe airframe() {
            hearing++;
            return super.airframe();
        }
    }

    private static final class Watcher extends ServerGamePacketListenerImpl {
        final List<S2C.FarFlight> got = new ArrayList<>();

        Watcher(FakePlayer player, Connection connection) {
            super(player.server, connection, player, CommonListenerCookie.createInitial(player.getGameProfile(), false));
        }

        @Override
        public void send(Packet<?> packet, @Nullable PacketSendListener listener) {
            if (packet instanceof ClientboundCustomPayloadPacket custom && custom.payload() instanceof S2C.FarFlights flights)
                got.addAll(flights.flights());
        }
    }

    @GameTest(template = "range", timeoutTicks = 100, batch = "far_flight_cadence", skyAccess = true)
    public static void audibleEveryTwoTicksVisibleEveryFour(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        Vec3 eye = h.absoluteVec(new Vec3(32, 20, 32));
        FakePlayer player = new FakePlayer(level, new GameProfile(UUID.randomUUID(), "far-flight-cadence"));
        player.setPos(eye);
        Connection connection = new Connection(PacketFlow.SERVERBOUND);
        EmbeddedChannel channel = new EmbeddedChannel(connection);
        Watcher watcher = new Watcher(player, connection);
        StrikeGameTests.afterTest(h, channel::finishAndReleaseAll);
        Missile audible = new Missile(level, eye.add(20, 0, 0));
        Missile visible = new Missile(level, eye.add(6000, 0, 0));
        Missile tracked = new Missile(level, eye.add(21, 0, 0));
        List<StrikeProjectile> own = List.of(audible, visible, tracked);
        var weather = Propagation.Weather.of(level);
        h.assertTrue(Hearing.audible(audible, eye, weather) && !Hearing.audible(visible, eye, weather), "unexpected hearing conditions");
        var map = level.getChunkSource().chunkMap;
        var entry = map.new TrackedEntity(tracked, 64, 1, true);
        entry.seenBy.add(player.connection);
        int[] ticks = {0};
        h.onEachTick(() -> {
            watcher.got.clear();
            tracked.hearing = 0;
            // Register only during the real send handler: these objects never tick or acquire flight tickets.
            level.players().add(player);
            VirtualFlights.get(level).flights().addAll(own);
            map.entityMap.put(tracked.getId(), entry);
            try {
                FarFlights.onServerTick(new ServerTickEvent.Post(() -> true, level.getServer()));
                int tick = level.getServer().getTickCount();
                h.assertTrue(watcher.got.stream().anyMatch(f -> f.id().equals(audible.getUUID())) == (tick % 2 == 0), "audible packet cadence");
                h.assertTrue(watcher.got.stream().anyMatch(f -> f.id().equals(visible.getUUID())) == (tick % 4 == 0), "visible-only packet cadence");
                h.assertTrue(watcher.got.stream().noneMatch(f -> f.id().equals(tracked.getUUID())) && tracked.hearing == 0,
                        "tracked flight reached Hearing or the packet");
            } finally {
                level.players().remove(player);
                VirtualFlights.get(level).flights().removeAll(own);
                map.entityMap.remove(tracked.getId());
            }
            if (++ticks[0] == 8) h.succeed();
        });
    }

    @GameTest(template = "range", timeoutTicks = 100, batch = "far_flight_sample", skyAccess = true)
    public static void trackingIsExcludedBeforeHearingWithSameNearest64(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        Vec3 eye = h.absoluteVec(new Vec3(32, 20, 32));
        FakePlayer player = new FakePlayer(level, new GameProfile(UUID.randomUUID(), "far-flight-watcher"));
        var map = level.getChunkSource().chunkMap;
        List<StrikeProjectile> flights = new ArrayList<>();
        List<Missile> missiles = new ArrayList<>();
        try {
            for (int i = 0; i < 120; i++) {
                // Mixed distances, including ties; distant flights are visible but inaudible.
                int distance = (i * 37 % 120) / 2;
                Missile p = new Missile(level, eye.add(i % 4 == 0 ? 6000 + distance : 20 + distance, 0, 0));
                p.virtual = i % 11 == 0;
                missiles.add(p);
                flights.add(p);
                if (i % 3 == 0) {
                    var tracked = map.new TrackedEntity(p, 64, 1, true);
                    tracked.seenBy.add(player.connection);
                    map.entityMap.put(p.getId(), tracked);
                }
            }
            Propagation.Weather weather = Propagation.Weather.of(level);
            h.assertTrue(S2C.FarFlights.PERIOD == 2 && S2C.FarFlights.FAR_PERIOD == 4, "packet periods changed");
            for (boolean noPlayer : new boolean[]{false, true}) {
                for (boolean silent : new boolean[]{false, true}) {
                    List<Missile> expected = new ArrayList<>();
                    int candidates = 0;
                    for (Missile p : missiles) {
                        boolean audible = Hearing.audible(p, eye, weather);
                        boolean tracked = map.entityMap.containsKey(p.getId());
                        boolean excluded = !noPlayer && !p.isVirtual() && tracked;
                        if (!excluded) candidates++;
                        if (!audible && p.position().subtract(eye).horizontalDistanceSqr() > (silent ? FarFlights.range() * FarFlights.range() : 0)) continue;
                        if (!excluded) expected.add(p);
                    }
                    if (expected.size() > 64) {
                        expected.sort(Comparator.comparingDouble(p -> p.position().subtract(eye).horizontalDistanceSqr()));
                        expected = expected.subList(0, 64);
                    }
                    missiles.forEach(p -> p.hearing = 0);
                    var got = FarFlights.sample(level, flights, eye, noPlayer ? null : player, silent);
                    h.assertTrue(got.stream().map(S2C.FarFlight::id).toList().equals(expected.stream().map(Missile::getUUID).toList()),
                            "UUIDs, order or the nearest-64 limit changed");
                    int calls = missiles.stream().mapToInt(p -> p.hearing).sum();
                    h.assertTrue(calls == candidates, "Hearing was called " + calls + " times instead of " + candidates);
                    for (Missile p : missiles) {
                        if (!noPlayer && !p.isVirtual() && map.entityMap.containsKey(p.getId()))
                            h.assertTrue(p.hearing == 0, "Hearing evaluated an already tracked entity");
                    }
                    for (var f : got) {
                        Missile p = missiles.stream().filter(m -> m.getUUID().equals(f.id())).findFirst().orElseThrow();
                        h.assertTrue(f.audible() == Hearing.audible(p, eye, weather), "audible flag changed");
                    }
                    if (silent && noPlayer) h.assertTrue(got.size() == 64, "test did not reach the packet limit");
                }
            }
            h.succeed();
        } finally {
            for (Missile p : missiles) map.entityMap.remove(p.getId());
        }
    }
}
