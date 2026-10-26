package ua.zentix.incoming.client.flight;

import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import ua.zentix.incoming.client.ClientWeaponSpec;
import ua.zentix.incoming.client.sound.Acoustics;
import ua.zentix.incoming.defense.InterceptorSpec;
import ua.zentix.incoming.entity.BunkerBusterEntity;
import ua.zentix.incoming.entity.FlightPhase;
import ua.zentix.incoming.entity.InterceptorEntity;
import ua.zentix.incoming.entity.StrikeProjectile;
import ua.zentix.incoming.net.S2C;
import ua.zentix.incoming.strike.Hearing;
import ua.zentix.incoming.strike.WeaponType;

import java.util.UUID;

/**
 * Client projectile flight history (by tick), shared by sound and distant visuals. Sound takes “retarded”
 * position and velocity; far view ({@code client.far.FarFlightView}) takes current position, model rotation and
 * trail path. Velocity is server-reported ({@link StrikeProjectile#velocity()}), not position difference by
 * client ticks: one server tick's packets arrive in the same or neighbouring client ticks, making that
 * difference alternate between zero and double. Recorded from entity while client has it, otherwise server
 * packets ({@link S2C.FarFlights}; intermediate ticks linearly between). Outlives entity: after explosion distant
 * listeners still hear engine for a while, and trail persists.
 */
public final class FlightTrack implements Acoustics.Path {
    /**
     * History ticks: sound travels to ear for at most {@link Hearing#MAX_DELAY} (server sends no path beyond
     * {@link Hearing#RANGE}), plus margin for uneven packet arrival.
     */
    private static final int CAPACITY = Hearing.MAX_DELAY + 64;
    /** Longer data gap means another flight in hearing range: restart history. */
    private static final int MAX_GAP = 8;

    public final UUID id;
    /** Weapon; null — SAM missile ({@link #interceptor}): separate path registry ({@link FlightTracks#interceptors}). */
    @Nullable
    public final WeaponType weapon;
    /** B-2 (its bomb has same weapon, different sound and appearance). */
    public final boolean bomber;
    private final double[] xs = new double[CAPACITY], ys = new double[CAPACITY], zs = new double[CAPACITY];
    private final double[] vxs = new double[CAPACITY], vys = new double[CAPACITY], vzs = new double[CAPACITY];
    private final int[] phases = new int[CAPACITY], phaseAges = new int[CAPACITY];
    private final double[] axs = new double[CAPACITY], ays = new double[CAPACITY], azs = new double[CAPACITY];
    private final float[] yaws = new float[CAPACITY], pitches = new float[CAPACITY], rolls = new float[CAPACITY];
    /** Point from server packets (client had no entity): distant trail drawn only from these. */
    private final boolean[] servers = new boolean[CAPACITY];
    private long first = -1, last = -1;
    /** Path point before last (line to last built from it). */
    private long previous = -1;
    private long death = Long.MAX_VALUE;
    /** Server confirmed end: a delayed entity view cannot revive this UUID. */
    private boolean confirmedEnd;
    /** Maximum speed in entire history, blocks/tick: from velocity and between-tick displacement. */
    private double maxSpeed;
    /**
     * Ticks after last sample for which sound may extrapolate: entity sampled every tick, so 1; packets until
     * next packet (plus a tick for uneven delivery). Beyond, no data (exploded or left hearing range): layer silent.
     */
    private int slack = 1;
    /** Last sample from entity. */
    private boolean fromEntity;
    /** Bomb is drilling. */
    private boolean drilling;
    /** Launch ground under projectile from server chunk (last packet containing it, {@link S2C.FarFlight#ground}); NaN — none. */
    private double launchGround = Double.NaN;

    public FlightTrack(UUID id, @Nullable WeaponType weapon, boolean bomber) {
        this.id = id;
        this.weapon = weapon;
        this.bomber = bomber;
    }

    /** SAM missile path: no weapon assigned, look and sound from SAM spec. */
    public static FlightTrack interceptor(UUID id) {
        return new FlightTrack(id, null, false);
    }

