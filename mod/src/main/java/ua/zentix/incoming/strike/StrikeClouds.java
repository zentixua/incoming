package ua.zentix.incoming.strike;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import ua.zentix.incoming.net.S2C;
import ua.zentix.incoming.registry.ModAttachments;
import ua.zentix.incoming.util.Air;
import ua.zentix.incoming.util.Terrain;
import ua.zentix.incoming.util.WorldClock;
import ua.zentix.incoming.warhead.FuelFires;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Conventional strikes still in the air, per world (rule 10 of the time note: the server defines the state, so a late
 * joiner and a replay seek see what everyone sees). Every event that leaves a client cloud (explosion, bomb entry,
 * vent, collapse dust, air-defence burst) is sent through {@link #send}: the live packet goes out as before and the
 * event is kept with its time, the world air and the ground under it before the crater. Crater fuel fires come from
 * {@link FuelFires}, which keeps and saves them.
 * <p>
 * A player receives {@link S2C.StrikeClouds} with everything within each event's packet reach: on login, dimension
 * change and respawn, and after new events, at most once per {@link #SYNC_TICKS} (a Grad salvo sends a few, not one per
 * rocket). The client builds what it has not seen at its motion age; what it saw live it keeps. The last packet is the
 * whole state, which a Flashback snapshot keeps for a seek.
 * <p>
 * A record leaves when its law's visible life is over by its motion age M ({@link #LIFE_S}: the longest cloud, so a
 * counted jump, an hour or more, clears all), and the oldest leave first beyond {@link #CAP}. The records live in a
 * non-persistent world attachment: a restart drops the clouds still in the air (the crater fires are saved and resent).
 */
public final class StrikeClouds {
    /**
     * Longest life of a conventional cloud by its law, motion seconds: {@code CloudModel.LIFE_MAX}, one hour (then it is
     * haze over the area); a water column's mist ends sooner ({@code StrikeCloudsTest} holds every weapon's cloud and mist
     * to it). So a counted jump, an hour or more, ends every record.
     */
    public static final double LIFE_S = 3600;
    /** Most records per world: the client draws at most as many explosion events ({@code FarBlasts.CAP}). */
    public static final int CAP = S2C.StrikeClouds.MAX;
    /** Shortest interval between two packets to the same world after new events, ticks. */
    public static final int SYNC_TICKS = 20;

    /** One kept event: its cloud as sent and where its packet reached. */
    record Held(S2C.StrikeClouds.Cloud cloud, Vec3 at, double reach) {}

    private final ArrayDeque<Held> held = new ArrayDeque<>();
    /** What each player in this world last received (hash of the list), to skip an unchanged one. */
    private final Map<UUID, Integer> sent = new HashMap<>();
    private boolean dirty;
    /** Game time of the last packets; game time starts at 0, so the first new event is sent at once. */
    private long sentAt = -SYNC_TICKS;

    public StrikeClouds() {}

    static StrikeClouds get(ServerLevel level) {
        return level.getData(ModAttachments.STRIKE_CLOUDS.get());
    }

    /** Sends {@code packet} to players within {@code reach} of {@code at}, as before, and keeps its cloud. */
    public static void send(ServerLevel level, Vec3 at, double reach, CustomPacketPayload packet) {
        send(level, at, reach, packet, Float.NaN);
    }

    /**
     * Same, with the ground under an explosion point as the client measures it ({@link #surface}), taken before the
     * crater changes it.
     */
    public static void send(ServerLevel level, Vec3 at, double reach, CustomPacketPayload packet, float surface) {
        PacketDistributor.sendToPlayersNear(level, null, at.x, at.y, at.z, reach, packet);
        keep(level, at, reach, packet, surface);
    }

    /** Keeps {@code packet}'s cloud without sending it (tests send it themselves). */
    static void keep(ServerLevel level, Vec3 at, double reach, CustomPacketPayload packet, float surface) {
        int kind = S2C.StrikeClouds.Cloud.kindOf(packet);
        if (kind < 0 || kind == S2C.StrikeClouds.FUEL) throw new IllegalArgumentException("not a cloud: " + packet);
        StrikeClouds all = get(level);
        all.held.addLast(new Held(new S2C.StrikeClouds.Cloud(kind, level.getGameTime(), WorldClock.counted(level), Air.of(level), surface, packet),
                at, reach));
        while (all.held.size() > CAP) all.held.pollFirst();
        all.dirty = true;
    }

    /** A crater fire changed ({@link FuelFires}): the next packet carries it. */
    public static void changed(ServerLevel level) {
        get(level).dirty = true;
    }

    /**
     * Ground under an explosion point as the client finds it ({@code FarBlasts.overSurface}): the highest first air over
     * the point and four half-block neighbours, with the client's canopy rule ({@link Terrain#dustTop}); ready chunks
     * only, NaN if none.
     */
    public static float surface(ServerLevel level, Vec3 pos) {
        double ground = Double.NaN;
        for (int[] d : new int[][]{{0, 0}, {1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
            int x = (int) Math.floor(pos.x + 0.5 * d[0]), z = (int) Math.floor(pos.z + 0.5 * d[1]);
            LevelChunk chunk = level.getChunkSource().getChunkNow(x >> 4, z >> 4);
            if (chunk == null) continue;
            double h = Terrain.dustTop(chunk, x, z) + 1;
            if (!(h <= ground)) ground = h;
        }
        return (float) ground;
    }

    /**
     * Motion age M of {@code c} at game time {@code now} with {@code counted} jumps, seconds: real age plus the jumps counted
     * since ({@code util/StrikeClock#motion}); the client reads the same.
     */
    public static double motion(S2C.StrikeClouds.Cloud c, long now, double counted) {
        return Math.max(0, now - c.born()) / 20.0 + Math.max(0, counted - c.jumps()) * WorldClock.SECONDS_PER_TICK;
    }

    /** Records whose life is over leave, oldest first (their motion age is the greatest). */
    void prune(long now, double counted) {
        while (!held.isEmpty() && motion(held.peekFirst().cloud(), now, counted) >= LIFE_S) held.pollFirst();
    }

    /** {@code p}'s packet: every kept cloud and burning crater whose packet reach covers it. */
    static S2C.StrikeClouds packet(ServerPlayer p) {
        ServerLevel level = p.serverLevel();
        StrikeClouds all = get(level);
        all.prune(level.getGameTime(), WorldClock.counted(level));
        List<S2C.StrikeClouds.Cloud> out = new ArrayList<>();
        for (Held h : all.held) if (p.distanceToSqr(h.at) < h.reach * h.reach) out.add(h.cloud);
        for (FuelFires.Burning b : FuelFires.burning(level)) {
            if (p.distanceToSqr(b.at()) < b.reach() * b.reach()) {
                out.add(new S2C.StrikeClouds.Cloud(S2C.StrikeClouds.FUEL, b.state().blastTick(), 0, Air.CALM, Float.NaN, b.state()));
            }
        }
        // a crater fire takes no air from the packet (its smoke follows the air now): a fixed one keeps the list unchanged
        // the newest stay when a player has more than a packet holds (the client keeps as many)
        if (out.size() > S2C.StrikeClouds.MAX) out = out.subList(out.size() - S2C.StrikeClouds.MAX, out.size());
        return new S2C.StrikeClouds(List.copyOf(out));
    }

    /** Sends {@code p} its packet unless it already has the same; an empty one only to a player who had clouds. */
    static void sync(ServerPlayer p, boolean always) {
        S2C.StrikeClouds packet = packet(p);
        StrikeClouds all = get(p.serverLevel());
        int hash = packet.clouds().hashCode();
        Integer last = all.sent.put(p.getUUID(), hash);
        if (last != null ? last != hash : always || !packet.clouds().isEmpty()) PacketDistributor.sendToPlayer(p, packet);
    }

    /** End of a world tick: prune, and after new events the players there get their packets (at most once a second). */
    public static void onLevelTick(LevelTickEvent.Post e) {
        if (!(e.getLevel() instanceof ServerLevel level)) return;
        StrikeClouds all = level.getExistingDataOrNull(ModAttachments.STRIKE_CLOUDS.get());
        if (all == null) return;
        long now = level.getGameTime();
        all.prune(now, WorldClock.counted(level));
        if (!all.dirty || now - all.sentAt < SYNC_TICKS) return;
        all.dirty = false;
        all.sentAt = now;
        // the server's player list, as the live packets ({@code PacketDistributor.sendToPlayersNear}) read it
        List<ServerPlayer> players = level.getServer().getPlayerList().getPlayers().stream().filter(p -> p.level() == level).toList();
        all.sent.keySet().retainAll(players.stream().map(ServerPlayer::getUUID).toList());
        for (ServerPlayer p : players) sync(p, false);
    }

    /** Login: the strikes around the player, at their age. */
    public static void onLogin(PlayerEvent.PlayerLoggedInEvent e) {
        if (e.getEntity() instanceof ServerPlayer p) fresh(p);
    }

    /** Dimension change: the new world's strikes (the client drops the old world's clouds with the world). */
    public static void onChangeDimension(PlayerEvent.PlayerChangedDimensionEvent e) {
        if (e.getEntity() instanceof ServerPlayer p) fresh(p);
    }

    /** Respawn, possibly in another world without a dimension-change event. */
    public static void onRespawn(PlayerEvent.PlayerRespawnEvent e) {
        if (e.getEntity() instanceof ServerPlayer p) fresh(p);
    }

    /** A player whose client starts from nothing in this world: whatever the server last sent it there is gone. */
    static void fresh(ServerPlayer p) {
        get(p.serverLevel()).sent.remove(p.getUUID());
        sync(p, false);
    }

    /** Tests: keeps {@code c} as the newest (no world, no reach). */
    void addForTest(S2C.StrikeClouds.Cloud c) {
        held.addLast(new Held(c, Vec3.ZERO, 0));
    }

    int sizeForTest() {
        return held.size();
    }

    /** Tests: the kept clouds of {@code level}, oldest first. */
    public static List<S2C.StrikeClouds.Cloud> heldForTest(ServerLevel level) {
        return get(level).held.stream().map(Held::cloud).toList();
    }

    /** Tests: moves the kept clouds within a block of {@code at} {@code ticks} into the past, as if they had happened that much earlier. */
    public static void ageForTest(ServerLevel level, Vec3 at, long ticks) {
        StrikeClouds all = get(level);
        List<Held> moved = new ArrayList<>(all.held.size());
        for (Held h : all.held) {
            S2C.StrikeClouds.Cloud c = h.cloud;
            if (h.at.distanceTo(at) <= 1) c = new S2C.StrikeClouds.Cloud(c.kind(), c.born() - ticks, c.jumps(), c.air(), c.surface(), c.packet());
            moved.add(new Held(c, h.at, h.reach));
        }
        // birth order holds the oldest first (prune reads the front)
        moved.sort(java.util.Comparator.comparingLong(h -> h.cloud.born()));
        all.held.clear();
        all.held.addAll(moved);
    }

    /** Tests: keeps {@code packet}'s cloud as if sent now to players within {@code reach}, without sending it. */
    public static void keepForTest(ServerLevel level, Vec3 at, double reach, CustomPacketPayload packet) {
        keep(level, at, reach, packet, Float.NaN);
    }

    /** Tests: the packet {@code p} would receive now. */
    public static S2C.StrikeClouds packetForTest(ServerPlayer p) {
        return packet(p);
    }

    /** Tests: {@code p} joins (login, dimension change, respawn). */
    public static void joinForTest(ServerPlayer p) {
        fresh(p);
    }
}
