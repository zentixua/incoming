package ua.zentix.incoming.nuclear.model;

import org.junit.jupiter.api.Test;
import ua.zentix.incoming.strike.WeaponType;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Ignition with a ball of fuel (complaint 23.10: after the rocket, almost every block in the circle burned - roofs, sand, rails): threshold -
 *  fuel class according to table Glasstone, the impulse does not ignite thick wood.
 */
class PulseIgnitionTest {
    /** Thresholds - from table Glasstone &amp; Dolan in cal/cm²: foliage 6,0, newspaper 7,9, grass 9,1, clothing 24. */
    @Test
    void thresholdsFromGlasstone() {
        assertEquals(6.0, PulseIgnition.Fuel.LEAVES.threshold, 0.05);
        assertEquals(7.9, PulseIgnition.Fuel.PAPER.threshold, 0.05);
        assertEquals(9.1, PulseIgnition.Fuel.GRASS.threshold, 0.05);
        assertEquals(23.9, PulseIgnition.Fuel.FABRIC.threshold, 0.05);
        // on the threshold - half; half as low - almost nothing, twice as high - almost everything
        for (PulseIgnition.Fuel f : new PulseIgnition.Fuel[]{PulseIgnition.Fuel.LEAVES, PulseIgnition.Fuel.GRASS, PulseIgnition.Fuel.FABRIC}) {
            assertEquals(0.5, PulseIgnition.share(f.threshold, f), 1e-6, f.name());
            assertTrue(PulseIgnition.share(f.threshold / 2, f) < 0.02, f.name());
            assertTrue(PulseIgnition.share(f.threshold * 2, f) > 0.98, f.name());
        }
    }

    /** Thick wood and other flammable materials without class cannot be ignited by an impulse of any strength; Without an impulse, nothing burns. */
    @Test
    void solidFuelNeedsBurningFuelOnIt() {
        assertEquals(0, PulseIgnition.share(1e6, PulseIgnition.Fuel.SOLID));
        for (PulseIgnition.Fuel f : PulseIgnition.Fuel.values()) assertEquals(0, PulseIgnition.share(0, f), f.name());
    }

    /**
     * Shahed (30 kg of gasoline to impact, 22,5 kg in a ball - ~8,4 cal/cm²) sets fire to about a third to half of dry grass in a ball,
     *  almost all foliage and does not set fire to fabric; rocket (275 kg, ~17,5 cal/cm²) - almost all grass and foliage, fabric - less than
     *  once out of five.
     */
    @Test
    void droneAndMissileFireballs() {
        double drone = FuelFireball.exposure(FuelFireball.ballKg(WeaponType.DRONE.spec().fuelAtImpact()));
        double missile = FuelFireball.exposure(FuelFireball.ballKg(WeaponType.MISSILE.spec().fuelAtImpact()));
        assertEquals(8.4, drone, 0.2);
        assertEquals(17.5, missile, 0.3);
        double grass = PulseIgnition.share(drone, PulseIgnition.Fuel.GRASS);
        assertTrue(grass > 0.25 && grass < 0.5, "shahed, grass " + grass);
        assertTrue(PulseIgnition.share(drone, PulseIgnition.Fuel.LEAVES) > 0.8, "shahed, foliage");
        assertTrue(PulseIgnition.share(drone, PulseIgnition.Fuel.FABRIC) < 0.01, "shahed, fabric");
        assertTrue(PulseIgnition.share(missile, PulseIgnition.Fuel.GRASS) > 0.95, "rocket, grass");
        double fabric = PulseIgnition.share(missile, PulseIgnition.Fuel.FABRIC);
        assertTrue(fabric > 0.05 && fabric < 0.2, "rocket, fabric " + fabric);
    }

    @Test
    void normalDistribution() {
        assertEquals(0.5, PulseIgnition.normal(0), 1e-7);
        assertEquals(0.841345, PulseIgnition.normal(1), 1e-6);
        assertEquals(0.022750, PulseIgnition.normal(-2), 1e-6);
    }
}
