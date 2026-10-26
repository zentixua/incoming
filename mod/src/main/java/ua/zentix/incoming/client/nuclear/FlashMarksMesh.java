package ua.zentix.incoming.client.nuclear;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.client.event.AddSectionGeometryEvent;
import net.neoforged.neoforge.client.model.IQuadTransformer;
import net.neoforged.neoforge.client.model.data.ModelData;
import net.neoforged.neoforge.client.model.lighting.QuadLighter;
import org.jetbrains.annotations.Nullable;
import ua.zentix.incoming.nuclear.flash.FlashChar;
import ua.zentix.incoming.nuclear.flash.FlashMarks;
import ua.zentix.incoming.nuclear.flash.FlashShadows;

import java.util.ArrayList;
import java.util.List;

/**
 * Flash marks in the section mesh ({@link AddSectionGeometryEvent}, no mixins): draw each marked face again using
 * the same block-model quads, in the same layer, after all section blocks, at the same vertex positions and
 * with darker vertex colors — at equal depth the later draw wins, making the face look charred, with the block texture beneath
 * the char. Vertex light uses the common NeoForge pipeline ({@link QuadLighter}: smooth light and directional shading as for blocks).
 * <p>
 * Char grade at face corners is averaged over neighboring coplanar faces (clean faces that could char count as zero;
 * wall edges do not): char blends into clean areas in patches rather than cells. The silhouette uses strips of 16×16 cells
 * (texture pixels) over the charred face: where a body blocked the ball, char is weaker — {@code grade · (1 − shadow/3)}.
 * A mark is drawn only while the same block (hash) occupies the location and the face is exposed.
 */
public final class FlashMarksMesh {
    /** How much full char darkens the color: R, G, B channels (slightly warmer toward brown). */
    private static final float DARK_R = 0.86f, DARK_G = 0.89f, DARK_B = 0.92f;
    /** Silhouette-cell offset in front of the face, blocks: the depth gap is visible out to about 50 blocks. */
    private static final float LIFT = 1 / 256f;

    private FlashMarksMesh() {}

