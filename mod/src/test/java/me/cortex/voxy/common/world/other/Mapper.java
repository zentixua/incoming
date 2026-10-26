package me.cortex.voxy.common.world.other;

import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.List;

/** Fake class Voxy 0.2.16 for unit tests: only what the mod reads (names and signatures). */
public class Mapper {
    /** Block number - place in the list; 0 - air. */
    public final List<BlockState> states = new ArrayList<>();

    public Mapper() {
        states.add(null);
    }

    /** Voxel with block {@code id} (bits 27–46, same as Voxy). */
    public static long voxel(int id) {
        return (long) id << 27;
    }

    public static int getBlockId(long voxel) {
        return (int) (voxel >>> 27 & 0xFFFFF);
    }

    public int id(BlockState state) {
        int i = states.indexOf(state);
        if (i > 0) return i;
        states.add(state);
        return states.size() - 1;
    }

    public BlockState getBlockStateFromBlockId(int id) {
        return states.get(id);
    }
}