    /**
     * Sample from client's SAM missile view: displacement per tick from previous position, boost phase while
     * boost engine burns ({@link InterceptorSpec#boostTicks}), sustain afterward. No target point: path is not
     * cut short ahead.
     */
    public void record(long tick, InterceptorEntity e) {
        Vec3 v = e.position().subtract(e.xo, e.yo, e.zo);
        int age = e.age();
        boolean boost = age <= InterceptorSpec.SAM.boostTicks();
        append(tick, new Sample(e.position(), v, e.getYRot(), e.getXRot(), 0, (boost ? FlightPhase.BOOST : FlightPhase.CRUISE).ordinal(),
                boost ? age : age - InterceptorSpec.SAM.boostTicks(), e.position().add(v.scale(Hearing.MAX_DELAY)), false));
        fromEntity = true;
        slack = 1;
    }

    /** Sample from client entity. */
    public void record(long tick, StrikeProjectile p) {
        append(tick, new Sample(p.position(), p.velocity(), p.getYRot(), p.getXRot(), p.roll(), p.flightPhase().ordinal(), p.phaseAge(),
                p.aimPoint(), false));
        fromEntity = true;
        slack = 1;
        drilling = p instanceof BunkerBusterEntity b && b.isDrilling();
    }

    /**
     * Sample from server packet. Two packets in one tick (network batch): latest wins; a tick recorded from
     * entity does not need a packet.
     */
    public void record(long tick, S2C.FarFlight f) {
        if (tick < last || tick == last && fromEntity) return;
        append(tick, new Sample(f.pos(), f.velocity(), f.yaw(), f.pitch(), f.roll(), f.phase(), f.phaseAge(), f.aim(), true));
        fromEntity = false;
        slack = f.period() + 2;
        drilling = f.drilling();
        if (Float.isFinite(f.ground())) launchGround = f.ground();
    }

    /**
     * Launch ground under projectile from server chunk (first air), from packets; NaN — server did not send it
     * (projectile was client entity or server launch-site chunk not ready).
     */
    public double launchGround() {
        return launchGround;
    }

    /**
     * New path point; missing ticks before it interpolate linearly (angles by shortest turn), long gap restarts
     * history. Same-tick point corrects last: rebuild line from previous point.
     */
    private void append(long tick, Sample s) {
        if (tick < last) return;
        if (tick > last) previous = last;
        long from = previous;
        if (from >= 0 && tick - from > MAX_GAP) first = tick;
        else if (from >= 0 && tick - from > 1) {
            int i0 = (int) (from % CAPACITY);
            Vec3 p0 = new Vec3(xs[i0], ys[i0], zs[i0]), v0 = new Vec3(vxs[i0], vys[i0], vzs[i0]);
            float yaw0 = yaws[i0], pitch0 = pitches[i0], roll0 = rolls[i0];
            for (long t = from + 1; t < tick; t++) {
                double k = (double) (t - from) / (tick - from);
                float f = (float) k;
                put(t, new Sample(p0.lerp(s.pos, k), v0.lerp(s.vel, k), Mth.rotLerp(f, yaw0, s.yaw), Mth.lerp(f, pitch0, s.pitch),
                        Mth.rotLerp(f, roll0, s.roll), s.phase, s.phaseAge - (int) (tick - t), s.aim, s.server));
            }
        }
        put(tick, s);
    }

    private void put(long tick, Sample s) {
        if (first < 0) first = tick;
        // displacement from previous point (same-tick correction: from point before it), blocks per tick
        long from = tick > last ? last : previous;
        if (from >= 0 && from >= first && tick > from) {
            int j = (int) (from % CAPACITY);
            maxSpeed = Math.max(maxSpeed, Math.sqrt(sq(s.pos.x - xs[j]) + sq(s.pos.y - ys[j]) + sq(s.pos.z - zs[j])) / (tick - from));
        }
        maxSpeed = Math.max(maxSpeed, s.vel.length());
        last = tick;
        int i = (int) (tick % CAPACITY);
        xs[i] = s.pos.x;
        ys[i] = s.pos.y;
        zs[i] = s.pos.z;
        vxs[i] = s.vel.x;
        vys[i] = s.vel.y;
        vzs[i] = s.vel.z;
        axs[i] = s.aim.x;
        ays[i] = s.aim.y;
        azs[i] = s.aim.z;
        yaws[i] = s.yaw;
        pitches[i] = s.pitch;
        rolls[i] = s.roll;
        phases[i] = s.phase;
        phaseAges[i] = Math.max(0, s.phaseAge);
        servers[i] = s.server;
    }

