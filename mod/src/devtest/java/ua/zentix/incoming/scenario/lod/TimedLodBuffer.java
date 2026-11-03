package ua.zentix.incoming.scenario.lod;

import java.util.HashMap;
import java.util.Map;
import java.util.PriorityQueue;

/** Experimental ownership ledger, excluded from the mod jar. Time is supplied by the authoritative event clock. */
public final class TimedLodBuffer<T> {
    public record Ticket(long id, long epoch, long key, long due, long bytes, int sections) {}
    public record Ready<T>(Ticket ticket, T value) {}
    private final int limit;
    private final long byteLimit;
    private final Map<Long, Ticket> pending = new HashMap<>();
    private final Map<Long, Ready<T>> held = new HashMap<>();
    private final PriorityQueue<Ready<T>> ready = new PriorityQueue<>((a, b) -> {
        int time = Long.compare(a.ticket.due, b.ticket.due);
        return time != 0 ? time : Long.compare(a.ticket.id, b.ticket.id);
    });
    private long epoch, sequence, bytes;

    public TimedLodBuffer(int limit, long byteLimit) {
        if (limit <= 0 || byteLimit <= 0) throw new IllegalArgumentException("positive limits required");
        this.limit = limit;
        this.byteLimit = byteLimit;
    }

    public synchronized Ticket reserve(long key, long due, long size, int sections) {
        if (size < 0 || sections <= 0) throw new IllegalArgumentException("invalid reservation");
        if (pending.size() + held.size() >= limit || size > byteLimit - bytes) return null;
        Ticket t = new Ticket(++sequence, epoch, key, due, size, sections);
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
                ready.add(r);
                return true;
            }
            bytes -= t.bytes;
        }
        return false;
    }

    /** Taking a due column keeps ownership charged until finish, including while ingestion is executing. */
    public synchronized Ready<T> take(long now) {
        Ready<T> r = ready.peek();
        return r != null && r.ticket.due <= now ? ready.poll() : null;
    }

    public synchronized void finish(Ready<T> r) {
        if (held.remove(r.ticket.id, r)) bytes -= r.ticket.bytes;
    }

    public synchronized long stop() {
        epoch++;
        // Only ready objects can be released now. Pending reads and taken objects still own memory.
        for (Ready<T> r : ready) if (held.remove(r.ticket.id, r)) bytes -= r.ticket.bytes;
        ready.clear();
        return epoch;
    }

    public synchronized boolean current(Ticket t) { return t.epoch == epoch; }
    public synchronized int size() { return pending.size() + held.size(); }
    public synchronized int waiting() { return pending.size(); }
    public synchronized long bytes() { return bytes; }
    public synchronized long epoch() { return epoch; }
}
