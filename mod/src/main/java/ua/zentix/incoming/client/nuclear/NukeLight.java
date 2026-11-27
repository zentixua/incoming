package ua.zentix.incoming.client.nuclear;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import net.neoforged.neoforge.client.event.ViewportEvent;
import ua.zentix.incoming.client.TickTime;
import ua.zentix.incoming.client.far.FarBlasts;
import ua.zentix.incoming.client.far.FarTerrain;
import ua.zentix.incoming.client.far.FarView;
import ua.zentix.incoming.client.far.Sight;
import ua.zentix.incoming.client.far.Sightline;
import ua.zentix.incoming.nuclear.Detonation;
import ua.zentix.incoming.nuclear.model.BlackBody;
import ua.zentix.incoming.nuclear.model.FireballModel;
import ua.zentix.incoming.nuclear.model.FlashLight;
import ua.zentix.incoming.nuclear.model.MushroomModel;
import ua.zentix.incoming.nuclear.model.ThermalModel;
import ua.zentix.incoming.util.Terrain;

import java.util.IdentityHashMap;
import java.util.Map;

/** One nuclear light sample and camera exposure per rendered frame, before fog, sky and effects. */
public final class NukeLight {
    private static Map<ClientNuclear.Active, FlashLight.Eye> CAMERA = new IdentityHashMap<>();
    private static final double PEAK = BlackBody.luminance(FireballModel.PEAK_K);
    private static long ticks;
    private static double last = Double.NaN, exposure = 1, bodyExposure = 1, day = 1, sky = 1;
    /**
     * Share of the flash the frame follows by day (presentation, marked): the frame's exposure is the unflashed one times
     * r^{@value}, r = flash exposure / unflashed exposure (DESIGN fireball-light §4.1). A frame fully adapted to the ball's light
     * turned the day black (mock round 2); one that keeps the daylight exposure showed the brightest thing in the world as a
     * mid-grey disc on an ordinary day ({@code main} before this change: the glow ring at 2 radii as bright as the sky).
     */
    static final double FOLLOW_DAY = 0.5;
    /** {@link FarView#ambient} of a clear night: the one-exposure change fades out toward it (night stays as it was). */
    static final double NIGHT_AMBIENT = 0.17;
    /** This frame's {@link #flashSetting}, read by the frame (the law itself takes no game state). */
    private static double setting = 1;
    private static boolean prepared;

    private NukeLight() {}

    public static void begin(RenderFrameEvent.Pre e) {
        ua.zentix.incoming.client.fx.layer.FxViews.outerFrame();
        prepared = false;
    }

    /** A secondary invocation samples its own light, without resetting the primary prepared flag. */
    public static void newView() { prepared = false; }

    /** Camera setup is complete here; RenderFrameEvent.Pre still has the previous frame's camera position. */
    public static void frame(ViewportEvent.ComputeFogColor e) {
        ua.zentix.incoming.client.fx.layer.FxViews.begin(e.getCamera());
        if (ua.zentix.incoming.client.fx.layer.FxViews.skipped()) return;
        if (prepared) return;
        prepared = true;
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (level == null) return;
        float partial = (float) e.getPartialTick();
        Vec3 camera = e.getCamera().getPosition();
        double now = TickTime.at(ticks, partial) / 20, fire = 0;
        double ambient = FarView.ambient(level, partial);
        CAMERA.clear();
        for (ClientNuclear.Active a : ClientNuclear.detonations()) {
            FlashLight.Eye eye = sample(level, a, camera, partial);
            CAMERA.put(a, eye);
            fire += eye.light();
        }
        setting = flashSetting();
        update(now, ambient, fire);
        if (!ua.zentix.incoming.client.fx.layer.FxViews.secondary()) NukeFlash.frame();
    }

    /** Advance the one exposure state from a frame's summed illumination; no wall clock. */
    static void update(double now, double ambient, double fire) {
        sky = dayShare(ambient);
        day = sky * setting;
        bodyExposure = Sight.exposure(ambient, 0);
        double target = Sight.exposure(ambient, fire);
        exposure = Double.isNaN(last) ? target : FlashLight.follow(exposure, target, Math.max(0, now - last), NukeExposure.ADAPT_S);
        last = now;
    }

    /** Pre-flash state, before the first live source starts to brighten the frame. */
    static void detonation(ClientLevel level) {
        if (Double.isNaN(last)) {
            exposure = bodyExposure = Sight.exposure(FarView.ambient(level, 0), 0);
            last = ticks / 20.0;
        }
    }

