package ua.zentix.incoming.nuclear.material;

import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import net.minecraft.Util;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import ua.zentix.incoming.nuclear.Detonation;
import ua.zentix.incoming.net.S2C;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/** A sealed set of votes belongs exclusively to this revision. Disk saves can race the worker without waiting. */
public final class MaterialBirth {
    private record Pending(Detonation geometry, long tick, int under, Long2IntOpenHashMap chunks, LoadedSurfaces.Snapshot live) {
        Long2IntOpenHashMap samples() {
            if (live == null) return chunks;
            var all = chunks.clone();
            live.overlay(all);
            return all;
        }
    }
    public final UUID id;
    public final int x, z;
    private long started;
    public long started() { return started; }
    private Pending pending;
    private CompletableFuture<Encoded> encoding;
    private final byte[] savedBytes;
    private Timeline timeline;
    private int seed;
    private boolean finished;
    private Encoded published;
    private long measuredCells;
    public int seed() { return seed; }
    public long ownCells() { return measuredCells; }
    public boolean surveying() { return timeline != null && !finished; }
    public Detonation geometry() { return timeline.initial.geometry; }
    public long burstTick() { return timeline.burst; }

    /** Freeze the observation stream at each cell's own deadline. The journal also survives a mid-wave save. */
    public static MaterialBirth prepare(UUID id, Detonation d) {
        var b = new MaterialBirth(id, (int) Math.floor(d.burst().x), (int) Math.floor(d.burst().z), null, null);
        b.timeline = new Timeline();
        return b;
    }

    public void begin(Detonation d, long burst, long now, int under, Long2IntOpenHashMap samples, LoadedSurfaces.Snapshot live) {
        started = now;
        pending = new Pending(d, now, under, samples, live);
        seed = under < 0 ? DustMaterial.SOIL.ordinal() : under;
        timeline.start(pending, burst);
    }
    public void advance(long tick, Long2IntOpenHashMap changes) {
        if (!surveying()) return;
        timeline.until = Math.max(timeline.until, tick);
        if (!changes.isEmpty()) timeline.tail = new Batch(tick, changes, timeline.tail);
    }

    private record Batch(long tick, Long2IntOpenHashMap votes, Batch previous) {}
    private record Publication(Encoded data, long own, boolean complete) {}
    private static final class Timeline {
        Pending initial;
        long burst;
        long until, requested = Long.MIN_VALUE;
        Batch tail, processed;
        MaterialFrontier frontier;
        CompletableFuture<Publication> worker;
        Timeline() {}
        void start(Pending p, long burst) {
            initial = p;
            this.burst = burst;
            until = p.tick;
        }
        static java.util.ArrayDeque<Batch> after(Batch tail, Batch stop) {
            var ordered = new java.util.ArrayDeque<Batch>();
            for (Batch b = tail; b != stop; b = b.previous) ordered.push(b);
            return ordered;
        }
        Publication reduce(UUID id, int seed, Batch last, long tick) {
            if (frontier == null) {
                frontier = new MaterialFrontier(initial.geometry, seed);
                frontier.observe(initial.chunks);
                if (initial.live != null) {
                    var ready = new Long2IntOpenHashMap();
                    initial.live.overlay(ready, MaterialFrontier.LIVE);
                    frontier.observe(ready);
                }
            }
            for (var b : after(last, processed)) {
                frontier.closeThrough(b.tick - burst - 1);
                frontier.observe(b.votes);
            }
            processed = last;
            frontier.closeThrough(tick - burst);
            var map = frontier.snapshot(tick);
            return new Publication(Encoded.of(id, map.encode()), frontier.own(), map.complete());
        }
    }

    public record Encoded(byte[] bytes, List<S2C.NukeMaterial> packets) {
        public static Encoded of(UUID id, byte[] bytes) {
            int count = Math.ceilDiv(bytes.length, S2C.NukeMaterial.PART);
            var packets = new java.util.ArrayList<S2C.NukeMaterial>(count);
            for (int i = 0; i < count; i++) {
                int from = i * S2C.NukeMaterial.PART;
                packets.add(new S2C.NukeMaterial(id, i, count - i - 1,
                        Arrays.copyOfRange(bytes, from, Math.min(bytes.length, from + S2C.NukeMaterial.PART))));
            }
            return new Encoded(bytes, List.copyOf(packets));
        }
    }

    public static MaterialBirth seal(UUID revision, Detonation d, long tick, int under, Long2IntOpenHashMap chunks, LoadedSurfaces.Snapshot live) {
        return new MaterialBirth(revision, (int) Math.floor(d.burst().x), (int) Math.floor(d.burst().z),
                new Pending(d, tick, under, chunks, live), null);
    }
    private MaterialBirth(UUID id, int x, int z, Pending snapshot, byte[] saved) {
        this.id = id;
        this.x = Math.clamp(x, -30000000, 30000000);
        this.z = Math.clamp(z, -30000000, 30000000);
        seed = snapshot == null ? DustMaterial.SOIL.ordinal() : Math.max(0, snapshot.under);
        if (snapshot != null && snapshot.under < 0) {
            int origin = snapshot.chunks.get(net.minecraft.world.level.ChunkPos.asLong(x >> 4, z >> 4));
            seed = origin == 0 ? DustMaterial.SOIL.ordinal() : DustMaterial.ofVotes(origin).ordinal();
        }
        started = snapshot == null ? 0 : snapshot.tick;
        pending = snapshot;
        savedBytes = saved;
    }

