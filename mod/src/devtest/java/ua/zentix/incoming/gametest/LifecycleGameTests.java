package ua.zentix.incoming.gametest;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ChunkLevel;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.DistanceManager;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.Ticket;
import net.minecraft.server.level.TicketType;
import net.minecraft.util.SortedArraySet;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import net.neoforged.neoforge.registries.NeoForgeRegistries;
import ua.zentix.incoming.Incoming;
import ua.zentix.incoming.entity.CruiseMissileEntity;
import ua.zentix.incoming.entity.FlightPhase;
import ua.zentix.incoming.entity.LoiterEntity;
import ua.zentix.incoming.entity.RocketEntity;
import ua.zentix.incoming.entity.StrikeProjectile;
import ua.zentix.incoming.guidance.Route;
import ua.zentix.incoming.legacy.OldNamespace;
import ua.zentix.incoming.registry.ModEntities;
import ua.zentix.incoming.strike.AreaLoader;
import ua.zentix.incoming.strike.ChunkTickets;
import ua.zentix.incoming.strike.FlightTickets;
import ua.zentix.incoming.strike.StrikeWorld;
import ua.zentix.incoming.strike.VirtualFlights;
import ua.zentix.incoming.target.Target;
import ua.zentix.incoming.target.TargetPicker;
import ua.zentix.incoming.target.TargetTracker;
import ua.zentix.incoming.util.Terrain;
import ua.zentix.incoming.work.StallWatch;

import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Consumer;

/**
 * The life cycle of projectiles and chunks is what the load stand ({@code tools/stress.sh}) checks in a game with friends:
 *  the sight beam does not load chunks on the server, tickets are released whenever a projectile leaves the world (and when unloading
 *  together with a chunk), when the server is stopped, projectiles go flying outside the world, and not freeze in chunk files.
 */
@GameTestHolder(Incoming.MOD_ID)
@PrefixGameTestTemplate(false)
public final class LifecycleGameTests {
    private static final BlockPos RANGE_CENTER = new BlockPos(32, 11, 32);

    private LifecycleGameTests() {}

    /**
     * A world with entries under the name {@code airstrike}: each mod entry is also under this name, and the block from the
     *  palette of the chunk with it is read by the Incoming block, and not by air.
     */
    @GameTest(template = "range", timeoutTicks = 20, batch = "old_namespace")
    public static void oldNamespaceResolves(GameTestHelper helper) {
        int checked = 0;
        for (Registry<?> registry : List.<Registry<?>>of(BuiltInRegistries.BLOCK, BuiltInRegistries.ITEM,
                BuiltInRegistries.ENTITY_TYPE, BuiltInRegistries.BLOCK_ENTITY_TYPE, BuiltInRegistries.MOB_EFFECT,
                BuiltInRegistries.DATA_COMPONENT_TYPE, NeoForgeRegistries.ATTACHMENT_TYPES)) {
            for (ResourceLocation id : registry.keySet()) {
                if (!id.getNamespace().equals(Incoming.MOD_ID)) continue;
                ResourceLocation old = ResourceLocation.fromNamespaceAndPath(OldNamespace.ID, id.getPath());
                if (registry.get(old) != registry.get(id)) {
                    throw new GameTestAssertException(old + " does not resolve to " + id + " in " + registry.key().location());
                }
                checked++;
            }
        }
        if (checked < 20) throw new GameTestAssertException("only " + checked + " entries of the mod found");
        Block block = BuiltInRegistries.BLOCK.stream()
                .filter(b -> BuiltInRegistries.BLOCK.getKey(b).getNamespace().equals(Incoming.MOD_ID)).findFirst().orElseThrow();
        CompoundTag state = new CompoundTag();
        state.putString("Name", OldNamespace.ID + ":" + BuiltInRegistries.BLOCK.getKey(block).getPath());
        BlockState read = NbtUtils.readBlockState(helper.getLevel().holderLookup(Registries.BLOCK), state);
        helper.assertTrue(read.is(block), "old block name read as " + read);
        // world data is renamed where the game keeps it: Nether - DIM-1, End - DIM1
        Path root = helper.getLevel().getServer().getWorldPath(LevelResource.ROOT);
        List<Path> folders = OldNamespace.dataFolders(helper.getLevel().getServer());
        for (String dim : List.of("DIM-1", "DIM1")) {
            helper.assertTrue(folders.contains(root.resolve(dim).resolve("data")), "no " + dim + "/data among " + folders);
        }
        helper.assertTrue(folders.contains(root.resolve("data")), "no data folder of the overworld among " + folders);
        helper.succeed();
    }

    /**
     * “Where I’m looking” from the remote control screen and from commands is aimed on the server: the beam on 400 blocks should not load
     *  chunks on the way (vanilla clip loads and generates them right on tick - the server stops for seconds).
     */
    @GameTest(template = "range", timeoutTicks = 20, batch = "picker_chunks")
    public static void serverPickDoesNotLoadChunks(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        // above the barrier wall around the site: above the flat test world, the beam travels through the air all 400 blocks
        Vec3 eye = Vec3.atCenterOf(h.absolutePos(RANGE_CENTER)).add(0, 100, 0);
        Vec3 look = new Vec3(1, 0, 0);
        // the first unloaded chunk along the beam
        ChunkPos unready = null;
        for (int d = 16; d <= 400 && unready == null; d += 4) {
            BlockPos p = BlockPos.containing(eye.add(look.scale(d)));
            if (!Terrain.ready(level, p)) unready = new ChunkPos(p);
        }
        h.assertTrue(unready != null, "there is no unloaded chunk on the beam - there is nothing to check");
        ArmorStand viewer = new ArmorStand(level, eye.x, eye.y - 1.6, eye.z);
        TargetPicker.pick(level, viewer, eye, look, 400);
        h.assertTrue(level.getChunkSource().getChunkNow(unready.x, unready.z) == null, "sight beam loaded chunk " + unready);
        h.succeed();
    }

    /**
     * The projectile in the world holds the ticket of its chunk and the chunk in front; unloading together with the chunk ({@code setRemoved},
     *  without {@code remove}) should release them - otherwise the chunks would hang loaded until the restart.
     */
    @GameTest(template = "range", timeoutTicks = 40, batch = "tickets_unload", skyAccess = true)
    public static void ticketsReleasedWhenUnloadedWithChunk(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        Vec3 start = Vec3.atCenterOf(h.absolutePos(RANGE_CENTER)).add(0, 1, 0);
        // on the rail (stands before ignition): holds the ticket of its chunk and does not fly away anywhere
        CruiseMissileEntity m = ModEntities.CRUISE_MISSILE.get().create(level);
        m.placeOnLauncher(start, 0, 40, 1000, 0, new Target.Point(start.add(0, 0, 3000)), start.add(0, 0, 3000), null);
        m.setRoute(Route.direct());
        level.addFreshEntity(m);
        UUID id = m.getUUID();
        h.runAfterDelay(3, () -> {
            h.assertTrue(chunkTickets(level, id) > 0, "the projectile did not take the ticket of its chunk: " + state(level, m));
            m.setRemoved(Entity.RemovalReason.UNLOADED_TO_CHUNK);
            h.assertTrue(chunkTickets(level, id) == 0, "ticket chunk remained after unloading the projectile");
            h.succeed();
        });
    }

