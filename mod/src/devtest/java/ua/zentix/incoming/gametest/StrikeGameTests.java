package ua.zentix.incoming.gametest;

import com.mojang.authlib.GameProfile;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.GameTestInfo;
import net.minecraft.gametest.framework.GameTestListener;
import net.minecraft.gametest.framework.GameTestRunner;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.entity.EntityTypeTest;
import net.minecraft.world.entity.animal.Cow;
import net.minecraft.world.entity.animal.Pig;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.item.FallingBlockEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import org.jetbrains.annotations.Nullable;
import ua.zentix.incoming.entity.flight.ProximityFuse;
import ua.zentix.incoming.Incoming;
import ua.zentix.incoming.IncomingConfig;
import ua.zentix.incoming.compat.SplitGuard;
import ua.zentix.incoming.entity.BomberEntity;
import ua.zentix.incoming.entity.BunkerBusterEntity;
import ua.zentix.incoming.entity.CruiseMissileEntity;
import ua.zentix.incoming.entity.DebrisEntity;
import ua.zentix.incoming.entity.DroneEntity;
import ua.zentix.incoming.entity.FlightPhase;
import ua.zentix.incoming.entity.LauncherEntity;
import ua.zentix.incoming.entity.LauncherRack;
import ua.zentix.incoming.entity.LoiterEntity;
import ua.zentix.incoming.entity.RocketEntity;
import ua.zentix.incoming.entity.SpentBoosterEntity;
import ua.zentix.incoming.entity.StrikeProjectile;
import ua.zentix.incoming.guidance.BombDrop;
import ua.zentix.incoming.guidance.Mission;
import ua.zentix.incoming.guidance.Route;
import ua.zentix.incoming.net.C2S;
import ua.zentix.incoming.nuclear.model.BlastModel;
import ua.zentix.incoming.nuclear.world.BlockResponse;
import ua.zentix.incoming.registry.ModEntities;
import ua.zentix.incoming.registry.ModItems;
import ua.zentix.incoming.strike.AreaLoader;
import ua.zentix.incoming.strike.ChunkTickets;
import ua.zentix.incoming.strike.FarFlights;
import ua.zentix.incoming.strike.FlightTickets;
import ua.zentix.incoming.strike.Hearing;
import ua.zentix.incoming.strike.Loadout;
import ua.zentix.incoming.strike.PickHints;
import ua.zentix.incoming.strike.SalvoData;
import ua.zentix.incoming.strike.ServerActions;
import ua.zentix.incoming.strike.FlightLog;
import ua.zentix.incoming.strike.LaunchOrigin;
import ua.zentix.incoming.strike.LaunchPlaces;
import ua.zentix.incoming.strike.LaunchSite;
import ua.zentix.incoming.strike.StrikeWorld;
import ua.zentix.incoming.strike.TargetMode;
import ua.zentix.incoming.strike.VirtualFlights;
import ua.zentix.incoming.strike.Waypoints;
import ua.zentix.incoming.strike.WeaponSpec;
import ua.zentix.incoming.strike.WeaponType;
import ua.zentix.incoming.target.Target;
import ua.zentix.incoming.target.TargetPicker;
import ua.zentix.incoming.target.TargetTracker;
import ua.zentix.incoming.util.Terrain;
import net.minecraft.world.entity.Entity;
import ua.zentix.incoming.util.Local;
import ua.zentix.incoming.strike.StrikeService;
import ua.zentix.incoming.entity.IcbmEntity;
import ua.zentix.incoming.warhead.CraterFalls;
import ua.zentix.incoming.warhead.Warheads;
import ua.zentix.incoming.sound.Propagation;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.locks.LockSupport;

/**
 * Checks without a window ({@code ./gradlew runGameTestServer}): every weapon reaches its target and explodes, the
 * Shahed leaves the launcher, the missile flies over an unloaded world, the bunker buster bores in, the salvo fires all
 * projectiles, the sight tells a block from an entity, data survives saving. Templates
 * (scripts/gen_test_structures.py): “range” — a 64×64 pad, turf at y = 11 (surface y = 12); “runway” — a 32×256 strip,
 * turf at y = 3 (surface y = 4), height 8 (a wall of barriers around it, no higher): projectiles approach from a real
 * distance. Flight tests need skyAccess: otherwise GameTest covers the pad with a ceiling of barriers, and the relief
 * is that ceiling.
 */
@GameTestHolder(Incoming.MOD_ID)
@PrefixGameTestTemplate(false)
public final class StrikeGameTests {
    /** Preserve the fixture height formerly returned by Level.getSeaLevel(), even in flat worlds. */
    private static final int REFERENCE_SEA_LEVEL = 63;
    /** Target on the strip: turf 200 blocks from the start (beyond it the relief “sees” the wall of the test area). */
    private static final BlockPos RUNWAY_TARGET = new BlockPos(16, 3, 200);
    /**
     * Launch positions in the sector checks with courses back and to the side — this many blocks above the pad. The
     * climb after arming ({@code LaunchSite.clearAhead}: 96 + 256 blocks along the course) goes past the pad to the
     * pads of earlier batches: the GameTest grid places them 8 in a row, 5–6 blocks apart, and does not clear them;
     * their walls and structures rise up to ~70 blocks above the pad. There are no pads yet at +Z and +X: the next
     * batches are placed later.
     */
    private static final int ABOVE_GRID = 100;
    /** Middle of the “range” pad. */
    private static final BlockPos RANGE_CENTER = new BlockPos(32, 11, 32);

    private StrikeGameTests() {}

    /**
     * An uneven canopy over the surroundings of the arrow {@code c} (±48 blocks, farther than all LaunchSite spots):
     * strips at two heights above the highest block of the surroundings — there is no sky under it, and no flat spot on
     * it. The height is from the highest block, not from the arrow: the GameTest grid does not clear the pads of
     * earlier batches, and a spot would be found on a neighboring one higher than the canopy (CI 16.10, a different
     * make-up of the test structure: the launcher at y −17 at the edge of the pad, above the canopy at −46). After the
     * check the canopy is removed: above someone else's structure it is higher than the next batch's pad clears, and
     * the Shahed of a neighboring check crashed into it ({@code CI 16.10}, {@code drone_dive_mast} right after {@code
     * operator_route}).
     */
    private static void roofOver(GameTestHelper h, BlockPos c) {
        ServerLevel level = h.getLevel();
        int top = c.getY();
        for (int x = -48; x <= 48; x++) for (int z = -48; z <= 48; z++) {
            BlockPos q = c.offset(x, 0, z);
            if (Terrain.ready(level, q)) top = Math.max(top, level.getHeight(Heightmap.Types.MOTION_BLOCKING, q.getX(), q.getZ()));
        }
        List<BlockPos> roof = new ArrayList<>();
        for (int x = -48; x <= 48; x++) for (int z = -48; z <= 48; z++) {
            BlockPos r = new BlockPos(c.getX() + x, top + (Math.floorMod(x, 3) == 0 ? 10 : 13), c.getZ() + z);
            if (Terrain.ready(level, r) && level.getBlockState(r).isAir()) {
                level.setBlock(r, Blocks.STONE.defaultBlockState(), 2);
                roof.add(r);
            }
        }
        afterTest(h, () -> roof.forEach(r -> {
            if (Terrain.ready(level, r) && level.getBlockState(r).is(Blocks.STONE)) level.setBlock(r, Blocks.AIR.defaultBlockState(), 2);
        }));
    }

    /** Top of the target block. */
    private static Vec3 top(GameTestHelper h, BlockPos block) {
        return Vec3.atCenterOf(h.absolutePos(block)).add(0, 0.5, 0);
    }

    /** The blast dug a crater at the target point (debris may later land back, so the volume is counted). */
    private static void assertCrater(GameTestHelper h, BlockPos at) {
        assertCrater(h, at, "");
    }

    /**
     * The projectile exploded at the impact point: a detonation by the charge no farther than 6 blocks ({@link
     * BlastLog}). The crater follows the charge law and the ground ({@code craterMatchesChargeLaw}); a Shahed on stone
     * makes none, so the flight is checked by the detonation. The source of each such detonation is the mod's
     * projectile ({@code Explosion.getDirectSourceEntity}): by it, explosion and protection mods tell the strike apart
     * from the weapon.
     */
    private static void assertCrater(GameTestHelper h, BlockPos at, String flight) {
        List<BlastLog.Blast> blasts = BlastLog.around(h, at, 6);
        h.assertTrue(!blasts.isEmpty(), "no detonation at the impact point" + (flight.isEmpty() ? "" : "; last: " + flight));
        for (BlastLog.Blast b : blasts) {
            h.assertTrue(b.source() != null && BuiltInRegistries.ENTITY_TYPE.getKey(b.source()).getNamespace().equals(Incoming.MOD_ID),
                    "detonation source is not a mod projectile: " + (b.source() == null ? "none" : BuiltInRegistries.ENTITY_TYPE.getKey(b.source())));
        }
    }

    @GameTest(template = "runway", timeoutTicks = 400, batch = "drone", skyAccess = true)
    public static void droneFliesAndExplodes(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        DroneEntity drone = ModEntities.DRONE.get().create(level);
        Vec3 start = Vec3.atCenterOf(h.absolutePos(new BlockPos(16, 50, 4)));
        drone.launch(start, new Target.Point(top(h, RUNWAY_TARGET)), top(h, RUNWAY_TARGET), null);
        level.addFreshEntity(drone);
        h.succeedWhen(() -> {
            h.assertTrue(drone.isRemoved(), "the Shahed is still flying: " + drone.position());
            h.assertTrue(drone.getRemovalReason() == net.minecraft.world.entity.Entity.RemovalReason.DISCARDED, "the Shahed vanished: " + drone.getRemovalReason());
            assertCrater(h, RUNWAY_TARGET);
        });
    }

    /**
     * A tall wall on the dive line between the cruiser and the target. A dive at 18° below the horizon, from 47 blocks
     * above the target and begun 145 blocks from it, would hit a wall 25 high 30 blocks before the target; the Shahed
     * waits for a clear line and explodes at the target, and the wall stays intact.
     */
    @GameTest(template = "runway", timeoutTicks = 400, batch = "drone_dive_wall", skyAccess = true)
    public static void droneDivesOverWallOnDiveLine(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        int wallZ = RUNWAY_TARGET.getZ() - 30, wallTop = RUNWAY_TARGET.getY() + 25;
        List<BlockPos> wall = new ArrayList<>();
        for (BlockPos p : BlockPos.betweenClosed(new BlockPos(0, RUNWAY_TARGET.getY() + 1, wallZ - 3), new BlockPos(31, wallTop, wallZ))) {
            h.setBlock(p, Blocks.STONE);
            wall.add(p.immutable());
        }
        Vec3 aim = top(h, RUNWAY_TARGET);
        DroneEntity drone = ModEntities.DRONE.get().create(level);
        drone.launch(Vec3.atCenterOf(h.absolutePos(new BlockPos(16, 50, 4))), new Target.Point(aim), aim, null);
        level.addFreshEntity(drone);
        UUID id = drone.getUUID();
        Vec3[] last = {drone.position()};
        h.onEachTick(() -> {
            StrikeProjectile f = flight(level, id);
            if (f != null) last[0] = f.position();
        });
        h.succeedWhen(() -> {
            h.assertTrue(flight(level, id) == null, "the Shahed is still flying: " + h.relativeVec(last[0]));
            long broken = wall.stream().filter(p -> !h.getBlockState(p).is(Blocks.STONE)).count();
            h.assertTrue(broken == 0, "the Shahed hit the wall: " + broken + " blocks knocked out, last position " + h.relativeVec(last[0]));
            h.assertTrue(last[0].distanceTo(aim) < 8, "the Shahed exploded " + String.format(Locale.ROOT, "%.1f", last[0].distanceTo(aim))
                    + " blocks from the target: " + h.relativeVec(last[0]));
            assertCrater(h, RUNWAY_TARGET);
        });
    }

    /**
     * The Shahed waits for a clear line over a low wall at the target (as in {@link #droneDivesOverWallOnDiveLine}),
     * and on its course 100 blocks before the target there is a fence mast 6 blocks above the cruiser. A sensor of
     * three points up to 45 blocks would see it too late: a climb no steeper than 15° does not make it in time, and the
     * Shahed hits the mast. The Shahed passes over it, the mast is intact, the impact is at the target.
     */
    @GameTest(template = "runway", timeoutTicks = 400, batch = "drone_dive_mast", skyAccess = true)
    public static void droneWaitingForLineClimbsOverMast(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        int wallZ = RUNWAY_TARGET.getZ() - 30, wallTop = RUNWAY_TARGET.getY() + 25;
        for (BlockPos p : BlockPos.betweenClosed(new BlockPos(0, RUNWAY_TARGET.getY() + 1, wallZ - 3), new BlockPos(31, wallTop, wallZ))) {
            h.setBlock(p, Blocks.STONE);
        }
        BlockPos start = new BlockPos(16, 50, 4);
        List<BlockPos> mast = fenceMast(h, new BlockPos(16, RUNWAY_TARGET.getY() + 1, RUNWAY_TARGET.getZ() - 100), start.getY() + 6);
        Vec3 aim = top(h, RUNWAY_TARGET);
        DroneEntity drone = ModEntities.DRONE.get().create(level);
        drone.launch(Vec3.atCenterOf(h.absolutePos(start)), new Target.Point(aim), aim, null);
        level.addFreshEntity(drone);
        UUID id = drone.getUUID();
        Vec3[] last = {drone.position()};
        h.onEachTick(() -> {
            StrikeProjectile f = flight(level, id);
            if (f != null) last[0] = f.position();
        });
        h.succeedWhen(() -> {
            h.assertTrue(flight(level, id) == null, "the Shahed is still flying: " + h.relativeVec(last[0]));
            assertMastStands(h, mast, last[0]);
            h.assertTrue(last[0].distanceTo(aim) < 8, "the Shahed exploded " + String.format(Locale.ROOT, "%.1f", last[0].distanceTo(aim))
                    + " blocks from the target: " + h.relativeVec(last[0]));
            assertCrater(h, RUNWAY_TARGET);
        });
    }

    /**
     * A missile from a launcher, on its climb path: a 1×1 fence mast above the low-level flight, and the climb over the
     * relief. A sensor of three points along the course does not see a thin column between its points, and the height
     * filter smooths a short peak: with them the missile crashes into the mast on its route. The missile passes over
     * it: the mast is intact, the missile did not crash. The target is far beyond the pad (its chunk is generated at
     * once); after passing the mast, the missile is removed.
     */
    @GameTest(template = "runway", timeoutTicks = 400, batch = "missile_mast", skyAccess = true)
    public static void missileClimbsOverFenceMast(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        BlockPos mastBase = new BlockPos(16, 4, 230);
        List<BlockPos> mast = fenceMast(h, mastBase, 40);
        Vec3 point = top(h, new BlockPos(16, 3, 700));
        level.getChunk(Mth.floor(point.x) >> 4, Mth.floor(point.z) >> 4);
        Vec3 site = Vec3.atBottomCenterOf(h.absolutePos(new BlockPos(16, 4, 12)));
        LauncherEntity launcher = LauncherEntity.create(level, site, 0, WeaponType.MISSILE, null, false);
        level.addFreshEntity(launcher);
        int ready = LauncherEntity.DEPLOY_TICKS + 10;
        CruiseMissileEntity missile = ModEntities.CRUISE_MISSILE.get().create(level);
        missile.placeOnLauncher(launcher.railPoint(0), launcher.getYRot(), launcher.elevation(), ready, LauncherEntity.DEPLOY_TICKS,
                new Target.Point(point), point, null);
        level.addFreshEntity(missile);
        UUID id = missile.getUUID();
        FlightLog log = StrikeWorld.get(level).flightLog();
        int crashed = log.total(FlightLog.Event.CRASHED);
        double passZ = h.absolutePos(mastBase).getZ() + 10;
        Vec3[] last = {missile.position()};
        String[] state = {""};
        h.onEachTick(() -> {
            StrikeProjectile f = flight(level, id);
            if (f == null) return;
            last[0] = f.position();
            state[0] = f.flightPhase() + " " + h.relativeVec(f.position()) + " v=" + f.speed();
        });
        h.succeedWhen(() -> {
            assertMastStands(h, mast, last[0]);
            h.assertTrue(log.total(FlightLog.Event.CRASHED) == crashed, "the missile crashed: " + state[0]);
            h.assertTrue(last[0].z > passZ, "the missile did not pass the mast: " + state[0]);
            StrikeProjectile f = flight(level, id);
            h.assertTrue(f != null, "the missile vanished before the mast: " + state[0]);
            if (!f.isVirtual()) f.discard();
        });
    }

    /** Mast of oak fence from {@code base} (relative to the pad) up to {@code top} inclusive. */
    private static List<BlockPos> fenceMast(GameTestHelper h, BlockPos base, int top) {
        List<BlockPos> mast = new ArrayList<>();
        for (int y = base.getY(); y <= top; y++) {
            BlockPos p = new BlockPos(base.getX(), y, base.getZ());
            h.setBlock(p, Blocks.OAK_FENCE);
            mast.add(p);
        }
        return mast;
    }

    private static void assertMastStands(GameTestHelper h, List<BlockPos> mast, Vec3 last) {
        long broken = mast.stream().filter(p -> !h.getBlockState(p).is(Blocks.OAK_FENCE)).count();
        h.assertTrue(broken == 0, "the projectile hit the mast: " + broken + " blocks knocked out, last position " + h.relativeVec(last));
    }

    /**
     * The target died mid-flight (the player the salvo is aimed at dies over and over, and Shaheds with “target lost”
     * must not fly for minutes). First the target goes back and forth 100 blocks (endurance grows by 2000 blocks of
     * pursuit ×1.5), then dies in the air, lies dead like a player on the death screen, and “respawns” — an entity with
     * the same UUID at the start of the strip. The Shahed does not follow it: its endurance after the loss covers only
     * the flight to the point of death, and it strikes there.
     */
    @GameTest(template = "runway", timeoutTicks = 600, batch = "drone_lost", skyAccess = true)
    public static void droneStrikesWhereTargetDied(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        Vec3 alive = top(h, RUNWAY_TARGET).add(0, 15, 0);
        Cow cow = EntityType.COW.create(level);
        cow.setNoAi(true);
        cow.setNoGravity(true);
        cow.moveTo(alive.x, alive.y, alive.z);
        level.addFreshEntity(cow);
        UUID cowId = cow.getUUID();
        DroneEntity drone = ModEntities.DRONE.get().create(level);
        drone.launch(Vec3.atCenterOf(h.absolutePos(new BlockPos(16, 50, 4))), Target.OfEntity.center(cow),
                cow.getBoundingBox().getCenter(), null);
        level.addFreshEntity(drone);
        UUID id = drone.getUUID();
        int[] tick = {0};
        Vec3[] died = {null};
        int[] lostAt = {-1};
        int[] bound = {0};
        boolean[] respawned = {false};
        Vec3[] last = {drone.position()};
        h.onEachTick(() -> {
            int t = ++tick[0];
            if (t <= 20) {
                // the target goes away and comes back: 20 shifts of 100 blocks of pursuit
                Vec3 to = t % 2 == 1 ? alive.add(0, 0, -100) : alive;
                cow.teleportTo(to.x, to.y, to.z);
            } else if (t == 25) {
                died[0] = cow.getBoundingBox().getCenter();
                cow.kill();
            } else if (died[0] != null && cow.isRemoved() && !respawned[0]) {
                respawned[0] = true;
                Cow again = EntityType.COW.create(level);
                again.setUUID(cowId);
                again.setNoAi(true);
                Vec3 at = top(h, new BlockPos(16, 3, 20));
                again.moveTo(at.x, at.y, at.z);
                level.addFreshEntity(again);
            }
            StrikeProjectile f = flight(level, id);
            if (f == null) return;
            last[0] = f.position();
            if (lostAt[0] < 0 && f.targetLost()) {
                lostAt[0] = t;
                // endurance — flight to the point with a margin ×1.5 and 10 s at cruise (here with a tolerance for the
                // ticks between the loss and the check); in ticks — at the current speed, like the time to impact;
                // unrestricted — another 1500 ticks of pursuit
                bound[0] = 2 * f.etaTicks() + 200;
                int left = (int) (f.rangeLeft() / Math.max(f.cruiseSpeed(), f.speed()));
                h.assertTrue(left <= bound[0], "after the target loss, endurance is " + left + " ticks, but the flight to the point is " + f.etaTicks());
            }
        });
        h.succeedWhen(() -> {
            h.assertTrue(died[0] != null && respawned[0], "the target did not die and did not respawn");
            h.assertTrue(lostAt[0] >= 0, "the Shahed did not lose the target");
            h.assertTrue(flight(level, id) == null, "the Shahed is still flying: " + h.relativeVec(last[0]) + ", since the loss of the target " + (tick[0] - lostAt[0]) + " ticks");
            h.assertTrue(tick[0] - lostAt[0] <= bound[0], "from the loss of the target to impact " + (tick[0] - lostAt[0]) + " ticks");
            h.assertTrue(last[0].distanceTo(died[0]) < 10, "the Shahed exploded away from the target's death point: " + last[0].subtract(died[0]));
        });
    }

    /**
     * The target died and respawned between two ticks of the projectile ({@code doImmediateRespawn}): a new entity with
     * the same UUID — it is no longer that target; tracking does not pick it up, even if the projectile never saw it
     * dead.
     */
    @GameTest(template = "range", timeoutTicks = 20, skyAccess = true)
    public static void respawnedTargetIsLost(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        Cow cow = h.spawn(EntityType.COW, RANGE_CENTER.above());
        TargetTracker tracker = new TargetTracker(Target.OfEntity.center(cow), cow.getBoundingBox().getCenter());
        tracker.tick(level, q -> false);
        h.assertFalse(tracker.isLost(), "live target lost");
        cow.discard();
        Cow again = EntityType.COW.create(level);
        again.setUUID(cow.getUUID());
        Vec3 at = Vec3.atBottomCenterOf(h.absolutePos(RANGE_CENTER.offset(10, 1, 0)));
        again.moveTo(at.x, at.y, at.z);
        level.addFreshEntity(again);
        h.assertTrue(level.getEntity(cow.getUUID()) == again, "new entity not found by UUID");
        tracker.tick(level, q -> false);
        h.assertTrue(tracker.isLost(), "tracking picked up a new entity with the same UUID");
        h.succeed();
    }

    /**
     * The target to the side, inside the turn circle (a miss in the dive, a point in the air where the target
     * vanished): the Shahed does not circle around it until the end of its lifetime, but flies straight off, gains
     * height and comes back. All of it is above the pad's barrier wall (template 8 blocks): the Shahed's turn circle is
     * wider than the strip.
     */
    @GameTest(template = "runway", timeoutTicks = 700, batch = "drone_reattack", skyAccess = true)
    public static void droneReattacksInsteadOfCircling(GameTestHelper h) {
        droneReattacks(h, false);
    }

    /**
     * The same, but the target is an entity that dies in the same tick the Shahed picked it up: its last point is
     * inside the circle of the turn, and the repeat approach must fit within the time after the target is lost (flight
     * to the point ×1.5 and 10 s).
     */
    @GameTest(template = "runway", timeoutTicks = 700, batch = "drone_reattack_lost", skyAccess = true)
    public static void droneReattacksLostTargetInTime(GameTestHelper h) {
        droneReattacks(h, true);
    }

    private static void droneReattacks(GameTestHelper h, boolean targetDies) {
        ServerLevel level = h.getLevel();
        gameSpeed(h);
        Vec3 point = Vec3.atCenterOf(h.absolutePos(new BlockPos(16, 85, 120)));
        DroneEntity drone = ModEntities.DRONE.get().create(level);
        Vec3 start = Vec3.atCenterOf(h.absolutePos(new BlockPos(-8, 110, 20)));
        drone.launch(start, new Target.Point(start.add(0, 0, 400)), start.add(0, 0, 400), null);
        // start past the edge of the pad: the chunk there may not tick — the Shahed returns to the world itself
        VirtualFlights.launch(level, drone);
        UUID id = drone.getUUID();
        // target that will die: hangs at the point, its body center in it
        Cow cow = targetDies ? EntityType.COW.create(level) : null;
        if (cow != null) {
            cow.setNoAi(true);
            cow.setNoGravity(true);
            cow.moveTo(point.x, point.y - cow.getBbHeight() / 2, point.z);
            level.addFreshEntity(cow);
        }
        int[] tick = {0};
        int[] retargetedAt = {-1};
        int[] lostAt = {-1};
        int[] bound = {0};
        Vec3[] last = {start};
        h.onEachTick(() -> {
            tick[0]++;
            StrikeProjectile f = flight(level, id);
            if (f == null) return;
            last[0] = f.position();
            // target — 24 blocks to the side, a bit ahead and 25 blocks lower: steeply under the wing
            if (retargetedAt[0] < 0 && f.getZ() >= point.z - 4) {
                retargetedAt[0] = tick[0];
                Target target = cow != null ? Target.OfEntity.center(cow) : new Target.Point(point);
                h.assertTrue(f.retarget(target, point), "the Shahed did not accept the target");
                if (cow != null) cow.kill();
            }
            if (targetDies && lostAt[0] < 0 && f.targetLost()) {
                lostAt[0] = tick[0];
                bound[0] = (int) (f.rangeLeft() / Math.max(f.cruiseSpeed(), f.speed()));
                h.assertTrue(bound[0] <= 2 * f.etaTicks() + 200, "after the target loss, endurance is " + bound[0] + " ticks, flight to the point is " + f.etaTicks());
            }
        });
        h.succeedWhen(() -> {
            h.assertTrue(retargetedAt[0] >= 0, "the Shahed did not reach the retargeting point: " + h.relativeVec(last[0]));
            if (targetDies) h.assertTrue(lostAt[0] >= 0, "the Shahed did not lose the target");
            h.assertTrue(flight(level, id) == null, "the Shahed is still flying: " + h.relativeVec(last[0]) + ", distance to target " + (int) last[0].distanceTo(point));
            h.assertTrue(last[0].distanceTo(point) < 10, "the Shahed exploded away from the target: " + last[0].subtract(point));
            if (targetDies) h.assertTrue(tick[0] - lostAt[0] < bound[0], "repeat approach did not fit the time limit: " + (tick[0] - lostAt[0]) + " of " + bound[0]);
        });
    }

    /** Edge of the 12-chunk render distance: from here the player at the target sees the approaching missile. */
    private static final double VIEW_EDGE = 192;
    /** The approach from the render edge must last at least this many ticks (2 s at 20 TPS): it must be visible. */
    private static final int MIN_VISIBLE_APPROACH = 40;

    /**
     * 199 blocks to the target: low flight, a climb and a dive. The approach is visible: from the render edge (192
     * blocks) to impact no less than {@link #MIN_VISIBLE_APPROACH} ticks (at 11.5 blocks/tick — 17), and the time to
     * impact, which the missile announces at the start (HUD, siren), matches the real one.
     */
    @GameTest(template = "runway", timeoutTicks = 300, batch = "missile", skyAccess = true)
    public static void missileStrikesAfterPopUp(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        CruiseMissileEntity missile = ModEntities.CRUISE_MISSILE.get().create(level);
        Vec3 aim = top(h, RUNWAY_TARGET);
        Vec3 start = Vec3.atCenterOf(h.absolutePos(new BlockPos(16, 5, 1)));
        missile.launch(start, new Target.Point(aim), aim, null);
        int eta = missile.etaTicks();
        level.addFreshEntity(missile);
        int[] ticks = {0}, inView = {-1};
        boolean[] poppedUp = {false};
        h.onEachTick(() -> {
            if (missile.isRemoved()) return;
            ticks[0]++;
            if (inView[0] < 0 && missile.position().distanceTo(aim) <= VIEW_EDGE) inView[0] = ticks[0];
            if (missile.flightPhase() == FlightPhase.POP_UP) poppedUp[0] = true;
        });
        h.succeedWhen(() -> {
            h.assertTrue(missile.isRemoved(), "the missile is still flying: " + missile.position());
            h.assertTrue(missile.getRemovalReason() == net.minecraft.world.entity.Entity.RemovalReason.DISCARDED, "the missile vanished: " + missile.getRemovalReason());
            assertCrater(h, RUNWAY_TARGET);
            h.assertTrue(poppedUp[0], "no pop-up");
            h.assertTrue(inView[0] > 0, "the missile did not enter the render distance at the target");
            int visible = ticks[0] - inView[0];
            h.assertTrue(visible >= MIN_VISIBLE_APPROACH, "approach from the render edge — " + visible + " ticks, less than " + MIN_VISIBLE_APPROACH);
            h.assertTrue(Math.abs(ticks[0] - eta) <= eta / 4, "time to impact at start " + eta + " ticks, in fact " + ticks[0]);
        });
    }

    /**
     * Launch from a launcher: it stands on the rail until ignition, the booster stage, separation of the booster (it
     * falls nearby), the climb — and the impact on the target at the end of the strip.
     */
    @GameTest(template = "runway", timeoutTicks = 900, batch = "launcher", skyAccess = true)
    public static void droneLaunchesFromLauncher(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        LauncherEntity launcher = LauncherEntity.create(level, Vec3.atBottomCenterOf(h.absolutePos(new BlockPos(16, 4, 12))), 0, WeaponType.DRONE, null, false);
        level.addFreshEntity(launcher);
        Vec3 point = top(h, RUNWAY_TARGET);
        int ready = LauncherEntity.DEPLOY_TICKS + 10;
        Vec3 rail = launcher.railPoint(0);
        DroneEntity drone = ModEntities.DRONE.get().create(level);
        drone.placeOnLauncher(rail, 0, launcher.elevation(), ready, LauncherEntity.DEPLOY_TICKS, new Target.Point(point), point, null);
        drone.setRoute(Route.plan(rail, point, new Vec3(0, 0, 1), 0, 120, 1));
        level.addFreshEntity(drone);
        String[] last = {""};
        h.onEachTick(() -> {
            if (level.getEntity(drone.getUUID()) instanceof DroneEntity d) last[0] = d.flightPhase() + " " + h.relativeVec(d.position()) + " v=" + d.speed();
        });
        h.runAfterDelay(20, () -> {
            h.assertTrue(drone.flightPhase() == FlightPhase.READY && !drone.isActive(), "the pod is still rising — the Shahed is not visible");
            h.assertTrue(drone.position().distanceTo(rail) < 0.01, "the Shahed left the rail before ignition");
        });
        h.runAfterDelay(ready + 4, () -> h.assertTrue(drone.flightPhase() == FlightPhase.IGNITION, "no ignition: " + drone.flightPhase()));
        h.runAfterDelay(ready + 70, () -> {
            h.assertTrue(drone.isRemoved() || drone.flightPhase().ordinal() >= FlightPhase.CLIMB.ordinal(), "booster did not separate: " + drone.flightPhase());
            h.assertFalse(level.getEntitiesOfClass(SpentBoosterEntity.class, h.getBounds().inflate(64)).isEmpty(), "no spent booster");
        });
        h.succeedWhen(() -> {
            // after leaving the loaded chunks, the Shahed flies outside the world and returns as a new entity with the
            // same UUID
            h.assertTrue(level.getEntity(drone.getUUID()) == null && VirtualFlights.get(level).flights().isEmpty(), "the Shahed is still flying: " + last[0]);
            assertCrater(h, RUNWAY_TARGET, last[0]);
        });
    }

    /**
     * A Shahed from a launcher with a wall in front of it higher than its climb: it crashes into it on the run-up,
     * before arming (without a detonation of the warhead), rather than passing through it on the booster — and this is
     * visible in the log as a {@link FlightLog.Event#CRASHED} line. Without it, a salvo that crashed entirely into a
     * house at the launcher looks like it vanished: no impact, no error, no lifetime.
     */
    @GameTest(template = "runway", timeoutTicks = 300, batch = "launcher_wall", skyAccess = true)
    public static void droneCrashIntoWallIsLogged(GameTestHelper h) {
        crashIntoWallIsLogged(h, WeaponType.DRONE);
    }

    /**
     * The same for the missile: its rail is steeper and its run-up shorter (without the line, the missile vanishes
     * during the climb).
     */
    @GameTest(template = "runway", timeoutTicks = 300, batch = "launcher_wall_missile", skyAccess = true)
    public static void missileCrashIntoWallIsLogged(GameTestHelper h) {
        crashIntoWallIsLogged(h, WeaponType.MISSILE);
    }

    private static void crashIntoWallIsLogged(GameTestHelper h, WeaponType weapon) {
        ServerLevel level = h.getLevel();
        for (int x = 4; x <= 28; x++) for (int y = 4; y <= 40; y++) h.setBlock(new BlockPos(x, y, 40), Blocks.STONE);
        Vec3 site = Vec3.atBottomCenterOf(h.absolutePos(new BlockPos(16, 4, 12)));
        h.assertFalse(LaunchSite.clearAhead(level, site, 0, weapon, top(h, RUNWAY_TARGET)), "wall along the course is not visible");
        LauncherEntity launcher = LauncherEntity.create(level, site, 0, weapon, null, false);
        level.addFreshEntity(launcher);
        Vec3 point = top(h, RUNWAY_TARGET);
        int ready = LauncherEntity.DEPLOY_TICKS + 10;
        Vec3 rail = launcher.railPoint(0);
        StrikeProjectile drone = weapon.spec().airframe().entity().get().create(level);
        drone.placeOnLauncher(rail, 0, launcher.elevation(), ready, LauncherEntity.DEPLOY_TICKS, new Target.Point(point), point, null);
        drone.setRoute(Route.plan(rail, point, new Vec3(0, 0, 1), 0, 120, 1));
        level.addFreshEntity(drone);
        FlightLog log = StrikeWorld.get(level).flightLog();
        int before = log.total(FlightLog.Event.CRASHED);
        String[] last = {""};
        h.onEachTick(() -> {
            if (level.getEntity(drone.getUUID()) instanceof StrikeProjectile d) last[0] = d.flightPhase() + " " + h.relativeVec(d.position()) + " v=" + d.speed();
        });
        h.succeedWhen(() -> {
            h.assertTrue(level.getEntity(drone.getUUID()) == null && VirtualFlights.get(level).flights().isEmpty(), weapon + " is still flying: " + last[0]);
            h.assertTrue(log.total(FlightLog.Event.CRASHED) == before + 1, "projectile that crashed into the wall was not logged; last seen: " + last[0]
                    + ", impacts " + StrikeWorld.get(level).impacts().size());
            h.assertTrue(StrikeWorld.get(level).impacts().isEmpty(), "crashed, yet the warhead detonated");
        });
    }

    /**
     * Launch sector: of two courses (going around the route on one side or the other) the launcher takes the one that
     * does not run into a house before the fuze is armed; if both are blocked, it turns, but no farther than 90° from
     * the target (the salvo does not turn away from it); a pod that cannot turn — nowhere (the projectile approaches
     * from afar). A pod facing the first point of the route from a random side would, in town, crash every second salvo
     * into a house at the launcher.
     */
    @GameTest(template = "runway", timeoutTicks = 20, batch = "launch_sector", skyAccess = true)
    public static void launchSectorAvoidsHouse(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        // courses in all directions: the climb after arming goes to the pads of earlier batches — the spot is above
        // them
        int y0 = 4 + ABOVE_GRID;
        Vec3 site = Vec3.atBottomCenterOf(h.absolutePos(new BlockPos(16, y0, 120)));
        float[] sides = {0, 180};
        Vec3 far = site.add(0, 0, 400);
        LaunchSite.Pick free = LaunchSite.pickOn(level, site, WeaponType.DRONE, sides, far, 90);
        h.assertTrue(free != null && free.preferred() == 0, "no houses — first course: " + free);
        // house 30 blocks along the first course (+Z), higher than the Shahed's climb before arming
        afterTest(h, () -> {
            for (int z : new int[]{90, 150}) for (int x = 4; x <= 28; x++) for (int y = y0; y <= y0 + 46; y++) h.setBlock(new BlockPos(x, y, z), Blocks.AIR);
        });
        for (int x = 4; x <= 28; x++) for (int y = y0; y <= y0 + 46; y++) h.setBlock(new BlockPos(x, y, 150), Blocks.STONE);
        h.assertFalse(LaunchSite.clearAhead(level, site, 0, WeaponType.DRONE, far), "house along the course is not visible");
        h.assertFalse(LaunchSite.clearAhead(level, site, 0, WeaponType.MISSILE, far), "house along the missile course is not visible");
        LaunchSite.Pick other = LaunchSite.pickOn(level, site, WeaponType.DRONE, sides, far, 90);
        h.assertTrue(other != null && other.preferred() == 1 && other.yaw() == 180, "course did not change to a free one: " + other);
        // and along the second (−Z): the pod turns from the first course until the sector is clear — no farther than
        // 90° from the target
        for (int x = 4; x <= 28; x++) for (int y = y0; y <= y0 + 46; y++) h.setBlock(new BlockPos(x, y, 90), Blocks.STONE);
        LaunchSite.Pick turned = LaunchSite.pickOn(level, site, WeaponType.DRONE, sides, far, 90);
        h.assertTrue(turned != null && turned.preferred() == -1 && Math.abs(turned.yaw()) >= 30 && Math.abs(turned.yaw()) <= 90
                && LaunchSite.clearAhead(level, site, turned.yaw(), WeaponType.DRONE, far), "course into a house or away from the target: " + turned);
        h.assertTrue(LaunchSite.pickOn(level, site, WeaponType.DRONE, sides, far, 0) == null, "pod turned although it may not turn");
        h.succeed();
    }

