package ua.zentix.replayscan.film;

import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Collection;

/**
 * Incoming effects-layer smoke/fire on a sightline: eye-to-point transmission (1 clear, 0 blocked).
 * Layer includes mod particles outside game particle system and explosion smoke columns; block rays
 * cannot see them, so reflect their fields (no Incoming dependency; stable since 2.10): particles
 * {@code FxPool.INSTANCE}, {@code parts}, {@code count}; particle {@code x}, {@code y}, {@code z},
 * {@code quadSize} (quad half-size), {@code aCol}, {@code kind.additive}; columns {@code FarBlasts.EVENTS}
 * and {@code clock}; explosion {@code x}, {@code y}, {@code z}, {@code born}, {@code look}; billows
 * {@code f}, {@code ox}, {@code oz}, {@code size}. Package by version: {@code ua.zentix.incoming} since
 * 3.0, earlier {@code ua.zentix.airstrike}. Missing Incoming/fields disables smoke checks
 * ({@link #available()}), reported; missing column fields leaves particles only ({@link #columns()}).
 */
final class Smoke {
    /** Dense billow core fraction of half-size, as rendered ({@code FxParticle.DENSE}). */
    private static final double DENSE = 0.7;
    /** Column: billow opacity, axis spread in radii, wind drift ({@code FarBlasts}). */
    private static final double OPACITY = 0.85, SPREAD = 0.4;
    /** Smoke-filled frame: grid rays to this range, blocked if transmission below this share. */
    static final double SMOTHER_REACH = 40, SMOTHER_LIGHT = 0.4;
    /** Frame ray grid: fractions of frame half-width/height (3 × 5, same as pilot {@link Pilot#smother}). */
    static final double[] GRID_X = {-0.6, 0, 0.6}, GRID_Y = {-0.7, -0.35, 0, 0.35, 0.7};

    @Nullable
    private final Object pool;
    private Field parts, count, x, y, z, quad, alpha, kind, additive;
    /** Columns: explosion queue, clock, explosion/appearance fields. */
    @Nullable
    private Collection<?> events;
    private Field clock, evX, evY, evZ, born, look, pf, pox, poz, psize;
    private Method column, riseTicks, base, top, life, ballTicks;
    private double windX, windZ;

    Smoke() {
        Object found = null;
        for (String pkg : new String[]{"ua.zentix.incoming", "ua.zentix.airstrike"}) {
            try {
                Class<?> poolClass = Class.forName(pkg + ".client.fx.particle.FxPool");
                Class<?> part = Class.forName(pkg + ".client.fx.particle.FxParticle");
                Class<?> kindClass = Class.forName(pkg + ".client.fx.particle.Fx$Kind");
                parts = open(poolClass, "parts");
                count = open(poolClass, "count");
                x = open(part, "x");
                y = open(part, "y");
                z = open(part, "z");
                quad = open(part, "quadSize");
                alpha = open(part, "aCol");
                kind = open(part, "kind");
                additive = open(kindClass, "additive");
                found = poolClass.getField("INSTANCE").get(null);
                columns(pkg);
                break;
            } catch (ReflectiveOperationException | RuntimeException e) {
                found = null;
            }
        }
        pool = found;
    }

    /** {@code FarBlasts} column fields; absent means ignore columns. */
    private void columns(String pkg) {
        try {
            Class<?> far = Class.forName(pkg + ".client.far.FarBlasts");
            Class<?> event = Class.forName(pkg + ".client.far.FarBlasts$Event");
            Class<?> lookClass = Class.forName(pkg + ".client.far.FarBlasts$Look");
            Class<?> fx = Class.forName(pkg + ".client.fx.particle.Fx");
            Collection<?> queue = (Collection<?>) open(far, "EVENTS").get(null);
            clock = open(far, "clock");
            evX = open(event, "x");
            evY = open(event, "y");
            evZ = open(event, "z");
            born = open(event, "born");
            look = open(event, "look");
            pf = open(event, "f");
            pox = open(event, "ox");
            poz = open(event, "oz");
            psize = open(event, "size");
            column = lookClass.getMethod("column");
            riseTicks = lookClass.getMethod("riseTicks");
            base = lookClass.getMethod("base");
            top = lookClass.getMethod("top");
            life = lookClass.getMethod("life");
            ballTicks = lookClass.getMethod("ballTicks");
            windX = open(fx, "WIND_X").getDouble(null);
            windZ = open(fx, "WIND_Z").getDouble(null);
            events = queue;
        } catch (ReflectiveOperationException | RuntimeException e) {
            events = null;
        }
    }

