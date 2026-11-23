package ua.zentix.incoming.gametest;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.CommandSource;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import ua.zentix.incoming.Incoming;
import ua.zentix.incoming.IncomingConfig;
import ua.zentix.incoming.api.StrikeOrderEvent;
import ua.zentix.incoming.entity.CruiseMissileEntity;
import ua.zentix.incoming.entity.IcbmEntity;
import ua.zentix.incoming.entity.LauncherEntity;
import ua.zentix.incoming.entity.StrikeProjectile;
import ua.zentix.incoming.guidance.Route;
import ua.zentix.incoming.launcher.FixedLauncherBlockEntity;
import ua.zentix.incoming.launcher.Mission;
import ua.zentix.incoming.nuclear.Detonation;
import ua.zentix.incoming.nuclear.NuclearEvents;
import ua.zentix.incoming.nuclear.NuclearStrikes;
import ua.zentix.incoming.nuclear.world.NuclearWorld;
import ua.zentix.incoming.nuclear.world.WorkClock;
import ua.zentix.incoming.registry.ModBlocks;
import ua.zentix.incoming.registry.ModEntities;
import ua.zentix.incoming.registry.ModItems;
import ua.zentix.incoming.strike.FlightTickets;
import ua.zentix.incoming.strike.Loadout;
import ua.zentix.incoming.strike.OrderStatus;
import ua.zentix.incoming.strike.RoundRef;
import ua.zentix.incoming.strike.SalvoData;
import ua.zentix.incoming.strike.ServerActions;
import ua.zentix.incoming.strike.StrikeWorld;
import ua.zentix.incoming.strike.VirtualFlights;
import ua.zentix.incoming.strike.Waypoints;
import ua.zentix.incoming.strike.WeaponType;
import ua.zentix.incoming.target.Target;
import ua.zentix.incoming.util.Terrain;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Regressions from the independent review of A2 (command results, status, tags), one probe each: status inside an
 * {@code Accepted} listener and during an off-world flight's world entry; a deferred command naming its order; {@code clear
 * tag} leaving launchers and removing the boosting ICBM it cancels; {@code nuke now} waiting for its ground counted as
 * queued; {@code launcher … fire} returning the admitted rounds, and naming its order when the release fails; a nuclear
 * crater still being dug keeping its round open; status inside {@code Accepted} after a clear there.
 */
@GameTestHolder(Incoming.MOD_ID)
@PrefixGameTestTemplate(false)
public final class OrderStatusRegressionGameTests {
    private static final BlockPos CENTER = new BlockPos(32, 11, 32);

    private OrderStatusRegressionGameTests() {}

    @GameTest(template = "range", timeoutTicks = 20, batch = "status_r_accepted", skyAccess = true)
    public static void commandReadsSingleRoundDuringItsAcceptedEvent(GameTestHelper h) {
        String tag = "reviewAccepted";
        int[] observed = {-1};
        CommandSourceStack source = console(h, new ArrayList<>());
        Consumer<StrikeOrderEvent.Accepted> observe = event -> {
            if (tag.equals(event.getTag())) observed[0] = run(source, "incoming status tag " + tag);
        };
        NeoForge.EVENT_BUS.addListener(observe);
        StrikeGameTests.afterTest(h, () -> {
            NeoForge.EVENT_BUS.unregister(observe);
            ServerActions.clearTag(h.getLevel().getServer(), tag, "review");
        });

        h.assertValueEqual(run(source, "incoming salvo drone 1 0" + at(h) + " tag " + tag), 1, "admitted");
        h.assertValueEqual(run(source, "incoming status tag " + tag), 1, "after command");
        h.assertValueEqual(observed[0], 1, "inside Accepted");
        h.succeed();
    }

