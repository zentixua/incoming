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
import java.util.List;
import java.util.stream.Stream;

/**
 * Bodies that were not in memory when a detonation's light, prompt radiation and wave crossed their place. The pulse
 * ({@link PulseJob}) and the front ({@link BlastFront}) act on loaded entities only, so a village nobody stood near kept
 * walking after a detonation that destroyed its houses (chunk ruins are applied on load, the bodies were not). Such a body
 * receives, once, when it loads, what its place received: burns and prompt dose by the same laws (sickness runs from the
 * detonation), and the wave injury without the throw and the flight, which belong to that moment. Marks
 * ({@link NukeSeen}), checked by the pulse and the front as well, give each body each detonation's pulse and wave once,
 * whichever path comes first; a body spawned after the pulse or the front passed its place gets neither.
 * <p>
 * Estimates: the world is read as it is when the body loads — ruins may already stand in place of the walls that shaded
 * or sheltered it; the launcher is not credited. Players are left out: a player who was away is not in the city.
 * A body that moved after it was spawned is judged by where it is now. Bodies generated with new terrain after a detonation
 * count as spawned then (the join event does not tell them from births), so they are unharmed in ruined chunks.
 * Detonations from before these marks existed ({@link NuclearEvents#lateFrom}) are left as they were: a body hit then
 * carries no mark and would be hit twice.
 */
final class LateExposure {
    /** Slow-log interval for the summary line, ticks. */
    private static final int LOG_PERIOD = 200;

    /** What a loaded body still has to receive from one detonation; one work unit. */
    private record Due(LivingEntity e, Detonation d, boolean pulse, boolean blast) {}

    /** Bodies loaded before every detonation's reach is known: sorted under the budget once it is. */
    private final ArrayDeque<LivingEntity> unsorted = new ArrayDeque<>();
    private final ArrayDeque<Due> queue = new ArrayDeque<>();
    /**
     * Light and radiation reach per detonation, blocks ({@link NuclearWarhead#exposureRange}); computed under the budget
     * with the wave reach and front tables, not in the join event: after a restart they are cold for every past detonation.
     */
    private final Int2DoubleOpenHashMap pulseRange = new Int2DoubleOpenHashMap();
    private int exposed, struck;
    private long lastLog = Long.MIN_VALUE / 2;

    /**
     * Entity enters the level: from disk — queue what it missed; newly spawned — mark its birth time.
     * Cheap (distance checks only): chunk loads bring hundreds of entities at once.
     *
     * @param pulses detonations whose pulse still runs: a body loaded before its list is taken is in it
     */
    void joined(ServerLevel level, LivingEntity e, boolean fromDisk, List<PulseJob> pulses) {
        if (e instanceof Player) return;
        NuclearEvents events = NuclearEvents.get(level);
        if (events.lastId() < events.lateFrom()) return;
        if (!fromDisk) {
            // born now: nothing to catch up on; the fallout account starts now wherever fallout may still fall
            NukeSeen seen = e.getExistingData(ModAttachments.NUKE_SEEN).orElse(NukeSeen.NONE);
            NukeSeen born = seen.withBorn(level.getGameTime());
            if (!events.detonations().isEmpty()) born = born.withFallout(level.getGameTime());
            if (born != seen) e.setData(ModAttachments.NUKE_SEEN, born);
            return;
        }
        if (!unsorted.isEmpty() || !warm(events)) unsorted.add(e);
        else sort(level, events, e, pulses);
    }

    /** All reaches known for the detonations a body may have missed. */
    private boolean warm(NuclearEvents events) {
        for (Detonation d : all(events)) if (d.id() >= events.lateFrom() && !pulseRange.containsKey(d.id())) return false;
        return true;
    }

    private static Iterable<Detonation> all(NuclearEvents events) {
        return Stream.concat(events.past().stream(), events.detonations().stream())::iterator;
    }

    /** Queue what a loaded body missed: pulses and passed fronts that covered its place after its birth. */
    private void sort(ServerLevel level, NuclearEvents events, LivingEntity e, List<PulseJob> pulses) {
        NukeSeen seen = e.getExistingData(ModAttachments.NUKE_SEEN).orElse(NukeSeen.NONE);
        long now = level.getGameTime();
        for (Detonation d : all(events)) {
            if (d.id() < events.lateFrom()) continue;
            boolean listing = false;
            for (PulseJob p : pulses) listing |= p.detonation().id() == d.id() && !p.listed();
            double dist = e.position().distanceTo(d.burst());
            boolean pulse = !seen.pulsed(d.id()) && !seen.bornAfter(d.gameTime()) && !listing && dist <= pulseRange.get(d.id());
            boolean blast = !seen.blasted(d.id()) && dist <= d.blastReach() && dist < d.frontRadius(now - d.gameTime())
                    && !seen.bornAfter(d.gameTime() + d.arrivalTicks(dist));
            if (pulse || blast) queue.add(new Due(e, d, pulse, blast));
        }
    }

    boolean busy() {
        return !queue.isEmpty() || !unsorted.isEmpty();
    }

    /** Reaches of new detonations, then waiting bodies, then each body's detonations: one per work unit. */
    void work(ServerLevel level, WorkClock clock, List<PulseJob> pulses) {
        NuclearEvents events = NuclearEvents.get(level);
        for (Detonation d : all(events)) {
            if (!clock.canStart()) return;
            if (d.id() < events.lateFrom() || pulseRange.containsKey(d.id())) continue;
            long t0 = clock.begin();
            pulseRange.put(d.id(), NuclearWarhead.exposureRange(d));
            d.blastReach();
            d.frontRadius(0);
            clock.end(t0);
        }
        while (!unsorted.isEmpty() && clock.canStart()) {
            long t0 = clock.begin();
            LivingEntity e = unsorted.removeFirst();
            // unloaded while waiting: sorted again when it loads
            if (e.isAlive() && e.level() == level) sort(level, events, e, pulses);
            clock.end(t0);
        }
        while (!queue.isEmpty() && clock.canStart()) {
            Due u = queue.removeFirst();
            LivingEntity e = u.e();
            // unloaded or dead while queued: an unloaded body is sorted again when it loads (no mark yet)
            if (!e.isAlive() || e.level() != level) continue;
            long t0 = clock.begin();
            // expose skips a pulse already received (queued twice by a quick reload, or taken by the pulse list)
            if (u.pulse() && NuclearWarhead.expose(level, u.d(), e, null, true)) exposed++;
            if (u.blast() && e.isAlive() && !e.getData(ModAttachments.NUKE_SEEN).blasted(u.d().id())) {
                e.setData(ModAttachments.NUKE_SEEN, e.getData(ModAttachments.NUKE_SEEN).withBlast(u.d().id()));
                // a passenger is skipped by the front too (BlastFront.advance)
                if (!e.isPassenger() && BlastBody.late(level, u.d(), e, null)) struck++;
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
        unsorted.clear();
        pulseRange.clear();
    }
}
