package me.cortex.voxy.common.world;

/** Подделка класса Voxy 0.2.16 для юнит-тестов: только то, что читает мод (имена и сигнатуры). */
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