    @GameTest(template = "range", timeoutTicks = 20, batch = "status_r_world_entry", skyAccess = true)
    public static void statusDuringWorldEntryFindsBothOffWorldFlights(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        String tag = "reviewWorldEntry";
        UUID order = UUID.randomUUID();
        Vec3 point = Vec3.atCenterOf(h.absolutePos(CENTER)).add(0, 80, 0);
        Vec3 target = point.add(0, 0, 40);
        class Missile extends CruiseMissileEntity {
            private final boolean enters;
            Missile(boolean enters) {
                super(ModEntities.CRUISE_MISSILE.get(), level);
                this.enters = enters;
            }
            @Override public boolean canMaterialize(ServerLevel current) { return enters; }
        }
        Missile returning = new Missile(true), other = new Missile(false);
        int[] observed = {-1};
        CommandSourceStack source = console(h, new ArrayList<>());
        Consumer<EntityJoinLevelEvent> observer = event -> {
            if (event.getEntity() == returning) observed[0] = run(source, "incoming status tag " + tag);
        };
        NeoForge.EVENT_BUS.addListener(observer);
        StrikeGameTests.afterTest(h, () -> {
            NeoForge.EVENT_BUS.unregister(observer);
            ServerActions.clearTag(level.getServer(), tag, "review");
        });
        List<Missile> flights = List.of(returning, other);
        for (int i = 0; i < flights.size(); i++) {
            Missile missile = flights.get(i);
            missile.launch(point, new Target.Point(target), target, null);
            missile.setRoute(Route.direct());
            missile.attachRound(RoundRef.of(order, i, tag));
            VirtualFlights.launch(level, missile);
        }
        h.assertValueEqual(run(source, "incoming status tag " + tag), 2, "status before world entry");
        h.startSequence().thenWaitUntil(() -> h.assertTrue(observed[0] >= 0, "the join observer did not run"))
                .thenExecute(() -> h.assertValueEqual(observed[0], 2, "status from EntityJoinLevelEvent"))
                .thenSucceed();
    }

    @GameTest(template = "range", timeoutTicks = 20, batch = "status_r_deferred", skyAccess = true)
    public static void deferredCommandReportsItsOrderIdOnAdmission(GameTestHelper h) {
        String outer = "reviewOuter", inner = "reviewDeferred";
        List<Component> feedback = new ArrayList<>();
        CommandSourceStack source = console(h, feedback);
        int[] nestedResult = {-1};
        UUID[] actual = {null};
        Consumer<StrikeOrderEvent.Request> enqueue = event -> {
            if (outer.equals(event.getTag())) nestedResult[0] = run(source, "incoming salvo drone 2 0" + at(h) + " tag " + inner);
        };
        Consumer<StrikeOrderEvent.Accepted> observe = event -> {
            if (inner.equals(event.getTag())) actual[0] = event.getOrderId();
        };
        NeoForge.EVENT_BUS.addListener(enqueue);
        NeoForge.EVENT_BUS.addListener(observe);
        StrikeGameTests.afterTest(h, () -> {
            NeoForge.EVENT_BUS.unregister(enqueue);
            NeoForge.EVENT_BUS.unregister(observe);
            ServerActions.clearTag(h.getLevel().getServer(), outer, "review");
            ServerActions.clearTag(h.getLevel().getServer(), inner, "review");
        });

        h.assertValueEqual(run(source, "incoming salvo drone 2 0" + at(h) + " tag " + outer), 2, "outer result");
        h.assertValueEqual(nestedResult[0], 0, "deferred result on request tick");
        h.startSequence().thenWaitUntil(() -> h.assertTrue(actual[0] != null, "deferred order was never admitted"))
                .thenExecute(() -> h.assertTrue(feedback.stream().anyMatch(c -> mentions(c, actual[0].toString())),
                        "no command feedback named deferred order " + actual[0] + ": " + feedback))
                .thenSucceed();
    }

    @GameTest(template = "range", timeoutTicks = 20, batch = "status_r_launcher", skyAccess = true)
    public static void clearTagLeavesTheUnrelatedOrderedLauncher(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        UUID owner = UUID.randomUUID();
        String tag = "reviewLauncher";
        Vec3 pad = Vec3.atCenterOf(h.absolutePos(CENTER));
        Vec3 anotherPad = pad.add(12, 0, 0);
        Vec3 target = pad.add(0, 10, 40);
        LauncherEntity assigned = LauncherEntity.create(level, pad, 0, WeaponType.MISSILE, owner, true, pad);
        LauncherEntity unrelated = LauncherEntity.create(level, anotherPad, 0, WeaponType.MISSILE, owner, true, anotherPad);
        StrikeProjectile shot = ModEntities.CRUISE_MISSILE.get().create(level);
        shot.placeOnLauncher(assigned.railPoint(0), 0, assigned.elevation(), 1000, 0, new Target.Point(target), target, owner);
        shot.attachRound(RoundRef.of(UUID.randomUUID(), 0, tag));
        StrikeGameTests.afterTest(h, () -> {
            shot.discard();
            assigned.discard();
            unrelated.discard();
        });
        h.assertTrue(level.addFreshEntity(assigned) && level.addFreshEntity(unrelated) && level.addFreshEntity(shot), "fixture did not enter world");

        h.assertValueEqual(run(console(h, new ArrayList<>()), "incoming clear tag " + tag), 1, "rounds cancelled");
        h.assertTrue(shot.isRemoved(), "the tagged shot on the rail remains");
        // a launcher matches its shots only by owner and weapon: a tag cannot tell which one served it, so both stay
        h.assertTrue(!assigned.isRemoved() && !unrelated.isRemoved(), "clear tag removed a launcher");
        h.succeed();
    }

