package ua.zentix.incoming.util;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.world.phys.Vec3;

/** Stream codecs absent from vanilla 1.21.1 (mod packets and detonation data). */
public final class StreamCodecs {
    /** Vector as three doubles: {@code Vec3} in 1.21.1 has only a {@code Codec}. */
    public static final StreamCodec<ByteBuf, Vec3> VEC3 = StreamCodec.composite(
            ByteBufCodecs.DOUBLE, Vec3::x, ByteBufCodecs.DOUBLE, Vec3::y, ByteBufCodecs.DOUBLE, Vec3::z, Vec3::new);

    /**
     * Field appended to a packet whose previous format already shipped (Flashback replays store packet bytes
     * and read them with the current codec, replay.md): always written, read only if packet bytes remain; otherwise
     * {@code absent}, “as before this field”. Only at packet end (followed only by similar fields): a mod-packet
     * codec receives bytes through packet-frame end; this cannot work mid-packet — following fields would supply bytes.
     */
    public static <T> StreamCodec<ByteBuf, T> trailing(StreamCodec<? super ByteBuf, T> codec, T absent) {
        return new StreamCodec<>() {
            @Override
            public T decode(ByteBuf b) {
                return b.isReadable() ? codec.decode(b) : absent;
            }

            @Override
            public void encode(ByteBuf b, T value) {
                codec.encode(b, value);
            }
        };
    }

    /**
     * Bytes deflated on the wire (raw DEFLATE, zlib level 6): their length, then the stored bytes where deflating would
     * not make them smaller (a frame as long as the length), else the deflated ones (a shorter frame). For bulk data a
     * Flashback recording keeps as received, before the connection's compression ({@code replay.md}); the connection
     * compresses the frame again and gains nothing. Thread-local zlib state: encoding runs on network threads.
     *
     * @param max most bytes either way, checked before inflating
     */
    public static StreamCodec<ByteBuf, byte[]> deflated(int max) {
        return new StreamCodec<>() {
            @Override
            public byte[] decode(ByteBuf b) {
                int n = net.minecraft.network.VarInt.read(b);
                byte[] frame = ByteBufCodecs.byteArray(max).decode(b);
                if (n < 0 || n > max || frame.length > n) throw new io.netty.handler.codec.DecoderException("deflated " + frame.length + " bytes of " + n);
                if (frame.length == n) return frame;
                java.util.zip.Inflater inflater = INFLATER.get();
                inflater.reset();
                inflater.setInput(frame);
                byte[] out = new byte[n];
                try {
                    int got = 0;
                    while (got < n) {
                        int k = inflater.inflate(out, got, n - got);
                        if (k == 0 && (inflater.finished() || inflater.needsInput() || inflater.needsDictionary())) break;
                        got += k;
                    }
                    if (got != n || !inflater.finished() || inflater.getRemaining() != 0) {
                        throw new io.netty.handler.codec.DecoderException("deflated bytes give " + got + " of " + n);
                    }
                } catch (java.util.zip.DataFormatException e) {
                    throw new io.netty.handler.codec.DecoderException(e);
                } finally {
                    inflater.reset(); // the thread-local state would keep the frame alive
                }
                return out;
            }

            @Override
            public void encode(ByteBuf b, byte[] value) {
                if (value.length > max) throw new io.netty.handler.codec.EncoderException(value.length + " bytes, at most " + max);
                java.util.zip.Deflater deflater = DEFLATER.get();
                deflater.reset();
                deflater.setInput(value);
                deflater.finish();
                // shorter than the input or stored as is
                byte[] out = new byte[value.length];
                int k = 0;
                while (!deflater.finished() && k < out.length) k += deflater.deflate(out, k, out.length - k);
                boolean shorter = deflater.finished() && k < value.length;
                deflater.reset(); // the thread-local state would keep the value alive
                net.minecraft.network.VarInt.write(b, value.length);
                if (shorter) {
                    net.minecraft.network.VarInt.write(b, k);
                    b.writeBytes(out, 0, k);
                } else {
                    ByteBufCodecs.byteArray(max).encode(b, value);
                }
            }
        };
    }

    private static final ThreadLocal<java.util.zip.Deflater> DEFLATER = ThreadLocal.withInitial(() -> new java.util.zip.Deflater(6, true));
    private static final ThreadLocal<java.util.zip.Inflater> INFLATER = ThreadLocal.withInitial(() -> new java.util.zip.Inflater(true));

    /**
     * A length-prefixed value: its own bytes end where its frame ends, so its {@link #trailing} fields work inside a
     * list or before other fields, and a reader can skip a value it does not know.
     */
    public static <T> StreamCodec<ByteBuf, T> framed(StreamCodec<? super ByteBuf, T> codec) {
        return new StreamCodec<>() {
            @Override
            public T decode(ByteBuf b) {
                int n = net.minecraft.network.VarInt.read(b);
                if (n < 0 || n > b.readableBytes()) throw new io.netty.handler.codec.DecoderException("frame of " + n + " bytes, " + b.readableBytes() + " left");
                // bytes a newer writer appended inside the frame stay unread: the slice already moved past them
                return codec.decode(b.readSlice(n));
            }

            @Override
            public void encode(ByteBuf b, T value) {
                ByteBuf frame = io.netty.buffer.Unpooled.buffer();
                try {
                    codec.encode(frame, value);
                    net.minecraft.network.VarInt.write(b, frame.readableBytes());
                    b.writeBytes(frame);
                } finally {
                    frame.release();
                }
            }
        };
    }

    private StreamCodecs() {}
}
