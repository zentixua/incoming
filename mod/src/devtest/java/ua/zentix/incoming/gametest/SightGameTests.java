package ua.zentix.incoming.gametest;

import com.mojang.authlib.GameProfile;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.PlayerList;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.animal.Pig;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.Scoreboard;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import ua.zentix.incoming.Incoming;
import ua.zentix.incoming.target.Sides;
import ua.zentix.incoming.target.Sight;

import java.util.Map;
import java.util.UUID;

/** Direct visibility ({@link Sight}) and your own by command ({@link Sides}). */
@GameTestHolder(Incoming.MOD_ID)
@PrefixGameTestTemplate(false)
public final class SightGameTests {
    private SightGameTests() {}

    /**
     * Visible in the open and through glass; not visible through stone, invisible (potion), observer and in another
     *  world; range - the player's rendering, clamped by the server.
     */
    @GameTest(template = "pad", batch = "sight")
    public static void seesOnlyWhatEyesSee(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        FakePlayer viewer = player(level, "sight_viewer", Vec3.atBottomCenterOf(h.absolutePos(new BlockPos(1, 1, 1))));
        FakePlayer target = player(level, "sight_target", Vec3.atBottomCenterOf(h.absolutePos(new BlockPos(6, 1, 6))));
        h.assertTrue(Sight.sees(viewer, target), "not visible in the open");
        h.assertFalse(Sight.sees(viewer, viewer), "sees himself");

        for (int z = 0; z < 8; z++) for (int y = 1; y < 5; y++) h.setBlock(new BlockPos(4, y, z), Blocks.STONE);
        h.assertFalse(Sight.sees(viewer, target), "visible through the stone");
        for (int z = 0; z < 8; z++) for (int y = 1; y < 5; y++) h.setBlock(new BlockPos(4, y, z), Blocks.GLASS);
        h.assertTrue(Sight.sees(viewer, target), "not visible through glass");

        target.setInvisible(true);
        h.assertFalse(Sight.sees(viewer, target), "the invisible is visible");
        target.setInvisible(false);
        target.setGameMode(GameType.SPECTATOR);
        h.assertFalse(Sight.sees(viewer, target), "visible to the observer");
        target.setGameMode(GameType.SURVIVAL);
        ServerLevel nether = level.getServer().getLevel(Level.NETHER);
        h.assertTrue(nether != null, "no Nether");
        h.assertFalse(Sight.sees(viewer, player(nether, "sight_nether", target.position())), "visible in another world");
        target.setHealth(0);
        h.assertFalse(Sight.sees(viewer, target), "the dead man is visible");

        Vec3 at = viewer.position();
        h.assertTrue(Sight.within(viewer, at.add(Sight.viewRange(viewer) - 1, 50, 0)), "point in range - out");
        h.assertFalse(Sight.within(viewer, at.add(Sight.viewRange(viewer) * 0.75, 0, Sight.viewRange(viewer) * 0.75)),
                "point beyond the range (diagonally) - in it");
        h.succeed();
    }

    /**
     * Range - like Minecraft: client settings ({@code FakePlayer} does not have it - the real {@code ServerPlayer} is outside the
     *  list of players), constrained by the server range, at least two chunks; the entity also has a tracking range.
     */
    @GameTest(template = "pad", batch = "sight_range")
    public static void rangeIsClampedLikeVanilla(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        PlayerList list = level.getServer().getPlayerList();
        int before = list.getViewDistance();
        StrikeGameTests.afterTest(h, () -> list.setViewDistance(before));
        list.setViewDistance(16);
        Vec3 at = Vec3.atBottomCenterOf(h.absolutePos(new BlockPos(1, 1, 1)));
        h.assertValueEqual(Sight.viewRange(viewer(level, at, 64)), 256, "setting 64 with server 16");
        h.assertValueEqual(Sight.viewRange(viewer(level, at, 5)), 80, "setting 5 with server 16");
        h.assertValueEqual(Sight.viewRange(viewer(level, at, 1)), 32, "setting 1 - at least two chunks");

        ServerPlayer far = viewer(level, at, 64);
        Pig pig = EntityType.PIG.create(level);
        h.assertTrue(pig != null, "no pig");
        // the pig is tracked on 10 chunks (160 blocks) with a server correction - closer to drawing (256); player - to 32
        // chanka, i.e. before drawing
        int pigRange = level.getServer().getScaledTrackingDistance(EntityType.PIG.clientTrackingRange() * 16);
        h.assertTrue(pigRange < 256, "the range of the pig should be less than the drawing: " + pigRange);
        h.assertValueEqual(Sight.range(far, pig), (double) pigRange, "pig range");
        h.assertValueEqual(Sight.range(far, far), 256.0, "player range is no further than drawing");
        h.succeed();
    }

