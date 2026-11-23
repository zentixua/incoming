package ua.zentix.incoming.gametest;

import com.mojang.authlib.GameProfile;
import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.commands.CommandSource;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.stats.ServerStatsCounter;
import net.minecraft.stats.Stats;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.animal.Cow;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.scores.Objective;
import net.minecraft.world.scores.Scoreboard;
import net.minecraft.world.scores.criteria.ObjectiveCriteria;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import org.jetbrains.annotations.Nullable;
import ua.zentix.incoming.Incoming;
import ua.zentix.incoming.IncomingConfig;
import ua.zentix.incoming.api.StrikeRoundEvent;
import ua.zentix.incoming.defense.SamBlockEntity;
import ua.zentix.incoming.entity.CruiseMissileEntity;
import ua.zentix.incoming.entity.DroneEntity;
import ua.zentix.incoming.entity.StrikeProjectile;
import ua.zentix.incoming.grid.Blackouts;
import ua.zentix.incoming.grid.Node;
import ua.zentix.incoming.grid.PowerGrid;
import ua.zentix.incoming.launcher.FixedLauncherBlockEntity;
import ua.zentix.incoming.launcher.LauncherOrders;
import ua.zentix.incoming.launcher.Mission;
import ua.zentix.incoming.nuclear.Detonation;
import ua.zentix.incoming.nuclear.NuclearStrikes;
import ua.zentix.incoming.nuclear.NuclearWarhead;
import ua.zentix.incoming.nuclear.model.BlastModel;
import ua.zentix.incoming.progress.ModStats;
import ua.zentix.incoming.registry.ModBlocks;
import ua.zentix.incoming.registry.ModEntities;
import ua.zentix.incoming.registry.ModItems;
import ua.zentix.incoming.strike.CameraLink;
import ua.zentix.incoming.strike.Loadout;
import ua.zentix.incoming.strike.Orders;
import ua.zentix.incoming.strike.RoundRef;
import ua.zentix.incoming.strike.ServerActions;
import ua.zentix.incoming.strike.StrikeWorld;
import ua.zentix.incoming.strike.VirtualFlights;
import ua.zentix.incoming.strike.Waypoints;
import ua.zentix.incoming.strike.WeaponType;
import ua.zentix.incoming.target.Target;

import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Advancements and statistics ({@code progress.Progress}): every probe of the A3 contract. Each advancement goes once to
 * the right player by its real path; never to an offline owner, an ownerless launcher, the console or a command block. Each
 * statistic counts once per round or event, survives the player's stats file and reads through a scoreboard objective.
 * Credited players are real {@link ServerPlayer}s in the player list ({@link Credited}): a {@code FakePlayer} counts no
 * stats and earns no advancements, as automation mods' fake players must not. They are not added to the world, so the
 * server never ticks them: a test posts their tick event itself. Each test has its own batch.
 */
@GameTestHolder(Incoming.MOD_ID)
@PrefixGameTestTemplate(false)
public final class ProgressGameTests {
    private static final BlockPos RANGE_CENTER = new BlockPos(32, 11, 32);

    private ProgressGameTests() {}

    // ---------------------------------------------------------------- launches

