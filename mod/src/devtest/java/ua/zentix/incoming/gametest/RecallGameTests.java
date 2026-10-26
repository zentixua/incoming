package ua.zentix.incoming.gametest;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import org.jetbrains.annotations.Nullable;
import ua.zentix.incoming.Incoming;
import ua.zentix.incoming.entity.BomberEntity;
import ua.zentix.incoming.entity.CruiseMissileEntity;
import ua.zentix.incoming.entity.LauncherEntity;
import ua.zentix.incoming.entity.StrikeProjectile;
import ua.zentix.incoming.guidance.Route;
import ua.zentix.incoming.registry.ModEntities;
import ua.zentix.incoming.strike.Loadout;
import ua.zentix.incoming.strike.SalvoData;
import ua.zentix.incoming.strike.ServerActions;
import ua.zentix.incoming.strike.VirtualFlights;
import ua.zentix.incoming.strike.WeaponType;
import ua.zentix.incoming.strike.Waypoints;
import ua.zentix.incoming.target.Target;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

/** Own release ({@link ServerActions#recall}): only own strikes, self-destruction; nuclear and unguided - do not respond. */
@GameTestHolder(Incoming.MOD_ID)
@PrefixGameTestTemplate(false)
public final class RecallGameTests {
    private static final BlockPos CENTER = new BlockPos(32, 11, 32);

    private RecallGameTests() {}

    /**
     * Player A cancels: his rocket self-destructs in flight, B-2 turns away before reset, the rocket on the guide is removed
     *  along with his launcher, the off-world rocket disappears, his salvo is cancelled. A missile with a nuclear warhead from the same player, strikes by
     *  of player B and his launcher - as they were; the MLRS missile and the dropped bomb have nothing to respond to.
     */
    @GameTest(template = "range", timeoutTicks = 20, batch = "recall", skyAccess = true)
    public static void recallStopsOnlyOwnStrikes(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        UUID a = UUID.randomUUID(), b = UUID.randomUUID();
        Vec3 c = Vec3.atCenterOf(h.absolutePos(CENTER));
        Vec3 far = c.add(0, 0, 3000);
        CruiseMissileEntity mine = flying(level, c.add(0, 40, 0), far, a, null);
        CruiseMissileEntity theirs = flying(level, c.add(12, 40, 0), far, b, null);
        CruiseMissileEntity nuclear = flying(level, c.add(-12, 40, 0), far, a, new Loadout.Nuke(15, true, true));
        Vec3 rail = c.add(0, 1, 0);
        CruiseMissileEntity onRail = ModEntities.CRUISE_MISSILE.get().create(level);
        onRail.placeOnLauncher(rail, 0, 40, 1000, 0, new Target.Point(far), far, a);
        onRail.setRoute(Route.direct());
        level.addFreshEntity(onRail);
        LauncherEntity launcher = LauncherEntity.create(level, rail.add(6, -1, 0), 0, WeaponType.MISSILE, a, false);
        LauncherEntity otherLauncher = LauncherEntity.create(level, rail.add(-6, -1, 0), 0, WeaponType.MISSILE, b, false);
        level.addFreshEntity(launcher);
        level.addFreshEntity(otherLauncher);
        CruiseMissileEntity outside = ModEntities.CRUISE_MISSILE.get().create(level);
        outside.launch(far.add(0, 60, 2000), new Target.Point(far), far, a);
        outside.setRoute(Route.direct());
        VirtualFlights.launch(level, outside);
        BomberEntity bomber = ModEntities.BOMBER.get().create(level);
        bomber.launch(c.add(0, 0, -20), far, null, a);
        level.addFreshEntity(bomber);
        SalvoData.start(level, WeaponType.DRONE, 5, 10, new Target.Point(far), far, 0, a, Loadout.Nuke.DEFAULT, Waypoints.NONE, null, false);
        SalvoData.start(level, WeaponType.DRONE, 5, 10, new Target.Point(far), far, 0, b, Loadout.Nuke.DEFAULT, Waypoints.NONE, null, false);
        StrikeGameTests.afterTest(h, () -> {
            SalvoData.get(level).cancel(level, a, List.of());
            SalvoData.get(level).cancel(level, b, List.of());
            VirtualFlights.get(level).clear(level, p -> a.equals(p.ownerId()) || b.equals(p.ownerId()));
            for (var e : List.of(theirs, nuclear, bomber, otherLauncher, launcher, onRail, mine)) e.discard();
        });
        List<String> lines = new CopyOnWriteArrayList<>();
        StrikeGameTests.afterTest(h, NuclearGameTests.watchLog(line -> {
            if (line.startsWith("Stand down")) lines.add(line);
        }));

        ServerActions.Recalled r = ServerActions.recall(level.getServer(), a, "check");
        h.assertTrue(mine.isRemoved(), "own missile did not self-destruct in flight");
        h.assertTrue(!bomber.isRemoved() && bomber.hasReleased(), "B-2 did not turn away before release: removed " + bomber.isRemoved());
        h.assertTrue(onRail.isRemoved() && launcher.isRemoved(), "own missile on the rail or launcher remained");
        h.assertTrue(outside.isRemoved() && !VirtualFlights.get(level).flights().contains(outside), "own missile remained outside the world");
        h.assertTrue(SalvoData.get(level).remaining(a) == 0, "own salvo was not cancelled");
        h.assertFalse(nuclear.isRemoved(), "own Recall cancelled a nuclear strike");
        h.assertTrue(!theirs.isRemoved() && !otherLauncher.isRemoved() && SalvoData.get(level).remaining(b) == 5, "own Recall affected other owners' strikes");
        h.assertTrue(r.projectiles() == 4 && r.salvos() == 1, "result: " + r);
        h.assertValueEqual(lines, List.of("Stand down — check: self-destructed 1, turned away 1, taken off launchers 1 (launchers 1), "
                + "off-world 1, salvos 1"), "log line");

        // uncontrollable ones have nothing to turn to: they fly further
        for (StrikeProjectile p : List.of(ModEntities.ROCKET.get().create(level), ModEntities.BUNKER_BUSTER.get().create(level))) {
            h.assertTrue(!p.recallable() && !p.recall(level) && !p.isRemoved(), "Recall stopped " + p.getType().getDescriptionId());
        }
        h.succeed();
    }

    /** Cruise missile in flight around the world. */
    private static CruiseMissileEntity flying(ServerLevel level, Vec3 at, Vec3 target, UUID owner, @Nullable Loadout.Nuke warhead) {
        CruiseMissileEntity m = ModEntities.CRUISE_MISSILE.get().create(level);
        m.launch(at, new Target.Point(target), target, owner);
        m.setRoute(Route.direct());
        m.setNuclear(warhead);
        level.addFreshEntity(m);
        return m;
    }
}
