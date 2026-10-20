package ua.zentix.incoming.client.fx;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.util.Mth;
import ua.zentix.incoming.client.fx.plume.Air;
import ua.zentix.incoming.util.Wind;

/**
 * Воздух мира у клиента сейчас ({@link Air}): ветер мира по времени игры, класс устойчивости по солнцу, ветру и дождю,
 * влажность. Один на тик: дым частиц, ленты шлейфов и клубы объёма берут его отсюда.
 */
public final class ClientAir {
    private static final Air CALM = new Air(new Wind(0, 0), 3, 0.5);
    private static long tick = Long.MIN_VALUE;
    private static Air now = CALM;

    private ClientAir() {}

    /** Воздух сейчас; мира нет — штиль. */
    public static Air now() {
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null) return CALM;
        long t = level.getGameTime();
        if (t != tick) {
            tick = t;
            // синус высоты солнца — как у звука взрывов (BlastSounds); в измерении без неба солнца нет
            double sun = level.dimensionType().hasSkyLight() && !level.dimensionType().hasFixedTime() ? Mth.cos(level.getTimeOfDay(1) * Mth.TWO_PI) : -1;
            now = Air.of(t, sun, level.getRainLevel(1), level.getThunderLevel(1));
        }
        return now;
    }

    /** Ветер на высоте y мира вдоль x, блоков за тик. */
    public static double windX(double y) {
        Air a = now();
        return a.speed(y) * a.wind().x() / 20;
    }

    /** Ветер на высоте y мира вдоль z, блоков за тик. */
    public static double windZ(double y) {
        Air a = now();
        return a.speed(y) * a.wind().z() / 20;
    }
}
