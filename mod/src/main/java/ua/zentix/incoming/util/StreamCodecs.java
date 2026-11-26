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
