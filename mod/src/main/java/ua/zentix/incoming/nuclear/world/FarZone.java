package ua.zentix.incoming.nuclear.world;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;
import ua.zentix.incoming.Incoming;
import ua.zentix.incoming.compat.DhUpdates;
import ua.zentix.incoming.strike.AreaLoader;
import ua.zentix.incoming.strike.StrikeWorld;
import ua.zentix.incoming.util.DiskStatus;
import ua.zentix.incoming.util.Terrain;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.UUID;

/**
 * Post-wave zone not intact on disk, loaded into the world for distant Distant Horizons LODs ({@link FarLods}). A ruined
 * disk copy requires final chunk blocks and a matching 5×5 window (ruin plan reads neighbors): at explored-world edges,
 * disk contains only incomplete chunks (ring 1 — through light, 2 — through caves, 3 — biomes without terrain, farther —
 * structure starts, usually without blocks), or none, so DH generates intact LODs there. A 1.17 chunk with below-zero
 * retrogen is suitable for copying (blocks above old world bottom are final); if its plan was not ready, it comes here
 * too: the post-wave zone does not load it. Many such chunks: for one 15 kt surface burst, 6273 of 14323 heavy-zone chunks
 * outside memory are themselves unsuitable for copying; another square's 21025 chunks include 7901 absent on disk and
 * 2282 incomplete without blocks. All are the same to the zone ({@link DiskStatus.State#PARTIAL}).
 * <p>
 * After wave arrival, such a chunk is loaded as a 5×5 tile (same tiles as the post-wave zone {@link NuclearPrep}; also
 * tiles touched by its ruin window {@link RuinPlanner#REACH}: their intact-on-disk chunks got no disk plan and are not
 * loaded by preparation) with a non-ticking load ticket ({@link AreaLoader}, {@code ticks = false}): vanilla completes
 * generation in the background, nothing loads synchronously. Ruins follow the normal load path — {@code ChunkEvent.Load},
 * ruin queue ({@link ScarQueue}: no {@code chunk_scar} mark), plan application ({@link RuinPlan#apply}) and its mark in
 * {@link DhUpdates}; tile releases when all chunks are ruined (or only waiting for neighbors — then held by their own
 * tickets, {@link ScarQueue#holdForTile}) and DH has received them. World generation matches a player's visit, just
 * earlier: a later-loaded chunk already carries ruins.
 * <p>
 * Only with DH and near players (DH range {@link FarLods#FAR_CHUNKS}): otherwise nobody sees LODs and generation is
 * pointless; world setting {@code nuclear.far_zone} disables it entirely. At most {@link #LOADING} tiles generating and
 * {@link #HELD} total; a tile not generated within {@link #LOAD_LIMIT} ticks (chunk never became full), or unreleased by
 * {@link #HOLD_LIMIT}, releases without ruins so the zone advances. At most {@link #RELEASE_PER_TICK} released per tick
 * (unload writes chunks to disk on the server tick), at most {@link #START_VISITS} queued tiles inspected per tick:
 * tick work is independent of zone size. State is non-persistent; server stop, cancel, DH departure or disabled setting
 * release all tiles ({@link #clear}) — stop before {@code util/StopDrain}.
 */
final class FarZone {
    private static final TicketType<UUID> TYPE = TicketType.create("incoming_far_zone", Comparator.<UUID>naturalOrder());
    /** Tiles as in the post-wave zone ({@link NuclearPrep}): 5×5 chunks, ticket is the tile itself. */
    static final int TILE_RADIUS = 2, TILE = TILE_RADIUS * 2 + 1;
    /** Tiles generating concurrently and total held (generating and awaiting ruins). */
    static final int LOADING = 2, HELD = 4;
    /** Tiles released per tick: each up to 25 chunk unloads with disk writes in this server tick. */
    private static final int RELEASE_PER_TICK = 2;
    /** Maximum queued tiles inspected per tick (including abandoned tiles without players). */
    static final int START_VISITS = 64;
    /** Maximum tile generation time, ticks: a chunk never becoming full does not hold a slot. */
    static final int LOAD_LIMIT = 2400;
    /** Maximum total tile hold time, ticks: ruins failing to apply do not hold a slot forever. */
    static final int HOLD_LIMIT = 6000;

    private static final class Tile {
        final ChunkPos centre;
        /** All 25 chunks full (generation complete). */
        boolean ready;
        long heldAt;

        Tile(ChunkPos centre) {
            this.centre = centre;
        }

        AreaLoader.Area area() {
            return new AreaLoader.Area(TYPE, centre, TILE_RADIUS, new UUID(0L, centre.toLong()), false);
        }
    }

    /** Centers of all queued and held tiles: a tile cannot be queued twice. */
    private final LongOpenHashSet known = new LongOpenHashSet();
    /** Waiting for a slot, in enqueue order (wave order). */
    private final ArrayDeque<Tile> waiting = new ArrayDeque<>();
    /** Held: generating or awaiting ruins; at most {@link #HELD}. */
    private final List<Tile> held = new ArrayList<>(HELD);
    /** Generation deadline (checks use a shorter one). */
    private int loadLimit = LOAD_LIMIT;
    /** For logging and checks: tiles acquired, released, expired, abandoned (players left). */
    private long taken, released, expired, dropped;
    /** Total generation ticks of generated tiles and acquire-to-release ticks of released tiles (averages for log). */
    private long loadTicks, loaded, holdTicks;
    /** Most tiles generating concurrently (checks). */
    private int loadingPeak;

