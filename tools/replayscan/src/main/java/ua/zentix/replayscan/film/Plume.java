package ua.zentix.replayscan.film;

import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

/**
 * Ground-impact smoke over time follows Incoming visuals code (same in 2.10/3.x), not guessed shape:
 * explosion particles ({@code Explosions.burst}, {@code BlastEffects.burn}) form cooling-ball cloud,
 * ground dust ring, crater afterburn smoke; smoke/dust column ({@code FarBlasts.column}) lasts minutes,
 * rises a hundred-plus blocks, spreads and drifts downwind. Particle motion follows mod
 * ({@code FxParticle.tick}: rise/wind, step, drag), same half-size growth/fade; randomness uses spread
 * edge, column uses centre plus billow-spread margin. Model shapes with opacity ({@link Shape}): ball
 * cloud/column billows are spheres, crater smoke a sphere chain, dust ring a cylinder; boundaries are
 * dense billow cores (0,7 half-size as rendered). Flybys 13.10 (9 flights, frames versus model) distinguish
 * smoke-obscured/clear frames: camera inside shape or forward view into it catches 86/87 obscured and
 * 2/193 clear frames. Previous model (radius 8, life 25 s) missed the column and halved billows, sending
 * the drone into smoke just after impact. {@link Pilot} and camera scoring avoid model smoke; export
 * measures actual mod smoke ({@link Smoke}).
 *
 * @param t impact tick
 * @param pos impact position
 * @param size fireball diameter, blocks (camera distance to impact)
 * @param look mod explosion appearance, determines particles/column
 */
record Plume(int t, Vec3 pos, double size, Look look) {
    /** Ball diameter if unknown to plan (older-minute impacts only have positions). */
    static final double SIZE = 16;
    /** Effects-layer wind, blocks/tick² ({@code Fx.WIND_X}, {@code WIND_Z}); column drift is 20 times stronger (drag 0,95). */
    static final double WIND_X = 0.0022, WIND_Z = 0.0011, DRIFT = 1 / (1 - 0.95);
    /** Dense billow core fraction of half-size ({@code FxParticle.DENSE}). */
    static final double CORE = 0.7;
    /** Count a shape if its opacity exceeds this. */
    static final double SEEN = 0.3;

    /**
     * Explosion appearance ({@code S2C.Blast}) and smoke: nearby particle-ball radius ({@code BlastEffects}:
     * Shahed/MLRS 5, missile 8,5, bomb 11), crater afterburn ticks, column (ball glowing ticks, height, rise
     * ticks, billow radius at base/cap, count, life). Numbers from old mod visuals (before 3.5.0:
     * {@code FarBlasts} column table); clouds now use {@code CloudModel}, this approximates smoke location
     * for filming. Bomb threshold ({@code warhead.BuriedBlast}; 2,4 t at 13,4 m below top): shallower than
     * breakout gives ball/dust/higher cloud; deeper gives vent gas cloud only, no ball. Bomb smoke sits above
     * top surface, not at charge.
     */
    enum Look {
        ROCKET(5, 220, 8, 105, 2400, 7, 26, 10, 3000),
        DRONE(5, 220, 13, 150, 2400, 10, 40, 12, 3600),
        MISSILE(8.5, 320, 30, 270, 2400, 18, 70, 14, 4800),
        BUNKER(11, 0, 20, 210, 2400, 18, 55, 14, 4200),
        BUNKER_DEEP(0, 0, 0, 110, 500, 14, 32, 12, 3000);

        final double r, column, base, top;
        final int fire, ball, riseTicks, puffs, life;

        Look(double r, int fire, int ball, double column, int riseTicks, double base, double top, int puffs, int life) {
            this.r = r;
            this.fire = fire;
            this.ball = ball;
            this.column = column;
            this.riseTicks = riseTicks;
            this.base = base;
            this.top = top;
            this.puffs = puffs;
            this.life = life;
        }

