package ua.zentix.incoming.gametest;

import com.mojang.authlib.GameProfile;
import dev.ryanhcode.sable.companion.SubLevelAccess;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ChunkLevel;
import net.minecraft.server.level.FullChunkStatus;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.phys.Vec3;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import org.jetbrains.annotations.Nullable;
import ua.zentix.incoming.Incoming;
import ua.zentix.incoming.compat.SubLevels;
import ua.zentix.incoming.registry.ModAttachments;
import ua.zentix.incoming.strike.ArrivalTickets;
import ua.zentix.incoming.strike.FlightTickets;
import ua.zentix.incoming.util.Terrain;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

/**
 * The player arrival or respawn site loads before salvo areas ({@link ArrivalTickets}).
 */
@GameTestHolder(Incoming.MOD_ID)
@PrefixGameTestTemplate(false)
public final class ArrivalGameTests {
    /** Player-site loading: a level-33 ticket on one chunk, like {@code UNKNOWN} for synchronous {@code getChunk}. */
    private static final TicketType<ChunkPos> PLAYER_LOAD = TicketType.create("incoming_test_player_load", Comparator.comparingLong(ChunkPos::toLong));
    private static final int SALVO = 30, SPREAD = 150;
    private static final BlockPos RANGE_CENTER = new BlockPos(32, 11, 32);
    /** An unloaded chunk waits this long before reloading (disk write). */
    private static final int SETTLE_TICKS = 60;

    private ArrivalGameTests() {}

    /**
     * A previously visited site (chunk P and surroundings generated, then unloaded), entered 5 ticks after a salvo
     * requesting 30 areas with spread 150 in fresh terrain: arrival ticket and P loading as requested by the player
     * tick. Without the arrival ticket, P queues behind salvo areas (levels 29–33 versus 33), and the server waits
     * within a tick. With it, P queues first: pass if P is ready before any whole salvo area that had more than half
     * its chunks unready at arrival. Check order, not a fraction: how many area chunks finish while P loads depends
     * on CPU cores and machine load (the queue cannot overtake generation tasks already assigned to threads).
     * A "less than half" fraction fails on a busy machine (774 of 888 with a neighboring build on the same 4 cores),
     * even though P goes first.
     */
    @GameTest(template = "range", timeoutTicks = 3600, batch = "arrival", skyAccess = true)
    public static void arrivalLoadsBeforeSalvoAreas(GameTestHelper h) {
        arrivalDuringSalvo(h, FlightTickets.DISTANCE);
    }

    /**
     * The same during a Lancet salvo: its target area is larger ({@link FlightTickets#LOITER_DISTANCE}, center level 27).
     * A level-28 arrival ticket made the arrival site wait for the whole salvo: ready after 2276 ticks, after all 888 area chunks.
     */
    @GameTest(template = "range", timeoutTicks = 3600, batch = "arrival_loiter", skyAccess = true)
    public static void arrivalLoadsBeforeLoiterAreas(GameTestHelper h) {
        arrivalDuringSalvo(h, FlightTickets.LOITER_DISTANCE);
    }

