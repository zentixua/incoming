package ua.zentix.incoming.client.fx;

import net.minecraft.client.Minecraft;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.ViewportEvent;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix3d;
import ua.zentix.incoming.IncomingConfig;
import ua.zentix.incoming.nuclear.model.BodySway;

import java.util.ArrayList;
import java.util.List;

/**
 * Camera shake without moving the aim: only the camera rotates (yaw, pitch, roll), the player's view direction remains fixed —
 * shaking the view direction itself would spoil aim.
 * <p>
 * The eye sees angular velocity: amplitude A° at frequency f gives up to 2π·f·A °/s (a fraction per frame). Ground
 * impulses (underground blast, bomb ground impact — {@link #ground}, on ground wave arrival) are therefore slow, like the body: the body
 * itself rocks the head (standing human vertical resonance — 4–6 Hz, ISO 2631-1), while rapid ground tremors barely
 * reach the eyes; vertical ground motion makes the head nod (pitch), with less sideways sway. Amplitudes change continuously:
 * a one-frame jump is still a jerk.
 * <p>
 * Sway from nuclear and conventional shock waves: the front hits a standing person at once (during milliseconds of diffraction loading the body gains
 * angular velocity), the wind behind it keeps pushing, and the body leans downwind and straightens ({@link BodySway} — a pendulum
 * with equilibrium); the head follows the body. Sway starts when the front reaches the body, rather than at the start of the tick that
 * detected it: the hit appears in the frame when it happened. Lean from multiple waves adds
 * (the pendulum is linear at small angles). Beyond the stability boundary a person steps — lean stays at that boundary. In the air
 * (jumping), the body has no foot support — no lean until standing again. Body lean rotates the camera around the world
 * horizontal perpendicular to the lean, as is: camera yaw rotates around the world vertical, so adding lean separately to
 * yaw, pitch and roll is invalid — looking down would turn sideways sway into image rotation.
 * <p>
 * <b>Impact tremor</b> ({@link #tremor}) — <b>presentation</b> (visual principle: the camera is presentation, facts stay unchanged): when a conventional blast
 * front arrives, the image briefly trembles by rotation, as in good strike footage and as before 3.5.0. Strength,
 * frequency and duration come from the wave at the body: amplitude by front pressure, duration by impulse, lower frequency for a stronger
 * hit; no distance bands. Rotation without translation; starts at zero and fades to zero — no jerk. Then only
 * body lean and head sway remain ({@link BodySway}).
 */
public final class CameraShake {
    /** Active ground impulses ({@link #ground}). */
    private static final List<Ground> GROUNDS = new ArrayList<>();
    /**
     * Full-strength ground impulse, ° — nod (pitch), sideways sway (yaw), roll as a fraction of yaw; frequencies — standing
     * human body (ISO 2631-1: 4–6 Hz vertically), Hz; rise time — a quarter body period, s. presentation: the eye sees amplitude and frequency,
     * while real ground displacement is fractions of a millimetre.
     */
    static final double GROUND_PITCH = 1.6, GROUND_YAW = 0.7, GROUND_ROLL = 0.4, GROUND_HZ = 5.1, GROUND_RISE_S = 0.05;

    /** Ground impulse: from {@code from} (fractional ticks), strength 0..1, duration, s, pattern offset. */
    private record Ground(double from, double strength, double seconds, double phase) {}
    private static long ticks;
    /** Waves currently swaying the body. */
    private static final List<Sway> SWAYS = new ArrayList<>();
    /** Beyond this simultaneous wave count the oldest is removed. */
    private static final int SWAYS_MAX = 8;
    /** Active impact tremors ({@link #tremor}). */
    private static final List<Tremor> TREMORS = new ArrayList<>();
    /**
     * Impact tremor: none at {@link #TREMOR_LOW_PA} pressure (140 dB — a loud crack), full at {@link #TREMOR_HIGH_PA} (5 psi —
     * knocks a person down); strength — logarithmic pressure fraction raised to {@link #TREMOR_POWER} (Eiserloh, GDC 2016,
     * uses trauma² or trauma³ for shake: a weak hit barely shakes; ours is gentler so a Shahed at 100 m shakes at least as much as in 3.4.1).
     * Presentation.
     */
    static final double TREMOR_LOW_PA = 200, TREMOR_HIGH_PA = 35_000, TREMOR_POWER = 1.5;
    /** Full tremor amplitude in yaw and pitch, ° (as for a nearby hit in 3.4.1); roll is a fraction of yaw. Presentation. */
    static final double TREMOR_YAW = 5, TREMOR_PITCH = 3.5, TREMOR_ROLL = 0.6;
    /** Tremor duration, s: from {@link #TREMOR_SHORT} for weak phase impulse to {@link #TREMOR_LONG} for strong impulse. Presentation. */
    static final double TREMOR_SHORT = 0.3, TREMOR_LONG = 1.7;
    /** Compression phase impulse, Pa·s, above which tremor exceeds the shortest duration and reaches the longest. Presentation. */
    static final double TREMOR_IMPULSE_LOW = 5, TREMOR_IMPULSE_HIGH = 300;
    /** Tremor frequency, Hz: {@link #TREMOR_HZ_WEAK} for a weak hit, {@link #TREMOR_HZ_STRONG} at full strength. Presentation. */
    static final double TREMOR_HZ_WEAK = 12.5, TREMOR_HZ_STRONG = 8.5;

