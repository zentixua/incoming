package ua.zentix.incoming.nuclear.world;

import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Material boundary of the existing nuclear bowl/rim model. Excavated ground supplies the rim;
 * its remainder is exported ejecta (the existing ejecta/cloud presentation), not invented local
 * rubble. Other materials inside the vaporization bowl and glazed surfaces are thermally transformed.
 * Counts and original solid volume/mass are retained per source state, including restart progress.
 */
public final class CraterMatter extends LedgerData {
    private static final String NAME = "incoming_crater_matter";
    private final Map<Integer, Pass> passes = new LinkedHashMap<>();
    /** Settled events need material totals, not a second permanent copy of every excavated column. */
    private final Map<BlockState, double[]> settled = new LinkedHashMap<>();

    void complete(int event) {
        requireWritable();
        Pass pass = passes.remove(event);
        if (pass == null) return;
        for (var entry : pass.materials.entrySet()) {
            double[] total = settled.computeIfAbsent(entry.getKey(), ignored -> new double[8]);
            for (int i = 0; i < total.length; i++) total[i] += entry.getValue()[i];
        }
        setDirty();
    }

    void retainActive(java.util.Set<Integer> active) {
        for (int event : java.util.List.copyOf(passes.keySet())) if (!active.contains(event)) complete(event);
    }

    double[] settled(BlockState state) { return settled.getOrDefault(state, new double[8]).clone(); }

    @Override CraterMatter snapshot() {
        requireWritable();
        CraterMatter copy = new CraterMatter();
        settled.forEach((state, values) -> copy.settled.put(state, values.clone()));
        passes.forEach((id, pass) -> {
            Pass frozen = copy.new Pass();
            frozen.soil = pass.soil;
            pass.materials.forEach((state, values) -> frozen.materials.put(state, values.clone()));
            frozen.available.putAll(pass.available);
            frozen.ground.putAll(pass.ground);
            frozen.done.addAll(pass.done);
            copy.passes.put(id, frozen);
        });
        return copy;
    }

    static CraterMatter get(ServerLevel level) {
        NuclearWorld world = level.getData(ua.zentix.incoming.registry.ModAttachments.NUCLEAR_WORLD);
        return world.ledgers.get(level, world, NAME, CraterMatter::new);
    }

    Pass pass(int event) { requireWritable(); return passes.computeIfAbsent(event, ignored -> new Pass()); }

    long retainedBytes() {
        long bytes = IO_BYTES + 1024L + settled.size() * 512L;
        for (Pass pass : passes.values()) bytes += 1024L + pass.materials.size() * 768L
                + pass.ground.size() * 96L + pass.done.size() * 96L;
        return bytes;
    }

    final class Pass {
        String soil = "";
        // Slots: removed, rim, exported, transformed; each has original solid volume and mass.
        final Map<BlockState, double[]> materials = new LinkedHashMap<>();
        final Map<BlockState, Long> available = new LinkedHashMap<>();
        final Long2IntOpenHashMap ground = new Long2IntOpenHashMap();
        final LongOpenHashSet done = new LongOpenHashSet();

        int ground(long column, int actual) {
            if (!ground.containsKey(column)) { ground.put(column, actual); setDirty(); }
            return ground.get(column);
        }

        void finish(long column) { done.add(column); setDirty(); }

        /** The separately saved job cursor has advanced past this chunk. Only the current chunk
         * can be replayed on clean restart, so earlier per-column checkpoints are redundant. */
        void chunkFinished() {
            ground.clear(); ground.trim();
            done.clear(); done.trim();
            setDirty();
        }

        void removed(BlockState state, boolean excavatedGround) {
            Blast.Props props = Blast.props(state);
            if (props.volume() <= 0 || props.fluid()) return;
            double[] row = materials.computeIfAbsent(state, ignored -> new double[8]);
            double volume = props.volume(), mass = volume * props.density();
            row[0] += volume; row[1] += mass;
            int destination = excavatedGround ? 4 : 6;
            row[destination] += volume; row[destination + 1] += mass;
            if (excavatedGround) available.merge(state, 1L, Long::sum);
            setDirty();
        }

        BlockState rim() {
            for (var entry : available.entrySet()) if (entry.getValue() > 0) return entry.getKey();
            return null; // A protected/empty bowl cannot manufacture an ejecta rim.
        }

        void deposited(BlockState state) {
            long count = available.getOrDefault(state, 0L);
            if (count <= 0) throw new IllegalStateException("Crater rim has no source material");
            double[] row = materials.get(state);
            // Freeze source mass/volume across data-pack changes. If a state acquired different
            // density during this pass, its remaining inventory supplies the proportional share.
            for (int k = 0; k < 2; k++) {
                double share = row[4 + k] / count;
                row[2 + k] += share;
                row[4 + k] -= share;
            }
            available.put(state, count - 1);
            setDirty();
        }
    }

    static CraterMatter load(CompoundTag tag, HolderLookup.Provider lookup) {
        return read(tag, lookup, new CraterMatter());
    }

