package ua.zentix.incoming.gametest;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import ua.zentix.incoming.Incoming;
import ua.zentix.incoming.IncomingConfig;
import ua.zentix.incoming.net.C2S;
import ua.zentix.incoming.registry.ModAttachments;
import ua.zentix.incoming.registry.ModItems;
import ua.zentix.incoming.strike.*;

import java.util.Optional;
import java.util.UUID;

import static ua.zentix.incoming.gametest.MunitionGameTests.*;

@GameTestHolder(Incoming.MOD_ID)
@PrefixGameTestTemplate(false)
public final class NuclearReviewGameTests {
    private NuclearReviewGameTests() {}

    private static MunitionGameTests.Listener setup(GameTestHelper h) {
        var c = IncomingConfig.SERVER;
        boolean ops = c.nukeOpsOnly.get();
        int cap = c.nukeMaxYield.get();
        StrikeGameTests.afterTest(h, () -> { c.nukeOpsOnly.set(ops); c.nukeMaxYield.set(cap); });
        c.nukeOpsOnly.set(false);
        c.nukeMaxYield.set(15);
        var p = player(h, "review");
        // Assertion failure must not leave an unpaid preparation blocking later settlement.
        StrikeGameTests.afterTest(h, () -> NuclearReview.cancel(p));
        give(p, ModItems.ICBM.get(), 1);
        return p;
    }

    private static C2S.Fire order(GameTestHelper h, int kt) {
        Vec3 q = Vec3.atCenterOf(h.absolutePos(new BlockPos(20, 4, 20)));
        Loadout l = new Loadout(WeaponType.NUKE, 1, 0, TargetMode.MAP, "", new Loadout.Nuke(kt, false));
        return new C2S.Fire(l, Optional.of(new C2S.AimHint(C2S.AimHint.POINT, q, 0, Vec3.ZERO)), Optional.empty(), Waypoints.NONE, Optional.empty());
    }

    private static void prepared(net.minecraft.server.level.ServerPlayer p, UUID id) {
        if (!NuclearReview.act(p, id, C2S.NuclearAction.PREPARE)) throw new AssertionError("prepare refused");
        var snapshot = p.getData(ModAttachments.NUCLEAR_REVIEW).snapshot().orElseThrow();
        ua.zentix.incoming.nuclear.world.NuclearReadyFixture.ready(p.serverLevel(), id, snapshot.target(),
                snapshot.order().loadout().nuke().yieldKt(), snapshot.order().loadout().nuke().airBurst());
    }

    private static UUID id(MunitionGameTests.Listener p) {
        return p.getData(ModAttachments.NUCLEAR_REVIEW).snapshot().orElseThrow().id();
    }

    @GameTest(template = "range", batch = "nuclear_review", timeoutTicks = 100, skyAccess = true)
    public static void cancellationAndRestartNeverConsumeACharge(GameTestHelper h) {
        var p = setup(h);
        int chunks = h.getLevel().getChunkSource().getLoadedChunksCount();
        NuclearReview.request(p, order(h, 15));
        h.assertValueEqual(h.getLevel().getChunkSource().getLoadedChunksCount(), chunks, "review loaded world chunks");
        UUID first = id(p);
        prepared(p, first);
        h.assertTrue(NuclearReview.act(p, first, C2S.NuclearAction.BEGIN), "hold refused");
        h.assertFalse(NuclearReview.act(p, first, C2S.NuclearAction.COMMIT), "early commit accepted");
        h.assertTrue(NuclearReview.act(p, first, C2S.NuclearAction.RELEASE), "release refused");
        h.runAfterDelay(65, () -> {
            h.assertFalse(NuclearReview.act(p, first, C2S.NuclearAction.COMMIT), "released hold committed");
            h.assertTrue(NuclearReview.act(p, first, C2S.NuclearAction.DISCARD), "cancel refused");
            h.assertFalse(NuclearReview.act(p, first, C2S.NuclearAction.COMMIT), "cancel replayed");
            NuclearReview.request(p, order(h, 5));
            UUID second = id(p);
            p.removeData(ModAttachments.NUCLEAR_REVIEW);
            h.assertFalse(NuclearReview.act(p, second, C2S.NuclearAction.COMMIT), "unsaved review survived reset");
            h.assertValueEqual(held(p, ModItems.ICBM.get()), 1, "review/cancel consumed inventory");
            h.succeed();
        });
    }

