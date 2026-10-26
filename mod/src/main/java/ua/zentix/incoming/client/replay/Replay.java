package ua.zentix.incoming.client.replay;

import it.unimi.dsi.fastutil.booleans.BooleanConsumer;
import net.minecraft.client.Minecraft;
import ua.zentix.incoming.Incoming;
import ua.zentix.incoming.compat.FlashbackReplay;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Flashback replay and seeking. In one tick during seek, replay server loads a recording segment snapshot and reads
 * packets from its start to the requested position, sending mod packets to client as soon as read; Flashback NeoForge
 * Fixed also stores the last packet of each type in every snapshot. Treating them as live would replay all explosions
 * and detonations from the segment's start at once, with flash and sound, so seek events are past: no impact visuals
 * or sound, nuclear detonation displayed at its age.
 * <p>
 * Replay events wait for the replay server end-of-tick marker ({@link FlashbackReplay}): client handles connection
 * packets in order, so everything before the marker was read no later than that tick. Event is live if this tick and
 * the two preceding ones are playback ({@link Markers#SETTLE}): no seek, position advances by at most
 * {@link Markers#STEP} (accelerated video export steps several ticks). Decide when processing marker, not from client
 * ticks: client ticks during seeking (Flashback only prevents rendering), while video export server stands between steps.
 * <p>
 * Outside replay, without Flashback or without markers, everything is live immediately.
 */
public final class Replay {
    /** Client ticks without any marker before pending events become live: markers are not arriving at all. */
    private static final int NO_MARKERS = 600;
    private static final Markers MARKERS = new Markers();
    private static final List<Pending> PENDING = new ArrayList<>();
    /** Live and past event counts per type (for replay scenario). */
    private static final Map<String, int[]> COUNTS = new LinkedHashMap<>();
    private static int waited;
    private static boolean warned;

    private record Pending(String kind, BooleanConsumer action) {}

    private Replay() {}

    /**
     * Server event ({@code kind} for counting): {@code action} receives whether live. Outside replay, immediate and live;
     * in replay, with this replay server tick's end marker.
     */
    public static void event(String kind, BooleanConsumer action) {
        if (!active()) {
            action.accept(true);
            return;
        }
        PENDING.add(new Pending(kind, action));
    }

    /**
     * Replay server end-of-tick marker: prior events become live or past; first playback marker after seek calls
     * {@code rewound} first (everything before seeking belongs to another recording position).
     */
    public static void marker(int place, boolean seeking, Runnable rewound) {
        Markers.Verdict v = MARKERS.next(place, seeking);
        if (v == Markers.Verdict.SETTLED) rewound.run();
        flush(v == Markers.Verdict.LIVE);
    }

    /** Client tick: pending events become live if replay closed or no markers arrive at all. */
    public static void tick() {
        if (PENDING.isEmpty()) return;
        if (!active()) {
            flush(true);
        } else if (MARKERS.count == 0 && ++waited > NO_MARKERS) {
            if (!warned) {
                warned = true;
                Incoming.LOG.warn("Flashback: no replay server markers for {} ticks — replay events play as live", NO_MARKERS);
            }
            flush(true);
        }
    }

    /** World exit: do not retain old replay's pending events and markers. */
    public static void reset() {
        PENDING.clear();
        MARKERS.reset();
        waited = 0;
    }

    /** Client is viewing a Flashback replay. */
    public static boolean active() {
        return FlashbackReplay.isReplay(Minecraft.getInstance().getSingleplayerServer());
    }

    /** Markers since replay opened. */
    public static long markers() {
        return MARKERS.count;
    }

    /** Replay playing or stationary without seeking: next server tick's events live if that tick is playback too. */
    public static boolean steady() {
        return MARKERS.steady();
    }

    /** Event count copy: kind → {live, past}. */
    public static Map<String, int[]> counts() {
        Map<String, int[]> copy = new LinkedHashMap<>();
        COUNTS.forEach((k, v) -> copy.put(k, v.clone()));
        return copy;
    }

    private static void flush(boolean live) {
        waited = 0;
        if (PENDING.isEmpty()) return;
        List<Pending> all = new ArrayList<>(PENDING);
        PENDING.clear();
        for (Pending p : all) {
            COUNTS.computeIfAbsent(p.kind, k -> new int[2])[live ? 0 : 1]++;
            p.action.accept(live);
        }
    }

    /** Replay server markers in order; no Minecraft, for tests. */
    static final class Markers {
        /**
         * Maximum position advance per server tick during playback: accelerated video export steps several ticks per
         * frame. Flashback itself distinguishes seeking this way: final 20 ticks to destination run tick by tick.
         */
        static final int STEP = 20;
        /**
         * Consecutive playback markers whose events are still past. First is last seek read or first tick after it.
         * Second is needed because Flashback NeoForge Fixed accumulates packets from snapshot start rather than
         * sending immediately, delivering at replay server tick end ({@code MixinReplayServer.catchUpNewViewers}) after
         * the final read's marker: all seek-read packets arrive before the second marker.
         */
        static final int SETTLE = 2;

        enum Verdict {
            /** This tick's events are live. */
            LIVE,
            /** Past: seeking, first replay marker, or second playback marker after seeking. */
            PAST,
            /** Past, and first playback marker after seeking. */
            SETTLED
        }

        /** Consecutive playback markers, up to {@code SETTLE + 1}. */
        private int streak;
        private int place;
        long count;

        Verdict next(int place, boolean seeking) {
            int moved = place - this.place;
            boolean clean = count > 0 && !seeking && moved >= 0 && moved <= STEP;
            streak = clean ? Math.min(streak + 1, SETTLE + 1) : 0;
            this.place = place;
            count++;
            return streak > SETTLE ? Verdict.LIVE : streak == 1 ? Verdict.SETTLED : Verdict.PAST;
        }

        /** Next marker's events will be live if it too is playback. */
        boolean steady() {
            return streak >= SETTLE;
        }

        void reset() {
            streak = 0;
            count = 0;
        }
    }
}
