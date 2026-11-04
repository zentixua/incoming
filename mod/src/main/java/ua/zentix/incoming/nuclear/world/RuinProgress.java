package ua.zentix.incoming.nuclear.world;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;

import java.util.HashMap;
import java.util.Map;

/** Durable scan cursor and unconfirmed chunks. A cursor alone can run ahead of vanilla chunk saves. */
final class RuinProgress extends LedgerData {
    static final class Pass {
        long cursor;
        boolean complete;
        String archive = "";
        final LongOpenHashSet pending = new LongOpenHashSet();
        int capacity;

        long retainedBytes() {
            capacity = Math.max(capacity, pending.size());
            return 128L + 2L * archive.length() + capacity * 64L;
        }

        void compact() {
            if (pending.isEmpty()) { pending.trim(); capacity = 0; }
        }
    }

    private final Map<Integer, Pass> passes = new HashMap<>();

    static RuinProgress get(ServerLevel level) {
        NuclearWorld world = level.getData(ua.zentix.incoming.registry.ModAttachments.NUCLEAR_WORLD);
        return world.ledgers.get(level, world, "incoming_ruin_settle", RuinProgress::new);
    }

    Pass pass(int id) {
        requireWritable();
        return passes.computeIfAbsent(id, ignored -> new Pass());
    }

    String archive(int id) { Pass pass = passes.get(id); return pass == null ? "" : pass.archive; }

    long retainedBytes() {
        long bytes = IO_BYTES + 256L;
        for (Pass pass : passes.values()) bytes += pass.retainedBytes();
        return bytes;
    }

    static RuinProgress load(CompoundTag tag, HolderLookup.Provider registries) {
        return read(tag, registries, new RuinProgress());
    }

    static RuinProgress read(CompoundTag tag, HolderLookup.Provider registries, RuinProgress progress) {
        for (String key : tag.getAllKeys()) {
            if (!tag.contains(key, Tag.TAG_COMPOUND)) continue;
            CompoundTag saved = tag.getCompound(key);
            Pass p = progress.pass(Integer.parseInt(key));
            // The bounded row-head iterator has a different tie order from the former quantized
            // whole-zone sort. Revisit unfinished legacy scans; saved scar marks prevent reapplying.
            p.cursor = saved.getInt("scan_version") == 2 ? Math.max(0, saved.getLong("cursor")) : 0;
            p.complete = saved.getBoolean("complete");
            if (p.complete) p.cursor = Long.MAX_VALUE;
            p.archive = saved.getString("archive");
            p.pending.addAll(new LongOpenHashSet(saved.getLongArray("pending")));
        }
        return progress;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        passes.forEach((id, p) -> {
            CompoundTag saved = new CompoundTag();
            saved.putLong("cursor", p.cursor);
            if (!p.archive.isEmpty()) saved.putString("archive", p.archive);
            saved.putInt("scan_version", 2);
            saved.putBoolean("complete", p.complete);
            saved.putLongArray("pending", p.pending.toLongArray());
            tag.put(Integer.toString(id), saved);
        });
        return tag;
    }
    @Override void write(LedgerWriter writer, HolderLookup.Provider registries) throws java.io.IOException {
        for (var entry : passes.entrySet()) {
            writer.compound(Integer.toString(entry.getKey()));
            Pass p = entry.getValue();
            CompoundTag row = new CompoundTag();
            row.putLong("cursor", p.cursor);
            if (!p.archive.isEmpty()) row.putString("archive", p.archive);
            row.putInt("scan_version", 2);
            row.putBoolean("complete", p.complete);
            writer.fields(row);
            writer.longs("pending", p.pending);
            writer.end();
        }
    }
}