    /**
     * Sound at time t still has data to drive it: data arrived at most {@link #slack} ticks earlier; ended
     * flight up to its end ({@link #impact}).
     */
    public boolean covers(double t) {
        return t <= (isDead() ? Math.min(death, last + MAX_GAP) : last + slack);
    }

    /**
     * Projectile exploded: blast packet arrived at {@code tick}, flight ended then too. Distant projectile
     * packets arrive every 2–4 ticks, unevenly, so last sample can precede explosion by several ticks: until
     * explosion, extrapolate path from it ({@link #at}); engine fades when blast front itself reaches ear,
     * not earlier with a silent gap before it.
     */
    public void impact(long tick) {
        confirmedEnd = true;
        if (last >= 0 && !isDead()) death = Math.max(last, tick);
    }

    /** Path currently follows server packets (client has no entity). */
    public boolean fromServer() {
        return !fromEntity;
    }

    /** Bomb is drilling. */
    public boolean drilling() {
        return drilling;
    }

    public void die(long tick) {
        if (death == Long.MAX_VALUE) death = Math.max(last, Math.min(tick, last + 1));
    }

    public boolean confirmedEnd() {
        return confirmedEnd;
    }

    public boolean isDead() {
        return death != Long.MAX_VALUE;
    }

    public long deathTick() {
        return death;
    }

    public long lastTick() {
        return last;
    }

    @Override
    public double maxSpeed() {
        return maxSpeed;
    }

    private static double sq(double x) {
        return x * x;
    }

    @Override
    public double start() {
        return Math.max(first, last - CAPACITY + 1);
    }

    /** Projectile at t: linear between samples, last velocity beyond last sample, never past target. */
    @Override
    public void at(double t, double[] out) {
        if (last >= 0 && t > last) {
            ahead(t - last, out);
            return;
        }
        double s = Math.max(start(), t);
        long a = (long) Math.floor(s);
        long b = Math.min(last, a + 1);
        double f = s - a;
        int ia = (int) (a % CAPACITY), ib = (int) (b % CAPACITY);
        out[0] = xs[ia] + (xs[ib] - xs[ia]) * f;
        out[1] = ys[ia] + (ys[ib] - ys[ia]) * f;
        out[2] = zs[ia] + (zs[ib] - zs[ia]) * f;
    }

    /**
     * Projectile at t, possibly after last sample (next packet still in transit): last point plus its velocity
     * ({@link #at}) while data covers time ({@link #covers}), never past target — projectile hitting this tick
     * does not fly through it. False — no data for this time.
     */
    public boolean predict(double t, double[] out) {
        if (last < 0 || !covers(t)) return false;
        at(t, out);
        return true;
    }

    /** Last sample extrapolated by its velocity for dt ticks (at most {@link #MAX_GAP}), never past target. */
    private void ahead(double dt, double[] out) {
        int i = (int) (last % CAPACITY);
        double vx = vxs[i], vy = vys[i], vz = vzs[i], ahead = Math.min(dt, MAX_GAP);
        double v2 = vx * vx + vy * vy + vz * vz;
        if (v2 > 1e-9) {
            // closest target approach on this line occurs after dot(aim − p, v)/|v|² ticks: do not extrapolate farther
            double toAim = ((axs[i] - xs[i]) * vx + (ays[i] - ys[i]) * vy + (azs[i] - zs[i]) * vz) / v2;
            if (toAim >= 0) ahead = Math.min(ahead, toAim);
        }
        out[0] = xs[i] + vx * ahead;
        out[1] = ys[i] + vy * ahead;
        out[2] = zs[i] + vz * ahead;
    }

    /** Point at t (whole tick within history) was recorded from server packets, not entity. */
    public boolean fromServer(long t) {
        return t >= start() && t <= last && servers[(int) (t % CAPACITY)];
    }

    /** Velocity at t, blocks/tick (linear between ticks). */
    public Vec3 velocity(double t) {
        double s = Math.max(start(), Math.min(last, t));
        long a = (long) Math.floor(s);
        long b = Math.min(last, a + 1);
        double f = s - a;
        int ia = (int) (a % CAPACITY), ib = (int) (b % CAPACITY);
        return new Vec3(vxs[ia] + (vxs[ib] - vxs[ia]) * f, vys[ia] + (vys[ib] - vys[ia]) * f, vzs[ia] + (vzs[ib] - vzs[ia]) * f);
    }

