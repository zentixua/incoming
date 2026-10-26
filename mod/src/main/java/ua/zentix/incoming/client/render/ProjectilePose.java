package ua.zentix.incoming.client.render;

import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import ua.zentix.incoming.entity.FlightPhase;
import ua.zentix.incoming.entity.StrikeProjectile;

/**
 * Everything the projectile model needs per frame ({@link WeaponModels}): position, orientation, flight phase and
 * phase time, flight destination. Filled from entity while present on client; otherwise from the server packet
 * track ({@code client.far.FarFlightView}): shared model and animation code nearby and at distance. Same step as
 * Mojang's 1.21.2 ({@code EntityRenderState}). Object lives across frames: no new pose needed each frame.
 */
public final class ProjectilePose {
    /** Position, blocks. */
    public double x, y, z;
    /** Heading, pitch and roll, degrees (as on the entity). */
    public float yaw, pitch, roll;
    public FlightPhase phase = FlightPhase.READY;
    /** Ticks in current phase and since flight start, including partial tick. */
    public float phaseAge, age;
    /** Target point, blocks. */
    public double aimX, aimY, aimZ;
    /** Draw simplified part copies ({@link WeaponModels.Mesh#lod}): model small on screen. */
    public boolean coarse;

    /** From client entity: position and angles interpolated between ticks, latest received roll. */
    public ProjectilePose set(StrikeProjectile e, float partial) {
        Vec3 p = e.getPosition(partial);
        x = p.x;
        y = p.y;
        z = p.z;
        yaw = Mth.rotLerp(partial, e.yRotO, e.getYRot());
        pitch = Mth.lerp(partial, e.xRotO, e.getXRot());
        roll = e.roll();
        phase = e.flightPhase();
        phaseAge = e.phaseAge() + partial;
        age = e.age() + partial;
        Vec3 aim = e.aimPoint();
        aimX = aim.x;
        aimY = aim.y;
        aimZ = aim.z;
        coarse = false;
        return this;
    }

    /** Same values as o. */
    public ProjectilePose copy(ProjectilePose o) {
        x = o.x;
        y = o.y;
        z = o.z;
        yaw = o.yaw;
        pitch = o.pitch;
        roll = o.roll;
        phase = o.phase;
        phaseAge = o.phaseAge;
        age = o.age;
        aimX = o.aimX;
        aimY = o.aimY;
        aimZ = o.aimZ;
        coarse = o.coarse;
        return this;
    }

    /** Model (nose +Z) rotation into world: heading, pitch, roll. */
    public Quaternionf rotation(Quaternionf out) {
        return out.rotationYXZ(-yaw * Mth.DEG_TO_RAD, pitch * Mth.DEG_TO_RAD, roll * Mth.DEG_TO_RAD);
    }
}
