package ua.zentix.incoming.grid;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;
import ua.zentix.incoming.nuclear.world.NuclearTickets;
import ua.zentix.incoming.util.SignalEdge;
import ua.zentix.incoming.util.Terrain;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Substation blocks follow the grid: knocked out — {@code powered=false} until power restoration starts in their area;
 * standing where a nuclear blast destroyed the grid ({@link PowerGrid#destroyed}) — for as long as that zone has no power.
 * {@code powered} is the block's redstone output ({@link SubstationBlock#getSignal}): a change notifies its neighbours
 * ({@link SignalEdge}).
 */
public final class Substations {
    private Substations() {}

    /** What the grid says about one substation: its state and, knocked out, when repairs start. */
    public enum State { WORKING, KNOCKED_OUT, DESTROYED }

    /**
     * @param repairIn world-clock ticks until power restoration starts in its area ({@link Outage#repairsAt}); {@link
     *                 Outage#NEVER} — no restoration is scheduled (a command outage), or the state is not
     *                 {@link State#KNOCKED_OUT}
     */
    public record Supply(State state, long repairIn) {}

    /**
     * The grid's state of the substation node at {@code pos} now, as {@link #works} decides it (the block's
     * {@code powered}); null — no substation node there. Reads only the grid's saved data.
     */
    @Nullable
    public static Supply supply(ServerLevel level, BlockPos pos) {
        PowerGrid grid = PowerGrid.get(level);
        Node node = grid.nodeAt(pos);
        if (node == null || !node.block()) return null;
        long now = Blackouts.now(level);
        Optional<Outage> down = grid.downOutage(node.id(), now);
        if (down.isPresent()) {
            long at = down.get().repairsAt();
            return new Supply(State.KNOCKED_OUT, at == Outage.NEVER ? Outage.NEVER : at - now);
        }
        if (grid.destroyed(pos.getX() >> 4, pos.getZ() >> 4, now)) return new Supply(State.DESTROYED, Outage.NEVER);
        return new Supply(State.WORKING, Outage.NEVER);
    }

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
