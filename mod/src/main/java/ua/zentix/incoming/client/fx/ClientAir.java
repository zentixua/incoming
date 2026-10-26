package ua.zentix.incoming.client.fx;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import ua.zentix.incoming.util.Air;
import ua.zentix.incoming.util.LocalWind;

/**
 * Current client world air ({@link Air#of(net.minecraft.world.level.Level)} — the same as on the server): world wind by
 * game time, stability class by sun, wind and rain, humidity. Once per tick: particle smoke, trail ribbons and volume
 * billows all take it from here.
 */
public final class ClientAir {
    private static long tick = Long.MIN_VALUE;
    private static Air now = Air.CALM;

    private ClientAir() {}

    /** Current air; no world (or client, in fire-source unit tests) — calm. */
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

    /** Ground-wind sites ({@link #site}) retained in memory. */
    private static final int SITES = 256;
    /** Site cell, blocks, and wind-direction step, rad: closer values are the same site. */
    private static final int CELL = 4;
    private static final double TURN = Math.PI / 16;
    private static final java.util.Map<Long, LocalWind> CACHE = new java.util.LinkedHashMap<>(64, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(java.util.Map.Entry<Long, LocalWind> e) {
            return size() > SITES;
        }
    };
    /** World owning cached sites: weak reference avoids retaining it after returning to the menu. */
    private static java.lang.ref.WeakReference<ClientLevel> cached = new java.lang.ref.WeakReference<>(null);

    /**
     * Ground wind at (x, z) ({@link LocalWind}) from client chunks and map terrain tiles under current world wind; once per
     * {@value #CELL}-block cell and direction in π/16 steps (a Grad salvo does not recompute the site). No world — open field.
     * {@link LocalWind#base} belongs to the cell rather than the point: sources use {@link #site(double, double, double)}.
     */
    public static LocalWind site(double x, double z) {
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc == null ? null : mc.level;
        if (level == null) return LocalWind.OPEN;
        if (level != cached.get()) {
            CACHE.clear();
            cached = new java.lang.ref.WeakReference<>(level);
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

    /** Ground wind at site (x, z), measured from the surface at world height y (below the blast). */
    public static LocalWind site(double x, double y, double z) {
        return site(x, z).from(y);
    }

    /** Ground-wind speed at site (x, z) and world height y, m/s: world wind at height above the site ground, with its correction. */
    public static double speed(double x, double y, double z) {
        Air a = now();
        LocalWind site = site(x, z);
        double h = site.above(y);
        return a.wind().at(h, ua.zentix.incoming.util.Stability.alpha(a.stability())) * site.overGround(h);
    }

    /** Wind along x at world height y, blocks per tick. */
    public static double windX(double y) {
        Air a = now();
        return a.speed(y) * a.wind().x() / 20;
    }

    /** Wind along z at world height y, blocks per tick. */
    public static double windZ(double y) {
        Air a = now();
        return a.speed(y) * a.wind().z() / 20;
    }
}
