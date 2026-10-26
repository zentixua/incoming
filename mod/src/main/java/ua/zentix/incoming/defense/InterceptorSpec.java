package ua.zentix.incoming.defense;

import ua.zentix.incoming.guidance.Ballistics;
import ua.zentix.incoming.strike.Article;
import ua.zentix.incoming.strike.FlightScale;
import ua.zentix.incoming.strike.WeaponSpec;
import ua.zentix.incoming.util.Air;

/**
 * SAM spec: radar and engagement ranges, missile flight values, and the real article's specifications ({@link
 * Passport}, 9M96). Air defense uses our own game model (Artem, 21.10): ranges, flight, and fuze use game values,
 * not physical, chosen for game scale (hundreds of blocks visible, a slower missile so flight can be seen),
 * rather than real system data. There is no hit probability: the target is shot down if the missile bursts within
 * fuze radius ({@link Interceptor}). Rail count and reload are world rules ({@code air_defense}). The world
 * flight scale ({@link FlightScale}) takes maximum missile speed from game to real values ({@link #maxSpeed()}),
 * with acceleration over the same ticks ({@link #boostAccel()}); turn limit and ranges remain game values.
 *
 * @param radarRange radar range, blocks (game value): projectiles beyond it are unseen; low-signature ones are
 * seen closer, per their spec ({@code WeaponSpec.Signature.visibility}); a hill or building between radar and
 * target hides it ({@link Radar})
 * @param engageRange engagement range, blocks (game value): the SAM launches at targets within it
 * @param railTicks first ticks after launch rail exit when the nose holds the rail angle without turning toward
 * the target
 * @param elevation rail angle above the horizon, degrees: the missile leaves upward toward the target (90 is
 * vertical)
 * @param launchSpeed launch rail exit speed, blocks/tick
 * @param boostTicks acceleration ticks
 * @param gameBoostAccel speed gain per acceleration tick at game speed, blocks/tick²
 * @param gameMaxSpeed maximum speed, blocks/tick (game value, not physical)
 * @param lateralG maximum lateral acceleration, g: turn limit at any speed ({@link #turnLimit}), so a slow
 * missile turns more sharply
 * @param fuelTicks flight duration in ticks before self-destruct
 * @param fuse proximity fuze radius, blocks (beyond half the target body length)
 * @param noseLength half the body length, blocks (model and nozzle exit: {@code
 * client.render.InterceptorRenderer})
 * @param passport real article specifications
 */
