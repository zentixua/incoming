package ua.zentix.incoming.gametest;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.Event;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import ua.zentix.incoming.Incoming;
import ua.zentix.incoming.IncomingConfig;
import ua.zentix.incoming.api.StrikeOrderEvent;
import ua.zentix.incoming.api.StrikeRoundEvent;
import ua.zentix.incoming.defense.SamBlockEntity;
import ua.zentix.incoming.entity.DroneEntity;
import ua.zentix.incoming.info.Automation;
import ua.zentix.incoming.launcher.FixedLauncherBlockEntity;
import ua.zentix.incoming.launcher.LauncherOrders;
import ua.zentix.incoming.launcher.Mission;
import ua.zentix.incoming.registry.ModItems;
import ua.zentix.incoming.strike.WeaponType;
import ua.zentix.incoming.strike.Waypoints;
import ua.zentix.incoming.target.Target;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * The computer side of the launcher and the SAM ({@link Automation}, what the CC:Tweaked peripherals call; CC:Tweaked is
 * not in the GameTest runtime, the adapters are checked by {@code compat.computercraft.PeripheralsTest}): {@code fire} is
 * refused exactly where a redstone signal is and accepted with the same order and round events; no call changes the
 * mission; a SAM's watchers hear each sweep while it tracks a hostile target, nothing otherwise.
 */
@GameTestHolder(Incoming.MOD_ID)
@PrefixGameTestTemplate(false)
public final class AutomationGameTests {
    private static final BlockPos PAD = new BlockPos(16, 4, 8);
    /** Ticks from the firing to the event snapshot: the round's release from the rail falls inside it. */
    private static final int WINDOW = 60;

    private AutomationGameTests() {}

