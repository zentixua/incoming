package me.cortex.voxy.common.world;

/** Fake class Voxy 0.2.16 for unit tests: only what the mod reads (names and signatures). */
public final class WorldSection {
    private final WorldEngine engine;
    private final long[] data;

    WorldSection(WorldEngine engine, long[] data) {
        this.engine = engine;
        this.data = data;
    }

    public void copyDataTo(long[] out) {
        System.arraycopy(data, 0, out, 0, data.length);
    }

    public int release() {
        engine.released++;
        return 0;
    }
}
