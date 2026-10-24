package ua.zentix.incoming.client.fx.layer;

import com.mojang.blaze3d.platform.GlStateManager;
import java.nio.FloatBuffer;
import java.util.Arrays;
import org.lwjgl.system.MemoryUtil;

/**
 * Individually faint, broad Gaussians of one source, integrated on a sorted angular mesh.
 * Each five-texel record carries centre/sigmaH; sigmaV/extinction/projectedSigma/card;
 * material RGB/coverage; atlas origin/frame mix/reserved; reserved/shade/mirror/reserved.
 * The vertex shader applies the same finite-ray transport, photo and six-way lighting as individual clubs.
 * The mesh is a sampling approximation: SmokeFillTest includes its triangle interpolation in the density check.
 */
public final class GaussianFields {
    public static final int COLS = 16, ROWS = 32;
    /** Five perceptual contrast thresholds: a broad individual Gaussian is still low frequency. */
    public static final double MAX_ALPHA = 5 * ua.zentix.incoming.client.far.Sight.THRESHOLD;
    private static float[] data = new float[20 * 128];
    private static int count;
    private static FloatBuffer transfer;
    private GaussianFields() {}
    public static void begin() {
        count = 0;
    }
    public static int add(float[] gaussian) {
        if (count == 65535)
            return -1;
        if (20 * (count + 1) > data.length)
            data = Arrays.copyOf(data, 2 * data.length);
        int id = count++;
        System.arraycopy(gaussian, 0, data, 20 * id, 20);
        return id;
    }
    static int rows() {
        return (5 * count + 255) / 256;
    }
    static void upload(int texture, int row) {
        int h = rows();
        if (h == 0)
            return;
        GlStateManager._bindTexture(texture);
        int size = 256 * h * 4;
        if (transfer == null || transfer.capacity() < size) {
            if (transfer != null)
                MemoryUtil.memFree(transfer);
            transfer = MemoryUtil.memAllocFloat(size);
        }
        transfer.clear();
        transfer.put(data, 0, 20 * count);
        while (transfer.position() < size) transfer.put(0);
        transfer.flip();
        PixelUnpack.upload(row, 256, h, transfer);
    }
}
