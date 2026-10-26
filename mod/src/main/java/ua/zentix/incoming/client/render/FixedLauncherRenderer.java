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
import ua.zentix.incoming.launcher.FixedLauncherBlockEntity;

/**
 * Fixed launcher: turntable above the block and mission weapon rack, shared with the trailer
 * ({@link LauncherRenderer#renderRack}), following rack heading and raised to elevation.
 */
public class FixedLauncherRenderer implements BlockEntityRenderer<FixedLauncherBlockEntity> {
    public FixedLauncherRenderer(BlockEntityRendererProvider.Context ctx) {}

    @Override
    public void render(FixedLauncherBlockEntity be, float partialTick, PoseStack pose, MultiBufferSource buffers, int packedLight, int packedOverlay) {
        Level level = be.getLevel();
        if (level == null) return;
        // Full opaque block: zero light inside; turntable and rack above it use light from above the block.
        int light = LevelRenderer.getLightColor(level, be.getBlockPos().above());
        pose.pushPose();
        pose.translate(0.5, 0, 0.5);
        pose.mulPose(new Quaternionf().rotationY(-be.yaw() * Mth.DEG_TO_RAD));
        LaunchModels.PAD.render(pose, buffers, light);
        if (be.hasRack()) LauncherRenderer.renderRack(be, level.getGameTime(), partialTick, pose, buffers, light);
        pose.popPose();
    }

    /** Rack exceeds the block (missile containers 7 m): otherwise culled by the block cube. */
    @Override
    public AABB getRenderBoundingBox(FixedLauncherBlockEntity be) {
        return new AABB(be.getBlockPos()).inflate(7, 0, 7).expandTowards(0, 7, 0);
    }

    /**
     * Rack extends beyond the block's chunk section: section culled (looking up beside rack, slope) while rack top
     * is in frame would hide the whole rack. Render these from a separate list, checking bounds and distance as usual.
     */
    @Override
    public boolean shouldRenderOffScreen(FixedLauncherBlockEntity be) {
        return true;
    }

    /** Rack visible from afar, like the trailer (block entities default to a 64-block render range). */
    @Override
    public int getViewDistance() {
        return 160;
    }
}