    @GameTest(template = "range", timeoutTicks = 20, batch = "status_r_booster", skyAccess = true)
    public static void clearTagCancelsTheBoostingIcbmEntity(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        var operator = new MunitionGameTests.Listener(level, "review_a2_booster", 2);
        operator.moveTo(Vec3.atCenterOf(h.absolutePos(CENTER)).add(0, 1, 12));
        String tag = "reviewBooster";
        boolean enabled = IncomingConfig.SERVER.nukeEnabled.get();
        StrikeGameTests.afterTest(h, () -> {
            ServerActions.clearTag(level.getServer(), tag, "review");
            StrikeWorld.projectiles(level).stream().filter(p -> operator.getUUID().equals(p.ownerId())).forEach(StrikeProjectile::discard);
            IncomingConfig.SERVER.nukeEnabled.set(enabled);
        });
        IncomingConfig.SERVER.nukeEnabled.set(true);

        h.assertValueEqual(run(operator.createCommandSourceStack(), "incoming nuke" + at(h) + " 1 tag " + tag), 1, "nuke admitted");
        IcbmEntity booster = level.getEntitiesOfClass(IcbmEntity.class, h.getBounds().inflate(48),
                p -> operator.getUUID().equals(p.ownerId())).stream().findFirst().orElseThrow();
        h.assertValueEqual(run(operator.createCommandSourceStack(), "incoming clear tag " + tag), 1, "one scheduled round cancelled");
        h.assertValueEqual(run(operator.createCommandSourceStack(), "incoming status tag " + tag), 0, "rounds left");
        h.assertTrue(booster.isRemoved(), "the cancelled ICBM is still boosting");
        h.succeed();
    }

    @GameTest(template = "range", timeoutTicks = 20, batch = "status_r_immediate", skyAccess = true)
    public static void nukeNowWaitingForGroundIsQueued(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        String tag = "reviewImmediate";
        BlockPos far = h.absolutePos(CENTER).offset(5000, 0, 5000);
        boolean enabled = IncomingConfig.SERVER.nukeEnabled.get();
        StrikeGameTests.afterTest(h, () -> {
            ServerActions.clearTag(level.getServer(), tag, "review");
            IncomingConfig.SERVER.nukeEnabled.set(enabled);
        });
        IncomingConfig.SERVER.nukeEnabled.set(true);
        h.assertTrue(!Terrain.ready(level, far), "fixture needs an unloaded target");
        CommandSourceStack console = console(h, new ArrayList<>());
        h.assertValueEqual(run(console, "incoming nuke now at " + far.getX() + " " + far.getY() + " " + far.getZ() + " 1 tag " + tag),
                1, "admitted rounds");
        var pending = OrderStatus.rounds(level.getServer(), new OrderStatus.Filter(null, tag));
        h.assertValueEqual(pending.size(), 1, "unfinished rounds");
        h.assertValueEqual(pending.getFirst().phase(), OrderStatus.Phase.QUEUED, "phase while awaiting the chunk without flight");
        h.succeed();
    }

