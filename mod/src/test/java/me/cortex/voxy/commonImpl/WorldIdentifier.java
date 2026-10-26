package me.cortex.voxy.commonImpl;

import me.cortex.voxy.common.world.WorldEngine;
import net.minecraft.world.level.Level;

/** Fake class Voxy 0.2.16 for unit tests: only what the mod reads (names and signatures). */
public class WorldIdentifier {
    public static WorldEngine ofEngineNullable(Level level) {
        return WorldEngine.current;
    }
}
