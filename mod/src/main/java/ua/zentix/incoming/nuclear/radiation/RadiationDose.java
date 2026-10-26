package ua.zentix.incoming.nuclear.radiation;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

/**
 * Player irradiation (data attachment): accumulated dose, Gy; clothing and skin contamination with radioactive dust
 * (irradiates until washed off with water), R/h; time dose crossed 1 Gy (sickness stages start there);
 * and current dose rate for the Geiger counter.
 */
public record RadiationDose(float doseGy, float contamination, long exposedAt, float rate) {
    public static final RadiationDose NONE = new RadiationDose(0, 0, -1, 0);

    public static final Codec<RadiationDose> CODEC = RecordCodecBuilder.create(i -> i.group(
            Codec.FLOAT.fieldOf("dose").forGetter(RadiationDose::doseGy),
            Codec.FLOAT.optionalFieldOf("contamination", 0f).forGetter(RadiationDose::contamination),
            Codec.LONG.optionalFieldOf("exposed_at", -1L).forGetter(RadiationDose::exposedAt),
            Codec.FLOAT.optionalFieldOf("rate", 0f).forGetter(RadiationDose::rate)
    ).apply(i, RadiationDose::new));

    public boolean isExposed() {
        return exposedAt >= 0;
    }
}
