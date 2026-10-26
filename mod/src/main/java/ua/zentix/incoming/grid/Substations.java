package ua.zentix.incoming.grid;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import ua.zentix.incoming.nuclear.world.NuclearTickets;
import ua.zentix.incoming.util.Terrain;

/** Substation blocks follow the grid: knocked out — {@code powered=false} until power restoration starts in their area. */
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
            boolean powered = grid.downOutage(node.id(), now).isEmpty();
            if (state.getValue(SubstationBlock.POWERED) != powered) {
                level.setBlock(pos, state.setValue(SubstationBlock.POWERED, powered), ChunkLights.FLAGS);
            }
        }
    }
}
