package ua.zentix.incoming.client.fx.layer;

import org.junit.jupiter.api.Test;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The half pass's per-frame texture uploads ({@link HalfPass} cuts and rectangles) read the unpack buffer, never client
 * memory: from client memory the NVIDIA driver held the render thread in the rectangles' upload 14–18 ms a frame
 * (200–340 ms a second) while a 1 Mt front crossed the camera at 3 km. Every {@code glTexSubImage2D} the class calls
 * takes the buffer offset (a {@code long}), not a Java buffer. Read from the class file without loading Minecraft or GL.
 */
class HalfPassUploadTest {
    @Test
    void everyTextureUploadReadsTheUnpackBuffer() throws IOException {
        List<String> uploads = descriptors("HalfPass", "glTexSubImage2D");
        assertFalse(uploads.isEmpty(), "HalfPass calls no glTexSubImage2D: the test no longer sees its uploads");
        for (String d : uploads) {
            assertTrue(d.endsWith("J)V"), "HalfPass uploads from client memory: glTexSubImage2D" + d);
        }
        // the offset reads a bound buffer that holds the data (review r1: without the bind, offset 0 is a null pointer)
        assertFalse(descriptors("HalfPass", "glBufferData").isEmpty(), "HalfPass fills no unpack buffer");
        assertFalse(descriptors("HalfPass", "_glBindBuffer").isEmpty(), "HalfPass binds no unpack buffer");
        assertTrue(descriptors("HalfPass", "_texSubImage2D").isEmpty(), "HalfPass uploads through GlStateManager._texSubImage2D");
    }

    /**
     * The line's figure is the last whole second's (review r1 probe): a second of 15 ms uploads shows once it has passed,
     * even with no draw after it, and a second without draws then reads 0.
     */
    @Test
    void uploadFigureIsTheLastWholeSecond() {
        long t = 1_000_000_000_000L;
        HalfPass.second(t);
        HalfPass.second(t + 2_000_000_000L);
        t += 2_000_000_000L;
        for (int i = 0; i < 30; i++) HalfPass.uploaded(15_000_000L, t + i * 20_000_000L);
        String idle = HalfPass.describe(t + 1_500_000_000L);
        assertTrue(idle.contains("uploads 15.000 ms a draw (longest 15.000"), idle);
        String empty = HalfPass.describe(t + 2_600_000_000L);
        assertTrue(empty.contains("uploads 0.000 ms a draw (longest 0.000"), empty);
    }

    /** The probe reads a client-memory call as one (the class below calls the buffer overload). */
    @Test
    void clientMemoryUploadIsSeen() throws IOException {
        List<String> uploads = descriptors("HalfPassUploadTest$ClientMemory", "glTexSubImage2D");
        assertTrue(uploads.stream().anyMatch(d -> d.contains("Ljava/nio/FloatBuffer;")), "probe missed " + uploads);
    }

    static final class ClientMemory {
        static void upload(java.nio.FloatBuffer b) {
            org.lwjgl.opengl.GL11.glTexSubImage2D(org.lwjgl.opengl.GL11.GL_TEXTURE_2D, 0, 0, 0, 1, 1, org.lwjgl.opengl.GL11.GL_RED,
                    org.lwjgl.opengl.GL11.GL_FLOAT, b);
        }
    }

    /** Descriptors of every method named name the class refers to (constant pool NameAndType, JVMS 4.4.6). */
    private static List<String> descriptors(String simpleName, String name) throws IOException {
        List<String> out = new ArrayList<>();
        try (InputStream raw = HalfPassUploadTest.class.getResourceAsStream(simpleName + ".class")) {
            if (raw == null) throw new IOException(simpleName + ".class not found");
            DataInputStream in = new DataInputStream(raw);
            in.readInt();
            in.readUnsignedShort();
            in.readUnsignedShort();
            int count = in.readUnsignedShort();
            String[] utf8 = new String[count];
            int[] nameOf = new int[count], typeOf = new int[count];
            for (int i = 1; i < count; i++) {
                int tag = in.readUnsignedByte();
                switch (tag) {
                    case 1 -> utf8[i] = in.readUTF();
                    case 12 -> {
                        nameOf[i] = in.readUnsignedShort();
                        typeOf[i] = in.readUnsignedShort();
                    }
                    case 3, 4, 9, 10, 11, 17, 18 -> in.readInt();
                    case 5, 6 -> {
                        in.readLong();
                        i++;
                    }
                    case 7, 8, 16, 19, 20 -> in.readUnsignedShort();
                    case 15 -> {
                        in.readUnsignedByte();
                        in.readUnsignedShort();
                    }
                    default -> throw new IOException("constant tag " + tag);
                }
            }
            for (int i = 1; i < count; i++) {
                if (nameOf[i] != 0 && name.equals(utf8[nameOf[i]])) out.add(utf8[typeOf[i]]);
            }
        }
        return out;
    }
}
