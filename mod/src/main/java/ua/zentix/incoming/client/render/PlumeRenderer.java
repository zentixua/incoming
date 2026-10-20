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
 * сверхзвуковой части. Ширина слоя — средняя по его длине (площадь сбоку та же, что у света вдали). Яркость — свет чёрного тела с
 * привыканием глаза ({@code Sight.adapted}): что ярче белого экрана, рисуется во всю силу и белеет, как у света вдали
 * ({@code FarSprites}, пересвет {@code Sight.WHITE} — белый). Блика и вуали здесь нет — они у света факела
 * ({@code FarFlightView}) на любой дальности. Рисуется сложением, как «глаза» паука: светится и ночью, под шейдерами
 * Iris проходит как излучающая текстура.
 */
public final class PlumeRenderer {
    private static final ResourceLocation PLUME = Incoming.id("textures/fx/plume.png");
    private static final ResourceLocation DIAMONDS = Incoming.id("textures/fx/plume_diamonds.png");

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
        VertexConsumer vc = buffers.getBuffer(RenderType.eyes(f.diamonds() ? DIAMONDS : PLUME));
        strip(vc, pose, cam, p.z(), (float) f.length(), (float) ((f.width() + f.spread()) / 2), f.tailColor(), f.tailLuminance() * p.adapt(), p.near());
        strip(vc, pose, cam, p.z(), (float) f.flame(), (float) ((f.width() + f.widthAt(f.flame())) / 2), f.flameColor(), f.flameLuminance() * p.adapt(),
                p.near());
        strip(vc, pose, cam, p.z(), (float) f.core(), (float) f.width(), f.coreColor(), f.coreLuminance() * p.adapt(), p.near());
        pose.popPose();
    }

    /** Полоса от среза сопла назад на {@code length}, повёрнутая вокруг оси Z лицом к камере. */
    private static void strip(VertexConsumer vc, PoseStack pose, Vector3f cam, float z0, float length, float halfWidth, int color, double seen, float near) {
        // во всю силу, если ярче белого; пересвет белит цвет к Sight.WHITE
        float k = (float) Math.min(1, seen) * near, white = seen > 1 ? (float) Math.min(1, Math.log(seen) / Math.log(Sight.WHITE)) : 0;
        int rgb = whiten(color, white);
        Vector3f mid = new Vector3f(0, 0, z0 - length / 2);
        Vector3f toCam = new Vector3f(cam).sub(mid);
        Vector3f side = new Vector3f(0, 0, -1).cross(toCam);
        if (side.lengthSquared() < 1e-6f) side.set(1, 0, 0);
        side.normalize().mul(halfWidth);
        float z1 = z0 - length;
        // v = 0 — у сопла, 1 — хвост; обе стороны (задние грани отсекаются)
        vertex(vc, pose, -side.x, -side.y, z0, 0, 0, rgb, k);
        vertex(vc, pose, side.x, side.y, z0, 1, 0, rgb, k);
        vertex(vc, pose, side.x, side.y, z1, 1, 1, rgb, k);
        vertex(vc, pose, -side.x, -side.y, z1, 0, 1, rgb, k);
        vertex(vc, pose, -side.x, -side.y, z1, 0, 1, rgb, k);
        vertex(vc, pose, side.x, side.y, z1, 1, 1, rgb, k);
        vertex(vc, pose, side.x, side.y, z0, 1, 0, rgb, k);
        vertex(vc, pose, -side.x, -side.y, z0, 0, 0, rgb, k);
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
    private static void vertex(VertexConsumer vc, PoseStack pose, float x, float y, float z, float u, float v, int rgb, float k) {
        float r = (rgb >> 16 & 0xFF) / 255f * k, g = (rgb >> 8 & 0xFF) / 255f * k, b = (rgb & 0xFF) / 255f * k;
        vc.addVertex(pose.last(), x, y, z).setColor(r, g, b, 1).setUv(u, v).setOverlay(OverlayTexture.NO_OVERLAY)
                .setLight(LightTexture.FULL_BRIGHT).setNormal(pose.last(), 0, 1, 0);
    }
}
