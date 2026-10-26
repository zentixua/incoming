package ua.zentix.incoming.entity.flight;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.Vec3;
import ua.zentix.incoming.strike.ChunkTickets;
import ua.zentix.incoming.util.Terrain;

import java.util.UUID;

/**
 * Chunks held by the projectile's own ticket ({@link ChunkTickets}): its current chunk and, while someone can see
 * it, the chunk ahead along heading. Only already ready chunks ({@link Terrain#ready}): no new chunks are
 * generated in flight, and {@code hasChunk} is true even for a chunk still loading. Not saved: after loading,
 * reacquire tickets on the first tick.
 * <p>
 * The ahead chunk keeps flight in the world: its ticket loads the surrounding square, so the next chunk ahead is
 * ready. Holding it always would pull ticking chunks across the map, and map-wide salvos would hold tens of
 * thousands (37 thousand chunks, TPS 7–10). Without it, the projectile reaches its chunk edge and enters virtual
 * flight ({@code StrikeProjectile.leavesTickingChunks}); players receive its path via {@code FarFlights} packets.
 */
public final class ChunkHold {
    private final LongSet held = new LongOpenHashSet();

    /** Holds nothing (projectile just spawned in the world or returned from an unloaded chunk). */
    public boolean isEmpty() {
        return held.isEmpty();
    }

    /**
     * Hold the chunk at {@code pos} and, if {@code lookAhead}, the chunk 3 steps ({@code speed}), at least 16
     * blocks, ahead along {@code dir}.
     */
    public void update(ServerLevel level, UUID owner, Vec3 pos, Vec3 dir, double speed, boolean lookAhead) {
        long here = ChunkPos.asLong(BlockPos.containing(pos));
        long ahead = lookAhead ? ChunkPos.asLong(BlockPos.containing(pos.add(dir.scale(Math.max(16, speed * 3))))) : here;
        if (held.contains(here) && held.contains(ahead) && held.size() == (here == ahead ? 1 : 2)) return;
        LongSet want = new LongOpenHashSet();
        for (long c : new long[]{here, ahead}) {
            if (Terrain.ready(level, ChunkPos.getX(c), ChunkPos.getZ(c))) want.add(c);
        }
        for (long c : held.toLongArray()) {
            if (!want.contains(c)) {
                ChunkTickets.hold(level, owner, c, false);
                held.remove(c);
            }
        }
        for (long c : want) {
            if (held.add(c)) ChunkTickets.hold(level, owner, c, true);
        }
    }

    /** Release everything. */
    public void release(ServerLevel level, UUID owner) {
        for (long c : held) ChunkTickets.hold(level, owner, c, false);
        held.clear();
    }
}
