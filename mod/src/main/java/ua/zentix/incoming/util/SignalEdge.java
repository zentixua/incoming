package ua.zentix.incoming.util;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import it.unimi.dsi.fastutil.longs.Long2BooleanMap;
import it.unimi.dsi.fastutil.longs.Long2BooleanOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.event.tick.LevelTickEvent;
import ua.zentix.incoming.registry.ModAttachments;

import java.util.Iterator;
import java.util.List;

/**
 * Redstone edges of our signal sources (SAM alarm, substation): the vanilla neighbour update a source sends when its
 * signal changes runs the circuit synchronously (dust recomputes and updates its own neighbours, a piston works out what
 * it would push), and a read in a chunk that is not ready would load it from the server tick ({@code AGENTS.md}). So a
 * source changes its block state without neighbour flags and hands the notice here: it goes out at once when every
 * chunk within {@link #REACH} is ready, otherwise it waits in the world's queue (saved with the world, so it outlives
 * chunk unloads, the source's removal and a restart) and goes out on the first tick that finds them ready.
 */
public final class SignalEdge {
    private SignalEdge() {}

    /**
     * Blocks a neighbour update can reach synchronously: dust carries a signal 15 blocks, the block beside the last
     * wire (16) may be a piston, which resolves its push up to 13 blocks further (29), and the blocks there read their
     * neighbours.
     */
    public static final int REACH = 32;

    /** Notices waiting for unready chunks: block position → also a shape update (a real change, observers see it). */
    public static final class Queue {
        /** Saved as the positions of each kind ({@code ModAttachments.SIGNAL_EDGES}). */
        public static final Codec<Queue> CODEC = RecordCodecBuilder.create(i -> i.group(
                Codec.LONG.listOf().fieldOf("changed").forGetter(q -> q.keys(true)),
                Codec.LONG.listOf().fieldOf("recheck").forGetter(q -> q.keys(false))
        ).apply(i, Queue::new));

        final Long2BooleanOpenHashMap pending = new Long2BooleanOpenHashMap();

        public Queue() {}

        private Queue(List<Long> changed, List<Long> recheck) {
            recheck.forEach(p -> pending.put((long) p, false));
            changed.forEach(p -> pending.put((long) p, true));
        }

        private List<Long> keys(boolean changed) {
            LongArrayList out = new LongArrayList();
            for (Long2BooleanMap.Entry e : pending.long2BooleanEntrySet()) if (e.getBooleanValue() == changed) out.add(e.getLongKey());
            return out;
        }

        public boolean isEmpty() {
            return pending.isEmpty();
        }
    }

    /** Every chunk within {@link #REACH} of {@code pos} is ready: telling the neighbours loads nothing. */
    public static boolean ready(Level level, BlockPos pos) {
        return Terrain.readyAround(level, Vec3.atCenterOf(pos), REACH);
    }

    /** The source at {@code pos}, now {@code state}, changed its signal: power and shape update. */
    public static void changed(ServerLevel level, BlockPos pos, BlockState state) {
        tell(level, pos, state, true);
    }

    /**
     * Have the neighbours of {@code pos} recheck its signal, with no change to show an observer: after a load or a
     * server start (a change may have been saved untold), or a removal by a write that skips neighbour updates.
     */
    public static void recheck(ServerLevel level, BlockPos pos, BlockState state) {
        tell(level, pos, state, false);
    }

    /**
     * The source at {@code pos} was removed and {@code now} stands there. Its neighbours recheck it if it was on, or if a
     * notice of it still waits; a waiting shape update is dropped: what it announced is gone, and an observer placed
     * since would see a change that never happened to the block it watches.
     */
    public static void removed(ServerLevel level, BlockPos pos, BlockState now, boolean wasOn) {
        long key = pos.asLong();
        boolean waited = level.hasData(ModAttachments.SIGNAL_EDGES) && level.getData(ModAttachments.SIGNAL_EDGES).pending.containsKey(key);
        if (!wasOn && !waited) return;
        if (waited) level.getData(ModAttachments.SIGNAL_EDGES).pending.remove(key);
        if (ready(level, pos)) {
            notify(level, pos, now, false);
        } else {
            level.getData(ModAttachments.SIGNAL_EDGES).pending.put(key, false);
        }
    }

    private static void tell(ServerLevel level, BlockPos pos, BlockState state, boolean changed) {
        long key = pos.asLong();
        Long2BooleanOpenHashMap pending = level.hasData(ModAttachments.SIGNAL_EDGES) ? level.getData(ModAttachments.SIGNAL_EDGES).pending : null;
        if (pending != null && pending.containsKey(key)) changed |= pending.remove(key);
        if (ready(level, pos)) {
            notify(level, pos, state, changed);
        } else {
            level.getData(ModAttachments.SIGNAL_EDGES).pending.put(key, changed);
        }
    }

    /** Waiting notices go out once their chunks are ready ({@code LevelTickEvent.Post}). */
    public static void onLevelTick(LevelTickEvent.Post event) {
        if (!(event.getLevel() instanceof ServerLevel level) || !level.hasData(ModAttachments.SIGNAL_EDGES)) return;
        Long2BooleanOpenHashMap pending = level.getData(ModAttachments.SIGNAL_EDGES).pending;
        if (pending.isEmpty()) return;
        // take the ready ones out first: a notice runs circuits that may hand this queue a new one
        Long2BooleanOpenHashMap due = null;
        BlockPos.MutableBlockPos at = new BlockPos.MutableBlockPos();
        for (Iterator<Long2BooleanMap.Entry> it = pending.long2BooleanEntrySet().iterator(); it.hasNext(); ) {
            Long2BooleanMap.Entry e = it.next();
            if (!ready(level, at.set(e.getLongKey()))) continue;
            if (due == null) due = new Long2BooleanOpenHashMap();
            due.put(e.getLongKey(), e.getBooleanValue());
            it.remove();
        }
        if (due == null) return;
        for (Long2BooleanMap.Entry e : due.long2BooleanEntrySet()) {
            BlockPos pos = BlockPos.of(e.getLongKey());
            notify(level, pos, level.getBlockState(pos), e.getBooleanValue());
        }
    }

    /** A notice for {@code pos} waits for unready chunks (tests). */
    public static boolean waiting(ServerLevel level, BlockPos pos) {
        return level.hasData(ModAttachments.SIGNAL_EDGES) && level.getData(ModAttachments.SIGNAL_EDGES).pending.containsKey(pos.asLong());
    }

    /**
     * What {@code setBlock} with {@link Block#UPDATE_ALL} sends: a power update and, for a real change, the shape update
     * observers watch.
     */
    private static void notify(Level level, BlockPos pos, BlockState state, boolean changed) {
        level.updateNeighborsAt(pos, state.getBlock());
        if (changed) state.updateNeighbourShapes(level, pos, Block.UPDATE_CLIENTS);
    }
}