        /**
         * Plan smoke-row number ({@code plan.py}): 1 MLRS, 2 Shahed, 3 missile, 4 bomb breakout,
         * 5 deeper bomb; 0/other absent.
         */
        static Look of(int code) {
            return switch (code) {
                case 1 -> ROCKET;
                case 2 -> DRONE;
                case 3 -> MISSILE;
                case 4 -> BUNKER;
                case 5 -> BUNKER_DEEP;
                default -> null;
            };
        }

        /** By ball diameter for older plans without appearance: MLRS 8, Shahed 12, unknown 16, missile 18, bomb 24. */
        static Look bySize(double size) {
            return size < 10 ? ROCKET : size < 17 ? DRONE : size < 21 ? MISSILE : BUNKER;
        }

        /** Tick ball smoke began appearing in old visuals (0,55 ball lifetime). */
        int smokeFrom() {
            return (int) Math.ceil(0.55 * ball);
        }
    }

    /**
     * Smoke shape: sphere (centre, radius) or vertical cylinder ({@code cylinder}: x/z axis,
     * from {@code y} to {@code top}); opacity 0..1.
     */
    record Shape(double x, double y, double z, double r, double top, boolean cylinder, double alpha) {
        /** Distance to shape (less than 0 inside). */
        double distance(double px, double py, double pz) {
            if (!cylinder) {
                double dx = px - x, dy = py - y, dz = pz - z;
                return Math.sqrt(dx * dx + dy * dy + dz * dz) - r;
            }
            double h = Math.sqrt((px - x) * (px - x) + (pz - z) * (pz - z)) - r, v = Math.max(y - py, py - top);
            if (h <= 0 && v <= 0) return Math.max(h, v);
            return Math.sqrt(Math.max(0, h) * Math.max(0, h) + Math.max(0, v) * Math.max(0, v));
        }

        /** Segment intersects shape. */
        boolean crosses(Vec3 from, Vec3 to) {
            double dx = to.x - from.x, dy = to.y - from.y, dz = to.z - from.z;
            if (!cylinder) {
                double len2 = dx * dx + dy * dy + dz * dz;
                double u = len2 < 1e-9 ? 0 : Mth.clamp(((x - from.x) * dx + (y - from.y) * dy + (z - from.z) * dz) / len2, 0, 1);
                double cx = from.x + dx * u - x, cy = from.y + dy * u - y, cz = from.z + dz * u - z;
                return cx * cx + cy * cy + cz * cz < r * r;
            }
            // Segment part within cylinder heights; closest horizontal point to axis along it
            double u0 = 0, u1 = 1;
            if (Math.abs(dy) < 1e-9) {
                if (from.y < y || from.y > top) return false;
            } else {
                double a = (y - from.y) / dy, b = (top - from.y) / dy;
                u0 = Math.max(0, Math.min(a, b));
                u1 = Math.min(1, Math.max(a, b));
                if (u0 > u1) return false;
            }
            double len2 = dx * dx + dz * dz;
            double u = len2 < 1e-9 ? u0 : Mth.clamp(((x - from.x) * dx + (z - from.z) * dz) / len2, u0, u1);
            double cx = from.x + dx * u - x, cz = from.z + dz * u - z;
            return cx * cx + cz * cz < r * r;
        }
    }

    static Plume of(double[] row) {
        double size = row.length > 4 ? row[4] : SIZE;
        Look look = row.length > 5 ? Look.of((int) row[5]) : Look.bySize(size);
        return new Plume((int) row[0], new Vec3(row[1], row[2], row[3]), size, look);
    }

    /** Smoke exists after impact until column fades (smokeless appearance is nuclear, with separate visuals). */
    boolean alive(double at) {
        double u = at - t;
        return look != null && u >= 0 && u <= look.life;
    }

    /** Smoke shapes at tick {@code at}, including opacity below {@link #SEEN}. */
    List<Shape> shapes(double at) {
        List<Shape> out = new ArrayList<>();
        shapes(at, out);
        return out;
    }

