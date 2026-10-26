package ua.zentix.incoming.entity;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import ua.zentix.incoming.Incoming;
import ua.zentix.incoming.guidance.Bearing;
import ua.zentix.incoming.guidance.BombDrop;
import ua.zentix.incoming.registry.ModEntities;
import ua.zentix.incoming.strike.FlightScale;
import ua.zentix.incoming.strike.VirtualFlights;
import ua.zentix.incoming.strike.WeaponSpec;
import ua.zentix.incoming.strike.WeaponType;
import ua.zentix.incoming.target.Target;
import ua.zentix.incoming.util.Terrain;

import java.util.UUID;

/**
 * B-2 at +170 altitude above the bomb impact point (up to 12 km on the flight scale, {@link
 * WeaponSpec.Airframe#cruiseHeight()}; retargeting higher or lower, a roof or tower at the target, changes
 * altitude gently, no steeper than 6°). Approaches from afar (most of the path outside the loaded world), flies
 * straight at 12 blocks/tick (240 m/s), releases a bunker-buster bomb at cruising altitude ~145 horizontal blocks
 * before the target (at any altitude, the same angle to the point, {@link #releaseLine}; the bomb inherits its
 * speed, controls lower the nose, and it enters almost vertically), then turns and climbs away. If off altitude
 * (materialized above a hill, retargeted higher or lower), releases from its current height, farther or closer:
 * the B-2 simulates the bomb fall ({@link BombDrop#releaseNow}); if every release misses (point too close ahead,
 * behind, or above), makes another approach.
 */
public class BomberEntity extends StrikeProjectile {
    /** B-2 spec: speed, turn (radius ~690 blocks), altitude above the release target point. */
    private static final WeaponSpec.Airframe AIR = WeaponSpec.BUNKER.airframe();
    /**
     * Release range at game altitude ({@link #GAME_ALTITUDE}): the release angle to the point is the same at any
     * height; the bomb lowers its nose and follows the same line to the point ({@link #releaseLine}).
     */
    private static final double GAME_RELEASE = WeaponSpec.BUNKER.route().gameFinalLeg();
    /** Game altitude above the release target point. */
    private static final double GAME_ALTITUDE = AIR.gameHeight();
    /** Release range divided by height above the point. */
    private static final double RELEASE_RATIO = GAME_RELEASE / GAME_ALTITUDE;
    /** Bomb fall from game altitude to impact, ticks (estimate: ~20). */
    private static final int FALL_TICKS = 20;
    /** Altitude change: pitch no steeper than this, ° (B-2 climbs and descends gently). */
    private static final double LEVEL_CHANGE_PITCH = 6;
    /** Pitch per block of altitude error, °: smooth leveling near cruising altitude. */
    private static final double PITCH_PER_BLOCK = 0.15;
    /** After release, flies away and disappears after this many ticks (or earlier at the loaded-world edge). */
    private static final int EGRESS_TICKS = 400;

    /** Bomb released or strike recalled before release ({@link #recall}): B-2 departs. */
    private boolean released;
    /** Underground point the bomb penetrates toward (cave target); null means drill downward. */
    @Nullable
    private BlockPos goal;
    /** Departure turn after release: +1 left, -1 right. */
    private float breakSide = 1;

    public BomberEntity(EntityType<? extends BomberEntity> type, Level level) {
        super(type, level);
    }

    @Override
    public WeaponType weapon() {
        return WeaponType.BUNKER;
    }

    /**
     * Keeps chunks like other projectiles, including departure after release: without its own ticket it enters a
     * chunk ticking on an oncoming salvo B-2's ticket; that aircraft leaves and this B-2 freezes in a non-ticking
     * chunk, logging “unloaded with chunk in flight” twice at one point (B-2 ×30 salvos). No virtual flight after
     * release: nobody would see departure, so it disappears at the ticking-chunk edge.
     */
    @Override
    protected boolean fliesVirtually() {
        return !released;
    }

    @Override
    protected boolean acceptsRetarget() {
        return !released;
    }

    /** The bomber passes the target and continues: its path does not end there. */
    @Override
    @Nullable
    protected Vec3 pathEnd() {
        return null;
    }

