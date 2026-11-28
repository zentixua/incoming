package ua.zentix.incoming.util;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.DecoderException;
import net.minecraft.network.VarInt;
import net.minecraft.network.codec.StreamCodec;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@link StreamCodecs#deflated}: what deflates goes shorter, what does not goes as is, and both read back exactly. */
class StreamCodecsTest {
    private static final StreamCodec<ByteBuf, byte[]> CODEC = StreamCodecs.deflated(1 << 20);

    private static byte[] roundTrip(byte[] value, int[] wire) {
        ByteBuf b = Unpooled.buffer();
        try {
            CODEC.encode(b, value);
            wire[0] = b.readableBytes();
            byte[] back = CODEC.decode(b);
            assertEquals(0, b.readableBytes(), "bytes left after the value");
            return back;
        } finally {
            b.release();
        }
    }

    @Test
    void repetitiveBytesGoDeflated() {
        // a stone section's palette indices: a few values over and over
        byte[] value = new byte[33_000];
        Random r = new Random(7);
        for (int i = 0; i < value.length; i++) value[i] = (byte) (r.nextInt(16) < 14 ? 0x11 : r.nextInt(4));
        int[] wire = new int[1];
        assertArrayEquals(value, roundTrip(value, wire));
        assertTrue(wire[0] < value.length / 4, "deflated to " + wire[0] + " of " + value.length);
    }

    @Test
    void incompressibleBytesGoAsTheyAre() {
        byte[] value = new byte[4096];
        new Random(3).nextBytes(value);
        int[] wire = new int[1];
        assertArrayEquals(value, roundTrip(value, wire));
        assertEquals(value.length + 2 * VarInt.getByteSize(value.length), wire[0], "stored: length, frame length, bytes");
        assertArrayEquals(new byte[0], roundTrip(new byte[0], wire));
    }

    @Test
    void damagedBytesAreRejected() {
        byte[] value = new byte[10_000];
        ByteBuf b = Unpooled.buffer();
        CODEC.encode(b, value);
        // claim one more byte than the stream holds
        ByteBuf longer = Unpooled.buffer();
        VarInt.write(longer, value.length + 1);
        VarInt.read(b);
        longer.writeBytes(b);
        assertThrows(DecoderException.class, () -> CODEC.decode(longer));
        // a frame longer than the claimed length
        ByteBuf wrong = Unpooled.buffer();
        VarInt.write(wrong, 2);
        VarInt.write(wrong, 3);
        wrong.writeBytes(new byte[3]);
        assertThrows(DecoderException.class, () -> CODEC.decode(wrong));
        // garbage instead of a deflate stream
        ByteBuf garbage = Unpooled.buffer();
        VarInt.write(garbage, 100);
        VarInt.write(garbage, 4);
        garbage.writeBytes(new byte[]{(byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0xff});
        assertThrows(DecoderException.class, () -> CODEC.decode(garbage));
        b.release();
        longer.release();
        wrong.release();
        garbage.release();
    }
}