    private static void arrivalDuringSalvo(GameTestHelper h, int distance) {
        ServerLevel level = h.getLevel();
        ChunkPos base = new ChunkPos(h.absolutePos(BlockPos.ZERO));
        ChunkPos p = new ChunkPos(base.x + 190, base.z);
        // surroundings of P out to arrival-ticket distance plus 2, as at a previously visited site
        for (int dx = -7; dx <= 7; dx++) {
            for (int dz = -7; dz <= 7; dz++) level.getChunk(p.x + dx, p.z + dz, ChunkStatus.FULL, true);
        }
        StrikeGameTests.gameSpeed(h);
        UUID who = UUID.randomUUID();
        ArrivalTickets.Slot slot = new ArrivalTickets.Slot();
        List<ChunkPos> targets = new ArrayList<>();
        List<UUID> keys = new ArrayList<>();
        StrikeGameTests.afterTest(h, () -> {
            ArrivalTickets.release(level.getServer(), who, slot);
            level.getChunkSource().chunkMap.getDistanceManager().removeTicket(PLAYER_LOAD, p, ChunkLevel.byStatus(FullChunkStatus.FULL), p);
            for (int i = 0; i < targets.size(); i++) FlightTickets.hold(level, targets.get(i), distance, keys.get(i), false);
        });
        int[] tick = {0}, unloadedAt = {-1}, salvoAt = {-1}, arrivedAt = {-1}, pendingAtArrival = {-1};
        // salvo areas, mostly unready at arrival: none may become ready before P
        List<ChunkPos> racing = new ArrayList<>();
        h.onEachTick(() -> {
            tick[0]++;
            if (salvoAt[0] < 0) {
                // P is unloaded and stored on disk
                if (level.getChunkSource().chunkMap.getVisibleChunkIfPresent(p.toLong()) != null) {
                    unloadedAt[0] = -1;
                    return;
                }
                if (unloadedAt[0] < 0) unloadedAt[0] = tick[0];
                if (tick[0] - unloadedAt[0] < SETTLE_TICKS) return;
                Random rnd = new Random(20260930L);
                int cx = base.x - 190, cz = base.z + 190;
                for (int i = 0; i < SALVO; i++) {
                    int bx = cx * 16 + rnd.nextInt(2 * SPREAD + 1) - SPREAD, bz = cz * 16 + rnd.nextInt(2 * SPREAD + 1) - SPREAD;
                    ChunkPos target = new ChunkPos(bx >> 4, bz >> 4);
                    UUID key = UUID.randomUUID();
                    FlightTickets.hold(level, target, distance, key, true);
                    targets.add(target);
                    keys.add(key);
                }
                salvoAt[0] = tick[0];
                return;
            }
            if (arrivedAt[0] < 0) {
                if (tick[0] - salvoAt[0] < 5) return;
                // arrival: the login event installs the ticket; the player tick requests its chunk
                ArrivalTickets.arrive(level, p, who, slot);
                level.getChunkSource().chunkMap.getDistanceManager().addTicket(PLAYER_LOAD, p, ChunkLevel.byStatus(FullChunkStatus.FULL), p);
                pendingAtArrival[0] = pending(level, targets, distance);
                // areas with more than half their chunks unready at arrival: an almost-ready area could finish through its last tasks
                // already assigned to generation threads
                int area = (2 * distance + 1) * (2 * distance + 1);
                for (ChunkPos t : targets) if (!racing.contains(t) && pending(level, List.of(t), distance) * 2 > area) racing.add(t);
                arrivedAt[0] = tick[0];
                h.assertTrue(!racing.isEmpty(), "salvo areas were ready immediately: the check tested nothing");
                return;
            }
            boolean ready = Terrain.ready(level, p.x, p.z);
            for (ChunkPos t : racing) {
                if (pending(level, List.of(t), distance) == 0) {
                    throw new GameTestAssertException("arrival site waited for salvo areas: area at " + t + " ready after "
                            + (tick[0] - arrivedAt[0]) + " ticks since arrival, site: " + (ready ? "same tick" : "not yet"));
                }
            }
            if (!ready) return;
            Incoming.LOG.info("Arrival during salvo (areas {}): P ready after {} ticks, area chunks unready {} of {} at arrival",
                    distance, tick[0] - arrivedAt[0], pending(level, targets, distance), pendingAtArrival[0]);
            h.assertTrue(ArrivalTickets.count(level, who) == 1, "arrival ticket removed too early");
            h.succeed();
        });
    }

