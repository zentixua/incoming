package ua.zentix.incoming.client.render;

import net.minecraft.client.multiplayer.ClientLevel;
import org.jetbrains.annotations.Nullable;
import ua.zentix.incoming.client.ClientWeaponSpec;
import ua.zentix.incoming.client.far.FarView;
import ua.zentix.incoming.client.far.Sight;
import ua.zentix.incoming.util.Air;
import ua.zentix.incoming.client.fx.plume.Flame;
import ua.zentix.incoming.client.fx.plume.FlameModel;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.inventory.InventoryMenu;
import org.joml.Quaternionf;
import ua.zentix.incoming.client.fx.Exhaust;
import ua.zentix.incoming.defense.InterceptorSpec;
import ua.zentix.incoming.entity.InterceptorEntity;

/**
 * SAM missile: model ({@link WeaponModels.Mesh#INTERCEPTOR_BODY}, nose along +Z) rotated by heading and pitch,
 * and engine plume behind the nozzle ({@link PlumeRenderer}): long and bright during boost, shorter in cruise mode.
 */
public class InterceptorRenderer extends EntityRenderer<InterceptorEntity> {
    public InterceptorRenderer(EntityRendererProvider.Context ctx) {
        super(ctx);
        this.shadowRadius = 0;
    }

    @Override
    public void render(InterceptorEntity e, float entityYaw, float partialTick, PoseStack pose, MultiBufferSource buffers, int packedLight) {
        float yaw = Mth.rotLerp(partialTick, e.yRotO, e.getYRot()), pitch = Mth.lerp(partialTick, e.xRotO, e.getXRot());
        Quaternionf rotation = new Quaternionf().rotationYXZ(-yaw * Mth.DEG_TO_RAD, pitch * Mth.DEG_TO_RAD, 0);
        pose.pushPose();
        pose.mulPose(rotation);
        WeaponModels.Mesh.INTERCEPTOR_BODY.draw(pose, buffers, packedLight);
        Exhaust.Plume plume = plume(e, partialTick);
        if (plume != null) PlumeRenderer.render(plume, e, rotation, e.getPosition(partialTick));
        pose.popPose();
        super.render(e, entityYaw, partialTick, pose, buffers, packedLight);
    }

    /**
     * Plume: nozzle exit at model tail; boost and cruise use their respective motor modes
     * ({@link ClientWeaponSpec#SAM_BOOST}, {@link ClientWeaponSpec#SAM_SUSTAIN}) with the plume law; null if invisible.
     */
    @Nullable
    static Exhaust.Plume plume(InterceptorEntity e, float partial) {
        boolean boost = e.age() + partial <= InterceptorSpec.SAM.boostTicks();
        Flame f = FlameModel.at(boost ? ClientWeaponSpec.SAM_BOOST : ClientWeaponSpec.SAM_SUSTAIN, e.getY() - Air.SEA);
        if (f == null || !(e.level() instanceof ClientLevel level)) return null;
        return new Exhaust.Plume(0, (float) -InterceptorSpec.SAM.noseLength(), f, 1);
    }

    @Override
    public boolean shouldRender(InterceptorEntity e, Frustum frustum, double x, double y, double z) {
        // Model and plume exceed entity bounds.
        return frustum.isVisible(e.getBoundingBox().inflate(8));
    }

    @Override
    public ResourceLocation getTextureLocation(InterceptorEntity e) {
        return InventoryMenu.BLOCK_ATLAS;
    }
}
