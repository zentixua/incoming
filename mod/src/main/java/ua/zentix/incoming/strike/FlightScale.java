package ua.zentix.incoming.strike;

import ua.zentix.incoming.IncomingConfig;

/**
 * World flight scale {@code scale.flight_speed} (Artem, 22.10): 0 gives game flight speeds and altitudes ({@link
 * WeaponSpec.Airframe}, not physical), 1 real ({@link Article.Envelope}), smoothly between.
 * <p>
 * Reason: a block is a meter, so everything at the target is real at any scale; visible world is small, and fast
 * real-speed weapons cross render distance in fractions of a second. Required approach speed is a game property,
 * not an article property: each article has two spec values and one world scale. Game speed cannot derive from
 * render distance: each player has their own distance, but flight is shared on the server.
 * <p>
 * Logarithmic blend: v = v<sub>game</sub>·(v<sub>real</sub>/v<sub>game</sub>)<sup>x</sup>. Each article has its
 * own game/real ratio; the fraction toward reality is shared, preserving article speed order at any scale.
 * Maneuvering (turn rate, climb rate) remains game-based: higher speed naturally increases turn radius.
 */
public final class FlightScale {
    private FlightScale() {}

    /**
     * Current world scale (from preset); 0 until server settings load (unit tests, client menu). In-world clients
     * receive server settings from NeoForge.
     */
    public static double x() {
        return IncomingConfig.SERVER_SPEC.isLoaded() ? IncomingConfig.SERVER.flightSpeed() : 0;
    }

    /**
     * Value at {@code x}: {@code game} at 0, {@code real} at 1, logarithmic between. Missing real or game value
     * (≤ 0: ballistic flight altitude unspecified) gives game value. At 0, exactly game value.
     */
    public static double blend(double game, double real, double x) {
        if (x == 0 || game <= 0 || real <= 0) return game;
        return game * Math.pow(real / game, x);
    }
}
