package ua.zentix.incoming.client.render;

import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.Vec3i;
import net.minecraft.util.FastColor;
import net.neoforged.neoforge.client.model.IQuadTransformer;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.util.Arrays;
import java.util.List;
import java.util.function.Supplier;
import java.util.function.ToIntFunction;

/**
 * Mesh from baked model faces, decoded once: numeric position, UV and normal per vertex, baked light (OBJ
 * {@code glow} material) separately. Frame writes vertices with one
 * {@link VertexConsumer#addVertex(float, float, float, int, float, float, int, int, float, float, float)} call per
 * vertex, as vanilla {@code ModelPart}, rather than decoding {@link BakedQuad} again ({@code putBulkData} copies
 * each vertex through {@code MemoryStack} and allocates vectors for it). Uses entity render-type buffer, so world
 * light, glowing outline, Iris and its shadow pass work as for any entity.
 */
final class QuadMesh {
    /** x y z, u v, normal x y z. */
    private static final int STRIDE = 8;
    private static final int WHITE = 0xFFFFFF;

    private final float[] vertices;
    /** Baked vertex light (packed as world light), or {@code null} if absent on all vertices. */
    private final int[] bakedLight;
    /** Vertex tint (RGB, block tint by {@code tintIndex}), or {@code null} if all white. */
    private final int[] tint;

    private QuadMesh(float[] vertices, int[] bakedLight, int[] tint) {
        this.vertices = vertices;
        this.bakedLight = bakedLight;
        this.tint = tint;
    }

    /**
     * Draw in current coordinate system.
     *
     * @param color ARGB: texture multiplier, alpha is part opacity
     */
    void draw(PoseStack.Pose pose, VertexConsumer vc, int color, int light, int overlay) {
        Matrix4f m = pose.pose();
        Vector3f p = new Vector3f(), n = new Vector3f();
        float[] v = vertices;
        for (int i = 0, k = 0; i < v.length; i += STRIDE, k++) {
            m.transformPosition(v[i], v[i + 1], v[i + 2], p);
            pose.transformNormal(v[i + 5], v[i + 6], v[i + 7], n);
            int l = bakedLight == null ? light : brighter(light, bakedLight[k]);
            int c = tint == null ? color : FastColor.ARGB32.multiply(color, 0xFF000000 | tint[k]);
            vc.addVertex(p.x, p.y, p.z, c, v[i + 3], v[i + 4], overlay, l, n.x, n.y, n.z);
        }
    }

    /**
     * Mesh vertices as is (unrotated, white, brighter of light and baked light) for a GPU buffer uploaded once
     * ({@link FarModels}); {@code null} for empty mesh.
     */
    @Nullable
    MeshData bake(ByteBufferBuilder bytes, int light) {
        BufferBuilder b = new BufferBuilder(bytes, VertexFormat.Mode.QUADS, DefaultVertexFormat.NEW_ENTITY);
        draw(new PoseStack().last(), b, white(1), light, OverlayTexture.NO_OVERLAY);
        return b.build();
    }

    /** Bytes for {@link #bake} vertices. */
    int bakedBytes() {
        return vertices.length / STRIDE * DefaultVertexFormat.NEW_ENTITY.getVertexSize();
    }

    static int white(float alpha) {
        return FastColor.ARGB32.colorFromFloat(alpha, 1, 1, 1);
    }

    /** Vertex light: brighter of world and baked light, block and sky separately (as NeoForge putBulkData). */
    private static int brighter(int a, int b) {
        return Math.max(a & 0xFFFF, b & 0xFFFF) | Math.max(a >>> 16, b >>> 16) << 16;
    }

    static Builder builder() {
        return new Builder();
    }

    static final class Builder {
        private float[] vertices = new float[STRIDE * 256];
        private int[] light = new int[256];
        private int[] tint = new int[256];
        private int count;
        private boolean anyLight, anyTint;

        /** Faces translated and scaled by {@code transform} (normals inverse-transposed, renormalized). */
        Builder add(List<BakedQuad> quads, Matrix4f transform) {
            return add(quads, transform, q -> WHITE);
        }

        /** Same with face tint (RGB), as block tint by {@code tintIndex} in {@code renderSingleBlock}. */
        Builder add(List<BakedQuad> quads, Matrix4f transform, ToIntFunction<BakedQuad> tintOf) {
            Matrix3f normals = transform.normal(new Matrix3f());
            Vector3f p = new Vector3f(), n = new Vector3f();
            for (BakedQuad q : quads) {
                int rgb = tintOf.applyAsInt(q) & WHITE;
                int[] d = q.getVertices();
                Vec3i face = q.getDirection().getNormal();
                for (int o = 0; o < d.length; o += IQuadTransformer.STRIDE) {
                    transform.transformPosition(Float.intBitsToFloat(d[o + IQuadTransformer.POSITION]),
                            Float.intBitsToFloat(d[o + IQuadTransformer.POSITION + 1]),
                            Float.intBitsToFloat(d[o + IQuadTransformer.POSITION + 2]), p);
                    // Vertex normal (smooth OBJ meshes), or face normal if absent, as in putBulkData.
                    int packed = d[o + IQuadTransformer.NORMAL];
                    byte nx = (byte) packed, ny = (byte) (packed >> 8), nz = (byte) (packed >> 16);
                    if (nx != 0 || ny != 0 || nz != 0) n.set(nx / 127f, ny / 127f, nz / 127f);
                    else n.set(face.getX(), face.getY(), face.getZ());
                    normals.transform(n).normalize();
                    int baked = d[o + IQuadTransformer.UV2];
                    vertex(p, Float.intBitsToFloat(d[o + IQuadTransformer.UV0]), Float.intBitsToFloat(d[o + IQuadTransformer.UV0 + 1]), n, baked, rgb);
                }
            }
            return this;
        }

        private void vertex(Vector3f p, float u, float v, Vector3f n, int baked, int rgb) {
            if ((count + 1) * STRIDE > vertices.length) {
                vertices = Arrays.copyOf(vertices, vertices.length * 2);
                light = Arrays.copyOf(light, light.length * 2);
                tint = Arrays.copyOf(tint, tint.length * 2);
            }
            tint[count] = rgb;
            anyTint |= rgb != WHITE;
            int i = count * STRIDE;
            vertices[i] = p.x;
            vertices[i + 1] = p.y;
            vertices[i + 2] = p.z;
            vertices[i + 3] = u;
            vertices[i + 4] = v;
            vertices[i + 5] = n.x;
            vertices[i + 6] = n.y;
            vertices[i + 7] = n.z;
            light[count++] = baked;
            anyLight |= baked != 0;
        }

        QuadMesh build() {
            return new QuadMesh(Arrays.copyOf(vertices, count * STRIDE), anyLight ? Arrays.copyOf(light, count) : null,
                    anyTint ? Arrays.copyOf(tint, count) : null);
        }
    }

    /**
     * Mesh (or meshes) built on first draw and again after each model reload (F3+T, resource pack): faces come from
     * model manager, which is ready only after resource loading.
     */
    static final class Cached<T> {
        private static int generation;

        private final Supplier<T> build;
        private T mesh;
        private int builtAt = -1;

        Cached(Supplier<T> build) {
            this.build = build;
        }

        T get() {
            if (builtAt != generation) {
                mesh = build.get();
                builtAt = generation;
            }
            return mesh;
        }

        /** Models rebaked: all meshes rebuild on next draw. */
        static void invalidateAll() {
            generation++;
        }
    }
}
