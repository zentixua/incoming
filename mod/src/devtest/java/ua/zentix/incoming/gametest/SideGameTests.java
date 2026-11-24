package ua.zentix.incoming.gametest;

import com.mojang.authlib.GameProfile;
import com.mojang.authlib.GameProfileRepository;
import com.mojang.authlib.yggdrasil.ServicesKeySet;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.Services;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.GameProfileCache;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.Scoreboard;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import org.jetbrains.annotations.Nullable;
import ua.zentix.incoming.Incoming;
import ua.zentix.incoming.defense.DefenseWorld;
import ua.zentix.incoming.defense.Interceptor;
import ua.zentix.incoming.defense.InterceptorSpec;
import ua.zentix.incoming.defense.Radar;
import ua.zentix.incoming.entity.DroneEntity;
import ua.zentix.incoming.info.Readout;
import ua.zentix.incoming.launcher.FixedLauncherBlockEntity;
import ua.zentix.incoming.launcher.LauncherLinks;
import ua.zentix.incoming.target.Sides;
import ua.zentix.incoming.target.Sightings;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * One side resolution (roadmap B5, {@link Sides}, {@code SideResolveEvent}): party members are one side for the SAM,
 * spotting, launcher links and the overlay; a membership change applies on the next resolution (at once with a change
 * signal, within {@link Sides#RECHECK} without one); friendliness is symmetric for every pair; an offline owner resolves
 * from memory; a radar sweep asks each owner's side once, not once per track. The party is {@link TestParties}, a
 * provider answering as the FTB Teams and OPAC bridges do (neither mod is in the GameTest runtime; their adapters have
 * unit tests). SAM cases run in batches of their own: a SAM sees every projectile in the world.
 */
@GameTestHolder(Incoming.MOD_ID)
@PrefixGameTestTemplate(false)
public final class SideGameTests {
    private SideGameTests() {}

    /**
     * Two players of one party: the owner's radar sees the mate's Shahed as friendly (a stranger's as hostile), the party
     * shares spotting, the mate may link the owner's launcher and sees its private overlay lines; a stranger none of it.
     */
    @GameTest(template = "runway", timeoutTicks = 200, batch = "side_party", skyAccess = true)
    public static void partyIsOneSide(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        MinecraftServer server = level.getServer();
        ServerPlayer owner = SamGameTests.listener(h, "side_owner", SamGameTests.SAM.east(3));
        ServerPlayer mate = SamGameTests.listener(h, "side_mate", SamGameTests.SAM.west(3));
        ServerPlayer stranger = SamGameTests.listener(h, "side_stranger", SamGameTests.SAM.north(3));
        h.assertFalse(Sides.friendly(server, owner.getUUID(), mate.getUUID()), "friendly before any party");
        TestParties.join(h, owner.getUUID(), "p1", true);
        TestParties.join(h, mate.getUUID(), "p1", true);
        h.assertValueEqual(Sides.side(owner), TestParties.key("p1"), "the owner's side");
        h.assertTrue(Sides.friendly(server, owner.getUUID(), mate.getUUID()) && Sides.friendly(server, mate.getUUID(), owner.getUUID()),
                "one party is not one side");
        h.assertFalse(Sides.friendly(server, owner.getUUID(), stranger.getUUID()), "a stranger is friendly");

        Sightings.spot(level, Sides.side(owner), stranger, Sightings.Source.EYES);
        h.assertTrue(Sightings.contact(level, Sides.side(mate), stranger.getUUID()) != null, "the party does not share spotting");
        h.assertTrue(Sightings.contact(level, Sides.side(stranger), owner.getUUID()) == null, "a stranger shares the party's spotting");

        FixedLauncherBlockEntity launcher = FixedLauncherGameTests.launcher(h, new BlockPos(30, 4, 20), owner.getUUID());
        h.assertTrue(LauncherLinks.refusal(mate, launcher) == null, "the mate may not link the owner's launcher: " + LauncherLinks.refusal(mate, launcher));
        h.assertTrue(LauncherLinks.refusal(stranger, launcher) != null, "a stranger may link the owner's launcher");
        h.assertTrue(Readout.privileged(server, owner.getUUID(), mate), "the mate does not see the owner's private lines");
        h.assertFalse(Readout.privileged(server, owner.getUUID(), stranger), "a stranger sees the owner's private lines");

        DroneEntity mates = SamGameTests.drone(h, 10, mate.getUUID());
        Vec3 antenna = Vec3.atCenterOf(h.absolutePos(SamGameTests.SAM)).add(0, 1, 0);
        h.succeedWhen(() -> {
            Radar.Track byOwner = track(scan(level, antenna, owner.getUUID()), mates);
            Radar.Track byStranger = track(scan(level, antenna, stranger.getUUID()), mates);
            h.assertTrue(byOwner != null && byStranger != null, "the radar does not see the mate's Shahed yet");
            h.assertFalse(byOwner.hostile(), "the owner's radar takes the mate's Shahed for hostile");
            h.assertTrue(byStranger.hostile(), "a stranger's radar takes the party's Shahed for friendly");
        });
    }

    /**
     * Leaving the party ends friendliness on the next resolution: at once with a change signal (vanilla team, FTB Teams),
     * within {@link Sides#RECHECK} ticks without one (OPAC). Contacts stay with the side that recorded them: the leaver
     * takes nothing along, the party keeps them.
     */
    @GameTest(template = "pad", timeoutTicks = 100, batch = "side_leave")
    public static void leavingEndsFriendship(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        MinecraftServer server = level.getServer();
        ServerPlayer a = SamGameTests.listener(h, "side_leave_a", new BlockPos(1, 2, 1));
        ServerPlayer b = SamGameTests.listener(h, "side_leave_b", new BlockPos(2, 2, 1));
        ServerPlayer c = SamGameTests.listener(h, "side_leave_c", new BlockPos(3, 2, 1));
        TestParties.join(h, a.getUUID(), "p2", true);
        TestParties.join(h, b.getUUID(), "p2", true);
        h.assertTrue(Sides.friendly(server, a.getUUID(), b.getUUID()), "the party is not one side");
        Sightings.spot(level, Sides.side(a), c, Sightings.Source.EYES);
        h.assertTrue(Sightings.contact(level, Sides.side(b), c.getUUID()) != null, "the party does not share spotting");

        TestParties.leave(b.getUUID(), true);
        h.assertFalse(Sides.friendly(server, a.getUUID(), b.getUUID()), "friendly after leaving with a change signal");
        h.assertFalse(Sides.friendly(server, b.getUUID(), a.getUUID()), "friendliness after leaving is not symmetric");
        h.assertTrue(Sightings.contact(level, Sides.side(b), c.getUUID()) == null, "the leaver took the party's contact along");
        h.assertTrue(Sightings.contact(level, Sides.side(a), c.getUUID()) != null, "the party lost its contact when a member left");

        // without a signal (an OPAC party change): the cached side holds at most RECHECK ticks
        TestParties.join(h, b.getUUID(), "p2", false);
        h.assertFalse(Sides.friendly(server, a.getUUID(), b.getUUID()), "a change without a signal skipped the cache: the probe is empty");
        int[] at = {-1};
        h.onEachTick(() -> {
            if (at[0] < 0 && Sides.friendly(server, a.getUUID(), b.getUUID())) at[0] = (int) h.getTick();
        });
        long joined = h.getTick();
        h.runAtTickTime(joined + Sides.RECHECK + 2, () -> {
            h.assertTrue(at[0] >= 0 && at[0] - joined <= Sides.RECHECK, "rejoining without a signal applied after " + (at[0] - joined) + " ticks");
            h.assertTrue(Sightings.contact(level, Sides.side(b), c.getUUID()) != null, "the rejoined member does not see the party's contact");
            h.succeed();
        });
    }

    /**
     * Friendliness for every pair over vanilla team, party, both and neither: symmetric, and equal to equal keys;
     * precedence vanilla team over party over the player alone.
     */
    @GameTest(template = "pad", batch = "side_symmetry")
    public static void symmetricForEveryPair(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        MinecraftServer server = level.getServer();
        Scoreboard board = level.getScoreboard();
        PlayerTeam red = board.addPlayerTeam("side_red"), blue = board.addPlayerTeam("side_blue");
        StrikeGameTests.afterTest(h, () -> {
            board.removePlayerTeam(red);
            board.removePlayerTeam(blue);
        });
        // nobody, red, party P, red + party Q, party P, blue + party P
        List<ServerPlayer> p = new ArrayList<>();
        for (int i = 0; i < 6; i++) p.add(SamGameTests.listener(h, "side_sym_" + i, new BlockPos(1 + i, 2, 1)));
        board.addPlayerToTeam(p.get(1).getScoreboardName(), red);
        board.addPlayerToTeam(p.get(3).getScoreboardName(), red);
        board.addPlayerToTeam(p.get(5).getScoreboardName(), blue);
        TestParties.join(h, p.get(2).getUUID(), "P", true);
        TestParties.join(h, p.get(3).getUUID(), "Q", true);
        TestParties.join(h, p.get(4).getUUID(), "P", true);
        TestParties.join(h, p.get(5).getUUID(), "P", true);
        Set<String> friends = Set.of("1-3", "2-4");
        for (int i = 0; i < p.size(); i++) {
            for (int j = 0; j < p.size(); j++) {
                UUID a = p.get(i).getUUID(), b = p.get(j).getUUID();
                boolean ab = Sides.friendly(server, a, b), ba = Sides.friendly(server, b, a);
                h.assertTrue(ab == ba, "not symmetric: " + i + "-" + j);
                h.assertTrue(ab == Sides.friendly(p.get(i), p.get(j)), "live and by-UUID differ: " + i + "-" + j);
                h.assertTrue(ab == (i == j || Sides.side(server, a).equals(Sides.side(server, b))), "friendly is not equal keys: " + i + "-" + j);
                boolean expected = i == j || friends.contains(Math.min(i, j) + "-" + Math.max(i, j));
                h.assertTrue(ab == expected, i + "-" + j + " friendly " + ab + ", expected " + expected);
            }
        }
        h.assertValueEqual(Sides.side(p.get(3)), "minecraft:team/side_red", "the vanilla team does not take precedence over a party");
        h.assertTrue(Sides.side(p.get(0)).startsWith("incoming:player/"), "a player without a side: " + Sides.side(p.get(0)));
        h.assertFalse(Sides.friendly(server, p.get(0).getUUID(), null), "unowned is friendly");
        h.succeed();
    }

    /**
     * An offline owner resolves from memory: the vanilla team by the name in the server's profile cache (whose profile
     * service fails on any lookup) and a party by UUID; no lookup reaches storage or the network.
     */
    @GameTest(template = "pad", batch = "side_offline")
    public static void offlineOwnerFromMemory(GameTestHelper h) throws Exception {
        ServerLevel level = h.getLevel();
        MinecraftServer server = level.getServer();
        AtomicInteger lookups = new AtomicInteger();
        GameProfileRepository repository = (GameProfileRepository) Proxy.newProxyInstance(GameProfileRepository.class.getClassLoader(),
                new Class<?>[] {GameProfileRepository.class}, (proxy, method, args) -> {
                    if (method.getDeclaringClass() == Object.class) return method.invoke(lookups, args);
                    lookups.incrementAndGet();
                    throw new IllegalStateException("profile lookup " + method.getName());
                });
        File file = Files.createTempFile(server.getServerDirectory(), "side-probe", ".json").toFile();
        Field field = MinecraftServer.class.getDeclaredField("services");
        field.setAccessible(true);
        Services before = (Services) field.get(server);
        Scoreboard board = level.getScoreboard();
        PlayerTeam team = board.addPlayerTeam("side_offline");
        try {
            Files.deleteIfExists(file.toPath());
            GameProfileCache cache = new GameProfileCache(repository, file);
            UUID absent = UUID.randomUUID(), partied = UUID.randomUUID();
            cache.add(new GameProfile(absent, "side_offline_owner"));
            Files.deleteIfExists(file.toPath());
            field.set(server, new Services(null, ServicesKeySet.EMPTY, repository, cache));
            ServerPlayer mate = SamGameTests.listener(h, "side_offline_mate", new BlockPos(1, 2, 1));
            board.addPlayerToTeam("side_offline_owner", team);
            board.addPlayerToTeam(mate.getScoreboardName(), team);
            h.assertTrue(server.getPlayerList().getPlayer(absent) == null, "the owner is online");
            h.assertTrue(Sides.friendly(server, absent, mate.getUUID()), "the offline owner's team is not found from the profile cache");
            ServerPlayer friend = SamGameTests.listener(h, "side_offline_friend", new BlockPos(2, 2, 1));
            TestParties.join(h, partied, "off", true);
            TestParties.join(h, friend.getUUID(), "off", true);
            h.assertTrue(Sides.friendly(server, partied, friend.getUUID()), "an offline party member (no name anywhere) is not the party's side");
            h.assertValueEqual(lookups.get(), 0, "profile service lookups");
            h.assertFalse(file.exists(), "the profile file was written back during resolution");
        } finally {
            field.set(server, before);
            board.removePlayerTeam(team);
            Files.deleteIfExists(file.toPath());
            Sides.invalidate();
        }
        h.succeed();
    }

    /**
     * Login forgets the player's cached side: an offline player unknown by name resolves to their party; on login their
     * vanilla team (joined while offline, by name) is their side at once, not after the age bound.
     */
    @GameTest(template = "pad", batch = "side_login")
    public static void loginForgetsTheSide(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        MinecraftServer server = level.getServer();
        Scoreboard board = level.getScoreboard();
        PlayerTeam team = board.addPlayerTeam("side_login");
        StrikeGameTests.afterTest(h, () -> board.removePlayerTeam(team));
        SamGameTests.Listener p = new SamGameTests.Listener(level, "side_login_p");
        board.addPlayerToTeam(p.getScoreboardName(), team);
        TestParties.join(h, p.getUUID(), "login", true);
        h.assertValueEqual(Sides.side(server, p.getUUID()), TestParties.key("login"), "the offline side (no name known)");
        Map<UUID, ServerPlayer> online = SamGameTests.playersByUuid(server.getPlayerList());
        online.put(p.getUUID(), p);
        StrikeGameTests.afterTest(h, () -> online.remove(p.getUUID(), p));
        Sides.onLogin(new PlayerEvent.PlayerLoggedInEvent(p));
        h.assertValueEqual(Sides.side(server, p.getUUID()), "minecraft:team/side_login", "the side right after login");
        h.succeed();
    }

    /**
     * Radar cost with the cache, counted in questions asked (no wall time): one per distinct owner (and the radar's own)
     * after an invalidation however many tracks and sweeps, none for further sweeps while the cache lives; a track's
     * friend-or-foe is otherwise a cached lookup. Twelve Shaheds of three owners, a hundred sweeps.
     */
    @GameTest(template = "runway", timeoutTicks = 200, batch = "side_radar_cost", skyAccess = true)
    public static void radarAsksOncePerOwner(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        MinecraftServer server = level.getServer();
        UUID radar = UUID.randomUUID();
        List<UUID> owners = List.of(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
        Map<UUID, UUID> ownerOf = new HashMap<>();
        for (int i = 0; i < 12; i++) {
            DroneEntity d = SamGameTests.drone(h, 4 + 2 * i, owners.get(i % owners.size()));
            ownerOf.put(d.getUUID(), owners.get(i % owners.size()));
        }
        Vec3 antenna = Vec3.atCenterOf(h.absolutePos(SamGameTests.SAM)).add(0, 1, 0);
        h.succeedWhen(() -> {
            Sides.invalidate();
            Sides.Cache cache = Sides.cache(server);
            long before = cache.resolutions();
            List<Radar.Track> tracks = scan(level, antenna, radar);
            long first = cache.resolutions() - before;
            for (int i = 0; i < 99; i++) scan(level, antenna, radar);
            long rest = cache.resolutions() - before - first;
            Set<UUID> seenOwners = new HashSet<>();
            for (Radar.Track t : tracks) seenOwners.add(ownerOf.get(t.id()));
            h.assertTrue(tracks.size() >= 6, "the radar sees " + tracks.size() + " Shaheds, the probe needs more than one per owner");
            h.assertTrue(first <= seenOwners.size() + 1, "the first sweep asked " + first + " times for " + seenOwners.size() + " owners and "
                    + tracks.size() + " tracks");
            h.assertValueEqual(rest, 0L, "questions in 99 further sweeps");
        });
    }

    /**
     * An interceptor already chasing a Shahed stands down when the Shahed's owner joins the SAM owner's party (friendly
     * rounds are not targets): it ends as {@code FRIENDLY}, the Shahed flies on, and no other missile is fired at it.
     */
    @GameTest(template = "runway", timeoutTicks = 400, batch = "side_pursuit", skyAccess = true)
    public static void pursuitEndsForFriend(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        SamGameTests.air(h, 4, 0);
        ServerPlayer owner = SamGameTests.listener(h, "side_pursuit_owner", SamGameTests.SAM.east(3));
        UUID pilot = UUID.randomUUID();
        TestParties.join(h, owner.getUUID(), "pursuit", true);
        SamGameTests.sam(h, SamGameTests.SAM, owner.getUUID(), 4);
        DroneEntity drone = SamGameTests.drone(h, 16, pilot);
        List<Interceptor> fired = new ArrayList<>();
        boolean[] joined = {false};
        h.onEachTick(() -> {
            for (Interceptor f : DefenseWorld.get(level).flights()) if (!fired.contains(f)) fired.add(f);
            if (!joined[0] && !fired.isEmpty()) {
                joined[0] = true;
                TestParties.join(h, pilot, "pursuit", true);
            }
        });
        h.succeedWhen(() -> {
            h.assertTrue(joined[0], "the SAM did not fire at the stranger's Shahed");
            h.assertTrue(drone.isRemoved(), "the Shahed is still flying");
            h.assertValueEqual(fired.size(), 1, "missiles fired");
            Interceptor f = fired.getFirst();
            h.assertTrue(f.done() && f.end() == Interceptor.End.FRIENDLY, "the pursuit ended as " + f.end());
            h.assertTrue(BlastLog.near(h, new BlockPos(16, 3, 200), 8), "the Shahed did not reach its target");
        });
    }

    private static List<Radar.Track> scan(ServerLevel level, Vec3 antenna, @Nullable UUID owner) {
        return Radar.scan(level, antenna, InterceptorSpec.SAM.radarRange(), InterceptorSpec.SAM.engageRange(), owner);
    }

    @Nullable
    private static Radar.Track track(List<Radar.Track> tracks, DroneEntity d) {
        for (Radar.Track t : tracks) if (t.id().equals(d.getUUID())) return t;
        return null;
    }
}