    // This delayed flow mutates/restores global policy. A concurrent review's cleanup can
    // restore ops-only/cap between this request and its revision, rejecting an otherwise valid fixture.
    @GameTest(template = "range", batch = "nuclear_review_revision", timeoutTicks = 100, skyAccess = true)
    public static void editedOrdersAndConfigChangesInvalidateTheOldHold(GameTestHelper h) {
        var p = setup(h);
        NuclearReview.request(p, order(h, 15));
        UUID first = id(p);
        NuclearReview.act(p, first, C2S.NuclearAction.BEGIN);
        h.runAfterDelay(5, () -> {
            NuclearReview.request(p, order(h, 5));
            UUID second = id(p);
            h.assertFalse(first.equals(second), "order revision reused identity");
            h.assertFalse(NuclearReview.act(p, first, C2S.NuclearAction.COMMIT), "old token accepted");
            NuclearReview.act(p, second, C2S.NuclearAction.BEGIN);
            IncomingConfig.SERVER.nukeMaxYield.set(1);
            h.assertFalse(NuclearReview.act(p, second, C2S.NuclearAction.COMMIT), "config reduction accepted");
            h.assertTrue(p.getData(ModAttachments.NUCLEAR_REVIEW).snapshot().isEmpty(), "stale review retained");
            h.assertValueEqual(held(p, ModItems.ICBM.get()), 1, "invalidated order paid");
            h.succeed();
        });
    }

    @GameTest(template = "range", batch = "nuclear_review_commit", timeoutTicks = 100, skyAccess = true)
    public static void validHoldIsConsumedOnceEvenWhenPaymentFails(GameTestHelper h) {
        var p = setup(h);
        p.getInventory().clearContent(); // test player's disposable inventory only
        NuclearReview.request(p, order(h, 15));
        UUID id = id(p);
        prepared(p, id);
        NuclearReview.act(p, id, C2S.NuclearAction.BEGIN);
        h.runAfterDelay(65, () -> {
            h.assertTrue(NuclearReview.act(p, id, C2S.NuclearAction.COMMIT), "valid held request not dispatched");
            h.assertFalse(NuclearReview.act(p, id, C2S.NuclearAction.COMMIT), "duplicate dispatched twice");
            h.assertTrue(p.last("incoming.munitions.short") != null, "did not reach ordinary payment checks");
            h.succeed();
        });
    }
    @GameTest(template = "range", batch = "nuclear_review_launch", timeoutTicks = 100, skyAccess = true)
    public static void paidCommitSchedulesOnlyOneStrike(GameTestHelper h) {
        var level = h.getLevel();
        var weather = (net.minecraft.world.level.storage.ServerLevelData) level.getLevelData();
        int clearTime = weather.getClearWeatherTime(), rainTime = weather.getRainTime(), thunderTime = weather.getThunderTime();
        boolean raining = weather.isRaining(), thundering = weather.isThundering();
        float rain = level.getRainLevel(1);
        // getThunderLevel includes the rain factor; sample raw thunder at unit rain, without advancing a tick.
        level.setRainLevel(1);
        float thunder = level.getThunderLevel(1);
        level.setRainLevel(rain);
        StrikeGameTests.afterTest(h, () -> {
            level.setWeatherParameters(clearTime, rainTime, raining, thundering);
            weather.setThunderTime(thunderTime);
            level.setRainLevel(rain);
            level.setThunderLevel(thunder);
        });
        // The preceding rain batch leaves a fading rain level after switching its weather flag off.
        // Review authority correctly expires when that visibility changes during the hold.
        level.setWeatherParameters(24000, 0, false, false);
        level.setRainLevel(0);
        level.setThunderLevel(0);
        var p = setup(h);
        int before = ua.zentix.incoming.nuclear.NuclearEvents.get(level).scheduled().size();
        StrikeGameTests.afterTest(h, () -> {
            ua.zentix.incoming.nuclear.NuclearStrikes.clear(level);
            VirtualFlights.get(level).clear(level, projectile -> p.getUUID().equals(projectile.ownerId()));
            for (var projectile : StrikeWorld.projectiles(level)) if (p.getUUID().equals(projectile.ownerId())) projectile.discard();
        });
        NuclearReview.request(p, order(h, 15));
        UUID id = id(p);
        prepared(p, id);
        h.assertTrue(NuclearReview.act(p, id, C2S.NuclearAction.BEGIN), "ready hold refused");
        h.runAfterDelay(65, () -> {
            h.assertTrue(NuclearReview.act(p, id, C2S.NuclearAction.COMMIT), "valid request not dispatched");
            h.assertValueEqual(held(p, ModItems.ICBM.get()), 0, "launch did not pay once");
            h.assertValueEqual(ua.zentix.incoming.nuclear.NuclearEvents.get(level).scheduled().size(), before + 1, "strike not scheduled");
            h.assertFalse(NuclearReview.act(p, id, C2S.NuclearAction.COMMIT), "duplicate accepted");
            h.assertValueEqual(ua.zentix.incoming.nuclear.NuclearEvents.get(level).scheduled().size(), before + 1, "duplicate scheduled");
            h.assertValueEqual(held(p, ModItems.ICBM.get()), 0, "duplicate changed payment");
            h.assertTrue(p.last("incoming.munitions.refunded") == null, "accepted launch refunded payment");
            h.succeed();
        });
    }

    @GameTest(template = "range", batch = "nuclear_review_permissions", timeoutTicks = 100, skyAccess = true)
    public static void operatorPolicyIsCheckedBeforeReview(GameTestHelper h) {
        var p = setup(h);
        IncomingConfig.SERVER.nukeOpsOnly.set(true);
        NuclearReview.request(p, order(h, 15));
        h.assertTrue(p.getData(ModAttachments.NUCLEAR_REVIEW).snapshot().isEmpty(), "unauthorized review created");
        h.assertValueEqual(held(p, ModItems.ICBM.get()), 1, "unauthorized request paid");
        h.succeed();
    }

