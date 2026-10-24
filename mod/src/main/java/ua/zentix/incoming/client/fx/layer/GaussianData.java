package ua.zentix.incoming.client.fx.layer;

import com.mojang.blaze3d.platform.GlStateManager;
import org.lwjgl.system.MemoryUtil;
import java.nio.FloatBuffer;
import java.util.Arrays;

/** Physical Gaussian descriptors for analytical near-field particle impostors; two texels per Gaussian. */
public final class GaussianData {
    private static final int WIDTH = 256;
    private static float[] data = new float[8 * 128];
    private static int count;
    private static FloatBuffer transfer;
    private GaussianData() {}

    public static void begin() { count = 0; }

    public static int add(double x, double y, double z, double sh, double sv, double ext, double across, double card) {
        if (count == 65535) return 0;
        if (8 * (count + 1) > data.length) data = Arrays.copyOf(data, 2 * data.length);
        int i = 8 * count++;
        data[i]=(float)x; data[i+1]=(float)y; data[i+2]=(float)z; data[i+3]=(float)sh;
        data[i+4]=(float)sv; data[i+5]=(float)ext; data[i+6]=(float)across; data[i+7]=(float)card;
        return count;
    }

    /** Rows after the fixed source-probe table; both tables share one sampler within Minecraft's 12-unit API. */
    static int rows() { return (2 * count + WIDTH - 1) / WIDTH; }

    static void upload(int texture) {
        int h = rows();
        if (h == 0) return;
        GlStateManager._bindTexture(texture);
        int size = WIDTH * h * 4;
        if (transfer == null || transfer.capacity() < size) {
            if (transfer != null) MemoryUtil.memFree(transfer);
            transfer = MemoryUtil.memAllocFloat(size);
        }
        transfer.clear();
        int actual = 8 * count;
        transfer.put(data, 0, actual);
        for (int i = actual; i < size; i++) transfer.put(0);
        transfer.flip();
        PixelUnpack.upload(SourceProbes.DATA_ROW,WIDTH,h,transfer);
    }
}