    /** Server thread. Encoding admission is separate from committing the birth revision. */
    public Encoded encoded() {
        if (published != null && finished) return published;
        if (timeline != null) {
            var t = timeline;
            if (t.worker != null && t.worker.isDone()) {
                var update = t.worker.join();
                published = update.data;
                measuredCells = update.own;
                finished = update.complete;
                t.worker = null;
                if (finished) { timeline = null; pending = null; return published; }
            }
            if (t.worker == null && (t.requested == Long.MIN_VALUE || t.until - t.requested >= 10)) {
                long until = t.until;
                Batch tail = t.tail;
                t.requested = until;
                t.worker = CompletableFuture.supplyAsync(() -> t.reduce(id, seed, tail, until), Util.backgroundExecutor())
                        .exceptionally(error -> new Publication(fallback(error), 0, true));
            }
            return published;
        }
        if (encoding == null) {
            Pending snapshot = pending;
            encoding = CompletableFuture.supplyAsync(() -> {
                byte[] bytes = savedBytes != null ? savedBytes : MaterialMap.build(DustGrid.Bounds.of(snapshot.geometry),
                        snapshot.samples(), snapshot.under, snapshot.tick).encode();
                return Encoded.of(id, bytes);
            }, Util.backgroundExecutor()).exceptionally(this::fallback);
        }
        Encoded ready = encoding.getNow(null);
        if (ready != null) pending = null;
        return ready;
    }

    private Encoded fallback(Throwable error) {
        ua.zentix.incoming.Incoming.LOG.warn("Using uniform ground material for source {}", id, error);
        var bounds = new DustGrid.Bounds(x, z, 0, 65536);
        return Encoded.of(id, MaterialMap.uniform(bounds, seed).encode());
    }

    private static void writeVotes(CompoundTag t, Long2IntOpenHashMap votes) {
        long[] keys = new long[votes.size()];
        int[] values = new int[keys.length];
        int i = 0;
        for (var e : votes.long2IntEntrySet()) {
            keys[i] = e.getLongKey();
            values[i++] = e.getIntValue();
        }
        t.putLongArray("chunks", keys);
        t.putIntArray("votes", values);
    }
    private static Long2IntOpenHashMap readVotes(CompoundTag t) {
        if (!t.contains("chunks", net.minecraft.nbt.Tag.TAG_LONG_ARRAY) || !t.contains("votes", net.minecraft.nbt.Tag.TAG_INT_ARRAY))
            throw new IllegalArgumentException("Missing ground observations");
        return new Long2IntOpenHashMap(t.getLongArray("chunks"), t.getIntArray("votes"));
    }
    public CompoundTag save() {
        CompoundTag t = new CompoundTag();
        t.putUUID("revision", id);
        t.putInt("x", x);
        t.putInt("z", z);
        t.putInt("seed", seed);
        Encoded ready = finished ? published : encoding == null ? null : encoding.getNow(null);
        if (ready != null || savedBytes != null) t.putByteArray("map", ready != null ? ready.bytes : savedBytes);
        else {
            Pending p = pending;
            t.put("geometry", Detonation.CODEC.encodeStart(NbtOps.INSTANCE, p.geometry).getOrThrow());
            t.putLong("tick", p.tick);
            t.putInt("under", p.under);
            var samples = p.chunks;
            if (p.live != null) {
                samples = samples.clone();
                p.live.overlay(samples, timeline == null ? 0 : MaterialFrontier.LIVE);
            }
            if (timeline != null) {
                t.putLong("burst", timeline.burst);
                t.putLong("until", timeline.until);
                var list = new net.minecraft.nbt.ListTag();
                for (var batch : Timeline.after(timeline.tail, null)) {
                    var item = new CompoundTag();
                    item.putLong("at", batch.tick);
                    writeVotes(item, batch.votes);
                    list.add(item);
                }
                t.put("observations", list);
            }
            writeVotes(t, samples);
        }
        return t;
    }
    public static MaterialBirth load(CompoundTag t) {
        if (!t.hasUUID("revision") || !t.contains("x", net.minecraft.nbt.Tag.TAG_INT) || !t.contains("z", net.minecraft.nbt.Tag.TAG_INT))
            throw new IllegalArgumentException("Incomplete ground material entry");
        UUID id = t.getUUID("revision");
        int x = t.getInt("x"), z = t.getInt("z");
        if (t.contains("map")) {
            byte[] bytes = t.getByteArray("map");
            var map = MaterialMap.decode(bytes);
            if (!map.complete() || map.bounds().x() != x || map.bounds().z() != z)
                throw new IllegalArgumentException("Ground material source bounds");
            var b = new MaterialBirth(id, x, z, null, bytes);
            b.seed = t.contains("seed") ? t.getInt("seed") : map.wet() ? 0 : DustMaterial.SOIL.ordinal();
            DustMaterial.of(b.seed);
            return b;
        }
        if (t.getInt("under") < -1 || t.getInt("under") > 3) throw new IllegalArgumentException("Invalid ground seed");
        var chunks = readVotes(t);
        var p = new Pending(Detonation.CODEC.parse(NbtOps.INSTANCE, t.get("geometry")).getOrThrow(),
                t.getLong("tick"), t.getInt("under"), chunks, null);
        var birth = new MaterialBirth(id, x, z, p, null);
        if (t.contains("burst")) {
            birth.timeline = new Timeline();
            birth.timeline.start(p, t.getLong("burst"));
            for (var tag : t.getList("observations", net.minecraft.nbt.Tag.TAG_COMPOUND)) {
                var item = (CompoundTag) tag;
                birth.advance(item.getLong("at"), readVotes(item));
            }
            birth.timeline.until = t.getLong("until");
        }
        return birth;
    }
}
