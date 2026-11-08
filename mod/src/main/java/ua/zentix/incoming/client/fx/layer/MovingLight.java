package ua.zentix.incoming.client.fx.layer;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL30;
import org.lwjgl.system.MemoryUtil;
import ua.zentix.incoming.compat.DynamicLights;

import java.nio.ByteBuffer;
import java.util.Locale;

/**
 * Light carried by entities near the eye ({@link DynamicLights}: a held torch with LambDynamicLights) over the light engine's
 * block light in {@link OpenAir}'s window, as the larger of the two (as that mod lights blocks). A box of {@link #SIZE}³
 * blocks around the eye block: that mod's light reaches 7.75 blocks from its source ({@code DynamicLightingEngine.MAX_RADIUS}),
 * so a light the player carries is whole in it; lights of other entities farther away are left out. The level is asked on a
 * grid of {@link #STEP} blocks (its falloff is smooth, linear in distance) and interpolated in between: {@value #POINTS}³
 * questions per frame instead of one per block, and none while the mod tracks no light. Every frame the previous box is put
 * back to the light engine's light ({@link OpenAir} mirror) and the new one written over it.
 */
final class MovingLight {
    /** Grid step, blocks; grid points per axis; box side, blocks (eye block ± 8). */
    static final int STEP = 2, POINTS = 9, SIZE = (POINTS - 1) * STEP + 1;
    static final float[] GRID = new float[POINTS * POINTS * POINTS];
    private static ByteBuffer upload;
    /** Box lower corner (world blocks) written last frame; {@code had} — a box is in the texture. */
    private static int bx, by, bz;
    private static boolean had;
    /** Debug: sources, questions, highest level, CPU time (ns) and bytes uploaded in the last frame. */
    private static int sources = -1, asked, uploaded;
    private static double highest;
    private static long nanos;

    private MovingLight() {}

    /** Whether a moving light is in the texture this frame. */
    static boolean lit() {
        return had;
    }

    static void reset() {
        had = false;
    }

    /**
     * Overlay for this frame, eye at block (ex, ey, ez); OpenAir's texture bound to {@code GL_TEXTURE_3D}, its sections for this
     * frame uploaded.
     */
    static void overlay(ClientLevel level, int ex, int ey, int ez) {
        long t0 = System.nanoTime();
        asked = uploaded = 0;
        highest = 0;
        sources = DynamicLights.sources();
        boolean on = sources > 0 && DynamicLights.active();
        if (!on && !had) {
            nanos = System.nanoTime() - t0;
            return;
        }
        if (upload == null) upload = MemoryUtil.memAlloc(2 * SIZE * SIZE * SIZE);
        GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, 1);
        // previous box back to the light engine's light (the eye moved, or the light went out)
        if (had) write(bx, by, bz, false);
        had = false;
        if (on) {
            bx = Math.floorDiv(ex - SIZE / 2, STEP) * STEP;
            by = Math.floorDiv(ey - SIZE / 2, STEP) * STEP;
            bz = Math.floorDiv(ez - SIZE / 2, STEP) * STEP;
            BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
            for (int k = 0; k < POINTS; k++) {
                for (int j = 0; j < POINTS; j++) {
                    for (int i = 0; i < POINTS; i++) {
                        double v = DynamicLights.level(pos.set(bx + i * STEP, by + j * STEP, bz + k * STEP));
                        GRID[(k * POINTS + j) * POINTS + i] = (float) v;
                        highest = Math.max(highest, v);
                        asked++;
                    }
                }
            }
            if (highest > 0) {
                write(bx, by, bz, true);
                had = true;
            }
        }
        GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, 4);
        nanos = System.nanoTime() - t0;
    }

    /**
     * Box at (x, y, z) into the texture: the light engine's voxels, with {@code light} the larger of its block light and the
     * moving light. Split where the window wraps (at most two pieces per axis).
     */
    private static void write(int x, int y, int z, boolean light) {
        split(x, y, z, (px, py, pz, w, h, d) -> piece(px, py, pz, w, h, d, x, y, z, light));
    }

    /** A box piece: world corner and size, blocks; inside it the texture does not wrap. */
    interface Piece {
        void at(int x, int y, int z, int w, int h, int d);
    }

    /** Box of {@link #SIZE}³ at world (x, y, z) in pieces where OpenAir's window wraps in its texture (at most two per axis). */
    static void split(int x, int y, int z, Piece out) {
        for (int pz = z; pz < z + SIZE; ) {
            int ez = Math.min(z + SIZE, pz + OpenAir.SIZE_XZ - OpenAir.slot(pz, OpenAir.SIZE_XZ));
            for (int py = y; py < y + SIZE; ) {
                int ey = Math.min(y + SIZE, py + OpenAir.SIZE_Y - OpenAir.slot(py, OpenAir.SIZE_Y));
                for (int px = x; px < x + SIZE; ) {
                    int ex = Math.min(x + SIZE, px + OpenAir.SIZE_XZ - OpenAir.slot(px, OpenAir.SIZE_XZ));
                    out.at(px, py, pz, ex - px, ey - py, ez - pz);
                    px = ex;
                }
                py = ey;
            }
            pz = ez;
        }
    }

    private static void piece(int px, int py, int pz, int w, int h, int d, int x, int y, int z, boolean light) {
        int tx = OpenAir.slot(px, OpenAir.SIZE_XZ), ty = OpenAir.slot(py, OpenAir.SIZE_Y), tz = OpenAir.slot(pz, OpenAir.SIZE_XZ);
        upload.clear();
        int n = 0;
        for (int k = 0; k < d; k++) {
            for (int j = 0; j < h; j++) {
                for (int i = 0; i < w; i++) {
                    int sky = OpenAir.stored(tx + i, ty + j, tz + k, 0), block = OpenAir.stored(tx + i, ty + j, tz + k, 1);
                    if (light) block = Math.max(block, Math.round(17 * at(px + i - x, py + j - y, pz + k - z)));
                    upload.put(n++, (byte) sky);
                    upload.put(n++, (byte) Math.min(block, 255));
                }
            }
        }
        upload.limit(n);
        GL12.glTexSubImage3D(GL12.GL_TEXTURE_3D, 0, tx, ty, tz, w, h, d, GL30.GL_RG, GL11.GL_UNSIGNED_BYTE, upload);
        uploaded += n;
    }

    /** Moving light at box block (i, j, k), trilinear between grid points ({@code GRID}). */
    static float at(int i, int j, int k) {
        int gi = Math.min(i / STEP, POINTS - 2), gj = Math.min(j / STEP, POINTS - 2), gk = Math.min(k / STEP, POINTS - 2);
        float fi = (i - gi * STEP) / (float) STEP, fj = (j - gj * STEP) / (float) STEP, fk = (k - gk * STEP) / (float) STEP;
        float v = 0;
        for (int c = 0; c < 8; c++) {
            int di = c & 1, dj = c >> 1 & 1, dk = c >> 2;
            float w = (di == 0 ? 1 - fi : fi) * (dj == 0 ? 1 - fj : fj) * (dk == 0 ? 1 - fk : fk);
            v += w * GRID[((gk + dk) * POINTS + gj + dj) * POINTS + gi + di];
        }
        return v;
    }

    /** Debug line part: sources, questions, highest level, CPU time and upload. */
    static String describe() {
        if (sources < 0) return "moving light: no LambDynamicLights";
        return String.format(Locale.ROOT, "moving light: sources %d, asked %d, highest %.1f, %.3f ms, uploaded %d B", sources, asked,
                highest, nanos / 1e6, uploaded);
    }
}
