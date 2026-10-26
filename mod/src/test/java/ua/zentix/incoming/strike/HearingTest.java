package ua.zentix.incoming.strike;

import org.junit.jupiter.api.Test;
import ua.zentix.incoming.entity.FlightPhase;
import ua.zentix.incoming.sound.Noise;
import ua.zentix.incoming.sound.Propagation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * How far each weapon can be heard is derived from its sound spec and the path to the ear, and is not specified. Speeds and altitudes—
 *  of the current flight (spec {@link WeaponSpec.Airframe}), listener on the ground below flight level.
 */
class HearingTest {
    private static final Propagation.Weather NIGHT = Propagation.Weather.NIGHT, NOON = Propagation.Weather.NOON;

    private static double range(WeaponSpec.Airframe a, FlightPhase ph, double speed, double height, Propagation.Weather w) {
        return Hearing.range(a.noise(), ph, age(ph), speed, false, height, w);
    }

    /** Phase age for measurement: during acceleration - tick of the thrust plateau (the Shahed accelerator burns out by 38), otherwise - it’s been going on for a long time. */
    private static int age(FlightPhase ph) {
        return ph == FlightPhase.BOOST ? 10 : 200;
    }

    private static double cruise(WeaponSpec.Airframe a, Propagation.Weather w) {
        return range(a, FlightPhase.CRUISE, a.cruiseSpeed(), a.cruiseHeight(), w);
    }

    @Test
    void shahedIsHeardForKilometresAtNightAndCloserByDay() {
        WeaponSpec.Airframe drone = WeaponSpec.DRONE.airframe();
        double night = cruise(drone, NIGHT), day = cruise(drone, NOON);
        System.out.printf("shahed heard: night %.0f m, day %.0f m%n", night, day);
        // in calm weather the Shahed engine can be heard further than 10 km (computingonline.net 2024, eyewitnesses): at night - the order of this
        assertTrue(night > 8000 && night < 14_000, "at night " + night);
        assertTrue(day < night * 0.8, "during the day " + day + ", at night " + night);
        // the sound overtakes Shahed: from the edge of audibility at night it can be heard long before it arrives
        double lead = night * (1 / drone.cruiseSpeed() - 1 / (Noise.SOUND / 20)) / 20;
        assertTrue(lead > 30, "for " + lead + " s before arrival");
    }

    /**
     * The source derived from the measurement, at the site of the measurement itself, sounds as measured: reference ultra-light (its own power,
     *  with a muffler, nominal speed) at 150 m above the meadow - 70 dBA. The previous output (correction “+3 dB at the microphone” with ground
     * ISO 9613-2 in distribution) gave 65 dBA there, and Shahed in the game was 4,9 dB quieter than his data (F4).
     */
    @Test
    void shahedSourceReproducesItsReferenceMeasurement() {
        Noise.Propeller d = (Noise.Propeller) WeaponSpec.DRONE.airframe().noise().engine();
        double lw = d.refA() - WeaponSpec.OPEN_EXHAUST - d.spectrum().aWeighting();
        double at = Propagation.level(lw, d.spectrum(), new Propagation.Path(WeaponSpec.UL_REF_M, WeaponSpec.UL_REF_M, WeaponSpec.UL_REF_MIC,
                Propagation.MEADOW, NOON), null);
        assertEquals(WeaponSpec.UL_REF_DBA, at, 0.05, "support ultra-light at the measurement site");
        // shahed in the game above the viewer at 54 m (look gate flyover scene): according to class ~92 dBA on a real cruise
        Noise noise = WeaponSpec.DRONE.airframe().noise();
        double cruise = noise.power(Noise.Part.ENGINE, FlightPhase.CRUISE, 200, d.speed() / 20, false);
        double l54 = Propagation.level(cruise, d.spectrum(), new Propagation.Path(54, 52, Propagation.EAR, Propagation.MEADOW, NOON), null);
        assertEquals(70 + WeaponSpec.OPEN_EXHAUST + 10 * Math.log10(WeaponSpec.DRONE_PISTON.power() / WeaponSpec.UL_REF_W) + 20 * Math.log10(150.0 / 54), l54, 1.5,
                "in 54 m - according to class with spread from 150 m");
    }

    @Test
    void lancetIsGoneBeyondAFewHundredMetres() {
        WeaponSpec.Airframe loiter = WeaponSpec.LOITER.airframe();
        double night = range(loiter, FlightPhase.LOITER, loiter.cruiseSpeed(), 45, NIGHT);
        assertTrue(night > 150 && night < 1000, "at night " + night);
        // at the peak the air and the propeller are louder
        assertTrue(range(loiter, FlightPhase.TERMINAL, loiter.diveSpeed(), 20, NIGHT) > night);
    }

