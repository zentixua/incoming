package ua.zentix.incoming.strike;

import net.minecraft.world.level.Level;
import ua.zentix.incoming.util.Air;

/**
 * Воздух, в котором летят снаряды: воздух мира ({@link Air#of(Level)} — ветер, устойчивость, влажность), а в проверках,
 * которые повторяют траектории точно (сценарии полёта: время игры у их пуска зависит от порядка прогона), — штиль.
 */
public final class FlightAir {
    /** Штиль вместо ветра мира (проверки); в игре не зовётся. */
    private static boolean calm;

    private FlightAir() {}

    /** Воздух мира {@code level} для полёта сейчас. */
    public static Air of(Level level) {
        return calm ? Air.CALM : Air.of(level);
    }

    /** Штиль для полёта (сценарии полёта, проверки без ветра). */
    public static void calm(boolean on) {
        calm = on;
    }
}