    /**
     * Terrain clearance on materialization only prevents appearing in a building or slope ahead: the B-2 already
     * flies +170 above the bomb impact point. A 120-block margin would lift it above altitude over every building
     * or hill more than 50 blocks high on course; materializing near the target would leave too little time to
     * descend, releasing above altitude and farther from the point.
     */

    public boolean hasReleased() {
        return released;
    }

    /**
     * Until bomb impact: reach the release point plus fall time ({@link #fallTicks}; no second approach, which
     * nobody knows in advance).
     */
    @Override
    public int etaTicks() {
        if (tracker == null) return 0;
        Vec3 aim = tracker.point();
        Bearing b = bearingTo(aim);
        return (int) Math.ceil(Math.max(0, b.horizontal() - releaseLine(getY() - aim.y)) / AIR.cruiseSpeed()) + fallTicks();
    }

    /** B-2 velocity in level flight at heading {@code yaw}, blocks/tick: the bomb inherits it. */
    public static Vec3 levelVelocity(float yaw) {
        return Vec3.directionFromRotation(0, yaw).scale(AIR.cruiseSpeed());
    }

    /**
     * Release range from {@code height} above the point: same angle to the point as at game altitude. Release may
     * occur earlier, at the edge of the window where the bomb still hits ({@link BombDrop#releaseNow}).
     */
    public static double releaseLine(double height) {
        return height * GAME_RELEASE / GAME_ALTITUDE;
    }

    /** Altitude above the bomb impact point on the world flight scale: game +170, real 12 km. */
    private static double altitude() {
        return AIR.cruiseHeight();
    }

    /** Release range at cruising altitude, horizontal distance to the point. */
    private static double releaseDistance() {
        return releaseLine(altitude());
    }

    /**
     * Bomb fall from cruising altitude to impact, ticks (estimate): path grows with altitude relative to game
     * altitude; speed grows with the B-2's release speed. At game scale, {@link #FALL_TICKS}.
     */
    private static int fallTicks() {
        double x = FlightScale.x();
        return (int) Math.round(FALL_TICKS * AIR.cruiseHeight(x) / GAME_ALTITUDE / AIR.stretch(x));
    }

    /**
     * @param surface surface point above the target (bomb impact point)
     * @param goal underground target or null
     */
    public void launch(Vec3 pos, Vec3 surface, @Nullable BlockPos goal, @Nullable UUID owner) {
        Vec3 start = new Vec3(pos.x, surface.y + altitude(), pos.z);
        super.launch(start, new Target.Point(surface), surface, owner);
        this.goal = goal;
        this.speed = AIR.cruiseSpeed();
        this.breakSide = random.nextBoolean() ? 1 : -1;
        setPhase(FlightPhase.CRUISE);
    }

    @Override
    protected void serverTick(ServerLevel level) {
        Vec3 aim = tracker.point();
        Bearing b = bearingTo(aim);
        // Cruising altitude is above the bomb impact point: launch uses the first surface estimate; retargeting lower
        // or higher, or a roof or tower absent from the generator estimate, changes it.
        double echelon = 0;
        if (!released) {
            Vec3 drop = surfaceUnder(level, aim);
            echelon = drop.y + altitude();
            // Release when a bomb dropped now would hit the point and waiting would not improve it (BombDrop.releaseNow): from 170
            // at 100 blocks out, a bomb at B-2 speed overshoots by ~75; a point 40 blocks ahead, by ~150; behind
            // (the bomb falls along the heading and cannot steer backward), do not release at all.
            if (ahead(drop) && BombDrop.releaseNow(position(), flight.forward().scale(speed), drop, RELEASE_RATIO)) {
                release(level, drop);
            }
        }
        if (exhausted() && !released) {
            Incoming.LOG.warn("B-2 {} ran out of endurance without dropping its bomb, removed at {} (aim point {}, off-world {})", getUUID(),
                    blockPosition(), BlockPos.containing(aim), isVirtual());
        }
        // No reason to fly outside the world after release: nobody would see departure.
        if (exhausted() || released && (phaseAge() >= EGRESS_TICKS || isVirtual())) {
            discard();
            return;
        }
        WeaponSpec.Agility egress = AIR.handling().climb(), cruise = AIR.handling().cruise();
        if (released) {
            // Departure: turn 70° off course and climb.
            flight.holdPitch(-6, 0.05, egress.pitchRate(), egress.pitchAccel());
            if (phaseAge() < 60) flight.steerYaw(flight.yaw() + 20 * breakSide, 0.08, egress.turnRate(), egress.turnAccel());
            else flight.settleYaw(egress.turnAccel());
        } else {
            flight.holdPitch(Mth.clamp((getY() - echelon) * PITCH_PER_BLOCK, -LEVEL_CHANGE_PITCH, LEVEL_CHANGE_PITCH), 0.1,
                    cruise.pitchRate(), cruise.pitchAccel());
        }
        if (!released && b.horizontal() > releaseDistance() + 40) {
            // Release point inside the turning circle (retargeted to the side, overshot): at maximum turn rate it would
            // circle forever; fly straight until the point is outside the circle, then approach again.
            if (insideTurn(aim, AIR.turnRate())) flight.settleYaw(cruise.turnAccel());
            else flight.steerYaw(b.yaw(), 0.1, AIR.turnRate(), cruise.turnAccel());
        }
        // Loses speed in the departure climb; holds cruise speed at altitude.
        throttle(AIR.cruiseSpeed());
        Vec3 dir = flight.forward();
        Vec3 next = position().add(dir.scale(speed));
        if (leavesTickingChunks(level, next)) return;
        moveAlong(level, next, dir);
    }

