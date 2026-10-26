package ua.zentix.incoming.strike;

import it.unimi.dsi.fastutil.longs.LongIterator;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ChunkLevel;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.DistanceManager;
import net.minecraft.server.level.FullChunkStatus;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;
import ua.zentix.incoming.util.Terrain;

import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Area preloaded by the mod (projectile target area {@link FlightTickets}, projectile's own and ahead chunks
 * {@link ChunkTickets}, map hint {@link PickHints}, blast area, nuclear detonation site from launch, crater, and
 * destruction queue): loading starts immediately as with a vanilla region ticket, but ticking starts only where
 * chunk neighbors are ready.
 * <p>
 * {@code DistanceManager} has two level trackers: loading ({@code ticketTracker}: chunk status and order;
 * generation queue follows level, {@code ChunkTaskPriorityQueue}) and ticking ({@code tickingTicketsTracker}:
 * {@code ServerLevel.shouldTickBlocksAt} and {@code DistanceManager.inEntityTickingRange}). Region tickets
 * ({@code addRegionTicket}) enter both; block entities in a full chunk tick from tick level alone without waiting
 * for neighbors. A target-area beehive releasing a bee reads a still-generating neighboring chunk and loads it
 * synchronously ({@code ServerChunkCache.getChunk}) behind the wave generation queue, stalling the server 2–10 s
 * (scene bench “stall:” line). Vanilla prevents this for entities: entity ticking starts only when the
 * surrounding 5×5 square is ready ({@code ChunkMap.prepareEntityTickingChunk}).
 * <p>
 * Thus an area uses two tickets. A loading ticket ({@code DistanceManager.addTicket}, loading tracker only) at
 * the region ticket's {@code 33 − distance} level is placed immediately: same chunks, statuses, and generation
 * order, so readiness is no later for projectile arrival. The region ticket grows: radius {@code k} once the
 * radius-{@code k} square is ready; blocks tick within {@code k − 1}, with ready neighbors, entities within
 * {@code k − 2}, as vanilla would already allow. Once the whole area is ready, only the region ticket remains.
 * <p>
 * Unsuitable (scene bench projectiles wait 60 s in vain): limiting chunks loading, region ticket only once the
 * area is ready, or growing rings with level-33 tickets; all change generation order.
 * <p>
 * Each area has its own loading ticket (value is the area itself): vanilla merges identical tickets (type, level,
 * value), so releasing one area would remove loading from another with the same center, radius, and key but a
 * different type (nuclear crater and destruction queue hold one chunk), leaving it permanently ticketless.
 * <p>
 * Loading tickets do not enable ticking themselves: blocks and block entities tick only under another tick
 * tracker (player simulation, another mod's region ticket). Vanilla then ticks a block entity from its chunk
 * level without checking neighbors, just like chunks at player-ticket edges. This is worse than vanilla only if
 * simulation distance exceeds server view distance: vanilla does not load beyond view, but mod tickets do. The
 * mod does not change block-entity ticking rules shared by all pack mods.
 * <p>
 * A non-ticking area ({@link Area#ticks} false: nuclear site while ICBM flies; projectile target area until the
 * last approach seconds) holds only a loading ticket: chunks fully loaded, neither blocks nor entities ticking.
 * <p>
 * Released areas stop ticking immediately, but a non-ticking ticket holds their chunks until the queue returns
 * them to vanilla at its rate ({@link #drainReleases}): releasing hundreds at once (abort, projectiles timing out
 * on area waiting) would otherwise unload and save thousands in one tick.
 * <p>
 * State is not saved (like tickets): after restart, projectiles request areas again. Lives in {@link
 * StrikeWorld}; ticks even during {@code /tick freeze}: world loading is not simulation, and the trailer waits
 * for area loading in a frozen world.
 */
public final class AreaLoader {
    /** Area: region ticket {@code type} at level {@code 33 − distance}, {@code centre}, key {@code key}. */
    public record Area(TicketType<UUID> type, ChunkPos centre, int distance, UUID key, boolean ticks) {
        /** Area that ticks as it becomes ready. */
        public Area(TicketType<UUID> type, ChunkPos centre, int distance, UUID key) {
            this(type, centre, distance, key, true);
        }

        int level() {
            return ChunkLevel.byStatus(FullChunkStatus.FULL) - distance;
        }
    }

    /**
     * Loading ticket order on one chunk also defines vanilla equality: distinct areas must not compare equal.
     * Ticket types differ by name (mod names are unique); for two types sharing a name, also compare the type
     * objects.
     */
    private static final TicketType<Area> LOAD = TicketType.create("incoming_area_load",
            Comparator.<Area, String>comparing(a -> a.type().toString()).thenComparingInt(a -> System.identityHashCode(a.type()))
                    .thenComparing(Area::key).thenComparingInt(Area::distance).thenComparing(Area::ticks)
                    .thenComparingLong(a -> a.centre().toLong()));

    /**
     * Released-area ticket ({@link #drainReleases}): non-ticking, same level as the area. Value is release
     * number, not area: area tickets cannot be found by its key after release.
     */
    private static final TicketType<Long> RELEASE = TicketType.create("incoming_area_release", Long::compare);
    /**
     * Chunk holders vanilla processes for unloading within tick time ({@code ChunkMap.processUnloads}): excess in
     * {@code toDrop} enters unloading that tick; excess ready-to-unload holders are saved and unloaded.
     */
    private static final int UNLOAD_CALM = 2000;
    /**
     * Holders vanilla moves from {@code toDrop} into unloading per tick even without free time ({@code
     * ChunkMap.processUnloads}): its own busy-server unloading rate.
     */
    private static final int UNLOAD_PER_TICK = 200;

    /**
     * Region ticket radius acquired by area: −1 means none yet. Equal to {@code distance} means whole area
     * acquired.
     */
    private final Map<Area, Integer> requests = new LinkedHashMap<>();
    /** Released areas still held by release tickets (release number as value), in release order. */
    private final Map<Area, Long> releasing = new LinkedHashMap<>();
    private long released;
    /** Generation retained by our released tickets; owned by the world's existing StrikeWorld attachment. */
    private final Set<ChunkHolder> releasedLoads = new HashSet<>();
    /**
     * Additional holders the queue returns to vanilla this tick beyond unloading space ({@link #drainReleases}):
     * add {@link #UNLOAD_PER_TICK} each tick, capped at that value; below zero means debt for an area larger than
     * that rate.
     */
    private int credit;
    /** Game tick for automatic area release (map hint: player may leave). */
    private final Map<Area, Long> expiring = new LinkedHashMap<>();

    /** Acquire area; use region ticket immediately if fully ready. */
    public void hold(ServerLevel level, Area area) {
        hold(level, area, Long.MAX_VALUE);
    }

    /** Acquire temporarily: release automatically at tick {@code until}, even if still growing. */
    public void hold(ServerLevel level, Area area, long until) {
        if (requests.containsKey(area)) return;
        if (until != Long.MAX_VALUE) expiring.put(area, until);
        if (area.ticks() && ready(level, area.centre(), area.distance())) {
            level.getChunkSource().addRegionTicket(area.type(), area.centre(), area.distance(), area.key());
            requests.put(area, area.distance());
        } else {
            distances(level).addTicket(LOAD, area.centre(), area.level(), area);
            requests.put(area, -1);
            grow(level, area);
        }
        // Reacquired while awaiting release-ticket removal: area tickets already hold the same chunks.
        Long release = releasing.remove(area);
        if (release != null) distances(level).removeTicket(RELEASE, area.centre(), area.level(), release);
        apply(level);
    }

    /** Extend temporary area until {@code until}; do not reacquire a released one. */
    public void renew(Area area, long until) {
        if (requests.containsKey(area)) expiring.put(area, until);
    }

    /**
     * Release area: region ticket and, if still growing, loading ticket. Ticking stops immediately; release
     * ticket holds ready-area chunks until {@link #drainReleases} reaches it. A still-loading area releases
     * immediately: same-level release ticket would finish unwanted generation. Also immediate on server stop:
     * queue no longer ticks and {@code util/StopDrain} waits for generation held by tickets.
     */
    public void release(ServerLevel level, Area area) {
        expiring.remove(area);
        Integer taken = requests.remove(area);
        if (taken == null) return;
        if (level.getServer().isRunning() && (area.ticks() ? taken == area.distance() : ready(level, area.centre(), area.distance()))) {
            // Release ticket first, then area tickets: chunk loading levels stay unchanged until the queue reaches it.
            long release = released++;
            distances(level).addTicket(RELEASE, area.centre(), area.level(), release);
            releasing.put(area, release);
        }
        removeTickets(level, area, taken);
        if (!hasOtherThan(ArrivalTickets.TYPE)) releaseAll(level, ArrivalTickets.TYPE);
        apply(level);
    }

    /** Active Incoming loads, excluding the tickets that only protect players from those loads. */
    boolean hasOtherThan(TicketType<?> type) {
        for (Area area : requests.keySet()) if (area.type() != type) return true;
        return false;
    }

    /** End dependent tickets immediately, including any already waiting in the release queue. */
    private void releaseAll(ServerLevel level, TicketType<?> type) {
        var held = requests.entrySet().iterator();
        while (held.hasNext()) {
            var entry = held.next();
            Area area = entry.getKey();
            if (area.type() != type) continue;
            removeTickets(level, area, entry.getValue());
            expiring.remove(area);
            held.remove();
        }
        var queued = releasing.entrySet().iterator();
        while (queued.hasNext()) {
            var entry = queued.next();
            Area area = entry.getKey();
            if (area.type() != type) continue;
            distances(level).removeTicket(RELEASE, area.centre(), area.level(), entry.getValue());
            queued.remove();
        }
    }

    /**
     * Apply area tickets to chunk levels and ticking immediately, rather than at someone else's recalculation.
     * Vanilla recalculates when called: tick start, synchronous chunk load, another area's release ({@link
     * #drainReleases}). Without this, a projectile acquiring an area sees it ticking this tick or next depending
     * on recalculation between its ticket and check, thus on other impacts, neighboring batch scenarios, and CI
     * shard composition (22.10: scenario loiter_rail.player Lancet materialized a tick earlier or later, same
     * detonation point but different hash).
     */
    private static void apply(ServerLevel level) {
        // Only holder levels, ticking, and statuses, without ServerChunkCache.runDistanceManagerUpdates: that also copies the entire
        // chunk map to the visible map (promoteChunkMap) when new holders appear, while salvo projectiles and
        // nuclear ruins acquire dozens of areas per tick; new holders become visible at vanilla's tick-start update.
        // getChunkNow's recent-chunk cache would remember a chunk demoted below full loading by recalculation (exposed by AT).
        if (distances(level).runAllUpdates(level.getChunkSource().chunkMap)) level.getChunkSource().clearCache();
    }

    /**
     * Release all areas immediately, without queue (server stop): area tickets are not saved; holding them during
     * stop would launch generation awaited by {@code util/StopDrain}. Owners reacquire areas after startup.
     */
    public void releaseAll(ServerLevel level) {
        requests.forEach((area, taken) -> removeTickets(level, area, taken));
        requests.clear();
        expiring.clear();
        releasing.forEach((area, release) -> distances(level).removeTicket(RELEASE, area.centre(), area.level(), release));
        releasing.clear();
    }

    private void removeTickets(ServerLevel level, Area area, int taken) {
        retainGenerating(level, area);
        if (taken >= 0) level.getChunkSource().removeRegionTicket(area.type(), area.centre(), taken, area.key());
        if (taken < area.distance()) distances(level).removeTicket(LOAD, area.centre(), area.level(), area);
    }

    /** Released areas whose chunks still have release tickets (tests). */
    public int releasing() {
        return releasing.size();
    }

    /**
     * Acquired areas as a string (test messages): ticket type, center, size, region ticket growth (−1 means
     * loading only), non-ticking, expiry; then released areas still held by queue.
     */
    public String describe() {
        StringBuilder b = new StringBuilder("held ").append(requests.size()).append(':');
        requests.forEach((a, taken) -> {
            b.append(' ').append(a.type()).append(' ').append(a.centre()).append(" d").append(a.distance()).append('/').append(taken);
            if (!a.ticks()) b.append(" no tick");
            Long until = expiring.get(a);
            if (until != null) b.append(" until ").append(until);
            b.append(';');
        });
        b.append(" releasing ").append(releasing.size()).append(':');
        releasing.keySet().forEach(a -> b.append(' ').append(a.type()).append(' ').append(a.centre()).append(';'));
        return b.toString();
    }

    /** Areas acquired or growing (tests). */
    public int size() {
        return requests.size();
    }

    /** Whether area is acquired or growing (tests). */
    public boolean holds(Area area) {
        return requests.containsKey(area);
    }

    /** Count acquired/growing areas with {@code type} ticket and {@code key} (tests). */
    public int count(TicketType<?> type, UUID key) {
        return (int) requests.keySet().stream().filter(a -> a.type() == type && a.key().equals(key)).count();
    }

    /** Same, limited to ticking areas ({@link Area#ticks}) or non-ticking areas (tests). */
    public int count(TicketType<?> type, UUID key, boolean ticks) {
        return (int) requests.keySet().stream().filter(a -> a.type() == type && a.key().equals(key) && a.ticks() == ticks).count();
    }

    /** Busy holders in our ticket footprints, including generation already canceled by a release. */
    public int inFlight(ServerLevel level) {
        for (Area area : requests.keySet()) retainGenerating(level, area);
        releasedLoads.removeIf(holder -> holder.getGenerationRefCount() == 0);
        return releasedLoads.size();
    }

    private void retainGenerating(ServerLevel level, Area area) {
        ChunkMap map = level.getChunkSource().chunkMap;
        int radius = area.distance() + ChunkLevel.RADIUS_AROUND_FULL_CHUNK;
        for (int x = area.centre().x - radius; x <= area.centre().x + radius; x++) {
            for (int z = area.centre().z - radius; z <= area.centre().z + radius; z++) {
                ChunkHolder holder = map.getUpdatingChunkIfPresent(ChunkPos.asLong(x, z));
                if (holder != null && holder.getGenerationRefCount() > 0) releasedLoads.add(holder);
            }
        }
    }

    void tick(ServerLevel level) {
        releasedLoads.removeIf(holder -> holder.getGenerationRefCount() == 0);
        long now = level.getGameTime();
        if (!expiring.isEmpty()) {
            for (Area area : expiring.entrySet().stream().filter(e -> e.getValue() <= now).map(Map.Entry::getKey).toList()) release(level, area);
        }
        for (Area area : requests.keySet()) grow(level, area);
        drainReleases(level);
        // Apply grown areas immediately ({@link #apply}).
        apply(level);
    }

    /**
     * Remove released-area tickets at vanilla's own unloading rate. Vanilla saves and unloads ticketless holders
     * in free tick time; without it, {@link #UNLOAD_PER_TICK} from {@code toDrop} per tick, plus everything
     * exceeding {@link #UNLOAD_CALM} in {@code toDrop} and unload queue that tick: ~9 thousand chunks released
     * together take 5 s to save. Thus release areas in order and immediately recalculate vanilla levels after
     * each ({@code runDistanceManagerUpdates}) to see holders returned.
     * <p>
     * Release the next area if unloading has space for its entire holder square ({@link #holders}), which vanilla
     * processes in free tick time. But a server without free time processes only excess beyond {@link
     * #UNLOAD_CALM} unloading, and never holders still held by neighbor generation, so space may never appear: a
     * space-only queue on a busy server would release one area per 100 ticks (after ~60 salvos: 12.5 thousand
     * release tickets, ~25 thousand loaded chunks, TPS 13–15; more held chunks leave less free time). Thus beyond
     * available space, return holders at vanilla's no-free-time rate too, {@link #UNLOAD_PER_TICK} per tick
     * ({@link #credit}): a larger area incurs debt, and the next waits until later ticks repay it. Only while
     * unloading ({@link #unloading}, excluding generation holders vanilla skips and queue need not await) stays
     * below {@link #UNLOAD_CALM} plus that rate, and {@code toDrop} below {@link #UNLOAD_CALM}: on the first
     * free-time tick vanilla moves all {@code toDrop} to unloading and saves excess beyond {@link #UNLOAD_CALM}
     * even after time expires. After this queue, excess is at most its rate plus one area, whether the server had
     * free time or not.
     * <p>
     * Released-area chunks do not tick; queue runs even during {@code /tick freeze}, like loading.
     */
    private void drainReleases(ServerLevel level) {
        credit = Math.min(credit + UNLOAD_PER_TICK, UNLOAD_PER_TICK);
        if (releasing.isEmpty()) return;
        ServerChunkCache chunks = level.getChunkSource();
        ChunkMap map = chunks.chunkMap;
        // Once per tick: release only adds to toDrop; generation holders it adds count toward unloading.
        int generating = generating(map);
        // Always take queue head, without a live iterator: level recalculation reaches chunk entities, and a projectile
        // leaving with them may release its area.
        for (int n = releasing.size(); n > 0 && !releasing.isEmpty(); n--) {
            Map.Entry<Area, Long> next = releasing.entrySet().iterator().next();
            Area area = next.getKey();
            int unloading = map.toDrop.size() + map.pendingUnloads.size() - generating;
            boolean room = unloading + holders(area) <= UNLOAD_CALM;
            if (!room && (credit <= 0 || unloading >= UNLOAD_CALM + UNLOAD_PER_TICK || map.toDrop.size() >= UNLOAD_CALM)) return;
            int dropping = map.toDrop.size();
            releasing.remove(area);
            distances(level).removeTicket(RELEASE, area.centre(), area.level(), next.getValue());
            chunks.runDistanceManagerUpdates();
            if (!room) credit -= Math.max(0, map.toDrop.size() - dropping);
        }
    }

    /**
     * Unloading chunk holders vanilla will process ({@code ChunkMap.processUnloads}): no longer loading or
     * already queued for unload, excluding those still held by neighbor generation (also tests).
     */
    public static int unloading(ChunkMap map) {
        return map.toDrop.size() + map.pendingUnloads.size() - generating(map);
    }

    /** Holders in {@code toDrop} still held by neighbor generation: vanilla skips them until it finishes. */
    private static int generating(ChunkMap map) {
        int n = 0;
        for (LongIterator it = map.toDrop.iterator(); it.hasNext(); ) {
            ChunkHolder holder = map.getVisibleChunkIfPresent(it.nextLong());
            if (holder != null && holder.getGenerationRefCount() != 0) n++;
        }
        return n;
    }

    /**
     * Maximum holders returned by area release: a level {@code 33 − distance} ticket holds a square of radius
     * {@code distance + ChunkLevel.RADIUS_AROUND_FULL_CHUNK}.
     */
    static int holders(Area area) {
        int side = 2 * (area.distance() + ChunkLevel.RADIUS_AROUND_FULL_CHUNK) + 1;
        return side * side;
    }

    private static DistanceManager distances(ServerLevel level) {
        return level.getChunkSource().chunkMap.getDistanceManager();
    }

    /**
     * Region ticket covers the largest ready square around center; once whole area is ready, loading ticket is
     * unnecessary.
     */
    private void grow(ServerLevel level, Area area) {
        int taken = requests.get(area);
        if (taken == area.distance() || !area.ticks()) return;
        // Acquired-radius square is ready and stays so (tickets hold it): only check subsequent rings.
        int k = taken;
        while (k < area.distance() && ringReady(level, area.centre(), k + 1)) k++;
        // Radius 0 enables no ticking (level 33): acquire only if this is the whole area.
        if (k == taken || (k < 1 && k < area.distance())) return;
        // New ticket first, then old: chunk levels never drop even for a tick.
        level.getChunkSource().addRegionTicket(area.type(), area.centre(), k, area.key());
        if (taken >= 0) level.getChunkSource().removeRegionTicket(area.type(), area.centre(), taken, area.key());
        if (k == area.distance()) distances(level).removeTicket(LOAD, area.centre(), area.level(), area);
        requests.put(area, k);
    }

    /** All chunks in the ring {@code radius} from center are ready (0 means center). */
    private static boolean ringReady(ServerLevel level, ChunkPos c, int radius) {
        for (int i = -radius; i <= radius; i++) {
            if (!Terrain.ready(level, c.x + i, c.z - radius) || !Terrain.ready(level, c.x + i, c.z + radius)) return false;
            if (!Terrain.ready(level, c.x - radius, c.z + i) || !Terrain.ready(level, c.x + radius, c.z + i)) return false;
        }
        return true;
    }

    private static boolean ready(ServerLevel level, ChunkPos c, int radius) {
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) if (!Terrain.ready(level, c.x + dx, c.z + dz)) return false;
        }
        return true;
    }
}
