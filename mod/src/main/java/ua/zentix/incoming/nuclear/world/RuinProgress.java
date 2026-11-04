package ua.zentix.incoming.nuclear.world;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.HashMap;
import java.util.Map;

/** Durable scan cursor and unconfirmed chunks. A cursor alone can run ahead of vanilla chunk saves. */
final class RuinProgress extends SavedData {
    static final class Pass {
        long cursor;
        boolean complete;
        final LongOpenHashSet pending = new LongOpenHashSet();
        int capacity;

        long retainedBytes() {
            capacity = Math.max(capacity, pending.size());
            return 128L + capacity * 64L;
        }

        void compact() {
            if (pending.isEmpty()) { pending.trim(); capacity = 0; }
        }
    }

    private final Map<Integer, Pass> passes = new HashMap<>();

    static RuinProgress get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(new Factory<>(RuinProgress::new, RuinProgress::load, null), "incoming_ruin_settle");
    }

    Pass pass(int id) {
        return passes.computeIfAbsent(id, ignored -> new Pass());
    }

    long retainedBytes() {
        long bytes = 256L;
        for (Pass pass : passes.values()) bytes += pass.retainedBytes();
        return bytes;
    }

    static RuinProgress load(CompoundTag tag, HolderLookup.Provider registries) {
        RuinProgress progress = new RuinProgress();
        for (String key : tag.getAllKeys()) {
            if (!tag.contains(key, Tag.TAG_COMPOUND)) continue;
            CompoundTag saved = tag.getCompound(key);
            Pass p = progress.pass(Integer.parseInt(key));
            // The bounded row-head iterator has a different tie order from the former quantized
            // whole-zone sort. Revisit unfinished legacy scans; saved scar marks prevent reapplying.
            p.cursor = saved.getInt("scan_version") == 2 ? Math.max(0, saved.getLong("cursor")) : 0;
            p.complete = saved.getBoolean("complete");
            if (p.complete) p.cursor = Long.MAX_VALUE;
            p.pending.addAll(new LongOpenHashSet(saved.getLongArray("pending")));
        }
        return progress;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        passes.forEach((id, p) -> {
            CompoundTag saved = new CompoundTag();
            saved.putLong("cursor", p.cursor);
            saved.putInt("scan_version", 2);
            saved.putBoolean("complete", p.complete);
            saved.putLongArray("pending", p.pending.toLongArray());
            tag.put(Integer.toString(id), saved);
        });
        return tag;
    }
}
