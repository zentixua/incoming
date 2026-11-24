package ua.zentix.incoming.claim;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

import java.util.ArrayList;
import java.util.List;

/** Claim protection of one cause for a writer: which of its positions it may change (server thread). */
@FunctionalInterface
public interface BlockGate {
    /** No protection asked (tests that dig without a cause). */
    BlockGate ALL = (level, positions) -> {};

    /** Remove from {@code positions} those the cause may not change. */
    void filter(ServerLevel level, List<BlockPos> positions);

    /** Whether the cause may change {@code pos}. */
    default boolean allows(ServerLevel level, BlockPos pos) {
        List<BlockPos> one = new ArrayList<>(1);
        one.add(pos);
        filter(level, one);
        return !one.isEmpty();
    }
}
