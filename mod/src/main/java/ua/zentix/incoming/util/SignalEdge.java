package ua.zentix.incoming.util;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * Redstone edges of our signal sources (SAM alarm, substation): the vanilla neighbour update a source sends when its
 * signal changes runs the circuit synchronously (dust recomputes and updates its own neighbours), and a read in a chunk
 * that is not ready would load it from the server tick ({@code AGENTS.md}). So a source tells its neighbours only while
 * every chunk the update can reach is ready; otherwise it keeps the news until one of its later checks finds them ready.
 */
public final class SignalEdge {
    private SignalEdge() {}

    /**
     * Blocks a neighbour update can reach synchronously: dust carries a signal 15 blocks, the last wire updates its
     * neighbours, and a neighbour that is itself dust or a piston reads the blocks around it.
     */
    public static final int REACH = 18;

    /** Every chunk within {@link #REACH} of {@code pos} is ready: telling the neighbours loads nothing. */
    public static boolean ready(Level level, BlockPos pos) {
        return Terrain.readyAround(level, Vec3.atCenterOf(pos), REACH);
    }

    /**
     * Tell the neighbours of {@code pos} about its block, now {@code state}. {@code changed}: its signal changed, which
     * {@code setBlock} with {@link Block#UPDATE_ALL} would announce (power update, then shape update, which observers
     * watch); otherwise only a power update, so circuits recheck the source without an observer seeing a change that
     * did not happen (a check after the chunk loads).
     */
    public static void notify(Level level, BlockPos pos, BlockState state, boolean changed) {
        level.updateNeighborsAt(pos, state.getBlock());
        if (changed) state.updateNeighbourShapes(level, pos, Block.UPDATE_CLIENTS);
    }
}
