package ua.zentix.incoming.client.fx;

import net.minecraft.world.phys.Vec3;
import org.joml.Quaterniond;
import org.joml.Vector3d;
import org.junit.jupiter.api.Test;
import ua.zentix.incoming.nuclear.model.BlastModel;
import ua.zentix.incoming.nuclear.model.BodySway;
import ua.zentix.incoming.nuclear.model.HobBlast;
import ua.zentix.incoming.nuclear.model.Yield;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CameraShakeTest {
    private static final int STEP = BodySway.RATE / 20;

    /**
     * Wave on the ground, where the pressure is {@code psi}, from the air blast {@code kt} at the best height: {velocity pressure along the
     *  ground, Pa; positive phase, s; front pressure, Pa}.
     */
    private static double[] wave(double psi, double kt) {
        double h = Yield.optimalBurstHeight(kt), g = HobBlast.rangeFor(psi, h, kt);
        return new double[]{1000 * BlastModel.dynamicPressureKpa(BlastModel.kpa(psi)) * HobBlast.alongGround(g, h, kt), HobBlast.positivePhaseSeconds(g, h, kt),
                1000 * BlastModel.kpa(psi)};
    }

    /** Wave {@code psi} from {@code kt} shook the player who is looking at the rate 0 (at +z); {@code away} - from the explosion to him. */
    private static double[] sway(double psi, double kt, Vec3 away) {
        double[] w = wave(psi, kt);
        return CameraShake.sway(w[2], w[0], 1, w[1], 0, away, 0);
    }

    /**
     * The nuclear wave tilts the camera in the wind from the explosion: the explosion in front - tilts, behind - tilts down, on the right -
     *  tilts to the left, tilting the body. The wind blows along the ground from the epicenter: the height of the explosion above the player does not change the direction
     *  (the share of the pressure along the ground is given by the wave itself, {@code Detonation.dynamicPressureKpa}).
     */
    @Test
    void nukeWindLeansAwayFromTheBurst() {
        CameraShake.reset();
        double peak = Math.toDegrees(BodySway.peak(sway(0.6, 2, new Vec3(0, 0, -1)), 0));
        assertTrue(peak > 0.5 && peak < Math.toDegrees(BodySway.BACK), "0,6 psi: " + peak);
        assertEquals(-peak, extreme(0.6, 2, new Vec3(0, 0, -1), 1)[0], 1e-6, "explosion ahead - back");
        assertEquals(peak, extreme(0.6, 2, new Vec3(0, 0, 1), 1)[1], 1e-6, "explosion behind - down");
        assertEquals(-peak, extreme(0.6, 2, new Vec3(1, 0, 0), 1)[2], 1e-6, "explosion right - left");
        assertEquals(-peak, extreme(0.6, 2, new Vec3(0, -1, -1), 1)[0], 1e-6, "above your head in front - also back");
    }

    /**
     * The tilt of the body turns the camera around the horizontal of the world across the tilt, as it is, in any view: the heading, pitch and
     *  roll, which are given by shaking, in {@code Camera.setRotation} give exactly this rotation. Looking straight down, tilting to the side
     *  takes the gaze to the side (the head in one direction, looking along the body in the other), rather than rotating the picture.
     */
    @Test
    void viewTurnsWithTheBody() {
        for (Vec3 away : new Vec3[]{new Vec3(1, 0, 0), new Vec3(-1, 0, 1), new Vec3(0.3, -0.5, -1)}) {
            for (double yaw : new double[]{0, 30, -135}) {
                for (double pitch : new double[]{-90, -45, 0, 30, 60, 85, 89.9, 90}) {
                    for (double roll : new double[]{0, 10}) {
                        CameraShake.reset();
                        double[] w = wave(1, 2), t = CameraShake.sway(w[2], w[0], 1, w[1], 0, away, yaw);
                        int top = 0;
                        for (int i = 0; i < t.length; i++) if (t[i] > t[top]) top = i;
                        for (int tick = 0; tick < top / STEP; tick++) CameraShake.tick(true);
                        double[] o = CameraShake.offsets((top % STEP) / (double) STEP, yaw, pitch, roll, yaw, 1);
                        Vector3d[] got = camera(yaw + o[0], pitch + o[1], roll + o[2]), was = camera(yaw, pitch, roll);
                        double flat = Math.hypot(away.x, away.z);
                        Quaterniond lean = new Quaterniond().rotationAxis(t[top], away.z / flat, 0, -away.x / flat);
                        String what = "from the explosion " + away + ", course " + yaw + ", pitch " + pitch + ", roll " + roll;
                        assertEquals(0, angle(got[0], was[0].rotate(lean)), 1e-6, what + ": look");
                        assertEquals(0, angle(got[1], was[1].rotate(lean)), 1e-6, what + ": top");
                    }
                }
            }
        }
        // straight down, explosion to the right: the head is carried to the left, and the gaze along the body goes to the right (−x with a heading of 0) to the tilt of the body
        CameraShake.reset();
        double[] t = sway(1, 2, new Vec3(1, 0, 0));
        int top = 0;
        for (int i = 0; i < t.length; i++) if (t[i] > t[top]) top = i;
        for (int tick = 0; tick < top / STEP; tick++) CameraShake.tick(true);
        double[] o = CameraShake.offsets((top % STEP) / (double) STEP, 0, 90, 0, 0, 1);
        Vector3d look = camera(o[0], 90 + o[1], o[2])[0];
        assertEquals(Math.toDegrees(t[top]), angle(look, new Vector3d(0, -1, 0)), 1e-6);
        assertTrue(look.x < 0, "looking down to the right: " + look);
        CameraShake.reset();
    }

    /**
     * The camera does not go further than the stability limit: facing a strong wave - no more than 4,5° back, back - no more than 8°
     *  forward, sideways - 8°, obliquely - along the ellipse between them; and it gets to her. Two waves together - no further either. From the edge of
     *  the body straightens, and does not fall to the other edge: with 3 psi back to the explosion - less 2° (reverse wind of rarefaction).
     * Shaking is weaker in the settings - the tilt is weaker.
     */
    @Test
    void nukeWindStopsAtTheBalanceLimit() {
        assertEquals(-Math.toDegrees(BodySway.BACK), extreme(5, 15, new Vec3(0, 0, -1), 1)[0], 1e-6);
        assertEquals(Math.toDegrees(BodySway.FORWARD), extreme(5, 15, new Vec3(0, 0, 1), 1)[1], 1e-6);
        assertEquals(-Math.toDegrees(BodySway.SIDE), extreme(5, 15, new Vec3(1, 0, 0), 1)[2], 1e-6);
        double[] behind = extreme(3, 15, new Vec3(0, 0, 1), 1);
        assertEquals(Math.toDegrees(BodySway.FORWARD), behind[1], 1e-6);
        assertTrue(behind[0] > -2, "3 psi: back " + behind[0]);
        // explosion behind on the right: tilt forward and left - on an ellipse; two waves - also on it
        for (int waves = 1; waves <= 2; waves++) {
            CameraShake.reset();
            Vec3 away = new Vec3(1, 0, 1);
            for (int i = 0; i < waves; i++) sway(5, 15, away);
            double edge = Math.toDegrees(CameraShake.edge(0, away.x / Math.sqrt(2), away.z / Math.sqrt(2))), most = 0;
            for (int tick = 0; tick < 20 * 20; tick++) {
                double[] o = CameraShake.offsets(0, 0, 0, 0, 0, 1);
                most = Math.max(most, angle(camera(o[0], o[1], o[2])[1], new Vector3d(0, 1, 0)) / edge);
                CameraShake.tick(true);
            }
            assertEquals(1, most, 1e-9, waves + " waves: obliquely - up to the ellipse and no further");
        }
        // half force in settings - half incline
        assertEquals(-0.5 * Math.toDegrees(BodySway.BACK), extreme(5, 15, new Vec3(0, 0, -1), 0.5)[0], 1e-6);
        assertEquals(0.5 * extreme(1, 2, new Vec3(0, 0, -1), 1)[0], extreme(1, 2, new Vec3(0, 0, -1), 0.5)[0], 1e-6);
    }

    /**
     * Without jerks: up to 2 psi (for 15 kt is already the limit) at any heading, and looking almost vertically, behind the frame in 1/10 tick the camera
     *  rotates by less than half a degree, and in 1 psi - no faster than 30°/s (the body swings in seconds); after
     *  the body has straightened, there is no turning. The stronger the pressure throws the body towards the border in hundredths of a second - and so does the camera.
     */
    @Test
    void nukeWindDoesNotJerkAndEnds() {
        for (double kt : new double[]{2, 15}) {
            for (double psi : new double[]{0.5, 1, 2}) {
                for (double pitch : new double[]{30, 89.9}) {
                    for (double yaw = 0; yaw < 360; yaw += 45) {
                        CameraShake.reset();
                        double[] w = wave(psi, kt), t = CameraShake.sway(w[2], w[0], 1, w[1], 0, new Vec3(0.3, -0.2, 1), yaw);
                        double most = 0;
                        Quaterniond prev = null;
                        for (int tick = 0; tick * STEP < t.length + 20; tick++) {
                            for (int f = 0; f < 10; f++) {
                                double[] o = CameraShake.offsets(f / 10.0, yaw, pitch, 0, yaw, 1);
                                Quaterniond q = rotation(yaw + o[0], pitch + o[1], o[2]);
                                if (prev != null) most = Math.max(most, Math.toDegrees(2 * Math.acos(Math.min(1, Math.abs(q.dot(prev))))));
                                prev = q;
                            }
                            CameraShake.tick(true);
                        }
                        String what = kt + " kt, " + psi + " psi, course " + yaw + ", pitch " + pitch;
                        assertTrue(most < 0.5, what + ": " + most + "° per frame");
                        if (psi == 1) assertTrue(most * 200 < 30, what + ": " + most * 200 + " °/s");
                        double[] o = CameraShake.offsets(0.5, yaw, pitch, 0, yaw, 1);
                        assertEquals(0, Math.abs(o[0]) + Math.abs(o[1]) + Math.abs(o[2]), 1e-12, what + ": the body is straightened, but the camera is not");
                    }
                }
            }
        }
        CameraShake.reset();
    }

    /**
     * Tilt - the player’s body: the camera not on him (the projectile camera) does not show the tilt; the limit of stability is based on where
     *  the body is looking, not the camera: the third-person front view is looking back, and with its back to the explosion the body is leaning forward to 8°.
     */
    @Test
    void leanIsTheBodys() {
        CameraShake.reset();
        sway(5, 15, new Vec3(0, 0, 1));
        double front = 0;
        for (int tick = 0; tick < 20 * 20; tick++) {
            double[] o = CameraShake.offsets(0.5, 0, 0, 0, Double.NaN, 1);
            assertEquals(0, Math.abs(o[0]) + Math.abs(o[1]) + Math.abs(o[2]), 1e-12, "camera is not on the player");
            o = CameraShake.offsets(0.5, 180, 0, 0, 0, 1);
            front = Math.max(front, angle(camera(180 + o[0], o[1], o[2])[1], new Vector3d(0, 1, 0)));
            CameraShake.tick(true);
        }
        assertEquals(Math.toDegrees(BodySway.FORWARD), front, 1e-6, "front view: border - bodies");
        CameraShake.reset();
    }

    /** The second wave falls on top of what remains of the first: without a jump at the moment of arrival, then the slopes add up. */
    @Test
    void wavesAdd() {
        CameraShake.reset();
        double[] first = sway(1, 2, new Vec3(0, 0, 1));
        for (int i = 0; i < 10; i++) CameraShake.tick(true);
        double before = CameraShake.offsets(0, 0, 0, 0, 0, 1)[1];
        double[] second = sway(0.5, 2, new Vec3(0, 0, 1));
        assertEquals(before, CameraShake.offsets(0, 0, 0, 0, 0, 1)[1], 1e-9, "at the moment of arrival");
        for (int i = 0; i < 10; i++) CameraShake.tick(true);
        assertEquals(Math.toDegrees(first[20 * STEP] + second[10 * STEP]), CameraShake.offsets(0, 0, 0, 0, 0, 1)[1], 1e-9);
        CameraShake.reset();
    }

    /**
     * The impact is from the arrival of the front to the body, and not from the tick in which it was noticed: the front passed the body half a point ago - in the first
     *  frame of this tick the body is already tilting half a point; from arrival - from the very beginning (not from 0, not later).
     */
    @Test
    void swayStartsWhenTheFrontPassed() {
        CameraShake.reset();
        double[] w = wave(1, 2), t = CameraShake.sway(w[2], w[0], 1, w[1], 0.5, new Vec3(0, 0, 1), 0);
        double half = Math.toDegrees(t[STEP / 2] + (t[STEP / 2 + 1] - t[STEP / 2]) * (STEP / 2.0 - STEP / 2));
        assertEquals(half, CameraShake.offsets(0, 0, 0, 0, 0, 1)[1], 1e-9);
        assertTrue(half > 0, "half a blow is already a tilt");
        CameraShake.reset();
    }

    /** Airborne (jumping), the body has no foot support: tilt fades in a quarter second and returns on landing. */
    @Test
    void noLeanInTheAir() {
        CameraShake.reset();
        double[] t = sway(1, 2, new Vec3(0, 0, 1));
        for (int i = 0; i < 10; i++) CameraShake.tick(true);
        assertTrue(CameraShake.offsets(0, 0, 0, 0, 0, 1)[1] > 0.1);
        for (int i = 0; i < 6; i++) CameraShake.tick(false);
        assertEquals(0, CameraShake.offsets(0.5, 0, 0, 0, 0, 1)[1], 1e-12);
        for (int i = 0; i < 6; i++) CameraShake.tick(true);
        assertEquals(Math.toDegrees(t[22 * STEP]), CameraShake.offsets(0, 0, 0, 0, 0, 1)[1], 1e-9);
        CameraShake.reset();
    }

    /**
     * A {@code psi} wave from {@code kt} with shake strength {@code k}: minimum and maximum pitch and minimum roll
     * while the body rocks, heading 0 (toward +z): {min pitch, max pitch, min roll}.
     */
    private static double[] extreme(double psi, double kt, Vec3 away, double k) {
        CameraShake.reset();
        double[] t = sway(psi, kt, away), e = {0, 0, 0};
        for (int tick = 0; tick * STEP < t.length; tick++) {
            for (int f = 0; f < 10; f++) {
                double[] o = CameraShake.offsets(f / 10.0, 0, 0, 0, 0, k);
                e[0] = Math.min(e[0], o[1]);
                e[1] = Math.max(e[1], o[1]);
                e[2] = Math.min(e[2], o[2]);
            }
            CameraShake.tick(true);
        }
        CameraShake.reset();
        return e;
    }

    /**
     * The camera rotation, as {@code Camera.setRotation} builds it, is in double: in float the course of JOML is about 0 (half the angle
     *  is about 90°, the cosine is from the sine) itself is mistaken up to three hundredths of a degrees.
     */
    private static Quaterniond rotation(double yaw, double pitch, double roll) {
        return new Quaterniond().rotationYXZ(Math.PI - Math.toRadians(yaw), -Math.toRadians(pitch), -Math.toRadians(roll));
    }

    /** Camera: {forward, up}. */
    private static Vector3d[] camera(double yaw, double pitch, double roll) {
        Quaterniond q = rotation(yaw, pitch, roll);
        return new Vector3d[]{new Vector3d(0, 0, -1).rotate(q), new Vector3d(0, 1, 0).rotate(q)};
    }

    /** Angle between directions, °. */
    private static double angle(Vector3d a, Vector3d b) {
        return Math.toDegrees(Math.atan2(new Vector3d(a).cross(b).length(), a.dot(b)));
    }

    /**
     * Regular explosion (complaint 23.10: “the camera doesn’t swing, it just gets hit a little and knocked down”): Shahed in 100 m behind
     *  back - the camera shakes, shakes its head on its neck (several swings back and forth), returns to zero with attenuation and between
     *  frames (by 1/6 tick) does not jump more than the hit speed allows. The nuclear wave does not hit the neck (this
     *  step did not change its picture).
     */
    @Test
    void conventionalBlastRocksTheCameraAndItSettles() {
        CameraShake.reset();
        double kg = 50, m = 100, pa = 1000 * BlastModel.surfaceOverpressureKpa(m, kg), tp = BlastModel.chemicalPhaseSeconds(m, kg, true);
        BodySway.Motion motion = CameraShake.sway(pa, 1000 * BlastModel.dynamicPressureKpa(pa / 1000), 1, tp, tp, 0, new Vec3(0, 0, 1), 0, true);
        double strength = CameraShake.tremor(pa, BlastModel.chemicalImpulse(m, kg, true), 0, 3);
        assertTrue(motion.neck().length == motion.body().length && motion.body().length > 20 && strength > 0);
        // Over 1/120 s the camera moves no more than the neck impulse and fastest tremor allow.
        double kick = Math.toDegrees(Math.abs(motion.body()[1] + motion.neck()[1])) * BodySway.RATE / 120
                + 2 * Math.PI * 1.73 * CameraShake.TREMOR_HZ_WEAK * 1.1 * CameraShake.TREMOR_PITCH * strength / 120, last = 0, most = 0;
        int turns = 0, sign = 0;
        for (int tick = 0; tick < 20 * 6; tick++) {
            for (int k = 0; k < 6; k++) {
                double pitch = CameraShake.offsets(k / 6.0, 0, 0, 0, 0, 1)[1];
                assertTrue(Math.abs(pitch - last) <= 1.05 * kick + 1e-9, "jump in tick " + tick + ": " + last + " → " + pitch);
                int s2 = (int) Math.signum(pitch);
                if (s2 != 0 && s2 != sign) {
                    if (sign != 0) turns++;
                    sign = s2;
                }
                most = Math.max(most, Math.abs(pitch));
                last = pitch;
            }
            CameraShake.tick(true);
        }
        assertTrue(turns >= 2, "swings: sign changes " + turns);
        assertTrue(most > 1, "scope " + most);
        assertEquals(0, CameraShake.offsets(0, 0, 0, 0, 0, 1)[1], 1e-9, "came back");
        CameraShake.reset();
        double[] w = wave(1, 2);
        assertEquals(null, CameraShake.sway(w[2], w[0], 1, w[1], w[1], 0, new Vec3(0, 0, 1), 0, false).neck(), "the nuke - no neck");
    }

    /**
     * Shock jitter (presentation, complaint 23.10: “the camera only pushes”): none below 140 dB, full with 5 psi; Shahed in 20/50/100 m
     *  trembles stronger closer, longer for a strong impulse - and no weaker than 3.4.1 (in 50 and 100 m there was 1,5° course); starts
     *  from zero, fades to zero, without a jerk between frames.
     */
    @Test
    void blastTremorFollowsThePressure() {
        assertEquals(0, CameraShake.tremorStrength(CameraShake.TREMOR_LOW_PA * 0.9), 1e-12);
        assertEquals(1, CameraShake.tremorStrength(CameraShake.TREMOR_HIGH_PA * 1.1), 1e-12);
        double kg = 30, last = 2;
        double[] peaks = new double[3];
        int k = 0;
        for (double m : new double[]{20, 50, 100}) {
            CameraShake.reset();
            double pa = 1000 * BlastModel.surfaceOverpressureKpa(m, kg), impulse = BlastModel.chemicalImpulse(m, kg, true);
            double s = CameraShake.tremor(pa, impulse, 0, 7);
            assertTrue(s < last, m + " m: " + s);
            last = s;
            double seconds = CameraShake.tremorSeconds(impulse), prev = 0, most = 0, step = 1.0 / 120;
            assertTrue(seconds >= CameraShake.TREMOR_SHORT && seconds <= CameraShake.TREMOR_LONG);
            for (double t = 0; t <= seconds + 0.1; t += step) {
                double yaw = CameraShake.tremorAt(20 * t)[0];
                assertTrue(Math.abs(yaw - prev) <= 2 * Math.PI * 1.73 * CameraShake.TREMOR_HZ_WEAK * 1.1 * CameraShake.TREMOR_YAW * s * step + 1e-9,
                        m + " m, " + t + " s: jerk");
                most = Math.max(most, Math.abs(yaw));
                prev = yaw;
            }
            assertEquals(0, CameraShake.tremorAt(0)[0], 1e-12, "from scratch");
            assertEquals(0, CameraShake.tremorAt(20 * (seconds + 0.01))[0], 1e-12, "went out");
            peaks[k++] = most;
        }
        assertTrue(peaks[0] > peaks[1] && peaks[1] > peaks[2], java.util.Arrays.toString(peaks));
        assertTrue(peaks[1] >= 1.5 && peaks[2] >= 1.2, "50 m: " + peaks[1] + "°, 100 m: " + peaks[2] + "° (3.4.1 — 1,5°)");
        assertTrue(CameraShake.tremorSeconds(BlastModel.chemicalImpulse(100, 450, true)) > CameraShake.tremorSeconds(BlastModel.chemicalImpulse(100, 30, true)));
        CameraShake.reset();
    }

    /** Shaking listens to vanilla “Screen distortion” (availability): in 0 the camera does not shake at any of its settings. */
    @Test
    void distortionEffectsScaleTheShake() {
        assertEquals(1, CameraShake.strength(1, 1));
        assertEquals(0, CameraShake.strength(2, 0));
        assertEquals(1, CameraShake.strength(2, 0.5), 1e-12);
        assertEquals(1, CameraShake.strength(1, 1.5), 1e-12, "vanilla doesn't give more than that");
    }

    /**
     * Ground push: from zero to zero without jumps, up and down (nod) stronger than sideways, weaker - less; There is no shock in the
     *  projectile camera (as well as impact vibration).
     */
    @Test
    void groundJoltNodsWithoutSteps() {
        CameraShake.reset();
        CameraShake.ground(1, 2, 0, 7);
        double[] start = CameraShake.groundAt(0);
        assertEquals(0, start[0], 1e-12);
        assertEquals(0, start[1], 1e-12);
        double pitch = 0, yaw = 0, before = 0, steepest = 0;
        for (double t = 0; t <= 40; t += 0.05) {
            double[] g = CameraShake.groundAt(t);
            pitch = Math.max(pitch, Math.abs(g[1]));
            yaw = Math.max(yaw, Math.abs(g[0]));
            steepest = Math.max(steepest, Math.abs(g[1] - before));
            before = g[1];
        }
        assertTrue(pitch > yaw, "nod " + pitch + " against sideways swing " + yaw);
        assertTrue(pitch <= CameraShake.GROUND_PITCH + 1e-9);
        // nod speed - no more than 2π·f·A (per 0,05 tick - 1/400 s)
        assertTrue(steepest <= 2 * Math.PI * 1.1 * CameraShake.GROUND_HZ * CameraShake.GROUND_PITCH / 400 * 1.05, "jerk " + steepest);
        assertEquals(0, CameraShake.groundAt(40)[1], 1e-12, "towards the end - zero");
        CameraShake.reset();
        CameraShake.ground(0.3, 2, 0, 7);
        double weak = 0;
        for (double t = 0; t <= 40; t += 0.05) weak = Math.max(weak, Math.abs(CameraShake.groundAt(t)[1]));
        assertTrue(weak < pitch, weak + " against " + pitch);
        assertEquals(0, CameraShake.offsets(5, 0, 0, 0, Double.NaN, 1)[1], 1e-12, "the projectile camera does not nod");
        CameraShake.reset();
    }
}
