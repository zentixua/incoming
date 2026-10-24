package ua.zentix.incoming.client.fx.layer;

import com.mojang.blaze3d.platform.GlStateManager;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;
import org.lwjgl.system.MemoryUtil;
import java.nio.FloatBuffer;
import java.util.Arrays;

/** Physical Gaussian descriptors for analytical near-field particle impostors; two texels per Gaussian. */
public final class GaussianData {
    private static final int WIDTH = 256;
    private static float[] data = new float[8 * 128];
    private static int count, texture = -1, rows;
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

    static int upload() {
        int h = Math.max(1, (2 * count + WIDTH - 1) / WIDTH);
        if (texture < 0) texture = GlStateManager._genTexture();
        GlStateManager._bindTexture(texture);
        if (h > rows) {
            GlStateManager._texParameter(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
            GlStateManager._texParameter(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
            GlStateManager._texImage2D(GL11.GL_TEXTURE_2D, 0, GL30.GL_RGBA32F, WIDTH, h, 0, GL11.GL_RGBA, GL11.GL_FLOAT, null);
            rows = h;
        }
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
        GL11.glTexSubImage2D(GL11.GL_TEXTURE_2D, 0, 0, 0, WIDTH, h, GL11.GL_RGBA, GL11.GL_FLOAT, transfer);
        return texture;
    }
}
