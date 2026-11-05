package ua.zentix.incoming.nuclear.world;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import net.neoforged.neoforge.attachment.AttachmentHolder;
import ua.zentix.incoming.Incoming;
import ua.zentix.incoming.IncomingConfig;
import ua.zentix.incoming.work.WorkScheduler;
import ua.zentix.incoming.nuclear.Detonation;
import ua.zentix.incoming.nuclear.NuclearEvents;
import ua.zentix.incoming.registry.ModAttachments;
import ua.zentix.incoming.strike.AreaLoader;
import ua.zentix.incoming.strike.StrikeWorld;
import ua.zentix.incoming.util.DiskStatus;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/** Visits saved, final-block chunks after the wave; the ordinary scar queue and vanilla saving do all writes. */
final class RuinSettle {
    private static final TicketType<UUID> TYPE = TicketType.create("incoming_ruin_settle", Comparator.<UUID>naturalOrder());
    static final int PENDING = 2048, READS = 128;
    private final SettleWindow window = new SettleWindow();
    private RadialChunks order;
    private long replayed;
    private int loadingLimit;
    private long adaptAt;
    private boolean spare = true;
    private static final int RETRY = 20;
    private final HeapWatch.Tracker heap = new HeapWatch.Tracker();
    private final Map<Long, Entry> entries = new LinkedHashMap<>();
    private Detonation detonation;
    private RuinProgress.Pass pass;
    private long nextReport, settled, loaded, began, checkAt;
    private boolean busy;
    private int selecting;
    private Detonation candidate;
    private boolean selectingHistory;
    private static final long READ_BYTES = 96L << 10;
    private final ArrayList<CompletableFuture<Header>> retiring = new ArrayList<>();

    private record Header(boolean finalBlocks, int scar) {}

    private static final class Entry {
        final ChunkPos pos;
        CompletableFuture<Header> read;
        boolean held, applied, finalBlocks;
        long retryAt, heldAt;
        Entry(long key) { pos = new ChunkPos(key); }
    }

    boolean busy() { return busy; }

