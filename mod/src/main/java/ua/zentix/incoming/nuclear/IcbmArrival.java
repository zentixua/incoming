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
    /**
     * Visible entry, ticks: a real warhead glows from ~{@link #ONSET} m for ~30 s ({@code ReentryGlow}); at game speed
     * the same 30 s start lower (≈14 km at 25 blocks/tick and the steep game path, not physical). {@link #BOOST}: the
     * warhead never appears while the first stage still burns over the launch site ({@code IcbmEntity}); the default
     * 90 s flight leaves 29 s, shorter flights less.
     */
    public static final int WINDOW = 600, BOOST = ua.zentix.incoming.strike.WeaponSpec.NUKE.ascent().burnTicks();
    /** Luminous entry onset above the burst, m (est.: plasma glow begins in the upper atmosphere, ~80 km). */
    public static final double ONSET = 80_000;
    /** Longest wake glow kept after the burst or drawn behind the head, ticks ({@code ReentryGlow.wakeLength}). */
    public static final double WAKE = 120;
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

    /** Window start for an approach at {@code velocity} blocks/tick: no longer than its fall from {@link #ONSET}. */
    public static long start(long launch, long end, Vec3 velocity) {
        return start(launch, end, (int) Math.min(WINDOW, Math.ceil(ONSET / Math.max(1e-6, -velocity.y))));
    }

    private static long start(long launch, long end, int window) {
        return end - Math.min(window, Math.max(0, end - launch - BOOST));
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
