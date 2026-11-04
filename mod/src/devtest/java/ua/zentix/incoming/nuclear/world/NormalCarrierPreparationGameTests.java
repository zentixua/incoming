package ua.zentix.incoming.nuclear.world;

import java.lang.reflect.Field;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import ua.zentix.incoming.Incoming;
import ua.zentix.incoming.IncomingConfig;
import ua.zentix.incoming.entity.StrikeProjectile;
import ua.zentix.incoming.gametest.StrikeGameTests;
import ua.zentix.incoming.nuclear.Detonation;
import ua.zentix.incoming.nuclear.NuclearEvents;
import ua.zentix.incoming.nuclear.NuclearStrikes;
import ua.zentix.incoming.nuclear.NuclearWarhead;
import ua.zentix.incoming.strike.Loadout;
import ua.zentix.incoming.strike.StrikeService;
import ua.zentix.incoming.strike.VirtualFlights;
import ua.zentix.incoming.strike.Waypoints;
import ua.zentix.incoming.strike.WeaponType;
import ua.zentix.incoming.target.Target;
import ua.zentix.incoming.util.Terrain;

/** Real facade receipts, registered flight and exact handoff; no injected READY or impact calls. */
@GameTestHolder(Incoming.MOD_ID)
@PrefixGameTestTemplate(false)
public final class NormalCarrierPreparationGameTests {
    private NormalCarrierPreparationGameTests() {}

    @GameTest(template = "range", batch = "nuke_normal_missile_ground", timeoutTicks = 4000, skyAccess = true)
    public static void missileGroundConsumesDurableNominalPlans(GameTestHelper h) { flight(h, WeaponType.MISSILE, true, false, false); }

    @GameTest(template = "range", batch = "nuke_normal_missile_air", timeoutTicks = 4000, skyAccess = true)
    public static void missileAirConsumesDurableNominalPlans(GameTestHelper h) { flight(h, WeaponType.MISSILE, true, true, false); }

    @GameTest(template = "range", batch = "nuke_normal_missile_canopy", timeoutTicks = 4000, skyAccess = true)
    public static void missileCanopyUsesBothHeightmaps(GameTestHelper h) { flight(h, WeaponType.MISSILE, true, false, true); }

    @GameTest(template = "range", batch = "nuke_normal_bomber", timeoutTicks = 4000, skyAccess = true)
    public static void bomberNormalContactConsumesDurableNominalPlans(GameTestHelper h) { flight(h, WeaponType.BUNKER, false, false, false); }

    @GameTest(template = "range", batch = "nuke_normal_point", timeoutTicks = 4000, skyAccess = true)
    public static void knownPointUsesExistingGroundClamp(GameTestHelper h) { flight(h, WeaponType.MISSILE, false, false, false); }

