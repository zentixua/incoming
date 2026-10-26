package ua.zentix.incoming.gametest;

import com.mojang.authlib.GameProfile;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.animal.Cow;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import ua.zentix.incoming.Incoming;
import ua.zentix.incoming.entity.CruiseMissileEntity;
import ua.zentix.incoming.entity.DroneEntity;
import ua.zentix.incoming.entity.StrikeProjectile;
import ua.zentix.incoming.registry.ModAttachments;
import ua.zentix.incoming.registry.ModEntities;
import ua.zentix.incoming.strike.CameraLink;
import ua.zentix.incoming.strike.ServerActions;
import ua.zentix.incoming.strike.WeaponSpec;
import ua.zentix.incoming.target.Sight;
import ua.zentix.incoming.target.Target;
import ua.zentix.incoming.target.TargetTracker;

import java.util.Optional;
import java.util.UUID;

/**
 * Player order according to the rules ({@link ServerActions#sighted}): by player - only seeing him, moving target - noticed
 * ({@link Target.Sighted}); only the weapon with the camera follows her while the operator holds it in the frame ({@link CameraLink}).
 */
@GameTestHolder(Incoming.MOD_ID)
@PrefixGameTestTemplate(false)
public final class PursuitGameTests {
    private PursuitGameTests() {}

    /**
     * The tracker tracks a noticed target only while it is visible: the invisible one remains where it was seen and is not lost;
     *  the missing one is lost, the dot is the last one seen. Saving and loading remembers that the target is spotted.
     */
    @GameTest(template = "pad", batch = "pursuit")
    public static void sightedTargetMovesOnlyInSight(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        Vec3 seen = Vec3.atBottomCenterOf(h.absolutePos(new BlockPos(2, 1, 2)));
        ArmorStand stand = new ArmorStand(level, seen.x, seen.y, seen.z);
        level.addFreshEntity(stand);
        Target sighted = new Target.Sighted(new Target.OfEntity(stand.getUUID(), Vec3.ZERO), seen);
        TargetTracker tracker = new TargetTracker(sighted, seen);
        h.assertValueEqual(TargetTracker.load(tracker.save()).target(), sighted, "spotted target after loading");

        Vec3 moved = seen.add(5, 0, 0);
        stand.teleportTo(moved.x, moved.y, moved.z);
        h.assertTrue(tracker.tick(level, q -> false) == 0 && tracker.point().equals(seen), "the target outside the frame has moved the point");
        h.assertFalse(tracker.inSight() || tracker.isLost(), "target out of frame visible or lost");
        h.assertTrue(Math.abs(tracker.tick(level, q -> q.distanceTo(moved) < 1e-6) - 5) < 1e-6 && tracker.inSight(),
                "the target is not visible in the frame");
        h.assertTrue(tracker.point().distanceTo(moved) < 1e-6, "the point is not where the target is visible");

        stand.discard();
        tracker.tick(level, q -> true);
        h.assertTrue(tracker.isLost() && tracker.point().distanceTo(moved) < 1e-6, "missing target is not lost or the point has moved");
        h.succeed();
    }

