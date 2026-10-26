package ua.zentix.incoming.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import org.joml.Quaternionf;
import ua.zentix.incoming.defense.SamBlock;
import ua.zentix.incoming.defense.SamBlockEntity;

/**
 * SAM: above the block (radar cabin is the block model), four full-size vertical containers
 * ({@link WeaponModels.Mesh#SAM_PACK}), rotated by {@link SamBlock#FACING}.
 */
public class SamRenderer implements BlockEntityRenderer<SamBlockEntity> {
    public SamRenderer(BlockEntityRendererProvider.Context ctx) {}

    @Override
    public void render(SamBlockEntity be, float partialTick, PoseStack pose, MultiBufferSource buffers, int packedLight, int packedOverlay) {
        Level level = be.getLevel();
        if (level == null) return;
        // Cabin is a full opaque block: zero light inside; rack above uses light from above the block.
        int light = LevelRenderer.getLightColor(level, be.getBlockPos().above());
        pose.pushPose();
        pose.translate(0.5, 0, 0.5);
        pose.mulPose(new Quaternionf().rotationY(-be.getBlockState().getValue(SamBlock.FACING).toYRot() * Mth.DEG_TO_RAD));
        WeaponModels.Mesh.SAM_PACK.draw(pose, buffers, light);
        pose.popPose();
    }

    /** Rack exceeds block (4.3 m containers, plate wider than cabin): otherwise culled by the block cube. */
    @Override
    public AABB getRenderBoundingBox(SamBlockEntity be) {
        return new AABB(be.getBlockPos()).inflate(SamBlockEntity.PACK_HALF_WIDTH - 0.5, 0, SamBlockEntity.PACK_HALF_WIDTH - 0.5)
                .expandTowards(0, SamBlockEntity.PACK_TOP - 1, 0);
    }

    /**
     * Rack extends beyond the block's chunk section: section culled (looking up beside rack, slope) while rack top
     * is in frame would hide the entire rack. Render these from a separate list, checking bounds and distance as usual.
     */
    @Override
    public boolean shouldRenderOffScreen(SamBlockEntity be) {
        return true;
    }

    /** Rack visible from afar, like a launcher (block entities default to a 64-block render range). */
    @Override
    public int getViewDistance() {
        return 160;
    }
}
