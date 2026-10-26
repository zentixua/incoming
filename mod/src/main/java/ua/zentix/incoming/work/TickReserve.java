package ua.zentix.incoming.work;

/** Recent measured mandatory server work, excluding our lanes and proven optional chunk saving. */
public final class TickReserve {
    // One second at the default tick rate. A slow mandatory tick raises the reserve immediately;
    // retaining it for a whole second avoids repeatedly spending the same headroom after a spike.
    private final long[] samples = new long[20];
    private int next, count;

    public void observe(long mandatoryNanos) {
        samples[next] = Math.max(0, mandatoryNanos);
        next = (next + 1) % samples.length;
        count = Math.min(samples.length, count + 1);
    }

    public long mandatoryNanos() {
        long maximum = 0;
        for (int i = 0; i < count; i++) maximum = Math.max(maximum, samples[i]);
        return maximum;
    }

    /** Absolute schedule headroom, after reserving measured simulation. Unknown cost means no early window. */
    public long earlyNanos(long cap, long now, long tickDeadline) {
        if (count == 0) return 0;
        return Math.min(cap, Math.max(0, tickDeadline - now - mandatoryNanos()));
    }
}