    /**
     * The target area (ticket of the region or warming up by chunks) keeps and loitering in the world; unloading with a chunk releases it too.
     */
    @GameTest(template = "range", timeoutTicks = 60, batch = "tickets_area", skyAccess = true)
    public static void targetAreaReleasedWhenUnloadedWithChunk(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        Vec3 target = Vec3.atCenterOf(h.absolutePos(RANGE_CENTER)).add(0, 0.5, 0);
        LoiterEntity l = ModEntities.LOITER.get().create(level);
        l.launch(target.add(-10, 45, 0), new Target.Point(target), target, null);
        l.setRoute(null);
        level.addFreshEntity(l);
        UUID id = l.getUUID();
        h.runAfterDelay(3, () -> {
            h.assertTrue(flightTickets(level, id) > 0, "loitering did not take the target area: " + state(level, l));
            l.setRemoved(Entity.RemovalReason.UNLOADED_TO_CHUNK);
            h.assertTrue(flightTickets(level, id) == 0, "the target area remained after the projectile was unloaded");
            h.succeed();
        });
    }

    /**
     * Server stop: a projectile from the world goes into flight outside the world with the same UUID (it will be saved by {@link VirtualFlights})
     *  and releases the ticket.
     */
    @GameTest(template = "range", timeoutTicks = 40, batch = "shutdown_park", skyAccess = true)
    public static void projectileParksOnShutdown(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        Vec3 start = Vec3.atCenterOf(h.absolutePos(RANGE_CENTER)).add(0, 1, 0);
        // on the rail (stands before ignition): holds the ticket of its chunk and does not fly away anywhere
        CruiseMissileEntity m = ModEntities.CRUISE_MISSILE.get().create(level);
        m.placeOnLauncher(start, 0, 40, 1000, 0, new Target.Point(start.add(0, 0, 3000)), start.add(0, 0, 3000), null);
        m.setRoute(Route.direct());
        level.addFreshEntity(m);
        UUID id = m.getUUID();
        h.runAfterDelay(3, () -> {
            h.assertTrue(chunkTickets(level, id) > 0, "the projectile did not take the ticket of its chunk: " + state(level, m));
            m.parkForShutdown(level);
            h.assertTrue(m.isRemoved() && level.getEntity(id) == null, "the projectile remained in the world");
            h.assertTrue(VirtualFlights.get(level).flights().stream().anyMatch(p -> p.getUUID().equals(id)), "the projectile did not go off-world");
            h.assertTrue(chunkTickets(level, id) == 0, "ticket chunk left");
            VirtualFlights.get(level).flights().removeIf(p -> {
                if (!p.getUUID().equals(id)) return false;
                p.discard();
                return true;
            });
            h.succeed();
        });
    }

    /**
     * Stopping the server while the projectile is on the launcher in a closed cell: after launch, it returns to the world on the same
     *  rail (and not above the terrain, like a projectile in flight) and is still hidden while the package is rising.
     */
    @GameTest(template = "range", timeoutTicks = 40, batch = "shutdown_rail", skyAccess = true)
    public static void parkedLauncherRoundReturnsToRail(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        Vec3 rail = Vec3.atCenterOf(h.absolutePos(RANGE_CENTER)).add(0, 1, 0);
        CruiseMissileEntity m = ModEntities.CRUISE_MISSILE.get().create(level);
        m.placeOnLauncher(rail, 0, 40, 1000, 200, new Target.Point(rail.add(0, 0, 3000)), rail.add(0, 0, 3000), null);
        m.setRoute(Route.direct());
        level.addFreshEntity(m);
        UUID id = m.getUUID();
        h.runAfterDelay(3, () -> m.parkForShutdown(level));
        h.runAfterDelay(8, () -> {
            h.assertTrue(level.getEntity(id) instanceof StrikeProjectile, "the projectile did not return to the world: " + state(level, m));
            StrikeProjectile back = (StrikeProjectile) level.getEntity(id);
            h.assertTrue(back != m && back.position().distanceTo(rail) < 1e-6, "the projectile did not return to the rail: " + back.position() + " instead of " + rail);
            h.assertTrue(back.flightPhase() == FlightPhase.READY && !back.isActive(), "the projectile in the closed cell is visible: " + state(level, back));
            back.discard();
            h.succeed();
        });
    }

    /**
     * A projectile outside the world that has run out of power reserve is removed - and does not take the target area again in the same tick
     *  (otherwise regional tickets remain after missiles that did not wait for the target area).
     */
    @GameTest(template = "range", timeoutTicks = 40, batch = "virtual_expiry", skyAccess = true)
    public static void expiredVirtualFlightLeavesNoTargetArea(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        Vec3 start = Vec3.atCenterOf(h.absolutePos(RANGE_CENTER)).add(0, 60, 0);
        Vec3 target = start.add(300, -60, 0);
        CruiseMissileEntity m = ModEntities.CRUISE_MISSILE.get().create(level);
        m.launch(start, new Target.Point(target), target, null);
        m.setRoute(Route.direct());
        // power reserve - two ticks: save and read back with another reserve
        net.minecraft.nbt.CompoundTag tag = new net.minecraft.nbt.CompoundTag();
        m.saveWithoutId(tag);
        tag.getCompound("mission").putDouble("range", 2 * m.cruiseSpeed());
        m.load(tag);
        VirtualFlights.launch(level, m);
        UUID id = m.getUUID();
        h.runAfterDelay(6, () -> {
            h.assertTrue(m.isRemoved(), "the projectile is not removed due to its lifespan: " + state(level, m));
            h.assertTrue(flightTickets(level, id) == 0, "the target area remained behind the removed projectile");
            h.succeed();
        });
    }

