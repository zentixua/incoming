package ua.zentix.incoming.guidance;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/**
 * Projectile orientation and turning. Degrees in Minecraft convention: yaw 0 = +Z (south), increasing toward -X
 * (west); pitch > 0 means nose down. Angular speed (°/tick) and acceleration (°/tick²) are limited for smooth
 * flight: the projectile does not snap at a point but enters course along a smooth arc.
 */
public final class FlightController {
    private float yaw;
    private float pitch;
    private float yawRate;
    private float pitchRate;

    public FlightController(float yaw, float pitch) {
        this.yaw = yaw;
        this.pitch = pitch;
    }

    /** Copy with identical angles and angular velocities: simulate turns ahead without touching the projectile. */
    public FlightController copy() {
        FlightController c = new FlightController(yaw, pitch);
        c.yawRate = yawRate;
        c.pitchRate = pitchRate;
        return c;
    }

    public float yaw() {
        return yaw;
    }

    public float pitch() {
        return pitch;
    }

    public void set(float yaw, float pitch) {
        this.yaw = Mth.wrapDegrees(yaw);
        this.pitch = Mth.clamp(pitch, -89f, 89f);
    }

    /** Nose direction (unit vector). */
    public Vec3 forward() {
        return Vec3.directionFromRotation(pitch, yaw);
    }

    /**
     * Pitch: commanded angular speed → limits |ω| ≤ maxRate, |Δω| ≤ maxAccel.
     *
     * @param commandRate desired angular speed, °/tick (> 0 means nose down)
     */
    public void steerPitch(double commandRate, double maxRate, double maxAccel) {
        double w = Mth.clamp(commandRate, -maxRate, maxRate);
        double dw = Mth.clamp(w - pitchRate, -maxAccel, maxAccel);
        pitchRate += (float) dw;
        pitch = Mth.clamp(pitch + pitchRate, -89f, 89f);
    }

    /** Hold pitch: angular speed proportional to error. */
    public void holdPitch(double targetPitch, double gain, double maxRate, double maxAccel) {
        steerPitch((targetPitch - pitch) * gain, maxRate, maxAccel);
    }

    /**
     * Arcing guidance (proportional): ω = 2·G·v·(target angle − pitch)/range, G = 1.6. Gentle turning at long
     * range, vigorous near target; projectile enters target along an arc.
     */
    public void arcPitch(double losPitch, double speed, double distance, double maxRate, double maxAccel) {
        double w = 3.2 * speed * (losPitch - pitch) / Math.max(distance, 1.0);
        steerPitch(w, maxRate, maxAccel);
    }

    /**
     * Target heading: ω = gain·error, |ω| ≤ maxRate °/tick, |Δω| ≤ maxAccel °/tick².
     *
     * @param targetYaw target heading
     */
    public void steerYaw(double targetYaw, double gain, double maxRate, double maxAccel) {
        trackYaw(targetYaw, 0, gain, maxRate, maxAccel);
    }

    /**
     * Curve heading: add gain·error correction to {@code feedForward} (°/tick, how fast the curve itself turns,
     * e.g. v/R on an orbit). Without feed-forward, steady-turn heading lags by ω/gain and the craft drifts
     * outward from the curve.
     */
    public void trackYaw(double targetYaw, double feedForward, double gain, double maxRate, double maxAccel) {
        double err = Mth.wrapDegrees(targetYaw - yaw);
        double w = Mth.clamp(feedForward + err * gain, -maxRate, maxRate);
        double dw = Mth.clamp(w - yawRate, -maxAccel, maxAccel);
        yawRate += (float) dw;
        yaw = Mth.wrapDegrees(yaw + yawRate);
    }

    /** Damp yaw rate (leave heading alone directly above target). */
    public void settleYaw(double maxAccel) {
        yawRate -= (float) Mth.clamp(yawRate, -maxAccel, maxAccel);
        yaw = Mth.wrapDegrees(yaw + yawRate);
    }

    /**
     * Model bank from turn rate, as in a coordinated turn: tan(bank) = v·ω/g. Here g is a game value, not
     * physical: 0.08 blocks/tick² (Minecraft entity gravity) rather than 0.0245. Real g would put a Shahed at
     * 3°/tick on its side (77°); game g banks ~55°, matching the look of a turn at this radius.
     */
    public float bankAngle(double speed) {
        // v in blocks/tick, ω in rad/tick.
        double w = Math.toRadians(yawRate);
        return (float) Mth.clamp(Math.toDegrees(Math.atan(speed * w / 0.08)), -70, 70);
    }

    public void save(CompoundTag tag) {
        tag.putFloat("yaw", yaw);
        tag.putFloat("pitch", pitch);
        tag.putFloat("yaw_rate", yawRate);
        tag.putFloat("pitch_rate", pitchRate);
    }

    public void load(CompoundTag tag) {
        yaw = tag.getFloat("yaw");
        pitch = tag.getFloat("pitch");
        yawRate = tag.getFloat("yaw_rate");
        pitchRate = tag.getFloat("pitch_rate");
    }

    /** Angle to a point: [yaw, pitch] in degrees. */
    public static float[] anglesTo(Vec3 from, Vec3 to) {
        double dx = to.x - from.x, dy = to.y - from.y, dz = to.z - from.z;
        double h = Math.sqrt(dx * dx + dz * dz);
        float yaw = (float) (Mth.atan2(dz, dx) * Mth.RAD_TO_DEG) - 90f;
        float pitch = (float) -(Mth.atan2(dy, h) * Mth.RAD_TO_DEG);
        return new float[]{Mth.wrapDegrees(yaw), pitch};
    }
}
