package ua.zentix.incoming.gametest;

import com.mojang.serialization.DataResult;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.Event;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import org.jetbrains.annotations.Nullable;
import ua.zentix.incoming.Incoming;
import ua.zentix.incoming.IncomingConfig;
import ua.zentix.incoming.api.GridOutageEvent;
import ua.zentix.incoming.api.GridRestoreEvent;
import ua.zentix.incoming.api.NuclearDetonationEvent;
import ua.zentix.incoming.api.StrikeOrderEvent;
import ua.zentix.incoming.api.StrikeRoundEvent;
import ua.zentix.incoming.entity.IcbmEntity;
import ua.zentix.incoming.entity.StrikeProjectile;
import ua.zentix.incoming.grid.Blackouts;
import ua.zentix.incoming.grid.Node;
import ua.zentix.incoming.grid.PowerGrid;
import ua.zentix.incoming.launcher.FixedLauncherBlockEntity;
import ua.zentix.incoming.launcher.LauncherOrders;
import ua.zentix.incoming.launcher.Mission;
import ua.zentix.incoming.nuclear.NuclearEvents;
import ua.zentix.incoming.nuclear.NuclearStrikes;
import ua.zentix.incoming.registry.ModBlocks;
import ua.zentix.incoming.registry.ModItems;
import ua.zentix.incoming.strike.Loadout;
import ua.zentix.incoming.strike.Orders;
import ua.zentix.incoming.strike.RoundRef;
import ua.zentix.incoming.strike.SalvoData;
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
import java.util.function.Predicate;

/**
 * Order lifecycle events ({@code ua.zentix.incoming.api}): every probe of the contract in the A1 pull request. Events
 * after Request are queued to the tick's end; a check right after an action delivers them first ({@link Orders#deliver}). Each request
 * gets exactly one Rejected or Accepted; each admitted round exactly one Ended, Released first when it left; the round id
 * survives a projectile's form changes and the save of a scheduled ICBM strike. Listeners are registered on the game bus for
 * the test only and filter by the test's own order ids (other tests in the batch also post events). Each test has its own
 * batch: nuclear clears and the grid are world-wide.
 */
@GameTestHolder(Incoming.MOD_ID)
@PrefixGameTestTemplate(false)
public final class OrderEventGameTests {
    private static final BlockPos RANGE_CENTER = new BlockPos(32, 11, 32);

    private OrderEventGameTests() {}