    /**
     * Climb after arming: a tower behind the arming point, higher than the Shahed and the missile can gain under the
     * autopilot law, closes the launch sector, although the path to arming is clear (the launcher is on a street:
     * otherwise the Shahed 40 blocks after arming goes into the tower). A house below the climb does not close the
     * sector. Course — +Z, the launcher — above the grid of pads ({@link #laneSite}): the climb reads only its own
     * strip.
     */
    @GameTest(template = "runway", timeoutTicks = 20, batch = "launch_climb_out", skyAccess = true)
    public static void launchSectorNeedsClimbOut(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        Vec3 site = laneSite(h);
        Vec3 far = site.add(0, 0, 1000);
        float[] ahead = {0};
        for (WeaponType weapon : new WeaponType[]{WeaponType.DRONE, WeaponType.MISSILE}) {
            h.assertTrue(LaunchSite.clearAhead(level, site, 0, weapon, far), weapon + ": empty strip is blocked");
            // by the arming point the Shahed climbs ~15 blocks above the rail, the missile ~24
            Vec3 rail = LauncherEntity.railPoint(site, 0, weapon, 0);
            BlockPos house = BlockPos.containing(rail.x, site.y, rail.z + ProximityFuse.ARM_DISTANCE + 40);
            build(level, house, Mth.floor(rail.y) + 6, Blocks.STONE);
            h.assertTrue(LaunchSite.clearAhead(level, site, 0, weapon, far), weapon + ": house below the climb blocked the sector");
            build(level, house, Mth.floor(rail.y) + 40, Blocks.STONE);
            h.assertFalse(LaunchSite.clearAhead(level, site, 0, weapon, far), weapon + ": tower above the climb after arming is not visible");
            h.assertTrue(LaunchSite.pickOn(level, site, weapon, ahead, far, 0) == null, weapon + ": launcher stopped in front of the tower");
            build(level, house, Mth.floor(rail.y) + 40, Blocks.AIR);
        }
        h.succeed();
    }

    /**
     * Missile launch sector — from both containers and along the real run-up path. A post that stands only on the path
     * of the second container (1.24 blocks to the side of the first), and a canopy over the line at the climb angle,
     * through which the missile flies on the run-up (with a rail at 40° it is higher than the line at 14°: at 13 blocks
     * — by ~6), close the sector. A sector from the first cell only, and only along a straight line, lets such through:
     * of a salvo of 20 missiles on the run-up exactly every second one crashes — all from one container, into the same
     * block.
     */
    @GameTest(template = "runway", timeoutTicks = 20, batch = "launch_sector_cells", skyAccess = true)
    public static void launchSectorCoversEveryCellAndBoost(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        Vec3 site = laneSite(h);
        Vec3 far = site.add(0, 0, 1000);
        WeaponType weapon = WeaponType.MISSILE;
        h.assertTrue(LaunchSite.clearAhead(level, site, 0, weapon, far), "empty strip is blocked");
        Vec3 first = LauncherEntity.railPoint(site, 0, weapon, 0), second = LauncherEntity.railPoint(site, 0, weapon, 1);
        int y = Mth.floor(first.y), z = Mth.floor(first.z);
        // a post 30 blocks along the course at the second container: the path of the first goes past it
        int postX = Mth.floor(second.x);
        h.assertTrue(Mth.floor(first.x) != postX, "post on the path of the first container");
        Iterable<BlockPos> post = BlockPos.betweenClosed(postX, Mth.floor(site.y), z + 30, postX, y + 30, z + 30);
        post.forEach(p -> level.setBlock(p, Blocks.STONE.defaultBlockState(), 2));
        h.assertFalse(LaunchSite.clearAhead(level, site, 0, weapon, far), "post on the path of the second container is not visible");
        post.forEach(p -> level.setBlock(p, Blocks.AIR.defaultBlockState(), 2));
        h.assertTrue(LaunchSite.clearAhead(level, site, 0, weapon, far), "without the post, the strip is blocked");
        // a canopy 11–15 blocks along the course at 8–11 above the rail: the line at 14° is 3–4 blocks above it
        BlockPos.betweenClosed(Mth.floor(site.x) - 12, y + 8, z + 11, Mth.floor(site.x) + 12, y + 11, z + 15)
                .forEach(p -> level.setBlock(p, Blocks.STONE.defaultBlockState(), 2));
        h.assertFalse(LaunchSite.clearAhead(level, site, 0, weapon, far), "canopy on the run-up path is not visible");
        h.succeed();
    }

    /**
     * Limit on the cost of choosing a launcher ({@link LaunchSite.Budget}): a sector check that ran out of budget
     * answers “occupied” and says the limit was exceeded ({@code out}) — the strike then comes from afar, rather than
     * spending hundreds of milliseconds in a tick; if the budget sufficed, the answer is unchanged, and a blocked
     * sector is blocked by blocks, not by the limit. Cells are counted, not time: CI does not measure wall-clock time.
     */
    @GameTest(template = "runway", timeoutTicks = 20, batch = "launch_sector_budget", skyAccess = true)
    public static void launchSectorStopsAtBudget(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        Vec3 site = laneSite(h);
        Vec3 far = site.add(0, 0, 1000);
        WeaponType weapon = WeaponType.MISSILE;
        LaunchSite.Budget open = new LaunchSite.Budget();
        h.assertTrue(LaunchSite.clearAhead(level, site, 0, weapon, far, open), "empty strip is blocked");
        h.assertTrue(!open.out() && open.spent() > 0, "empty strip: limit exceeded or not spent: " + open.spent());
        LaunchSite.Budget none = new LaunchSite.Budget(0);
        h.assertFalse(LaunchSite.clearAhead(level, site, 0, weapon, far, none), "empty strip checked without a limit");
        h.assertTrue(none.out() && none.spent() == 0, "limit 0 was not exceeded: " + none.spent());
        // canopy over the run-up path, as in launchSectorCoversEveryCellAndBoost
        Vec3 rail = LauncherEntity.railPoint(site, 0, weapon, 0);
        int y = Mth.floor(rail.y), z = Mth.floor(rail.z);
        Iterable<BlockPos> roof = BlockPos.betweenClosed(Mth.floor(site.x) - 12, y + 8, z + 11, Mth.floor(site.x) + 12, y + 11, z + 15);
        roof.forEach(p -> level.setBlock(p, Blocks.STONE.defaultBlockState(), 2));
        LaunchSite.Budget small = new LaunchSite.Budget(50);
        h.assertFalse(LaunchSite.clearAhead(level, site, 0, weapon, far, small), "canopy under a small limit did not block the sector");
        h.assertTrue(small.out() && small.spent() <= 50, "small limit: out " + small.out() + ", cells " + small.spent());
        LaunchSite.Budget full = new LaunchSite.Budget();
        h.assertFalse(LaunchSite.clearAhead(level, site, 0, weapon, far, full), "canopy on the run-up path is not visible");
        h.assertFalse(full.out(), "canopy blocked the sector by the limit, not by blocks: cells " + full.spent());
        LaunchSite.Budget pick = new LaunchSite.Budget(50);
        h.assertTrue(LaunchSite.pickOn(level, site, weapon, new float[]{0}, far, 90, pick) == null && pick.out(),
                "course search under a small limit: out " + pick.out() + ", cells " + pick.spent());
        h.succeed();
    }

    /**
     * Sector check of an MLRS pod without a single heightmap cull (a roof high above the whole arc: each segment is
     * checked by rays from all 40 tubes) for a target 400 blocks away fits the default limit: an MLRS launcher in town
     * is not rejected by the limit.
     */
    @GameTest(template = "runway", timeoutTicks = 20, batch = "launch_sector_budget_tubes", skyAccess = true)
    public static void rocketSectorFitsBudget(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        Vec3 site = Vec3.atBottomCenterOf(h.absolutePos(new BlockPos(16, 4, 8)));
        Vec3 target = site.add(0, 0, 400);
        int roofY = Mth.floor(site.y) + 100;
        Iterable<BlockPos> roof = BlockPos.betweenClosed(h.absolutePos(new BlockPos(4, 0, 0)).atY(roofY), h.absolutePos(new BlockPos(28, 0, 112)).atY(roofY));
        roof.forEach(p -> level.setBlock(p, Blocks.STONE.defaultBlockState(), 2));
        afterTest(h, () -> roof.forEach(p -> level.setBlock(p, Blocks.AIR.defaultBlockState(), 2)));
        LaunchSite.Budget budget = new LaunchSite.Budget();
        h.assertTrue(LaunchSite.clearAhead(level, site, 0, WeaponType.ROCKET, target, budget), "roof over the arc blocked the sector");
        h.assertTrue(!budget.out() && budget.spent() > LaunchSite.Budget.CELLS / 2,
                "arc under the roof: out " + budget.out() + ", cells " + budget.spent());
        Incoming.LOG.info("GameTest: MLRS sector under the roof — {} cells of {}", budget.spent(), LaunchSite.Budget.CELLS);
        h.succeed();
    }

    /**
     * Wall across the strip (13 blocks wide, 2 thick) from the ground {@code base} up to height {@code top} inclusive.
     */
    private static void build(ServerLevel level, BlockPos base, int top, Block block) {
        for (int x = -6; x <= 6; x++) for (int z = 0; z <= 1; z++) for (int y = base.getY(); y <= top; y++) {
            level.setBlock(new BlockPos(base.getX() + x, y, base.getZ() + z), block.defaultBlockState(), 2);
        }
    }

    /**
     * Launch sector for other pods — by their run-up path from the spec: the Lancet catapult — a line at its angle, the
     * MLRS tubes — an arc from the tube (the canopy over the pod blocks it).
     */
    @GameTest(template = "runway", timeoutTicks = 20, batch = "launch_sector_other", skyAccess = true)
    public static void launchSectorOfCatapultAndTubes(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        Vec3 site = Vec3.atBottomCenterOf(h.absolutePos(new BlockPos(16, 4, 120)));
        float[] ahead = {0};
        Vec3 far = site.add(0, 0, 400);
        h.assertTrue(LaunchSite.pickOn(level, site, WeaponType.LOITER, ahead, far, 0) != null, "empty strip gets in the way of the catapult");
        h.assertTrue(LaunchSite.pickOn(level, site, WeaponType.ROCKET, ahead, far, 0) != null, "empty strip gets in the way of the MLRS pod");
        // canopy over the pod
        for (int x = 8; x <= 24; x++) for (int z = 104; z <= 140; z++) h.setBlock(new BlockPos(x, 12, z), Blocks.STONE);
        h.assertTrue(LaunchSite.pickOn(level, site, WeaponType.ROCKET, ahead, far, 0) == null, "MLRS under the canopy: sector is free");
        for (int x = 8; x <= 24; x++) for (int z = 104; z <= 140; z++) h.setBlock(new BlockPos(x, 12, z), Blocks.AIR);
        // wall 20 blocks in front of the catapult
        for (int x = 4; x <= 28; x++) for (int y = 4; y <= 40; y++) h.setBlock(new BlockPos(x, y, 140), Blocks.STONE);
        h.assertTrue(LaunchSite.pickOn(level, site, WeaponType.LOITER, ahead, far, 0) == null, "catapult faces a wall");
        h.succeed();
    }

    /**
     * The MLRS arc at a near target is lower than at a far one (speed sets the range): a tower 20 blocks along the
     * course, of a height between them (on the arc: ≈16 blocks above the tube at a 60-block target, ≈22 at a 400-block
     * one), blocks the sector only for the near target. A line at cruise speed (≈23 blocks over 20 blocks) would pass
     * over the tower and let the pod through, and the missile would crash into it unarmed.
     */
    @GameTest(template = "runway", timeoutTicks = 20, batch = "launch_sector_tubes", skyAccess = true)
    public static void rocketSectorFollowsArc(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        Vec3 site = Vec3.atBottomCenterOf(h.absolutePos(new BlockPos(16, 4, 40)));
        Vec3 rail = LauncherEntity.railPoint(site, 0, WeaponType.ROCKET, 0);
        Vec3 near = new Vec3(site.x, site.y, rail.z + 60);
        Vec3 far = site.add(0, 0, 400);
        float[] ahead = {0};
        h.assertTrue(LaunchSite.pickOn(level, site, WeaponType.ROCKET, ahead, near, 0) != null, "empty strip gets in the way of the MLRS pod");
        int towerZ = Mth.floor(rail.z + 20);
        int towerTop = Mth.floor(rail.y + 19);
        for (int x = -12; x <= 12; x++) for (int y = Mth.floor(site.y); y <= towerTop; y++) {
            level.setBlock(new BlockPos(Mth.floor(site.x) + x, y, towerZ), Blocks.STONE.defaultBlockState(), 2);
        }
        h.assertTrue(LaunchSite.pickOn(level, site, WeaponType.ROCKET, ahead, near, 0) == null, "arc to the near target passed through the tower");
        h.assertTrue(LaunchSite.pickOn(level, site, WeaponType.ROCKET, ahead, far, 0) != null, "arc to the far target grazed the tower");
        h.succeed();
    }

    /**
     * ICBM from a table under a canopy: the run-up does not count collisions (it has no {@code advance}), goes up
     * without crashing.
     */
    @GameTest(template = "range", timeoutTicks = 200, batch = "icbm_roof", skyAccess = true)
    public static void icbmUnderRoofStillClimbs(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        Vec3 pad = Vec3.atBottomCenterOf(h.absolutePos(RANGE_CENTER));
        BlockPos roof = BlockPos.containing(pad).above(20);
        for (int x = -3; x <= 3; x++) for (int z = -3; z <= 3; z++) level.setBlock(roof.offset(x, 0, z), Blocks.STONE.defaultBlockState(), 3);
        FlightLog log = StrikeWorld.get(level).flightLog();
        int crashed = log.total(FlightLog.Event.CRASHED);
        IcbmEntity icbm = ModEntities.ICBM.get().create(level);
        icbm.prepare(pad, pad.add(2000, 0, 0), null);
        level.addFreshEntity(icbm);
        double[] top = {pad.y};
        h.onEachTick(() -> {
            if (!icbm.isRemoved()) top[0] = Math.max(top[0], icbm.getY());
        });
        h.succeedWhen(() -> {
            h.assertTrue(log.total(FlightLog.Event.CRASHED) == crashed, "ICBM crashed into the canopy");
            h.assertTrue(top[0] > roof.getY() + 20, "ICBM did not go above the canopy: " + top[0]);
        });
    }

    /**
     * ICBM from the bottom of a silo (a stone wall 25 blocks with a 4×4 opening — like a Minuteman silo, ~24 m deep and
     * ~3.7 m across): while the tail is in the silo, the missile flies along the axis — no farther than 0.5 blocks from
     * it (the 2×2 body does not reach the wall even at 1 block) — and goes 20 blocks above the silo. An ICBM has no
     * collisions on the run-up ({@link #icbmUnderRoofStillClimbs}), so the silo check is the axis.
     */
    @GameTest(template = "range", timeoutTicks = 200, batch = "icbm_silo", skyAccess = true)
    public static void icbmClimbsOutOfSilo(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        BlockPos c = h.absolutePos(RANGE_CENTER);
        // the silo axis — between four blocks: the opening is blocks −2…1 from it, the wall is the ring at −3 and 2
        Vec3 pad = new Vec3(c.getX(), c.getY(), c.getZ());
        int depth = 25, wallTop = c.getY() + depth;
        List<BlockPos> wall = new ArrayList<>();
        for (int y = 0; y < depth; y++) {
            for (int x = -3; x <= 2; x++) {
                for (int z = -3; z <= 2; z++) {
                    if (x == -3 || x == 2 || z == -3 || z == 2) wall.add(c.offset(x, y, z));
                }
            }
        }
        wall.forEach(p -> level.setBlock(p, Blocks.STONE.defaultBlockState(), 2));
        afterTest(h, () -> wall.forEach(p -> level.setBlock(p, Blocks.AIR.defaultBlockState(), 2)));
        FlightLog log = StrikeWorld.get(level).flightLog();
        int crashed = log.total(FlightLog.Event.CRASHED);
        IcbmEntity icbm = ModEntities.ICBM.get().create(level);
        icbm.prepare(pad, pad.add(2000, 0, 0), null);
        level.addFreshEntity(icbm);
        afterTest(h, icbm::discard);
        double half = WeaponSpec.NUKE.airframe().noseLength();
        double[] top = {pad.y}, off = {0};
        int[] out = {-1};
        h.onEachTick(() -> {
            if (icbm.isRemoved()) return;
            top[0] = Math.max(top[0], icbm.getY());
            if (icbm.getY() - half < wallTop) off[0] = Math.max(off[0], Math.hypot(icbm.getX() - pad.x, icbm.getZ() - pad.z));
            else if (out[0] < 0) {
                out[0] = icbm.age();
                Incoming.LOG.info("ICBM from the silo: tail out on tick {}, deviation from the axis in the silo {} blocks", out[0],
                        String.format(Locale.ROOT, "%.3f", off[0]));
            }
        });
        h.succeedWhen(() -> {
            h.assertTrue(log.total(FlightLog.Event.CRASHED) == crashed, "ICBM crashed into the silo");
            h.assertTrue(off[0] <= 0.5, "ICBM in the silo deviated from the axis by " + off[0] + " blocks");
            h.assertTrue(top[0] > wallTop + 20, "ICBM did not go above the silo: " + top[0]);
        });
    }

    /**
     * An ICBM from a table at normal ground height (y 64) is visible for about nine seconds: from launch until it
     * leaves through the world ceiling — 8–11 s (160–220 ticks), and it leaves exactly there, not earlier, at the edge
     * of the ticking chunks. The table is in the air above the pad: there are no collisions on the run-up, and the
     * GameTest pad height is not that of the world surface.
     */
    @GameTest(template = "range", timeoutTicks = 300, batch = "icbm_sky", skyAccess = true)
    public static void icbmStaysInTheSkyNineSeconds(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        BlockPos c = h.absolutePos(RANGE_CENTER);
        BlockPos table = new BlockPos(c.getX(), Math.max(c.getY(), 64), c.getZ());
        List<BlockPos> stone = new ArrayList<>();
        for (int x = -3; x <= 3; x++) for (int z = -3; z <= 3; z++) stone.add(table.offset(x, -1, z));
        stone.forEach(p -> level.setBlock(p, Blocks.STONE.defaultBlockState(), 2));
        afterTest(h, () -> stone.forEach(p -> level.setBlock(p, Blocks.AIR.defaultBlockState(), 2)));
        Vec3 pad = Vec3.atBottomCenterOf(table);
        IcbmEntity icbm = ModEntities.ICBM.get().create(level);
        icbm.prepare(pad, pad.add(2000, 0, 0), null);
        level.addFreshEntity(icbm);
        double[] last = {pad.y};
        int[] age = {0};
        StringBuilder bySecond = new StringBuilder();
        h.onEachTick(() -> {
            if (icbm.isRemoved()) return;
            last[0] = icbm.getY();
            age[0] = icbm.age();
            if (age[0] % 20 == 0) bySecond.append(String.format(Locale.ROOT, " %d s: +%.0f (%.2f blocks/tick);", age[0] / 20, icbm.getY() - 9 - pad.y, icbm.speed()));
        });
        boolean[] told = {false};
        h.succeedWhen(() -> {
            h.assertTrue(icbm.isRemoved(), "ICBM still in the sky");
            if (!told[0]) {
                told[0] = true;
                Incoming.LOG.info("ICBM in the sky: {} ticks, last altitude {} (ceiling {});{}", age[0], String.format(Locale.ROOT, "%.0f", last[0]),
                        level.getMaxBuildHeight(), bySecond);
            }
            double ceiling = level.getMaxBuildHeight() + WeaponSpec.NUKE.ascent().ceiling();
            h.assertTrue(last[0] > ceiling - 10, "ICBM removed before the ceiling " + ceiling + ": at " + last[0]);
            h.assertTrue(age[0] >= 160 && age[0] <= 220, "ICBM in the sky " + age[0] + " ticks");
        });
    }

    /**
     * Launch on behalf of a player: a launcher with a course clear until arming, the first route point — on this course
     * within the arming range (up to it the projectile goes through the checked sector). There is no launcher spot (an
     * uneven canopy over the whole surroundings) — the approach from afar, without a launcher.
     */
    @GameTest(template = "runway", timeoutTicks = 20, batch = "launch_gate", skyAccess = true)
    public static void launcherRouteStartsOnCheckedSector(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        FakePlayer shooter = new FakePlayer(level, new GameProfile(UUID.randomUUID(), "incoming_launch_gate"));
        shooter.moveTo(Vec3.atBottomCenterOf(h.absolutePos(new BlockPos(16, 4, 60))), 0, 0);
        Vec3 point = top(h, RUNWAY_TARGET);
        StrikeProjectile p = StrikeService.launchGuided(level, WeaponType.DRONE, point, 0, shooter, Waypoints.NONE);
        List<LauncherEntity> launchers = level.getEntitiesOfClass(LauncherEntity.class, h.getBounds().inflate(64));
        StrikeGameTests.afterTest(h, () -> launchers.forEach(Entity::discard));
        h.assertTrue(p != null && !p.isVirtual() && launchers.size() == 1, "the Shahed is not from a launcher: " + p + ", launchers " + launchers.size());
        LauncherEntity launcher = launchers.getFirst();
        h.assertTrue(LaunchSite.clearAhead(level, launcher, point, new LaunchSite.Budget()), "launcher faces an occupied sector");
        Vec3 gate = launcher.railPoint(0).add(Local.horizontal(launcher.getYRot()).scale(ProximityFuse.ARM_DISTANCE));
        Vec3 first = p.route().points().getFirst();
        h.assertTrue(Math.abs(first.x - gate.x) < 1.5 && Math.abs(first.z - gate.z) < 1.5, "first point is not on the launcher's course: " + first + " against " + gate);
        launcher.discard();
        // canopy over the whole surroundings, in strips at two heights: no spot for a launcher either under it or on it
        roofOver(h, BlockPos.containing(shooter.position()));
        StrikeProjectile far = StrikeService.launchGuided(level, WeaponType.DRONE, point, 0, shooter, Waypoints.NONE);
        h.assertTrue(far != null && far.isVirtual(), "without a launcher spot the Shahed did not approach from afar: " + far);
        h.assertTrue(level.getEntitiesOfClass(LauncherEntity.class, h.getBounds().inflate(64), LauncherEntity::isAlive).isEmpty(), "launcher placed under a canopy");
        VirtualFlights.get(level).clear(level, f -> f == far);
        h.succeed();
    }

    /**
     * Operator route ({@link Waypoints}): the launcher faces the first point, the path — the arming gate, points, the target, and
     * the range — by the path through the points (one and a half paths plus a margin, as for the loop). Without a launch site a Shahed and the “Lancet”
     * approach from afar — before the first point, on the extension of the first leg. A redirect drops the remaining points; a salvo
     * remembers the route in the save.
     */
    @GameTest(template = "runway", timeoutTicks = 20, batch = "operator_route", skyAccess = true)
    public static void operatorRouteGuidesLaunch(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        FakePlayer shooter = new FakePlayer(level, new GameProfile(UUID.randomUUID(), "incoming_route"));
        shooter.moveTo(Vec3.atBottomCenterOf(h.absolutePos(new BlockPos(16, 4, 60))), 0, 0);
        Vec3 point = top(h, RUNWAY_TARGET);
        Vec3 a = h.absoluteVec(new Vec3(26, 0, 170)), b = h.absoluteVec(new Vec3(6, 0, 190));
        Waypoints via = new Waypoints(List.of(a, b));
        StrikeProjectile p = StrikeService.launchGuided(level, WeaponType.DRONE, point, 0, shooter, via);
        List<LauncherEntity> launchers = level.getEntitiesOfClass(LauncherEntity.class, h.getBounds().inflate(64));
        StrikeGameTests.afterTest(h, () -> launchers.forEach(Entity::discard));
        h.assertTrue(p != null && !p.isVirtual() && launchers.size() == 1, "Shahed on the route not from a launcher: " + p + ", launchers " + launchers.size());
        LauncherEntity launcher = launchers.getFirst();
        float toFirst = (float) Math.toDegrees(Math.atan2(-(a.x - launcher.getX()), a.z - launcher.getZ()));
        h.assertTrue(Math.abs(Mth.wrapDegrees(launcher.getYRot() - toFirst)) < 3, "launcher not facing the first point: heading " + launcher.getYRot() + ", to point " + toFirst);
        Vec3 gate = launcher.railPoint(0).add(Local.horizontal(launcher.getYRot()).scale(ProximityFuse.ARM_DISTANCE));
        List<Vec3> path = p.route().points();
        h.assertTrue(path.size() == 3 && flat(path.get(0), gate) < 1.5 && flat(path.get(1), a) < 1e-6 && flat(path.get(2), b) < 1e-6,
                "route not gate → points: " + path + ", gate " + gate);
        double planned = p.route().remaining(p.position(), point);
        h.assertTrue(planned > flat(p.position(), point), "path along points " + planned + " not longer than the direct " + flat(p.position(), point));
        h.assertTrue(p.rangeLeft() >= 1.5 * planned, "range left " + p.rangeLeft() + " less than one and a half paths along points " + planned);
        launcher.discard();
        // no launch site — approach from afar
        roofOver(h, BlockPos.containing(shooter.position()));
        StrikeProjectile far = StrikeService.launchGuided(level, WeaponType.DRONE, point, 0, shooter, via);
        h.assertTrue(far != null && far.isVirtual(), "without a launch site the Shahed on the route did not approach from afar: " + far);
        Vec3 entry = via.afar(point, WeaponType.DRONE.spec().finalLeg());
        h.assertTrue(flat(far.position(), entry) < 1e-6 && far.route().points().equals(via.points()),
                "approach from afar not before the first point: " + far.position() + " versus " + entry + ", route " + far.route().points());
        h.assertTrue(far.position().y > point.y, "approach from afar below the target: " + far.position().y);
        StrikeService.Result loiter = StrikeService.launch(level, WeaponType.LOITER, new Target.Point(point), point, 0, null, false, Loadout.Nuke.DEFAULT, via, null);
        StrikeProjectile lancet = VirtualFlights.get(level).flights().stream().filter(f -> f.weapon() == WeaponType.LOITER && f.aimPoint().distanceTo(point) < 1)
                .findFirst().orElse(null);
        h.assertTrue(loiter.ok() && lancet != null && lancet.route() != null && lancet.route().points().equals(via.points()), "“Lancet” without a route: " + lancet);
        Vec3 lancetEntry = via.afar(point, WeaponType.LOITER.spec().standoff());
        h.assertTrue(flat(lancet.position(), lancetEntry) < 1e-6, "“Lancet” approaches not before the first point: " + lancet.position() + " versus " + lancetEntry);
        // retarget from the camera: straight at the new target after that
        Vec3 other = point.add(40, 0, 0);
        h.assertTrue(far.retarget(new Target.Point(other), other) && far.route().finished(), "retarget did not drop the route points");
        VirtualFlights.get(level).clear(level, f -> f == far || f == lancet);
        // salvo remembers the route
        SalvoData.start(level, WeaponType.DRONE, 3, 6, new Target.Point(point), point, 0, null, Loadout.Nuke.DEFAULT, via, null, false);
        CompoundTag saved = SalvoData.get(level).save(new CompoundTag(), level.registryAccess());
        SalvoData.get(level).cancel(level, null, List.of());
        ListTag salvos = saved.getList("salvos", Tag.TAG_COMPOUND);
        CompoundTag salvo = salvos.getCompound(salvos.size() - 1);
        Waypoints kept = Waypoints.CODEC.parse(NbtOps.INSTANCE, salvo.get("route")).result().orElse(Waypoints.NONE);
        h.assertTrue(kept.equals(via), "salvo kept route " + kept.points() + " instead of " + via.points());
        h.succeed();
    }

    /**
     * Launch site and route from a command ({@code /incoming salvo … from x z via x z …}) from the console: the launcher stands on
     * the launch site itself (the console has no owner) and faces the first point; the path — arming gate and points; a second
     * order from the same place — from the same launcher. A launch site in an unready chunk — an order, even for one projectile, waits for it as a salvo
     * ({@link LaunchPlaces}), and does not start outside the world already on the cruise leg.
     * The command does not parse points for the B-2; it does not accept a path longer than the range, or points outside the world (NaN, infinity, beyond the border). Launchers at launch sites are not counted as excess for the owner
     * and a salvo remembers the launch site in the save.
     */
    @GameTest(template = "runway", timeoutTicks = 20, batch = "launch_from", skyAccess = true)
    public static void commandLaunchPointAndRoute(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        Vec3 point = top(h, RUNWAY_TARGET);
        Vec3 from = h.absoluteVec(new Vec3(16.5, 0, 60.5)), a = h.absoluteVec(new Vec3(26, 0, 170)), b = h.absoluteVec(new Vec3(6, 0, 190));
        String at = String.format(Locale.ROOT, " at %.2f %.2f %.2f", point.x, point.y, point.z);
        String via = String.format(Locale.ROOT, " via %.2f %.2f %.2f %.2f", a.x, a.z, b.x, b.z);
        java.util.function.Predicate<LauncherEntity> placed = l -> l.isAlive() && flat(l.position(), from) < PLACE_SLACK;
        // launchers and projectiles on them — before launch: nothing takes off on the strip after the test
        StrikeGameTests.afterTest(h, () -> {
            level.getEntitiesOfClass(StrikeProjectile.class, h.getBounds().inflate(64)).forEach(Entity::discard);
            level.getEntitiesOfClass(LauncherEntity.class, h.getBounds().inflate(64)).forEach(Entity::discard);
        });

        placeReady(h, from);
        h.assertTrue(command(h, "incoming salvo drone 1 0" + at + fromArg(from) + via) == 1, "order from the launch site not accepted");
        List<LauncherEntity> launchers = level.getEntitiesOfClass(LauncherEntity.class, h.getBounds().inflate(64), LauncherEntity::isAlive);
        h.assertTrue(launchers.size() == 1 && placed.test(launchers.getFirst()), "launcher not at the launch site " + from + ": "
                + launchers.stream().map(Entity::position).toList());
        LauncherEntity launcher = launchers.getFirst();
        h.assertTrue(launcher.ordered() && launcher.ownerId() == null, "console launcher: at the launch site " + launcher.ordered() + ", owner " + launcher.ownerId());
        float toFirst = (float) Math.toDegrees(Math.atan2(-(a.x - launcher.getX()), a.z - launcher.getZ()));
        h.assertTrue(Math.abs(Mth.wrapDegrees(launcher.getYRot() - toFirst)) < 3, "launcher not facing the first point: heading " + launcher.getYRot() + ", to point " + toFirst);
        List<StrikeProjectile> onRail = level.getEntitiesOfClass(StrikeProjectile.class, h.getBounds().inflate(64), launcher::serves);
        h.assertTrue(onRail.size() == 1, "not one Shahed on the launcher: " + onRail.size());
        // its own chunk — from entry into the world, before the first tick: a launch site can be where entities do not tick, and without a ticket
        // a projectile on the rail would never tick
        StrikeProjectile first = onRail.getFirst();
        h.assertTrue(ChunkTickets.holds(level, first.getUUID(), ChunkPos.asLong(first.blockPosition())), "Shahed on a launch-site launcher does not hold its own chunk");
        List<Vec3> path = first.route().points();
        h.assertTrue(path.size() == 3 && flat(path.get(1), a) < 0.01 && flat(path.get(2), b) < 0.01, "route not gate → points: " + path);
        h.assertTrue(command(h, "incoming salvo drone 1 0" + at + fromArg(from)) == 1, "second order from the launch site not accepted");
        h.assertTrue(level.getEntitiesOfClass(LauncherEntity.class, h.getBounds().inflate(64), LauncherEntity::isAlive).size() == 1
                && level.getEntitiesOfClass(StrikeProjectile.class, h.getBounds().inflate(64), launcher::serves).size() == 2,
                "second order from the same place — not from the same launcher");
        // marker moved 50 blocks (in the area of the former one): a new order — from a new launcher at the new marker, not from the
        // old one, which faces the former target (the launcher remembers its marker)
        Vec3 moved = from.add(0, 0, 50);
        placeReady(h, moved);
        h.assertTrue(command(h, "incoming salvo drone 1 0" + at + fromArg(moved)) == 1, "order from the moved marker not accepted");
        List<LauncherEntity> both = level.getEntitiesOfClass(LauncherEntity.class, h.getBounds().inflate(64), LauncherEntity::isAlive);
        h.assertTrue(both.size() == 2 && both.stream().anyMatch(l -> flat(l.position(), moved) < PLACE_SLACK),
                "moved marker took the old launcher: " + both.stream().map(Entity::position).toList());

        // launch site far away, chunk not ready: one projectile waits for it as a salvo (the salvo loads the site); does not start outside the world without a launch
        Vec3 far = from.add(0, 0, 20_000);
        java.util.function.Predicate<StrikeProjectile> fromFar = f -> flat(f.position(), far) < 1e-6;
        h.assertTrue(LaunchPlaces.waits(level, WeaponType.DRONE, new LaunchOrigin.Place(far)), "distant launch site already ready");
        int pending = SalvoData.get(level).size();
        h.assertTrue(command(h, "incoming salvo drone 1 0" + at + fromArg(far)) == 1, "order from a distant launch site not accepted");
        h.assertTrue(command(h, "incoming salvo rocket 1 0" + at + fromArg(far)) == 1, "MLRS salvo from a distant launch site not accepted");
        h.assertTrue(SalvoData.get(level).size() == pending + 2, "one projectile from an unready launch site does not wait for it as a salvo: salvos "
                + (SalvoData.get(level).size() - pending));
        h.assertTrue(VirtualFlights.get(level).flights().stream().noneMatch(fromFar), "projectile from an unready launch site started outside the world without a launch");
        // for the B-2 the launch site is only the approach heading: it has nothing to wait for
        h.assertTrue(!LaunchPlaces.waits(level, WeaponType.BUNKER, new LaunchOrigin.Place(far)), "B-2 waits for a launch site");
        SalvoData.get(level).cancel(level, null, List.of());

        // the B-2 has no points; a path beyond the weapon's range — refused
        h.assertTrue(command(h, "incoming salvo bunker 1 0" + at + via) == -1, "command parsed route points for the B-2");
        Vec3 beyond = from.add(0, 0, -WeaponType.DRONE.spec().route().reach());
        h.assertTrue(command(h, "incoming salvo drone 1 0" + at + String.format(Locale.ROOT, " via %.2f %.2f", beyond.x, beyond.z)) == 0,
                "route longer than the range accepted");

        // salvo remembers the launch site
        h.assertTrue(command(h, "incoming salvo missile 3 6" + at + fromArg(far)) == 1, "salvo from the launch site not accepted");
        CompoundTag saved = SalvoData.get(level).save(new CompoundTag(), level.registryAccess());
        SalvoData.get(level).cancel(level, null, List.of());
        ListTag salvos = saved.getList("salvos", Tag.TAG_COMPOUND);
        CompoundTag salvo = salvos.getCompound(salvos.size() - 1);
        h.assertTrue(Math.abs(salvo.getDouble("from_x") - far.x) < 0.01 && Math.abs(salvo.getDouble("from_z") - far.z) < 0.01,
                "salvo kept launch site " + salvo.getDouble("from_x") + " " + salvo.getDouble("from_z") + " instead of " + far);

        // no points outside the world (NaN and infinity — from a forged map packet, beyond the border — from a command) exist either at the launch site
        // or on the route; the same points within the world's bounds pass
        Vec3 beyondBorder = new Vec3(level.getWorldBorder().getMaxX() + 100, 0, from.z);
        for (Vec3 bad : List.of(new Vec3(Double.NaN, 0, from.z), new Vec3(from.x, 0, Double.POSITIVE_INFINITY), beyondBorder)) {
            h.assertTrue(outsideWorld(ServerActions.routeProblem(level, null, false, WeaponType.DRONE, new LaunchOrigin.Place(bad), Waypoints.NONE, point)),
                    "launch site " + bad + " not named as outside the world");
            // a path through such a point is longer than the range anyway: the refusal must be exactly “outside the world”
            h.assertTrue(outsideWorld(ServerActions.routeProblem(level, null, false, WeaponType.DRONE, new LaunchOrigin.Place(from), new Waypoints(List.of(a, bad)), point)),
                    "route point " + bad + " not named as outside the world");
        }
        h.assertTrue(ServerActions.routeProblem(level, null, false, WeaponType.DRONE, new LaunchOrigin.Place(from), new Waypoints(List.of(a, b)), point) == null,
                "launch site and route within the world's bounds not accepted");
        h.assertTrue(command(h, "incoming salvo drone 1 0" + at + fromArg(beyondBorder)) == 0, "command accepted a launch site beyond the world border");

        // launchers at launch sites stand however many there are; the shooter has no more than three of its own following it
        UUID owner = UUID.randomUUID();
        Vec3 c = Vec3.atBottomCenterOf(h.absolutePos(new BlockPos(16, 4, 120)));
        for (int i = 0; i < LaunchSite.MAX_PER_OWNER + 1; i++) {
            LaunchSite.deploy(level, c.add(0, 0, 12 * i), 0, WeaponType.DRONE, new LaunchSite.Post(c, 0, owner, true));
            LaunchSite.deploy(level, c.add(0, 0, 12 * i + 6), 0, WeaponType.DRONE, new LaunchSite.Post(c, 0, owner, false));
        }
        List<LauncherEntity> mine = level.getEntitiesOfClass(LauncherEntity.class, h.getBounds().inflate(64), l -> l.isAlive() && owner.equals(l.ownerId()));
        h.assertTrue(mine.stream().filter(LauncherEntity::ordered).count() == LaunchSite.MAX_PER_OWNER + 1
                        && mine.stream().filter(l -> !l.ordered()).count() == LaunchSite.MAX_PER_OWNER,
                "launchers at launch sites " + mine.stream().filter(LauncherEntity::ordered).count() + ", for the owner " + mine.stream().filter(l -> !l.ordered()).count());
        h.succeed();
    }

