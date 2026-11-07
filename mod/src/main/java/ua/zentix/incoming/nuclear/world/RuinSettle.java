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
    private CompletableFuture<long[]> layout;
    private long[] order;
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
                if (e.read == null && !e.held && (!e.finalBlocks || e.applied) && now >= e.retryAt && reading < READS) {
                    e.read = read(level, e.pos);
                    reading++;
                }
                // Rotate even waiting entries, so a short work window does not starve the tail.
                entries.remove(e.pos.toLong());
                entries.put(e.pos.toLong(), e);
            } finally { clock.end(unit); }
        }
        while (admit && entries.size() < PENDING && pass.cursor < total() && clock.canStart()) {
            long unit = clock.begin();
            try {
                ChunkPos pos = new ChunkPos(order[(int) pass.cursor++]);
                if (pass.pending.add(pos.toLong())) entries.put(pos.toLong(), new Entry(pos.toLong()));
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
            for (long key : p.pending) entries.put(key, new Entry(key));
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
        if (e.held) StrikeWorld.get(level).areas().release(level, area(e));
        e.held = false;
    }

    void clear(ServerLevel level) {
        for (Entry e : entries.values()) release(level, e);
        entries.clear();
        detonation = null;
        pass = null;
        order = null;
        if (layout != null) layout.cancel(false);
        layout = null;
        busy = false;
        checkAt = 0;
    }

    int held() { return (int) entries.values().stream().filter(e -> e.held).count(); }
    int reads() { return (int) entries.values().stream().filter(e -> e.read != null).count(); }

    private void report(long now, boolean done) {
        Incoming.LOG.info("Ruin settlement #{}: {} of {} positions scanned, {} saved chunks confirmed, {} loads, {} pending ({} held, admission {}, {} reads), {}, {} ticks{}",
                detonation.id(), pass.cursor, total(), settled, loaded, entries.size(), held(), loadingLimit, reads(),
                window.report(), now - began, done ? "; complete" : "");
    }
}
