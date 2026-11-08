package ua.zentix.incoming.nuclear.world;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import net.neoforged.neoforge.attachment.AttachmentHolder;
import ua.zentix.incoming.Incoming;
import ua.zentix.incoming.IncomingConfig;
import ua.zentix.incoming.work.ChunkLoadPace;
import ua.zentix.incoming.work.WorkScheduler;
import ua.zentix.incoming.nuclear.Detonation;
import ua.zentix.incoming.nuclear.NuclearEvents;
import ua.zentix.incoming.registry.ModAttachments;
import ua.zentix.incoming.strike.AreaLoader;
import ua.zentix.incoming.strike.StrikeWorld;
import ua.zentix.incoming.util.DiskStatus;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/** Visits saved, final-block chunks after the wave; the ordinary scar queue and vanilla saving do all writes. */
final class RuinSettle {
    private static final TicketType<UUID> TYPE = TicketType.create("incoming_ruin_settle", Comparator.<UUID>naturalOrder());
    static final int PENDING = 2048, READS = 128;
    private final SettleWindow window = new SettleWindow();
    private CompletableFuture<long[]> layout;
    private long[] order;
    private int loadingLimit;
    private long adaptAt;
    private boolean spare = true;
    private static final int RETRY = 20;
    private final HeapWatch.Tracker heap = new HeapWatch.Tracker();
    /**
     * Pending chunks in rotation order. fastutil's mixing hash: {@code Long.hashCode} of a chunk key is {@code x ^ z},
     * so a square of chunks collides into treeified bins, and every rotation boxed the key and allocated a node
     * (54 % of the server's allocation while a 250 kt pass waited, Zearth 07.11.2026).
     */
    private final it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap<Entry> entries = new it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap<>();
    /** Entries holding a ticket and entries with a read in flight, kept with each change instead of a scan per tick. */
    private int holding, reading;
    /** Entries visited by the last call (tests). */
    private int visited;
    /**
     * Keys of {@link #entries} by what each waits for, each entry in exactly one: holding a ticket or reading a header,
     * read as final blocks and waiting for a load slot, or waiting for a header read (first, after its ruins, or retry).
     */
    private final it.unimi.dsi.fastutil.longs.LongLinkedOpenHashSet active = new it.unimi.dsi.fastutil.longs.LongLinkedOpenHashSet(),
            ready = new it.unimi.dsi.fastutil.longs.LongLinkedOpenHashSet(), unread = new it.unimi.dsi.fastutil.longs.LongLinkedOpenHashSet();
    private Detonation detonation;
    private RuinProgress.Pass pass;
    private long nextReport, settled, loaded, began, checkAt;
    private boolean busy;

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
            if (detonation == null) { checkAt = level.getGameTime() + 100; return; }
        }
        if (order == null) {
            if (!layout.isDone()) return;
            order = layout.join();
            layout = null;
        }
        long now = level.getGameTime();
        NuclearWorld world = NuclearWorld.get(level);
        long room = Math.max(0, world.settleMemoryRoom(level) - (long) order.length * Long.BYTES);
        if (room < NuclearPrep.PLANS_CAP / 8) {
            world.trimSettleCache(detonation.id(), clock);
            room = Math.max(0, world.settleMemoryRoom(level) - (long) order.length * Long.BYTES);
        }
        boolean admit = world.settleCanAdmit(level) && !heap.tight() && room > 0;
        if (now >= adaptAt) {
            window.adapt(externalMillis(level), IncomingConfig.SERVER.workBudgetMs.get(), spare, admit);
            adaptAt = now + 20;
        }
        loadingLimit = admit ? window.limit(room, level.getHeight()) : 0;
        // Only entries that can move are visited: a pass keeps PENDING entries, most waiting for a load slot, and
        // visiting all of them every tick took 0.75 ms of server thread and spent the lane, so the window was cut for
        // "mod budget" with the server thread 40–47 % idle (250 kt, Zearth 07.11.2026). Each set rotates, so a short
        // work window does not starve its tail, and a player-held chunk awaiting autosave cannot block other work.
        visited = 0;
        for (int n = active.size(); n > 0 && !active.isEmpty() && clock.canStart(); n--) {
            long key = active.removeFirstLong();
            visited++;
            Entry e = entries.get(key);
            long unit = clock.begin();
            try {
                advance(level, key, e, now, progress);
            } finally {
                refile(key, e);
                clock.end(unit);
            }
        }
        // A disk load decodes on the server thread after this tick: start loads only as fast as they decode before the
        // next tick (ChunkLoadPace); in bursts they started 20 % of ticks 18–32 ms late after 1 Mt (Zearth r4 run6).
        ChunkLoadPace pace = ChunkLoadPace.of(level.getServer());
        while (admit && holding < loadingLimit && !ready.isEmpty() && clock.canStart()
                && (NuclearTickets.inMemory(level, ready.firstLong()) != null
                    || pace.tryStart(level, ChunkPos.getX(ready.firstLong()), ChunkPos.getZ(ready.firstLong())))) {
            long key = ready.removeFirstLong();
            visited++;
            Entry e = entries.get(key);
            long unit = clock.begin();
            try {
                StrikeWorld.get(level).areas().hold(level, area(e));
                e.held = true;
                e.heldAt = now;
                holding++;
                loaded++;
            } finally {
                refile(key, e);
                clock.end(unit);
            }
        }
        for (int n = unread.size(); n > 0 && reading < READS && clock.canStart(); n--) {
            long key = unread.removeFirstLong();
            visited++;
            Entry e = entries.get(key);
            if (now < e.retryAt) {
                unread.add(key);
                continue;
            }
            long unit = clock.begin();
            try {
                e.read = read(level, e.pos);
                reading++;
            } finally {
                refile(key, e);
                clock.end(unit);
            }
        }
        while (admit && entries.size() < PENDING && pass.cursor < total() && clock.canStart()) {
            long unit = clock.begin();
            try {
                ChunkPos pos = new ChunkPos(order[(int) pass.cursor++]);
                if (pass.pending.add(pos.toLong())) add(pos.toLong());
                progress.setDirty();
            } finally { clock.end(unit); }
        }
        spare = !clock.overdue();
        if (pass.cursor >= total() && entries.isEmpty()) {
            report(now, true);
            pass.complete = true;
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

    /**
     * Held or reading entry ({@link #active}): checks the hold and the header read. Returns false when the chunk is
     * settled and the entry removed.
     */
    private boolean advance(ServerLevel level, long key, Entry e, long now, RuinProgress progress) {
        if (e.held && now - e.heldAt > FarZone.HOLD_LIMIT) {
            release(level, e);
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
                return true;
            }
            e.read = null;
            reading--;
            if (!h.finalBlocks || h.scar >= detonation.id()) {
                release(level, e);
                entries.remove(key);
                pass.pending.remove(key);
                progress.setDirty();
                if (h.scar >= detonation.id()) settled++;
                return false;
            }
            e.finalBlocks = true;
            e.retryAt = now + RETRY;
        }
        return true;
    }

    private void add(long key) {
        Entry e = new Entry(key);
        if (entries.put(key, e) != null) active.remove(key);
        ready.remove(key);
        unread.remove(key);
        place(key, e);
    }

    /** Files a visited entry again unless it was settled; also after a throw, so the entry is retried, not lost. */
    private void refile(long key, Entry e) {
        if (entries.get(key) == e) place(key, e);
    }

    /** Files an entry under what it waits for: its ticket or read, a load slot, or a header read. */
    private void place(long key, Entry e) {
        if (e.held || e.read != null) active.add(key);
        else if (e.finalBlocks && !e.applied) ready.add(key);
        else unread.add(key);
    }

    private void select(ServerLevel level, NuclearEvents events, RuinProgress progress) {
        // Retained pre-upgrade detonations have no progress entry and start at zero.
        ArrayList<Detonation> all = new ArrayList<>(events.past());
        all.addAll(events.detonations());
        all.sort(Comparator.comparingInt(Detonation::id));
        busy = false;
        for (Detonation d : all) {
            RuinProgress.Pass p = progress.pass(d.id());
            if (p.complete && p.pending.isEmpty()) continue;
            busy = true;
            if (level.getGameTime() < d.gameTime() + d.arrivalTicks(Math.hypot(d.ruinRadius() + 32, d.burst().y - d.groundY()))) continue;
            detonation = d;
            pass = p;
            java.nio.file.Path folder = ua.zentix.incoming.util.RegionFiles.folder(level);
            // a region file with data (an empty one is what a read of a never-saved region leaves)
            Regions on = regions != null ? regions : (rx, rz) -> ua.zentix.incoming.util.RegionFiles.present(folder, rx << 5, rz << 5);
            // chunks still queued for writing count: their region file exists once the queue drains
            CompletableFuture<Void> written = level.getChunkSource().chunkMap.chunkScanner() instanceof net.minecraft.world.level.chunk.storage.IOWorker io
                    ? io.synchronize(false) : CompletableFuture.completedFuture(null);
            layout = written.thenApplyAsync(v -> layout(d, on), net.minecraft.Util.backgroundExecutor());
            for (long key : p.pending) add(key);
            began = level.getGameTime();
            nextReport = began + 1200;
            settled = loaded = 0;
            return;
        }
    }

    /** Region files to scan; null: those on disk. Tests set it to scan a world they did not save. */
    @org.jetbrains.annotations.Nullable
    Regions regions;

    /** Region file present on disk, by region coordinates. */
    interface Regions {
        boolean present(int rx, int rz);
    }

    static long[] layout(Detonation d) {
        return layout(d, (rx, rz) -> true);
    }

    /**
     * Same compact distance sort as distant LOD rings, built off-thread. Quantization: 1/16 block. Only chunks of region
     * files on disk: the rest of the circle was never generated (open maps fill a fraction of a megaton's 0.5 psi circle;
     * 15 kt: 1.1 million positions), and a chunk generated later gets its ruins when it loads. A region file created
     * after the layout only adds positions, so a restored cursor can repeat chunks, never skip them.
     */
    static long[] layout(Detonation d, Regions regions) {
        int cx = (int) Math.floor(d.burst().x / 16), cz = (int) Math.floor(d.burst().z / 16);
        int radius = (int) Math.ceil(d.ruinRadius() / 16) + 1;
        var keyed = new it.unimi.dsi.fastutil.longs.LongArrayList();
        it.unimi.dsi.fastutil.longs.Long2BooleanOpenHashMap present = new it.unimi.dsi.fastutil.longs.Long2BooleanOpenHashMap();
        for (int x = cx - radius; x <= cx + radius; x++) for (int z = cz - radius; z <= cz + radius; z++) {
            int rx = x >> 5, rz = z >> 5;
            if (!present.computeIfAbsent(ChunkPos.asLong(rx, rz), k -> regions.present(rx, rz))) {
                z |= 31;
                continue;
            }
            ChunkPos pos = new ChunkPos(x, z);
            if (ScarQueue.inRange(pos, d)) {
                long distance = (long) (NuclearPrep.horizontal(pos, d) * 16);
                keyed.add(distance << 32 | (long) (x - cx + 0x8000) << 16 | (z - cz + 0x8000));
            }
        }
        long[] result = keyed.toLongArray();
        java.util.Arrays.sort(result);
        for (int i = 0; i < result.length; i++) result[i] = ChunkPos.asLong(
                cx + (int) (result[i] >> 16 & 0xffff) - 0x8000, cz + (int) (result[i] & 0xffff) - 0x8000);
        return result;
    }

    private long total() { return order.length; }

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
        return ua.zentix.incoming.util.RegionFiles.read(level, pos).thenApply(tag -> {
            CompoundTag data = tag.orElseGet(CompoundTag::new);
            return new Header(DiskStatus.of(data).blocksFinal(), data.getCompound(AttachmentHolder.ATTACHMENTS_NBT_KEY).getInt("incoming:chunk_scar"));
        });
    }

    private AreaLoader.Area area(Entry e) {
        return new AreaLoader.Area(TYPE, e.pos, 0, new UUID(detonation.id(), e.pos.toLong()), false);
    }

    private void release(ServerLevel level, Entry e) {
        if (!e.held) return;
        StrikeWorld.get(level).areas().release(level, area(e));
        e.held = false;
        holding--;
    }

    void clear(ServerLevel level) {
        for (Entry e : entries.values()) release(level, e);
        entries.clear();
        active.clear();
        ready.clear();
        unread.clear();
        holding = reading = 0;
        detonation = null;
        pass = null;
        order = null;
        if (layout != null) layout.cancel(false);
        layout = null;
        busy = false;
        checkAt = 0;
    }

    int held() { return holding; }
    /** For tests: entries visited last call, and entries read as final blocks waiting for a load slot. */
    int visited() { return visited; }
    int waiting() { return ready.size(); }
    int loadingLimit() { return loadingLimit; }
    int reads() { return reading; }

    private void report(long now, boolean done) {
        Incoming.LOG.info("Ruin settlement #{}: {} of {} positions scanned, {} saved chunks confirmed, {} loads, {} pending ({} held, admission {}, {} reads), {}, {} ticks{}",
                detonation.id(), pass.cursor, total(), settled, loaded, entries.size(), held(), loadingLimit, reads(),
                window.report(), now - began, done ? "; complete" : "");
    }
}
