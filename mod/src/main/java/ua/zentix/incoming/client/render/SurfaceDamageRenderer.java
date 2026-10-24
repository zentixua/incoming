package ua.zentix.incoming.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.DisplayRenderer;
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
public final class SurfaceDamageRenderer extends DisplayRenderer.BlockDisplayRenderer {
    public SurfaceDamageRenderer(EntityRendererProvider.Context context) { super(context); }

    @Override
    public void renderInner(Display.BlockDisplay entity, Display.BlockDisplay.BlockRenderState state,
                            PoseStack pose, MultiBufferSource buffers, int light, float partialTick) {
        // DisplayRenderer applies the native synced transformation before drawing this composite model.
        BlockState material = state.blockState();
        pose.pushPose();
        pose.translate(0.5, 0, 0.5);
        int hash = entity.blockPosition().hashCode();
        pose.mulPose(new Quaternionf().rotateY((hash & 3) * (float) (Math.PI / 2)));
        pose.translate(-0.5, 0, -0.5);
        BlockState scorch = Blocks.BLACKSTONE.defaultBlockState();
        BlockState aggregate = Blocks.ANDESITE.defaultBlockState();
        // Overlapping lobes give the scorched edge and exposed face an uneven contour, not a block-grid square.
        piece(pose, buffers, light, scorch, .10f, .004f, .27f, .55f, .012f, .33f, -.25f);
        piece(pose, buffers, light, scorch, .38f, .005f, .12f, .35f, .012f, .48f, .22f);
        piece(pose, buffers, light, scorch, .37f, .006f, .52f, .45f, .012f, .29f, -.19f);
        piece(pose, buffers, light, scorch, .10f, .005f, .54f, .33f, .012f, .24f, .28f);
        piece(pose, buffers, light, aggregate, .17f, .022f, .31f, .42f, .018f, .26f, -.20f);
        piece(pose, buffers, light, aggregate, .44f, .025f, .23f, .23f, .018f, .40f, .18f);
        piece(pose, buffers, light, aggregate, .32f, .022f, .57f, .38f, .018f, .19f, -.14f);
        piece(pose, buffers, light, Blocks.LIGHT_GRAY_CONCRETE.defaultBlockState(), .28f, .044f, .37f, .25f, .024f, .22f, -.15f);
        piece(pose, buffers, light, material, .20f, .065f, .32f, .16f, .045f, .12f, .27f);
        piece(pose, buffers, light, material, .57f, .06f, .47f, .18f, .09f, .12f, -.35f);
        piece(pose, buffers, light, aggregate, .37f, .052f, .64f, .14f, .04f, .10f, .31f);
        piece(pose, buffers, light, Blocks.LIGHT_GRAY_CONCRETE.defaultBlockState(), .15f, .024f, .66f, .08f, .035f, .07f, .4f);
        pose.popPose();
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