public record InterceptorSpec(double radarRange, double engageRange, int railTicks, double elevation, double launchSpeed, int boostTicks, double gameBoostAccel, double gameMaxSpeed,
                              double lateralG, int fuelTicks, double fuse, double noseLength, Passport passport) {
    /** Maximum speed on the world flight scale, blocks/tick ({@link #maxSpeed(double)}). */
    public double maxSpeed() {
        return maxSpeed(FlightScale.x());
    }

    /** Maximum speed at scale {@code x}, blocks/tick: game to real ({@link Passport#maxSpeed}). */
    public double maxSpeed(double x) {
        return FlightScale.blend(gameMaxSpeed, passport.maxSpeed() / 20, x);
    }

    /** Speed gain per acceleration tick on the world flight scale, blocks/tick² ({@link #boostAccel(double)}). */
    public double boostAccel() {
        return boostAccel(FlightScale.x());
    }

    /**
     * Speed gain per acceleration tick at scale {@code x}, blocks/tick²: reach maximum speed in the same
     * acceleration ticks.
     */
    public double boostAccel(double x) {
        return x == 0 ? gameBoostAccel : gameBoostAccel * (maxSpeed(x) - launchSpeed) / (gameMaxSpeed - launchSpeed);
    }

    /**
     * The missile does not fly above this many blocks over sea level: upper altitude boundary ({@link
     * Passport#altitudeMax}).
     */
    public double ceiling() {
        return Air.SEA + passport.altitudeMax();
    }

    /**
     * Interceptor missile specifications. SI units; “E” means an estimate derived by a law from other values,
     * with a range.
     *
     * @param launchMass launch mass, kg
     * @param length length, m
     * @param diameter diameter, m
     * @param maxSpeed maximum speed, m/s
     * @param lateralG maximum maneuver load, g
     * @param rangeMin inner engagement boundary, m
     * @param rangeMax outer boundary, m
     * @param altitudeMin lower altitude boundary, m
     * @param altitudeMax upper boundary, m
     * @param warhead warhead
     * @param fuse proximity fuze activation radius, m
     * @param boost engine (flow and exhaust also used for the client's plume)
     * @param sustain sustain mode after game acceleration until the end of game flight: not physical, see {@link
     * #M96}
     */
    public record Passport(double launchMass, double length, double diameter, double maxSpeed, double lateralG, double rangeMin,
                           double rangeMax, double altitudeMin, double altitudeMax, Article.Charge warhead, double fuse,
                           Article.SolidMotor boost, Article.SolidMotor sustain) {}

    /**
     * 9M96E2 (S-400; the S-350's 9M96E is 333 kg, 4.75 m, 900 m/s, 1–40 km, up to 20 km): 420 kg, length 5.65 m
     * (5.35–5.65 across sources), Ø 0.24 m, up to 1000 m/s, engagement zone 1–120 km in range and 5 m–30 km in
     * altitude; cold vertical launch ejects it by gas to ~30 m, then ignition and gas-dynamic turning (Wikipedia
     * “S-350”, “S-400”, Russian “Redut (surface-to-air missile system)”). Load exceeds 20 g at altitudes up to 30
     * km (“about 20 g in 0.025 s” by gas dynamics, Russian Wikipedia citing paralay.com). Warhead: 24 kg,
     * directional with multipoint initiation; explosive mass is unpublished. E: ~10 kg explosive (40% of mass, as
     * in fragmentation SAM warheads), ~10 kg TNT, casing and preformed fragments ~14 kg. Fuze: public claim
     * “about 70% direct hits on the target warhead, the rest within several meters”; E: 5 m. Engine: single-mode
     * solid rocket motor (dual mode unconfirmed), burns 10–12 s (legion.wplus.net, low confidence); thrust
     * unpublished. E: 15 kg/s at exhaust velocity 2350 m/s (composite propellant, ~240 s), giving 35 kN, 11 s,
     * 165 kg propellant: Tsiolkovsky gives ~1100 m/s without losses. Open data give no sustain mode for the 9M96:
     * game flight accelerates for 1.5 s then burns in a weak 3 kg/s mode, not physical, pending physical flight
     * (air-defense step).
     */
    static final Passport M96 = new Passport(420, 5.65, 0.24, 1000, 20, 1000, 120_000, 5, 30_000,
            new Article.Charge(10, 10, 14, 0.24), 5, new Article.SolidMotor(15 * 2350, 2350, 11, 0), new Article.SolidMotor(3 * 2350, 2350, (300 - 30) / 20.0, 0));

    /**
     * 9M96 SAM missile ({@link #M96}). Game model, not physical (slower than the real one so flight is visible):
     * vertical canister launch as on the S-350 and S-400's 9M96, up to 14 blocks/tick (280 m/s) in one and a half
     * seconds of acceleration (~12.5 in vertical climb, the rest gained in sustain mode), 15 s flight, ~4 km;
     * speed follows the same powered-flight law as projectiles ({@link #speed}: vertical climb reduces
     * acceleration through altitude/speed exchange). Lateral acceleration 40 g is not physical: open sources give
     * “over 20 g” for 9M96 at altitudes up to 30–35 km (FAS, “S-400 SA-20 Triumf”,
     * https://nuke.fas.org/guide/russia/airdef/s-400.htm); denser air near the ground allows sharper maneuvers.
     * This number is a game limit: at 30 g the missile cannot catch a Shahed already above the SAM at launch and
     * diving at a target behind it ({@code InterceptorFlightTest.catchesDivingTargetOverhead}). After launch rail
     * exit (~2.7 blocks/tick), turn is ~20°/tick, arcing from vertical toward a target on the horizon in ~0.25 s;
     * at full speed, 4°/tick (radius ~200 blocks). Fuze radius is 6 blocks (game value): it passes a Shahed at 2
     * blocks/tick within one tick with margin. Radar range is 3000 blocks, engagement range 1500 (game values,
     * not physical: a real system reaches tens and hundreds of kilometers, beyond game visibility; former world
     * settings {@code radar_range} and {@code engage_range} used these defaults): a Shahed takes over 30 s to
     * reach the SAM from the engagement boundary, an oncoming missile takes 5–6 s.
     */
    public static final InterceptorSpec SAM = new InterceptorSpec(3000, 1500, 5, 90, 0.8, 30, 0.45, 14, 40, 300, 6, 2.0, M96);

    /**
     * Speed after flight tick {@code age} under the powered-flight law ({@link WeaponSpec.Handling#speed}: engine
     * pulls toward maximum speed, climbing reduces speed, descending adds it); {@code climbSine} is the sine of
     * the flight-path angle. Engine is a game model, not physical: during boost the gain is {@link #boostAccel()}
     * per tick, during sustain it is reduced by the sustain/boost thrust ratio from the spec; reaches maximum
     * speed in a tick (relax 1) if the gain allows it. Before the first tick, use launch rail exit speed.
     */
    public double speed(double speed, int age, double climbSine) {
        double boost = boostAccel();
        double accel = age <= boostTicks ? boost : boost * passport.sustain().thrust() / passport.boost().thrust();
        return WeaponSpec.Handling.speed(speed, maxSpeed(), climbSine, accel, 1);
    }

    /**
     * Turn limit per tick at {@code speed}, °: the missile has one lateral acceleration (a = v·ω, ω = a / v), so
     * it turns more sharply during boost than at maximum speed. With one angular speed, a missile leaving the
     * rail toward a target directly overhead would only turn behind it and follow a diving Shahed to the ground.
     */
    public double turnLimit(double speed) {
        return Math.toDegrees(lateralG * Ballistics.GRAVITY / Math.max(speed, launchSpeed));
    }
}
