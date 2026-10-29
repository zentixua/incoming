package ua.zentix.incoming.command;

import com.electronwill.nightconfig.core.CommentedConfig;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.tree.CommandNode;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSource;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;
import net.neoforged.fml.config.IConfigSpec;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.loading.LoadingModList;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import ua.zentix.incoming.IncomingConfig;
import ua.zentix.incoming.nuclear.NuclearStrikes;
import ua.zentix.incoming.strike.Loadout;
import ua.zentix.incoming.strike.ServerActions;
import ua.zentix.incoming.strike.Waypoints;
import ua.zentix.incoming.strike.WeaponType;
import ua.zentix.incoming.target.Target;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/** Exercise the registered Brigadier tree and its real permission predicates without starting a server. */
class IncomingCommandTest {
    private static final Set<String> WEAPONS = Set.of("drone", "shahed", "missile", "bunker", "rocket", "grad", "loiter", "lancet");
    private CommandDispatcher<CommandSourceStack> dispatcher;

    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        if (LoadingModList.get() == null) LoadingModList.of(List.of(), List.of(), List.of(), List.of(), Map.of());
        Bootstrap.bootStrap();
    }

    @BeforeEach
    void register() throws ReflectiveOperationException {
        CommentedConfig config = CommentedConfig.inMemory();
        IncomingConfig.SERVER_SPEC.correct(config);
        // ILoadedConfig is sealed and its only implementation is package-private. Attach an in-memory instance
        // without starting the loader, writing a server config or changing any production permission predicates.
        var constructor = Class.forName("net.neoforged.fml.config.LoadedConfig")
                .getDeclaredConstructor(CommentedConfig.class, Path.class, ModConfig.class);
        constructor.setAccessible(true);
        IConfigSpec.ILoadedConfig loaded = (IConfigSpec.ILoadedConfig) constructor.newInstance(config, null, null);
        IncomingConfig.SERVER_SPEC.acceptConfig(loaded);
        IncomingConfig.SERVER.designatorForEveryone.set(true);
        IncomingConfig.SERVER.nukeEnabled.set(true);
        IncomingConfig.SERVER.nukeOpsOnly.set(true);
        dispatcher = new CommandDispatcher<>();
        IncomingCommand.register(new RegisterCommandsEvent(dispatcher, Commands.CommandSelection.ALL, mock(CommandBuildContext.class)));
    }

    @AfterEach
    void unloadConfig() {
        IncomingConfig.SERVER_SPEC.acceptConfig(null);
    }

    private static CommandSourceStack source(int permission, boolean player) {
        return source(permission, player, null);
    }

    private static CommandSourceStack source(int permission, boolean player, ServerLevel level) {
        Player entity = null;
        if (player) {
            entity = mock(Player.class);
            when(entity.hasPermissions(2)).thenReturn(permission >= 2);
        }
        return new CommandSourceStack(CommandSource.NULL, Vec3.ZERO, Vec2.ZERO, level, permission, "test", Component.literal("test"), null, entity);
    }

    private CommandNode<CommandSourceStack> node(String... path) {
        CommandNode<CommandSourceStack> node = dispatcher.getRoot();
        for (String name : path) {
            node = node.getChild(name);
            assertNotNull(node, String.join(" ", path));
        }
        return node;
    }

    private void parses(String input) {
        var parsed = dispatcher.parse(input, source(2, false));
        assertFalse(parsed.getReader().canRead(), () -> input + ": " + parsed.getExceptions());
        assertNotNull(parsed.getContext().getCommand(), input);
    }

    private static Set<String> children(CommandNode<CommandSourceStack> node) {
        return node.getChildren().stream().map(CommandNode::getName).collect(Collectors.toSet());
    }

    @Test
    void rootAndAllWeaponAliasesHaveExecutableTargets() {
        assertEquals(Set.of("incoming"), children(dispatcher.getRoot()));
        assertEquals(Set.of("help", "menu", "clear", "give", "nuke", "grid", "launcher", "radiation", "salvo",
                "drone", "shahed", "missile", "bunker", "rocket", "grad", "loiter", "lancet"), children(node("incoming")));
        assertEquals(WEAPONS, children(node("incoming", "salvo")));
        for (String weapon : WEAPONS) {
            for (String target : List.of("", " at 10 64 20", " at ~ ~", " target")) parses("incoming " + weapon + target);
            for (String target : List.of("", " me", " look", " at 10 64 20", " at ~ ~", " target")) {
                parses("incoming salvo " + weapon + " 2 5" + target);
            }
        }
        for (String command : List.of("help", "menu", "clear", "give", "give @a", "radiation", "radiation clear @a",
                "grid status", "grid node list", "launcher 0 64 0", "launcher 0 64 0 fire", "launcher 0 64 0 clear",
                "launcher 0 64 0 load 1", "launcher 0 64 0 owner none")) parses("incoming " + command);
    }

    @Test
    void aliasesDispatchTheIntendedWeaponAndExactPoint() throws CommandSyntaxException {
        ServerLevel level = mock(ServerLevel.class);
        Vec3 point = new Vec3(10.25, 64.5, 20.75);
        ServerActions.Aim aim = new ServerActions.Aim(new Target.Point(point), point, null);
        Map<String, WeaponType> aliases = Map.of("drone", WeaponType.DRONE, "shahed", WeaponType.DRONE,
                "missile", WeaponType.MISSILE, "bunker", WeaponType.BUNKER, "rocket", WeaponType.ROCKET,
                "grad", WeaponType.ROCKET, "loiter", WeaponType.LOITER, "lancet", WeaponType.LOITER);
        for (var alias : aliases.entrySet()) {
            try (var actions = mockStatic(ServerActions.class)) {
                actions.when(() -> ServerActions.dispatch(level, "test", 0, alias.getValue(), 1, 0, aim,
                        Loadout.Nuke.DEFAULT, Waypoints.NONE, null)).thenReturn(true);
                assertEquals(1, dispatcher.execute("incoming " + alias.getKey() + " at 10.25 64.5 20.75", source(2, false, level)), alias.getKey());
                actions.verify(() -> ServerActions.dispatch(level, "test", 0, alias.getValue(), 1, 0, aim,
                        Loadout.Nuke.DEFAULT, Waypoints.NONE, null));
            }
        }
    }

    @Test
    void nuclearCommandsShareGroundDefaultAndKeepExplicitAir() throws CommandSyntaxException {
        ServerLevel level = mock(ServerLevel.class);
        Vec3 point = new Vec3(10.25, 64.5, 20.75);
        ServerActions.Aim aim = new ServerActions.Aim(new Target.Point(point), point, null);
        IncomingConfig.SERVER.nukeDefaultYield.set(6);
        for (String suffix : List.of("", " 4", " 4 ground", " 4 air")) {
            int yield = suffix.isEmpty() ? 6 : 4;
            boolean air = suffix.endsWith(" air");
            Loadout.Nuke expected = new Loadout.Nuke(yield, air);
            try (var actions = mockStatic(ServerActions.class); var nuclear = mockStatic(NuclearStrikes.class)) {
                actions.when(() -> ServerActions.dispatch(level, "test", 0, WeaponType.NUKE, 1, 0, aim,
                        expected, Waypoints.NONE, null)).thenReturn(true);
                assertEquals(1, dispatcher.execute("incoming nuke at 10.25 64.5 20.75" + suffix, source(2, false, level)));
                actions.verify(() -> ServerActions.dispatch(level, "test", 0, WeaponType.NUKE, 1, 0, aim,
                        expected, Waypoints.NONE, null));
                nuclear.when(() -> NuclearStrikes.ground(level, point)).thenReturn(point);
                assertEquals(1, dispatcher.execute("incoming nuke now at 10.25 64.5 20.75" + suffix, source(2, false, level)));
                nuclear.verify(() -> NuclearStrikes.detonateNow(level, point, yield, air, null));
            }
        }
    }

    @Test
    void rootAndAdministrativeBranchesRespectPermissionLevels() {
        for (boolean everyone : new boolean[]{false, true}) {
            IncomingConfig.SERVER.designatorForEveryone.set(everyone);
            for (boolean player : new boolean[]{false, true}) {
                for (int permission = 0; permission <= 4; permission++) {
                    CommandSourceStack s = source(permission, player);
                    assertEquals(permission >= 2 || player && everyone, node("incoming").canUse(s));
                    for (String branch : List.of("give", "grid", "launcher", "radiation")) {
                        assertEquals(permission >= 2, node("incoming", branch).canUse(s), branch);
                    }
                    assertEquals(permission >= 2, node("incoming", "salvo", "missile", "count", "spread", "from").canUse(s));
                }
            }
        }
    }

    @Test
    void nuclearEnablementAndOperatorOnlyRulesApplyToTheActualTree() {
        for (boolean enabled : new boolean[]{false, true}) {
            IncomingConfig.SERVER.nukeEnabled.set(enabled);
            for (boolean opsOnly : new boolean[]{false, true}) {
                IncomingConfig.SERVER.nukeOpsOnly.set(opsOnly);
                for (boolean player : new boolean[]{false, true}) {
                    for (int permission = 0; permission <= 4; permission++) {
                        CommandSourceStack s = source(permission, player);
                        assertEquals(enabled && (permission >= 2 || !opsOnly && player), node("incoming", "nuke").canUse(s));
                        assertEquals(permission >= 2, node("incoming", "nuke", "now").canUse(s));
                    }
                }
            }
        }
        IncomingConfig.SERVER.designatorForEveryone.set(false);
        IncomingConfig.SERVER.nukeOpsOnly.set(false);
        IncomingConfig.SERVER.nukeEnabled.set(true);
        assertFalse(node("incoming", "nuke").canUse(source(0, true)));
    }

    @Test
    void routeAndLauncherSyntaxPreservesWeaponCapabilities() {
        for (String weapon : WEAPONS) {
            parses("incoming salvo " + weapon + " 1 0 at 10 20 from 30 40");
        }
        for (String weapon : List.of("drone", "shahed", "missile", "loiter", "lancet")) {
            parses("incoming salvo " + weapon + " 1 0 at 10 20 from 30 40 via 50 60");
            parses("incoming launcher 0 64 0 mission " + weapon + " 1 0 10 64 20 via 30 40");
        }
        String route = "incoming salvo missile 1 0 me via" + " 10 20".repeat(Waypoints.MAX);
        parses(route);
        assertThrows(CommandSyntaxException.class, () -> dispatcher.execute(route + " 30 40", source(2, false)));
        for (String weapon : List.of("rocket", "grad", "bunker")) {
            assertEquals(Set.of("me", "look", "at", "target", "from"), children(node("incoming", "salvo", weapon, "count", "spread")));
        }
        assertEquals(Set.of("drone", "shahed", "missile", "rocket", "grad", "loiter", "lancet"),
                children(node("incoming", "launcher", "launcher", "mission")));
    }

    @Test
    void invalidArgumentsAndInsufficientPermissionsAreRefusedBeforeExecution() {
        for (String command : List.of("salvo missile 0 0", "salvo missile " + (Loadout.MAX_COUNT + 1) + " 0",
                "salvo missile 1 -1", "salvo missile 1 " + (Loadout.MAX_SPREAD + 1), "nuke 0", "nuke " + (Loadout.Nuke.MAX_YIELD + 1),
                "launcher 0 64 0 load 0", "missile at 10", "salvo nuke 1 0", "unknown")) {
            assertThrows(CommandSyntaxException.class, () -> dispatcher.execute("incoming " + command, source(2, false)), command);
        }
        for (String command : List.of("give", "grid status", "launcher 0 64 0", "radiation", "nuke now at 0 64 0",
                "salvo missile 1 0 me from 0 0")) {
            assertThrows(CommandSyntaxException.class, () -> dispatcher.execute("incoming " + command, source(1, true)), command);
        }
        assertThrows(CommandSyntaxException.class, () -> dispatcher.execute("incoming help", source(1, false)));
        IncomingConfig.SERVER.designatorForEveryone.set(false);
        assertThrows(CommandSyntaxException.class, () -> dispatcher.execute("incoming help", source(1, true)));
    }

    @Test
    void consoleGetsThePlayerOnlyRefusalForLookAndItemCommands() throws CommandSyntaxException {
        for (String command : List.of("menu", "give", "radiation", "radiation clear", "nuke", "nuke now", "drone", "shahed",
                "missile", "bunker", "rocket", "grad", "loiter", "lancet", "salvo missile 1 0 look")) {
            CommandSyntaxException refusal = assertThrows(CommandSyntaxException.class,
                    () -> dispatcher.execute("incoming " + command, source(2, false)), command);
            Component message = (Component) refusal.getRawMessage();
            assertEquals("permissions.requires.player", ((TranslatableContents) message.getContents()).getKey(), command);
        }
        assertEquals(1, dispatcher.execute("incoming help", source(2, false)));
    }

    @Test
    void launcherLookupRefusesUnloadedChunksWithoutReadingOrLoadingThem() {
        ServerLevel level = mock(ServerLevel.class);
        ServerChunkCache chunks = mock(ServerChunkCache.class);
        when(level.getChunkSource()).thenReturn(chunks);
        CommandSourceStack source = source(2, false, level);
        CommandSyntaxException unloaded = assertThrows(CommandSyntaxException.class,
                () -> dispatcher.execute("incoming launcher 0 64 0 fire", source));
        assertEquals("incoming.fixed_launcher.not_loaded", ((TranslatableContents) ((Component) unloaded.getRawMessage()).getContents()).getKey());
        verify(chunks).getChunkNow(0, 0);
        verify(level, never()).getBlockEntity(any(BlockPos.class));

        when(chunks.getChunkNow(0, 0)).thenReturn(mock(LevelChunk.class));
        CommandSyntaxException missing = assertThrows(CommandSyntaxException.class,
                () -> dispatcher.execute("incoming launcher 0 64 0 fire", source));
        assertEquals("incoming.fixed_launcher.none_here", ((TranslatableContents) ((Component) missing.getRawMessage()).getContents()).getKey());
        verify(level).getBlockEntity(new BlockPos(0, 64, 0));
    }
}
