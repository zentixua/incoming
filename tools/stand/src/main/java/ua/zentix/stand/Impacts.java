package ua.zentix.stand;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Hit counting without Minecraft ({@link ServerWatch} translates events; test {@code ImpactsTest}).
 * <p>A hit requires projectile killed/removed near salvo target ({@link #leave}) and an explosion from that
 * same projectile, specifically its warhead ({@link #blast}: explosion-source UUID and warhead damage type).
 * Its crash explosion (before arming, small vanilla blast without warhead) is not a hit: otherwise the bench
 * would measure crashes. Either order: Incoming impact work uses the tick budget and explosion may start
 * ticks after removal or before it. Match UUID only: a nearby other projectile blast confirms nothing,
 * secondary detonations count once, sourceless explosions confirm nothing. Removal without explosion means
 * off-world flight ({@code VirtualFlights.park} removes entities too); wait until the same UUID rejoins
 * ({@link #join} cancels waiting) or explodes. No wait deadline: detonation under load can be arbitrarily
 * later; salvo completion owns its timeout. Server thread only except {@link #waiting}.
 */
final class Impacts {
    /** Salvo target: removal within horizontal radius {@code r} of (x, z) belongs to this salvo. */
    record Target(String salvo, double x, double z, double r) {}

    /** Removal near target: projectile, salvo, position, time (nanoTime), the impact instant. */
    record Leave(UUID uuid, String salvo, double x, double y, double z, long nanos) {}

    /** Confirmed hit: removal and confirmation ({@code blast-after} means explosion after removal, {@code blast-before} before). */
    record Hit(Leave leave, String how) {}

    private final List<Target> targets = new ArrayList<>();
    private final Map<UUID, Leave> pending = new LinkedHashMap<>();
    /** Projectiles whose explosion started but target removal has not occurred. */
    private final Set<UUID> blasted = new HashSet<>();
    private final Set<UUID> counted = new HashSet<>();
    /** Also read by client thread ({@link #waiting}). */
    private final Map<String, Integer> waiting = new ConcurrentHashMap<>();

    /** Salvo awaits hits within r of (x, z); later registered target claims a removal before older targets (same sites). */
    void target(String salvo, double x, double z, double r) {
        targets.addFirst(new Target(salvo, x, z, r));
    }

    /** Projectile joined/rejoined from off-world flight: waiting removal was not a hit; return whether it existed. */
    boolean join(UUID uuid) {
        Leave l = pending.remove(uuid);
        if (l == null) return false;
        waiting.merge(l.salvo, -1, Integer::sum);
        return true;
    }

    /**
     * Projectile killed/removed at (x, y, z). Return hit if explosion already occurred, otherwise null;
     * target removal without explosion waits ({@link #waiting}), removal far from targets is forgotten.
     */
    Hit leave(UUID uuid, double x, double y, double z, long nanos) {
        if (counted.contains(uuid)) return null;
        for (Target t : targets) {
            if (Math.hypot(x - t.x, z - t.z) > t.r) continue;
            Leave l = new Leave(uuid, t.salvo, x, y, z, nanos);
            if (blasted.remove(uuid)) return count(l, "blast-before");
            Leave old = pending.put(uuid, l);
            if (old != null) waiting.merge(old.salvo, -1, Integer::sum);
            waiting.merge(l.salvo, 1, Integer::sum);
            return null;
        }
        return null;
    }

    /**
     * Explosion of projectile {@code uuid} started (null means no projectile); {@code warhead} distinguishes
     * warhead detonation from crash. Ignore absent projectile/non-warhead blast; return hit or null.
     */
    Hit blast(UUID uuid, boolean warhead) {
        if (uuid == null || !warhead || counted.contains(uuid)) return null;
        Leave l = pending.remove(uuid);
        if (l == null) {
            blasted.add(uuid);
            return null;
        }
        waiting.merge(l.salvo, -1, Integer::sum);
        return count(l, "blast-after");
    }

    private Hit count(Leave l, String how) {
        counted.add(l.uuid);
        return new Hit(l, how);
    }

    /** Salvo target removals awaiting explosion or rejoin. */
    int waiting(String salvo) {
        return waiting.getOrDefault(salvo, 0);
    }
}
