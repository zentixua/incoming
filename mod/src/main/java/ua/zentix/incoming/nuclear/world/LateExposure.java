package ua.zentix.incoming.nuclear.world;

import it.unimi.dsi.fastutil.ints.Int2DoubleOpenHashMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import ua.zentix.incoming.Incoming;
import ua.zentix.incoming.nuclear.Detonation;
import ua.zentix.incoming.nuclear.NuclearEvents;
import ua.zentix.incoming.nuclear.NuclearWarhead;
import ua.zentix.incoming.registry.ModAttachments;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * Bodies that were not in memory when a detonation's light, prompt radiation and wave crossed their place. The pulse
 * ({@link PulseJob}) and the front ({@link BlastFront}) act on loaded entities only, so a village nobody stood near kept
 * walking after a detonation that destroyed its houses (chunk ruins are applied on load, the bodies were not). Such a body
 * receives, once, when it loads, what its place received: burns and prompt dose by the same laws (sickness runs from the
 * detonation), and the wave injury without the throw and the flight, which belong to that moment. Marks
 * ({@link NukeSeen}) keep a body that was hit in memory from being hit again on a later load; a body spawned after a
 * detonation within its reach is marked at once.
 * <p>
 * Estimates: the world is read as it is when the body loads — ruins may already stand in place of the walls that shaded
 * or sheltered it; the launcher is not credited. Players are left out: a player who was away is not in the city.
 * Detonations from before this mark existed ({@link NuclearEvents#lateFrom}) are left as they were: a body hit then
 * carries no mark and would be hit twice.
 */
final class LateExposure {
    /** Slow-log interval for the summary line, ticks. */
    private static final int LOG_PERIOD = 200;

    /** Detonation and what a body loaded now still has to receive from it. */
    private record Due(Detonation d, boolean pulse, boolean blast) {}

    private record Body(LivingEntity e, List<Due> due) {}

    private final ArrayDeque<Body> queue = new ArrayDeque<>();
    /** Light and radiation reach per detonation, blocks ({@link NuclearWarhead#exposureRange}: bisection, cached). */
    private final Int2DoubleOpenHashMap pulseRange = new Int2DoubleOpenHashMap();
    private int exposed, struck;
    private long lastLog = Long.MIN_VALUE / 2;

    /**
     * Entity enters the level: from disk — queue what it missed; newly spawned — mark it settled for every detonation
     * so far. Cheap (distance checks only): chunk loads bring hundreds of entities at once.
     *
     * @param pulses detonations whose pulse still runs: a body loaded before its list is taken is in it
     */
    void joined(ServerLevel level, LivingEntity e, boolean fromDisk, List<PulseJob> pulses) {
        if (e instanceof Player) return;
        NuclearEvents events = NuclearEvents.get(level);
        if (events.lastId() < events.lateFrom()) return;
        long now = level.getGameTime();
        NukeSeen seen = e.getExistingData(ModAttachments.NUKE_SEEN).orElse(NukeSeen.NONE);
        if (!fromDisk) {
            // born now: nothing to catch up on. Marked only where a detonation reached (not every mob of the world forever);
            // the fallout account starts now wherever fallout may still fall (trails reach beyond the pulse and the wave)
            NukeSeen born = events.detonations().isEmpty() ? seen : seen.withFallout(now);
            for (Detonation d : (Iterable<Detonation>) Stream.concat(events.past().stream(), events.detonations().stream())::iterator) {
                if (d.id() >= events.lateFrom() && e.position().distanceTo(d.burst()) <= Math.max(d.blastReach(), pulseRange(d))) {
                    born = born.withPulse(d.id()).withBlast(d.id());
                }
            }
            if (born != seen) e.setData(ModAttachments.NUKE_SEEN, born);
            return;
        }
        List<Due> due = null;
        for (Detonation d : (Iterable<Detonation>) Stream.concat(events.past().stream(), events.detonations().stream())::iterator) {
            if (d.id() < events.lateFrom()) continue;
            boolean listing = false;
            for (PulseJob p : pulses) listing |= p.detonation().id() == d.id() && !p.listed();
            double dist = e.position().distanceTo(d.burst());
            boolean pulse = d.id() > seen.pulse() && !listing && dist <= pulseRange(d);
            boolean blast = d.id() > seen.blast() && dist <= d.blastReach() && dist < d.frontRadius(now - d.gameTime());
            if (!pulse && !blast) continue;
            if (due == null) due = new ArrayList<>(1);
            due.add(new Due(d, pulse, blast));
        }
        if (due != null) queue.add(new Body(e, due));
    }

    private double pulseRange(Detonation d) {
        return pulseRange.computeIfAbsent(d.id(), id -> NuclearWarhead.exposureRange(d));
    }

    boolean busy() {
        return !queue.isEmpty();
    }

    /** Bodies within budget, one per work unit. */
    void work(ServerLevel level, WorkClock clock) {
        while (!queue.isEmpty() && clock.canStart()) {
            Body b = queue.removeFirst();
            LivingEntity e = b.e();
            // unloaded or dead while queued: an unloaded body is queued again when it loads (no mark yet)
            if (!e.isAlive() || e.level() != level) continue;
            long t0 = clock.begin();
            for (Due u : b.due()) {
                if (!e.isAlive()) break;
                // marked since it was queued (queued twice by a quick reload, or the pulse list took it): nothing more
                NukeSeen seen = e.getData(ModAttachments.NUKE_SEEN);
                if (u.pulse() && u.d().id() > seen.pulse() && NuclearWarhead.expose(level, u.d(), e, null, true)) exposed++;
                if (u.blast() && u.d().id() > seen.blast() && e.isAlive()) {
                    e.setData(ModAttachments.NUKE_SEEN, e.getData(ModAttachments.NUKE_SEEN).withBlast(u.d().id()));
                    // a passenger is skipped by the front too (BlastFront.advance)
                    if (!e.isPassenger() && BlastBody.late(level, u.d(), e, null)) struck++;
                }
            }
            clock.end(t0);
        }
        long now = level.getGameTime();
        if (queue.isEmpty() && exposed + struck > 0 && now - lastLog >= LOG_PERIOD) {
            Incoming.LOG.info("Nuclear: bodies loaded after the detonation — light and radiation {}, wave {}", exposed, struck);
            exposed = struck = 0;
            lastLog = now;
        }
    }

    void clear() {
        queue.clear();
        pulseRange.clear();
    }
}
