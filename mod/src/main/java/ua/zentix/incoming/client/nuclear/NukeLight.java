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
    private static final Map<ClientNuclear.Active, FlashLight.Eye> CAMERA = new IdentityHashMap<>();
    private static final double PEAK = BlackBody.luminance(FireballModel.PEAK_K);
    private static long ticks;
    private static double last = Double.NaN, exposure = 1, bodyExposure = 1;
    private static boolean prepared;

    private NukeLight() {}

    public static void begin(RenderFrameEvent.Pre e) { prepared = false; }

    /** Camera setup is complete here; RenderFrameEvent.Pre still has the previous frame's camera position. */
    public static void frame(ViewportEvent.ComputeFogColor e) {
        if (prepared) return;
        prepared = true;
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (level == null) return;
        float partial = (float) e.getPartialTick();
        Vec3 camera = e.getCamera().getPosition();
        double now = (ticks + partial) / 20, fire = 0;
        double ambient = FarView.ambient(level, partial);
        bodyExposure = Sight.exposure(ambient, 0);
        CAMERA.clear();
        for (ClientNuclear.Active a : ClientNuclear.detonations()) {
            FlashLight.Eye eye = sample(level, a, camera, partial);
            CAMERA.put(a, eye);
            fire += eye.light();
        }
        double target = Sight.exposure(ambient, fire);
        exposure = Double.isNaN(last) ? target : FlashLight.follow(exposure, target, Math.max(0, now - last), NukeExposure.ADAPT_S);
        last = now;
        NukeFlash.frame();
    }

    /** Pre-flash state, before the first live source starts to brighten the frame. */
    static void detonation(ClientLevel level) {
        if (Double.isNaN(last)) {
            exposure = bodyExposure = Sight.exposure(FarView.ambient(level, 0), 0);
            last = ticks / 20.0;
        }
    }

    static void tick() { ticks++; }
    static double exposure() { return exposure; }
    static double bodyExposure() { return bodyExposure; }
    static FlashLight.Eye camera(ClientNuclear.Active a) { return CAMERA.getOrDefault(a, FlashLight.Eye.DARK); }

    /** Same light law for the player's eye at pulse acquisition and the frame camera at render time. */
    static FlashLight.Eye sample(ClientLevel level, ClientNuclear.Active a, Vec3 eye, float partial) {
        Detonation d = a.d;
        MushroomModel.Shape shape = MushroomField.shape(a, partial);
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
        double beam = MushroomField.air(d, eye, d.visibility()).transmittance(distance, d.metres(source.y - eye.y));
        boolean seen = visible(level, eye, source);
        int sky = level.getBrightness(LightLayer.SKY, BlockPos.containing(eye));
        double through = FlashLight.transmission(seen, sky, total, beam);
        // Fluence/peak use original burst geometry; current illumination follows the rising and drifting cap.
        double burstDistance = d.metres(d.burst().distanceTo(eye));
        double burstTotal = ThermalModel.transmittance(burstDistance, d.visibility());
        double burstBeam = MushroomField.air(d, eye, d.visibility()).transmittance(burstDistance, d.metres(d.burst().y - eye.y));
        double burstThrough = FlashLight.transmission(seen, sky, burstTotal, burstBeam);
        return new FlashLight.Eye(light * through, PEAK * FlashLight.dilution(radius, burstDistance) * burstThrough,
                d.fluence(eye) * burstThrough / Math.max(burstTotal, 1e-30), burstThrough, seen);
    }

    /** Loaded near blocks plus the existing bounded distant terrain ray, never chunk loading. */
    static boolean visible(ClientLevel level, Vec3 eye, Vec3 source) {
        Vec3 delta = source.subtract(eye);
        // Same 48-block near ray as NuclearWarhead.sees; the terrain ray handles the remaining distance.
        Vec3 near = eye.add(delta.scale(Math.min(1, 48 / Math.max(delta.length(), 1e-3))));
        if (!Terrain.readyAlong(level, eye, near)) return false;
        if (level.clip(new ClipContext(eye, near, ClipContext.Block.VISUAL, ClipContext.Fluid.NONE,
                Minecraft.getInstance().getCameraEntity())).getType() != HitResult.Type.MISS) return false;
        return Sightline.trace(FarTerrain.of(level), source.x, source.y, source.z, near.x, near.y, near.z).hidden() <= 0;
    }

    static void reset() {
        CAMERA.clear();
        ticks = 0;
        last = Double.NaN;
        exposure = bodyExposure = 1;
        prepared = false;
    }
}
