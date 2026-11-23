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
    private Pending pending;
    private final CompletableFuture<Encoded> encoding;

    public record Encoded(byte[] bytes, List<S2C.NukeMaterial> packets) {
        static Encoded of(UUID id, byte[] bytes) {
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

    public static MaterialBirth seal(Detonation d, long tick, int under, Long2IntOpenHashMap chunks, LoadedSurfaces.Snapshot live) {
        return new MaterialBirth(UUID.randomUUID(), (int) Math.floor(d.burst().x), (int) Math.floor(d.burst().z),
                new Pending(d, tick, under, chunks, live), null);
    }
    private MaterialBirth(UUID id, int x, int z, Pending snapshot, byte[] saved) {
        this.id = id;
        this.x = x;
        this.z = z;
        pending = snapshot;
        encoding = CompletableFuture.supplyAsync(() -> {
            byte[] bytes = saved != null ? saved : MaterialMap.build(DustGrid.Bounds.of(snapshot.geometry),
                    snapshot.samples(), snapshot.under, snapshot.tick).encode();
            return Encoded.of(id, bytes);
        }, Util.backgroundExecutor());
    }

    /** Server thread. Zero wait even on the birth tick. */
    public Encoded encoded() {
        Encoded ready = encoding.getNow(null);
        if (ready != null) pending = null;
        return ready;
    }

    public CompoundTag save() {
        CompoundTag t = new CompoundTag();
        t.putUUID("revision", id);
        t.putInt("x", x);
        t.putInt("z", z);
        Encoded ready = encoded();
        if (ready != null) t.putByteArray("map", ready.bytes);
        else {
            Pending p = pending;
            if (p == null) {
                t.putByteArray("map", savedBytes);
                return t;
            }
            t.put("geometry", Detonation.CODEC.encodeStart(NbtOps.INSTANCE, p.geometry).getOrThrow());
            t.putLong("tick", p.tick);
            t.putInt("under", p.under);
            var samples = p.samples();
            long[] keys = new long[samples.size()];
            int[] votes = new int[keys.length];
            int i = 0;
            for (var entry : samples.long2IntEntrySet()) {
                keys[i] = entry.getLongKey();
                votes[i++] = entry.getIntValue();
            }
            t.putLongArray("chunks", keys);
            t.putIntArray("votes", votes);
        }
        return t;
    }
    private byte[] savedBytes;
    public static MaterialBirth load(CompoundTag t) {
        UUID id = t.getUUID("revision");
        int x = t.getInt("x"), z = t.getInt("z");
        if (t.contains("map")) {
            byte[] bytes = t.getByteArray("map");
            var birth = new MaterialBirth(id, x, z, null, bytes);
            birth.savedBytes = bytes;
            return birth;
        }
        var chunks = new Long2IntOpenHashMap(t.getLongArray("chunks"), t.getIntArray("votes"));
        var p = new Pending(Detonation.CODEC.parse(NbtOps.INSTANCE, t.get("geometry")).getOrThrow(),
                t.getLong("tick"), t.getInt("under"), chunks, null);
        return new MaterialBirth(id, x, z, p, null);
    }
}
