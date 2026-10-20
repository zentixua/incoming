package ua.zentix.incoming.client.fx.plume;

import org.junit.jupiter.api.Test;
import ua.zentix.incoming.client.ClientWeaponSpec;
import ua.zentix.incoming.client.ClientWeaponSpec.FarLook;
import ua.zentix.incoming.entity.FlightPhase;
import ua.zentix.incoming.nuclear.model.BlackBody;
import ua.zentix.incoming.strike.WeaponType;
import ua.zentix.incoming.util.StandardAtmosphere;

import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Факел из данных двигателя: один закон для всех, числа паспорта правдоподобны. */
class FlameModelTest {
    private static Motor motor(WeaponType w, FlightPhase ph) {
        return ClientWeaponSpec.of(w).airframe(false).far().stage(ph).motor();
    }

    private static Flame flame(WeaponType w, FlightPhase ph) {
        return FlameModel.of(motor(w, ph), 0);
    }

    @Test
    void passportMotorsAreSelfConsistent() {
        // давление в камеры у РДТТ — единицы МПа, тяга — из расхода и скорости
        for (WeaponType w : new WeaponType[]{WeaponType.DRONE, WeaponType.MISSILE, WeaponType.ROCKET, WeaponType.NUKE}) {
            Motor m = motor(w, FlightPhase.BOOST);
            double pc = m.chamberPressure();
            System.out.printf(Locale.ROOT, "%s: thrust %.1f kN, chamber %.1f MPa, exit %.0f kPa, exit r %.3f m, Te %.0f K%n", m.name(),
                    m.thrust() / 1000, pc / 1e6, m.exitPressure() / 1000, m.exitRadius(), m.exitTemperature());
            assertTrue(pc > 1e6 && pc < 15e6, m.name() + " камера " + pc);
            assertTrue(m.exitTemperature() > 1200 && m.exitTemperature() < m.temperature());
        }
        assertEquals(903_700, motor(WeaponType.NUKE, FlightPhase.BOOST).thrust(), 1, "тяга первой ступени — USAF");
    }

    @Test
    void flameColourIsTheBlackBodyAtItsTemperature() {
        for (WeaponType w : new WeaponType[]{WeaponType.DRONE, WeaponType.MISSILE, WeaponType.ROCKET, WeaponType.NUKE}) {
            Flame f = flame(w, FlightPhase.BOOST);
            assertNotNull(f, w + " горит");
            System.out.printf(Locale.ROOT, "%s: core %.1f, flame %.1f, length %.1f m, width %.2f, spread %.2f, T %.0f/%.0f K, eps %.2f, L %.2f/%.2f/%.4f, diamonds %s, light r %.2f b %.2f%n",
                    w, f.core(), f.flame(), f.length(), f.width(), f.spread(), f.temperature(), f.flameTemperature(), f.emissivity(), f.coreLuminance(),
                    f.flameLuminance(), f.tailLuminance(), f.diamonds(), f.lightRadius(), f.brightness());
            assertEquals(FlameModel.color(f.temperature()), f.coreColor());
            assertEquals(f.emissivity() * BlackBody.luminance(f.temperature()), f.coreLuminance(), 1e-9 * f.coreLuminance());
            assertEquals(FlameModel.color(f.flameTemperature()), f.flameColor());
            assertTrue(f.length() > f.flame() && f.flame() > f.core() && f.core() > 0);
            assertTrue(f.flameTemperature() > f.temperature(), "продукты догорают в воздухе");
            assertTrue(f.tailLuminance() < f.flameLuminance(), "за пламенем газ остывает");
        }
        // цвет чёрного тела: горячее — синее
        int warm = FlameModel.color(1500), hot = FlameModel.color(3000);
        assertTrue((hot & 255) > (warm & 255));
        assertEquals(255, warm >> 16 & 255, "красный — наибольший канал");
    }