    void shapes(double at, List<Shape> out) {
        if (!alive(at)) return;
        double u = at - t, r = look.r;
        int from = look.smokeFrom();
        if (r > 0 && u < BALL_LIFE) {
            // Ball cloud: billows from 0,3 R above impact spread up to 0,11 R/tick (drag 0,9), rise and drift
            double cy = pos.y + 0.35 * r + 0.3 * r * 0.55 + (0.11 + 0.55 * 0.08 * r) * BALL.move(u) + BALL.rise(u);
            double reach = 0.3 * r + 0.11 * r * BALL.move(u), half = r * (0.55 + 1.4 * grow4(u / BALL_MEAN));
            double in = u < from ? 0 : Math.min(1, (u - from) / Math.max(1, look.ball - from));
            out.add(new Shape(pos.x + WIND_X * BALL.wind(u), cy, pos.z + WIND_Z * BALL.wind(u), reach + CORE * half, 0, false,
                    0.92 * in * fade(u / BALL_MEAN, 0.45)));
        }
        if (r > 0 && u < DUST_LIFE) {
            // Dust ring: billows from 0,5 R spread along ground up to 0,35 R/tick (drag 0,88)
            double half = r * (0.35 + 0.8 * grow4(u / DUST_MEAN));
            out.add(new Shape(pos.x + WIND_X * DUST.wind(u), pos.y, pos.z + WIND_Z * DUST.wind(u), 0.5 * r + 0.35 * r * DUST.move(u) + CORE * half,
                    pos.y + 0.6 + 0.07 * DUST.move(u) + DUST.rise(u) + CORE * half, true, 0.8 * fade(u / DUST_MEAN, 0.4)));
        }
        if (look.fire > 0 && u < look.fire + BURN_LIFE) {
            // Crater smoke: one billow per burning tick, tilted column from youngest to oldest
            double lo = Math.max(0, u - look.fire), hi = Math.min(u, BURN_LIFE);
            double half = r * (0.12 + 0.48 * grow3(hi / BURN_MEAN)), y0 = pos.y + 0.3 + 0.15 * r;
            double alpha = 0.6 * Math.min(1, 1.5 * (1 - lo / look.fire));
            for (int k = 0; k < 4; k++) {
                double v = lo + (hi - lo) * k / 3;
                out.add(new Shape(pos.x + WIND_X * BURN.wind(v), y0 + 0.1 * BURN.move(v) + BURN.rise(v), pos.z + WIND_Z * BURN.wind(v),
                        0.8 * r + CORE * half, 0, false, alpha * fade(v / BURN_MEAN, 0.4)));
            }
        }
        // FarBlasts column: billows by height fraction f, appearing as ball fades, fading bottom-up
        double rise = 1 - Math.exp(-3 * u / look.riseTicks), emerge = smoothstep(from, Math.max(from + 8, look.ball), u);
        double spread = 1 + 0.5 * u / look.life, grow = 0.35 + 0.65 * rise;
        for (int i = 0; i < look.puffs; i++) {
            double f = Math.pow((i + 0.5) / look.puffs, 1.3);
            double alpha = 0.85 * emerge * (1 - smoothstep(look.life * (0.35 + 0.45 * f), look.life, u));
            double pr = (look.base + (look.top - look.base) * f) * grow * spread, h = 0.6 * pr + f * look.column * rise;
            double shear = 0.3 + 0.7 * f * rise;
            // Billow size up to 1,15 nominal; centre offset from column axis up to 0,49 radius for 9/10 billows
            out.add(new Shape(pos.x + WIND_X * DRIFT * u * shear, pos.y + h, pos.z + WIND_Z * DRIFT * u * shear, pr * (1.15 * CORE + 0.49), 0, false, alpha));
        }
    }

    /** Point distance to smoke (less than 0 inside); no smoke this tick means infinity. */
    double clearance(Vec3 p, double at) {
        double best = Double.POSITIVE_INFINITY;
        if (!alive(at)) return best;
        for (Shape s : shapes(at)) {
            if (s.alpha() >= SEEN) best = Math.min(best, s.distance(p.x, p.y, p.z));
        }
        return best;
    }

