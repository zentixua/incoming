package ua.zentix.incoming.strike;

import org.junit.jupiter.api.Test;
import ua.zentix.incoming.entity.FlightPhase;
import ua.zentix.incoming.sound.Noise;
import ua.zentix.incoming.sound.Propagation;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Докуда слышно каждое оружие — выводится из его паспорта звука и пути до уха, а не задаётся. Скорости и высоты —
 * нынешнего полёта (паспорт {@link WeaponSpec.Airframe}), слушатель на земле под эшелоном.
 */
class HearingTest {
    private static final Propagation.Weather NIGHT = Propagation.Weather.NIGHT, NOON = Propagation.Weather.NOON;

    private static double range(WeaponSpec.Airframe a, FlightPhase ph, double speed, double height, Propagation.Weather w) {
        return Hearing.range(a.noise(), ph, 200, speed, false, height, w);
    }

    private static double cruise(WeaponSpec.Airframe a, Propagation.Weather w) {
        return range(a, FlightPhase.CRUISE, a.cruiseSpeed(), a.cruiseHeight(), w);
    }

    @Test
    void shahedIsHeardForKilometresAtNightAndCloserByDay() {
        WeaponSpec.Airframe drone = WeaponSpec.DRONE.airframe();
        double night = cruise(drone, NIGHT), day = cruise(drone, NOON);
        assertTrue(night > 2000, "ночью " + night);
        assertTrue(day < night * 0.8, "днём " + day + ", ночью " + night);
        // звук обгоняет шахед: с края слышимости ночью его слышно задолго до прихода
        double lead = night * (1 / drone.cruiseSpeed() - 1 / (Noise.SOUND / 20)) / 20;
        assertTrue(lead > 30, "за " + lead + " с до прихода");
    }

    @Test
    void lancetIsGoneBeyondAFewHundredMetres() {
        WeaponSpec.Airframe loiter = WeaponSpec.LOITER.airframe();
        double night = range(loiter, FlightPhase.LOITER, loiter.cruiseSpeed(), 45, NIGHT);
        assertTrue(night > 150 && night < 1000, "ночью " + night);
        // в пике воздух и винт громче
        assertTrue(range(loiter, FlightPhase.TERMINAL, loiter.diveSpeed(), 20, NIGHT) > night);
    }

    @Test
    void rangesKeepTheOrderOfTheOldCutoffsWhereTheyWereRight() {
        // прежние срезы: «Ланцет» 200 < мотор шахеда 300 < гул турбины 1500; ускоритель 600 > мотор; сход снаряда РСЗО 900
        // > его вой 500; ступень МБР — дальше всех
        double lancet = range(WeaponSpec.LOITER.airframe(), FlightPhase.LOITER, WeaponSpec.LOITER.airframe().cruiseSpeed(), 45, NIGHT);
        double shahed = cruise(WeaponSpec.DRONE.airframe(), NIGHT);
        double missile = cruise(WeaponSpec.MISSILE.airframe(), NIGHT);
        double bomber = cruise(WeaponSpec.BUNKER.airframe(), NIGHT);
        double booster = range(WeaponSpec.DRONE.airframe(), FlightPhase.BOOST, 1, 5, NIGHT);
        double gradLaunch = range(WeaponSpec.ROCKET.airframe(), FlightPhase.BOOST, 2, 5, NIGHT);
        double gradAir = range(WeaponSpec.ROCKET.airframe(), FlightPhase.CRUISE, 4, 100, NIGHT);
        double icbm = range(WeaponSpec.NUKE.airframe(), FlightPhase.BOOST, 5, 50, NIGHT);
        assertTrue(lancet < shahed && shahed < missile, lancet + " < " + shahed + " < " + missile);
        assertTrue(shahed < bomber, "B-2 " + bomber);
        assertTrue(booster > shahed, "ускоритель " + booster);
        assertTrue(gradLaunch > gradAir, "сход " + gradLaunch + ", вой " + gradAir);
        // ракетные двигатели слышно дальше, чем клиент помнит путь (Hearing.RANGE): их порядок — по запасу над фоном
        // на одном расстоянии
        double boosterAt = margin(WeaponSpec.DRONE.airframe(), FlightPhase.BOOST, 1), gradAt = margin(WeaponSpec.ROCKET.airframe(), FlightPhase.BOOST, 2);
        double icbmAt = margin(WeaponSpec.NUKE.airframe(), FlightPhase.BOOST, 5), missileAt = margin(WeaponSpec.MISSILE.airframe(), FlightPhase.CRUISE, 4);
        assertTrue(icbmAt > gradAt && icbmAt > boosterAt && icbmAt > missileAt, "МБР " + icbmAt + ", «Град» " + gradAt + ", ускоритель " + boosterAt);
        assertTrue(icbm >= gradLaunch && icbm >= booster && icbm >= missile);
        // дальность — там, где звук тонет в фоне, а не предел памяти пути: у моторов на маршруте она меньше предела
        for (double r : new double[] {lancet, shahed, gradAir}) assertTrue(r < Hearing.RANGE, "дальность " + r);
    }

    /** Насколько шум громче фона ночью в 15 км, дБ. */
    private static double margin(WeaponSpec.Airframe a, FlightPhase ph, double speed) {
        return a.noise().margin(ph, 200, speed, false, Propagation.Path.over(15_000, 50, NIGHT));
    }

    @Test
    void silentPartsAreSilent() {
        // турбина ракеты не работает на пусковой, барражирующий без ускорителя
        Noise missile = WeaponSpec.MISSILE.airframe().noise();
        assertTrue(missile.power(Noise.Part.ENGINE, FlightPhase.BOOST, 10, 2, false) == Noise.SILENT);
        assertTrue(missile.power(Noise.Part.MOTOR, FlightPhase.CRUISE, 10, 4, false) == Noise.SILENT);
        assertTrue(WeaponSpec.LOITER.airframe().noise().power(Noise.Part.MOTOR, FlightPhase.BOOST, 10, 1, false) == Noise.SILENT);
        // бомба, пока бурит, — только бурение
        Noise bomb = WeaponSpec.BUNKER.payload().noise();
        assertTrue(bomb.power(Noise.Part.BODY, FlightPhase.DRILL, 10, 1, true) == Noise.SILENT);
        assertTrue(bomb.power(Noise.Part.DRILL, FlightPhase.DRILL, 10, 1, true) > Noise.SILENT);
    }
}