    static void tick() { ticks++; }
    /** The eye's flash exposure (dazzle, glare): ball light seen at this exposure is in eye units. */
    static double exposure() { return exposure; }
    static double bodyExposure() { return bodyExposure; }
    /** This frame's daylight share of the sky ({@link #dayShare}), without the Flash setting. */
    static double daySky() { return sky; }
    /** This frame's daylight share of the sky ({@link #dayShare}) times the Flash setting ({@link #flashSetting}). */
    static double day() { return day; }

    /**
     * The player's Flash brightness setting, capped in calm mode (vanilla Hide Lightning Flashes, {@code Flash.calm}): it scales
     * the day's share in the one-exposure change and the eye's veil like every other flash path (0: the frame keeps its
     * daylight exposure). Between full day and night (dusk, a lower setting) the frame's exposure moves log-linearly between
     * the unflashed one and r^{@value #FOLLOW_DAY} of it.
     */
    static double flashSetting() {
        double k = ua.zentix.incoming.IncomingConfig.CLIENT.flash.get();
        return ua.zentix.incoming.client.fx.Flash.calm() ? Math.min(k, ua.zentix.incoming.client.fx.Flash.CALM) : k;
    }

    /** Daylight share of the sky's ambient light: 1 by day, 0 at a clear night or darker. */
    static double dayShare(double ambient) {
        return Math.clamp((ambient - NIGHT_AMBIENT) / (1 - NIGHT_AMBIENT), 0, 1);
    }

    /** Flash exposure against the unflashed one, r ≤ 1. */
    static double ratio(double exposure, double bodyExposure) {
        return Math.min(1, exposure / Math.max(bodyExposure, 1e-30));
    }

    /**
     * The frame's one exposure for the ball's light (world, pall, mushroom, amber sky), relative to the eye's: r^((f − 1)·day).
     * By day the frame follows the flash only partly ({@link #FOLLOW_DAY}), so what the ball lights shows brighter than at
     * the eye's full adaptation; at night 1, as before.
     */
    static double ballGain(double ratio, double day) {
        // the ball's light is in eye units (× E = E0·r); at the frame's exposure E0·r^(f·day) it is × r^(f·day − 1), and
        // the change fades out with day (r^(day − day) = 1 at night)
        return Math.pow(Math.max(ratio, 1e-30), (FOLLOW_DAY - 1) * day);
    }

    /** The same exposure for daylight terms (vanilla world, sky, clouds, the pall's sun and sky light): r^(f·day) ≤ 1. */
    static double daylightGain(double ratio, double day) {
        return Math.pow(Math.max(ratio, 1e-30), FOLLOW_DAY * day);
    }

    /** {@link #exposure} as the frame shows the ball's light: the one frame exposure (DESIGN fireball-light §4.1). */
    public static double frameExposure() {
        return exposure * ballGain(ratio(exposure, bodyExposure), day);
    }

    /** This frame's daylight multiplier under the flash, linear, ≤ 1 (1 without a flash or at night). */
    public static double daylight() {
        return daylightGain(ratio(exposure, bodyExposure), day);
    }
    static FlashLight.Eye camera(ClientNuclear.Active a) { return CAMERA.getOrDefault(a, FlashLight.Eye.DARK); }

    /** Same light law for the player's eye at pulse acquisition and the frame camera at render time. */
    static FlashLight.Eye sample(ClientLevel level, ClientNuclear.Active a, Vec3 eye, float partial) {
        Detonation d = a.d;
        MushroomModel.Shape shape = MushroomField.shape(a, partial);
        if (shape == null && a.seconds(partial) > 0) return FlashLight.Eye.DARK;
        double radius = FireballModel.maxRadius(d.yieldKt(), d.surface());
        Vec3 source = d.burst();
        double light = 0;
        if (shape != null) {
            double[] centre = new double[3], rgb = new double[4];
            MushroomField.centre(a, shape, centre);
            source = new Vec3(centre[0], centre[1], centre[2]);
            FlashLight.source(shape, d.yieldKt(), d.surface(), rgb);
            light = rgb[3] * FlashLight.dilution(shape.capR(), d.metres(source.distanceTo(eye)));
        }
        double distance = d.metres(source.distanceTo(eye));
        double total = ThermalModel.transmittance(distance, d.visibility());
        // Use the same uniform-air path for beam and total, as world relight; subtracting an altitude-thinned
        // beam from a sea-level total would erase scattered light at an elevated burst.
        double beam = Math.exp(-Sight.KOSCHMIEDER * distance / d.visibility());
        double ballRadius = shape != null ? d.blocks(shape.capR()) : d.blocks(radius);
        double share = visible(level, eye, source, d.groundY(), ballRadius);
        // glare and dazzle keep a yes/no source: seen while most of the disc is
        boolean seen = share >= 0.5;
        int sky = level.getBrightness(LightLayer.SKY, BlockPos.containing(eye));
        double through = FlashLight.transmission(share, sky, total, beam);
        // Fluence/peak use original burst geometry; current illumination follows the rising and drifting cap.
        double burstDistance = d.metres(d.burst().distanceTo(eye));
        double burstTotal = ThermalModel.transmittance(burstDistance, d.visibility());
        double burstBeam = Math.exp(-Sight.KOSCHMIEDER * burstDistance / d.visibility());
        double burstThrough = FlashLight.transmission(share, sky, burstTotal, burstBeam);
        return new FlashLight.Eye(light * through, PEAK * FlashLight.dilution(radius, burstDistance) * burstThrough,
                d.fluence(eye) * burstThrough / Math.max(burstTotal, 1e-30), burstThrough, seen);
    }