    private static Field open(Class<?> c, String name) throws NoSuchFieldException {
        Field f = c.getDeclaredField(name);
        f.setAccessible(true);
        return f;
    }

    boolean available() {
        return pool != null;
    }

    /** Include explosion smoke columns. */
    boolean columns() {
        return events != null;
    }

    /**
     * Eye-to-point transmission through billows: a segment billow attenuates by opacity in its dense core,
     * fading to none at quad edge. Exclude additive particles and billows within {@code spare} of target
     * (projectile own trail/fireball). No effects layer means 1.
     */
    double transmittance(double ex, double ey, double ez, double tx, double ty, double tz, double spare) {
        return puffs(ex, ey, ez) ? light(ex, ey, ez, tx, ty, tz, spare) : 1;
    }

    /**
     * Share of frame rays (3 × 5 grid) smoke-blocked within {@link #SMOTHER_REACH} blocks: most of frame
     * filled by smoke at camera, as flybys entering smoke looked.
     */
    double smothered(Cameras.View v) {
        Vec3 e = v.eye();
        if (!puffs(e.x, e.y, e.z)) return 0;
        int n = 0, closed = 0;
        for (double sx : GRID_X) {
            for (double sy : GRID_Y) {
                Vec3 d = v.fwd().add(v.right().scale(sx * v.tanX())).add(v.up().scale(sy * v.tanY())).normalize().scale(SMOTHER_REACH);
                n++;
                if (light(e.x, e.y, e.z, e.x + d.x, e.y + d.y, e.z + d.z, 0) < SMOTHER_LIGHT) closed++;
            }
        }
        return (double) closed / n;
    }

    /** Frame smoke billows packed as x, y, z, half-size, opacity; shared render-thread buffer for all calls. */
    private double[] buf = new double[5 * 1024];
    private int held;

    /** Current billows in {@link #buf} (column evaluated for eye {@code e…}); false without effects layer. */
    private boolean puffs(double ex, double ey, double ez) {
        if (pool == null) return false;
        held = 0;
        try {
            Object[] all = (Object[]) parts.get(pool);
            int n = Math.min(count.getInt(pool), all.length);
            for (int i = 0; i < n; i++) {
                Object p = all[i];
                if (p == null) continue;
                float a = alpha.getFloat(p);
                if (a <= 0.004f || additive.getBoolean(kind.get(p))) continue;
                put(x.getDouble(p), y.getDouble(p), z.getDouble(p), quad.getFloat(p), a);
            }
        } catch (ReflectiveOperationException | RuntimeException e) {
            return false;
        }
        if (events != null) {
            int particles = held;
            try {
                columns(ex, ey, ez);
            } catch (ReflectiveOperationException | RuntimeException e) {
                // Cannot read columns: particles only, report smoke_columns false
                events = null;
                held = particles;
            }
        }
        return true;
    }

    private void put(double px, double py, double pz, double half, double a) {
        if (5 * held + 5 > buf.length) buf = Arrays.copyOf(buf, 2 * buf.length);
        int i = 5 * held++;
        buf[i] = px;
        buf[i + 1] = py;
        buf[i + 2] = pz;
        buf[i + 3] = half;
        buf[i + 4] = a;
    }

