package ua.zentix.incoming.entity;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import ua.zentix.incoming.guidance.MissileAutopilot;
import ua.zentix.incoming.nuclear.NuclearStrikes;
import ua.zentix.incoming.nuclear.NuclearWarhead;
import ua.zentix.incoming.util.Terrain;
import ua.zentix.incoming.strike.WeaponSpec;
import ua.zentix.incoming.strike.WeaponType;
import ua.zentix.incoming.target.Target;

import java.util.UUID;

/**
 * Cruise missile: 80 m/s (4 blocks/tick, ≈290 km/h). Launch from an inclined canister: a launch booster carries
 * it upward (~2 s), separates, and the turbojet starts, accelerating to cruise, descending to terrain-hugging
 * flight 12 blocks above terrain, and following a detour route. On the final leg, 160 blocks from the target, it
 * pops up and dives in an arc, accelerating to 5 blocks/tick. With a nuclear warhead in airburst mode, it
 * detonates above the target.
 *
 * <p>Speed is slower than real (Tomahawk ~250 m/s) so the approach is visible: witnesses see a real missile
 * kilometers away, whereas in game it appears within render distance (12 chunks, 192 blocks). At 11.5 blocks/tick
 * it would cross that in under a second, and at 12 server TPS in 11-block jumps; at 4 blocks/tick the approach
 * from render distance with popup and dive lasts ~2.5 s (~4 s at 12 TPS), a pass across the field of view ~5 s.
 * The {@code missile_flight_time} setting determines time to impact: route length is speed × time. The last
 * {@link WeaponSpec.Airframe#visibleLeg} blocks fly in the world (approach strip) when a player is at the target,
 * rather than outside it; otherwise it would appear only within server simulation distance of the player.
 * Post-launch flight law: {@link MissileAutopilot}.
 */
public class CruiseMissileEntity extends StrikeProjectile {
    /** Spec: cruise and dive speeds, turn limits, terrain-hugging height, launch, approach strip. */
    private static final WeaponSpec.Airframe AIR = WeaponSpec.MISSILE.airframe();
    /** Cruise speed for a missile saved in flight without the {@code cruise_speed} key (2.3.0 and earlier). */
    private static final double LEGACY_CRUISE_SPEED = 11.5;

    private final MissileAutopilot autopilot = new MissileAutopilot(AIR);

    public CruiseMissileEntity(EntityType<? extends CruiseMissileEntity> type, Level level) {
        super(type, level);
    }

    @Override
    public WeaponType weapon() {
        return WeaponType.MISSILE;
    }

    /**
     * Already airborne (approach from afar, tests): terrain-hugging flight, 12 blocks above terrain and no lower
     * than target+12.
     */
    @Override
    public void launch(Vec3 pos, Target target, Vec3 targetPoint, @Nullable UUID owner) {
        double y = autopilot.airborneAltitude(targetPoint, surfaceY(level(), pos.x, pos.z));
        Vec3 start = new Vec3(pos.x, y, pos.z);
        super.launch(start, target, targetPoint, owner);
        speed = AIR.cruiseSpeed();
        altitude.reset(y);
        autopilot.approach(Math.sqrt(targetPoint.subtract(start).horizontalDistanceSqr()));
        setPhase(FlightPhase.CRUISE);
    }

    @Override
    protected void onRetarget() {
        autopilot.retarget(craft, tracker.point());
    }

    /** Route complete: popup only if the final leg to target has room (operator waypoints can leave a short leg). */
    @Override
    protected void onRouteDone(Vec3 aim) {
        autopilot.approach(bearingTo(aim).horizontal());
    }

    @Override
    protected void serverTick(ServerLevel level) {
        Vec3 aim = updateTarget(level);
        if (launchTick(level)) return;
        Vec3 nav = navPoint(aim);
        // Nuclear warhead, airburst: above the target before reaching ground (the warhead sets burst height).
        if (nuclear != null && nuclear.airBurst() && onFinalLeg() && armed() && bearingTo(aim).horizontal() <= speed * 2 + 12
                && (!isVirtual() || Terrain.ready(level, BlockPos.containing(aim)))) {
            impact(level, aim, null);
            return;
        }
        autopilot.fly(craft, aim, nav, onFinalLeg());
        advance(level, aim, AIR.reachPad());
    }

    @Override
    protected void impact(ServerLevel level, Vec3 point, @Nullable Entity hitEntity) {
        if (nuclear == null) {
            super.impact(level, point, hitEntity);
            return;
        }
        discard();
        NuclearWarhead.detonate(level, NuclearStrikes.ground(level, point), nuclear.yieldKt(), nuclear.airBurst(), ownerId());
    }

    @Override
    protected void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        autopilot.setPopUp(!tag.contains("pop_up") || tag.getBoolean("pop_up"));
        // Saved at a higher cruise speed (2.3.0 and earlier): now flies at the new one; route speed drops automatically
        // to cruise, but popup would retain the old speed. Remaining path in blocks is speed-independent (range).
        if (savedCruiseSpeed(tag) > AIR.cruiseSpeed()) speed = Math.min(speed, AIR.cruiseSpeed());
    }

    /** Saved cruise speed; without {@code cruise_speed}, 2.3.0 and earlier. */
    @Override
    protected double savedCruiseSpeed(CompoundTag tag) {
        return tag.contains("cruise_speed") ? tag.getDouble("cruise_speed") : LEGACY_CRUISE_SPEED;
    }

    @Override
    protected void addAdditionalSaveData(CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        tag.putBoolean("pop_up", autopilot.popUp());
        tag.putDouble("cruise_speed", AIR.cruiseSpeed());
    }
}
