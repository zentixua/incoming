package ua.zentix.incoming.scenario.trailer;

import net.minecraft.client.Minecraft;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Marker;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.ViewportEvent;
import org.jetbrains.annotations.Nullable;

import java.util.function.DoubleSupplier;
import java.util.function.Supplier;

/**
 * Trailer movie camera: the camera is a separate entity that does not exist in the world (the player is visible as an actor or hidden),
 *  its place and view of each frame are taken from {@link Path} according to the time of the shot with a tick share - the movement is smooth at any
 *  game speed. Roll and focal length - through camera events.
 */
final class CineCamera {
    /** Camera position: location, heading and pitch (like Minecraft), roll, vertical field of view. */
    record Pose(Vec3 pos, float yaw, float pitch, float roll, double fov) {
        static Pose look(Vec3 from, Vec3 at, float roll, double fov) {
            Vec3 d = at.subtract(from);
            double h = Math.sqrt(d.x * d.x + d.z * d.z);
            float yaw = (float) (Mth.atan2(d.z, d.x) * Mth.RAD_TO_DEG) - 90f;
            float pitch = (float) (-Mth.atan2(d.y, h) * Mth.RAD_TO_DEG);
            return new Pose(from, yaw, pitch, roll, fov);
        }
    }

    /** Camera movement per shot: {@code t} - tick from the beginning of the shot (with a fraction). */
    interface Path {
        Pose at(double t);
    }

    @Nullable
    private static Marker eye;
    @Nullable
    private static Path path;
    private static Pose pose;
    /** Frame angle for the player's view (NaN - as in the settings). */
    static double viewFov = Double.NaN;

    private CineCamera() {}

    static boolean active() {
        return path != null;
    }

    static void use(Path p) {
        Minecraft mc = Minecraft.getInstance();
        if (eye == null || eye.level() != mc.level) eye = new Marker(EntityType.MARKER, mc.level);
        path = p;
        apply(0);
        mc.setCameraEntity(eye);
    }