    /**
     * A second listener vetoes after a first one let the request through: Rejected(VETOED), no Accepted, nothing paid, its
     * line shown to the player. Without the veto the same salvo is Accepted (paid upfront, 3 rounds); cancelling it before
     * any launch ends each of the 3 rounds once as recalled, unreleased and refunded, with the order's round ids.
     */
    @GameTest(template = "range", timeoutTicks = 20, batch = "order_veto", skyAccess = true)
    public static void secondListenerVetoesBeforePayment(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        MunitionGameTests.Listener p = MunitionGameTests.player(h, "order_veto");
        MunitionGameTests.give(p, ModItems.SHAHED.get(), 3);
        EventLog log = EventLog.open(h, e -> e instanceof StrikeOrderEvent o ? p.getUUID().equals(o.getActorId())
                : e instanceof StrikeRoundEvent r && p.getUUID().equals(r.getOwnerId()));
        List<String> seen = new ArrayList<>();
        boolean[] veto = {true};
        listen(h, EventPriority.HIGH, StrikeOrderEvent.Request.class, r -> {
            if (p.getUUID().equals(r.getActorId())) seen.add("first");
        });
        listen(h, EventPriority.NORMAL, StrikeOrderEvent.Request.class, r -> {
            if (!p.getUUID().equals(r.getActorId())) return;
            seen.add("second");
            if (veto[0]) {
                r.setCanceled(true);
                r.setRefusal(Component.literal("cooldown"));
            }
        });
        ServerActions.Aim aim = aim(h);

        h.assertFalse(ServerActions.strike(p, WeaponType.DRONE, 3, 20, aim, Loadout.Nuke.DEFAULT, Waypoints.NONE, null, true), "vetoed order launched");
        Orders.deliver(level);
        h.assertValueEqual(seen, List.of("first", "second"), "listeners in priority order");
        StrikeOrderEvent.Request request = log.only(StrikeOrderEvent.Request.class);
        StrikeOrderEvent.Rejected rejected = log.only(StrikeOrderEvent.Rejected.class);
        h.assertValueEqual(rejected.getOrderId(), request.getOrderId(), "Rejected answers its Request");
        h.assertValueEqual(rejected.getReason(), StrikeOrderEvent.Reason.VETOED, "reason");
        h.assertValueEqual(log.all(StrikeOrderEvent.Accepted.class).size(), 0, "a vetoed order was accepted");
        h.assertValueEqual(MunitionGameTests.held(p, ModItems.SHAHED.get()), 3, "a vetoed order was paid");
        h.assertValueEqual(SalvoData.get(level).remaining(p.getUUID()), 0, "a vetoed salvo was recorded");
        h.assertTrue(p.said.stream().anyMatch(c -> c.getString().contains("cooldown")), "the listener's refusal was not shown: " + p.said);

        veto[0] = false;
        log.clear();
        h.assertTrue(ServerActions.strike(p, WeaponType.DRONE, 3, 20, aim, Loadout.Nuke.DEFAULT, Waypoints.NONE, null, true), "order without veto");
        Orders.deliver(level);
        StrikeOrderEvent.Accepted accepted = log.only(StrikeOrderEvent.Accepted.class);
        h.assertValueEqual(log.all(StrikeOrderEvent.Rejected.class).size(), 0, "an accepted order was also rejected");
        h.assertValueEqual(accepted.getOrderId(), log.only(StrikeOrderEvent.Request.class).getOrderId(), "Accepted answers its Request");
        h.assertValueEqual(accepted.getRounds(), 3, "admitted rounds");
        h.assertValueEqual(accepted.getPayment(), StrikeOrderEvent.Payment.UPFRONT, "payment");
        h.assertValueEqual(MunitionGameTests.held(p, ModItems.SHAHED.get()), 0, "accepted salvo not paid");

        // cancelled in the same tick: no round has launched yet
        h.assertValueEqual(SalvoData.get(level).cancel(level, p.getUUID(), List.of(p)), 1, "salvo not cancelled");
        Orders.deliver(level);
        List<StrikeRoundEvent.Ended> ended = log.all(StrikeRoundEvent.Ended.class);
        h.assertValueEqual(ended.size(), 3, "each unlaunched round ends once");
        for (int i = 0; i < 3; i++) {
            StrikeRoundEvent.Ended e = ended.get(i);
            h.assertValueEqual(e.getRoundId(), RoundRef.of(accepted.getOrderId(), i, null).round(), "round " + i + " id");
            h.assertValueEqual(e.getOutcome(), StrikeRoundEvent.Outcome.RECALLED, "round " + i + " outcome");
            h.assertTrue(!e.isReleased() && e.isRefunded() && e.getPosition() == null, "round " + i + " unreleased and refunded");
        }
        h.assertValueEqual(log.all(StrikeRoundEvent.Released.class).size(), 0, "a cancelled round was released");
        h.assertValueEqual(MunitionGameTests.held(p, ModItems.SHAHED.get()), 3, "refund");
        h.succeed();
    }

