package ua.zentix.incoming.nuclear.world;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

/**
 * What a body already received from nuclear detonations (entity attachment, saved with it), so a body that was not in memory
 * when a detonation's flash and wave crossed its place receives them once when it loads ({@link LateExposure}), and a
 * body that was in memory never receives them twice.
 *
 * @param pulse     highest detonation number whose light and prompt radiation this body has been settled for
 * @param blast     highest detonation number whose blast wave this body has been settled for
 * @param falloutAt game time up to which fallout dose is accounted ({@code MobFallout}); -1 — never
 */
public record NukeSeen(int pulse, int blast, long falloutAt) {
    public static final NukeSeen NONE = new NukeSeen(0, 0, -1);

    public static final Codec<NukeSeen> CODEC = RecordCodecBuilder.create(i -> i.group(
            Codec.INT.optionalFieldOf("pulse", 0).forGetter(NukeSeen::pulse),
            Codec.INT.optionalFieldOf("blast", 0).forGetter(NukeSeen::blast),
            Codec.LONG.optionalFieldOf("fallout_at", -1L).forGetter(NukeSeen::falloutAt)
    ).apply(i, NukeSeen::new));

    public NukeSeen withPulse(int id) {
        return id <= pulse ? this : new NukeSeen(id, blast, falloutAt);
    }

    public NukeSeen withBlast(int id) {
        return id <= blast ? this : new NukeSeen(pulse, id, falloutAt);
    }

    public NukeSeen withFallout(long at) {
        return at <= falloutAt ? this : new NukeSeen(pulse, blast, at);
    }
}
