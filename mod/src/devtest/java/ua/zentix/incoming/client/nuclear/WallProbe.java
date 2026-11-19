package ua.zentix.incoming.client.nuclear;

import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.Vec3;
import ua.zentix.incoming.nuclear.Detonation;

import java.util.Locale;

/**
 * Scenario probe (wave-color measurement): per detonation, the dust-wall head model behind a frame — model time, dust edge vs
 * shock radius, wedge, top, fire-zone edge, fire glow, frame sun/sky/exposure — and the head colour {@code fx_pall} would give
 * ({@link NukePall#dustLight}, the shader's Java copy) on the camera's line to ground zero: wedge share u, height share of the
 * top, with the soot share ({@link PallField#smoke}) and fire light from below that apply there. Logged by the {@code commands}
 * scenario with every shot that has a detonation ({@code SCENARIO shot … wall:}).
 */
public final class WallProbe {
    private WallProbe() {}

    public static String describe() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return "-";
        Vec3 eye = mc.gameRenderer.getMainCamera().getPosition();
        NukePall.Sky sky = new NukePall.Sky();
        NukePall.sky(mc.level, eye, 0, sky);
        String biome = mc.level.getBiome(net.minecraft.core.BlockPos.containing(eye)).unwrapKey().map(k -> k.location().toString()).orElse("?");
        StringBuilder b = new StringBuilder(String.format(Locale.ROOT, "camera %.0f %.0f %.0f biome %s, time %d; ", eye.x, eye.y, eye.z, biome, mc.level.getDayTime()));
        b.append(String.format(Locale.ROOT, "sky: sun (%.2f, %.2f) day %.2f sun %s sky %s base %.3f exposure %.4f glowOnSmoke %.3f",
                sky.sx, sky.sy, sky.day, rgb(sky.sun), rgb(sky.sky), sky.base, sky.exposure, sky.glowOnSmoke));
        float[] s = new float[BlastWall.SLOT], glow = new float[8], c = new float[3];
        for (ClientNuclear.Active a : ClientNuclear.detonations()) {
            double t = a.seconds(0), ticks = a.ticks(0);
            Detonation d = a.d;
            double gx = eye.x - d.burst().x, gz = eye.z - d.burst().z, range = Math.hypot(gx, gz);
            boolean head = a.wall.slot(t, s) && s[3] > 0;
            boolean lit = a.puffs.pallLightNow(a, glow);
            b.append(String.format(Locale.ROOT, " | det %d %.0f kt %s: t %.2f s, clock %.1f s, camera range %.0f, shock radius %.1f, fireR %.0f, glow %.2f, ball r %.0f lit %b, fire growth %.4f",
                    d.id(), d.yieldKt(), d.surface() ? "surface" : "air", t, a.clockSeconds(0), range, d.frontRadius(ticks), glow[3], glow[1], glow[0], lit,
                    FireStorm.growth(a.clockSeconds(0))));
            if (!head) {
                b.append(", no head");
                continue;
            }
            b.append(String.format(Locale.ROOT, ", head edge %.1f (edge - shock %.2f), wedge %.1f, top %.1f, scale %.4g, carried %.1f, wilson r %.0f tau %.3g",
                    s[0], s[0] - d.frontRadius(ticks), s[1], s[2], s[3], s[4], s[16], s[18]));
            double ux = range > 1e-3 ? gx / range : 1, uz = range > 1e-3 ? gz / range : 0;
            for (double u : new double[]{0.05, 0.3, 1.0, 1.3}) {
                double r = s[0] - u * s[1];
                double smoke = a.pall.smoke(r, ticks), gxr = d.burst().x + ux * r, gzr = d.burst().z + uz * r;
                double sand = PallField.sandShare(a.ground.hasSand() ? a.ground.sand(gxr, gzr) : 0, a.ground.hasHard() ? a.ground.hard(gxr, gzr) : 0, s[12]);
                double burn = CloudPuffs.burn(r, 0, glow[1], glow[3]) * (1 - 0.75 * sky.day);
                b.append(String.format(Locale.ROOT, "; u %.2f (r %.0f smoke %.2f sand %.2f burn %.3f, sigma@0.1top %.3g):", u, r, smoke, sand, burn,
                        BlastWall.sigma(s, ux * r, uz * r, 0.1 * s[2], 0)));
                for (double zf : new double[]{0.9, 0.5, 0.1}) {
                    double up = zf * s[2] * BlastWall.top(Math.min(u, 1));
                    NukePall.dustLight(s, glow, sky, d.windDir(), ux * r, up, uz * r, up, smoke, sand, c);
                    b.append(String.format(Locale.ROOT, " z%.1f %s", zf, rgb(c)));
                }
            }
        }
        return b.toString();
    }

    private static String rgb(float[] c) {
        return String.format(Locale.ROOT, "(%.3f %.3f %.3f)", c[0], c[1], c[2]);
    }
}