    @GameTest(template = "range", timeoutTicks = 20, batch = "status_r_fixed_fire", skyAccess = true)
    public static void fixedLauncherFireReturnsTheAdmittedRounds(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        UUID owner = UUID.randomUUID();
        h.setBlock(CENTER, ModBlocks.FIXED_LAUNCHER.get());
        FixedLauncherBlockEntity launcher = h.getBlockEntity(CENTER);
        launcher.setOwner(owner);
        Vec3 target = Vec3.atCenterOf(h.absolutePos(CENTER)).add(0, 0, 40);
        launcher.setMission(new Mission(WeaponType.DRONE, 3, 0, new Target.Point(target), target, Waypoints.NONE));
        h.assertTrue(launcher.load(new ItemStack(ModItems.SHAHED.get(), 3)).isEmpty(), "stock not loaded");
        StrikeGameTests.afterTest(h, () -> {
            SalvoData.get(level).cancel(level, owner, List.of());
            h.setBlock(CENTER, Blocks.AIR);
        });
        BlockPos pad = h.absolutePos(CENTER);
        List<Component> feedback = new ArrayList<>();
        int result = run(console(h, feedback), "incoming launcher " + pad.getX() + " " + pad.getY() + " " + pad.getZ() + " fire");
        h.assertValueEqual(SalvoData.get(level).remaining(owner), 3, "rounds queued by the launcher");
        h.assertValueEqual(result, 3, "command result for that order");
        h.succeed();
    }

    @GameTest(template = "range", timeoutTicks = 20, batch = "status_r_crater", skyAccess = true)
    public static void aCraterWaitingOnBudgetKeepsNuclearImpactOpen(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        NuclearEvents events = NuclearEvents.get(level);
        NuclearWorld world = NuclearWorld.get(level);
        boolean crater = IncomingConfig.SERVER.nukeCrater.get(), damage = IncomingConfig.SERVER.nukeBlockDamage.get();
        StrikeGameTests.afterTest(h, () -> {
            NuclearStrikes.clear(level);
            IncomingConfig.SERVER.nukeCrater.set(crater);
            IncomingConfig.SERVER.nukeBlockDamage.set(damage);
        });
        IncomingConfig.SERVER.nukeCrater.set(true);
        IncomingConfig.SERVER.nukeBlockDamage.set(true);
        Vec3 point = Vec3.atBottomCenterOf(h.absolutePos(CENTER));
        String tag = "reviewCrater";
        // Model a ground burst beyond its front while the lane is fully spent on prior work.
        long now = level.getGameTime();
        int id = events.nextId();
        Detonation d = new Detonation(id, point, point.y, 1, true, now - 1199,
                0, 0, 20000, 42, 0.05f, false, 1, Detonation.NO_DAY_TIME, false, false);
        h.assertTrue(now - d.gameTime() > d.arrivalTicks(d.blastReach()) + d.positivePhaseTicks(d.blastReach()) + 2,
                "fixture must be beyond the front");
        events.add(d, level);
        events.impact(id, RoundRef.of(UUID.randomUUID(), 0, tag), null);
        world.onDetonation(level, d, null);
        h.assertTrue(world.craterJobs() > 0, "no crater to finish");
        WorkClock budget = WorkClock.counting(1);
        budget.start(1);
        budget.end(budget.begin());
        h.assertTrue(!budget.canStart(), "budget was not exhausted");

        NuclearStrikes.work(List.of(level), budget);
        h.assertTrue(world.craterJobs() > 0, "crater already finished");
        h.assertValueEqual(run(console(h, new ArrayList<>()), "incoming status tag " + tag), 1, "round while its crater still has work");
        h.succeed();
    }

    @GameTest(template = "range", timeoutTicks = 20, batch = "status_r2_accepted_clear", skyAccess = true)
    public static void statusInsideAcceptedReflectsAnImmediatelyClearedSalvo(GameTestHelper h) {
        String tag = "reviewAcceptedClear";
        BlockPos target = h.absolutePos(CENTER);
        CommandSourceStack source = console(h, new ArrayList<>());
        String status = "incoming status tag " + tag;
        int[] seen = {-1, -1, -1};
        Consumer<StrikeOrderEvent.Accepted> listener = event -> {
            if (!tag.equals(event.getTag())) return;
            seen[0] = run(source, status);
            seen[1] = run(source, "incoming clear tag " + tag);
            seen[2] = run(source, status);
        };
        NeoForge.EVENT_BUS.addListener(listener);
        StrikeGameTests.afterTest(h, () -> {
            NeoForge.EVENT_BUS.unregister(listener);
            ServerActions.clearTag(h.getLevel().getServer(), tag, "review");
        });

        h.assertValueEqual(run(source, "incoming salvo drone 2 0 at " + target.getX() + " " + target.getY() + " " + target.getZ() + " tag " + tag), 2, "rounds admitted");
        h.assertValueEqual(seen[0], 2, "status at entry to Accepted");
        h.assertValueEqual(seen[1], 2, "cancelled from Accepted");
        h.assertValueEqual(run(source, status), 0, "status after the same admission returned");
        h.assertValueEqual(seen[2], 0, "status immediately after clear returned in Accepted");
        h.succeed();
    }

