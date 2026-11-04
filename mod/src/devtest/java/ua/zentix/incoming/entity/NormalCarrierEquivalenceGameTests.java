package ua.zentix.incoming.entity;

import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import ua.zentix.incoming.Incoming;
import ua.zentix.incoming.IncomingConfig;
import ua.zentix.incoming.gametest.StrikeGameTests;
import ua.zentix.incoming.nuclear.NuclearEvents;
import ua.zentix.incoming.nuclear.NuclearStrikes;
import ua.zentix.incoming.nuclear.world.RuinPreparation;
import ua.zentix.incoming.strike.Loadout;
import ua.zentix.incoming.strike.StrikeService;
import ua.zentix.incoming.strike.VirtualFlights;
import ua.zentix.incoming.strike.Waypoints;
import ua.zentix.incoming.strike.WeaponType;
import ua.zentix.incoming.target.Target;

/** Same sampled error, route random input, air/time and readiness schedule through real registered flights. */
@GameTestHolder(Incoming.MOD_ID)
@PrefixGameTestTemplate(false)
public final class NormalCarrierEquivalenceGameTests {
    private NormalCarrierEquivalenceGameTests() {}

    @GameTest(template = "range", batch = "nuke_carrier_equivalent_ground", timeoutTicks = 4000, skyAccess = true)
    public static void groundFlightMatchesOrdinaryLaunch(GameTestHelper h) { pair(h, false); }

    @GameTest(template = "range", batch = "nuke_carrier_equivalent_air", timeoutTicks = 4000, skyAccess = true)
    public static void airFlightMatchesOrdinaryLaunch(GameTestHelper h) { pair(h, true); }

    private static void pair(GameTestHelper h, boolean air) {
        StrikeGameTests.gameSpeed(h);
        var level = h.getLevel();
        NuclearStrikes.clear(level);
        boolean damage = IncomingConfig.SERVER.nukeBlockDamage.get(), carriers = IncomingConfig.SERVER.carrierNukes.get();
        IncomingConfig.SERVER.nukeBlockDamage.set(false);
        IncomingConfig.SERVER.carrierNukes.set(true);
        UUID handle = UUID.randomUUID(), preparedOwner = UUID.randomUUID(), ordinaryOwner = UUID.randomUUID();
        Vec3 target = Vec3.atCenterOf(h.absolutePos(new BlockPos(32, 1, 32)));
        long seed = 781329L;
        level.random.setSeed(seed);
        RuinPreparation.prepareCarrier(level, handle, preparedOwner, target, 1, true, air, WeaponType.MISSILE);
        long[] launched = {-1};
        int[] compared = {0};
        StrikeGameTests.afterTest(h, () -> {
            VirtualFlights.get(level).clear(level, p -> preparedOwner.equals(p.ownerId()) || ordinaryOwner.equals(p.ownerId()));
            var owned = new java.util.ArrayList<StrikeProjectile>();
            for (var e : level.getAllEntities()) if (e instanceof StrikeProjectile p
                    && (preparedOwner.equals(p.ownerId()) || ordinaryOwner.equals(p.ownerId()))) owned.add(p);
            owned.forEach(StrikeProjectile::discard);
            RuinPreparation.cancel(level, handle);
            NuclearStrikes.clear(level);
            IncomingConfig.SERVER.nukeBlockDamage.set(damage);
            IncomingConfig.SERVER.carrierNukes.set(carriers);
        });
        h.onEachTick(() -> {
            if (launched[0] < 0) {
                var status = RuinPreparation.status(level, handle);
                if (status.stage() != RuinPreparation.Stage.READY) return;
                Vec3 point = status.resolvedTarget();
                Target order = new Target.Ground(point);
                Loadout.Nuke nuke = new Loadout.Nuke(1, air, true);
                // Control only the shared route RNG inputs, not flight state or terrain.
                var expectedRandom = net.minecraft.util.RandomSource.create(seed);
                WeaponType.MISSILE.spec().route().miss(expectedRandom);
                expectedRandom.nextBoolean(); // Ordinary guided route side, following its two Gaussian draws.
                long afterLaunch = expectedRandom.nextLong();
                level.random.setSeed(seed);
                Vec3 miss = WeaponType.MISSILE.spec().route().miss(level.random);
                h.assertTrue(miss.equals(RuinPreparation.carrierMiss(level, handle, WeaponType.MISSILE)), "different route samples");
                h.assertTrue(StrikeService.launchPrepared(level, WeaponType.MISSILE, order, point, 0, preparedOwner, null,
                        false, nuke, Waypoints.NONE, null, handle, 1800).ok(), "prepared launch refused");
                h.assertTrue(level.random.nextLong() == afterLaunch, "prepared launch redrew miss or changed route RNG consumption");
                level.random.setSeed(seed);
                h.assertTrue(StrikeService.launch(level, WeaponType.MISSILE, order, point, 0, ordinaryOwner,
                        false, nuke, Waypoints.NONE, null).ok(), "ordinary launch refused");
                h.assertTrue(level.random.nextLong() == afterLaunch, "ordinary launch changed RNG consumption");
                launched[0] = level.getGameTime();
            }
            StrikeProjectile prepared = find(h, preparedOwner), ordinary = find(h, ordinaryOwner);
            if (prepared == null || ordinary == null) {
                h.assertTrue(prepared == null && ordinary == null, "paired flights terminated on different ticks");
                return;
            }
            h.assertTrue(prepared.position().equals(ordinary.position()) && prepared.aimPoint().equals(ordinary.aimPoint())
                            && prepared.flightPhase() == ordinary.flightPhase() && prepared.speed == ordinary.speed,
                    "paired flight state diverged at tick " + (level.getGameTime() - launched[0]));
            compared[0]++;
        });
        h.succeedWhen(() -> {
            var events = NuclearEvents.get(level).detonations();
            h.assertTrue(events.size() == 2, "waiting for both actual terminal events");
            var a = events.getFirst(); var b = events.getLast();
            h.assertTrue(compared[0] > 100, "pair did not exercise an ordinary route");
            h.assertTrue(a.burst().equals(b.burst()) && a.groundY() == b.groundY() && a.gameTime() == b.gameTime(),
                    "preparation changed actual terminal geometry or flight duration");
            Incoming.LOG.info("CARRIER paired air={} comparedTicks={} terminalTick={} burst={} ground={}",
                    air, compared[0], a.gameTime() - launched[0], a.burst(), a.groundY());
        });
    }

    private static StrikeProjectile find(GameTestHelper h, UUID owner) {
        for (var p : VirtualFlights.get(h.getLevel()).flights()) if (owner.equals(p.ownerId()) && !p.isRemoved()) return p;
        for (var e : h.getLevel().getAllEntities()) if (e instanceof StrikeProjectile p && owner.equals(p.ownerId()) && !p.isRemoved()) return p;
        return null;
    }
}
