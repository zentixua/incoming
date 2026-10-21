package ua.zentix.incoming.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Camera;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import ua.zentix.incoming.Incoming;
import ua.zentix.incoming.client.far.Sight;
import ua.zentix.incoming.client.fx.Exhaust;
import ua.zentix.incoming.client.fx.plume.Flame;
import ua.zentix.incoming.client.fx.plume.FlameModel;

/**
 * Факел двигателя ({@link Flame}, из {@code FlameModel}): полоса вдоль оси сопла, развёрнутая к камере (с любой стороны —
 * струя, а не плоская картинка), в три слоя — остывающий хвост во всю видимую длину, пламя догорания и ядро до конца
 * сверхзвуковой части. Ширина слоя — средняя по его длине (площадь сбоку та же, что у света вдали). Струя — цилиндр:
 * сбоку видна её длина, с торца — сечение. Полоса вдоль оси с торца сходит в отрезок поперёк (вид строго сзади —
 * «палка»), поэтому у среза сопла — ещё сечение струи лицом к камере: круг пламени догорания (полуширина в конце пламени)
 * и ядро (полуширина у среза), круг {@code far/disc} того же потока, что сечение ({@link #HALO}). Сбоку они — свечение у
 * сопла, как у настоящей струи. Геометрия — {@link #layout} (её площадь с торца и сбоку сверяет
 * {@code PlumeRendererTest}). Яркость — свет чёрного тела с
 * привыканием глаза ({@code Sight.adapted}): что ярче белого экрана, рисуется во всю силу и белеет, как у света вдали
 * ({@code FarSprites}, пересвет {@code Sight.WHITE} — белый). Блика и вуали здесь нет — они у света факела
 * ({@code FarFlightView}) на любой дальности. Рисуется сложением, как «глаза» паука: светится и ночью, под шейдерами
 * Iris проходит как излучающая текстура.
 */
public final class PlumeRenderer {
    private static final ResourceLocation PLUME = Incoming.id("textures/fx/plume.png");
    private static final ResourceLocation DIAMONDS = Incoming.id("textures/fx/plume_diamonds.png");
    private static final ResourceLocation HALO_TEXTURE = Incoming.id("textures/far/disc.png");
    /**
     * Полуразмер квадрата сечения на его радиус: круг {@code far/disc} с мягким краем — того же потока, что круг радиуса r
     * ({@link ua.zentix.incoming.client.far.FarSprites#DISC}). Не ореол {@code fx/halo}: его хвост в три радиуса — свечение
     * вокруг, а не струя, и с торца факел выходил «солнцем» (свет в глазу вокруг — блик и вуаль у {@code FarFlightView}).
     */
    static final float HALO = (float) ua.zentix.incoming.client.far.FarSprites.DISC;

    /** Куда {@link #layout} кладёт квадраты: четыре угла (система снаряда), слой (0–2 — полосы, 3–4 — сечения). */
    interface Quads {
        void quad(int layer, Vector3f a, Vector3f b, Vector3f c, Vector3f d);
    }

    private PlumeRenderer() {}

    /**
     * @param pose     система снаряда (нос по +Z), уже повёрнутая
     * @param rotation поворот, которым её повернули (чтобы перевести камеру в систему снаряда)
     * @param origin   положение снаряда в мире в этот кадр
     */
    public static void render(Exhaust.Plume p, PoseStack pose, MultiBufferSource buffers, Quaternionf rotation, Vec3 origin, Camera camera) {
        Flame f = p.flame();
        Quaternionf inv = new Quaternionf(rotation).conjugate();
        Vec3 c = camera.getPosition().subtract(origin);
        Vector3f cam = new Vector3f((float) c.x, (float) c.y, (float) c.z).rotate(inv).sub(0, p.y(), 0);
        pose.pushPose();
        pose.translate(0, p.y(), 0);
        // оси экрана в системе снаряда (копии: векторы камеры — её живые поля)
        Vector3f left = new Vector3f(camera.getLeftVector()).rotate(inv), up = new Vector3f(camera.getUpVector()).rotate(inv);
        VertexConsumer strips = buffers.getBuffer(RenderType.eyes(f.diamonds() ? DIAMONDS : PLUME));
        int[] colours = {f.tailColor(), f.flameColor(), f.coreColor(), f.flameColor(), f.coreColor()};
        double[] seen = {f.tailLuminance(), f.flameLuminance(), f.coreLuminance(), f.flameLuminance(), f.coreLuminance()};
        layout(f, p.z(), cam, left, up, (layer, q0, q1, q2, q3) -> {
            VertexConsumer vc = layer < 3 ? strips : buffers.getBuffer(RenderType.eyes(HALO_TEXTURE));
            double lum = seen[layer] * p.adapt();
            // во всю силу, если ярче белого; пересвет белит цвет к Sight.WHITE
            float k = (float) Math.min(1, lum) * p.near(), white = lum > 1 ? (float) Math.min(1, Math.log(lum) / Math.log(Sight.WHITE)) : 0;
            int rgb = whiten(colours[layer], white);
            // v = 0 — у сопла, 1 — хвост; обе стороны (задние грани отсекаются)
            vertex(vc, pose, q0, 0, 0, rgb, k);
            vertex(vc, pose, q1, 1, 0, rgb, k);
            vertex(vc, pose, q2, 1, 1, rgb, k);
            vertex(vc, pose, q3, 0, 1, rgb, k);
            vertex(vc, pose, q3, 0, 1, rgb, k);
            vertex(vc, pose, q2, 1, 1, rgb, k);
            vertex(vc, pose, q1, 1, 0, rgb, k);
            vertex(vc, pose, q0, 0, 0, rgb, k);
        });
        pose.popPose();
    }