    /**
     * Rounds of a designator order, a player's command salvo of two and a fixed launcher whose owner is online: each owner
     * gets First Strike once and one launched round per round, in total and for the weapon; the command player's scoreboard
     * objective on {@code minecraft.custom:incoming.rounds_launched.drone} reads 2, and so does their stats file once saved
     * and read back. The console, a command block and a launcher whose owner is offline launch too, and credit no one: the
     * offline owner, logging in afterwards, has neither the advancement nor a launched round.
     */
    @GameTest(template = "runway", timeoutTicks = 1200, batch = "progress_launch", skyAccess = true)
    public static void launchesCreditOnlineOwnersOnce(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        MinecraftServer server = level.getServer();
        Credited designator = Credited.online(h, "progress_designator", 0, new BlockPos(8, 4, 40));
        designator.getInventory().add(new ItemStack(ModItems.SHAHED.get(), 1));
        Credited commander = Credited.online(h, "progress_commander", 2, new BlockPos(24, 4, 40));
        Credited launcherOwner = Credited.online(h, "progress_launcher_owner", 0, new BlockPos(16, 4, 70));
        UUID offline = UUID.randomUUID();

        Scoreboard board = server.getScoreboard();
        ObjectiveCriteria criterion = ObjectiveCriteria.byName("minecraft.custom:incoming.rounds_launched.drone")
                .orElseThrow(() -> new GameTestAssertException("no scoreboard criterion for the Shahed-136 statistic"));
        Objective objective = board.addObjective("progress_drone", criterion, Component.literal("drones"), criterion.getDefaultRenderType(), false, null);
        StrikeGameTests.afterTest(h, () -> board.removeObjective(objective));

        Set<UUID> orders = new HashSet<>();
        List<StrikeRoundEvent.Released> released = new ArrayList<>();
        listen(h, StrikeRoundEvent.Released.class, e -> {
            if (orders.contains(e.getOrderId())) released.add(e);
        });
        listen(h, ua.zentix.incoming.api.StrikeOrderEvent.Accepted.class, e -> orders.add(e.getOrderId()));
        StrikeGameTests.afterTest(h, () -> clearRounds(level, orders));

        // a kilometre down the runway: every launch heads along it
        Vec3 aim = Vec3.atBottomCenterOf(h.absolutePos(new BlockPos(16, 3, 1000)));
        String at = String.format(Locale.ROOT, "at %.1f %.1f", aim.x, aim.z);
        h.assertTrue(ServerActions.strike(designator, WeaponType.DRONE, 1, 0, new ServerActions.Aim(new Target.Point(aim), aim, null), Loadout.Nuke.DEFAULT,
                Waypoints.NONE, null, true), "designator order refused");
        server.getCommands().performPrefixedCommand(commander.createCommandSourceStack().withSuppressedOutput(), "incoming salvo drone 2 4 " + at);
        BlockPos block = h.absolutePos(new BlockPos(16, 4, 100));
        server.getCommands().performPrefixedCommand(new CommandSourceStack(CommandSource.NULL, Vec3.atCenterOf(block), Vec2.ZERO, level, 2, "@",
                Component.literal("@"), server, null), "incoming salvo drone 1 0 " + at);
        BlockPos console = h.absolutePos(new BlockPos(16, 4, 130));
        server.getCommands().performPrefixedCommand(server.createCommandSourceStack().withLevel(level).withSuppressedOutput(),
                "incoming salvo drone 1 0 " + at + " from " + console.getX() + " " + console.getZ());
        fireLauncher(h, new BlockPos(8, 4, 8), launcherOwner.getUUID(), aim);
        fireLauncher(h, new BlockPos(24, 4, 8), offline, aim);

        h.succeedWhen(() -> {
            // designator 1, command 2, command block 1, console 1, two launchers 1 each
            h.assertTrue(released.size() >= 7, "rounds released: " + released.size() + " of 7");
            Orders.deliver(level);
            for (Credited p : List.of(designator, launcherOwner)) {
                h.assertTrue(done(p, "strikes/first_strike"), p.getGameProfile().getName() + " has no First Strike");
                h.assertValueEqual(stat(p, ModStats.ROUNDS_LAUNCHED), 1, p.getGameProfile().getName() + ": rounds launched");
                h.assertValueEqual(stat(p, ModStats.launched(WeaponType.DRONE)), 1, p.getGameProfile().getName() + ": Shahed-136 launched");
            }
            h.assertTrue(done(commander, "strikes/first_strike"), "the command player has no First Strike");
            h.assertValueEqual(stat(commander, ModStats.ROUNDS_LAUNCHED), 2, "command salvo of two: rounds launched");
            h.assertValueEqual(stat(commander, ModStats.launched(WeaponType.DRONE)), 2, "command salvo of two: Shahed-136 launched");
            h.assertValueEqual(stat(commander, ModStats.launched(WeaponType.MISSILE)), 0, "a cruise missile counted");
            h.assertValueEqual(board.getOrCreatePlayerScore(commander, objective).get(), 2, "scoreboard objective");
            h.assertTrue(released.stream().filter(r -> r.getOwnerId() == null).count() >= 2, "console and command block rounds not released");

            commander.getStats().save();
            File file = server.getWorldPath(LevelResource.PLAYER_STATS_DIR).resolve(commander.getStringUUID() + ".json").toFile();
            ServerStatsCounter reread = new ServerStatsCounter(server, file);
            h.assertValueEqual(reread.getValue(Stats.CUSTOM.get(ModStats.launched(WeaponType.DRONE))), 2, "stats file read back");

            Credited back = Credited.online(h, offline, "progress_offline_owner", 0, new BlockPos(16, 4, 20));
            h.assertFalse(done(back, "strikes/first_strike"), "the offline launcher owner got First Strike");
            h.assertValueEqual(stat(back, ModStats.ROUNDS_LAUNCHED), 0, "the offline launcher owner: rounds launched");
        });
    }