    /** One impact tremor: from {@code from} (fractional ticks), strength 0..1, duration, s, frequency, Hz, pattern offset. */
    private record Tremor(double from, double strength, double seconds, double hz, double phase) {}
    /** Player foot support (1 — standing, 0 — airborne), previous tick and now. */
    private static double standing = 1, standingBefore = 1;
    /** Support change per tick: the body gains and loses foot support over a quarter second. */
    private static final double STANDING_STEP = 0.2;

    /**
     * Body lean from one wave's hit and wind, rad ({@link BodySway#RATE} samples per second), from {@code from} (fractional
     * client ticks); wind direction — away from the blast along the ground (x, z, unit vector).
     */
    private record Sway(double[] tilt, double[] neck, double from, double pushX, double pushZ) {}

    private CameraShake() {}

    /**
     * Ground impulse from the old source-free packet ({@code S2C.Quake}: ticks in Z = R/W^⅓ bands — old Flashback replay
     * recordings): same strength bands (80, 60, 40 ticks — previously amplitudes of 1.6°, 0.9° and 0.35°) and durations, from
     * packet arrival. Ground wave source — {@link GroundShake}.
     */
    public static void quake(int ticks) {
        ground(ticks >= 50 ? 1 : ticks >= 24 ? 0.56 : 0.22, ticks / 20.0, 0, ticks);
    }

    /**
     * Ground wave reached the player: impulse strength {@code strength} 0..1 (perceptibility from the ground model), duration
     * {@code seconds}, s; {@code late} — ticks since arrival (the packet may be more than a tick late — the impulse
     * resumes at the point it would have reached); {@code seed} — pattern. Impulses add, jointly capped at full amplitude.
     */
    public static void ground(double strength, double seconds, double late, long seed) {
        double s = Mth.clamp(strength, 0, 1);
        if (!(s > 1e-4) || !(seconds > 0)) return;
        GROUNDS.add(new Ground(ticks - Math.max(0, late), s, seconds, (seed & 0xFFFF) / 65536.0));
    }

    /**
     * Ground impulse at time {@code t} ticks, ° (yaw, pitch, roll): rises over {@link #GROUND_RISE_S}, fades as
     * (1 − s)² to the end; pattern — two body sinusoids starting at zero. Sum capped at full amplitude.
     */
    static double[] groundAt(double t) {
        double yaw = 0, pitch = 0;
        for (Ground g : GROUNDS) {
            double s = (t - g.from) / 20;
            if (s < 0 || s >= g.seconds) continue;
            double rise = Math.min(1, s / GROUND_RISE_S), x = s / g.seconds;
            double a = g.strength * rise * rise * (3 - 2 * rise) * (1 - x) * (1 - x);
            pitch += GROUND_PITCH * a * shiver(s, GROUND_HZ, g.phase);
            yaw += GROUND_YAW * a * shiver(s, 1.31 * GROUND_HZ, g.phase + 0.53);
        }
        pitch = Mth.clamp(pitch, -GROUND_PITCH, GROUND_PITCH);
        yaw = Mth.clamp(yaw, -GROUND_YAW, GROUND_YAW);
        return new double[]{yaw, pitch, GROUND_ROLL * yaw};
    }

    /** Tremor strength 0..1 at front pressure {@code pPa}, Pa: (logarithmic pressure fraction)^{@link #TREMOR_POWER}. */
    static double tremorStrength(double pPa) {
        double x = Mth.clamp(Math.log(Math.max(pPa, 1e-9) / TREMOR_LOW_PA) / Math.log(TREMOR_HIGH_PA / TREMOR_LOW_PA), 0, 1);
        return Math.pow(x, TREMOR_POWER);
    }

