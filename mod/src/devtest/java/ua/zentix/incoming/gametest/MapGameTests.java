package ua.zentix.incoming.gametest;

import com.mojang.authlib.GameProfile;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import org.jetbrains.annotations.Nullable;
import ua.zentix.incoming.Incoming;
import ua.zentix.incoming.IncomingConfig;
import ua.zentix.incoming.net.S2C;
import ua.zentix.incoming.registry.ModAttachments;
import ua.zentix.incoming.strike.ServerActions;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/** Remote control map on the server: who does it show. */
@GameTestHolder(Incoming.MOD_ID)
@PrefixGameTestTemplate(false)
public final class MapGameTests {
    private MapGameTests() {}

    /**
     * Players on the map without reconnaissance ({@link ServerActions#mapPlayers}; {@code FakePlayer} NeoForge - he does not send packets
     *  and is not included in the list of players, so the list is transmitted itself): only in the dimension of the one who asked and no further {@code map_range};
     *  not himself, not the observer, not invisible; without rights to the remote control and more than once in 4 tick - no response; disabled
     * in world settings - empty list.
     */
    @GameTest(template = "pad", batch = "map_players")
    public static void mapPlayersShowsOnlyWhomItShould(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        ServerLevel nether = level.getServer().getLevel(Level.NETHER);
        h.assertTrue(nether != null, "no Nether");
        int range = IncomingConfig.SERVER.mapRange.get();
        boolean everyone = IncomingConfig.SERVER.designatorForEveryone.get(), shown = IncomingConfig.SERVER.mapPlayers.get();
        boolean rules = IncomingConfig.SERVER.sightRules.get();
        StrikeGameTests.afterTest(h, () -> {
            IncomingConfig.SERVER.mapRange.set(range);
            IncomingConfig.SERVER.designatorForEveryone.set(everyone);
            IncomingConfig.SERVER.mapPlayers.set(shown);
            IncomingConfig.SERVER.sightRules.set(rules);
        });
        // without reconnaissance - all players are where they are now (with reconnaissance - SpottingGameTests)
        IncomingConfig.SERVER.sightRules.set(false);
        IncomingConfig.SERVER.mapRange.set(256);
        IncomingConfig.SERVER.designatorForEveryone.set(true);
        IncomingConfig.SERVER.mapPlayers.set(true);

        Vec3 at = Vec3.atCenterOf(h.absolutePos(new net.minecraft.core.BlockPos(8, 2, 8)));
        FakePlayer viewer = player(level, "map_viewer", at);
        FakePlayer near = player(level, "map_near", at.add(200, 0, -100));
        FakePlayer far = player(level, "map_far", at.add(250, 0, 100)); // √(250² + 100²) ≈ 269 > 256
        FakePlayer spectator = player(level, "map_spectator", at.add(10, 0, 0));
        spectator.setGameMode(GameType.SPECTATOR);
        FakePlayer invisible = player(level, "map_invisible", at.add(0, 0, 10));
        invisible.setInvisible(true);
        FakePlayer elsewhere = player(nether, "map_nether", at);
        List<ServerPlayer> all = List.of(viewer, near, far, spectator, invisible, elsewhere);

        h.assertValueEqual(names(ServerActions.mapPlayers(viewer, all)), Set.of("map_near"), "on the map");
        S2C.MapPlayer mark = ServerActions.mapPlayers(fresh(viewer), all).players().getFirst();
        h.assertTrue(mark.id().equals(near.getUUID()) && mark.x() == near.getX() && mark.z() == near.getZ(), "the label is not there: " + mark);

        viewer.setData(ModAttachments.LAST_MAP_PLAYERS.get(), level.getGameTime());
        h.assertTrue(ServerActions.mapPlayers(viewer, all) == null, "answer more often than once in 4 tick");
        viewer.setData(ModAttachments.LAST_MAP_PLAYERS.get(), level.getGameTime() - 4);
        h.assertTrue(ServerActions.mapPlayers(viewer, all) != null, "no answer via 4 tick");

        IncomingConfig.SERVER.mapRange.set(300);
        h.assertValueEqual(names(ServerActions.mapPlayers(fresh(viewer), all)), Set.of("map_near", "map_far"), "map range 300");

        // disabled in world settings: the map does not draw, but the remote control still selects a target from them
        IncomingConfig.SERVER.mapPlayers.set(false);
        S2C.MapPlayers off = ServerActions.mapPlayers(fresh(viewer), all);
        h.assertTrue(off != null && !off.shown(), "turned off in the world settings, but the map draws");
        h.assertValueEqual(names(off), Set.of("map_near", "map_far"), "remote control targets when the map is turned off");
        IncomingConfig.SERVER.mapPlayers.set(true);

        // not an operator (FakePlayer without rights), remote control - only for operators
        IncomingConfig.SERVER.designatorForEveryone.set(false);
        h.assertTrue(ServerActions.mapPlayers(fresh(viewer), all) == null, "without rights to the remote control - answer");
        h.succeed();
    }

    private static FakePlayer player(ServerLevel level, String name, Vec3 at) {
        FakePlayer p = new FakePlayer(level, new GameProfile(UUID.randomUUID(), name));
        p.moveTo(at);
        return p;
    }

    /** Remove the pause between requests: the next one asks how in a second. */
    private static ServerPlayer fresh(ServerPlayer p) {
        p.removeData(ModAttachments.LAST_MAP_PLAYERS.get());
        return p;
    }

    private static Set<String> names(@Nullable S2C.MapPlayers marks) {
        if (marks == null) throw new AssertionError("no answer");
        return marks.players().stream().map(S2C.MapPlayer::name).collect(Collectors.toSet());
    }
}