    /**
     * A stationary target is not lost, no matter how far the projectile is (a missile launched further than 3000 blocks does not indicate the entire flight “target lost”); the moving one is lost when it disappears or goes beyond
     *  reserve for the pursuit, and the point remains the last known.
     */
    @GameTest(template = "range", timeoutTicks = 20, batch = "tracker_loss")
    public static void onlyRunawayTargetIsLost(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        Vec3 far = Vec3.atBottomCenterOf(h.absolutePos(new BlockPos(8, 11, 8))).add(0, 0, 5000);
        TargetTracker point = new TargetTracker(new Target.Point(far), far);
        for (int i = 0; i < 3; i++) point.tick(level, q -> false);
        h.assertFalse(point.isLost(), "fixed target lost");

        Vec3 at = Vec3.atBottomCenterOf(h.absolutePos(new BlockPos(8, 11, 8)));
        ArmorStand stand = new ArmorStand(level, at.x, at.y, at.z);
        level.addFreshEntity(stand);
        TargetTracker moving = new TargetTracker(new Target.OfEntity(stand.getUUID(), Vec3.ZERO), at);
        Vec3 near = at.add(20, 0, 0);
        stand.teleportTo(near.x, near.y, near.z);
        h.assertTrue(Math.abs(moving.tick(level, q -> false) - 20) < 1e-6, "the target shift does not catch up");
        h.assertFalse(moving.isLost(), "the target is lost within the pursuit reserve");
        Vec3 runaway = near.add(0, 0, TargetTracker.CHASE_BUDGET);
        stand.teleportTo(runaway.x, runaway.y, runaway.z);
        h.assertTrue(moving.tick(level, q -> false) == 0 && moving.isLost(), "the target has gone beyond the pursuit reserve and is not lost");
        h.assertTrue(moving.point().distanceTo(near) < 1e-6, "lost target moved point of impact");

        TargetTracker gone = new TargetTracker(new Target.OfEntity(stand.getUUID(), Vec3.ZERO), runaway);
        stand.discard();
        gone.tick(level, q -> false);
        h.assertTrue(gone.isLost(), "a missing target is not lost");
        h.succeed();
    }

    /**
     * The target has gone far away (the player has flown thousands of blocks away, retargeting): the power reserve increases for the duration of this shift.
     *  Without this, it remains according to plan to the old point, and the projectile disappears along the way without detonation (for example,
     * 5 “Lancet” and the rocket behind the player who flew away 3000 blocks and who left the game there). The reserve for the chase
     *  is finite: the projectile that has already selected it is removed according to the power reserve and does not hold the target area.
     */
    @GameTest(template = "range", timeoutTicks = 40, batch = "moved_target", skyAccess = true)
    public static void rangeFollowsMovedTarget(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        // start above the far corner of the site: ~70 blocks to the target; Shahed will not reach it during the test
        Vec3 start = Vec3.atCenterOf(h.absolutePos(new BlockPos(56, 11, 56))).add(0, 60, 0);
        Vec3 near = Vec3.atBottomCenterOf(h.absolutePos(new BlockPos(8, 11, 8)));
        ArmorStand stand = new ArmorStand(level, near.x, near.y, near.z);
        level.addFreshEntity(stand);
        StrikeProjectile followed = virtualWithTwoTicks(level, start, new Target.OfEntity(stand.getUUID(), Vec3.ZERO), near);
        StrikeProjectile retargeted = virtualWithTwoTicks(level, start, new Target.Point(near), near);
        // without a reserve, it will explode in the air according to its power reserve - in another corner, so that the explosion does not affect the others
        Vec3 aside = Vec3.atCenterOf(h.absolutePos(new BlockPos(8, 11, 56))).add(0, 60, 0);
        StrikeProjectile exhausted = virtualWithTwoTicks(level, aside, new Target.Point(near), near);
        net.minecraft.nbt.CompoundTag tag = new net.minecraft.nbt.CompoundTag();
        exhausted.saveWithoutId(tag);
        tag.getCompound("tracker").putDouble("chased", TargetTracker.CHASE_BUDGET);
        exhausted.load(tag);
        // the target moves to another corner of the site (Shahed is ~30 flight tick), the new target is 1500 blocks away
        Vec3 moved = Vec3.atBottomCenterOf(h.absolutePos(new BlockPos(56, 11, 8)));
        stand.teleportTo(moved.x, moved.y, moved.z);
        Vec3 far = near.add(0, 0, 1500);
        h.assertTrue(retargeted.retarget(new Target.Point(far), far), "shahed did not accept the new goal");
        h.assertTrue(exhausted.retarget(new Target.Point(far), far), "shahed did not accept the new goal without reserve");
        h.runAfterDelay(6, () -> {
            h.assertFalse(followed.isRemoved(), "shahed removed the old move reserve for the departed target: " + state(level, followed));
            h.assertFalse(retargeted.isRemoved(), "retargeted Shahed removed the old power reserve: " + state(level, retargeted));
            h.assertTrue(exhausted.isRemoved(), "shahed without a reserve for the pursuit is not removed in terms of power reserve: " + state(level, exhausted));
            h.assertTrue(flightTickets(level, exhausted.getUUID()) == 0, "the target area remained behind Shahed without reserves");
            for (StrikeProjectile m : List.of(followed, retargeted)) {
                VirtualFlights.get(level).flights().remove(m);
                m.discard();
            }
            stand.discard();
            h.succeed();
        });
    }

    /** Shahed in flight outside the world, which has a power reserve of two ticks. */
    private static StrikeProjectile virtualWithTwoTicks(ServerLevel level, Vec3 start, Target target, Vec3 point) {
        StrikeProjectile m = ModEntities.DRONE.get().create(level);
        m.launch(start, target, point, null);
        m.setRoute(Route.direct());
        net.minecraft.nbt.CompoundTag tag = new net.minecraft.nbt.CompoundTag();
        m.saveWithoutId(tag);
        tag.getCompound("mission").putDouble("range", 2 * m.cruiseSpeed());
        m.load(tag);
        VirtualFlights.launch(level, m);
        return m;
    }