    /** Section rebuild: snapshot its marks now, render on the build thread. */
    public static void onSection(AddSectionGeometryEvent e) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || e.getLevel() != mc.level) return;
        BlockPos o = e.getSectionOrigin();
        int cx = o.getX() >> 4, cz = o.getZ() >> 4;
        long now = mc.level.getGameTime();
        List<FlashMarksClient.Slice> slices = FlashMarksClient.section(ChunkPos.asLong(cx, cz), o.getY() >> 4, now);
        if (slices == null) return;
        Section s = Section.of(slices, cx, o.getY(), cz, now);
        if (s != null) e.addRenderer(s::render);
    }

    /** One detonation's contribution to a face: grade and silhouette mask (null — no silhouette). */
    private record Part(int level, @Nullable long[] mask) {}

    /** A section face with all contributions. */
    private static final class Face {
        final int key, hash;
        final List<Part> parts = new ArrayList<>(1);

        Face(int key, int hash) {
            this.key = key;
            this.hash = hash;
        }

        boolean masked() {
            for (Part p : parts) if (p.mask != null) return true;
            return false;
        }

        /** Face grade without silhouette (for corners and neighbors). */
        int level() {
            int l = 0;
            for (Part p : parts) l = Math.max(l, p.level);
            return l;
        }

        /** Cell char share: maximum across detonations, with silhouette shadow. */
        double cell(int u, int v) {
            double out = 0;
            for (Part p : parts) {
                int shade = p.mask == null ? 0 : (int) (p.mask[(2 * (v * FlashMarks.CELLS + u)) >> 6] >>> ((2 * (v * FlashMarks.CELLS + u)) & 63)) & 3;
                out = Math.max(out, p.level * (1 - shade / 3.0));
            }
            return out;
        }
    }

    /** Section mark snapshot for the build thread. */
    private record Section(int cx, int sy, int cz, List<Face> faces, Neighbours neighbours) {
        /** @param slices section marks by visible layer ({@link FlashMarksClient#section}) */
        @Nullable
        static Section of(List<FlashMarksClient.Slice> slices, int cx, int y0, int cz, long now) {
            Int2ObjectOpenHashMap<Face> faces = new Int2ObjectOpenHashMap<>();
            for (FlashMarksClient.Slice l : slices) {
                // a detonation silhouette replaces that detonation's own char on this face
                Int2ObjectOpenHashMap<long[]> masks = new Int2ObjectOpenHashMap<>();
                for (int k = 0; k < l.shadows().length; k++) {
                    int m = l.shadows()[k];
                    long[] mask = java.util.Arrays.copyOfRange(l.masks(), k * FlashMarks.MASK_LONGS, (k + 1) * FlashMarks.MASK_LONGS);
                    masks.put(m & FlashChar.KEY_MASK, mask);
                    add(faces, m, mask);
                }
                for (int m : l.chars()) {
                    if (masks.containsKey(m & FlashChar.KEY_MASK)) continue;
                    add(faces, m, null);
                }
            }
            if (faces.isEmpty()) return null;
            return new Section(cx, y0 >> 4, cz, new ArrayList<>(faces.values()), Neighbours.around(cx, cz, now));
        }

        private static void add(Int2ObjectOpenHashMap<Face> faces, int mark, @Nullable long[] mask) {
            Face f = faces.computeIfAbsent(mark & FlashChar.KEY_MASK, k -> new Face(k, FlashChar.hashOf(mark)));
            f.parts.add(new Part(FlashChar.level(mark), mask));
        }

        void render(AddSectionGeometryEvent.SectionRenderingContext ctx) {
            BlockAndTintGetter region = ctx.getRegion();
            Minecraft mc = Minecraft.getInstance();
            RandomSource rand = RandomSource.create();
            PoseStack pose = ctx.getPoseStack();
            BlockPos.MutableBlockPos front = new BlockPos.MutableBlockPos();
            for (Face f : faces) {
                BlockPos pos = new BlockPos(cx << 4 | FlashChar.lx(f.key), FlashChar.y(f.key), cz << 4 | FlashChar.lz(f.key));
                Direction dir = FlashChar.face(f.key);
                BlockState state = region.getBlockState(pos);
                if (state.getRenderShape() != RenderShape.MODEL || FlashChar.hash(state.getBlock()) != f.hash || FlashChar.material(state) == FlashChar.NONE) continue;
                front.setWithOffset(pos, dir);
                if (!Block.shouldRenderFace(state, region, pos, dir, front)) continue;
                double[] corners = corners(region, pos, dir, f.level());
                BakedModel model = mc.getBlockRenderer().getBlockModel(state);
                ModelData data = model.getModelData(region, pos, state, region.getModelData(pos));
                long seed = state.getSeed(pos);
                rand.setSeed(seed);
                for (RenderType type : model.getRenderTypes(state, rand, data)) {
                    rand.setSeed(seed);
                    List<BakedQuad> quads = model.getQuads(state, dir, rand, data, type);
                    if (quads.isEmpty()) continue;
                    boolean smooth = Minecraft.useAmbientOcclusion() && switch (model.useAmbientOcclusion(state, data, type)) {
                        case TRUE -> true;
                        case DEFAULT -> state.getLightEmission(region, pos) == 0;
                        case FALSE -> false;
                    };
                    QuadLighter lighter = ctx.getQuadLighter(smooth);
                    lighter.setup(region, pos, state);
                    VertexConsumer out = ctx.getOrCreateChunkBuffer(type);
                    pose.pushPose();
                    pose.translate(pos.getX() & 15, pos.getY() & 15, pos.getZ() & 15);
                    for (BakedQuad q : quads) emit(q, dir, f, corners, lighter, out, pose.last());
                    pose.popPose();
                    lighter.reset();
                }
            }
        }

        /**
         * Char grade at face corners: {@code [u0v0, u1v0, u0v1, u1v1]} along cell axes ({@link FlashShadows#axes}) —
         * average of this face and available coplanar neighbors (marked, or clean but charrable and exposed).
         */
        private double[] corners(BlockAndTintGetter region, BlockPos pos, Direction dir, int own) {
            int[] ax = FlashShadows.axes(dir);
            double[] out = new double[4];
            for (int c = 0; c < 4; c++) {
                int du = (c & 1) == 0 ? -1 : 1, dv = (c & 2) == 0 ? -1 : 1;
                double sum = own;
                int n = 1;
                for (int k = 1; k <= 3; k++) {
                    int[] off = new int[3];
                    if ((k & 1) != 0) off[ax[0]] = du;
                    if ((k & 2) != 0) off[ax[1]] = dv;
                    int l = neighbour(region, pos.offset(off[0], off[1], off[2]), dir);
                    if (l < 0) continue;
                    sum += l;
                    n++;
                }
                out[c] = sum / n;
            }
            return out;
        }

        /** Grade of an adjacent face on the same side: marked — its grade; clean, charrable and exposed — 0; otherwise −1. */
        private int neighbour(BlockAndTintGetter region, BlockPos at, Direction dir) {
            int l = neighbours.level(at, dir);
            if (l > 0) return l;
            BlockState st = region.getBlockState(at);
            if (FlashChar.material(st) == FlashChar.NONE) return -1;
            BlockPos ahead = at.relative(dir);
            return Block.shouldRenderFace(st, region, at, dir, ahead) ? 0 : -1;
        }
    }

    /** Face grades in and around the section's chunk (for corners at chunk edges): snapshot of {@link FlashMarksClient} maps. */
    private record Neighbours(int cx, int cz, Int2IntOpenHashMap[] maps) {
        static Neighbours around(int cx, int cz, long now) {
            Int2IntOpenHashMap[] maps = new Int2IntOpenHashMap[9];
            for (int dz = -1; dz <= 1; dz++) {
                for (int dx = -1; dx <= 1; dx++) maps[(dz + 1) * 3 + dx + 1] = FlashMarksClient.levels(ChunkPos.asLong(cx + dx, cz + dz), now);
            }
            return new Neighbours(cx, cz, maps);
        }

        int level(BlockPos at, Direction dir) {
            int dx = (at.getX() >> 4) - cx, dz = (at.getZ() >> 4) - cz;
            if (dx < -1 || dx > 1 || dz < -1 || dz > 1) return 0;
            Int2IntOpenHashMap m = maps[(dz + 1) * 3 + dx + 1];
            return m == null ? 0 : m.get(FlashChar.key(at.getX() & 15, at.getY(), at.getZ() & 15, dir));
        }
    }

    // ---------------------------------------------------------------- quads

    /**
     * Draw the face again: whole, darker at corners, with the same vertex positions as the model (at equal depth the later draw
     * wins). Silhouette on top: cells in the body shadow are lighter, drawn as strips {@link #LIFT} in front of the face — strips at the same
     * positions would fight the model face for depth (depth interpolation across different triangles differs in its last
     * bits), producing bright lines. Far away, where depth precision cannot resolve this gap, the silhouette merges into char.
     */
    private static void emit(BakedQuad q, Direction dir, Face f, double[] corners, QuadLighter lighter, VertexConsumer out, PoseStack.Pose pose) {
        int[] v = q.getVertices();
        int[] ax = FlashShadows.axes(dir);
        int stride = IQuadTransformer.STRIDE;
        int[] copy = v.clone();
        for (int i = 0; i < 4; i++) {
            double value = bilerp(corners, pos(v, i, ax[0]), pos(v, i, ax[1]));
            copy[i * stride + IQuadTransformer.COLOR] = darken(v[i * stride + IQuadTransformer.COLOR], value);
        }
        lighter.process(out, pose, new BakedQuad(copy, q.getTintIndex(), q.getDirection(), q.getSprite(), q.isShade(), q.hasAmbientOcclusion()), OverlayTexture.NO_OVERLAY);
        if (!f.masked()) return;
        // quad corners along cell axes: vertex role is (u greater, v greater)
        float u0 = Float.MAX_VALUE, u1 = -Float.MAX_VALUE, v0 = Float.MAX_VALUE, v1 = -Float.MAX_VALUE;
        for (int i = 0; i < 4; i++) {
            float pu = pos(v, i, ax[0]), pv = pos(v, i, ax[1]);
            u0 = Math.min(u0, pu);
            u1 = Math.max(u1, pu);
            v0 = Math.min(v0, pv);
            v1 = Math.max(v1, pv);
        }
        int[] role = new int[4];
        int[] at = {-1, -1, -1, -1};
        for (int i = 0; i < 4; i++) {
            boolean hu = pos(v, i, ax[0]) > (u0 + u1) / 2, hv = pos(v, i, ax[1]) > (v0 + v1) / 2;
            role[i] = (hu ? 1 : 0) | (hv ? 2 : 0);
            at[role[i]] = i;
        }
        if (at[0] < 0 || at[1] < 0 || at[2] < 0 || at[3] < 0 || u1 - u0 < 1e-4 || v1 - v0 < 1e-4) return;
        int normal = dir.getAxis().ordinal();
        float lift = LIFT * dir.getAxisDirection().getStep();
        // strips of cells with equal shadow: cell (u, v) is [u/16, (u+1)/16] × [v/16, (v+1)/16] blocks
        int n = FlashMarks.CELLS;
        for (int cv = 0; cv < n; cv++) {
            float sv0 = Math.max(v0, cv / (float) n), sv1 = Math.min(v1, (cv + 1) / (float) n);
            if (sv1 <= sv0) continue;
            int cu = 0;
            while (cu < n) {
                double share = f.cell(cu, cv) / Math.max(1, f.level());
                int end = cu + 1;
                while (end < n && f.cell(end, cv) / Math.max(1, f.level()) == share) end++;
                float su0 = Math.max(u0, cu / (float) n), su1 = Math.min(u1, end / (float) n);
                cu = end;
                // an unshadowed cell is already dark — the face itself draws it
                if (su1 <= su0 || share >= 1) continue;
                int[] sub = v.clone();
                for (int i = 0; i < 4; i++) {
                    float pu = (role[i] & 1) != 0 ? su1 : su0, pv = (role[i] & 2) != 0 ? sv1 : sv0;
                    float s = (pu - u0) / (u1 - u0), t = (pv - v0) / (v1 - v0);
                    int o = i * stride;
                    setPos(sub, i, ax[0], pu);
                    setPos(sub, i, ax[1], pv);
                    setPos(sub, i, normal, pos(v, i, normal) + lift);
                    // texture from the original quad corners
                    for (int c = 0; c < 2; c++) {
                        float a = uv(v, at[0], c), b = uv(v, at[1], c), cc = uv(v, at[2], c), dd = uv(v, at[3], c);
                        float val = (a * (1 - s) + b * s) * (1 - t) + (cc * (1 - s) + dd * s) * t;
                        sub[o + IQuadTransformer.UV0 + c] = Float.floatToRawIntBits(val);
                    }
                    int colour = v[at[0] * stride + IQuadTransformer.COLOR];
                    sub[o + IQuadTransformer.COLOR] = darken(colour, bilerp(corners, pu, pv) * share);
                }
                lighter.process(out, pose, new BakedQuad(sub, q.getTintIndex(), q.getDirection(), q.getSprite(), q.isShade(), q.hasAmbientOcclusion()),
                        OverlayTexture.NO_OVERLAY);
            }
        }
    }

    private static float pos(int[] v, int vertex, int axis) {
        return Float.intBitsToFloat(v[vertex * IQuadTransformer.STRIDE + IQuadTransformer.POSITION + axis]);
    }

    private static void setPos(int[] v, int vertex, int axis, float value) {
        v[vertex * IQuadTransformer.STRIDE + IQuadTransformer.POSITION + axis] = Float.floatToRawIntBits(value);
    }

    private static float uv(int[] v, int vertex, int c) {
        return Float.intBitsToFloat(v[vertex * IQuadTransformer.STRIDE + IQuadTransformer.UV0 + c]);
    }

    /** Grade at a face point from corners {@code [u0v0, u1v0, u0v1, u1v1]}. */
    private static double bilerp(double[] c, float u, float v) {
        double s = Math.clamp(u, 0, 1), t = Math.clamp(v, 0, 1);
        return (c[0] * (1 - s) + c[1] * s) * (1 - t) + (c[2] * (1 - s) + c[3] * s) * t;
    }

    /** Vertex color (ABGR) darkened by char grade {@code level} out of {@link FlashChar#LEVELS}. */
    static int darken(int abgr, double level) {
        double f = Math.clamp(level / FlashChar.LEVELS, 0, 1);
        if (f <= 0) return abgr;
        f = Math.pow(f, 0.9);
        int r = abgr & 0xFF, g = abgr >> 8 & 0xFF, b = abgr >> 16 & 0xFF;
        r = (int) Math.round(r * (1 - DARK_R * f));
        g = (int) Math.round(g * (1 - DARK_G * f));
        b = (int) Math.round(b * (1 - DARK_B * f));
        return abgr & 0xFF000000 | b << 16 | g << 8 | r;
    }
}
