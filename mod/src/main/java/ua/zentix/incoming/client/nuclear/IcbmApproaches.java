package ua.zentix.incoming.client.nuclear;

import ua.zentix.incoming.nuclear.IcbmArrival;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Finite terminal snapshots; warning removal cannot erase an already emitted wake. No audio sources. */
public final class IcbmApproaches {
    public static final class Entry {
        public final IcbmArrival path;
        private long seen;
        private double ended = Double.NaN;
        Entry(IcbmArrival path) { this.path = path; }
        public double time(double server, double local) {
            return Double.isNaN(ended) ? server : path.end() + local - ended;
        }
        public boolean head() { return Double.isNaN(ended); }
    }

    private final Map<Integer, Entry> entries = new LinkedHashMap<>();
    private boolean supported;
    public boolean supported() { return supported; }
    private long generation;
    private long epoch = Long.MIN_VALUE;
    private double local, received, shown = Double.NEGATIVE_INFINITY;

    public void snapshot(long time, List<IcbmArrival> arrivals) {
        if (time < epoch) reset(); // recording seek; live server snapshots are ordered
        supported = true;
        epoch = time;
        received = local;
        long revision = ++generation;
        for (IcbmArrival a : arrivals) entries.computeIfAbsent(a.strikeId(), id -> new Entry(a)).seen = revision;
        entries.values().removeIf(e -> e.head() && e.seen != revision);
    }

    public void finish(int id) { finish(id, 0); }

    /** A seek delivers old arrival and burst packets together: retain only the wake's remaining
     * lifetime at the recording clock, never start a fresh wake at packet receipt. */
    public void finish(int id, double eventAge) {
        if (eventAge >= IcbmArrival.WAKE) { entries.remove(id); return; }
        Entry e = entries.get(id);
        if (e != null) {
            double end = local - Math.max(0, eventAge);
            e.ended = e.head() ? end : Math.min(e.ended, end);
        }
    }

    public void tick() {
        local++;
        entries.values().removeIf(e -> !e.head() && local - e.ended > IcbmArrival.WAKE);
    }

    /** At most one tick of interpolation behind received server time, never extrapolate beyond it. */
    public double time(float partial) {
        if (epoch == Long.MIN_VALUE) return 0;
        shown = Math.max(shown, epoch - 1 + Math.min(1, local - received + partial));
        return shown;
    }

    public double local(float partial) { return local + partial; }
    public Collection<Entry> entries() { return entries.values(); }
    public void reset() {
        entries.clear();
        supported = false;
        epoch = Long.MIN_VALUE;
        local = received = 0;
        generation = 0;
        shown = Double.NEGATIVE_INFINITY;
    }
}
