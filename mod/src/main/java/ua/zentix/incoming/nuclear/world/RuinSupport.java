package ua.zentix.incoming.nuclear.world;

import it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import ua.zentix.incoming.nuclear.Detonation;

import java.util.List;

/**
 * Continuation of support reconciliation beyond a planner window. Starts only beside a support
 * actually removed by this strike. Unknown chunk faces are pending edges, never foundations.
 * A connected component reaches a protected block/world floor or is completely enumerated before
 * release. Work is sliced; neither a block count nor a chunk seam decides structural support.
 */
final class RuinSupport {
    private static final Direction[] ORDER = {Direction.UP, Direction.NORTH, Direction.SOUTH,
            Direction.WEST, Direction.EAST, Direction.DOWN};
    private final LongArrayList frontier = new LongArrayList();
    private final LongOpenHashSet seen = new LongOpenHashSet();
    private final Long2ObjectLinkedOpenHashMap<BlockState> component = new Long2ObjectLinkedOpenHashMap<>();
    private final long seed;
    private boolean validating, releasing, original = true, invalidated;
    private long[] cells;
    private int cursor;
    private BlockPos ownRemoval;
    boolean waiting;

    RuinSupport(long seed) {
        this.seed = seed;
        frontier.add(seed);
    }

    /** True when settled as supported, absent, or fully released. */
    boolean step(ServerLevel level, RuinMatter ledger, Detonation d) {
        waiting = false;
        if (invalidated) restart();
        RuinScorch scorch = null;
        long scorchChunk = Long.MIN_VALUE;
        for (int work = 0; work < 16; work++) {
            if (invalidated) { restart(); return false; }
            if (releasing) {
                if (cursor == cells.length) {
                    ledger.resolved(component.keySet());
                    return true;
                }
                BlockPos at = BlockPos.of(cells[cursor]);
                if (!ledger.ready(level, at, true)) { waiting = true; return false; }
                BlockState state = level.getBlockState(at);
                if (state != component.get(cells[cursor])) {
                    restart();
                    return false;
                }
                // Silent local writes are checked as well as loader notifications. Never release a
                // cell through a newly added external contact.
                if (newContact(level, at)) { restart(); return false; }
                if (!ledger.readyToReplace(level, at, state, Blocks.AIR.defaultBlockState())) {
                    waiting = true; return false;
                }
                long chunkKey = net.minecraft.world.level.ChunkPos.asLong(at.getX() >> 4, at.getZ() >> 4);
                if (scorch == null || scorchChunk != chunkKey) {
                    scorch = new RuinScorch(level.getChunkSource().getChunkNow(at.getX() >> 4, at.getZ() >> 4));
                    scorchChunk = chunkKey;
                }
                RuinMatter.Piece piece = RuinMatter.capture(level, at, state, d, scorch);
                if (piece != null) {
                    ownRemoval = at;
                    try { ColumnScar.replace(level, at, state, Blocks.AIR.defaultBlockState()); }
                    finally { ownRemoval = null; }
                    if (level.getBlockState(at).isAir()) {
                        ledger.release(List.of(piece));
                        ledger.support(level, List.of(piece), d);
                    }
                }
                cursor++;
                continue;
            }
            if (validating) {
                if (cursor == cells.length) {
                    releasing = true;
                    cursor = 0;
                    continue;
                }
                BlockPos at = BlockPos.of(cells[cursor]);
                if (!ledger.ready(level, at, true)) { waiting = true; return false; }
                if (level.getBlockState(at) != component.get(cells[cursor])) {
                    restart();
                    return false;
                }
                // A newly added connection invalidates the closed component, even if none of its
                // original blocks changed. Reconcile it instead of deleting an edited structure.
                for (Direction direction : ORDER) {
                    BlockPos next = at.relative(direction);
                    Blast.Props own = Blast.props(component.get(cells[cursor]));
                    Blast.Props neighbour = Blast.props(level.getBlockState(next));
                    if (!component.containsKey(next.asLong()) && neighbour.bearing() && own.touches(direction, neighbour)) {
                        restart();
                        return false;
                    }
                }
                cursor++;
                continue;
            }
            if (frontier.isEmpty()) {
                // The original connected component had no anchor: the strike did not create its
                // lack of support. Keep it, including ordinary End islands and floating builds.
                if (original) { ledger.resolved(seen); return true; }
                if (component.isEmpty()) return true;
                cells = component.keySet().toLongArray();
                validating = true;
                cursor = 0;
                continue;
            }
            long key = frontier.getLong(frontier.size() - 1);
            BlockPos at = BlockPos.of(key);
            if (!ledger.ready(level, at, false)) { waiting = true; return false; }
            if (seen.contains(key)) {
                frontier.removeLong(frontier.size() - 1);
                continue;
            }
            if (at.getY() < level.getMinBuildHeight() || at.getY() >= level.getMaxBuildHeight()) {
                frontier.removeLong(frontier.size() - 1);
                continue;
            }
            BlockState state = original ? ledger.original(level, at, d.id()) : level.getBlockState(at);
            Blast.Props props = Blast.props(state);
            if (props.bearing() && !ledger.ready(level, at, true)) { waiting = true; return false; }
            frontier.removeLong(frontier.size() - 1);
            seen.add(key);
            if (!props.bearing()) continue;
            if (props.fixed() || at.getY() == level.getMinBuildHeight()) {
                if (original) {
                    original = false;
                    frontier.clear(); seen.clear(); component.clear();
                    frontier.add(seed);
                    continue;
                }
                ledger.resolved(seen);
                return true;
            }
            if (!original) component.put(key, state);
            // Depth first and downward first: a real foundation is reached without flooding all
            // connected bedrock. Roof components still follow their complete boundary graph.
            for (Direction direction : ORDER) {
                BlockPos next = at.relative(direction);
                Blast.Props neighbour = Blast.props(original ? ledger.original(level, next, d.id()) : level.getBlockState(next));
                if (!seen.contains(next.asLong()) && neighbour.bearing() && props.touches(direction, neighbour))
                    frontier.add(next.asLong());
            }
        }
        return false;
    }

    /** Loader notification includes the changed cell, so a repair touching an already validated
     * part invalidates the whole release certificate before the next bounded batch. */
    void changed(BlockPos at) {
        if (at.equals(ownRemoval)) return;
        if (component.containsKey(at.asLong())) invalidated = true;
        for (Direction direction : ORDER) if (component.containsKey(at.relative(direction).asLong())) invalidated = true;
    }

    private boolean newContact(ServerLevel level, BlockPos at) {
        Blast.Props own = Blast.props(component.get(at.asLong()));
        for (Direction direction : ORDER) {
            BlockPos next = at.relative(direction);
            if (!component.containsKey(next.asLong()) && own.touches(direction, Blast.props(level.getBlockState(next)))) return true;
        }
        return false;
    }

    // Package diagnostics also let tests insert edits at the actual resumable phase boundary.
    boolean validating() { return validating && !releasing && cursor > 0; }
    boolean releasing() { return releasing; }

    private void restart() {
        frontier.clear(); seen.clear(); component.clear();
        frontier.add(seed);
        validating = releasing = invalidated = false;
        original = true;
        cells = null;
        cursor = 0;
    }
}