    private static void flight(GameTestHelper h, WeaponType weapon, boolean surface, boolean air, boolean canopy) {
        StrikeGameTests.gameSpeed(h);
        ServerLevel level = h.getLevel();
        NuclearStrikes.clear(level);
        boolean carriers = IncomingConfig.SERVER.carrierNukes.get();
        boolean fires = IncomingConfig.SERVER.nukeFires.get();
        var preset = IncomingConfig.SERVER.scalePreset.get();
        double scale = IncomingConfig.SERVER.nukeEffectsScale.get();
        IncomingConfig.SERVER.scalePreset.set(IncomingConfig.ScalePreset.CUSTOM);
        IncomingConfig.SERVER.nukeEffectsScale.set(.02);
        IncomingConfig.SERVER.carrierNukes.set(true);
        IncomingConfig.SERVER.nukeFires.set(false);
        // A broad flat glass roof supplies actual nonempty changed-block plans at the sampled column.
        for (int x = 16; x < 48; x++) for (int z = 16; z < 48; z++) {
            h.setBlock(new BlockPos(x, 1, z), Blocks.STONE);
            h.setBlock(new BlockPos(x, 2, z), Blocks.GLASS);
            if (canopy) h.setBlock(new BlockPos(x, 5, z), Blocks.OAK_LEAVES.defaultBlockState()
                    .setValue(net.minecraft.world.level.block.LeavesBlock.PERSISTENT, true));
        }
        Vec3 target = Vec3.atCenterOf(h.absolutePos(new BlockPos(32, surface ? 2 : 12, 32)));
        Target order = surface ? new Target.Ground(target) : new Target.Point(target);
        UUID handle = UUID.randomUUID(), owner = UUID.randomUUID();
        int yield = 1;
        Object[] request = {null};
        Detonation[] expected = {null};
        long[] launchedAt = {-1};
        int[] identity = {-1};
        long beforePrepared = number(NuclearWorld.get(level).scars(), "appliedPrepared");
        StrikeGameTests.afterTest(h, () -> {
            VirtualFlights.get(level).clear(level, p -> owner.equals(p.ownerId()));
            var owned = new java.util.ArrayList<StrikeProjectile>();
            for (var e : level.getAllEntities()) if (e instanceof StrikeProjectile p && owner.equals(p.ownerId())) owned.add(p);
            owned.forEach(StrikeProjectile::discard);
            RuinPreparation.cancel(level, handle);
            NuclearStrikes.clear(level);
            RuinMatterGameTests.clearFixture(level);
            IncomingConfig.SERVER.carrierNukes.set(carriers);
            IncomingConfig.SERVER.nukeFires.set(fires);
            IncomingConfig.SERVER.scalePreset.set(preset);
            IncomingConfig.SERVER.nukeEffectsScale.set(scale);
        });
        h.runAtTickTime(2, () -> RuinPreparation.prepareCarrier(level, handle, owner, target, yield, surface, air, weapon));
        h.onEachTick(() -> {
            if (launchedAt[0] >= 0) {
                if (surface && weapon == WeaponType.MISSILE && level.getGameTime() == launchedAt[0] + 1) {
                    StrikeProjectile active = carrier(level, owner);
                    h.assertTrue(active != null, "carrier vanished before first tracker tick");
                    var tracked = ((Target.Ground) tracker(active).target()).surface(level);
                    h.assertTrue(trackerPoint(active).equals(tracked), "first tracker tick did not refine the original Ground target");
                }
                return;
            }
            if (RuinPreparation.status(level, handle).stage() != RuinPreparation.Stage.READY) return;
            Object p = request(level, handle);
            RetainedPlans plans = (RetainedPlans) value(p, "plans");
            h.assertTrue(plans.values().stream().anyMatch(plan -> plan != null && plan.changedBlocks() > 0),
                    "READY did not contain a nonempty actual ruin plan");
            h.assertTrue(plans.pendingWrites() == 0, "READY preceded durable plan receipts");
            request[0] = p;
            expected[0] = (Detonation) value(p, "geometry");
            var status = RuinPreparation.status(level, handle);
            Vec3 miss = RuinPreparation.carrierMiss(level, handle, weapon);
            Vec3 launch = status.resolvedTarget();
            Vec3 sampled = target.add(miss);
            var d = Terrain.estimate(level, Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                    (int) Math.floor(sampled.x), (int) Math.floor(sampled.z), Terrain.Allowed.CHUNK);
            var m = Terrain.estimate(level, Heightmap.Types.MOTION_BLOCKING,
                    (int) Math.floor(sampled.x), (int) Math.floor(sampled.z), Terrain.Allowed.CHUNK);
            h.assertTrue(d.source() == Terrain.Source.CHUNK && m.source() == Terrain.Source.CHUNK, "fixture terrain unready");
            double aimY = weapon == WeaponType.BUNKER ? d.y() - .5 : surface ? m.y() - .5 : target.y;
            Detonation geometry = NuclearWarhead.geometry(level, new Vec3(sampled.x, aimY, sampled.z), yield, air,
                    (float) IncomingConfig.SERVER.effectsScale());
            h.assertTrue(geometry.burst().equals(expected[0].burst()) && geometry.groundY() == expected[0].groundY(),
                    "preparation differs from existing nominal terminal law");
            h.assertTrue(StrikeService.launchPrepared(level, weapon, surface ? new Target.Ground(launch) : order, launch,
                    0, owner, null, false, new Loadout.Nuke(yield, air, true), Waypoints.NONE, null, handle, 1800).ok(), "normal launch refused");
            StrikeProjectile carrier = carrier(level, owner);
            h.assertTrue(carrier != null, "normal launch produced no carrier");
            identity[0] = carrier.nuclearPreparation();
            h.assertTrue(identity[0] >= 0, "normal launch lost explicit binding");
            if (surface && weapon == WeaponType.MISSILE)
                h.assertTrue(trackerPoint(carrier).equals(launch.add(miss)), "initial Ground launch height changed");
            h.assertTrue(!StrikeService.launchPrepared(level, weapon, order, launch, 0, owner, null, false,
                    new Loadout.Nuke(yield, air, true), Waypoints.NONE, null, handle, 1800).ok(), "duplicate commit launched");
            launchedAt[0] = level.getGameTime();
        });
        h.succeedWhen(() -> {
            h.assertTrue(launchedAt[0] >= 0, "waiting for real durable READY: " + RuinPreparation.status(level, handle));
            var events = NuclearEvents.get(level).detonations();
            h.assertTrue(events.size() == 1, "waiting for registered normal carrier flight termination");
            var event = events.getFirst();
            h.assertTrue(event.burst().equals(expected[0].burst()) && event.groundY() == expected[0].groundY(),
                    "actual flight missed nominal exact geometry: actual=" + event.burst() + " expected=" + expected[0].burst());
            h.assertTrue(number(request[0], "strike") == identity[0] && number(request[0], "detonation") == event.id(),
                    "actual event did not consume explicit prepared identity");
            h.assertTrue(number(NuclearWorld.get(level).scars(), "appliedPrepared") > beforePrepared,
                    "waiting for actual prepared-plan replacement");
            Incoming.LOG.info("CARRIER exact {} surface={} air={} canopy={} identity={} event={} burst={} ground={} flightTicks={}",
                    weapon, surface, air, canopy, identity[0], event.id(), event.burst(), event.groundY(), level.getGameTime() - launchedAt[0]);
        });
    }

