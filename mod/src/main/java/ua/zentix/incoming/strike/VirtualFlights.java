package ua.zentix.incoming.strike;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.phys.Vec3;
import ua.zentix.incoming.Incoming;
import ua.zentix.incoming.entity.StrikeProjectile;
import ua.zentix.incoming.util.Terrain;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * Projectiles flying outside the loaded world: live entity objects not added to the world. Each tick calls
 * {@link StrikeProjectile#virtualTick}; on entering ticking chunks, added back with the same UUID
 * (clients see a new entity; camera and HUD recognize the UUID). Stored in the world: flight survives
 * server restart.
 */
public final class VirtualFlights extends SavedData {
    private static final String NAME = Incoming.MOD_ID + "_virtual_flights";
    private static final Factory<VirtualFlights> FACTORY = new Factory<>(VirtualFlights::new, VirtualFlights::load, null);

    private final List<StrikeProjectile> flights = new ArrayList<>();
    /** Read from disk but not yet instantiated (entity created on first world tick). */
    private final List<CompoundTag> pending = new ArrayList<>();

    public static VirtualFlights get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(FACTORY, NAME);
    }

    /**
     * Projectile leaves ticking chunks: copy takes the target area and lane of the same mission (UUID), then the old
     * entity leaves the world, releasing only its own chunks. Copy failure ends the mission and releases everything.
     *
     * @return outside-world flying copy, or null if saving failed (projectile is simply removed)
     */
    public static StrikeProjectile park(ServerLevel level, StrikeProjectile p) {
        StrikeProjectile copy = null;
        try {
            CompoundTag tag = new CompoundTag();
            boolean saved = p.save(tag);
            copy = saved ? create(level, tag) : null;
            if (copy != null) {
                p.handOverTargetArea(copy);
                get(level).add(copy);
            } else Incoming.LOG.warn("Projectile {} {} at {} could not switch to off-world flight ({}), removed", p.getType().getDescriptionId(), p.getUUID(),
                    p.blockPosition(), saved ? "copy not created" : "save failed");
            return copy;
        } catch (RuntimeException e) {
            if (copy != null) copy.discard();
            Incoming.LOG.error("Projectile {} {} at {} failed to switch to off-world flight and was removed",
                    p.getType().getDescriptionId(), p.getUUID(), p.blockPosition(), e);
            return null;
        } finally {
            p.discard();
        }
    }

    /** Begin flight outside the world immediately (distant approach: B-2, launch without a nearby launcher). */
    public static void launch(ServerLevel level, StrikeProjectile p) {
        get(level).add(p);
    }

    private void add(StrikeProjectile p) {
        p.markVirtual();
        flights.add(p);
        setDirty();
    }

    public List<StrikeProjectile> flights() {
        return flights;
    }

    /** Number of outside-world flights (including disk records not yet instantiated). */
    public int size() {
        return flights.size() + pending.size();
    }

    /**
     * Cancel: remove projectiles matching {@code which} without exploding.
     *
     * @return UUIDs of removed projectiles
     */
    public List<UUID> clear(ServerLevel level, Predicate<StrikeProjectile> which) {
        createPending(level);
        List<UUID> removed = new ArrayList<>();
        for (Iterator<StrikeProjectile> it = flights.iterator(); it.hasNext(); ) {
            StrikeProjectile p = it.next();
            if (!which.test(p)) continue;
            p.discard();
            it.remove();
            removed.add(p.getUUID());
        }
        if (!removed.isEmpty()) setDirty();
        return removed;
    }

    /** Instantiate entities for flights read from disk. */
    private void createPending(ServerLevel level) {
        if (pending.isEmpty()) return;
        for (CompoundTag t : pending) {
            StrikeProjectile p = create(level, t);
            if (p != null) {
                p.markVirtual();
                flights.add(p);
            }
        }
        pending.clear();
    }

    void tick(ServerLevel level) {
        createPending(level);
        if (flights.isEmpty()) return;
        // Snapshot: a projectile returning to the world may leave again (and be added here) in the same tick.
        List<StrikeProjectile> now = new ArrayList<>(flights);
        flights.clear();
        for (StrikeProjectile p : now) {
            p.virtualTick(level);
            if (p.isRemoved()) continue;
            if (p.canMaterialize(level)) {
                try {
                    p.materialize(level);
                    if (level.addFreshEntity(p)) continue;
                    Incoming.LOG.warn("Projectile {} {} could not return to the world at {}", p.getType().getDescriptionId(), p.getUUID(), p.blockPosition());
                } catch (RuntimeException e) {
                    // World reentry failed (including join handler — protection mod): remove as a failed entrant;
                    // other flights continue this tick, otherwise the error would escape the world tick and crash the server.
                    Incoming.LOG.error("Projectile {} {} failed to return to the world at {} with an exception and was removed",
                            p.getType().getDescriptionId(), p.getUUID(), p.blockPosition(), e);
                }
                p.discard(); // Release target-area and own-chunk tickets: absent from world, it will not release them itself.
                continue;
            }
            flights.add(p);
        }
        setDirty();
    }

    /**
     * Reentry allowed when all chunk columns along the path are live (ready and entity-ticking) for
     * {@code max(16, 3 steps)} blocks ahead horizontally, not just own and end columns. First world step
     * ({@code leavesTickingChunks}) checks its destination; cutting a corner can enter a chunk present in
     * neither — projectile would reenter and immediately leave each tick, resending entity spawn
     * to clients. Target area stays with the same mission across flight-form changes.
     */
    public static boolean clearAhead(Vec3 pos, Vec3 forward, double speed, Terrain.ChunkReady live) {
        Vec3 ahead = pos.add(forward.multiply(1, 0, 1).scale(Math.max(16, speed * 3)));
        return Terrain.readyFraction(pos.x, pos.z, ahead.x, ahead.z, live) >= 1;
    }

    private static StrikeProjectile create(ServerLevel level, CompoundTag tag) {
        return EntityType.create(tag, level).filter(e -> e instanceof StrikeProjectile).map(e -> (StrikeProjectile) e).orElse(null);
    }

    private static VirtualFlights load(CompoundTag tag, HolderLookup.Provider registries) {
        VirtualFlights v = new VirtualFlights();
        for (Tag t : tag.getList("flights", Tag.TAG_COMPOUND)) v.pending.add((CompoundTag) t);
        return v;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        ListTag list = new ListTag();
        for (StrikeProjectile p : flights) {
            CompoundTag t = new CompoundTag();
            if (p.save(t)) list.add(t);
        }
        list.addAll(pending);
        tag.put("flights", list);
        return tag;
    }
}