    /**
     * An order started inside a Request listener is not admitted inside it: it gets its own Request on a later tick, and both
     * orders get their answers.
     */
    @GameTest(template = "range", timeoutTicks = 20, batch = "order_nested", skyAccess = true)
    public static void orderFromRequestListenerIsQueued(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        String outer = "order_nested_outer", inner = "order_nested_inner";
        EventLog log = EventLog.open(h, e -> e instanceof StrikeOrderEvent o && (outer.equals(o.getActorName()) || inner.equals(o.getActorName())));
        boolean[] inside = {false};
        boolean[] nested = {false};
        long[] askedAt = {-1};
        UUID[] innerOrder = {null};
        StrikeGameTests.afterTest(h, () -> VirtualFlights.get(level).clear(level, p -> p.round() != null && p.round().order().equals(innerOrder[0])));
        listen(h, EventPriority.NORMAL, StrikeOrderEvent.Request.class, r -> {
            if (inner.equals(r.getActorName())) {
                h.assertFalse(inside[0], "the nested order was requested inside the outer request");
                askedAt[0] = level.getGameTime();
                innerOrder[0] = r.getOrderId();
            }
            if (!outer.equals(r.getActorName()) || nested[0]) return;
            nested[0] = true;
            inside[0] = true;
            try {
                h.assertTrue(dispatch(h, inner), "the nested order was not queued");
            } finally {
                inside[0] = false;
            }
            r.setCanceled(true);
        });
        long now = level.getGameTime();
        h.assertFalse(dispatch(h, outer), "the vetoed outer order launched");
        h.assertValueEqual(log.all(StrikeOrderEvent.Request.class).size(), 1, "requests in the outer tick");
        h.succeedWhen(() -> {
            h.assertTrue(askedAt[0] > now, "the nested order was not requested on a later tick");
            h.assertValueEqual(log.all(StrikeOrderEvent.Request.class).size(), 2, "requests");
            h.assertValueEqual(log.all(StrikeOrderEvent.Rejected.class).size(), 1, "outer vetoed");
            StrikeOrderEvent.Accepted a = log.only(StrikeOrderEvent.Accepted.class);
            h.assertValueEqual(a.getActorName(), inner, "nested order accepted");
        });
    }

    /**
     * A fixed-launcher shot admitted and then failing at release (launch sector walled in): Request and Accepted (paid per
     * release), no Released, one Ended(FAILED) unreleased and refunded; the munition stays in the magazine.
     */
    @GameTest(template = "runway", timeoutTicks = 20, batch = "order_launcher", skyAccess = true)
    public static void launcherShotFailingAfterAdmissionEndsItsRound(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        BlockPos pad = new BlockPos(16, 4, 8);
        h.setBlock(pad, ModBlocks.FIXED_LAUNCHER.get());
        FixedLauncherBlockEntity be = h.getBlockEntity(pad);
        BlockPos abs = h.absolutePos(pad);
        be.setOwner(null);
        be.setYaw(0);
        List<BlockPos> walls = new ArrayList<>();
        for (int dx = -4; dx <= 4; dx++) {
            for (int dz = -4; dz <= 4; dz++) {
                if (Math.max(Math.abs(dx), Math.abs(dz)) != 4) continue;
                for (int y = pad.getY(); y <= 50; y++) walls.add(new BlockPos(pad.getX() + dx, y, pad.getZ() + dz));
            }
        }
        walls.forEach(p -> h.setBlock(p, Blocks.STONE));
        StrikeGameTests.afterTest(h, () -> {
            walls.forEach(p -> h.setBlock(p, Blocks.AIR));
            level.removeBlockEntity(abs);
            level.setBlock(abs, Blocks.AIR.defaultBlockState(), 3);
        });
        h.assertTrue(be.load(new ItemStack(ModItems.SHAHED.get(), 2)).isEmpty(), "ammunition not stored");
        Vec3 far = Vec3.atBottomCenterOf(abs).add(0, -1, 1000);
        be.setMission(new Mission(WeaponType.DRONE, 1, 0, new Target.Point(far), far, Waypoints.NONE));
        EventLog log = EventLog.open(h, e -> e instanceof StrikeOrderEvent o ? abs.equals(o.getLauncherPos()) : e instanceof StrikeRoundEvent);

        Component problem = LauncherOrders.fire(level, be);
        Orders.deliver(level);
        h.assertTrue(problem != null, "a walled-in launcher fired");
        StrikeOrderEvent.Request request = log.only(StrikeOrderEvent.Request.class);
        h.assertValueEqual(request.getActor(), StrikeOrderEvent.Actor.LAUNCHER, "actor");
        StrikeOrderEvent.Accepted accepted = log.only(StrikeOrderEvent.Accepted.class);
        h.assertValueEqual(accepted.getPayment(), StrikeOrderEvent.Payment.PER_RELEASE, "payment");
        h.assertValueEqual(accepted.getRounds(), 1, "rounds");
        UUID round = RoundRef.of(accepted.getOrderId(), 0, null).round();
        List<StrikeRoundEvent.Ended> ended = log.all(StrikeRoundEvent.Ended.class).stream().filter(e -> e.getRoundId().equals(round)).toList();
        h.assertValueEqual(ended.size(), 1, "the failed round ends once");
        StrikeRoundEvent.Ended e = ended.getFirst();
        h.assertValueEqual(e.getOutcome(), StrikeRoundEvent.Outcome.FAILED, "outcome");
        h.assertTrue(!e.isReleased() && e.isRefunded(), "a failed release is unreleased and refunded");
        h.assertTrue(log.all(StrikeRoundEvent.Released.class).stream().noneMatch(r -> r.getRoundId().equals(round)), "the failed round was released");
        h.assertValueEqual(be.stockCount(), 2, "the failed release kept the magazine's munition");
        h.succeed();
    }

