package ua.zentix.incoming.gametest;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.portal.DimensionTransition;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.Event;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import ua.zentix.incoming.Incoming;
import ua.zentix.incoming.api.StrikeOrderEvent;
import ua.zentix.incoming.api.StrikeRoundEvent;
import ua.zentix.incoming.entity.StrikeProjectile;
import ua.zentix.incoming.strike.Loadout;
import ua.zentix.incoming.strike.Orders;
import ua.zentix.incoming.strike.ServerActions;
import ua.zentix.incoming.strike.VirtualFlights;
import ua.zentix.incoming.strike.Waypoints;
import ua.zentix.incoming.strike.WeaponType;
import ua.zentix.incoming.target.Target;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/** Order events, independent review probes: One event queue for the server: a round that changes dimension keeps Released before Ended; an order started from a Rejected listener in another dimension is answered at once. */
@GameTestHolder(Incoming.MOD_ID)
@PrefixGameTestTemplate(false)
public final class OrderDimensionGameTests {
    private OrderDimensionGameTests() {}

    @GameTest(template = "range", batch = "order_probe_dimension", skyAccess = true)
    public static void transferredRoundStillEndsAfterItsRelease(GameTestHelper h) {
        ServerLevel earlier = h.getLevel();
        ServerLevel later = earlier.getServer().getLevel(Level.NETHER);
        h.assertTrue(later != null && earlier.dimension().equals(Level.OVERWORLD), "Overworld and Nether required");
        String tag = "probe_dimension_transfer";
        List<String> seen = new ArrayList<>();
        listen(h, StrikeOrderEvent.Accepted.class, e -> {
            if (tag.equals(e.getTag())) seen.add("accepted");
        });
        listen(h, StrikeRoundEvent.Released.class, e -> {
            if (tag.equals(e.getTag())) seen.add("released");
        });
        listen(h, StrikeRoundEvent.Ended.class, e -> {
            if (tag.equals(e.getTag())) seen.add("ended");
        });
        VirtualFlights flights = VirtualFlights.get(later);
        StrikeGameTests.afterTest(h, () -> flights.clear(later, p -> p.round() != null && tag.equals(p.round().tag())));
        h.assertTrue(dispatch(later, tag, new Vec3(0, 75, 0)), "Nether order was refused");
        StrikeProjectile source = flights.flights().stream().filter(p -> p.round() != null && tag.equals(p.round().tag()))
                .findFirst().orElseThrow();
        var round = source.round();
        flights.flights().remove(source);
        StrikeGameTests.afterTest(h, () -> {
            source.detachRound();
            source.discard();
        });
        Vec3 at = point(h);
        var transferred = source.changeDimension(new DimensionTransition(earlier, at, Vec3.ZERO, 0, 0, DimensionTransition.DO_NOTHING));
        h.assertTrue(transferred instanceof StrikeProjectile p && round.equals(p.round()), "Dimension transfer lost the fixture round");
        transferred.discard();

        Orders.onServerTick(new ServerTickEvent.Post(() -> true, earlier.getServer()));
        h.assertValueEqual(seen, List.of("accepted", "released", "ended"), "one round's events after transfer");
        h.succeed();
    }

    @GameTest(template = "range", batch = "order_probe_dimension_answer", skyAccess = true)
    public static void crossDimensionCallbackGetsItsAnswerInTheSameTick(GameTestHelper h) {
        ServerLevel earlier = h.getLevel();
        ServerLevel later = earlier.getServer().getLevel(Level.NETHER);
        h.assertTrue(later != null && earlier.dimension().equals(Level.OVERWORLD), "Overworld and Nether required");
        String outer = "probe_dimension_outer";
        String inner = "probe_dimension_inner";
        Vec3 at = point(h);
        List<String> seen = new ArrayList<>();
        listen(h, StrikeOrderEvent.Request.class, e -> {
            if (inner.equals(e.getTag())) seen.add("request");
            if (inner.equals(e.getTag()) || outer.equals(e.getTag())) e.setCanceled(true);
        });
        listen(h, StrikeOrderEvent.Rejected.class, e -> {
            if (outer.equals(e.getTag())) dispatch(earlier, inner, at);
            if (inner.equals(e.getTag())) seen.add("rejected");
        });
        dispatch(later, outer, new Vec3(0, 75, 0));
        Orders.onServerTick(new ServerTickEvent.Post(() -> true, earlier.getServer()));
        h.assertValueEqual(seen, List.of("request", "rejected"), "Request and answer at this server tick's end");
        h.succeed();
    }

    private static Vec3 point(GameTestHelper h) {
        return Vec3.atCenterOf(h.absolutePos(new BlockPos(32, 12, 32)));
    }

    private static boolean dispatch(ServerLevel level, String tag, Vec3 point) {
        var aim = new ServerActions.Aim(new Target.Point(point), point, null);
        return ServerActions.dispatch(level, tag, 0, WeaponType.DRONE, 1, 0, aim, Loadout.Nuke.DEFAULT, Waypoints.NONE, null, tag, null);
    }

    private static <T extends Event> void listen(GameTestHelper h, Class<T> type, Consumer<T> listener) {
        NeoForge.EVENT_BUS.addListener(type, listener);
        StrikeGameTests.afterTest(h, () -> NeoForge.EVENT_BUS.unregister(listener));
    }
}