    /**
     * The player's order follows the rules: he does not hit a player he cannot see, but a player who is visible, he hits where he was seen; mob and
     *  device - also noticed, without verification; a point remains a point.
     */
    @GameTest(template = "pad", batch = "pursuit_players")
    public static void playerOrdersNeedSight(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        FakePlayer shooter = player(level, "rules_shooter", Vec3.atBottomCenterOf(h.absolutePos(new BlockPos(1, 1, 1))));
        FakePlayer victim = player(level, "rules_victim", Vec3.atBottomCenterOf(h.absolutePos(new BlockPos(6, 1, 6))));
        // by nickname, the target is searched in the world of the shooter: the player must be in it
        level.addNewPlayer(victim);
        StrikeGameTests.afterTest(h, () -> level.removePlayerImmediately(victim, Entity.RemovalReason.DISCARDED));

        ServerActions.Aim atVictim = ServerActions.atPlayer(victim);
        ServerActions.Aim seen = ServerActions.sighted(shooter, atVictim, t -> Sight.sees(shooter, victim));
        h.assertTrue(seen != null && seen.target() instanceof Target.Sighted s && s.quarry().equals(atVictim.target())
                && s.seen().equals(atVictim.point()), "by visible player - unseen target: " + (seen == null ? null : seen.target()));

        for (int z = 0; z < 8; z++) for (int y = 1; y < 5; y++) h.setBlock(new BlockPos(4, y, z), Blocks.STONE);
        h.assertTrue(ServerActions.sighted(shooter, atVictim, t -> Sight.sees(shooter, victim)) == null, "the order has been accepted for the player behind the wall");

        Cow cow = h.spawn(EntityType.COW, new BlockPos(2, 1, 6));
        ServerActions.Aim atCow = new ServerActions.Aim(Target.OfEntity.center(cow), cow.getBoundingBox().getCenter(), Component.literal("cow"));
        ServerActions.Aim cowAim = ServerActions.sighted(shooter, atCow, v -> false);
        h.assertTrue(cowAim != null && cowAim.target() instanceof Target.Sighted && cowAim.label() == atCow.label(), "mob - unnoticed target");

        Vec3 point = Vec3.atCenterOf(h.absolutePos(new BlockPos(3, 1, 3)));
        ServerActions.Aim atPoint = new ServerActions.Aim(new Target.Point(point), point, null);
        h.assertTrue(ServerActions.sighted(shooter, atPoint, v -> false) == atPoint, "the point has changed");
        h.succeed();
    }

    /**
     * The camera keeps the target: a fresh view from the side of this projectile, the target is in the frame and there are no blocks between them. The camera turned
     *  to the side, the old look, someone else's projectile and the target behind the wall - they don't hold.
     */
    @GameTest(template = "pad", batch = "pursuit")
    public static void cameraHoldsOnlyWhatIsInFrame(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        FakePlayer operator = player(level, "camera_operator", Vec3.atBottomCenterOf(h.absolutePos(new BlockPos(1, 1, 1))));
        ArmorStand stand = new ArmorStand(EntityType.ARMOR_STAND, level);
        Vec3 standAt = Vec3.atBottomCenterOf(h.absolutePos(new BlockPos(6, 1, 6)));
        stand.moveTo(standAt.x, standAt.y, standAt.z);
        level.addFreshEntity(stand);
        Target subject = new Target.OfEntity(stand.getUUID(), new Vec3(0, 1, 0));
        Vec3 at = standAt.add(0, 1, 0);

        // launch raises Shahed to flight altitude - we put it back under the ceiling of the site
        DroneEntity drone = drone(level, Vec3.atCenterOf(h.absolutePos(new BlockPos(1, 4, 6))), subject, at, operator.getUUID());
        drone.setPos(Vec3.atCenterOf(h.absolutePos(new BlockPos(1, 4, 6))));
        DroneEntity other = drone(level, Vec3.atCenterOf(h.absolutePos(new BlockPos(1, 4, 5))), subject, at, operator.getUUID());
        other.setPos(Vec3.atCenterOf(h.absolutePos(new BlockPos(1, 4, 5))));
        StrikeGameTests.afterTest(h, () -> {
            drone.discard();
            other.discard();
        });
        float[] toTarget = angles(drone.getEyePosition(), at);
        CameraLink.receive(operator, drone.getUUID(), toTarget[0], toTarget[1]);
        h.assertTrue(CameraLink.holds(operator, drone, subject, at), "the target does not stay in the frame");
        h.assertFalse(CameraLink.holds(operator, other, subject, at), "held by the camera of another projectile");

        CameraLink.receive(operator, drone.getUUID(), toTarget[0] + 90, toTarget[1]);
        h.assertFalse(CameraLink.holds(operator, drone, subject, at), "held by the camera turned to the side");

        CameraLink.receive(operator, drone.getUUID(), toTarget[0], toTarget[1]);
        CameraLink view = operator.getData(ModAttachments.CAMERA_LINK).orElseThrow();
        operator.setData(ModAttachments.CAMERA_LINK, Optional.of(new CameraLink(view.projectile(), view.look(), view.tick() - 11)));
        h.assertFalse(CameraLink.holds(operator, drone, subject, at), "keeps up the old look");

        CameraLink.receive(operator, drone.getUUID(), toTarget[0], toTarget[1]);
        for (int z = 0; z < 8; z++) for (int y = 1; y < 6; y++) h.setBlock(new BlockPos(3, y, z), Blocks.STONE);
        h.assertFalse(CameraLink.holds(operator, drone, subject, at), "sticks through the wall");
        h.succeed();
    }

