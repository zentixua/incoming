package ua.zentix.incoming.client.fx;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import ua.zentix.incoming.util.Air;
import ua.zentix.incoming.util.LocalWind;

/**
 * Воздух мира у клиента сейчас ({@link Air#of(net.minecraft.world.level.Level)} — тот же, что у сервера): ветер мира по
 * времени игры, класс устойчивости по солнцу, ветру и дождю, влажность. Один на тик: дым частиц, ленты шлейфов и клубы
 * объёма берут его отсюда.
 */
public final class ClientAir {
    private static long tick = Long.MIN_VALUE;
    private static Air now = Air.CALM;

    private ClientAir() {}

    /** Воздух сейчас; мира нет (и клиента — в юнит-тестах очагов) — штиль. */
    public static Air now() {
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc == null ? null : mc.level;
        if (level == null) return Air.CALM;
        long t = level.getGameTime();
        if (t != tick) {
            tick = t;
            now = Air.of(level);
        }
        return now;
    }

    /** Мест с ветром у земли ({@link #site}) в памяти. */
    private static final int SITES = 256;
    /** Ячейка места, блоков, и шаг направления ветра, рад: ближе — то же место. */
    private static final int CELL = 4;
    private static final double TURN = Math.PI / 16;
    private static final java.util.Map<Long, LocalWind> CACHE = new java.util.LinkedHashMap<>(64, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(java.util.Map.Entry<Long, LocalWind> e) {
            return size() > SITES;
        }
    };
    private static ClientLevel cached;

    /**
     * Ветер у земли в (x, z) ({@link LocalWind}) по чанкам клиента и плиткам рельефа карты при нынешнем ветре мира; одно на
     * ячейку {@value #CELL} блоков и направление с шагом π/16 (залп «Града» не считает место заново). Мира нет — поле.
     */
    public static LocalWind site(double x, double z) {
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc == null ? null : mc.level;
        if (level == null) return LocalWind.OPEN;
        if (level != cached) {
            CACHE.clear();
            cached = level;
        }
        Air a = now();
        long turn = Math.floorMod(Math.round(a.wind().direction() / TURN), Math.round(2 * Math.PI / TURN));
        long key = (Math.floorDiv((long) Math.floor(x), CELL) & 0x3FFFFFL) << 42 | (Math.floorDiv((long) Math.floor(z), CELL) & 0x3FFFFFL) << 20 | turn;
        LocalWind site = CACHE.get(key);
        if (site == null) {
            double dir = turn * TURN;
            var heights = ua.zentix.incoming.client.far.FarTerrain.of(level);
            site = LocalWind.of(heights::at, x, z, Math.cos(dir), Math.sin(dir));
            CACHE.put(key, site);
        }
        return site;
    }

    /** Скорость ветра у земли места (x, z) на высоте y мира, м/с: ветер мира на высоте над землёй места и его поправка. */
    public static double speed(double x, double y, double z) {
        Air a = now();
        LocalWind site = site(x, z);
        double h = site.above(y);
        return a.wind().at(h, ua.zentix.incoming.util.Stability.alpha(a.stability())) * site.overGround(h);
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