    @Test
    void airBreathersAndElectricShowNoFlame() {
        assertNull(flame(WeaponType.DRONE, FlightPhase.CRUISE), "поршень шахеда не светит");
        assertNull(flame(WeaponType.MISSILE, FlightPhase.CRUISE), "ТРДД ракеты не светит");
        assertNull(flame(WeaponType.MISSILE, FlightPhase.TERMINAL), "и в пике");
        assertNull(ClientWeaponSpec.of(WeaponType.BUNKER).airframe(false).far().stage(FlightPhase.CRUISE).flame(64));
        for (FlightPhase ph : FlightPhase.values()) assertNull(ClientWeaponSpec.of(WeaponType.LOITER).airframe(false).far().stage(ph).motor());
    }

    @Test
    void diamondsOnlyWhenExitPressureDiffersFromAir() {
        Motor grad = motor(WeaponType.ROCKET, FlightPhase.BOOST), booster = motor(WeaponType.MISSILE, FlightPhase.BOOST);
        // сопло «Града» в калибре — недорасширено у земли
        assertTrue(grad.exitPressure() > FlameModel.DIAMONDS * StandardAtmosphere.P0);
        assertTrue(FlameModel.of(grad, 0).diamonds());
        // ускоритель ракеты рассчитан на землю: давления равны — алмазов нет
        assertEquals(StandardAtmosphere.P0, booster.exitPressure(), 1);
        assertFalse(FlameModel.of(booster, 0).diamonds());
        // тот же ускоритель высоко, где воздух в 1,3+ раза реже, — недорасширен
        double h = 3_000;
        assertTrue(StandardAtmosphere.pressure(h) * FlameModel.DIAMONDS < StandardAtmosphere.P0);
        assertTrue(FlameModel.of(booster, h).diamonds());
        // у порога: чуть меньше — нет, чуть больше — есть
        double edge = StandardAtmosphere.P0 / FlameModel.DIAMONDS * 1.01, below = StandardAtmosphere.P0 / FlameModel.DIAMONDS * 0.99;
        assertFalse(FlameModel.of(booster, altitude(edge)).diamonds());
        assertTrue(FlameModel.of(booster, altitude(below)).diamonds());
    }

    @Test
    void underExpandedJetIsWiderThanItsNozzle() {
        Motor grad = motor(WeaponType.ROCKET, FlightPhase.BOOST);
        assertTrue(FlameModel.of(grad, 0).width() > grad.exitRadius());
    }

    @Test
    void coreFollowsEldred() {
        // M_j → длина ядра 3,45·d·(1 + 0,38 M)²; у ускорителя на расчётной высоте струя — срез сопла
        Motor m = motor(WeaponType.MISSILE, FlightPhase.BOOST);
        Flame f = FlameModel.of(m, 0);
        double a = Math.sqrt(m.gamma() * Motor.R * m.exitTemperature() / m.molarMass());
        double mach = m.velocity() / a;
        assertEquals(m.exitRadius(), f.width(), 1e-3 * m.exitRadius());
        assertEquals(3.45 * 2 * m.exitRadius() * Math.pow(1 + 0.38 * mach, 2), f.core(), 0.01 * f.core());
        // пламя — до стехиометрической смеси на оси; хвост — до точки Дрейпера
        double fst = 1 / (1 + m.monoxide() * 2.46 + m.hydrogen() * 34.5);
        assertEquals(f.core() / fst, f.flame(), 1e-9 * f.flame());
        assertEquals(f.flame() * (f.flameTemperature() - StandardAtmosphere.T0) / (FlameModel.DRAPER - StandardAtmosphere.T0), f.length(), 1e-6 * f.length());
    }

    /** Высота стандартной атмосферы с давлением p (тропосфера). */
    private static double altitude(double p) {
        double lo = 0, hi = 11_000;
        for (int i = 0; i < 60; i++) {
            double mid = (lo + hi) / 2;
            if (StandardAtmosphere.pressure(mid) > p) lo = mid;
            else hi = mid;
        }
        return lo;
    }

    @Test
    void farLooksHaveNoFlameWithoutMotor() {
        for (WeaponType w : WeaponType.values()) {
            FarLook look = ClientWeaponSpec.of(w).airframe(false).far();
            assertNull(look.stage(FlightPhase.READY).motor());
        }
    }
}