    /** A glance at an unready chunk is “not visible”, and the chunk is not loaded for its sake. */
    @GameTest(template = "pad", batch = "sight")
    public static void clearNeverLoadsChunks(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        Vec3 from = Vec3.atCenterOf(h.absolutePos(new BlockPos(1, 3, 1)));
        Vec3 far = from.add(200_000, 0, 200_000);
        ChunkPos chunk = new ChunkPos(BlockPos.containing(far));
        h.assertTrue(level.getChunkSource().getChunkNow(chunk.x, chunk.z) == null, "the distant chunk is already loaded");
        h.assertFalse(Sight.clear(level, from, far), "visible through unfinished chunks");
        h.assertTrue(level.getChunkSource().getChunkNow(chunk.x, chunk.z) == null, "look loaded chunk");
        h.assertTrue(Sight.clear(level, from, Vec3.atCenterOf(h.absolutePos(new BlockPos(6, 3, 6)))), "not visible on the site");
        h.succeed();
    }

    /** Ours - the same player or one team {@code /team}; without a team and draws (console) - strangers. */
    @GameTest(template = "pad", batch = "sight")
    public static void sidesFollowTeams(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        Vec3 at = Vec3.atBottomCenterOf(h.absolutePos(new BlockPos(1, 1, 1)));
        FakePlayer a = player(level, "sides_a", at), b = player(level, "sides_b", at), c = player(level, "sides_c", at);
        Scoreboard board = level.getScoreboard();
        PlayerTeam red = board.addPlayerTeam("sight_red"), blue = board.addPlayerTeam("sight_blue");
        StrikeGameTests.afterTest(h, () -> {
            board.removePlayerTeam(red);
            board.removePlayerTeam(blue);
        });

        h.assertFalse(Sides.friendly(a, b), "without commands - your own");
        h.assertTrue(Sides.friendly(a, a), "a stranger to himself");
        board.addPlayerToTeam(a.getScoreboardName(), red);
        board.addPlayerToTeam(b.getScoreboardName(), red);
        board.addPlayerToTeam(c.getScoreboardName(), blue);
        h.assertTrue(Sides.friendly(a, b), "one team - strangers");
        h.assertFalse(Sides.friendly(a, c), "different teams - their own");

        UUID ua = a.getUUID();
        h.assertTrue(Sides.friendly(level.getServer(), ua, ua), "the same player by UUID is a stranger");
        // by UUID (owner is offline): names are from the profile cache, the server GameTest does not have it - their own names
        Map<UUID, String> names = Map.of(ua, a.getScoreboardName(), b.getUUID(), b.getScoreboardName(), c.getUUID(), c.getScoreboardName());
        h.assertTrue(Sides.friendly(board, ua, b.getUUID(), names::get), "one team for UUID - strangers");
        h.assertFalse(Sides.friendly(board, ua, c.getUUID(), names::get), "different teams for UUID - their own");
        h.assertFalse(Sides.friendly(board, ua, UUID.randomUUID(), names::get), "player without name");
        h.assertValueEqual(Sides.side(board, ua, names::get), Sides.side(a), "side by UUID and the player on the network are different");
        h.assertFalse(Sides.friendly(level.getServer(), ua, null), "no one's - yours");
        h.assertFalse(Sides.friendly(level.getServer(), null, null), "two draws are yours");
        h.assertFalse(Sides.side(level.getServer(), ua).equals(Sides.side(level.getServer(), c.getUUID())), "same side for different players");
        h.assertValueEqual(Sides.side(a), Sides.side(b), "the team has different sides");
        h.assertFalse(Sides.side(a).equals(Sides.side(c)), "different teams have the same side");
        h.succeed();
    }

    private static FakePlayer player(ServerLevel level, String name, Vec3 at) {
        FakePlayer p = new FakePlayer(level, new GameProfile(UUID.randomUUID(), name));
        p.moveTo(at);
        return p;
    }

    /** A real player outside the list of players with the rendering setting {@code chunks}: {@code FakePlayer} does not accept it. */
    private static ServerPlayer viewer(ServerLevel level, Vec3 at, int chunks) {
        ServerPlayer p = new ServerPlayer(level.getServer(), level, new GameProfile(UUID.randomUUID(), "sight_range"), withView(chunks));
        p.setPos(at); // moveTo at ServerPlayer is calling connection.resetPosition, but there is no connection
        return p;
    }

    private static ClientInformation withView(int chunks) {
        ClientInformation d = ClientInformation.createDefault();
        return new ClientInformation(d.language(), chunks, d.chatVisibility(), d.chatColors(), d.modelCustomisation(), d.mainHand(),
                d.textFilteringEnabled(), d.allowsListing());
    }
}