    /**
     * A Shahed from the console: Released, then one Ended(IMPACT) with the same round id, though it entered the world from
     * off-world flight and was parked off-world once more in flight (a copy loaded from its save): the old entity's removal
     * ends nothing.
     */
    @GameTest(template = "range", timeoutTicks = 2400, batch = "order_projectile", skyAccess = true)
    public static void roundIdSurvivesOffWorldFlightAndEndsOnce(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        String who = "order_projectile";
        UUID[] order = {null};
        EventLog log = EventLog.open(h, e -> e instanceof StrikeOrderEvent o ? who.equals(o.getActorName())
                : e instanceof StrikeRoundEvent r && order[0] != null && order[0].equals(r.getOrderId()));
        listen(h, EventPriority.LOWEST, StrikeOrderEvent.Accepted.class, a -> {
            if (who.equals(a.getActorName())) order[0] = a.getOrderId();
        });
        StrikeGameTests.afterTest(h, () -> VirtualFlights.get(level).clear(level, p -> p.round() != null && p.round().order().equals(order[0])));
        h.assertTrue(dispatch(h, who), "order not accepted");
        Orders.deliver(level);
        StrikeRoundEvent.Released released = log.only(StrikeRoundEvent.Released.class);
        UUID round = released.getRoundId();
        h.assertValueEqual(round, RoundRef.of(order[0], 0, null).round(), "round id");
        boolean[] parked = {false};
        h.onEachTick(() -> {
            if (parked[0]) return;
            StrikeProjectile p = carrying(level, round);
            if (p == null || p.isVirtual() || p.flightPhase().onLauncher()) return;
            StrikeProjectile copy = VirtualFlights.park(level, p);
            h.assertTrue(copy != null && copy.round() != null && copy.round().round().equals(round), "the off-world copy lost the round");
            h.assertTrue(p.round() == null, "the parked entity kept the round");
            h.assertValueEqual(log.all(StrikeRoundEvent.Ended.class).size(), 0, "parking ended the round");
            parked[0] = true;
        });
        h.succeedWhen(() -> {
            h.assertTrue(parked[0], "the Shahed never flew in the world");
            StrikeRoundEvent.Ended e = log.only(StrikeRoundEvent.Ended.class);
            h.assertValueEqual(e.getRoundId(), round, "ended round id");
            h.assertValueEqual(e.getOutcome(), StrikeRoundEvent.Outcome.IMPACT, "outcome");
            h.assertTrue(e.isReleased() && e.getChargeKg() > 0 && e.getPosition() != null, "impact details");
        });
    }

