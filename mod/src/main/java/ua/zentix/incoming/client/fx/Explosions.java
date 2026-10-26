package ua.zentix.incoming.client.fx;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.phys.Vec3;
import ua.zentix.incoming.client.far.FarBlasts;
import ua.zentix.incoming.client.far.FarSprites;
import ua.zentix.incoming.client.fx.particle.Fx;
import ua.zentix.incoming.client.fx.particle.FxBudget;
import ua.zentix.incoming.warhead.GroundMaterial;

/**
 * Conventional blast solids as {@link Fx} particles: burning pieces with smoky tails (if fuel was present), sparks. Crater
 * fuel burns as Minecraft block fire (server, {@code warhead/FuelFires}). Smoke, dust and fireball are the blast model cloud ({@code client/fx/smoke}) at the
 * {@code FarBlasts} event: baked billows and fireball frames in the effects layer; none here. Casing fragment hits — {@link FragmentHits}.
 * Pieces and sparks follow the model fireball size
 * ({@code r}, blocks). Bomb breakout from underground ({@link #burst}) — previous particle visuals.
 */
final class Explosions {
    private Explosions() {}

    /** Bulges around the main fireball. */
    static final int LOBES = 4;

    /**
     * Instantaneous part of a surface blast (t = 0) — solids: explosive products sparks and, if the tank held fuel, fuel-soaked burning
     * casing pieces with smoky tails (their smoke is thinner than a grid cell — particles). Warhead casing fragments themselves
     * do not glow (≈500–700 K, {@code warhead.FragmentModel}) or smoke — nothing to burn: they are visible through hits
     * ({@link FragmentHits}). Burning piece and spark counts follow fireball radius r (est.) (as previously by visual radius).
     */
    static void solids(ClientLevel level, Vec3 c, float r, boolean fuel, RandomSource rnd) {
        float k = Fx.density(c);
        int n;
        if (fuel) {
            Fx.Spec tail = Fx.smoke().size(r * 0.12f, r * 0.55f).life(140).color(0x3A3430, 0x8A8279).alpha(0.55f).glow(0.6f, 5).drag(0.93f)
                    .rise(0.003f).fadeIn(1).fadeFrom(0.35f).budget(FxBudget.DEBRIS);
            n = Math.round(9 * r / 4 * k) + 3;
            for (int i = 0; i < n; i++) {
                Vec3 d = dir(rnd, 0.35);
                Fx.spark().vel(d.scale(0.9 + rnd.nextDouble() * 1.1)).size(0.16f, 0.08f).life(28 + rnd.nextInt(24)).gravity(0.045f).drag(0.985f)
                        .streak(1.5f).trail(tail, 0.45f, 0.85f).spawn(level, c.add(0, r * 0.3, 0));
            }
        }
        n = Math.round(60 * r / 4 * k) + 10;
        for (int i = 0; i < n; i++) {
            Vec3 d = dir(rnd, 0.0);
            Fx.spark().vel(d.scale(0.4 + rnd.nextDouble() * 1.3)).life(10 + rnd.nextInt(30)).gravity(0.04f).drag(0.96f)
                    .spawn(level, c.add(0, r * 0.3, 0));
        }
    }


    /**
     * Fireball of radius {@code radius}, glowing for {@code ticks} ticks: main ball above the blast point (hemisphere for a surface blast,
     * its bottom below ground), and edge bulges that cool a little earlier. It grows and cools in animation
     * frames; the ball itself rises slowly, like hot gas.
     */
    static void fireball(ClientLevel level, Vec3 c, float radius, int ticks, RandomSource rnd) {
        float half = (float) (radius * FarSprites.FIREBALL);
        Vec3 at = c.add(0, radius * FarBlasts.BALL_LIFT, 0);
        Fx.fireball().vel(0, radius * 0.012, 0).size(half, half * 1.08f).life(ticks).spawn(level, at);
        for (int i = 0; i < LOBES; i++) {
            Vec3 d = dir(rnd, 0.1);
            float s = half * (0.5f + 0.2f * rnd.nextFloat());
            Fx.fireball().vel(d.scale(radius * 0.015).add(0, radius * 0.01, 0)).size(s, s * 1.12f).life(Math.max(3, ticks - rnd.nextInt(ticks / 4 + 1)))
                    .spawn(level, at.add(d.scale(radius * 0.55)));
        }
    }

    /** Smoke billow count in a blast fireball of radius {@code r} at particle share {@code k} ({@link Fx#density}). */
    static int ballSmoke(float r, float k) {
        return Math.round(22 * r / 4 * k) + 4;
    }

    /**
     * Secondary crater explosion (ammunition, fuel): flame tongues and sparks; its smoke and heat belong to the blast cloud in the smoke
     * grid (fuel burning inside it is already in the cloud model).
     */
    static void cookoff(ClientLevel level, Vec3 p, float r, RandomSource rnd) {
        for (int i = 0; i < 8; i++) {
            Vec3 d = dir(rnd, 0.3);
            Fx.fire().vel(d.scale(r * 0.08)).size(r * 0.25f, r * 0.6f).growFast().life(8 + rnd.nextInt(8)).drag(0.8f).spawn(level, p.add(0, r * 0.3, 0));
        }
        for (int i = 0; i < 25; i++) {
            Fx.spark().vel(dir(rnd, 0.2).scale(0.3 + rnd.nextDouble())).life(10 + rnd.nextInt(20)).gravity(0.04f).spawn(level, p.add(0, 0.5, 0));
        }
    }

    /** Random direction; {@code minUp} — lower bound on the vertical component (0 — hemisphere). */
    static Vec3 dir(RandomSource rnd, double minUp) {
        double y = minUp + (1 - minUp) * rnd.nextDouble();
        double a = rnd.nextDouble() * Mth.TWO_PI, h = Math.sqrt(1 - y * y);
        return new Vec3(Math.cos(a) * h, y, Math.sin(a) * h);
    }

    static int rgb(GroundMaterial m) {
        return (int) (m.r * 255) << 16 | (int) (m.g * 255) << 8 | (int) (m.b * 255);
    }

    static int lighten(int rgb, float f) {
        int r = rgb >> 16 & 0xFF, g = rgb >> 8 & 0xFF, b = rgb & 0xFF;
        return (int) (r + (255 - r) * f) << 16 | (int) (g + (255 - g) * f) << 8 | (int) (b + (255 - b) * f);
    }
}
