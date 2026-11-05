package ua.zentix.incoming.nuclear.world;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.IdentityHashMap;

/** One named-state palette per durable page; no repeated block-state compounds per removed voxel. */
final class RuinPalette {
    private final ArrayList<BlockState> states = new ArrayList<>();
    private final IdentityHashMap<BlockState, Integer> ids = new IdentityHashMap<>();

    long bytes() { return 512L + 96L * states.size(); }

    RuinPalette snapshot() {
        RuinPalette copy = new RuinPalette();
        copy.states.addAll(states);
        copy.ids.putAll(ids);
        return copy;
    }

    int index(BlockState state) {
        Integer found = ids.get(state);
        if (found != null) return found;
        int id = states.size();
        states.add(state);
        ids.put(state, id);
        return id;
    }

    int[] indices(BlockState[] values) {
        int[] out = new int[values.length];
        for (int i = 0; i < out.length; i++) out[i] = index(values[i]);
        return out;
    }

    BlockState state(int id) {
        if (id < 0 || id >= states.size()) throw new IllegalArgumentException("Invalid ruin palette index " + id);
        return states.get(id);
    }

    BlockState[] states(int[] values) {
        BlockState[] out = new BlockState[values.length];
        for (int i = 0; i < out.length; i++) out[i] = state(values[i]);
        return out;
    }

    ListTag save() {
        ListTag out = new ListTag();
        for (BlockState state : states) out.add(NbtUtils.writeBlockState(state));
        return out;
    }

    static RuinPalette load(ListTag tag) {
        RuinPalette palette = new RuinPalette();
        for (var value : tag) {
            // Missing blocks must invalidate a prepared page, never silently become air.
            BlockState state = BlockState.CODEC.parse(NbtOps.INSTANCE, (CompoundTag) value).getOrThrow();
            palette.states.add(state);
            palette.ids.putIfAbsent(state, palette.states.size() - 1);
        }
        return palette;
    }
}
