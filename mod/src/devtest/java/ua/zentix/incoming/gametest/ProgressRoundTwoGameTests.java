package ua.zentix.incoming.gametest;

import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import ua.zentix.incoming.Incoming;
import ua.zentix.incoming.grid.Blackouts;
import ua.zentix.incoming.grid.Districts;
import ua.zentix.incoming.grid.Outage;
import ua.zentix.incoming.grid.PowerGrid;
import ua.zentix.incoming.nuclear.Detonation;
import ua.zentix.incoming.nuclear.NuclearWarhead;
import ua.zentix.incoming.nuclear.model.BlastModel;
import ua.zentix.incoming.nuclear.world.BlastBody;
import ua.zentix.incoming.nuclear.world.WorkClock;
import ua.zentix.incoming.progress.Progress;
import ua.zentix.incoming.registry.ModAttachments;
import ua.zentix.incoming.strike.Orders;

import java.lang.reflect.Method;
import java.util.function.IntPredicate;

/**
 * Regressions from the second independent review of advancements and statistics (its reproducing probes): a burial the
 * player escaped is no wave hit, a district whose darkening never arrived earns no restore credit, and the front ring
 * below the pressure the wave hits with starts no survival watch.
 */
@GameTestHolder(Incoming.MOD_ID)
@PrefixGameTestTemplate(false)
public final class ProgressRoundTwoGameTests {
    private static final BlockPos CENTRE = new BlockPos(32, 11, 32);

    private ProgressRoundTwoGameTests() {}

    @GameTest(template = "range", batch = "progress_r2_burial", timeoutTicks = 120, skyAccess = true)
    public static void escapedLateBurialIsNotAWaveHit(GameTestHelper h) throws ReflectiveOperationException {
        ServerLevel level = h.getLevel();
        BlockPos feet = h.absolutePos(CENTRE);
        Vec3 at = Vec3.atBottomCenterOf(feet);
        Detonation plan = NuclearWarhead.geometry(level, at.add(-10, 0, 0), 0.1, false, 0.1f);
        Detonation d = new Detonation(301, plan.burst(), plan.groundY(), plan.yieldKt(), plan.surface(),
                level.getGameTime() - 80, 0, 0, plan.visibility(), 0, plan.scale(), false, plan.wave());
        ServerPlayer p = ProgressGameTests.Credited.online(h, "r2_escaped_burial", 0, at);
        Vec3 exit = at.add(2, 0, 0);
        h.assertTrue(d.psi(exit) >= 5, "exit pressure below 5 psi");
        h.assertTrue(d.frontRadius(80) > at.distanceTo(d.burst()), "front has not passed before player arrives");
        for (BlockPos position : new BlockPos[]{CENTRE, CENTRE.above(), CENTRE.east(2), CENTRE.east(2).above()}) {
            h.setBlock(position, Blocks.AIR);
        }
        h.assertFalse(BlastBody.buried(level, p), "player should arrive free");
        h.setBlock(CENTRE, Blocks.STONE);
        h.assertTrue(BlastBody.buried(level, p), "the ruin fixture did not bury the player");
        Object front = newFront();
        invoke(front, "bury", new Class<?>[]{Detonation.class, net.minecraft.world.entity.LivingEntity.class}, d, p);
        // A pearl or a teleport gets the player out after the ruin job but before the next front work window.
        p.moveTo(exit);
        h.assertFalse(BlastBody.buried(level, p), "the player did not escape the buried position");
        float health = p.getHealth();
        WorkClock clock = WorkClock.counting(1_000);
        clock.start(10_000);
        invoke(front, "work", new Class<?>[]{ServerLevel.class, WorkClock.class, IntPredicate.class},
                level, clock, (IntPredicate) ignored -> true);
        h.assertValueEqual(p.getHealth(), health, "burial unexpectedly injured the player");
        h.assertFalse(p.getData(ModAttachments.NUKE_SEEN).blasted(d.id()), "the blast front hit the player");
        long due = Progress.survivalDue(d, p.position(), level.getGameTime());
        h.runAfterDelay(due - level.getGameTime() + 1, () -> {
            NeoForge.EVENT_BUS.post(new PlayerTickEvent.Post(p));
            h.assertFalse(done(p, "nuclear_survivor"), "Still Standing without a wave hit or a burial injury; exit pressure " + d.psi(exit) + " psi");
            h.succeed();
        });
    }

