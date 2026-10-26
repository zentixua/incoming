package ua.zentix.incoming.gametest;

import com.mojang.authlib.GameProfile;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import ua.zentix.incoming.Incoming;
import ua.zentix.incoming.registry.ModAttachments;
import ua.zentix.incoming.strike.AreaLoader;
import ua.zentix.incoming.strike.ArrivalTickets;
import ua.zentix.incoming.strike.FlightTickets;
import ua.zentix.incoming.strike.StrikeWorld;

import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Arrival priority exists only to keep Incoming's own loads from delaying a player. */
@GameTestHolder(Incoming.MOD_ID)
@PrefixGameTestTemplate(false)
public final class ArrivalScopeGameTests {
    private static final TicketType<UUID> WORK = TicketType.create("incoming_test_arrival_work", Comparator.<UUID>naturalOrder());

    private ArrivalScopeGameTests() {}

    @GameTest(template = "range", batch = "arrival_idle")
    public static void noOwnWorkDoesNotHandlePlayers(GameTestHelper h) {
        ServerLevel level = isolatedLevel(h);
        h.assertTrue(StrikeWorld.get(level).areas().size() == 0, "Another test left active Incoming areas");
        FakePlayer player = player(h, level, "arrival_idle");
        FakePlayer clone = new FakePlayer(level, player.getGameProfile());
        Map<Long, List<String>> before = tickets(level);

        ArrivalTickets.onLogin(new PlayerEvent.PlayerLoggedInEvent(player));
        idle(h, player, "login");
        ArrivalTickets.onChangeDimension(new PlayerEvent.PlayerChangedDimensionEvent(player, Level.NETHER, level.dimension()));
        idle(h, player, "dimension change");
        ArrivalTickets.onLevelTick(new LevelTickEvent.Post(() -> true, level));
        player.moveTo(player.position().add(64, 0, 64));
        ArrivalTickets.onLevelTick(new LevelTickEvent.Post(() -> true, level));
        idle(h, player, "teleport");
        player.setHealth(0);
        ArrivalTickets.onDeath(new LivingDeathEvent(player, level.damageSources().genericKill()));
        ArrivalTickets.onPlayerTick(new PlayerTickEvent.Post(player));
        idle(h, player, "death and dead-player tick");
        ArrivalTickets.onClone(new PlayerEvent.Clone(clone, player, true));
        ArrivalTickets.onRespawn(new PlayerEvent.PlayerRespawnEvent(clone, false));
        ArrivalTickets.onPlayerTick(new PlayerTickEvent.Post(clone));
        ArrivalTickets.onLogout(new PlayerEvent.PlayerLoggedOutEvent(clone));
        idle(h, clone, "clone, respawn, tick and logout");

        ArrivalTickets.Slot slot = new ArrivalTickets.Slot();
        ChunkPos at = player.chunkPosition();
        ArrivalTickets.arrive(level, at, player.getUUID(), slot);
        ArrivalTickets.awaitRespawn(level, at, player.getUUID(), slot);
        ArrivalTickets.moved(level, at, player.getUUID(), slot, 32);
        h.assertFalse(ArrivalTickets.moved(level, new ChunkPos(at.x + 10, at.z), player.getUUID(), slot, 32),
                "Direct movement created an arrival ticket without work");
        h.assertTrue(ArrivalTickets.held(slot) == null, "Direct arrival/respawn acquired an idle ticket");
        h.assertTrue(StrikeWorld.get(level).areas().size() == 0, "Player events acquired Incoming areas");
        h.assertTrue(tickets(level).equals(before), "Player events changed Incoming's actual chunk tickets");
        h.succeed();
    }

