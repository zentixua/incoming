package ua.zentix.incoming.client.fx;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import ua.zentix.incoming.Incoming;

import java.util.ArrayList;
import java.util.List;

/** Client effect timelines (blasts, gas vents, collapses): tick with the world, stop when paused. */
public final class Effects {
    private Effects() {}

    /** Effect; tick(t) is called with t = 0, 1, 2 …, false — finished. */
    public interface Effect {
        boolean tick(ClientLevel level, int t);
    }

    private static final class Running {
        final Effect effect;
        int t;

        Running(Effect effect) {
            this.effect = effect;
        }
    }

    private static final List<Running> RUNNING = new ArrayList<>();

    public static void add(Effect effect) {
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null) return;
        Running r = new Running(effect);
        // tick zero immediately, at the event (the flash does not wait)
        if (run(level, r)) RUNNING.add(r);
    }

    public static void tick() {
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null) {
            clear();
            return;
        }
        RUNNING.removeIf(r -> !run(level, r));
    }

    private static boolean run(ClientLevel level, Running r) {
        try {
            return r.effect.tick(level, r.t++);
        } catch (RuntimeException e) {
            Incoming.LOG.error("Effect failed with an exception and was removed", e);
            return false;
        }
    }

    public static void clear() {
        RUNNING.clear();
    }
}
