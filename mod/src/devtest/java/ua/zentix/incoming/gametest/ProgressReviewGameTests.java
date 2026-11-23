package ua.zentix.incoming.gametest;

import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.advancements.CriteriaTriggers;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.stats.Stats;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.AdvancementEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import ua.zentix.incoming.Incoming;
import ua.zentix.incoming.IncomingConfig;
import ua.zentix.incoming.api.StrikeRoundEvent;
import ua.zentix.incoming.entity.BomberEntity;
import ua.zentix.incoming.entity.StrikeProjectile;
import ua.zentix.incoming.grid.Blackouts;
import ua.zentix.incoming.grid.Districts;
import ua.zentix.incoming.grid.Outage;
import ua.zentix.incoming.grid.PowerGrid;
import ua.zentix.incoming.nuclear.Detonation;
import ua.zentix.incoming.nuclear.NuclearStrikes;
import ua.zentix.incoming.nuclear.NuclearWarhead;
import ua.zentix.incoming.nuclear.model.BlastModel;
import ua.zentix.incoming.progress.ModStats;
import ua.zentix.incoming.progress.Progress;
import ua.zentix.incoming.registry.ModAttachments;
import ua.zentix.incoming.registry.ModItems;
import ua.zentix.incoming.strike.Loadout;
import ua.zentix.incoming.strike.Orders;
import ua.zentix.incoming.strike.ServerActions;
import ua.zentix.incoming.strike.StrikeWorld;
import ua.zentix.incoming.strike.VirtualFlights;
import ua.zentix.incoming.strike.Waypoints;
import ua.zentix.incoming.strike.WeaponType;
import ua.zentix.incoming.target.Target;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Regressions from the first independent review of advancements and statistics (its reproducing probes): a reward that
 * kills the player during the survival grant, camera credit through a B-2's bomb, survival that needs the wave's real hit
 * (a player who left, changed dimension, turned creative or whose front was cleared gets nothing), a witnessed burst
 * cleared in its own tick, restore credit by the grid's districts, and the tab opening with Trinitite.
 */
@GameTestHolder(Incoming.MOD_ID)
@PrefixGameTestTemplate(false)
public final class ProgressReviewGameTests {
    private static final BlockPos CENTRE = new BlockPos(32, 11, 32);
    private static final String SURVIVOR = "nuclear_survivor";

    private ProgressReviewGameTests() {}

    @GameTest(template = "range", timeoutTicks = 40, batch = "progress_review_death_callback", skyAccess = true)
    public static void deathDuringAdvancementGrantDoesNotCrashThePlayerTick(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        ServerPlayer p = ProgressGameTests.Credited.online(h, "review_callback", 0, CENTRE);
        long now = level.getGameTime();
        Progress.Watches watches = p.getData(ModAttachments.SURVIVAL);
        // The later detonation reaches the player first. The first watch is still pending when the second grants.
        watches.add(new Progress.Watch(level.dimension(), now + 100, 6, 1));
        watches.add(new Progress.Watch(level.dimension(), now, 6, 1));
        Consumer<AdvancementEvent.AdvancementEarnEvent> listener = e -> {
            if (e.getEntity() == p && e.getAdvancement().id().equals(Incoming.id("strikes/" + SURVIVOR))) p.kill();
        };
        NeoForge.EVENT_BUS.addListener(EventPriority.NORMAL, false, AdvancementEvent.AdvancementEarnEvent.class, listener);
        StrikeGameTests.afterTest(h, () -> NeoForge.EVENT_BUS.unregister(listener));
        try {
            NeoForge.EVENT_BUS.post(new PlayerTickEvent.Post(p));
        } catch (java.util.ConcurrentModificationException ex) {
            h.fail("The advancement callback cleared the watch list inside its active iterator: " + ex);
        }
        h.assertFalse(p.isAlive(), "the advancement callback did not kill the player");
        h.assertTrue(watches.isEmpty(), "death left pending survival watches");
        h.succeed();
    }