    /**
     * One launcher, each state fired first by a redstone block, then by the computer entry. Short of stock (mission 3,
     * stock 2) and with the owner refused the designator: the same refusal, no order event, stock kept. Ready: both give
     * the order with the same order and round events, one Shahed each from the stock, the status line "order given". The
     * status and stock reads and every call leave the mission as it was.
     */
    @GameTest(template = "runway", timeoutTicks = 900, batch = "automation_fire", skyAccess = true)
    public static void computerFiresExactlyLikeRedstone(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        UUID owner = UUID.randomUUID();
        FixedLauncherBlockEntity be = FixedLauncherGameTests.launcher(h, PAD, owner);
        BlockPos abs = h.absolutePos(PAD);
        Vec3 far = Vec3.atBottomCenterOf(abs).add(0, -1, 1000);
        Mission three = new Mission(WeaponType.DRONE, 3, 0, new Target.Point(far), far, Waypoints.NONE);
        Mission one = new Mission(WeaponType.DRONE, 1, 0, new Target.Point(far), far, Waypoints.NONE);
        FixedLauncherGameTests.load(h, be, new ItemStack(ModItems.SHAHED.get(), 2));
        boolean everyone = IncomingConfig.SERVER.designatorForEveryone.get();
        StrikeGameTests.afterTest(h, () -> IncomingConfig.SERVER.designatorForEveryone.set(everyone));
        List<String> events = record(h, abs);
        Outcome[] redstone = new Outcome[3], computer = new Outcome[3];
        int[] mark = {0};

        h.startSequence()
                // 1: stock short
                .thenExecute(() -> be.setMission(three))
                .thenExecute(() -> {
                    mark[0] = events.size();
                    h.setBlock(PAD.east(), Blocks.REDSTONE_BLOCK);
                })
                .thenIdle(6)
                .thenExecute(() -> {
                    redstone[0] = Outcome.of(be, events, mark[0], null);
                    h.setBlock(PAD.east(), Blocks.AIR);
                    mark[0] = events.size();
                    computer[0] = Outcome.of(be, events, mark[0], Automation.fire(level, be));
                })
                // 2: the owner may not use the designator
                .thenExecute(() -> {
                    be.setMission(one);
                    IncomingConfig.SERVER.designatorForEveryone.set(false);
                    mark[0] = events.size();
                    h.setBlock(PAD.east(), Blocks.REDSTONE_BLOCK);
                })
                .thenIdle(6)
                .thenExecute(() -> {
                    redstone[1] = Outcome.of(be, events, mark[0], null);
                    h.setBlock(PAD.east(), Blocks.AIR);
                    mark[0] = events.size();
                    computer[1] = Outcome.of(be, events, mark[0], Automation.fire(level, be));
                    IncomingConfig.SERVER.designatorForEveryone.set(everyone);
                })
                // 3: ready — redstone first
                .thenExecute(() -> {
                    Map<String, Object> status = Automation.status(level, be);
                    h.assertValueEqual(status.get("ready"), true, "status before the shot");
                    mark[0] = events.size();
                    h.setBlock(PAD.east(), Blocks.REDSTONE_BLOCK);
                })
                .thenIdle(4 + WINDOW)
                .thenExecute(() -> {
                    redstone[2] = Outcome.of(be, events, mark[0], null);
                    h.setBlock(PAD.east(), Blocks.AIR);
                })
                .thenWaitUntil(() -> h.assertFalse(LauncherOrders.busy(level, be), "the launcher is still busy with the redstone shot"))
                // then the computer, on the same launcher
                .thenExecute(() -> {
                    mark[0] = events.size();
                    Object[] r = Automation.fire(level, be);
                    h.assertValueEqual(List.of(r), List.of(true), "the computer's ready shot");
                })
                .thenIdle(WINDOW)
                .thenExecute(() -> {
                    computer[2] = Outcome.of(be, events, mark[0], new Object[]{true});
                    for (int i = 0; i < 3; i++) {
                        h.assertValueEqual(computer[i].same(), redstone[i].same(), "state " + (i + 1) + ": computer and redstone differ");
                    }
                    h.assertValueEqual(redstone[0].reason, "incoming.munitions.short", "state 1 reason");
                    h.assertValueEqual(redstone[1].reason, "incoming.fixed_launcher.no_rights", "state 2 reason");
                    h.assertValueEqual(redstone[2].reason, "incoming.fixed_launcher.fired", "state 3 status line");
                    for (int i = 0; i < 2; i++) {
                        h.assertTrue(redstone[i].events.isEmpty() && redstone[i].stock == 2, "a redstone refusal ordered or spent: " + redstone[i]);
                        h.assertTrue(computer[i].stock == 2, "a computer refusal spent: " + computer[i]);
                    }
                    h.assertTrue(redstone[2].events.containsAll(List.of("Request", "Accepted", "Released")), "the shot's events: " + redstone[2].events);
                    h.assertValueEqual(redstone[2].stock, 1, "stock after the redstone shot");
                    h.assertValueEqual(computer[2].stock, 0, "stock after the computer shot");
                    Automation.status(level, be);
                    Automation.stock(be);
                    h.assertValueEqual(be.mission(), one, "the mission after the computer's calls");
                })
                .thenSucceed();
    }

