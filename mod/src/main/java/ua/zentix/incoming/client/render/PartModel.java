package ua.zentix.incoming.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.client.RenderTypeHelper;
import net.neoforged.neoforge.client.model.data.ModelData;
import org.joml.Matrix4f;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.ToIntFunction;

/**
 * Model built from blocks: each part is a block in cube [0, 1]³, translated and scaled to its dimensions (blocks).
 * Model +Z is nose, +Y up, +X port side. World light at entity position, as for its other parts.
 * All part faces combine into {@link QuadMesh} by render type (again after model reload) and draw as
 * {@code renderSingleBlock} would: same entity render type for each block render type, same block tint,
 * without processing one block at a time every frame.
 */
public final class PartModel {
    private final List<Part> parts;
    private final QuadMesh.Cached<Map<RenderType, QuadMesh>> layers = new QuadMesh.Cached<>(this::compile);

    private PartModel(List<Part> parts) {
        this.parts = List.copyOf(parts);
    }

    public static Builder builder() {
        return new Builder();
    }

    public void render(PoseStack pose, MultiBufferSource buffers, int packedLight) {
        layers.get().forEach((type, mesh) ->
                mesh.draw(pose.last(), buffers.getBuffer(type), QuadMesh.white(1), packedLight, OverlayTexture.NO_OVERLAY));
    }

    /**
     * Block faces as used by {@code renderSingleBlock}: all model render types (entity render type for each:
     * {@link RenderTypeHelper#getEntityRenderType}), each side and general faces with seed 42, tint by {@code tintIndex}.
     */
    private Map<RenderType, QuadMesh> compile() {
        Minecraft mc = Minecraft.getInstance();
        Map<RenderType, QuadMesh.Builder> builders = new LinkedHashMap<>();
        RandomSource random = RandomSource.create();
        for (Part p : parts) {
            BakedModel model = mc.getBlockRenderer().getBlockModel(p.state);
            int color = mc.getBlockColors().getColor(p.state, null, null, 0);
            ToIntFunction<BakedQuad> tint = q -> q.isTinted() ? color : 0xFFFFFF;
            Matrix4f transform = new Matrix4f().translation(p.tx, p.ty, p.tz).scale(p.sx, p.sy, p.sz);
            random.setSeed(42);
            for (RenderType layer : model.getRenderTypes(p.state, random, ModelData.EMPTY)) {
                QuadMesh.Builder b = builders.computeIfAbsent(RenderTypeHelper.getEntityRenderType(layer, false), t -> QuadMesh.builder());
                for (Direction side : Direction.values()) {
                    random.setSeed(42);
                    b.add(model.getQuads(p.state, side, random, ModelData.EMPTY, layer), transform, tint);
                }
                random.setSeed(42);
                b.add(model.getQuads(p.state, null, random, ModelData.EMPTY, layer), transform, tint);
            }
        }
        Map<RenderType, QuadMesh> meshes = new LinkedHashMap<>();
        builders.forEach((type, b) -> meshes.put(type, b.build()));
        return meshes;
    }

    private record Part(BlockState state, float tx, float ty, float tz, float sx, float sy, float sz) {}

    public static final class Builder {
        private final List<Part> parts = new ArrayList<>();

        /** Part: block, corner offset and dimensions. */
        public Builder block(Block block, float tx, float ty, float tz, float sx, float sy, float sz) {
            parts.add(new Part(block.defaultBlockState(), tx, ty, tz, sx, sy, sz));
            return this;
        }

        public PartModel build() {
            return new PartModel(parts);
        }
    }
}