    /** Tremor duration, s, at compression phase impulse {@code impulse}, Pa·s. */
    static double tremorSeconds(double impulse) {
        double x = Mth.clamp(Math.log(Math.max(impulse, 1e-9) / TREMOR_IMPULSE_LOW) / Math.log(TREMOR_IMPULSE_HIGH / TREMOR_IMPULSE_LOW), 0, 1);
        return TREMOR_SHORT + (TREMOR_LONG - TREMOR_SHORT) * x;
    }

    /**
     * Conventional blast front reached the body: camera tremor from its pressure {@code pPa}, Pa, and compression phase impulse
     * {@code impulse}, Pa·s; {@code late} — ticks since arrival (0..1); {@code seed} — pattern. Presentation. Returns strength 0..1.
     */
    public static double tremor(double pPa, double impulse, double late, long seed) {
        double strength = tremorStrength(pPa);
        if (!(strength > 1e-4)) return 0;
        double hz = TREMOR_HZ_WEAK + (TREMOR_HZ_STRONG - TREMOR_HZ_WEAK) * Math.sqrt(strength);
        TREMORS.add(new Tremor(ticks - Mth.clamp(late, 0, 1), strength, tremorSeconds(impulse), hz, (seed & 0xFFFF) / 65536.0));
        return strength;
    }

    /**
     * Tremor at time {@code t} ticks, ° (yaw, pitch; roll is a fraction of yaw): pattern — two sinusoids at ratio 1.73, starting at zero;
     * envelope (1 − s)² fades to zero over the tremor duration. Sum capped at full amplitude (no tremor count
     * limit: evicting an active tremor causes a jerk; each ends within {@link #TREMOR_LONG} s).
     */
    static double[] tremorAt(double t) {
        double yaw = 0, pitch = 0;
        for (Tremor r : TREMORS) {
            double s = (t - r.from) / 20;
            if (s < 0 || s >= r.seconds) continue;
            double fade = (1 - s / r.seconds) * (1 - s / r.seconds), a = r.strength * fade;
            yaw += TREMOR_YAW * a * shiver(s, r.hz, r.phase);
            pitch += TREMOR_PITCH * a * shiver(s, 1.27 * r.hz, r.phase + 0.37);
        }
        yaw = Mth.clamp(yaw, -TREMOR_YAW, TREMOR_YAW);
        pitch = Mth.clamp(pitch, -TREMOR_PITCH, TREMOR_PITCH);
        return new double[]{yaw, pitch, TREMOR_ROLL * yaw};
    }

    /** Tremor pattern, amplitude ~1, starting at zero at s = 0: two sinusoids of frequencies f and 1.73·f, each hit has its own frequency offset. */
    private static double shiver(double s, double hz, double phase) {
        double f = hz * (0.9 + 0.2 * phase);
        return 0.7 * Math.sin(Mth.TWO_PI * f * s) + 0.3 * Math.sin(Mth.TWO_PI * 1.73 * f * s);
    }

    /**
     * A wave front (nuclear or conventional blast) hit the body and the wind behind it swayed it ({@link BodySway#tilt}): front pressure {@code pPa} and
     * dynamic pressure behind the front along the ground {@code qPa}, Pa, exposed fraction toward the blast {@code open} (wind shadow), with
     * positive phase {@code tPlus}, s; {@code late} —
     * ticks since the front passed the body (0..1); {@code away} — from blast to player (wind blows away from the epicentre),
     * {@code yaw} — player view direction, °. Lean is added to what remains of previous waves. Returns downwind body lean,
     * rad.
     */
    public static double[] sway(double pPa, double qPa, double open, double tPlus, double late, Vec3 away, double yaw) {
        return sway(pPa, qPa, open, tPlus, tPlus, late, away, yaw, false).body();
    }

    /**
     * Like {@link #sway(double, double, double, double, double, Vec3, double)}, but diffraction loading decays over its own phase
     * {@code hitPhase}, s: for a nuclear wave the in-game wind phase is model time × {@code effects_scale}, while body diffraction loading takes
     * real milliseconds, and its impulse phase is the model phase. {@code neck} — the camera also sways with the head on the neck
     * ({@link BodySway#motion}); without it the head follows the body (<b>not physical</b>: currently used for the nuclear wave — this
     * step did not change its visuals).
     */
    public static BodySway.Motion sway(double pPa, double qPa, double open, double tPlus, double hitPhase, double late, Vec3 away, double yaw,
                                       boolean neck) {
        double flat = Math.hypot(away.x, away.z);
        if (!(flat > 1e-6)) return new BodySway.Motion(new double[0], new double[0]);
        double x = away.x / flat, z = away.z / flat;
        double edge = edge(yaw, x, z), back = edge(yaw, -x, -z);
        BodySway.Motion m = neck ? BodySway.motion(pPa, qPa, open, tPlus, hitPhase, edge, back)
                : new BodySway.Motion(BodySway.tilt(pPa, qPa, open, tPlus, hitPhase, edge, back), null);
        if (m.still()) return m;
        if (SWAYS.size() >= SWAYS_MAX) SWAYS.remove(0);
        SWAYS.add(new Sway(m.body(), m.neck(), ticks - Mth.clamp(late, 0, 1), x, z));
        return m;
    }

