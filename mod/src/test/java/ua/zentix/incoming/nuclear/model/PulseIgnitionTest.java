package ua.zentix.incoming.nuclear.model;

import org.junit.jupiter.api.Test;
import ua.zentix.incoming.strike.WeaponType;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Поджиг шаром топлива (жалоба 23.10: после ракеты горел почти каждый блок в круге — крыши, песок, рельсы): порог —
 * у класса горючего по таблице Glasstone, толстое дерево импульс не поджигает.
 */
class PulseIgnitionTest {
    /** Пороги — из таблицы Glasstone &amp; Dolan в кал/см²: листва 6,0, газета 7,9, трава 9,1, одежда 24. */
    @Test
    void thresholdsFromGlasstone() {
        assertEquals(6.0, PulseIgnition.Fuel.LEAVES.threshold, 0.05);
        assertEquals(7.9, PulseIgnition.Fuel.PAPER.threshold, 0.05);
        assertEquals(9.1, PulseIgnition.Fuel.GRASS.threshold, 0.05);
        assertEquals(23.9, PulseIgnition.Fuel.FABRIC.threshold, 0.05);
        // на пороге — половина; вдвое ниже — почти ничего, вдвое выше — почти всё
        for (PulseIgnition.Fuel f : new PulseIgnition.Fuel[]{PulseIgnition.Fuel.LEAVES, PulseIgnition.Fuel.GRASS, PulseIgnition.Fuel.FABRIC}) {
            assertEquals(0.5, PulseIgnition.share(f.threshold, f), 1e-6, f.name());
            assertTrue(PulseIgnition.share(f.threshold / 2, f) < 0.02, f.name());
            assertTrue(PulseIgnition.share(f.threshold * 2, f) > 0.98, f.name());
        }
    }

    /** Толстое дерево и прочее горючее без класса импульс любой силы не поджигает; без импульса не горит ничего. */
    @Test
    void solidFuelNeedsBurningFuelOnIt() {
        assertEquals(0, PulseIgnition.share(1e6, PulseIgnition.Fuel.SOLID));
        for (PulseIgnition.Fuel f : PulseIgnition.Fuel.values()) assertEquals(0, PulseIgnition.share(0, f), f.name());
    }

    /**
     * Шахед (30 кг бензина к удару, 22,5 кг в шаре — ~8,4 кал/см²) поджигает в шаре около трети-половины сухой травы,
     * почти всю листву и не поджигает ткань; ракета (275 кг, ~17,5 кал/см²) — почти всю траву и листву, ткань — реже
     * одного раза из пяти.
     */
    @Test
    void droneAndMissileFireballs() {
        double drone = FuelFireball.exposure(FuelFireball.ballKg(WeaponType.DRONE.spec().fuelAtImpact()));
        double missile = FuelFireball.exposure(FuelFireball.ballKg(WeaponType.MISSILE.spec().fuelAtImpact()));
        assertEquals(8.4, drone, 0.2);
        assertEquals(17.5, missile, 0.3);
        double grass = PulseIgnition.share(drone, PulseIgnition.Fuel.GRASS);
        assertTrue(grass > 0.25 && grass < 0.5, "шахед, трава " + grass);
        assertTrue(PulseIgnition.share(drone, PulseIgnition.Fuel.LEAVES) > 0.8, "шахед, листва");
        assertTrue(PulseIgnition.share(drone, PulseIgnition.Fuel.FABRIC) < 0.01, "шахед, ткань");
        assertTrue(PulseIgnition.share(missile, PulseIgnition.Fuel.GRASS) > 0.95, "ракета, трава");
        double fabric = PulseIgnition.share(missile, PulseIgnition.Fuel.FABRIC);
        assertTrue(fabric > 0.05 && fabric < 0.2, "ракета, ткань " + fabric);
    }

    @Test
    void normalDistribution() {
        assertEquals(0.5, PulseIgnition.normal(0), 1e-7);
        assertEquals(0.841345, PulseIgnition.normal(1), 1e-6);
        assertEquals(0.022750, PulseIgnition.normal(-2), 1e-6);
    }
}
