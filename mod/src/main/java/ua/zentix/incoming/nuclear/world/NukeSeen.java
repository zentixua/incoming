package ua.zentix.incoming.nuclear.world;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import java.util.ArrayList;
import java.util.List;

/**
 * What a body already received from nuclear detonations (entity attachment, saved with it), so a body that was not in memory
 * when a detonation's flash and wave crossed its place receives them once when it loads ({@link LateExposure}), and a
 * body that was in memory never receives them twice. Each detonation is recorded by its own number: the pulse of a later
 * detonation may reach a body before the queued pulse of an earlier one.
 *
 * @param born      highest detonation number when this body was spawned: older detonations happened before it existed
 *                  (0 — loaded from a save, or spawned before any detonation)
 * @param pulse     detonations whose light and prompt radiation this body has received
 * @param blast     detonations whose blast wave this body has received
 * @param falloutAt game time up to which fallout dose is accounted ({@code MobFallout}); -1 — never
 */
public record NukeSeen(int born, List<Integer> pulse, List<Integer> blast, long falloutAt) {
    public static final NukeSeen NONE = new NukeSeen(0, List.of(), List.of(), -1);

    public static final Codec<NukeSeen> CODEC = RecordCodecBuilder.create(i -> i.group(
            Codec.INT.optionalFieldOf("born", 0).forGetter(NukeSeen::born),
            Codec.INT.listOf().optionalFieldOf("pulse", List.of()).forGetter(NukeSeen::pulse),
            Codec.INT.listOf().optionalFieldOf("blast", List.of()).forGetter(NukeSeen::blast),
            Codec.LONG.optionalFieldOf("fallout_at", -1L).forGetter(NukeSeen::falloutAt)
    ).apply(i, NukeSeen::new));

    public NukeSeen {
        pulse = List.copyOf(pulse);
        blast = List.copyOf(blast);
    }

    /** Detonation {@code id} happened before this body was spawned. */
    public boolean predates(int id) {
        return id <= born;
    }

    public boolean pulsed(int id) {
        return pulse.contains(id);
    }

    public boolean blasted(int id) {
        return blast.contains(id);
    }

    public NukeSeen withBorn(int id) {
        return id <= born ? this : new NukeSeen(id, pulse, blast, falloutAt);
    }

    public NukeSeen withPulse(int id) {
        return pulsed(id) ? this : new NukeSeen(born, plus(pulse, id), blast, falloutAt);
    }

    public NukeSeen withBlast(int id) {
        return blasted(id) ? this : new NukeSeen(born, pulse, plus(blast, id), falloutAt);
    }

    public NukeSeen withFallout(long at) {
        return at <= falloutAt ? this : new NukeSeen(born, pulse, blast, at);
    }

    private static List<Integer> plus(List<Integer> ids, int id) {
        List<Integer> out = new ArrayList<>(ids.size() + 1);
        out.addAll(ids);
        out.add(id);
        return out;
    }
}