    @GameTest(template = "range", batch = "nuke_normal_miss_sample", skyAccess = true)
    public static void repeatedPreparePollAndLaunchReadNeverRedrawSpecError(GameTestHelper h) {
        var level = h.getLevel();
        var target = Vec3.atCenterOf(h.absolutePos(new BlockPos(32, 2, 32)));
        for (WeaponType weapon : new WeaponType[]{WeaponType.MISSILE, WeaponType.BUNKER}) {
            long seed = 879341L;
            RandomSource expected = RandomSource.create(seed);
            Vec3 sample = weapon.spec().route().miss(expected);
            level.random.setSeed(seed);
            UUID handle = UUID.randomUUID(), owner = UUID.randomUUID();
            RuinPreparation.prepareCarrier(level, handle, owner, target, .00001, true, false, weapon);
            for (int i = 0; i < 8; i++) {
                RuinPreparation.prepareCarrier(level, handle, owner, target, .00001, true, false, weapon);
                RuinPreparation.keepAlive(level, handle, owner);
                RuinPreparation.status(level, handle);
                h.assertTrue(sample.equals(RuinPreparation.carrierMiss(level, handle, weapon)), "sample changed during request");
            }
            h.assertTrue(level.random.nextLong() == expected.nextLong(), "request consumed additional random draws");
            RuinPreparation.cancel(level, handle);
        }
        h.succeed();
    }

    private static ua.zentix.incoming.target.TargetTracker tracker(StrikeProjectile carrier) {
        try {
            Field f = StrikeProjectile.class.getDeclaredField("tracker"); f.setAccessible(true);
            return (ua.zentix.incoming.target.TargetTracker) f.get(carrier);
        } catch (ReflectiveOperationException e) { throw new AssertionError(e); }
    }
    private static Vec3 trackerPoint(StrikeProjectile carrier) { return tracker(carrier).point(); }

    private static StrikeProjectile carrier(ServerLevel level, UUID owner) {
        for (StrikeProjectile p : VirtualFlights.get(level).flights()) if (owner.equals(p.ownerId()) && !p.isRemoved()) return p;
        for (var e : level.getAllEntities()) if (e instanceof StrikeProjectile p && owner.equals(p.ownerId()) && !p.isRemoved()) return p;
        return null;
    }

    private static Object request(ServerLevel level, UUID handle) {
        @SuppressWarnings("unchecked") var all = (List<Object>) value(NuclearWorld.get(level).prep(), "preps");
        return all.stream().filter(p -> handle.equals(value(p, "handle")) && !Boolean.TRUE.equals(value(p, "draining"))).findFirst().orElseThrow();
    }
    private static Object value(Object o, String name) {
        try { Field f = o.getClass().getDeclaredField(name); f.setAccessible(true); return f.get(o); }
        catch (ReflectiveOperationException e) { throw new AssertionError(e); }
    }
    private static long number(Object o, String name) { return ((Number) value(o, name)).longValue(); }
}
