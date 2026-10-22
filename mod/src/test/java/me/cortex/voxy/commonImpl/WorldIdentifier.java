package me.cortex.voxy.commonImpl;

import me.cortex.voxy.common.world.WorldEngine;
import net.minecraft.world.level.Level;

/** Подделка класса Voxy 0.2.16 для юнит-тестов: только то, что читает мод (имена и сигнатуры). */
public class WorldIdentifier {
    public static WorldEngine ofEngineNullable(Level level) {
        return WorldEngine.current;
    }
}