    /**
     * A B-2 from the console: the round passes from the bomber to its bomb at release (the departing B-2 carries none) and
     * ends once, as the bomb's impact, under the round id Released gave.
     */
    @GameTest(template = "range", timeoutTicks = 3000, batch = "order_bomber", skyAccess = true)
    public static void bomberHandsItsRoundToTheBomb(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        String who = "order_bomber";
        UUID[] order = {null};
        EventLog log = EventLog.open(h, e -> e instanceof StrikeOrderEvent o ? who.equals(o.getActorName())
                : e instanceof StrikeRoundEvent r && order[0] != null && order[0].equals(r.getOrderId()));
        listen(h, EventPriority.LOWEST, StrikeOrderEvent.Accepted.class, a -> {
            if (who.equals(a.getActorName())) order[0] = a.getOrderId();
        });
        StrikeGameTests.afterTest(h, () -> VirtualFlights.get(level).clear(level, p -> p instanceof ua.zentix.incoming.entity.BomberEntity
                || p.round() != null && p.round().order().equals(order[0])));
        h.assertTrue(ServerActions.dispatch(level, who, 0, WeaponType.BUNKER, 1, 0, aim(h), Loadout.Nuke.DEFAULT, Waypoints.NONE, null),
                "order not accepted");
        Orders.deliver(level);
        UUID round = log.only(StrikeRoundEvent.Released.class).getRoundId();
        boolean[] handed = {false};
        h.onEachTick(() -> {
            if (handed[0]) return;
            StrikeProjectile p = carrying(level, round);
            if (p instanceof ua.zentix.incoming.entity.BunkerBusterEntity) handed[0] = true;
        });
        h.succeedWhen(() -> {
            h.assertTrue(handed[0], "the bomb never carried the round");
            StrikeRoundEvent.Ended e = log.only(StrikeRoundEvent.Ended.class);
            h.assertValueEqual(e.getRoundId(), round, "ended round id");
            h.assertValueEqual(e.getOutcome(), StrikeRoundEvent.Outcome.IMPACT, "outcome");
            h.assertTrue(StrikeWorld.projectiles(level).stream().noneMatch(p -> p.round() != null && p.round().round().equals(round)),
                    "something still carries the ended round");
        });
    }