    /** A fixed launcher at {@code pad} owned by {@code owner}, one Shahed-136 at {@code aim}, fired as redstone would. */
    private static void fireLauncher(GameTestHelper h, BlockPos pad, UUID owner, Vec3 aim) {
        FixedLauncherBlockEntity be = FixedLauncherGameTests.launcher(h, pad, owner);
        FixedLauncherGameTests.load(h, be, new ItemStack(ModItems.SHAHED.get(), 1));
        be.setMission(new Mission(WeaponType.DRONE, 1, 0, new Target.Point(aim), aim, Waypoints.NONE));
        Component problem = LauncherOrders.fire(h.getLevel(), be);
        h.assertTrue(problem == null, "launcher refused: " + FixedLauncherGameTests.key(problem));
    }

    // ---------------------------------------------------------------- interceptions

    /**
     * A SAM whose owner is online shoots down an ownerless Shahed-136 carrying a round: the owner gets Clear Skies and one
     * interception.
     */
    @GameTest(template = "runway", timeoutTicks = 600, batch = "progress_sam", skyAccess = true)
    public static void samInterceptionCreditsItsOnlineOwner(GameTestHelper h) {
        Credited crew = Credited.online(h, "progress_sam_crew", 0, new BlockPos(19, 4, 100));
        SamGameTests.air(h, 1, 1);
        SamBlockEntity sam = SamGameTests.sam(h, new BlockPos(16, 4, 100), crew.getUUID(), 1);
        DroneEntity drone = SamGameTests.drone(h, 10, null);
        drone.attachRound(RoundRef.of(UUID.randomUUID(), 0, null));
        h.succeedWhen(() -> {
            h.assertTrue(drone.isRemoved(), "the Shahed-136 is still flying");
            Orders.deliver(h.getLevel());
            h.assertTrue(done(crew, "strikes/first_interception"), "the SAM owner has no Clear Skies");
            h.assertValueEqual(stat(crew, ModStats.INTERCEPTIONS), 1, "interceptions");
            h.assertTrue(sam.ready() + sam.stockCount() == 0, "the SAM did not fire");
        });
    }

    /**
     * A player's weapon downs an ownerless cruise missile carrying a round: Clear Skies and one interception. Their own missile
     * downed the same way (and a teammate's would be, by the SAM radar's rule) counts nothing.
     */
    @GameTest(template = "range", timeoutTicks = 20, batch = "progress_shot_down", skyAccess = true)
    public static void shotDownByPlayerCountsOnlyHostileRounds(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        Credited gunner = Credited.online(h, "progress_gunner", 0, RANGE_CENTER);
        StrikeGameTests.afterTest(h, () -> NuclearStrikes.clear(level));
        CruiseMissileEntity own = missile(h, new BlockPos(20, 32, 20), gunner.getUUID());
        own.hurt(level.damageSources().playerAttack(gunner), Float.MAX_VALUE);
        Orders.deliver(level);
        h.assertTrue(own.isRemoved(), "the gunner's own missile survived");
        h.assertValueEqual(stat(gunner, ModStats.INTERCEPTIONS), 0, "own round counted as an interception");
        h.assertFalse(done(gunner, "strikes/first_interception"), "own round gave Clear Skies");

        CruiseMissileEntity hostile = missile(h, new BlockPos(40, 32, 40), null);
        hostile.hurt(level.damageSources().playerAttack(gunner), Float.MAX_VALUE);
        Orders.deliver(level);
        h.assertTrue(hostile.isRemoved(), "the hostile missile survived");
        h.assertValueEqual(stat(gunner, ModStats.INTERCEPTIONS), 1, "interceptions");
        h.assertTrue(done(gunner, "strikes/first_interception"), "no Clear Skies");
        h.succeed();
    }