    @GameTest(template = "range", batch = "nuclear_review_preparation", timeoutTicks = 100, skyAccess = true)
    public static void preparingHoldCannotBecomeAnAutomaticReadyLaunch(GameTestHelper h) {
        var p = setup(h);
        NuclearReview.request(p, order(h, 1));
        UUID id = id(p);
        h.assertFalse(NuclearReview.act(p, id, C2S.NuclearAction.BEGIN), "inspection allowed hold");
        h.assertTrue(NuclearReview.act(p, id, C2S.NuclearAction.POLL), "inspection heartbeat refused");
        h.assertTrue(NuclearReview.act(p, id, C2S.NuclearAction.PREPARE), "heartbeat swallowed the one-shot prepare click");
        h.assertFalse(NuclearReview.act(p, id, C2S.NuclearAction.PREPARE), "repeat prepare ignored poll bound");
        h.assertFalse(NuclearReview.act(p, id, C2S.NuclearAction.BEGIN), "unready preparation allowed hold");
        var snapshot = p.getData(ModAttachments.NUCLEAR_REVIEW).snapshot().orElseThrow();
        ua.zentix.incoming.nuclear.world.NuclearReadyFixture.ready(p.serverLevel(), id, snapshot.target(), 1, false);
        h.runAfterDelay(65, () -> {
            h.assertFalse(NuclearReview.act(p, id, C2S.NuclearAction.COMMIT), "hold started before READY survived transition");
            h.assertValueEqual(held(p, ModItems.ICBM.get()), 1, "preparation spent ammunition");
            h.assertTrue(NuclearReview.act(p, id, C2S.NuclearAction.DISCARD), "close refused");
            h.assertTrue(ua.zentix.incoming.nuclear.world.RuinPreparation.status(p.serverLevel(), id).stage()
                    == ua.zentix.incoming.nuclear.world.RuinPreparation.Stage.ABSENT, "close retained preparation");
            h.succeed();
        });
    }

    @GameTest(template = "range", batch = "nuclear_review_lost_ready", timeoutTicks = 100, skyAccess = true)
    public static void lostReadyRevokesHoldAndCannotPay(GameTestHelper h) {
        var p = setup(h);
        NuclearReview.request(p, order(h, 1));
        UUID id = id(p);
        prepared(p, id);
        h.assertTrue(NuclearReview.act(p, id, C2S.NuclearAction.BEGIN), "ready hold refused");
        ua.zentix.incoming.nuclear.world.RuinPreparation.cancel(p.serverLevel(), id);
        h.runAfterDelay(65, () -> {
            h.assertFalse(NuclearReview.act(p, id, C2S.NuclearAction.COMMIT), "missing prepared receipt accepted");
            h.assertValueEqual(held(p, ModItems.ICBM.get()), 1, "lost readiness paid ammunition");
            NuclearReview.act(p, id, C2S.NuclearAction.DISCARD);
            h.succeed();
        });
    }

    @GameTest(template = "range", batch = "nuclear_review_lease", timeoutTicks = 1500, skyAccess = true)
    public static void watchedPreparationOutlivesOldReviewTimeout(GameTestHelper h) {
        var p = setup(h);
        NuclearReview.request(p, order(h, 1));
        UUID id = id(p);
        prepared(p, id);
        h.runAfterDelay(900, () -> {
            h.assertTrue(NuclearReview.act(p, id, C2S.NuclearAction.POLL), "watched lease not renewed");
            h.assertFalse(NuclearReview.act(p, id, C2S.NuclearAction.POLL), "unbounded polling accepted");
        });
        h.runAfterDelay(1300, () -> {
            h.assertTrue(NuclearReview.act(p, id, C2S.NuclearAction.BEGIN), "watched prep unavailable after original minute: "
                    + ua.zentix.incoming.nuclear.world.RuinPreparation.status(p.serverLevel(), id));
            NuclearReview.act(p, id, C2S.NuclearAction.DISCARD);
            h.assertValueEqual(held(p, ModItems.ICBM.get()), 1, "lease renewal spent ammo");
            h.succeed();
        });
    }

    @GameTest(template = "range", batch = "nuclear_review_launch_permission", skyAccess = true)
    public static void nuclearReviewCannotSmuggleAnOperatorLaunchSite(GameTestHelper h) {
        var p = setup(h);
        var original = order(h, 1);
        var packet = new C2S.Fire(original.loadout(), original.aim(), original.aircraft(), original.via(), Optional.of(p.position()));
        NuclearReview.request(p, packet);
        h.assertTrue(p.getData(ModAttachments.NUCLEAR_REVIEW).snapshot().isEmpty(), "non-operator supplied a launch site through review");
        h.assertValueEqual(held(p, ModItems.ICBM.get()), 1, "rejected site spent ammunition");
        h.succeed();
    }

}
