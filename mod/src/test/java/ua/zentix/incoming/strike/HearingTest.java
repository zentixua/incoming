package ua.zentix.incoming.strike;

import org.junit.jupiter.api.Test;
import ua.zentix.incoming.entity.FlightPhase;
import ua.zentix.incoming.sound.Noise;
import ua.zentix.incoming.sound.Propagation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Докуда слышно каждое оружие — выводится из его паспорта звука и пути до уха, а не задаётся. Скорости и высоты —
 * нынешнего полёта (паспорт {@link WeaponSpec.Airframe}), слушатель на земле под эшелоном.
 */
class HearingTest {
    private static final Propagation.Weather NIGHT = Propagation.Weather.NIGHT, NOON = Propagation.Weather.NOON;

    private static double range(WeaponSpec.Airframe a, FlightPhase ph, double speed, double height, Propagation.Weather w) {
        return Hearing.range(a.noise(), ph, age(ph), speed, false, height, w);
    }

    /** Возраст фазы для замера: на разгоне — тик полки тяги (ускоритель шахеда догорает к 38-му), иначе — давно идёт. */
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
        // в тихую погоду мотор шахеда слышно дальше 10 км (computingonline.net 2024, очевидцы): ночью — порядок этого
        assertTrue(night > 8000 && night < 14_000, "ночью " + night);
        assertTrue(day < night * 0.8, "днём " + day + ", ночью " + night);
        // звук обгоняет шахед: с края слышимости ночью его слышно задолго до прихода
        double lead = night * (1 / drone.cruiseSpeed() - 1 / (Noise.SOUND / 20)) / 20;
        assertTrue(lead > 30, "за " + lead + " с до прихода");
    }

    /**
     * Источник, выведенный из замера, на месте самого замера звучит, как замерено: опорный сверхлёгкий (своя мощность,
     * с глушителем, номинал оборотов) в 150 м над лугом — 70 дБА. Прежний вывод (поправка «+3 дБ у микрофона» при земле
     * ISO 9613-2 в распространении) давал там 65 дБА, и шахед в игре был на 4,9 дБ тише своих данных (F4).
     */
    @Test
    void shahedSourceReproducesItsReferenceMeasurement() {
        Noise.Propeller d = (Noise.Propeller) WeaponSpec.DRONE.airframe().noise().engine();
        double lw = d.refA() - WeaponSpec.OPEN_EXHAUST - d.spectrum().aWeighting();
        double at = Propagation.level(lw, d.spectrum(), new Propagation.Path(WeaponSpec.UL_REF_M, WeaponSpec.UL_REF_M, WeaponSpec.UL_REF_MIC,
                Propagation.MEADOW, NOON), null);
        assertEquals(WeaponSpec.UL_REF_DBA, at, 0.05, "опорный сверхлёгкий на месте замера");
        // шахед в игре над зрителем в 54 м (сцена пролёта шлагбаума): по классу ~92 дБА на настоящем крейсере
        Noise noise = WeaponSpec.DRONE.airframe().noise();
        double cruise = noise.power(Noise.Part.ENGINE, FlightPhase.CRUISE, 200, d.speed() / 20, false);
        double l54 = Propagation.level(cruise, d.spectrum(), new Propagation.Path(54, 52, Propagation.EAR, Propagation.MEADOW, NOON), null);
        assertEquals(70 + WeaponSpec.OPEN_EXHAUST + 10 * Math.log10(WeaponSpec.DRONE_PISTON.power() / WeaponSpec.UL_REF_W) + 20 * Math.log10(150.0 / 54), l54, 1.5,
                "в 54 м — по классу с разлётом от 150 м");
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
        // прежние срезы: «Ланцет» 200 < мотор шахеда 300; ускоритель 600 > мотор; сход снаряда РСЗО 900 > его вой 500;
        // ступень МБР — дальше всех. Гул турбины ракеты (1500) — уже не дальше шахеда: двухтакт без глушителя слышно
        // дальше 10 км, а ТРДД ракеты на бреющем — за несколько километров
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
        return a.noise().margin(ph, age(ph), speed, false, Propagation.Path.over(15_000, 50, NIGHT));
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