    /**
     * The projectile in the world flies off the edge of the loaded one: before exiting it holds a ticket, and having gone into flight outside the world, it does not hold any
     *  and does not remain in the world (otherwise it steps into the chunk without a tick and can get stuck there with a ticket).
     */
    @GameTest(template = "range", timeoutTicks = 200, batch = "leave_loaded", skyAccess = true)
    public static void projectileLeavingLoadedWorldHoldsNoTickets(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        // mLRS projectile: ballistics takes it up, above the barrier wall of the site (the cruise missile would have pressed
        //  against the terrain and crashed against the wall)
        Vec3 start = Vec3.atCenterOf(h.absolutePos(RANGE_CENTER)).add(0, 10, 0);
        Vec3 target = start.add(600, -10, 0);
        RocketEntity m = ModEntities.ROCKET.get().create(level);
        m.launchFrom(start, new Target.Point(target), target, null);
        level.addFreshEntity(m);
        UUID id = m.getUUID();
        boolean[] held = new boolean[1];
        String[] away = new String[1];
        h.onEachTick(() -> {
            if (away[0] != null) return;
            if (level.getEntity(id) instanceof StrikeProjectile p && !p.isVirtual()) {
                if (chunkTickets(level, id) > 0) held[0] = true;
                h.assertTrue(level.isPositionEntityTicking(p.blockPosition()), "the projectile in the world is in a chunk without a tick: " + state(level, p));
            }
            for (StrikeProjectile p : List.copyOf(VirtualFlights.get(level).flights())) {
                if (!p.getUUID().equals(id)) continue;
                // left: a snapshot of what was left behind, and the copy is removed (the test no longer needs it)
                away[0] = "in-world " + (level.getEntity(id) != null) + ", tickets " + chunkTickets(level, id);
                VirtualFlights.get(level).flights().remove(p);
                p.discard();
            }
        });
        h.succeedWhen(() -> {
            h.assertTrue(away[0] != null, "the projectile did not go off-world: " + state(level, m));
            h.assertTrue(held[0], "in the world the projectile did not hold a ticket - there is nothing to check");
            h.assertTrue(away[0].equals("in-world false, tickets 0"), "after leaving the world: " + away[0]);
        });
    }

    /**
     * A projectile from a flight outside the world did not enter the world - the input handler fell or canceled the input (territory defense mods): it
     *  retracts and releases the ticket of its chunks taken before the entrance and the target area. The error does not leave the tick world (there it would
     *  crash the server): the next flight of the same tick also tries to enter.
     */
    @GameTest(template = "range", timeoutTicks = 200, batch = "virtual_return_refused", skyAccess = true)
    public static void refusedReturnFromVirtualFlightLeavesNoTickets(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        // on the site, in the lane x, z 16–47, and the path 16 blocks ahead too; entities in a chunk tick when they are ready and
        //  neighbors in two chunks: in the test - immediately, without waiting for background generation
        Vec3 start = Vec3.atCenterOf(h.absolutePos(new BlockPos(20, 11, 30))).add(0, 20, 0);
        ChunkPos from = new ChunkPos(BlockPos.containing(start)), to = new ChunkPos(BlockPos.containing(start.add(40, 0, 4)));
        for (int x = Math.min(from.x, to.x) - 2; x <= Math.max(from.x, to.x) + 2; x++) {
            for (int z = Math.min(from.z, to.z) - 2; z <= Math.max(from.z, to.z) + 2; z++) level.getChunk(x, z);
        }
        StrikeProjectile crashing = ModEntities.DRONE.get().create(level);
        StrikeProjectile vetoed = ModEntities.DRONE.get().create(level);
        for (StrikeProjectile p : List.of(crashing, vetoed)) {
            Vec3 at = p == crashing ? start : start.add(0, 0, 4);
            Vec3 target = at.add(300, -20, 0);
            p.launch(at, new Target.Point(target), target, null);
            p.setRoute(Route.direct());
        }
        Map<UUID, Long> tried = new HashMap<>();
        Map<UUID, Boolean> heldOnEntry = new HashMap<>();
        Consumer<EntityJoinLevelEvent> refuse = e -> {
            UUID id = e.getEntity().getUUID();
            if (id.equals(crashing.getUUID()) || id.equals(vetoed.getUUID())) {
                tried.put(id, h.getTick());
                heldOnEntry.put(id, chunkTickets(level, id) > 0 && flightTickets(level, id) > 0);
                if (id.equals(crashing.getUUID())) throw new IllegalStateException("check: entering the world crashes with an error");
                e.setCanceled(true);
            }
        };
        NeoForge.EVENT_BUS.addListener(refuse);
        StrikeGameTests.afterTest(h, () -> {
            NeoForge.EVENT_BUS.unregister(refuse);
            VirtualFlights.get(level).clear(level, p -> p == crashing || p == vetoed);
        });
        boolean[] launched = {false};
        h.onEachTick(() -> {
            // on a flight outside the world - when he can immediately return: the fallen entry is first in the list, canceled after it
            if (launched[0] || !crashing.canMaterialize(level) || !vetoed.canMaterialize(level)) return;
            VirtualFlights.launch(level, crashing);
            VirtualFlights.launch(level, vetoed);
            launched[0] = true;
        });
        h.succeedWhen(() -> {
            h.assertTrue(tried.size() == 2, "not both tried to enter the world: " + tried.size() + ", " + state(level, crashing) + "; " + state(level, vetoed));
            h.assertValueEqual(tried.get(vetoed.getUUID()), tried.get(crashing.getUUID()), "tick of an input canceled after a failed one");
            h.assertTrue(heldOnEntry.values().stream().allMatch(b -> b), "upon entering the world, the projectile did not hold ticket chunks and the target area - there is nothing to check");
            for (StrikeProjectile p : List.of(crashing, vetoed)) {
                UUID id = p.getUUID();
                h.assertTrue(p.isRemoved() && level.getEntity(id) == null && VirtualFlights.get(level).flights().stream().noneMatch(v -> v == p),
                        "the projectile that did not enter the world remained: " + state(level, p));
                h.assertValueEqual(chunkTickets(level, id), 0, "ticket chunks of a projectile not included in the world");
                h.assertValueEqual(flightTickets(level, id), 0, "ticket for the target area of a projectile that did not enter the world");
            }
        });
    }

