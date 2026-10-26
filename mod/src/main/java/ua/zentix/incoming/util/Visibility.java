package ua.zentix.incoming.util;

/**
 * Weather visibility range, meters (= blocks), shared by everything: distant visual haze ({@code client.far.Sight}) and
 * nuclear flash transmission through air ({@code nuclear.model.ThermalModel}). Clear — 20 km. Minecraft rain is moderate
 * (3–8 mm/h): 5 km; thunderstorm is heavy rain, 1 km (ITU-R P.1814, table 2: rain attenuation 1.076·R^0.67 dB/km,
 * V = 3.91/σ; no thunderstorm observations — estimated from 25–100 mm/h heavy rain, 0.6–1.8 km).
 */
public final class Visibility {
    public static final double CLEAR = 20_000, RAIN = 5_000, THUNDER = 1_000;

    private Visibility() {}
}
