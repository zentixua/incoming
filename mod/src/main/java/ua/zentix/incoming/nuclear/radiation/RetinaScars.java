package ua.zentix.incoming.nuclear.radiation;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;

import java.util.ArrayList;
import java.util.List;

/**
 * Player retinal burns (data attachment): permanent blind spots where the fireball was imaged on the retina
 * ({@link FlashEyes}). Reset on death (not copied), as dose is.
 */
public record RetinaScars(List<Scar> scars) {
    /** Maximum scars remembered: oldest removed. */
    public static final int MAX = 8;
    public static final RetinaScars NONE = new RetinaScars(List.of());

    /**
     * Visual-field scar: position is tangent of gaze-relative angle right and up (gaze basis, no roll); radius is tangent
     * of the ball image's angular radius.
     */
    public record Scar(float x, float y, float r) {
        public static final Codec<Scar> CODEC = RecordCodecBuilder.create(i -> i.group(
                Codec.FLOAT.fieldOf("x").forGetter(Scar::x),
                Codec.FLOAT.fieldOf("y").forGetter(Scar::y),
                Codec.FLOAT.fieldOf("r").forGetter(Scar::r)
        ).apply(i, Scar::new));
        public static final StreamCodec<ByteBuf, Scar> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.FLOAT, Scar::x, ByteBufCodecs.FLOAT, Scar::y, ByteBufCodecs.FLOAT, Scar::r, Scar::new);
    }

    public static final Codec<RetinaScars> CODEC = Scar.CODEC.sizeLimitedListOf(MAX).xmap(RetinaScars::new, RetinaScars::scars);
    public static final StreamCodec<ByteBuf, RetinaScars> STREAM_CODEC = Scar.STREAM_CODEC.apply(ByteBufCodecs.list(MAX))
            .map(RetinaScars::new, RetinaScars::scars);

    public RetinaScars {
        scars = List.copyOf(scars);
    }

    public boolean isEmpty() {
        return scars.isEmpty();
    }

    /** With a new scar; oldest removed beyond {@link #MAX}. */
    public RetinaScars with(Scar scar) {
        List<Scar> next = new ArrayList<>(scars);
        next.add(scar);
        while (next.size() > MAX) next.removeFirst();
        return new RetinaScars(next);
    }
}
