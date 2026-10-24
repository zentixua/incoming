package ua.zentix.incoming.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Display;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.client.model.data.ModelData;
import org.joml.Quaternionf;

/** Presentation only: fresh pale aggregate inside a scorched edge, with centimetre-sized material chips.
 * Uses ordinary block models and entity buffers so world lighting and shader packs still apply. */
public final class SurfaceDamageRenderer extends EntityRenderer<Display.BlockDisplay> {
    public SurfaceDamageRenderer(EntityRendererProvider.Context context) { super(context); }

    @Override
    public void render(Display.BlockDisplay entity, float yaw, float partialTick, PoseStack pose,
                       MultiBufferSource buffers, int light) {
        if (entity.blockRenderState() == null) return;
        BlockState material = entity.blockRenderState().blockState();
        pose.pushPose();
        pose.translate(0.5, 0, 0.5);
        int hash = Long.hashCode(entity.blockPosition().asLong());
        pose.mulPose(new Quaternionf().rotateY((hash & 3) * (float) (Math.PI / 2)));
        pose.translate(-0.5, 0, -0.5);
        piece(pose, buffers, light, Blocks.BLACKSTONE.defaultBlockState(), .04f, .004f, .09f, .91f, .012f, .82f, -.08f);
        piece(pose, buffers, light, Blocks.ANDESITE.defaultBlockState(), .12f, .019f, .18f, .69f, .018f, .62f, .12f);
        piece(pose, buffers, light, Blocks.LIGHT_GRAY_CONCRETE.defaultBlockState(), .23f, .038f, .29f, .36f, .024f, .31f, -.18f);
        piece(pose, buffers, light, material, .15f, .06f, .21f, .19f, .045f, .14f, .27f);
        piece(pose, buffers, light, material, .63f, .05f, .46f, .21f, .065f, .13f, -.35f);
        piece(pose, buffers, light, Blocks.ANDESITE.defaultBlockState(), .34f, .047f, .66f, .17f, .04f, .12f, .31f);
        piece(pose, buffers, light, Blocks.GRAVEL.defaultBlockState(), .71f, .025f, .18f, .11f, .03f, .09f, -.23f);
        piece(pose, buffers, light, Blocks.LIGHT_GRAY_CONCRETE.defaultBlockState(), .08f, .022f, .73f, .09f, .035f, .07f, .4f);
        pose.popPose();
        super.render(entity, yaw, partialTick, pose, buffers, light);
    }

    private static void piece(PoseStack pose, MultiBufferSource buffers, int light, BlockState state,
                              float x, float y, float z, float sx, float sy, float sz, float angle) {
        pose.pushPose();
        pose.translate(x, y, z);
        pose.mulPose(new Quaternionf().rotateY(angle));
        pose.scale(sx, sy, sz);
        Minecraft.getInstance().getBlockRenderer().renderSingleBlock(state, pose, buffers, light,
                OverlayTexture.NO_OVERLAY, ModelData.EMPTY, null);
        pose.popPose();
    }

    @Override public ResourceLocation getTextureLocation(Display.BlockDisplay entity) { return InventoryMenu.BLOCK_ATLAS; }
}
