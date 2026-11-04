package ua.zentix.incoming.entity;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import ua.zentix.incoming.Incoming;
import ua.zentix.incoming.nuclear.Detonation;
import ua.zentix.incoming.nuclear.NuclearEvents;
import ua.zentix.incoming.nuclear.NuclearStrikes;
import ua.zentix.incoming.nuclear.NuclearWarhead;
import ua.zentix.incoming.registry.ModEntities;
import ua.zentix.incoming.strike.Loadout;
import ua.zentix.incoming.target.Target;

/** Actual carrier trigger paths. These prove event semantics, not cold full/LOD readiness. */
@GameTestHolder(Incoming.MOD_ID)
@PrefixGameTestTemplate(false)
public final class NuclearCarrierGameTests {
    private NuclearCarrierGameTests() {}

    private static CruiseMissileEntity missile(ServerLevel level, Vec3 at, Vec3 aim, boolean air) {
        var p = new CruiseMissileEntity(ModEntities.CRUISE_MISSILE.get(), level);
        p.launch(at, new Target.Point(aim), aim, null);
        p.setPos(at); p.flight.set(0, 0); p.speed = 4;
        p.setNuclear(new Loadout.Nuke(15, air, true));
        return p;
    }

    private static void event(GameTestHelper h, Vec3 expected, double ground, boolean surface, long tick) {
        var events = NuclearEvents.get(h.getLevel()).detonations();
        h.assertTrue(events.size() == 1, "Carrier did not produce exactly one registered event");
        var d = events.getFirst();
        h.assertTrue(d.burst().distanceTo(expected) < .1 && Math.abs(d.groundY() - ground) < .001,
                "Off-target event moved to planned target: " + d.burst() + " expected " + expected);
        h.assertTrue(d.yieldKt() == 15 && d.surface() == surface && d.gameTime() == tick,
                "Carrier changed yield, burst mode or event time");
    }

    @GameTest(template = "range", batch = "nuke_carrier_collision", timeoutTicks = 40, skyAccess = true)
    public static void armedCruiseCollisionUsesActualObstacleWithoutPostponingFlash(GameTestHelper h) {
        var level = h.getLevel();
        NuclearStrikes.clear(level);
        Vec3 at = Vec3.atCenterOf(h.absolutePos(new BlockPos(24, 25, 24)));
        Vec3 aim = at.add(0, 0, 2000);
        var p = missile(level, at, aim, false);
        BlockPos obstacle = BlockPos.containing(at.add(0, 0, 4));
        level.setBlock(obstacle, Blocks.OBSIDIAN.defaultBlockState(), 2);
        Vec3 contact = new Vec3(at.x, at.y, obstacle.getZ());
        Detonation expected = NuclearWarhead.geometry(level, NuclearStrikes.ground(level, contact), 15, false, 1);
        try {
            h.assertFalse(p.advance(level, aim, 0), "Missile passed through early obstacle");
            event(h, expected.burst(), expected.groundY(), expected.surface(), level.getGameTime());
        } finally { p.discard(); NuclearStrikes.clear(level); }
        h.succeed();
    }

    @GameTest(template = "range", batch = "nuke_carrier_damage", timeoutTicks = 40, skyAccess = true)
    public static void lethalDamageAndSamInterceptionKeepDistinctOutcomes(GameTestHelper h) {
        var level = h.getLevel();
        Vec3 at = Vec3.atCenterOf(h.absolutePos(new BlockPos(24, 25, 24)));
        try {
            for (FlightPhase phase : new FlightPhase[]{FlightPhase.CRUISE, FlightPhase.TERMINAL}) {
                NuclearStrikes.clear(level);
                var p = missile(level, at, at.add(0, 0, 2000), true);
                p.setPhase(phase);
                var expected = NuclearWarhead.geometry(level, NuclearStrikes.ground(level, at), 15, true, 1);
                h.assertTrue(p.hurt(level.damageSources().generic(), Float.MAX_VALUE), "Ordinary damage ignored");
                event(h, expected.burst(), expected.groundY(), expected.surface(), level.getGameTime());
            }
            NuclearStrikes.clear(level);
            var intercepted = missile(level, at, at.add(0, 0, 2000), true);
            intercepted.intercepted(level, "GameTest");
            h.assertTrue(intercepted.isRemoved() && NuclearEvents.get(level).detonations().isEmpty(), "SAM interception detonated nuclear payload");
            var unarmed = missile(level, at, at.add(0, 0, 2000), true);
            unarmed.launchPos = at; unarmed.setPhase(FlightPhase.IGNITION);
            unarmed.hurt(level.damageSources().generic(), Float.MAX_VALUE);
            h.assertTrue(unarmed.isRemoved() && NuclearEvents.get(level).detonations().isEmpty(), "Unarmed damage detonated payload");
        } finally { NuclearStrikes.clear(level); }
        h.succeed();
    }

