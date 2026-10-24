package ua.zentix.incoming.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.inventory.InventoryMenu;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import net.minecraft.world.phys.Vec3;
import ua.zentix.incoming.client.fx.plume.NozzleGlow;
import ua.zentix.incoming.client.ClientWeaponSpec;
import ua.zentix.incoming.entity.SpentBoosterEntity;

/** Отработавший ускоритель кувыркается в падении; на земле лежит на боку. */
public class SpentBoosterRenderer extends EntityRenderer<SpentBoosterEntity> {
    public SpentBoosterRenderer(EntityRendererProvider.Context ctx) {
        super(ctx);
        this.shadowRadius = 0.3f;
    }

    @Override
    public void render(SpentBoosterEntity e, float entityYaw, float partialTick, PoseStack pose, MultiBufferSource buffers, int packedLight) {
        ClientWeaponSpec.SpentBooster spent = ClientWeaponSpec.of(e.weapon()).spent();
        float visibility = e.visibility(partialTick);
        if (spent != null && visibility > 0) {
            float t = e.age() + partialTick;
            pose.pushPose();
            pose.translate(0, spent.lift(), 0);
            float tumble = e.landed() ? 90 : e.getXRot() + t * e.spin;
            pose.mulPose(new Quaternionf().rotationYXZ(-e.getYRot() * Mth.DEG_TO_RAD, tumble * Mth.DEG_TO_RAD, 0));
            // сетка ускорителя — в системе снаряда: сдвинуть её середину в начало
            pose.translate(0, spent.dy(), spent.dz());
            spent.mesh().get().draw(pose, buffers.getBuffer(visibility < 1 ? WeaponModels.TRANSLUCENT : WeaponModels.SOLID),
                    packedLight, visibility, false);
            pose.popPose();
            glow(e, spent, tumble, t, visibility);
        }
        this.shadowRadius = 0.3f * visibility;
        super.render(e, entityYaw, partialTick, pose, buffers, packedLight);
    }

    /**
     * Горячее сопло: стенки остывают с конца работы ({@link NozzleGlow#spent}); срез — там же, где у сетки (та же поза).
     */
    private static void glow(SpentBoosterEntity e, ClientWeaponSpec.SpentBooster spent, float tumble, float age, float visibility) {
        NozzleGlow g = ClientWeaponSpec.nozzleGlow(e.weapon());
        ClientWeaponSpec.At at = ClientWeaponSpec.of(e.weapon()).airframe(false).boosterNozzle();
        if (g == null || at == null) return;
        Quaternionf rot = new Quaternionf().rotationYXZ(-e.getYRot() * Mth.DEG_TO_RAD, tumble * Mth.DEG_TO_RAD, 0);
        Vector3f n = new Vector3f((float) at.x(), (float) at.y() + spent.dy(), (float) at.z() + spent.dz()).rotate(rot).add(0, spent.lift(), 0);
        float partial = age - e.age();
        Vec3 nozzle = e.getPosition(partial).add(n.x, n.y, n.z);
        PlumeRenderer.glow(g.spent(age), e, rot, nozzle, visibility);
    }

    @Override
    public ResourceLocation getTextureLocation(SpentBoosterEntity e) {
        return InventoryMenu.BLOCK_ATLAS;
    }
}