    /**
     * Player jump ({@link ArrivalTickets#moved}): a step smaller than {@link ArrivalTickets#JUMP} chunks or a site
     * held by player tickets creates no ticket; a jump to an unheld site creates one, replacing the previous ticket;
     * the first tick in the world only remembers position.
     */
    @GameTest(template = "range", batch = "arrival_jump")
    public static void jumpHoldsArrivalTicket(GameTestHelper h) {
        holdWork(h);
        ServerLevel level = h.getLevel();
        ChunkPos c = new ChunkPos(h.absolutePos(BlockPos.ZERO));
        UUID who = UUID.randomUUID();
        ArrivalTickets.Slot slot = new ArrivalTickets.Slot();
        int r = ArrivalTickets.DISTANCE - 2, playerLevel = ChunkLevel.byStatus(FullChunkStatus.ENTITY_TICKING);
        var distances = level.getChunkSource().chunkMap.getDistanceManager();
        ChunkPos covered = new ChunkPos(c.x + 60, c.z);
        StrikeGameTests.afterTest(h, () -> {
            ArrivalTickets.release(level.getServer(), who, slot);
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    ChunkPos pos = new ChunkPos(covered.x + dx, covered.z + dz);
                    distances.removeTicket(TicketType.PLAYER, pos, playerLevel, pos);
                }
            }
        });
        h.assertFalse(ArrivalTickets.moved(level, c, who, slot, 32), "first tick in the world created a ticket");
        h.assertFalse(ArrivalTickets.moved(level, new ChunkPos(c.x + ArrivalTickets.JUMP - 1, c.z), who, slot, 32),
                "step smaller than a jump created a ticket");
        ChunkPos far = new ChunkPos(c.x + 30, c.z - 30);
        h.assertTrue(ArrivalTickets.moved(level, far, who, slot, 32), "jump to an unheld site created no ticket");
        h.assertTrue(far.equals(ArrivalTickets.held(slot)) && ArrivalTickets.count(level, who) == 1, "jump ticket is not at the new site");
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                ChunkPos pos = new ChunkPos(covered.x + dx, covered.z + dz);
                distances.addTicket(TicketType.PLAYER, pos, playerLevel, pos);
            }
        }
        h.assertFalse(ArrivalTickets.moved(level, covered, who, slot, 32), "jump to a site held by player tickets created a ticket");
        h.assertTrue(far.equals(ArrivalTickets.held(slot)), "a jump needing no ticket removed the previous ticket");
        h.succeed();
    }

    /**
     * Teleport to fresh terrain: NeoForge loads the destination synchronously ({@code Entity.setPosRaw → Level.getChunk},
     * {@code UNKNOWN} ticket), then removes the ticket a tick later. A jump ticket created in the same tick holds
     * that loading ring: no holder within 11 chunks enters unloading before player tickets take hold (none exist here).
     * Otherwise, with neighbors still generating, a ring holder later revived by player tickets spins the vanilla unload
     * task until each tick ends ({@link ArrivalTickets}, rig: 50 ms/tick). The second check repeats without a ticket:
     * the ring enters unloading, otherwise the first check would prove nothing.
     */
    @GameTest(template = "range", timeoutTicks = 200, batch = "arrival_teleport", skyAccess = true)
    public static void teleportRingStaysLoaded(GameTestHelper h) {
        teleportRing(h, 260, true);
    }

    @GameTest(template = "range", timeoutTicks = 200, batch = "arrival_teleport_bare", skyAccess = true)
    public static void teleportRingDropsWithoutArrival(GameTestHelper h) {
        teleportRing(h, -260, false);
    }

    private static void teleportRing(GameTestHelper h, int offset, boolean arrival) {
        holdWork(h);
        ServerLevel level = h.getLevel();
        ChunkPos base = new ChunkPos(h.absolutePos(BlockPos.ZERO));
        ChunkPos p = new ChunkPos(base.x + offset, base.z + offset);
        UUID who = UUID.randomUUID();
        ArrivalTickets.Slot slot = new ArrivalTickets.Slot();
        StrikeGameTests.afterTest(h, () -> ArrivalTickets.release(level.getServer(), who, slot));
        var map = level.getChunkSource().chunkMap;
        h.assertTrue(map.getVisibleChunkIfPresent(p.toLong()) == null, "teleport site already loaded");
        ArrivalTickets.moved(level, base, who, slot, 32);
        // teleport: synchronous destination load as in NeoForge, then world-tick end in the same tick
        level.getChunk(p.x, p.z);
        if (arrival) h.assertTrue(ArrivalTickets.moved(level, p, who, slot, 32), "jump created no ticket");
        // holders raised by the synchronous load enter unloading when their level exceeds loading or the holder is already gone
        // (a fresh chunk passes toDrop and pendingUnloads in one tick)
        Map<Long, ChunkHolder> ring = new HashMap<>();
        for (int dx = -11; dx <= 11; dx++) {
            for (int dz = -11; dz <= 11; dz++) {
                long c = ChunkPos.asLong(p.x + dx, p.z + dz);
                ChunkHolder holder = map.getVisibleChunkIfPresent(c);
                if (holder != null) ring.put(c, holder);
            }
        }
        h.assertTrue(ring.size() > 1, "synchronous load did not raise a ring");
        int[] tick = {0};
        h.onEachTick(() -> {
            tick[0]++;
            int dropped = 0;
            for (Map.Entry<Long, ChunkHolder> e : ring.entrySet()) {
                ChunkHolder now = map.getVisibleChunkIfPresent(e.getKey());
                if (now != e.getValue() || !ChunkLevel.isLoaded(now.getTicketLevel())) dropped++;
            }
            if (arrival && dropped > 0) {
                throw new GameTestAssertException(dropped + " holders in the synchronous-load ring entered unloading after " + tick[0] + " ticks");
            }
            if (!arrival && dropped > 0) h.succeed();
            if (tick[0] < 20) return;
            if (arrival) h.succeed();
            else throw new GameTestAssertException("without a jump ticket, the synchronous-load ring did not enter unloading within 20 ticks");
        });
    }

    /** The arrival ticket expires after {@link ArrivalTickets#LIFESPAN} ticks; a new arrival replaces the previous one. */
    @GameTest(template = "range", timeoutTicks = ArrivalTickets.LIFESPAN + 100, batch = "arrival_lifespan")
    public static void arrivalTicketExpires(GameTestHelper h) {
        holdWork(h);
        ServerLevel level = h.getLevel();
        ChunkPos c = new ChunkPos(h.absolutePos(BlockPos.ZERO));
        UUID who = UUID.randomUUID();
        ArrivalTickets.Slot slot = new ArrivalTickets.Slot();
        StrikeGameTests.afterTest(h, () -> ArrivalTickets.release(level.getServer(), who, slot));
        ArrivalTickets.arrive(level, c, who, slot);
        ArrivalTickets.arrive(level, new ChunkPos(c.x + 1, c.z), who, slot);
        h.assertTrue(ArrivalTickets.count(level, who) == 1, "new arrival did not replace the previous ticket");
        long placed = level.getGameTime();
        h.onEachTick(() -> {
            long age = level.getGameTime() - placed;
            int n = ArrivalTickets.count(level, who);
            if (age < ArrivalTickets.LIFESPAN) {
                if (n != 1) throw new GameTestAssertException("arrival ticket removed after " + age + " ticks, too early");
                return;
            }
            if (n != 0) {
                if (age > ArrivalTickets.LIFESPAN + 2) throw new GameTestAssertException("arrival ticket still present after " + age + " ticks");
                return;
            }
            h.succeed();
        });
    }

    /**
     * The arrival ticket is removed once player tickets ({@code TicketType.PLAYER}) hold a square of radius
     * {@code DISTANCE − 2}, limited by view distance. Player tickets are installed manually without a player here:
     * mock players fail on Create packets.
     */
    @GameTest(template = "range", batch = "arrival_players")
    public static void arrivalReleasedWhenPlayersHoldPlace(GameTestHelper h) {
        holdWork(h);
        ServerLevel level = h.getLevel();
        ChunkPos c = new ChunkPos(h.absolutePos(BlockPos.ZERO));
        UUID who = UUID.randomUUID();
        ArrivalTickets.Slot slot = new ArrivalTickets.Slot();
        int r = ArrivalTickets.DISTANCE - 2, playerLevel = ChunkLevel.byStatus(FullChunkStatus.ENTITY_TICKING);
        var distances = level.getChunkSource().chunkMap.getDistanceManager();
        List<ChunkPos> placed = new ArrayList<>();
        StrikeGameTests.afterTest(h, () -> {
            ArrivalTickets.release(level.getServer(), who, slot);
            for (ChunkPos pos : placed) distances.removeTicket(TicketType.PLAYER, pos, playerLevel, pos);
        });
        ChunkPos corner = new ChunkPos(c.x + r, c.z + r);
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                ChunkPos pos = new ChunkPos(c.x + dx, c.z + dz);
                if (pos.equals(corner)) continue;
                distances.addTicket(TicketType.PLAYER, pos, playerLevel, pos);
                placed.add(pos);
            }
        }
        // without one corner of the square, the ticket remains
        ArrivalTickets.arrive(level, c, who, slot);
        ArrivalTickets.tick(level.getServer(), who, slot, 32, false);
        h.assertTrue(ArrivalTickets.count(level, who) == 1, "arrival ticket removed although no player holds the square corner");
        // view distance smaller than the square: the view-distance square suffices
        ArrivalTickets.tick(level.getServer(), who, slot, r - 1, false);
        h.assertTrue(ArrivalTickets.count(level, who) == 0, "arrival ticket remains although players hold the view-distance square");
        // the full square
        ArrivalTickets.arrive(level, c, who, slot);
        distances.addTicket(TicketType.PLAYER, corner, playerLevel, corner);
        placed.add(corner);
        ArrivalTickets.tick(level.getServer(), who, slot, 32, false);
        h.assertTrue(ArrivalTickets.count(level, who) == 0, "arrival ticket remains although players hold the full square");
        // slot empty: the next tick removes nothing and does not crash
        ArrivalTickets.tick(level.getServer(), who, slot, 32, false);
        h.succeed();
    }

    /**
     * The respawn-site ticket (installed at death) remains while the dead player ticks, longer than the arrival-ticket
     * lifespan and lease, even when player tickets hold the site. Respawn (arrival at the new site) replaces it, and
     * the arrival ticket is again removed according to player tickets.
     */
    @GameTest(template = "range", timeoutTicks = ArrivalTickets.LIFESPAN + 100, batch = "arrival_respawn")
    public static void respawnTicketWaitsForRespawn(GameTestHelper h) {
        holdWork(h);
        ServerLevel level = h.getLevel();
        ChunkPos c = new ChunkPos(h.absolutePos(BlockPos.ZERO));
        UUID who = UUID.randomUUID();
        ArrivalTickets.Slot slot = new ArrivalTickets.Slot();
        int r = ArrivalTickets.DISTANCE - 2, playerLevel = ChunkLevel.byStatus(FullChunkStatus.ENTITY_TICKING);
        var distances = level.getChunkSource().chunkMap.getDistanceManager();
        List<ChunkPos> placed = new ArrayList<>();
        StrikeGameTests.afterTest(h, () -> {
            ArrivalTickets.release(level.getServer(), who, slot);
            for (ChunkPos pos : placed) distances.removeTicket(TicketType.PLAYER, pos, playerLevel, pos);
        });
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                ChunkPos pos = new ChunkPos(c.x + dx, c.z + dz);
                distances.addTicket(TicketType.PLAYER, pos, playerLevel, pos);
                placed.add(pos);
            }
        }
        ArrivalTickets.awaitRespawn(level, c, who, slot);
        long placedAt = level.getGameTime();
        h.onEachTick(() -> {
            ArrivalTickets.tick(level.getServer(), who, slot, 32, true);
            if (ArrivalTickets.count(level, who) != 1) {
                throw new GameTestAssertException("respawn-site ticket removed after " + (level.getGameTime() - placedAt) + " ticks, player still dead");
            }
            if (level.getGameTime() - placedAt <= ArrivalTickets.LIFESPAN + 20) return;
            // respawn elsewhere: arrival ticket there, respawn-site ticket removed
            ArrivalTickets.arrive(level, new ChunkPos(c.x + 20, c.z), who, slot);
            h.assertTrue(ArrivalTickets.count(level, who) == 1, "respawn did not replace the respawn-site ticket");
            // arrival at a site held by players: now an arrival ticket, removed by its tick (the arrival ticket's "dead" flag
            // has no effect; a remaining respawn-ticket flag would extend it on tick)
            ArrivalTickets.arrive(level, c, who, slot);
            ArrivalTickets.tick(level.getServer(), who, slot, 32, true);
            h.assertTrue(ArrivalTickets.count(level, who) == 0, "arrival ticket after respawn remained a respawn ticket");
            h.succeed();
        });
    }

    /** An unrenewed respawn-site ticket (player no longer ticks) expires after its lease. */
    @GameTest(template = "range", timeoutTicks = ArrivalTickets.LEASE + 100, batch = "arrival_respawn")
    public static void respawnTicketLeaseExpires(GameTestHelper h) {
        holdWork(h);
        ServerLevel level = h.getLevel();
        ChunkPos c = new ChunkPos(h.absolutePos(BlockPos.ZERO).offset(16 * 30, 0, 0));
        UUID who = UUID.randomUUID();
        ArrivalTickets.Slot slot = new ArrivalTickets.Slot();
        StrikeGameTests.afterTest(h, () -> ArrivalTickets.release(level.getServer(), who, slot));
        ArrivalTickets.awaitRespawn(level, c, who, slot);
        long placedAt = level.getGameTime();
        h.onEachTick(() -> {
            long age = level.getGameTime() - placedAt;
            int n = ArrivalTickets.count(level, who);
            if (age < ArrivalTickets.LEASE) {
                if (n != 1) throw new GameTestAssertException("respawn-site ticket removed before lease expiry: after " + age + " ticks");
                return;
            }
            if (age < ArrivalTickets.LEASE + 5) return;
            h.assertTrue(n == 0, "respawn-site ticket still present after " + age + " ticks without player ticks");
            h.succeed();
        });
    }

    /**
     * Event handlers with a NeoForge {@code FakePlayer} (absent from the player list, sends no packets, unlike the
     * GameTest mock player): death installs a bed ticket, dead-player tick holds it, {@code Clone} transfers its slot
     * to the new object, respawn moves it to the spawn site, logout removes it; logout while dead and a revived player
     * tick remove it too. No respawn point means no ticket: vanilla {@code START} holds the world spawn.
     */
    @GameTest(template = "range", batch = "arrival_respawn_events")
    public static void respawnEventsMoveTicket(GameTestHelper h) {
        holdWork(h);
        ServerLevel level = h.getLevel();
        BlockPos base = h.absolutePos(BlockPos.ZERO);
        BlockPos bed = base.offset(16 * 20, 0, 0);
        List<FakePlayer> made = new ArrayList<>();
        StrikeGameTests.afterTest(h, () -> {
            for (FakePlayer p : made) ArrivalTickets.release(level.getServer(), p.getUUID(), p.getData(ModAttachments.ARRIVAL.get()));
        });
        // death → death screen → respawn → logout
        GameProfile profile = new GameProfile(UUID.randomUUID(), "incoming_arrival_a");
        FakePlayer dead = died(level, profile, bed, made);
        UUID who = profile.getId();
        h.assertTrue(ArrivalTickets.count(level, who) == 1 && new ChunkPos(bed).equals(held(dead)), "death created no bed ticket: " + held(dead));
        ArrivalTickets.onPlayerTick(new PlayerTickEvent.Post(dead));
        h.assertTrue(ArrivalTickets.count(level, who) == 1, "dead-player tick removed the respawn-site ticket");
        FakePlayer neu = new FakePlayer(level, profile);
        made.add(neu);
        neu.moveTo(Vec3.atCenterOf(base.offset(16 * 40, 0, 0)));
        ArrivalTickets.onClone(new PlayerEvent.Clone(neu, dead, true));
        ArrivalTickets.onRespawn(new PlayerEvent.PlayerRespawnEvent(neu, false));
        h.assertTrue(ArrivalTickets.count(level, who) == 1 && neu.chunkPosition().equals(held(neu)),
                "respawn did not move the ticket to the spawn site: tickets " + ArrivalTickets.count(level, who) + ", slot "
                        + held(neu) + ", player at " + neu.chunkPosition());
        ArrivalTickets.onLogout(new PlayerEvent.PlayerLoggedOutEvent(neu));
        h.assertTrue(ArrivalTickets.count(level, who) == 0, "logout did not remove the arrival ticket");
        // logout from the death screen
        GameProfile quitter = new GameProfile(UUID.randomUUID(), "incoming_arrival_b");
        FakePlayer gone = died(level, quitter, bed, made);
        h.assertTrue(ArrivalTickets.count(level, quitter.getId()) == 1, "death created no ticket");
        ArrivalTickets.onLogout(new PlayerEvent.PlayerLoggedOutEvent(gone));
        h.assertTrue(ArrivalTickets.count(level, quitter.getId()) == 0, "logout while dead did not remove the respawn-site ticket");
        // another mod canceled death and healed the player
        GameProfile revived = new GameProfile(UUID.randomUUID(), "incoming_arrival_c");
        FakePlayer alive = died(level, revived, bed, made);
        alive.setHealth(alive.getMaxHealth());
        ArrivalTickets.onPlayerTick(new PlayerTickEvent.Post(alive));
        h.assertTrue(ArrivalTickets.count(level, revived.getId()) == 0, "live-player tick did not remove the respawn-site ticket");
        // without a respawn point: START holds world spawn
        GameProfile homeless = new GameProfile(UUID.randomUUID(), "incoming_arrival_d");
        died(level, homeless, null, made);
        if (level.getGameRules().getInt(GameRules.RULE_SPAWN_CHUNK_RADIUS) > 0) {
            h.assertTrue(ArrivalTickets.count(level, homeless.getId()) == 0, "ticket at world spawn held by START");
        } else {
            h.assertTrue(ArrivalTickets.count(level, homeless.getId()) == 1, "without a spawn point in START chunks, no ticket");
        }
        h.succeed();
    }

    /**
     * Bed on a Sable craft: respawn point lies in the plot grid (Sable respawns at its own world point); no ticket
     * there. Otherwise a level-26 ticket would enter the plot grid, bypassing Sable's guard ({@code addRegionTicket}).
     */
    @GameTest(template = "range", timeoutTicks = 200, batch = "arrival_respawn_craft", skyAccess = true)
    public static void respawnOnCraftNoTicket(GameTestHelper h) {
        if (!ModList.get().isLoaded("sable")) {
            h.succeed();
            return;
        }
        holdWork(h);
        ServerLevel level = h.getLevel();
        MinecraftServer server = level.getServer();
        BlockPos floor = RANGE_CENTER.above();
        BlockPos.betweenClosed(floor.offset(-1, 0, -1), floor.offset(1, 0, 1)).forEach(p -> h.setBlock(p, Blocks.OAK_PLANKS));
        BlockPos a = h.absolutePos(floor.offset(-1, 0, -1)), b = h.absolutePos(floor.offset(1, 0, 1));
        Vec3 centre = Vec3.atCenterOf(h.absolutePos(floor));
        List<FakePlayer> made = new ArrayList<>();
        StrikeGameTests.afterTest(h, () -> {
            for (FakePlayer p : made) ArrivalTickets.release(server, p.getUUID(), p.getData(ModAttachments.ARRIVAL.get()));
        });
        h.startSequence()
                .thenExecute(() -> server.getCommands().performPrefixedCommand(server.createCommandSourceStack().withLevel(level).withSuppressedOutput(),
                        String.format(Locale.ROOT, "sable assemble area %d %d %d %d %d %d", a.getX(), a.getY(), a.getZ(), b.getX(), b.getY(), b.getZ())))
                .thenWaitUntil(() -> h.assertFalse(SubLevels.near(level, centre, 8).isEmpty(), "craft not assembled"))
                .thenExecute(() -> {
                    SubLevelAccess sub = SubLevels.near(level, centre, 8).getFirst();
                    BlockPos plot = BlockPos.containing(SubLevels.toPlot(sub, centre.add(0, 1, 0)));
                    h.assertTrue(SubLevels.inPlotGrid(level, new ChunkPos(plot)), "craft point " + plot + " not in the plot grid");
                    GameProfile profile = new GameProfile(UUID.randomUUID(), "incoming_arrival_craft");
                    FakePlayer dead = died(level, profile, plot, made);
                    h.assertTrue(ArrivalTickets.count(level, profile.getId()) == 0,
                            "respawn-site ticket in the plot grid: " + held(dead) + " (point " + dead.getRespawnPosition() + ")");
                })
                .thenSucceed();
    }

    /** Keep a flight area active while testing arrival priority and player handover. */
    private static void holdWork(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        ChunkPos centre = new ChunkPos(h.absolutePos(RANGE_CENTER));
        UUID flight = UUID.randomUUID();
        StrikeGameTests.afterTest(h, () -> FlightTickets.hold(level, centre, 0, flight, false));
        FlightTickets.hold(level, centre, 0, flight, true);
    }

    /** A player (outside the player list, no packets) with respawn point {@code bed} (null for none) died: death reached the mod. */
    private static FakePlayer died(ServerLevel level, GameProfile profile, @Nullable BlockPos bed, List<FakePlayer> made) {
        FakePlayer p = new FakePlayer(level, profile);
        made.add(p);
        if (bed != null) p.setRespawnPosition(level.dimension(), bed, 0, true, false);
        p.setHealth(0);
        ArrivalTickets.onDeath(new LivingDeathEvent(p, level.damageSources().genericKill()));
        return p;
    }

    @Nullable
    private static ChunkPos held(ServerPlayer p) {
        return ArrivalTickets.held(p.getData(ModAttachments.ARRIVAL.get()));
    }

    /** Salvo-area chunks (squares of radius {@code r}) that are still unready. */
    private static int pending(ServerLevel level, List<ChunkPos> targets, int r) {
        Set<Long> seen = new HashSet<>();
        int n = 0;
        for (ChunkPos c : targets) {
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    if (seen.add(ChunkPos.asLong(c.x + dx, c.z + dz)) && !Terrain.ready(level, c.x + dx, c.z + dz)) n++;
                }
            }
        }
        return n;
    }
}
