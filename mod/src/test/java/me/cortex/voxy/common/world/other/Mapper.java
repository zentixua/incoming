package me.cortex.voxy.common.world.other;

import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.List;

/** Подделка класса Voxy 0.2.16 для юнит-тестов: только то, что читает мод (имена и сигнатуры). */
public class Mapper {
    /** Номер блока — место в списке; 0 — воздух. */
    public final List<BlockState> states = new ArrayList<>();

    public Mapper() {
        states.add(null);
    }

    /** Воксель с блоком {@code id} (биты 27–46, как у Voxy). */
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
