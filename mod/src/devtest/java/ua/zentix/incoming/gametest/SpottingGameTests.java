package ua.zentix.incoming.gametest;

import com.mojang.authlib.GameProfile;
import net.minecraft.commands.arguments.EntityAnchorArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.animal.Pig;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.Scoreboard;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import org.jetbrains.annotations.Nullable;
import ua.zentix.incoming.Incoming;
import ua.zentix.incoming.IncomingConfig;
import ua.zentix.incoming.entity.CruiseMissileEntity;
import ua.zentix.incoming.net.S2C;
import ua.zentix.incoming.registry.ModAttachments;
import ua.zentix.incoming.registry.ModEntities;
import ua.zentix.incoming.strike.CameraLink;
import ua.zentix.incoming.strike.ServerActions;
import ua.zentix.incoming.target.Sides;
import ua.zentix.incoming.target.Sightings;
import ua.zentix.incoming.target.Target;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Intelligence ({@link Sightings}): who the party sees and what follows from this for the remote control ({@link ServerActions#sighted}) and
 *  map. {@code FakePlayer} NeoForge is not added to the world: inspection and map get the list of players themselves.
 */
@GameTestHolder(Incoming.MOD_ID)
@PrefixGameTestTemplate(false)
public final class SpottingGameTests {
    private static final BlockPos A = new BlockPos(1, 1, 1), B = new BlockPos(6, 1, 6);

    private SpottingGameTests() {}

    /**
     * Eyes: a stranger is in the field of view and without obstacles seen beyond the viewer - the whole team knows him; back, behind the wall
     *  and no one’s own.
     */
    @GameTest(template = "pad", batch = "spotting")
    public static void eyesSpotForTheWholeTeam(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        Scoreboard board = level.getScoreboard();
        PlayerTeam blue = board.addPlayerTeam("spot_blue");
        StrikeGameTests.afterTest(h, () -> board.removePlayerTeam(blue));
        FakePlayer a = player(h, "spot_a", A), b = player(h, "spot_b", B), mate = player(h, "spot_mate", new BlockPos(1, 1, 6));
        board.addPlayerToTeam(a.getScoreboardName(), blue);
        board.addPlayerToTeam(mate.getScoreboardName(), blue);
        List<ServerPlayer> all = List.of(a, b, mate);
        mate.lookAt(EntityAnchorArgument.Anchor.EYES, mate.getEyePosition().add(-5, 0, 0));
        b.lookAt(EntityAnchorArgument.Anchor.EYES, b.getEyePosition().add(5, 0, 5));

        a.lookAt(EntityAnchorArgument.Anchor.EYES, a.getEyePosition().subtract(b.getEyePosition().subtract(a.getEyePosition())));
        Sightings.scan(level, all);
        h.assertTrue(Sightings.contact(level, Sides.side(a), b.getUUID()) == null, "noticed with my back");

        a.lookAt(EntityAnchorArgument.Anchor.EYES, b.getEyePosition());
        Sightings.scan(level, all);
        Sightings.Contact c = Sightings.contact(level, Sides.side(mate), b.getUUID());
        h.assertTrue(c != null && c.source() == Sightings.Source.EYES && c.kind() == Sightings.Kind.PLAYER
                && c.name().equals("spot_b") && c.pos().equals(b.position()), "the team does not know what was noticed: " + c);
        h.assertTrue(Sightings.contact(level, Sides.side(a), mate.getUUID()) == null, "marked one of his own as a stranger");
        h.assertTrue(Sightings.contact(level, Sides.side(b), a.getUUID()) == null, "the one who turned away noticed");

        // you can’t see behind the wall even at close range
        FakePlayer d = player(h, "spot_d", A);
        d.lookAt(EntityAnchorArgument.Anchor.EYES, b.getEyePosition());
        for (int z = 0; z < 8; z++) for (int y = 1; y < 5; y++) h.setBlock(new BlockPos(3, y, z), Blocks.STONE);
        Sightings.scan(level, List.of(d, b));
        h.assertTrue(Sightings.contact(level, Sides.side(d), b.getUUID()) == null, "noticed through the wall");
        h.succeed();
    }

    /** The camera of its projectile: while the player is looking through his eyes, the projectile notices someone whom the player himself cannot see from behind the wall. */
    @GameTest(template = "pad", batch = "spotting")
    public static void projectileCameraSpots(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        FakePlayer a = player(h, "spot_cam", A), b = player(h, "spot_cam_b", B);
        for (int z = 0; z < 8; z++) for (int y = 1; y < 5; y++) h.setBlock(new BlockPos(3, y, z), Blocks.STONE);
        a.lookAt(EntityAnchorArgument.Anchor.EYES, b.getEyePosition());
        Vec3 rail = Vec3.atCenterOf(h.absolutePos(new BlockPos(5, 3, 1)));
        CruiseMissileEntity cam = ModEntities.CRUISE_MISSILE.get().create(level);
        h.assertTrue(cam != null, "no rocket");
        cam.placeOnLauncher(rail, 0, 0, 100_000, 0, new Target.Point(rail.add(0, 0, 3000)), rail.add(0, 0, 3000), a.getUUID());
        level.addFreshEntity(cam);
        StrikeGameTests.afterTest(h, cam::discard);

        Sightings.scan(level, List.of(a, b));
        h.assertTrue(Sightings.contact(level, Sides.side(a), b.getUUID()) == null, "saw through the wall");
        // the camera's view is its own (the client sends the camera rotation), and not the last turn of the player on the server: he looks at b
        Vec3 toB = b.getEyePosition().subtract(cam.position());
        float yaw = (float) (Mth.atan2(toB.z, toB.x) * Mth.RAD_TO_DEG) - 90;
        float pitch = (float) -(Mth.atan2(toB.y, toB.horizontalDistance()) * Mth.RAD_TO_DEG);
        CameraLink.receive(a, cam.getUUID(), yaw + 180, 0);
        Sightings.scan(level, List.of(a, b));
        h.assertTrue(Sightings.contact(level, Sides.side(a), b.getUUID()) == null, "the camera turned away noticed");
        CameraLink.receive(a, cam.getUUID(), yaw, pitch);
        Sightings.scan(level, List.of(a, b));
        Sightings.Contact c = Sightings.contact(level, Sides.side(a), b.getUUID());
        h.assertTrue(c != null && c.source() == Sightings.Source.CAMERA, "the projectile camera did not notice: " + c);

        // someone else's projectile is not the player's camera
        CameraLink.receive(b, cam.getUUID(), yaw, pitch);
        h.assertTrue(CameraLink.current(b).isEmpty(), "someone else's projectile is received by the camera");
        h.succeed();
    }

    /**
     * Remote control at someone else's player, whom the shooter himself cannot see now: unnoticed by the party - refusal; noticed only
     * that - he himself, as a noticed target; seen before - place where seen; your own and mobs - without reconnaissance rules.
     */
    @GameTest(template = "pad", batch = "spotting", timeoutTicks = 60)
    public static void strikesOnlyAtSpotted(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        FakePlayer a = player(h, "scout_a", A), b = player(h, "scout_b", B);
        // the target of the order is searched in the world: the player must be in it
        level.addNewPlayer(b);
        StrikeGameTests.afterTest(h, () -> level.removePlayerImmediately(b, Entity.RemovalReason.DISCARDED));
        ServerActions.Aim live = ServerActions.atPlayer(b);
        h.assertTrue(ServerActions.sighted(a, live, t -> false) == null, "blow to the unnoticed");

        Vec3 seenAt = b.position();
        Sightings.spot(level, Sides.side(a), b, Sightings.Source.EYES);
        h.assertTrue(sightedAt(ServerActions.sighted(a, live, t -> false), live), "just saw it - not by himself");
        h.assertTrue(sightedAt(ServerActions.sighted(a, live, t -> true), live), "sees for himself - not by himself");
        b.moveTo(seenAt.add(0, 0, -3));

        Pig pig = EntityType.PIG.create(level);
        h.assertTrue(pig != null, "no pig");
        pig.moveTo(Vec3.atBottomCenterOf(h.absolutePos(B)));
        level.addFreshEntity(pig);
        StrikeGameTests.afterTest(h, pig::discard);
        ServerActions.Aim atPig = ServerActions.atPlayer(pig);
        h.assertTrue(sightedAt(ServerActions.sighted(a, atPig, t -> false), atPig), "mob under intelligence rule");

        h.runAfterDelay(Sightings.CURRENT + 5, () -> {
            ServerActions.Aim old = ServerActions.sighted(a, live, t -> false);
            h.assertTrue(old != null && old.target() instanceof Target.Point p && p.pos().equals(seenAt.add(0, 1, 0)),
                    "not least: " + (old == null ? null : old.target()));

            Scoreboard board = level.getScoreboard();
            PlayerTeam red = board.addPlayerTeam("scout_red");
            board.addPlayerToTeam(a.getScoreboardName(), red);
            board.addPlayerToTeam(b.getScoreboardName(), red);
            boolean friendly = sightedAt(ServerActions.sighted(a, live, t -> false), live);
            board.removePlayerTeam(red);
            h.assertTrue(friendly, "in its own way - a rule of intelligence");
            h.succeed();
        });
    }

    /** The order was accepted as the sighted target {@code aim} where it was seen. */
    private static boolean sightedAt(@Nullable ServerActions.Aim got, ServerActions.Aim aim) {
        return got != null && got.target() instanceof Target.Sighted s && s.quarry().equals(aim.target()) && s.seen().equals(aim.point());
    }

    /** Forgotten: through {@code sight_memory} there is nothing noticed either for the remote control or for the map. */
    @GameTest(template = "pad", batch = "spotting_memory", timeoutTicks = 60)
    public static void spottedIsForgotten(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        int memory = IncomingConfig.SERVER.sightMemory.get();
        StrikeGameTests.afterTest(h, () -> IncomingConfig.SERVER.sightMemory.set(memory));
        IncomingConfig.SERVER.sightMemory.set(1);
        FakePlayer a = player(h, "forget_a", A), b = player(h, "forget_b", B);
        Sightings.spot(level, Sides.side(a), b, Sightings.Source.EYES);
        h.assertTrue(Sightings.contact(level, Sides.side(a), b.getUUID()) != null, "not noticed");
        h.runAfterDelay(25, () -> {
            h.assertTrue(Sightings.contact(level, Sides.side(a), b.getUUID()) == null, "not forgotten in a second");
            h.assertTrue(Sightings.contacts(level, Sides.side(a)).isEmpty(), "on the list after oblivion");
            h.succeed();
        });
    }

    /**
     * Map with reconnaissance: your own on command - where they are now; stranger - only noticed and where he was seen; without
     * intelligence in the settings - that's it.
     */
    @GameTest(template = "pad", batch = "spotting_map")
    public static void mapShowsTeamAndSpotted(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        boolean everyone = IncomingConfig.SERVER.designatorForEveryone.get(), rules = IncomingConfig.SERVER.sightRules.get();
        Scoreboard board = level.getScoreboard();
        PlayerTeam green = board.addPlayerTeam("map_green");
        StrikeGameTests.afterTest(h, () -> {
            IncomingConfig.SERVER.designatorForEveryone.set(everyone);
            IncomingConfig.SERVER.sightRules.set(rules);
            board.removePlayerTeam(green);
        });
        IncomingConfig.SERVER.designatorForEveryone.set(true);
        IncomingConfig.SERVER.sightRules.set(true);
        FakePlayer viewer = player(h, "smap_viewer", A), mate = player(h, "smap_mate", new BlockPos(6, 1, 1)),
                enemy = player(h, "smap_enemy", B);
        board.addPlayerToTeam(viewer.getScoreboardName(), green);
        board.addPlayerToTeam(mate.getScoreboardName(), green);
        List<ServerPlayer> all = List.of(viewer, mate, enemy);

        Map<String, S2C.MapPlayer> marks = byName(ServerActions.mapPlayers(fresh(viewer), all));
        h.assertValueEqual(marks.keySet(), java.util.Set.of("smap_mate"), "no reconnaissance on the map");
        h.assertTrue(marks.get("smap_mate").friendly(), "yours is not marked as yours");

        Vec3 seenAt = enemy.position();
        Sightings.spot(level, Sides.side(viewer), enemy, Sightings.Source.EYES);
        enemy.moveTo(seenAt.add(0, 0, -4));
        marks = byName(ServerActions.mapPlayers(fresh(viewer), all));
        S2C.MapPlayer e = marks.get("smap_enemy");
        h.assertTrue(e != null && !e.friendly() && e.kind() == Sightings.Kind.PLAYER && e.x() == seenAt.x && e.z() == seenAt.z && e.age() == 0,
                "spotted in the wrong place: " + e);

        IncomingConfig.SERVER.sightRules.set(false);
        marks = byName(ServerActions.mapPlayers(fresh(viewer), all));
        h.assertTrue(marks.containsKey("smap_enemy") && marks.get("smap_enemy").z() == enemy.getZ(), "no rules - not where he is now");
        h.succeed();
    }

    private static FakePlayer player(GameTestHelper h, String name, BlockPos at) {
        FakePlayer p = new FakePlayer(h.getLevel(), new GameProfile(UUID.randomUUID(), name));
        p.moveTo(Vec3.atBottomCenterOf(h.absolutePos(at)));
        return p;
    }

    /** Remove the pause between map requests. */
    private static ServerPlayer fresh(ServerPlayer p) {
        p.removeData(ModAttachments.LAST_MAP_PLAYERS.get());
        return p;
    }

    private static Map<String, S2C.MapPlayer> byName(@Nullable S2C.MapPlayers marks) {
        if (marks == null) throw new AssertionError("no answer");
        return marks.players().stream().collect(Collectors.toMap(S2C.MapPlayer::name, m -> m));
    }
}
