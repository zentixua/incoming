package ua.zentix.incoming.grid;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import it.unimi.dsi.fastutil.ints.IntSet;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import ua.zentix.incoming.nuclear.world.NuclearTickets;
import ua.zentix.incoming.util.SignalEdge;
import ua.zentix.incoming.util.Terrain;

/**
 * Substation blocks follow the grid: knocked out — {@code powered=false} until power restoration starts in their area;
 * standing where a nuclear blast destroyed the grid ({@link PowerGrid#destroyed}) — for as long as that zone has no power.
 * {@code powered} is the block's redstone output ({@link SubstationBlock#getSignal}): a change notifies its neighbours
 * ({@link SignalEdge}).
 */
final class Substations {
    private Substations() {}

    static void sync(ServerLevel level, PowerGrid grid, long now) {
        for (Node node : grid.nodes()) {
            if (!node.block()) continue;
            BlockPos pos = node.pos();
            // chunk or neighbors not ready (Sable reads neighboring blocks) — sync when ready (once a second)
            if (!Terrain.ready(level, pos) || !NuclearTickets.aroundLoaded(level, pos)) continue;
            BlockState state = level.getBlockState(pos);
            if (!(state.getBlock() instanceof SubstationBlock)) continue;
            boolean powered = grid.downOutage(node.id(), now).isEmpty() && !grid.destroyed(pos.getX() >> 4, pos.getZ() >> 4, now);
            BlackoutWorld world = BlackoutWorld.get(level);
            IntSet signalled = world.signalled(), pending = world.signalPending();
            if (state.getValue(SubstationBlock.POWERED) != powered) {
                state = state.setValue(SubstationBlock.POWERED, powered);
                level.setBlock(pos, state, Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
                pending.add(node.id());
            }
            // Neighbours learn of a change when every chunk their circuit can reach is ready, else at a later sync; and
            // recheck the source once after each server start, in case a change was saved untold.
            boolean changed = pending.contains(node.id());
            if ((changed || !signalled.contains(node.id())) && SignalEdge.ready(level, pos)) {
                SignalEdge.notify(level, pos, state, changed);
                pending.remove(node.id());
                signalled.add(node.id());
            }
        }
    }
}