    /**
     * Visible share of the ball disc: loaded near blocks plus the existing bounded distant terrain ray, never chunk
     * loading. The near 48 blocks stay a yes/no test (a wall at the eye hides everything); beyond them the terrain line
     * covers the ball from below as for conventional fireballs ({@link FarBlasts#visible}). A single ray to the centre
     * of a ground ball, which sits at ground level for its first seconds, was hidden by tree crowns 2 km out while
     * 94–98 % of the disc was open, and dropped the light 3.5 times (Zearth audit 09.11.2026).
     */
    static double visible(ClientLevel level, Vec3 eye, Vec3 source, double groundY, double radius) {
        Vec3 delta = source.subtract(eye);
        // Same 48-block near ray as NuclearWarhead.sees; the terrain ray handles the remaining distance.
        Vec3 near = eye.add(delta.scale(Math.min(1, 48 / Math.max(delta.length(), 1e-3))));
        if (!Terrain.readyAlong(level, eye, near)) return 0;
        if (level.clip(new ClipContext(eye, near, ClipContext.Block.VISUAL, ClipContext.Fluid.NONE,
                Minecraft.getInstance().getCameraEntity())).getType() != HitResult.Type.MISS) return 0;
        return share(FarTerrain.of(level), near, source, groundY, radius);
    }

    /**
     * Share of a ball (centre {@code centre}, radius blocks, standing on {@code groundY}) above the terrain line seen
     * from {@code from}: the ray runs from the ground under the centre, so the line is its height above that ground.
     */
    static double share(Sightline.Heights heights, Vec3 from, Vec3 centre, double groundY, double radius) {
        double line = Sightline.trace(heights, centre.x, groundY, centre.z, from.x, from.y, from.z).hidden();
        return FarBlasts.visible(centre.y - groundY, Math.max(radius, 1), line);
    }

    /** Diagnostic only; samples and clock are not advanced by readers. */
    static String describe() {
        double fire = 0;
        for (FlashLight.Eye eye : CAMERA.values()) fire += eye.light();
        return String.format(java.util.Locale.ROOT, "time %.3f s, eye %.6g, exposure %.6g, body %.6g, day %.2f, frame %.6g, daylight %.4f, sources %d",
                last, fire, exposure, bodyExposure, day, frameExposure(), daylight(), CAMERA.size());
    }

    static void reset() {
        CAMERA.clear();
        ticks = 0;
        last = Double.NaN;
        exposure = bodyExposure = 1;
        day = sky = 1;
        prepared = false;
    }

    /** Saved only for secondary views; swapping leaves the primary draw path and operation order unchanged. */
    public static final class ViewState {
        private Map<ClientNuclear.Active, FlashLight.Eye> CAMERA = new IdentityHashMap<>();
        private double last = Double.NaN;
        private double exposure = 1;
        private double bodyExposure = 1;
        private double day = 1;
        private double sky = 1;
        private boolean prepared;

        public void swap() {
            Map<ClientNuclear.Active, FlashLight.Eye> oldCAMERA = NukeLight.CAMERA;
            NukeLight.CAMERA = CAMERA;
            CAMERA = oldCAMERA;
            double oldlast = NukeLight.last;
            NukeLight.last = last;
            last = oldlast;
            double oldexposure = NukeLight.exposure;
            NukeLight.exposure = exposure;
            exposure = oldexposure;
            double oldbodyExposure = NukeLight.bodyExposure;
            NukeLight.bodyExposure = bodyExposure;
            bodyExposure = oldbodyExposure;
            double oldday = NukeLight.day;
            NukeLight.day = day;
            day = oldday;
            double oldsky = NukeLight.sky;
            NukeLight.sky = sky;
            sky = oldsky;
            boolean oldprepared = NukeLight.prepared;
            NukeLight.prepared = prepared;
            prepared = oldprepared;
        }
    }
}
