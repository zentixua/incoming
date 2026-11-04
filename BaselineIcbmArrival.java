package ua.zentix.incoming.nuclear;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.world.phys.Vec3;
import ua.zentix.incoming.util.StreamCodecs;

/** Committed game approach, not physical flight. No entity, collision simulation or arrival-driven chunk loads. */
public record IcbmArrival(int strikeId, long start, long end, Detonation geometry, Vec3 velocity) {
    /** Presentation window, not a physical luminous-entry altitude. Short flights retain nine seconds for boost. */
    public static final int WINDOW = 240, BOOST = 180;
    /** presentation: a short cooling wake, independent of flight duration, yield and effects scale. */
    public static final double WAKE = 4;
    public static final Codec<IcbmArrival> CODEC = RecordCodecBuilder.create(i -> i.group(
            Codec.INT.fieldOf("strike").forGetter(IcbmArrival::strikeId),
            Codec.LONG.fieldOf("start").forGetter(IcbmArrival::start),
            Codec.LONG.fieldOf("end").forGetter(IcbmArrival::end),
            Detonation.CODEC.fieldOf("geometry").forGetter(IcbmArrival::geometry),
            Vec3.CODEC.fieldOf("velocity").forGetter(IcbmArrival::velocity)
    ).apply(i, IcbmArrival::new));
    public static final StreamCodec<ByteBuf, IcbmArrival> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, IcbmArrival::strikeId,
            ByteBufCodecs.VAR_LONG, IcbmArrival::start,
            ByteBufCodecs.VAR_LONG, IcbmArrival::end,
            Detonation.STREAM_CODEC, IcbmArrival::geometry,
            StreamCodecs.VEC3, IcbmArrival::velocity, IcbmArrival::new);

    public static long start(long launch, long end) {
        return end - Math.min(WINDOW, Math.max(0, end - launch - BOOST));
    }

    /** Same steep game direction as the previous graphic; no researched guidance or trajectory model. */
    public static Vec3 velocity(Vec3 launch, Vec3 target, double speed) {
        Vec3 flat = new Vec3(target.x - launch.x, 0, target.z - launch.z);
        flat = flat.lengthSqr() < 1 ? new Vec3(1, 0, 0) : flat.normalize();
        return flat.scale(0.34).add(0, -0.94, 0).normalize().scale(speed);
    }

    /** Absolute position at emission time. Old wake positions never follow the current head. */
    public Vec3 at(double tick) {
        return geometry.burst().add(velocity.scale(tick - end));
    }

    /** presentation: continuous luminous onset, without a fully formed trail on the first frame. */
    public double light(double emitted) {
        double u = Math.clamp((emitted - start) / Math.min(20.0, Math.max(1, end - start)), 0, 1);
        return u * u * (3 - 2 * u);
    }
}
