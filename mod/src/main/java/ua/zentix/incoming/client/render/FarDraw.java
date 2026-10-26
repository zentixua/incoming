package ua.zentix.incoming.client.render;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.MeshData;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Matrix4fStack;
import org.joml.Vector3f;

/**
 * Shared rendering after the world ({@code AFTER_LEVEL}) at any distance: nuclear strike, distant projectiles and
 * explosions. Under Iris shaders only this stage remains visible (earlier rendering goes through pack passes,
 * whose fog eats distant objects). Test depth without writing: nearer terrain occludes, sky does not.
 * Beyond the far clip plane, move a point closer along the same ray and shrink by the same factor to preserve
 * angular size ({@link #fold}). Origin is the eye ({@link #eye}): relocation rays start there.
 */
public final class FarDraw {
    private static final Matrix4f VIEW_PROJECTION = new Matrix4f();
    private static final Vector3f OFFSET = new Vector3f();

    private FarDraw() {}

    /**
     * Frame eye in the world: projection centre from which the world was actually drawn. View bobbing shifts it
     * from camera position by up to 0.1 blocks: vanilla puts bobbing in projection, Iris with a shader pack in the
     * view matrix ({@code MixinModelViewBobbing}), with the same product. Anything moved closer along a ray with the
     * same angular size (glare 0.25 blocks before the eye, atmospheric halo, distant objects at the far plane) moves
     * toward this eye: from camera position, fire glare would swing by up to 22° with each step while the world behind
     * stood still.
     */
    public static Vec3 eye(RenderLevelStageEvent e) {
        Vector3f o = eyeOffset(e.getProjectionMatrix(), e.getModelViewMatrix(), OFFSET);
        return e.getCamera().getPosition().add(o.x(), o.y(), o.z());
    }

    /**
     * Eye from camera position using frame matrices (projection and camera-relative view), into dest. Orthographic
     * projection has no centre: eye at camera position.
     */
    static Vector3f eyeOffset(Matrix4fc projection, Matrix4fc modelView, Vector3f dest) {
        VIEW_PROJECTION.set(projection).mul(modelView).perspectiveOrigin(dest);
        return dest.isFinite() ? dest : dest.zero();
    }

    /** Begin: custom view matrix with eye {@code eye} ({@link #eye}) at origin, state as left by world rendering. */
    public static void begin(RenderLevelStageEvent e, Vec3 eye) {
        Matrix4fStack mv = RenderSystem.getModelViewStack();
        mv.pushMatrix();
        mv.identity();
        fromEye(mv.mul(e.getModelViewMatrix()), e, eye);
        RenderSystem.applyModelViewMatrix();
    }

    /** Frame view for eye-relative coordinates {@code eye}, as {@link #begin}, into dest. */
    public static Matrix4f view(RenderLevelStageEvent e, Vec3 eye, Matrix4f dest) {
        fromEye(dest.set(e.getModelViewMatrix()), e, eye);
        return dest;
    }

    /** Frame projection-view for eye-relative coordinates {@code eye}, as {@link #begin}, into dest. */
    public static Matrix4f viewProjection(RenderLevelStageEvent e, Vec3 eye, Matrix4f dest) {
        fromEye(dest.set(e.getProjectionMatrix()).mul(e.getModelViewMatrix()), e, eye);
        return dest;
    }

    /** Convert camera-relative matrix to eye-relative coordinates. */
    private static void fromEye(Matrix4f m, RenderLevelStageEvent e, Vec3 eye) {
        Vec3 cam = e.getCamera().getPosition();
        m.translate((float) (eye.x - cam.x), (float) (eye.y - cam.y), (float) (eye.z - cam.z));
    }

    /** Restore matrix and state expected by subsequent rendering. */
    public static void end() {
        RenderSystem.getModelViewStack().popMatrix();
        RenderSystem.applyModelViewMatrix();
        RenderSystem.depthMask(true);
        RenderSystem.enableDepthTest();
        RenderSystem.enableCull();
        RenderSystem.defaultBlendFunc();
        RenderSystem.disableBlend();
        RenderSystem.setShaderColor(1, 1, 1, 1);
    }

    /** Beyond {@code far}, factor to bring point closer (and shrink its size); otherwise 1. */
    public static double fold(double dist, double far) {
        return dist > far ? far / dist : 1;
    }

    /** Custom texture and blending. */
    public static void setup(ResourceLocation texture, boolean additive) {
        RenderSystem.setShader(GameRenderer::getPositionTexColorShader);
        RenderSystem.setShaderTexture(0, texture);
        blend(additive);
    }

    /** Ordinary blending or additive light; test depth without writing (translucency over the world). */
    public static void blend(boolean additive) {
        RenderSystem.enableBlend();
        if (additive) {
            RenderSystem.blendFunc(GlStateManager.SourceFactor.SRC_ALPHA, GlStateManager.DestFactor.ONE);
        } else {
            RenderSystem.defaultBlendFunc();
        }
        RenderSystem.depthMask(false);
        RenderSystem.enableDepthTest();
        RenderSystem.disableCull();
    }

    /** Camera-facing quad centred at camera-relative (x, y, z), half-size half, rotated by rot. */
    public static void billboard(BufferBuilder b, Vector3f left, Vector3f up, float x, float y, float z, float half, float rot,
                                 float u0, float v0, float u1, float v1, float r, float g, float bl, float a) {
        float c = Mth.cos(rot) * half, s = Mth.sin(rot) * half;
        // Quad axes in the screen plane, rotated by rot.
        float ax = left.x() * c + up.x() * s, ay = left.y() * c + up.y() * s, az = left.z() * c + up.z() * s;
        float bx = -left.x() * s + up.x() * c, by = -left.y() * s + up.y() * c, bz = -left.z() * s + up.z() * c;
        b.addVertex(x - ax - bx, y - ay - by, z - az - bz).setUv(u1, v1).setColor(r, g, bl, a);
        b.addVertex(x - ax + bx, y - ay + by, z - az + bz).setUv(u1, v0).setColor(r, g, bl, a);
        b.addVertex(x + ax + bx, y + ay + by, z + az + bz).setUv(u0, v0).setColor(r, g, bl, a);
        b.addVertex(x + ax - bx, y + ay - by, z + az - bz).setUv(u0, v1).setColor(r, g, bl, a);
    }

    public static void draw(BufferBuilder b) {
        MeshData mesh = b.build();
        if (mesh != null) BufferUploader.drawWithShader(mesh);
    }
}