    /**
     * A tagged ICBM (operator command with a shooter: visible boost): Released at launch with the tag; the scheduled strike
     * carries the round through the boost, after burnout and through a save and load of the strike (a restart); at the
     * burst, NuclearDetonationEvent with the order, round and tag, then one Ended(IMPACT) of the yield.
     */
    @GameTest(template = "runway", timeoutTicks = 1400, batch = "order_icbm", skyAccess = true)
    public static void taggedIcbmKeepsItsRoundToTheBurst(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        int flight = IncomingConfig.SERVER.nukeFlightTime.get();
        boolean enabled = IncomingConfig.SERVER.nukeEnabled.get();
        StrikeGameTests.afterTest(h, () -> {
            NuclearStrikes.clear(level);
            VirtualFlights.get(level).clear(level, p -> p instanceof IcbmEntity);
            level.getEntities(net.minecraft.world.level.entity.EntityTypeTest.forClass(IcbmEntity.class), e -> true).forEach(Entity::discard);
            IncomingConfig.SERVER.nukeFlightTime.set(flight);
            IncomingConfig.SERVER.nukeEnabled.set(enabled);
        });
        IncomingConfig.SERVER.nukeEnabled.set(true);
        IncomingConfig.SERVER.nukeFlightTime.set(300);
        MunitionGameTests.Listener op = new MunitionGameTests.Listener(level, "order_icbm", 2);
        op.moveTo(Vec3.atBottomCenterOf(h.absolutePos(new BlockPos(16, 4, 60))), 0, 0);
        EventLog log = EventLog.open(h, e -> e instanceof StrikeOrderEvent o ? op.getUUID().equals(o.getActorId())
                : e instanceof StrikeRoundEvent r ? "wave3".equals(r.getTag()) : e instanceof NuclearDetonationEvent d && "wave3".equals(d.getTag()));
        Vec3 point = Vec3.atCenterOf(h.absolutePos(new BlockPos(16, 3, 120)));
        ServerActions.Aim aim = new ServerActions.Aim(new Target.Point(point), point, null);
        h.assertTrue(ServerActions.strike(op, WeaponType.NUKE, 1, 0, aim, new Loadout.Nuke(1, true), Waypoints.NONE, null, false, "wave3"), "ICBM order");
        Orders.deliver(level);
        StrikeOrderEvent.Accepted accepted = log.only(StrikeOrderEvent.Accepted.class);
        h.assertValueEqual(accepted.getPayment(), StrikeOrderEvent.Payment.WAIVED, "operator command payment");
        h.assertValueEqual(accepted.getTag(), "wave3", "order tag");
        StrikeRoundEvent.Released released = log.only(StrikeRoundEvent.Released.class);
        UUID round = released.getRoundId();
        h.assertValueEqual(round, RoundRef.of(accepted.getOrderId(), 0, "wave3").round(), "round id");
        h.assertValueEqual(released.getWeapon(), "nuke", "weapon");
        NuclearEvents.ScheduledStrike strike = strike(level, round);
        h.assertTrue(strike != null, "no scheduled strike carries the round");
        long launched = strike.launchTime();
        int boost = ua.zentix.incoming.nuclear.IcbmArrival.boost(300);
        boolean[] boosted = {false}, burnedOut = {false}, reloaded = {false};
        h.onEachTick(() -> {
            long age = level.getGameTime() - launched;
            NuclearEvents.ScheduledStrike s = strike(level, round);
            if (!boosted[0] && age == boost / 2) {
                h.assertTrue(s != null, "the round left the strike during boost");
                h.assertTrue(icbmFlying(level), "no ICBM in its boost");
                h.assertValueEqual(log.all(StrikeRoundEvent.Ended.class).size(), 0, "ended during boost");
                boosted[0] = true;
            }
            if (!burnedOut[0] && age == boost + 20) {
                h.assertTrue(s != null, "the round left the strike after burnout");
                h.assertValueEqual(log.all(StrikeRoundEvent.Ended.class).size(), 0, "ended after burnout");
                burnedOut[0] = true;
                // restart: the strike as saved and read back replaces the live one
                DataResult<Tag> saved = NuclearEvents.ScheduledStrike.CODEC.encodeStart(NbtOps.INSTANCE, s);
                NuclearEvents.ScheduledStrike read = NuclearEvents.ScheduledStrike.CODEC.parse(NbtOps.INSTANCE, saved.getOrThrow()).getOrThrow();
                h.assertValueEqual(read.round().map(RoundRef::round).orElse(null), round, "round after save and load");
                h.assertValueEqual(read.round().map(RoundRef::tag).orElse(null), "wave3", "tag after save and load");
                NuclearEvents events = NuclearEvents.get(level);
                events.unschedule(s);
                events.schedule(read);
                reloaded[0] = true;
            }
        });
        h.succeedWhen(() -> {
            h.assertTrue(boosted[0] && burnedOut[0] && reloaded[0], "probes not reached");
            NuclearDetonationEvent burst = log.only(NuclearDetonationEvent.class);
            StrikeRoundEvent.Ended e = log.only(StrikeRoundEvent.Ended.class);
            h.assertValueEqual(burst.getRoundId(), round, "detonation round");
            h.assertValueEqual(burst.getOrderId(), accepted.getOrderId(), "detonation order");
            h.assertTrue(log.index(burst) < log.index(e), "Ended before the detonation event");
            h.assertValueEqual(e.getRoundId(), round, "ended round");
            h.assertValueEqual(e.getOutcome(), StrikeRoundEvent.Outcome.IMPACT, "outcome");
            h.assertTrue(Math.abs(e.getChargeKg() - burst.getYieldKt() * 1e6) < 1, "charge " + e.getChargeKg() + " for " + burst.getYieldKt() + " kt");
        });
    }

