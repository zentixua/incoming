package ua.zentix.incoming.gametest;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import ua.zentix.incoming.Incoming;
import ua.zentix.incoming.api.StrikeOrderEvent;
import ua.zentix.incoming.strike.Loadout;
import ua.zentix.incoming.strike.SalvoData;
import ua.zentix.incoming.strike.ServerActions;
import ua.zentix.incoming.strike.Waypoints;
import ua.zentix.incoming.strike.WeaponType;
import ua.zentix.incoming.target.Target;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/** Order events, independent review probes: Accepted is posted during admission: a cooldown a listener starts there refuses the next order in the same tick. */
@GameTestHolder(Incoming.MOD_ID)
@PrefixGameTestTemplate(false)
public final class OrderCooldownGameTests {
    private OrderCooldownGameTests() {}

    @GameTest(template = "range", batch = "order_probe_cooldown", skyAccess = true)
    public static void acceptedCooldownAppliesToTheNextRequestInTheSameTick(GameTestHelper h) {
        var level = h.getLevel();
        var actor = new MunitionGameTests.Listener(level, "probe_cooldown", 2);
        UUID id = actor.getUUID();
        Vec3 point = Vec3.atCenterOf(h.absolutePos(new BlockPos(32, 12, 32)));
        var aim = new ServerActions.Aim(new Target.Point(point), point, null);
        AtomicBoolean coolingDown = new AtomicBoolean();
        Consumer<StrikeOrderEvent.Request> check = e -> {
            if (id.equals(e.getActorId()) && coolingDown.get()) e.setCanceled(true);
        };
        Consumer<StrikeOrderEvent.Accepted> start = e -> {
            if (id.equals(e.getActorId())) coolingDown.set(true);
        };
        NeoForge.EVENT_BUS.addListener(check);
        NeoForge.EVENT_BUS.addListener(start);
        StrikeGameTests.afterTest(h, () -> {
            NeoForge.EVENT_BUS.unregister(check);
            NeoForge.EVENT_BUS.unregister(start);
            SalvoData.get(level).cancel(level, id, List.of());
        });

        h.assertTrue(ServerActions.strike(actor, WeaponType.DRONE, 2, 0, aim, Loadout.Nuke.DEFAULT, Waypoints.NONE, null, false),
                "First fixture order was refused");
        boolean second = ServerActions.strike(actor, WeaponType.DRONE, 2, 0, aim, Loadout.Nuke.DEFAULT, Waypoints.NONE, null, false);
        h.assertFalse(second, "Both orders passed the guide's cooldown in the same tick");
        h.succeed();
    }
}