    @GameTest(template = "range", batch = "arrival_scope")
    public static void lastOwnAreaReleasesArrivalAndRespawn(GameTestHelper h) {
        ServerLevel level = isolatedLevel(h);
        AreaLoader areas = StrikeWorld.get(level).areas();
        h.assertTrue(areas.size() == 0, "Another test left active Incoming areas");
        int alreadyReleasing = areas.releasing();
        ChunkPos centre = new ChunkPos(h.absolutePos(new BlockPos(32, 11, 32)));
        level.getChunk(centre.x, centre.z);
        UUID first = UUID.randomUUID(), second = UUID.randomUUID();
        StrikeGameTests.afterTest(h, () -> {
            FlightTickets.hold(level, centre, 0, first, false);
            FlightTickets.hold(level, centre, 0, second, false);
        });
        FlightTickets.hold(level, centre, 0, first, true);
        FlightTickets.hold(level, centre, 0, second, true);
        FakePlayer arriving = player(h, level, "arrival_active");
        FakePlayer dead = player(h, level, "respawn_active");
        ArrivalTickets.onLogin(new PlayerEvent.PlayerLoggedInEvent(arriving));
        dead.setHealth(0);
        ArrivalTickets.onDeath(new LivingDeathEvent(dead, level.damageSources().genericKill()));
        h.assertTrue(ArrivalTickets.count(level, arriving.getUUID()) == 1, "Active work did not protect login");
        h.assertTrue(ArrivalTickets.count(level, dead.getUUID()) == 1, "Active work did not protect respawn");

        // Make arrival areas ready so their normal release would leave a queued load ticket.
        for (FakePlayer p : List.of(arriving, dead)) {
            ChunkPos at = ArrivalTickets.held(p.getData(ModAttachments.ARRIVAL.get()));
            for (int dx = -ArrivalTickets.DISTANCE; dx <= ArrivalTickets.DISTANCE; dx++) {
                for (int dz = -ArrivalTickets.DISTANCE; dz <= ArrivalTickets.DISTANCE; dz++) level.getChunk(at.x + dx, at.z + dz);
            }
        }
        ArrivalTickets.onLogout(new PlayerEvent.PlayerLoggedOutEvent(arriving));
        h.assertTrue(areas.releasing() > 0, "The test did not queue a released arrival area");
        ArrivalTickets.onLogin(new PlayerEvent.PlayerLoggedInEvent(arriving));
        // A second location leaves the previous arrival release queued while a live ticket remains.
        arriving.moveTo(arriving.position().add(16, 0, 0));
        ArrivalTickets.onLogin(new PlayerEvent.PlayerLoggedInEvent(arriving));
        FlightTickets.hold(level, centre, 0, first, false);
        h.assertTrue(ArrivalTickets.count(level, arriving.getUUID()) == 1 && ArrivalTickets.count(level, dead.getUUID()) == 1,
                "Ending one flight released priority still needed by another");
        FlightTickets.hold(level, centre, 0, second, false);
        h.assertTrue(ArrivalTickets.count(level, arriving.getUUID()) == 0 && ArrivalTickets.count(level, dead.getUUID()) == 0,
                "The last flight left arrival or respawn tickets alive");
        h.assertTrue(areas.size() == 0, "Arrival tickets counted themselves as work");
        h.assertTrue(areas.releasing() == alreadyReleasing + 2, "Arrival load tickets survived in the release queue: " + areas.describe());
        FakePlayer clone = new FakePlayer(level, dead.getGameProfile());
        ArrivalTickets.onClone(new PlayerEvent.Clone(clone, dead, true));
        idle(h, clone, "clone after the last flight ended");
        ArrivalTickets.onPlayerTick(new PlayerTickEvent.Post(arriving));
        ArrivalTickets.onPlayerTick(new PlayerTickEvent.Post(dead));
        idle(h, arriving, "last flight ended");
        idle(h, dead, "last flight ended while dead");
        ArrivalTickets.onLevelTick(new LevelTickEvent.Post(() -> true, level));
        idle(h, arriving, "idle world tick after a strike");
        h.succeed();
    }

    @GameTest(template = "range", batch = "arrival_dimension_scope")
    public static void onlyDestinationWorkNeedsArrivalPriority(GameTestHelper h) {
        ServerLevel level = isolatedLevel(h);
        ServerLevel other = level.getServer().getLevel(Level.NETHER);
        h.assertTrue(other != null, "The test needs a second dimension");
        ChunkPos centre = new ChunkPos(h.absolutePos(new BlockPos(32, 11, 32)));
        UUID flight = UUID.randomUUID();
        StrikeGameTests.afterTest(h, () -> FlightTickets.hold(level, centre, 0, flight, false));
        FlightTickets.hold(level, centre, 0, flight, true);
        FakePlayer player = player(h, other, "arrival_other");
        ArrivalTickets.onLogin(new PlayerEvent.PlayerLoggedInEvent(player));
        ArrivalTickets.onChangeDimension(new PlayerEvent.PlayerChangedDimensionEvent(player, level.dimension(), other.dimension()));
        ArrivalTickets.onDeath(new LivingDeathEvent(player, other.damageSources().genericKill()));
        idle(h, player, "work in another dimension");

        player.setRespawnPosition(level.dimension(), h.absolutePos(new BlockPos(32, 11, 32)), 0, true, false);
        player.setHealth(0);
        ArrivalTickets.onDeath(new LivingDeathEvent(player, other.damageSources().genericKill()));
        h.assertTrue(ArrivalTickets.count(level, player.getUUID()) == 1, "Work at the respawn destination was ignored");
        ArrivalTickets.onPlayerTick(new PlayerTickEvent.Post(player));
        h.assertTrue(ArrivalTickets.count(level, player.getUUID()) == 1, "The source dimension ended the destination's lease");
        FlightTickets.hold(level, centre, 0, flight, false);
        ArrivalTickets.onPlayerTick(new PlayerTickEvent.Post(player));
        idle(h, player, "destination work ended");
        h.assertTrue(ArrivalTickets.count(level, player.getUUID()) == 0, "Destination retained a respawn ticket");
        h.succeed();
    }

