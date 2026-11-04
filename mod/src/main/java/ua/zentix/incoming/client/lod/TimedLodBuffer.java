package ua.zentix.incoming.client.lod;

import java.util.HashMap;
import java.util.Map;
import java.util.PriorityQueue;

/** Bounded ownership ledger. Cancellation invalidates results; it is not an external ingestion fence. Time is supplied by the authoritative event clock. */
public final class TimedLodBuffer<T> {
    public record Ticket(long id, long epoch, long key, long due, long bytes, int sections, int timeline) {}
    public record Ready<T>(Ticket ticket, T value) {}
    private final int limit;
    private final long byteLimit;
    private final Map<Long, Ticket> pending = new HashMap<>();
    private final Map<Long, Ready<T>> held = new HashMap<>();
    private final Map<Integer, PriorityQueue<Ready<T>>> timelines = new HashMap<>();
    private static int order(Ready<?> a, Ready<?> b) {
        int time = Long.compare(a.ticket.due, b.ticket.due);
        return time != 0 ? time : Long.compare(a.ticket.id, b.ticket.id);
    }

    private PriorityQueue<Ready<T>> timeline(int id) {
        return timelines.computeIfAbsent(id, unused -> new PriorityQueue<>(TimedLodBuffer::order));
    }
    private long epoch, sequence, bytes;

    public TimedLodBuffer(int limit, long byteLimit) {
        if (limit <= 0 || byteLimit <= 0) throw new IllegalArgumentException("positive limits required");
        this.limit = limit;
        this.byteLimit = byteLimit;
    }

    public synchronized Ticket reserve(long key, long due, long size, int sections) {
        return reserve(key, due, size, sections, -1);
    }

    public synchronized Ticket reserve(long key, long due, long size, int sections, int timeline) {
        if (size < 0 || sections <= 0) throw new IllegalArgumentException("invalid reservation");
        if (pending.size() + held.size() >= limit || size > byteLimit - bytes) return null;
        Ticket t = new Ticket(++sequence, epoch, key, due, size, sections, timeline);
        pending.put(t.id, t);
        bytes += size;
        return t;
    }

    /** A late/cancelled/duplicate read cannot enter a later event. Missing reads keep their reservation. */
    public synchronized boolean complete(Ticket t, T value) {
        if (pending.remove(t.id, t)) {
            if (t.epoch == epoch && value != null) {
                Ready<T> r = new Ready<>(t, value);
                held.put(t.id, r);
                timeline(t.timeline).add(r);
                return true;
            }
            bytes -= t.bytes;
        }
        return false;
    }

    /** Taking a due column keeps ownership charged until finish, including while ingestion is executing. */
    public synchronized Ready<T> take(long now) { return take(unused -> now); }

    /** Claim and session check are one operation: an old worker may not take a newly refilled epoch's work. */
    public synchronized Ready<T> take(long expectedEpoch, java.util.function.IntToDoubleFunction clock) {
        return epoch == expectedEpoch ? take(clock) : null;
    }

    /** One head per event: a future event must not block another event whose own visible clock is due. */
    public synchronized Ready<T> take(java.util.function.IntToDoubleFunction clock) {
        Ready<T> first = null;
        for (var queue : timelines.values()) {
            Ready<T> head = queue.peek();
            if (head.ticket.due <= clock.applyAsDouble(head.ticket.timeline)
                    && (first == null || order(head, first) < 0)) first = head;
        }
        if (first == null) return null;
        var queue = timelines.get(first.ticket.timeline);
        queue.remove();
        if (queue.isEmpty()) timelines.remove(first.ticket.timeline);
        return first;
    }

    /** Return taken work whose event clock has not reached its deadline; retains the same reservation. */
    public synchronized void defer(Ready<T> r) {
        if (r.ticket.epoch == epoch && held.get(r.ticket.id) == r) timeline(r.ticket.timeline).add(r);
        else finish(r);
    }

    public synchronized void finish(Ready<T> r) {
        if (held.remove(r.ticket.id, r)) bytes -= r.ticket.bytes;
    }

    public synchronized long stop() {
        epoch++;
        // Only ready objects can be released now. Pending reads and taken objects still own memory.
        for (var queue : timelines.values()) {
            for (Ready<T> r : queue) if (held.remove(r.ticket.id, r)) bytes -= r.ticket.bytes;
        }
        timelines.clear();
        return epoch;
    }

    public synchronized boolean current(Ticket t) { return t.epoch == epoch; }
    public synchronized int size() { return pending.size() + held.size(); }
    public synchronized int waiting() { return pending.size(); }
    public synchronized long bytes() { return bytes; }
    public synchronized long epoch() { return epoch; }
}
