package ua.zentix.incoming.client;

import net.minecraft.SharedConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.MinecraftServer;
import net.neoforged.fml.loading.LoadingModList;
import net.neoforged.neoforge.network.PacketDistributor;
import org.junit.jupiter.api.Test;
import ua.zentix.incoming.client.hud.Alerts;
import ua.zentix.incoming.net.ClientHooks;
import ua.zentix.incoming.net.S2C;
import ua.zentix.incoming.strike.ServerActions;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/** A tagged recall leaves the owner's other salvo progress on screen (A2 review). */
class TaggedClearAlertsTest {
    @Test
    void clearingAnEmptyTagLeavesTheOwnersCurrentSalvoProgress() throws Exception {
        SharedConstants.tryDetectVersion();
        if (LoadingModList.get() == null) LoadingModList.of(List.of(), List.of(), List.of(), List.of(), Map.of());
        Bootstrap.bootStrap();
        var constructor = Class.forName("ua.zentix.incoming.client.IncomingClient$Hooks").getDeclaredConstructor();
        constructor.setAccessible(true);
        ClientHooks hooks = (ClientHooks) constructor.newInstance();
        var progress = Alerts.class.getDeclaredField("salvoLeft");
        progress.setAccessible(true);
        UUID owner = UUID.randomUUID();
        Minecraft client = mock(Minecraft.class);
        client.player = mock(LocalPlayer.class);
        when(client.player.getUUID()).thenReturn(owner);
        MinecraftServer server = mock(MinecraftServer.class);
        when(server.getAllLevels()).thenReturn(List.of());

        try (var minecraft = mockStatic(Minecraft.class); var packets = mockStatic(PacketDistributor.class)) {
            minecraft.when(Minecraft::getInstance).thenReturn(client);
            packets.when(() -> PacketDistributor.sendToAllPlayers(any(S2C.Cleared.class))).thenAnswer(call -> {
                hooks.cleared(call.getArgument(0));
                return null;
            });
            hooks.salvoStatus(new S2C.SalvoStatus(0, 1, 4));
            int remaining = progress.getInt(null);
            assertEquals(0, ServerActions.recall(server, owner, "missing", "review").rounds());
            assertEquals(remaining, progress.getInt(null), "a different salvo is still launching");
        } finally {
            Alerts.reset();
        }
    }
}
