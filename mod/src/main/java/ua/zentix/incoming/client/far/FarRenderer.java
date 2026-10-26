package ua.zentix.incoming.client.far;

import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import ua.zentix.incoming.client.fx.layer.FxLayer;
import ua.zentix.incoming.client.fx.layer.HeatPass;
import ua.zentix.incoming.client.fx.layer.FxLights;
import ua.zentix.incoming.client.fx.layer.FxQuads;
import ua.zentix.incoming.client.fx.layer.SourceProbes;
import ua.zentix.incoming.client.fx.layer.WaterDecal;
import ua.zentix.incoming.client.nuclear.NukeRenderer;
import ua.zentix.incoming.client.render.FarDraw;

import java.util.Arrays;

/**
 * Distant projectiles and blasts: no particles or entities beyond render distance, and under Iris shaders only
 * the after-world stage is visible. Frame: visible {@link FarBlasts} and {@link FarFlightView} collected into
 * {@link FarSprites} as quads in shared effects-layer pass ({@link FxLayer}) with near particles; projectile
 * models render into their own atlas ({@code FarModels}), tiles as quads in the same pass.
 */
public final class FarRenderer {
    private static final FarSprites SPRITES = new FarSprites();
    /** Billows, dots, lights, ribbons and models in last frame (scenario line). */
    private static final int[] LAST_FRAME = new int[6];

    private FarRenderer() {}

    /** Client tick (unpaused). */
    public static void tick() {
        FarBlasts.tick();
        FarFlightView.tick();
    }

    /**
     * Distant objects into effects-layer frame; fireballs also light frame particles ({@code lights}); bright
     * sources get visibility probes for their glare and veil ({@code probes}).
     */
    public static void collect(FarView view, FxQuads out, FxLights lights, SourceProbes probes) {
        SPRITES.begin(view, out, probes);
        // smoke-tube billow budget shared by projectile trails and fire plumes
        FarFlightView.budget();
        // clouds (blast, launch, fire) use baked billows near and far
        SmokePuffs.collect(view, SPRITES);
        if (!FarBlasts.isEmpty() || !FarFlightView.isEmpty()) {
            FarBlasts.collect(view, SPRITES, lights);
            FarFlightView.collect(view, SPRITES, lights);
        }
        // cloud billows, tube clusters and frame veils share one budget, after projectile trails
        SmokePuffs.flush();
        // distant fuel-fire flames where flame particles are already absent
        ua.zentix.incoming.client.fx.FireSmoke.far(view, SPRITES);
        // frame entity plume jets: after world, not through entity pipeline, or shader pack recolours them
        ua.zentix.incoming.client.render.PlumeRenderer.collect(view, SPRITES);
        NukeRenderer.fireballs(view, SPRITES, lights);
        SPRITES.counts(LAST_FRAME);
    }

    /**
     * Frame models into atlas, after {@link #collect}, before quad drawing. Atlas texture (0 — no atlas yet).
     */
    public static int renderModels() {
        SPRITES.models.render();
        return SPRITES.models.texture();
    }

    /**
     * First frame in world (and after resource reload): model atlas, meshes in GPU memory and one render each
     * before first distant projectile ({@code FarModels#warmup}).
     */
    public static void warmup() {
        SPRITES.models.warmup();
    }

    /** Frame data needed by all distant visuals and effects layer. */
    public static FarView view(RenderLevelStageEvent e, Minecraft mc, ClientLevel level) {
        Camera camera = e.getCamera();
        float partial = e.getPartialTick().getGameTimeDeltaPartialTick(false);
        // projection: m11 = 1 / tan(fovY / 2) — vertical pixel angle
        double pixel = 2 / (e.getProjectionMatrix().m11() * Math.max(1, mc.getWindow().getHeight()));
        double edge = Math.atan(0.5 * pixel * Math.hypot(mc.getWindow().getWidth(), mc.getWindow().getHeight()));
        float ambient = FarView.ambient(level, partial);
        double exposure = FarView.exposure(level, partial);
        Vec3 eye = FarDraw.eye(e);
        double range = Sight.range(level.getRainLevel(partial), level.getThunderLevel(partial));
        return new FarView(eye, camera.getLookVector(), camera.getLeftVector(), camera.getUpVector(), partial,
                FarView.depthFar(), pixel, edge, ambient, exposure, FarView.sky(mc.gameRenderer.lightTexture(), level, partial, new org.joml.Vector3f()), range, mc.options.getEffectiveRenderDistance() * 16.0,
                level.effects().getCloudHeight(), level.getRainLevel(partial), Sight.Air.at(eye.y - WaterDecal.SEA_LEVEL, range));
    }

    /**
     * Current distant state in one line (client scenarios log it): blasts and sound, projectiles, last rendered
     * frame's sprites and entire effects-layer frame time. Empty — nothing distant.
     */
    public static String describe() {
        if (FarBlasts.isEmpty() && FarFlightView.isEmpty()) return "";
        return "blasts: " + FarBlasts.describe() + "; projectiles: " + FarFlightView.describe() + "; frame: puffs " + LAST_FRAME[0] + ", points "
                + LAST_FRAME[1] + ", lights " + LAST_FRAME[2] + ", ribbons " + LAST_FRAME[3] + ", models " + LAST_FRAME[4] + ", veils " + LAST_FRAME[5] + "; layer: "
                + FxLayer.describeTiming();
    }

    /** Frame projectile plumes light world ({@link HeatPass}): air air, 1/block. */
    public static void heat(HeatPass.Frame heat, double air) {
        FarFlightView.heat(heat, air);
    }

    /** Projectile plume light at eye in previous frame ({@link HeatPass.Frame#eye}). */
    public static double flamesAtEye() {
        return FarFlightView.flamesAtEye();
    }

    /** World exit or dimension change. */
    public static void reset() {
        HeatPass.forget();
        FarBlasts.reset();
        FarFlightView.reset();
        ua.zentix.incoming.client.render.PlumeRenderer.reset();
        SmokePuffs.reset();
        Arrays.fill(LAST_FRAME, 0);
    }

    /**
     * Frame fill by cloud and tube quads (screens, with overlap), floor before and after merging and veil, cluster
     * share, cross-source merge level, veil Gaussians and clusters ({@link SmokePuffs#FILL_BUDGET}).
     */
    public static String describeSmoke() {
        return String.format(java.util.Locale.ROOT,
                "fill %.2f screens (budget %.0f, one club per gaussian %.2f, before merging %.2f), cluster share %.2f, merge level %d, veil gaussians %d, clusters %d",
                SmokePuffs.lastFill(), SmokePuffs.FILL_BUDGET, SmokePuffs.lastFloor(), SmokePuffs.lastRaw(), SmokePuffs.lastShare(),
                SmokePuffs.lastLevel(), SmokePuffs.lastVeils(), SmokePuffs.lastItems());
    }
}
