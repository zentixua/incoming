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
 * Твёрдое обычного взрыва — частицами {@link Fx}: горящие куски с дымными хвостами (если было топливо), искры. Топливо в
 * воронке горит огнём Minecraft на блоках (сервер, {@code warhead/FuelFires}). Дым, пыль и огненный шар — облако по модели взрыва ({@code client/fx/smoke}) у события
 * {@code FarBlasts}: запечённые клубы и кадры шара слоя эффектов; здесь их нет. Удары осколков корпуса — {@link FragmentHits}.
 * Куски и искры — по размеру огненного шара модели
 * ({@code r}, блоки). Прорыв бомбы из-под земли ({@link #burst}) — прежняя картинка из частиц.
 */
final class Explosions {
    private Explosions() {}

    /** Бугров вокруг главного шара. */
    static final int LOBES = 4;

    /**
     * Мгновенная часть наземного взрыва (t = 0) — твёрдое: искры продуктов взрыва и, если в баке было топливо, горящие
     * куски корпуса, пропитанные им, с дымными хвостами (их дым тоньше клетки сетки — частицами). Осколки корпуса БЧ сами
     * не светятся (≈500–700 K, {@code warhead.FragmentModel}) и дыма не дают — гореть нечему: их видно по ударам
     * ({@link FragmentHits}). Число горящих кусков и искр — по радиусу шара r (оценка, как прежде по радиусу картинки).
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
     * Огненный шар радиуса {@code radius}, светящий {@code ticks} тиков: главный шар над точкой взрыва (у наземного —
     * полусфера, низ уходит в землю) и бугры по краю, которые остывают чуть раньше. Растёт и остывает он в кадрах
     * анимации; сам поднимается медленно, как горячий газ.
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

    /** Клубов дыма в шаре взрыва радиуса {@code r} при доле частиц {@code k} ({@link Fx#density}). */
    static int ballSmoke(float r, float k) {
        return Math.round(22 * r / 4 * k) + 4;
    }

    /** Пыль и дым, которые ударная волна гонит по земле во все стороны. */
    private static void dustSurge(ClientLevel level, Vec3 c, float r, GroundMaterial mat, RandomSource rnd, float k) {
        int n = Math.round(44 * r / 4 * k) + 6;
        int light = lighten(rgb(mat), 0.35f);
        for (int i = 0; i < n; i++) {
            double a = rnd.nextDouble() * Mth.TWO_PI, v = 0.5 + rnd.nextDouble() * 0.9;
            Fx.smoke().vel(Math.cos(a) * v * r / 4, 0.03 + rnd.nextDouble() * 0.08, Math.sin(a) * v * r / 4).size(r * 0.35f, r * (0.9f + 0.5f * rnd.nextFloat()))
                    .growFast().life(200 + rnd.nextInt(160)).color(rgb(mat), light).alpha(0.8f).drag(0.88f).collide().rise(0.002f)
                    .fadeIn(2).fadeFrom(0.4f).budget(FxBudget.GROUND).spawn(level, c.x + Math.cos(a) * r * 0.5, c.y + 0.6, c.z + Math.sin(a) * r * 0.5);
        }
    }

    /**
     * Вторичный подрыв в воронке (боекомплект, топливо): языки огня и искры; его дым и жар — часть облака взрыва в сетке
     * дыма (топливо, что горит в нём, — уже в модели облака).
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

    /** Случайное направление; {@code minUp} — нижняя граница вертикальной составляющей (0 — полусфера). */
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
