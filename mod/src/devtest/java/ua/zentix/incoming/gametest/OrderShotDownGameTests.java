package ua.zentix.incoming.gametest;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.EntityLeaveLevelEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import ua.zentix.incoming.Incoming;
import ua.zentix.incoming.api.StrikeRoundEvent;
import ua.zentix.incoming.nuclear.NuclearStrikes;
import ua.zentix.incoming.registry.ModEntities;
import ua.zentix.incoming.strike.Loadout;
import ua.zentix.incoming.strike.Orders;
import ua.zentix.incoming.strike.RoundRef;
import ua.zentix.incoming.target.Target;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

/** Order events, independent review probes: A failing removal listener cannot cost an armed carrier shot down by damage its burst or its round's end. */
@GameTestHolder(Incoming.MOD_ID)
@PrefixGameTestTemplate(false)
public final class OrderShotDownGameTests {
    private OrderShotDownGameTests() {}

    @GameTest(template = "range", batch = "order_probe_shot_down_leave", skyAccess = true)
    public static void armedCarrierStillEndsWhenEntityLeaveListenerThrows(GameTestHelper h) {
        var level = h.getLevel();
        var missile = ModEntities.CRUISE_MISSILE.get().create(level);
        h.assertTrue(missile != null, "Carrier fixture could not be created");
        Vec3 at = Vec3.atCenterOf(h.absolutePos(new BlockPos(32, 32, 32)));
        Vec3 target = at.add(0, 0, 2000);
        missile.launch(at, new Target.Point(target), target, null);
        missile.setPos(at);
        missile.setNuclear(new Loadout.Nuke(1, true, true));
        RoundRef round = RoundRef.of(UUID.randomUUID(), 0, "probe_shot_down");
        missile.attachRound(round);
        StrikeGameTests.afterTest(h, () -> NuclearStrikes.clear(level));
        h.assertTrue(level.addFreshEntity(missile) && level.getEntity(missile.getUUID()) == missile, "Carrier fixture was not tracked");
        var listenerFailure = new IllegalStateException("fixture entity leave failure");
        List<StrikeRoundEvent.Outcome> seen = new ArrayList<>();
        Consumer<EntityLeaveLevelEvent> leave = e -> {
            if (e.getEntity() == missile) throw listenerFailure;
        };
        Consumer<StrikeRoundEvent.Ended> ended = e -> {
            if (round.round().equals(e.getRoundId())) seen.add(e.getOutcome());
        };
        NeoForge.EVENT_BUS.addListener(leave);
        NeoForge.EVENT_BUS.addListener(ended);
        try {
            missile.hurt(level.damageSources().generic(), Float.MAX_VALUE);
        } catch (IllegalStateException e) {
            if (e != listenerFailure) throw e;
        } finally {
            NeoForge.EVENT_BUS.unregister(leave);
            try {
                Orders.deliver(level);
            } finally {
                NeoForge.EVENT_BUS.unregister(ended);
            }
        }
        h.assertTrue(missile.isRemoved(), "Carrier survived lethal damage");
        h.assertValueEqual(seen, List.of(StrikeRoundEvent.Outcome.INTERCEPTED), "terminal event after a failed removal callback");
        h.succeed();
    }
}
