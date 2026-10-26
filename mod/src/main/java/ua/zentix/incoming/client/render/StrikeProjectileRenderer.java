package ua.zentix.incoming.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import ua.zentix.incoming.client.far.FarView;
import ua.zentix.incoming.client.fx.Exhaust;
import ua.zentix.incoming.entity.BunkerBusterEntity;
import ua.zentix.incoming.entity.StrikeProjectile;

/**
 * Projectile: {@link WeaponModels} model rotated by heading and pitch, plus roll in turns. Position arrives each
 * tick and interpolates smoothly between ticks. Engine jet behind nozzle ({@link PlumeRenderer}); its light
 * (glare, veil, and core from render-distance edge) belongs to {@code client.far.FarFlightView}.
 */
public class StrikeProjectileRenderer<T extends StrikeProjectile> extends EntityRenderer<T> {
    private final WeaponModels.Look look;
    /** Frame pose: renderer draws entities one by one on the render thread. */
    private final ProjectilePose state = new ProjectilePose();
    private final WeaponModels.Buffers parts = new WeaponModels.Buffers();

    public StrikeProjectileRenderer(EntityRendererProvider.Context ctx, WeaponModels.Look look) {
        super(ctx);
        this.look = look;
        this.shadowRadius = 0;
    }

    @Override
    public void render(T entity, float entityYaw, float partialTick, PoseStack pose, MultiBufferSource buffers, int packedLight) {
        if (!entity.isActive() || entity instanceof BunkerBusterEntity b && b.isDrilling()) return;
        Quaternionf rotation = state.set(entity, partialTick).rotation(new Quaternionf());
        pose.pushPose();
        pose.mulPose(rotation);
        look.render(state, pose, parts.into(buffers, packedLight));
        Exhaust.Plume plume = Exhaust.plume(entity, partialTick);
        if (plume != null) {
            // From 0.8 render distance the same plume's distant light replaces the jet (FarFlightView): by the edge, where
            // Minecraft and shader pack fog eat the entity, that light already represents the plume.
            Vec3 at = entity.getPosition(partialTick);
            double world = Minecraft.getInstance().options.getEffectiveRenderDistance() * 16.0;
            float near = (float) (1 - FarView.farShare(at.distanceTo(entityRenderDispatcher.camera.getPosition()), world));
            if (near > 0) PlumeRenderer.render(plume.dimmed(near), entity, rotation, at);
        }
        // Hot booster nozzle: beneath plume, visible during thrust decay as fire fades; no distant replacement (nozzle spans
        // fractions of a pixel), so full strength as on the separated booster; otherwise it would flare on separation near render edge.
        Exhaust.Hot hot = Exhaust.hot(entity, partialTick);
        if (hot != null) {
            Vec3 at = entity.getPosition(partialTick);
            Vector3f n = new Vector3f((float) hot.at().x(), (float) hot.at().y(), (float) hot.at().z()).rotate(rotation);
            PlumeRenderer.glow(hot.shine(), entity, rotation, at.add(n.x, n.y, n.z), 1);
        }
        pose.popPose();
        super.render(entity, entityYaw, partialTick, pose, buffers, packedLight);
    }

    @Override
    public boolean shouldRender(T entity, Frustum frustum, double x, double y, double z) {
        // Model exceeds entity bounds: test expanded volume; beyond its range the distant visual owns the body
        // (FarView.entityReach: beyond frame's far plane the model would be clipped).
        return entity.isActive() && frustum.isVisible(entity.getBoundingBox().inflate(24))
                && entity.position().distanceToSqr(x, y, z) < Math.pow(FarView.entityReach(FarView.depthFar()), 2);
    }

    @Override
    public ResourceLocation getTextureLocation(T entity) {
        return InventoryMenu.BLOCK_ATLAS;
    }
}
