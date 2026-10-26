package ua.zentix.incoming.defense;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.UUID;

/**
 * Engage radar targets ({@link Radar}): one missile per target. A target already pursued by a missile (from this
 * or a nearby SAM) gets no second missile until the first bursts. A miss frees the target again. The launcher
 * ({@link SamBlockEntity}) decides how many missiles are ready and how often to launch; a portable SAM can reuse
 * this selection and launch logic.
 */
public final class FireControl {
    private FireControl() {}

    /** Nearest engageable target that nobody is firing at yet; null if there are none. */
    @Nullable
    public static Radar.Track pick(List<Radar.Track> tracks, DefenseWorld world) {
        for (Radar.Track t : tracks) {
            if (t.engageable() && !world.engaged(t.id())) return t;
        }
        return null;
    }

    /**
     * Launch a missile from {@code rail} at {@code track}.
     *
     * @param owner missile owner (who placed the SAM)
     * @param launcher launch location (for the log)
     */
    public static Interceptor launch(ServerLevel level, Vec3 rail, Radar.Track track, @Nullable UUID owner, String launcher, InterceptorSpec spec) {
        return DefenseWorld.get(level).launch(level, rail, track, owner, launcher, spec);
    }
}