    /** Called after player ruins, preparation and fallout; no independent time allowance. */
    void work(ServerLevel level, NuclearEvents events, WorkClock clock) {
        if (detonation == null && (level.getGameTime() < checkAt || events.past().isEmpty() && events.detonations().isEmpty())) return;
        RuinProgress progress = RuinProgress.get(level);
        if (detonation == null) {
            if (!clock.canStart()) return;
            long unit = clock.begin();
            try { select(level, events, progress); } finally { clock.end(unit); }
            if (detonation == null) {
                if (!selectingHistory) checkAt = level.getGameTime() + 100;
                return;
            }
        }
        NuclearWorld world = NuclearWorld.get(level);
        // Free re-readable ownership before asking for room to construct the traversal itself.
        // A capacity-blocked scar keeps its context alive; admission cannot also gate cleanup.
        if (world.settleMemoryRoom(level) < NuclearPrep.PLANS_CAP / 8)
            world.trimSettleCache(detonation.id(), clock);
        if (!order.prepared()) {
            if (clock.canStart()) {
                long unit = clock.begin();
                try (var reservation = RuinMemory.reserve(level, 4096)) {
                    if (reservation != null) order.prepare(32);
                } finally { clock.end(unit); }
            }
            return;
        }
        // Old saves migrate by replaying scar-marked chunks once. Current saves resume their
        // streamed cursor in bounded batches; no area-sized array is ever constructed or retained.
        while (replayed < pass.cursor && !order.done() && clock.canStart()) {
            long unit = clock.begin();
            try {
                for (int i = 0; i < 64 && replayed < pass.cursor && !order.done(); i++, replayed++) order.advance();
            } finally { clock.end(unit); }
        }
        if (replayed < pass.cursor && !order.done()) return;
        long now = level.getGameTime();
        long room = world.settleMemoryRoom(level);
        boolean admit = world.settleCanAdmit(level) && !heap.tight() && room > 0;
        if (now >= adaptAt) {
            window.adapt(externalMillis(level), IncomingConfig.SERVER.workBudgetMs.get(), spare, admit);
            adaptAt = now + 20;
        }
        loadingLimit = admit ? window.limit(room, level.getHeight()) : 0;
        int holding = (int) entries.values().stream().filter(e -> e.held).count();
        int reading = (int) entries.values().stream().filter(e -> e.read != null).count();
        int visits = 0;
        // A bounded rotating window: a player-held chunk awaiting autosave cannot block all other work.
        for (Entry e : new ArrayList<>(entries.values())) {
            if (visits++ >= PENDING || !clock.canStart()) break;
            long unit = clock.begin();
            try {
                if (e.held && now - e.heldAt > FarZone.HOLD_LIMIT) {
                    release(level, e);
                    holding--;
                    e.finalBlocks = false;
                    e.retryAt = now + RETRY;
                    Incoming.LOG.warn("Ruin settlement: chunk {} did not finish; releasing and retrying", e.pos);
                }
                if (e.held) {
                    LevelChunk chunk = NuclearTickets.inMemory(level, e.pos.toLong());
                    if (chunk != null) {
                        ScarQueue scars = NuclearWorld.get(level).scars();
                        scars.offer(chunk, detonation);
                        if (chunk.getExistingData(ModAttachments.CHUNK_SCAR).orElse(0) >= detonation.id()) {
                            e.applied = true;
                            release(level, e);
                            holding--;
                        } else if (scars.waitsNeighbours(e.pos.toLong()) && scars.tileHoldsLeft() > 0) {
                            // A job loaded under another scar's neighbor ticket cannot acquire its own by default.
                            // Give it the normal bounded handoff, otherwise every settlement slot can wait forever.
                            scars.holdForTile(level, e.pos.toLong());
                        }
                    }
                }
                if (e.read != null && e.read.isDone()) {
                    Header h;
                    try { h = e.read.join(); }
                    catch (RuntimeException ex) {
                        e.read = null;
                        reading--;
                        e.retryAt = now + RETRY;
                        Incoming.LOG.warn("Ruin settlement: chunk {} read failed; retrying", e.pos, ex);
                        continue;
                    }
                    e.read = null;
                    reading--;
                    if (!h.finalBlocks || h.scar >= detonation.id()) {
                        if (e.held) holding--;
                        release(level, e);
                        entries.remove(e.pos.toLong());
                        pass.pending.remove(e.pos.toLong());
                        progress.setDirty();
                        if (h.scar >= detonation.id()) settled++;
                        continue;
                    }
                    e.finalBlocks = true;
                    e.retryAt = now + RETRY;
                }
                if (e.finalBlocks && !e.applied && !e.held && admit && holding < loadingLimit) {
                    StrikeWorld.get(level).areas().hold(level, area(e));
                    e.held = true;
                    e.heldAt = now;
                    holding++;
                    loaded++;
                }
                if (e.read == null && !e.held && (!e.finalBlocks || e.applied) && now >= e.retryAt && reading < READS
                        && NuclearPrep.memory(level, false) + READ_BYTES <= NuclearPrep.PLANS_CAP) {
                    e.read = read(level, e.pos);
                    reading++;
                }
                // Rotate even waiting entries, so a short work window does not starve the tail.
                entries.remove(e.pos.toLong());
                entries.put(e.pos.toLong(), e);
            } finally { clock.end(unit); }
        }
        while (admit && entries.size() < PENDING && !order.done() && clock.canStart()) {
            long unit = clock.begin();
            try (var reservation = RuinMemory.reserve(level, 2048)) {
                if (reservation == null) break;
                ChunkPos pos = new ChunkPos(order.peek());
                order.advance(); pass.cursor++; replayed++;
                if (ScarQueue.inRange(pos, detonation) && pass.pending.add(pos.toLong())) entries.put(pos.toLong(), new Entry(pos.toLong()));
                progress.setDirty();
            } finally { clock.end(unit); }
        }
        spare = !clock.overdue();
        if (order.done() && entries.isEmpty()) {
            report(now, true);
            pass.complete = true;
            pass.compact();
            progress.setDirty();
            detonation = null;
            order = null;
            pass = null;
            busy = false;
        } else if (now >= nextReport) {
            report(now, false);
            nextReport = now + 1200;
        }
    }