    @GameTest(template = "range", timeoutTicks = 40, batch = "arrival_work_expiry")
    public static void expiringWorkReleasesTicketsWithoutPlayerTicks(GameTestHelper h) {
        ServerLevel level = isolatedLevel(h);
        AreaLoader areas = StrikeWorld.get(level).areas();
        h.assertTrue(areas.size() == 0, "Another test left active Incoming areas");
        ChunkPos centre = new ChunkPos(h.absolutePos(new BlockPos(32, 11, 32)));
        AreaLoader.Area work = new AreaLoader.Area(WORK, centre, 0, UUID.randomUUID());
        ArrivalTickets.Slot arrival = new ArrivalTickets.Slot(), respawn = new ArrivalTickets.Slot();
        UUID arriving = UUID.randomUUID(), dead = UUID.randomUUID();
        StrikeGameTests.afterTest(h, () -> {
            areas.release(level, work);
            ArrivalTickets.release(level.getServer(), arriving, arrival);
            ArrivalTickets.release(level.getServer(), dead, respawn);
        });
        areas.hold(level, work, level.getGameTime() + 5);
        ArrivalTickets.arrive(level, centre, arriving, arrival);
        ArrivalTickets.awaitRespawn(level, centre, dead, respawn);
        h.assertTrue(ArrivalTickets.count(level, arriving) == 1 && ArrivalTickets.count(level, dead) == 1,
                "The test did not acquire both dependent tickets");
        h.runAfterDelay(10, () -> {
            h.assertTrue(areas.size() == 0, "Expired work left arrival tickets alive without player ticks: " + areas.describe());
            h.assertFalse(areas.describe().contains("incoming_arrival"), "Expired work left queued arrival releases");
            h.succeed();
        });
    }

    /** Other batches may still have strike after-effects in the overworld; idle tests need an untouched dimension. */
    private static ServerLevel isolatedLevel(GameTestHelper h) {
        ServerLevel level = h.getLevel().getServer().getLevel(Level.END);
        h.assertTrue(level != null, "The test needs the End dimension");
        return level;
    }

    private static FakePlayer player(GameTestHelper h, ServerLevel level, String name) {
        FakePlayer player = new FakePlayer(level, new GameProfile(UUID.randomUUID(), name));
        BlockPos at = h.absolutePos(new BlockPos(32, 11, 32));
        player.moveTo(Vec3.atCenterOf(at));
        player.setRespawnPosition(level.dimension(), at, 0, true, false);
        level.players().add(player);
        StrikeGameTests.afterTest(h, () -> {
            ArrivalTickets.onLogout(new PlayerEvent.PlayerLoggedOutEvent(player));
            level.players().remove(player);
        });
        return player;
    }

    private static void idle(GameTestHelper h, FakePlayer player, String event) {
        h.assertFalse(player.hasData(ModAttachments.ARRIVAL.get()), event + " allocated/retained player tracking without Incoming work");
        h.assertTrue(ArrivalTickets.count(player.serverLevel(), player.getUUID()) == 0, event + " retained an arrival ticket");
    }

    private static Map<Long, List<String>> tickets(ServerLevel level) {
        Map<Long, List<String>> result = new HashMap<>();
        level.getChunkSource().chunkMap.getDistanceManager().tickets.forEach((pos, at) -> {
            List<String> incoming = at.stream().filter(t -> t.getType().toString().startsWith("incoming_"))
                    .map(Object::toString).sorted().toList();
            if (!incoming.isEmpty()) result.put(pos, incoming);
        });
        return result;
    }
}
