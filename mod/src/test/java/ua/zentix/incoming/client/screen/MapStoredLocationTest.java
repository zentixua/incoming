package ua.zentix.incoming.client.screen;

import net.minecraft.SharedConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.phys.Vec3;
import net.neoforged.fml.loading.LoadingModList;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.lwjgl.glfw.GLFW;
import ua.zentix.incoming.client.map.MapTarget;
import ua.zentix.incoming.strike.Loadout;
import ua.zentix.incoming.strike.TargetMode;
import ua.zentix.incoming.strike.Waypoints;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** The map fires at its stored location whatever target mode the console was left in (player or aircraft not picked). */
class MapStoredLocationTest {
    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        if (LoadingModList.get() == null) LoadingModList.of(List.of(), List.of(), List.of(), List.of(), Map.of());
        Bootstrap.bootStrap();
    }

    @Test
    void mapShortcutCanFireAtStoredLocationWhenConsoleHasNoAircraft() throws Exception {
        assertCanFireAtStoredLocation(TargetMode.AIRCRAFT);
    }

    @Test
    void mapShortcutCanFireAtStoredLocationWhenConsoleHasNoPlayer() throws Exception {
        assertCanFireAtStoredLocation(TargetMode.PLAYER);
    }

    @Test
    void mapShortcutCanFireWhenConsoleAlreadyUsesMapMode() throws Exception {
        assertCanFireAtStoredLocation(TargetMode.MAP);
    }

    private static void assertCanFireAtStoredLocation(TargetMode remoteMode) throws Exception {
        Minecraft client = mock(Minecraft.class);
        client.level = mock(ClientLevel.class);
        when(client.level.dimension()).thenReturn(Level.OVERWORLD);
        when(client.level.dimensionType()).thenReturn(mock(DimensionType.class));

        var detached = RemoteScreen.class.getDeclaredField("detached");
        detached.setAccessible(true);
        Object previous = detached.get(null);
        try (var minecraft = mockStatic(Minecraft.class)) {
            minecraft.when(Minecraft::getInstance).thenReturn(client);
            var storedLocation = new MapTarget.Place(20, 20);
            MapTarget.set(client.level, storedLocation);
            detached.set(null, Loadout.DEFAULT.withMode(remoteMode));
            // The map hotkey creates a new RemoteScreen with the saved loadout and no selected aircraft.
            List<Loadout> dispatched = new ArrayList<>();
            RemoteScreen remote = new RemoteScreen() {
                @Override
                void fire(Waypoints via, Optional<Vec3> from) {
                    dispatched.add(loadout()); // Stop at dispatch; keep selection and Enter handling real.
                }
            };
            MapScreen map = new MapScreen(remote);

            assertEquals(Optional.of(storedLocation), MapTarget.get(client.level));
            assertEquals(remoteMode, remote.loadout().mode());
            assertTrue(map.keyPressed(GLFW.GLFW_KEY_ENTER, 0, 0));

            assertAll(
                    () -> assertEquals(TargetMode.MAP, remote.loadout().mode(), "use the already selected map location"),
                    () -> assertEquals(List.of(Loadout.DEFAULT.withMode(TargetMode.MAP)), dispatched, "dispatch the map order"));
        } finally {
            detached.set(null, previous);
            MapTarget.reset();
        }
    }
}
