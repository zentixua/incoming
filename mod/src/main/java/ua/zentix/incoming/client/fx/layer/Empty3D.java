package ua.zentix.incoming.client.fx.layer;

import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL30;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;

/**
 * A complete 1×1×1 zero 3D texture for a {@code sampler3D} whose data is absent this frame (no smoke grid, open-air window,
 * solids window or fireball pattern yet). Binding object 0 there leaves an active sampler on an incomplete texture at the
 * draw: legal in GL (it reads 0, 0, 0, 1), but a path drivers treat differently, logged by NVIDIA on every pall draw
 * (229 070 of 229 407 debug messages in one nuke scenario, #656). The shaders already ignore the texture through their own
 * flag uniforms, so the value never reaches the picture.
 */
public final class Empty3D {
    private static int texture = -1;

    private Empty3D() {
    }

    /** Texture name, created on first use (render thread). */
    public static int texture() {
        if (texture < 0) {
            int previous = GL11.glGetInteger(GL12.GL_TEXTURE_BINDING_3D);
            texture = GL11.glGenTextures();
            GL11.glBindTexture(GL12.GL_TEXTURE_3D, texture);
            GL11.glTexParameteri(GL12.GL_TEXTURE_3D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
            GL11.glTexParameteri(GL12.GL_TEXTURE_3D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
            GL11.glTexParameteri(GL12.GL_TEXTURE_3D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
            GL11.glTexParameteri(GL12.GL_TEXTURE_3D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
            GL11.glTexParameteri(GL12.GL_TEXTURE_3D, GL12.GL_TEXTURE_WRAP_R, GL12.GL_CLAMP_TO_EDGE);
            GL11.glTexParameteri(GL12.GL_TEXTURE_3D, GL12.GL_TEXTURE_MAX_LEVEL, 0);
            ByteBuffer zero = MemoryUtil.memCalloc(4);
            GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, 1);
            GL12.glTexImage3D(GL12.GL_TEXTURE_3D, 0, GL30.GL_R8, 1, 1, 1, 0, GL11.GL_RED, GL11.GL_UNSIGNED_BYTE, zero);
            GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, 4);
            MemoryUtil.memFree(zero);
            GL11.glBindTexture(GL12.GL_TEXTURE_3D, previous);
        }
        return texture;
    }
}
