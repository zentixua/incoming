package ua.zentix.incoming.nuclear.world;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.ProtoChunk;
import org.jetbrains.annotations.Nullable;
import ua.zentix.incoming.Incoming;
import ua.zentix.incoming.IncomingConfig;
import ua.zentix.incoming.compat.DhUpdates;
import ua.zentix.incoming.grid.ChunkLights;
import ua.zentix.incoming.grid.PowerGrid;
import ua.zentix.incoming.nuclear.Detonation;

import java.util.ArrayDeque;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Distant Horizons heavy-zone LODs arrive with front, like player chunks. {@link FarLods} requests outside-world chunk copy
 * only after wave arrival: disk read, copy, {@link DhUpdates} queue (20 per tick), own DH delay
 * ({@link DhUpdates#LEAD} or more); 2 kt zone-edge LOD changed tens of seconds after wall. Thus during
 * ICBM flight every outside-world zone chunk with ready disk ruin plan ({@link NuclearPrep}) gets prebuilt
 * copy by same path (disk sections plus plan positions, {@link FarLods#build}; lit grid lamps as positions,
 * {@link ChunkLights#litLamps}), held until detonation then sent to DH ({@link DhUpdates#sendHeld})
 * {@link DhUpdates#LEAD} ticks before front reaches its DH section midpoint, no earlier than detonation: section chunks sent together
 * ({@link FrontSchedule}), lamps set by grid at front arrival. Rate bounded by DH window ({@link DhUpdates#heldRoom}: whole section
 * or wait) and per-tick send time ({@link #SEND_NANOS}), not {@code DhUpdates.PER_TICK}.
 * <p>
 * Memory shares prebuilt-ruin cap ({@code NuclearPrep.PLANS_CAP}) after plans: copies built once entire zone's
 * disk plans submitted/ready (or halted), using remainder after plans, snapshots, reads, nearest epicenter
 * first; if full, evict distant copies, whose LODs follow {@link FarLods} after wave. Sent copy occupies memory another
 * {@link DhUpdates#HOLD_NANOS}, held by DH. Copy removed if chunk loads meanwhile: world supplies its ruins/LODs.
 * Copy claims {@link FarLods} ruin and district-light requests ({@link #claim}); a copy removed
 * without sending to DH (capacity, heap stop, preparation end) returns claimed request to
 * {@link FarLods}, which requests each chunk's ruins once. No-flight detonation ({@code nuke now}) and chunks beyond heavy zone follow
 * {@link FarLods}.
 */
final class FrontLods {
    /** Concurrent/new disk reads per tick as in {@link FarLods}: I/O thread shared with world loading. */
    static final int READS = 16, PER_TICK = 8;
    /** Estimated memory of copy being read, bytes. */
    static final long READ_BYTES = 96L << 10;
    /**
     * Maximum send time per tick (first section always): DH call costs microseconds (queues copy by
     * reference), lamps updated by positions; 2 kt sends nearly entire zone on detonation tick.
     */
    static final long SEND_NANOS = 2_000_000;

    /** {@link #claim} result: request unrelated to copy; claimed; copy requested again after front (ruin request). */
    static final int NONE = 0, OURS = 1, AFTER_FRONT = 2;

    /** Copy reading; held; sent to DH; nothing to show (plan changes nothing, no lamps). */
    private static final byte BUILDING = 1, READY = 2, SENT = 3, NOTHING = 4;
    private static final int[] NO_LAMPS = new int[0];

    private static final class Copy {
        /** Position in copy queue ({@link #candidates}): evict distant copies from end. */
        final int index;
        byte state = BUILDING;
        @Nullable
        ProtoChunk chunk;
        int[] lamps = NO_LAMPS;
        /**
         * Copy has ruins; district light changed ({@link FarLods}: LOD sent even unchanged from disk); ruin request
         * claimed by copy; copy had lit grid lamps; sent with lamps dark.
         */
        boolean ruined, lights, ruins, lit, dark;
        long bytes;
        /** Front arrival at chunk midpoint ({@link ScarQueue#due}); 0 before detonation. */
        long due;

        Copy(int index) {
            this.index = index;
        }
    }

    /** Ready background copy: copy null if build failed (absent on disk, stale plan). */
    private record Built(long chunk, @Nullable ProtoChunk copy, boolean ruined, int[] lamps, long bytes) {}

    private final int strike;
    /** Zone chunks with ready disk plans, in plan (wave) order; cursor tracks read submissions. */
    private final LongArrayList candidates = new LongArrayList();
    private int next;
    private final Long2ObjectOpenHashMap<Copy> copies = new Long2ObjectOpenHashMap<>();
    private final ConcurrentLinkedQueue<Built> built = new ConcurrentLinkedQueue<>();
    private final FrontSchedule schedule = new FrontSchedule();
    private int reading;
    /** Bytes in held copies. */
    private long readyBytes;
    /** Sent copies still held by DH ({@link DhUpdates#HOLD_NANOS}): send times/bytes; total bytes. */
    private final ArrayDeque<long[]> inDh = new ArrayDeque<>();
    private long dhBytes;
    @Nullable
    private DiskShots.Format format;
    @Nullable
    private Detonation d;
    /** For log/tests: built, sent, sent after {@code due − LEAD}; send minus front arrival sum/min/max. */
    private long made, sent, late, offsetSum, offsetMin = Long.MAX_VALUE, offsetMax = Long.MIN_VALUE;
    /** Not sent: chunk loaded, no room, front preceded copy, build failed, nothing to show. */
    private long loaded, evicted, abandoned, failed, unchanged;
    /** Front handoff logged (copies existed); DH stats ({@link DhUpdates#takeHeldStats}) active; summary logged. */
    private boolean announced, counting, reported;

    FrontLods(int strike) {
        this.strike = strike;
    }

    /** Zone chunk's disk plan ready: enqueue copy (plan order, nearest first). Server thread. */
    void offer(long chunk) {
        candidates.add(chunk);
    }

    /** Copy bytes: held, sent but still held by DH, in-flight reads. */
    long bytes() {
        return readyBytes + dhBytes + (long) reading * READ_BYTES;
    }

    /**
     * Preparation tick: hold ready copies, evict distant ones above cap, read new ones (if {@code build});
     * after detonation send with front.
     *
     * @param plans preparation plans (copies use these)
     * @param room  copy capacity, bytes: prebuilt-ruin cap minus preparation plans, snapshots, reads
     * @param build all zone disk plans ready (or halted): copies use remainder
     */
    void tick(ServerLevel level, Long2ObjectOpenHashMap<RuinPlan> plans, long room, boolean build, long now) {
        harvest(level);
        if (!DhUpdates.enabled(level)) {
            // no DH (or gone): nobody to receive copies
            clear(level);
            return;
        }
        long nanos = System.nanoTime();
        for (long[] e; (e = inDh.peek()) != null && nanos - e[0] >= DhUpdates.HOLD_NANOS; inDh.poll()) dhBytes -= e[1];
        fit(level, room);
        if (build) start(level, plans, room, now);
        if (d != null) release(level, now);
    }

    /** Ready background copies: hold until detonation, then enqueue for send. */
    private void harvest(ServerLevel level) {
        for (Built b; (b = built.poll()) != null; ) {
            reading--;
            Copy c = copies.get(b.chunk);
            // removed while reading (loaded, front early, cancellation)
            if (c == null || c.state != BUILDING) continue;
            if (b.copy == null) {
                copies.remove(b.chunk);
                failed++;
                // district light claimed by copy returns to FarLods
                if (c.lights) FarLods.request(level, b.chunk, false);
                continue;
            }
            made++;
            if (!b.ruined && b.lamps.length == 0) {
                c.state = NOTHING;
                unchanged++;
                continue;
            }
            c.state = READY;
            c.chunk = b.copy;
            c.lamps = b.lamps;
            c.ruined = b.ruined;
            c.bytes = b.bytes;
            readyBytes += b.bytes;
            if (d != null) schedule.add(b.chunk, sendAt(b.chunk));
        }
    }

    /** Copies above cap: evict held copies from queue end, farthest epicenter first (in-flight reads finish). */
    private void fit(ServerLevel level, long room) {
        for (int i = candidates.size() - 1; i >= 0 && bytes() > room && readyBytes > 0; i--) {
            long chunk = candidates.getLong(i);
            Copy c = copies.get(chunk);
            if (c == null || c.state != READY || c.index != i) continue;
            drop(chunk, c);
            giveBack(level, chunk, c);
            evicted++;
        }
    }

    /** Read new copies in queue order while capacity, reads and background pool available. */
    private void start(ServerLevel level, Long2ObjectOpenHashMap<RuinPlan> plans, long room, long now) {
        boolean fires = IncomingConfig.SERVER.nukeFires.get();
        for (int started = 0; next < candidates.size() && started < PER_TICK && reading < READS && bytes() + READ_BYTES <= room; ) {
            // after detonation use at most half pool: player-chunk ruins more important
            if (!RuinWorkers.admit(RuinWorkers.Priority.FAR) || d != null && RuinWorkers.inFlight() >= RuinWorkers.capacity() / 2) return;
            int index = next++;
            long chunk = candidates.getLong(index);
            RuinPlan plan = plans.get(chunk);
            // chunk in world (world supplies ruins/LODs), far from players (DH does not send), front precedes copy
            if (plan == null || copies.containsKey(chunk) || FarLods.inWorld(level, chunk) || !FarLods.nearPlayer(level, chunk)
                    || d != null && ScarQueue.due(d, new ChunkPos(chunk)) - now <= DhUpdates.LEAD) continue;
            Copy c = new Copy(index);
            if (d != null) c.due = ScarQueue.due(d, new ChunkPos(chunk));
            copies.put(chunk, c);
            reading++;
            started++;
            if (format == null) format = DiskShots.Format.of(level);
            DiskShots.Format f = format;
            DiskShots.readSections(f, new ChunkPos(chunk)).thenApply(s -> build(f, s, plan, fires))
                    .exceptionally(e -> new Built(chunk, null, false, NO_LAMPS, 0)).thenAccept(built::add);
        }
    }

    /** Chunk copy with plan ruins and lit-lamp positions (background thread). */
    private static Built build(DiskShots.Format f, DiskShots.Sections s, RuinPlan plan, boolean fires) {
        FarLods.Built b = FarLods.build(f, s, plan, fires, false, false);
        if (b.copy() == null) return new Built(b.chunk(), null, false, NO_LAMPS, 0);
        LevelChunkSection[] sections = b.copy().getSections();
        int[] lamps = ChunkLights.litLamps(sections);
        return new Built(b.chunk(), b.copy(), b.ruined(), lamps, bytes(sections) + 4L * lamps.length);
    }

    /** Copy memory, bytes: section palettes (estimated like {@code ChunkShot}) and chunk-copy object. */
    private static long bytes(LevelChunkSection[] sections) {
        long n = 1024;
        for (LevelChunkSection s : sections) {
            if (s != null) n += 64 + s.getStates().getSerializedSize() + s.getBiomes().getSerializedSize();
        }
        return n;
    }

    /**
     * Prepared detonation: give copies front-arrival deadlines, enqueue held copies nearest first (equal
     * section deadlines, as for 2 kt on detonation tick, allocate DH window/tick time to nearest).
     */
    void handOff(Detonation d) {
        this.d = d;
        int ready = 0, building = 0;
        for (int i = 0; i < next; i++) {
            long chunk = candidates.getLong(i);
            Copy c = copies.get(chunk);
            if (c == null || c.index != i) continue;
            c.due = ScarQueue.due(d, new ChunkPos(chunk));
            if (c.state == READY) {
                schedule.add(chunk, sendAt(chunk));
                ready++;
            } else if (c.state == BUILDING) {
                building++;
            }
        }
        announced = next > 0 || !candidates.isEmpty();
        if (!announced) return;
        Incoming.LOG.info("Detonation #{}: heavy zone LODs held for the wave front — {} ready ({} MB), {} reading, {} more queued; sent {} ticks before "
                + "the front reaches their Distant Horizons section", d.id(), ready, readyBytes >> 20, building, candidates.size() - next, DhUpdates.LEAD);
    }

    /** Section send tick: {@link DhUpdates#LEAD} before front reaches midpoint, no earlier than detonation. */
    private long sendAt(long chunk) {
        long s = FrontSchedule.section(chunk);
        double dx = FrontSchedule.middle((int) s) - d.burst().x, dz = FrontSchedule.middle((int) (s >>> 32)) - d.burst().z, dy = d.burst().y - d.groundY();
        long due = d.gameTime() + (long) d.arrivalTicks(Math.sqrt(dx * dx + dy * dy + dz * dz));
        return Math.max(d.gameTime(), due - DhUpdates.LEAD);
    }

    /** Send due sections whole to DH while DH window/tick time remain (first always if window available). */
    private void release(ServerLevel level, long now) {
        long until = System.nanoTime() + SEND_NANOS;
        boolean first = true;
        for (LongArrayList section; (section = schedule.head(now)) != null; first = false) {
            if (!first && System.nanoTime() >= until || section.size() > DhUpdates.heldRoom(level)) return;
            long[] chunks = section.toLongArray();
            schedule.pop();
            for (long chunk : chunks) send(level, chunk, now);
        }
    }

    private void send(ServerLevel level, long chunk, long now) {
        Copy c = copies.get(chunk);
        if (c == null || c.state != READY) return;
        if (FarLods.inWorld(level, chunk)) {
            drop(chunk, c);
            loaded++;
            return;
        }
        // no twins on disk: set lamps by grid at front arrival (nuke blackout cascade faster than wave)
        boolean dark = PowerGrid.get(level).dark(ChunkPos.getX(chunk), ChunkPos.getZ(chunk), Math.max(now, c.due));
        int lamps = dark ? ChunkLights.darken(c.chunk.getSections(), c.lamps) : 0;
        if (!c.ruined && lamps == 0 && !c.lights) {
            // LOD already same; lit lamps require later district-light check, like sent copy (claim)
            readyBytes -= c.bytes;
            c.chunk = null;
            c.lit = c.lamps.length > 0;
            c.lamps = NO_LAMPS;
            c.dark = dark;
            c.state = c.lit ? SENT : NOTHING;
            unchanged++;
            return;
        }
        // DH stats start at first copy of this detonation (previous-world detonation acknowledgments excluded)
        if (!counting) {
            DhUpdates.takeHeldStats(level);
            counting = true;
        }
        // same chunk just sent via another DH path: DH would skip copy, retry next tick
        if (!DhUpdates.sendHeld(level, c.chunk)) {
            schedule.add(chunk, now + 1);
            return;
        }
        // copy memory counted while DH retains it
        readyBytes -= c.bytes;
        dhBytes += c.bytes;
        inDh.add(new long[] {System.nanoTime(), c.bytes});
        c.chunk = null;
        c.lit = c.lamps.length > 0;
        c.lamps = NO_LAMPS;
        c.state = SENT;
        c.dark = dark;
        sent++;
        long offset = now - c.due;
        if (offset > -DhUpdates.LEAD) late++;
        offsetSum += offset;
        offsetMin = Math.min(offsetMin, offset);
        offsetMax = Math.max(offsetMax, offset);
    }

    private void drop(long chunk, Copy c) {
        copies.remove(chunk);
        readyBytes -= c.bytes;
        schedule.remove(chunk);
    }

    /**
     * Copy removed without DH send: return claimed ruin/light requests to {@link FarLods} (requests ruins once
     * per chunk at wave arrival). Copy already removed, request will not find it.
     */
    private static void giveBack(ServerLevel level, long chunk, Copy c) {
        if (c.ruins) FarLods.request(level, chunk, true);
        if (c.lights) FarLods.request(level, chunk, false);
    }

    /**
     * {@link FarLods} request for copied chunk: ruins (wave arrived) claimed if built (still reading means copy
     * removed, use {@link FarLods}); district light claimed while held (lamps set for front arrival), after
     * send only if copy had no lamps or darkness at front (or now if passed) unchanged; otherwise before
     * front request copy again via ruins ({@link #AFTER_FRONT}, awaiting plan, thus front), after front use
     * {@link FarLods} (blackout cascade reached district after wave).
     */
    int claim(ServerLevel level, long chunk, boolean lights, long now) {
        Copy c = copies.get(chunk);
        if (c == null) return NONE;
        switch (c.state) {
            case BUILDING -> {
                if (!lights) {
                    // front preceded copy
                    copies.remove(chunk);
                    abandoned++;
                    return NONE;
                }
                c.lights = true;
                return OURS;
            }
            case READY -> {
                if (lights) c.lights = true;
                else c.ruins = true;
                return OURS;
            }
            case SENT -> {
                if (!lights || !c.lit) return OURS;
                boolean dark = PowerGrid.get(level).dark(ChunkPos.getX(chunk), ChunkPos.getZ(chunk), Math.max(now, c.due));
                if (dark == c.dark) return OURS;
                return now < c.due ? AFTER_FRONT : NONE;
            }
            default -> {
                return OURS;
            }
        }
    }

    /** Chunk loaded: world supplies ruins/LODs. */
    void loaded(long chunk) {
        Copy c = copies.get(chunk);
        if (c == null) return;
        if (c.state == READY) drop(chunk, c);
        else copies.remove(chunk);
        if (c.state == READY || c.state == BUILDING) loaded++;
    }

    /**
     * Cancellation, wrong detonation, heap stop, preparation end: copies unnecessary (in-flight reads finish and discarded).
     * After detonation, log once if copies existed.
     */
    void clear(ServerLevel level) {
        if (d != null && !reported && (announced || next > 0)) report(level);
        // claimed held/reading-copy requests return to FarLods after copies removed
        LongArrayList back = new LongArrayList();
        java.util.ArrayList<Copy> backCopies = new java.util.ArrayList<>();
        for (var e : copies.long2ObjectEntrySet()) {
            Copy c = e.getValue();
            if ((c.state == READY || c.state == BUILDING) && (c.ruins || c.lights)) {
                back.add(e.getLongKey());
                backCopies.add(c);
            }
        }
        copies.clear();
        schedule.clear();
        candidates.clear();
        next = 0;
        readyBytes = 0;
        for (int i = 0; i < back.size(); i++) giveBack(level, back.getLong(i), backCopies.get(i));
    }

    private void report(ServerLevel level) {
        reported = true;
        // acknowledgments shared across world: two detonations seconds apart split stats between summaries
        long[] dh = counting ? DhUpdates.takeHeldStats(level) : new long[6];
        Incoming.LOG.info("Detonation #{} (strike #{}): heavy zone LODs at the wave front — built {}, sent {} ({} less than {} ticks before the front; "
                        + "send minus front arrival min {} avg {} max {} ticks); not sent: loaded {}, over memory {}, front came first {}, unread or stale plan {}, "
                        + "nothing to show {}; Distant Horizons saved {} of {} held after avg {} ms (max {}), unconfirmed {}, still waiting {}",
                d.id(), strike, made, sent, late, DhUpdates.LEAD, sent == 0 ? 0 : offsetMin, sent == 0 ? 0 : offsetSum / sent, sent == 0 ? 0 : offsetMax,
                loaded, evicted, abandoned, failed, unchanged, dh[1], dh[0], dh[3], dh[4], dh[2], dh[5]);
    }

    /**
     * For tests: built, sent, late {@code due − LEAD}, send minus front arrival (min/max), loaded,
     * evicted, front early, build failed, nothing to show, awaiting send, bytes, reading, awaiting read.
     */
    long[] stats() {
        return new long[] {made, sent, late, sent == 0 ? 0 : offsetMin, sent == 0 ? 0 : offsetMax, loaded, evicted, abandoned, failed, unchanged,
                schedule.size(), bytes(), reading, candidates.size() - next};
    }
}