    static CraterMatter read(CompoundTag tag, HolderLookup.Provider lookup, CraterMatter result) {
        ListTag passes = tag.getList("passes", Tag.TAG_COMPOUND);
        for (int i = 0; i < passes.size(); i++) {
            CompoundTag data = passes.getCompound(i);
            Pass pass = result.pass(data.getInt("event"));
            pass.soil = data.getString("soil");
            long[] columns = data.getLongArray("columns");
            int[] heights = data.getIntArray("ground");
            if (columns.length != heights.length) throw new IllegalStateException("Invalid crater column checkpoint");
            for (int k = 0; k < columns.length; k++) pass.ground.put(columns[k], heights[k]);
            for (long column : data.getLongArray("done")) pass.done.add(column);
            ListTag rows = data.getList("materials", Tag.TAG_COMPOUND);
            for (int k = 0; k < rows.size(); k++) {
                CompoundTag row = rows.getCompound(k);
                BlockState state = NbtUtils.readBlockState(lookup.lookupOrThrow(Registries.BLOCK), row.getCompound("state"));
                double[] balance = new double[8];
                for (int j = 0; j < balance.length; j++) balance[j] = row.getDouble("v" + j);
                validate(state, balance);
                pass.materials.put(state, balance);
                pass.available.put(state, row.getLong("available"));
            }
        }
        ListTag totals = tag.getList("settled", Tag.TAG_COMPOUND);
        for (int i = 0; i < totals.size(); i++) {
            CompoundTag row = totals.getCompound(i);
            BlockState state = NbtUtils.readBlockState(lookup.lookupOrThrow(Registries.BLOCK), row.getCompound("state"));
            double[] balance = new double[8];
            for (int j = 0; j < balance.length; j++) balance[j] = row.getDouble("v" + j);
            validate(state, balance);
            // Streaming import can already contain retired passes of the same material.
            // Saved aggregates add to those totals, regardless of compound field order.
            double[] total = result.settled.computeIfAbsent(state, ignored -> new double[8]);
            for (int j = 0; j < total.length; j++) total[j] += balance[j];
        }
        return result;
    }

    private static void validate(BlockState state, double[] balance) {
        for (int j = 0; j < 2; j++) {
            double accounted = balance[2 + j] + balance[4 + j] + balance[6 + j];
            if (!Double.isFinite(accounted) || Math.abs(accounted - balance[j]) > 1e-9 * Math.max(1, accounted))
                throw new IllegalStateException("Unbalanced crater material " + state);
        }
    }

    @Override public CompoundTag save(CompoundTag tag, HolderLookup.Provider lookup) {
        ListTag saved = new ListTag();
        for (var entry : passes.entrySet()) {
            CompoundTag data = new CompoundTag();
            data.putInt("event", entry.getKey());
            Pass pass = entry.getValue();
            data.putString("soil", pass.soil);
            data.putLongArray("columns", pass.ground.keySet().toLongArray());
            data.putIntArray("ground", pass.ground.values().toIntArray());
            data.putLongArray("done", pass.done.toLongArray());
            ListTag rows = new ListTag();
            for (var material : pass.materials.entrySet()) {
                CompoundTag row = new CompoundTag();
                row.put("state", NbtUtils.writeBlockState(material.getKey()));
                for (int j = 0; j < material.getValue().length; j++) row.putDouble("v" + j, material.getValue()[j]);
                row.putLong("available", pass.available.getOrDefault(material.getKey(), 0L));
                rows.add(row);
            }
            data.put("materials", rows); saved.add(data);
        }
        tag.put("passes", saved);
        ListTag totals = new ListTag();
        for (var entry : settled.entrySet()) {
            CompoundTag row = new CompoundTag();
            row.put("state", NbtUtils.writeBlockState(entry.getKey()));
            for (int j = 0; j < entry.getValue().length; j++) row.putDouble("v" + j, entry.getValue()[j]);
            totals.add(row);
        }
        tag.put("settled", totals);
        return tag;
    }

    @Override void write(LedgerWriter writer, HolderLookup.Provider lookup) throws java.io.IOException {
        writer.list("passes", passes.size());
        for (var entry : passes.entrySet()) {
            CompoundTag data = new CompoundTag();
            data.putInt("event", entry.getKey());
            Pass pass = entry.getValue();
            data.putString("soil", pass.soil);
            writer.fields(data);
            writer.longs("columns", pass.ground.keySet());
            writer.ints("ground", pass.ground.values());
            writer.longs("done", pass.done);
            writer.list("materials", pass.materials.size());
            for (var material : pass.materials.entrySet()) {
                CompoundTag row = new CompoundTag();
                row.put("state", NbtUtils.writeBlockState(material.getKey()));
                for (int j = 0; j < material.getValue().length; j++) row.putDouble("v" + j, material.getValue()[j]);
                row.putLong("available", pass.available.getOrDefault(material.getKey(), 0L));
                writer.row(row);
            }
            writer.end();
        }

        writer.list("settled", settled.size());
        for (var entry : settled.entrySet()) {
            CompoundTag row = new CompoundTag();
            row.put("state", NbtUtils.writeBlockState(entry.getKey()));
            for (int j = 0; j < entry.getValue().length; j++) row.putDouble("v" + j, entry.getValue()[j]);
            writer.row(row);
        }

    }
}
