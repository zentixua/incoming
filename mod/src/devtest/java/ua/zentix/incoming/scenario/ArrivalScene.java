package ua.zentix.incoming.scenario;

import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.Vec3;
import ua.zentix.incoming.net.ClientHooks;
import ua.zentix.incoming.net.S2C;
import ua.zentix.incoming.nuclear.Detonation;
import ua.zentix.incoming.nuclear.IcbmArrival;
import ua.zentix.incoming.nuclear.model.Yield;
import ua.zentix.incoming.strike.WeaponSpec;

import java.util.ArrayList;
import java.util.List;

/** Repeatable arrival-only sound stem: no sirens, no boost engine, no world destruction. */
final class ArrivalScene {
    private static final List<IcbmArrival> PATHS = new ArrayList<>();
    private static long start;
    private static int age = -1;
    private static boolean flash;
    private static Vec3 ground;
    private ArrivalScene() {}

    static void start(Vec3 at, int count, boolean burst) {
        reset();
        var mc = Minecraft.getInstance();
        double kt = count == 1 ? 15 : count == 2 ? 100 : 1000;
        long when = mc.level.getGameTime() - 6000;
        var d = new Detonation(9900, at, at.y, kt, true, when, 0, 3, 20000,
                1234, 1, false, .25f, mc.level.getDayTime() - 6000, true);
        ua.zentix.incoming.client.nuclear.ClientNuclear.detonation(new S2C.NukeDetonation(d, 0), false);
    }

    private static Detonation geometry(int id, Vec3 target, long end) {
        return new Detonation(id, target.add(0, Yield.optimalBurstHeight(15), 0), target.y, 15, false, end,
                0, 5, 20_000, 1234, 1, false, 0.25f, Minecraft.getInstance().level.getDayTime(), false);
    }

    static void tick() {
        if (age < 0) return;
        age++;
        if (age < 240) ClientHooks.get().nukeApproach(new S2C.NukeApproach(start + age, List.copyOf(PATHS)));
        else if (age == 240) {
            if (flash) ClientHooks.get().nukeDetonation(new S2C.NukeDetonation(geometry(9000, ground, start + age), 0, 9000));
            else for (IcbmArrival path : PATHS) ua.zentix.incoming.client.nuclear.ClientNuclear.approaches().finish(path.strikeId());
            ClientHooks.get().nukeApproach(new S2C.NukeApproach(start + age, List.of()));
        }
    }

    static void reset() { age = -1; PATHS.clear(); }
}