    /**
     * Owner recall before release: no bomb; B-2 turns away and departs as after release. After release there is
     * nothing to recall.
     */
    @Override
    public boolean recall(ServerLevel level) {
        if (released) return false;
        released = true;
        setPhase(FlightPhase.EGRESS);
        Incoming.LOG.info("B-2 {} recalled at {}: leaving without a drop (off-world {})", getUUID(), blockPosition(), isVirtual());
        return true;
    }

    /** @param drop surface point where the bomb falls */
    private void release(ServerLevel level, Vec3 drop) {
        released = true;
        setPhase(FlightPhase.EGRESS);
        BunkerBusterEntity bomb = ModEntities.BUNKER_BUSTER.get().create(level);
        if (bomb == null) return;
        bomb.drop(position().add(0, -BombDrop.DROP_BELOW, 0), flight.forward().scale(speed), drop, goal, ownerId());
        bomb.setNuclear(nuclear);
        shareAir(bomb);
        // The bomb UUID from projectile warnings identifies its B-2.
        Incoming.LOG.info("B-2 {} dropped bomb {} at {}, {} blocks from the aim point, {} above it (off-world {})", getUUID(), bomb.getUUID(),
                blockPosition(), Math.round(position().subtract(drop).horizontalDistance()), Math.round(getY() - drop.y), isVirtual());
        if (isVirtual()) VirtualFlights.launch(level, bomb);
        else level.addFreshEntity(bomb);
    }

    /**
     * Release point ahead or almost under the belly, where the bomb can fall: behind is already “passed” ({@link
     * BombDrop#passed}: falls steeply along the heading, without steering).
     */
    private boolean ahead(Vec3 drop) {
        return !BombDrop.passed(position(), drop, flight.yaw());
    }

    /**
     * Where to fall: at launch, the surface under the target may be a generator estimate (chunk unready); by
     * release, the target chunk is usually ready and supplies the height.
     */
    private static Vec3 surfaceUnder(ServerLevel level, Vec3 aim) {
        Terrain.Surface ground = Terrain.estimate(level, Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, Mth.floor(aim.x), Mth.floor(aim.z), Terrain.Allowed.CHUNK);
        return ground.known() ? new Vec3(aim.x, ground.y() - 0.5, aim.z) : aim;
    }

    @Override
    protected void impact(ServerLevel level, Vec3 point, @Nullable Entity hitEntity) {
        discard();
    }

    @Override
    protected void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        released = tag.getBoolean("released");
        goal = NbtUtils.readBlockPos(tag, "goal").orElse(null);
        breakSide = tag.contains("break_side") ? tag.getFloat("break_side") : 1;
    }

    @Override
    protected void addAdditionalSaveData(CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        tag.putBoolean("released", released);
        if (goal != null) tag.put("goal", NbtUtils.writeBlockPos(goal));
        tag.putFloat("break_side", breakSide);
    }
}