    private static CruiseMissileEntity missile(GameTestHelper h, BlockPos at, @Nullable UUID owner) {
        ServerLevel level = h.getLevel();
        CruiseMissileEntity m = ModEntities.CRUISE_MISSILE.get().create(level);
        Vec3 from = Vec3.atCenterOf(h.absolutePos(at)), to = from.add(0, 0, 2000);
        m.launch(from, new Target.Point(to), to, owner);
        m.setPos(from);
        m.attachRound(RoundRef.of(UUID.randomUUID(), 0, null));
        h.assertTrue(level.addFreshEntity(m), "missile not added");
        StrikeGameTests.afterTest(h, () -> {
            if (!m.isRemoved()) m.discard();
        });
        return m;
    }

    // ---------------------------------------------------------------- camera

    /**
     * Two Shahed-136 at a spotted cow. The first one's owner holds the cow in its camera frame each tick: its impact gives Eyes
     * on Target. The second one's owner never looks through its camera: its round ends without it. A third Shahed-136,
     * retargeted from its camera, keeps the mark through off-world flight (a copy loaded from its save) and gives nothing when
     * it ends without an impact.
     */
    @GameTest(template = "range", timeoutTicks = 2400, batch = "progress_camera", skyAccess = true)
    public static void cameraGuidedImpactCreditsItsOwner(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        Credited pilot = Credited.online(h, "progress_pilot", 0, RANGE_CENTER.offset(-10, 0, -10));
        Credited blind = Credited.online(h, "progress_blind", 0, RANGE_CENTER.offset(10, 0, -10));
        Credited retargeter = Credited.online(h, "progress_retarget", 0, RANGE_CENTER.offset(0, 0, -16));
        Cow cow = h.spawn(EntityType.COW, RANGE_CENTER.above());
        cow.setNoAi(true);
        Vec3 seen = cow.getBoundingBox().getCenter();
        Target sighted = new Target.Sighted(Target.OfEntity.center(cow), seen);
        DroneEntity guided = drone(h, new BlockPos(8, 40, 8), sighted, seen, pilot.getUUID());
        DroneEntity unguided = drone(h, new BlockPos(56, 40, 8), sighted, seen, blind.getUUID());
        UUID guidedId = guided.getUUID();
        List<StrikeRoundEvent.Ended> ended = new ArrayList<>();
        UUID[] rounds = {guided.round().round(), unguided.round().round()};
        listen(h, StrikeRoundEvent.Ended.class, e -> {
            if (e.getRoundId().equals(rounds[0]) || e.getRoundId().equals(rounds[1])) ended.add(e);
        });

        DroneEntity third = drone(h, new BlockPos(32, 40, 56), new Target.Point(seen), seen, retargeter.getUUID());
        Vec3 elsewhere = seen.add(5, 0, 5);
        h.assertTrue(third.retarget(new Target.Point(elsewhere), elsewhere), "retarget refused");
        StrikeProjectile copy = VirtualFlights.park(level, third);
        h.assertTrue(copy != null && copy.cameraGuided(), "the camera mark did not survive off-world flight");
        VirtualFlights.get(level).clear(level, p -> p == copy);
        Orders.deliver(level);
        h.assertFalse(done(retargeter, "strikes/camera_strike"), "a camera-steered round that never went off gave Eyes on Target");

        h.onEachTick(() -> {
            // the pilot's client sends its camera view every tick while aboard
            if (level.getEntity(guidedId) instanceof StrikeProjectile p && cow.isAlive()) {
                Vec3 d = cow.getBoundingBox().getCenter().subtract(p.getEyePosition());
                float yaw = (float) Math.toDegrees(Math.atan2(-d.x, d.z));
                float pitch = (float) Math.toDegrees(-Math.atan2(d.y, Math.sqrt(d.x * d.x + d.z * d.z)));
                CameraLink.receive(pilot, guidedId, yaw, pitch);
            }
        });
        h.succeedWhen(() -> {
            h.assertValueEqual(ended.size(), 2, "rounds ended");
            StrikeRoundEvent.Ended g = ended.stream().filter(e -> e.getRoundId().equals(rounds[0])).findFirst().orElseThrow();
            h.assertValueEqual(g.getOutcome(), StrikeRoundEvent.Outcome.IMPACT, "the guided round's outcome");
            h.assertTrue(g.isCameraGuided(), "the guided round's end is not camera-guided");
            h.assertTrue(done(pilot, "strikes/camera_strike"), "the pilot has no Eyes on Target");
            h.assertFalse(ended.stream().filter(e -> e.getRoundId().equals(rounds[1])).findFirst().orElseThrow().isCameraGuided(),
                    "a round never seen through its camera ended camera-guided");
            h.assertFalse(done(blind, "strikes/camera_strike"), "the owner who never looked got Eyes on Target");
        });
    }