    /**
     * Two SAMs without missiles beside a Shahed's path: one owned by a stranger (the Shahed is hostile to it), one by the
     * Shahed's owner. A watcher on the first hears every sweep (10 ticks apart) while its radar tracks the Shahed; the
     * watcher on the second, which does not track it, hears nothing. Once the Shahed is gone, the first hears nothing more
     * and its alarm is off.
     */
    @GameTest(template = "runway", timeoutTicks = 600, batch = "automation_alert", skyAccess = true)
    public static void alertOnlyWhileItsSamTracks(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        SamGameTests.air(h, 1, 1);
        UUID shooter = UUID.randomUUID();
        SamBlockEntity hostile = SamGameTests.sam(h, SamGameTests.SAM, UUID.randomUUID(), 0);
        SamBlockEntity friendly = SamGameTests.sam(h, SamGameTests.SAM.east(6), shooter, 0);
        List<Long> heard = new ArrayList<>(), heardFriendly = new ArrayList<>();
        List<Integer> counts = new ArrayList<>();
        SamBlockEntity.Watcher w = (n, nearest) -> {
            heard.add(level.getGameTime());
            counts.add(n);
        };
        SamBlockEntity.Watcher wf = (n, nearest) -> heardFriendly.add(level.getGameTime());
        hostile.watch(w);
        friendly.watch(wf);
        DroneEntity drone = SamGameTests.drone(h, 16, shooter);
        int[] at = {0};
        h.startSequence()
                .thenWaitUntil(() -> h.assertTrue(heard.size() >= 3, "the tracking SAM's watcher heard " + heard.size() + " sweeps"))
                .thenExecute(() -> {
                    for (int i = 1; i < heard.size(); i++) h.assertValueEqual(heard.get(i) - heard.get(i - 1), (long) SamBlockEntity.SWEEP, "sweep spacing");
                    h.assertTrue(counts.stream().allMatch(c -> c == 1), "hostile counts " + counts);
                    h.assertTrue(heardFriendly.isEmpty(), "the SAM that does not track was heard " + heardFriendly.size() + " times");
                    h.assertTrue(hostile.alarm() && Automation.tracking(hostile).get("tracking") == Boolean.TRUE, "the tracking SAM is not tracking");
                    h.assertTrue(Automation.tracking(friendly).get("tracking") == Boolean.FALSE, "the friendly SAM tracks its own Shahed");
                    drone.discard();
                })
                .thenIdle(1)
                .thenExecute(() -> at[0] = heard.size())
                .thenIdle(3 * SamBlockEntity.SWEEP)
                .thenExecute(() -> {
                    h.assertValueEqual(heard.size(), at[0], "sweeps heard after the target was gone");
                    h.assertTrue(!hostile.alarm() && hostile.hostile() == 0, "the SAM still tracks a removed Shahed");
                    h.assertTrue(heardFriendly.isEmpty(), "the SAM that never tracked was heard");
                })
                .thenSucceed();
    }

    /** Order events of the launcher at {@code abs} and round events of its orders, by simple class name, in order. */
    private static List<String> record(GameTestHelper h, BlockPos abs) {
        List<String> names = new ArrayList<>();
        Set<UUID> orders = new HashSet<>();
        listen(h, StrikeOrderEvent.Request.class, e -> {
            if (abs.equals(e.getLauncherPos())) names.add("Request");
        });
        listen(h, StrikeOrderEvent.Rejected.class, e -> {
            if (abs.equals(e.getLauncherPos())) names.add("Rejected");
        });
        listen(h, StrikeOrderEvent.Accepted.class, e -> {
            if (!abs.equals(e.getLauncherPos())) return;
            names.add("Accepted");
            orders.add(e.getOrderId());
        });
        listen(h, StrikeRoundEvent.Released.class, e -> {
            if (orders.contains(e.getOrderId())) names.add("Released");
        });
        listen(h, StrikeRoundEvent.Ended.class, e -> {
            if (orders.contains(e.getOrderId())) names.add("Ended:" + e.getOutcome());
        });
        return names;
    }

    private static <T extends Event> void listen(GameTestHelper h, Class<T> type, Consumer<T> listener) {
        NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, true, type, listener);
        StrikeGameTests.afterTest(h, () -> NeoForge.EVENT_BUS.unregister(listener));
    }

    /**
     * What one firing did: the status line's key (a refusal or "order given"), the order and round events since the
     * mark, the stock left; a computer's result must carry the same refusal code.
     */
    private record Outcome(String reason, List<String> events, int stock, String result) {
        static Outcome of(FixedLauncherBlockEntity be, List<String> events, int mark, Object[] computer) {
            String reason = FixedLauncherGameTests.key(be.lastReport());
            String result = computer == null ? null : computer[0] == Boolean.TRUE ? "ordered" : String.valueOf(computer[1]);
            if (computer != null && computer[0] != Boolean.TRUE && !("incoming." + result).equals(reason)) {
                throw new net.minecraft.gametest.framework.GameTestAssertException("computer reason " + result + " vs status line " + reason);
            }
            return new Outcome(reason, List.copyOf(events.subList(mark, events.size())), be.stockCount(), result);
        }

        /** What must match between the two entries (the stock is compared per state by the caller). */
        List<Object> same() {
            return List.of(String.valueOf(reason), events);
        }
    }
}