    @GameTest(template = "range", timeoutTicks = 20, batch = "status_r2_fixed_report", skyAccess = true)
    public static void fixedFireNamesTheAcceptedOrderEvenIfReleaseFails(GameTestHelper h) {
        BlockPos pad = h.absolutePos(CENTER);
        h.setBlock(CENTER, ModBlocks.FIXED_LAUNCHER.get());
        FixedLauncherBlockEntity launcher = h.getBlockEntity(CENTER);
        Vec3 target = Vec3.atCenterOf(pad).add(0, 0, 40);
        launcher.setMission(new Mission(WeaponType.DRONE, 1, 0, new Target.Point(target), target, Waypoints.NONE));
        h.assertTrue(launcher.load(new ItemStack(ModItems.SHAHED.get())).isEmpty(), "stock loaded");

        UUID[] accepted = {null};
        int[] vetoes = {0};
        Consumer<StrikeOrderEvent.Accepted> observe = event -> {
            if (pad.equals(event.getLauncherPos())) accepted[0] = event.getOrderId();
        };
        Consumer<EntityJoinLevelEvent> veto = event -> {
            if (event.getLevel() == h.getLevel() && event.getEntity() instanceof StrikeProjectile p
                    && p.flightPhase().onLauncher() && p.position().distanceToSqr(Vec3.atCenterOf(pad)) < 36) {
                vetoes[0]++;
                event.setCanceled(true);
            }
        };
        NeoForge.EVENT_BUS.addListener(observe);
        NeoForge.EVENT_BUS.addListener(veto);
        StrikeGameTests.afterTest(h, () -> {
            NeoForge.EVENT_BUS.unregister(observe);
            NeoForge.EVENT_BUS.unregister(veto);
            h.setBlock(CENTER, Blocks.AIR);
        });

        List<Component> feedback = new ArrayList<>();
        int result = run(console(h, feedback), "incoming launcher " + pad.getX() + " " + pad.getY() + " " + pad.getZ() + " fire");
        h.assertValueEqual(result, 1, "the failed release still admitted one round");
        h.assertTrue(accepted[0] != null, "the launcher's order was accepted");
        h.assertValueEqual(vetoes[0], 1, "claim listener vetoed the projectile join");
        h.assertValueEqual(launcher.stockCount(), 1, "failed release restored its munition");
        h.assertTrue(feedback.stream().anyMatch(c -> mentions(c, accepted[0].toString())), "feedback did not name admitted order " + accepted[0]);
        h.succeed();
    }

    /** {@code c} holds {@code text}: literal text in it, its arguments or its siblings. */
    private static boolean mentions(Component c, String text) {
        if (c.getContents() instanceof net.minecraft.network.chat.contents.TranslatableContents t) {
            for (Object arg : t.getArgs()) {
                if (arg instanceof Component a ? mentions(a, text) : String.valueOf(arg).contains(text)) return true;
            }
        }
        if (c.getContents() instanceof net.minecraft.network.chat.contents.PlainTextContents p && p.text().contains(text)) return true;
        for (Component s : c.getSiblings()) if (mentions(s, text)) return true;
        return false;
    }

    private static String at(GameTestHelper h) {
        BlockPos p = h.absolutePos(CENTER);
        return " at " + p.getX() + " " + p.getY() + " " + p.getZ();
    }

    private static int run(CommandSourceStack source, String command) {
        try {
            return source.getServer().getCommands().getDispatcher().execute(command, source);
        } catch (CommandSyntaxException e) {
            throw new GameTestAssertException(command + ": " + e.getMessage());
        }
    }

    private static CommandSourceStack console(GameTestHelper h, List<Component> feedback) {
        ServerLevel level = h.getLevel();
        CommandSource output = new CommandSource() {
            @Override public void sendSystemMessage(Component message) { feedback.add(message); }
            @Override public boolean acceptsSuccess() { return true; }
            @Override public boolean acceptsFailure() { return true; }
            @Override public boolean shouldInformAdmins() { return false; }
        };
        return new CommandSourceStack(output, Vec3.atCenterOf(h.absolutePos(CENTER)), Vec2.ZERO, level, 4,
                "review", Component.literal("review"), level.getServer(), null);
    }
}
