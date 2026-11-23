package ua.zentix.incoming.gametest;

import net.minecraft.commands.CommandSource;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.NbtOps;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.Event;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import ua.zentix.incoming.Incoming;
import ua.zentix.incoming.IncomingConfig;
import ua.zentix.incoming.api.GridRestoreEvent;
import ua.zentix.incoming.api.NuclearDetonationEvent;
import ua.zentix.incoming.api.StrikeOrderEvent;
import ua.zentix.incoming.api.StrikeRoundEvent;
import ua.zentix.incoming.entity.BomberEntity;
import ua.zentix.incoming.entity.BunkerBusterEntity;
import ua.zentix.incoming.entity.CruiseMissileEntity;
import ua.zentix.incoming.entity.StrikeProjectile;
import ua.zentix.incoming.grid.Blackouts;
import ua.zentix.incoming.grid.Outage;
import ua.zentix.incoming.grid.PowerGrid;
import ua.zentix.incoming.guidance.BombDrop;
import ua.zentix.incoming.nuclear.NuclearEvents;
import ua.zentix.incoming.nuclear.NuclearStrikes;
import ua.zentix.incoming.nuclear.world.WorkClock;
import ua.zentix.incoming.registry.ModEntities;
import ua.zentix.incoming.registry.ModItems;
import ua.zentix.incoming.strike.Loadout;
import ua.zentix.incoming.strike.Munitions;
import ua.zentix.incoming.strike.NuclearKeys;
import ua.zentix.incoming.strike.Order;
import ua.zentix.incoming.strike.Orders;
import ua.zentix.incoming.strike.RoundRef;
import ua.zentix.incoming.strike.SalvoData;
import ua.zentix.incoming.strike.ServerActions;
import ua.zentix.incoming.strike.StrikeWorld;
import ua.zentix.incoming.strike.TargetMode;
import ua.zentix.incoming.strike.VirtualFlights;
import ua.zentix.incoming.strike.Waypoints;
import ua.zentix.incoming.strike.WeaponSpec;
import ua.zentix.incoming.strike.WeaponType;
import ua.zentix.incoming.target.Target;
import ua.zentix.incoming.util.Terrain;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Order and round events against listeners that act on them (the independent review's probes of A1): a listener that
 * recalls, clears, cancels or throws never makes a round end twice or not at all, never breaks Incoming's own loops, and
 * never reorders what other listeners see, because every event after Request is delivered at the tick's end
 * ({@link Orders#deliver}); orders started inside a Request listener or off the server thread run again with their
 * checks on the server thread; detonations and outages outside the order lifecycle are reported with null order ids.
 */
@GameTestHolder(Incoming.MOD_ID)
@PrefixGameTestTemplate(false)
public final class OrderReentryGameTests {
    private OrderReentryGameTests() {}

    @GameTest(template = "range", batch = "order_probe_salvo_recall", skyAccess = true)
    public static void releasedSalvoRoundCannotAlsoBeRefunded(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        var owner = MunitionGameTests.player(h, "probe_salvo");
        MunitionGameTests.give(owner, ModItems.SHAHED.get(), 2);
        List<StrikeRoundEvent.Ended> ended = new ArrayList<>();
        List<UUID> released = new ArrayList<>();
        listen(h, EventPriority.NORMAL, StrikeRoundEvent.Ended.class, e -> {
            if (owner.getUUID().equals(e.getOwnerId())) ended.add(e);
        });
        listen(h, EventPriority.NORMAL, StrikeRoundEvent.Released.class, e -> {
            if (!owner.getUUID().equals(e.getOwnerId())) return;
            released.add(e.getRoundId());
            SalvoData.get(level).cancel(level, owner.getUUID(), List.of(owner));
        });
        StrikeGameTests.afterTest(h, () -> {
            SalvoData.get(level).cancel(level, owner.getUUID(), List.of(owner));
            MunitionGameTests.removeFlights(level, owner.getUUID());
        });
        h.assertTrue(ServerActions.strike(owner, WeaponType.DRONE, 2, 0, aim(h), Loadout.Nuke.DEFAULT, Waypoints.NONE, null, true),
                "Fixture salvo was refused");
        RuntimeException tickFailure = null;
        try {
            StrikeWorld.onLevelTick(new LevelTickEvent.Post(() -> true, level));
        } catch (RuntimeException e) {
            tickFailure = e;
        }
        MunitionGameTests.removeFlights(level, owner.getUUID());
        Orders.deliver(level);
        h.assertValueEqual(released.size(), 1, "released rounds");
        UUID first = released.getFirst();
        h.assertTrue(tickFailure == null && ended.size() == 2
                        && ended.stream().filter(e -> first.equals(e.getRoundId())).count() == 1
                        && ended.stream().anyMatch(e -> first.equals(e.getRoundId()) && e.isReleased() && !e.isRefunded())
                        && MunitionGameTests.held(owner, ModItems.SHAHED.get()) == 1,
                "tick failure " + tickFailure + ", final events " + ended.size() + ", inventory "
                        + MunitionGameTests.held(owner, ModItems.SHAHED.get()));
        h.succeed();
    }

    @GameTest(template = "range", batch = "order_probe_orderevent_order", skyAccess = true)
    public static void observerSeesReleaseBeforeReentrantRecall(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        String tag = "probe_release_order";
        List<String> seen = new ArrayList<>();
        listen(h, EventPriority.HIGH, StrikeRoundEvent.Released.class, e -> {
            if (tag.equals(e.getTag())) VirtualFlights.get(level).clear(level, p -> carries(p, tag));
        });
        listen(h, EventPriority.LOW, StrikeRoundEvent.Released.class, e -> {
            if (tag.equals(e.getTag())) seen.add("released");
        });
        listen(h, EventPriority.LOW, StrikeRoundEvent.Ended.class, e -> {
            if (tag.equals(e.getTag())) seen.add("ended");
        });
        StrikeGameTests.afterTest(h, () -> VirtualFlights.get(level).clear(level, p -> carries(p, tag)));
        h.assertTrue(dispatch(h, WeaponType.DRONE, tag), "Fixture order was refused");
        Orders.deliver(level);
        h.assertValueEqual(seen, List.of("released", "ended"), "observer event order");
        h.succeed();
    }

    @GameTest(template = "range", batch = "order_probe_virtual_recall", skyAccess = true)
    public static void recallingASecondVirtualRoundFromEndedKeepsBothRemovals(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        String tag = "probe_virtual_recall";
        h.assertTrue(dispatch(h, WeaponType.DRONE, tag), "First fixture order was refused");
        h.assertTrue(dispatch(h, WeaponType.DRONE, tag), "Second fixture order was refused");
        Orders.deliver(level);
        h.assertValueEqual(VirtualFlights.get(level).flights().stream().filter(p -> carries(p, tag)).count(), 2L, "virtual fixture rounds");
        AtomicBoolean recalled = new AtomicBoolean();
        List<UUID> ends = new ArrayList<>();
        StrikeGameTests.afterTest(h, () -> VirtualFlights.get(level).clear(level, p -> carries(p, tag)));
        listen(h, EventPriority.NORMAL, StrikeRoundEvent.Ended.class, e -> {
            if (!tag.equals(e.getTag())) return;
            ends.add(e.getRoundId());
            if (recalled.compareAndSet(false, true)) VirtualFlights.get(level).clear(level, p -> carries(p, tag));
        });
        RuntimeException failure = null;
        try {
            VirtualFlights.get(level).clear(level, p -> carries(p, tag));
            Orders.deliver(level);
        } catch (RuntimeException e) {
            failure = e;
        }
        h.assertTrue(failure == null && ends.size() == 2 && ends.stream().distinct().count() == 2
                        && VirtualFlights.get(level).flights().stream().noneMatch(p -> carries(p, tag)),
                "Virtual recall threw " + failure + " and ended " + ends);
        h.succeed();
    }

    @GameTest(template = "range", batch = "order_probe_scheduled_clear", skyAccess = true)
    public static void nestedClearEndsScheduledRoundOnce(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        String tag = "probe_sched_clear";
        NuclearEvents.ScheduledStrike scheduled = scheduled(h, tag, level.getGameTime() + 200);
        StrikeGameTests.afterTest(h, () -> NuclearEvents.get(level).unschedule(scheduled));
        AtomicBoolean cleared = new AtomicBoolean();
        List<StrikeRoundEvent.Ended> ends = new ArrayList<>();
        listen(h, EventPriority.NORMAL, StrikeRoundEvent.Ended.class, e -> {
            if (!tag.equals(e.getTag())) return;
            ends.add(e);
            if (cleared.compareAndSet(false, true)) NuclearStrikes.clear(level);
        });
        NuclearStrikes.clear(level);
        Orders.deliver(level);
        h.assertValueEqual(ends.size(), 1, "one recalled event for the scheduled round");
        h.succeed();
    }

    @GameTest(template = "range", batch = "order_probe_scheduled_due", skyAccess = true)
    public static void strikesDueTogetherEachEndOnceDespiteAClearFromADetonation(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        StrikeGameTests.afterTest(h, () -> NuclearStrikes.clear(level));
        scheduled(h, "probe_due_first", level.getGameTime());
        NuclearEvents.ScheduledStrike second = scheduled(h, "probe_due_second", level.getGameTime());
        List<StrikeRoundEvent.Outcome> ends = new ArrayList<>();
        List<Integer> secondDetonations = new ArrayList<>();
        listen(h, EventPriority.NORMAL, NuclearDetonationEvent.class, e -> {
            if ("probe_due_first".equals(e.getTag())) NuclearStrikes.clear(level);
            if ("probe_due_second".equals(e.getTag())) secondDetonations.add(e.getDetonationId());
        });
        listen(h, EventPriority.NORMAL, StrikeRoundEvent.Ended.class, e -> {
            if (second.round().orElseThrow().round().equals(e.getRoundId())) ends.add(e.getOutcome());
        });
        NuclearStrikes.work(List.of(level), clock());
        Orders.deliver(level);
        // both were due in this tick and burst in it; the listener's clear comes after, and ends nothing twice
        h.assertTrue(ends.equals(List.of(StrikeRoundEvent.Outcome.IMPACT)) && secondDetonations.size() == 1,
                "Second due strike has outcomes " + ends + " and detonations " + secondDetonations);
        h.succeed();
    }

    @GameTest(template = "range", batch = "order_probe_second_keys", skyAccess = true)
    public static void nestedKeyCancelDoesNotRefundAnOrderTwice(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        var owner = MunitionGameTests.player(h, "probe_key_owner");
        var other = MunitionGameTests.player(h, "probe_key_peer");
        var online = List.of(owner, other);
        Loadout loadout = new Loadout(WeaponType.NUKE, 1, 0, TargetMode.LOOK, "", new Loadout.Nuke(1, false));
        Munitions.Bill bill = Munitions.Bill.of(loadout);
        MunitionGameTests.give(owner, WeaponType.NUKE.spec().munition().item().get(), 2);
        for (int i = 0; i < 2; i++) {
            Order order = Order.player(owner, loadout, aim(h), StrikeOrderEvent.Payment.UPFRONT);
            h.assertTrue(Orders.admit(order, o -> {
                if (!Munitions.pay(owner, bill)) return false;
                o.accept(1);
                return NuclearKeys.order(owner, online, loadout, aim(h), Waypoints.NONE, null, bill, o.round(0));
            }), "Fixture order did not reach second-key wait");
        }
        AtomicBoolean nested = new AtomicBoolean();
        List<UUID> ended = new ArrayList<>();
        listen(h, EventPriority.NORMAL, StrikeRoundEvent.Ended.class, e -> {
            if (!owner.getUUID().equals(e.getOwnerId())) return;
            ended.add(e.getRoundId());
            if (nested.compareAndSet(false, true)) NuclearKeys.cancelAll(level.getServer(), online, null, "nested probe recall");
        });
        Orders.deliver(level);
        NuclearKeys.cancelAll(level.getServer(), online, null, "probe recall");
        Orders.deliver(level);
        int stock = MunitionGameTests.held(owner, WeaponType.NUKE.spec().munition().item().get());
        h.assertTrue(ended.size() == 2 && ended.stream().distinct().count() == 2 && stock == 2,
                "Expected two unique ends and two refunded munitions, got " + ended + " and " + stock);
        h.succeed();
    }

    @GameTest(template = "range", batch = "order_probe_deferred_cap", skyAccess = true)
    public static void deferredPlayerOrdersStillObeyTheActiveLimit(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        var owner = MunitionGameTests.player(h, "probe_deferred");
        MunitionGameTests.give(owner, ModItems.SHAHED.get(), 2);
        int original = IncomingConfig.SERVER.maxActivePerPlayer.get();
        IncomingConfig.SERVER.maxActivePerPlayer.set(1);
        StrikeGameTests.afterTest(h, () -> {
            IncomingConfig.SERVER.maxActivePerPlayer.set(original);
            SalvoData.get(level).cancel(level, owner.getUUID(), List.of(owner));
            MunitionGameTests.removeFlights(level, owner.getUUID());
        });
        List<UUID> admitted = new ArrayList<>();
        listen(h, EventPriority.NORMAL, StrikeOrderEvent.Accepted.class, e -> {
            if (owner.getUUID().equals(e.getActorId())) admitted.add(e.getOrderId());
        });
        listen(h, EventPriority.NORMAL, StrikeOrderEvent.Request.class, e -> {
            if (!"probe_deferred_outer".equals(e.getActorName())) return;
            for (int i = 0; i < 2; i++) {
                ServerActions.strike(owner, WeaponType.DRONE, 1, 1, aim(h), Loadout.Nuke.DEFAULT, Waypoints.NONE, null, true);
            }
            e.setCanceled(true);
        });
        h.assertFalse(dispatch(h, WeaponType.DRONE, "probe_deferred_outer"), "Fixture outer order was not vetoed");
        h.runAfterDelay(3, () -> {
            h.assertValueEqual(admitted.size(), 1, "admissions with active limit one");
            h.assertValueEqual(MunitionGameTests.held(owner, ModItems.SHAHED.get()), 1, "munition for second order stays with owner");
            h.succeed();
        });
    }

    @GameTest(template = "range", batch = "order_probe_listener_error", skyAccess = true)
    public static void assertionErrorFromListenerDoesNotInterruptRemoval(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        StrikeProjectile projectile = ModEntities.CRUISE_MISSILE.get().create(level);
        h.assertTrue(projectile != null, "missile fixture was not created");
        RoundRef round = RoundRef.of(UUID.randomUUID(), 0, "probe_listener_error");
        projectile.attachRound(round);
        listen(h, EventPriority.NORMAL, StrikeRoundEvent.Ended.class, e -> {
            if (round.round().equals(e.getRoundId())) throw new AssertionError("third-party listener failure");
        });
        Throwable failure = null;
        try {
            projectile.discard();
            Orders.deliver(level);
        } catch (AssertionError e) {
            failure = e;
        }
        boolean removed = projectile.isRemoved();
        if (!removed) projectile.discard();
        h.assertTrue(failure == null && removed, "Removal stopped at listener failure: " + failure);
        h.succeed();
    }

    @GameTest(template = "range", batch = "order_probe_entity_set_removed", skyAccess = true)
    public static void directDestructiveEntityRemovalEndsRound(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        String tag = "probe_set_removed";
        h.assertTrue(dispatch(h, WeaponType.DRONE, tag), "Fixture order was refused");
        Orders.deliver(level);
        StrikeProjectile p = VirtualFlights.get(level).flights().stream().filter(f -> carries(f, tag)).findFirst().orElseThrow();
        VirtualFlights.get(level).flights().remove(p);
        StrikeGameTests.afterTest(h, p::discard);
        Vec3 pos = aim(h).point().add(0, 20, 0);
        p.setPos(pos);
        p.materialize(level);
        h.assertTrue(level.addFreshEntity(p) && level.getEntity(p.getUUID()) == p, "Fixture did not enter the tracked world");
        List<StrikeRoundEvent.Ended> ended = new ArrayList<>();
        listen(h, EventPriority.NORMAL, StrikeRoundEvent.Ended.class, e -> {
            if (tag.equals(e.getTag())) ended.add(e);
        });
        p.setRemoved(Entity.RemovalReason.DISCARDED);
        Orders.deliver(level);
        h.assertTrue(p.isRemoved() && level.getEntity(p.getUUID()) == null, "Fixture entity was not removed by setRemoved");
        h.assertTrue(ended.size() == 1 && ended.getFirst().isReleased() && !ended.getFirst().isRefunded(),
                "Direct destructive removal posted " + ended.size() + " endings");
        h.succeed();
    }

    @GameTest(template = "range", batch = "order_probe_thread", skyAccess = true)
    public static void workerDispatchCannotPostARequestOffThread(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        String tag = "probe_thread";
        AtomicBoolean offThread = new AtomicBoolean();
        listen(h, EventPriority.HIGHEST, StrikeOrderEvent.Request.class, e -> {
            if (!tag.equals(e.getTag())) return;
            e.setCanceled(true);
            offThread.set(!level.getServer().isSameThread());
        });
        ServerActions.Aim aim = aim(h);
        var worker = CompletableFuture.runAsync(() ->
                ServerActions.dispatch(level, tag, 0, WeaponType.DRONE, 1, 0, aim, Loadout.Nuke.DEFAULT, Waypoints.NONE, null, tag, null));
        h.succeedWhen(() -> {
            h.assertTrue(worker.isDone(), "worker call has yet to return");
            h.assertFalse(offThread.get(), "Request was posted on the worker");
        });
    }

    @GameTest(template = "range", batch = "order_probe_nuke_dimension", skyAccess = true)
    public static void nukeNowRequestNamesTheCommandDimension(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        ServerLevel other = level.getServer().getLevel(Level.NETHER);
        h.assertTrue(other != null && other != level, "fixture needs another dimension");
        var actor = new MunitionGameTests.Listener(other, "probe_nuke_dimension", 2);
        boolean enabled = IncomingConfig.SERVER.nukeEnabled.get();
        IncomingConfig.SERVER.nukeEnabled.set(true);
        StrikeGameTests.afterTest(h, () -> IncomingConfig.SERVER.nukeEnabled.set(enabled));
        List<StrikeOrderEvent.Request> requests = new ArrayList<>();
        listen(h, EventPriority.NORMAL, StrikeOrderEvent.Request.class, e -> {
            if (!actor.getUUID().equals(e.getActorId())) return;
            requests.add(e);
            e.setCanceled(true);
        });
        Vec3 at = aim(h).point();
        var source = new CommandSourceStack(CommandSource.NULL, at, Vec2.ZERO, level, 2, "probe_nuke_dimension",
                Component.literal("probe_nuke_dimension"), level.getServer(), actor).withSuppressedOutput();
        level.getServer().getCommands().performPrefixedCommand(source, "incoming nuke now at ~ ~ ~ 1 air");
        h.assertValueEqual(requests.size(), 1, "requests");
        h.assertValueEqual(requests.getFirst().getDimension(), level.dimension(), "dimension being targeted");
        h.succeed();
    }

    @GameTest(template = "range", batch = "order_probe_shot_nuke", skyAccess = true)
    public static void armedCarrierShotDownReportsCorrelatedBurstBeforeEnd(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        StrikeGameTests.afterTest(h, () -> NuclearStrikes.clear(level));
        Vec3 at = aim(h).point().add(0, 20, 0);
        Vec3 target = at.add(0, 0, 2000);
        CruiseMissileEntity missile = ModEntities.CRUISE_MISSILE.get().create(level);
        h.assertTrue(missile != null, "missile fixture was not created");
        missile.launch(at, new Target.Point(target), target, null);
        missile.setPos(at);
        missile.setNuclear(new Loadout.Nuke(1, true, true));
        RoundRef round = RoundRef.of(UUID.randomUUID(), 0, "probe_shot_nuke");
        missile.attachRound(round);
        List<String> events = new ArrayList<>();
        listen(h, EventPriority.NORMAL, NuclearDetonationEvent.class, e -> {
            if (e.getPosition().distanceToSqr(at) < 1_000_000) {
                events.add(round.round().equals(e.getRoundId()) && round.order().equals(e.getOrderId()) ? "burst" : "unlinked burst");
            }
        });
        listen(h, EventPriority.NORMAL, StrikeRoundEvent.Ended.class, e -> {
            if (round.round().equals(e.getRoundId())) events.add(e.getOutcome().getSerializedName());
        });
        h.assertTrue(missile.hurt(level.damageSources().generic(), Float.MAX_VALUE), "Armed fixture ignored lethal damage");
        Orders.deliver(level);
        h.assertValueEqual(events, List.of("burst", "intercepted"), "correlated detonation before intercepted end");
        h.succeed();
    }

    @GameTest(template = "range", batch = "order_probe_bomb_join", skyAccess = true)
    public static void bombJoinExceptionSettlesTheTransferredRound(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        Vec3 point = aim(h).point();
        Terrain.Surface surface = Terrain.estimate(level, Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                BlockPos.containing(point).getX(), BlockPos.containing(point).getZ(), Terrain.Allowed.CHUNK);
        h.assertTrue(surface.known(), "fixture surface is not ready");
        Vec3 drop = new Vec3(point.x, surface.y() - 0.5, point.z);
        double altitude = WeaponSpec.BUNKER.airframe().cruiseHeight();
        double line = BomberEntity.releaseLine(altitude);
        Vec3 start = null;
        for (double distance = line + 200; distance > 0; distance--) {
            Vec3 at = drop.add(0, altitude, -distance);
            if (BombDrop.releaseNow(at, BomberEntity.levelVelocity(0), drop, line / altitude)) {
                start = at;
                break;
            }
        }
        h.assertTrue(start != null, "no release position for the fixture");
        BomberEntity bomber = ModEntities.BOMBER.get().create(level);
        h.assertTrue(bomber != null, "bomber fixture was not created");
        bomber.launch(start, drop, null, null);
        bomber.setRoute(null);
        RoundRef round = RoundRef.of(UUID.randomUUID(), 0, "probe_bomb_join");
        bomber.attachRound(round);
        List<StrikeRoundEvent.Ended> ends = new ArrayList<>();
        List<BunkerBusterEntity> refused = new ArrayList<>();
        listen(h, EventPriority.NORMAL, StrikeRoundEvent.Ended.class, e -> {
            if (round.round().equals(e.getRoundId())) ends.add(e);
        });
        listen(h, EventPriority.NORMAL, EntityJoinLevelEvent.class, e -> {
            if (e.getEntity() instanceof BunkerBusterEntity bomb && bomb.ownerId() == null) {
                refused.add(bomb);
                throw new IllegalStateException("claim handler failed during entry");
            }
        });
        StrikeGameTests.afterTest(h, () -> {
            bomber.discard();
            refused.forEach(Entity::discard);
        });
        bomber.tick();
        Orders.deliver(level);
        h.assertValueEqual(refused.size(), 1, "bomb reached the join hook");
        h.assertTrue(ends.size() == 1 && ends.getFirst().isReleased() && ends.getFirst().getOutcome() == StrikeRoundEvent.Outcome.FAILED,
                "Transferred round has " + ends.size() + " endings");
        h.succeed();
    }

    @GameTest(template = "range", batch = "order_probe_legacy_nuclear", skyAccess = true)
    public static void legacyScheduledStrikeIsReportedWithoutOrderIds(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        StrikeGameTests.afterTest(h, () -> NuclearStrikes.clear(level));
        var data = NuclearEvents.get(level);
        Vec3 point = aim(h).point();
        long now = level.getGameTime();
        var old = new NuclearEvents.ScheduledStrike(data.nextId(), point, 1, true, now - 300, now, point, Optional.empty(), false);
        var codec = NuclearEvents.ScheduledStrike.CODEC;
        var encoded = codec.encodeStart(NbtOps.INSTANCE, old).getOrThrow();
        data.schedule(codec.parse(NbtOps.INSTANCE, encoded).getOrThrow());
        List<NuclearDetonationEvent> events = new ArrayList<>();
        listen(h, EventPriority.NORMAL, NuclearDetonationEvent.class, events::add);
        NuclearStrikes.work(List.of(level), clock());
        Orders.deliver(level);
        var burst = data.detonations().stream().filter(d -> d.gameTime() == now && d.burst().x == point.x && d.burst().z == point.z).findFirst();
        h.assertTrue(burst.isPresent(), "legacy strike did not detonate");
        // a detonation is reported whatever caused it; outside the order lifecycle its order and round are null
        h.assertTrue(events.stream().anyMatch(e -> e.getDetonationId() == burst.orElseThrow().id() && e.getOrderId() == null && e.getRoundId() == null),
                "legacy strike's detonation not reported with null order ids");
        h.succeed();
    }

    @GameTest(template = "range", batch = "order_probe_legacy_grid", skyAccess = true)
    public static void outageRecordedBeforeListenersStillReportsItsRestore(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        PowerGrid grid = PowerGrid.get(level);
        Vec3 at = aim(h).point();
        long now = Blackouts.now(level);
        Outage legacy = grid.addOutage(at.x, at.z, 16, now - 10, 8, now - 1, 0, -1);
        grid.swept(legacy.id(), true, Long.MAX_VALUE);
        List<UUID> restores = new ArrayList<>();
        listen(h, EventPriority.NORMAL, GridRestoreEvent.class, e -> restores.add(e.getOutageId()));
        h.succeedWhen(() -> {
            h.assertTrue(grid.outages().stream().noneMatch(o -> o.id() == legacy.id()), "legacy outage has not been pruned");
            h.assertTrue(restores.contains(Blackouts.outageId(level, legacy)), "the restore of an outage begun before the listener was not reported");
        });
    }

    @GameTest(template = "range", batch = "order_probe_nuke_now_clear", skyAccess = true)
    public static void clearFromUnloadedNukeNowReleaseCanRecallIt(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        Vec3 far = aim(h).point().add(40_000, 0, 40_000);
        h.assertFalse(Terrain.ready(level, BlockPos.containing(far)), "fixture target is already loaded");
        RoundRef round = RoundRef.of(UUID.randomUUID(), 0, "probe_nuke_now_clear");
        List<StrikeRoundEvent.Outcome> ends = new ArrayList<>();
        StrikeGameTests.afterTest(h, () -> NuclearStrikes.clear(level));
        listen(h, EventPriority.NORMAL, StrikeRoundEvent.Released.class, e -> {
            if (round.round().equals(e.getRoundId())) NuclearStrikes.clear(level);
        });
        listen(h, EventPriority.NORMAL, StrikeRoundEvent.Ended.class, e -> {
            if (round.round().equals(e.getRoundId())) ends.add(e.getOutcome());
        });
        NuclearStrikes.detonateNow(level, far, 1, true, null, round);
        Orders.deliver(level);
        h.assertTrue(NuclearEvents.get(level).scheduled().stream().noneMatch(s -> s.round().map(round::equals).orElse(false))
                        && ends.equals(List.of(StrikeRoundEvent.Outcome.RECALLED)),
                "The clear missed the released round; final outcomes " + ends);
        h.succeed();
    }

    private static boolean dispatch(GameTestHelper h, WeaponType weapon, String tag) {
        return ServerActions.dispatch(h.getLevel(), tag, 0, weapon, 1, 0, aim(h), Loadout.Nuke.DEFAULT, Waypoints.NONE, null, tag, null);
    }

    private static ServerActions.Aim aim(GameTestHelper h) {
        Vec3 point = Vec3.atCenterOf(h.absolutePos(new BlockPos(32, 12, 32)));
        return new ServerActions.Aim(new Target.Point(point), point, null);
    }

    private static boolean carries(StrikeProjectile p, String tag) {
        return p.round() != null && tag.equals(p.round().tag());
    }

    private static NuclearEvents.ScheduledStrike scheduled(GameTestHelper h, String tag, long when) {
        ServerLevel level = h.getLevel();
        NuclearEvents data = NuclearEvents.get(level);
        Vec3 point = aim(h).point();
        var record = new NuclearEvents.ScheduledStrike(data.nextId(), point, 1, true, level.getGameTime(), when, point,
                Optional.empty(), false).withRound(RoundRef.of(UUID.randomUUID(), 0, tag));
        data.schedule(record);
        return record;
    }

    private static WorkClock clock() {
        WorkClock clock = WorkClock.counting(1_000_000);
        clock.start(1_000_000);
        return clock;
    }

    private static <T extends Event> void listen(GameTestHelper h, EventPriority priority, Class<T> type, Consumer<T> listener) {
        NeoForge.EVENT_BUS.addListener(priority, false, type, listener);
        StrikeGameTests.afterTest(h, () -> NeoForge.EVENT_BUS.unregister(listener));
    }
}
