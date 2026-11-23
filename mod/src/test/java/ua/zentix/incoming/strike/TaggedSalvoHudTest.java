package ua.zentix.incoming.strike;

import net.minecraft.SharedConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.players.PlayerList;
import net.minecraft.world.phys.Vec3;
import net.neoforged.fml.loading.LoadingModList;
import net.neoforged.neoforge.network.PacketDistributor;
import org.junit.jupiter.api.Test;
import ua.zentix.incoming.client.hud.Alerts;
import ua.zentix.incoming.entity.StrikeProjectile;
import ua.zentix.incoming.net.ClientHooks;
import ua.zentix.incoming.net.S2C;
import ua.zentix.incoming.nuclear.NuclearEvents;
import ua.zentix.incoming.target.Target;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/** A tagged clear ends the salvo progress line of the salvo it cancelled (A2 review round 2). */
class TaggedSalvoHudTest {
    @Test
    void clearingTheDisplayedTaggedSalvoEndsItsProgress() throws Exception {
        SharedConstants.tryDetectVersion();
        if (LoadingModList.get() == null) LoadingModList.of(List.of(), List.of(), List.of(), List.of(), Map.of());
        Bootstrap.bootStrap();
        var constructor = Class.forName("ua.zentix.incoming.client.IncomingClient$Hooks").getDeclaredConstructor();
        constructor.setAccessible(true);
        ClientHooks hooks = (ClientHooks) constructor.newInstance();
        var countdown = Alerts.class.getDeclaredField("salvoLeft");
        countdown.setAccessible(true);
        UUID owner = UUID.randomUUID();
        String tag = "displayedSalvo";
        Minecraft client = mock(Minecraft.class);
        client.player = mock(LocalPlayer.class);
        when(client.player.getUUID()).thenReturn(owner);
        MinecraftServer server = mock(MinecraftServer.class);
        ServerLevel level = mock(ServerLevel.class);
        PlayerList players = mock(PlayerList.class);
        when(server.getPlayerList()).thenReturn(players);
        when(players.getPlayers()).thenReturn(List.of());
        when(server.getAllLevels()).thenReturn(List.of(level));
        when(server.overworld()).thenReturn(level);
        when(level.getServer()).thenReturn(server);
        when(level.getAllEntities()).thenReturn(List.of());

        SalvoData salvos = new SalvoData();
        Vec3 point = new Vec3(0, 64, 0);
        var salvo = new SalvoData.Salvo(WeaponType.DRONE, 4, 3, 0, new Target.Point(point), point, 0, owner,
                1, Loadout.Nuke.DEFAULT, Waypoints.NONE, null, false, 0, 1);
        salvo.order = UUID.randomUUID();
        salvo.tag = tag;
        salvos.add(salvo);
        VirtualFlights flights = new VirtualFlights();
        StrikeProjectile released = mock(StrikeProjectile.class);
        when(released.round()).thenReturn(RoundRef.of(salvo.order, 0, tag));
        when(released.getUUID()).thenReturn(UUID.randomUUID());
        when(released.ownerId()).thenReturn(owner);
        flights.flights().add(released);
        StrikeWorld world = new StrikeWorld();

        try (var minecraft = mockStatic(Minecraft.class); var packets = mockStatic(PacketDistributor.class);
             var worlds = mockStatic(StrikeWorld.class); var saves = mockStatic(SalvoData.class);
             var virtual = mockStatic(VirtualFlights.class); var nukes = mockStatic(NuclearEvents.class)) {
            minecraft.when(Minecraft::getInstance).thenReturn(client);
            worlds.when(() -> StrikeWorld.get(level)).thenReturn(world);
            saves.when(() -> SalvoData.get(level)).thenReturn(salvos);
            virtual.when(() -> VirtualFlights.get(level)).thenReturn(flights);
            nukes.when(() -> NuclearEvents.get(level)).thenReturn(new NuclearEvents());
            packets.when(() -> PacketDistributor.sendToAllPlayers(any(S2C.Cleared.class))).thenAnswer(call -> {
                hooks.cleared(call.getArgument(0));
                return null;
            });
            hooks.salvoStatus(new S2C.SalvoStatus(WeaponType.DRONE.id(), 1, 4));
            assertEquals(400, countdown.getInt(null), "the one displayed salvo is at 1/4");
            assertEquals(4, ServerActions.clearTag(server, tag, "review"), "all four rounds were recalled");
            assertEquals(0, salvos.remaining(owner));
            assertEquals(0, countdown.getInt(null), "the cancelled salvo should be off the HUD");
        } finally {
            Alerts.reset();
        }
    }
}