    /**
     * {@code nuke now} at a spot whose chunk is not loaded: the order is Accepted and its round Released at once, but ends
     * only at the burst, once the ground is ready; the detonation event comes first.
     */
    @GameTest(template = "range", timeoutTicks = 1000, batch = "order_nuke_now", skyAccess = true)
    public static void nukeNowWaitingForItsChunkEndsAtTheBurst(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        boolean enabled = IncomingConfig.SERVER.nukeEnabled.get();
        StrikeGameTests.afterTest(h, () -> {
            NuclearStrikes.clear(level);
            IncomingConfig.SERVER.nukeEnabled.set(enabled);
        });
        IncomingConfig.SERVER.nukeEnabled.set(true);
        StrikeGameTests.gameSpeed(h);
        BlockPos far = h.absolutePos(RANGE_CENTER).offset(40_000, 0, 40_000);
        h.assertFalse(level.hasChunk(far.getX() >> 4, far.getZ() >> 4), "the far spot is already loaded");
        String who = "order_nuke_now";
        UUID[] order = {null};
        EventLog log = EventLog.open(h, e -> e instanceof StrikeOrderEvent o ? who.equals(o.getActorName())
                : e instanceof StrikeRoundEvent r ? order[0] != null && order[0].equals(r.getOrderId())
                : e instanceof NuclearDetonationEvent d && order[0] != null && order[0].equals(d.getOrderId()));
        listen(h, EventPriority.LOWEST, StrikeOrderEvent.Request.class, r -> {
            if (who.equals(r.getActorName())) order[0] = r.getOrderId();
        });
        CommandSourceStack source = new CommandSourceStack(net.minecraft.commands.CommandSource.NULL, Vec3.atCenterOf(far), Vec2.ZERO, level, 4, who,
                Component.literal(who), level.getServer(), null).withSuppressedOutput();
        level.getServer().getCommands().performPrefixedCommand(source, "incoming nuke now at " + far.getX() + " " + far.getY() + " " + far.getZ() + " 1 air");
        Orders.deliver(level);
        StrikeOrderEvent.Accepted accepted = log.only(StrikeOrderEvent.Accepted.class);
        h.assertValueEqual(accepted.getActor(), StrikeOrderEvent.Actor.SERVER, "actor");
        StrikeRoundEvent.Released released = log.only(StrikeRoundEvent.Released.class);
        h.assertValueEqual(log.all(StrikeRoundEvent.Ended.class).size(), 0, "the round ended before its chunk was ready");
        h.assertValueEqual(log.all(NuclearDetonationEvent.class).size(), 0, "detonated before its chunk was ready");
        h.succeedWhen(() -> {
            NuclearDetonationEvent burst = log.only(NuclearDetonationEvent.class);
            StrikeRoundEvent.Ended e = log.only(StrikeRoundEvent.Ended.class);
            h.assertValueEqual(e.getRoundId(), released.getRoundId(), "ended round");
            h.assertValueEqual(burst.getRoundId(), released.getRoundId(), "detonation round");
            h.assertTrue(log.index(burst) < log.index(e), "Ended before the detonation event");
            h.assertValueEqual(e.getOutcome(), StrikeRoundEvent.Outcome.IMPACT, "outcome");
        });
    }

    /**
     * A struck substation: GridOutageEvent with its block as the node; power restored by command: GridRestoreEvent with the
     * same outage id once every district is lit and the outage is forgotten.
     */
    @GameTest(template = "range", timeoutTicks = 1200, batch = "order_grid", skyAccess = true)
    public static void outageAndRestoreShareOneId(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        Blackouts.restore(level, null, 0, 0);
        PowerGrid grid = PowerGrid.get(level);
        List<Integer> ids = new ArrayList<>();
        for (Node n : grid.nodes()) ids.add(n.id());
        ids.forEach(grid::removeNode);
        h.setBlock(RANGE_CENTER, ModBlocks.SUBSTATION.get());
        BlockPos node = h.absolutePos(RANGE_CENTER);
        EventLog log = EventLog.open(h, e -> e instanceof GridOutageEvent o ? node.equals(o.getNodePos()) : e instanceof GridRestoreEvent);
        Vec3 c = Vec3.atCenterOf(node.east(3));
        level.explode(null, c.x, c.y, c.z, 2f, Level.ExplosionInteraction.NONE);
        Orders.deliver(level);
        GridOutageEvent outage = log.only(GridOutageEvent.class);
        h.assertFalse(outage.isNuclear(), "a substation outage is not nuclear");
        h.assertValueEqual(outage.getDimension(), level.dimension(), "dimension");
        h.assertTrue(Blackouts.restore(level, Vec3.atCenterOf(node), 8, 0) >= 1, "nothing to restore");
        h.succeedWhen(() -> {
            GridRestoreEvent restored = log.all(GridRestoreEvent.class).stream().filter(r -> r.getOutageId().equals(outage.getOutageId())).findFirst()
                    .orElseThrow(() -> new GameTestAssertException("no restore event for outage " + outage.getOutageId()));
            h.assertValueEqual(restored.getNodePos(), node, "node");
        });
    }

