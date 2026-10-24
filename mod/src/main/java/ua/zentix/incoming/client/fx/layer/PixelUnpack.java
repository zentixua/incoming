package ua.zentix.incoming.client.fx.layer;

import com.mojang.blaze3d.platform.GlStateManager;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL21;
import java.nio.FloatBuffer;

/** Scoped OpenGL client-memory layout; atlas uploads may leave a different stride or origin. */
final class PixelUnpack {
    interface Driver {
        int get(int name);
        void store(int name, int value);
        void buffer(int name);
        void upload(int row, int width, int height, FloatBuffer pixels);
    }

    private static final Driver GL = new Driver() {
        public int get(int name) { return GL11.glGetInteger(name); }
        public void store(int name, int value) { GlStateManager._pixelStore(name, value); }
        public void buffer(int name) { GlStateManager._glBindBuffer(GL21.GL_PIXEL_UNPACK_BUFFER, name); }
        public void upload(int row, int width, int height, FloatBuffer pixels) {
            GL11.glTexSubImage2D(GL11.GL_TEXTURE_2D,0,0,row,width,height,GL11.GL_RGBA,GL11.GL_FLOAT,pixels);
        }
    };

    private PixelUnpack() {}

    static void upload(int row, int width, int height, FloatBuffer pixels) {
        upload(GL,row,width,height,pixels);
    }

    static void upload(Driver gl, int row, int width, int height, FloatBuffer pixels) {
        int alignment=gl.get(GL11.GL_UNPACK_ALIGNMENT), stride=gl.get(GL11.GL_UNPACK_ROW_LENGTH);
        int rows=gl.get(GL11.GL_UNPACK_SKIP_ROWS), columns=gl.get(GL11.GL_UNPACK_SKIP_PIXELS);
        int swap=gl.get(GL11.GL_UNPACK_SWAP_BYTES), buffer=gl.get(GL21.GL_PIXEL_UNPACK_BUFFER_BINDING);
        try {
            gl.buffer(0);
            gl.store(GL11.GL_UNPACK_ALIGNMENT,4);
            gl.store(GL11.GL_UNPACK_ROW_LENGTH,0);
            gl.store(GL11.GL_UNPACK_SKIP_ROWS,0);
            gl.store(GL11.GL_UNPACK_SKIP_PIXELS,0);
            gl.store(GL11.GL_UNPACK_SWAP_BYTES,0);
            gl.upload(row,width,height,pixels);
        } finally {
            gl.store(GL11.GL_UNPACK_ALIGNMENT,alignment);
            gl.store(GL11.GL_UNPACK_ROW_LENGTH,stride);
            gl.store(GL11.GL_UNPACK_SKIP_ROWS,rows);
            gl.store(GL11.GL_UNPACK_SKIP_PIXELS,columns);
            gl.store(GL11.GL_UNPACK_SWAP_BYTES,swap);
            gl.buffer(buffer);
        }
    }
}