    /**
     * Stability boundary, rad, for lean in world direction (x, z) when the player faces yaw {@code yaw}°: forward
     * {@link BodySway#FORWARD}, backward {@link BodySway#BACK}, sideways {@link BodySway#SIDE}, diagonally — an ellipse between them.
     */
    static double edge(double yaw, double x, double z) {
        double r = Math.toRadians(yaw), sin = Math.sin(r), cos = Math.cos(r);
        double ahead = -sin * x + cos * z, side = -cos * x - sin * z, len = Math.hypot(ahead, side);
        if (!(len > 0)) return BodySway.FORWARD;
        return len / Math.hypot(ahead / (ahead > 0 ? BodySway.FORWARD : BodySway.BACK), side / BodySway.SIDE);
    }

    /** Whether the player has foot support: grounded and not carried ({@link #carried}). */
    public static boolean standing(@Nullable Player p) {
        return p != null && p.onGround() && !carried(p);
    }

    /** Wind carries the whole player, or there is no standing support: riding, swimming, flying, sleeping. */
    public static boolean carried(Player p) {
        return p.isPassenger() || p.isSwimming() || p.isFallFlying() || p.isSleeping() || p.getAbilities().flying;
    }

    /** Client tick; {@code onFeet} — player has foot support ({@link #standing}). */
    public static void tick(boolean onFeet) {
        ticks++;
        standingBefore = standing;
        standing = Mth.clamp(standing + (onFeet ? STANDING_STEP : -STANDING_STEP), 0, 1);
        SWAYS.removeIf(s -> (ticks - s.from) / 20.0 * BodySway.RATE >= s.tilt.length - 1);
        TREMORS.removeIf(r -> (ticks - r.from) / 20.0 >= r.seconds);
        GROUNDS.removeIf(g -> (ticks - g.from) / 20.0 >= g.seconds);
    }

    public static void reset() {
        GROUNDS.clear();
        SWAYS.clear();
        TREMORS.clear();
        standing = standingBefore = 1;
        ticks = 0;
    }

    /**
     * Shake strength in the frame: {@code camera_shake} setting × vanilla Distortion Effects (accessibility:
     * lets players remove image sway — nausea and portal; a wave-swayed camera is the same kind of sway).
     */
    static double strength(double cameraShake, double distortion) {
        return cameraShake * Mth.clamp(distortion, 0, 1);
    }

    public static void apply(ViewportEvent.ComputeCameraAngles e) {
        Minecraft mc = Minecraft.getInstance();
        double k = strength(IncomingConfig.CLIENT.cameraShake.get(), mc.options.screenEffectScale().get());
        if (k <= 0 || GROUNDS.isEmpty() && SWAYS.isEmpty() && TREMORS.isEmpty() || mc.isPaused()) return;
        // lean belongs to the player's body: invisible to the projectile camera or another entity's eyes
        Player p = mc.player;
        double body = p != null && mc.getCameraEntity() == p ? p.getViewYRot((float) e.getPartialTick()) : Double.NaN;
        double[] o = offsets(e.getPartialTick(), e.getYaw(), e.getPitch(), e.getRoll(), body, k);
        e.setYaw(e.getYaw() + (float) o[0]);
        e.setPitch(e.getPitch() + (float) o[1]);
        e.setRoll(e.getRoll() + (float) o[2]);
    }