    /** Eye-to-point segment transmission through {@link #buf} billows. */
    private double light(double ex, double ey, double ez, double tx, double ty, double tz, double spare) {
        double dx = tx - ex, dy = ty - ey, dz = tz - ez, len2 = dx * dx + dy * dy + dz * dz;
        double light = 1;
        for (int i = 0; i < 5 * held; i += 5) {
            light *= 1 - buf[i + 4] * cover(ex, ey, ez, dx, dy, dz, len2, buf[i], buf[i + 1], buf[i + 2], buf[i + 3], tx, ty, tz, spare);
        }
        return light;
    }

    /**
     * {@code FarBlasts.column} billows follow render numbers (rise, spreading, drift, bottom-up fade,
     * appear as ball fades); billows containing the eye fade as in rendering.
     */
    private void columns(double ex, double ey, double ez) throws ReflectiveOperationException {
        double now = clock.getLong(null);
        for (Object e : events.toArray()) {
            double age = Math.max(0, now - born.getLong(e));
            Object k = look.get(e);
            double lifeT = ((Number) life.invoke(k)).doubleValue();
            if (age > lifeT) continue;
            double rise = 1 - Math.exp(-3 * age / ((Number) riseTicks.invoke(k)).doubleValue());
            double ball = ((Number) ballTicks.invoke(k)).doubleValue(), from = Math.ceil(0.55 * ball);
            double emerge = Plume.smoothstep(from, Math.max(from + 8, ball), age);
            if (emerge <= 0) continue;
            double col = ((Number) column.invoke(k)).doubleValue(), b0 = ((Number) base.invoke(k)).doubleValue(), b1 = ((Number) top.invoke(k)).doubleValue();
            double bx = evX.getDouble(e), by = evY.getDouble(e), bz = evZ.getDouble(e);
            double windDx = windX * Plume.DRIFT * age, windDz = windZ * Plume.DRIFT * age;
            double spread = 1 + 0.5 * age / lifeT, grow = 0.35 + 0.65 * rise;
            float[] f = (float[]) pf.get(e), ox = (float[]) pox.get(e), oz = (float[]) poz.get(e), size = (float[]) psize.get(e);
            for (int i = 0; i < f.length; i++) {
                double a = OPACITY * emerge * (1 - Plume.smoothstep(lifeT * (0.35 + 0.45 * f[i]), lifeT, age));
                if (a < 0.004) continue;
                double r = (b0 + (b1 - b0) * f[i]) * size[i] * grow * spread, h = 0.6 * r + f[i] * col * rise, shear = 0.3 + 0.7 * f[i] * rise;
                double px = bx + ox[i] * r * SPREAD + windDx * shear, py = by + h, pz = bz + oz[i] * r * SPREAD + windDz * shear;
                double d = Math.sqrt((px - ex) * (px - ex) + (py - ey) * (py - ey) + (pz - ez) * (pz - ez));
                a *= Plume.smoothstep(0.5 * r, r, d);
                if (a >= 0.004) put(px, py, pz, r, a);
            }
        }
    }

    /**
     * How much a billow at {@code p} with half-size {@code half} covers eye segment (0 misses, 1 through
     * centre). Camera-facing quad through its centre ({@code FxQuads.billboard}): centre behind eye means
     * quad behind camera, invisible.
     */
    static double cover(double ex, double ey, double ez, double dx, double dy, double dz, double len2,
                                double px, double py, double pz, double half, double tx, double ty, double tz, double spare) {
        double ox = px - tx, oy = py - ty, oz = pz - tz;
        if (ox * ox + oy * oy + oz * oz < spare * spare) return 0;
        double along = (px - ex) * dx + (py - ey) * dy + (pz - ez) * dz;
        if (along <= 0) return 0;
        double u = len2 < 1e-9 ? 0 : Math.min(1, along / len2);
        double cx = ex + dx * u - px, cy = ey + dy * u - py, cz = ez + dz * u - pz;
        double miss = Math.sqrt(cx * cx + cy * cy + cz * cz), dense = half * DENSE;
        if (miss >= half) return 0;
        return miss <= dense ? 1 : 1 - (miss - dense) / (half - dense);
    }
}