    private void select(ServerLevel level, NuclearEvents events, RuinProgress progress) {
        // Scan history in fixed-size slices. Do not copy/sort every old detonation in one tick.
        if (!selectingHistory) { selectingHistory = true; selecting = 0; candidate = null; busy = false; }
        int total = events.past().size() + events.detonations().size();
        for (int i = 0; i < 16 && selecting < total; i++, selecting++) {
            Detonation d = selecting < events.past().size() ? events.past().get(selecting)
                    : events.detonations().get(selecting - events.past().size());
            RuinProgress.Pass p = progress.pass(d.id());
            if (p.complete && p.pending.isEmpty()) continue;
            busy = true;
            if (level.getGameTime() < d.gameTime() + d.arrivalTicks(Math.hypot(d.ruinRadius() + 32, d.burst().y - d.groundY()))) continue;
            if (candidate == null || d.id() < candidate.id()) candidate = d;
        }
        if (selecting < total) return;
        selectingHistory = false;
        if (candidate == null) return;
        detonation = candidate;
        candidate = null;
        pass = progress.pass(detonation.id());
        order = new RadialChunks(detonation.burst().x, detonation.burst().z, -1, detonation.ruinRadius() + 32);
        replayed = 0;
        for (long key : pass.pending) entries.put(key, new Entry(key));
        began = level.getGameTime();
        nextReport = began + 1200;
        settled = loaded = 0;
    }

    long retainedBytes() {
        retiring.removeIf(CompletableFuture::isDone);
        return 512L + entries.size() * 1024L + (reads() + retiring.size()) * READ_BYTES
                + (order == null ? 0 : order.bytes());
    }

    private static double externalMillis(ServerLevel level) {
        var server = level.getServer();
        int tick = server.getTickCount();
        long[] times = server.getTickTimesNanos();
        long sum = 0;
        for (int i = 1; i <= 20; i++) sum += Math.max(0,
                times[Math.floorMod(tick - i, times.length)] - WorkScheduler.ownNanos(tick - i));
        return sum / 20e6;
    }

    private static CompletableFuture<Header> read(ServerLevel level, ChunkPos pos) {
        // ChunkStorage reads on its I/O thread and observes pending vanilla saves. No join until complete.
        return level.getChunkSource().chunkMap.read(pos).thenApply(tag -> {
            CompoundTag data = tag.orElseGet(CompoundTag::new);
            return new Header(DiskStatus.of(data).blocksFinal(), data.getCompound(AttachmentHolder.ATTACHMENTS_NBT_KEY).getInt("incoming:chunk_scar"));
        });
    }

    private AreaLoader.Area area(Entry e) {
        return new AreaLoader.Area(TYPE, e.pos, 0, new UUID(detonation.id(), e.pos.toLong()), false);
    }

    private void release(ServerLevel level, Entry e) {
        if (e.held) StrikeWorld.get(level).areas().release(level, area(e));
        e.held = false;
    }

    void clear(ServerLevel level) {
        for (Entry e : entries.values()) {
            release(level, e);
            if (e.read != null && !e.read.isDone()) retiring.add(e.read);
        }
        entries.clear();
        detonation = null;
        pass = null;
        order = null;
        busy = false;
        checkAt = 0;
        selectingHistory = false; candidate = null; selecting = 0;
    }

    int held() { return (int) entries.values().stream().filter(e -> e.held).count(); }
    int reads() { return (int) entries.values().stream().filter(e -> e.read != null).count(); }

    private void report(long now, boolean done) {
        Incoming.LOG.info("Ruin settlement #{}: {} positions scanned, {} saved chunks confirmed, {} loads, {} pending ({} held, admission {}, {} reads), {} ticks{}",
                detonation.id(), pass.cursor, settled, loaded, entries.size(), held(), loadingLimit, reads(),
                now - began, done ? "; complete" : "");
    }
}