    /**
     * Launch site from the remote's map ({@code C2S.Fire#from}, {@link ServerActions#launchSiteProblem}): only the
     * operator (or the host) chooses it, no farther than {@code map_range} from themselves, within the world's bounds. An operator's order from the remote follows the rules
     * (munition taken); the launcher stands at his launch site, and his “Stand down” removes it.
     */
    @GameTest(template = "runway", timeoutTicks = 20, batch = "launch_from_map", skyAccess = true)
    public static void designatorLaunchSite(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        Vec3 point = top(h, RUNWAY_TARGET);
        Vec3 from = h.absoluteVec(new Vec3(16.5, 0, 60.5));
        StrikeGameTests.afterTest(h, () -> {
            level.getEntitiesOfClass(StrikeProjectile.class, h.getBounds().inflate(64)).forEach(Entity::discard);
            level.getEntitiesOfClass(LauncherEntity.class, h.getBounds().inflate(64)).forEach(Entity::discard);
        });
        Vec3 stand = Vec3.atBottomCenterOf(h.absolutePos(new BlockPos(16, 4, 130)));
        FakePlayer player = new Ranked(level, "site_player", 0);
        player.moveTo(stand);
        Ranked op = new Ranked(level, "site_operator", 2);
        op.moveTo(stand);

        h.assertTrue(key(ServerActions.launchSiteProblem(player, from)).equals("incoming.map.launch.ops_only"), "launch site accepted not from the operator");
        h.assertTrue(ServerActions.launchSiteProblem(op, from) == null, "operator's nearby launch site not accepted");
        double range = IncomingConfig.SERVER.mapRange.get();
        h.assertTrue(key(ServerActions.launchSiteProblem(op, stand.add(range + 10, 0, 0))).equals("incoming.map.launch.out_of_range"),
                "launch site beyond map_range accepted");
        h.assertTrue(outsideWorld(ServerActions.launchSiteProblem(op, new Vec3(Double.NaN, 0, from.z))), "NaN launch site not named as outside the world");

        Item shahed = ModItems.SHAHED.get();
        MunitionGameTests.give(op, shahed, 1);
        ServerActions.Aim aim = new ServerActions.Aim(new Target.Point(point), point, null);
        placeReady(h, from);
        h.assertTrue(ServerActions.strike(op, WeaponType.DRONE, 1, 0, aim, Loadout.Nuke.DEFAULT, Waypoints.NONE, new LaunchOrigin.Place(from), true),
                "operator's order from the launch site not accepted");
        h.assertTrue(MunitionGameTests.held(op, shahed) == 0, "order from the remote did not take the munition: left " + MunitionGameTests.held(op, shahed));
        List<LauncherEntity> launchers = level.getEntitiesOfClass(LauncherEntity.class, h.getBounds().inflate(64), LauncherEntity::isAlive);
        h.assertTrue(launchers.size() == 1 && flat(launchers.getFirst().position(), from) < PLACE_SLACK, "launcher not at the launch site " + from + ": "
                + launchers.stream().map(Entity::position).toList());
        LauncherEntity launcher = launchers.getFirst();
        h.assertTrue(launcher.ordered() && op.getUUID().equals(launcher.ownerId()), "launch-site launcher: at the site " + launcher.ordered() + ", owner " + launcher.ownerId());
        List<StrikeProjectile> onRail = level.getEntitiesOfClass(StrikeProjectile.class, h.getBounds().inflate(64), launcher::serves);
        h.assertTrue(onRail.size() == 1 && op.getUUID().equals(onRail.getFirst().ownerId()), "not the operator's Shahed on the launcher: " + onRail.size());

        ServerActions.recall(level.getServer(), op.getUUID(), op.getGameProfile().getName());
        h.assertTrue(level.getEntitiesOfClass(LauncherEntity.class, h.getBounds().inflate(64), LauncherEntity::isAlive).isEmpty(), "“Stand down” did not remove the launch-site launcher");
        h.succeed();
    }

    /** GameTest player with {@code level} permissions (operator — 2): sends no packets, is not in the player list. */
    private static final class Ranked extends FakePlayer {
        private final int level;

        Ranked(ServerLevel world, String name, int level) {
            super(world, new GameProfile(UUID.randomUUID(), name));
            this.level = level;
        }

        @Override
        protected int getPermissionLevel() {
            return level;
        }
    }

    /** Refusal translation key; empty — no refusal. */
    private static String key(@Nullable Component problem) {
        return problem != null && problem.getContents() instanceof TranslatableContents t ? t.getKey() : "";
    }

    /**
     * The command target “at x z” — a place on the ground ({@link Target.Ground}), as from the remote's map: the server finds the height, and both
     * the salvo command and a single projectile (projectiles approach from afar, outside the world: the launch site from the order waits for its area,
     * {@link LaunchPlaces} — this is {@link #farLaunchPlaceLoadsThenLaunches}). “at x y z” — a point as is, also above the ground. The host does not know the height of a distant
     * site, and a point above the ground is an explosion in the air.
     */
    @GameTest(template = "runway", timeoutTicks = 20, batch = "command_place", skyAccess = true)
    public static void commandAtPlaceTargetsGround(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        Vec3 point = top(h, RUNWAY_TARGET);
        String place = String.format(Locale.ROOT, " at %.2f %.2f", point.x, point.z);
        java.util.function.Predicate<StrikeProjectile> near = f -> f.target() != null && flat(f.aimPoint(), point) < 2 + 5 * WeaponType.DRONE.spec().route().error();
        StrikeGameTests.afterTest(h, () -> {
            VirtualFlights.get(level).clear(level, near);
            level.getEntitiesOfClass(StrikeProjectile.class, h.getBounds().inflate(64)).forEach(Entity::discard);
            level.getEntitiesOfClass(LauncherEntity.class, h.getBounds().inflate(64)).forEach(Entity::discard);
        });

        h.assertTrue(command(h, "incoming salvo drone 1 0" + place) == 1, "salvo “at x z” not accepted");
        assertOnGround(h, aimingAt(level, near), point, "salvo “at x z”");
        h.assertTrue(command(h, "incoming drone" + place) == 1, "“drone at x z” not accepted");
        assertOnGround(h, aimingAt(level, near), point, "«drone at x z»");

        double air = point.y + 30;
        h.assertTrue(command(h, "incoming salvo drone 1 0" + String.format(Locale.ROOT, " at %.2f %.2f %.2f", point.x, air, point.z)) == 1,
                "“at x y z” not accepted");
        StrikeProjectile exact = aimingAt(level, near);
        h.assertTrue(exact.target() instanceof Target.Point p && p.pos().y == air, "“at x y z” — not a point at its own height: " + exact.target());
        h.succeed();
    }

    /**
     * A salvo with spread at a distant point whose area is not yet loaded for the launch: the projectiles hit the ground at their own places, not in
     * the air at the centre's height. The centre is 60 blocks above the ground, as the height the host set at random; the place
     * of a projectile takes the height as soon as its chunk is ready. The area loads after the first projectile — the others hit the ground anyway:
     * whether the centre is in the air or not is decided by the first projectile, and the salvo does not split.
     */
    @GameTest(template = "range", timeoutTicks = 120, batch = "spread_far_ground", skyAccess = true)
    public static void salvoSpreadAtFarPointLandsOnGround(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        java.util.UUID owner = java.util.UUID.randomUUID();
        Vec3 center = Vec3.atCenterOf(h.absolutePos(RANGE_CENTER)).add(5000, 60, 0);
        int radius = 20;
        h.assertFalse(Terrain.ready(level, BlockPos.containing(center)), "target area already loaded");
        SalvoData.start(level, WeaponType.DRONE, 3, radius, new Target.Point(center), center, 0, owner, Loadout.Nuke.DEFAULT, Waypoints.NONE, null, false);
        List<ChunkPos> held = new ArrayList<>();
        StrikeGameTests.afterTest(h, () -> {
            SalvoData.get(level).cancel(level, owner, List.of());
            VirtualFlights.get(level).clear(level, p -> owner.equals(p.ownerId()));
            level.getEntities(EntityTypeTest.forClass(StrikeProjectile.class), p -> owner.equals(p.ownerId())).forEach(Entity::discard);
            held.forEach(c -> level.getChunkSource().removeRegionTicket(READY_ONLY, c, 0, c));
        });
        h.runAfterDelay(3, () -> {
            h.assertTrue(fired(level, owner).size() == 1, "by the third tick the salvo fired not one projectile: " + fired(level, owner).size());
            // the target area is ready and held (in play, the approach loads it): the centre is now visible in the air
            ChunkPos middle = new ChunkPos(BlockPos.containing(center));
            for (int x = -2; x <= 2; x++) {
                for (int z = -2; z <= 2; z++) {
                    ChunkPos c = new ChunkPos(middle.x + x, middle.z + z);
                    level.getChunk(c.x, c.z);
                    level.getChunkSource().addRegionTicket(READY_ONLY, c, 0, c);
                    held.add(c);
                }
            }
            h.assertTrue(center.y - surface(level, center) > 30, "centre not above the ground: ground " + surface(level, center) + ", centre " + center.y);
        });
        h.succeedWhen(() -> {
            List<StrikeProjectile> fired = fired(level, owner);
            if (fired.size() < 3) throw new GameTestAssertException("salvo fired " + fired.size() + " of 3");
            for (StrikeProjectile p : fired) {
                h.assertTrue(p.target() instanceof Target.Ground, "salvo projectile aims at a point at the centre's height: " + p.target());
                Vec3 place = ((Target.Ground) p.target()).pos();
                level.getChunk(Mth.floor(place.x) >> 4, Mth.floor(place.z) >> 4);
                double ground = surface(level, place);
                Vec3 aim = p.target().resolve(level).orElseThrow();
                h.assertTrue(Math.abs(aim.y - ground) < 1e-6 && aim.y < center.y - 30,
                        "projectile place not on the ground: y " + aim.y + ", ground " + ground + ", centre " + center.y);
            }
        });
    }

    /** The test owner's projectiles — in the world and outside it. */
    private static List<StrikeProjectile> fired(ServerLevel level, java.util.UUID owner) {
        List<StrikeProjectile> fired = new ArrayList<>(VirtualFlights.get(level).flights());
        fired.addAll(level.getEntities(EntityTypeTest.forClass(StrikeProjectile.class), p -> true));
        fired.removeIf(p -> !owner.equals(p.ownerId()));
        return fired;
    }

    /** The test's own projectile (in the world or outside it) by predicate — exactly one; it is also removed so as not to disturb the next. */
    private static StrikeProjectile aimingAt(ServerLevel level, java.util.function.Predicate<StrikeProjectile> which) {
        List<StrikeProjectile> found = new ArrayList<>(VirtualFlights.get(level).flights().stream().filter(which).toList());
        found.addAll(level.getEntities(EntityTypeTest.forClass(StrikeProjectile.class), which));
        if (found.size() != 1) throw new GameTestAssertException("projectiles at the target " + found.size() + " instead of one");
        StrikeProjectile p = found.getFirst();
        VirtualFlights.get(level).clear(level, f -> f == p);
        if (!p.isVirtual()) p.discard();
        return p;
    }

    /**
     * Projectile target — a place on the ground {@code at}: the aim from the launch — on top of this column, and the place itself (with the miss offset)
     * — on its own top.
     */
    private static void assertOnGround(GameTestHelper h, StrikeProjectile p, Vec3 at, String what) {
        ServerLevel level = h.getLevel();
        h.assertTrue(p.target() instanceof Target.Ground, what + ": target not a place on the ground: " + p.target());
        double here = surface(level, at);
        h.assertTrue(Math.abs(p.aimPoint().y - here) < 1e-3, what + ": aim y " + p.aimPoint().y + " not on the ground " + here);
        Vec3 place = ((Target.Ground) p.target()).pos();
        Vec3 aim = p.target().resolve(level).orElseThrow();
        h.assertTrue(Math.abs(aim.y - surface(level, place)) < 1e-6, what + ": place y " + aim.y + " not on the ground " + surface(level, place));
    }

    /** Middle of the top block of the column (with foliage), as for a place from the map. */
    private static double surface(ServerLevel level, Vec3 column) {
        return level.getHeight(Heightmap.Types.MOTION_BLOCKING, Mth.floor(column.x), Mth.floor(column.z)) - 0.5;
    }

    /** Refusal of an order — “point beyond the world border” ({@code incoming.route.outside_world}). */
    private static boolean outsideWorld(@Nullable Component problem) {
        return problem != null && problem.getContents() instanceof TranslatableContents t && t.getKey().equals("incoming.route.outside_world");
    }

    /** Launcher at the order's launch site — at the centre of the site's own block (correction {@code LaunchSite.check}). */
    private static final double PLACE_SLACK = 1;

    /** “ from x z” for the command. */
    /**
     * A launch site beyond the loaded chunks ({@link LaunchPlaces}): a salvo of a single Grad takes the site's area while it waits,
     * and fires as soon as the site is ready (before {@link LaunchPlaces#WAIT}) — with a launcher at the site, and the projectile passes
     * the launch in the world (visible from afar). GameTest may have no flat place next to it in the world's random terrain — then
     * the start is without a launcher, as at a site in loaded chunks; this is a log line, not a failure.
     */
    @GameTest(template = "runway", timeoutTicks = 900, batch = "launch_place", skyAccess = true)
    public static void farLaunchPlaceLoadsThenLaunches(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        // the mod measures the wait for a site in ticks, while a fresh area generates at its own pace: the test runs at game pace
        gameSpeed(h);
        Vec3 far = h.absoluteVec(new Vec3(16.5, 0, 60.5)).add(-20_000, 0, 0);
        Vec3 aim = far.add(0, 0, 400);
        LaunchOrigin place = new LaunchOrigin.Place(far);
        java.util.function.Predicate<Entity> near = e -> flat(e.position(), far) < LaunchSite.PLACE_REACH + 8;
        StrikeGameTests.afterTest(h, () -> {
            VirtualFlights.get(level).clear(level, near::test);
            level.getEntitiesOfClass(StrikeProjectile.class, new AABB(far, far).inflate(600, 512, 600)).forEach(Entity::discard);
            level.getEntitiesOfClass(LauncherEntity.class, new AABB(far, far).inflate(LaunchSite.PLACE_REACH + 8, 256, LaunchSite.PLACE_REACH + 8)).forEach(Entity::discard);
        });
        h.assertTrue(LaunchPlaces.waits(level, WeaponType.ROCKET, place), "distant launch site already ready");
        int salvos = SalvoData.get(level).size();
        h.assertTrue(command(h, String.format(Locale.ROOT, "incoming salvo rocket 1 0 at %.2f %.2f", aim.x, aim.z) + fromArg(far)) == 1,
                "order from a distant launch site not accepted");
        h.assertTrue(SalvoData.get(level).size() == salvos + 1, "order from an unready site does not wait for it as a salvo");
        long start = level.getGameTime();
        h.succeedWhen(() -> {
            h.assertTrue(LaunchPlaces.ready(level, place), "launch site area not yet ready");
            h.assertTrue(SalvoData.get(level).size() == salvos, "salvo has not fired from the ready site yet");
            h.assertTrue(level.getGameTime() - start < LaunchPlaces.WAIT, "salvo waited for the site only up to the wait limit");
            List<LauncherEntity> pads = level.getEntitiesOfClass(LauncherEntity.class, new AABB(far, far).inflate(LaunchSite.PLACE_REACH + 8, 256, LaunchSite.PLACE_REACH + 8), l -> l.isAlive() && near.test(l));
            List<StrikeProjectile> rockets = level.getEntitiesOfClass(StrikeProjectile.class, new AABB(far, far).inflate(LaunchSite.PLACE_REACH + 8, 256, LaunchSite.PLACE_REACH + 8),
                    p -> p.weapon() == WeaponType.ROCKET && near.test(p));
            if (pads.isEmpty()) {
                Incoming.LOG.info("farLaunchPlaceLoadsThenLaunches: no flat place near {} in this world's terrain, launched without a launcher", BlockPos.containing(far));
                return;
            }
            h.assertTrue(!rockets.isEmpty() && rockets.stream().allMatch(r -> r.flightPhase().launching()),
                    "launcher at the distant site has no Grad on the launch: " + rockets.stream().map(r -> r.flightPhase()).toList());
        });
    }

    private static String fromArg(Vec3 from) {
        return String.format(Locale.ROOT, " from %.2f %.2f", from.x, from.z);
    }

    /**
     * The area of the launch site {@code at} is ready at once ({@link LaunchPlaces#ready}: chunks within {@link LaunchSite#PLACE_REACH} around):
     * GameTest loads only its own pad, and a site at its edge would otherwise wait for its area as a salvo, and the launcher
     * would not stand up in this tick. In play, a player's site area next to them is within their draw distance anyway.
     */
    static void placeReady(GameTestHelper h, Vec3 at) {
        ServerLevel level = h.getLevel();
        double r = LaunchSite.PLACE_REACH;
        for (int cx = Mth.floor(at.x - r) >> 4; cx <= Mth.floor(at.x + r) >> 4; cx++) {
            for (int cz = Mth.floor(at.z - r) >> 4; cz <= Mth.floor(at.z + r) >> 4; cz++) level.getChunk(cx, cz);
        }
        h.assertTrue(LaunchPlaces.ready(level, new LaunchOrigin.Place(at)), "launch site area " + at + " not ready");
    }

    /**
     * A command from the server console, as the host gives it: the command's result; −1 — not parsed (no such branch in the tree).
     */
    static int command(GameTestHelper h, String command) {
        net.minecraft.server.MinecraftServer server = h.getLevel().getServer();
        try {
            return server.getCommands().getDispatcher().execute(command,
                    server.createCommandSourceStack().withLevel(h.getLevel()).withPermission(4).withSuppressedOutput());
        } catch (com.mojang.brigadier.exceptions.CommandSyntaxException e) {
            return -1;
        }
    }

    /** Horizontal distance. */
    private static double flat(Vec3 p, Vec3 q) {
        return Math.hypot(p.x - q.x, p.z - q.z);
    }

    /**
     * A projectile saved in flight with a lifetime (no {@code mission} key): the remaining lifetime (the wait for the target's area
     * is not counted in it) and the fractional pursuit ticks become range at the cruise speed of the save; the
     * “capped after losing the target” flag carries over. A missile without the {@code cruise_speed} key (11.5 blocks/tick, on the climb)
     * flies at the present cruise speed, and its range is 11.5 blocks per tick of what is left: the path does not depend on speed. A save
     * with the {@code mission} key — unchanged.
     */
    @GameTest(template = "range", timeoutTicks = 20, skyAccess = true)
    public static void legacyFlightKeepsRange(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        Vec3 aim = Vec3.atCenterOf(h.absolutePos(RANGE_CENTER));
        CruiseMissileEntity m = ModEntities.CRUISE_MISSILE.get().create(level);
        m.launch(aim.add(0, 40, -300), new Target.Point(aim), aim, null);
        m.setRoute(Route.direct());
        CompoundTag tag = m.saveWithoutId(new CompoundTag());
        tag.putString("flight_phase", FlightPhase.POP_UP.getSerializedName());
        tag.getCompound("mission").putDouble("range", 1234.5);
        CruiseMissileEntity fresh = ModEntities.CRUISE_MISSILE.get().create(level);
        fresh.load(tag);
        h.assertTrue(fresh.rangeLeft() == 1234.5, "saved by the new version: range " + fresh.rangeLeft() + " instead of 1234.5");

        // without mission: lifetime 400 at age 100, of which 20 ticks waited for the target's area, half a tick of pursuit in range
        tag.remove("mission");
        tag.putInt("age", 100);
        tag.putInt("area_wait", 20);
        tag.putInt("lifetime", 400);
        tag.putDouble("lifetime_credit", 0.5);
        tag.putBoolean("lost_capped", true);
        double cruise = WeaponSpec.MISSILE.airframe().cruiseSpeed();
        CruiseMissileEntity saved = ModEntities.CRUISE_MISSILE.get().create(level);
        saved.load(tag);
        h.assertTrue(Math.abs(saved.rangeLeft() - 320.5 * cruise) < 1e-9, "lifetime 2.3.x: range " + saved.rangeLeft() + " instead of " + 320.5 * cruise);
        // range capped after losing the target is not capped again, and pursuit no longer grows it: the flag is carried over
        h.assertTrue(saved.saveWithoutId(new CompoundTag()).getCompound("mission").getBoolean("lost_capped"), "“capped” flag not carried over");

        tag.remove("cruise_speed");
        tag.putDouble("speed", 11.5);
        CruiseMissileEntity old = ModEntities.CRUISE_MISSILE.get().create(level);
        old.load(tag);
        h.assertTrue(old.speed() <= cruise, "old missile flies " + old.speed() + " blocks/tick");
        h.assertTrue(Math.abs(old.rangeLeft() - 320.5 * 11.5) < 1e-9, "missile 2.3.0: range " + old.rangeLeft() + " instead of " + 320.5 * 11.5);
        h.succeed();
    }

    /**
     * MLRS at 5 km, saved at the start of the arc with a lifetime (trajectory ticks + 200, no
     * {@code mission} key): on the far arc the projectile is faster than cruise (here ≈ 11 blocks/tick), and the remaining lifetime × cruise
     * runs out halfway. After loading, the range is the remaining arc from the saved time plus the overshoot; the usage is the distance covered,
     * so it is enough to reach the impact point. Without carrying over along the arc the range is less than the arc (checked below).
     */
    @GameTest(template = "range", timeoutTicks = 20, skyAccess = true)
    public static void legacyRocketKeepsArc(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        Vec3 point = Vec3.atCenterOf(h.absolutePos(RANGE_CENTER));
        RocketEntity r = ModEntities.ROCKET.get().create(level);
        r.launchFrom(point.add(0, 0, -5000), new Target.Point(point), point, null);
        CompoundTag tag = r.saveWithoutId(new CompoundTag());
        int flightTicks = tag.getInt("flight_ticks");
        double t = 10.5;
        tag.putDouble("t", t);
        tag.remove("mission");
        tag.putInt("age", 10);
        tag.putInt("lifetime", flightTicks + 200);
        RocketEntity saved = ModEntities.ROCKET.get().create(level);
        saved.load(tag);

        Vec3 start = ua.zentix.incoming.util.Nbt.getVec(tag, "start"), v0 = ua.zentix.incoming.util.Nbt.getVec(tag, "v0");
        // the path — by the same law as the projectile's: gravity, drag, the world's wind (there is no firing data — the wind entirely)
        var path = ua.zentix.incoming.guidance.Ballistics.fly(start, v0, WeaponSpec.ROCKET.airframe().drag(),
                ua.zentix.incoming.guidance.Ballistics.Wind.of(ua.zentix.incoming.util.Air.of(level)), point.y - 64, flightTicks + 400);
        double arc = path.at(11).distanceTo(path.at(t));
        for (int k = 11; k < flightTicks; k++) arc += path.at(k + 1).distanceTo(path.at(k));
        double cruise = WeaponSpec.ROCKET.airframe().cruiseSpeed();
        double old = (flightTicks + 200 - 10) * cruise;
        h.assertTrue(old < arc, "at 5 km the old carry-over (" + Math.round(old) + ") already covered the arc " + Math.round(arc) + " — the test catches nothing");
        double expected = arc + 200 * cruise;
        h.assertTrue(Math.abs(saved.rangeLeft() - expected) < 1e-6 * expected,
                "MLRS 2.3.x: range " + Math.round(saved.rangeLeft()) + " instead of arc " + Math.round(arc) + " + overshoot");
        h.succeed();
    }

    /**
     * Cruise missile with a launcher along a route with a detour (launch, acceleration, climb, route outside the world, climb, dive):
     * the speed does not sag at the transitions acceleration → climb → route (the booster accelerates below cruise, then the turbine),
     * and the time to impact announced at the launcher (HUD, siren, “impact in ~N s”) matches the real one within 10 %.
     * At game pace: the path goes beyond the pad, the target's area loads in the background. The target is on a platform 48 blocks above the strip:
     * the route goes over the pads of neighbouring tests, and a missile holding the target's height + 12 passes over their walls
     * of barriers (for “range” — 40 blocks; at low level it would hit the neighbour's wall 110 blocks to the side). The detour — to the east:
     * GameTest places pads in rows from west to east, and east of this one, while it runs, there are no pads, but west of it, within
     * 5 blocks of the strip, a “range” may stand — on acceleration the missile is still 20 blocks above the ground and would hit its wall
     * (with GameTest in parts, the neighbour can be any).
     */
    @GameTest(template = "runway", timeoutTicks = 1200, batch = "missile_launcher", skyAccess = true)
    public static void missileFromLauncherKeepsSpeedAndEta(GameTestHelper h) {
        gameSpeed(h);
        ServerLevel level = h.getLevel();
        BlockPos deck = new BlockPos(RUNWAY_TARGET.getX(), 47, RUNWAY_TARGET.getZ());
        for (BlockPos b : BlockPos.betweenClosed(deck.offset(-4, -3, -4), deck.offset(4, 0, 4))) h.setBlock(b, Blocks.STONE);
        Vec3 point = Vec3.atBottomCenterOf(h.absolutePos(deck.above()));
        // 600 blocks of path: the detour point ~220 blocks east of the strip, entry 150 blocks before the target; the packet, as in play
        // (StrikeService.fromLauncher), faces the route's first point
        Vec3 site = Vec3.atBottomCenterOf(h.absolutePos(new BlockPos(16, 4, 12)));
        Vec3 approach = new Vec3(0, 0, 1);
        Vec3 first = Route.plan(site, point, approach, 600, 150, -1).current();
        float yaw = ua.zentix.incoming.guidance.FlightController.anglesTo(site, first)[0];
        LauncherEntity launcher = LauncherEntity.create(level, site, yaw, WeaponType.MISSILE, null, false);
        level.addFreshEntity(launcher);
        int ready = LauncherEntity.DEPLOY_TICKS + 10;
        Vec3 rail = launcher.railPoint(0);
        CruiseMissileEntity missile = ModEntities.CRUISE_MISSILE.get().create(level);
        missile.placeOnLauncher(rail, launcher.getYRot(), launcher.elevation(), ready, LauncherEntity.DEPLOY_TICKS, new Target.Point(point), point, null);
        missile.setRoute(Route.plan(rail, point, approach, 600, 150, -1));
        int eta = missile.etaTicks();
        level.addFreshEntity(missile);
        UUID id = missile.getUUID();
        int[] ticks = {0};
        double[] prev = {-1};
        boolean[] cruised = {false};
        String[] drop = {null};
        String[] last = {""};
        StrikeProjectile[] seen = {null};
        h.onEachTick(() -> {
            StrikeProjectile p = findProjectile(level, id);
            if (p == null) {
                // how it left: explosion (DISCARDED), unloaded with its chunk, out of the world or beyond it — for the failure message
                if (seen[0] != null) last[0] += ", gone: " + seen[0].getRemovalReason() + (seen[0].isVirtual() ? " outside the world" : " in the world");
                seen[0] = null;
                return;
            }
            seen[0] = p;
            ticks[0]++;
            FlightPhase ph = p.flightPhase();
            last[0] = ph + " " + h.relativeVec(p.position()) + " v=" + p.speed();
            if (ph == FlightPhase.CRUISE) cruised[0] = true;
            boolean steady = ph == FlightPhase.BOOST || ph == FlightPhase.CLIMB || ph == FlightPhase.CRUISE;
            if (steady && prev[0] >= 0 && p.speed() < prev[0] - 1e-9 && drop[0] == null) {
                drop[0] = "speed dropped " + prev[0] + " → " + p.speed() + " at " + ph;
            }
            prev[0] = steady ? p.speed() : -1;
        });
        h.succeedWhen(() -> {
            h.assertTrue(findProjectile(level, id) == null, "missile still flying: " + last[0]);
            assertCrater(h, deck, last[0]);
            h.assertTrue(cruised[0], "missile did not enter the route");
            h.assertTrue(drop[0] == null, drop[0]);
            h.assertTrue(Math.abs(ticks[0] - eta) <= eta / 10, "time to impact at the launcher " + eta + " ticks, in fact " + ticks[0]);
            h.assertTrue(FlightTickets.held(level, id) == 0, "missile did not release the areas");
        });
    }

    /** Projectile by UUID: in the world or outside it (leaving the loaded chunks, it becomes a new entity). */
    private static StrikeProjectile findProjectile(ServerLevel level, UUID id) {
        if (level.getEntity(id) instanceof StrikeProjectile e) return e;
        for (StrikeProjectile p : VirtualFlights.get(level).flights()) {
            if (p.getUUID().equals(id)) return p;
        }
        return null;
    }

    /**
     * The target's area loads in advance, and ticks only the last {@link StrikeProjectile#AREA_TICK_LEAD} ticks of the approach:
     * a missile from afar outside the world (no player at the target — no approach strip) takes the area without ticking from launch (to the target less than
     * {@code PRELOAD_TICKS} of flight), ticking when no more than this is left before impact, and releases everything after impact.
     */
    @GameTest(template = "runway", timeoutTicks = 1200, batch = "missile_area_late_tick", skyAccess = true)
    public static void targetAreaTicksOnlyNearArrival(GameTestHelper h) {
        areaTicksOnlyNearArrival(h, 1200);
    }

    /**
     * The same at real speed (flight scale 1): the area loads from launch and ticks the last {@link
     * StrikeProjectile#AREA_TICK_LEAD} ticks — the approach time is computed from the real cruise, not the game one (otherwise
     * the area would tick three times earlier, or the missile would arrive at a non-ticking one).
     */
    @GameTest(template = "runway", timeoutTicks = 1200, batch = "flight_scale_area", skyAccess = true)
    public static void targetAreaTicksOnlyNearArrivalAtRealSpeed(GameTestHelper h) {
        flightScale(h, 1);
        areaTicksOnlyNearArrival(h, 1200 * WeaponSpec.MISSILE.airframe().stretch(1));
    }

    /** Missile {@code distance} blocks from the target outside the world: the target's area loads from launch, ticks only on the approach. */
    private static void areaTicksOnlyNearArrival(GameTestHelper h, double distance) {
        ServerLevel level = h.getLevel();
        Vec3 point = top(h, RUNWAY_TARGET);
        CruiseMissileEntity missile = ModEntities.CRUISE_MISSILE.get().create(level);
        missile.launch(point.add(distance, 80, 0), new Target.Point(point), point, null);
        missile.setRoute(Route.direct());
        VirtualFlights.launch(level, missile);
        UUID id = missile.getUUID();
        boolean[] loadedEarly = {false}, tickedLate = {false};
        String[] early = {null};
        h.onEachTick(() -> {
            if (!(findProjectile(level, id) instanceof StrikeProjectile p)) return;
            int eta = p.etaTicks();
            if (eta > StrikeProjectile.AREA_TICK_LEAD + 1) {
                if (FlightTickets.held(level, id) > 0) loadedEarly[0] = true;
                if (FlightTickets.ticking(level, id) > 0 && early[0] == null) early[0] = "area ticks " + eta + " ticks before impact";
            } else if (eta < StrikeProjectile.AREA_TICK_LEAD - 1 && FlightTickets.ticking(level, id) == 1) {
                tickedLate[0] = true;
            }
        });
        h.succeedWhen(() -> {
            h.assertTrue(findProjectile(level, id) == null, "missile still flying");
            h.assertTrue(early[0] == null, early[0]);
            h.assertTrue(loadedEarly[0], "target area did not load in advance");
            h.assertTrue(tickedLate[0], "target area never became ticking on the approach");
            assertCrater(h, RUNWAY_TARGET, "");
            h.assertTrue(FlightTickets.held(level, id) == 0, "missile did not release the areas");
        });
    }

    /**
     * A projectile that no player sees holds only its own chunk ({@link ChunkTickets}), not the chunk ahead: otherwise
     * the ticket on it would load the square around, and the projectile would drag ticking chunks along to the target. A missile with a launcher without
     * players: at the start — its own and the one ahead; in flight — no more than one; and beyond the pad it goes into flight outside the world.
     */
    @GameTest(template = "runway", timeoutTicks = 1200, batch = "missile_unseen_chunks", skyAccess = true)
    public static void unseenMissileHoldsOnlyItsChunk(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        Vec3 point = top(h, RUNWAY_TARGET).add(0, 0, 900);
        Vec3 site = Vec3.atBottomCenterOf(h.absolutePos(new BlockPos(16, 4, 12)));
        float yaw = ua.zentix.incoming.guidance.FlightController.anglesTo(site, point)[0];
        LauncherEntity launcher = LauncherEntity.create(level, site, yaw, WeaponType.MISSILE, null, false);
        level.addFreshEntity(launcher);
        Vec3 rail = launcher.railPoint(0);
        CruiseMissileEntity missile = ModEntities.CRUISE_MISSILE.get().create(level);
        missile.placeOnLauncher(rail, launcher.getYRot(), launcher.elevation(), LauncherEntity.DEPLOY_TICKS + 10, LauncherEntity.DEPLOY_TICKS,
                new Target.Point(point), point, null);
        missile.setRoute(Route.direct());
        level.addFreshEntity(missile);
        UUID id = missile.getUUID();
        // had it arrived, the missile would have exploded 900 blocks away — where the grid places the pads of the next batches
        afterTest(h, () -> {
            VirtualFlights.get(level).clear(level, p -> p.getUUID().equals(id));
            if (level.getEntity(id) != null) level.getEntity(id).discard();
        });
        int[] most = {0}, flying = {0};
        boolean[] virtual = {false};
        h.onEachTick(() -> {
            if (!(findProjectile(level, id) instanceof StrikeProjectile p)) return;
            if (p.isVirtual()) virtual[0] = true;
            // the ticket is dropped at the next transfer after the phase change: the first tick after launch still has the launch
            else if (p.flightPhase().launching()) flying[0] = 0;
            else if (++flying[0] > 1) most[0] = Math.max(most[0], ChunkTickets.held(level, id));
        });
        h.succeedWhen(() -> {
            h.assertTrue(virtual[0], "missile did not go into flight outside the world");
            h.assertTrue(most[0] <= 1, "in flight without players the missile held " + most[0] + " chunks");
        });
    }