    private static DroneEntity drone(GameTestHelper h, BlockPos at, Target target, Vec3 point, UUID owner) {
        ServerLevel level = h.getLevel();
        DroneEntity d = ModEntities.DRONE.get().create(level);
        d.launch(Vec3.atCenterOf(h.absolutePos(at)), target, point, owner);
        d.attachRound(RoundRef.of(UUID.randomUUID(), 0, null));
        level.addFreshEntity(d);
        UUID id = d.getUUID();
        StrikeGameTests.afterTest(h, () -> {
            if (level.getEntity(id) != null) level.getEntity(id).discard();
            VirtualFlights.get(level).clear(level, p -> p.getUUID().equals(id));
        });
        return d;
    }

    // ---------------------------------------------------------------- nuclear

    /** Burst of the survival test: its 5 psi ring falls inside the platform with room for a box either side. */
    private static final double SURVIVAL_KT = 0.1;
    private static final float SURVIVAL_SCALE = 0.1f;

    /**
     * A small surface burst. Five players stand sealed in stone boxes in the world, so the real wave reaches and hits them
     * (shelter only spares them its injuries). Inside the 5 psi ring: one alive once the wave has passed gets Still Standing
     * (not before); one killed after the hit, one who turns invulnerable (creative) after the hit, and one in creative mode
     * throughout do not. Between 4 and 5 psi: hit, not granted. All within the damage reach count one detonation witnessed,
     * a player beyond it none.
     */
    @GameTest(template = "range", timeoutTicks = 600, batch = "progress_nuke", skyAccess = true)
    public static void survivorOfTheWaveOnly(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        boolean fires = IncomingConfig.SERVER.nukeFires.get(), damage = IncomingConfig.SERVER.nukeBlockDamage.get();
        IncomingConfig.SERVER.nukeFires.set(false);
        IncomingConfig.SERVER.nukeBlockDamage.set(false);
        StrikeGameTests.afterTest(h, () -> {
            IncomingConfig.SERVER.nukeFires.set(fires);
            IncomingConfig.SERVER.nukeBlockDamage.set(damage);
            NuclearStrikes.clear(level);
        });
        Vec3 ground = Vec3.atBottomCenterOf(h.absolutePos(RANGE_CENTER));
        Detonation plan = NuclearWarhead.geometry(level, ground, SURVIVAL_KT, false, SURVIVAL_SCALE);
        double ring = plan.groundRange(BlastModel.kpa(5)), edge = plan.groundRange(BlastModel.kpa(4));
        h.assertTrue(ring > 8 && edge < 30, "5 psi ring " + ring + ", 4 psi " + edge + " blocks: does not fit the platform");
        Vec3 burst = plan.burst();
        Credited survivor = sealed(h, "progress_survivor", burst.add(ring * 0.6, 0, 0));
        Credited dead = sealed(h, "progress_dead", burst.add(-ring * 0.6, 0, 0));
        Credited creative = sealed(h, "progress_creative", burst.add(0, 0, ring * 0.6));
        creative.getAbilities().invulnerable = true;
        Credited turned = sealed(h, "progress_turned", burst.add(0, 0, -ring * 0.6));
        double mid = (ring + edge) / 2 / Math.sqrt(2);
        Credited outside = sealed(h, "progress_outside", burst.add(mid, 0, mid));
        Credited far = Credited.online(h, "progress_far", 0, burst.add(plan.radiusMax() + 50, 0, 0));
        for (Credited p : List.of(survivor, dead, creative, turned)) h.assertTrue(plan.psi(p.position()) >= 5, p.getGameProfile().getName() + " not inside 5 psi");
        double between = plan.psi(outside.position());
        h.assertTrue(between < 5 && between >= 4, "the outside player at " + between + " psi");

        Detonation d = NuclearWarhead.detonate(level, ground, SURVIVAL_KT, false, null, SURVIVAL_SCALE);
        h.assertTrue(d.burst().distanceTo(burst) < 1e-6, "the burst moved from its plan");
        List<Credited> all = List.of(survivor, dead, creative, turned, outside, far);
        boolean[] killed = {false}, switched = {false};
        h.onEachTick(() -> {
            for (Credited p : all) NeoForge.EVENT_BUS.post(new PlayerTickEvent.Post(p));
            if (!killed[0] && !watches(dead).isEmpty()) {
                dead.kill();
                h.assertTrue(watches(dead).isEmpty(), "death left a survival watch");
                killed[0] = true;
            }
            if (!switched[0] && !watches(turned).isEmpty()) {
                turned.getAbilities().invulnerable = true;
                switched[0] = true;
            }
            if (watches(survivor).isEmpty() == false) {
                h.assertFalse(done(survivor, "strikes/nuclear_survivor"), "Still Standing while still watched");
            }
        });
        h.succeedWhen(() -> {
            h.assertTrue(killed[0] && switched[0], "the wave never hit the boxed players");
            h.assertTrue(watches(outside).isEmpty() && watches(survivor).isEmpty(), "waiting for the wave to pass");
            h.assertTrue(done(survivor, "strikes/nuclear_survivor"), "the survivor has no Still Standing");
            h.assertFalse(done(dead, "strikes/nuclear_survivor"), "a dead player got Still Standing");
            h.assertFalse(done(creative, "strikes/nuclear_survivor"), "a creative player got Still Standing");
            h.assertFalse(done(turned, "strikes/nuclear_survivor"), "a player invulnerable when the wave had passed got Still Standing");
            h.assertFalse(done(outside, "strikes/nuclear_survivor"), "a player below 5 psi got Still Standing");
            for (Credited p : List.of(survivor, dead, creative, turned, outside)) {
                h.assertValueEqual(stat(p, ModStats.NUCLEAR_DETONATIONS_WITNESSED), 1, p.getGameProfile().getName() + ": detonations witnessed");
            }
            h.assertValueEqual(stat(far, ModStats.NUCLEAR_DETONATIONS_WITNESSED), 0, "beyond the damage reach: detonations witnessed");
        });
    }

