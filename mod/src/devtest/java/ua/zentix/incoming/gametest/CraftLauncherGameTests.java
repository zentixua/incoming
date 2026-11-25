package ua.zentix.incoming.gametest;

import dev.ryanhcode.sable.companion.SubLevelAccess;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import org.jetbrains.annotations.Nullable;
import dev.ryanhcode.sable.api.physics.PhysicsPipeline;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import org.joml.Quaterniond;
import org.joml.Vector3d;
import org.joml.Vector3dc;
import ua.zentix.incoming.Incoming;
import ua.zentix.incoming.compat.SubLevels;
import ua.zentix.incoming.entity.FlightPhase;
import ua.zentix.incoming.entity.LauncherMount;
import ua.zentix.incoming.entity.flight.ProximityFuse;
import ua.zentix.incoming.entity.Rails;
import ua.zentix.incoming.entity.StrikeProjectile;
import ua.zentix.incoming.guidance.FlightController;
import ua.zentix.incoming.launcher.FixedLauncherBlockEntity;
import ua.zentix.incoming.launcher.LauncherOrders;
import ua.zentix.incoming.launcher.Mission;
import ua.zentix.incoming.registry.ModBlocks;
import ua.zentix.incoming.strike.StrikeWorld;
import ua.zentix.incoming.strike.VirtualFlights;
import ua.zentix.incoming.strike.WeaponType;
import ua.zentix.incoming.strike.Waypoints;
import ua.zentix.incoming.target.Target;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Fixed launcher on an assembled Create Aeronautics craft (Sable sub-level): its block sits in the craft's plot, and it
 * fires from where the craft carries it. Launch position, heading, the ride on the rail while the craft turns and moves,
 * and the launch sector against the craft's own hull and against world blocks. Without Sable there is no craft: the
 * tests pass empty.
 * <p>
 * Deck: 7×7 planks on the "runway" ground, launcher in its middle on top; the strip leaves the launch path clear past
 * arming distance along +Z (the test area's barrier walls stand 16 blocks to the sides). After assembly the test holds
 * the craft 1 block above the ground at a set pose ({@link Hold}), and turns and moves it through that pose.
 */
@GameTestHolder(Incoming.MOD_ID)
@PrefixGameTestTemplate(false)
public final class CraftLauncherGameTests {
    /** Launcher on the deck: "runway" ground layers y 0–2, deck at y 3; launches along the strip (+Z, 200 blocks ahead). */
    private static final BlockPos LAUNCHER = new BlockPos(16, 4, 40);
    private static final int DECK = 3;

    private CraftLauncherGameTests() {}

    /**
     * Lancet on a craft turned 90° by teleport: the launcher is in the plot grid, yet its position is the craft's place in
     * the world, its world heading is the turntable's own heading turned with the craft, and a signal launches. The
     * catapult faces the target in the world (rack aimed at it), the Lancet stands on its rail's world point with the
     * rail's world heading and elevation. The craft then moves 3 blocks and turns 30° more while the Lancet waits: it
     * follows the rail. After launch it flies from there into the world, along the rail heading.
     */
    @GameTest(template = "runway", timeoutTicks = 400, batch = "craft_launcher_fire", skyAccess = true)
    public static void firesFromCraftPose(GameTestHelper h) {
        if (!sable()) {
            h.succeed();
            return;
        }
        ServerLevel level = h.getLevel();
        FixedLauncherBlockEntity ground = deck(h, null);
        Vec3 far = Vec3.atBottomCenterOf(h.absolutePos(LAUNCHER)).add(0, -1, 1000);
        Vec3[] site = new Vec3[1];
        ground.setMission(new Mission(WeaponType.LOITER, 1, 0, new Target.Point(far), far, Waypoints.NONE));
        h.assertTrue(ground.load(new ItemStack(WeaponType.LOITER.spec().munition().item().get(), 2)).isEmpty(), "Lancets did not fit");
        FixedLauncherBlockEntity[] be = new FixedLauncherBlockEntity[1];
        Hold hold = new Hold(h);
        SubLevelAccess[] craft = hold.craft;
        StrikeProjectile[] lancet = new StrikeProjectile[1];
        float[] before = new float[1];
        Vec3[] release = new Vec3[1];
        h.startSequence()
                .thenExecute(() -> assemble(h, 0))
                .thenWaitUntil(() -> be[0] = carried(h, craft))
                .thenExecute(hold::start)
                .thenWaitUntil(hold.reached(h))
                .thenExecute(() -> {
                    h.assertTrue(SubLevels.inPlotGrid(level, new ChunkPos(be[0].getBlockPos())), "launcher block is not in the plot grid");
                    h.assertTrue(be[0].carried(), "launcher on the craft is not carried");
                    // the deck is centred on the launcher: the craft's world bounds centre stands over or under it
                    Vec3 centre = SubLevels.center(craft[0]);
                    h.assertTrue(horizontal(be[0].position(), centre) < 0.05 && Math.abs(be[0].position().y - centre.y) < 1.5,
                            "launcher at " + be[0].position() + ", its craft's centre " + centre);
                    site[0] = be[0].position();
                    before[0] = be[0].yaw();
                    hold.yaw = 90;
                })
                .thenWaitUntil(() -> h.assertTrue(Math.abs(Math.abs(Mth.wrapDegrees(be[0].yaw() - before[0])) - 90) < 2,
                        "craft not turned: heading " + be[0].yaw() + " from " + before[0]))
                .thenIdle(5)
                .thenExecute(() -> {
                    Vec3 at = be[0].position();
                    h.assertFalse(SubLevels.inPlotGrid(level, new ChunkPos(BlockPos.containing(at))), "launcher position is in the plot: " + at);
                    h.assertTrue(horizontal(at, site[0]) < 2 && horizontal(at, SubLevels.center(craft[0])) < 0.05,
                            "turned craft carries the launcher to " + at + ", its centre " + SubLevels.center(craft[0]));
                    h.assertTrue(Math.abs(be[0].rackYaw() - before[0]) < 1e-3 && Math.abs(Mth.wrapDegrees(be[0].yaw() - be[0].rackYaw())) > 80,
                            "world heading " + be[0].yaw() + " is not the turntable's " + be[0].rackYaw() + " turned with the craft");
                    h.assertTrue(LauncherOrders.problem(level, be[0]) == null, "a craft launcher refuses: " + FixedLauncherGameTests.key(LauncherOrders.problem(level, be[0])));
                    fired(h, level, be[0], "signal on a craft");
                    lancet[0] = only(level, be[0]);
                    Rails rails = be[0].rails();
                    float bearing = FlightController.anglesTo(be[0].position(), far)[0];
                    h.assertTrue(Math.abs(Mth.wrapDegrees(rails.yaw() - bearing)) < 1, "catapult heads " + rails.yaw() + ", target bears " + bearing);
                    onRail(h, lancet[0], rails, 0.01);
                })
                .thenExecute(() -> {
                    hold.at = hold.at.add(3, 0, 0);
                    hold.yaw = 120;
                })
                .thenWaitUntil(() -> h.assertTrue(horizontal(be[0].position(), site[0].add(3, 0, 0)) < 2, "craft not moved: " + be[0].position()))
                .thenIdle(3)
                .thenExecute(() -> {
                    h.assertTrue(lancet[0].flightPhase().onLauncher(), "Lancet left the rail early: " + lancet[0].flightPhase());
                    onRail(h, lancet[0], be[0].rails(), 0.01);
                })
                .thenWaitUntil(() -> h.assertTrue(lancet[0].isRemoved() || !lancet[0].flightPhase().onLauncher(), "Lancet still on the rail"))
                .thenExecute(() -> release[0] = lancet[0].position())
                .thenIdle(10)
                .thenExecute(() -> {
                    StrikeProjectile p = lancet[0];
                    h.assertFalse(p.isRemoved(), "Lancet gone right after launch (crashed into its craft?)");
                    Vec3 now = p.position();
                    h.assertFalse(SubLevels.inPlotGrid(level, new ChunkPos(BlockPos.containing(now))), "Lancet flies in the plot: " + now);
                    h.assertTrue(now.distanceTo(release[0]) > 3, "Lancet did not leave the rail: " + release[0] + " → " + now);
                    float flown = FlightController.anglesTo(release[0], now)[0];
                    float rail = be[0].rails().yaw();
                    h.assertTrue(Math.abs(Mth.wrapDegrees(flown - rail)) < 20, "Lancet flew " + flown + "°, rail heads " + rail + "°");
                })
                .thenSucceed();
    }

    /**
     * A round leaving a moving craft's rail keeps the rail's speed along its nose. The craft's body is given 10 blocks/s
     * along the strip (+Z, the catapult's heading) through Sable's physics pipeline each tick while the Lancet waits: the
     * launcher reads it as 0.5 blocks/tick (Sable's velocities are per second), and the Lancet starts its boost at that
     * velocity along its rail (0.5·cos 20° = 0.47 blocks/tick), give or take its first flight step, where a still craft
     * gives at most 0.06. The body's velocity is the cause read, not the pose's steps: Sable moves the pose on its own physics
     * steps (under teleports here 0 or 1 block per tick for a steady 0.5).
     */
    @GameTest(template = "runway", timeoutTicks = 300, batch = "craft_launcher_speed", skyAccess = true)
    public static void carriesRailSpeed(GameTestHelper h) {
        if (!sable()) {
            h.succeed();
            return;
        }
        ServerLevel level = h.getLevel();
        FixedLauncherBlockEntity ground = deck(h, null);
        Vec3 far = Vec3.atBottomCenterOf(h.absolutePos(LAUNCHER)).add(0, -1, 1000);
        ground.setMission(new Mission(WeaponType.LOITER, 1, 0, new Target.Point(far), far, Waypoints.NONE));
        h.assertTrue(ground.load(new ItemStack(WeaponType.LOITER.spec().munition().item().get(), 1)).isEmpty(), "Lancet did not fit");
        FixedLauncherBlockEntity[] be = new FixedLauncherBlockEntity[1];
        Hold hold = new Hold(h);
        SubLevelAccess[] craft = hold.craft;
        StrikeProjectile[] lancet = new StrikeProjectile[1];
        boolean[] moving = new boolean[1];
        Vec3[] railSpeed = new Vec3[1];
        Rails[] rails = new Rails[1];
        h.onEachTick(() -> {
            if (!moving[0]) return;
            ServerSubLevel sub = (ServerSubLevel) craft[0];
            PhysicsPipeline pipeline = SubLevelContainer.getContainer(level).physicsSystem().getPipeline();
            Vector3d v = pipeline.getLinearVelocity(sub, new Vector3d()), w = pipeline.getAngularVelocity(sub, new Vector3d());
            pipeline.addLinearAndAngularVelocity(sub, new Vector3d(-v.x, -v.y, 10 - v.z), w.negate(new Vector3d()));
            if (lancet[0].flightPhase().onLauncher()) {
                rails[0] = be[0].rails();
                railSpeed[0] = be[0].velocity(rails[0].point(0));
            }
        });
        h.startSequence()
                .thenExecute(() -> assemble(h, 0))
                .thenWaitUntil(() -> be[0] = carried(h, craft))
                .thenExecute(() -> hold.start(4))
                .thenWaitUntil(hold.reached(h))
                .thenExecute(() -> {
                    fired(h, level, be[0], "signal on a craft");
                    lancet[0] = only(level, be[0]);
                    hold.at = null;
                    moving[0] = true;
                })
                .thenWaitUntil(() -> h.assertTrue(lancet[0].flightPhase() == FlightPhase.BOOST, "Lancet not boosting yet: " + lancet[0].flightPhase()))
                .thenExecute(() -> {
                    moving[0] = false;
                    h.assertTrue(railSpeed[0] != null && railSpeed[0].distanceTo(new Vec3(0, 0, 0.5)) < 0.03,
                            "craft at 10 blocks/s along +Z reads " + railSpeed[0] + " blocks/tick at the rail");
                    double along = Math.max(0, railSpeed[0].dot(rails[0].direction())), v = lancet[0].speed();
                    // the first flight step adds boost and pays the climb trade (game flight model): within a few hundredths
                    h.assertTrue(along > 0.44 && v > along - 0.03 && v < along + 0.07,
                            "Lancet left a rail moving " + along + " blocks/tick along it at " + v + " blocks/tick");
                })
                .thenSucceed();
    }

    /**
     * On a pitched craft (25° about X) the rails a round is placed on are the rack the renderer draws: the turntable's own
     * heading on its mount in the plot, mapped by the craft's pose (review 1, finding 2: through a world heading and back
     * they were 0.18 blocks and 5.4° off).
     */
    @GameTest(template = "runway", timeoutTicks = 300, batch = "craft_launcher_tilt", skyAccess = true)
    public static void tiltedRailsAreTheDrawnRack(GameTestHelper h) {
        if (!sable()) {
            h.succeed();
            return;
        }
        ServerLevel level = h.getLevel();
        FixedLauncherBlockEntity ground = deck(h, null);
        shahedMission(h, ground);
        ground.setYaw(45);
        FixedLauncherBlockEntity[] be = new FixedLauncherBlockEntity[1];
        Hold hold = new Hold(h);
        SubLevelAccess[] craft = hold.craft;
        h.startSequence()
                .thenExecute(() -> assemble(h, 0))
                .thenWaitUntil(() -> be[0] = carried(h, craft))
                .thenExecute(() -> {
                    hold.turn = new Quaterniond().rotateX(Math.toRadians(25));
                    hold.start(3);
                })
                .thenWaitUntil(hold.reached(h))
                .thenExecute(() -> {
                    SubLevels.Frame frame = SubLevels.carrying(level, be[0].getBlockPos());
                    h.assertTrue(frame != null, "no craft frame");
                    Rails own = LauncherMount.PAD.rails(Vec3.atBottomCenterOf(be[0].getBlockPos()), be[0].rackYaw(), be[0].weapon());
                    Rails placed = be[0].rails();
                    double worst = 0;
                    for (int i = 0; i < own.slots(); i++) worst = Math.max(worst, frame.toWorld(own.point(i)).distanceTo(placed.point(i)));
                    double angle = Math.toDegrees(Math.acos(Mth.clamp(frame.toWorldDirection(own.direction()).dot(placed.direction()), -1, 1)));
                    // the axis goes through float headings and Minecraft's table sine: a few tenths of a degree of rounding
                    h.assertTrue(worst < 0.01 && angle < 0.5, "rails off the drawn rack by " + worst + " blocks, axis by " + angle + "°");
                })
                .thenSucceed();
    }

    /**
     * The fuze arms {@link ProximityFuse#ARM_DISTANCE} from where the round left its rail, and its launch gate (the route's
     * first point) stands that far ahead of there along the rail: the craft carrying the round meanwhile is no flight.
     * A Shahed rides a turned rack while it raises again (about 50 ticks) on a craft moving 2 blocks/tick: about 100 blocks
     * (review 1, finding 1: it left armed on its own rail, the gate 96 blocks behind).
     */
    @GameTest(template = "runway", timeoutTicks = 400, batch = "craft_launcher_arm", skyAccess = true)
    public static void armingCountsFromRelease(GameTestHelper h) {
        if (!sable()) {
            h.succeed();
            return;
        }
        ServerLevel level = h.getLevel();
        FixedLauncherBlockEntity ground = deck(h, null);
        shahedMission(h, ground);
        // facing away from the target: the order turns the rack, which then raises again before ignition
        ground.setYaw(180);
        FixedLauncherBlockEntity[] be = new FixedLauncherBlockEntity[1];
        Hold hold = new Hold(h);
        SubLevelAccess[] craft = hold.craft;
        StrikeProjectile[] shahed = new StrikeProjectile[1];
        Vec3[] placed = new Vec3[1];
        h.onEachTick(() -> {
            if (shahed[0] != null && shahed[0].flightPhase().onLauncher()) hold.at = hold.at.add(0, 0, 2);
        });
        h.startSequence()
                .thenExecute(() -> assemble(h, 0))
                .thenWaitUntil(() -> be[0] = carried(h, craft))
                .thenExecute(hold::start)
                .thenWaitUntil(hold.reached(h))
                .thenExecute(() -> {
                    fired(h, level, be[0], "signal on a craft");
                    shahed[0] = only(level, be[0]);
                    placed[0] = shahed[0].position();
                })
                .thenWaitUntil(() -> h.assertTrue(shahed[0].flightPhase() == FlightPhase.BOOST, "Shahed still on the rail: " + shahed[0].flightPhase()))
                .thenExecute(() -> {
                    StrikeProjectile p = shahed[0];
                    Vec3 origin = p.launchOrigin(), at = p.position();
                    h.assertTrue(at.distanceTo(placed[0]) > ProximityFuse.ARM_DISTANCE, "the craft carried the Shahed only " + at.distanceTo(placed[0]));
                    h.assertTrue(origin != null && origin.distanceTo(at) < 1, "arming counts from " + origin + ", the Shahed left its rail at " + at);
                    Vec3 gate = p.route() == null ? null : p.route().current();
                    h.assertTrue(gate != null, "no launch gate");
                    double ahead = Math.hypot(gate.x - at.x, gate.z - at.z);
                    float bearing = FlightController.anglesTo(at, new Vec3(gate.x, at.y, gate.z))[0];
                    h.assertTrue(Math.abs(ahead - ProximityFuse.ARM_DISTANCE) < 1.5 && Math.abs(Mth.wrapDegrees(bearing - p.getYRot())) < 1,
                            "gate " + ahead + " blocks ahead at " + bearing + "°, the Shahed heads " + p.getYRot() + "°");
                })
                .thenSucceed();
    }

    /**
     * The craft's own hull in front of its rack blocks the launch sector: a 4-block plank wall around the launcher on the
     * deck refuses a Shahed with "sector blocked" (stock kept, nothing launched); with the wall removed from the craft the
     * same signal launches.
     */
    @GameTest(template = "runway", timeoutTicks = 300, batch = "craft_launcher_hull", skyAccess = true)
    public static void ownHullBlocksSector(GameTestHelper h) {
        if (!sable()) {
            h.succeed();
            return;
        }
        ServerLevel level = h.getLevel();
        List<BlockPos> wall = ring(LAUNCHER, DECK, LAUNCHER.getY(), LAUNCHER.getY() + 3);
        FixedLauncherBlockEntity ground = deck(h, wall);
        shahedMission(h, ground);
        FixedLauncherBlockEntity[] be = new FixedLauncherBlockEntity[1];
        Hold hold = new Hold(h);
        SubLevelAccess[] craft = hold.craft;
        List<BlockPos> plotWall = new ArrayList<>();
        h.startSequence()
                .thenExecute(() -> assemble(h, 3))
                .thenWaitUntil(() -> be[0] = carried(h, craft))
                .thenExecute(hold::start)
                .thenWaitUntil(hold.reached(h))
                .thenExecute(() -> {
                    // assembly keeps the blocks' layout in the plot
                    for (BlockPos w : wall) plotWall.add(be[0].getBlockPos().offset(w.subtract(LAUNCHER)));
                    for (BlockPos w : plotWall) h.assertTrue(level.getBlockState(w).is(Blocks.OAK_PLANKS), "hull wall not on the craft at " + w);
                    h.assertValueEqual(FixedLauncherGameTests.key(be[0].fire(level)), "incoming.fixed_launcher.sector", "launcher walled by its own craft");
                    h.assertTrue(be[0].stockCount() == 2 && ours(level, be[0]).isEmpty(), "blocked sector spent stock or launched");
                    for (BlockPos w : plotWall) level.setBlock(w, Blocks.AIR.defaultBlockState(), 3);
                })
                .thenIdle(5)
                .thenExecute(() -> {
                    fired(h, level, be[0], "open deck");
                    h.assertTrue(be[0].stockCount() == 1 && ours(level, be[0]).size() == 1, "open deck did not launch one Shahed");
                })
                .thenSucceed();
    }

    /**
     * World blocks around the craft block its launch sector in the world frame: a stone wall around the deck (outside the
     * craft, built after assembly) refuses a Shahed with "sector blocked"; without the wall it launches.
     */
    @GameTest(template = "runway", timeoutTicks = 300, batch = "craft_launcher_world", skyAccess = true)
    public static void worldBlocksSector(GameTestHelper h) {
        if (!sable()) {
            h.succeed();
            return;
        }
        ServerLevel level = h.getLevel();
        FixedLauncherBlockEntity ground = deck(h, null);
        shahedMission(h, ground);
        List<BlockPos> wall = ring(LAUNCHER, DECK + 1, LAUNCHER.getY(), LAUNCHER.getY() + 8);
        StrikeGameTests.afterTest(h, () -> wall.forEach(w -> h.setBlock(w, Blocks.AIR)));
        FixedLauncherBlockEntity[] be = new FixedLauncherBlockEntity[1];
        Hold hold = new Hold(h);
        SubLevelAccess[] craft = hold.craft;
        h.startSequence()
                .thenExecute(() -> assemble(h, 0))
                .thenWaitUntil(() -> be[0] = carried(h, craft))
                .thenExecute(hold::start)
                .thenWaitUntil(hold.reached(h))
                .thenExecute(() -> {
                    wall.forEach(w -> h.setBlock(w, Blocks.STONE));
                    h.assertValueEqual(FixedLauncherGameTests.key(be[0].fire(level)), "incoming.fixed_launcher.sector", "craft walled by the world");
                    h.assertTrue(be[0].stockCount() == 2 && ours(level, be[0]).isEmpty(), "blocked sector spent stock or launched");
                    wall.forEach(w -> h.setBlock(w, Blocks.AIR));
                    fired(h, level, be[0], "open world around the craft");
                    h.assertTrue(be[0].stockCount() == 1 && ours(level, be[0]).size() == 1, "open world did not launch one Shahed");
                })
                .thenSucceed();
    }

    // ---------------------------------------------------------------- craft

    private static boolean sable() {
        return ModList.get().isLoaded("sable");
    }

    /** Deck of planks with an ownerless launcher in its middle on top (and {@code extra} planks); cleanup removes our rounds. */
    private static FixedLauncherBlockEntity deck(GameTestHelper h, @Nullable List<BlockPos> extra) {
        ServerLevel level = h.getLevel();
        for (int dx = -DECK; dx <= DECK; dx++) {
            for (int dz = -DECK; dz <= DECK; dz++) h.setBlock(LAUNCHER.offset(dx, -1, dz), Blocks.OAK_PLANKS);
        }
        if (extra != null) extra.forEach(p -> h.setBlock(p, Blocks.OAK_PLANKS));
        h.setBlock(LAUNCHER, ModBlocks.FIXED_LAUNCHER.get());
        FixedLauncherBlockEntity be = h.getBlockEntity(LAUNCHER);
        be.setOwner(null);
        be.setYaw(0);
        Vec3 site = Vec3.atBottomCenterOf(h.absolutePos(LAUNCHER));
        StrikeGameTests.afterTest(h, () -> {
            for (StrikeProjectile p : StrikeWorld.projectiles(level)) if (p.ownerId() == null && p.position().distanceTo(site) < 64) p.discard();
            VirtualFlights.get(level).clear(level, p -> p.ownerId() == null && p.position().distanceTo(site) < 2048);
            for (SubLevelAccess s : SubLevels.near(level, site, 16)) command(level, "sable sub_level remove " + s.getUniqueId(), true);
        });
        return be;
    }

    private static void shahedMission(GameTestHelper h, FixedLauncherBlockEntity be) {
        Vec3 far = Vec3.atBottomCenterOf(h.absolutePos(LAUNCHER)).add(0, -1, 1000);
        be.setMission(new Mission(WeaponType.DRONE, 1, 0, new Target.Point(far), far, Waypoints.NONE));
        h.assertTrue(be.load(new ItemStack(WeaponType.DRONE.spec().munition().item().get(), 2)).isEmpty(), "Shaheds did not fit");
    }

    /** {@code sable assemble area} over the deck and {@code above} blocks over the launcher. */
    private static void assemble(GameTestHelper h, int above) {
        BlockPos a = h.absolutePos(LAUNCHER.offset(-DECK, -1, -DECK)), b = h.absolutePos(LAUNCHER.offset(DECK, above, DECK));
        command(h.getLevel(), String.format(Locale.ROOT, "sable assemble area %d %d %d %d %d %d", Math.min(a.getX(), b.getX()), a.getY(),
                Math.min(a.getZ(), b.getZ()), Math.max(a.getX(), b.getX()), b.getY(), Math.max(a.getZ(), b.getZ())), true);
    }

    /** The launcher moved into the assembled craft's plot (its world block gone); fails until then. */
    private static FixedLauncherBlockEntity carried(GameTestHelper h, SubLevelAccess[] craft) {
        ServerLevel level = h.getLevel();
        Vec3 at = Vec3.atCenterOf(h.absolutePos(LAUNCHER));
        List<SubLevelAccess> near = SubLevels.near(level, at, 8);
        h.assertFalse(near.isEmpty(), "craft not assembled");
        h.assertTrue(level.getBlockState(h.absolutePos(LAUNCHER)).isAir(), "launcher still in the world");
        craft[0] = near.getFirst();
        for (BlockPos p : SubLevels.plotCells(craft[0])) {
            if (level.getBlockEntity(p) instanceof FixedLauncherBlockEntity be) return be;
        }
        throw new net.minecraft.gametest.framework.GameTestAssertException("no launcher in the craft's plot");
    }

    /**
     * Holds the assembled craft in the air at a pose every tick, as an airship's lift and engines would: Sable's gravity
     * would drop it, and on a fresh GameTest platform it can fall through ground whose collision Sable has not built yet.
     * Starts 1 block above where the craft was assembled, unturned; tests turn and move it through {@link #at} and
     * {@link #yaw}.
     */
    private static final class Hold {
        final SubLevelAccess[] craft = new SubLevelAccess[1];
        @Nullable
        Vec3 at;
        float yaw;
        /** Full turn instead of {@link #yaw} (a pitched or rolled craft); null: {@link #yaw} about the vertical. */
        @Nullable
        Quaterniond turn;

        Hold(GameTestHelper h) {
            h.onEachTick(() -> {
                if (at != null) teleport(h, craft[0], at, turn != null ? turn : new Quaterniond().rotateY(Math.toRadians(yaw)));
            });
        }

        /** Hold the craft found by {@link #carried}, lifted 1 block. */
        void start() {
            start(1);
        }

        /** Hold the craft found by {@link #carried}, lifted {@code lift} blocks. */
        void start(double lift) {
            Vector3dc p = craft[0].logicalPose().position();
            at = new Vec3(p.x(), p.y() + lift, p.z());
        }

        /** The craft stands at the held pose now. */
        Runnable reached(GameTestHelper h) {
            return () -> {
                Vector3dc p = craft[0].logicalPose().position();
                Vec3 now = new Vec3(p.x(), p.y(), p.z());
                h.assertTrue(at != null && now.distanceTo(at) < 1e-3, "craft at " + now + ", held at " + at);
            };
        }
    }

    /** Put {@code craft} at {@code at}, turned by {@code turn}, at rest (Sable's physics pipeline). */
    private static void teleport(GameTestHelper h, SubLevelAccess craft, Vec3 at, Quaterniond turn) {
        ServerSubLevel sub = (ServerSubLevel) craft;
        PhysicsPipeline pipeline = SubLevelContainer.getContainer(h.getLevel()).physicsSystem().getPipeline();
        pipeline.resetVelocity(sub);
        pipeline.teleport(sub, new Vector3d(at.x, at.y, at.z), new Quaterniond(turn));
    }

    private static void command(ServerLevel level, String command, boolean quiet) {
        MinecraftServer server = level.getServer();
        var source = server.createCommandSourceStack().withLevel(level);
        server.getCommands().performPrefixedCommand(quiet ? source.withSuppressedOutput() : source, command);
    }

    /** Ring of block columns at Chebyshev distance {@code r} around {@code c}, from {@code y0} to {@code y1}. */
    private static List<BlockPos> ring(BlockPos c, int r, int y0, int y1) {
        List<BlockPos> out = new ArrayList<>();
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                if (Math.max(Math.abs(dx), Math.abs(dz)) != r) continue;
                for (int y = y0; y <= y1; y++) out.add(new BlockPos(c.getX() + dx, y, c.getZ() + dz));
            }
        }
        return out;
    }

    // ---------------------------------------------------------------- rounds

    /** A signal orders the mission: no refusal, status "fired". */
    private static void fired(GameTestHelper h, ServerLevel level, FixedLauncherBlockEntity be, String what) {
        var problem = be.fire(level);
        h.assertTrue(problem == null, what + ": refused, " + FixedLauncherGameTests.key(problem));
        h.assertValueEqual(FixedLauncherGameTests.key(be.lastReport()), "incoming.fixed_launcher.fired", what);
    }

    /** Ownerless rounds of the launcher {@code be} near its world place. */
    private static List<StrikeProjectile> ours(ServerLevel level, FixedLauncherBlockEntity be) {
        Vec3 at = be.position();
        return StrikeWorld.projectiles(level).stream().filter(p -> p.ownerId() == null && p.position().distanceTo(at) < 16).toList();
    }

    private static StrikeProjectile only(ServerLevel level, FixedLauncherBlockEntity be) {
        List<StrikeProjectile> on = ours(level, be);
        if (on.size() != 1) throw new net.minecraft.gametest.framework.GameTestAssertException("not exactly one round at the launcher: " + on);
        return on.getFirst();
    }

    /** {@code p} waits on one of {@code rails}' world points (within {@code tolerance}), nose along their world axis. */
    private static void onRail(GameTestHelper h, StrikeProjectile p, Rails rails, double tolerance) {
        h.assertTrue(p.flightPhase() == FlightPhase.READY || p.flightPhase() == FlightPhase.IGNITION, "round not on the launcher: " + p.flightPhase());
        double nearest = Double.MAX_VALUE;
        for (Vec3 r : rails.points()) nearest = Math.min(nearest, r.distanceTo(p.position()));
        h.assertTrue(nearest < tolerance, "round " + p.position() + " is " + nearest + " from its rails " + rails.points());
        h.assertTrue(Math.abs(Mth.wrapDegrees(p.getYRot() - rails.yaw())) < 0.5 && Math.abs(p.getXRot() + rails.elevation()) < 0.5,
                "round faces " + p.getYRot() + "/" + p.getXRot() + ", rails " + rails.yaw() + "/" + (-rails.elevation()));
    }

    private static double horizontal(Vec3 a, Vec3 b) {
        return Math.hypot(a.x - b.x, a.z - b.z);
    }
}