    /**
     * A missile from afar outside the world returns to the world at the edge of the approach strip ({@link CruiseMissileEntity#VISIBLE_LEG}),
     * not at the target's area (±40 blocks): its approach is visible to the player at the target, and at a simulation distance less than the draw distance.
     * The approach is across the strip: GameTest holds the pad's own chunks, and a missile along it would enter the world even without the approach strip.
     * The strip's chunks are generated in advance: the bench, not GameTest, measures the background generation time. At game pace: areas
     * grow by ticks. Without the strip the missile enters the world at 38 blocks. Impact — and all areas are released.
     */
    @GameTest(template = "runway", timeoutTicks = 1200, batch = "missile_approach", skyAccess = true)
    public static void missileEntersWorldAtApproachEdge(GameTestHelper h) {
        gameSpeed(h);
        ServerLevel level = h.getLevel();
        Vec3 point = top(h, RUNWAY_TARGET);
        Vec3 start = point.add(1200, 80, 0);
        for (ChunkPos c : FlightTickets.approach(List.of(point, start), WeaponSpec.MISSILE.airframe().visibleLeg())) {
            int r = FlightTickets.APPROACH_DISTANCE;
            for (int dx = -r; dx <= r; dx++) for (int dz = -r; dz <= r; dz++) level.getChunk(c.x + dx, c.z + dz);
        }
        watcher(h, point);
        CruiseMissileEntity missile = ModEntities.CRUISE_MISSILE.get().create(level);
        missile.launch(start, new Target.Point(point), point, null);
        missile.setRoute(Route.direct());
        VirtualFlights.launch(level, missile);
        UUID id = missile.getUUID();
        double[] entered = {-1};
        String[] last = {""};
        h.onEachTick(() -> {
            if (level.getEntity(id) instanceof CruiseMissileEntity m && !m.isVirtual()) {
                if (entered[0] < 0) entered[0] = Math.hypot(m.getX() - point.x, m.getZ() - point.z);
                last[0] = "in the world " + m.flightPhase() + " " + h.relativeVec(m.position());
            } else if (findProjectile(level, id) instanceof StrikeProjectile p) {
                last[0] = "outside the world " + p.flightPhase() + " " + h.relativeVec(p.position());
            }
        });
        h.succeedWhen(() -> {
            h.assertTrue(findProjectile(level, id) == null, "missile still flying: " + last[0]);
            assertCrater(h, RUNWAY_TARGET, last[0]);
            h.assertTrue(entered[0] >= 224, "missile returned to the world at " + Math.round(entered[0]) + " blocks from the target, not at the edge of the approach strip");
            h.assertTrue(FlightTickets.held(level, id) == 0, "missile did not release the areas");
        });
    }

    /**
     * The approach strip — only when there is someone to watch at the target; shared by a salvo and within {@link FlightTickets#APPROACH_LIMIT}
     * per world (a strip per missile — ~150 chunks; a salvo of 30 missiles — thousands of chunks in the generation queue).
     * Two missiles to one point from one direction hold one set of areas; the player left — the strip is released; a strip
     * that does not fit in the world is not taken.
     */
    @GameTest(template = "runway", timeoutTicks = 200, batch = "missile_approach_shared", skyAccess = true)
    public static void missileApproachOnlyWatchedSharedAndCapped(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        Vec3 point = top(h, RUNWAY_TARGET);
        List<CruiseMissileEntity> made = new ArrayList<>();
        afterTest(h, () -> made.forEach(m -> {
            StrikeProjectile p = findProjectile(level, m.getUUID());
            if (p != null) p.discard();
        }));
        // outside the world, 1000 blocks away: the target's area is taken at once, impact is far off
        for (int i = 0; i < 2; i++) {
            CruiseMissileEntity m = ModEntities.CRUISE_MISSILE.get().create(level);
            m.launch(point.add(1000, 80, 0), new Target.Point(point), point, null);
            m.setRoute(Route.direct());
            VirtualFlights.launch(level, m);
            made.add(m);
        }
        int n = FlightTickets.approach(List.of(point, point.add(1000, 0, 0)), WeaponSpec.MISSILE.airframe().visibleLeg()).size();
        FakePlayer[] player = {null};
        int[] tick = {0};
        String[] fail = {null};
        h.onEachTick(() -> {
            int t = ++tick[0];
            if (fail[0] != null) return;
            int areas = FlightTickets.approachAreas(level);
            int held = FlightTickets.held(level, made.getFirst().getUUID());
            if (t == 30 && (areas != 0 || held != 1)) fail[0] = "without a player at the target: strip areas " + areas + ", missile areas " + held;
            if (t == 31) player[0] = watcher(h, point);
            if (t == 60 && (areas != n || held != 1 + n || FlightTickets.held(level, made.get(1).getUUID()) != 1 + n)) {
                fail[0] = "with a player at the target: strip areas " + areas + " (need " + n + " — one strip for both missiles), missile " + held;
            }
            if (t == 61) {
                // strips of other directions: the world reaches the limit, an extra strip is not taken whole
                UUID other = UUID.randomUUID();
                int taken = 0;
                for (int k = 1; k < 64; k++) {
                    double a = Math.toRadians(k * 5.625);
                    List<ChunkPos> c = FlightTickets.approach(List.of(point, point.add(1000 * Math.sin(a), 0, -1000 * Math.cos(a))),
                            WeaponSpec.MISSILE.airframe().visibleLeg());
                    if (!FlightTickets.holdApproach(level, c, other)) break;
                    taken++;
                }
                int full = FlightTickets.approachAreas(level);
                if (full > FlightTickets.APPROACH_LIMIT || taken == 0) fail[0] = "strip limit: areas " + full + ", strips taken " + taken;
                for (int k = 1; k <= taken; k++) {
                    double a = Math.toRadians(k * 5.625);
                    FlightTickets.releaseApproach(level, FlightTickets.approach(List.of(point, point.add(1000 * Math.sin(a), 0, -1000 * Math.cos(a))),
                            WeaponSpec.MISSILE.airframe().visibleLeg()), other);
                }
                if (FlightTickets.approachAreas(level) != n) fail[0] = "after releasing others' strips, areas " + FlightTickets.approachAreas(level) + ", need " + n;
                level.players().remove(player[0]);
            }
            if (t == 90 && (areas != 0 || held != 1)) fail[0] = "player left, but strip areas " + areas + ", missile " + held;
        });
        h.runAtTickTime(100, () -> {
            if (fail[0] != null) throw new GameTestAssertException(fail[0]);
            h.assertTrue(n >= 6, "strip of " + n + " areas");
            h.succeed();
        });
    }

    /**
     * Player at the target for the approach strip ({@link FlightTickets#watched}): a NeoForge {@code FakePlayer} only
     * in the world's player list — without chunk tickets (it is not in {@code ChunkMap}) and without packets. Removed
     * after the test.
     */
    private static FakePlayer watcher(GameTestHelper h, Vec3 at) {
        ServerLevel level = h.getLevel();
        var p = new FakePlayer(level, new GameProfile(UUID.randomUUID(), "watcher"));
        p.moveTo(at.x, at.y, at.z);
        level.players().add(p);
        afterTest(h, () -> level.players().remove(p));
        return p;
    }

