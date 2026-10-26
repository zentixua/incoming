package ua.zentix.incoming.client.fx.smoke;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import ua.zentix.incoming.client.far.FarBlasts;
import ua.zentix.incoming.client.fx.layer.PallPass;
import ua.zentix.incoming.client.fx.layer.SmokePass;
import ua.zentix.incoming.client.nuclear.NukePall;

/**
 * Conventional blast smoke in effects-layer frame: smoke grid ({@link SmokeGrid}) into pall-pass frame
 * ({@link PallPass}), with or without nuke (then frame contains only skylight and air, {@link NukePall#lightFrame}).
 * Billow noise follows cloud wind; cloud fireballs light smoke and world (free nuclear-fireball columns,
 * {@link FarBlasts#fireballs}).
 */
public final class SmokeFrame {
    /** Two-phase billow-noise period, s ({@code fx_pall}: {@code smokeClumps}): billows travel speed × period over it (estimate). */
    static final float PERIOD = 2;
    /** Altitude for billow-noise wind, blocks (clouds hundreds of blocks above ground; estimate). */
    static final double NOISE_WIND_HEIGHT = 100;

    /** This frame's grid ({@link #build}). */
    private static SmokeGrid.Frame grid;

    private SmokeFrame() {}

    /**
     * Frame smoke grid at eye, before collecting far-view quads: clouds whose billows were not placed in grid
     * ({@link SmokeGrid.Source#placed}) draw themselves as quads in same frame.
     */
    public static void build(Vec3 eye, float partial) {
        SmokeGrid.enable(PallPass.available() && SmokePass.available());
        grid = SmokeGrid.frame(eye.x, eye.y, eye.z, SmokeGrid.seconds(partial));
    }

    /**
     * Pall-pass frame with this frame's smoke ({@link #build}): {@code pall} is nuke frame
     * ({@link NukePall#frame}, null — no nuke). If volume unavailable (shaders failed to build), blast clouds draw
     * entirely as quads ({@link SmokeGrid#on}). null — neither pall nor smoke.
     */
    @Nullable
    public static PallPass.Frame frame(ClientLevel level, Vec3 eye, float partial, @Nullable PallPass.Frame pall) {
        boolean on = PallPass.available() && SmokePass.available();
        if (!on || grid == null || grid.empty()) {
            if (pall != null) pall.smoke = null;
            return pall;
        }
        PallPass.Frame f = pall != null ? pall : NukePall.lightFrame(level, eye, partial);
        f.smoke = grid;
        float tile = 32;
        double seconds = SmokeGrid.seconds(partial);
        f.smokeNoise.set((float) Math.floorMod((long) Math.floor(eye.x * 1024), (long) tile * 1024) / 1024f,
                (float) Math.floorMod((long) Math.floor(eye.y * 1024), (long) tile * 1024) / 1024f,
                (float) Math.floorMod((long) Math.floor(eye.z * 1024), (long) tile * 1024) / 1024f, (float) (seconds % (64 * PERIOD)));
        double y = ua.zentix.incoming.util.Air.SEA + NOISE_WIND_HEIGHT;
        f.smokeWind.set((float) (20 * ua.zentix.incoming.client.fx.ClientAir.windX(y)), 0, (float) (20 * ua.zentix.incoming.client.fx.ClientAir.windZ(y)), PERIOD);
        f.smokeBalls.zero();
        f.smokeBallLight.zero();
        FarBlasts.fireballs(eye, partial, f.exposure, 0, f.smokeBalls, f.smokeBallLight);
        // fire flames light their own smoke from below, into free slots
        ua.zentix.incoming.client.fx.FireSmoke.lights(eye, f.exposure, 0, f.smokeBalls, f.smokeBallLight);
        // conventional fireball light on world uses own pass (HeatPass); only smoke-grid lighting here
        return f;
    }
}
