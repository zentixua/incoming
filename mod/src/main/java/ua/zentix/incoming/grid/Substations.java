package ua.zentix.incoming.grid;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import ua.zentix.incoming.nuclear.world.NuclearTickets;
import ua.zentix.incoming.util.SignalEdge;
import ua.zentix.incoming.util.Terrain;

import java.util.ArrayList;
import java.util.List;

/**
 * Substation blocks follow the grid: knocked out — {@code powered=false} until power restoration starts in their area;
 * standing where a nuclear blast destroyed the grid ({@link PowerGrid#destroyed}) — for as long as that zone has no power.
 * {@code powered} is the block's redstone output ({@link SubstationBlock#getSignal}): a change notifies its neighbours
 * ({@link SignalEdge}).
 */
final class Substations {
    private Substations() {}

    /** The node's substation works at {@code now}: no outage of its own before repairs, not in a destroyed zone. */
    static boolean works(ServerLevel level, PowerGrid grid, Node node, long now) {
        BlockPos pos = node.pos();
        return grid.downOutage(node.id(), now).isEmpty() && !grid.destroyed(pos.getX() >> 4, pos.getZ() >> 4, now);
    }

    static void sync(ServerLevel level, PowerGrid grid, long now) {
        // a copy: the notices below run circuits, which may place or break substations
        List<Node> nodes = new ArrayList<>();
        grid.nodes().forEach(nodes::add);
        for (Node node : nodes) {
            if (!node.block()) continue;
            BlockPos pos = node.pos();
            // chunk or neighbors not ready (Sable reads neighboring blocks) — sync when ready (once a second)
            if (!Terrain.ready(level, pos) || !NuclearTickets.aroundLoaded(level, pos)) continue;
            BlockState state = level.getBlockState(pos);
            if (!(state.getBlock() instanceof SubstationBlock)) continue;
            boolean powered = works(level, grid, node, now);
            if (state.getValue(SubstationBlock.POWERED) != powered) {
                state = state.setValue(SubstationBlock.POWERED, powered);
                level.setBlock(pos, state, Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
                // neighbours learn of it when every chunk their circuit can reach is ready
                SignalEdge.changed(level, pos, state);
            }
            // and recheck the source once after each server start, in case a change was saved untold
            if (BlackoutWorld.get(level).signalled().add(node.id())) SignalEdge.recheck(level, pos, state);
        }
    }
}
