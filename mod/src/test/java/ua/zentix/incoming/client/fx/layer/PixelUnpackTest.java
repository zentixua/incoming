package ua.zentix.incoming.client.fx.layer;

import org.junit.jupiter.api.Test;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL21;
import java.nio.FloatBuffer;
import java.util.HashMap;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class PixelUnpackTest {
    @Test
    void atlasLayoutCannotReadPastTheClientBufferAndIsRestoredOnFailure() {
        for(boolean fail:new boolean[]{false,true}) {
            Map<Integer,Integer> state=new HashMap<>(Map.of(GL11.GL_UNPACK_ALIGNMENT,8,GL11.GL_UNPACK_ROW_LENGTH,1024,
                    GL11.GL_UNPACK_SKIP_ROWS,17,GL11.GL_UNPACK_SKIP_PIXELS,23,GL11.GL_UNPACK_SWAP_BYTES,1,
                    GL21.GL_PIXEL_UNPACK_BUFFER_BINDING,41));
            Map<Integer,Integer> before=Map.copyOf(state);
            FloatBuffer pixels=FloatBuffer.wrap(new float[]{1,2,3,4,5,6,7,8});
            PixelUnpack.Driver driver=new PixelUnpack.Driver() {
                public int get(int key) { return state.get(key); }
                public void store(int key,int value) { state.put(key,value); }
                public void buffer(int value) { state.put(GL21.GL_PIXEL_UNPACK_BUFFER_BINDING,value); }
                public void upload(int row,int width,int height,FloatBuffer data) {
                    assertEquals(128,row);assertEquals(2,width);assertEquals(1,height);
                    assertEquals(4,state.get(GL11.GL_UNPACK_ALIGNMENT));
                    for(int key:new int[]{GL11.GL_UNPACK_ROW_LENGTH,GL11.GL_UNPACK_SKIP_ROWS,GL11.GL_UNPACK_SKIP_PIXELS,
                            GL11.GL_UNPACK_SWAP_BYTES,GL21.GL_PIXEL_UNPACK_BUFFER_BINDING}) assertEquals(0,state.get(key));
                    assertSame(pixels,data);
                    assertEquals(8,data.remaining());
                    if(fail) throw new IllegalStateException("transfer failed");
                }
            };
            if(fail) assertThrows(IllegalStateException.class,()->PixelUnpack.upload(driver,128,2,1,pixels));
            else PixelUnpack.upload(driver,128,2,1,pixels);
            assertEquals(before,state,"the caller's upload layout must be restored, including failure paths");
        }
    }
}
