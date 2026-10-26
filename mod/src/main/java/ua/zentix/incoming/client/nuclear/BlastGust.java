package ua.zentix.incoming.client.nuclear;

import net.minecraft.world.phys.Vec3;
import ua.zentix.incoming.client.fx.Fragments;
import ua.zentix.incoming.nuclear.Detonation;

import java.util.function.DoubleSupplier;

/**
 * Wind behind the shock front at one location — the same law that carries wall-head dust ({@link BlastWall#carried}):
 * along the ground away from ground zero, immediately behind the front {@link Detonation#blastWind} (Rankine–Hugoniot × √along-ground dynamic-pressure share),
 * then Friedlander u(t) = U(1 − t/t₊)e^(−t/t₊) (Glasstone and Dolan 1977, §3.57), where t₊ is the local positive phase
 * ({@link Detonation#positivePhaseTicks}); reversed and weaker in the negative phase (maximum e^(−2) of U at 2t₊).
 * Air displacement since arrival — ∫u dt = U·t₊·x·e^(−x), x = t/t₊: U·t₊/e at phase end ({@link BlastWall#CARRY}), then back.
 * It carries demolished pieces ({@link Fragments}) and breach dust ({@link BlastDust}) near the camera. Wind is sampled where
 * they were torn away: pieces and dust travel tens of blocks, over which pressure barely changes (⚠ approximation).
 * <p>
 * No wind ahead of the front: air behind the shock is always slower than the shock itself, but the distant game front travels at
 * {@code nuclear.wave_speed} (default 0.2 of sound speed, {@link Detonation#wave}), while wind and phase are real, so air in a strong
 * wave would overtake the front. Thus wind is capped at game front speed, displacement cannot pass the front, and wind does not act on anything already
 * ahead of it (⚠ not physical — consequence of game wave speed; changes nothing for a wave at real speed).
 * <p>
 * Game blocks and ticks: displacement in blocks, speed in blocks per tick (one model meter is {@code scale} blocks; one model second is
 * {@code 20·scale} ticks, so speed in blocks per tick is U/20 at any scale); time is ticks since detonation.
 */
public final class BlastGust implements Fragments.Wind {
    private final Detonation d;
    /** Game-front arrival belongs to the detonation ({@link Detonation#front}): table, not a cache lookup on each call. */
    private final ua.zentix.incoming.nuclear.model.FrontProfile front;
    /** Slant range from detonation to the location, blocks. */
    private final double slant;
    /** Front arrival and positive phase, ticks since detonation; wind immediately behind the front, blocks per tick; direction from ground zero. */
    final double arrival, phase, u0, ax, az;
    private final DoubleSupplier clock;
    /** Farthest air position ({@link #travel}): time in ticks since detonation, displacement in blocks; calculated on first request. */
    private double peakTicks = Double.NaN, peakPath;

    private BlastGust(Detonation d, Vec3 at, DoubleSupplier clock) {
        this.d = d;
        this.front = d.front();
        this.slant = at.distanceTo(d.burst());
        double dx = at.x - d.burst().x, dz = at.z - d.burst().z, len = Math.sqrt(dx * dx + dz * dz);
        this.ax = len < 1e-3 ? 1 : dx / len;
        this.az = len < 1e-3 ? 0 : dz / len;
        this.arrival = d.arrivalTicks(slant);
        this.phase = Math.max(d.positivePhaseTicks(slant), 1e-3);
        this.u0 = d.blastWind(at) / 20;
        this.clock = clock;
    }

    /**
     * Wind from detonation {@code a}'s wave at {@code at} — using the client detonation clock found by ID: world entry,
     * respawn and replay seek replace its record ({@code ClientNuclear.sync}), while flying pieces remain; with the old
     * clock their wind would freeze for about ten seconds. No detonation means no wind.
     */
    static BlastGust at(ClientNuclear.Active a, Vec3 at) {
        int id = a.d.id();
        return of(a.d, at, () -> ClientNuclear.ticksOf(id));
    }

    /** Wind from detonation {@code d}'s wave at {@code at}; {@code clock} is ticks since detonation. */
    public static BlastGust of(Detonation d, Vec3 at, DoubleSupplier clock) {
        return new BlastGust(d, at, clock);
    }

    /** Fraction of the positive phase since front arrival at {@code ticks} (0 before arrival). */
    double x(double ticks) {
        return Math.max(0, ticks - arrival) / phase;
    }

    /**
     * Air speed along the ground away from ground zero, blocks per tick (negative in the negative phase; 0 before the front), capped at
     * game front speed.
     */
    double speed(double ticks) {
        double x = x(ticks);
        if (x <= 0) return 0;
        double u = u0 * (1 - x) * Math.exp(-x);
        return Math.min(u, front.radiusAt(ticks + 0.5) - front.radiusAt(ticks - 0.5));
    }

    /**
     * Air displacement away from ground zero since front arrival, blocks: U·t₊·x·e^(−x) (returns during the negative phase), capped at the game
     * front.
     */
    double path(double ticks) {
        double x = x(ticks);
        if (x <= 0) return 0;
        return Math.min(u0 * phase * x * Math.exp(-x), Math.max(0, front.radiusAt(ticks) - slant));
    }

    /**
     * Total air distance traveled outward and back, blocks (nondecreasing): displacement up to the farthest position, then the entire
     * return trip too. Displacement rises once and falls once (Friedlander wind changes sign once; the front only recedes),
     * so there is one farthest position ({@link #peak}).
     */
    double travel(double ticks) {
        if (Double.isNaN(peakTicks)) peak();
        return ticks <= peakTicks ? path(ticks) : 2 * peakPath - path(ticks);
    }

    /** Time and displacement of the farthest air position: ternary search (unimodal displacement) from arrival to three phases. */
    private void peak() {
        double lo = arrival, hi = arrival + 3 * phase;
        for (int i = 0; i < 60; i++) {
            double m1 = lo + (hi - lo) / 3, m2 = hi - (hi - lo) / 3;
            if (path(m1) < path(m2)) lo = m1;
            else hi = m2;
        }
        peakTicks = 0.5 * (lo + hi);
        peakPath = path(peakTicks);
    }

    @Override
    public void at(double x, double y, double z, double[] into) {
        double now = clock.getAsDouble(), u = Double.isNaN(now) ? 0 : speed(now);
        // air ahead of the front is still
        double bx = x - d.burst().x, by = y - d.burst().y, bz = z - d.burst().z, r = u == 0 ? 0 : front.radiusAt(now);
        if (bx * bx + by * by + bz * bz > r * r) u = 0;
        into[0] = ax * u;
        into[1] = u;
        into[2] = az * u;
    }
}
