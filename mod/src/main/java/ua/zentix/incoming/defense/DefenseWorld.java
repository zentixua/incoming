package ua.zentix.incoming.defense;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.event.tick.LevelTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import org.jetbrains.annotations.Nullable;
import ua.zentix.incoming.Incoming;
import ua.zentix.incoming.IncomingConfig;
import ua.zentix.incoming.entity.InterceptorEntity;
import ua.zentix.incoming.entity.StrikeProjectile;
import ua.zentix.incoming.net.S2C;
import ua.zentix.incoming.registry.ModAttachments;
import ua.zentix.incoming.strike.StrikeWorld;
import ua.zentix.incoming.strike.VirtualFlights;
import ua.zentix.incoming.util.Terrain;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * World air defense: interceptor missiles in flight ({@link Interceptor}), their targets, targets already
 * announced in an alert ({@link AirAlert}), and a per-tick snapshot of world projectiles for radars. Separate for
 * each world, not saved ({@link ModAttachments#DEFENSE_WORLD}): a missile flies for seconds and does not survive
 * a server restart.
 * <p>
 * Missiles fly here at the end of the world tick, using the same code inside and outside the world; in ticking
 * chunks a missile has a visual entity ({@link InterceptorEntity}) placed at its flight position every tick;
 * elsewhere it has none.
 */
public final class DefenseWorld {
    private final List<Interceptor> flights = new ArrayList<>();
    /** Target → missile pursuing it. */
    private final Map<UUID, Interceptor> engaged = new HashMap<>();
    /** Side → targets announced to it and when radar last saw them. */
    private final Map<String, Map<UUID, Long>> alerted = new HashMap<>();
    /** Side → when its last alert sounded. */
    private final Map<String, Long> alertSound = new HashMap<>();
    private long snapshotTick = Long.MIN_VALUE;
    private List<StrikeProjectile> snapshot = List.of();

    /** For {@link ModAttachments#DEFENSE_WORLD}. */
    public DefenseWorld() {}

    public static DefenseWorld get(ServerLevel level) {
        return level.getData(ModAttachments.DEFENSE_WORLD);
    }

    public static void onLevelTick(LevelTickEvent.Post event) {
        if (!(event.getLevel() instanceof ServerLevel level) || !level.hasData(ModAttachments.DEFENSE_WORLD)) return;
        DefenseWorld world = get(level);
        // This tick's radars have already swept the sky (block entities tick earlier): release the projectile snapshot.
        world.snapshot = List.of();
        world.snapshotTick = Long.MIN_VALUE;
        if (level.getGameTime() % AirAlert.FORGET == 0) world.forget(level.getGameTime());
        // “/tick freeze” stops entities: missiles stop along with projectiles.
        if (!level.tickRateManager().runsNormally()) return;
        world.tick(level);
    }

    /** Forget alerts for targets unseen by the side's radars for a long time (the side's SAM may be gone too). */
    private void forget(long now) {
        for (Map<UUID, Long> seen : alerted.values()) seen.values().removeIf(t -> now - t > AirAlert.FORGET);
        alerted.values().removeIf(Map::isEmpty);
        alertSound.values().removeIf(t -> now - t >= AirAlert.SOUND_GAP);
    }

    /** World projectiles for this tick (inside and outside the world): one list for all radars in the tick. */
    List<StrikeProjectile> projectiles(ServerLevel level) {
        long now = level.getGameTime();
        if (now != snapshotTick) {
            snapshot = StrikeWorld.projectiles(level);
            snapshotTick = now;
        }
        return snapshot;
    }

    /**
     * Mod projectile by UUID, inside or outside the world; null means absent (detonated, removed, left this
     * world).
     */
    @Nullable
    StrikeProjectile find(ServerLevel level, UUID id) {
        if (level.getEntity(id) instanceof StrikeProjectile p && !p.isRemoved()) return p;
        for (StrikeProjectile p : VirtualFlights.get(level).flights()) {
            if (p.getUUID().equals(id) && !p.isRemoved()) return p;
        }
        return null;
    }

    /** A missile is already pursuing the target. */
    public boolean engaged(UUID target) {
        return engaged.containsKey(target);
    }

    /** Missiles in flight. */
    public List<Interceptor> flights() {
        return Collections.unmodifiableList(flights);
    }

    Map<UUID, Long> alerted(String side) {
        return alerted.computeIfAbsent(side, k -> new HashMap<>());
    }

    /** The side's alert sounds at most once per {@code gap} ticks: true means it is time and the time is recorded. */
    boolean alertSound(String side, long now, int gap) {
        Long last = alertSound.get(side);
        if (last != null && now - last < gap) return false;
        alertSound.put(side, now);
        return true;
    }

    /** Launch: the missile leaves the rail upward at the spec's angle toward the target (vertically for the SAM). */
    Interceptor launch(ServerLevel level, Vec3 rail, Radar.Track track, @Nullable UUID owner, String launcher, InterceptorSpec spec) {
        Interceptor f = new Interceptor(spec, track.id(), owner, launcher, rail, Interceptor.railDirection(rail, track.position(), spec));
        flights.add(f);
        engaged.put(track.id(), f);
        Incoming.LOG.debug("SAM {} fired at {} {}, range {} blocks", launcher, track.projectile().getType().getDescriptionId(), track.id(),
                (int) track.distance());
        show(level, f);
        return f;
    }

    private void tick(ServerLevel level) {
        if (flights.isEmpty()) return;
        for (Interceptor f : new ArrayList<>(flights)) {
            try {
                f.tick(level, this);
            } catch (RuntimeException e) {
                Incoming.LOG.error("Interceptor at {} failed with an exception and was removed", BlockPos.containing(f.position()), e);
                end(f, Interceptor.End.LOST);
            }
            if (!f.done()) show(level, f);
        }
    }

    /**
     * The missile burst at {@code at}: near the target ({@code target}) or by itself (self-destruct, ground). On
     * a hit, the target breaks apart ({@link StrikeProjectile#intercepted}). Clients receive an airburst ({@link
     * S2C.Intercept}).
     */
    void burst(ServerLevel level, Interceptor f, Vec3 at, @Nullable StrikeProjectile target, Interceptor.End end) {
        boolean kill = end == Interceptor.End.KILL && target != null;
        if (kill) target.intercepted(level, "SAM " + f.launcher());
        Incoming.LOG.debug("Interceptor from SAM {} against {}: {} at {}", f.launcher(), f.target(), end, BlockPos.containing(at));
        PacketDistributor.sendToPlayersNear(level, null, at.x, at.y, at.z, IncomingConfig.SERVER.farRange.get(),
                new S2C.Intercept(at, kill, kill ? Optional.of(target.getUUID()) : Optional.empty(), level.random.nextLong()));
        ua.zentix.incoming.strike.FarFlights.endInterceptor(level, f, at);
        end(f, end);
    }

    private void end(Interceptor f, Interceptor.End end) {
        f.finish(end);
        flights.remove(f);
        engaged.remove(f.target(), f);
        InterceptorEntity v = f.view();
        if (v != null) v.discard();
        f.setView(null);
    }

    /**
     * Missile visual: exists only above a ready ticking chunk (the entity loads nothing there), placed at the
     * flight position; outside those chunks the visual is removed and flight continues without it.
     */
    private static void show(ServerLevel level, Interceptor f) {
        BlockPos at = BlockPos.containing(f.position());
        boolean live = Terrain.ready(level, at) && level.isPositionEntityTicking(at);
        InterceptorEntity v = f.view();
        if (v != null && (v.isRemoved() || !live)) {
            v.discard();
            f.setView(null);
            v = null;
        }
        if (!live) return;
        if (v != null) {
            v.follow(f);
            return;
        }
        v = InterceptorEntity.of(level, f);
        if (level.addFreshEntity(v)) f.setView(v);
    }

    /** Remove all missiles without a burst (tests: leftovers do not reach subsequent tests). */
    public void clear() {
        for (Interceptor f : new ArrayList<>(flights)) end(f, Interceptor.End.LOST);
        alerted.clear();
        alertSound.clear();
    }
}