    /**
     * A world without players (Nether, where no one has entered) after 300 tick entities stop ticking, and the ticket of the
     *  projectile is not considered forced loading: while the world is being hit, the world does not fall asleep. The projectile on the rail stands
     *  before being ignited and ticks in place - its age grows only while the world ticks.
     */
    @GameTest(template = "pad", timeoutTicks = 1200, batch = "empty_dimension")
    public static void strikeKeepsEmptyDimensionAwake(GameTestHelper h) {
        ServerLevel nether = h.getLevel().getServer().getLevel(Level.NETHER);
        h.assertTrue(nether != null && nether.players().isEmpty(), "no Nether without players");
        // above the bedrock roof, in the column of the test site (each launch has its own, distant coordinates)
        BlockPos at = new BlockPos(h.absolutePos(BlockPos.ZERO).getX(), 140, h.absolutePos(BlockPos.ZERO).getZ());
        ChunkPos chunk = new ChunkPos(at);
        UUID holder = UUID.randomUUID();
        ChunkTickets.hold(nether, holder, chunk.toLong(), true);
        // entities in a chunk tick when they are ready and neighbors in two chunks: in the test - immediately, without waiting for background generation
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) nether.getChunk(chunk.x + dx, chunk.z + dz);
        }
        Vec3 rail = Vec3.atBottomCenterOf(at);
        CruiseMissileEntity m = ModEntities.CRUISE_MISSILE.get().create(nether);
        m.placeOnLauncher(rail, 0, 40, 5000, 0, new Target.Point(rail.add(0, 0, 3000)), rail.add(0, 0, 3000), null);
        m.setRoute(Route.direct());
        long[] placed = {-1};
        h.onEachTick(() -> {
            if (placed[0] < 0 && nether.isPositionEntityTicking(at)) {
                nether.addFreshEntity(m);
                placed[0] = h.getTick();
            }
        });
        h.succeedWhen(() -> {
            h.assertTrue(placed[0] >= 0 && h.getTick() - placed[0] >= 400, "wait for 400 tick after launch: " + state(nether, m));
            // without the impact, the world would fall asleep no later than 300 tick, and the age of the projectile would stop
            h.assertTrue(m.age() >= 390, "the world without players fell asleep in the middle of the blow: " + state(nether, m));
            m.discard();
            ChunkTickets.hold(nether, holder, chunk.toLong(), false);
        });
    }

    private static String state(ServerLevel level, StrikeProjectile p) {
        return "removed=" + p.getRemovalReason() + " virtual=" + VirtualFlights.get(level).flights().stream().anyMatch(v -> v.getUUID().equals(p.getUUID()))
                + " phase=" + p.flightPhase() + " ticking=" + level.isPositionEntityTicking(p.blockPosition()) + " age=" + p.age();
    }

    /**
     * ticket for the target area of this projectile (the key is its UUID): region ticket {@code FlightTickets} and, until the area
     *  is completely ready, loading ticket {@code AreaLoader}.
     */
    private static int flightTickets(ServerLevel level, UUID id) {
        return TicketProbe.count(level, "incoming_flight", id) + TicketProbe.count(level, "incoming_area_load", id);
    }

    /** district ticket without {@code AreaLoader} - vanilla region ticket for the entire district at once (verification control). */
    private static final TicketType<UUID> PLAIN_AREA = TicketType.create("incoming_test_plain_area", Comparator.<UUID>naturalOrder());

    /**
     * Target areas in the fresh world ({@code AreaLoader}): at any tick, the blocks of the area tick only where all
     *  neighboring chunks are ready (otherwise the hive at the edge of the area, releasing a bee, loads its neighbor synchronously, and the server costs
     * 2–18 s); the district has one region ticket, and its square is ready; the loading ticket costs until the entire area is taken;
     * entities in the center are ticking; the released area does not leave a ticket. Control - a vanilla region ticket for a fresh
     * region without {@code AreaLoader}: the check should catch ticking blocks next to an unready chunk.
     */
    @GameTest(template = "range", timeoutTicks = 6000, batch = "tickets_background", skyAccess = true)
    public static void targetAreasTickOnlyNextToReadyChunks(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        // areas are loaded in the background: the deadline is in-game (the GameTest server ticks without pauses, on CI it would have passed before generation)
        StrikeGameTests.gameSpeed(h);
        ChunkPos base = new ChunkPos(h.absolutePos(BlockPos.ZERO));
        ChunkPos[] centre = {new ChunkPos(base.x + 40, base.z), new ChunkPos(base.x + 70, base.z + 30), new ChunkPos(base.x - 50, base.z - 40)};
        int[] distance = {FlightTickets.DISTANCE, FlightTickets.DISTANCE, 6};
        UUID[] id = {UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID()};
        ChunkPos plain = new ChunkPos(base.x - 40, base.z + 60);
        UUID plainId = UUID.randomUUID();
        for (int i = 0; i < 3; i++) {
            h.assertFalse(Terrain.ready(level, centre[i].x, centre[i].z), "district " + i + " not fresh");
            FlightTickets.hold(level, centre[i], distance[i], id[i], true);
        }
        h.assertFalse(Terrain.ready(level, plain.x, plain.z), "control area is not fresh");
        level.getChunkSource().addRegionTicket(PLAIN_AREA, plain, FlightTickets.DISTANCE, plainId);
        int[] tick = {0}, plainTicking = {-1}, plainViolations = {0};
        int[] ticking = {-1, -1, -1};
        h.onEachTick(() -> {
            tick[0]++;
            for (int i = 0; i < 3; i++) {
                String where = "district " + i + ", tick " + tick[0] + ": ";
                String bad = tickingNextToUnready(level, centre[i], distance[i] + 1);
                if (bad != null) throw new GameTestAssertException(where + "blocks ticking in " + bad);
                List<Integer> radii = regionRadii(level, "incoming_flight", id[i]);
                if (radii.size() > 1) throw new GameTestAssertException(where + "ticket region " + radii);
                int r = radii.isEmpty() ? -1 : radii.get(0);
                for (int dx = -r; dx <= r; dx++) {
                    for (int dz = -r; dz <= r; dz++) {
                        if (!Terrain.ready(level, centre[i].x + dx, centre[i].z + dz)) throw new GameTestAssertException(where + "ticket region radius " + r + " to an unready chunk " + dx + " " + dz);
                    }
                }
                int load = TicketProbe.count(level, "incoming_area_load", id[i]);
                if (load != (r == distance[i] ? 0 : 1)) throw new GameTestAssertException(where + "ticket loading " + load + " at radius " + r);
                if (ticking[i] < 0 && level.isPositionEntityTicking(centre[i].getMiddleBlockPosition(64))) ticking[i] = tick[0];
            }
            if (tickingNextToUnready(level, plain, FlightTickets.DISTANCE + 1) != null) plainViolations[0]++;
            if (plainTicking[0] < 0 && level.isPositionEntityTicking(plain.getMiddleBlockPosition(64))) plainTicking[0] = tick[0];
            boolean done = plainTicking[0] >= 0;
            for (int i = 0; i < 3; i++) done &= ticking[i] >= 0 && regionRadii(level, "incoming_flight", id[i]).equals(List.of(distance[i]));
            if (!done) return;
            for (int i = 0; i < 3; i++) FlightTickets.hold(level, centre[i], distance[i], id[i], false);
            level.getChunkSource().removeRegionTicket(PLAIN_AREA, plain, FlightTickets.DISTANCE, plainId);
            for (int i = 0; i < 3; i++) h.assertTrue(flightTickets(level, id[i]) == 0, "area ticket " + i + " stayed after vacation");
            Incoming.LOG.info("Target areas in the background: entities in the center are ticking at tick {}, the entire area is taken at {}; vanilla ticket - for {}, ticking blocks next to the unready chunk it has {} tick",
                    java.util.Arrays.toString(ticking), tick[0], plainTicking[0], plainViolations[0]);
            h.assertTrue(plainViolations[0] > 0, "the check did not catch a vanilla region ticket in a fresh area");
            h.succeed();
        });
    }

    private static final TicketType<UUID> SHARED_A = TicketType.create("incoming_test_shared_a", Comparator.<UUID>naturalOrder());
    private static final TicketType<UUID> SHARED_B = TicketType.create("incoming_test_shared_b", Comparator.<UUID>naturalOrder());

    /**
     * Two areas with the same center, radius and key, but different types (like a crater and a queue of destruction of a nuclear
     *  strike on the same chunk): releasing one while both are still loading does not remove the loading of the other, and that one is loaded
     *  and is taken entirely. Vanilla ticket one per (type, level, value) - with a general loading ticket, the second district
     *  remained without a ticket forever.
     */
    @GameTest(template = "range", timeoutTicks = 3000, batch = "tickets_shared", skyAccess = true)
    public static void releasingOneAreaKeepsTwinLoading(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        StrikeGameTests.gameSpeed(h);
        ChunkPos base = new ChunkPos(h.absolutePos(BlockPos.ZERO));
        ChunkPos centre = new ChunkPos(base.x + 60, base.z - 60);
        UUID key = UUID.randomUUID();
        AreaLoader areas = StrikeWorld.get(level).areas();
        AreaLoader.Area a = new AreaLoader.Area(SHARED_A, centre, 2, key), b = new AreaLoader.Area(SHARED_B, centre, 2, key);
        h.assertFalse(Terrain.ready(level, centre.x, centre.z), "the area is not fresh");
        areas.hold(level, a);
        areas.hold(level, b);
        h.assertTrue(TicketProbe.count(level, "incoming_area_load", key) == 2, "two districts do not have two ticket loadings");
        areas.release(level, a);
        h.assertTrue(TicketProbe.count(level, "incoming_area_load", key) == 1, "the vacation of one area took off the load of another");
        h.onEachTick(() -> {
            if (!regionRadii(level, "incoming_test_shared_b", key).equals(List.of(2))) return;
            h.assertTrue(regionRadii(level, "incoming_test_shared_a", key).isEmpty(), "the released area is retaken");
            areas.release(level, b);
            h.assertTrue(TicketProbe.count(level, "incoming_area_load", key) + TicketProbe.count(level, "incoming_test_shared_b", key) == 0, "area tickets left after vacation");
            h.succeed();
        });
    }

    private static final TicketType<UUID> RELEASED = TicketType.create("incoming_test_released", Comparator.<UUID>naturalOrder());

    /**
     * Release of many districts at once (Valley release; at once - tick in 5–7 s saving ~9 thousand chunks):
     *  districts are released in turn, and at the end tick at vanilla on unloading ({@link AreaLoader#unloading}) no more than its
     *  threshold (2000 holders; what's more, {@code ChunkMap.processUnloads} saves in the next tick at once), its pace without
     *  free time (200) and one area. Without a queue there is 3645 with a limit of 2729. The released area does not immediately
     *  tick, the queue reaches the end, each area in the queue has a release ticket and no one else has it. The area taken by
     *  again while waiting in line ticks right away. The area that was still loading is released without a queue (its generation more than
     *  is not needed).
     */
    @GameTest(template = "range", timeoutTicks = 4000, batch = "tickets_release", skyAccess = true)
    public static void releasedAreasUnloadInTurn(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        ChunkMap map = level.getChunkSource().chunkMap;
        AreaLoader areas = StrikeWorld.get(level).areas();
        ChunkPos base = new ChunkPos(h.absolutePos(BlockPos.ZERO));
        int distance = 2;
        // district holders - square up to level 44 (side 27): districts through 30 chunks do not share any
        int side = 2 * (distance + ChunkLevel.RADIUS_AROUND_FULL_CHUNK) + 1;
        List<AreaLoader.Area> list = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            AreaLoader.Area a = new AreaLoader.Area(RELEASED, new ChunkPos(base.x + 40 + 30 * i, base.z + 40), distance, UUID.randomUUID());
            h.assertFalse(Terrain.ready(level, a.centre().x, a.centre().z), "district " + i + " not fresh");
            areas.hold(level, a);
            list.add(a);
        }
        AreaLoader.Area kept = list.get(list.size() - 1);
        AreaLoader.Area loading = new AreaLoader.Area(RELEASED, new ChunkPos(base.x + 40, base.z + 80), distance, UUID.randomUUID());
        areas.hold(level, loading);
        areas.release(level, loading);
        h.assertTrue(areas.releasing() == 0 && TicketProbe.count(level, "incoming_area_load", loading.key()) == 0, "the area that was still loading is waiting in line");
        int limit = 2000 + 200 + side * side;
        int[] tick = {0}, releasedAt = {-1}, keptReleasedAt = {-1}, waiting = {-1}, maxUnloading = {0};
        long[] last = {System.nanoTime()};
        h.onEachTick(() -> {
            tick[0]++;
            if (releasedAt[0] < 0) {
                // areas are loaded in the background at the pace of the game (on the CI server GameTest without pauses the deadline would have passed before generation);
                long deadline = last[0] + 50_000_000L, left;
                while ((left = deadline - System.nanoTime()) > 0) LockSupport.parkNanos(left);
                last[0] = System.nanoTime();
                for (AreaLoader.Area a : list) if (!regionRadii(level, "incoming_test_released", a.key()).equals(List.of(distance))) return;
                for (AreaLoader.Area a : list) areas.release(level, a);
                areas.hold(level, kept);
                h.assertTrue(regionRadii(level, "incoming_test_released", kept.key()).equals(List.of(distance)), "the area taken again is not taken entirely");
                releasedAt[0] = tick[0];
                return;
            }
            String where = "tick " + (tick[0] - releasedAt[0]) + " after vacation: ";
            int unloading = AreaLoader.unloading(map);
            maxUnloading[0] = Math.max(maxUnloading[0], unloading);
            if (unloading > limit) throw new GameTestAssertException(where + "on unloading " + unloading + " holders, limit " + limit);
            int tickets = TicketProbe.count(level, "incoming_area_release");
            if (tickets != areas.releasing()) throw new GameTestAssertException(where + "vacation ticket " + tickets + " districts have a queue " + areas.releasing());
            for (AreaLoader.Area a : list) {
                boolean ticks = level.shouldTickBlocksAt(a.centre().toLong()) || level.isPositionEntityTicking(a.centre().getMiddleBlockPosition(64));
                if (ticks != (a == kept && keptReleasedAt[0] < 0)) throw new GameTestAssertException(where + "district " + list.indexOf(a) + (ticks ? " ticking" : " not ticking"));
            }
            if (waiting[0] < 0) waiting[0] = areas.releasing();
            if (areas.releasing() > 0) return;
            if (keptReleasedAt[0] < 0) {
                areas.release(level, kept);
                keptReleasedAt[0] = tick[0];
                return;
            }
            h.assertTrue(waiting[0] > 0, "districts were released at once, without queue");
            Incoming.LOG.info("Release of districts in turn: after the first tick they waited for {} from {}, the queue passed for {} tick, at most {} holders were unloaded (limit {})",
                    waiting[0], list.size() - 1, keptReleasedAt[0] - releasedAt[0], maxUnloading[0], limit);
            h.succeed();
        });
    }

    /**
     * Vacation of areas on the server without free time in tick (for GameTest - {@code /tick sprint}: vanilla ticks with
     * {@code hasTimeLeft = () -> false}). Vanilla then holds up to 2000 holders at the unloading and dismantles only what is
     *  above, and the queue that would be waiting for a place at the unloading would release the area once every 100 tick: after ~60 volleys -
     * 12,5 thousand ticket release and ~25 thousand downloaded chunks. Therefore, the queue goes at a vanilla pace (it translates into
     *  unloading 200 holders per tick and without free time): 8 districts without common holders go per tick to their
     *  squares at this pace and two ticks per area, at unloading ({@link AreaLoader#unloading}) at the end of the tick not more
     * 2000, this tempo and one area (for a tick vanilla retains in addition to its 2000 no more than its tempo and one area -
     *  and in the first tick, when free time returns), the vacation ticket is exactly for the areas in the queue, the released ones do not tick.
     */
    @GameTest(template = "range", timeoutTicks = 3000, batch = "tickets_release_busy", skyAccess = true)
    public static void releasedAreasUnloadWithoutFreeTime(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        ChunkMap map = level.getChunkSource().chunkMap;
        AreaLoader areas = StrikeWorld.get(level).areas();
        ChunkPos base = new ChunkPos(h.absolutePos(BlockPos.ZERO));
        int distance = 2;
        int side = 2 * (distance + ChunkLevel.RADIUS_AROUND_FULL_CHUNK) + 1;
        List<AreaLoader.Area> list = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            AreaLoader.Area a = new AreaLoader.Area(RELEASED, new ChunkPos(base.x + 40 + 30 * i, base.z - 100), distance, UUID.randomUUID());
            areas.hold(level, a);
            list.add(a);
        }
        StrikeGameTests.afterTest(h, () -> {
            level.getServer().tickRateManager().stopSprinting();
            for (AreaLoader.Area a : list) areas.release(level, a);
        });
        // deadline - squares of all districts at the vanilla pace and two ticks per district (the queue waiting for a place at the unloading - by
        // 100 tick per district)
        int limit = list.size() * side * side / 200 + 2 * list.size();
        int bound = 2000 + 200 + side * side;
        int[] tick = {0}, releasedAt = {-1}, maxQueued = {0}, maxUnloading = {0};
        long[] last = {System.nanoTime()};
        h.onEachTick(() -> {
            tick[0]++;
            if (releasedAt[0] < 0) {
                // areas are loaded in the background at the pace of the game, vacation then - without pauses and without free time
                long deadline = last[0] + 50_000_000L, left;
                while ((left = deadline - System.nanoTime()) > 0) LockSupport.parkNanos(left);
                last[0] = System.nanoTime();
                for (AreaLoader.Area a : list) if (!regionRadii(level, "incoming_test_released", a.key()).equals(List.of(distance))) return;
                level.getServer().tickRateManager().requestGameToSprint(3000);
                for (AreaLoader.Area a : list) areas.release(level, a);
                releasedAt[0] = tick[0];
                return;
            }
            int ticks = tick[0] - releasedAt[0];
            String where = "tick " + ticks + " after vacation: ";
            int queued = AreaLoader.unloading(map);
            maxQueued[0] = Math.max(maxQueued[0], queued);
            maxUnloading[0] = Math.max(maxUnloading[0], map.toDrop.size() + map.pendingUnloads.size());
            if (queued > bound) throw new GameTestAssertException(where + "on unloading " + queued + " holders, limit " + bound);
            int tickets = TicketProbe.count(level, "incoming_area_release");
            if (tickets != areas.releasing()) throw new GameTestAssertException(where + "vacation ticket " + tickets + " districts have a queue " + areas.releasing());
            for (AreaLoader.Area a : list) {
                if (level.shouldTickBlocksAt(a.centre().toLong()) || level.isPositionEntityTicking(a.centre().getMiddleBlockPosition(64))) {
                    throw new GameTestAssertException(where + "released area " + list.indexOf(a) + " ticking");
                }
            }
            if (areas.releasing() > 0) {
                if (ticks > limit) throw new GameTestAssertException(where + "still in line " + areas.releasing() + " from " + list.size() + " districts, term " + limit);
                return;
            }
            level.getServer().tickRateManager().stopSprinting();
            Incoming.LOG.info("Vacation of areas without free time: the queue from {} passed for {} tick (term {}), at unloading at most {} (limit {}), with generation holders {}",
                    list.size(), ticks, limit, maxQueued[0], bound, maxUnloading[0]);
            // vanilla without free time kept the upload full: otherwise the idle server would have been checked
            h.assertTrue(maxUnloading[0] > 2000, "on unloading there is no more than 2000 - the server parsed it during the time free in tick");
            h.succeed();
        });
    }

    /**
     * A salvo on a busy server (no spare tick time, {@code /tick sprint}): 4 MLRS rockets into fresh areas
     * 300 blocks apart. After the projectiles finish and the mod releases every area (targets, projectile chunks,
     * explosions), the release queue empties within {@link #SALVO_DRAIN} ticks and no mod tickets remain in the world.
     * A queue waiting for room in unloading would release an area once every 100 ticks: after 100 ticks, 17 salvo
     * areas would still wait here, with 2000 holders awaiting unloading that vanilla does not process without spare time.
     * If the wait fails, fail before the deadline with what was still pending (areas, projectiles), rather than a silent timeout.
     */
    @GameTest(template = "range", timeoutTicks = SALVO_TIMEOUT, batch = "salvo_busy_tickets", skyAccess = true)
    public static void salvoLeavesNoTicketsOnBusyServer(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        AreaLoader areas = StrikeWorld.get(level).areas();
        StrikeGameTests.gameSpeed(h);
        StrikeGameTests.afterTest(h, () -> level.getServer().tickRateManager().stopSprinting());
        Vec3 start = Vec3.atCenterOf(h.absolutePos(RANGE_CENTER)).add(0, 10, 0);
        List<UUID> salvo = new ArrayList<>();
        int[] tick = {0}, firedAt = {-1}, goneAt = {-1}, releasedAt = {-1}, queued = {0};
        h.onEachTick(() -> {
            tick[0]++;
            if (tick[0] >= SALVO_TIMEOUT - 10) {
                List<String> rockets = salvo.stream().map(id -> level.getEntity(id) instanceof StrikeProjectile p && !p.isRemoved()
                        ? "in the world at " + p.blockPosition().toShortString()
                        : VirtualFlights.get(level).flights().stream().filter(v -> v.getUUID().equals(id)).findFirst()
                        .map(v -> "out of the world at " + v.blockPosition().toShortString()).orElse("gone")).toList();
                throw new GameTestAssertException("the salvo did not wait: launch on tick " + firedAt[0] + ", the shells died down " + goneAt[0]
                        + ", areas released " + releasedAt[0] + "; rockets " + rockets + "; districts " + areas.describe()
                        + "; hits in line " + StrikeWorld.get(level).impacts().size());
            }
            if (firedAt[0] >= 0) queued[0] = Math.max(queued[0], areas.releasing());
            if (firedAt[0] < 0) {
                // the queue of the previous batch is empty: everything else in it comes from this salvo
                if (areas.releasing() > 0) return;
                level.getServer().tickRateManager().requestGameToSprint(2400);
                for (int i = 1; i <= 4; i++) {
                    Vec3 aim = new Vec3(start.x + 300 * i, level.getSeaLevel() + 60, start.z);
                    RocketEntity m = ModEntities.ROCKET.get().create(level);
                    m.launchFrom(start.add(0, 0, 3 * i), new Target.Point(aim), aim, null);
                    level.addFreshEntity(m);
                    salvo.add(m.getUUID());
                }
                firedAt[0] = tick[0];
                return;
            }
            if (goneAt[0] < 0) {
                boolean flying = salvo.stream().anyMatch(id -> level.getEntity(id) instanceof StrikeProjectile p && !p.isRemoved()
                        || VirtualFlights.get(level).flights().stream().anyMatch(v -> v.getUUID().equals(id)));
                if (!flying) goneAt[0] = tick[0];
                return;
            }
            // areas of explosions live until their explosions and crumbling end
            if (releasedAt[0] < 0) {
                if (areas.size() > 0) return;
                releasedAt[0] = tick[0];
            }
            int ticks = tick[0] - releasedAt[0];
            if (areas.releasing() > 0) {
                if (ticks > SALVO_DRAIN) {
                    throw new GameTestAssertException("through " + ticks + " tick after the release of all areas in the queue " + areas.releasing()
                            + ", vacation ticket " + TicketProbe.count(level, "incoming_area_release") + "; toDrop " + level.getChunkSource().chunkMap.toDrop.size()
                            + ", pendingUnloads " + level.getChunkSource().chunkMap.pendingUnloads.size());
                }
                return;
            }
            level.getServer().tickRateManager().stopSprinting();
            List<String> left = new ArrayList<>();
            for (String type : List.of("incoming_area_release", "incoming_area_load", "incoming_projectile", "incoming_flight", "incoming_blast")) {
                int n = TicketProbe.count(level, type);
                if (n > 0) left.add(type + " " + n);
            }
            h.assertTrue(left.isEmpty(), "after the volley there were tickets left: " + left);
            // the areas of explosions for the "Grad" charge are small (~8 blocks), and the release queue often disassembles them in the same tick; The
            //  queue itself on a busy server is checked by releasedAreasUnloadWithoutFreeTime and releasedAreasUnloadInTurn
            Incoming.LOG.info("Salvo on a busy server: shells died down at tick {} after launch, areas released at {}, queue empty after {} tick, queue at most {}",
                    goneAt[0] - firedAt[0], releasedAt[0] - firedAt[0], ticks, queued[0]);
            h.succeed();
        });
    }

    /** Duration of the release queue after a salvo on a busy server, tick (waiting for a place to unload - area in 100 tick). */
    private static final int SALVO_DRAIN = 100;
    /** Deadline for checking a salvo on a busy server, tick. */
    private static final int SALVO_TIMEOUT = 2400;

    /** The server thread stands at tick longer than 2 s ({@code StallWatch}): when it moves on, the stop line goes to the log. */
    @GameTest(template = "range", timeoutTicks = 2000, batch = "stall_watch", skyAccess = true)
    public static void longStallIsReported(GameTestHelper h) {
        // observer's response - half a second after stopping: time limit - game
        StrikeGameTests.gameSpeed(h);
        int before = StallWatch.reported();
        boolean[] stalled = {false};
        h.onEachTick(() -> {
            if (!stalled[0]) {
                stalled[0] = true;
                // parkNanos wakes up earlier (chunk tasks wake up the server thread): wait until the deadline in a loop
                long deadline = System.nanoTime() + 3_000_000_000L, left;
                while ((left = deadline - System.nanoTime()) > 0) LockSupport.parkNanos(left);
                return;
            }
            if (StallWatch.reported() > before) h.succeed();
        });
    }

    /** Chunk in square {@code radius} around {@code c}, where blocks are ticking and the neighbor is not ready - or {@code null}. */
    private static String tickingNextToUnready(ServerLevel level, ChunkPos c, int radius) {
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                int x = c.x + dx, z = c.z + dz;
                if (!level.shouldTickBlocksAt(ChunkPos.asLong(x, z))) continue;
                for (int nx = -1; nx <= 1; nx++) {
                    for (int nz = -1; nz <= 1; nz++) {
                        if (!Terrain.ready(level, x + nx, z + nz)) return "chunk " + dx + " " + dz + " (neighbor " + nx + " " + nz + " not ready)";
                    }
                }
            }
        }
        return null;
    }

    /** Ticket radii of the region {@code type} with the key {@code id} (ticket level - 33 - radius). */
    static List<Integer> regionRadii(ServerLevel level, String type, UUID id) {
        try {
            Field f = DistanceManager.class.getDeclaredField("tickets");
            f.setAccessible(true);
            @SuppressWarnings("unchecked")
            var map = (Long2ObjectOpenHashMap<SortedArraySet<Ticket<?>>>) f.get(level.getChunkSource().chunkMap.getDistanceManager());
            List<Integer> radii = new java.util.ArrayList<>();
            for (SortedArraySet<Ticket<?>> set : map.values()) {
                for (Ticket<?> t : set) {
                    if (t.getType().toString().equals(type) && id.equals(TicketProbe.key(t))) radii.add(33 - t.getTicketLevel());
                }
            }
            return radii;
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    /** ticket of your chunk and the chunk in front ({@code ChunkTickets}, key - UUID projectile). */
    private static int chunkTickets(ServerLevel level, UUID id) {
        return TicketProbe.count(level, "incoming_projectile", id);
    }
}