    // ---------------------------------------------------------------- helpers

    private static ServerActions.Aim aim(GameTestHelper h) {
        Vec3 point = Vec3.atCenterOf(h.absolutePos(RANGE_CENTER)).add(0, 0.5, 0);
        return new ServerActions.Aim(new Target.Point(point), point, null);
    }

    /** One Shahed at the range centre from the console named {@code who}. */
    private static boolean dispatch(GameTestHelper h, String who) {
        return ServerActions.dispatch(h.getLevel(), who, 0, WeaponType.DRONE, 1, 0, aim(h), Loadout.Nuke.DEFAULT, Waypoints.NONE, null);
    }

    @Nullable
    private static StrikeProjectile carrying(ServerLevel level, UUID round) {
        for (StrikeProjectile p : StrikeWorld.projectiles(level)) {
            if (p.round() != null && p.round().round().equals(round)) return p;
        }
        return null;
    }

    @Nullable
    private static NuclearEvents.ScheduledStrike strike(ServerLevel level, UUID round) {
        for (NuclearEvents.ScheduledStrike s : NuclearEvents.get(level).scheduled()) {
            if (s.round().map(r -> r.round().equals(round)).orElse(false)) return s;
        }
        return null;
    }

    private static boolean icbmFlying(ServerLevel level) {
        if (!level.getEntities(net.minecraft.world.level.entity.EntityTypeTest.forClass(IcbmEntity.class), e -> true).isEmpty()) return true;
        return VirtualFlights.get(level).flights().stream().anyMatch(p -> p instanceof IcbmEntity);
    }

    /** {@code listener} on the game bus for this test only. */
    private static <T extends Event> void listen(GameTestHelper h, EventPriority priority, Class<T> type, Consumer<T> listener) {
        listen(h, priority, false, type, listener);
    }

    private static <T extends Event> void listen(GameTestHelper h, EventPriority priority, boolean cancelled, Class<T> type, Consumer<T> listener) {
        NeoForge.EVENT_BUS.addListener(priority, cancelled, type, listener);
        StrikeGameTests.afterTest(h, () -> NeoForge.EVENT_BUS.unregister(listener));
    }

    /** Every Incoming event passing {@code mine}, in posting order. */
    private static final class EventLog {
        private final List<Event> events = new ArrayList<>();
        private final Predicate<Event> mine;

        private EventLog(Predicate<Event> mine) {
            this.mine = mine;
        }

        static EventLog open(GameTestHelper h, Predicate<Event> mine) {
            EventLog log = new EventLog(mine);
            log.record(h, StrikeOrderEvent.Request.class);
            log.record(h, StrikeOrderEvent.Rejected.class);
            log.record(h, StrikeOrderEvent.Accepted.class);
            log.record(h, StrikeRoundEvent.Released.class);
            log.record(h, StrikeRoundEvent.Ended.class);
            log.record(h, NuclearDetonationEvent.class);
            log.record(h, GridOutageEvent.class);
            log.record(h, GridRestoreEvent.class);
            return log;
        }

        /** Last on the bus, cancelled requests included (a veto is part of the record). */
        private <T extends Event> void record(GameTestHelper h, Class<T> type) {
            listen(h, EventPriority.LOWEST, true, type, (T e) -> {
                if (mine.test(e)) events.add(e);
            });
        }

        <T extends Event> List<T> all(Class<T> type) {
            return events.stream().filter(type::isInstance).map(type::cast).toList();
        }

        /** The one event of {@code type}; fails on none or several. */
        <T extends Event> T only(Class<T> type) {
            List<T> all = all(type);
            if (all.size() != 1) throw new GameTestAssertException("expected one " + type.getSimpleName() + ", got " + all.size());
            return all.getFirst();
        }

        int index(Event e) {
            return events.indexOf(e);
        }

        void clear() {
            events.clear();
        }
    }
}