    /**
     * No one keeps the spotted cow in frame and it's gone: Shahed flies to where it was seen and the camera shows
     *  "target out of frame"; the cruise missile goes in the same direction and does not show pursuit - it does not follow the target at all.
     */
    @GameTest(template = "range", timeoutTicks = 40, batch = "pursuit", skyAccess = true)
    public static void unheldTargetStaysWhereSeen(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        Cow cow = h.spawn(EntityType.COW, new BlockPos(32, 12, 32));
        cow.setNoAi(true);
        Vec3 seen = cow.getBoundingBox().getCenter();
        Target sighted = new Target.Sighted(Target.OfEntity.center(cow), seen);
        UUID owner = UUID.randomUUID();
        DroneEntity drone = drone(level, Vec3.atCenterOf(h.absolutePos(new BlockPos(8, 40, 8))), sighted, seen, owner);
        CruiseMissileEntity missile = ModEntities.CRUISE_MISSILE.get().create(level);
        missile.launch(Vec3.atCenterOf(h.absolutePos(new BlockPos(2, 60, 2))), sighted, seen, owner);
        level.addFreshEntity(missile);
        UUID droneId = drone.getUUID(), missileId = missile.getUUID();
        StrikeGameTests.afterTest(h, () -> {
            for (UUID id : new UUID[]{droneId, missileId}) if (level.getEntity(id) != null) level.getEntity(id).discard();
        });
        h.runAfterDelay(1, () -> cow.teleportTo(seen.x + 20, cow.getY(), seen.z));
        h.runAfterDelay(5, () -> {
            h.assertTrue(cow.getBoundingBox().getCenter().distanceTo(seen) > 15, "the cow has not left - there is nothing to check");
            for (UUID id : new UUID[]{droneId, missileId}) {
                h.assertTrue(level.getEntity(id) instanceof StrikeProjectile, "the shell disappeared before checking: " + id);
                StrikeProjectile p = (StrikeProjectile) level.getEntity(id);
                // the client's aiming point is at float (DATA_AIM): at distant coordinates GameTest step to 1 block
                Vec3 synced = new Vec3((float) seen.x, (float) seen.y, (float) seen.z);
                h.assertTrue(p.aimPoint().distanceTo(synced) < 0.1, p.weapon() + " aims not where the target was seen: " + p.aimPoint());
                h.assertFalse(p.targetLost(), p.weapon() + " lost a living target");
                StrikeProjectile.Pursuit expected = p.weapon().spec().tracking() == WeaponSpec.Tracking.CAMERA
                        ? StrikeProjectile.Pursuit.WAITING : StrikeProjectile.Pursuit.NONE;
                h.assertValueEqual(p.pursuit(), expected, p.weapon() + ": pursuit in the camera");
            }
            h.succeed();
        });
    }

    private static DroneEntity drone(ServerLevel level, Vec3 at, Target target, Vec3 point, UUID owner) {
        DroneEntity drone = ModEntities.DRONE.get().create(level);
        drone.launch(at, target, point, owner);
        level.addFreshEntity(drone);
        return drone;
    }

    /** The course and pitch of view from {@code from} to {@code to} is the same as the player's turn. */
    private static float[] angles(Vec3 from, Vec3 to) {
        Vec3 d = to.subtract(from);
        float yaw = (float) Math.toDegrees(Math.atan2(-d.x, d.z));
        float pitch = (float) Math.toDegrees(-Math.atan2(d.y, Math.sqrt(d.x * d.x + d.z * d.z)));
        return new float[]{yaw, pitch};
    }

    private static FakePlayer player(ServerLevel level, String name, Vec3 at) {
        FakePlayer p = new FakePlayer(level, new GameProfile(UUID.randomUUID(), name));
        p.moveTo(at);
        return p;
    }
}