    private static List<ua.zentix.incoming.progress.Progress.Watch> watches(ServerPlayer p) {
        return p.getData(ua.zentix.incoming.registry.ModAttachments.SURVIVAL);
    }

    /**
     * A credited player standing at {@code at} in the world, in a closed stone box (no air path to the sky: the wave's
     * pressure does not reach the body, its hit still comes).
     */
    private static Credited sealed(GameTestHelper h, String name, Vec3 at) {
        ServerLevel level = h.getLevel();
        BlockPos feet = BlockPos.containing(at);
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                for (int dy = -1; dy <= 2; dy++) {
                    if (dx == 0 && dz == 0 && (dy == 0 || dy == 1)) continue;
                    level.setBlock(feet.offset(dx, dy, dz), net.minecraft.world.level.block.Blocks.STONE.defaultBlockState(), 2);
                }
            }
        }
        level.setBlock(feet, net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(), 2);
        level.setBlock(feet.above(), net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(), 2);
        Credited p = Credited.online(h, name, 0, Vec3.atBottomCenterOf(feet));
        level.addNewPlayer(p);
        StrikeGameTests.afterTest(h, () -> level.removePlayerImmediately(p, net.minecraft.world.entity.Entity.RemovalReason.DISCARDED));
        return p;
    }

    // ---------------------------------------------------------------- grid

    /** A substation outage restored: the player inside its area gets Lights Back On, the one outside does not. */
    @GameTest(template = "range", timeoutTicks = 400, batch = "progress_grid", skyAccess = true)
    public static void restoreCreditsPlayersInTheArea(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        Blackouts.restore(level, null, 0, 0);
        PowerGrid grid = PowerGrid.get(level);
        List<Integer> ids = new ArrayList<>();
        for (Node n : grid.nodes()) ids.add(n.id());
        ids.forEach(grid::removeNode);
        h.setBlock(RANGE_CENTER, ModBlocks.SUBSTATION.get());
        BlockPos node = h.absolutePos(RANGE_CENTER);
        List<ua.zentix.incoming.api.GridOutageEvent> outages = new ArrayList<>();
        listen(h, ua.zentix.incoming.api.GridOutageEvent.class, e -> {
            if (node.equals(e.getNodePos())) outages.add(e);
        });
        Vec3 c = Vec3.atCenterOf(node.east(3));
        level.explode(null, c.x, c.y, c.z, 2f, Level.ExplosionInteraction.NONE);
        Orders.deliver(level);
        h.assertValueEqual(outages.size(), 1, "outages");
        double radius = outages.getFirst().getRadius();
        Credited inside = Credited.online(h, "progress_in_dark", 0, RANGE_CENTER.offset(2, 0, 2));
        // beyond any outage another batch may restore meanwhile (a nuclear one reaches at most 16 384 blocks)
        Credited away = Credited.online(h, "progress_away", 0, RANGE_CENTER);
        away.moveTo(Vec3.atCenterOf(node).add(radius + 40_000, 0, 0));
        int cx = inside.getBlockX() >> 4, cz = inside.getBlockZ() >> 4;
        // restored only once its cascade has darkened the player's district: a district never dark earns nothing
        h.startSequence()
                .thenWaitUntil(() -> h.assertTrue(grid.dark(cx, cz, Blackouts.now(level)), "waiting for the player's district to go dark"))
                .thenExecute(() -> h.assertTrue(Blackouts.restore(level, Vec3.atCenterOf(node), 8, 0) >= 1, "nothing to restore"))
                .thenWaitUntil(() -> {
                    Orders.deliver(level);
                    h.assertTrue(done(inside, "strikes/lights_back"), "the player in the area has no Lights Back On");
                })
                .thenExecute(() -> h.assertFalse(done(away, "strikes/lights_back"), "a player outside the area got Lights Back On"))
                .thenSucceed();
    }

    // ---------------------------------------------------------------- data

    /**
     * Every statistic is a scoreboard criterion and has a stat object; the six advancements loaded (a broken criterion
     * would drop an advancement with only a log line) and all but the root show in the vanilla screen with toasts.
     */
    @GameTest(template = "range", timeoutTicks = 20, batch = "progress_data", skyAccess = true)
    public static void statisticsAndAdvancementsLoad(GameTestHelper h) {
        MinecraftServer server = h.getLevel().getServer();
        // created at common setup, before any lookup could create them lazily: the statistics screen lists only these
        for (var id : ModStats.all()) h.assertTrue(Stats.CUSTOM.contains(id), "stat " + id + " not created");
        for (var id : ModStats.all()) {
            String name = "minecraft.custom:" + id.getNamespace() + "." + id.getPath();
            h.assertTrue(ObjectiveCriteria.byName(name).isPresent(), "no scoreboard criterion " + name);
        }
        h.assertValueEqual(ModStats.all().size(), 3 + WeaponType.values().length, "statistics");
        // the tab opens with any Incoming item: the root's list names every item the mod registers
        Set<String> listed = new HashSet<>();
        try (var in = server.getResourceManager().getResourceOrThrow(Incoming.id("advancement/strikes/root.json")).openAsReader()) {
            com.google.gson.JsonObject root = com.google.gson.JsonParser.parseReader(in).getAsJsonObject();
            for (var e : root.getAsJsonObject("criteria").getAsJsonObject("has_incoming_item").getAsJsonObject("conditions").getAsJsonArray("items")
                    .get(0).getAsJsonObject().getAsJsonArray("items")) listed.add(e.getAsString());
        } catch (java.io.IOException e) {
            throw new GameTestAssertException("root advancement unreadable: " + e);
        }
        for (var id : net.minecraft.core.registries.BuiltInRegistries.ITEM.keySet()) {
            if (id.getNamespace().equals(Incoming.MOD_ID)) h.assertTrue(listed.contains(id.toString()), "the tab does not open with " + id);
        }
        for (String path : List.of("root", "first_strike", "camera_strike", "first_interception", "nuclear_survivor", "lights_back")) {
            AdvancementHolder a = server.getAdvancements().get(Incoming.id("strikes/" + path));
            h.assertTrue(a != null && a.value().display().isPresent(), "advancement strikes/" + path + " not loaded");
            boolean root = path.equals("root");
            h.assertValueEqual(a.value().display().get().shouldShowToast(), !root, path + ": toast");
            h.assertValueEqual(a.value().parent().isPresent(), !root, path + ": parent");
        }
        h.succeed();
    }

    // ---------------------------------------------------------------- helpers

    private static boolean done(ServerPlayer p, String path) {
        AdvancementHolder a = p.server.getAdvancements().get(Incoming.id(path));
        if (a == null) throw new GameTestAssertException("advancement " + path + " not loaded");
        return p.getAdvancements().getOrStartProgress(a).isDone();
    }

    private static int stat(ServerPlayer p, net.minecraft.resources.ResourceLocation id) {
        return p.getStats().getValue(Stats.CUSTOM.get(id));
    }

    private static <T extends net.neoforged.bus.api.Event> void listen(GameTestHelper h, Class<T> type, Consumer<T> listener) {
        NeoForge.EVENT_BUS.addListener(net.neoforged.bus.api.EventPriority.LOWEST, false, type, listener);
        StrikeGameTests.afterTest(h, () -> NeoForge.EVENT_BUS.unregister(listener));
    }

    /** Every projectile, salvo and off-world flight of {@code orders}: the test's own rounds leave with it. */
    private static void clearRounds(ServerLevel level, Set<UUID> orders) {
        for (StrikeProjectile p : List.copyOf(StrikeWorld.projectiles(level))) {
            if (p.round() != null && orders.contains(p.round().order())) p.discard();
        }
        VirtualFlights.get(level).clear(level, p -> p.round() != null && orders.contains(p.round().order()));
        for (ServerPlayer p : level.getServer().getPlayerList().getPlayers()) {
            ua.zentix.incoming.strike.SalvoData.get(level).cancel(level, p.getUUID(), List.of());
        }
        ua.zentix.incoming.strike.SalvoData.get(level).cancel(level, null, List.of());
    }

    /**
     * A real player in the server's player list (credited: online, not a fake player), at {@code at} in the test's world but
     * not added to it. Packets to it are swallowed ({@link PermissionGameTests#online}).
     */
    static final class Credited extends ServerPlayer {
        private final int permission;

        private Credited(ServerLevel level, UUID id, String name, int permission) {
            super(level.getServer(), level, new GameProfile(id, name), ClientInformation.createDefault());
            this.permission = permission;
        }

        static Credited online(GameTestHelper h, String name, int permission, BlockPos at) {
            return online(h, UUID.randomUUID(), name, permission, at);
        }

        static Credited online(GameTestHelper h, UUID id, String name, int permission, BlockPos at) {
            return online(h, id, name, permission, Vec3.atBottomCenterOf(h.absolutePos(at)));
        }

        static Credited online(GameTestHelper h, String name, int permission, Vec3 at) {
            return online(h, UUID.randomUUID(), name, permission, at);
        }

        /** Its connection first: a server player's move resets the client's position through it. */
        static Credited online(GameTestHelper h, UUID id, String name, int permission, Vec3 at) {
            Credited p = new Credited(h.getLevel(), id, name, permission);
            PermissionGameTests.online(h, p);
            p.moveTo(at);
            return p;
        }

        @Override
        protected int getPermissionLevel() {
            return permission;
        }
    }
}