    /**
     * Квадраты факела f со срезом сопла на z0 (система снаряда, нос по +Z) для камеры cam и осей экрана left, up там же:
     * полосы хвоста, пламени и ядра вдоль оси, повёрнутые к камере, и сечения — круги пламени и ядра у среза лицом
     * к камере.
     */
    static void layout(Flame f, float z0, Vector3f cam, Vector3f left, Vector3f up, Quads out) {
        strip(out, 0, cam, z0, (float) f.length(), (float) ((f.width() + f.spread()) / 2));
        strip(out, 1, cam, z0, (float) f.flame(), (float) ((f.width() + f.widthAt(f.flame())) / 2));
        strip(out, 2, cam, z0, (float) f.core(), (float) f.width());
        disc(out, 3, new Vector3f(0, 0, z0 - (float) f.width()), left, up, HALO * (float) f.widthAt(f.flame()));
        disc(out, 4, new Vector3f(0, 0, z0 - 0.5f * (float) f.width()), left, up, HALO * (float) f.width());
    }

    /** Полоса от среза сопла назад на {@code length}, повёрнутая вокруг оси Z лицом к камере. */
    private static void strip(Quads out, int layer, Vector3f cam, float z0, float length, float halfWidth) {
        Vector3f mid = new Vector3f(0, 0, z0 - length / 2);
        Vector3f toCam = new Vector3f(cam).sub(mid);
        Vector3f side = new Vector3f(0, 0, -1).cross(toCam);
        if (side.lengthSquared() < 1e-6f) side.set(1, 0, 0);
        side.normalize().mul(halfWidth);
        float z1 = z0 - length;
        out.quad(layer, new Vector3f(-side.x, -side.y, z0), new Vector3f(side.x, side.y, z0), new Vector3f(side.x, side.y, z1),
                new Vector3f(-side.x, -side.y, z1));
    }

    /** Квадрат полуразмера s с центром at лицом к камере (оси экрана left, up). */
    private static void disc(Quads out, int layer, Vector3f at, Vector3f left, Vector3f up, float s) {
        Vector3f l = new Vector3f(left).mul(s), u = new Vector3f(up).mul(s);
        out.quad(layer, new Vector3f(at).sub(l).sub(u), new Vector3f(at).sub(l).add(u), new Vector3f(at).add(l).add(u), new Vector3f(at).add(l).sub(u));
    }

    /** Цвет, белеющий на долю w (в линейном свете). */
    private static int whiten(int rgb, float w) {
        int out = 0;
        for (int shift = 16; shift >= 0; shift -= 8) {
            double l = FlameModel.linear(rgb >> shift & 255);
            out |= (int) Math.round(255 * FlameModel.srgb(l + (1 - l) * w)) << shift;
        }
        return out;
    }

    /** Цвет × яркость: при сложении прозрачность не участвует, яркость — в самом цвете. */
    private static void vertex(VertexConsumer vc, PoseStack pose, Vector3f at, float u, float v, int rgb, float k) {
        float r = (rgb >> 16 & 0xFF) / 255f * k, g = (rgb >> 8 & 0xFF) / 255f * k, b = (rgb & 0xFF) / 255f * k;
        vc.addVertex(pose.last(), at.x, at.y, at.z).setColor(r, g, b, 1).setUv(u, v).setOverlay(OverlayTexture.NO_OVERLAY)
                .setLight(LightTexture.FULL_BRIGHT).setNormal(pose.last(), 0, 1, 0);
    }
}