    public int phase(double t) {
        long a = (long) Math.max(start(), Math.min(last, Math.floor(t)));
        return phases[(int) (a % CAPACITY)];
    }

    /** Where projectile was headed at t (target point; retargeting from tick when learned). */
    public Vec3 aim(double t) {
        int i = (int) ((long) Math.max(start(), Math.min(last, Math.floor(t))) % CAPACITY);
        return new Vec3(axs[i], ays[i], azs[i]);
    }

    /** Same into out (x, y, z), allocation-free for frame. */
    public void aim(double t, double[] out) {
        int i = (int) ((long) Math.max(start(), Math.min(last, Math.floor(t))) % CAPACITY);
        out[0] = axs[i];
        out[1] = ays[i];
        out[2] = azs[i];
    }

    /**
     * Yaw, pitch and roll at t (degrees, into out): shortest turn between ticks, last values beyond last sample.
     */
    public void angles(double t, float[] out) {
        double s = Math.max(start(), Math.min(last, t));
        long a = (long) Math.floor(s);
        long b = Math.min(last, a + 1);
        float f = (float) (s - a);
        int ia = (int) (a % CAPACITY), ib = (int) (b % CAPACITY);
        out[0] = Mth.rotLerp(f, yaws[ia], yaws[ib]);
        out[1] = Mth.lerp(f, pitches[ia], pitches[ib]);
        out[2] = Mth.rotLerp(f, rolls[ia], rolls[ib]);
    }

    /** Projectile ticks in this history by t (for propeller and rocket spin on distant model). */
    public double age(double t) {
        return Math.max(0, t - first);
    }

    /** Flight phase ticks by t (fractional for smooth motor spin-up). */
    public double phaseAge(double t) {
        double s = Math.max(start(), Math.min(last, t));
        long a = (long) Math.floor(s);
        return phaseAges[(int) (a % CAPACITY)] + (s - a);
    }

    /**
     * Add projectile-axis point (right/left, up, along; nose +Z) to out with nose rotation at t, like
     * {@code Local.offset}, allocation-free: nozzle offset from body centre.
     */
    public void offset(double t, ClientWeaponSpec.At p, double[] out) {
        int i = (int) ((long) Math.max(start(), Math.min(last, Math.floor(t))) % CAPACITY);
        float yaw = yaws[i], pitch = pitches[i];
        double f = Mth.cos((yaw + 90) * Mth.DEG_TO_RAD), f1 = Mth.sin((yaw + 90) * Mth.DEG_TO_RAD);
        double f2 = Mth.cos(-pitch * Mth.DEG_TO_RAD), f3 = Mth.sin(-pitch * Mth.DEG_TO_RAD);
        double f4 = Mth.cos((-pitch + 90) * Mth.DEG_TO_RAD), f5 = Mth.sin((-pitch + 90) * Mth.DEG_TO_RAD);
        double fx = f * f2, fy = f3, fz = f1 * f2, ux = f * f4, uy = f5, uz = f1 * f4;
        // left is minus (forward × up)
        double lx = fz * uy - fy * uz, ly = fx * uz - fz * ux, lz = fy * ux - fx * uy;
        out[0] += fx * p.z() + ux * p.y() + lx * p.x();
        out[1] += fy * p.z() + uy * p.y() + ly * p.x();
        out[2] += fz * p.z() + uz * p.y() + lz * p.x();
    }

    /** Nose direction at t (for “whistle in front, roar behind”). */
    public Vec3 forward(double t) {
        long a = (long) Math.max(start(), Math.min(last, Math.floor(t)));
        int i = (int) (a % CAPACITY);
        return Vec3.directionFromRotation(pitches[i], yaws[i]);
    }

    /**
     * One path sample: projectile position, displacement per tick, nose and roll, phase and elapsed phase time,
     * target, whether from server packet.
     */
    private record Sample(Vec3 pos, Vec3 vel, float yaw, float pitch, float roll, int phase, int phaseAge, Vec3 aim, boolean server) {}
}