    @Test
    void rangesKeepTheOrderOfTheOldCutoffsWhereTheyWereRight() {
        // previous cuts: “Lancet” 200 < Shahed motor 300; accelerator 600 > motor; launch rail exit of the MLRS projectile 900 > its howl 500;
        // ICBM stage is the farthest. The hum of a rocket turbine (1500) is no longer further than Shahed: a two-stroke engine without a muffler can be heard
        //  further than 10 km, and a low-level turbofan rocket engine can be heard several kilometers away
        double lancet = range(WeaponSpec.LOITER.airframe(), FlightPhase.LOITER, WeaponSpec.LOITER.airframe().cruiseSpeed(), 45, NIGHT);
        double shahed = cruise(WeaponSpec.DRONE.airframe(), NIGHT);
        double missile = cruise(WeaponSpec.MISSILE.airframe(), NIGHT);
        double bomber = cruise(WeaponSpec.BUNKER.airframe(), NIGHT);
        double booster = range(WeaponSpec.DRONE.airframe(), FlightPhase.BOOST, 1, 5, NIGHT);
        double gradLaunch = range(WeaponSpec.ROCKET.airframe(), FlightPhase.BOOST, 2, 5, NIGHT);
        double gradAir = range(WeaponSpec.ROCKET.airframe(), FlightPhase.CRUISE, 4, 100, NIGHT);
        double icbm = range(WeaponSpec.NUKE.airframe(), FlightPhase.BOOST, 5, 50, NIGHT);
        assertTrue(lancet < shahed && lancet < missile, lancet + " < " + shahed + ", " + missile);
        assertTrue(shahed < bomber, "B-2 " + bomber);
        assertTrue(booster > shahed, "accelerator " + booster);
        assertTrue(gradLaunch > gradAir, "launch rail exit " + gradLaunch + ", howl " + gradAir);
        // rocket engines can be heard further than the client remembers the path (Hearing.RANGE): their order is by margin above the background
        //  at the same distance
        double boosterAt = margin(WeaponSpec.DRONE.airframe(), FlightPhase.BOOST, 1), gradAt = margin(WeaponSpec.ROCKET.airframe(), FlightPhase.BOOST, 2);
        double icbmAt = margin(WeaponSpec.NUKE.airframe(), FlightPhase.BOOST, 5), missileAt = margin(WeaponSpec.MISSILE.airframe(), FlightPhase.CRUISE, 4);
        assertTrue(icbmAt > gradAt && icbmAt > boosterAt && icbmAt > missileAt, "ICBM " + icbmAt + ", \"grad\" " + gradAt + ", accelerator " + boosterAt);
        assertTrue(icbm >= gradLaunch && icbm >= booster && icbm >= missile);
        // range is where the sound drowns in the background, and not the limit of the path memory: for motors on the route it is less than the limit
        for (double r : new double[] {lancet, shahed, gradAir}) assertTrue(r < Hearing.RANGE, "range " + r);
    }

    /** How much louder is the noise than the background at night in 15 km, dB. */
    private static double margin(WeaponSpec.Airframe a, FlightPhase ph, double speed) {
        return a.noise().margin(ph, age(ph), speed, false, Propagation.Path.over(15_000, 50, NIGHT));
    }

    @Test
    void silentPartsAreSilent() {
        // the rocket turbine does not work on the launcher loitering without an accelerator
        Noise missile = WeaponSpec.MISSILE.airframe().noise();
        assertTrue(missile.power(Noise.Part.ENGINE, FlightPhase.BOOST, 10, 2, false) == Noise.SILENT);
        assertTrue(missile.power(Noise.Part.MOTOR, FlightPhase.CRUISE, 10, 4, false) == Noise.SILENT);
        assertTrue(WeaponSpec.LOITER.airframe().noise().power(Noise.Part.MOTOR, FlightPhase.BOOST, 10, 1, false) == Noise.SILENT);
        // the bomb is still drilling - only drilling
        Noise bomb = WeaponSpec.BUNKER.payload().noise();
        assertTrue(bomb.power(Noise.Part.BODY, FlightPhase.DRILL, 10, 1, true) == Noise.SILENT);
        assertTrue(bomb.power(Noise.Part.DRILL, FlightPhase.DRILL, 10, 1, true) > Noise.SILENT);
    }
}
