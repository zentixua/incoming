package ua.zentix.incoming.entity;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import ua.zentix.incoming.guidance.DroneAutopilot;
import ua.zentix.incoming.strike.WeaponSpec;
import ua.zentix.incoming.strike.WeaponType;
import ua.zentix.incoming.target.Target;

import java.util.UUID;

/**
 * Shahed-136-style kamikaze drone: ≈150 km/h (2.1 blocks/tick). Launcher start: a solid booster under the tail
 * burns ~2 s and is dropped; a pusher propeller then drives the climb and turn onto the route. Cruises above
 * terrain and no lower than target+30; on the final leg dives in an arc when the target is 18° below the horizon
 * and the line to it is clear of blocks; accelerates to 3 blocks/tick in the dive. Post-launch flight law: {@link
 * DroneAutopilot}.
 */
public class DroneEntity extends StrikeProjectile {
    /** Spec: speeds, route and climb turn limits, cruise height above launch and target, launch. */
    private static final WeaponSpec.Airframe AIR = WeaponSpec.DRONE.airframe();

    private final DroneAutopilot autopilot = new DroneAutopilot(AIR);

    public DroneEntity(EntityType<? extends DroneEntity> type, Level level) {
        super(type, level);
    }

    @Override
    public WeaponType weapon() {
        return WeaponType.DRONE;
    }

    /** Already airborne (approach from afar, tests): flight height = max(launch, target+30, terrain+20). */
    @Override
    public void launch(Vec3 pos, Target target, Vec3 targetPoint, @Nullable UUID owner) {
        double y = autopilot.airborneAltitude(pos, targetPoint, surfaceY(level(), pos.x, pos.z));
        Vec3 start = new Vec3(pos.x, y, pos.z);
        super.launch(start, target, targetPoint, owner);
        speed = AIR.cruiseSpeed();
        altitude.reset(y);
        setPhase(FlightPhase.CRUISE);
    }

    @Override
    public void placeOnLauncher(Vec3 rail, float yaw, float elevation, int readyTicks, int hiddenTicks, Target target, Vec3 targetPoint,
                                @Nullable UUID owner) {
        super.placeOnLauncher(rail, yaw, elevation, readyTicks, hiddenTicks, target, targetPoint, owner);
        autopilot.fromLauncher(rail, targetPoint);
    }

    @Override
    protected void serverTick(ServerLevel level) {
        Vec3 aim = updateTarget(level);
        if (launchTick(level)) return;
        Vec3 nav = navPoint(aim);
        autopilot.fly(craft, aim, nav, onFinalLeg());
        advance(level, aim, AIR.reachPad());
    }

    @Override
    protected void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        autopilot.setCruiseAlt(tag.getDouble("cruise_alt"));
    }

    @Override
    protected void addAdditionalSaveData(CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        tag.putDouble("cruise_alt", autopilot.cruiseAlt());
    }
}
