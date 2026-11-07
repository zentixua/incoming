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
    /** Terminal window of this scene's paths, ticks (as the server: {@link IcbmArrival#start}). */
    private static int window = 240;
    private ArrivalScene() {}

    static void start(Vec3 at, int count, boolean burst) {
        reset();
        ground = at;
        flash = burst;
        start = Minecraft.getInstance().level.getGameTime();
        age = 0;
        Vec3 v = IcbmArrival.velocity(at.add(-6000, 0, 0), at, WeaponSpec.NUKE.airframe().diveSpeed());
        window = (int) (IcbmArrival.WINDOW + 1800 - IcbmArrival.start(0, IcbmArrival.WINDOW + 1800, v));
        long end = start + window;
        for (int i = 0; i < count; i++) {
            Vec3 target = at.add((i % 10) * 30, 0, (i / 10) * 30);
            Vec3 launch = target.add(-6000, 0, 0);
            ClientHooks.get().nukeWarning(new S2C.NukeWarning(9000 + i, target, launch, end - 1800, end, 15, true, false, false, 1));
            Detonation g = geometry(9000 + i, target, end);
            PATHS.add(new IcbmArrival(9000 + i, start, end, g,
                    IcbmArrival.velocity(launch, target, WeaponSpec.NUKE.airframe().diveSpeed())));
        }
        ClientHooks.get().nukeApproach(new S2C.NukeApproach(start, List.copyOf(PATHS)));
    }

    private static Detonation geometry(int id, Vec3 target, long end) {
        return new Detonation(id, target.add(0, Yield.optimalBurstHeight(15), 0), target.y, 15, false, end,
                0, 5, 20_000, 1234, 1, false, 0.25f, Minecraft.getInstance().level.getDayTime(), false);
    }

    static void tick() {
        if (age < 0) return;
        age++;
        if (age < window) ClientHooks.get().nukeApproach(new S2C.NukeApproach(start + age, List.copyOf(PATHS)));
        else if (age == window) {
            if (flash) ClientHooks.get().nukeDetonation(new S2C.NukeDetonation(geometry(9000, ground, start + age), 0, 9000));
            else for (IcbmArrival path : PATHS) ua.zentix.incoming.client.nuclear.ClientNuclear.approaches().finish(path.strikeId());
            ClientHooks.get().nukeApproach(new S2C.NukeApproach(start + age, List.of()));
        }
    }

    static void reset() { age = -1; PATHS.clear(); }
}