    @GameTest(template = "range", batch = "progress_r2_grid_no_dark", timeoutTicks = 500, skyAccess = true)
    public static void districtThatNeverWentDarkGetsNoRestoreCredit(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        PowerGrid grid = PowerGrid.get(level);
        Vec3 at = Vec3.atBottomCenterOf(h.absolutePos(CENTRE));
        ServerPlayer[] player = new ServerPlayer[1];
        int cx = Mth.floor(at.x) >> 4, cz = Mth.floor(at.z) >> 4;
        long district = Districts.of(cx, cz);
        Vec3 seed = new Vec3(Districts.seedX(district), at.y, Districts.seedZ(district));
        int radius = 1024;
        Vec3 centre = seed.add(seed.subtract(at).normalize().scale(radius - 0.5));
        h.assertTrue(at.distanceTo(centre) > radius, "configure a position outside the old radial check");
        Outage[] made = new Outage[2];
        boolean[] watching = {false};
        h.onEachTick(() -> {
            if (watching[0]) h.assertFalse(grid.dark(cx, cz, Blackouts.now(level)), "district was dark");
        });
        ProgressGameTests.quietGrid(h)
                .thenExecute(() -> {
                    // arrives once earlier outages are over
                    player[0] = ProgressGameTests.Credited.online(h, "r2_never_dark", 0, at);
                    // The default 60 block/s cascade takes longer to arrive here than the command's 10 s restore.
                    Outage o = Blackouts.blackout(level, centre, radius, 60.0 / 20, -1);
                    h.assertTrue(o.covers(district), "district seed is not covered");
                    h.assertValueEqual(Blackouts.restore(level, centre, 0), 1, "restore did not select this outage");
                    Outage restoring = grid.outages().stream().filter(a -> a.id() == o.id()).findFirst().orElseThrow();
                    h.assertTrue(restoring.lightAt(district) < restoring.darkAt(district), "district goes dark before scheduled restore");
                    made[0] = o;
                    made[1] = restoring;
                    watching[0] = true;
                })
                .thenWaitUntil(() -> h.assertTrue(made[0] == null || grid.outages().stream().noneMatch(a -> a.id() == made[0].id()), "waiting for outage to finish"))
                .thenExecute(() -> {
                    Orders.deliver(level);
                    h.assertTrue(made[1] != null, "the probe's outage was never made");
                    h.assertFalse(done(player[0], "lights_back"), "Lights Back On in an always-lit district; restore at +"
                            + (made[1].lightAt(district) - made[0].start()) + ", darkening scheduled at +" + (made[1].darkAt(district) - made[0].start()));
                })
                .thenSucceed();
    }

    @GameTest(template = "range", batch = "progress_r2_below_wave", timeoutTicks = 50, skyAccess = true)
    public static void frontRingBelowDamageThresholdCreatesNoWatch(GameTestHelper h) throws ReflectiveOperationException {
        ServerLevel level = h.getLevel();
        Vec3 at = Vec3.atBottomCenterOf(h.absolutePos(CENTRE));
        Detonation plan = new Detonation(302, at, at.y, 0.1, true, 0, 0, 0, 20_000, 0, 0.1f, false, 0.35f);
        double radius = plan.groundRange(Detonation.HIT_KPA) + 0.05;
        Vec3 origin = at.add(-radius, 0, 0);
        int elapsed = (int) Math.floor(plan.arrivalTicks(radius)) + 1;
        Detonation d = new Detonation(302, origin, origin.y, plan.yieldKt(), plan.surface(),
                level.getGameTime() - elapsed, 0, 0, plan.visibility(), 0, plan.scale(), false, plan.wave());
        ServerPlayer p = ProgressGameTests.Credited.online(h, "r2_below_wave", 0, at);
        level.addNewPlayer(p);
        StrikeGameTests.afterTest(h, () -> level.removePlayerImmediately(p, net.minecraft.world.entity.Entity.RemovalReason.DISCARDED));
        double pressure = d.overpressureKpa(p.position());
        h.assertTrue(pressure < Detonation.HIT_KPA, "player is within the wave's injury range");
        h.assertTrue(d.frontRadius(elapsed - 1) <= radius && radius < d.frontRadius(elapsed), "the front ring did not reach the player in this tick");
        Object front = newFront();
        invoke(front, "advance", new Class<?>[]{ServerLevel.class, java.util.List.class, long.class},
                level, java.util.List.of(d), level.getGameTime());
        WorkClock clock = WorkClock.counting(1_000);
        clock.start(10_000);
        invoke(front, "work", new Class<?>[]{ServerLevel.class, WorkClock.class, IntPredicate.class},
                level, clock, (IntPredicate) ignored -> true);
        h.assertTrue(p.getData(ModAttachments.NUKE_SEEN).blasted(d.id()), "fixture did not drain the queued ring hit");
        h.assertTrue(p.getData(ModAttachments.SURVIVAL).isEmpty(),
                "survival watch recorded at " + BlastModel.psi(pressure) + " psi, below the wave hit minimum");
        h.succeed();
    }

    private static Object newFront() throws ReflectiveOperationException {
        var constructor = Class.forName("ua.zentix.incoming.nuclear.world.BlastFront").getDeclaredConstructor();
        constructor.setAccessible(true);
        return constructor.newInstance();
    }

    private static void invoke(Object target, String name, Class<?>[] types, Object... args) throws ReflectiveOperationException {
        Method method = target.getClass().getDeclaredMethod(name, types);
        method.setAccessible(true);
        method.invoke(target, args);
    }

    private static boolean done(ServerPlayer p, String path) {
        AdvancementHolder a = p.server.getAdvancements().get(Incoming.id("strikes/" + path));
        return p.getAdvancements().getOrStartProgress(a).isDone();
    }
}
