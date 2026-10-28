package ua.zentix.incoming.nuclear.world;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import org.jetbrains.annotations.Nullable;
import ua.zentix.incoming.Incoming;
import ua.zentix.incoming.nuclear.Detonation;
import ua.zentix.incoming.nuclear.NuclearWarhead;
import ua.zentix.incoming.nuclear.radiation.FlashEyes;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * Thermal pulse and prompt radiation on entities (DESIGN-nuke §1.4, §1.5) under shared tick budget, players and entities
 * nearest the burst first. The pulse is not instantaneous in reality: 15 kt maximum after 0.04·W^0.44 ≈ 0.14 s,
 * main energy over about ten such intervals (Glasstone §7.85), so a couple ticks for hundreds of entities is fair.
 * Doing burns, deaths and loot at once in the detonation tick would stall the server for hundreds of milliseconds.
 * <p>
 * List is a snapshot ({@code getEntitiesOfClass}: mob death adds loot to the live entity map), taken as its first work
 * unit: nothing heavy in the detonation tick. Entities later dead or unloaded are skipped ({@link LivingEntity#isAlive}).
 */
final class PulseJob {
    private final Detonation d;
    @Nullable
    private final UUID owner;
    @Nullable
    private List<LivingEntity> targets;
    private int next;
    private int touched;
    private long nanos;
    private final long startTick;

    PulseJob(ServerLevel level, Detonation d, @Nullable UUID owner) {
        this.d = d;
        this.owner = owner;
        this.startTick = level.getGameTime();
    }

    Detonation detonation() {
        return d;
    }

    /** Whether the body list is taken: a body loaded from now on is not in it ({@link LateExposure}). */
    boolean listed() {
        return targets != null;
    }

    /**
     * Living entities within thermal/radiation range and players within retinal burn range: players first (retinal scar
     * from gaze as close as possible to flash time, also used by the client's afterimage), then nearest entities.
     */
    private static List<LivingEntity> inRange(ServerLevel level, Detonation d) {
        double range = NuclearWarhead.exposureRange(d), eyes = FlashEyes.reach(d);
        List<LivingEntity> in = new ArrayList<>(level.getEntitiesOfClass(LivingEntity.class, AABB.ofSize(d.burst(), range * 2, range * 2, range * 2),
                e -> e.distanceToSqr(d.burst()) <= range * range));
        for (ServerPlayer p : level.players()) {
            double r = p.distanceToSqr(d.burst());
            if (r > range * range && r <= eyes * eyes) in.add(p);
        }
        in.sort(Comparator.comparing((LivingEntity e) -> !(e instanceof Player)).thenComparingDouble(e -> e.distanceToSqr(d.burst())));
        return in;
    }

    /** Work fitting within budget; true — pulse processed everyone. */
    boolean work(ServerLevel level, WorkClock clock) {
        Entity ownerEntity = owner == null ? null : level.getPlayerByUUID(owner);
        while ((targets == null || next < targets.size()) && clock.canStart()) {
            long t0 = clock.begin();
            if (targets == null) targets = inRange(level, d);
            else if (NuclearWarhead.expose(level, d, targets.get(next++), ownerEntity)) touched++;
            nanos += clock.end(t0);
        }
        if (targets == null || next < targets.size()) return false;
        Incoming.LOG.info("Nuclear detonation #{}: light and radiation — {} of {} entities in {} ticks, {} ms", d.id(), touched, targets.size(),
                level.getGameTime() - startTick + 1, nanos / 1_000_000);
        return true;
    }
}