    @GameTest(template = "range", batch = "nuke_carrier_roof", timeoutTicks = 40, skyAccess = true)
    public static void nuclearBombHitsUnexpectedRoofBeforeItsAim(GameTestHelper h) {
        var level = h.getLevel();
        NuclearStrikes.clear(level);
        Vec3 at = Vec3.atCenterOf(h.absolutePos(new BlockPos(24, 25, 24)));
        BlockPos roof = BlockPos.containing(at.add(0, -4, 0));
        level.setBlock(roof, Blocks.OBSIDIAN.defaultBlockState(), 2);
        var p = new BunkerBusterEntity(ModEntities.BUNKER_BUSTER.get(), level);
        p.drop(at, new Vec3(0, -4, 0), at.add(0, -100, 0), null, null);
        p.setNuclear(new Loadout.Nuke(15, false, true));
        Vec3 contact = new Vec3(at.x, roof.getY() + 1, at.z);
        var expected = NuclearWarhead.geometry(level, contact, 15, false, 1);
        try {
            h.assertFalse(p.advance(level, p.aimPoint(), 0), "Bomb passed through unexpected roof");
            event(h, expected.burst(), expected.groundY(), expected.surface(), level.getGameTime());
        } finally { p.discard(); NuclearStrikes.clear(level); }
        h.succeed();
    }

    @GameTest(template = "range", batch = "nuke_carrier_retarget", timeoutTicks = 40, skyAccess = true)
    public static void movingTargetRetargetAndReloadDoNotTeleportEarlyBurst(GameTestHelper h) {
        var level = h.getLevel();
        NuclearStrikes.clear(level);
        Vec3 at = Vec3.atCenterOf(h.absolutePos(new BlockPos(24, 25, 24)));
        var target = h.spawn(EntityType.COW, new BlockPos(32, 12, 32));
        target.setNoAi(true);
        var p = missile(level, at, at.add(0, 0, 2000), false);
        try {
            h.assertTrue(p.retarget(Target.OfEntity.center(target), target.position()), "Retarget mechanism refused moving target");
            target.setPos(target.position().add(8, 0, 0));
            p.updateTarget(level);
            h.assertTrue(p.aimPoint().distanceTo(target.position()) < 10, "Tracker retained old prepared target");
            Vec3 outside = at.add(1200, 0, 0);
            h.assertTrue(p.retarget(new Target.Point(outside), outside), "In-flight operator mechanism refused far point");
            // A saved carrier can outlive the unsaved preparation owner after restart.
            p.setNuclearPreparation(8_310_007);
            CompoundTag saved = new CompoundTag(); p.saveWithoutId(saved);
            var restored = new CruiseMissileEntity(ModEntities.CRUISE_MISSILE.get(), level);
            restored.load(saved);
            h.assertTrue(restored.isNuclear(), "Restart lost nuclear payload");
            h.assertTrue(restored.nuclearPreparation() == 8_310_007, "Saved flight lost explicit identity");
            var expected = NuclearWarhead.geometry(level, NuclearStrikes.ground(level, restored.position()), 15, false, 1);
            h.assertTrue(restored.hurt(level.damageSources().generic(), Float.MAX_VALUE), "Reloaded carrier ignored lethal damage");
            event(h, expected.burst(), expected.groundY(), expected.surface(), level.getGameTime());
        } finally { p.discard(); target.discard(); NuclearStrikes.clear(level); }
        h.succeed();
    }
}
