package me.cortex.voxy.common.world;

import me.cortex.voxy.common.world.other.Mapper;

import java.util.HashMap;
import java.util.Map;

/** Fake class Voxy 0.2.16 for unit tests: only what the mod reads (names and signatures). */
public class WorldEngine {
    /** A world that gives {@code WorldIdentifier.ofEngineNullable}; null - Voxy does not hold peace. */
    public static WorldEngine current;
    public final Map<String, long[]> sections = new HashMap<>();
    public final Mapper mapper = new Mapper();
    public boolean live = true;
    /** Living world, but the section is not readable (storage failure). */
    public boolean broken;
    public int refs, acquired, released;

    public static String key(int lvl, int x, int y, int z) {
        return lvl + "/" + x + "/" + y + "/" + z;
    }

    public WorldSection acquireIfExists(int lvl, int x, int y, int z) {
        if (!live) throw new IllegalStateException("World is not live");
        if (broken) throw new IllegalStateException("storage failure");
        long[] data = sections.get(key(lvl, x, y, z));
        if (data == null) return null;
        acquired++;
        return new WorldSection(this, data);
    }

    public boolean isLive() {
        return live;
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
