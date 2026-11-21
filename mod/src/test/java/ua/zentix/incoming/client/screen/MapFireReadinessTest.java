package ua.zentix.incoming.client.screen;

import com.electronwill.nightconfig.core.CommentedConfig;
import net.minecraft.SharedConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.dimension.DimensionType;
import net.neoforged.fml.config.IConfigSpec;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.loading.LoadingModList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import ua.zentix.incoming.IncomingConfig;
import ua.zentix.incoming.client.map.MapTarget;
import ua.zentix.incoming.strike.Loadout;
import ua.zentix.incoming.strike.TargetMode;
import ua.zentix.incoming.strike.WeaponType;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.*;

/** The map's Fire follows the remote's order readiness: an invalid yield keeps it inactive, as on the remote. */
class MapFireReadinessTest {
    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        if (LoadingModList.get() == null) LoadingModList.of(List.of(), List.of(), List.of(), List.of(), Map.of());
        Bootstrap.bootStrap();
    }

    @BeforeEach
    void loadConfig() throws ReflectiveOperationException {
        CommentedConfig config = CommentedConfig.inMemory();
        IncomingConfig.SERVER_SPEC.correct(config);
        var constructor = Class.forName("net.neoforged.fml.config.LoadedConfig")
                .getDeclaredConstructor(CommentedConfig.class, Path.class, ModConfig.class);
        constructor.setAccessible(true);
        IncomingConfig.SERVER_SPEC.acceptConfig((IConfigSpec.ILoadedConfig) constructor.newInstance(config, null, null));
    }

    @AfterEach
    void unloadConfig() {
        IncomingConfig.SERVER_SPEC.acceptConfig(null);
    }

    @Test
    void mapDoesNotEnableFireWithAnInvalidYieldInTheRemote() throws Exception {
        Minecraft client = mock(Minecraft.class);
        client.level = mock(ClientLevel.class);
        when(client.level.dimension()).thenReturn(Level.OVERWORLD);
        when(client.level.dimensionType()).thenReturn(mock(DimensionType.class));
        try (var minecraft = mockStatic(Minecraft.class)) {
            minecraft.when(Minecraft::getInstance).thenReturn(client);
            MapTarget.set(client.level, new MapTarget.Place(20, 20));
            RemoteScreen remote = new RemoteScreen();
            Field loadout = RemoteScreen.class.getDeclaredField("loadout");
            loadout.setAccessible(true);
            loadout.set(remote, Loadout.DEFAULT.withWeapon(WeaponType.NUKE).withMode(TargetMode.MAP));
            Field validYield = RemoteScreen.class.getDeclaredField("validYield");
            validYield.setAccessible(true);
            validYield.setBoolean(remote, false); // clear the yield box, then open the map
            MapScreen map = new MapScreen(remote);
            Method canFire = MapScreen.class.getDeclaredMethod("canFire");
            canFire.setAccessible(true);
            assertFalse((boolean) canFire.invoke(map), "map enables Fire while RemoteScreen.fire immediately returns");
        } finally {
            MapTarget.reset();
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1})
    void mapShortcutChecksTheSavedYieldAgainstTheCurrentWorldCap(int aboveCap) throws Exception {
        int cap = IncomingConfig.SERVER.nukeMaxYield.get();
        Loadout saved = Loadout.DEFAULT.withWeapon(WeaponType.NUKE).withMode(TargetMode.MAP);
        Field detached = RemoteScreen.class.getDeclaredField("detached");
        detached.setAccessible(true);
        Object previous = detached.get(null);
        Minecraft client = mock(Minecraft.class);
        client.level = mock(ClientLevel.class);
        when(client.level.dimension()).thenReturn(Level.OVERWORLD);
        when(client.level.dimensionType()).thenReturn(mock(DimensionType.class));
        try (var minecraft = mockStatic(Minecraft.class)) {
            detached.set(null, saved.withNuke(saved.nuke().withYield(cap + aboveCap)));
            minecraft.when(Minecraft::getInstance).thenReturn(client);
            MapTarget.set(client.level, new MapTarget.Place(20, 20));
            // The map key constructs the remote without initializing its editor.
            MapScreen map = new MapScreen(new RemoteScreen());
            Method canFire = MapScreen.class.getDeclaredMethod("canFire");
            canFire.setAccessible(true);
            assertEquals(aboveCap == 0, canFire.invoke(map), "saved yield " + (cap + aboveCap) + ", cap " + cap);
        } finally {
            detached.set(null, previous);
            MapTarget.reset();
        }
    }
}
