package ua.zentix.incoming.client.nuclear;

import com.mojang.blaze3d.shaders.FogShape;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.ViewportEvent;
import ua.zentix.incoming.nuclear.Detonation;

/**
 * Sky and fog: white at the flash, then amber while the ball is brighter than the sky ({@link #amber}: seconds by day,
 * about 10 s at night; {@link NukeRenderer} colors the sky); orange-brown from dust and smoke near ground zero; gray-brown and close in black rain.
 * Vanilla weather is unchanged: black rain is local.
 */
public final class NukeSky {
    /** Black rain intensity at the camera 0..1 (smooth rise and fall). */
    private static float rain;
    private static boolean rainTarget;

    private NukeSky() {}

    public static float blackRain() {
        return rain;
    }

    public static boolean inBlackRain() {
        return rainTarget;
    }

    static void tick(ClientLevel level) {
        Vec3 cam = Minecraft.getInstance().gameRenderer.getMainCamera().getPosition();
        boolean in = false;
        if (Detonation.underOpenSky(level, cam)) {
            for (ClientNuclear.Active a : ClientNuclear.detonations()) {
                if (a.d.blackRainAt(cam.x, cam.z, a.clockSeconds(0))) {
                    in = true;
                    break;
                }
            }
        }
        rainTarget = in;
        rain = Mth.clamp(rain + (in ? 0.02f : -0.03f), 0, 1);
    }

    static void reset() {
        rain = 0;
        rainTarget = false;
    }

    /** Air-lit sky from the same admitted light and followed exposure as the world and dust. */
    static float amber(ClientNuclear.Active a, Vec3 cam, float partial) {
        double lit = NukeLight.camera(a).light() * NukeLight.exposure();
        return (float) Math.min(1, lit);
    }

    public static void fogColor(ViewportEvent.ComputeFogColor e) {
        if (ClientNuclear.isEmpty() && rain <= 0) return;
        float partial = (float) e.getPartialTick();
        float r = e.getRed(), g = e.getGreen(), b = e.getBlue();
        // brown haze near ground zero: a minute after dust-wall arrival, denser at shorter distances
        Vec3 cam = e.getCamera().getPosition();
        float dust = 0, amber = 0;
        for (ClientNuclear.Active a : ClientNuclear.detonations()) {
            Detonation d = a.d;
            amber = Math.max(amber, amber(a, cam, partial));
            double dist = cam.distanceTo(d.burst());
            double near = 1 - dist / (d.radiusMax() * 2);
            if (near <= 0) continue;
            // haze is dust brought by the wall: measured from its arrival at the camera
            double t = (a.ticks(partial) - d.arrivalTicks(dist)) / (20.0 * d.scale());
            if (t < 0.5) continue;
            dust = Math.max(dust, (float) (0.45 * near * Math.exp(-t / 45) * CloudPuffs.smooth(0.5, 4, t)));
        }
        // amber glare while the ball and mushroom glow
        r = Mth.lerp(amber * 0.5f, r, 1f);
        g = Mth.lerp(amber * 0.5f, g, 0.6f);
        b = Mth.lerp(amber * 0.5f, b, 0.3f);
        r = Mth.lerp(dust, r, 0.62f);
        g = Mth.lerp(dust, g, 0.38f);
        b = Mth.lerp(dust, b, 0.2f);
        r = Mth.lerp(rain * 0.75f, r, 0.2f);
        g = Mth.lerp(rain * 0.75f, g, 0.19f);
        b = Mth.lerp(rain * 0.75f, b, 0.17f);
        float white = NukeFlash.whiteness(partial);
        r = Mth.lerp(white, r, 1f);
        g = Mth.lerp(white, g, 0.97f);
        b = Mth.lerp(white, b, 0.9f);
        e.setRed(r);
        e.setGreen(g);
        e.setBlue(b);
    }

    /** Visibility in black rain is 40–60 blocks. */
    public static void fog(ViewportEvent.RenderFog e) {
        if (rain <= 0) return;
        float far = Mth.lerp(rain, e.getFarPlaneDistance(), Math.min(e.getFarPlaneDistance(), 48));
        e.setFarPlaneDistance(far);
        e.setNearPlaneDistance(Math.min(e.getNearPlaneDistance(), far * 0.1f));
        e.setFogShape(FogShape.SPHERE);
        e.setCanceled(true);
    }
}