    /**
     * Sightline from {@code from} to {@code to} intersects smoke. Exclude target own smoke: impact within
     * {@link #OWN} blocks belongs to target (its fireball/smoke are what camera watches).
     */
    boolean blocks(Vec3 from, Vec3 to, double at) {
        if (!alive(at) || own(to)) return false;
        for (Shape s : shapes(at)) {
            if (s.alpha() >= 0.5 && s.crosses(from, to)) return true;
        }
        return false;
    }

    /** Target own smoke: impact closer than this. */
    static final double OWN = 12;

    /**
     * Points within horizontal {@code d}: bomb smoke sits above subsidence, while plan impact is charge
     * tens of blocks below in solid material.
     */
    static boolean beside(Vec3 a, Vec3 b, double d) {
        double dx = a.x - b.x, dz = a.z - b.z;
        return dx * dx + dz * dz <= d * d;
    }

    /** This impact smoke belongs to target {@code to} if beside/below it (bomb charge), not a projectile high above. */
    boolean own(Vec3 to) {
        return beside(pos, to, OWN) && to.y <= pos.y + OWN;
    }

    // ---------------------------------------------------------------- mod particles

    /** Billow lifetimes, ticks: ball cloud 420–720, dust ring 200–360, crater smoke 140–220; midpoints for growth/fade. */
    static final int BALL_LIFE = 720, DUST_LIFE = 360, BURN_LIFE = 220;
    static final double BALL_MEAN = 570, DUST_MEAN = 280, BURN_MEAN = 180;
    /** Three particle motion kinds: drag/rise ({@code Fx.Spec.drag}, {@code rise}). */
    static final Motion BALL = new Motion(0.9, 0.011, BALL_LIFE), DUST = new Motion(0.88, 0.002, DUST_LIFE), BURN = new Motion(0.96, 0.006, BURN_LIFE);

    /**
     * Particle path over {@code age} mod ticks: velocity += rise (stronger while hot) and wind, position +=
     * velocity, velocity *= drag. Tables for initial velocity 1, wind 1/tick² and rise displacement.
     */
    static final class Motion {
        private final double[] move, wind, rise;

        Motion(double drag, double up, int life) {
            move = new double[life + 1];
            wind = new double[life + 1];
            rise = new double[life + 1];
            double v0 = 1, vw = 0, vr = 0;
            for (int age = 1; age <= life; age++) {
                vw += 1;
                vr += up * (0.15 + 0.85 * Math.exp(-age / 40.0));
                move[age] = move[age - 1] + v0;
                wind[age] = wind[age - 1] + vw;
                rise[age] = rise[age - 1] + vr;
                v0 *= drag;
                vw *= drag;
                vr *= drag;
            }
        }

        double move(double age) {
            return at(move, age);
        }

        double wind(double age) {
            return at(wind, age);
        }

        double rise(double age) {
            return at(rise, age);
        }

        private static double at(double[] v, double age) {
            double u = Mth.clamp(age, 0, v.length - 1);
            int i = Math.min(v.length - 2, (int) u);
            return v[i] + (v[i + 1] - v[i]) * (u - i);
        }
    }

    /** Half-size growth toward end of life ({@code FxParticle.update}): fast 1 − (1 − f)⁴, normal 1 − (1 − f)³. */
    static double grow4(double f) {
        double g = 1 - Mth.clamp(f, 0, 1);
        return 1 - g * g * g * g;
    }

    static double grow3(double f) {
        double g = 1 - Mth.clamp(f, 0, 1);
        return 1 - g * g * g;
    }

    /** Fade from lifetime fraction {@code from} to end. */
    static double fade(double f, double from) {
        return f < from ? 1 : Math.max(0, 1 - (f - from) / (1 - from));
    }

    static double smoothstep(double e0, double e1, double x) {
        if (e1 <= e0) return x >= e1 ? 1 : 0;
        double u = Mth.clamp((x - e0) / (e1 - e0), 0, 1);
        return u * u * (3 - 2 * u);
    }
}
