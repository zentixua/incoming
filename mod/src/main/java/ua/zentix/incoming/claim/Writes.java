package ua.zentix.incoming.claim;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import ua.zentix.incoming.nuclear.world.ColumnScar;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * A writer's portion of block changes, asked of claim protection in one question ({@link BlockGate#filter}) and then
 * written through {@link ColumnScar#replace} in the order added (server thread).
 */
public final class Writes {
    private final List<BlockPos> at = new ArrayList<>();
    private final List<BlockState> was = new ArrayList<>(), with = new ArrayList<>();
    private final int flags;

    public Writes(int flags) {
        this.flags = flags;
    }

    public void add(BlockPos pos, BlockState old, BlockState to) {
        at.add(pos.immutable());
        was.add(old);
        with.add(to);
    }

    /** Ask, write what may change; returns written count. */
    public int flush(ServerLevel level, BlockGate gate) {
        if (at.isEmpty()) return 0;
        List<BlockPos> allowed = new ArrayList<>(at);
        gate.filter(level, allowed);
        Set<BlockPos> may = allowed.size() == at.size() ? null : new HashSet<>(allowed);
        int n = 0;
        for (int k = 0; k < at.size(); k++) {
            if (may != null && !may.contains(at.get(k))) continue;
            ColumnScar.replace(level, at.get(k), was.get(k), with.get(k), flags);
            n++;
        }
        at.clear();
        was.clear();
        with.clear();
        return n;
    }
}
