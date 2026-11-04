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
        String archive = "";
        final LongOpenHashSet pending = new LongOpenHashSet();
    }

    private final Map<Integer, Pass> passes = new HashMap<>();

    static RuinProgress get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(new Factory<>(RuinProgress::new, RuinProgress::load, null), "incoming_ruin_settle");
    }

    Pass pass(int id) {
        return passes.computeIfAbsent(id, ignored -> new Pass());
    }

    String archive(int id) { Pass pass = passes.get(id); return pass == null ? "" : pass.archive; }

    static RuinProgress load(CompoundTag tag, HolderLookup.Provider registries) {
        RuinProgress progress = new RuinProgress();
        for (String key : tag.getAllKeys()) {
            if (!tag.contains(key, Tag.TAG_COMPOUND)) continue;
            CompoundTag saved = tag.getCompound(key);
            Pass p = progress.pass(Integer.parseInt(key));
            // Early development builds used a north-to-south cursor. Restart that scan safely;
            // storage scar marks make revisiting completed positions harmless.
            p.cursor = saved.getBoolean("radial") ? Math.max(0, saved.getLong("cursor")) : 0;
            p.complete = saved.getBoolean("radial") && saved.getBoolean("complete");
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
            saved.putBoolean("radial", true);
            saved.putBoolean("complete", p.complete);
            saved.putLongArray("pending", p.pending.toLongArray());
            tag.put(Integer.toString(id), saved);
        });
        return tag;
    }
}
