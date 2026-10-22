package me.cortex.voxy.common.world;

import me.cortex.voxy.common.world.other.Mapper;

import java.util.HashMap;
import java.util.Map;

/** Подделка класса Voxy 0.2.16 для юнит-тестов: только то, что читает мод (имена и сигнатуры). */
public class WorldEngine {
    /** Мир, который отдаёт {@code WorldIdentifier.ofEngineNullable}; null — Voxy мира не держит. */
    public static WorldEngine current;
    public final Map<String, long[]> sections = new HashMap<>();
    public final Mapper mapper = new Mapper();
    public boolean live = true;
    public int refs, acquired, released;

    public static String key(int lvl, int x, int y, int z) {
        return lvl + "/" + x + "/" + y + "/" + z;
    }

    public WorldSection acquireIfExists(int lvl, int x, int y, int z) {
        if (!live) throw new IllegalStateException("World is not live");
        long[] data = sections.get(key(lvl, x, y, z));
        if (data == null) return null;
        acquired++;
        return new WorldSection(this, data);
    }

    public Mapper getMapper() {
        return mapper;
    }

    public void acquireRef() {
        if (!live) throw new IllegalStateException();
        refs++;
    }

    public void releaseRef() {
        refs--;
    }
}