    /**
     * MLRS: three rockets from the tubes of one rack — a queue, not at once (even when ordered while the rack rises), a
     * ballistic arc above fifty blocks and a hit on the target at the end of the strip.
     */
    @GameTest(template = "runway", timeoutTicks = 600, batch = "rocket", skyAccess = true)
    public static void rocketsRippleFromLauncher(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        Vec3 point = top(h, RUNWAY_TARGET);
        LauncherEntity launcher = LauncherEntity.create(level, Vec3.atBottomCenterOf(h.absolutePos(new BlockPos(16, 4, 12))), 0, WeaponType.ROCKET, null, false);
        level.addFreshEntity(launcher);
        List<RocketEntity> rockets = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            int[] slot = launcher.reserve(level.getGameTime(), 10, LauncherRack.ROCKET.busyTicks());
            RocketEntity r = ModEntities.ROCKET.get().create(level);
            r.placeInTube(launcher.railPoint(slot[0]), launcher.getYRot(), launcher.elevation(), slot[1], LauncherEntity.DEPLOY_TICKS,
                    new Target.Point(point), point, null);
            level.addFreshEntity(r);
            rockets.add(r);
        }
        long[] fired = new long[3];
        double[] apex = {-1e9};
        String[] last = {""};
        h.onEachTick(() -> {
            for (int i = 0; i < 3; i++) {
                RocketEntity r = rockets.get(i);
                if (fired[i] == 0 && r.flightPhase().ordinal() >= FlightPhase.BOOST.ordinal()) fired[i] = level.getGameTime();
                if (!r.isRemoved()) {
                    apex[0] = Math.max(apex[0], r.getY() - point.y);
                    last[0] = "№" + i + " " + r.flightPhase() + " " + h.relativeVec(r.position()) + " v=" + r.speed();
                }
            }
        });
        h.succeedWhen(() -> {
            for (RocketEntity r : rockets) h.assertTrue(r.isRemoved(), "rocket still flying: " + last[0]);
            for (int i = 1; i < 3; i++) {
                h.assertTrue(fired[i] - fired[i - 1] >= LauncherEntity.spacing(WeaponType.ROCKET), "departure not as a queue: " + java.util.Arrays.toString(fired));
            }
            h.assertTrue(apex[0] > 40, "not ballistic: arc apex " + apex[0] + " above the target");
            assertCrater(h, RUNWAY_TARGET, last[0]);
        });
    }

    /** MLRS without a launcher: a rocket from a position 600 blocks away, flight outside the world and a hit. */
    @GameTest(template = "runway", timeoutTicks = 800, batch = "rocket_far", skyAccess = true)
    public static void rocketFromAfar(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        Vec3 point = top(h, RUNWAY_TARGET);
        RocketEntity r = ModEntities.ROCKET.get().create(level);
        r.launchFrom(point.add(0, 0, -600), new Target.Point(point), point, null);
        VirtualFlights.launch(level, r);
        java.util.UUID id = r.getUUID();
        String[] last = {""};
        h.onEachTick(() -> {
            if (level.getEntity(id) instanceof RocketEntity e) last[0] = "in the world " + e.flightPhase() + " " + h.relativeVec(e.position());
        });
        h.succeedWhen(() -> {
            h.assertTrue(VirtualFlights.get(level).flights().isEmpty() && level.getEntity(id) == null, "rocket still flying: " + last[0]);
            assertCrater(h, RUNWAY_TARGET, last[0]);
        });
    }

    /**
     * MLRS without a launcher on the real flight scale (1): the rack at the passport standoff for this scale (~19 km),
     * exit at the real 683 m/s (±3 %) with real drag and the Mach curve, flight outside the world for more than a
     * minute and a hit.
     */
    @GameTest(template = "runway", timeoutTicks = 2400, batch = "flight_scale_rocket", skyAccess = true)
    public static void rocketFromAfarAtRealSpeed(GameTestHelper h) {
        flightScale(h, 1);
        ServerLevel level = h.getLevel();
        Vec3 point = top(h, RUNWAY_TARGET);
        RocketEntity r = ModEntities.ROCKET.get().create(level);
        Vec3 from = point.add(0, 0, -WeaponSpec.ROCKET.standoff());
        r.launchFrom(from, new Target.Point(point), point, null);
        VirtualFlights.launch(level, r);
        java.util.UUID id = r.getUUID();
        String[] last = {""};
        // exit — by the first step of the path (in a tick, drag and gravity take less than a percent from 683 m/s)
        double[] exit = {Double.NaN};
        h.onEachTick(() -> {
            if (level.getEntity(id) instanceof RocketEntity e) last[0] = "in the world " + e.flightPhase() + " " + h.relativeVec(e.position());
            if (Double.isNaN(exit[0]) && findProjectile(level, id) instanceof StrikeProjectile p && p.position().distanceTo(from) > 1) {
                exit[0] = p.position().distanceTo(from);
            }
        });
        h.succeedWhen(() -> {
            double want = WeaponSpec.ROCKET.airframe().cruiseSpeed();
            h.assertTrue(Math.abs(exit[0] - want) < 0.03 * want, "exit " + Math.round(exit[0] * 20) + " m/s, not " + Math.round(want * 20));
            h.assertTrue(VirtualFlights.get(level).flights().isEmpty() && level.getEntity(id) == null, "rocket still flying: " + last[0]);
            assertCrater(h, RUNWAY_TARGET, last[0]);
        });
    }

    /**
     * Projectile outside the world for distant players: an MLRS rocket flies “virtually” 600 blocks from the target —
     * its path (phase, where it is, speed — its shift per tick, where it goes) goes out with each packet to those who
     * hear it: how far — {@link Hearing} derives from the sound passport and the path to the ear. Outside the world the
     * rocket flies on inertia, and its howl is heard for hundreds of blocks: at half the derived range it is heard, at
     * one and a half it is not heard but seen (the path goes out only with the packet of visible ones, without an
     * “audible” mark); beyond {@code far_range} — not at all.
     */
    @GameTest(template = "runway", timeoutTicks = 100, batch = "heard", skyAccess = true)
    public static void virtualFlightReachesOnlyThoseWhoHearOrSeeIt(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        Vec3 point = top(h, RUNWAY_TARGET);
        RocketEntity r = ModEntities.ROCKET.get().create(level);
        r.launchFrom(point.add(0, 0, -600), new Target.Point(point), point, null);
        VirtualFlights.launch(level, r);
        Vec3[] before = new Vec3[1];
        h.runAfterDelay(9, () -> before[0] = r.position());
        h.runAfterDelay(10, () -> {
            var flights = FarFlights.flights(level);
            h.assertTrue(flights.stream().anyMatch(f -> f.getUUID().equals(r.getUUID()) && f.isVirtual()), "rocket is not among those flying outside the world");
            Vec3 at = r.position();
            Propagation.Weather weather = ua.zentix.incoming.util.Air.weather(level);
            double heard = Hearing.range(r, 0, weather);
            h.assertTrue(heard > 50 && heard < 0.5 * FarFlights.range(), "heard on inertia up to " + heard);
            // only its own rocket: other checks' projectiles fly in the batch and in the CI shard too
            // (the batch order depends on the split)
            java.util.function.Predicate<ua.zentix.incoming.net.S2C.FarFlight> own = p -> p.id().equals(r.getUUID());
            var near = FarFlights.sample(level, flights, at.add(0.5 * heard, 0, 0), null, false).stream().filter(own).toList();
            h.assertTrue(near.size() == 1 && near.getFirst().audible(),
                    "at " + Math.round(0.5 * heard) + " blocks (half the audibility range) not heard: " + near);
            var f = near.getFirst();
            h.assertTrue(f.pos().distanceTo(at) < 1.0e-6 && f.weapon() == WeaponType.ROCKET.id() && !f.bomber(), "wrong path: " + f);
            h.assertTrue(f.aim().distanceTo(r.aimPoint()) < 1.0e-6, "aim: " + f.aim());
            Vec3 step = at.subtract(before[0]);
            h.assertTrue(step.length() > 1 && f.velocity().distanceTo(step) < 1.0e-6, "speed " + f.velocity() + ", but the shift per tick " + step);
            Vec3 beyond = at.add(1.5 * heard, 0, 0);
            h.assertTrue(FarFlights.sample(level, flights, beyond, null, false).stream().noneMatch(own), "heard at one and a half audibility ranges");
            var seen = FarFlights.sample(level, flights, beyond, null, true).stream().filter(own).toList();
            h.assertTrue(seen.size() == 1 && !seen.getFirst().audible(), "at " + Math.round(1.5 * heard) + " blocks not visible or audible: " + seen);
            h.assertTrue(FarFlights.sample(level, flights, at.add(FarFlights.range() + 100, 0, 0), null, true).stream().noneMatch(own), "seen beyond far_range");
            // do not let it land: no one else is in the batch,
            // and a rocket landing after the test ends would land on another test's pad
            VirtualFlights.get(level).clear(level, p -> true);
            h.succeed();
        });
    }

    /**
     * Cruise missile on attack whose target ended up to the side, inside its turn circle (at 4 blocks/tick and 3°/tick
     * — radius ~80 blocks, with the margin {@code TURN_MARGIN} ~90): it flies straight until the target leaves the
     * circle, and turns in again. Otherwise it circles the target until its lifetime runs out, and is removed on attack
     * hundreds of blocks from the target.
     */
    @GameTest(template = "range", timeoutTicks = 400, batch = "missile_reattack", skyAccess = true)
    public static void missileReattacksTargetInsideTurn(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        Vec3 target = Vec3.atCenterOf(h.absolutePos(RANGE_CENTER)).add(0, 60, 0);
        CruiseMissileEntity m = ModEntities.CRUISE_MISSILE.get().create(level);
        m.launch(target.add(0, 0, -150), new Target.Point(target), target, null);
        m.setRoute(Route.direct());
        // heading east, target 150 blocks to the south, phase already attack, lifetime with margin for the approach
        CompoundTag tag = new CompoundTag();
        m.saveWithoutId(tag);
        tag.getCompound("flight").putFloat("yaw", -90);
        tag.getCompound("flight").putFloat("pitch", 0);
        tag.putString("flight_phase", FlightPhase.TERMINAL.getSerializedName());
        tag.getCompound("mission").putDouble("range", 300 * m.cruiseSpeed());
        m.load(tag);
        VirtualFlights.launch(level, m);
        java.util.UUID id = m.getUUID();
        Vec3[] lastPos = {m.position()};
        int[] lastAge = {0};
        boolean[] reattacked = {false};
        String[] last = {""};
        h.onEachTick(() -> {
            StrikeProjectile p = level.getEntity(id) instanceof StrikeProjectile e ? e : VirtualFlights.get(level).flights().stream()
                    .filter(f -> f.getUUID().equals(id)).findFirst().orElse(null);
            if (p == null) return;
            lastPos[0] = p.position();
            lastAge[0] = p.age();
            if (p.flightPhase() == FlightPhase.CRUISE) reattacked[0] = true;
            last[0] = p.flightPhase() + " " + h.relativeVec(p.position()) + " age " + p.age();
        });
        h.succeedWhen(() -> {
            h.assertTrue(level.getEntity(id) == null && VirtualFlights.get(level).flights().stream().noneMatch(f -> f.getUUID().equals(id)),
                    "missile still flying: " + last[0]);
            h.assertTrue(reattacked[0], "attack was not cancelled: " + last[0]);
            h.assertTrue(lastPos[0].distanceTo(target) < 20, "missile removed not at the target (circled until the end of its lifetime?): " + last[0]);
        });
    }

    /**
     * Loitering munition by UUID: in the world or outside it (when it leaves loaded chunks it becomes
     * a new entity).
     */
    private static LoiterEntity findLoiter(ServerLevel level, java.util.UUID id) {
        if (level.getEntity(id) instanceof LoiterEntity e) return e;
        for (StrikeProjectile p : VirtualFlights.get(level).flights()) {
            if (p.getUUID().equals(id) && p instanceof LoiterEntity e) return e;
        }
        return null;
    }

    /**
     * Target of the loitering munition — in the air 60 blocks above the strip: the circle (another 45 higher) and the
     * dive run over the test site's barrier wall, and the circle of radius 40 is wider than the strip.
     */
    private static Vec3 airTarget(GameTestHelper h) {
        return top(h, RUNWAY_TARGET).add(0, 60, 0);
    }

    /**
     * Loitering munition: approach, circles over the target the whole loiter time (from settings, ±20%) at its own
     * radius, then a dive and a detonation at the target.
     */
    @GameTest(template = "runway", timeoutTicks = 1400, batch = "loiter", skyAccess = true)
    public static void loiterCirclesThenDives(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        Vec3 point = airTarget(h);
        LoiterEntity e = ModEntities.LOITER.get().create(level);
        e.launch(point.add(0, WeaponSpec.LOITER.airframe().cruiseHeight(), -150), new Target.Point(point), point, null);
        e.setRoute(null);
        level.addFreshEntity(e);
        java.util.UUID id = e.getUUID();
        int[] loiter = {0};
        boolean[] dived = {false};
        // the largest deviation from its own circle (the munition enters the circle tangentially — from the first tick)
        double[] drift = {0, 0};
        Vec3[] lastPos = {null};
        String[] last = {""};
        h.onEachTick(() -> {
            LoiterEntity l = findLoiter(level, id);
            if (l == null) return;
            lastPos[0] = l.position();
            last[0] = l.flightPhase() + " " + h.relativeVec(l.position()) + " v=" + l.speed();
            if (l.flightPhase() == FlightPhase.LOITER) {
                loiter[0]++;
                double off = Math.hypot(l.getX() - point.x, l.getZ() - point.z) - l.orbitRadius();
                if (Math.abs(off) > Math.abs(drift[0])) {
                    drift[0] = off;
                    drift[1] = l.orbitRadius();
                }
            }
            if (l.flightPhase() == FlightPhase.TERMINAL) dived[0] = true;
        });
        h.succeedWhen(() -> {
            h.assertTrue(findLoiter(level, id) == null, "loitering munition still flying: " + last[0]);
            int min = (int) (ua.zentix.incoming.IncomingConfig.SERVER.loiterTime.get() * 20 * 0.8);
            h.assertTrue(loiter[0] >= min, "circled " + loiter[0] + " ticks, but must be at least " + min);
            h.assertTrue(Math.abs(drift[0]) <= ua.zentix.incoming.guidance.Orbit.TOLERANCE, "left the circle of radius " + drift[1] + " at " + drift[0]);
            h.assertTrue(dived[0], "did not dive: " + last[0]);
            h.assertTrue(lastPos[0].distanceTo(point) < 8, "detonation not at the target: " + last[0]);
        });
    }

    /**
     * Lancet range covers the longest configured orbit (+20%): approach distance times 1.5, orbit at cruise speed,
     * and dive at its own speed ({@code LoiterEntity.extraRange}), leaving {@link Mission#RESERVE_TICKS} untouched.
     * The dive is faster than the orbit (4 versus 1.6 blocks/tick), so cruise-speed range for those ticks would be
     * insufficient. Check the dive alone, excluding approach slack: it consumes more than the planned
     * {@link WeaponSpec.Loiter#diveTicks()} times cruise speed and no more than those ticks times dive speed.
     */
    @GameTest(template = "runway", timeoutTicks = 1600, batch = "loiter_range", skyAccess = true)
    public static void loiterFullCircleWithinRange(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        Vec3 point = airTarget(h);
        LoiterEntity e = ModEntities.LOITER.get().create(level);
        e.launch(point.add(0, WeaponSpec.LOITER.airframe().cruiseHeight(), -150), new Target.Point(point), point, null);
        int longest = (int) (ua.zentix.incoming.IncomingConfig.SERVER.loiterTime.get() * 20 * 1.2);
        CompoundTag tag = e.saveWithoutId(new CompoundTag());
        tag.putInt("loiter_ticks", longest);
        e.load(tag);
        e.setRoute(null);
        level.addFreshEntity(e);
        java.util.UUID id = e.getUUID();
        double reserve = Mission.RESERVE_TICKS * WeaponSpec.LOITER.airframe().cruiseSpeed();
        int[] loiter = {0};
        double[] left = {Double.NaN};
        double[] beforeDive = {Double.NaN};
        Vec3[] lastPos = {null};
        String[] last = {""};
        h.onEachTick(() -> {
            LoiterEntity l = findLoiter(level, id);
            if (l == null) return;
            lastPos[0] = l.position();
            left[0] = l.rangeLeft();
            if (l.flightPhase() != FlightPhase.TERMINAL) beforeDive[0] = l.rangeLeft();
            last[0] = l.flightPhase() + " " + h.relativeVec(l.position()) + " reserve " + Math.round(l.rangeLeft());
            // Counted by the munition's own phase clock, not by test ticks: while it leaves the world or returns, the
            // test does not find it (on CI this is how circle ticks go missing).
            if (l.flightPhase() == FlightPhase.LOITER) loiter[0] = Math.max(loiter[0], l.phaseAge() + 1);
        });
        h.succeedWhen(() -> {
            h.assertTrue(findLoiter(level, id) == null, "loitering munition still flying: " + last[0]);
            h.assertTrue(loiter[0] >= longest, "circled " + loiter[0] + " ticks, but must " + longest);
            h.assertTrue(lastPos[0].distanceTo(point) < 8, "detonation not at the target: " + last[0]);
            h.assertTrue(left[0] >= reserve, "plan did not cover the flight: left for detonation " + Math.round(left[0]) + " blocks out of reserve " + Math.round(reserve));
            WeaponSpec.Airframe air = WeaponSpec.LOITER.airframe();
            double dive = beforeDive[0] - left[0];
            h.assertTrue(dive > WeaponSpec.LOITER.airframe().handling().loiter().diveTicks() * air.cruiseSpeed(),
                    "dive " + Math.round(dive) + " blocks fits in ticks at cruise speed — the test does not tell the dive plan from the circle plan");
            h.assertTrue(dive <= WeaponSpec.LOITER.airframe().handling().loiter().diveTicks() * air.diveSpeed(),
                    "dive " + Math.round(dive) + " blocks more than planned " + Math.round(WeaponSpec.LOITER.airframe().handling().loiter().diveTicks() * air.diveSpeed()));
        });
    }

    /**
     * Target set from the camera during the loiter: dive at once, without waiting for the end of the circle,
     * and detonation at the new target.
     */
    @GameTest(template = "runway", timeoutTicks = 700, batch = "loiter", skyAccess = true)
    public static void loiterStrikesOnRetarget(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        Vec3 point = airTarget(h);
        Vec3 other = point.add(8, 0, -12);
        LoiterEntity e = ModEntities.LOITER.get().create(level);
        e.launch(point.add(0, WeaponSpec.LOITER.airframe().cruiseHeight(), -150), new Target.Point(point), point, null);
        e.setRoute(null);
        level.addFreshEntity(e);
        java.util.UUID id = e.getUUID();
        int[] loiter = {0};
        long[] retargeted = {0}, dived = {0};
        Vec3[] lastPos = {null};
        String[] last = {""};
        h.onEachTick(() -> {
            LoiterEntity l = findLoiter(level, id);
            if (l == null) return;
            lastPos[0] = l.position();
            last[0] = l.flightPhase() + " " + h.relativeVec(l.position()) + " v=" + l.speed();
            if (l.flightPhase() == FlightPhase.LOITER && ++loiter[0] == 60) {
                h.assertTrue(l.retarget(new Target.Point(other), other), "did not accept the target");
                retargeted[0] = level.getGameTime();
            }
            if (l.flightPhase() == FlightPhase.TERMINAL && dived[0] == 0) dived[0] = level.getGameTime();
        });
        h.succeedWhen(() -> {
            h.assertTrue(retargeted[0] > 0 && findLoiter(level, id) == null, "loitering munition still flying: " + last[0]);
            h.assertTrue(dived[0] - retargeted[0] <= 120, "dive not at once: after " + (dived[0] - retargeted[0]) + " ticks");
            h.assertTrue(lastPos[0].distanceTo(other) < 8, "detonation not at the new target: " + last[0]);
        });
    }

    /**
     * Target moves (a mob, a player flying in circles): the loitering munition circles after it and in the dive hits
     * with a lead on its speed. A dive to where the target is now passes by it, and the munition weaves up and down
     * until its lifetime ends.
     */
    @GameTest(template = "runway", timeoutTicks = 900, batch = "loiter_moving", skyAccess = true)
    public static void loiterHitsMovingTarget(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        // High above the barrier wall around the pad: the target's circle reaches the strip's edge (x = 32), and a dive
        // at the edge at the wall's height, as well as the pull-out after a miss (up to 45 blocks below the target) and
        // the re-approach, would hit the barrier (the munition vanishes 12 blocks from the target).
        Vec3 center = airTarget(h).add(0, 100, 0);
        ArmorStand stand = new ArmorStand(level, center.x + 16, center.y, center.z);
        stand.setNoGravity(true);
        level.addFreshEntity(stand);
        LoiterEntity e = ModEntities.LOITER.get().create(level);
        e.launch(center.add(0, WeaponSpec.LOITER.airframe().cruiseHeight(), -150), new Target.OfEntity(stand.getUUID(), Vec3.ZERO), stand.position(), null);
        e.setRoute(null);
        level.addFreshEntity(e);
        java.util.UUID id = e.getUUID();
        int[] loiter = {0}, tick = {0};
        Vec3[] lastPos = {null};
        String[] last = {""};
        h.onEachTick(() -> {
            // circles at radius 16 blocks at 0.5 blocks/tick, like a player flying on the stress bench
            double a = ++tick[0] * 0.5 / 16;
            stand.teleportTo(center.x + 16 * Math.cos(a), center.y, center.z + 16 * Math.sin(a));
            LoiterEntity l = findLoiter(level, id);
            if (l == null) return;
            lastPos[0] = l.position();
            last[0] = l.flightPhase() + " " + h.relativeVec(l.position()) + " to target " + String.format(Locale.ROOT, "%.1f", l.position().distanceTo(stand.position()));
            if (l.flightPhase() == FlightPhase.LOITER && ++loiter[0] == 60) {
                h.assertTrue(l.retarget(new Target.OfEntity(stand.getUUID(), Vec3.ZERO), stand.position()), "did not accept the target");
            }
        });
        h.succeedWhen(() -> {
            h.assertTrue(loiter[0] >= 60, "did not enter the circle over the moving target: " + last[0]);
            h.assertTrue(findLoiter(level, id) == null, "loitering munition still flying: " + last[0]);
            h.assertTrue(lastPos[0].distanceTo(stand.position()) < 10, "detonation not at the target: " + last[0]);
            stand.discard();
        });
    }

    /**
     * Salvo at a target on a cliff edge: the munition's point, with spread, lands on the ground below it, not in the
     * air at the target's height (otherwise the Lancet dives on a point 64 blocks above the ground and weaves until its
     * lifetime ends).
     */
    @GameTest(template = "range", timeoutTicks = 40, batch = "spread_ground", skyAccess = true)
    public static void salvoSpreadAroundEntityLandsOnGround(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        BlockPos base = RANGE_CENTER.below();
        for (int y = 1; y <= 12; y++) h.setBlock(base.above(y), Blocks.STONE);
        Vec3 top = Vec3.atBottomCenterOf(h.absolutePos(base.above(13)));
        ArmorStand stand = new ArmorStand(level, top.x, top.y, top.z);
        level.addFreshEntity(stand);
        Target spread = new Target.OfEntity(stand.getUUID(), new Vec3(0, 1, 0)).offset(new Vec3(12, 0, 0));
        h.runAfterDelay(10, () -> {
            h.assertTrue(stand.onGround(), "armor stand is not standing on the pillar");
            Vec3 p = spread.resolve(level).orElseThrow();
            double ground = Vec3.atBottomCenterOf(h.absolutePos(base.above())).y;
            h.assertTrue(Math.abs(p.y - ground) < 1.5 && Math.abs(p.x - (stand.getX() + 12)) < 1e-6,
                    "salvo point not on the ground: " + h.relativeVec(p) + ", ground at " + (ground - h.absolutePos(BlockPos.ZERO).getY()));
            stand.discard();
            h.succeed();
        });
    }

    /**
     * Map target: the height from the client is only an estimate (here 40 blocks underground), but the Shahed still
     * strikes the surface of this spot as soon as it knows it.
     */
    @GameTest(template = "runway", timeoutTicks = 400, batch = "map_target", skyAccess = true)
    public static void mapTargetHitsSurface(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        DroneEntity drone = ModEntities.DRONE.get().create(level);
        Vec3 start = Vec3.atCenterOf(h.absolutePos(new BlockPos(16, 50, 4)));
        Vec3 guess = top(h, RUNWAY_TARGET).subtract(0, 40, 0);
        Target.Ground ground = new Target.Ground(guess);
        drone.launch(start, ground, ground.surface(level), null);
        level.addFreshEntity(drone);
        h.succeedWhen(() -> {
            h.assertTrue(drone.isRemoved(), "Shahed still flying: " + h.relativeVec(drone.position()));
            assertCrater(h, RUNWAY_TARGET);
        });
    }

    /**
     * A map target takes the ground height in its own column: both at the center and for a salvo munition
     * offset (on a pillar).
     */
    @GameTest(template = "range", timeoutTicks = 20, skyAccess = true)
    public static void groundTargetTakesSurface(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        BlockPos pillar = RANGE_CENTER.offset(12, 0, 0);
        for (int y = 1; y <= 6; y++) h.setBlock(pillar.above(y), Blocks.STONE);
        Target.Ground center = new Target.Ground(Vec3.atCenterOf(h.absolutePos(RANGE_CENTER)).add(0, 50, 0));
        double surface = h.absolutePos(RANGE_CENTER).getY() + 0.5;
        Vec3 c = center.resolve(level).orElseThrow();
        h.assertTrue(Math.abs(c.y - surface) < 1e-6, "center not on the ground: " + h.relativeVec(c));
        Vec3 shot = center.offset(new Vec3(12, 7, 0)).resolve(level).orElseThrow();
        h.assertTrue(Math.abs(shot.y - (surface + 6)) < 1e-6 && Math.abs(shot.x - (c.x + 12)) < 1e-6,
                "salvo offset not on the pillar: " + h.relativeVec(shot));
        h.succeed();
    }

    /**
     * Map target: only the server knows the height. For a ready chunk — the top of the ground; for an unloaded one —
     * the generator's terrain, not the bottom of the world, and the chunk is not loaded.
     */
    @GameTest(template = "range", timeoutTicks = 20, skyAccess = true)
    public static void groundAtFindsHeightWithoutLoading(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        Vec3 here = Vec3.atCenterOf(h.absolutePos(RANGE_CENTER));
        Vec3 near = Target.Ground.at(level, here.x, here.z).pos();
        h.assertTrue(Math.abs(near.y - (h.absolutePos(RANGE_CENTER).getY() + 0.5)) < 1e-6, "ready chunk not at the top of the ground: " + h.relativeVec(near));
        Vec3 far = here.add(4096, 0, 0);
        BlockPos column = BlockPos.containing(far);
        h.assertFalse(Terrain.ready(level, column), "distant area is already loaded");
        Vec3 estimate = Target.Ground.at(level, far.x, far.z).pos();
        int bottom = level.getMinBuildHeight();
        h.assertTrue(estimate.y > bottom + 1 && estimate.y < level.getMaxBuildHeight(), "distant, not the generator's terrain: y " + estimate.y);
        h.assertTrue(estimate.x == far.x && estimate.z == far.z, "the spot moved");
        h.assertFalse(Terrain.ready(level, column), "the estimate loaded the chunk");
        h.succeed();
    }

    /**
     * Map target in an unready chunk — by the console hint, as the client sends it: the height estimate is the top from
     * the client's map, not the generator's terrain (for a city from a save, the generator's is under roofs: 63 instead
     * of 107); a top whose block is outside the world's heights does not count. For a ready chunk — its surface,
     * whatever the map shows.
     */
    @GameTest(template = "range", timeoutTicks = 20, skyAccess = true)
    public static void groundAimPrefersMapHeightOverGenerator(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        Vec3 here = Vec3.atCenterOf(h.absolutePos(RANGE_CENTER));
        int ground = h.absolutePos(RANGE_CENTER).getY();
        Vec3 near = mapAim(level, here, OptionalInt.of(ground + 40));
        h.assertTrue(Math.abs(near.y - (ground + 0.5)) < 1e-6, "ready chunk not its top: " + h.relativeVec(near));
        Vec3 far = here.add(4096, 0, 0);
        BlockPos column = BlockPos.containing(far);
        h.assertFalse(Terrain.ready(level, column), "distant area is already loaded");
        int map = level.getMaxBuildHeight() - 7;
        h.assertTrue(mapAim(level, far, OptionalInt.of(map)).y == map - 0.5, "estimate not from the map");
        // top at the very top of the world — the block below it is still in the world;
        // top at the bottom of the world — there is no block below it
        int top = level.getMaxBuildHeight();
        h.assertTrue(mapAim(level, far, OptionalInt.of(top)).y == top - 0.5, "top at the world ceiling not accepted");
        double generator = mapAim(level, far, OptionalInt.empty()).y;
        h.assertTrue(generator == Target.Ground.at(level, far.x, far.z).pos().y, "without the map, not the generator: y " + generator);
        for (int outside : new int[]{level.getMinBuildHeight(), top + 1}) {
            double y = mapAim(level, far, OptionalInt.of(outside)).y;
            h.assertTrue(y == generator, "top outside the world (" + outside + ") accepted: y " + y + ", generator " + generator);
        }
        h.assertFalse(Terrain.ready(level, column), "the estimate loaded the chunk");
        h.succeed();
    }

    /**
     * One answer to “where is the ground” ({@link Terrain#estimate}) in the real world: for a ready chunk — its height
     * map; for an unready one, an order takes the client's map, without it — the generator (not below sea level),
     * flight outside the world — the generator's sea level, in-world tick code — nothing (the bottom of the world). No
     * answer loads the chunk. In the Nether the height map is the ceiling: in the world the answer is “ceiling”, for
     * flight outside the world — sea.
     */
    @GameTest(template = "range", timeoutTicks = 20, skyAccess = true)
    public static void terrainSourcesAnswerWithoutLoading(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        BlockPos here = h.absolutePos(RANGE_CENTER); // top ground block of the pad
        Heightmap.Types type = Heightmap.Types.MOTION_BLOCKING;
        for (Terrain.Allowed a : new Terrain.Allowed[]{Terrain.Allowed.CHUNK, Terrain.Allowed.FLIGHT, Terrain.Allowed.ORDER}) {
            Terrain.Surface s = Terrain.estimate(level, type, here.getX(), here.getZ(), a);
            h.assertTrue(s.equals(new Terrain.Surface(here.getY() + 1, Terrain.Source.CHUNK)), "ready chunk: " + s);
        }
        int x = here.getX() + 4096, z = here.getZ();
        h.assertFalse(Terrain.ready(level, x >> 4, z >> 4), "distant area is already loaded");
        int sea = level.getChunkSource().getGenerator().getSeaLevel();
        Terrain.Surface generator = Terrain.estimate(level, type, x, z, Terrain.Allowed.ORDER);
        h.assertTrue(generator.source() == Terrain.Source.GENERATOR && generator.y() >= sea, "order without a map: " + generator);
        Terrain.Surface map = Terrain.estimate(level, type, x, z, Terrain.Allowed.ORDER.withMap(Optional.of(sea + 50)));
        h.assertTrue(map.equals(new Terrain.Surface(sea + 50, Terrain.Source.CLIENT_MAP)), "order with a map: " + map);
        Terrain.Surface flight = Terrain.estimate(level, type, x, z, Terrain.Allowed.FLIGHT);
        h.assertTrue(flight.equals(new Terrain.Surface(sea, Terrain.Source.SEA)), "flight outside the world: " + flight);
        Terrain.Surface tick = Terrain.estimate(level, type, x, z, Terrain.Allowed.CHUNK);
        h.assertTrue(tick.equals(new Terrain.Surface(level.getMinBuildHeight(), Terrain.Source.UNKNOWN)), "in world: " + tick);
        h.assertFalse(Terrain.ready(level, x >> 4, z >> 4), "the estimate loaded the chunk");

        ServerLevel nether = level.getServer().getLevel(Level.NETHER);
        h.assertTrue(nether != null, "no Nether");
        int nx = here.getX(), nz = here.getZ();
        nether.getChunk(nx >> 4, nz >> 4);
        h.assertTrue(Terrain.ready(nether, nx >> 4, nz >> 4), "Nether chunk not ready");
        int roof = Terrain.height(nether, type, nx, nz);
        Terrain.Surface ceiling = Terrain.estimate(nether, type, nx, nz, Terrain.Allowed.CHUNK);
        h.assertTrue(ceiling.equals(new Terrain.Surface(roof, Terrain.Source.CEILING)), "Nether in the world: " + ceiling);
        Terrain.Surface netherFlight = Terrain.estimate(nether, type, nx, nz, Terrain.Allowed.FLIGHT);
        h.assertTrue(netherFlight.equals(new Terrain.Surface(nether.getChunkSource().getGenerator().getSeaLevel(), Terrain.Source.SEA)),
                "Nether outside the world: " + netherFlight);
        h.succeed();
    }

    /** Target point from the console hint “map target”, as the server parses it. */
    private static Vec3 mapAim(ServerLevel level, Vec3 at, OptionalInt mapSurface) {
        return ServerActions.groundAim(level, C2S.AimHint.ground(at.x, at.z, mapSurface)).point();
    }

    /**
     * MLRS at a map target: the launch height is an estimate 30 blocks above the ground, and the impact point is spread
     * 40 blocks (more than two chunks) from the spot. As soon as the impact point's chunk is ready, the point settles
     * onto the surface: the rocket hits the ground rather than bursting in the air above it. The target mark (the
     * rocket's aim) is there too, not at the unspread spot: the target's area follows the mark, and at the spot it
     * would have pulled the load away from the impact point — the rocket would wait for it until it was removed.
     */
    @GameTest(template = "runway", timeoutTicks = 800, batch = "rocket_map_target", skyAccess = true)
    public static void rocketMapTargetSettlesOnSurface(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        Vec3 guess = top(h, RUNWAY_TARGET).add(0, 30, 0);
        Vec3 settled = top(h, RUNWAY_TARGET).subtract(0, 0.5, 0);
        RocketEntity r = ModEntities.ROCKET.get().create(level);
        r.launchFrom(guess.add(0, -30, -600), new Target.Ground(guess.add(0, 0, -40)), guess, null);
        VirtualFlights.launch(level, r);
        UUID id = r.getUUID();
        String[] last = {""};
        Vec3[] aim = {null};
        h.onEachTick(() -> {
            StrikeProjectile p = VirtualFlights.get(level).flights().stream().filter(f -> f.getUUID().equals(id)).findFirst()
                    .orElseGet(() -> level.getEntity(id) instanceof StrikeProjectile e && !e.isRemoved() ? e : null);
            if (p == null) return;
            // the aim is synced as float: for GameTest pads millions of blocks away — to within half a block
            aim[0] = p.aimPoint();
            last[0] = (p.isVirtual() ? "outside the world " : "in the world ") + p.flightPhase() + " " + h.relativeVec(p.position());
        });
        h.succeedWhen(() -> {
            h.assertTrue(VirtualFlights.get(level).flights().isEmpty() && level.getEntity(id) == null, "rocket still flying: " + last[0]);
            h.assertTrue(aim[0] != null && aim[0].distanceTo(settled) < 1, "target mark not at the impact point: "
                    + (aim[0] == null ? "none" : h.relativeVec(aim[0])) + ", expected " + h.relativeVec(settled));
            assertCrater(h, RUNWAY_TARGET, last[0]);
        });
    }

    /**
     * Flight outside loaded chunks: the missile starts 1.5 km from the target, where there is no world, flies
     * “virtually” and returns to the world at the target (the target's chunks are loaded by its own ticket), without
     * touching anything unloaded along the way.
     */
    @GameTest(template = "runway", timeoutTicks = 900, batch = "virtual", skyAccess = true)
    public static void missileFliesThroughUnloadedWorld(GameTestHelper h) {
        missileThroughUnloadedWorld(h, 1500);
    }

    /**
     * The same at real speed (flight scale 1: 250 m/s, more than twice the game speed): from a place three times as far
     * — the same number of ticks in flight outside the world, a return to the world at the target and a detonation at
     * the point.
     */
    @GameTest(template = "runway", timeoutTicks = 900, batch = "flight_scale_virtual", skyAccess = true)
    public static void missileFliesThroughUnloadedWorldAtRealSpeed(GameTestHelper h) {
        flightScale(h, 1);
        missileThroughUnloadedWorld(h, 1500 * WeaponSpec.MISSILE.airframe().stretch(1));
    }

    /**
     * Missile {@code distance} blocks from the target, outside the world, in a straight line: it returns to the world
     * at the target and strikes the point.
     */
    private static void missileThroughUnloadedWorld(GameTestHelper h, double distance) {
        ServerLevel level = h.getLevel();
        Vec3 point = top(h, RUNWAY_TARGET);
        CruiseMissileEntity missile = ModEntities.CRUISE_MISSILE.get().create(level);
        // above the barrier wall around the test pad: otherwise the missile hits it on entry
        missile.launch(point.add(0, 80, -distance), new Target.Point(point), point, null);
        missile.setRoute(Route.direct());
        VirtualFlights.launch(level, missile);
        java.util.UUID id = missile.getUUID();
        boolean[] seen = new boolean[1];
        String[] last = {""};
        h.onEachTick(() -> {
            if (level.getEntity(id) instanceof CruiseMissileEntity m && !m.isVirtual()) {
                seen[0] = true;
                last[0] = "in the world " + m.flightPhase() + " " + h.relativeVec(m.position()) + " v=" + m.speed();
            }
            for (StrikeProjectile p : VirtualFlights.get(level).flights()) {
                if (p.getUUID().equals(id)) last[0] = "outside the world " + p.flightPhase() + " " + h.relativeVec(p.position()) + " v=" + p.speed();
            }
        });
        h.succeedWhen(() -> {
            h.assertTrue(VirtualFlights.get(level).flights().isEmpty(), "missile still outside the world");
            h.assertTrue(seen[0], "missile did not return to the world");
            h.assertTrue(level.getEntity(id) == null, "missile still flying");
            assertCrater(h, RUNWAY_TARGET, last[0]);
        });
    }

    /**
     * “/tick freeze”: a missile outside the world stands still, as entities in the world do, and flies on after thawing
     * (otherwise projectiles outside the world would run ahead of the frozen world — the trailer freezes the world
     * while the camera waits for chunks to load). The frozen world also stops the test itself (its clock is world
     * time), so the server thaws it after one second: in that second the server makes ~20 ticks with the frozen world.
     */
    @GameTest(template = "runway", timeoutTicks = 900, batch = "frozen", skyAccess = true)
    public static void virtualFlightStopsWhileFrozen(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        Vec3 point = top(h, RUNWAY_TARGET);
        CruiseMissileEntity missile = ModEntities.CRUISE_MISSILE.get().create(level);
        missile.launch(point.add(0, 80, -1500), new Target.Point(point), point, null);
        missile.setRoute(Route.direct());
        VirtualFlights.launch(level, missile);
        java.util.UUID id = missile.getUUID();
        java.util.function.Supplier<Vec3> where = () -> VirtualFlights.get(level).flights().stream()
                .filter(p -> p.getUUID().equals(id)).map(StrikeProjectile::position).findFirst().orElse(null);
        var server = level.getServer();
        int[] tick = {0};
        boolean[] held = {false}, thawed = {false};
        h.onEachTick(() -> {
            if (++tick[0] != 20) return;
            Vec3 frozenAt = where.get();
            level.tickRateManager().setFrozen(true);
            java.util.concurrent.CompletableFuture.delayedExecutor(1, java.util.concurrent.TimeUnit.SECONDS).execute(() -> server.execute(() -> {
                Vec3 now = where.get();
                held[0] = frozenAt != null && now != null && now.distanceTo(frozenAt) < 1e-6;
                level.tickRateManager().setFrozen(false);
                thawed[0] = true;
            }));
        });
        h.succeedWhen(() -> {
            h.assertTrue(thawed[0], "still frozen");
            h.assertTrue(held[0], "missile outside the world moved while the world was frozen");
            h.assertTrue(VirtualFlights.get(level).flights().isEmpty() && level.getEntity(id) == null, "missile still flying");
            assertCrater(h, RUNWAY_TARGET, "after thawing");
        });
    }

    /**
     * A moving target outside the loaded world: the missile takes the target's area while the target is at the start of
     * the strip, and the target moves 160 blocks — the area must move after it, or the missile waits at the target for
     * loading and vanishes when its lifetime ends.
     */
    @GameTest(template = "runway", timeoutTicks = 900, batch = "virtual_moving", skyAccess = true)
    public static void missileFollowsMovingTargetArea(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        Vec3 end = top(h, RUNWAY_TARGET);
        Vec3 start = end.add(0, 0, -160);
        ArmorStand stand = EntityType.ARMOR_STAND.create(level);
        stand.setNoGravity(true);
        stand.moveTo(start.x, start.y, start.z);
        level.addFreshEntity(stand);
        CruiseMissileEntity missile = ModEntities.CRUISE_MISSILE.get().create(level);
        missile.launch(end.add(0, 80, -1500), Target.OfEntity.center(stand), start, null);
        missile.setRoute(Route.direct());
        VirtualFlights.launch(level, missile);
        java.util.UUID id = missile.getUUID();
        boolean[] moved = new boolean[1];
        String[] last = {""};
        h.onEachTick(() -> {
            for (StrikeProjectile p : VirtualFlights.get(level).flights()) {
                if (!p.getUUID().equals(id)) continue;
                last[0] = "outside the world " + p.flightPhase() + " " + h.relativeVec(p.position());
                // the missile has already taken the area at the start of the strip — the target moves toward the end
                if (!moved[0] && p.position().distanceTo(start) < 700) {
                    moved[0] = true;
                    stand.teleportTo(end.x, end.y, end.z);
                }
            }
            if (level.getEntity(id) instanceof CruiseMissileEntity m && !m.isVirtual()) {
                last[0] = "in the world " + m.flightPhase() + " " + h.relativeVec(m.position());
            }
        });
        h.succeedWhen(() -> {
            h.assertTrue(moved[0], "target did not move");
            h.assertTrue(VirtualFlights.get(level).flights().isEmpty() && level.getEntity(id) == null, "missile still flying: " + last[0]);
            assertCrater(h, RUNWAY_TARGET, last[0]);
        });
    }

    /**
     * A blast near unready chunks does not read them in a tick (the server would stall for more than a second): it
     * waits until the area is ready, and fires when everything within its reach is ready. Background generation 4 km
     * away runs as long as it needs against GameTest's server ticks (it ticks without pauses), so the test itself loads
     * the area here — synchronously, after the tick in which the blast was already waiting: the test's timeout depends
     * only on ticks, not on the machine's speed.
     */
    @GameTest(template = "range", timeoutTicks = 100, batch = "deferred_blast")
    public static void blastWaitsForUnreadyChunks(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        Vec3 far = Vec3.atCenterOf(h.absolutePos(RANGE_CENTER)).add(4096, 0, 0);
        double reach = Warheads.reach(4);
        h.assertFalse(Terrain.readyAround(level, far, reach), "distant area is already loaded");
        boolean[] ran = new boolean[1];
        boolean[] readyWhenRan = new boolean[1];
        Warheads.whenReady(level, far, reach, l -> {
            ran[0] = true;
            readyWhenRan[0] = Terrain.readyAround(l, far, reach);
        });
        h.assertFalse(ran[0], "blast fired at once, without waiting for the area");
        h.runAfterDelay(2, () -> {
            // the blast was already ticking in the queue: if the area has not loaded itself by then,
            // the blast must not have happened
            h.assertTrue(!ran[0] || readyWhenRan[0], "blast fired before the area was ready");
            for (int cx = Mth.floor(far.x - reach) >> 4; cx <= Mth.floor(far.x + reach) >> 4; cx++) {
                for (int cz = Mth.floor(far.z - reach) >> 4; cz <= Mth.floor(far.z + reach) >> 4; cz++) level.getChunk(cx, cz);
            }
            h.assertTrue(Terrain.readyAround(level, far, reach), "the area did not become ready after loading");
        });
        h.succeedWhen(() -> {
            h.assertTrue(ran[0], "blast is still waiting for the area");
            h.assertTrue(readyWhenRan[0], "blast fired before the area was ready");
        });
    }

    /**
     * The stress bench credits a blast to its projectile by the damage source ({@code StressDirector.blastBy}), not by
     * place: a warhead near an unready area waits for its loading, and a deferred blast — already after the
     * projectile's cleanup — still carries the same projectile. The test loads the area itself after the tick in which
     * the blast was already waiting (as {@link #blastWaitsForUnreadyChunks}).
     */
    @GameTest(template = "runway", timeoutTicks = 100, batch = "deferred_blast_owner", skyAccess = true)
    public static void deferredBlastKeepsItsProjectile(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        Vec3 at = Vec3.atCenterOf(FarSite.DEFERRED_BLAST.at(h));
        // strike area — up to ~23 blocks from the point (wave rays, Warheads.chargeReach): with margin
        double area = 32;
        h.assertFalse(Terrain.readyAround(level, at, area), "distant area is already loaded");
        RocketEntity rocket = ModEntities.ROCKET.get().create(level);
        int[] tick = {0};
        List<String> foreign = new ArrayList<>();
        int[] own = {0};
        int[] firstAt = {-1};
        java.util.function.Consumer<net.neoforged.neoforge.event.level.ExplosionEvent.Start> onBlast = e -> {
            Vec3 c = e.getExplosion().center();
            if (e.getLevel() != level || c.distanceTo(at) > area) return;
            UUID by = ua.zentix.incoming.stress.StressDirector.blastBy(e.getExplosion());
            if (!rocket.getUUID().equals(by)) foreign.add(by + " at " + c.subtract(at));
            else if (own[0]++ == 0) firstAt[0] = tick[0];
        };
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(onBlast);
        afterTest(h, () -> net.neoforged.neoforge.common.NeoForge.EVENT_BUS.unregister(onBlast));
        h.onEachTick(() -> tick[0]++);
        Warheads.detonate(level, WeaponType.ROCKET, at, rocket, null);
        h.assertTrue(own[0] == 0, "blast fired at once, without waiting for the area");
        h.runAfterDelay(2, () -> {
            for (int cx = Mth.floor(at.x - area) >> 4; cx <= Mth.floor(at.x + area) >> 4; cx++)
                for (int cz = Mth.floor(at.z - area) >> 4; cz <= Mth.floor(at.z + area) >> 4; cz++) level.getChunk(cx, cz);
        });
        h.succeedWhen(() -> {
            h.assertTrue(foreign.isEmpty(), "blast without a projectile or with a foreign one: " + foreign);
            h.assertTrue(own[0] == 1, "projectile blasts " + own[0] + " (expecting one charge detonation)");
            h.assertTrue(firstAt[0] > 0, "the projectile's first blast at tick " + firstAt[0] + " — not deferred");
        });
    }

    /**
     * Verdict of the stress bench probes (stretch and to-surface): a mod refusal by the wait deadline is “not checked”;
     * a failed check matters more.
     */
    @GameTest(template = "range", batch = "stress_verdict")
    public static void stressProbeVerdict(GameTestHelper h) {
        h.assertTrue(ua.zentix.incoming.stress.StressDirector.probeVerdict(List.of(), 0).equals("ok"), "no refusals — ok");
        h.assertTrue(ua.zentix.incoming.stress.StressDirector.probeVerdict(List.of(), 3).startsWith("not checked"), "refusals — not checked");
        h.assertTrue(ua.zentix.incoming.stress.StressDirector.probeVerdict(List.of("x"), 3).startsWith("failed"), "a failed check outweighs refusals");
        h.succeed();
    }

    /**
     * A bomb in the world falls where the B-2 simulates its fall before the drop ({@link BombDrop#miss}): both on the
     * point (from an altitude, 145 blocks out) and off it — closer than the near edge of the window, where a bomb at
     * the B-2's speed has no time to lower its nose (from 140 at 126, from 170 at 118, from 200 at 110, from 100 at
     * 60).
     */
    @GameTest(template = "runway", timeoutTicks = 300, batch = "bunker_predict", skyAccess = true)
    public static void bunkerBusterFallsWhereBombDropPredicts(GameTestHelper h) {
        double[][] drops = {{170, 145}, {140, 126}, {170, 118}, {200, 110}, {100, 60}};
        Vec3[] releases = new Vec3[drops.length];
        Vec3[] aims = new Vec3[drops.length];
        double[] predicted = new double[drops.length];
        // Ground entry points are at least 24 blocks apart: a bomb that entered the ground first detonates underground
        // after ~10 ticks, and a crater at a neighboring spot (6 blocks away) would count a hit for its neighbor; a
        // miss goes ahead along the heading (+z) and stays on the pad, the drop is over it (z ≥ 0).
        int[][] at = {{4, 150}, {28, 150}, {4, 180}, {28, 170}, {4, 160}};
        for (int i = 0; i < drops.length; i++) {
            aims[i] = dropPoint(h, new BlockPos(at[i][0], 3, at[i][1]));
            releases[i] = aims[i].add(0, drops[i][0], -drops[i][1]);
            predicted[i] = BombDrop.miss(releases[i], BomberEntity.levelVelocity(0), aims[i]);
        }
        h.assertTrue(predicted[0] == 0 && Arrays.stream(predicted, 1, drops.length).allMatch(m -> m > 10), "predicted: " + Arrays.toString(predicted));
        bombsEnter(h, releases, aims, predicted);
    }

    /**
     * Where the B-2 drops the bomb ({@link BombDrop#releaseNow}) from different heights above the point and from
     * different places on the approach, the bomb in the world arrives at the point: below ~170 blocks — from the edge
     * of the window, above — from the threshold for its height.
     */
    @GameTest(template = "runway", timeoutTicks = 300, batch = "bunker_release_points", skyAccess = true)
    public static void bomberReleasePointsHitInWorld(GameTestHelper h) {
        double[] heights = {60, 100, 140, 170, 214, 250};
        // points at least 24 blocks apart (see above: the neighbor's crater), the drop is over the strip (z ≥ 0)
        int[][] at = {{16, 165}, {28, 190}, {4, 190}, {16, 215}, {28, 240}, {4, 240}};
        Vec3[] releases = new Vec3[heights.length];
        Vec3[] aims = new Vec3[heights.length];
        for (int i = 0; i < heights.length; i++) {
            aims[i] = dropPoint(h, new BlockPos(at[i][0], 3, at[i][1]));
            Vec3 step = new Vec3(0, 0, WeaponSpec.BUNKER.airframe().cruiseSpeed());
            // approach in a straight line on heading +z; the start offset is to hit different places in the window
            for (Vec3 pos = aims[i].add(0, heights[i], -600 - 2 * i); pos.z < aims[i].z; pos = pos.add(step)) {
                if (BombDrop.releaseNow(pos, step, aims[i], WeaponSpec.BUNKER.route().gameFinalLeg() / WeaponSpec.BUNKER.airframe().gameHeight())) {
                    releases[i] = pos;
                    break;
                }
            }
            h.assertTrue(releases[i] != null, "from " + (int) heights[i] + " did not drop");
        }
        bombsEnter(h, releases, aims, new double[heights.length]);
    }

    /** Where the B-2 aims the bomb above a block: the middle of the top block ({@code BomberEntity.surfaceUnder}). */
    private static Vec3 dropPoint(GameTestHelper h, BlockPos block) {
        return top(h, block).add(0, -BombDrop.GROUND_ABOVE_AIM, 0);
    }

    /**
     * Bombs dropped from {@code releases} at the points {@code aims} enter the ground
     * {@code expected} blocks from them (±2).
     */
    private static void bombsEnter(GameTestHelper h, Vec3[] releases, Vec3[] aims, double[] expected) {
        ServerLevel level = h.getLevel();
        BunkerBusterEntity[] bombs = new BunkerBusterEntity[releases.length];
        for (int i = 0; i < releases.length; i++) {
            bombs[i] = ModEntities.BUNKER_BUSTER.get().create(level);
            bombs[i].drop(releases[i].add(0, -BombDrop.DROP_BELOW, 0), BomberEntity.levelVelocity(0), aims[i], null, null);
            level.addFreshEntity(bombs[i]);
        }
        Vec3[] entries = new Vec3[releases.length];
        h.onEachTick(() -> {
            for (int i = 0; i < releases.length; i++) if (entries[i] == null && bombs[i].isDrilling()) entries[i] = bombs[i].entry();
        });
        h.succeedWhen(() -> {
            for (int i = 0; i < releases.length; i++) {
                String from = "bomb from " + Math.round(releases[i].y - aims[i].y) + " at "
                        + String.format(Locale.ROOT, "%.1f", releases[i].subtract(aims[i]).horizontalDistance());
                h.assertTrue(entries[i] != null, from + " has not entered the ground yet");
                double miss = entries[i].subtract(aims[i]).horizontalDistance();
                h.assertTrue(Math.abs(miss - expected[i]) < 2, from + " entered the ground at " + String.format(Locale.ROOT, "%.1f", miss)
                        + " blocks from the point, expected " + String.format(Locale.ROOT, "%.1f", expected[i]));
            }
        });
    }

    /**
     * A stress-bench teleport whose player is not in the game (left by the scenario while the teleport waited for the
     * area) waits for the player's entry and holds the area, rather than skipping the step; the summary releases the
     * area's ticket that never got its player (the client crashed), and there are no new teleports after it.
     */
    @GameTest(template = "range", timeoutTicks = 100, batch = "stress_teleport")
    public static void stressTeleportWaitsForOfflinePlayer(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        var director = new ua.zentix.incoming.stress.StressDirector(null);
        BlockPos at = h.absolutePos(new BlockPos(32, 2, 32));
        director.begin(level.getServer(), "Nobody", level, at.getX(), at.getY(), at.getZ(), 0);
        h.assertTrue(director.teleportTicketCount() == 1, "area ticket not placed");
        // the area is ready at once (the teleport's ticket holds it): otherwise the teleport would wait
        // for the area, not the player
        int area = ua.zentix.incoming.stress.StressDirector.teleportArea(level.getServer());
        ChunkPos centre = new ChunkPos(at);
        for (int dx = -area; dx <= area; dx++)
            for (int dz = -area; dz <= area; dz++) level.getChunk(centre.x + dx, centre.z + dz);
        for (int dx = -area; dx <= area; dx++)
            for (int dz = -area; dz <= area; dz++)
                h.assertTrue(ua.zentix.incoming.util.Terrain.ready(level, centre.x + dx, centre.z + dz), "area chunk not ready");
        h.onEachTick(director::runWaits);
        h.runAfterDelay(40, () -> {
            int released;
            try {
                h.assertTrue(director.teleportTicketCount() == 1, "area released without the player");
                h.assertTrue(director.problemList().stream().noneMatch(p -> p.contains("step skipped")), "step skipped: " + director.problemList());
            } finally {
                released = director.closeTeleports();
            }
            h.assertTrue(released == 1, "summary released area tickets " + released);
            h.assertTrue(director.teleportTicketCount() == 0, "area ticket remained after the summary");
            director.begin(level.getServer(), "Nobody", level, at.getX(), at.getY(), at.getZ(), 40);
            h.assertTrue(director.teleportTicketCount() == 0, "teleport started after the summary");
            h.succeed();
        });
    }

    @GameTest(template = "runway", timeoutTicks = 300, batch = "bunker", skyAccess = true)
    public static void bunkerBusterDrillsAndDetonatesUnderground(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        BunkerBusterEntity bomb = ModEntities.BUNKER_BUSTER.get().create(level);
        // as from a standard B-2: a drop 170 blocks above the target, 145 blocks before it, at the B-2's speed
        Vec3 surface = top(h, RUNWAY_TARGET);
        bomb.drop(surface.add(0, 170, -145), BomberEntity.levelVelocity(0), surface, null, null);
        level.addFreshEntity(bomb);
        h.succeedWhen(() -> {
            h.assertTrue(bomb.isRemoved(), "bomb has not detonated yet");
            h.assertFalse(h.getBlockState(RUNWAY_TARGET).is(Blocks.GRASS_BLOCK), "no entry hole");
            h.assertFalse(h.getBlockState(RUNWAY_TARGET.below()).is(Blocks.DIRT), "bomb did not breach the ground");
        });
    }

    /**
     * Bomb detonation in rock under sand (stone to y = 14, above it to y = 22 sand with gravel layers, the charge at
     * depth 14, under it a water layer that the blast opens at the bottom of the cavity): the sand over the cavity, the
     * bomb's rubble and the collapse pipe slump in hundreds of blocks. When the detonation is over, the stone shelf
     * under the sand stack in the blast area is removed (the slumping is already without blasts; the shelf is brick — a
     * rock vault in the fracture zone collapses by itself). Live falling blocks in the area are no more than {@link
     * CraterFalls#LIVE_CAP} (for B-2 craters without a limit they are 151 or more each); beyond them the blocks landed
     * at once. None disappeared (sand and gravel — as blocks, falling ones and items — at the end are as many as before
     * the shelf collapsed), and when the fall is over, no loose block hangs over a void or water. Without {@link
     * CraterFalls}, falling blocks at once: 1384.
     */
    @GameTest(template = "range", timeoutTicks = 400, batch = "crater_falls", skyAccess = true)
    public static void craterFallsStayUnderCap(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        // only chunks whose neighbors are also on the pad (x and z from 16 to 47): they need not wait for background
        // loading
        BlockPos lo = new BlockPos(16, 1, 16), hi = new BlockPos(47, 22, 47);
        sandBed(h, lo, hi, 15);
        for (BlockPos p : BlockPos.betweenClosed(new BlockPos(24, 3, 24), new BlockPos(40, 3, 40))) {
            level.setBlock(h.absolutePos(p), Blocks.WATER.defaultBlockState(), Block.UPDATE_CLIENTS);
        }
        // stack 6×6×8 on a stone shelf above the ground, in a corner of the pad — farther than the blast reaches in
        // rock
        BlockPos shelfLo = new BlockPos(17, 26, 42), shelfHi = new BlockPos(22, 26, 47);
        // shelf is masonry, not rock: a rock vault over a void in the fracture zone of an underground blast collapses
        // by itself
        for (BlockPos p : BlockPos.betweenClosed(shelfLo, shelfHi)) {
            level.setBlock(h.absolutePos(p), Blocks.STONE_BRICKS.defaultBlockState(), Block.UPDATE_CLIENTS);
        }
        for (BlockPos p : BlockPos.betweenClosed(shelfLo.above(), shelfHi.above(8))) {
            level.setBlock(h.absolutePos(p), Blocks.SAND.defaultBlockState(), Block.UPDATE_CLIENTS);
        }
        BlockPos top = new BlockPos(hi.getX(), shelfHi.getY() + 8, hi.getZ());
        AABB box = craterBox(h, lo, top);
        Vec3 charge = Vec3.atCenterOf(h.absolutePos(new BlockPos(32, 8, 32)));
        // blast area (reach of force 20 — 41 blocks) — at once: otherwise the detonation would wait for background
        // generation beyond the pad's edge
        generateNow(level, new ChunkPos(BlockPos.containing(charge)), 4);
        // No fire in the cavity: an item that falls into fire burns (a stack of 16 at once), and the count would catch
        // the fire rather than the slumping
        boolean fire = ua.zentix.incoming.IncomingConfig.SERVER.fire.get();
        ua.zentix.incoming.IncomingConfig.SERVER.fire.set(false);
        afterTest(h, () -> ua.zentix.incoming.IncomingConfig.SERVER.fire.set(fire));
        long settledBefore = CraterFalls.get(level).settled();
        Warheads.bunker(level, charge, charge.add(0, 15, 0), null, null);
        AABB column = new AABB(box.minX, level.getMinBuildHeight(), box.minZ, box.maxX, level.getMaxBuildHeight(), box.maxZ);
        int[] loose = {-1};
        long[] settledShelf = {-1};
        h.onEachTick(() -> {
            int live = level.getEntitiesOfClass(FallingBlockEntity.class, box).size();
            h.assertTrue(live <= CraterFalls.LIVE_CAP, "live falling blocks " + live + " more than the limit " + CraterFalls.LIVE_CAP);
            // The detonation and all its work (cavity, crushing, fractures, collapse pipe — in units) ended, and the
            // ejecta debris landed: they land as a block of ground — sand, and the tallest fly for more than 120 ticks.
            // Then the count, then the shelf, later. Sand and gravel only slump; the area is still open (until the end
            // of the detonation + GRACE) and nothing is falling: a falling block whose place the slumping has already
            // taken would be counted twice.
            if (loose[0] >= 0 || h.getTick() < 120 || !StrikeWorld.get(level).impacts().isEmpty()
                    || !level.getEntitiesOfClass(DebrisEntity.class, column).isEmpty()
                    || !level.getEntitiesOfClass(FallingBlockEntity.class, box).isEmpty()) return;
            loose[0] = looseCount(level, box);
            settledShelf[0] = CraterFalls.get(level).settled();
            for (BlockPos p : BlockPos.betweenClosed(shelfLo, shelfHi)) level.setBlock(h.absolutePos(p), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        });
        h.succeedWhen(() -> {
            h.assertTrue(loose[0] >= 0, "detonation still in progress");
            h.assertTrue(CraterFalls.get(level).settled() > settledShelf[0], "no block of the stack fell from the shelf at once: conservation not checked");
            h.assertTrue(CraterFalls.get(level).settled() > settledBefore, "no block landed at once: slumping did not exceed the limit, nothing to check");
            h.assertTrue(level.getEntitiesOfClass(FallingBlockEntity.class, box).isEmpty(), "blocks still falling");
            assertSettled(h, lo, top);
            int now = looseCount(level, box);
            h.assertTrue(now == loose[0], "sand and gravel were " + loose[0] + ", became " + now + ": blocks disappeared or doubled");
        });
    }

    /**
     * Three detonations in sand on the strip, 88 blocks apart (the blast areas do not overlap) and in one tick, like a
     * salvo with a large spread: live falling blocks across all of them — no more than {@link CraterFalls#TOTAL_CAP},
     * although each area would let out its own {@link CraterFalls#LIVE_CAP}, and more than one area limit at once
     * (falling at several craters).
     */
    @GameTest(template = "runway", timeoutTicks = 400, batch = "crater_falls_salvo", skyAccess = true)
    public static void craterFallsShareWorldCap(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        int[] centres = {40, 128, 216};
        List<Vec3> charges = new ArrayList<>();
        for (int z : centres) {
            // Charge under the sand deeper than the breakthrough (scaled depth ~1.3): the cavity reaches the sand,
            // which pours into it until the cavity is filled — at three craters at once.
            BlockPos lo = new BlockPos(4, 1, z - 12), hi = new BlockPos(27, 27, z + 12);
            sandBed(h, lo, hi, 13);
            Vec3 charge = Vec3.atCenterOf(h.absolutePos(new BlockPos(16, 10, z)));
            generateNow(level, new ChunkPos(BlockPos.containing(charge)), 4);
            charges.add(charge);
        }
        // The impact-strip clock is a counting one: the work of the three detonations (cavities, fractures, collapse
        // pipes) is split by ticks the same way on any machine; on a busy machine by wall-clock time the craters
        // slumped in turn, not at once.
        net.minecraft.server.MinecraftServer server = level.getServer();
        ua.zentix.incoming.work.WorkScheduler.useImpactClock(server, ua.zentix.incoming.nuclear.world.WorkClock.counting(1_000_000L));
        afterTest(h, () -> ua.zentix.incoming.work.WorkScheduler.useImpactClock(server, ua.zentix.incoming.work.WorkScheduler.newImpactClock()));
        for (Vec3 c : charges) Warheads.bunker(level, c, c.add(0, 14, 0), null, null);
        // Only its own strip: the pads of neighboring batches are 5 blocks away, and their falling blocks (after a
        // batch, the neighbors' chunks do not tick — a block hangs) are not about this check
        AABB box = new AABB(Vec3.atLowerCornerOf(h.absolutePos(new BlockPos(0, 1, 0))),
                Vec3.atLowerCornerOf(h.absolutePos(new BlockPos(32, 28, 256)))).inflate(0, 16, 0);
        long settledBefore = CraterFalls.get(level).settled();
        int[] max = {0};
        h.onEachTick(() -> {
            int live = level.getEntitiesOfClass(FallingBlockEntity.class, box).size();
            max[0] = Math.max(max[0], live);
            h.assertTrue(live <= CraterFalls.TOTAL_CAP, "live falling blocks " + live + " more than the total limit " + CraterFalls.TOTAL_CAP);
        });
        h.succeedWhen(() -> {
            h.assertTrue(CraterFalls.get(level).settled() > settledBefore, "no block landed at once: nothing to check");
            h.assertTrue(max[0] > CraterFalls.LIVE_CAP, "live at once there were no more than " + max[0] + ": they fell at one crater, the total limit was not checked");
            List<FallingBlockEntity> falling = level.getEntitiesOfClass(FallingBlockEntity.class, box);
            if (falling.isEmpty()) return;
            // where they hang: the place and where it started to fall (from the start of the pad), ticks in flight,
            // whether the chunk ticks entities
            StringBuilder where = new StringBuilder();
            for (FallingBlockEntity f : falling.subList(0, Math.min(5, falling.size()))) {
                BlockPos origin = h.absolutePos(BlockPos.ZERO);
                where.append(String.format(java.util.Locale.ROOT, " %s from %s, %d ticks, ticking %b;", f.blockPosition().subtract(origin).toShortString(),
                        f.getStartPos().subtract(origin).toShortString(), f.time, level.isPositionEntityTicking(f.blockPosition())));
            }
            h.fail("blocks still falling: " + falling.size() + ";" + where);
        });
    }

    /**
     * A fall in a crater area where the chunk ticks blocks but not entities (level 32 — next to the pad's ticking
     * chunks) lands at once: the block tick has started it, but the entity does not tick there and would hang in the
     * air (CI 22.10: gravel of a vault under a neighboring pad, brought down by the bomb's fracture zone at the edge of
     * the area, hung until the end of {@link #craterFallsShareWorldCap} — where the blast area had not yet grown to the
     * edge, and was then released).
     */
    @GameTest(template = "range", timeoutTicks = 100, batch = "crater_falls_edge", skyAccess = true)
    public static void craterFallWithoutEntityTicksLandsAtOnce(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        // column beyond the pad's edge toward +X (there are no pads there yet): the first whose chunk ticks blocks but
        // not entities
        BlockPos column = null;
        for (int x = 64; x < 96 && column == null; x++) {
            BlockPos p = h.absolutePos(new BlockPos(x, 0, 32));
            if (level.shouldTickBlocksAt(p) && !level.isPositionEntityTicking(p)) column = p;
        }
        h.assertTrue(column != null, "no chunk beyond the pad ticks blocks without entities");
        int top = level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING, column.getX(), column.getZ());
        BlockPos rest = new BlockPos(column.getX(), top, column.getZ()), from = rest.above(3);
        // blast area above the column: a small charge high in the air (the area is a couple of blocks, it does not give
        // entities a tick)
        Warheads.testCharge(level, Vec3.atCenterOf(from).add(0, 20, 0), 0.05);
        long settledBefore = CraterFalls.get(level).settled();
        level.setBlock(from, Blocks.GRAVEL.defaultBlockState(), Block.UPDATE_ALL);
        afterTest(h, () -> {
            level.setBlock(from, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
            level.setBlock(rest, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
        });
        AABB around = new AABB(rest).inflate(1, 6, 1);
        h.succeedWhen(() -> {
            List<FallingBlockEntity> hanging = level.getEntitiesOfClass(FallingBlockEntity.class, around);
            h.assertTrue(hanging.isEmpty(), "falling gravel hangs: " + hanging.stream().map(f -> f.position() + " " + f.time + " ticks").toList());
            h.assertTrue(level.getBlockState(rest).is(Blocks.GRAVEL) && level.getBlockState(from).isAir(), "gravel did not land on the support: "
                    + level.getBlockState(from) + " above " + level.getBlockState(rest));
            h.assertTrue(CraterFalls.get(level).settled() > settledBefore, "gravel landed not through the crater's slumping");
        });
    }

    /**
     * Ground {@code lo}…{@code hi}: stone below {@code sandFrom}, above — sand with a gravel layer every three layers.
     */
    private static void sandBed(GameTestHelper h, BlockPos lo, BlockPos hi, int sandFrom) {
        for (BlockPos p : BlockPos.betweenClosed(lo, hi)) {
            BlockState s = p.getY() < sandFrom ? Blocks.STONE.defaultBlockState()
                    : p.getY() % 4 == 0 ? Blocks.GRAVEL.defaultBlockState() : Blocks.SAND.defaultBlockState();
            h.getLevel().setBlock(h.absolutePos(p), s, Block.UPDATE_CLIENTS);
        }
    }

    /** Ground box {@code lo}…{@code hi} with a margin: slumping, items and debris do not leave it. */
    private static AABB craterBox(GameTestHelper h, BlockPos lo, BlockPos hi) {
        return new AABB(Vec3.atLowerCornerOf(h.absolutePos(lo)), Vec3.atLowerCornerOf(h.absolutePos(hi.offset(1, 1, 1)))).inflate(8, 16, 8);
    }

    /** Sand and gravel in the box: blocks, falling blocks and items (items — over the full world height above the box). */
    private static int looseCount(ServerLevel level, AABB box) {
        int n = 0;
        for (BlockPos p : BlockPos.betweenClosed(BlockPos.containing(box.minX, box.minY, box.minZ), BlockPos.containing(box.maxX - 1, box.maxY - 1, box.maxZ - 1))) {
            if (loose(level.getBlockState(p))) n++;
        }
        for (FallingBlockEntity e : level.getEntitiesOfClass(FallingBlockEntity.class, box)) {
            if (loose(e.getBlockState())) n++;
        }
        AABB column = new AABB(box.minX, level.getMinBuildHeight(), box.minZ, box.maxX, level.getMaxBuildHeight(), box.maxZ);
        for (ItemEntity e : level.getEntitiesOfClass(ItemEntity.class, column)) {
            if (e.getItem().is(Items.SAND) || e.getItem().is(Items.GRAVEL)) n += e.getItem().getCount();
        }
        return n;
    }

    private static boolean loose(BlockState s) {
        return s.is(Blocks.SAND) || s.is(Blocks.GRAVEL);
    }

    /** No loose block in {@code lo}…{@code hi} hangs over a void, fire, water or grass. */
    private static void assertSettled(GameTestHelper h, BlockPos lo, BlockPos hi) {
        for (BlockPos p : BlockPos.betweenClosed(lo.offset(0, 1, 0), hi)) {
            BlockState s = h.getBlockState(p);
            h.assertFalse(s.getBlock() instanceof FallingBlock && FallingBlock.isFree(h.getBlockState(p.below())),
                    "loose block hangs over a void: " + s + " at " + p);
        }
    }

    /**
     * B-2 whose drop point ended up inside its turn circle (retargeted from the side, 200 blocks from it) goes
     * straight, comes back in and drops the bomb, rather than circling at the limit of its turn around a point 300–480
     * blocks away until “Abort”.
     */
    @GameTest(template = "runway", timeoutTicks = 2000, batch = "bomber_reattack", skyAccess = true)
    public static void bomberReattacksAimInsideItsTurn(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        Vec3 start = top(h, RUNWAY_TARGET);
        // heading +z, far ahead; at once a retarget 150 blocks to the side and 60 ahead — inside the turn circle
        Vec3 aside = start.add(150, 0, 60);
        // Point beyond the pad's edge: its area — at once, otherwise the bomb would wait for background generation, and
        // the GameTest server ticks without pauses, and on CI the test's time would run out first (the bomb is dropped
        // but would not enter the ground within 2000 ticks).
        generateNow(level, new ChunkPos(BlockPos.containing(aside)), FlightTickets.DISTANCE);
        // the bomb enters the ground under the point — wait for it there, not at the pad's height
        Vec3 ground = new Vec3(aside.x, level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, Mth.floor(aside.x), Mth.floor(aside.z)), aside.z);
        bomberEntersNear(h, start, start.add(0, 0, 3000), aside, ground);
    }

    /**
     * B-2 retargeted to a point behind it, closer than the release distance, does not drop the bomb backward: a bomb
     * dropped so falls steeply down its heading (it does not level out or steer when the target is behind) and goes
     * into the ground ~150 blocks from the point. It leaves, comes back in, and the bomb hits.
     */
    @GameTest(template = "runway", timeoutTicks = 1500, batch = "bomber_behind", skyAccess = true)
    public static void bomberRetargetedJustBehindHitsOnReattack(GameTestHelper h) {
        Vec3 start = top(h, RUNWAY_TARGET);
        // heading +z; drop point 60 blocks behind, closer than RELEASE_DISTANCE
        Vec3 behind = start.add(0, 0, -60);
        bomberEntersNear(h, start, start.add(0, 0, 3000), behind, behind);
    }

    /*
     * The B-2's flight level is above the place where the bomb will fall, not above the first surface estimate at
     * launch: retargeted to a point 43 blocks lower than before, a B-2 from the old flight level would drop the bomb
     * from 214 blocks instead of 170, and it would enter the ground 42 blocks past the point. It changes flight level
     * and drops only from it (or re-attacks).
     */

    @GameTest(template = "runway", timeoutTicks = 1500, batch = "bomber_lower", skyAccess = true)
    public static void bomberRetargetedLowerHits(GameTestHelper h) {
        Vec3 start = top(h, RUNWAY_TARGET);
        Vec3 behind = start.add(0, 0, -60);
        bomberEntersNear(h, start, start.add(0, 43, 3000), behind, behind);
    }

    @GameTest(template = "runway", timeoutTicks = 1500, batch = "bomber_higher", skyAccess = true)
    public static void bomberRetargetedHigherHits(GameTestHelper h) {
        Vec3 start = top(h, RUNWAY_TARGET);
        Vec3 behind = start.add(0, 0, -60);
        bomberEntersNear(h, start, start.add(0, -43, 3000), behind, behind);
    }

    @GameTest(template = "runway", timeoutTicks = 2000, batch = "bomber_much_lower", skyAccess = true)
    public static void bomberRetargetedMuchLowerHits(GameTestHelper h) {
        Vec3 start = top(h, RUNWAY_TARGET);
        Vec3 behind = start.add(0, 0, -60);
        bomberEntersNear(h, start, start.add(0, 100, 3000), behind, behind);
    }

    @GameTest(template = "runway", timeoutTicks = 2000, batch = "bomber_much_higher", skyAccess = true)
    public static void bomberRetargetedMuchHigherHits(GameTestHelper h) {
        Vec3 start = top(h, RUNWAY_TARGET);
        Vec3 behind = start.add(0, 0, -60);
        bomberEntersNear(h, start, start.add(0, -100, 3000), behind, behind);
    }

    /*
     * On approach, a B-2 above the flight level (the level is 43 blocks lower than at launch; launches at 650 and 690
     * blocks) drops from the height it is at, from where the bomb will reach the point — on the first pass. Dropping
     * only at the flight level at the 145-block line, it would not have had time to descend to it and would go around
     * for a second pass, while one that reached the level already inside the line would drop from there, and the bomb
     * would overshoot the point by tens of blocks.
     */

    @GameTest(template = "runway", timeoutTicks = 1500, batch = "bomber_level_650", skyAccess = true)
    public static void bomberAboveLevelAtReleaseDistanceHitsOnFirstPass(GameTestHelper h) {
        Vec3 start = top(h, RUNWAY_TARGET);
        bomberEntersNear(h, start.add(0, 0, -650), start.add(0, 43, 0), null, start, true);
    }

    @GameTest(template = "runway", timeoutTicks = 1500, batch = "bomber_level_690", skyAccess = true)
    public static void bomberReachingLevelInsideReleaseDistanceHitsOnFirstPass(GameTestHelper h) {
        Vec3 start = top(h, RUNWAY_TARGET);
        bomberEntersNear(h, start.add(0, 0, -690), start.add(0, 43, 0), null, start, true);
    }

    /* Retargeted to a point 40 blocks ahead, dropping at once, it would miss by ~150:
     * the bomb would not hit from there — re-attack. */

    @GameTest(template = "runway", timeoutTicks = 2500, batch = "bomber_ahead_40", skyAccess = true)
    public static void bomberRetargetedJustAheadReattacks(GameTestHelper h) {
        Vec3 start = top(h, RUNWAY_TARGET);
        Vec3 ahead = start.add(0, 0, 40);
        bomberEntersNear(h, start, start.add(0, 0, 3000), ahead, ahead);
    }

    /*
     * B-2 returns to the world over a city or a hill. A climb at the return of 120 blocks above the tallest thing
     * ahead, within 80 blocks, over a 70-block house would give 20 blocks above the flight level, and from the
     * remaining ~230 blocks it would not have had time to descend and would go around for a second pass. The bomb is in
     * the ground on the first pass, without a second.
     */
    @GameTest(template = "runway", timeoutTicks = 2500, batch = "bomber_tall_entry", skyAccess = true)
    public static void bomberEnteringWorldOverTallBuildingHitsOnFirstPass(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        Vec3 start = top(h, RUNWAY_TARGET);
        int x = Mth.floor(start.x), z0 = Mth.floor(start.z);
        // strip of ticking chunks on the heading from 320 to 120 blocks from the target: the B-2 returns to the world
        // at its far edge
        List<ChunkPos> forced = new ArrayList<>();
        for (int dz = -320; dz <= -120; dz += 16) forced.add(new ChunkPos(x >> 4, (z0 + dz) >> 4));
        // The strip and two chunk columns on each side — ready at once (background generation would not finish by the
        // test's deadline: on a slow machine the B-2 would return to the world at the pad's edge, and the test would
        // not catch the climb at the strip).
        for (ChunkPos c : forced) for (int dx = -2; dx <= 2; dx++) level.getChunk(c.x + dx, c.z);
        for (ChunkPos c : forced) level.setChunkForced(c.x, c.z, true);
        // house 70 blocks above the ground at the target — ahead of the return point within 80 blocks
        int groundY = Mth.floor(start.y);
        for (int dx = -3; dx <= 3; dx++) for (int dz = -3; dz <= 3; dz++) for (int y = 0; y < 70; y++) {
            level.setBlock(new BlockPos(x + dx, groundY + y, z0 - 290 + dz), Blocks.STONE.defaultBlockState(), 2);
        }
        bomberEntersNear(h, start.add(0, 0, -2000), start, null, start, true);
        // the strip is needed only for the return to the world, beyond it — the target's area
        h.onEachTick(() -> {
            if (!forced.isEmpty() && level.getEntitiesOfClass(BunkerBusterEntity.class, new AABB(start, start).inflate(400)).stream().anyMatch(BunkerBusterEntity::isDrilling)) {
                for (ChunkPos c : forced) level.setChunkForced(c.x, c.z, false);
                forced.clear();
            }
        });
    }

    /**
     * After release the B-2 holds its own chunk while it leaves. Without its own ticket it enters a chunk that ticks by
     * the ticket of an oncoming salvo B-2; that one leaves, and the B-2 freezes in an unticked chunk (“in flight,
     * unloaded with the chunk”). The check is two chunks from the release point and beyond: the chunk ahead on the
     * heading, taken in the release tick, holds even without its own ticket.
     */
    @GameTest(template = "runway", timeoutTicks = 200, batch = "bomber_egress", skyAccess = true)
    public static void bomberHoldsChunkAfterRelease(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        BomberEntity bomber = ModEntities.BOMBER.get().create(level);
        bomber.launch(top(h, new BlockPos(16, 3, 20)), top(h, RUNWAY_TARGET), null, UUID.randomUUID());
        bomber.setRoute(null);
        level.addFreshEntity(bomber);
        UUID id = bomber.getUUID();
        ChunkPos[] release = {null};
        h.succeedWhen(() -> {
            h.assertTrue(level.getEntity(id) instanceof BomberEntity b && !b.isRemoved(), "B-2 removed"
                    + (release[0] == null ? " before the release" : " at " + bomber.blockPosition().toShortString() + ", release in chunk " + release[0]));
            BomberEntity b = (BomberEntity) level.getEntity(id);
            h.assertTrue(b.hasReleased(), "B-2 has not released the bomb yet");
            ChunkPos here = b.chunkPosition();
            if (release[0] == null) release[0] = here;
            h.assertTrue(here.getChessboardDistance(release[0]) >= 2, "B-2 still at the release point");
            h.assertTrue(ChunkTickets.holds(level, id, here.toLong()), "B-2 does not hold its chunk after release " + here);
        });
    }

    /**
     * Without a retarget: a tower 40 blocks above the target that was not in the surface estimate at launch
     * (roof, chunk not ready).
     */
    @GameTest(template = "runway", timeoutTicks = 1500, batch = "bomber_tower", skyAccess = true)
    public static void bomberHitsTowerTopMissingFromFirstEstimate(GameTestHelper h) {
        Vec3 start = top(h, RUNWAY_TARGET);
        for (int i = 1; i <= 40; i++) h.setBlock(RUNWAY_TARGET.above(i), Blocks.STONE);
        bomberEntersNear(h, start.add(0, 0, -2000), start, null, start.add(0, 40, 0));
    }

    /** Airborne point (a player in flight) behind: the bomb — into the ground under it, flight level — above the ground. */
    @GameTest(template = "runway", timeoutTicks = 1500, batch = "bomber_airborne", skyAccess = true)
    public static void bomberRetargetedAtAirbornePointHitsGroundUnder(GameTestHelper h) {
        Vec3 start = top(h, RUNWAY_TARGET);
        Vec3 behind = start.add(0, 0, -60);
        bomberEntersNear(h, start, start.add(0, 0, 3000), behind.add(0, 64, 0), behind);
    }

    /**
     * B-2 from launch {@code from} onto surface {@code aim} (the flight level above it), at once retargeted to {@code
     * retarget} (null — no); the bomb, one, must enter the ground 4 blocks from {@code expect} horizontally and at its
     * height (not into the pad's barrier wall) — dropped no earlier and no later, and not lost.
     */
    private static void bomberEntersNear(GameTestHelper h, Vec3 from, Vec3 aim, @Nullable Vec3 retarget, Vec3 expect) {
        bomberEntersNear(h, from, aim, retarget, expect, false);
    }

    /**
     * {@code onePass} — also without a second pass: a B-2 that passes the release line without dropping fails
     * the test.
     */
    private static void bomberEntersNear(GameTestHelper h, Vec3 from, Vec3 aim, @Nullable Vec3 retarget, Vec3 expect, boolean onePass) {
        ServerLevel level = h.getLevel();
        BomberEntity bomber = ModEntities.BOMBER.get().create(level);
        // its own owner — count only its own bomb
        UUID owner = UUID.randomUUID();
        bomber.launch(from, aim, null, owner);
        // flight-plan range margin, as for a combat launch (StrikeService.launchBomber)
        bomber.setRoute(null);
        if (retarget != null) h.assertTrue(bomber.retarget(new Target.Point(retarget), retarget), "bomber did not accept the retarget");
        // As a combat launch: the start of the flight is outside the world; in a loaded place it returns to the world
        // on the nearest tick (a launch from afar through addFreshEntity into an unloaded chunk would not tick).
        VirtualFlights.launch(level, bomber);
        UUID id = bomber.getUUID();
        BomberEntity[] last = {bomber};
        Vec3[] entry = {null};
        BunkerBusterEntity[] bomb = {null};
        Set<UUID> bombs = new HashSet<>();
        h.onEachTick(() -> {
            StrikeProjectile now = VirtualFlights.get(level).flights().stream().filter(f -> f.getUUID().equals(id)).findFirst()
                    .orElseGet(() -> level.getEntity(id) instanceof StrikeProjectile e && !e.isRemoved() ? e : null);
            if (now instanceof BomberEntity b) {
                last[0] = b;
                double d = b.position().subtract(expect).horizontalDistance();
                if (onePass && !b.hasReleased() && d < BomberEntity.releaseLine(WeaponSpec.BUNKER.airframe().cruiseHeight()) - WeaponSpec.BUNKER.airframe().cruiseSpeed()) {
                    h.fail("B-2 passed the release line without dropping (second pass): at " + (int) d + " blocks from the point, at height "
                            + (int) (b.getY() - expect.y) + ", outside the world " + b.isVirtual());
                }
            }
            List<BunkerBusterEntity> seen = new ArrayList<>(level.getEntitiesOfClass(BunkerBusterEntity.class, new AABB(expect, expect).inflate(400, 400, 400)));
            for (StrikeProjectile p : VirtualFlights.get(level).flights()) if (p instanceof BunkerBusterEntity b) seen.add(b);
            for (BunkerBusterEntity b : seen) {
                if (!owner.equals(b.ownerId())) continue;
                bombs.add(b.getUUID());
                bomb[0] = b;
                if (entry[0] == null && b.isDrilling()) entry[0] = b.entry();
            }
        });
        h.succeedWhen(() -> {
            h.assertTrue(entry[0] != null, "bomb has not entered the ground yet (bombs " + bombs.size() + "); B-2 at "
                    + (int) last[0].position().subtract(expect).horizontalDistance() + " blocks from the point, at height " + (int) (last[0].getY() - expect.y)
                    + ", outside the world " + last[0].isVirtual() + ", released " + last[0].hasReleased() + ", removed " + last[0].isRemoved()
                    + (bomb[0] == null ? "" : "; bomb at " + (int) bomb[0].position().subtract(expect).horizontalDistance() + " blocks from the point, at height "
                    + (int) (bomb[0].getY() - expect.y) + ", outside the world " + bomb[0].isVirtual() + ", removed " + bomb[0].isRemoved()));
            h.assertTrue(bombs.size() == 1, "bombs " + bombs.size());
            double miss = entry[0].subtract(expect).horizontalDistance();
            h.assertTrue(miss < 4 && Math.abs(entry[0].y - expect.y) < 4, "bomb entered the ground at " + (int) miss
                    + " blocks from the point, at height " + (int) (entry[0].y - expect.y));
        });
    }

    /**
     * B-2 on the real flight scale (1): flight level 12 km above the point, 250 m/s, drop at the release line at ~10 km
     * horizontally (the same angle to the point as at the game flight level). The bomb falls ~50 s — outside the loaded
     * world and in it — and enters the ground at the point at the GBU-57 impact speed from the passport (511 m/s, here
     * not less than 450): penetration at the target (S5) — by this speed, not by the game one (~235 m/s). The player
     * directly under the B-2 gets its path for the distant view: the 12 km of height does not eat into the visibility
     * range.
     */
    @GameTest(template = "runway", timeoutTicks = 1600, batch = "flight_scale_bomber", skyAccess = true)
    public static void bomberAtRealEchelonDropsAtPassportSpeed(GameTestHelper h) {
        flightScale(h, 1);
        ServerLevel level = h.getLevel();
        Vec3 surface = top(h, RUNWAY_TARGET).subtract(0, 0.5, 0);
        WeaponSpec.Airframe b2 = WeaponSpec.BUNKER.airframe();
        double line = BomberEntity.releaseLine(b2.cruiseHeight());
        BomberEntity bomber = ModEntities.BOMBER.get().create(level);
        UUID owner = UUID.randomUUID();
        // as a combat launch (StrikeService.launchBomber): at flight level above the point, from afar — here 60 ticks
        // before the line
        bomber.launch(surface.add(0, 0, -(line + 60 * b2.cruiseSpeed())), surface, null, owner);
        bomber.setRoute(null);
        VirtualFlights.launch(level, bomber);
        UUID id = bomber.getUUID();
        double[] release = {Double.NaN, Double.NaN}, entry = {Double.NaN};
        Vec3[] entered = {null};
        boolean[] overhead = {false};
        h.onEachTick(() -> {
            // Distant view: a B-2 at 12 km above the player's head beneath it — in the path of the projectiles the
            // player sees (far_range — horizontally).
            if (!overhead[0] && findProjectile(level, id) instanceof BomberEntity b) {
                Vec3 under = new Vec3(b.getX(), surface.y, b.getZ());
                overhead[0] = FarFlights.sample(level, FarFlights.flights(level), under, null, true).stream().anyMatch(f -> f.id().equals(id));
            }
            List<BunkerBusterEntity> seen = new ArrayList<>(level.getEntitiesOfClass(BunkerBusterEntity.class, new AABB(surface, surface).inflate(400, 400, 400)));
            for (StrikeProjectile p : VirtualFlights.get(level).flights()) if (p instanceof BunkerBusterEntity b) seen.add(b);
            for (BunkerBusterEntity b : seen) {
                if (!owner.equals(b.ownerId()) || entered[0] != null) continue;
                // release — where the bomb appeared (a B-2 outside the world is removed at once after release)
                if (Double.isNaN(release[0])) {
                    release[0] = b.position().subtract(surface).horizontalDistance();
                    release[1] = b.getY() + BombDrop.DROP_BELOW - surface.y;
                }
                if (b.isDrilling()) entered[0] = b.entry();
                else entry[0] = b.speed();
            }
        });
        h.succeedWhen(() -> {
            h.assertTrue(overhead[0], "the B-2 at flight level is not visible to the player directly beneath it");
            h.assertTrue(!Double.isNaN(release[0]), "B-2 did not release the bomb");
            h.assertTrue(Math.abs(release[1] - b2.cruiseHeight()) < 200 && Math.abs(release[0] - line) < 600,
                    "release at " + (int) release[0] + " blocks from the point at height " + (int) release[1] + " (release line " + (int) line + ")");
            h.assertTrue(entered[0] != null, "bomb has not entered the ground yet (last speed " + entry[0] + ")");
            double miss = entered[0].subtract(surface).horizontalDistance();
            h.assertTrue(miss < 6, "bomb entered the ground at " + (int) miss + " blocks from the point");
            h.assertTrue(entry[0] * 20 > 450, "impact at " + Math.round(entry[0] * 20) + " m/s");
        });
    }

    /**
     * Shahed from afar on the real flight scale (1): approach at a flight level 2 km above the target, a dive from
     * there — and detonation at the point, not self-destruction in the air: the range margin of the plan also counts
     * the descent to the target (the route — horizontally). The point is the one the Shahed aims at: the launch shifts
     * it from the target by the passport's miss (SD 3 m along the axis, {@code WeaponSpec.Route#miss}), and with the
     * target the test diverged in e⁻² ≈ 13 % of launches (CI 22.10); the miss itself — no more than 5 SD.
     */
    @GameTest(template = "runway", timeoutTicks = 1600, batch = "flight_scale_drone", skyAccess = true)
    public static void droneFromRealEchelonDivesIntoTarget(GameTestHelper h) {
        flightScale(h, 1);
        int time = IncomingConfig.SERVER.droneFlightTime.get();
        IncomingConfig.SERVER.droneFlightTime.set(12);
        afterTest(h, () -> IncomingConfig.SERVER.droneFlightTime.set(time));
        ServerLevel level = h.getLevel();
        Vec3 point = top(h, RUNWAY_TARGET);
        UUID owner = UUID.randomUUID();
        h.assertTrue(StrikeService.launch(level, WeaponType.DRONE, new Target.Point(point), point, 0, owner, false, Loadout.Nuke.DEFAULT,
                Waypoints.NONE, null).ok(), "launch not accepted");
        String[] last = {""};
        boolean[] high = {false};
        Vec3[] aim = {null};
        h.onEachTick(() -> {
            List<StrikeProjectile> all = new ArrayList<>(VirtualFlights.get(level).flights());
            all.addAll(level.getEntitiesOfClass(DroneEntity.class, new AABB(point, point).inflate(600, 3000, 600)));
            for (StrikeProjectile p : all) {
                if (!owner.equals(p.ownerId())) continue;
                if (p.getY() - point.y > 1500) high[0] = true;
                aim[0] = p.aimPoint();
                last[0] = p.flightPhase() + " " + h.relativeVec(p.position()) + " reserve " + Math.round(p.rangeLeft()) + ", aims at " + h.relativeVec(aim[0]);
            }
        });
        h.succeedWhen(() -> {
            h.assertTrue(high[0], "Shahed did not fly at the real flight level: " + last[0]);
            h.assertTrue(VirtualFlights.get(level).flights().stream().noneMatch(p -> owner.equals(p.ownerId()))
                    && level.getEntitiesOfClass(DroneEntity.class, new AABB(point, point).inflate(600, 3000, 600)).isEmpty(), "Shahed still flying: " + last[0]);
            double miss = Math.hypot(aim[0].x - point.x, aim[0].z - point.z);
            h.assertTrue(miss <= 5 * WeaponType.DRONE.spec().route().error(), String.format(Locale.ROOT, "launch miss %.1f m — more than 5 SD", miss));
            // from the pad's start — by subtraction: relativePos in 1.21.1 turns by 180°
            assertCrater(h, BlockPos.containing(aim[0]).subtract(h.absolutePos(BlockPos.ZERO)), last[0]);
        });
    }

    /**
     * Lancet on the real flight scale (1): a circle 700 m above the target; the target (a cow) dies while it circles —
     * it finishes the circle and dives from there onto its last point, rather than self-destructing in the air: the
     * range margin after losing the target is cut to the path per the plan ({@code Mission.lose}), and the plan lays
     * the dive from the circle's height ({@code LoiterEntity.diveTicks}), not 25 ticks of dive from the game circle.
     * The munition's seed is fixed (circle, loiter time) — the one with which the dive ends vertically, with the target
     * behind the heading: the pitch hits 89°, and before the correction over the target ({@code Dive.steer}) the
     * residual drift took the Lancet 10 m off (CI 22.10, 2 seeds out of 40).
     */
    @GameTest(template = "runway", timeoutTicks = 1600, batch = "flight_scale_loiter", skyAccess = true)
    public static void loiterLosingTargetAtRealHeightStillDives(GameTestHelper h) {
        flightScale(h, 1);
        int time = IncomingConfig.SERVER.loiterTime.get();
        IncomingConfig.SERVER.loiterTime.set(5);
        afterTest(h, () -> IncomingConfig.SERVER.loiterTime.set(time));
        ServerLevel level = h.getLevel();
        Vec3 point = top(h, RUNWAY_TARGET);
        Cow cow = EntityType.COW.create(level);
        cow.setNoAi(true);
        cow.moveTo(point.x, point.y, point.z);
        level.addFreshEntity(cow);
        LoiterEntity e = ModEntities.LOITER.get().create(level);
        e.seed(237583);
        e.launch(point.add(0, WeaponSpec.LOITER.airframe().cruiseHeight(), -150), Target.OfEntity.center(cow),
                cow.getBoundingBox().getCenter(), null);
        e.setRoute(null);
        VirtualFlights.launch(level, e);
        java.util.UUID id = e.getUUID();
        boolean[] high = {false}, killed = {false};
        String[] last = {""};
        h.onEachTick(() -> {
            LoiterEntity l = findLoiter(level, id);
            if (l == null) return;
            if (l.getY() - point.y > 600) high[0] = true;
            if (!killed[0] && l.flightPhase() == FlightPhase.LOITER && l.phaseAge() > 20) {
                killed[0] = true;
                cow.kill();
            }
            last[0] = l.flightPhase() + " " + h.relativeVec(l.position()) + " reserve " + Math.round(l.rangeLeft());
        });
        h.succeedWhen(() -> {
            h.assertTrue(high[0], "circle not at the real height: " + last[0]);
            h.assertTrue(killed[0], "target did not die on the circle: " + last[0]);
            h.assertTrue(findLoiter(level, id) == null, "loitering munition still flying: " + last[0]);
            assertCrater(h, RUNWAY_TARGET, last[0]);
        });
    }

    /**
     * Flight scale {@code x} for the check (preset {@code CUSTOM}); afterwards the former preset and value.
     */
    static void flightScale(GameTestHelper h, double x) {
        IncomingConfig.ScalePreset preset = IncomingConfig.SERVER.scalePreset.get();
        double was = IncomingConfig.SERVER.flightSpeed.get();
        IncomingConfig.SERVER.scalePreset.set(IncomingConfig.ScalePreset.CUSTOM);
        IncomingConfig.SERVER.flightSpeed.set(x);
        afterTest(h, () -> {
            IncomingConfig.SERVER.scalePreset.set(preset);
            IncomingConfig.SERVER.flightSpeed.set(was);
        });
    }

    /**
     * A bomb dropped under the point at its own height (the target in the air: a player in flight, and the chunk under
     * him was not ready at launch), does not level out or circle, but falls to the ground long before the end of its
     * lifetime, rather than stretching to the point, passing under it and going to a circle of radius ~240 blocks until
     * the end of its lifetime.
     */
    @GameTest(template = "runway", timeoutTicks = 200, batch = "bunker_air", skyAccess = true)
    public static void bunkerBusterFallsUnderAirborneAim(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        BunkerBusterEntity bomb = ModEntities.BUNKER_BUSTER.get().create(level);
        Vec3 aim = top(h, RUNWAY_TARGET).add(0, 64, 0);
        bomb.drop(aim.add(0, -4, -85), BomberEntity.levelVelocity(0), aim, null, null);
        level.addFreshEntity(bomb);
        double[] lastY = {bomb.getY()};
        boolean[] drilled = {false};
        h.onEachTick(() -> {
            if (bomb.isRemoved()) return;
            h.assertTrue(bomb.getY() <= lastY[0] + 1e-6, "bomb gains height: " + lastY[0] + " → " + bomb.getY());
            lastY[0] = bomb.getY();
            if (bomb.isDrilling()) drilled[0] = true;
        });
        h.succeedWhen(() -> {
            h.assertTrue(drilled[0], "bomb did not enter the ground");
            h.assertTrue(bomb.isRemoved(), "bomb has not detonated yet");
            Vec3 entry = bomb.entry();
            h.assertTrue(Math.abs(entry.x - aim.x) < 16 && entry.z > aim.z - 85 && entry.z < aim.z + 120,
                    "bomb fell far from the target: " + entry);
        });
    }

    /**
     * A projectile outside the world that passed the target does not go underground: outside the world there are no
     * collisions, and such a projectile would fall without a blast until the end of its lifetime or to the bottom of
     * the world (a bomb at a point behind the B-2 — below the world at y=−3022). The path outside the world ends on the
     * surface (the height map of a ready chunk; for an unready one, sea level): the projectile waits for that place to
     * load, returns to the world on the surface and detonates on an ordinary hit. A bomb at a point behind itself does
     * not steer and falls steeply down its heading — it must enter the ground where its path meets the terrain.
     */
    @GameTest(template = "runway", timeoutTicks = 1500, batch = "virtual_ground", skyAccess = true)
    public static void virtualMissNeverFallsBelowGround(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        // the impact place is not known in advance; it loads in the background while the bomb waits: the waiting time
        // is game time
        gameSpeed(h);
        // far beyond the pad: nothing is loaded around, the bomb flies outside the world at once
        BlockPos origin = FarSite.MISS_BELOW_GROUND.at(h);
        Vec3 from = new Vec3(origin.getX() + 0.5, REFERENCE_SEA_LEVEL + 200, origin.getZ() + 0.5);
        Vec3 behind = from.add(0, -150, -300);
        BunkerBusterEntity bomb = ModEntities.BUNKER_BUSTER.get().create(level);
        bomb.drop(from, BomberEntity.levelVelocity(0), behind, null, null);
        VirtualFlights.launch(level, bomb);
        UUID id = bomb.getUUID();
        Vec3[] entry = {null};
        double[] lowest = {from.y};
        int[] ground = {0};
        h.onEachTick(() -> {
            StrikeProjectile p = VirtualFlights.get(level).flights().stream().filter(f -> f.getUUID().equals(id)).findFirst()
                    .orElseGet(() -> level.getEntity(id) instanceof StrikeProjectile e ? e : null);
            if (p == null) return;
            lowest[0] = Math.min(lowest[0], p.getY());
            if (entry[0] == null && p instanceof BunkerBusterEntity b && b.isDrilling()) {
                entry[0] = b.entry();
                // terrain beside the hole: inside the hole the bomb has already chosen the ground
                ground[0] = Terrain.height(level, Heightmap.Types.MOTION_BLOCKING, Mth.floor(entry[0].x) + 24, Mth.floor(entry[0].z));
            }
        });
        h.succeedWhen(() -> {
            h.assertTrue(lowest[0] > level.getMinBuildHeight(), "bomb went under the world: y=" + (int) lowest[0]);
            h.assertTrue(entry[0] != null, "bomb did not enter the ground, lowest y=" + (int) lowest[0]);
            // entry 1 block above the impact point (smoke from the hole)
            h.assertTrue(Math.abs(entry[0].y - 1 - ground[0]) < 2, "bomb entered the ground not at the surface: y=" + (int) entry[0].y + ", terrain " + ground[0]);
            // falls forward on its heading (+z), does not steer: it does not drift sideways, forward — no further than
            // two fall heights
            h.assertTrue(entry[0].z > from.z && entry[0].z - from.z < 2 * (from.y - ground[0]) && Math.abs(entry[0].x - from.x) < 8,
                    "bomb entered the ground off its path: " + entry[0].subtract(from));
            assertFlightReleased(h, id);
        });
    }

    /**
     * A bomb outside the world falls over an unready chunk, where the flight floor is sea level, and the chunk becomes
     * ready in the middle of the fall, and the real surface is above the bomb. The bomb does not steer up: below the
     * surface it is already in the ground, and it hits the surface above it: the floor has “risen” above it, there is
     * no top-down crossing, and otherwise it would fall under the world.
     */
    @GameTest(template = "runway", timeoutTicks = 1500, batch = "virtual_risen_ground", skyAccess = true)
    public static void virtualBombUnderRisenSurfaceHitsIt(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        // the impact place loads in the background while the bomb waits: the waiting time is game time
        gameSpeed(h);
        BlockPos origin = FarSite.BOMB_RISEN_SURFACE.at(h);
        Vec3 from = new Vec3(origin.getX() + 0.5, REFERENCE_SEA_LEVEL + 200, origin.getZ() + 0.5);
        Vec3 behind = from.add(0, -150, -300);
        int sea = level.getChunkSource().getGenerator().getSeaLevel();
        // “terrain” not visible outside the world: a slab 80 blocks above the generator's sea
        int roof = sea + 80;
        BunkerBusterEntity bomb = ModEntities.BUNKER_BUSTER.get().create(level);
        bomb.drop(from, BomberEntity.levelVelocity(0), behind, null, null);
        VirtualFlights.launch(level, bomb);
        UUID id = bomb.getUUID();
        Vec3[] entry = {null};
        double[] lowest = {from.y};
        boolean[] risen = {false};
        double[] risenZ = {0};
        List<ChunkPos> held = new ArrayList<>();
        afterTest(h, () -> held.forEach(c -> level.getChunkSource().removeRegionTicket(READY_ONLY, c, 0, c)));
        h.onEachTick(() -> {
            StrikeProjectile p = flight(level, id);
            if (p == null) return;
            lowest[0] = Math.min(lowest[0], p.getY());
            // the bomb outside the world is below the future slab but above the sea — its chunks are ready, and the
            // slab is already above it
            if (!risen[0] && p.isVirtual() && p.getY() < roof - 20 && p.getY() > sea + 5) {
                risen[0] = true;
                risenZ[0] = p.getZ();
                BlockPos at = p.blockPosition();
                // The slab is short: beyond it the bomb goes below the bottom of the world only ~35 blocks along the
                // heading — the slab does not reach there, and only a bomb that fell into the surface where the slab
                // rose over it hits it
                for (int dx = -3; dx <= 3; dx++) {
                    for (int dz = -8; dz <= 16; dz++) {
                        BlockPos b = new BlockPos(at.getX() + dx, roof, at.getZ() + dz);
                        ChunkPos c = new ChunkPos(b);
                        if (!held.contains(c)) {
                            // level 33: the chunk is ready (FULL), but does not tick — the bomb above it stays outside
                            // the world
                            level.getChunkSource().addRegionTicket(READY_ONLY, c, 0, c);
                            level.getChunk(c.x, c.z);
                            held.add(c);
                        }
                        level.setBlock(b, Blocks.STONE.defaultBlockState(), Block.UPDATE_CLIENTS);
                    }
                }
            }
            if (entry[0] == null && p instanceof BunkerBusterEntity b && b.isDrilling()) entry[0] = b.entry();
        });
        h.succeedWhen(() -> {
            h.assertTrue(risen[0], "slab did not rise above the bomb");
            h.assertTrue(lowest[0] > level.getMinBuildHeight(), "bomb went under the world: y=" + (int) lowest[0]);
            h.assertTrue(entry[0] != null, "bomb did not enter the ground, lowest y=" + (int) lowest[0]);
            // entry 1 block above the impact point (smoke from the hole); the impact is the slab's top
            h.assertTrue(Math.abs(entry[0].y - 1 - (roof + 1)) < 2, "bomb entered not the slab above it: y=" + (int) entry[0].y + ", slab " + roof);
            h.assertTrue(Math.abs(entry[0].z - risenZ[0]) < 4, "bomb entered the slab not where the slab rose over it: " + (entry[0].z - risenZ[0]));
            // under the slab — air down to the ground: the bomb passes through it in the ground and goes into
            // neighboring chunks
            assertFlightReleased(h, id);
        });
    }

    /**
     * A target below the terrain (cave, quarry, ravine; in a normal world — everything below sea level 63; for unready
     * chunks that is the surface outside the world): outside the world a projectile flies at the target's height — a
     * launch from afar above it, an MLRS from its height — and beneath the surface, until it is not below the target,
     * it flies on. Here above the launch place is a “terrain” from a ready but unticked chunk, 60 blocks above the
     * target; otherwise the very first step outside the world ends on it, and the blast is 1500 blocks from the target.
     */
    @GameTest(template = "runway", timeoutTicks = 900, batch = "virtual_deep_missile", skyAccess = true)
    public static void virtualMissileUnderTerrainReachesDeepTarget(GameTestHelper h) {
        Vec3 aim = top(h, RUNWAY_TARGET);
        CruiseMissileEntity missile = ModEntities.CRUISE_MISSILE.get().create(h.getLevel());
        // as a launch from afar (StrikeService.fromAfar): 12 blocks above the target
        Vec3 start = aim.add(0, 12, -1500);
        missile.launch(start, new Target.Point(aim), aim, null);
        missile.setRoute(Route.direct());
        reachesUnderTerrain(h, missile, start, aim);
    }

    @GameTest(template = "runway", timeoutTicks = 900, batch = "virtual_deep_rocket", skyAccess = true)
    public static void virtualRocketUnderTerrainReachesDeepTarget(GameTestHelper h) {
        Vec3 aim = top(h, RUNWAY_TARGET);
        RocketEntity rocket = ModEntities.ROCKET.get().create(h.getLevel());
        // as an MLRS launch from afar (StrikeService): from the target's height, 600 blocks away
        Vec3 start = aim.add(0, 0, -600);
        rocket.launchFrom(start, new Target.Point(aim), aim, null);
        reachesUnderTerrain(h, rocket, start, aim);
    }

    private static final TicketType<ChunkPos> READY_ONLY = TicketType.create("incoming_test_ready", Comparator.comparingLong(ChunkPos::toLong));

    /**
     * The target that the projectile holds its height on rose 40 blocks (the player climbed out of a ravine, a
     * teleport), and in the same tick a “terrain” rose over the projectile (a ready but unticked chunk loaded): the
     * flight floor outside the world ({@code BELOW_AIM} under the target) rose above the projectile. A projectile at
     * low altitude does not fall where it was caught (1500 blocks from the target), but climbs over the terrain and
     * behind the target and hits it. The terrain rises only then: in advance, the rocket would see it with its sensor
     * ({@code StrikeProjectile.relief}) and would fly over it, never being below the floor.
     */
    @GameTest(template = "runway", timeoutTicks = 900, batch = "virtual_rising_aim", skyAccess = true)
    public static void virtualMissileClimbsAfterRisingAim(GameTestHelper h) {
        // the missile descended under the “terrain” to its height over the target (+12)
        climbsAfterRisingAim(h, 0, (y, floor, dy) -> y < floor - 11);
    }

    /**
     * The same, but the target rises while the missile is still descending to its former height — in the tick after
     * which a missile step would pass through the new floor: the floor rose above it by itself; this is not a top-down
     * crossing.
     */
    @GameTest(template = "runway", timeoutTicks = 900, batch = "virtual_rising_aim_descent", skyAccess = true)
    public static void virtualMissileClimbsAfterAimRisesMidDescent(GameTestHelper h) {
        climbsAfterRisingAim(h, 64, (y, floor, dy) -> dy > 0 && y >= floor && y - dy < floor);
    }

    @FunctionalInterface
    private interface RiseWhen {
        /** Raise the target now: missile height, floor after the target's rise, missile descent in the previous tick. */
        boolean test(double y, double floorAfter, double descent);
    }

    private static void climbsAfterRisingAim(GameTestHelper h, int ridge, RiseWhen when) {
        ServerLevel level = h.getLevel();
        Vec3 low = top(h, RUNWAY_TARGET);
        ArmorStand stand = EntityType.ARMOR_STAND.create(level);
        stand.setNoGravity(true);
        stand.moveTo(low.x, low.y, low.z);
        level.addFreshEntity(stand);
        // +Z of the pad is still empty: the earlier batches stand on -Z, and the sensor sees their walls/roofs along
        // the whole path.
        Vec3 start = low.add(0, 12, 1500);
        int direction = -1;
        int roofLength = 1000;
        // A short ridge gives height for a real descent. A long canopy appears only when the target rises.
        if (ridge > 0) roofOverStart(h, start, Mth.floor(low.y) + 60, ridge, direction);
        CruiseMissileEntity missile = ModEntities.CRUISE_MISSILE.get().create(level);
        UUID id = missile.getUUID();
        Vec3 high = low.add(0, 40, 0);
        // flight floor outside the world after the rise: the target's center 16 blocks lower (terrain over the path is
        // higher)
        double floorAfter = high.y + stand.getBbHeight() / 2 - 16;
        Vec3[] last = {start};
        boolean[] launched = {false}, raised = {false}, climbed = {false};
        double[] raisedAt = {0}, range = {Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY};
        List<String> crossing = new ArrayList<>();
        h.onEachTick(() -> {
            if (!launched[0]) {
                // getChunk() returned FULL when built, but its holder may not yet be in the visible map. launch() reads
                // the height only through getChunkNow: otherwise the start is at +12, and the short ridge does not
                // always manage to raise the missile above the new floor. We wait for readiness itself, not a set
                // number of ticks.
                if (ridge > 0 && !Terrain.ready(level, BlockPos.containing(start))) return;
                missile.launch(start, Target.OfEntity.center(stand), low, null);
                missile.setRoute(Route.direct());
                h.assertTrue(ridge == 0 || missile.getY() > floorAfter,
                        "descent preparation: start y=" + missile.getY() + " not above the future floor " + floorAfter);
                last[0] = missile.position();
                VirtualFlights.launch(level, missile);
                launched[0] = true;
            }
            StrikeProjectile f = flight(level, id);
            if (f == null) return;
            double descent = last[0].y - f.getY();
            if (!raised[0] && f.isVirtual() && (f.getZ() - start.z) * direction < roofLength - 100) {
                range[0] = Math.min(range[0], f.getY());
                range[1] = Math.max(range[1], f.getY());
                if (descent > 0 && Math.abs(f.getY() - floorAfter) < 5 && crossing.size() < 20)
                    crossing.add(String.format(Locale.ROOT, "z=%.1f y=%.4f dy=%.4f predict=%.4f phase=%s",
                            (f.getZ() - start.z) * direction, f.getY(), descent, f.getY() - descent, f.flightPhase()));
            }
            last[0] = f.position();
            if (raised[0] && descent < 0 && f.getY() > raisedAt[0] + 4) climbed[0] = true;
            if (!raised[0] && f.isVirtual() && (f.getZ() - start.z) * direction < roofLength - 100 && when.test(f.getY(), floorAfter, descent)) {
                raised[0] = true;
                raisedAt[0] = f.getY();
                stand.teleportTo(high.x, high.y, high.z);
                // “terrain” above the missile and ahead to the end of the former canopy: the floor rose above it, on
                // this side too
                roofOverStart(h, new Vec3(start.x, start.y, Math.floor(f.getZ())), Mth.floor(low.y) + 60, (int) Math.abs(start.z + direction * roofLength - Math.floor(f.getZ())), direction);
            }
        });
        h.succeedWhen(() -> {
            h.assertTrue(launched[0], "ridge not ready, missile not launched");
            h.assertTrue(raised[0], "target did not rise; floor " + floorAfter + "; heights up to 900 m " + java.util.Arrays.toString(range) + "; descent " + crossing + "; last place " + last[0].subtract(low));
            h.assertTrue(climbed[0], "missile did not gain height after the target's rise");
            h.assertFalse(flight(level, id) != null, "missile still flying, to the target " + (int) last[0].distanceTo(high));
            h.assertTrue(last[0].distanceTo(high) < 24, "missile detonated not at the target: " + last[0].subtract(high));
            assertFlightReleased(h, id);
        });
    }

    /** Projectile by UUID — outside the world or inside it (null — it is already gone). */
    @Nullable
    private static StrikeProjectile flight(ServerLevel level, UUID id) {
        return VirtualFlights.get(level).flights().stream().filter(v -> v.getUUID().equals(id)).findFirst()
                .orElseGet(() -> level.getEntity(id) instanceof StrikeProjectile e && !e.isRemoved() ? e : null);
    }

    /**
     * The flight is over: the projectile is neither in the world nor outside it, the impacts have run, and the mod has
     * no tickets left with the projectile's key: neither its own chunks ({@code incoming_projectile}) nor the target
     * area and strip ({@code incoming_flight}), nor their load tickets. A projectile stuck in the world where nothing
     * ticks would hold them until the server stopped.
     */
    private static void assertFlightReleased(GameTestHelper h, UUID id) {
        ServerLevel level = h.getLevel();
        StrikeProjectile p = flight(level, id);
        if (p != null) {
            BlockPos at = p.blockPosition();
            throw new GameTestAssertException("projectile did not end: " + (p.isVirtual() ? "outside the world" : level.isPositionEntityTicking(at) ? "in the world" : "in the world, not ticking")
                    + " at " + at.toShortString() + ", phase " + p.flightPhase() + "; its tickets " + TicketProbe.types(level, id));
        }
        h.assertTrue(StrikeWorld.get(level).impacts().size() == 0, "impacts still queued");
        List<String> left = TicketProbe.types(level, id);
        h.assertTrue(left.isEmpty(), "after the flight its tickets remain: " + left + "; areas " + StrikeWorld.get(level).areas().describe());
    }

    /**
     * A projectile under the “relief” 60 blocks above the target, over the launch site, reaches the target and explodes
     * there.
     */
    private static void reachesUnderTerrain(GameTestHelper h, StrikeProjectile p, Vec3 start, Vec3 aim) {
        ServerLevel level = h.getLevel();
        roofOverStart(h, start, Mth.floor(aim.y) + 60, 64);
        VirtualFlights.launch(level, p);
        UUID id = p.getUUID();
        Vec3[] last = {start};
        h.onEachTick(() -> {
            StrikeProjectile f = flight(level, id);
            if (f != null) last[0] = f.position();
        });
        h.succeedWhen(() -> {
            h.assertFalse(flight(level, id) != null, "projectile still flying, distance to target " + (int) last[0].distanceTo(aim));
            h.assertTrue(last[0].distanceTo(aim) < 24, "projectile exploded away from the target: " + last[0].subtract(aim));
            assertCrater(h, RUNWAY_TARGET, "at target");
            assertFlightReleased(h, id);
        });
    }

    /**
     * The “relief” at height {@code roof} above the launch site and {@code length} blocks of path ahead (+z): ready
     * chunks where entities do not tick — the projectile flies outside the world over them. The tickets and the
     * “relief” itself are removed after the test in any outcome: a left-over strip of stone hung over the pads of the
     * next batches in the same grid column (the B-2 bunker buster entered it at 61 blocks above the ground, the ridge
     * hid the Shahed from the SAM).
     */
    private static void roofOverStart(GameTestHelper h, Vec3 start, int roof, int length) {
        roofOverStart(h, start, roof, length, 1);
    }

    /** direction — sign of the path direction along Z. */
    private static void roofOverStart(GameTestHelper h, Vec3 start, int roof, int length, int direction) {
        ServerLevel level = h.getLevel();
        List<ChunkPos> held = new ArrayList<>();
        List<BlockPos> laid = new ArrayList<>();
        for (int dz = 0; dz <= length; dz++) {
            BlockPos b = BlockPos.containing(start.x, roof, start.z + direction * dz);
            ChunkPos c = new ChunkPos(b);
            if (!held.contains(c)) {
                // level 33: chunk is ready (FULL), but not ticking
                level.getChunkSource().addRegionTicket(READY_ONLY, c, 0, c);
                level.getChunk(c.x, c.z);
                held.add(c);
            }
            level.setBlock(b, Blocks.STONE.defaultBlockState(), Block.UPDATE_CLIENTS);
            laid.add(b);
        }
        afterTest(h, () -> {
            // stone — while the chunks still hold their ticket
            for (BlockPos b : laid) if (level.getBlockState(b).is(Blocks.STONE)) level.setBlock(b, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
            held.forEach(c -> level.getChunkSource().removeRegionTicket(READY_ONLY, c, 0, c));
        });
    }

    /**
     * Cleanup after the test — both when it passed and when it failed or timed out (in {@code succeedWhen} it would run
     * only after success). Public: other test classes and flight scenarios use it as well.
     */
    public static void afterTest(GameTestHelper h, Runnable cleanup) {
        h.testInfo.addListener(new GameTestListener() {
            @Override
            public void testStructureLoaded(GameTestInfo info) {
            }

            @Override
            public void testPassed(GameTestInfo info, GameTestRunner runner) {
                cleanup.run();
            }

            @Override
            public void testFailed(GameTestInfo info, GameTestRunner runner) {
                cleanup.run();
            }

            @Override
            public void testAddedForRerun(GameTestInfo old, GameTestInfo rerun, GameTestRunner runner) {
            }
        });
    }

    /**
     * A projectile outside the world that reached the target, whose area already ticks, explodes at the target even if
     * the chunks beyond the target do not tick. It returns to the world only when the area ticks and the place ahead
     * along its course does too (margin against jumps at the border); at the target, in a chunk that ticks alone (a
     * player in the air over the edge of loading) this does not happen, and the projectile would fly past the target
     * without exploding and would fall ballistically until the end of its lifetime — hundreds of blocks below the
     * world.
     */
    @GameTest(template = "runway", timeoutTicks = 600, batch = "virtual_arrival", skyAccess = true)
    public static void virtualRocketDetonatesAtTickingAim(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        // far beyond the pad: only the target's chunk ticks (forced), the neighbors do not
        BlockPos origin = FarSite.ROCKET_TICKING_AIM.at(h);
        ChunkPos chunk = new ChunkPos(origin);
        level.setChunkForced(chunk.x, chunk.z, true);
        afterTest(h, () -> level.setChunkForced(chunk.x, chunk.z, false));
        // entities in the target's chunk tick only when the neighbors (5×5) are ready, and their background generation
        // on CI (the server ticks without pauses) takes longer than the test time limit — the projectile would wait for
        // the target's area (“still flying, distance to target 65”); neighbors are ready, but not ticking
        generateNow(level, chunk);
        Vec3 aim = new Vec3(chunk.getMiddleBlockX() + 0.5, REFERENCE_SEA_LEVEL + 140, chunk.getMiddleBlockZ() + 0.5);
        RocketEntity rocket = ModEntities.ROCKET.get().create(level);
        rocket.launchFrom(aim.add(-300, -140, 0), new Target.Point(aim), aim, null);
        VirtualFlights.launch(level, rocket);
        UUID id = rocket.getUUID();
        Vec3[] last = {rocket.position()};
        boolean[] passed = {false};
        h.onEachTick(() -> {
            StrikeProjectile p = VirtualFlights.get(level).flights().stream().filter(f -> f.getUUID().equals(id)).findFirst()
                    .orElseGet(() -> level.getEntity(id) instanceof StrikeProjectile e && !e.isRemoved() ? e : null);
            if (p == null) return;
            last[0] = p.position();
            // flew past the target: beyond it along the course (the projectile goes along +x)
            if (p.getX() > aim.x + 16) passed[0] = true;
        });
        h.succeedWhen(() -> {
            h.assertFalse(passed[0], "projectile flew past the target: " + last[0].subtract(aim));
            boolean flying = level.getEntity(id) != null && !level.getEntity(id).isRemoved()
                    || VirtualFlights.get(level).flights().stream().anyMatch(f -> f.getUUID().equals(id));
            h.assertFalse(flying, "projectile still flying, distance to target " + (int) last[0].distanceTo(aim));
            h.assertTrue(last[0].distanceTo(aim) < 16, "projectile vanished away from the target: " + last[0].subtract(aim));
        });
    }

    /**
     * The target below the relief (3 blocks underground), and beyond it along the course — a cliff 50 blocks higher:
     * the projectile returns to the world at the edge of the target's area (~40 blocks), and it rises above the relief
     * only along its remaining path. A relief 80 blocks ahead — and beyond the target — would lift an MLRS rocket onto
     * the cliff, and it would burst in the air or on the slope next to the target (10 blocks from the target — with y
     * 51 above 95).
     */
    @GameTest(template = "runway", timeoutTicks = 900, batch = "virtual_rocket_hill_beyond", skyAccess = true)
    public static void virtualRocketIgnoresTerrainBeyondAim(GameTestHelper h) {
        returnsUnderOwnTerrain(h, 4, 6, FarSite.ROCKET_HILL_BEYOND, (level, aim) -> {
            RocketEntity rocket = ModEntities.ROCKET.get().create(level);
            rocket.launchFrom(aim.add(-300, 0, 0), new Target.Point(aim), aim, null);
            return rocket;
        });
    }

    @GameTest(template = "runway", timeoutTicks = 900, batch = "virtual_missile_hill_beyond", skyAccess = true)
    public static void virtualMissileIgnoresTerrainBeyondAim(GameTestHelper h) {
        // the cliff is farther than for the rocket: the cruise missile in a dive overshoots the target by about 10
        // blocks
        returnsUnderOwnTerrain(h, 12, 28, FarSite.MISSILE_HILL_BEYOND, (level, aim) -> {
            CruiseMissileEntity missile = ModEntities.CRUISE_MISSILE.get().create(level);
            // like a launch from afar (StrikeService.fromAfar): 12 blocks above the target
            missile.launch(aim.add(-300, 12, 0), new Target.Point(aim), aim, null);
            missile.setRoute(Route.direct());
            return missile;
        });
    }

    /** The cliff behind the target is this many blocks above the relief over it. */
    private static final int HILL_BEYOND = 50;

    /** Closer to the target horizontally — the blast at the target (not on the cliff behind it). */
    private static final double BURST_NEAR_AIM = 5;
    /**
     * Target area of the test — as for the projectile ({@link FlightTickets}), but from the first tick: the return path
     * to the world is the same.
     */
    private static final TicketType<ChunkPos> AIM_AREA = TicketType.create("incoming_test_aim_area", Comparator.comparingLong(ChunkPos::toLong));

    /**
     * {@code clearance} — the projectile's margin above the relief when returning to the world ({@code
     * StrikeProjectile.clearance}), {@code cliff} — how many blocks behind the target along the course the cliff
     * starts, {@code site} — the test's own place.
     */
    private static void returnsUnderOwnTerrain(GameTestHelper h, int clearance, int cliff, FarSite site, java.util.function.BiFunction<ServerLevel, Vec3, StrikeProjectile> make) {
        ServerLevel level = h.getLevel();
        // far beyond the pad, as in virtualRocketDetonatesAtTickingAim. The target area is generated at once and ticks
        // from the first tick (entities — 5×5 chunks, ±40 blocks): a projectile outside the world always returns at the
        // edge of the area, not when the ticket of the projectile's own target area loads it in the background (then
        // the path would depend on the generation speed)
        BlockPos origin = site.at(h);
        ChunkPos chunk = new ChunkPos(origin);
        generateNow(level, chunk, FlightTickets.DISTANCE);
        level.getChunkSource().addRegionTicket(AIM_AREA, chunk, FlightTickets.DISTANCE, chunk);
        afterTest(h, () -> level.getChunkSource().removeRegionTicket(AIM_AREA, chunk, FlightTickets.DISTANCE, chunk));
        int x = chunk.getMiddleBlockX(), z = chunk.getMiddleBlockZ();
        int surface = Terrain.height(level, Heightmap.Types.MOTION_BLOCKING, x, z);
        Vec3 aim = new Vec3(x + 0.5, surface - 3, z + 0.5);
        // a cliff behind the target along the course (+x)
        for (int bx = x + cliff; bx <= x + cliff + 1; bx++)
            for (int bz = z - 1; bz <= z + 1; bz++)
                for (int by = surface - 4; by < surface + HILL_BEYOND; by++)
                    level.setBlockAndUpdate(new BlockPos(bx, by, bz), Blocks.STONE.defaultBlockState());
        StrikeProjectile p = make.apply(level, aim);
        UUID id = p.getUUID();
        // the first detonation at the target (16 blocks horizontally: the other tests are thousands of blocks away);
        // the chunks around the target are ready — the detonation is not postponed. A detonation farther away (on the
        // cruise missile's cliff) is not caught, and the test fails “without a detonation at the target”
        Vec3[] burst = {null};
        java.util.function.Consumer<net.neoforged.neoforge.event.level.ExplosionEvent.Start> onBlast = e -> {
            Vec3 c = e.getExplosion().center();
            if (burst[0] == null && e.getLevel() == level && Math.hypot(c.x - aim.x, c.z - aim.z) < 16) burst[0] = c;
        };
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(onBlast);
        afterTest(h, () -> net.neoforged.neoforge.common.NeoForge.EVENT_BUS.unregister(onBlast));
        VirtualFlights.launch(level, p);
        Vec3[] last = {p.position()};
        // height in the first tick in the world and in the last tick outside it: the return lifts the projectile only
        // above the relief of its own path
        double[] back = {Double.NaN, Double.NaN};
        h.onEachTick(() -> {
            StrikeProjectile f = flight(level, id);
            if (f == null) return;
            if (f.isVirtual()) back[1] = f.getY();
            else if (Double.isNaN(back[0])) back[0] = f.getY();
            last[0] = f.position();
        });
        h.succeedWhen(() -> {
            h.assertFalse(flight(level, id) != null, "projectile still flying, distance to target " + (int) last[0].distanceTo(aim));
            h.assertFalse(Double.isNaN(back[0]) || Double.isNaN(back[1]), "projectile did not arrive outside the world and did not return to the world");
            // not higher than its own path and the relief over the target with the projectile's margin — not on the
            // cliff behind the target
            h.assertTrue(back[0] <= Math.max(back[1], surface + clearance) + 2,
                    "projectile at the target lifted onto the relief behind it: y " + (int) back[0] + " (outside the world " + (int) back[1] + "), relief over the target " + surface);
            h.assertTrue(burst[0] != null, "projectile removed without a detonation at the target, last seen at " + last[0].subtract(aim));
            Vec3 off = burst[0].subtract(aim);
            h.assertTrue(Math.hypot(off.x, off.z) < BURST_NEAR_AIM && burst[0].y <= surface + 2,
                    "detonation not at the target: " + off + " from it, relief over the target " + surface + " (cliff — from " + cliff + " blocks behind the target along the course)");
        });
    }

    /**
     * An MLRS projectile in a fresh area nearby: it leaves the pod at once (without waiting in the tube) and moves
     * every tick until the detonation. The flight (~100 ticks) is no longer than the area loading: the end of the
     * flight outside the world is stretched in time, and the projectile does not freeze in the air at the target (the
     * whine does not cut off for 8–40 ticks). At game pace: without pauses background generation takes thousands of
     * ticks, and the projectile would stop at the edge; the stretch over its whole length, until the area is ready, is
     * checked by the test holding the area ({@link #rocketStretchesFlightWhileAimAreaLoads}).
     */
    @GameTest(template = "runway", timeoutTicks = 1200, batch = "rocket_fresh_near", skyAccess = true)
    public static void rocketToFreshNearAreaNeverFreezes(GameTestHelper h) {
        gameSpeed(h);
        rocketLaunchesAtOnceAndNeverFreezes(h, 250, 0);
    }

    /**
     * The target area loads longer than the flight (the test keeps it unloaded for 300 ticks at a flight of ~100): the
     * projectile leaves at once, outside the world it approaches the target ever more slowly, never stands still for a
     * tick, and when the area is ready, explodes at the target.
     */
    @GameTest(template = "runway", timeoutTicks = 1500, batch = "rocket_slow_area", skyAccess = true)
    public static void rocketStretchesFlightWhileAimAreaLoads(GameTestHelper h) {
        rocketLaunchesAtOnceAndNeverFreezes(h, 250, 300);
    }

    /**
     * The end of the stretch without a jump: the target area is ready, the projectile outside the world speeds up
     * smoothly again. The speed — the one that goes to clients in the sound's travel time ({@code velocity()}): a jump
     * in it — a step in the pitch and loudness of the whine. The step into the target itself on the last tick is not
     * counted.
     */
    @GameTest(template = "runway", timeoutTicks = 1500, batch = "rocket_stretch_end", skyAccess = true)
    public static void rocketStretchEndsWithoutSpeedStep(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        Vec3 rail = Vec3.atCenterOf(h.absolutePos(RUNWAY_TARGET)).add(0, 3, 0);
        ChunkPos aimChunk = new ChunkPos(BlockPos.containing(rail).offset(250, 0, 0));
        Vec3 aim = new Vec3(aimChunk.getMiddleBlockX() + 0.5, REFERENCE_SEA_LEVEL + 60, aimChunk.getMiddleBlockZ() + 0.5);
        RocketEntity rocket = ModEntities.ROCKET.get().create(level);
        rocket.placeInTube(rail, -90, LauncherEntity.elevation(WeaponType.ROCKET), 5, 0, new Target.Point(aim), aim, null);
        level.addFreshEntity(rocket);
        UUID id = rocket.getUUID();
        int withhold = 300;
        int[] tick = {0};
        List<String> track = new ArrayList<>();
        List<Double> speeds = new ArrayList<>();
        double[] minRate = {1};
        Vec3[] last = {rail};
        h.onEachTick(() -> {
            tick[0]++;
            StrikeProjectile p = VirtualFlights.get(level).flights().stream().filter(f -> f.getUUID().equals(id)).findFirst()
                    .orElseGet(() -> level.getEntity(id) instanceof StrikeProjectile e && !e.isRemoved() ? e : null);
            if (p != null) FlightTickets.hold(level, aimChunk, FlightTickets.DISTANCE, id, tick[0] >= withhold);
            if (tick[0] == withhold) generateNow(level, aimChunk);
            // after burnout: from then on only gravity and the stretch rate change the speed
            if (p == null || p.flightPhase() == FlightPhase.BOOST || p.flightPhase().onLauncher() || p.flightPhase() == FlightPhase.IGNITION) return;
            last[0] = p.position();
            double rate = ((RocketEntity) p).timeRate();
            minRate[0] = Math.min(minRate[0], rate);
            speeds.add(p.velocity().length());
            track.add(String.format(Locale.ROOT, "tick %d (outside the world %s, rate %.2f, to target %.0f)", tick[0], p.isVirtual(), rate, p.position().distanceTo(aim)));
        });
        h.succeedWhen(() -> {
            boolean flying = level.getEntity(id) != null && !level.getEntity(id).isRemoved()
                    || VirtualFlights.get(level).flights().stream().anyMatch(f -> f.getUUID().equals(id));
            h.assertFalse(flying, "projectile still flying");
            // detonation at the target, not the end of the area wait (AREA_WAIT_LIMIT): otherwise there would be no end
            // of the stretch
            h.assertTrue(last[0].distanceTo(aim) < 16, "projectile vanished away from the target: " + last[0].subtract(aim));
            h.assertTrue(minRate[0] < 0.5, "flight was not stretched (rate not below " + minRate[0] + ")");
            // last tick — the step into the target itself (it reaches it, rather than by a whole step length), not
            // counted
            double worst = 0;
            String where = "";
            for (int i = 1; i < speeds.size() - 1; i++) {
                double d = Math.abs(speeds.get(i) - speeds.get(i - 1));
                if (d > worst) {
                    worst = d;
                    where = String.format(Locale.ROOT, "%.2f → %.2f, %s", speeds.get(i - 1), speeds.get(i), track.get(i));
                }
            }
            Incoming.LOG.info("MLRS measurement: largest speed jump per tick {}", where);
            // the rate changes no faster than 0.05 per tick: at a speed up to ~8 blocks/tick that is up to 0.4, plus
            // gravity
            h.assertTrue(worst <= 0.6, "projectile speed jump " + where);
        });
    }

    /**
     * Far fresh area: the flight is longer than the area loading — departure at the order's tick, without stretching
     * and stops.
     */
    @GameTest(template = "runway", timeoutTicks = 1200, batch = "rocket_fresh_far", skyAccess = true)
    public static void rocketToFreshFarAreaLaunchesAtOnce(GameTestHelper h) {
        // “flight longer than the area loading” — in game time: without a pause, 1200 ticks of the limit pass faster
        // than generation
        gameSpeed(h);
        rocketLaunchesAtOnceAndNeverFreezes(h, 1750, 0);
    }

    /**
     * Test places far beyond the pad (nothing is loaded around, the projectile flies outside the world) — each test has
     * its own, a thousand blocks or more from the others: what a test left in the world (a cliff, a crater) does not
     * stand on another's path, if their pads are near. A new test far away — a new place here.
     */
    private enum FarSite {
        MISS_BELOW_GROUND(-4000, 4000),
        BOMB_RISEN_SURFACE(4000, -4000),
        ROCKET_TICKING_AIM(4000, 4000),
        ROCKET_HILL_BEYOND(-4000, -4000),
        MISSILE_HILL_BEYOND(-4000, 5200),
        DEFERRED_BLAST(5200, 0);

        private final int dx, dz;

        FarSite(int dx, int dz) {
            this.dx = dx;
            this.dz = dz;
        }

        BlockPos at(GameTestHelper h) {
            return h.absolutePos(RUNWAY_TARGET).offset(dx, 0, dz);
        }
    }

    /**
     * The target area released by the test is ready at once: the 5×5 chunks around are synchronous, the ticket is
     * already in place. The stretch is measured in ticks, and the GameTest server ticks without pauses (on CI ~2000
     * ticks per second): background generation after the limit takes thousands of ticks, and the projectile would have
     * time to stop at the edge (“stood in the air 522 ticks”). So the moment of readiness is the tick of the limit, at
     * any speed of the runner.
     */
    private static void generateNow(ServerLevel level, ChunkPos centre) {
        generateNow(level, centre, 2);
    }

    /** Chunks within {@code radius} chunks around {@code centre} — synchronously. */
    private static void generateNow(ServerLevel level, ChunkPos centre, int radius) {
        for (int dx = -radius; dx <= radius; dx++)
            for (int dz = -radius; dz <= radius; dz++) level.getChunk(centre.x + dx, centre.z + dz);
    }

    /**
     * Game pace (20 ticks per second) for the test that waits for background area loading: the mod measures the wait in
     * ticks (limit {@code AREA_WAIT_LIMIT} — 1200 ticks, a minute of play), and the GameTest server ticks without
     * pauses — on CI about 2000 ticks per second, and a minute of play passes in half a second, before generation of
     * the fresh area.
     */
    /**
     * Place of the launcher for the launch-sector checks — above the middle of the runway, high above the grid of pads:
     * the pads of the GameTest structure are placed all at once, and ahead along +Z stand walls of barriers and
     * structures of other checks, and the missile's climb after arming looks at the relief hundreds of blocks ahead
     * ({@code Airframe.reliefLookahead}): at the strip itself the check depended on which check ended up in the next
     * row (CI FV: “empty strip is blocked”). Over all of this the strip is empty for any make-up of the structure; what
     * the check places itself is from the rail, as before.
     */
    static Vec3 laneSite(GameTestHelper h) {
        return Vec3.atBottomCenterOf(h.absolutePos(new BlockPos(16, 4 + LANE_LIFT, 8)));
    }

    /**
     * How much higher than the strip the launcher of the sector checks stands, in blocks: above the walls and
     * structures of neighboring pads.
     */
    static final int LANE_LIFT = 120;

    public static void gameSpeed(GameTestHelper h) {
        long[] last = {System.nanoTime()};
        h.onEachTick(() -> {
            // parkNanos wakes earlier (chunk tasks wake the server thread): wait until the deadline in a loop
            long deadline = last[0] + 50_000_000L, left;
            while ((left = deadline - System.nanoTime()) > 0) LockSupport.parkNanos(left);
            last[0] = System.nanoTime();
        });
    }

    /**
     * @param withhold how many ticks the target area is not loaded (the test takes the projectile's ticket off, then
     * puts it back itself)
     */
    private static void rocketLaunchesAtOnceAndNeverFreezes(GameTestHelper h, int distance, int withhold) {
        ServerLevel level = h.getLevel();
        Vec3 rail = Vec3.atCenterOf(h.absolutePos(RUNWAY_TARGET)).add(0, 3, 0);
        ChunkPos aimChunk = new ChunkPos(BlockPos.containing(rail).offset(distance, 0, 0));
        Vec3 aim = new Vec3(aimChunk.getMiddleBlockX() + 0.5, REFERENCE_SEA_LEVEL + 60, aimChunk.getMiddleBlockZ() + 0.5);
        RocketEntity rocket = ModEntities.ROCKET.get().create(level);
        int ready = 5;
        rocket.placeInTube(rail, -90, LauncherEntity.elevation(WeaponType.ROCKET), ready, 0, new Target.Point(aim), aim, null);
        level.addFreshEntity(rocket);
        UUID id = rocket.getUUID();
        long orderedAt = System.nanoTime();
        int[] tick = {0};
        int[] leftTube = {-1};
        int[] eta = {0};
        int[] lastSeen = {0};
        int[] stalls = {0};
        Vec3[] last = {rocket.position()};
        boolean[] reported = {false};
        int[] areaReadyAt = {-1};
        BlockPos aimPos = BlockPos.containing(aim);
        h.onEachTick(() -> {
            tick[0]++;
            if (areaReadyAt[0] < 0 && Terrain.ready(level, aimPos) && level.isPositionEntityTicking(aimPos)) areaReadyAt[0] = tick[0];
            StrikeProjectile p = VirtualFlights.get(level).flights().stream().filter(f -> f.getUUID().equals(id)).findFirst()
                    .orElseGet(() -> level.getEntity(id) instanceof StrikeProjectile e && !e.isRemoved() ? e : null);
            // area “loads slowly”: the projectile's ticket is removed until the limit passes, then put back (the
            // projectile will remove it)
            if (withhold > 0 && p != null) FlightTickets.hold(level, aimChunk, FlightTickets.DISTANCE, id, tick[0] >= withhold);
            if (withhold > 0 && tick[0] == withhold) generateNow(level, aimChunk);
            if (p == null) return;
            lastSeen[0] = tick[0];
            boolean moved = p.position().distanceToSqr(last[0]) > 1.0e-6;
            last[0] = p.position();
            // departure — the first shift from the tube; from then on the projectile moves every tick until the
            // detonation
            if (leftTube[0] < 0) {
                if (moved) {
                    leftTube[0] = tick[0];
                    eta[0] = p.etaTicks();
                }
            } else if (!moved) {
                stalls[0]++;
            }
        });
        h.succeedWhen(() -> {
            boolean flying = level.getEntity(id) != null && !level.getEntity(id).isRemoved()
                    || VirtualFlights.get(level).flights().stream().anyMatch(f -> f.getUUID().equals(id));
            h.assertFalse(flying, "projectile still flying, distance to target " + (int) last[0].distanceTo(aim));
            // measurement for the PR: departure delay and stretch of the flight outside the world (ticks beyond the
            // calculated flight time)
            if (!reported[0]) Incoming.LOG.info("MLRS measurement {} blocks: departure at tick {} (ready at {}), flight {} ticks against calculated {}, stretch {}, target area ready at tick {}, {} ms",
                    distance, leftTube[0], ready + WeaponSpec.ROCKET.airframe().ballistic().rocket().ignitionTicks(), lastSeen[0] - leftTube[0], eta[0],
                    lastSeen[0] - leftTube[0] - eta[0], areaReadyAt[0],
                    (System.nanoTime() - orderedAt) / 1_000_000);
            reported[0] = true;
            h.assertTrue(leftTube[0] > 0 && leftTube[0] <= ready + WeaponSpec.ROCKET.airframe().ballistic().rocket().ignitionTicks() + 2,
                    "projectile left the pod at tick " + leftTube[0] + ", but ready at " + (ready + WeaponSpec.ROCKET.airframe().ballistic().rocket().ignitionTicks()));
            h.assertTrue(stalls[0] == 0, "projectile stood in the air " + stalls[0] + " ticks");
            if (withhold > 0) h.assertTrue(areaReadyAt[0] >= withhold, "target area loaded before the test released it");
            h.assertTrue(last[0].distanceTo(aim) < 16, "projectile vanished away from the target: " + last[0].subtract(aim));
        });
    }

    /**
     * Map hint under spam: two players click a new place every tick for 100 ticks. Each of them, in any tick, has at
     * most one hint area (and at most one region ticket), a new one no more often than once per {@link
     * PickHints#MIN_INTERVAL} ticks, the last click is taken (deferred), and after {@link PickHints#LIFESPAN} ticks the
     * area is released on its own — no region ticket and no load ticket remains.
     */
    @GameTest(template = "runway", timeoutTicks = 1000, batch = "pick_spam", skyAccess = true)
    public static void mapPickSpamHoldsOneAreaPerPlayer(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        BlockPos origin = h.absolutePos(RUNWAY_TARGET);
        UUID[] who = {UUID.randomUUID(), UUID.randomUUID()};
        PickHints.Slot[] slots = {new PickHints.Slot(), new PickHints.Slot()};
        int[] tick = {0};
        int[] taken = {0, 0};
        ChunkPos[] lastHeld = {null, null};
        ChunkPos[] lastClick = {null, null};
        int spam = 100;
        h.onEachTick(() -> {
            tick[0]++;
            for (int i = 0; i < 2; i++) {
                if (tick[0] <= spam) {
                    // each click — another fresh area (step 500 blocks), for the second player in the other direction
                    double x = origin.getX() + (i == 0 ? 1 : -1) * 500.0 * tick[0], z = origin.getZ() + 3000 * i;
                    PickHints.pick(level, who[i], slots[i], x, z);
                    lastClick[i] = new ChunkPos(BlockPos.containing(x, 0, z));
                }
                PickHints.tick(level, who[i], slots[i]);
                if (slots[i].held() != null && !slots[i].held().equals(lastHeld[i])) taken[i]++;
                lastHeld[i] = slots[i].held();
                int held = pickTickets(level, who[i]), areas = PickHints.areas(level, who[i]);
                if (held > 1 || areas > 1) throw new GameTestAssertException("player " + i + " hint areas: " + areas + ", tickets " + held + " at tick " + tick[0]);
            }
        });
        h.runAtTickTime(spam + PickHints.MIN_INTERVAL + 1, () -> {
            for (int i = 0; i < 2; i++) {
                h.assertTrue(lastClick[i].equals(slots[i].held()), "last click not taken: holds " + slots[i].held() + ", click " + lastClick[i]);
                int limit = spam / PickHints.MIN_INTERVAL + 2;
                h.assertTrue(taken[i] <= limit, "new areas " + taken[i] + " in " + spam + " ticks, limit " + limit);
                h.assertTrue(PickHints.areas(level, who[i]) == 1, "last click's area not taken");
            }
        });
        h.runAtTickTime(spam + PickHints.MIN_INTERVAL + PickHints.LIFESPAN + 20, () -> {
            for (int i = 0; i < 2; i++) {
                h.assertTrue(PickHints.areas(level, who[i]) == 0 && pickTickets(level, who[i]) == 0 && loadTickets(level, who[i]) == 0,
                        "hint area not released in " + PickHints.LIFESPAN + " ticks");
            }
            h.succeed();
        });
    }

    /** Map hint tickets with key {@code who} in the whole world (from the vanilla ticket queue). */
    private static int pickTickets(ServerLevel level, UUID who) {
        return TicketProbe.count(level, PickHints::isPickTicket, who);
    }

    /** Area load tickets ({@code AreaLoader}) with key {@code who}. */
    private static int loadTickets(ServerLevel level, UUID who) {
        return TicketProbe.count(level, "incoming_area_load", who);
    }

    @GameTest(template = "runway", timeoutTicks = 2400, batch = "salvo", skyAccess = true)
    public static void salvoFiresEveryShot(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        Vec3 c = top(h, RUNWAY_TARGET);
        SalvoData.start(level, WeaponType.DRONE, 3, 6, new Target.Point(c), c, 0, null, Loadout.Nuke.DEFAULT, Waypoints.NONE, null, false);
        h.succeedWhen(() -> {
            h.assertTrue(SalvoData.get(level).size() == 0, "salvo has not ended yet");
            h.assertTrue(VirtualFlights.get(level).flights().isEmpty(), "still flying outside the world: " + VirtualFlights.get(level).flights().size());
            List<StrikeProjectile> flying = level.getEntitiesOfClass(StrikeProjectile.class, h.getBounds().inflate(128));
            h.assertTrue(flying.isEmpty(), "still flying: " + flying.stream().map(p -> p.flightPhase() + " " + h.relativeVec(p.position())
                    + " age " + p.age() + " distance to target " + (int) p.position().distanceTo(p.aimPoint())).toList());
        });
    }

    /**
     * No nuclear salvos: a console order with a nuclear warhead on the missile is one missile, not a salvo (as for a
     * player), and the projectiles of a salvo do not carry a nuclear warhead, even if it got into the salvo.
     */
    @GameTest(template = "range", timeoutTicks = 20, batch = "salvo_nuke", skyAccess = true)
    public static void salvoNeverCarriesNuke(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        // far beyond the pad: nobody reaches it before the end of the test
        Vec3 far = Vec3.atCenterOf(h.absolutePos(RANGE_CENTER)).add(0, 0, 3000);
        Loadout.Nuke nuke = new Loadout.Nuke(15, true, true);
        ServerActions.Aim aim = new ServerActions.Aim(new Target.Point(far), far, null);
        // own projectiles — by the target point (other projectiles may be flying in the test world too)
        java.util.function.Predicate<StrikeProjectile> ours = p -> p.aimPoint().distanceTo(far) < 40;
        h.assertTrue(ServerActions.dispatch(level, "GameTest", 0, WeaponType.MISSILE, 5, 20, aim, nuke, Waypoints.NONE, null), "launch from console did not pass");
        h.assertTrue(SalvoData.get(level).size() == 0, "nuclear order became a salvo");
        boolean carriers = ua.zentix.incoming.IncomingConfig.SERVER.carrierNukes.get();
        List<StrikeProjectile> single = VirtualFlights.get(level).flights().stream().filter(ours).toList();
        h.assertTrue(single.size() == 1 && single.getFirst().isNuclear() == carriers,
                "not a single missile with a nuclear warhead: " + single.stream().map(StrikeProjectile::isNuclear).toList());
        VirtualFlights.get(level).clear(level, ours);
        SalvoData.start(level, WeaponType.MISSILE, 3, 20, new Target.Point(far), far, 0, null, nuke, Waypoints.NONE, null, false);
        h.runAfterDelay(3, () -> {
            List<StrikeProjectile> fired = VirtualFlights.get(level).flights().stream().filter(ours).toList();
            h.assertTrue(!fired.isEmpty() && fired.stream().noneMatch(StrikeProjectile::isNuclear),
                    "salvo projectiles: " + fired.stream().map(StrikeProjectile::isNuclear).toList());
            SalvoData.get(level).cancel(level, null, List.of());
            VirtualFlights.get(level).clear(level, ours);
            h.succeed();
        });
    }

    /**
     * The player's strike limit ({@code max_active_per_player}) counts everything in work: the salvo's unfired
     * projectiles, projectiles in the world and outside it; others' — no. A fired salvo projectile moves from one to
     * the other, the total is the same.
     */
    @GameTest(template = "range", timeoutTicks = 20, batch = "active_count", skyAccess = true)
    public static void activeShotsCountEverythingOfOwner(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        java.util.UUID owner = java.util.UUID.randomUUID(), other = java.util.UUID.randomUUID();
        Vec3 far = Vec3.atCenterOf(h.absolutePos(RANGE_CENTER)).add(0, 0, 3000);
        Vec3 rail = Vec3.atCenterOf(h.absolutePos(RANGE_CENTER)).add(0, 1, 0);
        SalvoData.start(level, WeaponType.DRONE, 5, 10, new Target.Point(far), far, 0, owner, Loadout.Nuke.DEFAULT, Waypoints.NONE, null, false);
        CruiseMissileEntity onRail = ModEntities.CRUISE_MISSILE.get().create(level);
        onRail.placeOnLauncher(rail, 0, 40, 1000, 0, new Target.Point(far), far, owner);
        onRail.setRoute(Route.direct());
        level.addFreshEntity(onRail);
        for (java.util.UUID id : List.of(owner, other)) {
            CruiseMissileEntity m = ModEntities.CRUISE_MISSILE.get().create(level);
            m.launch(far.add(0, 60, 2000), new Target.Point(far), far, id);
            m.setRoute(Route.direct());
            VirtualFlights.launch(level, m);
        }
        h.assertTrue(StrikeWorld.active(level.getServer(), owner) == 7, "in work: " + StrikeWorld.active(level.getServer(), owner));
        h.runAfterDelay(3, () -> {
            h.assertTrue(SalvoData.get(level).remaining(owner) < 5, "salvo did not fire a single projectile");
            h.assertTrue(StrikeWorld.active(level.getServer(), owner) == 7, "after the launch from the salvo: " + StrikeWorld.active(level.getServer(), owner));
            h.assertTrue(StrikeWorld.active(level.getServer(), other) == 1, "for the other player: " + StrikeWorld.active(level.getServer(), other));
            SalvoData.get(level).cancel(level, null, List.of());
            VirtualFlights.get(level).clear(level, p -> owner.equals(p.ownerId()) || other.equals(p.ownerId()));
            onRail.discard();
            h.succeed();
        });
    }

    /**
     * A salvo at an entity that died: the remainder hits the place of death, not a new entity with the same UUID (the
     * player respawns as a new {@code ServerPlayer} with the same UUID — otherwise the salvo would switch to the
     * respawn place).
     */
    @GameTest(template = "range", timeoutTicks = 120, batch = "salvo_target_died", skyAccess = true)
    public static void salvoKeepsLastPointOfDeadTarget(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        java.util.UUID owner = java.util.UUID.randomUUID();
        Vec3 at = Vec3.atBottomCenterOf(h.absolutePos(RANGE_CENTER));
        Pig pig = EntityType.PIG.create(level);
        pig.moveTo(at.x, at.y, at.z, 0, 0);
        pig.setNoAi(true);
        level.addFreshEntity(pig);
        Target.OfEntity target = new Target.OfEntity(pig.getUUID(), Vec3.ZERO);
        SalvoData.start(level, WeaponType.DRONE, 30, 0, target, at, 0, owner, Loadout.Nuke.DEFAULT, Waypoints.NONE, null, false);
        Runnable cleanup = () -> {
            SalvoData.get(level).cancel(level, null, List.of());
            VirtualFlights.get(level).clear(level, p -> owner.equals(p.ownerId()));
        };
        h.runAfterDelay(3, () -> {
            h.assertTrue(SalvoData.get(level).centers(owner).equals(List.of(target)), "salvo does not follow the live target: " + SalvoData.get(level).centers(owner));
            pig.kill();
        });
        h.runAfterDelay(30, () -> {
            // “respawn”: a new entity with the same UUID to the side
            Pig again = EntityType.PIG.create(level);
            again.setUUID(pig.getUUID());
            again.moveTo(at.x + 40, at.y, at.z, 0, 0);
            again.setNoAi(true);
            h.assertTrue(level.getEntity(pig.getUUID()) == null && level.addFreshEntity(again), "second entity with the same UUID did not enter the world");
        });
        // pause of the Shahed salvo 20–40 ticks: by the 90th tick after the death (the 3rd tick) at least one remainder
        // projectile is fired
        h.runAfterDelay(90, () -> {
            List<Target> centers = SalvoData.get(level).centers(owner);
            // remainder projectiles — with the point target (before the death — with the entity target); in and outside
            // the world
            List<StrikeProjectile> mine = new ArrayList<>(VirtualFlights.get(level).flights());
            mine.addAll(level.getEntities(EntityTypeTest.forClass(StrikeProjectile.class), p -> true));
            List<Vec3> aims = mine.stream().filter(p -> owner.equals(p.ownerId()) && p.target() instanceof Target.Point)
                    .map(p -> ((Target.Point) p.target()).pos()).toList();
            cleanup.run();
            level.getEntities(EntityType.PIG, h.getBounds().inflate(64), p -> p.getUUID().equals(pig.getUUID())).forEach(Entity::discard);
            h.assertTrue(centers.size() == 1 && centers.getFirst() instanceof Target.Point p && p.pos().distanceTo(at) < 1,
                    "salvo after the target's death does not go to the place of death: " + centers);
            h.assertTrue(!aims.isEmpty(), "after the target's death, not a single remainder projectile was fired");
            // the Shahed has its own miss (spec sheet): 5 SD — with a margin, and the respawn place is 40 blocks away
            double miss = 2 + 5 * WeaponType.DRONE.spec().route().error();
            h.assertTrue(aims.stream().allMatch(a -> a.distanceTo(at) < miss),
                    "remainder projectiles fly not to the place of death " + at + ": " + aims);
            h.succeed();
        });
    }

    /**
     * The blast wave knocks out glass and leaves in its cube, by chunk sections: each such block inside (and on
     * section boundaries), none outside, and nothing else.
     */
    @GameTest(template = "range", timeoutTicks = 20, skyAccess = true)
    public static void shatterBreaksGlassAndLeavesInBox(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        BlockPos c = h.absolutePos(RANGE_CENTER.above(8));
        // at the corners and middles of the 2r+1 cube — points in different sections; outside — a block beyond the edge
        int r = 9;
        List<BlockPos> inside = new ArrayList<>();
        for (int dx : new int[]{-r, 0, r}) {
            for (int dy : new int[]{-3, 0, 5}) {
                for (int dz : new int[]{-r, 0, r}) {
                    if (dx != 0 || dy != 0 || dz != 0) inside.add(c.offset(dx, dy, dz));
                }
            }
        }
        List<BlockPos> outside = List.of(c.offset(r + 1, 0, 0), c.offset(0, 6, 0), c.offset(0, -4, -r - 1));
        for (int i = 0; i < inside.size(); i++) level.setBlock(inside.get(i), (i % 2 == 0 ? Blocks.GLASS : Blocks.GLASS_PANE).defaultBlockState(), 3);
        for (BlockPos p : outside) level.setBlock(p, Blocks.GLASS.defaultBlockState(), 3);
        BlockPos leaves = c.offset(2, 1, -2), stone = c.offset(-2, 1, 2);
        level.setBlock(leaves, Blocks.OAK_LEAVES.defaultBlockState(), 3);
        level.setBlock(stone, Blocks.STONE.defaultBlockState(), 3);
        Vec3 centre = Vec3.atCenterOf(c);
        int glass = Warheads.shatter(level, centre, r, 3, 5, ua.zentix.incoming.registry.ModTags.SHATTERS);
        h.assertTrue(glass == inside.size(), "glass knocked out " + glass + " of " + inside.size());
        for (BlockPos p : inside) h.assertTrue(level.getBlockState(p).isAir(), "glass remained at " + h.relativePos(p));
        for (BlockPos p : outside) h.assertTrue(level.getBlockState(p).is(Blocks.GLASS), "glass knocked out beyond the edge at " + h.relativePos(p));
        h.assertTrue(level.getBlockState(leaves).is(Blocks.OAK_LEAVES) && level.getBlockState(stone).is(Blocks.STONE), "the wave at the glass touched something other than glass");
        h.assertTrue(Warheads.shatter(level, centre, r, 3, 5, net.minecraft.tags.BlockTags.LEAVES) == 1 && level.getBlockState(leaves).isAir(),
                "leaves not knocked out");
        h.assertTrue(level.getBlockState(stone).is(Blocks.STONE), "the wave at the leaves touched stone");
        h.succeed();
    }

    /**
     * The ground-explosion wave knocks out glass by pressure, in a ball in all directions: colored, tinted and panes —
     * as transparent. Closer than the range where pressure exceeds the glass threshold at any spread: all that air from
     * the street reaches (and at the bottom of a pit below the blast point, which the box did not reach), glass in the
     * ground with no air beside it — intact, beyond the ball — none, and nothing but glass.
     */
    @GameTest(template = "range", timeoutTicks = 20, skyAccess = true)
    public static void shockwaveBreaksGlassByPressure(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        BlockPos c = h.absolutePos(RANGE_CENTER.above(8));
        Vec3 centre = Vec3.atCenterOf(c);
        double kg = 0.3;
        // ~11.8 and ~15.3 blocks: the ball is held in the pad's chunks, whose neighbors are also on it
        double sure = BlastModel.rangeForSurfaceOverpressure(BlastModel.kpa(BlockResponse.FRAGILE_PSI * BlockResponse.JITTER_MAX), kg);
        double reach = Warheads.glassReach(kg);
        h.assertTrue(sure > 11 && reach < 16, "wave ranges " + sure + " and " + reach + " not on the pad");
        List<BlockState> kinds = List.of(Blocks.RED_STAINED_GLASS.defaultBlockState(), Blocks.LIGHT_BLUE_STAINED_GLASS_PANE.defaultBlockState(),
                Blocks.TINTED_GLASS.defaultBlockState(), Blocks.GLASS.defaultBlockState(), Blocks.BLACK_STAINED_GLASS.defaultBlockState(),
                Blocks.GLASS_PANE.defaultBlockState());
        List<BlockPos> inside = List.of(c.below(10), c.above(10), c.east(10), c.offset(-7, 0, 7), c.offset(0, -7, -7), c.offset(6, 6, 6));
        int out = Mth.ceil(reach) + 1;
        List<BlockPos> outside = List.of(c.west(out), c.north(out), c.below(out), c.offset(12, 0, -12));
        // below the blast point — in the ground at the bottom of the pit up to the sky; nearby, 3 blocks away — the
        // same glass in the ground without a pit
        BlockPos sealed = c.offset(3, -10, 0);
        h.assertTrue(Math.sqrt(centre.distanceToSqr(Vec3.atCenterOf(sealed))) < sure && !level.getBlockState(sealed.above()).isAir(),
                "glass in the ground not nearer than the true range or not under the ground");
        for (BlockPos p = c.below(9); !level.getBlockState(p).isAir(); p = p.above()) level.setBlock(p, Blocks.AIR.defaultBlockState(), 3);
        level.setBlock(sealed, Blocks.RED_STAINED_GLASS.defaultBlockState(), 3);
        for (int i = 0; i < inside.size(); i++) {
            h.assertTrue(Math.sqrt(centre.distanceToSqr(Vec3.atCenterOf(inside.get(i)))) < sure, "glass " + i + " not nearer than the true range");
            level.setBlock(inside.get(i), kinds.get(i % kinds.size()), 3);
        }
        for (int i = 0; i < outside.size(); i++) {
            h.assertTrue(Math.sqrt(centre.distanceToSqr(Vec3.atCenterOf(outside.get(i)))) > reach, "glass " + i + " not farther than the ball");
            level.setBlock(outside.get(i), kinds.get(i % kinds.size()), 3);
        }
        BlockPos stone = c.offset(2, 1, -2);
        level.setBlock(stone, Blocks.STONE.defaultBlockState(), 3);
        int glass = Warheads.shatterGlass(level, centre, kg);
        h.assertTrue(glass == inside.size(), "glass knocked out " + glass + " of " + inside.size());
        for (BlockPos p : inside) h.assertTrue(level.getBlockState(p).isAir(), "glass remained at " + h.relativePos(p));
        for (int i = 0; i < outside.size(); i++) {
            h.assertTrue(level.getBlockState(outside.get(i)) == kinds.get(i % kinds.size()), "glass knocked out beyond the ball at " + h.relativePos(outside.get(i)));
        }
        h.assertTrue(level.getBlockState(stone).is(Blocks.STONE), "the wave at the glass touched stone");
        h.assertTrue(level.getBlockState(sealed).is(Blocks.RED_STAINED_GLASS), "glass knocked out in the ground, which air does not reach");
        h.succeed();
    }

    /**
     * Glass walls 14×14 one block from the blast: in the sections there are hundreds of panes, more than one batch of
     * work — batches continue from where the previous one ended, and knock out everything: none twice and none missed.
     */
    @GameTest(template = "range", timeoutTicks = 20, skyAccess = true)
    public static void shockwaveBreaksGlassCubeInPortions(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        BlockPos c = h.absolutePos(RANGE_CENTER.above(10));
        Vec3 centre = Vec3.atCenterOf(c);
        double kg = 2;
        int half = 7;
        // the far corner of the walls — ~12 blocks, nearer than the true range (~22); panes 7 × 14 × 14
        h.assertTrue(BlastModel.rangeForSurfaceOverpressure(BlastModel.kpa(BlockResponse.FRAGILE_PSI * BlockResponse.JITTER_MAX), kg) > half * Math.sqrt(3) + 1,
                "walls not nearer than the true range");
        // walls across X, one block away: each pane has air from the street at its side
        int placed = 0;
        for (BlockPos p : BlockPos.betweenClosed(c.offset(-half, -half, -half), c.offset(half - 1, half - 1, half - 1))) {
            if (((p.getX() - c.getX()) & 1) != 0) continue;
            level.setBlock(p, ((p.getY() + p.getZ()) % 2 == 0 ? Blocks.RED_STAINED_GLASS : Blocks.LIGHT_BLUE_STAINED_GLASS).defaultBlockState(), 2);
            placed++;
        }
        int glass = Warheads.shatterGlass(level, centre, kg);
        h.assertTrue(glass == placed, "glass knocked out " + glass + " of " + placed);
        for (BlockPos p : BlockPos.betweenClosed(c.offset(-half, -half, -half), c.offset(half - 1, half - 1, half - 1))) {
            h.assertTrue(level.getBlockState(p).isAir(), "glass remained at " + h.relativePos(p));
        }
        h.succeed();
    }

    /**
     * The ringing of glass comes from the knocked-out windows, not from the blast point (BLASTSEQ D13): two windows on
     * opposite sides, 10 blocks apart, each from its own place, volume is √ of the pane count in the batch, and there
     * is no sound at the blast point itself.
     */
    @GameTest(template = "range", timeoutTicks = 20, batch = "glass_sound", skyAccess = true)
    public static void glassSoundsFromItsWindow(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        BlockPos c = h.absolutePos(RANGE_CENTER.above(8));
        Vec3 centre = Vec3.atCenterOf(c);
        // a 1×3 window to the east and one pane to the west — in different sections
        List<BlockPos> east = List.of(c.offset(10, -1, 0), c.offset(10, 0, 0), c.offset(10, 1, 0)), west = List.of(c.offset(-10, 0, 0));
        for (BlockPos p : east) level.setBlock(p, Blocks.GLASS.defaultBlockState(), 3);
        for (BlockPos p : west) level.setBlock(p, Blocks.GLASS_PANE.defaultBlockState(), 3);
        List<net.neoforged.neoforge.event.PlayLevelSoundEvent.AtPosition> heard = new ArrayList<>();
        java.util.function.Consumer<net.neoforged.neoforge.event.PlayLevelSoundEvent.AtPosition> ear = e -> {
            if (e.getLevel() == level && e.getPosition().distanceTo(centre) < 30) heard.add(e);
        };
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(ear);
        try {
            int glass = Warheads.shatterGlass(level, centre, 0.3);
            h.assertTrue(glass == 4, "glass knocked out " + glass + " of 4");
        } finally {
            net.neoforged.neoforge.common.NeoForge.EVENT_BUS.unregister(ear);
        }
        h.assertTrue(heard.size() == 2, "glass sounds " + heard.size() + " instead of two windows");
        for (var e : heard) {
            h.assertTrue(e.getPosition().distanceTo(centre) > 9, "glass sounds at the blast place: " + e.getPosition());
            boolean three = e.getPosition().x > centre.x;
            Vec3 window = three ? Vec3.atCenterOf(c.offset(10, 0, 0)) : Vec3.atCenterOf(c.offset(-10, 0, 0));
            h.assertTrue(e.getPosition().distanceTo(window) < 0.01, "sound not from the middle of the knocked-out window: " + e.getPosition() + " instead of " + window);
            h.assertTrue(Math.abs(e.getNewVolume() - Math.sqrt(three ? 3 : 1)) < 1e-4, "volume " + e.getNewVolume() + " for a window of " + (three ? 3 : 1));
        }
        h.succeed();
    }

    @GameTest(template = "range", timeoutTicks = 20)
    public static void pickerSeesBlockAndEntity(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        ArmorStand viewer = h.spawn(EntityType.ARMOR_STAND, new BlockPos(32, 12, 4));
        Vec3 eye = Vec3.atCenterOf(h.absolutePos(new BlockPos(32, 14, 4)));
        Vec3 down = Vec3.atCenterOf(h.absolutePos(new BlockPos(32, 11, 20))).subtract(eye).normalize();

        TargetPicker.Pick ground = TargetPicker.pick(level, viewer, eye, down, 200);
        h.assertTrue(ground != null && ground.kind() == TargetPicker.Kind.BLOCK, "must see the ground: " + (ground == null ? null : ground.kind()));

        Cow cow = h.spawn(EntityType.COW, new BlockPos(32, 12, 12));
        cow.setNoAi(true);
        Vec3 atCow = cow.getBoundingBox().getCenter().subtract(eye).normalize();
        TargetPicker.Pick pick = TargetPicker.pick(level, viewer, eye, atCow, 200);
        h.assertTrue(pick != null && pick.entity() == cow, "must see the cow, but sees " + (pick == null ? null : pick.kind() + " " + pick.label().getString() + " " + pick.point()) + ", cow " + cow.getBoundingBox());
        h.assertTrue(pick.target() instanceof Target.OfEntity, "target is an entity");
        h.succeed();
    }

    @GameTest(template = "pad", timeoutTicks = 5)
    public static void dataSurvivesSaving(GameTestHelper h) {
        Loadout l = new Loadout(WeaponType.BUNKER, 7, 33, TargetMode.PLAYER, "Steve", new Loadout.Nuke(100, false, true));
        h.assertTrue(l.nuclear(), "B-2 with a nuclear warhead");
        Loadout back = Loadout.CODEC.parse(NbtOps.INSTANCE, Loadout.CODEC.encodeStart(NbtOps.INSTANCE, l).getOrThrow()).getOrThrow();
        h.assertTrue(l.equals(back), "console did not survive saving: " + back);

        Target t = new Target.OfSubLevel(new Vec3(1.5, 2.5, 3.5));
        Target tb = Target.CODEC.parse(NbtOps.INSTANCE, Target.CODEC.encodeStart(NbtOps.INSTANCE, t).getOrThrow()).getOrThrow();
        h.assertTrue(t.equals(tb), "target did not survive saving: " + tb);
        Target g = new Target.Ground(new Vec3(-1234.5, 70, 987.25));
        Target gb = Target.CODEC.parse(NbtOps.INSTANCE, Target.CODEC.encodeStart(NbtOps.INSTANCE, g).getOrThrow()).getOrThrow();
        h.assertTrue(g.equals(gb), "map place did not survive saving: " + gb);

        Loadout huge = new Loadout(WeaponType.DRONE, 10_000, -5, TargetMode.LOOK, "x".repeat(40), new Loadout.Nuke(0, true));
        h.assertTrue(huge.count() == Loadout.MAX_COUNT && huge.spread() == 0 && huge.player().length() == 16, "clamping of values");
        h.succeed();
    }

    /**
     * Sable craft split wrapper is in place (CI runs GameTest with Sable): otherwise splitting drops the world tick.
     */
    @GameTest(template = "pad", timeoutTicks = 5)
    public static void sableSplitGuardApplied(GameTestHelper h) throws ClassNotFoundException {
        if (!ModList.get().isLoaded("sable")) {
            h.succeed();
            return;
        }
        Class<?> heat = Class.forName("dev.ryanhcode.sable.sublevel.plot.heat.SubLevelHeatMapManager");
        h.assertTrue(SplitGuard.class.isAssignableFrom(heat), "SubLevelSplitGuardMixin not applied to Sable");
        // the interface merges in even when the wrapper is not in place (require = 0): we check the wrapper itself
        h.assertTrue(java.util.Arrays.stream(heat.getDeclaredMethods()).anyMatch(m -> m.getName().contains("guardSplit")),
                "split wrapper not in place: " + java.util.Arrays.stream(heat.getDeclaredMethods()).map(java.lang.reflect.Method::getName).toList());
        h.assertTrue(SplitGuard.isRemovedPlot(new RuntimeException(SplitGuard.REMOVED_PLOT)), "recognizes a Sable failure");
        h.assertFalse(SplitGuard.isRemovedPlot(new RuntimeException("other")), "does not swallow other exceptions");
        h.succeed();
    }

    @GameTest(template = "range", timeoutTicks = 200, batch = "debris", skyAccess = true)
    public static void debrisLandsAsBlocks(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        Vec3 at = Vec3.atCenterOf(h.absolutePos(new BlockPos(32, 20, 32)));
        DebrisEntity d = DebrisEntity.create(level, at, Blocks.COBBLESTONE.defaultBlockState(), false, true, new Vec3(0.2, 0.5, 0));
        level.addFreshEntity(d);
        h.succeedWhen(() -> {
            h.assertTrue(d.isRemoved(), "debris still flying");
            boolean found = false;
            for (BlockPos p : BlockPos.betweenClosed(new BlockPos(28, 12, 28), new BlockPos(44, 13, 36))) {
                if (h.getBlockState(p).is(Blocks.COBBLESTONE)) found = true;
            }
            h.assertTrue(found, "debris did not land as a block");
        });
    }

    /**
     * Debris that flew beyond the ticking chunks crumbles instead of freezing in the air: the pad is held by forced
     * loaded chunks (entities tick only in them); there is nothing at +Z of it — the chunk past the edge does not tick
     * entities.
     */
    @GameTest(template = "range", timeoutTicks = 100, batch = "debris", skyAccess = true)
    public static void debrisCrumblesBeyondTickingChunks(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        // above the wall of barriers (pad height 40), toward +Z — beyond the pad's edge
        Vec3 at = Vec3.atCenterOf(h.absolutePos(new BlockPos(32, 45, 58)));
        DebrisEntity d = DebrisEntity.create(level, at, Blocks.COBBLESTONE.defaultBlockState(), false, false, new Vec3(0, 0.3, 1.5));
        level.addFreshEntity(d);
        h.succeedWhen(() -> {
            h.assertTrue(d.isRemoved(), "debris hangs at the edge of the ticking chunks: " + d.blockPosition());
            h.assertTrue(d.getRemovalReason() == Entity.RemovalReason.DISCARDED, "debris removed by something other than itself: " + d.getRemovalReason());
            h.assertFalse(level.isPositionEntityTicking(d.blockPosition()), "debris crumbled in a ticking chunk: " + d.blockPosition());
        });
    }

    /** Debris and boosters live for seconds: they are not written to the world (chunk unload, save). */
    @GameTest(template = "pad", timeoutTicks = 5, batch = "debris")
    public static void shortLivedEffectsNotSaved(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        DebrisEntity d = DebrisEntity.create(level, Vec3.atCenterOf(h.absolutePos(BlockPos.ZERO)), Blocks.GRAVEL.defaultBlockState(), false, false, Vec3.ZERO);
        h.assertFalse(d.save(new CompoundTag()), "debris is saved to the world");
        h.assertFalse(ModEntities.SPENT_BOOSTER.get().create(level).save(new CompoundTag()), "booster is saved to the world");
        h.succeed();
    }
}