    /**
     * Camera shake rotation in the frame, ° (yaw, pitch — positive downward, roll — positive with top to the right), with camera yaw
     * {@code cameraYaw}, pitch {@code cameraPitch} and roll {@code cameraRoll}; {@code bodyYaw} — player view direction,
     * ° (sets the stability boundary: front third-person view looks backward); NaN — camera is not on the player,
     * no body lean; {@code k} — shake strength from settings.
     */
    static double[] offsets(double partial, double cameraYaw, double cameraPitch, double cameraRoll, double bodyYaw, double k) {
        double t = ticks + partial;
        // amplitudes: yaw/pitch, °
        double yaw = 0, pitch = 0, roll = 0;
        if (!GROUNDS.isEmpty() && !Double.isNaN(bodyYaw)) {
            // ground impulse is at the player's body, like impact tremor: invisible to the projectile camera
            double[] g = groundAt(t);
            yaw += g[0];
            pitch += g[1];
            roll += g[2];
        }
        if (!TREMORS.isEmpty() && !Double.isNaN(bodyYaw)) {
            // impact tremor is at the player's head, like lean: invisible to the projectile camera
            double[] tr = tremorAt(t);
            yaw += tr[0];
            pitch += tr[1];
            roll += tr[2];
        }
        yaw *= k;
        pitch *= k;
        roll *= k;
        // body and neck-mounted head lean from all waves' wind, ° around world axes
        double leanX = 0, leanZ = 0, neckX = 0, neckZ = 0;
        for (Sway s : SWAYS) {
            double f = (ticks - s.from + partial) / 20 * BodySway.RATE;
            int i = (int) f;
            if (f < 0 || i + 1 >= s.tilt.length) continue;
            double tilt = Math.toDegrees(s.tilt[i] + (s.tilt[i + 1] - s.tilt[i]) * (f - i));
            leanX += tilt * s.pushX;
            leanZ += tilt * s.pushZ;
            if (s.neck == null) continue;
            double neck = Math.toDegrees(s.neck[i] + (s.neck[i + 1] - s.neck[i]) * (f - i));
            neckX += neck * s.pushX;
            neckZ += neck * s.pushZ;
        }
        double w = k * (standingBefore + (standing - standingBefore) * partial), body = w * Math.hypot(leanX, leanZ);
        if (Double.isNaN(bodyYaw) || !(body > 0) && !(neckX != 0 || neckZ != 0)) return new double[]{yaw, pitch, roll};
        // the body leans downwind away from the blast — a frontal hit tips the head back, a rear hit tips it down, a side hit tilts it away from
        // the blast; beyond the stability boundary a person steps — body lean stays there; the head sways on the neck on top
        if (body > 0) {
            double bx = leanX / Math.hypot(leanX, leanZ), bz = leanZ / Math.hypot(leanX, leanZ);
            body = Math.min(body, Math.toDegrees(edge(bodyYaw, bx, bz)));
            leanX = body * bx;
            leanZ = body * bz;
        }
        leanX += w * neckX;
        leanZ += w * neckZ;
        double lean = Math.hypot(leanX, leanZ);
        if (!(lean > 0)) return new double[]{yaw, pitch, roll};
        double x = leanX / lean, z = leanZ / lean;
        double[] to = leaned(cameraYaw + yaw, cameraPitch + pitch, cameraRoll + roll, lean, x, z);
        return new double[]{Mth.wrapDegrees(to[0] - cameraYaw), to[1] - cameraPitch, Mth.wrapDegrees(to[2] - cameraRoll)};
    }

    /**
     * Camera with yaw {@code yaw}, pitch {@code pitch} and roll {@code roll} (°) after body lean of {@code lean}° in unit world
     * direction (x, z): rotation around the horizontal perpendicular to the lean — the camera top moves in the direction the
     * body leans. Returns yaw, pitch and roll that produce this rotation in {@code Camera.setRotation}:
     * {@code rotationYXZ(π − yaw, −pitch, −roll)}.
     */
    static double[] leaned(double yaw, double pitch, double roll, double lean, double x, double z) {
        Matrix3d m = new Matrix3d().rotation(Math.toRadians(lean), z, 0, -x)
                .mul(new Matrix3d().rotationYXZ(Math.PI - Math.toRadians(yaw), -Math.toRadians(pitch), -Math.toRadians(roll)));
        // decompose R = Ry(a)·Rx(b)·Rz(c); JOML mCR means column C, row R
        double b = Math.asin(Mth.clamp(-m.m21(), -1, 1)), a, c;
        if (Math.cos(b) > 1e-7) {
            a = Math.atan2(m.m20(), m.m22());
            c = Math.atan2(m.m01(), m.m11());
        } else {
            // looking exactly at zenith or nadir: yaw and roll rotate the image around one axis — keep the previous roll
            c = -Math.toRadians(roll);
            double turn = Math.atan2(-m.m02(), m.m00());
            a = b < 0 ? turn - c : turn + c;
        }
        return new double[]{180 - Math.toDegrees(a), -Math.toDegrees(b), -Math.toDegrees(c)};
    }
}