    @GameTest(template = "range", timeoutTicks = 3000, batch = "progress_review_bomber", skyAccess = true)
    public static void cameraCreditFollowsBomberRoundIntoBomb(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        ServerPlayer pilot = ProgressGameTests.Credited.online(h, "review_bomber", 2, CENTRE);
        Vec3 aim = Vec3.atBottomCenterOf(h.absolutePos(CENTRE));
        StrikeGameTests.afterTest(h, () -> {
            VirtualFlights.get(level).clear(level, p -> pilot.getUUID().equals(p.ownerId()));
            for (StrikeProjectile p : List.copyOf(StrikeWorld.projectiles(level))) {
                if (pilot.getUUID().equals(p.ownerId())) p.discard();
            }
        });
        h.assertTrue(ServerActions.strike(pilot, WeaponType.BUNKER, 1, 0,
                new ServerActions.Aim(new Target.Point(aim), aim, null), Loadout.Nuke.DEFAULT, Waypoints.NONE, null, false),
                "bomber did not launch");
        BomberEntity bomber = (BomberEntity) StrikeWorld.projectiles(level).stream()
                .filter(p -> p instanceof BomberEntity && pilot.getUUID().equals(p.ownerId())).findFirst().orElseThrow();
        UUID round = bomber.round().round();
        List<StrikeRoundEvent.Ended> ended = new ArrayList<>();
        Consumer<StrikeRoundEvent.Ended> listener = e -> {
            if (round.equals(e.getRoundId())) ended.add(e);
        };
        NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, false, StrikeRoundEvent.Ended.class, listener);
        StrikeGameTests.afterTest(h, () -> NeoForge.EVENT_BUS.unregister(listener));
        Vec3 newAim = aim.add(0, 0, 3);
        h.assertTrue(bomber.retarget(new Target.Point(newAim), newAim), "bomber refused the camera retarget");
        h.assertTrue(bomber.cameraGuided(), "retarget did not mark the B-2");
        h.startSequence()
                .thenWaitUntil(() -> h.assertValueEqual(ended.size(), 1, "ends of the bomber round"))
                .thenExecute(() -> {
                    h.assertValueEqual(ended.getFirst().getOutcome(), StrikeRoundEvent.Outcome.IMPACT, "the bomb did not go off");
                    h.assertTrue(ended.getFirst().isCameraGuided(), "The bomb detonated under the same round ID without the B-2's camera mark");
                    h.assertTrue(done(pilot, "camera_strike"), "the pilot got no Eyes on Target");
                })
                .thenSucceed();
    }

    @GameTest(template = "range", timeoutTicks = 200, batch = "progress_review_escape", skyAccess = true)
    public static void leavingBeforeFrontDoesNotCountAsSurvival(GameTestHelper h) {
        Detonation d = burst(h);
        ServerPlayer p = near(h, d);
        long due = passed(d, p);
        Orders.deliver(h.getLevel());
        Vec3 safe = d.burst().add(Math.max(d.radiusMax(), d.blastReach()) + 50, 0, 0);
        h.assertTrue(d.psi(safe) < 0.3, "escape destination still in the blast");
        h.assertTrue(d.arrivalTicks(p.position().distanceTo(d.burst())) > 1, "front already here");
        p.moveTo(safe);
        checkAtDue(h, p, due, "A player who left the blast before the front arrived got Still Standing");
    }

    @GameTest(template = "range", timeoutTicks = 200, batch = "progress_review_dimension", skyAccess = true)
    public static void changingDimensionBeforeTheFrontDoesNotCountAsSurvival(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        ServerLevel nether = level.getServer().getLevel(Level.NETHER);
        h.assertTrue(nether != null, "test requires the Nether");
        Detonation d = burst(h);
        ServerPlayer p = near(h, d);
        long due = passed(d, p);
        Orders.deliver(level);
        h.assertTrue(d.arrivalTicks(p.position().distanceTo(d.burst())) > 1, "front already here");
        // The fixture has no world entity membership. Mirror the real transition's level update and event on the same player.
        p.setServerLevel(nether);
        NeoForge.EVENT_BUS.post(new net.neoforged.neoforge.event.entity.player.PlayerEvent.PlayerChangedDimensionEvent(
                p, level.dimension(), nether.dimension()));
        checkAtDue(h, p, due, "The Overworld blast granted Still Standing to a player who left for the Nether");
    }

    @GameTest(template = "range", timeoutTicks = 200, batch = "progress_review_creative", skyAccess = true)
    public static void switchingToCreativeBeforeTheFrontDoesNotCountAsSurvival(GameTestHelper h) {
        Detonation d = burst(h);
        ServerPlayer p = near(h, d);
        long due = passed(d, p);
        Orders.deliver(h.getLevel());
        h.assertTrue(d.arrivalTicks(p.position().distanceTo(d.burst())) > 1, "front already here");
        h.assertTrue(p.setGameMode(GameType.CREATIVE), "could not enter creative");
        checkAtDue(h, p, due, "A player in creative when the wave arrived got Still Standing");
    }

    @GameTest(template = "range", timeoutTicks = 200, batch = "progress_review_cancel_watch", skyAccess = true)
    public static void stoppingTheFrontCancelsSurvival(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        Detonation d = burst(h);
        ServerPlayer p = near(h, d);
        long due = passed(d, p);
        Orders.deliver(level);
        h.assertTrue(d.arrivalTicks(p.position().distanceTo(d.burst())) > 1, "front already here");
        h.assertTrue(NuclearStrikes.clear(level) > 0, "no detonation cancelled");
        checkAtDue(h, p, due, "A wave cancelled before it hit still awarded Still Standing");
    }

    @GameTest(template = "range", timeoutTicks = 40, batch = "progress_review_cancel_witness", skyAccess = true)
    public static void aCompletedBurstCountsEvenIfClearedBeforeEventDelivery(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        // the witness stands there before the burst, as a player who sees it does
        Vec3 centre = Vec3.atBottomCenterOf(h.absolutePos(CENTRE));
        ServerPlayer p = ProgressGameTests.Credited.online(h, "review_witness", 0, centre.add(10, 0, 0));
        Detonation d = burst(h);
        h.assertTrue(p.position().distanceTo(d.burst()) < d.radiusMax(),
                "witness is outside the blast reach");
        h.assertTrue(NuclearStrikes.clear(level) > 0, "no detonation cancelled");
        Orders.deliver(level);
        h.assertValueEqual(p.getStats().getValue(Stats.CUSTOM.get(ModStats.NUCLEAR_DETONATIONS_WITNESSED)), 1,
                "detonations witnessed after a burst followed by same-tick clear");
        h.succeed();
    }

    @GameTest(template = "range", timeoutTicks = 200, batch = "progress_review_grid_district", skyAccess = true)
    public static void actualDistrictReceivesRestoreCreditOutsideCircle(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        Vec3 at = Vec3.atBottomCenterOf(h.absolutePos(CENTRE));
        ServerPlayer p = ProgressGameTests.Credited.online(h, "review_district", 0, at);
        long district = Districts.of(p.chunkPosition().x, p.chunkPosition().z);
        Vec3 seed = new Vec3(Districts.seedX(district), at.y, Districts.seedZ(district));
        if (at.distanceTo(seed) <= 2) p.moveTo(at.add(6, 0, 0));
        Outage outage = Blackouts.blackout(level, seed, 2, 16, -1);
        h.assertTrue(outage.covers(Districts.of(p.chunkPosition().x, p.chunkPosition().z)), "fixture outside outage district");
        h.assertTrue(p.position().distanceTo(seed) > outage.radius(), "fixture inside restore event circle");
        h.startSequence()
                .thenWaitUntil(() -> h.assertTrue(darkAtPlayer(level, p), "waiting for the player's district to go dark"))
                .thenExecute(() -> h.assertValueEqual(Blackouts.restore(level, seed, 0, 0), 1, "outages set to restore"))
                .thenWaitUntil(() -> assertRestored(h, outage))
                .thenExecute(() -> {
                    h.assertFalse(darkAtPlayer(level, p), "the player's district still has no power");
                    h.assertTrue(done(p, "lights_back"), "The player's district was dark then relit but got no Lights Back On");
                })
                .thenSucceed();
    }

    @GameTest(template = "range", timeoutTicks = 200, batch = "progress_review_grid_overlap", skyAccess = true)
    public static void districtStillDarkFromAnotherOutageDoesNotGetCredit(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        Vec3 at = Vec3.atBottomCenterOf(h.absolutePos(CENTRE));
        ServerPlayer p = ProgressGameTests.Credited.online(h, "review_overlap", 0, at);
        Outage first = Blackouts.blackout(level, at, 120, 32, -1);
        Vec3 secondCentre = at.add(4, 0, 0);
        Outage second = Blackouts.blackout(level, secondCentre, 120, 32, -1);
        long district = Districts.of(p.chunkPosition().x, p.chunkPosition().z);
        StrikeGameTests.afterTest(h, () -> Blackouts.restore(level, secondCentre, 0, 0));
        h.startSequence()
                .thenWaitUntil(() -> {
                    long now = Blackouts.now(level);
                    h.assertTrue(first.dark(district, now) && second.dark(district, now), "waiting for both outages");
                })
                .thenExecute(() -> h.assertValueEqual(Blackouts.restore(level, at, 0, 0), 1, "outages set to restore"))
                .thenWaitUntil(() -> assertRestored(h, first))
                .thenExecute(() -> {
                    h.assertTrue(darkAtPlayer(level, p), "the second outage no longer darkens the district");
                    h.assertFalse(done(p, "lights_back"), "The player got Lights Back On while still in a dark district");
                })
                .thenSucceed();
    }

    @GameTest(template = "range", timeoutTicks = 40, batch = "progress_review_root", skyAccess = true)
    public static void pickingUpTrinititeUnlocksTheIncomingTab(GameTestHelper h) {
        ServerPlayer p = ProgressGameTests.Credited.online(h, "review_trinitite", 0, CENTRE);
        ItemStack trinitite = new ItemStack(ModItems.TRINITITE.get());
        h.assertTrue(p.getInventory().add(trinitite.copy()), "could not add Trinitite to the inventory");
        CriteriaTriggers.INVENTORY_CHANGED.trigger(p, p.getInventory(), trinitite);
        h.assertTrue(done(p, "root"), "Holding Trinitite did not unlock the Incoming tab");
        h.succeed();
    }

    private static Detonation burst(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        Vec3 centre = Vec3.atBottomCenterOf(h.absolutePos(CENTRE));
        boolean fires = IncomingConfig.SERVER.nukeFires.get();
        boolean damage = IncomingConfig.SERVER.nukeBlockDamage.get();
        boolean grid = IncomingConfig.SERVER.gridNuke.get();
        IncomingConfig.SERVER.nukeFires.set(false);
        IncomingConfig.SERVER.nukeBlockDamage.set(false);
        IncomingConfig.SERVER.gridNuke.set(false);
        StrikeGameTests.afterTest(h, () -> {
            NuclearStrikes.clear(level);
            IncomingConfig.SERVER.nukeFires.set(fires);
            IncomingConfig.SERVER.nukeBlockDamage.set(damage);
            IncomingConfig.SERVER.gridNuke.set(grid);
        });
        return NuclearWarhead.detonate(level, centre, 0.1, false, null, 0.2f);
    }

    private static ServerPlayer near(GameTestHelper h, Detonation d) {
        double ring = d.groundRange(BlastModel.kpa(5));
        ServerPlayer p = ProgressGameTests.Credited.online(h, "review_survivor", 0, d.burst().add(ring * 0.8, 0, 0));
        p.setGameMode(GameType.SURVIVAL);
        h.assertTrue(d.psi(p.position()) >= 5 && !p.getAbilities().invulnerable && p.isAlive(), "fixture cannot be watched");
        return p;
    }

    /**
     * Game time by which the wave has passed {@code p} at its place at the burst, with room for the hit's wait: a watch
     * made when the wave hit them would be due by then.
     */
    private static long passed(Detonation d, ServerPlayer p) {
        double slant = p.position().distanceTo(d.burst());
        return d.gameTime() + (long) Math.ceil(d.arrivalTicks(slant) + d.positivePhaseSeconds(p.position()) * 20 * d.scale()) + Progress.LANDING_TICKS + 20;
    }

    private static void checkAtDue(GameTestHelper h, ServerPlayer p, long due, String failure) {
        h.startSequence()
                .thenWaitUntil(() -> h.assertTrue(p.serverLevel().getGameTime() >= due, "waiting for the original survival deadline"))
                .thenExecute(() -> {
                    NeoForge.EVENT_BUS.post(new PlayerTickEvent.Post(p));
                    h.assertFalse(done(p, SURVIVOR), failure);
                })
                .thenSucceed();
    }

    private static void assertRestored(GameTestHelper h, Outage outage) {
        Orders.deliver(h.getLevel());
        h.assertTrue(PowerGrid.get(h.getLevel()).outages().stream().noneMatch(o -> o.id() == outage.id()),
                "waiting for the restore event");
    }

    private static boolean darkAtPlayer(ServerLevel level, ServerPlayer p) {
        return PowerGrid.get(level).dark(p.chunkPosition().x, p.chunkPosition().z, Blackouts.now(level));
    }

    private static boolean done(ServerPlayer p, String path) {
        AdvancementHolder advancement = p.server.getAdvancements().get(Incoming.id("strikes/" + path));
        return p.getAdvancements().getOrStartProgress(advancement).isDone();
    }
}
