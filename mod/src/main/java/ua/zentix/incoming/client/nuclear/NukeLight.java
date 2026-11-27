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
     * The eye's slow adaptation, 0..1: how far it has adapted to a lasting light, growing with {@link #SLOW_UP_S} while the
     * ball's light exceeds the daylight and fading at that pace once it no longer does ({@link #adapt}).
     */
    private static double slow;
    /**
     * Share of the flash the frame follows by day (presentation, marked): the frame's exposure is the unflashed one times
     * r^{@value}, r = flash exposure / unflashed exposure (DESIGN fireball-light §4.1). A frame fully adapted to the ball's light
     * turned the day black (mock round 2); one that keeps the daylight exposure showed the brightest thing in the world as a
     * mid-grey disc on an ordinary day ({@code main} before this change: the glow ring at 2 radii as bright as the sky).
     */
    static final double FOLLOW_DAY = 0.5;
    /**
     * Share of the flash the frame follows once the eye has had time to adapt to a lasting light (presentation, est.): the
     * square root holds for a flash, and an eye or a camera held for seconds in a light tens of noons strong adapts most of
     * the way (cone light adaptation, auto-exposure). At {@value #FOLLOW_DAY} through a megaton's ten-second pulse the world
     * and the dust the ball lights stand at 3–9 whites for 15 s at 4.5 km from 1 Mt, one flat white and orange fill. Below
     * 1: the sun-lit day keeps a dim share beside the ball's light.
     */
    static final double FOLLOW_LASTING = 0.85;
    /**
     * Time constant of that slow adaptation (presentation, est.): a flash under a second long (15 kt) keeps nearly the square
     * root, a pulse of seconds (100 kt and more) is followed to {@link #FOLLOW_LASTING} within a few seconds.
     */
    static final double SLOW_UP_S = 4.0;
    /** {@link FarView#ambient} of a clear night: the one-exposure change fades out toward it (night stays as it was). */
    static final double NIGHT_AMBIENT = 0.17;
    /** This frame's {@link #flashSetting}, read by the frame (the law itself takes no game state). */
    private static double setting = 1;
    private static boolean prepared;
    /** The game time (ticks plus partial tick) the frame's light was last sampled at. */
    private static double preparedAt = Double.NaN;

    private NukeLight() {}

    /**
     * Whether a fog-colour call at game time {@code at} must sample the light again: once per frame event, and on any new game
     * time. A Flashback video export draws its frames directly, without the frame events (replay.md): sampled per frame
     * event alone, an export keeps its pre-export exposure and shows the ball's light at the unflashed one, the world white.
     */
    static boolean fresh(boolean prepared, double preparedAt, double at) {
        return !prepared || at != preparedAt;
    }

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
        float partial = (float) e.getPartialTick();
        double at = TickTime.at(ticks, partial);
        if (!fresh(prepared, preparedAt, at)) return;
        prepared = true;
        preparedAt = at;
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (level == null) return;
        Vec3 camera = e.getCamera().getPosition();
        double now = at / 20, fire = 0, lasting = 0;
        double ambient = FarView.ambient(level, partial);
        CAMERA.clear();
        for (ClientNuclear.Active a : ClientNuclear.detonations()) {
            FlashLight.Eye eye = sample(level, a, camera, partial);
            CAMERA.put(a, eye);
            fire += eye.light();
            if (eye.light() > 0) lasting = Math.max(lasting, a.seconds(partial));
        }
        setting = flashSetting();
        update(now, ambient, fire, lasting);
        if (!ua.zentix.incoming.client.fx.layer.FxViews.secondary()) NukeFlash.frame();
    }

    /** Advance the one exposure state from a frame's summed illumination; no wall clock. */
    static void update(double now, double ambient, double fire) {
        update(now, ambient, fire, 0);
    }

    /** As above; lasting: seconds the summed light has shone (the brightest lit source's age), for a rebuilt state. */
    static void update(double now, double ambient, double fire, double lasting) {
        sky = dayShare(ambient);
        day = sky * setting;
        bodyExposure = Sight.exposure(ambient, 0);
        double target = Sight.exposure(ambient, fire);
        double dt = Double.isNaN(last) ? 0 : Math.max(0, now - last);
        exposure = Double.isNaN(last) ? target : FlashLight.follow(exposure, target, dt, NukeExposure.ADAPT_S);
        double x = Math.log(1 / ratio(exposure, bodyExposure));
        // a rebuilt state (seek, world entry, sync, respawn) takes the adaptation of an eye that watched the light since it began
        slow = Double.isNaN(last) ? (x > ABOVE_DAY ? 1 - Math.exp(-Math.max(lasting, 0) / SLOW_UP_S) : 0) : adapt(slow, x, dt);
        last = now;
    }

    /**
     * The light counts as lasting while it stands over the daylight by more than this, ln(1/r) (est.: a tenth brighter than
     * the day; below that the frame's exposure is within 8 % of the unflashed one whatever the follow share).
     */
    static final double ABOVE_DAY = 0.1;

    /**
     * The slow adaptation a after dt seconds under a light x = ln(1/r) over the daylight: it grows while the light lasts and
     * fades at the same pace once it is gone (an eye readapts to the dark no faster than to the light), so the ball hidden
     * for a moment behind a tree keeps the adapted look.
     */
    static double adapt(double a, double x, double dt) {
        double k = Math.exp(-dt / SLOW_UP_S);
        return x > ABOVE_DAY ? 1 - (1 - a) * k : a * k;
    }

    /** Pre-flash state, before the first live source starts to brighten the frame. */
    static void detonation(ClientLevel level) {
        if (Double.isNaN(last)) {
            exposure = bodyExposure = Sight.exposure(FarView.ambient(level, 0), 0);
            slow = 0;
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
     * The frame's one exposure for the ball's light (world, pall, mushroom, amber sky), relative to the eye's: by day the frame
     * follows the flash only partly ({@link #FOLLOW_DAY}, deepening toward {@link #FOLLOW_LASTING} as the eye adapts to a lasting
     * light: slow, the adaptation's share), so what the ball lights shows brighter than at the eye's full adaptation; at night 1,
     * as before. The ball's light is in eye units (× E = E0·r): at the frame's exposure E0·(daylight gain) it is × (daylight
     * gain) / r^day, and the change fades out with day.
     */
    static double ballGain(double ratio, double day, double slow) {
        return daylightGain(ratio, day, slow) * Math.pow(Math.max(ratio, 1e-30), -day);
    }

    static double ballGain(double ratio, double day) {
        return ballGain(ratio, day, 0);
    }

    /**
     * The same exposure for daylight terms (vanilla world, sky, clouds, the pall's sun and sky light), ≤ 1:
     * r^(f·day), f moving from {@link #FOLLOW_DAY} to {@link #FOLLOW_LASTING} with the slow adaptation's share.
     */
    static double daylightGain(double ratio, double day, double slow) {
        double f = FOLLOW_DAY + (FOLLOW_LASTING - FOLLOW_DAY) * Math.clamp(slow, 0, 1);
        return Math.pow(Math.max(Math.min(ratio, 1), 1e-30), f * day);
    }

    static double daylightGain(double ratio, double day) {
        return daylightGain(ratio, day, 0);
    }

    /** {@link #exposure} as the frame shows the ball's light: the one frame exposure (DESIGN fireball-light §4.1). */
    public static double frameExposure() {
        return exposure * ballGain(ratio(exposure, bodyExposure), day, slow);
    }

    /** This frame's daylight multiplier under the flash, linear, ≤ 1 (1 without a flash or at night). */
    public static double daylight() {
        return daylightGain(ratio(exposure, bodyExposure), day, slow);
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
    public static String describe() {
        double fire = 0;
        for (FlashLight.Eye eye : CAMERA.values()) fire += eye.light();
        return String.format(java.util.Locale.ROOT, "time %.3f s, eye %.6g, exposure %.6g, body %.6g, day %.2f, slow %.3f, frame %.6g, daylight %.4f, sources %d",
                last, fire, exposure, bodyExposure, day, slow, frameExposure(), daylight(), CAMERA.size());
    }

    static void reset() {
        CAMERA.clear();
        ticks = 0;
        last = Double.NaN;
        exposure = bodyExposure = 1;
        day = sky = 1;
        slow = 0;
        prepared = false;
        preparedAt = Double.NaN;
    }

    /** Saved only for secondary views; swapping leaves the primary draw path and operation order unchanged. */
    public static final class ViewState {
        private Map<ClientNuclear.Active, FlashLight.Eye> CAMERA = new IdentityHashMap<>();
        private double last = Double.NaN;
        private double exposure = 1;
        private double bodyExposure = 1;
        private double day = 1;
        private double sky = 1;
        private double slow;
        private boolean prepared;
        private double preparedAt = Double.NaN;

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
            double oldslow = NukeLight.slow;
            NukeLight.slow = slow;
            slow = oldslow;
            boolean oldprepared = NukeLight.prepared;
            NukeLight.prepared = prepared;
            prepared = oldprepared;
            double oldpreparedAt = NukeLight.preparedAt;
            NukeLight.preparedAt = preparedAt;
            preparedAt = oldpreparedAt;
        }
    }
}