    static void release() {
        path = null;
        viewFov = Double.NaN;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) mc.setCameraEntity(mc.player);
    }

    @Nullable
    static Pose pose() {
        return path == null ? null : pose;
    }

    /** Place the camera at the moment {@code t}: old position = new, so the interpolation of the game does not interfere. */
    static void apply(double t) {
        if (path == null || eye == null) return;
        pose = path.at(t);
        eye.setPos(pose.pos());
        eye.xo = eye.xOld = pose.pos().x;
        eye.yo = eye.yOld = pose.pos().y;
        eye.zo = eye.zOld = pose.pos().z;
        eye.setYRot(pose.yaw());
        eye.yRotO = pose.yaw();
        eye.setXRot(pose.pitch());
        eye.xRotO = pose.pitch();
    }

    static void angles(ViewportEvent.ComputeCameraAngles e) {
        if (path != null && pose != null) e.setRoll(e.getRoll() + pose.roll());
    }

    static void fov(ViewportEvent.ComputeFov e) {
        if (path != null && pose != null) {
            e.setFOV(pose.fov());
        } else if (!Double.isNaN(viewFov) && e.usedConfiguredFov()) {
            // player view: shot angle on top of settings, binocular zoom (modifier) preserved
            e.setFOV(e.getFOV() * viewFov / Minecraft.getInstance().options.fov().get());
        }
    }

    // ---------------------------------------------------------------- ready-made movements

    static double smooth(double x) {
        x = Mth.clamp(x, 0, 1);
        return x * x * (3 - 2 * x);
    }

    /** Travel from {@code a} to {@code b} for {@code ticks} with smooth acceleration and braking, look at {@code at}. */
    static Path dolly(Vec3 a, Vec3 b, double ticks, Supplier<Vec3> at, double fov) {
        return t -> Pose.look(a.lerp(b, smooth(t / ticks)), at.get(), 0, fov);
    }

    /** Travel at a constant speed (for long spans where acceleration is noticeable). */
    static Path glide(Vec3 a, Vec3 b, double ticks, Supplier<Vec3> at, double fov) {
        return t -> Pose.look(a.lerp(b, Mth.clamp(t / ticks, 0, 1)), at.get(), 0, fov);
    }

    /** Flying around a point: radius, height above it, initial angle (degrees, 0 - to +Z) and speed (degrees per tick). */
    static Path orbit(Supplier<Vec3> center, double radius, double height, double startDeg, double degPerTick, double fov) {
        return t -> {
            Vec3 c = center.get();
            double a = Math.toRadians(startDeg + degPerTick * t);
            Vec3 from = c.add(Math.sin(a) * radius, height, Math.cos(a) * radius);
            return Pose.look(from, c, 0, fov);
        };
    }

    /**
     * Chase: camera behind the entity along its course ({@code back}), above ({@code up}) and to the side ({@code side}), looking at
     *  slightly ahead of it. The position of the entity is with a tick, as the game draws it.
     */
    static Path chase(Supplier<Entity> target, double back, double up, double side, double lead, float roll, double fov) {
        return chase(target, Vec3.ZERO, new Vec3(0, 0, 1), back, up, side, lead, roll, fov);
    }

    /**
     * The same, but there is no target yet, the camera is at {@code start} (course {@code heading}): an invisible person walks behind the camera,
     * , and without this he would go to the zero of the world - the chunks at the target would be unloaded, and the projectile would not return to the world.
     */
    static Path chase(Supplier<Entity> target, Vec3 start, Vec3 heading, double back, double up, double side, double lead,
                      float roll, double fov) {
        final Vec3[] last = {start, heading.normalize()};
        return t -> {
            Entity e = target.get();
            if (e != null) {
                float pt = partial();
                Vec3 p = e.getPosition(pt);
                Vec3 v = p.subtract(e.xo, e.yo, e.zo);
                if (v.lengthSqr() > 1e-4) last[1] = v.normalize();
                last[0] = p;
            }
            Vec3 dir = last[1];
            Vec3 flat = new Vec3(dir.x, 0, dir.z);
            flat = flat.lengthSqr() < 1e-6 ? new Vec3(0, 0, 1) : flat.normalize();
            Vec3 right = new Vec3(-flat.z, 0, flat.x);
            Vec3 from = last[0].subtract(dir.scale(back)).add(0, up, 0).add(right.scale(side));
            return Pose.look(from, last[0].add(dir.scale(lead)), roll, fov);
        };
    }

    /** A fixed camera that tracks a point (for example, a projectile). */
    static Path track(Vec3 from, Supplier<Vec3> at, double fov) {
        return t -> Pose.look(from, at.get(), 0, fov);
    }

    /** The same with a variable field of view (for example, “zoom” behind a distant projectile). */
    static Path track(Vec3 from, Supplier<Vec3> at, DoubleSupplier fov) {
        return t -> Pose.look(from, at.get(), 0, fov.getAsDouble());
    }

    /** The view smoothly moves from one point to another behind {@code ticks}, the camera moves from {@code a} to {@code b}. */
    static Path pan(Vec3 a, Vec3 b, Supplier<Vec3> lookA, Supplier<Vec3> lookB, double ticks, double fov) {
        return t -> {
            double s = smooth(t / ticks);
            return Pose.look(a.lerp(b, s), lookA.get().lerp(lookB.get(), s), 0, fov);
        };
    }

    /** The same trajectory with a slow “zoom” (field of view from {@code fovA} to {@code fovB}). */
    static Path zoom(Path p, double fovA, double fovB, double ticks) {
        return t -> {
            Pose q = p.at(t);
            return new Pose(q.pos(), q.yaw(), q.pitch(), q.roll(), Mth.lerp(smooth(t / ticks), fovA, fovB));
        };
    }

    static float partial() {
        return Minecraft.getInstance().getTimer().getGameTimeDeltaPartialTick(true);
    }
}
