package ua.zentix.incoming.entity;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import ua.zentix.incoming.guidance.FlightController;
import ua.zentix.incoming.strike.WeaponSpec;
import ua.zentix.incoming.strike.WeaponType;
import ua.zentix.incoming.target.Target;

import java.util.UUID;

/**
 * Intercontinental ballistic missile, boost phase only (DESIGN-nuke §7): vertical launch from the ground near the
 * launching player, first-stage acceleration from spec thrust and mass ({@link WeaponSpec.Ascent}: ~1.5 g at
 * liftoff, increasing as propellant burns), strictly vertical from the silo, then a slight tilt toward the target
 * heading and exit above the world ceiling, after ~8.5 s at y 64. Subsequent flight is a scheduled-strike timer;
 * the missile itself detonates nothing.
 */
public class IcbmEntity extends StrikeProjectile {
    /** ICBM spec: maximum boost speed, length, range. */
    private static final WeaponSpec.Airframe AIR = WeaponSpec.NUKE.airframe();
    /** First-stage boost (spec). */
    private static final WeaponSpec.Ascent ASCENT = WeaponSpec.NUKE.ascent();
    private static final Vec3 UP = new Vec3(0, 1, 0);

    public IcbmEntity(EntityType<? extends IcbmEntity> type, Level level) {
        super(type, level);
    }

    @Override
    public WeaponType weapon() {
        return WeaponType.NUKE;
    }

    @Override
    protected boolean fliesVirtually() {
        return false;
    }

    @Override
    protected boolean acceptsRetarget() {
        return false;
    }

    /** ICBM is always nuclear, although the scheduled strike ({@code NuclearStrikes}), not the missile, detonates. */
    @Override
    public boolean isNuclear() {
        return true;
    }

    /** Place on the launch pad nose up. */
    public void prepare(Vec3 pad, Vec3 target, @Nullable UUID owner) {
        super.launch(pad.add(0, 9, 0), new Target.Point(target), target, owner);
        float[] a = FlightController.anglesTo(pad, target);
        flight.set(a[0], -89);
        setYRot(a[0]);
        setXRot(-89);
        // Hot silo launch: from rest on the launch table.
        speed = 0;
        setPhase(FlightPhase.IGNITION);
    }

    @Override
    protected void serverTick(ServerLevel level) {
        speed = Math.min(AIR.cruiseSpeed(), ASCENT.step(speed, age));
        if (age >= 40) setPhase(FlightPhase.BOOST);
        // Before tilting, strictly along the silo axis (controller pitch stops at 89°); then nose turns smoothly toward target heading.
        if (age > ASCENT.pitchOverTick()) flight.holdPitch(-ASCENT.climbPitch(), 0.02, 0.6, 0.05);
        Vec3 dir = age > ASCENT.pitchOverTick() ? flight.forward() : UP;
        Vec3 next = position().add(dir.scale(speed));
        // Beyond the sky, at range exhaustion, or at the ticking-chunk edge, the ICBM is no longer needed: the “strike” flies on (NuclearStrikes).
        if (next.y > level.getMaxBuildHeight() + ASCENT.ceiling() || exhausted()) {
            discard();
            return;
        }
        if (leavesTickingChunks(level, next)) return;
        // Ahead chunk follows heading over ground: the missile is almost vertical, so a chunk “16 blocks ahead along the nose” would be
        // only a couple of horizontal blocks away; it would enter the next chunk before entities start ticking there.
        moveAlong(level, next, Vec3.directionFromRotation(0, flight.yaw()));
    }

    @Override
    protected void impact(ServerLevel level, Vec3 point, @Nullable Entity hitEntity) {
        discard();
    }

}
