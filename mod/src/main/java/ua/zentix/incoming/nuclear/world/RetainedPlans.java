package ua.zentix.incoming.nuclear.world;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;

/** Shared preparation/scar ownership: applying or replacing a plan releases its actual retained bytes. */
final class RetainedPlans extends Long2ObjectOpenHashMap<RuinPlan> {
    private static final long serialVersionUID = 1L;
    private long bytes;

    long bytes() { return bytes; }

    @Override
    public RuinPlan put(long key, RuinPlan value) {
        RuinPlan old = super.put(key, value);
        bytes += value.bytes() - (old == null ? 0 : old.bytes());
        return old;
    }

    @Override
    public RuinPlan remove(long key) {
        RuinPlan old = super.remove(key);
        if (old != null) bytes -= old.bytes();
        return old;
    }

    @Override
    public void clear() {
        super.clear();
        bytes = 0;
    }
}
