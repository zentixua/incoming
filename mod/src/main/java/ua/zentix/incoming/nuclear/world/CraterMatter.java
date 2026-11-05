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
import net.minecraft.world.level.saveddata.SavedData;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Material boundary of the existing nuclear bowl/rim model. Excavated ground supplies the rim;
 * its remainder is exported ejecta (the existing ejecta/cloud presentation), not invented local
 * rubble. Other materials inside the vaporization bowl and glazed surfaces are thermally transformed.
 * Counts and original solid volume/mass are retained per source state, including restart progress.
 */
public final class CraterMatter extends SavedData {
    private static final String NAME = "incoming_crater_matter";
    private final Map<Integer, Pass> passes = new LinkedHashMap<>();

    static CraterMatter get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(new Factory<>(CraterMatter::new, CraterMatter::load, null), NAME);
    }

    Pass pass(int event) { return passes.computeIfAbsent(event, ignored -> new Pass()); }

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
        CraterMatter result = new CraterMatter();
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
                for (int j = 0; j < 2; j++) {
                    double accounted = balance[2 + j] + balance[4 + j] + balance[6 + j];
                    if (!Double.isFinite(accounted) || Math.abs(accounted - balance[j]) > 1e-9 * Math.max(1, accounted))
                        throw new IllegalStateException("Unbalanced crater material " + state);
                }
                pass.materials.put(state, balance);
                pass.available.put(state, row.getLong("available"));
            }
        }
        return result;
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
        return tag;
    }
}