    /** Center of the 5×5 tile containing the chunk. */
    static ChunkPos tileOf(int x, int z) {
        return new ChunkPos(Math.floorDiv(x, TILE) * TILE + TILE_RADIUS, Math.floorDiv(z, TILE) * TILE + TILE_RADIUS);
    }

    /**
     * Post-wave zone chunk not intact on disk: queue its tile and tiles touched by its ruin window (their intact-on-disk
     * chunks got no disk plan).
     */
    void offer(long chunk) {
        int x = ChunkPos.getX(chunk), z = ChunkPos.getZ(chunk);
        // window no wider than a tile: its corners touch every tile it touches (own tile first)
        add(tileOf(x, z));
        for (int dx : new int[]{-RuinPlanner.REACH, RuinPlanner.REACH}) {
            for (int dz : new int[]{-RuinPlanner.REACH, RuinPlanner.REACH}) add(tileOf(x + dx, z + dz));
        }
    }

    private void add(ChunkPos centre) {
        if (known.add(centre.toLong())) waiting.add(new Tile(centre));
    }

    boolean busy() {
        return !held.isEmpty() || !waiting.isEmpty();
    }

    void loadLimit(int ticks) {
        loadLimit = ticks;
    }

    /** Server thread, after {@link FarLods} (its marks first). Work over held tiles and at most {@link #START_VISITS} waiting tiles. */
    void tick(ServerLevel level) {
        if (held.isEmpty() && waiting.isEmpty()) return;
        long now = level.getGameTime();
        ScarQueue scars = NuclearWorld.get(level).scars();
        int loading = 0, releasedNow = 0;
        for (Iterator<Tile> it = held.iterator(); it.hasNext(); ) {
            Tile t = it.next();
            long age = now - t.heldAt;
            if (!t.ready && age > loadLimit || age > HOLD_LIMIT) {
                Incoming.LOG.warn("Far LODs: tile {} held for {} ticks ({}), released before all its chunks got ruins", t.centre, age,
                        t.ready ? "ruins not placed" : "generation not finished");
                expired++;
                it.remove();
                release(level, t);
                continue;
            }
            if (!t.ready && ready(level, t)) {
                t.ready = true;
                loadTicks += age;
                loaded++;
            }
            if (t.ready && releasedNow < RELEASE_PER_TICK && finished(level, scars, t)) {
                releasedNow++;
                released++;
                holdTicks += age;
                it.remove();
                release(level, t);
                continue;
            }
            if (!t.ready) loading++;
        }
        // new tiles in order while slots remain; a tile left by players is unneeded
        for (int n = 0; n < START_VISITS && !waiting.isEmpty() && loading < LOADING && held.size() < HELD; n++) {
            Tile t = waiting.poll();
            if (!FarLods.nearPlayer(level, t.centre.toLong())) {
                known.remove(t.centre.toLong());
                dropped++;
                continue;
            }
            StrikeWorld.get(level).areas().hold(level, t.area());
            t.heldAt = now;
            held.add(t);
            taken++;
            loading++;
        }
        loadingPeak = Math.max(loadingPeak, loading);
    }

    private static boolean ready(ServerLevel level, Tile t) {
        for (int dx = -TILE_RADIUS; dx <= TILE_RADIUS; dx++) {
            for (int dz = -TILE_RADIUS; dz <= TILE_RADIUS; dz++) if (!Terrain.ready(level, t.centre.x + dx, t.centre.z + dz)) return false;
        }
        return true;
    }

    /**
     * All tile chunks ruined and sent to DH, or remaining chunks only await neighbors (then held by their own tickets,
     * as in post-wave zone tiles).
     */
    private static boolean finished(ServerLevel level, ScarQueue scars, Tile t) {
        int waiting = 0;
        for (int dx = -TILE_RADIUS; dx <= TILE_RADIUS; dx++) {
            for (int dz = -TILE_RADIUS; dz <= TILE_RADIUS; dz++) {
                long c = ChunkPos.asLong(t.centre.x + dx, t.centre.z + dz);
                if (scars.queued(c)) {
                    if (!scars.waitsNeighbours(c)) return false;
                    waiting++;
                } else if (DhUpdates.pending(level, c)) {
                    return false;
                }
            }
        }
        if (waiting > scars.tileHoldsLeft()) return false;
        if (waiting > 0) {
            for (int dx = -TILE_RADIUS; dx <= TILE_RADIUS; dx++) {
                for (int dz = -TILE_RADIUS; dz <= TILE_RADIUS; dz++) scars.holdForTile(level, ChunkPos.asLong(t.centre.x + dx, t.centre.z + dz));
            }
        }
        return true;
    }

    private void release(ServerLevel level, Tile t) {
        StrikeWorld.get(level).areas().release(level, t.area());
        known.remove(t.centre.toLong());
    }

    /** Cancel, DH departure, disabled setting or server stop: all tiles released, queue empty. */
    void clear(ServerLevel level) {
        for (Tile t : held) StrikeWorld.get(level).areas().release(level, t.area());
        held.clear();
        waiting.clear();
        known.clear();
    }

    /** For logging: tiles waiting, held, acquired, released, expired, abandoned; average generation and acquire-to-release ticks. */
    String summary() {
        return "tiles waiting " + waiting.size() + ", held " + held.size() + ", taken " + taken + ", released " + released + " (timed out " + expired
                + "), dropped without players " + dropped + "; generation avg " + (loaded == 0 ? 0 : loadTicks / loaded) + " ticks, until release "
                + (released == 0 ? 0 : holdTicks / released);
    }

    /** For checks: waiting, held, acquired, released, expired, abandoned, maximum concurrent generation. */
    long[] stats() {
        return new long[] {waiting.size(), held.size(), taken, released, expired, dropped, loadingPeak};
    }
}
