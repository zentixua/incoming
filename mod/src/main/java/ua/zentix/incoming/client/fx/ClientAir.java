package ua.zentix.incoming.client.fx;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import ua.zentix.incoming.util.Air;

/**
 * Воздух мира у клиента сейчас ({@link Air}): ветер мира по времени игры, класс устойчивости по солнцу, ветру и дождю,
 * влажность. Один на тик: дым частиц, ленты шлейфов и клубы объёма берут его отсюда.
 */
public final class ClientAir {
    private static long tick = Long.MIN_VALUE;
    private static Air now = Air.CALM;

    private ClientAir() {}

    /** Воздух сейчас; мира нет — штиль. */
    public static Air now() {
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null) return Air.CALM;
        long t = level.getGameTime();
        if (t != tick) {
            tick = t;
            now = Air.of(level);
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
