package ua.zentix.incoming.client;

import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

/** Read-only database witness, on the probe worker, never a renderer modification or visibility acknowledgement. */
final class LodProbeReadback {
    private final Object engine, mapper;
    private final java.lang.reflect.Method acquire, copy, release, acquireRef, releaseRef, blockId, state;
    private final long[] data = new long[32768];
    LodProbeReadback(Level level) throws ReflectiveOperationException {
        Class<?> id = Class.forName("me.cortex.voxy.commonImpl.WorldIdentifier");
        engine = id.getMethod("ofEngineNullable", Level.class).invoke(null, level);
        if (engine == null) throw new IllegalStateException("Voxy world unavailable");
        Class<?> e = Class.forName("me.cortex.voxy.common.world.WorldEngine");
        Class<?> s = Class.forName("me.cortex.voxy.common.world.WorldSection");
        Class<?> m = Class.forName("me.cortex.voxy.common.world.other.Mapper");
        mapper = e.getMethod("getMapper").invoke(engine);
        acquire = e.getMethod("acquireIfExists", int.class, int.class, int.class, int.class);
        acquireRef = e.getMethod("acquireRef"); releaseRef = e.getMethod("releaseRef");
        copy = s.getMethod("copyDataTo", long[].class); release = s.getMethod("release");
        blockId = m.getMethod("getBlockId", long.class); state = m.getMethod("getBlockStateFromBlockId", int.class);
    }
    boolean air(int x, int y, int z) throws ReflectiveOperationException {
        acquireRef.invoke(engine);
        try {
            Object section = acquire.invoke(engine, 0, x >> 5, y >> 5, z >> 5);
            if (section == null) return true;
            try { copy.invoke(section, (Object) data); } finally { release.invoke(section); }
            long voxel = data[(y & 31) * 1024 + (z & 31) * 32 + (x & 31)];
            return ((BlockState) state.invoke(mapper, blockId.invoke(null, voxel))).isAir();
        } finally { releaseRef.invoke(engine); }
    }
}
